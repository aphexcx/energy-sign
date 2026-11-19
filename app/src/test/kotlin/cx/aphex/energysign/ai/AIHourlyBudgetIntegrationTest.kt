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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
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
import kotlin.test.assertSame
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
                val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
                val generator = AnthropicReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    baseUrl = baseUrl,
                    budget = budget,
                    nanoTime = { clockMs.get() * 1_000_000L },
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted, reservation ->
                        service.replyTo(text, deadline, stillWanted, reservation)
                    },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                    hourlyBudget = budget,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)

                val desk = AIQueryDesk<Query>(
                    start = { q, reservation ->
                        runner.ask(q.text, stillWanted = { true }, reservation = reservation) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                    nowMs = { clockMs.get() },
                    budget = budget,
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

                // 22 queries sent 2 POSTs each (44); 23rd sent 1 POST (503) and its replay was blocked -> 45 total.
                assertEquals(45, postTimes.size, "Expected exactly 45 wire POSTs in the hour, got ${postTimes.size}")
                assertEquals(45, maxPostsInAnyRollingHour(postTimes))
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
                val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
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
                    budget = budget,
                    nanoTime = { clockMs.get() * 1_000_000L },
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted, reservation ->
                        service.replyTo(text, deadline, stillWanted, reservation)
                    },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                    hourlyBudget = budget,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)

                val desk = AIQueryDesk<Query>(
                    start = { q, reservation ->
                        runner.ask(q.text, stillWanted = { true }, reservation = reservation) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                    nowMs = { clockMs.get() },
                    budget = budget,
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

                // At t0 + 3_600_000 (1 h after handover, only 3,595 s after POST #1): still full!
                clockMs.set(t0 + 3_600_000L)
                val qTooEarly = Query("ravegpt q46-too-early")
                listed.add(qTooEarly)
                assertFalse(desk.shown(qTooEarly, clockMs.get()), "POST #1 is only 3,595 s old")

                // At t0 + 3_604_999 (3,599.999 s after POST #1): still full!
                clockMs.set(t0 + 3_604_999L)
                assertFalse(desk.shown(qTooEarly, clockMs.get()), "POST #1 is 1 ms short of an hour old")

                // At t0 + 3_605_000 (exactly 1 h after POST #1 left on the wire): room for 1!
                clockMs.set(t0 + 3_605_000L)
                latch = CountDownLatch(1)
                assertTrue(desk.shown(qTooEarly, clockMs.get()), "POST #1 is now a full hour old")
                assertTrue(latch.await(15, TimeUnit.SECONDS))
                val (finishedQ, res) = pendingResult!!
                desk.finished(finishedQ, res, clockMs.get())

                assertEquals(46, postTimes.size)
                assertEquals(45, maxPostsInAnyRollingHour(postTimes))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `withdrawn and too-late calls return their budget place and do not consume the query's three attempts`() {
        val clockMs = AtomicLong(1_000_000L)

        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("Still alive."))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val budget = AIHourlyBudget(callsPerHour = 2, nowMs = { clockMs.get() })
                val generator = AnthropicReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    baseUrl = api.baseUrl,
                    budget = budget,
                    nanoTime = { clockMs.get() * 1_000_000L },
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted, reservation ->
                        service.replyTo(text, deadline, stillWanted, reservation)
                    },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                    hourlyBudget = budget,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)
                var allowSend = false
                var expireDuringPrep = false

                val desk = AIQueryDesk<Query>(
                    start = { q, reservation ->
                        runner.ask(
                            q.text,
                            stillWanted = {
                                if (expireDuringPrep) {
                                    // Consume 90 s of the 100 s query budget -> only 10 s (< 15 s) left.
                                    clockMs.addAndGet(90_000L)
                                    true
                                } else {
                                    allowSend
                                }
                            },
                            reservation = reservation,
                        ) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                    limits = AIQueryDesk.Limits(callsPerHour = 2),
                    nowMs = { clockMs.get() },
                    budget = budget,
                )

                val q = Query("ravegpt resilient")
                listed.add(q)

                // Three WITHDRAWN calls (e.g., owner.ask 2 s timeout): must neither retire nor burn the 2-call budget.
                repeat(3) { attempt ->
                    latch = CountDownLatch(1)
                    assertTrue(desk.shown(q, clockMs.get()), "withdrawn try $attempt should be accepted")
                    assertTrue(latch.await(15, TimeUnit.SECONDS))
                    val (finishedQ, res) = pendingResult!!
                    val outcome = desk.finished(finishedQ, res, clockMs.get())
                    assertIs<AIQueryDesk.Outcome.Failed>(outcome, "WITHDRAWN try $attempt must not retire: $outcome")
                    assertEquals(0, budget.usedCount(clockMs.get()), "unused budget place must be returned")
                }

                // Three TOO_LATE_TO_SEND calls: must also neither retire nor burn the 2-call budget.
                expireDuringPrep = true
                repeat(3) { attempt ->
                    latch = CountDownLatch(1)
                    assertTrue(desk.shown(q, clockMs.get()), "too-late try $attempt should be accepted")
                    assertTrue(latch.await(15, TimeUnit.SECONDS))
                    val (finishedQ, res) = pendingResult!!
                    val outcome = desk.finished(finishedQ, res, clockMs.get())
                    assertIs<AIQueryDesk.Outcome.Failed>(outcome, "TOO_LATE_TO_SEND try $attempt must not retire: $outcome")
                    assertEquals(0, budget.usedCount(clockMs.get()), "unused budget place must be returned")
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
                assertEquals(1, api.received.size, "only the single real call should have hit the wire")
                assertEquals(1, budget.usedCount(clockMs.get()))
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
            val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
            val generator = AnthropicReplyGenerator(
                apiKey = "test-key-not-real",
                log = {},
                baseUrl = server.url("/").toString().trimEnd('/'),
                budget = budget,
                nanoTime = { clockMs.get() * 1_000_000L },
            )
            val deadline = Deadline.after(Duration.ofSeconds(16)) { clockMs.get() * 1_000_000L }

            val result = runBlocking {
                generator.generateReply(AIReplyRequest("sys", "user", deadline = deadline))
            }

            assertEquals(1, wirePosts.get(), "replay with only 11 s (< 15 s) left must NOT go on the wire")
            assertEquals(1, budget.usedCount(clockMs.get()), "first POST stays counted")
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
                val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
                val generator = OpenAIReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    baseUrl = baseUrl,
                    budget = budget,
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted, reservation ->
                        service.replyTo(text, deadline, stillWanted, reservation)
                    },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                    hourlyBudget = budget,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)

                val desk = AIQueryDesk<Query>(
                    start = { q, reservation ->
                        runner.ask(q.text, stillWanted = { true }, reservation = reservation) { result ->
                            pendingResult = q to result
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                    nowMs = { clockMs.get() },
                    budget = budget,
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

                assertEquals(45, postTimes.size, "Expected exactly 45 OpenAI wire POSTs in an hour, got ${postTimes.size}")
                assertEquals(45, maxPostsInAnyRollingHour(postTimes))
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
            override fun ask(
                text: String,
                stillWanted: () -> Boolean,
                reservation: AIHourlyBudget.Reservation?,
                onResult: (AIReplyResult) -> Unit,
            ) {
                asked.add(text to { result ->
                    if (result is AIReplyResult.Reply || !(result as AIReplyResult.NoReply).reason.nothingWasSent) {
                        reservation?.stamp()
                    }
                    onResult(result)
                })
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

            manager.processNewUserMessage("ravegpt query-a")
            while (owner.drain { throw it } > 0) Unit
            val queryA = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
            repeat(20) {
                if (manager.getNextMessage() === queryA) return@repeat
            }
            assertEquals(1, asked.size, "query-a should be out")

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
            val sharedBudget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })

            val generator = AnthropicReplyGenerator(
                apiKey = "test-key-not-real",
                log = {},
                baseUrl = baseUrl,
                budget = sharedBudget,
                nanoTime = { clockMs.get() * 1_000_000L },
            )
            val service = AIReplyService(
                generator = generator,
                log = {},
                now = { Instant.ofEpochMilli(clockMs.get()) },
            )
            val runner = AIReplyRunner(
                reply = { text, deadline, stillWanted, reservation ->
                    service.replyTo(text, deadline, stillWanted, reservation)
                },
                nanoTime = { clockMs.get() * 1_000_000L },
                scope = scope,
                hourlyBudget = sharedBudget,
            )
            // Mirrors AIReplies: exposes hourlyBudget and forwards reservation to AIReplyRunner.
            val wiredSource = object : AIReplySource {
                override val hourlyBudget: AIHourlyBudget = sharedBudget
                override fun warmUp(onDone: () -> Unit): Boolean = false
                override fun ask(
                    text: String,
                    stillWanted: () -> Boolean,
                    reservation: AIHourlyBudget.Reservation?,
                    onResult: (AIReplyResult) -> Unit,
                ) {
                    runner.ask(text, stillWanted, reservation) { result ->
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
                    single<AIReplySource> { wiredSource }
                })
            }
            try {
                val manager = MessageManager(context, repo, owner, nowMs = { clockMs.get() })
                while (owner.drain { throw it } > 0) Unit

                // Verify MessageManager's desk holds the EXACT SAME budget instance as the generator.
                val deskField = MessageManager::class.java.getDeclaredField("desk").apply { isAccessible = true }
                val desk = deskField.get(manager) as AIQueryDesk<*>
                assertSame(sharedBudget, desk.hourlyBudget, "MessageManager.desk and generator must share ONE AIHourlyBudget instance")

                repeat(26) { idx ->
                    val beforeCompleted = completed.get()
                    manager.processNewUserMessage("ravegpt mm-$idx")
                    while (owner.drain { throw it } > 0) Unit
                    val query = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())

                    var shown = false
                    repeat(20) {
                        if (manager.getNextMessage() === query) {
                            shown = true
                            return@repeat
                        }
                    }
                    assertTrue(shown, "query $idx should be shown")

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

                assertEquals(45, postTimes.size, "Expected exactly 45 wire POSTs through MessageManager, got ${postTimes.size}")
                assertEquals(45, sharedBudget.usedCount(clockMs.get()))
            } finally {
                scope.cancel()
                stopKoin()
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `free GET syncClock takes an absolute deadline before lazy client construction`() {
        val clockMs = AtomicLong(1_000_000L)
        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(
                200,
                """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
                headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
            )
            val serverClock = ServerClock { clockMs.get() }
            val generator = AnthropicReplyGenerator(
                apiKey = "test-key-not-real",
                log = {},
                serverClock = serverClock,
                baseUrl = api.baseUrl,
                nanoTime = { clockMs.get() * 1_000_000L },
                // Lazy client build takes 4.5 s of the 5 s syncClock limit -> only 500 ms (< 1 s) left!
                onBuildClient = { clockMs.addAndGet(4_500L) },
            )

            generator.syncClock(Duration.ofSeconds(5))

            assertEquals(
                0,
                api.received.size,
                "free GET must not be sent when lazy client construction left less than 1 s of the 5 s deadline"
            )
        }
    }

    @Test
    fun `free GET syncClock trims HTTP timeout to what remains after lazy client construction`() {
        val clockMs = AtomicLong(1_000_000L)
        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(
                200,
                """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
                headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
            )
            val serverClock = ServerClock { clockMs.get() }
            val generator = AnthropicReplyGenerator(
                apiKey = "test-key-not-real",
                log = {},
                serverClock = serverClock,
                baseUrl = api.baseUrl,
                nanoTime = { clockMs.get() * 1_000_000L },
                // Lazy client build takes 3 s of the 5 s syncClock limit -> 2 s left.
                onBuildClient = { clockMs.addAndGet(3_000L) },
            )

            generator.syncClock(Duration.ofSeconds(5))

            val req = api.received.single()
            assertEquals("2", req.header("x-stainless-timeout"), "free GET timeout must reflect 2 s left, not full 5 s")
            assertEquals(Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
        }
    }

    // ---- Exhaustive table over EVERY outcome (AIReplyResult.Reply + all 22 NoReplyReason values)

    private data class OutcomeCase(
        val label: String,
        val expectedReason: NoReplyReason?, // null means AIReplyResult.Reply
        val expectWireSent: Boolean,
        val expectBudgetKept: Boolean,
        val expectAttemptUsed: Boolean,
        val run: (
            budget: AIHourlyBudget,
            clockMs: AtomicLong,
            scope: CoroutineScope,
            onResult: (Query, AIReplyResult) -> Unit,
            listed: MutableList<Query>,
        ) -> Pair<AIQueryDesk<Query>, Query>,
    )

    @Test
    fun `exhaustive table over every member of NoReplyReason and Reply verifies wire send, budget place, and attempts`() {
        val testedReasons = mutableSetOf<NoReplyReason>()

        fun runCase(
            label: String,
            expectedReason: NoReplyReason?,
            expectWireSent: Boolean,
            expectBudgetKept: Boolean,
            expectAttemptUsed: Boolean,
            setup: (
                api: FakeMessagesApi,
                clockMs: AtomicLong,
                budget: AIHourlyBudget,
                scope: CoroutineScope,
            ) -> Triple<AIReplySource, Query, () -> Boolean>,
        ) {
            if (expectedReason != null) testedReasons.add(expectedReason)
            val clockMs = AtomicLong(1_000_000L)
            FakeMessagesApi().use { api ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val budget = AIHourlyBudget(callsPerHour = 10, nowMs = { clockMs.get() })
                    val (source, query, stillWanted) = setup(api, clockMs, budget, scope)
                    val listed = mutableListOf(query)
                    var finishedResult: AIReplyResult? = null
                    var latch = CountDownLatch(1)
                    val desk = AIQueryDesk<Query>(
                        start = { q, reservation ->
                            source.ask(q.text, stillWanted = stillWanted, reservation = reservation) { res ->
                                finishedResult = res
                                latch.countDown()
                            }
                        },
                        listedAmong = { known -> listed.filter { it in known }.toSet() },
                        limits = AIQueryDesk.Limits(callsPerHour = 10, coolDownsMs = listOf(0), maxAttempts = 3),
                        nowMs = { clockMs.get() },
                        budget = budget,
                    )

                    assertTrue(desk.shown(query, clockMs.get()), "$label: shown must admit")
                    assertTrue(latch.await(15, TimeUnit.SECONDS), "$label: call timed out")
                    val res = finishedResult!!
                    if (expectedReason == null) {
                        assertIs<AIReplyResult.Reply>(res, "$label: expected Reply, got $res")
                    } else {
                        val noReply = assertIs<AIReplyResult.NoReply>(res, "$label: expected NoReply($expectedReason), got $res")
                        assertEquals(expectedReason, noReply.reason, "$label: wrong NoReplyReason")
                    }

                    val outcome = desk.finished(query, res, clockMs.get())
                    val usedAfter = budget.usedCount(clockMs.get())
                    assertEquals(
                        if (expectBudgetKept) 1 else 0,
                        usedAfter,
                        "$label: budget usedCount after finished (wireSent=$expectWireSent)"
                    )

                    if (expectWireSent) {
                        assertTrue(api.received.size >= 1 || expectedReason == NoReplyReason.NETWORK || expectedReason == NoReplyReason.TIMEOUT, "$label: expected wire request")
                    } else {
                        assertEquals(0, api.received.size, "$label: expected 0 wire POSTs")
                    }

                    // Verify attempt consumption for retryable outcomes: if !expectAttemptUsed, 3 more
                    // occurrences must NOT retire the query; if expectAttemptUsed and askAgain, 2 more
                    // occurrences (total 3) MUST retire the query!
                    if (expectedReason != null && expectedReason.askAgain) {
                        assertIs<AIQueryDesk.Outcome.Failed>(outcome, "$label: first retryable failure must not retire")
                        if (!expectAttemptUsed) {
                            repeat(3) { extra ->
                                latch = CountDownLatch(1)
                                assertTrue(desk.shown(query, clockMs.get()), "$label extra $extra")
                                assertTrue(latch.await(15, TimeUnit.SECONDS))
                                val extraOutcome = desk.finished(query, finishedResult!!, clockMs.get())
                                assertIs<AIQueryDesk.Outcome.Failed>(
                                    extraOutcome,
                                    "$label: unsent call must NOT consume attempts (retired on extra $extra)"
                                )
                            }
                            assertEquals(0, budget.usedCount(clockMs.get()), "$label: unsent calls must keep returning budget slots")
                        } else {
                            // 2nd sent failure -> Failed; 3rd sent failure -> Retired
                            latch = CountDownLatch(1)
                            assertTrue(desk.shown(query, clockMs.get()), "$label attempt 2")
                            assertTrue(latch.await(15, TimeUnit.SECONDS))
                            val res2 = finishedResult!!
                            assertIs<AIQueryDesk.Outcome.Failed>(
                                desk.finished(query, res2, clockMs.get()),
                                "$label attempt 2 (res=$res2, used=${budget.usedCount(clockMs.get())}, recv=${api.received.size})"
                            )
                            assertEquals(2, budget.usedCount(clockMs.get()), "$label attempt 2 usedCount (res=$res2, recv=${api.received.size})")

                            latch = CountDownLatch(1)
                            assertTrue(desk.shown(query, clockMs.get()), "$label attempt 3")
                            assertTrue(latch.await(15, TimeUnit.SECONDS))
                            val res3 = finishedResult!!
                            assertIs<AIQueryDesk.Outcome.Retired>(
                                desk.finished(query, res3, clockMs.get()),
                                "$label attempt 3 must retire (res=$res3, used=${budget.usedCount(clockMs.get())}, recv=${api.received.size})"
                            )
                            assertEquals(3, budget.usedCount(clockMs.get()), "$label: 3 sent attempts = 3 budget slots")
                        }
                    } else if (expectedReason != null) {
                        assertIs<AIQueryDesk.Outcome.Retired>(outcome, "$label: non-retryable must retire at once")
                    } else {
                        assertIs<AIQueryDesk.Outcome.Reply>(outcome, "$label: Reply outcome")
                    }
                } finally {
                    scope.cancel()
                }
            }
        }

        fun anthropicSource(
            api: FakeMessagesApi,
            clockMs: AtomicLong,
            budget: AIHourlyBudget,
            scope: CoroutineScope,
            apiKey: String = "test-key-not-real",
            baseUrl: String = api.baseUrl,
            requestTimeout: Duration = Duration.ofSeconds(5),
            minTimeToSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
            fit: (String) -> String = AIReplyLimits::fitToSign,
        ): AIReplySource {
            val generator = AnthropicReplyGenerator(
                apiKey = apiKey,
                log = {},
                baseUrl = baseUrl,
                requestTimeout = requestTimeout,
                minTimeToSend = minTimeToSend,
                budget = budget,
                nanoTime = { clockMs.get() * 1_000_000L },
            )
            val service = AIReplyService(
                generator = generator,
                log = {},
                now = { Instant.ofEpochMilli(clockMs.get()) },
                fit = fit,
            )
            return AIReplyRunner(
                reply = { text, deadline, wanted, reservation ->
                    service.replyTo(text, deadline, wanted, reservation)
                },
                nanoTime = { clockMs.get() * 1_000_000L },
                scope = scope,
                hourlyBudget = budget,
            )
        }

        // 0. AIReplyResult.Reply
        runCase("Reply", null, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("Yes."))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt ok")) { true }
        }

        // 1. MISSING_KEY
        runCase("MISSING_KEY", NoReplyReason.MISSING_KEY, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { api, clock, budget, scope ->
            Triple(anthropicSource(api, clock, budget, scope, apiKey = ""), Query("ravegpt nokey")) { true }
        }

        // 2. NOTHING_TO_ASK
        runCase("NOTHING_TO_ASK", NoReplyReason.NOTHING_TO_ASK, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { api, clock, budget, scope ->
            Triple(anthropicSource(api, clock, budget, scope), Query("   ")) { true }
        }

        // 3. NOT_SET_UP
        runCase("NOT_SET_UP", NoReplyReason.NOT_SET_UP, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { _, _, _, _ ->
            Triple(NoAIReplies("setup failed"), Query("ravegpt nosetup")) { true }
        }

        // 4. WITHDRAWN
        runCase("WITHDRAWN", NoReplyReason.WITHDRAWN, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { api, clock, budget, scope ->
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt withdrawn")) { false }
        }

        // 5. TOO_LATE_TO_SEND
        runCase("TOO_LATE_TO_SEND", NoReplyReason.TOO_LATE_TO_SEND, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { api, clock, budget, scope ->
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt late")) {
                clock.addAndGet(90_000L)
                true
            }
        }

        // 6. REFUSED
        runCase("REFUSED", NoReplyReason.REFUSED, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "refusal"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt refused")) { true }
        }

        // 7. EMPTY
        runCase("EMPTY", NoReplyReason.EMPTY, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"   "}]"""))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt empty")) { true }
        }

        // 8. TRUNCATED
        runCase("TRUNCATED", NoReplyReason.TRUNCATED, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"half"}]""", stopReason = "max_tokens"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt cut")) { true }
        }

        // 9. UNEXPECTED_RESPONSE
        runCase("UNEXPECTED_RESPONSE", NoReplyReason.UNEXPECTED_RESPONSE, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "tool_use"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt weird")) { true }
        }

        // 10. FAILED_AFTER_ANSWER
        runCase("FAILED_AFTER_ANSWER", NoReplyReason.FAILED_AFTER_ANSWER, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("Good answer."))
            Triple(anthropicSource(api, clock, budget, scope, fit = { throw IllegalStateException("fit failed") }), Query("ravegpt fitfail")) { true }
        }

        // 11. NOT_A_MESSAGE
        runCase("NOT_A_MESSAGE", NoReplyReason.NOT_A_MESSAGE, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, "<html>Captive portal</html>")
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt portal")) { true }
        }

        // 12. TIMEOUT (after POST sent on wire -> server stalls past 400 ms request timeout;
        // use literal 127.0.0.1 so OkHttp's RouteDatabase never fails over to ::1 after timeout 1)
        runCase("TIMEOUT", NoReplyReason.TIMEOUT, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("slow"), delayMs = 2_000)
            Triple(
                anthropicSource(
                    api, clock, budget, scope,
                    baseUrl = api.baseUrl.replace("localhost", "127.0.0.1"),
                    requestTimeout = Duration.ofMillis(400),
                    minTimeToSend = Duration.ofMillis(100),
                ),
                Query("ravegpt timeout"),
            ) { true }
        }

        // 13a. NETWORK after POST sent (server resets TCP connection after receiving request bytes)
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { rstServer ->
            val acceptThread = Thread {
                while (!rstServer.isClosed) {
                    val s = runCatching { rstServer.accept() }.getOrNull() ?: break
                    try {
                        s.soTimeout = 1_000
                        s.getInputStream().read(ByteArray(4096))
                        s.setSoLinger(true, 0)
                    } catch (_: Exception) {
                        // ignore
                    } finally {
                        runCatching { s.close() }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                runCase("NETWORK (after wire send)", NoReplyReason.NETWORK, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
                    Triple(
                        anthropicSource(api, clock, budget, scope, baseUrl = "http://127.0.0.1:${rstServer.localPort}"),
                        Query("ravegpt rst"),
                    ) { true }
                }
            } finally {
                rstServer.close()
                acceptThread.join(2_000)
            }
        }

        // 13b. NETWORK before wire send (connection refused before ConnectInterceptor completes -> 0 HTTP bytes sent!)
        val unusedPort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        runCase("NETWORK (connect refused before wire)", NoReplyReason.NETWORK, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { api, clock, budget, scope ->
            Triple(
                anthropicSource(api, clock, budget, scope, baseUrl = "http://127.0.0.1:$unusedPort"),
                Query("ravegpt refused-port"),
            ) { true }
        }

        // 14, 15, 16: CERTIFICATE_CLOCK, CERTIFICATE_UNTRUSTED, TLS — TLS handshake fails before HTTP bytes leave
        // (tested via real AIReplyRunner + AIReplyService + AIQueryDesk with a generator reporting those pre-wire IO failures).
        for (tlsReason in listOf(NoReplyReason.CERTIFICATE_CLOCK, NoReplyReason.CERTIFICATE_UNTRUSTED, NoReplyReason.TLS)) {
            runCase(tlsReason.name, tlsReason, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { _, clock, budget, scope ->
                val gen = object : AIReplyGenerator {
                    override val name = "tls-fake"
                    override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                        val ex = when (tlsReason) {
                            NoReplyReason.CERTIFICATE_CLOCK -> CertificateExpiredException("expired")
                            NoReplyReason.CERTIFICATE_UNTRUSTED -> javax.net.ssl.SSLPeerUnverifiedException("untrusted")
                            else -> javax.net.ssl.SSLException("tls")
                        }
                        return AIReplyResult.NoReply(AnthropicReplyGenerator.reasonForIoFailure(ex), tlsReason.name)
                    }
                }
                val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clock.get()) })
                val runner = AIReplyRunner(
                    reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
                    nanoTime = { clock.get() * 1_000_000L },
                    scope = scope,
                    hourlyBudget = budget,
                )
                Triple(runner, Query("ravegpt $tlsReason")) { true }
            }
        }

        // 17. RATE_LIMITED (HTTP 429)
        runCase("RATE_LIMITED", NoReplyReason.RATE_LIMITED, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt 429")) { true }
        }

        // 18. SERVER_ERROR (HTTP 529)
        runCase("SERVER_ERROR", NoReplyReason.SERVER_ERROR, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(529, FakeMessagesApi.error("overloaded_error", "overloaded"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt 529")) { true }
        }

        // 19. KEY_REJECTED (HTTP 401)
        runCase("KEY_REJECTED", NoReplyReason.KEY_REJECTED, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(401, FakeMessagesApi.error("authentication_error", "bad key"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt 401")) { true }
        }

        // 20. REQUEST_REJECTED (HTTP 400)
        runCase("REQUEST_REJECTED", NoReplyReason.REQUEST_REJECTED, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = false) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(400, FakeMessagesApi.error("invalid_request_error", "bad req"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt 400")) { true }
        }

        // 21. PROVIDER_ERROR (HTTP 402 / non-standard status after wire send)
        runCase("PROVIDER_ERROR", NoReplyReason.PROVIDER_ERROR, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
            api.answer = FakeMessagesApi.Answer(402, FakeMessagesApi.error("billing_error", "billing"))
            Triple(anthropicSource(api, clock, budget, scope), Query("ravegpt 402")) { true }
        }

        // 22a. UNEXPECTED_FAILURE before wire send (throws during prep before HTTP -> 0 wire, slot refunded, 0 attempts)
        runCase("UNEXPECTED_FAILURE (before wire)", NoReplyReason.UNEXPECTED_FAILURE, expectWireSent = false, expectBudgetKept = false, expectAttemptUsed = false) { _, clock, budget, scope ->
            val gen = object : AIReplyGenerator {
                override val name = "throw-before-wire"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult =
                    throw IllegalStateException("prep boom")
            }
            val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clock.get()) })
            val runner = AIReplyRunner(
                reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
                nanoTime = { clock.get() * 1_000_000L },
                scope = scope,
                hourlyBudget = budget,
            )
            Triple(runner, Query("ravegpt boom-before")) { true }
        }

        // 22b. UNEXPECTED_FAILURE after wire send (reservation already stamped -> slot kept, attempt used)
        runCase("UNEXPECTED_FAILURE (after wire)", NoReplyReason.UNEXPECTED_FAILURE, expectWireSent = true, expectBudgetKept = true, expectAttemptUsed = true) { api, clock, budget, scope ->
            val gen = object : AIReplyGenerator {
                override val name = "throw-after-wire"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                    assertTrue(request.reservation!!.stamp())
                    api.received.add(FakeMessagesApi.Received("/v1/messages", okhttp3.Headers.headersOf(), "{}"))
                    throw IllegalStateException("post-wire boom")
                }
            }
            val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clock.get()) })
            val runner = AIReplyRunner(
                reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
                nanoTime = { clock.get() * 1_000_000L },
                scope = scope,
                hourlyBudget = budget,
            )
            Triple(runner, Query("ravegpt boom-after")) { true }
        }

        // Guard: every single member of NoReplyReason MUST be covered in the table above.
        assertEquals(
            NoReplyReason.values().toSet(),
            testedReasons,
            "Missing NoReplyReason members from table: ${NoReplyReason.values().toSet() - testedReasons}"
        )
    }

    @Test
    fun `multi-hour mixed workload with replays, failures, withdrawals, deleted queries, and prep time never exceeds 45 wire POSTs in any hour`() {
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()
        val requestCounter = AtomicInteger(0)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        postTimes.add(clockMs.get())
                    }
                    val n = requestCounter.incrementAndGet()
                    return when (n % 7) {
                        // Replay trigger: 503 Retry-After: 0 followed immediately by 200 OK
                        1 -> MockResponse()
                            .setResponseCode(503)
                            .setHeader("content-type", "application/json")
                            .setHeader("retry-after", "0")
                            .setBody(FakeMessagesApi.error("api_error", "busy"))
                        // Ordinary server error (triggers backoff, no replay)
                        4 -> MockResponse()
                            .setResponseCode(529)
                            .setHeader("content-type", "application/json")
                            .setBody(FakeMessagesApi.error("overloaded_error", "overloaded"))
                        else -> MockResponse()
                            .setResponseCode(200)
                            .setHeader("content-type", "application/json")
                            .setBody(okAnthropicBody("Ok $n."))
                    }
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
                val prepCounter = AtomicInteger(0)
                val generator = AnthropicReplyGenerator(
                    apiKey = "test-key-not-real",
                    log = {},
                    effort = {
                        // Every 3rd call spends 5 s in preparation before sending.
                        if (prepCounter.incrementAndGet() % 3 == 0) clockMs.addAndGet(5_000L)
                        AIEffort.HIGH
                    },
                    baseUrl = baseUrl,
                    budget = budget,
                    nanoTime = { clockMs.get() * 1_000_000L },
                )
                val service = AIReplyService(
                    generator = generator,
                    log = {},
                    now = { Instant.ofEpochMilli(clockMs.get()) },
                )
                val runner = AIReplyRunner(
                    reply = { text, deadline, stillWanted, reservation ->
                        service.replyTo(text, deadline, stillWanted, reservation)
                    },
                    nanoTime = { clockMs.get() * 1_000_000L },
                    scope = scope,
                    hourlyBudget = budget,
                )

                val listed = mutableListOf<Query>()
                var pendingResult: Pair<Query, AIReplyResult>? = null
                var latch = CountDownLatch(1)
                var turn = 0

                val desk = AIQueryDesk<Query>(
                    start = { q, reservation ->
                        val thisTurn = turn
                        val wanted = {
                            when {
                                thisTurn % 11 == 0 -> false // WITHDRAWN
                                thisTurn % 13 == 0 -> {
                                    clockMs.addAndGet(90_000L) // TOO_LATE_TO_SEND
                                    true
                                }
                                else -> true
                            }
                        }
                        runner.ask(q.text, stillWanted = wanted, reservation = reservation) { res ->
                            pendingResult = q to res
                            latch.countDown()
                        }
                    },
                    listedAmong = { known -> listed.filter { it in known }.toSet() },
                    limits = AIQueryDesk.Limits(callsPerHour = 45, coolDownsMs = listOf(10_000L, 20_000L)),
                    nowMs = { clockMs.get() },
                    budget = budget,
                )

                val startClock = clockMs.get()
                // Run across 2.5 hours of simulated festival time.
                while (clockMs.get() < startClock + 9_000_000L) {
                    turn++
                    val q = Query("ravegpt mix-$turn")
                    listed.add(q)
                    latch = CountDownLatch(1)
                    if (desk.shown(q, clockMs.get())) {
                        assertTrue(latch.await(15, TimeUnit.SECONDS), "turn $turn did not finish")
                        val (finishedQ, res) = pendingResult!!
                        // Every 17th query gets deleted while its call is in flight (Outcome.Gone).
                        if (turn % 17 == 0) listed.remove(finishedQ)
                        desk.finished(finishedQ, res, clockMs.get())
                    }
                    clockMs.addAndGet(45_000L)
                }

                assertTrue(postTimes.size > 70, "expected budget recovery across 2.5 hours, got ${postTimes.size} POSTs")
                val maxInHour = maxPostsInAnyRollingHour(postTimes)
                assertTrue(
                    maxInHour <= 45,
                    "rolling hour ceiling breached: $maxInHour > 45 across ${postTimes.size} POSTs"
                )
            } finally {
                scope.cancel()
            }
        }
    }
}
