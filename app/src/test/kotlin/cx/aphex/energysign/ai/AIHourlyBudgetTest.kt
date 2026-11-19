package cx.aphex.energysign.ai

import android.content.Context
import android.content.ContextWrapper
import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.core.http.HttpResponse
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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.cert.CertificateExpiredException
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AIHourlyBudgetTest {

    private class Query(val text: String)

    /** One shared rig for desk + runner + service + generator scenarios. */
    private class DeskRig(
        val clockMs: AtomicLong = AtomicLong(1_000_000L),
        callsPerHour: Int = AIHourlyBudget.DEFAULT_CALLS_PER_HOUR,
        coolDownsMs: List<Long> = listOf(0),
        sourceFactory: (AIHourlyBudget, AtomicLong, CoroutineScope) -> AIReplySource,
    ) : Closeable {
        val budget = AIHourlyBudget(nowMs = clockMs::get, callsPerHour = callsPerHour)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source: AIReplySource = sourceFactory(budget, clockMs, scope)
        val listed: MutableSet<Query> = Collections.newSetFromMap(IdentityHashMap())
        var stillWanted: () -> Boolean = { true }
        private val pending = AtomicReference<Pair<CountDownLatch, AtomicReference<AIReplyResult>>?>()

        val desk = AIQueryDesk<Query>(
            start = { q, reservation ->
                val pair = CountDownLatch(1) to AtomicReference<AIReplyResult>()
                pending.set(pair)
                source.ask(q.text, stillWanted = { stillWanted() }, reservation = reservation) { res ->
                    pair.second.set(res); pair.first.countDown()
                }
            },
            listedAmong = { qs -> qs.filterTo(Collections.newSetFromMap(IdentityHashMap())) { it in listed } },
            nowMs = clockMs::get,
            limits = AIQueryDesk.Limits(coolDownsMs = coolDownsMs),
            budget = budget,
        )

        fun askAndFinish(query: Query, removeBeforeFinish: Boolean = false): Pair<AIReplyResult, AIQueryDesk.Outcome>? {
            listed.add(query)
            if (!desk.shown(query, clockMs.get())) return null
            val (latch, box) = assertNotNull(pending.getAndSet(null), "start was not called")
            assertTrue(latch.await(15, TimeUnit.SECONDS), "call did not finish in time")
            if (removeBeforeFinish) listed.remove(query)
            val result = assertNotNull(box.get())
            return result to desk.finished(query, result, clockMs.get())
        }

        override fun close() = scope.cancel()
    }

    private fun runnerFor(
        gen: AIReplyGenerator,
        clockMs: AtomicLong,
        scope: CoroutineScope,
        fit: (String) -> String = AIReplyLimits::fitToSign,
    ): AIReplyRunner {
        val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clockMs.get()) }, fit = fit)
        return AIReplyRunner(reply = { t, d, w, r -> svc.replyTo(t, d, w, r) }, nanoTime = { clockMs.get() * 1_000_000L }, scope = scope)
    }

    private fun anthropicSource(
        baseUrl: String, budget: AIHourlyBudget, clockMs: AtomicLong, scope: CoroutineScope,
        apiKey: String = "test-key", requestTimeout: Duration = Duration.ofSeconds(10),
        minTimeToSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND, fit: (String) -> String = AIReplyLimits::fitToSign,
    ) = runnerFor(
        AnthropicReplyGenerator(apiKey, log = {}, baseUrl = baseUrl, requestTimeout = requestTimeout, minTimeToSend = minTimeToSend, budget = budget, nanoTime = { clockMs.get() * 1_000_000L }),
        clockMs, scope, fit,
    )

    private fun openAiSource(baseUrl: String, budget: AIHourlyBudget, clockMs: AtomicLong, scope: CoroutineScope) =
        runnerFor(OpenAIReplyGenerator("test-key", log = {}, baseUrl = "$baseUrl/v1/", requestTimeout = Duration.ofSeconds(10), budget = budget), clockMs, scope)

    private fun withMockServer(onRequest: (RecordedRequest) -> MockResponse, block: (MockWebServer, String) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = onRequest(request) }
            server.start()
            block(server, server.url("/").toString().trimEnd('/').replace("localhost", "127.0.0.1"))
        }
    }

    private fun okAnthropicBody(text: String = "ok") = FakeMessagesApi.message("""[{"type":"text","text":"$text"}]""")

    private fun okOpenAiSse(text: String = "ok") =
        "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5.1\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun maxInAnyHour(times: List<Long>): Int {
        val sorted = times.sorted()
        var best = 0; var left = 0
        for (right in sorted.indices) {
            while (sorted[right] - sorted[left] >= AIHourlyBudget.HOUR_MS) left++
            best = maxOf(best, right - left + 1)
        }
        return best
    }

    // ---- 1. Budget lifecycle (Test #2)

    @Test
    fun `budget place is held, stamped once, counted for one hour from stamp, or returned and poisoned`() {
        var now = 1_000L
        val budget = AIHourlyBudget(nowMs = { now }, callsPerHour = 3)
        val r1 = assertNotNull(budget.reserve())
        val r2 = assertNotNull(budget.reserve())
        val r3 = assertNotNull(budget.reserve())
        assertFalse(budget.hasRoom())
        assertNull(budget.reserve(), "ceiling holds while places are reserved")

        // Hour runs from stamp (6_000), not reserve (1_000); cannot stamp twice.
        now = 6_000L
        assertTrue(r1.stamp())
        assertFalse(r1.stamp())
        assertTrue(r1.release())
        assertEquals(3, budget.usedCount())

        // Unstamped r2 returned and poisoned against late use.
        assertFalse(r2.release())
        assertFalse(r2.release())
        assertFalse(r2.stamp())
        assertEquals(2, budget.usedCount())

        val r4 = assertNotNull(budget.reserve())
        assertTrue(r3.stamp()); assertTrue(r4.stamp())
        assertFalse(budget.hasRoom())

        now = 1_000L + AIHourlyBudget.HOUR_MS; assertFalse(budget.hasRoom(), "must not expire 1 h from reserve")
        now = 6_000L + AIHourlyBudget.HOUR_MS - 1; assertFalse(budget.hasRoom(), "must not expire 1 ms early")
        now = 6_000L + AIHourlyBudget.HOUR_MS; assertTrue(budget.hasRoom()); assertEquals(0, budget.usedCount())
    }

    // ---- 2. Wire gate at the network layer + desk launch rollback (Test #3)

    @Test
    fun `wire gate enforces deadline, cancellation, pre-write shutdowns, tags, free GETs, and launch rollback`() {
        withMockServer({ MockResponse().setResponseCode(200).setBody("ok") }) { server, baseUrl ->
            val url = "$baseUrl/v1/messages"
            val modelUrl = "$baseUrl/v1/models/test"
            var nowMs = 10_000L
            val budget = AIHourlyBudget(nowMs = { nowMs }, callsPerHour = 10)
            val okDeadline = Deadline.after(Duration.ofSeconds(30)) { nowMs * 1_000_000L }

            fun client(afterGate: Interceptor? = null) = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor(WireBudgetInterceptor())
                .apply { afterGate?.let(::addNetworkInterceptor) }
                .build()

            fun post(ctx: WireCallContext?) = Request.Builder().url(url)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .apply { ctx?.let { tag(WireCallContext::class.java, it) } }.build()

            // 1. Paid POST leaves and stamps.
            val r1 = assertNotNull(budget.reserve())
            client().newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), r1))).execute().close()
            assertEquals(1, server.requestCount); assertTrue(r1.release()); assertEquals(1, budget.usedCount())

            // 2. 14 s left on first request at the wire (C3.2): refused TOO_LATE_TO_SEND, 0 wire, place stays held.
            val rLate = assertNotNull(budget.reserve())
            val lateDeadline = Deadline.after(Duration.ofSeconds(14)) { nowMs * 1_000_000L }
            val lateErr = runCatching { client().newCall(post(WireCallContext(lateDeadline, Duration.ofSeconds(15), rLate))).execute().close() }.exceptionOrNull()
            assertEquals(NoReplyReason.TOO_LATE_TO_SEND, assertNotNull(lateErr?.let(WireSendBlockedException::findIn)).reason)
            assertEquals(1, server.requestCount); assertTrue(rLate.isReserved()); assertFalse(rLate.release()); assertEquals(1, budget.usedCount())

            // 3. Pre-cancelled call: IOException, 0 wire, place returned.
            val rCancel = assertNotNull(budget.reserve())
            val cancelCall = client().newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rCancel))).apply { cancel() }
            assertIs<IOException>(runCatching { cancelCall.execute().close() }.exceptionOrNull())
            assertEquals(1, server.requestCount); assertFalse(rCancel.release()); assertEquals(1, budget.usedCount())

            // 4. Pre-write shutdowns (Major 1): ConnectionShutdownException & StreamResetException(REFUSED_STREAM) leave place held.
            for (preWriteEx in listOf(ConnectionShutdownException(), StreamResetException(ErrorCode.REFUSED_STREAM))) {
                val rPre = assertNotNull(budget.reserve())
                assertNotNull(runCatching { client(Interceptor { throw preWriteEx }).newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rPre))).execute().close() }.exceptionOrNull())
                assertEquals(1, server.requestCount); assertTrue(rPre.isReserved()); assertFalse(rPre.release()); assertEquals(1, budget.usedCount())
            }

            // 5. HTTP/2 REFUSED_STREAM with retry ON (Class 1 member 3): gate runs twice, 1 place spent (not 2).
            MockWebServer().use { h2 ->
                h2.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
                h2.enqueue(MockResponse().setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START).setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode))
                h2.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
                h2.start()
                val rH2 = assertNotNull(budget.reserve())
                val entries = AtomicInteger(0)
                val h2Client = OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).retryOnConnectionFailure(true)
                    .addNetworkInterceptor { c -> entries.incrementAndGet(); WireBudgetInterceptor().intercept(c) }.build()
                val h2Req = Request.Builder().url(h2.url("/v1/messages")).post("{}".toRequestBody("application/json".toMediaType()))
                    .tag(WireCallContext::class.java, WireCallContext(okDeadline, Duration.ofSeconds(15), rH2)).build()
                h2Client.newCall(h2Req).execute().use { assertEquals(200, it.code) }
                assertEquals(2, entries.get()); assertTrue(rH2.release()); assertEquals(2, budget.usedCount())
            }

            // 6. Post-write failures (CANCEL, generic IOException): stamped conservatively.
            for (postEx in listOf(StreamResetException(ErrorCode.CANCEL), IOException("broken pipe"))) {
                val usedBefore = budget.usedCount()
                val rPost = assertNotNull(budget.reserve())
                assertNotNull(runCatching { client(Interceptor { throw postEx }).newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rPost))).execute().close() }.exceptionOrNull())
                assertTrue(rPost.release()); assertEquals(usedBefore + 1, budget.usedCount())
            }

            // 7a. Deterministic Finding #1 (release()-beats-stamp() race during in-flight proceed, as in OpenAI withTimeoutOrNull):
            // Calling release() while chain.proceed() is in-flight MUST stamp & keep the send counted (never drop to 0).
            run {
                val raceBudget = AIHourlyBudget(nowMs = { nowMs }, callsPerHour = 10)
                val rRace = assertNotNull(raceBudget.reserve())
                val inProceed = CountDownLatch(1)
                val allowExit = CountDownLatch(1)
                val raceClient = client(Interceptor { c ->
                    inProceed.countDown()
                    assertTrue(allowExit.await(5, TimeUnit.SECONDS))
                    c.proceed(c.request())
                })
                val bg = Thread {
                    runCatching { raceClient.newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rRace))).execute().close() }
                }.apply { isDaemon = true; start() }
                assertTrue(inProceed.await(5, TimeUnit.SECONDS), "must enter chain.proceed")
                val kept = rRace.release()
                allowExit.countDown()
                bg.join(5_000)
                assertTrue(kept, "release() racing ahead of stamp() during in-flight proceed must keep send counted")
                assertEquals(1, raceBudget.usedCount(), "in-flight send cannot be dropped from hourly budget")
            }

            // 7b. OpenAI wire gate: pre-headers withTimeoutOrNull keeps sent POST counted,
            // and wire-layer TOO_LATE_TO_SEND maps cleanly through Ktor/OpenAIReplyGenerator with 0 wire & 0 budget.
            val slowHits = AtomicInteger(0)
            withMockServer({
                slowHits.incrementAndGet()
                MockResponse().setResponseCode(200).setHeadersDelay(3_000, TimeUnit.MILLISECONDS).setBody(okOpenAiSse())
            }) { _, slowUrl ->
                val oaBudget = AIHourlyBudget(nowMs = { nowMs }, callsPerHour = 10)
                val oaGen = OpenAIReplyGenerator("test-key", log = {}, baseUrl = "$slowUrl/v1/", requestTimeout = Duration.ofMillis(400), minTimeToSend = Duration.ofMillis(100), budget = oaBudget)
                val timeoutRes = runBlocking { oaGen.generateReply(AIReplyRequest("s", "u", Deadline.after(Duration.ofSeconds(10)))) }
                assertEquals(NoReplyReason.TIMEOUT, assertIs<AIReplyResult.NoReply>(timeoutRes).reason)
                assertEquals(1, slowHits.get(), "OpenAI POST reached server before timeout")
                assertEquals(1, oaBudget.usedCount(), "OpenAI pre-headers timeout after wire POST must keep budget place counted")

                // OpenAI wire-layer TOO_LATE_TO_SEND: first read (pre-call) = 20 s left; third read (WireBudgetInterceptor) = 14 s left.
                val reads = AtomicInteger(0)
                val steppingDeadline = Deadline.after(Duration.ofSeconds(20)) { if (reads.incrementAndGet() <= 2) 0L else 6_000_000_000L }
                val lateOaGen = OpenAIReplyGenerator("test-key", log = {}, baseUrl = "$slowUrl/v1/", budget = oaBudget)
                val lateOaRes = runBlocking { lateOaGen.generateReply(AIReplyRequest("s", "u", steppingDeadline)) }
                val lateNoReply = assertIs<AIReplyResult.NoReply>(lateOaRes)
                assertEquals(NoReplyReason.TOO_LATE_TO_SEND, lateNoReply.reason, "got $lateNoReply (reads=${reads.get()}, slowHits=${slowHits.get()})")
                assertEquals(1, slowHits.get(), "OpenAI wire-layer TOO_LATE_TO_SEND must send 0 additional POSTs")
                assertEquals(1, oaBudget.usedCount(), "OpenAI wire-layer TOO_LATE_TO_SEND must return its budget place")
            }

            // 7c. Reused stamped reservation -> PROVIDER_ERROR; untagged / free-tag POST -> WITHDRAWN; 0 wire.
            val reqBeforeBad = server.requestCount
            val reusedErr = runCatching { client().newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), r1))).execute().close() }.exceptionOrNull()
            assertEquals(NoReplyReason.PROVIDER_ERROR, assertNotNull(reusedErr?.let(WireSendBlockedException::findIn)).reason)
            for (badCtx in listOf(null, WireCallContext(okDeadline, Duration.ofSeconds(1), null))) {
                val err = runCatching { client().newCall(post(badCtx)).execute().close() }.exceptionOrNull()
                assertEquals(NoReplyReason.WITHDRAWN, assertNotNull(err?.let(WireSendBlockedException::findIn)).reason)
            }
            assertEquals(reqBeforeBad, server.requestCount)

            // 8. Free GET passes with 0 budget; expired free GET is refused TIMEOUT with 0 wire.
            val usedBeforeFree = budget.usedCount()
            client().newCall(Request.Builder().url(modelUrl).get().tag(WireCallContext::class.java, WireCallContext(okDeadline, Duration.ofSeconds(1), null)).build()).execute().close()
            assertEquals(reqBeforeBad + 1, server.requestCount); assertEquals(usedBeforeFree, budget.usedCount())
            val expiredErr = runCatching {
                client().newCall(Request.Builder().url(modelUrl).get().tag(WireCallContext::class.java, WireCallContext(Deadline.after(Duration.ZERO) { nowMs * 1_000_000L }, Duration.ofSeconds(1), null)).build()).execute().close()
            }.exceptionOrNull()
            assertEquals(NoReplyReason.TIMEOUT, assertNotNull(expiredErr?.let(WireSendBlockedException::findIn)).reason)
            assertEquals(reqBeforeBad + 1, server.requestCount)

            // 9. Synchronous throw from AIQueryDesk.start() rolls back reservation and thinking notice.
            val launchBudget = AIHourlyBudget(nowMs = { nowMs }, callsPerHour = 1)
            var shouldThrow = true
            val launched = mutableListOf<Query>()
            val desk = AIQueryDesk<Query>(start = { q, _ -> if (shouldThrow) throw IllegalStateException("boom") else launched.add(q) }, listedAmong = { it }, nowMs = { nowMs }, budget = launchBudget)
            assertIs<IllegalStateException>(runCatching { desk.shown(Query("a"), nowMs) }.exceptionOrNull())
            assertEquals(0, launchBudget.usedCount()); assertFalse(desk.thinkingWanted(nowMs))
            shouldThrow = false
            assertTrue(desk.shown(Query("b"), nowMs)); assertEquals(1, launched.size)
        }
    }

    // ---- 3. Class 2: stale limits after body serialization & free GET client build (Tests #4 and #7)

    @Test
    fun `time limit is computed after body serialization and free GET client construction`() {
        withMockServer({ req ->
            if (req.method == "GET") {
                MockResponse().setResponseCode(200).addHeader("Date", "Wed, 18 Nov 2026 12:00:00 GMT")
                    .setBody("""{"id":"${AnthropicReplyGenerator.MODEL}","type":"model","display_name":"Claude","created_at":"2026-01-01T00:00:00Z"}""")
            } else {
                MockResponse().setResponseCode(200).setBody("ok")
            }
        }) { server, baseUrl ->
            var nowNanos = 0L
            var prepareAdvanceMs = 0L
            val activeCtx = AtomicReference<WireCallContext?>()
            val backend = object : Backend {
                override fun baseUrl() = baseUrl
                override fun prepareRequest(request: HttpRequest) = request.also { nowNanos += prepareAdvanceMs * 1_000_000L }
                override fun prepareResponse(response: HttpResponse) = response
                override fun authorizeRequest(request: HttpRequest) = request
                override fun close() {}
            }
            val transport = BudgetedOkHttpClient(Duration.ofSeconds(90), Duration.ofSeconds(10), backend, WireBudgetInterceptor(), activeCtx::get)
            try {
                fun slowBody(advanceMs: Long) = object : HttpRequestBody {
                    private val bytes = "{}".toByteArray()
                    override fun writeTo(outputStream: OutputStream) = outputStream.write(bytes)
                    override fun contentType() = "application/json"
                    override fun contentLength() = bytes.size.toLong().also { nowNanos += advanceMs * 1_000_000L }
                    override fun repeatable() = true
                    override fun close() {}
                }
                val budget = AIHourlyBudget(nowMs = { nowNanos / 1_000_000L }, callsPerHour = 10)

                // Paid POST: 4 s in body.contentLength() out of 20 s -> X-Stainless-Timeout == 16.
                val r1 = assertNotNull(budget.reserve())
                activeCtx.set(WireCallContext(Deadline.after(Duration.ofSeconds(20)) { nowNanos }, Duration.ofSeconds(15), r1))
                transport.execute(HttpRequest.builder().method(HttpMethod.POST).baseUrl(baseUrl).addPathSegment("v1").addPathSegment("messages").body(slowBody(4_000)).build(), RequestOptions.none()).close()
                val rec1 = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("16", rec1.getHeader("X-Stainless-Timeout")); assertEquals("16", rec1.getHeader("X-Stainless-Read-Timeout"))
                assertTrue(r1.release())

                // Paid POST: 6 s in body.contentLength() (14 s < 15 s) -> refused before connect, 0 wire.
                val r2 = assertNotNull(budget.reserve())
                activeCtx.set(WireCallContext(Deadline.after(Duration.ofSeconds(20)) { nowNanos }, Duration.ofSeconds(15), r2))
                val err2 = assertIs<AnthropicIoException>(runCatching {
                    transport.execute(HttpRequest.builder().method(HttpMethod.POST).baseUrl(baseUrl).addPathSegment("v1").addPathSegment("messages").body(slowBody(6_000)).build(), RequestOptions.none()).close()
                }.exceptionOrNull())
                assertEquals(NoReplyReason.TOO_LATE_TO_SEND, assertNotNull(WireSendBlockedException.findIn(err2)).reason)
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS)); assertFalse(r2.release())

                // Free GET: 3 s in backend.prepareRequest out of 5 s -> X-Stainless-Timeout == 2, 0 budget.
                prepareAdvanceMs = 3_000L
                val usedBeforeGet = budget.usedCount()
                activeCtx.set(WireCallContext(Deadline.after(Duration.ofSeconds(5)) { nowNanos }, Duration.ofSeconds(1), null))
                transport.execute(HttpRequest.builder().method(HttpMethod.GET).baseUrl(baseUrl).addPathSegment("v1").addPathSegment("models").addPathSegment("test").build(), RequestOptions.none()).close()
                assertEquals("2", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("X-Stainless-Timeout"))
                assertEquals(usedBeforeGet, budget.usedCount())

                // AnthropicReplyGenerator.syncClock(5 s) with stepping nanoTime (no production test hook).
                fun steppingClock(afterBuildMs: Long): () -> Long {
                    val reads = AtomicInteger(0)
                    return { if (reads.incrementAndGet() == 1) 0L else afterBuildMs * 1_000_000L }
                }
                val skipped = ServerClock(monotonicMs = { 0L })
                AnthropicReplyGenerator("test-key", log = {}, baseUrl = baseUrl, serverClock = skipped, budget = budget, nanoTime = steppingClock(4_500L)).syncClock(Duration.ofSeconds(5))
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS)); assertNull(skipped.now())

                val trimmed = ServerClock(monotonicMs = { 0L })
                AnthropicReplyGenerator("test-key", log = {}, baseUrl = baseUrl, serverClock = trimmed, budget = budget, nanoTime = steppingClock(3_000L)).syncClock(Duration.ofSeconds(5))
                val recSync = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("GET", recSync.method); assertEquals("2", recSync.getHeader("X-Stainless-Timeout"))
                assertNotNull(trimmed.now()); assertEquals(usedBeforeGet, budget.usedCount())
            } finally {
                transport.close()
            }
        }
    }

    // ---- 4. Exhaustive outcome table over Reply and all 22 NoReplyReason values (Test #8)

    @Test
    fun `every way a call can end obeys wire count, budget place, and attempt count`() {
        val testedReasons = mutableSetOf<NoReplyReason>()

        fun verifyRow(
            label: String, expectedReason: NoReplyReason?, expectWire: Boolean, expectBudget: Boolean, expectAttempt: Boolean,
            setup: (FakeMessagesApi, AtomicLong, AIHourlyBudget, CoroutineScope) -> Triple<AIReplySource, Query, Pair<() -> Boolean, () -> Int>>,
        ) {
            expectedReason?.let(testedReasons::add)
            FakeMessagesApi().use { api ->
                val clockMs = AtomicLong(1_000_000L)
                lateinit var query: Query
                var wanted: () -> Boolean = { true }
                var wireCounter: () -> Int = { api.received.size }
                DeskRig(clockMs = clockMs, callsPerHour = 10, coolDownsMs = listOf(0)) { b, clk, s ->
                    val (src, q, hooks) = setup(api, clk, b, s)
                    query = q; wanted = hooks.first; wireCounter = hooks.second
                    src
                }.use { rig ->
                    rig.stillWanted = wanted
                    val (res, outcome) = assertNotNull(rig.askAndFinish(query), "$label: admitted")
                    if (expectedReason == null) {
                        assertIs<AIReplyResult.Reply>(res, label); assertIs<AIQueryDesk.Outcome.Reply>(outcome, label)
                    } else {
                        assertEquals(expectedReason, assertIs<AIReplyResult.NoReply>(res, label).reason, label)
                    }
                    assertEquals(if (expectBudget) 1 else 0, rig.budget.usedCount(), "$label: budget")
                    assertEquals(if (expectWire) 1 else 0, wireCounter(), "$label: wire count")

                    if (expectedReason != null && expectedReason.askAgain) {
                        assertIs<AIQueryDesk.Outcome.Failed>(outcome, "$label: attempt 1")
                        if (!expectAttempt) {
                            repeat(3) { assertIs<AIQueryDesk.Outcome.Failed>(assertNotNull(rig.askAndFinish(query)).second, "$label: unsent does not retire") }
                            assertEquals(0, rig.budget.usedCount(), "$label: unsent returns places")
                            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("later"))
                            rig.stillWanted = { true }
                            assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(query)).second, "$label: later real call answers")
                            assertEquals(1, rig.budget.usedCount(), "$label: real call spends 1 place")
                        } else {
                            assertIs<AIQueryDesk.Outcome.Failed>(assertNotNull(rig.askAndFinish(query)).second, "$label: attempt 2")
                            assertIs<AIQueryDesk.Outcome.Retired>(assertNotNull(rig.askAndFinish(query)).second, "$label: attempt 3 retires")
                            assertEquals(if (expectBudget) 3 else 0, rig.budget.usedCount(), "$label: 3 attempts budget")
                        }
                    } else if (expectedReason != null) {
                        assertIs<AIQueryDesk.Outcome.Retired>(outcome, "$label: non-retryable retires")
                    }
                }
            }
        }

        fun std(
            api: FakeMessagesApi, clk: AtomicLong, b: AIHourlyBudget, s: CoroutineScope,
            q: String = "ravegpt q", apiKey: String = "test-key", baseUrl: String = api.baseUrl,
            timeout: Duration = Duration.ofSeconds(5), minSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
            fit: (String) -> String = AIReplyLimits::fitToSign, wanted: () -> Boolean = { true }, wireCount: () -> Int = { api.received.size },
        ) = Triple(anthropicSource(baseUrl, b, clk, s, apiKey, timeout, minSend, fit), Query(q), wanted to wireCount)

        // Reply & all 22 NoReplyReason values:
        verifyRow("Reply", null, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, okAnthropicBody()); std(api, clk, b, s) }
        verifyRow("MISSING_KEY", NoReplyReason.MISSING_KEY, false, false, false) { api, clk, b, s -> std(api, clk, b, s, apiKey = "") }
        verifyRow("NOTHING_TO_ASK", NoReplyReason.NOTHING_TO_ASK, false, false, false) { api, clk, b, s -> std(api, clk, b, s, q = "   ") }
        verifyRow("NOT_SET_UP", NoReplyReason.NOT_SET_UP, false, false, false) { api, _, _, _ -> Triple(NoAIReplies("no setup"), Query("ravegpt"), ({ true }) to ({ api.received.size })) }
        verifyRow("WITHDRAWN", NoReplyReason.WITHDRAWN, false, false, false) { api, clk, b, s -> std(api, clk, b, s, wanted = { false }) }
        verifyRow("TOO_LATE_TO_SEND", NoReplyReason.TOO_LATE_TO_SEND, false, false, false) { api, clk, b, s -> std(api, clk, b, s, wanted = { clk.addAndGet(90_000L); true }) }
        verifyRow("REFUSED", NoReplyReason.REFUSED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "refusal")); std(api, clk, b, s) }
        verifyRow("EMPTY", NoReplyReason.EMPTY, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"   "}]""")); std(api, clk, b, s) }
        verifyRow("TRUNCATED", NoReplyReason.TRUNCATED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"hi"}]""", stopReason = "max_tokens")); std(api, clk, b, s) }
        verifyRow("UNEXPECTED_RESPONSE", NoReplyReason.UNEXPECTED_RESPONSE, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "tool_use")); std(api, clk, b, s) }
        verifyRow("FAILED_AFTER_ANSWER", NoReplyReason.FAILED_AFTER_ANSWER, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, okAnthropicBody()); std(api, clk, b, s, fit = { throw IllegalStateException("fit") }) }
        verifyRow("NOT_A_MESSAGE", NoReplyReason.NOT_A_MESSAGE, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(200, "<html>portal</html>"); std(api, clk, b, s) }
        verifyRow("TIMEOUT", NoReplyReason.TIMEOUT, true, true, true) { api, clk, b, s ->
            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody(), delayMs = 2_000)
            std(api, clk, b, s, baseUrl = api.baseUrl.replace("localhost", "127.0.0.1"), timeout = Duration.ofMillis(400), minSend = Duration.ofMillis(100))
        }
        verifyRow("RATE_LIMITED", NoReplyReason.RATE_LIMITED, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "429")); std(api, clk, b, s) }
        verifyRow("SERVER_ERROR", NoReplyReason.SERVER_ERROR, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(529, FakeMessagesApi.error("overloaded_error", "529")); std(api, clk, b, s) }
        verifyRow("KEY_REJECTED", NoReplyReason.KEY_REJECTED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(401, FakeMessagesApi.error("authentication_error", "401")); std(api, clk, b, s) }
        verifyRow("REQUEST_REJECTED", NoReplyReason.REQUEST_REJECTED, true, true, false) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(400, FakeMessagesApi.error("invalid_request_error", "400")); std(api, clk, b, s) }
        verifyRow("PROVIDER_ERROR", NoReplyReason.PROVIDER_ERROR, true, true, true) { api, clk, b, s -> api.answer = FakeMessagesApi.Answer(402, FakeMessagesApi.error("billing_error", "402")); std(api, clk, b, s) }

        // NETWORK post-wire (raw TCP RST) & pre-wire (refused port; ConnectionShutdownException):
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
                verifyRow("NETWORK (post-wire RST)", NoReplyReason.NETWORK, true, true, true) { api, clk, b, s ->
                    std(api, clk, b, s, baseUrl = "http://127.0.0.1:${rst.localPort}", wireCount = rstHits::get)
                }
            } finally { rst.close(); t.join(2_000) }
        }
        val unusedPort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        verifyRow("NETWORK (pre-wire refused)", NoReplyReason.NETWORK, false, false, true) { api, clk, b, s -> std(api, clk, b, s, baseUrl = "http://127.0.0.1:$unusedPort") }
        verifyRow("NETWORK (pre-wire ConnectionShutdownException)", NoReplyReason.NETWORK, false, false, true) { api, clk, _, s ->
            val ok = OkHttpClient.Builder().retryOnConnectionFailure(false).addNetworkInterceptor(WireBudgetInterceptor()).addNetworkInterceptor(Interceptor { throw ConnectionShutdownException() }).build()
            val gen = object : AIReplyGenerator {
                override val name = "goaway"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                    val req = Request.Builder().url("${api.baseUrl}/v1/messages").post("{}".toRequestBody("application/json".toMediaType()))
                        .tag(WireCallContext::class.java, WireCallContext(request.deadline, AnthropicReplyGenerator.MIN_TIME_TO_SEND, request.reservation)).build()
                    val err = assertNotNull(runCatching { ok.newCall(req).execute().close() }.exceptionOrNull())
                    return AIReplyResult.NoReply(AnthropicReplyGenerator.reasonForIoFailure(err), err.javaClass.simpleName)
                }
            }
            Triple(runnerFor(gen, clk, s), Query("ravegpt goaway"), ({ true }) to ({ api.received.size }))
        }

        // Fake-generator rows (no real HTTPS/fault server): TLS/cert & pre/post UNEXPECTED_FAILURE.
        for ((tls, ex) in listOf(
            NoReplyReason.CERTIFICATE_CLOCK to CertificateExpiredException("exp"),
            NoReplyReason.CERTIFICATE_UNTRUSTED to SSLPeerUnverifiedException("untrusted"),
            NoReplyReason.TLS to SSLException("tls"),
        )) {
            verifyRow("$tls (fake gen)", tls, false, false, true) { _, clk, _, s ->
                val gen = object : AIReplyGenerator {
                    override val name = "tls"
                    override suspend fun generateReply(request: AIReplyRequest) = AIReplyResult.NoReply(AnthropicReplyGenerator.reasonForIoFailure(ex), tls.name)
                }
                Triple(runnerFor(gen, clk, s), Query("ravegpt $tls"), ({ true }) to ({ 0 }))
            }
        }
        verifyRow("UNEXPECTED_FAILURE (pre-wire fake gen)", NoReplyReason.UNEXPECTED_FAILURE, false, false, true) { _, clk, _, s ->
            val gen = object : AIReplyGenerator { override val name = "pre"; override suspend fun generateReply(request: AIReplyRequest): AIReplyResult = throw IllegalStateException("prep") }
            Triple(runnerFor(gen, clk, s), Query("ravegpt pre"), ({ true }) to ({ 0 }))
        }
        val postBoomHits = AtomicInteger(0)
        verifyRow("UNEXPECTED_FAILURE (post-wire fake gen)", NoReplyReason.UNEXPECTED_FAILURE, true, true, true) { _, clk, _, s ->
            val gen = object : AIReplyGenerator {
                override val name = "post"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                    assertTrue(request.reservation!!.stamp()); postBoomHits.incrementAndGet(); throw IllegalStateException("post")
                }
            }
            Triple(runnerFor(gen, clk, s), Query("ravegpt post"), ({ true }) to postBoomHits::get)
        }
        assertEquals(NoReplyReason.values().toSet(), testedReasons, "Missing NoReplyReason values from table")
    }

    // ---- 5. Rolling-hour 45 ceiling across providers, prep delays, and 2.5-hour mixed workload (Test #9)

    @Test
    fun `no rolling hour puts more than 45 requests on the wire across providers, prep delays, and mixed workloads`() {
        fun runScenario(
            onRequest: (RecordedRequest, Long, Int) -> MockResponse,
            openAi: Boolean = false,
            drive: (DeskRig, AtomicLong, List<Long>) -> Unit,
        ) {
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            val seq = AtomicInteger(0)
            withMockServer({ req -> postTimes.add(clockMs.get()); onRequest(req, clockMs.get(), seq.incrementAndGet()) }) { _, baseUrl ->
                DeskRig(clockMs = clockMs, coolDownsMs = listOf(0)) { b, clk, s ->
                    if (openAi) openAiSource(baseUrl, b, clk, s) else anthropicSource(baseUrl, b, clk, s)
                }.use { rig -> drive(rig, clockMs, postTimes) }
            }
        }

        // Scenario A: Anthropic 503 Retry-After: 0 (one-shot -> 1 POST per call, max 45).
        runScenario({ _, _, _ -> MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody(FakeMessagesApi.error("overloaded_error", "503")) }) { rig, clk, times ->
            repeat(50) { i ->
                val out = rig.askAndFinish(Query("ravegpt a-$i"))
                if (i < 45) assertEquals(NoReplyReason.SERVER_ERROR, assertIs<AIReplyResult.NoReply>(assertNotNull(out).first).reason) else assertNull(out)
                clk.addAndGet(1_000L)
            }
            assertEquals(45, times.size); assertEquals(45, maxInAnyHour(times))
        }

        // Scenario B: OpenAI 503 Retry-After: 0 & 408 (one-shot + retryOnConnectionFailure(false) -> 1 POST per call, max 45).
        runScenario(
            onRequest = { _, _, n ->
                if (n % 2 == 1) MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"error":{"message":"busy"}}""")
                else MockResponse().setResponseCode(408).setBody("""{"error":{"message":"timeout"}}""")
            },
            openAi = true,
        ) { rig, clk, times ->
            repeat(50) { i ->
                val out = rig.askAndFinish(Query("ravegpt o-$i"))
                if (i < 45) assertNotNull(out) else assertNull(out)
                clk.addAndGet(1_000L)
            }
            assertEquals(45, times.size); assertEquals(45, maxInAnyHour(times))
        }

        // Scenario C: 5 s prep delay on query #1 shifts its rolling-hour release by 5 s.
        runScenario({ _, _, _ -> MockResponse().setResponseCode(200).setBody(okAnthropicBody()) }) { rig, clk, times ->
            val t0 = clk.get()
            rig.stillWanted = { clk.addAndGet(5_000L); true }
            assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(Query("ravegpt prep-1"))).second)
            rig.stillWanted = { true }
            repeat(44) { i -> clk.addAndGet(1_000L); assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(Query("ravegpt p-${i + 2}"))).second) }
            assertEquals(45, times.size); assertEquals(t0 + 5_000L, times.first())

            clk.set(t0 + AIHourlyBudget.HOUR_MS); assertNull(rig.askAndFinish(Query("ravegpt early-1")))
            clk.set(t0 + 5_000L + AIHourlyBudget.HOUR_MS - 1); assertNull(rig.askAndFinish(Query("ravegpt early-2")))
            clk.set(t0 + 5_000L + AIHourlyBudget.HOUR_MS); assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(Query("ravegpt ok"))).second)
            assertEquals(46, times.size); assertEquals(45, maxInAnyHour(times))
        }

        // Scenario D: 2.5-hour mixed workload (200, 503 Retry-After: 0, 529, 5 s prep, withdrawn, too-late, deleted Gone).
        runScenario({ _, _, n ->
            when (n % 4) {
                0 -> MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody(FakeMessagesApi.error("overloaded_error", "503"))
                1 -> MockResponse().setResponseCode(529).setBody(FakeMessagesApi.error("overloaded_error", "529"))
                else -> MockResponse().setResponseCode(200).setBody(okAnthropicBody())
            }
        }) { rig, clk, times ->
            val endMs = clk.get() + 2 * AIHourlyBudget.HOUR_MS + AIHourlyBudget.HOUR_MS / 2
            var i = 0
            while (clk.get() < endMs) {
                val mode = i++ % 7
                rig.stillWanted = when (mode) {
                    0 -> { { false } }
                    1 -> { { clk.addAndGet(90_000L); true } }
                    2 -> { { clk.addAndGet(5_000L); true } }
                    else -> { { true } }
                }
                rig.askAndFinish(Query("ravegpt mix-$i"), removeBeforeFinish = (mode == 3))
                clk.addAndGet(50_000L)
            }
            assertTrue(times.size > 70, "expected > 70 POSTs over 2.5 h, got ${times.size}")
            assertTrue(maxInAnyHour(times) <= 45, "rolling hour exceeded 45: ${maxInAnyHour(times)}")
        }
    }

    // ---- 6. Production wiring: MessageManager.desk & AIReplyGenerators.forProvider (Test #10)

    @Test
    fun `through MessageManager and forProvider the same 45-request ceiling holds`() {
        val dir = Files.createTempDirectory("es6wb-wiring").toFile()
        val context: Context = object : ContextWrapper(null) { override fun getFilesDir(): File = dir }
        val clockMs = AtomicLong(1_000_000L)
        val postTimes = CopyOnWriteArrayList<Long>()
        val seq = AtomicInteger(0)

        withMockServer({ req ->
            postTimes.add(clockMs.get())
            when {
                req.path?.contains("chat/completions") == true -> MockResponse().setResponseCode(200).setBody(okOpenAiSse())
                seq.incrementAndGet() == 1 -> MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody(FakeMessagesApi.error("overloaded_error", "503"))
                else -> MockResponse().setResponseCode(200).setBody(okAnthropicBody())
            }
        }) { _, baseUrl ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = MessageRepository(saver = Executor { })
            val owner = OwnerQueue()
            val completed = AtomicInteger(0)
            val sharedBudget = AIHourlyBudget(nowMs = clockMs::get)
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
                val manager = MessageManager(context, repo, owner, nowMs = clockMs::get)
                while (owner.drain { throw it } > 0) Unit
                val desk = MessageManager::class.java.getDeclaredField("desk").apply { isAccessible = true }.get(manager) as AIQueryDesk<*>
                assertSame(sharedBudget, desk.hourlyBudget)

                repeat(48) { idx ->
                    val before = completed.get()
                    manager.processNewUserMessage("ravegpt mm-$idx")
                    while (owner.drain { throw it } > 0) Unit
                    val q = assertIs<Message.Marquee.AIQuery>(repo.marqueeMessages.first())
                    assertTrue((1..20).any { manager.getNextMessage() === q })
                    if (repo.oneTimeMessages.any { it is Message.ColorMessage.AIThinking }) {
                        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                        while (completed.get() == before && System.nanoTime() < until) {
                            while (owner.drain { throw it } > 0) Unit; Thread.sleep(5)
                        }
                        assertEquals(before + 1, completed.get())
                        while (owner.drain { throw it } > 0) Unit
                    }
                    clockMs.addAndGet(if (idx == 0) 35_000L else 1_000L)
                }
                assertEquals(45, postTimes.size); assertEquals(45, sharedBudget.usedCount())

                for (provider in listOf(AIProvider.ANTHROPIC, AIProvider.OPENAI)) {
                    val beforeWire = postTimes.size
                    val gen = AIReplyGenerators.forProvider(
                        provider, "test-key", "test-key", { AIEffort.HIGH }, log = {}, budget = sharedBudget,
                        anthropicBaseUrl = baseUrl, openAiBaseUrl = "$baseUrl/v1/",
                    )
                    val res = runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
                    assertEquals(NoReplyReason.WITHDRAWN, assertIs<AIReplyResult.NoReply>(res).reason, "$provider obeys shared budget")
                    assertEquals(beforeWire, postTimes.size, "$provider sends 0 wire POSTs at 45/45")
                }
            } finally {
                scope.cancel(); stopKoin(); dir.deleteRecursively()
            }
        }
    }
}
