package cx.aphex.energysign.ai

import android.content.Context
import android.content.ContextWrapper
import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.errors.AnthropicIoException
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.cert.CertificateExpiredException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit, whole-class regression, exhaustive outcome-table, and integrated rolling-hour tests for
 * [AIHourlyBudget], [WireBudgetInterceptor], [BudgetedOkHttpClient], [AIQueryDesk],
 * [AnthropicReplyGenerator], [OpenAIReplyGenerator], and [MessageManager] wiring.
 */
class AIHourlyBudgetTest {

    private class Query(val text: String)

    private fun okAnthropicBody(text: String = "Hydrate."): String =
        FakeMessagesApi.message("""[{"type":"text","text":"$text"}]""")

    private fun okOpenAiBody(text: String = "Hydrate."): String =
        """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"$text"},"finish_reason":null}]}""" + "\n\n" +
            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
            "data: [DONE]\n\n"

    private fun maxInRollingHour(timestampsMs: List<Long>): Int =
        timestampsMs.maxOfOrNull { t -> timestampsMs.count { it >= t && it < t + AIHourlyBudget.HOUR_MS } } ?: 0

    private fun replayDispatcher(
        clockMs: AtomicLong,
        postTimes: MutableList<Long>,
        openAi: Boolean = false,
    ): Dispatcher {
        val toggle = AtomicInteger(0)
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST") postTimes.add(clockMs.get())
                return if (toggle.incrementAndGet() % 2 == 1) {
                    MockResponse()
                        .setResponseCode(503)
                        .setHeader("content-type", "application/json")
                        .setHeader("retry-after", "0")
                        .setBody(if (openAi) """{"error":{"message":"busy","type":"server_error"}}""" else FakeMessagesApi.error("api_error", "busy"))
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("content-type", if (openAi) "text/event-stream" else "application/json")
                        .setBody(if (openAi) okOpenAiBody() else okAnthropicBody())
                }
            }
        }
    }

    /** Shared real desk + runner + service + generator harness driven by a test clock. */
    private inner class DeskHarness(
        val clockMs: AtomicLong = AtomicLong(1_000_000L),
        callsPerHour: Int = 45,
        coolDownsMs: List<Long> = AIQueryDesk.Limits().coolDownsMs,
        val budget: AIHourlyBudget = AIHourlyBudget(callsPerHour = callsPerHour, nowMs = { clockMs.get() }),
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        val sourceFactory: (AIHourlyBudget, AtomicLong, CoroutineScope) -> AIReplySource,
    ) : AutoCloseable {
        val listed = mutableListOf<Query>()
        private val source = sourceFactory(budget, clockMs, scope)
        private var pendingResult: Pair<Query, AIReplyResult>? = null
        private var latch = CountDownLatch(1)
        var stillWanted: () -> Boolean = { true }

        val desk = AIQueryDesk<Query>(
            start = { q, reservation ->
                source.ask(q.text, stillWanted = stillWanted, reservation = reservation) { res ->
                    pendingResult = q to res
                    latch.countDown()
                }
            },
            listedAmong = { known -> listed.filter { it in known }.toSet() },
            limits = AIQueryDesk.Limits(callsPerHour = callsPerHour, coolDownsMs = coolDownsMs),
            nowMs = { clockMs.get() },
            budget = budget,
        )

        fun askAndFinish(
            query: Query,
            deleteBeforeFinish: Boolean = false,
        ): Pair<AIReplyResult, AIQueryDesk.Outcome>? {
            if (query !in listed) listed.add(query)
            latch = CountDownLatch(1)
            if (!desk.shown(query, clockMs.get())) return null
            assertTrue(latch.await(15, TimeUnit.SECONDS), "call did not finish for ${query.text}")
            val (q, res) = pendingResult!!
            if (deleteBeforeFinish) listed.remove(q)
            return res to desk.finished(q, res, clockMs.get())
        }

        override fun close() = scope.cancel()
    }

    private fun anthropicSource(
        baseUrl: String,
        budget: AIHourlyBudget,
        clockMs: AtomicLong,
        scope: CoroutineScope,
        apiKey: String = "test-key-not-real",
        effort: () -> AIEffort = { AIEffort.HIGH },
        requestTimeout: Duration = Duration.ofSeconds(5),
        minTimeToSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
        fit: (String) -> String = AIReplyLimits::fitToSign,
    ): AIReplySource {
        val gen = AnthropicReplyGenerator(
            apiKey = apiKey, log = {}, effort = effort, baseUrl = baseUrl,
            requestTimeout = requestTimeout, minTimeToSend = minTimeToSend,
            budget = budget, nanoTime = { clockMs.get() * 1_000_000L },
        )
        val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clockMs.get()) }, fit = fit)
        return AIReplyRunner(
            reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
            nanoTime = { clockMs.get() * 1_000_000L }, scope = scope, hourlyBudget = budget,
        )
    }

    // ---- 1. Budget invariants, concurrency, and fail-closed interceptor guards

    @Test
    fun `budget reservation holds ceiling, stamps at wire time, poisons on release, and survives concurrency`() {
        val now = AtomicLong(1_000_000L)
        val budget = AIHourlyBudget(callsPerHour = 2, nowMs = { now.get() })

        val r1 = assertNotNull(budget.reserve())
        val r2 = assertNotNull(budget.reserve())
        assertFalse(budget.hasRoom())
        assertNull(budget.reserve())

        now.addAndGet(5_000L)
        assertTrue(r1.stamp())
        assertTrue(r1.wasSent)
        assertFalse(r1.stamp())

        assertFalse(r2.release())
        assertFalse(r2.stamp(), "released reservation must stay permanently closed")
        assertTrue(budget.hasRoom())
        assertTrue(r1.release(), "releasing a stamped reservation keeps the timestamp")
        assertEquals(1, budget.usedCount())

        now.set(1_005_000L + AIHourlyBudget.HOUR_MS - 1)
        assertEquals(1, budget.usedCount())
        now.set(1_005_000L + AIHourlyBudget.HOUR_MS)
        assertEquals(0, budget.usedCount())

        // Concurrent 8-thread stress test: never stamps more than callsPerHour.
        val concBudget = AIHourlyBudget(callsPerHour = 45, nowMs = { now.get() })
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val stamped = AtomicInteger(0)
        try {
            repeat(8) { t ->
                pool.submit {
                    start.await()
                    repeat(30) { i ->
                        if ((t + i) % 2 == 0) {
                            val r = concBudget.reserve()
                            if (r != null) {
                                if (r.stamp()) stamped.incrementAndGet()
                                r.release()
                            }
                        } else if (concBudget.tryStampAdditional()) {
                            stamped.incrementAndGet()
                        }
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(45, stamped.get())
            assertEquals(45, concBudget.usedCount())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `WireBudgetInterceptor fails closed on untagged requests, non-GET FreeModelGet, and expired FreeModelGet`() {
        val now = AtomicLong(1_000_000L)
        val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { now.get() })
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val client = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor(WireBudgetInterceptor(budget))
                .build()
            try {
                // Untagged POST -> refused
                val ex1 = assertFailsWith<WireSendBlockedException> {
                    client.newCall(Request.Builder().url(server.url("/v1/messages")).post("{}".toRequestBody()).build()).execute().close()
                }
                assertEquals(WireSendBlockedException.Kind.BUDGET_EXHAUSTED, ex1.kind)
                assertEquals(0, server.requestCount)

                // POST under FreeModelGet -> refused
                val freeCtx = WireCallContext.FreeModelGet(Deadline.after(Duration.ofSeconds(5)) { now.get() * 1_000_000L })
                assertFailsWith<WireSendBlockedException> {
                    client.newCall(Request.Builder().url(server.url("/v1/messages")).post("{}".toRequestBody()).tag(WireCallContext::class.java, freeCtx).build()).execute().close()
                }
                assertEquals(0, server.requestCount)

                // Expired FreeModelGet -> refused
                val expiredFree = WireCallContext.FreeModelGet(Deadline.after(Duration.ZERO) { now.get() * 1_000_000L })
                val ex3 = assertFailsWith<WireSendBlockedException> {
                    client.newCall(Request.Builder().url(server.url("/v1/models/claude")).get().tag(WireCallContext::class.java, expiredFree).build()).execute().close()
                }
                assertEquals(WireSendBlockedException.Kind.TOO_LATE, ex3.kind)
                assertEquals(0, server.requestCount)

                // Valid FreeModelGet -> succeeds, 0 budget slots used
                client.newCall(Request.Builder().url(server.url("/v1/models/claude")).get().tag(WireCallContext::class.java, freeCtx).build()).execute().use {
                    assertEquals(200, it.code)
                }
                assertEquals(1, server.requestCount)
                assertEquals(0, budget.usedCount())
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    // ---- 2. Class 1 table: nothing is charged or decided before its outcome is known

    @Test
    fun `class 1 table - pre-write failures do not charge budget or attempts, post-write failures do, and sync start throw clears notice`() {
        data class Row(val name: String, val failure: Throwable?, val preCanceled: Boolean = false, val expectCharged: Boolean)
        val rows = listOf(
            Row("HTTP/2 GOAWAY ConnectionShutdownException", ConnectionShutdownException(), expectCharged = false),
            Row("HTTP/2 REFUSED_STREAM", StreamResetException(ErrorCode.REFUSED_STREAM), expectCharged = false),
            Row("pre-cancelled call", null, preCanceled = true, expectCharged = false),
            Row("post-write TCP RST IOException", IOException("Connection reset by peer"), expectCharged = true),
            Row("post-write HTTP/2 CANCEL", StreamResetException(ErrorCode.CANCEL), expectCharged = true),
        )

        val stubClient = OkHttpClient()
        try {
            for (row in rows) {
                val now = AtomicLong(1_000_000L)
                val budget = AIHourlyBudget(callsPerHour = 2, nowMs = { now.get() })
                val interceptor = WireBudgetInterceptor(budget)
                val reservation = assertNotNull(budget.reserve())
                val ctx = WireCallContext.Paid(reservation, Deadline.after(Duration.ofSeconds(60)) { now.get() * 1_000_000L }, Duration.ofSeconds(15))
                val req = Request.Builder().url("http://127.0.0.1/v1/messages").post("{}".toRequestBody()).tag(WireCallContext::class.java, ctx).build()
                val call = stubClient.newCall(req).apply { if (row.preCanceled) cancel() }
                val chain = object : Interceptor.Chain {
                    override fun request() = req
                    override fun proceed(request: Request): Response = throw (row.failure ?: error("proceed must not run when pre-canceled"))
                    override fun connection(): Connection? = null
                    override fun call(): Call = call
                    override fun connectTimeoutMillis() = 1_000
                    override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
                    override fun readTimeoutMillis() = 1_000
                    override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
                    override fun writeTimeoutMillis() = 1_000
                    override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
                }

                assertFailsWith<Throwable>(row.name) { interceptor.intercept(chain) }
                assertEquals(row.expectCharged, reservation.wasSent, "${row.name}: wasSent")
                assertEquals(if (row.expectCharged) 1 else 0, ctx.wireSends.get(), "${row.name}: wireSends")
                assertEquals(row.expectCharged, reservation.release(), "${row.name}: release()")
                assertEquals(if (row.expectCharged) 1 else 0, budget.usedCount(), "${row.name}: usedCount")
            }

            // Pre-write GOAWAY on a replay (2nd send of a call): extra replay slot released, send #1 kept.
            val now = AtomicLong(1_000_000L)
            val budget = AIHourlyBudget(callsPerHour = 2, nowMs = { now.get() })
            val interceptor = WireBudgetInterceptor(budget)
            val r1 = assertNotNull(budget.reserve())
            assertTrue(r1.stamp())
            val ctx = WireCallContext.Paid(r1, Deadline.after(Duration.ofSeconds(60)) { now.get() * 1_000_000L }, Duration.ofSeconds(15))
            ctx.wireSends.set(1)
            val req = Request.Builder().url("http://127.0.0.1/v1/messages").post("{}".toRequestBody()).tag(WireCallContext::class.java, ctx).build()
            val call = stubClient.newCall(req)
            val replayChain = object : Interceptor.Chain {
                override fun request() = req
                override fun proceed(request: Request): Response = throw ConnectionShutdownException()
                override fun connection(): Connection? = null
                override fun call(): Call = call
                override fun connectTimeoutMillis() = 1_000
                override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
                override fun readTimeoutMillis() = 1_000
                override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
                override fun writeTimeoutMillis() = 1_000
                override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
            }
            assertFailsWith<ConnectionShutdownException> { interceptor.intercept(replayChain) }
            assertEquals(1, budget.usedCount(), "unused replay slot must be released on GOAWAY while send #1 stays")
            assertEquals(1, ctx.wireSends.get())
        } finally {
            stubClient.dispatcher.executorService.shutdown()
        }

        // Synchronous throw from AIQueryDesk.start must clear noticeFor and release reservation.
        val now = AtomicLong(1_000_000L)
        val desk = AIQueryDesk<String>(
            start = { _, _ -> throw IllegalStateException("start failed") },
            listedAmong = { it },
            nowMs = { now.get() },
        )
        assertFailsWith<IllegalStateException> { desk.shown("q", now.get()) }
        assertFalse(desk.thinkingWanted(now.get()), "sync throw from start() must clear noticeFor")
        assertEquals(0, desk.hourlyBudget.usedCount(now.get()))
    }

    // ---- 3. Class 2 table: every limit is computed AFTER time-consuming preparation

    @Test
    fun `class 2 table - timeouts and expiry checks are computed after serialization, lazy client build, and history scan`() {
        fun slowBody(clockMs: AtomicLong, serializeMs: Long): HttpRequestBody = object : HttpRequestBody {
            private val bytes: ByteArray by lazy { clockMs.addAndGet(serializeMs); "{}".toByteArray() }
            override fun writeTo(outputStream: OutputStream) = outputStream.write(bytes)
            override fun contentType() = "application/json"
            override fun contentLength() = bytes.size.toLong()
            override fun repeatable() = true
            override fun close() {}
        }

        // Row 2a: BudgetedOkHttpClient lazy body serialization spends 4 s of 20 s -> timeout MUST be 16 s.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val clockMs = AtomicLong(1_000_000L)
            val budget = AIHourlyBudget(45) { clockMs.get() }
            val ctx = WireCallContext.Paid(budget.reserve(), Deadline.after(Duration.ofSeconds(20)) { clockMs.get() * 1_000_000L }, Duration.ofSeconds(15))
            val baseUrl = server.url("/").toString().trimEnd('/')
            val transport = BudgetedOkHttpClient(
                Timeout.builder().connect(Duration.ofSeconds(10)).request(Duration.ofSeconds(90)).build(),
                Duration.ofSeconds(90), Duration.ofSeconds(10),
                object : Backend { override fun baseUrl() = baseUrl; override fun close() {} },
                WireBudgetInterceptor(budget),
            ) { ctx }
            try {
                val req = HttpRequest.builder().method(HttpMethod.POST).baseUrl(baseUrl).addPathSegments("v1", "messages").body(slowBody(clockMs, 4_000L)).build()
                transport.execute(req, RequestOptions.none()).use { assertEquals(200, it.statusCode()) }
                val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("16", recorded.getHeader("X-Stainless-Timeout"))
                assertEquals("16", recorded.getHeader("X-Stainless-Read-Timeout"))
            } finally {
                transport.close()
            }
        }

        // Row 2b: BudgetedOkHttpClient lazy serialization spends 6 s of 20 s (14 s < 15 s) -> blocked BEFORE TCP accept().
        val accepted = AtomicInteger(0)
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            val acceptThread = Thread {
                while (!listener.isClosed) {
                    val s = runCatching { listener.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    runCatching { s.close() }
                }
            }.apply { isDaemon = true; start() }
            val clockMs = AtomicLong(1_000_000L)
            val budget = AIHourlyBudget(45) { clockMs.get() }
            val res = assertNotNull(budget.reserve())
            val ctx = WireCallContext.Paid(res, Deadline.after(Duration.ofSeconds(20)) { clockMs.get() * 1_000_000L }, Duration.ofSeconds(15))
            val url = "http://127.0.0.1:${listener.localPort}"
            val transport = BudgetedOkHttpClient(
                Timeout.builder().connect(Duration.ofSeconds(10)).request(Duration.ofSeconds(90)).build(),
                Duration.ofSeconds(90), Duration.ofSeconds(10),
                object : Backend { override fun baseUrl() = url; override fun close() {} },
                WireBudgetInterceptor(budget),
            ) { ctx }
            try {
                val req = HttpRequest.builder().method(HttpMethod.POST).baseUrl(url).addPathSegments("v1", "messages").body(slowBody(clockMs, 6_000L)).build()
                val ex = assertFailsWith<AnthropicIoException> { transport.execute(req, RequestOptions.none()).close() }
                assertEquals(WireSendBlockedException.Kind.TOO_LATE, assertNotNull(WireSendBlockedException.findIn(ex)).kind)
                assertEquals(0, accepted.get(), "must block before opening TCP socket")
                assertFalse(res.release())
            } finally {
                transport.close()
                listener.close()
                acceptThread.join(2_000)
            }
        }

        // Row 2c & 2d: Free GET syncClock absolute deadline across lazy client build (4.5 s skips; 3 s trims to 2 s).
        val modelJson = """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}"""
        val dateHdr = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT")
        for ((buildMs, expectRequests, expectTimeoutHeader) in listOf(Triple(4_500L, 0, null), Triple(3_000L, 1, "2"))) {
            val clockMs = AtomicLong(1_000_000L)
            FakeMessagesApi().use { api ->
                api.answer = FakeMessagesApi.Answer(200, modelJson, headers = dateHdr)
                val serverClock = ServerClock { clockMs.get() }
                AnthropicReplyGenerator(
                    apiKey = "test-key-not-real", log = {}, serverClock = serverClock, baseUrl = api.baseUrl,
                    nanoTime = { clockMs.get() * 1_000_000L }, onBuildClient = { clockMs.addAndGet(buildMs) },
                ).syncClock(Duration.ofSeconds(5))
                assertEquals(expectRequests, api.received.size, "buildMs=$buildMs")
                if (expectTimeoutHeader != null) {
                    assertEquals(expectTimeoutHeader, api.received.single().header("x-stainless-timeout"))
                    assertEquals(Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
                }
            }
        }

        // Row 2e: MessageManager desk reads fresh clock on dequeue after finished() history scan.
        val dir = Files.createTempDirectory("es6wb-dequeue").toFile()
        val context: Context = object : ContextWrapper(null) { override fun getFilesDir(): File = dir }
        val repo = MessageRepository(saver = Executor { })
        val owner = OwnerQueue()
        val asked = CopyOnWriteArrayList<Pair<String, (AIReplyResult) -> Unit>>()
        val fakeReplies = object : AIReplySource {
            override fun warmUp(onDone: () -> Unit) = false
            override fun ask(text: String, stillWanted: () -> Boolean, reservation: AIHourlyBudget.Reservation?, onResult: (AIReplyResult) -> Unit) {
                asked.add(text to { r -> reservation?.stamp(); onResult(r) })
            }
        }
        var phase = "init"
        var finishedReads = 0
        var nowMs = 1_000_000L
        val clock: () -> Long = { if (phase == "finishing") { if (++finishedReads == 1) 1_059_999L else 1_060_001L } else nowMs }
        stopKoin()
        startKoin { modules(module { single { repo }; single { owner }; single<AIReplySource> { fakeReplies } }) }
        try {
            val manager = MessageManager(context, repo, owner, nowMs = clock)
            while (owner.drain { throw it } > 0) Unit
            manager.processNewUserMessage("ravegpt query-a")
            while (owner.drain { throw it } > 0) Unit
            val qA = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
            repeat(20) { if (manager.getNextMessage() === qA) return@repeat }
            assertEquals(1, asked.size)

            nowMs = 1_030_000L
            manager.processNewUserMessage("ravegpt query-b")
            while (owner.drain { throw it } > 0) Unit
            val qB = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
            assertTrue((1..20).any { manager.getNextMessage() === qB })

            phase = "finishing"
            asked.first().second(AIReplyResult.Reply("Answer A."))
            while (owner.drain { throw it } > 0) Unit
            assertEquals(1, asked.size, "query-b expired at 1_060_000 and must not be promoted at 1_060_001")
        } finally {
            stopKoin()
            dir.deleteRecursively()
        }
    }

    // ---- 4. Rolling-hour ceiling: replays (Anthropic & OpenAI), prep time, 15 s replay check, withdrawals, multi-hour mix

    @Test
    fun `replays on 503 Retry-After 0 never exceed 45 wire POSTs in an hour for Anthropic or OpenAI`() {
        for (provider in listOf(AIProvider.ANTHROPIC, AIProvider.OPENAI)) {
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            MockWebServer().use { server ->
                server.dispatcher = replayDispatcher(clockMs, postTimes, openAi = provider == AIProvider.OPENAI)
                server.start()
                val baseUrl = server.url("/").toString().trimEnd('/')
                DeskHarness(clockMs = clockMs, sourceFactory = { budget, clk, scope ->
                    val gen = AIReplyGenerators.forProvider(
                        provider = provider, anthropicApiKey = "test-key", openAiApiKey = "test-key",
                        effort = { AIEffort.HIGH }, log = {}, budget = budget, nanoTime = { clk.get() * 1_000_000L },
                        anthropicBaseUrl = baseUrl, openAiBaseUrl = "$baseUrl/v1/",
                    )
                    val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clk.get()) })
                    AIReplyRunner(reply = { t, d, w, r -> svc.replyTo(t, d, w, r) }, nanoTime = { clk.get() * 1_000_000L }, scope = scope, hourlyBudget = budget)
                }).use { h ->
                    repeat(30) { idx ->
                        h.askAndFinish(Query("ravegpt $provider-$idx"))
                        clockMs.addAndGet(1_000L)
                    }
                }
                assertEquals(45, postTimes.size, "$provider: expected exactly 45 wire POSTs")
                assertEquals(45, maxInRollingHour(postTimes), "$provider: rolling hour max")
            }
        }
    }

    @Test
    fun `prep delay, 15 s replay check, withdrawn and too-late refunds, and multi-hour mixed workload hold the 45-call ceiling`() {
        // A. 5 s preparation delay on query #1: slot reopens 1 full hour after wire send (t0 + 3_605_000), not handover.
        run {
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            val prepDone = AtomicBoolean(false)
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "POST") postTimes.add(clockMs.get())
                        return MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(okAnthropicBody())
                    }
                }
                server.start()
                val baseUrl = server.url("/").toString().trimEnd('/')
                DeskHarness(clockMs = clockMs, sourceFactory = { budget, clk, scope ->
                    anthropicSource(baseUrl, budget, clk, scope, effort = {
                        if (prepDone.compareAndSet(false, true)) clk.addAndGet(5_000L)
                        AIEffort.HIGH
                    })
                }).use { h ->
                    val t0 = clockMs.get()
                    repeat(45) { idx ->
                        assertNotNull(h.askAndFinish(Query("ravegpt prep-$idx")))
                        clockMs.addAndGet(1_000L)
                    }
                    clockMs.set(t0 + AIHourlyBudget.HOUR_MS)
                    assertNull(h.askAndFinish(Query("ravegpt early-1")), "only 3,595 s since POST #1")
                    clockMs.set(t0 + AIHourlyBudget.HOUR_MS + 4_999L)
                    assertNull(h.askAndFinish(Query("ravegpt early-2")), "1 ms short of 1 h since POST #1")
                    clockMs.set(t0 + AIHourlyBudget.HOUR_MS + 5_000L)
                    assertNotNull(h.askAndFinish(Query("ravegpt ok-46")), "full hour after POST #1")
                }
                assertEquals(46, postTimes.size)
                assertEquals(45, maxInRollingHour(postTimes))
            }
        }

        // B. 15 s check re-evaluated before a 503 Retry-After: 0 replay leaves on the wire.
        run {
            val clockMs = AtomicLong(1_000_000L)
            val wirePosts = AtomicInteger(0)
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return if (wirePosts.incrementAndGet() == 1) {
                            clockMs.addAndGet(5_000L) // 16 s -> 11 s (< 15 s)
                            MockResponse().setResponseCode(503).setHeader("content-type", "application/json").setHeader("retry-after", "0").setBody(FakeMessagesApi.error("api_error", "busy"))
                        } else {
                            MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(okAnthropicBody())
                        }
                    }
                }
                server.start()
                val budget = AIHourlyBudget(45) { clockMs.get() }
                val gen = AnthropicReplyGenerator("test-key", log = {}, baseUrl = server.url("/").toString().trimEnd('/'), budget = budget, nanoTime = { clockMs.get() * 1_000_000L })
                val res = runBlocking { gen.generateReply(AIReplyRequest("s", "u", Deadline.after(Duration.ofSeconds(16)) { clockMs.get() * 1_000_000L })) }
                assertEquals(1, wirePosts.get())
                assertEquals(1, budget.usedCount())
                assertEquals(NoReplyReason.SERVER_ERROR, assertIs<AIReplyResult.NoReply>(res).reason)
            }
        }

        // C. Multi-hour mixed workload: replays, 529s, 5 s prep delays, withdrawals, too-late, deleted Gone queries.
        run {
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            val reqNum = AtomicInteger(0)
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "POST") postTimes.add(clockMs.get())
                        return when (reqNum.incrementAndGet() % 7) {
                            1 -> MockResponse().setResponseCode(503).setHeader("content-type", "application/json").setHeader("retry-after", "0").setBody(FakeMessagesApi.error("api_error", "busy"))
                            4 -> MockResponse().setResponseCode(529).setHeader("content-type", "application/json").setBody(FakeMessagesApi.error("overloaded_error", "busy"))
                            else -> MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(okAnthropicBody())
                        }
                    }
                }
                server.start()
                val baseUrl = server.url("/").toString().trimEnd('/')
                val prepCount = AtomicInteger(0)
                DeskHarness(clockMs = clockMs, coolDownsMs = listOf(10_000L, 20_000L), sourceFactory = { budget, clk, scope ->
                    anthropicSource(baseUrl, budget, clk, scope, effort = {
                        if (prepCount.incrementAndGet() % 3 == 0) clk.addAndGet(5_000L)
                        AIEffort.HIGH
                    })
                }).use { h ->
                    val start = clockMs.get()
                    var turn = 0
                    while (clockMs.get() < start + 9_000_000L) {
                        turn++
                        val t = turn
                        h.stillWanted = {
                            when {
                                t % 11 == 0 -> false
                                t % 13 == 0 -> { clockMs.addAndGet(90_000L); true }
                                else -> true
                            }
                        }
                        h.askAndFinish(Query("ravegpt mix-$t"), deleteBeforeFinish = t % 17 == 0)
                        clockMs.addAndGet(45_000L)
                    }
                }
                assertTrue(postTimes.size > 70, "budget must recover across 2.5 h: ${postTimes.size}")
                assertTrue(maxInRollingHour(postTimes) <= 45, "rolling hour breached: ${maxInRollingHour(postTimes)}")
            }
        }
    }

    // ---- 5. Exhaustive outcome table over ALL 22 NoReplyReason values + Reply (zero assertion bypasses)

    @Test
    fun `exhaustive table over every NoReplyReason and Reply verifies exact wire count, budget slot, and attempts`() {
        val testedReasons = mutableSetOf<NoReplyReason>()

        fun verifyOutcome(
            label: String,
            expectedReason: NoReplyReason?,
            expectWireSent: Boolean,
            expectBudgetKept: Boolean,
            expectAttemptUsed: Boolean,
            setup: (FakeMessagesApi, AtomicLong, AIHourlyBudget, CoroutineScope) -> Triple<AIReplySource, Query, Pair<() -> Boolean, () -> Int>>,
        ) {
            expectedReason?.let(testedReasons::add)
            FakeMessagesApi().use { api ->
                val clockMs = AtomicLong(1_000_000L)
                var wireCounter: () -> Int = { api.received.size }
                DeskHarness(clockMs = clockMs, callsPerHour = 10, coolDownsMs = listOf(0), sourceFactory = { budget, clk, scope ->
                    val (src, _, hooks) = setup(api, clk, budget, scope)
                    wireCounter = hooks.second
                    src
                }).use { h ->
                    val (_, query, hooks) = setup(api, clockMs, h.budget, h.scope)
                    h.stillWanted = hooks.first
                    val (res, outcome) = assertNotNull(h.askAndFinish(query), "$label: shown must admit")
                    if (expectedReason == null) {
                        assertIs<AIReplyResult.Reply>(res, "$label")
                        assertIs<AIQueryDesk.Outcome.Reply>(outcome, "$label")
                    } else {
                        assertEquals(expectedReason, assertIs<AIReplyResult.NoReply>(res, "$label").reason, "$label")
                    }
                    assertEquals(if (expectBudgetKept) 1 else 0, h.budget.usedCount(clockMs.get()), "$label: budget")
                    if (expectWireSent) {
                        assertTrue(wireCounter() >= 1, "$label: expected >= 1 wire request, got ${wireCounter()}")
                    } else {
                        assertEquals(0, wireCounter(), "$label: expected 0 wire requests")
                    }

                    if (expectedReason != null && expectedReason.askAgain) {
                        assertIs<AIQueryDesk.Outcome.Failed>(outcome, "$label: attempt 1")
                        if (!expectAttemptUsed) {
                            repeat(3) { extra ->
                                val (_, extraOut) = assertNotNull(h.askAndFinish(query), "$label extra $extra")
                                assertIs<AIQueryDesk.Outcome.Failed>(extraOut, "$label: unsent call must not retire")
                            }
                            assertEquals(0, h.budget.usedCount(clockMs.get()), "$label: unsent calls return slots")
                        } else {
                            assertIs<AIQueryDesk.Outcome.Failed>(assertNotNull(h.askAndFinish(query)).second, "$label: attempt 2")
                            assertIs<AIQueryDesk.Outcome.Retired>(assertNotNull(h.askAndFinish(query)).second, "$label: attempt 3 retires")
                            assertEquals(3, h.budget.usedCount(clockMs.get()), "$label: 3 sent attempts = 3 slots")
                        }
                    } else if (expectedReason != null) {
                        assertIs<AIQueryDesk.Outcome.Retired>(outcome, "$label: non-retryable retires")
                    }
                }
            }
        }

        fun std(
            api: FakeMessagesApi, clk: AtomicLong, b: AIHourlyBudget, scope: CoroutineScope,
            q: String = "ravegpt q", apiKey: String = "test-key", baseUrl: String = api.baseUrl,
            timeout: Duration = Duration.ofSeconds(5), minSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
            fit: (String) -> String = AIReplyLimits::fitToSign, wanted: () -> Boolean = { true },
            wireCount: () -> Int = { api.received.size },
        ) = Triple(anthropicSource(baseUrl, b, clk, scope, apiKey, requestTimeout = timeout, minTimeToSend = minSend, fit = fit), Query(q), wanted to wireCount)

        // Reply & all 22 NoReplyReason values:
        verifyOutcome("Reply", null, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, okAnthropicBody()); std(api, clk, b, s) }
        verifyOutcome("MISSING_KEY", NoReplyReason.MISSING_KEY, false, false, false) { api, clk, b, s -> std(api, clk, b, s, apiKey = "") }
        verifyOutcome("NOTHING_TO_ASK", NoReplyReason.NOTHING_TO_ASK, false, false, false) { api, clk, b, s -> std(api, clk, b, s, q = "   ") }
        verifyOutcome("NOT_SET_UP", NoReplyReason.NOT_SET_UP, false, false, false) { api, _, _, _ -> Triple(NoAIReplies("no setup"), Query("ravegpt"), ({ true }) to ({ api.received.size })) }
        verifyOutcome("WITHDRAWN", NoReplyReason.WITHDRAWN, false, false, false) { api, clk, b, s -> std(api, clk, b, s, wanted = { false }) }
        verifyOutcome("TOO_LATE_TO_SEND", NoReplyReason.TOO_LATE_TO_SEND, false, false, false) { api, clk, b, s -> std(api, clk, b, s, wanted = { clk.addAndGet(90_000L); true }) }
        verifyOutcome("REFUSED", NoReplyReason.REFUSED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "refusal")); std(api, clk, b, s) }
        verifyOutcome("EMPTY", NoReplyReason.EMPTY, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"   "}]""")); std(api, clk, b, s) }
        verifyOutcome("TRUNCATED", NoReplyReason.TRUNCATED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"hi"}]""", stopReason = "max_tokens")); std(api, clk, b, s) }
        verifyOutcome("UNEXPECTED_RESPONSE", NoReplyReason.UNEXPECTED_RESPONSE, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "tool_use")); std(api, clk, b, s) }
        verifyOutcome("FAILED_AFTER_ANSWER", NoReplyReason.FAILED_AFTER_ANSWER, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, okAnthropicBody()); std(api, clk, b, s, fit = { throw IllegalStateException("fit") }) }
        verifyOutcome("NOT_A_MESSAGE", NoReplyReason.NOT_A_MESSAGE, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, "<html>portal</html>"); std(api, clk, b, s) }
        verifyOutcome("TIMEOUT", NoReplyReason.TIMEOUT, true, true, true) { api, clk, b, s ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody(), delayMs = 2_000)
            std(api, clk, b, s, baseUrl = api.baseUrl.replace("localhost", "127.0.0.1"), timeout = Duration.ofMillis(400), minSend = Duration.ofMillis(100))
        }
        verifyOutcome("RATE_LIMITED", NoReplyReason.RATE_LIMITED, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "429")); std(api, clk, b, s) }
        verifyOutcome("SERVER_ERROR", NoReplyReason.SERVER_ERROR, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(529, FakeMessagesApi.error("overloaded_error", "529")); std(api, clk, b, s) }
        verifyOutcome("KEY_REJECTED", NoReplyReason.KEY_REJECTED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(401, FakeMessagesApi.error("authentication_error", "401")); std(api, clk, b, s) }
        verifyOutcome("REQUEST_REJECTED", NoReplyReason.REQUEST_REJECTED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(400, FakeMessagesApi.error("invalid_request_error", "400")); std(api, clk, b, s) }
        verifyOutcome("PROVIDER_ERROR", NoReplyReason.PROVIDER_ERROR, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(402, FakeMessagesApi.error("billing_error", "402")); std(api, clk, b, s) }

        // NETWORK after wire send (explicit raw-socket counter, no bypass) & before wire send (refused port):
        val rstHits = AtomicInteger(0)
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { rst ->
            val t = Thread {
                while (!rst.isClosed) {
                    val sock = runCatching { rst.accept() }.getOrNull() ?: break
                    rstHits.incrementAndGet()
                    runCatching { sock.soTimeout = 1_000; sock.getInputStream().read(ByteArray(4096)); sock.setSoLinger(true, 0) }
                    runCatching { sock.close() }
                }
            }.apply { isDaemon = true; start() }
            try {
                verifyOutcome("NETWORK (post-wire RST)", NoReplyReason.NETWORK, true, true, true) { api, clk, b, s ->
                    std(api, clk, b, s, baseUrl = "http://127.0.0.1:${rst.localPort}", wireCount = rstHits::get)
                }
            } finally {
                rst.close()
                t.join(2_000)
            }
        }
        val unusedPort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        verifyOutcome("NETWORK (pre-wire refused)", NoReplyReason.NETWORK, false, false, false) { api, clk, b, s ->
            std(api, clk, b, s, baseUrl = "http://127.0.0.1:$unusedPort")
        }

        // Pre-wire TLS/cert failures and pre/post-wire UNEXPECTED_FAILURE:
        for (tls in listOf(NoReplyReason.CERTIFICATE_CLOCK, NoReplyReason.CERTIFICATE_UNTRUSTED, NoReplyReason.TLS)) {
            verifyOutcome(tls.name, tls, false, false, false) { _, clk, b, s ->
                val ex: Throwable = when (tls) {
                    NoReplyReason.CERTIFICATE_CLOCK -> CertificateExpiredException("exp")
                    NoReplyReason.CERTIFICATE_UNTRUSTED -> SSLPeerUnverifiedException("untrusted")
                    else -> SSLException("tls")
                }
                val gen = object : AIReplyGenerator {
                    override val name = "tls"
                    override suspend fun generateReply(request: AIReplyRequest) = AIReplyResult.NoReply(AnthropicReplyGenerator.reasonForIoFailure(ex), tls.name)
                }
                val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clk.get()) })
                Triple(AIReplyRunner(reply = { t, d, w, r -> svc.replyTo(t, d, w, r) }, nanoTime = { clk.get() * 1_000_000L }, scope = s, hourlyBudget = b), Query("ravegpt $tls"), ({ true }) to ({ 0 }))
            }
        }
        verifyOutcome("UNEXPECTED_FAILURE (pre-wire)", NoReplyReason.UNEXPECTED_FAILURE, false, false, false) { _, clk, b, s ->
            val gen = object : AIReplyGenerator {
                override val name = "pre-boom"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult = throw IllegalStateException("prep")
            }
            val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clk.get()) })
            Triple(AIReplyRunner(reply = { t, d, w, r -> svc.replyTo(t, d, w, r) }, nanoTime = { clk.get() * 1_000_000L }, scope = s, hourlyBudget = b), Query("ravegpt pre"), ({ true }) to ({ 0 }))
        }
        val postBoomHits = AtomicInteger(0)
        verifyOutcome("UNEXPECTED_FAILURE (post-wire)", NoReplyReason.UNEXPECTED_FAILURE, true, true, true) { _, clk, b, s ->
            val gen = object : AIReplyGenerator {
                override val name = "post-boom"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                    assertTrue(request.reservation!!.stamp())
                    postBoomHits.incrementAndGet()
                    throw IllegalStateException("post")
                }
            }
            val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clk.get()) })
            Triple(AIReplyRunner(reply = { t, d, w, r -> svc.replyTo(t, d, w, r) }, nanoTime = { clk.get() * 1_000_000L }, scope = s, hourlyBudget = b), Query("ravegpt post"), ({ true }) to postBoomHits::get)
        }

        assertEquals(NoReplyReason.values().toSet(), testedReasons, "Missing NoReplyReason values from table")
    }

    // ---- 6. Production wiring: MessageManager.desk & AIReplyGenerators share ONE AIHourlyBudget

    @Test
    fun `production wiring shares one AIHourlyBudget between MessageManager desk and provider generators`() {
        val dir = Files.createTempDirectory("es6wb-wiring").toFile()
        val context: Context = object : ContextWrapper(null) { override fun getFilesDir(): File = dir }
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()

        MockWebServer().use { server ->
            server.dispatcher = replayDispatcher(clockMs, postTimes)
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = MessageRepository(saver = Executor { })
            val owner = OwnerQueue()
            val completed = AtomicInteger(0)
            val sharedBudget = AIHourlyBudget(45) { clockMs.get() }
            val runner = anthropicSource(baseUrl, sharedBudget, clockMs, scope)
            val wiredSource = object : AIReplySource {
                override val hourlyBudget = sharedBudget
                override fun warmUp(onDone: () -> Unit) = false
                override fun ask(text: String, stillWanted: () -> Boolean, reservation: AIHourlyBudget.Reservation?, onResult: (AIReplyResult) -> Unit) {
                    runner.ask(text, stillWanted, reservation) { r -> onResult(r); completed.incrementAndGet() }
                }
            }

            stopKoin()
            startKoin { modules(module { single { repo }; single { owner }; single<AIReplySource> { wiredSource } }) }
            try {
                val manager = MessageManager(context, repo, owner, nowMs = { clockMs.get() })
                while (owner.drain { throw it } > 0) Unit
                val desk = MessageManager::class.java.getDeclaredField("desk").apply { isAccessible = true }.get(manager) as AIQueryDesk<*>
                assertSame(sharedBudget, desk.hourlyBudget)

                repeat(26) { idx ->
                    val before = completed.get()
                    manager.processNewUserMessage("ravegpt mm-$idx")
                    while (owner.drain { throw it } > 0) Unit
                    val q = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
                    assertTrue((1..20).any { manager.getNextMessage() === q })
                    if (repo.oneTimeMessages.any { it is Message.ColorMessage.AIThinking }) {
                        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (completed.get() == before && System.nanoTime() < until) {
                            while (owner.drain { throw it } > 0) Unit
                            Thread.sleep(5)
                        }
                        assertEquals(before + 1, completed.get())
                        while (owner.drain { throw it } > 0) Unit
                    }
                    clockMs.addAndGet(1_000L)
                }
                assertEquals(45, postTimes.size)
                assertEquals(45, sharedBudget.usedCount())

                // Also verify AIReplyGenerators.forProvider wires the shared budget for BOTH providers:
                // with sharedBudget already at 45/45, neither Anthropic nor OpenAI may send a 46th POST.
                for (provider in listOf(AIProvider.ANTHROPIC, AIProvider.OPENAI)) {
                    val beforeWire = postTimes.size
                    val gen = AIReplyGenerators.forProvider(
                        provider = provider, anthropicApiKey = "test-key", openAiApiKey = "test-key",
                        effort = { AIEffort.HIGH }, log = {}, budget = sharedBudget,
                        anthropicBaseUrl = baseUrl, openAiBaseUrl = "$baseUrl/v1/",
                    )
                    val res = runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
                    assertEquals(NoReplyReason.WITHDRAWN, assertIs<AIReplyResult.NoReply>(res).reason, "$provider must obey shared budget")
                    assertEquals(beforeWire, postTimes.size, "$provider must send 0 wire POSTs when shared budget is full")
                }
            } finally {
                scope.cancel()
                stopKoin()
                dir.deleteRecursively()
            }
        }
    }
}
