package cx.aphex.energysign.ai

import android.content.Context
import android.content.ContextWrapper
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integrated tests driving the real [AIQueryDesk], [AIReplyRunner], [AIReplyService],
 * [AnthropicReplyGenerator], [OpenAIReplyGenerator], and [MessageManager] against local HTTP
 * servers with a test-controlled clock.
 */
class AIHourlyBudgetIntegrationTest {

    private class Query(val text: String)

    private fun okAnthropicBody(text: String = "Hydrate."): String =
        FakeMessagesApi.message("""[{"type":"text","text":"$text"}]""")

    private fun okOpenAiBody(text: String = "Hydrate."): String =
        """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"$text"},"finish_reason":null}]}""" + "\n\n" +
            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
            "data: [DONE]\n\n"

    private fun maxPostsInAnyRollingHour(timestampsMs: List<Long>): Int =
        timestampsMs.maxOfOrNull { start ->
            timestampsMs.count { it >= start && it < start + 3_600_000L }
        } ?: 0

    @Test
    fun `replays on 503 Retry-After 0 cannot push Anthropic wire POSTs past 45 in an hour`() {
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()
        val toggle = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        postTimes.add(clockMs.get())
                    }
                    // Every odd POST gets 503 Retry-After: 0, every even POST gets 200 OK.
                    return if (toggle.incrementAndGet() % 2 == 1) {
                        MockResponse()
                            .setResponseCode(503)
                            .setHeader("content-type", "application/json")
                            .setHeader("retry-after", "0")
                            .setBody(FakeMessagesApi.error("api_error", "busy"))
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("content-type", "application/json")
                            .setBody(okAnthropicBody())
                    }
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val generator = AnthropicReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    baseUrl = baseUrl,
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted -> service.replyTo(text, deadline, stillWanted) },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)

                val desk = AIQueryDesk<Query>(
                    start = { q ->
                        runner.ask(q.text, stillWanted = { true }) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                )

                repeat(30) { idx ->
                    val q = Query("ravegpt q$idx")
                    listed.add(q)
                    latch = CountDownLatch(1)
                    if (desk.shown(q, clockMs.get())) {
                        assertTrue(latch.await(15, TimeUnit.SECONDS), "call $idx did not finish")
                        val (finishedQ, res) = pendingResult!!
                        desk.finished(finishedQ, res, clockMs.get())
                    }
                    clockMs.addAndGet(1_000L)
                }

                assertTrue(
                    postTimes.size <= 45,
                    "Expected at most 45 wire POSTs in an hour, got ${postTimes.size} (max in rolling hour: ${maxPostsInAnyRollingHour(postTimes)})"
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `preparation time before the physical send cannot push wire POSTs past 45 in a rolling hour`() {
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()
        val firstCallPrepApplied = AtomicBoolean(false)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        postTimes.add(clockMs.get())
                    }
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("content-type", "application/json")
                        .setBody(okAnthropicBody())
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val generator = AnthropicReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    effort = {
                        // First query spends 5 s preparing before the POST physically leaves.
                        if (firstCallPrepApplied.compareAndSet(false, true)) {
                            clockMs.addAndGet(5_000L)
                        }
                        AIEffort.HIGH
                    },
                    baseUrl = baseUrl,
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted -> service.replyTo(text, deadline, stillWanted) },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)

                val desk = AIQueryDesk<Query>(
                    start = { q ->
                        runner.ask(q.text, stillWanted = { true }) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                )

                val t0 = clockMs.get()
                // Send 45 queries: first handed over at t0, physically sent at t0 + 5_000.
                repeat(45) { idx ->
                    val q = Query("ravegpt prep-$idx")
                    listed.add(q)
                    latch = CountDownLatch(1)
                    assertTrue(desk.shown(q, clockMs.get()), "query $idx should be admitted")
                    assertTrue(latch.await(15, TimeUnit.SECONDS), "call $idx did not finish")
                    val (finishedQ, res) = pendingResult!!
                    desk.finished(finishedQ, res, clockMs.get())
                    clockMs.addAndGet(1_000L)
                }

                // Move to t0 + 3_600_000: 1 hour after handover, but only 3,595 s after POST #1!
                clockMs.set(t0 + 3_600_000L)
                val q46 = Query("ravegpt q46-too-early")
                listed.add(q46)
                latch = CountDownLatch(1)
                if (desk.shown(q46, clockMs.get())) {
                    assertTrue(latch.await(15, TimeUnit.SECONDS))
                    val (finishedQ, res) = pendingResult!!
                    desk.finished(finishedQ, res, clockMs.get())
                }

                val maxInHour = maxPostsInAnyRollingHour(postTimes)
                assertTrue(
                    maxInHour <= 45,
                    "Expected <= 45 wire POSTs in any rolling hour, got $maxInHour (timestamps=${postTimes.take(2)}..${postTimes.takeLast(2)})"
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `withdrawn and too-late calls return their budget place and do not consume the query's three attempts`() {
        val clockMs = AtomicLong(1_000_000L)
        val postCount = AtomicInteger(0)

        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("Still alive."))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val generator = AnthropicReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    baseUrl = api.baseUrl,
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted -> service.replyTo(text, deadline, stillWanted) },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)
                var allowSend = false
                var expireDuringPrep = false

                val desk = AIQueryDesk<Query>(
                    start = { q ->
                        runner.ask(q.text, stillWanted = {
                            if (expireDuringPrep) {
                                // Consume 90 s of the 100 s query budget -> only 10 s (< 15 s) left.
                                clockMs.addAndGet(90_000L)
                                true
                            } else {
                                allowSend
                            }
                        }) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                    limits = AIQueryDesk.Limits(callsPerHour = 2),
                )

                val q = Query("ravegpt resilient")
                listed.add(q)

                // Three WITHDRAWN calls (e.g., owner.ask 2 s timeout): must neither retire nor burn the 2-call budget.
                repeat(3) { attempt ->
                    latch = CountDownLatch(1)
                    assertTrue(desk.shown(q, clockMs.get()), "withdrawn try $attempt should be accepted (budget must be refunded)")
                    assertTrue(latch.await(15, TimeUnit.SECONDS))
                    val (finishedQ, res) = pendingResult!!
                    val outcome = desk.finished(finishedQ, res, clockMs.get())
                    assertIs<AIQueryDesk.Outcome.Failed>(outcome, "WITHDRAWN try $attempt must not retire the query: $outcome")
                }

                // Three TOO_LATE_TO_SEND calls: must also neither retire nor burn the 2-call budget.
                expireDuringPrep = true
                repeat(3) { attempt ->
                    latch = CountDownLatch(1)
                    assertTrue(desk.shown(q, clockMs.get()), "too-late try $attempt should be accepted (budget must be refunded)")
                    assertTrue(latch.await(15, TimeUnit.SECONDS))
                    val (finishedQ, res) = pendingResult!!
                    val outcome = desk.finished(finishedQ, res, clockMs.get())
                    assertIs<AIQueryDesk.Outcome.Failed>(outcome, "TOO_LATE_TO_SEND try $attempt must not retire the query: $outcome")
                }

                // Now let the query actually send: still has all 3 attempts and budget still has room!
                expireDuringPrep = false
                allowSend = true
                latch = CountDownLatch(1)
                assertTrue(desk.shown(q, clockMs.get()), "budget must still have room after unsent calls")
                assertTrue(latch.await(15, TimeUnit.SECONDS))
                val (finishedQ, res) = pendingResult!!
                val outcome = desk.finished(finishedQ, res, clockMs.get())
                assertIs<AIQueryDesk.Outcome.Reply>(outcome, "query must still succeed after unsent tries: $outcome")
                postCount.set(api.received.size)
                assertEquals(1, postCount.get(), "only the single real call should have hit the wire")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `15-second check is re-checked before a 503 Retry-After 0 replay leaves on the wire`() {
        val clockMs = AtomicLong(1_000_000L)
        val wirePosts = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = wirePosts.incrementAndGet()
                    if (n == 1) {
                        // First POST entered with 16 s left; before 503 arrives, 5 s elapse -> 11 s left (< 15 s).
                        clockMs.addAndGet(5_000L)
                        return MockResponse()
                            .setResponseCode(503)
                            .setHeader("content-type", "application/json")
                            .setHeader("retry-after", "0")
                            .setBody(FakeMessagesApi.error("api_error", "busy"))
                    }
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("content-type", "application/json")
                        .setBody(okAnthropicBody())
                }
            }
            server.start()
            val generator = AnthropicReplyGenerator(
                apiKey = "test-key-not-real",
                log = {},
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val deadline = Deadline.after(Duration.ofSeconds(16)) { clockMs.get() * 1_000_000L }

            val result = runBlocking {
                generator.generateReply(AIReplyRequest("sys", "user", deadline = deadline))
            }

            assertEquals(1, wirePosts.get(), "replay with only 11 s (< 15 s) left must NOT go on the wire")
            assertIs<AIReplyResult.NoReply>(result)
            assertEquals(NoReplyReason.SERVER_ERROR, result.reason)
        }
    }

    @Test
    fun `replays on 503 Retry-After 0 cannot push OpenAI wire POSTs past 45 in an hour`() {
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()
        val toggle = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        postTimes.add(clockMs.get())
                    }
                    return if (toggle.incrementAndGet() % 2 == 1) {
                        MockResponse()
                            .setResponseCode(503)
                            .setHeader("content-type", "application/json")
                            .setHeader("retry-after", "0")
                            .setBody("""{"error":{"message":"busy","type":"server_error"}}""")
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("content-type", "text/event-stream")
                            .setBody(okOpenAiBody())
                    }
                }
            }
            server.start()
            val baseUrl = "${server.url("/").toString().trimEnd('/')}/v1/"

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val generator = OpenAIReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    baseUrl = baseUrl,
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted -> service.replyTo(text, deadline, stillWanted) },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)

                val desk = AIQueryDesk<Query>(
                    start = { q ->
                        runner.ask(q.text, stillWanted = { true }) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                )

                repeat(30) { idx ->
                    val q = Query("ravegpt openai-$idx")
                    listed.add(q)
                    latch = CountDownLatch(1)
                    if (desk.shown(q, clockMs.get())) {
                        assertTrue(latch.await(15, TimeUnit.SECONDS), "openai call $idx did not finish")
                        val (finishedQ, res) = pendingResult!!
                        desk.finished(finishedQ, res, clockMs.get())
                    }
                    clockMs.addAndGet(1_000L)
                }

                assertTrue(
                    postTimes.size <= 45,
                    "Expected at most 45 OpenAI wire POSTs in an hour, got ${postTimes.size}"
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `MessageManager desk reads fresh clock on dequeue so a query expired during finished is not sent`() {
        val dir: File = Files.createTempDirectory("es6wb-dequeue-clock").toFile()
        val context: Context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val repo = MessageRepository(saver = Executor { })
        val owner = OwnerQueue()
        val asked = CopyOnWriteArrayList<Pair<String, (AIReplyResult) -> Unit>>()
        val fakeReplies = object : AIReplySource {
            override fun warmUp(onDone: () -> Unit): Boolean = false
            override fun ask(text: String, stillWanted: () -> Boolean, onResult: (AIReplyResult) -> Unit) {
                asked.add(text to onResult)
            }
        }

        // Clock starts at 1_000_000 (query-a shown; notice expires at 1_030_000 while query-a stays out).
        // At 1_030_000 query-b is shown and waits in line until 1_060_000.
        // During replyArrived for query-a, first clock read returns 1_059_999 (1 ms before b expires)
        // and any subsequent read inside sendNext returns 1_060_001 (1 ms AFTER b expired).
        var phase = "init"
        var finishedReads = 0
        var nowMs = 1_000_000L
        val clock: () -> Long = {
            if (phase == "finishing") {
                finishedReads++
                if (finishedReads == 1) 1_059_999L else 1_060_001L
            } else {
                nowMs
            }
        }

        stopKoin()
        startKoin {
            modules(module {
                single { repo }
                single { owner }
                single<AIReplySource> { fakeReplies }
            })
        }
        try {
            val manager = MessageManager(context, repo, owner, nowMs = clock)
            while (owner.drain { throw it } > 0) Unit

            // Add query-a and show it at t = 1_000_000 (call goes out, notice expires at 1_030_000).
            manager.processNewUserMessage("ravegpt query-a")
            while (owner.drain { throw it } > 0) Unit
            val queryA = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
            repeat(20) {
                if (manager.getNextMessage() === queryA) return@repeat
            }
            assertEquals(1, asked.size, "query-a should be out")

            // Expire query-a's thinking notice (call stays out), then add and show query-b at t = 1_030_000.
            nowMs = 1_030_000L
            manager.processNewUserMessage("ravegpt query-b")
            while (owner.drain { throw it } > 0) Unit
            val queryB = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
            var showedB = false
            repeat(20) {
                if (manager.getNextMessage() === queryB) {
                    showedB = true
                    return@repeat
                }
            }
            assertTrue(showedB, "query-b should have been shown and queued")

            // Complete query-a: replyArrived enters at 1_059_999, clock crosses 1_060_000 before sendNext.
            phase = "finishing"
            asked.first().second(AIReplyResult.Reply("Answer A."))
            while (owner.drain { throw it } > 0) Unit

            assertEquals(
                1,
                asked.size,
                "query-b expired at 1_060_000 and must NOT be promoted when clock reads 1_060_001 at dequeue"
            )
        } finally {
            stopKoin()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `MessageManager production wiring shares one budget between desk and real Anthropic generator`() {
        val dir: File = Files.createTempDirectory("es6wb-mm-wiring").toFile()
        val context: Context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()
        val toggle = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        postTimes.add(clockMs.get())
                    }
                    return if (toggle.incrementAndGet() % 2 == 1) {
                        MockResponse()
                            .setResponseCode(503)
                            .setHeader("content-type", "application/json")
                            .setHeader("retry-after", "0")
                            .setBody(FakeMessagesApi.error("api_error", "busy"))
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("content-type", "application/json")
                            .setBody(okAnthropicBody())
                    }
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = MessageRepository(saver = Executor { })
            val owner = OwnerQueue()
            val completed = AtomicInteger(0)

            val generator = AnthropicReplyGenerator(
                apiKey = "test-key-not-real",
                log = {},
                baseUrl = baseUrl,
            )
            val service = AIReplyService(
                generator = generator,
                log = {},
                now = { Instant.ofEpochMilli(clockMs.get()) },
            )
            val runner = AIReplyRunner(
                reply = { text, deadline, stillWanted -> service.replyTo(text, deadline, stillWanted) },
                nanoTime = { clockMs.get() * 1_000_000L },
                scope = scope,
            )
            val countingSource = object : AIReplySource {
                override fun warmUp(onDone: () -> Unit): Boolean = false
                override fun ask(text: String, stillWanted: () -> Boolean, onResult: (AIReplyResult) -> Unit) {
                    runner.ask(text, stillWanted) { result ->
                        onResult(result)
                        completed.incrementAndGet()
                    }
                }
            }

            stopKoin()
            startKoin {
                modules(module {
                    single { repo }
                    single { owner }
                    single<AIReplySource> { countingSource }
                })
            }
            try {
                val manager = MessageManager(context, repo, owner, nowMs = { clockMs.get() })
                while (owner.drain { throw it } > 0) Unit

                repeat(26) { idx ->
                    val beforeCompleted = completed.get()
                    manager.processNewUserMessage("ravegpt mm-$idx")
                    while (owner.drain { throw it } > 0) Unit
                    val query = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())

                    // Advance past one-time announcements until this query is shown on the sign.
                    var shown = false
                    repeat(20) {
                        if (manager.getNextMessage() === query) {
                            shown = true
                            return@repeat
                        }
                    }
                    assertTrue(shown, "query $idx should be shown")

                    // Wait for async reply if the desk admitted it (thinking notice queued).
                    val admitted = repo.oneTimeMessages.any { it is Message.ColorMessage.AIThinking }
                    if (admitted) {
                        val deadlineWait = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (completed.get() == beforeCompleted && System.nanoTime() < deadlineWait) {
                            while (owner.drain { throw it } > 0) Unit
                            Thread.sleep(5)
                        }
                        assertEquals(beforeCompleted + 1, completed.get(), "call $idx should finish")
                        while (owner.drain { throw it } > 0) Unit
                    }
                    clockMs.addAndGet(1_000L)
                }

                assertTrue(
                    postTimes.size <= 45,
                    "Expected <= 45 wire POSTs through MessageManager, got ${postTimes.size}"
                )
            } finally {
                scope.cancel()
                stopKoin()
                dir.deleteRecursively()
            }
        }
    }
}
