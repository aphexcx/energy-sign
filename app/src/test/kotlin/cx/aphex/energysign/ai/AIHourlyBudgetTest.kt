package cx.aphex.energysign.ai

import android.content.Context
import android.content.ContextWrapper
import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
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
        maxAttempts: Int = 3,
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
                    pair.second.set(res)
                    pair.first.countDown()
                }
            },
            listedAmong = { qs -> qs.filterTo(Collections.newSetFromMap(IdentityHashMap())) { it in listed } },
            nowMs = clockMs::get,
            limits = AIQueryDesk.Limits(coolDownsMs = coolDownsMs, maxAttempts = maxAttempts),
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

        override fun close() {
            scope.cancel()
        }
    }

    private fun anthropicSource(
        baseUrl: String,
        budget: AIHourlyBudget,
        clockMs: AtomicLong,
        scope: CoroutineScope,
        apiKey: String = "test-key",
        requestTimeout: Duration = Duration.ofSeconds(10),
        minTimeToSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
        fit: (String) -> String = AIReplyLimits::fitToSign,
    ): AIReplyRunner {
        val gen = AnthropicReplyGenerator(
            apiKey = apiKey,
            log = {},
            baseUrl = baseUrl,
            requestTimeout = requestTimeout,
            minTimeToSend = minTimeToSend,
            budget = budget,
            nanoTime = { clockMs.get() * 1_000_000L },
        )
        val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clockMs.get()) }, fit = fit)
        return AIReplyRunner(
            reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
            nanoTime = { clockMs.get() * 1_000_000L },
            scope = scope,
        )
    }

    private fun openAiSource(
        baseUrl: String,
        budget: AIHourlyBudget,
        clockMs: AtomicLong,
        scope: CoroutineScope,
    ): AIReplyRunner {
        val gen = OpenAIReplyGenerator(
            apiKey = "test-key",
            log = {},
            baseUrl = "$baseUrl/v1/",
            requestTimeout = Duration.ofSeconds(10),
            budget = budget,
        )
        val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clockMs.get()) })
        return AIReplyRunner(
            reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
            nanoTime = { clockMs.get() * 1_000_000L },
            scope = scope,
        )
    }

    private fun fakeGeneratorSource(
        gen: AIReplyGenerator,
        clockMs: AtomicLong,
        scope: CoroutineScope,
    ): AIReplyRunner {
        val svc = AIReplyService(gen, log = {}, now = { Instant.ofEpochMilli(clockMs.get()) })
        return AIReplyRunner(
            reply = { t, d, w, r -> svc.replyTo(t, d, w, r) },
            nanoTime = { clockMs.get() * 1_000_000L },
            scope = scope,
        )
    }

    private fun okAnthropicBody(text: String = "ok"): String =
        FakeMessagesApi.message("""[{"type":"text","text":"$text"}]""")

    private fun okOpenAiSse(text: String = "ok"): String =
        "data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5.1\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    private fun maxInAnyHour(times: List<Long>): Int {
        val sorted = times.sorted()
        var best = 0
        var left = 0
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

        // Stamped 5 s after reserve: hour runs from stamp (6_000), not reserve (1_000).
        now = 6_000L
        assertTrue(r1.stamp())
        assertFalse(r1.stamp(), "same reservation cannot stamp twice")
        assertTrue(r1.release(), "release after stamp keeps the slot counted")
        assertEquals(3, budget.usedCount())

        // Unstamped r2 returned and poisoned against late use.
        assertFalse(r2.release())
        assertFalse(r2.release(), "second release is a no-op")
        assertFalse(r2.stamp(), "released reservation cannot be stamped")
        assertTrue(budget.hasRoom())
        assertEquals(2, budget.usedCount())

        // Refill slot freed by r2 and stamp both remaining reservations at t=6_000.
        val r4 = assertNotNull(budget.reserve())
        assertTrue(r3.stamp())
        assertTrue(r4.stamp())
        assertFalse(budget.hasRoom())

        // Still full at reserve + 1 h and at stamp + 1 h - 1 ms; opens at stamp + 1 h.
        now = 1_000L + AIHourlyBudget.HOUR_MS
        assertFalse(budget.hasRoom(), "must not expire one hour from reserve")
        now = 6_000L + AIHourlyBudget.HOUR_MS - 1
        assertFalse(budget.hasRoom(), "must not expire 1 ms before stamp + 1 h")
        now = 6_000L + AIHourlyBudget.HOUR_MS
        assertTrue(budget.hasRoom(), "must open exactly one hour after stamp")
        assertEquals(0, budget.usedCount())
    }

    // ---- 2. Wire gate at the network layer + desk launch failure (Test #3)

    @Test
    fun `wire gate enforces deadline, cancellation, pre-write shutdowns, tags, free GETs, and launch rollback`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("ok")
            }
            server.start()
            val url = server.url("/v1/messages").toString()
            val modelUrl = server.url("/v1/models/test").toString()

            fun client(retry: Boolean = false, afterGate: Interceptor? = null): OkHttpClient =
                OkHttpClient.Builder()
                    .retryOnConnectionFailure(retry)
                    .addNetworkInterceptor(WireBudgetInterceptor())
                    .apply { afterGate?.let(::addNetworkInterceptor) }
                    .build()

            fun post(ctx: WireCallContext?): Request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .apply { ctx?.let { tag(WireCallContext::class.java, it) } }
                .build()

            var nowMs = 10_000L
            val budget = AIHourlyBudget(nowMs = { nowMs }, callsPerHour = 10)
            val okDeadline = Deadline.after(Duration.ofSeconds(30)) { nowMs * 1_000_000L }

            // 1. Paid POST leaves and stamps.
            val r1 = assertNotNull(budget.reserve())
            val reqBefore1 = server.requestCount
            client().newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), r1))).execute().close()
            assertEquals(reqBefore1 + 1, server.requestCount)
            assertTrue(r1.release(), "sent request stays stamped")
            assertEquals(1, budget.usedCount())

            // 2. 14 s left on first request at the wire: refused TOO_LATE_TO_SEND, place still held.
            val rLate = assertNotNull(budget.reserve())
            val reqBeforeLate = server.requestCount
            val lateDeadline = Deadline.after(Duration.ofSeconds(14)) { nowMs * 1_000_000L }
            val lateErr = runCatching {
                client().newCall(post(WireCallContext(lateDeadline, Duration.ofSeconds(15), rLate))).execute().close()
            }.exceptionOrNull()
            val lateBlocked = assertNotNull(lateErr?.let(WireSendBlockedException::findIn))
            assertEquals(NoReplyReason.TOO_LATE_TO_SEND, lateBlocked.reason)
            assertEquals(reqBeforeLate, server.requestCount, "14 s left must not write bytes")
            assertTrue(rLate.isReserved(), "blocked send must leave place reserved")
            assertFalse(rLate.release(), "release must return unstamped place")
            assertEquals(1, budget.usedCount())

            // 3. Pre-cancelled call: IOException, 0 wire requests, place returned.
            val rCancel = assertNotNull(budget.reserve())
            val reqBeforeCancel = server.requestCount
            val cancelCall = client().newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rCancel)))
            cancelCall.cancel()
            assertIs<IOException>(runCatching { cancelCall.execute().close() }.exceptionOrNull())
            assertEquals(reqBeforeCancel, server.requestCount)
            assertFalse(rCancel.release())
            assertEquals(1, budget.usedCount())

            // 4. ConnectionShutdownException with retry OFF (Major 1): 0 wire, place stays held & returned.
            val rGoawayOff = assertNotNull(budget.reserve())
            val reqBeforeGoawayOff = server.requestCount
            val goawayClientOff = client(retry = false, afterGate = Interceptor { throw ConnectionShutdownException() })
            assertIs<ConnectionShutdownException>(
                runCatching { goawayClientOff.newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rGoawayOff))).execute().close() }.exceptionOrNull(),
            )
            assertEquals(reqBeforeGoawayOff, server.requestCount)
            assertTrue(rGoawayOff.isReserved(), "GOAWAY before write must not stamp")
            assertFalse(rGoawayOff.release(), "release must return place after GOAWAY")
            assertEquals(1, budget.usedCount())

            // 5. HTTP/2 REFUSED_STREAM with retry ON (Class 1 member 3): OkHttp retries automatically;
            // WireBudgetInterceptor runs twice (first leaves place RESERVED, second stamps 200) -> 1 place, not 2.
            MockWebServer().use { h2 ->
                h2.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
                h2.enqueue(MockResponse().setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START).setHttp2ErrorCode(ErrorCode.REFUSED_STREAM.httpCode))
                h2.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
                h2.start()
                val rH2Retry = assertNotNull(budget.reserve())
                val gateEntries = AtomicInteger(0)
                val h2Client = OkHttpClient.Builder()
                    .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                    .retryOnConnectionFailure(true)
                    .addNetworkInterceptor { chain ->
                        gateEntries.incrementAndGet()
                        WireBudgetInterceptor().intercept(chain)
                    }
                    .build()
                val h2Req = Request.Builder()
                    .url(h2.url("/v1/messages"))
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .tag(WireCallContext::class.java, WireCallContext(okDeadline, Duration.ofSeconds(15), rH2Retry))
                    .build()
                h2Client.newCall(h2Req).execute().use { resp ->
                    assertEquals(200, resp.code, "HTTP/2 REFUSED_STREAM retry must succeed with 200")
                }
                assertEquals(2, gateEntries.get(), "WireBudgetInterceptor must run on both initial REFUSED_STREAM and retry")
                assertTrue(rH2Retry.release(), "successful retry stamps the 1 place")
                assertEquals(2, budget.usedCount(), "retry after REFUSED_STREAM must spend 1 place, not 2")
            }

            // 6. StreamResetException(REFUSED_STREAM): pre-write -> place stays held & returned.
            val rRefused = assertNotNull(budget.reserve())
            val refusedClient = client(afterGate = Interceptor { throw StreamResetException(ErrorCode.REFUSED_STREAM) })
            assertIs<StreamResetException>(
                runCatching { refusedClient.newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rRefused))).execute().close() }.exceptionOrNull(),
            )
            assertFalse(rRefused.release(), "REFUSED_STREAM before write returns place")
            assertEquals(2, budget.usedCount())

            // 7. StreamResetException(CANCEL) and generic post-write IOException: stamped conservatively.
            for (postWriteEx in listOf(StreamResetException(ErrorCode.CANCEL), IOException("broken pipe"))) {
                val usedBefore = budget.usedCount()
                val rPost = assertNotNull(budget.reserve())
                val errClient = client(afterGate = Interceptor { throw postWriteEx })
                assertNotNull(runCatching { errClient.newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), rPost))).execute().close() }.exceptionOrNull())
                assertTrue(rPost.release(), "post-write ${postWriteEx.javaClass.simpleName} must stay stamped")
                assertEquals(usedBefore + 1, budget.usedCount())
            }

            // 8. Second send on an already-stamped reservation: refused WITHDRAWN, 0 wire requests.
            val reqBeforeReuse = server.requestCount
            val reuseErr = runCatching {
                client().newCall(post(WireCallContext(okDeadline, Duration.ofSeconds(15), r1))).execute().close()
            }.exceptionOrNull()
            assertEquals(NoReplyReason.WITHDRAWN, assertNotNull(reuseErr?.let(WireSendBlockedException::findIn)).reason)
            assertEquals(reqBeforeReuse, server.requestCount)

            // 9. Untagged POST and free tag on POST: both refused WITHDRAWN, 0 wire requests.
            for (badCtx in listOf(null, WireCallContext(okDeadline, Duration.ofSeconds(1), reservation = null))) {
                val reqBeforeBad = server.requestCount
                val err = runCatching { client().newCall(post(badCtx)).execute().close() }.exceptionOrNull()
                assertEquals(NoReplyReason.WITHDRAWN, assertNotNull(err?.let(WireSendBlockedException::findIn)).reason)
                assertEquals(reqBeforeBad, server.requestCount)
            }

            // 10. Free GET passes with 0 budget; expired free GET is refused TIMEOUT with 0 wire.
            val usedBeforeFree = budget.usedCount()
            val reqBeforeFree = server.requestCount
            val freeGet = Request.Builder().url(modelUrl).get()
                .tag(WireCallContext::class.java, WireCallContext(okDeadline, Duration.ofSeconds(1), null)).build()
            client().newCall(freeGet).execute().close()
            assertEquals(reqBeforeFree + 1, server.requestCount)
            assertEquals(usedBeforeFree, budget.usedCount(), "free GET must cost 0 budget")

            val expiredGet = Request.Builder().url(modelUrl).get()
                .tag(WireCallContext::class.java, WireCallContext(Deadline.after(Duration.ZERO) { nowMs * 1_000_000L }, Duration.ofSeconds(1), null)).build()
            val expErr = runCatching { client().newCall(expiredGet).execute().close() }.exceptionOrNull()
            assertEquals(NoReplyReason.TIMEOUT, assertNotNull(expErr?.let(WireSendBlockedException::findIn)).reason)
            assertEquals(reqBeforeFree + 1, server.requestCount)

            // 11. Synchronous throw from AIQueryDesk.start() rolls back reservation AND thinking notice.
            val launchBudget = AIHourlyBudget(nowMs = { nowMs }, callsPerHour = 1)
            var shouldThrow = true
            val launched = mutableListOf<Query>()
            val desk = AIQueryDesk<Query>(
                start = { q, _ -> if (shouldThrow) throw IllegalStateException("sync boom") else launched.add(q) },
                listedAmong = { it },
                nowMs = { nowMs },
                budget = launchBudget,
            )
            assertIs<IllegalStateException>(runCatching { desk.shown(Query("a"), nowMs) }.exceptionOrNull())
            assertEquals(0, launchBudget.usedCount(), "sync start() failure must release reservation")
            assertFalse(desk.thinkingWanted(nowMs), "sync start() failure must clear thinking notice")
            shouldThrow = false
            assertTrue(desk.shown(Query("b"), nowMs), "desk must stay usable after sync start() failure")
            assertEquals(1, launched.size)
        }
    }

    // ---- 3. Class 2: stale limits after body serialization & free GET client build (Tests #4 and #7)

    @Test
    fun `time limit is computed after body serialization and free GET client construction`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.method == "GET") {
                        MockResponse().setResponseCode(200)
                            .addHeader("Date", "Wed, 18 Nov 2026 12:00:00 GMT")
                            .setBody("""{"id":"${AnthropicReplyGenerator.MODEL}","type":"model","display_name":"Claude","created_at":"2026-01-01T00:00:00Z"}""")
                    } else {
                        MockResponse().setResponseCode(200).setBody("ok")
                    }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')

            var nowNanos = 0L
            val activeCtx = AtomicReference<WireCallContext?>()
            var prepareAdvanceMs = 0L
            val stubBackend = object : Backend {
                override fun baseUrl() = baseUrl
                override fun prepareRequest(request: HttpRequest): HttpRequest {
                    nowNanos += prepareAdvanceMs * 1_000_000L
                    return request
                }
                override fun prepareResponse(response: HttpResponse) = response
                override fun authorizeRequest(request: HttpRequest) = request
                override fun close() {}
            }
            val transport = BudgetedOkHttpClient(
                maxRequestTimeout = Duration.ofSeconds(90),
                connectTimeout = Duration.ofSeconds(10),
                backend = stubBackend,
                networkInterceptor = WireBudgetInterceptor(),
                currentContext = activeCtx::get,
            )
            try {
                fun slowBody(advanceMs: Long) = object : HttpRequestBody {
                    private val bytes = "{}".toByteArray()
                    override fun writeTo(outputStream: OutputStream) = outputStream.write(bytes)
                    override fun contentType() = "application/json"
                    override fun contentLength(): Long {
                        nowNanos += advanceMs * 1_000_000L
                        return bytes.size.toLong()
                    }
                    override fun repeatable() = true
                    override fun close() {}
                }

                val budget = AIHourlyBudget(nowMs = { nowNanos / 1_000_000L }, callsPerHour = 10)

                // Row 1: body.contentLength() takes 4 s of 20 s -> X-Stainless-Timeout == 16.
                val r1 = assertNotNull(budget.reserve())
                activeCtx.set(WireCallContext(Deadline.after(Duration.ofSeconds(20)) { nowNanos }, Duration.ofSeconds(15), r1))
                val postReq1 = HttpRequest.builder().method(HttpMethod.POST).baseUrl(baseUrl)
                    .addPathSegment("v1").addPathSegment("messages").body(slowBody(4_000)).build()
                transport.execute(postReq1, RequestOptions.none()).close()
                val recorded1 = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("16", recorded1.getHeader("X-Stainless-Timeout"))
                assertEquals("16", recorded1.getHeader("X-Stainless-Read-Timeout"))
                assertTrue(r1.release())

                // Row 2: body.contentLength() takes 6 s of 20 s (14 s < 15 s) -> refused before connect, 0 wire.
                val r2 = assertNotNull(budget.reserve())
                activeCtx.set(WireCallContext(Deadline.after(Duration.ofSeconds(20)) { nowNanos }, Duration.ofSeconds(15), r2))
                val postReq2 = HttpRequest.builder().method(HttpMethod.POST).baseUrl(baseUrl)
                    .addPathSegment("v1").addPathSegment("messages").body(slowBody(6_000)).build()
                val err2 = assertIs<AnthropicIoException>(runCatching { transport.execute(postReq2, RequestOptions.none()).close() }.exceptionOrNull())
                assertEquals(NoReplyReason.TOO_LATE_TO_SEND, assertNotNull(WireSendBlockedException.findIn(err2)).reason)
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS), "14 s left after body must not connect")
                assertFalse(r2.release())

                // Row 3: free GET where backend.prepareRequest advances 3 s of 5 s -> X-Stainless-Timeout == 2, 0 budget.
                prepareAdvanceMs = 3_000L
                val usedBeforeGet = budget.usedCount()
                activeCtx.set(WireCallContext(Deadline.after(Duration.ofSeconds(5)) { nowNanos }, Duration.ofSeconds(1), null))
                val getReq = HttpRequest.builder().method(HttpMethod.GET).baseUrl(baseUrl)
                    .addPathSegment("v1").addPathSegment("models").addPathSegment("test").build()
                transport.execute(getReq, RequestOptions.none()).close()
                val recordedGet = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("2", recordedGet.getHeader("X-Stainless-Timeout"))
                assertEquals(usedBeforeGet, budget.usedCount())

                // Row 4 & 5: AnthropicReplyGenerator.syncClock(5 s) with stepping nanoTime (no test hook):
                // First read = 0 (Deadline.after before lazy client); second+ read = elapsed build time.
                fun steppingClock(afterBuildMs: Long): () -> Long {
                    val reads = AtomicInteger(0)
                    return { if (reads.incrementAndGet() == 1) 0L else afterBuildMs * 1_000_000L }
                }

                // 4.5 s spent building client -> 0.5 s < 1 s MIN_TIME_CHECK -> 0 GETs.
                val clockSkipped = ServerClock(monotonicMs = { 0L })
                AnthropicReplyGenerator(
                    apiKey = "test-key", log = {}, baseUrl = baseUrl, serverClock = clockSkipped,
                    budget = budget, nanoTime = steppingClock(4_500L),
                ).syncClock(Duration.ofSeconds(5))
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS), "0.5 s left after client build must skip GET")
                assertNull(clockSkipped.now())

                // 3.0 s spent building client -> 2.0 s left -> 1 GET with X-Stainless-Timeout == 2, 0 budget.
                val clockTrimmed = ServerClock(monotonicMs = { 0L })
                AnthropicReplyGenerator(
                    apiKey = "test-key", log = {}, baseUrl = baseUrl, serverClock = clockTrimmed,
                    budget = budget, nanoTime = steppingClock(3_000L),
                ).syncClock(Duration.ofSeconds(5))
                val recordedSync = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("GET", recordedSync.method)
                assertEquals("2", recordedSync.getHeader("X-Stainless-Timeout"))
                assertNotNull(clockTrimmed.now())
                assertEquals(usedBeforeGet, budget.usedCount())
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
                DeskRig(clockMs = clockMs, callsPerHour = 10, coolDownsMs = listOf(0)) { budget, clk, scope ->
                    val (src, _, hooks) = setup(api, clk, budget, scope)
                    wireCounter = hooks.second
                    src
                }.use { rig ->
                    val (_, query, hooks) = setup(api, clockMs, rig.budget, rig.scope)
                    rig.stillWanted = hooks.first
                    val (res, outcome) = assertNotNull(rig.askAndFinish(query), "$label: shown must admit")
                    if (expectedReason == null) {
                        assertIs<AIReplyResult.Reply>(res, label)
                        assertIs<AIQueryDesk.Outcome.Reply>(outcome, label)
                    } else {
                        assertEquals(expectedReason, assertIs<AIReplyResult.NoReply>(res, label).reason, label)
                    }
                    assertEquals(if (expectBudgetKept) 1 else 0, rig.budget.usedCount(), "$label: budget after 1st call")
                    if (expectWireSent) {
                        assertTrue(wireCounter() >= 1, "$label: expected >= 1 wire request, got ${wireCounter()}")
                    } else {
                        assertEquals(0, wireCounter(), "$label: expected 0 wire requests")
                    }

                    if (expectedReason != null && expectedReason.askAgain) {
                        assertIs<AIQueryDesk.Outcome.Failed>(outcome, "$label: attempt 1")
                        if (!expectAttemptUsed) {
                            // Unsent withdrawal / too-late consumes neither budget nor attempts; a later real call succeeds.
                            repeat(3) { i ->
                                val (_, extraOut) = assertNotNull(rig.askAndFinish(query), "$label extra $i")
                                assertIs<AIQueryDesk.Outcome.Failed>(extraOut, "$label: unsent call must not retire")
                            }
                            assertEquals(0, rig.budget.usedCount(), "$label: unsent calls return places")
                            api.answer = FakeMessagesApi.Answer(200, okAnthropicBody("later"))
                            rig.stillWanted = { true }
                            val (_, realOut) = assertNotNull(rig.askAndFinish(query), "$label: subsequent real call")
                            assertIs<AIQueryDesk.Outcome.Reply>(realOut, "$label: still answers after 4 unsent calls")
                            assertEquals(1, rig.budget.usedCount(), "$label: real call spends 1 place")
                        } else {
                            // Attempt counts (including pre-write network/TLS failures per G2); 3rd failure retires.
                            assertIs<AIQueryDesk.Outcome.Failed>(assertNotNull(rig.askAndFinish(query)).second, "$label: attempt 2")
                            assertIs<AIQueryDesk.Outcome.Retired>(assertNotNull(rig.askAndFinish(query)).second, "$label: attempt 3 retires")
                            assertEquals(
                                if (expectBudgetKept) 3 else 0,
                                rig.budget.usedCount(),
                                "$label: 3 attempts budget places",
                            )
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
        ) = Triple(anthropicSource(baseUrl, b, clk, scope, apiKey, timeout, minSend, fit), Query(q), wanted to wireCount)

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

        // NETWORK post-wire (raw TCP RST after reading request) & pre-wire (refused port; GOAWAY shutdown):
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
            } finally {
                rst.close()
                t.join(2_000)
            }
        }
        val unusedPort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        verifyRow("NETWORK (pre-wire refused)", NoReplyReason.NETWORK, false, false, true) { api, clk, b, s ->
            std(api, clk, b, s, baseUrl = "http://127.0.0.1:$unusedPort")
        }
        verifyRow("NETWORK (pre-wire ConnectionShutdownException)", NoReplyReason.NETWORK, false, false, true) { api, clk, _, s ->
            // Real WireBudgetInterceptor + 2nd network interceptor throwing ConnectionShutdownException before write.
            val goawayOkHttp = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor(WireBudgetInterceptor())
                .addNetworkInterceptor(Interceptor { throw ConnectionShutdownException() })
                .build()
            val gen = object : AIReplyGenerator {
                override val name = "goaway"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                    val req = Request.Builder().url("${api.baseUrl}/v1/messages")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .tag(WireCallContext::class.java, WireCallContext(request.deadline, AnthropicReplyGenerator.MIN_TIME_TO_SEND, request.reservation))
                        .build()
                    val err = assertNotNull(runCatching { goawayOkHttp.newCall(req).execute().close() }.exceptionOrNull())
                    return AIReplyResult.NoReply(AnthropicReplyGenerator.reasonForIoFailure(err), err.javaClass.simpleName)
                }
            }
            Triple(fakeGeneratorSource(gen, clk, s), Query("ravegpt goaway"), ({ true }) to ({ api.received.size }))
        }

        // Fake-generator rows (no real HTTPS/fault server): pre-wire TLS/cert failures and pre/post-wire UNEXPECTED_FAILURE.
        for (tls in listOf(NoReplyReason.CERTIFICATE_CLOCK, NoReplyReason.CERTIFICATE_UNTRUSTED, NoReplyReason.TLS)) {
            verifyRow("$tls (fake gen)", tls, false, false, true) { _, clk, _, s ->
                val ex: Throwable = when (tls) {
                    NoReplyReason.CERTIFICATE_CLOCK -> CertificateExpiredException("exp")
                    NoReplyReason.CERTIFICATE_UNTRUSTED -> SSLPeerUnverifiedException("untrusted")
                    else -> SSLException("tls")
                }
                val gen = object : AIReplyGenerator {
                    override val name = "tls"
                    override suspend fun generateReply(request: AIReplyRequest) =
                        AIReplyResult.NoReply(AnthropicReplyGenerator.reasonForIoFailure(ex), tls.name)
                }
                Triple(fakeGeneratorSource(gen, clk, s), Query("ravegpt $tls"), ({ true }) to ({ 0 }))
            }
        }
        verifyRow("UNEXPECTED_FAILURE (pre-wire fake gen)", NoReplyReason.UNEXPECTED_FAILURE, false, false, true) { _, clk, _, s ->
            val gen = object : AIReplyGenerator {
                override val name = "pre-boom"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult = throw IllegalStateException("prep")
            }
            Triple(fakeGeneratorSource(gen, clk, s), Query("ravegpt pre"), ({ true }) to ({ 0 }))
        }
        val postBoomHits = AtomicInteger(0)
        verifyRow("UNEXPECTED_FAILURE (post-wire fake gen)", NoReplyReason.UNEXPECTED_FAILURE, true, true, true) { _, clk, _, s ->
            val gen = object : AIReplyGenerator {
                override val name = "post-boom"
                override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
                    assertTrue(request.reservation!!.stamp())
                    postBoomHits.incrementAndGet()
                    throw IllegalStateException("post")
                }
            }
            Triple(fakeGeneratorSource(gen, clk, s), Query("ravegpt post"), ({ true }) to postBoomHits::get)
        }

        assertEquals(NoReplyReason.values().toSet(), testedReasons, "Missing NoReplyReason values from table")
    }

    // ---- 5. Rolling-hour 45 ceiling across providers, prep delays, and 2.5-hour mixed workload (Test #9)

    @Test
    fun `no rolling hour puts more than 45 requests on the wire across providers, prep delays, and mixed workloads`() {
        // Scenario A: Anthropic against 503 Retry-After: 0 (one-shot stops OkHttp replay: 1 POST per call, max 45).
        MockWebServer().use { server ->
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    postTimes.add(clockMs.get())
                    return MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                        .setBody(FakeMessagesApi.error("overloaded_error", "503"))
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            DeskRig(clockMs = clockMs, coolDownsMs = listOf(0)) { b, clk, s -> anthropicSource(baseUrl, b, clk, s) }.use { rig ->
                repeat(50) { i ->
                    val out = rig.askAndFinish(Query("ravegpt a-$i"))
                    if (i < 45) {
                        assertEquals(NoReplyReason.SERVER_ERROR, assertIs<AIReplyResult.NoReply>(assertNotNull(out).first).reason)
                    } else {
                        assertNull(out, "query $i must be blocked at 45/45")
                    }
                    clockMs.addAndGet(1_000L)
                }
                assertEquals(45, postTimes.size, "Anthropic 503 Retry-After: 0 must send 1 wire POST per call, 45 total")
                assertEquals(45, maxInAnyHour(postTimes))
            }
        }

        // Scenario B: OpenAI against 503 Retry-After: 0 and 408 (one-shot + retryOnConnectionFailure(false) -> 1 POST per call, max 45).
        MockWebServer().use { server ->
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            val seq = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    postTimes.add(clockMs.get())
                    return if (seq.incrementAndGet() % 2 == 1) {
                        MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"error":{"message":"busy"}}""")
                    } else {
                        MockResponse().setResponseCode(408).setBody("""{"error":{"message":"timeout"}}""")
                    }
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            DeskRig(clockMs = clockMs, coolDownsMs = listOf(0)) { b, clk, s -> openAiSource(baseUrl, b, clk, s) }.use { rig ->
                repeat(50) { i ->
                    val out = rig.askAndFinish(Query("ravegpt o-$i"))
                    if (i < 45) assertNotNull(out) else assertNull(out, "OpenAI query $i must be blocked at 45/45")
                    clockMs.addAndGet(1_000L)
                }
                assertEquals(45, postTimes.size, "OpenAI 503/408 must send 1 wire POST per call, 45 total")
                assertEquals(45, maxInAnyHour(postTimes))
            }
        }

        // Scenario C: 5 s prep delay on query #1 shifts its rolling-hour release by 5 s.
        MockWebServer().use { server ->
            val t0 = 1_000_000L
            val clockMs = AtomicLong(t0)
            val postTimes = CopyOnWriteArrayList<Long>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    postTimes.add(clockMs.get())
                    return MockResponse().setResponseCode(200).setBody(okAnthropicBody())
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            DeskRig(clockMs = clockMs, coolDownsMs = listOf(0)) { b, clk, s -> anthropicSource(baseUrl, b, clk, s) }.use { rig ->
                rig.stillWanted = { clockMs.addAndGet(5_000L); true }
                assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(Query("ravegpt prep-1"))).second)
                rig.stillWanted = { true }
                repeat(44) { i ->
                    clockMs.addAndGet(1_000L)
                    assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(Query("ravegpt prep-${i + 2}"))).second)
                }
                assertEquals(45, postTimes.size)
                assertEquals(t0 + 5_000L, postTimes.first())

                clockMs.set(t0 + AIHourlyBudget.HOUR_MS)
                assertNull(rig.askAndFinish(Query("ravegpt early-1")), "t0 + 1 h is still inside 1 h of wire send")
                clockMs.set(t0 + 5_000L + AIHourlyBudget.HOUR_MS - 1)
                assertNull(rig.askAndFinish(Query("ravegpt early-2")), "1 ms before wire send + 1 h must stay blocked")
                clockMs.set(t0 + 5_000L + AIHourlyBudget.HOUR_MS)
                assertIs<AIQueryDesk.Outcome.Reply>(assertNotNull(rig.askAndFinish(Query("ravegpt on-time"))).second)
                assertEquals(46, postTimes.size)
                assertEquals(45, maxInAnyHour(postTimes))
            }
        }

        // Scenario D: 2.5-hour mixed workload (200, 503 Retry-After: 0, 529, 5 s prep, withdrawn, too-late, deleted Gone).
        MockWebServer().use { server ->
            val clockMs = AtomicLong(1_000_000L)
            val postTimes = CopyOnWriteArrayList<Long>()
            val seq = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    postTimes.add(clockMs.get())
                    return when (seq.incrementAndGet() % 4) {
                        0 -> MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody(FakeMessagesApi.error("overloaded_error", "503"))
                        1 -> MockResponse().setResponseCode(529).setBody(FakeMessagesApi.error("overloaded_error", "529"))
                        else -> MockResponse().setResponseCode(200).setBody(okAnthropicBody())
                    }
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            DeskRig(clockMs = clockMs, coolDownsMs = listOf(0)) { b, clk, s -> anthropicSource(baseUrl, b, clk, s) }.use { rig ->
                val endMs = clockMs.get() + 2 * AIHourlyBudget.HOUR_MS + AIHourlyBudget.HOUR_MS / 2
                var i = 0
                while (clockMs.get() < endMs) {
                    val mode = i++ % 7
                    rig.stillWanted = when (mode) {
                        0 -> { { false } } // withdrawn
                        1 -> { { clockMs.addAndGet(90_000L); true } } // too late
                        2 -> { { clockMs.addAndGet(5_000L); true } } // 5 s prep
                        else -> { { true } }
                    }
                    rig.askAndFinish(Query("ravegpt mix-$i"), removeBeforeFinish = (mode == 3))
                    clockMs.addAndGet(50_000L)
                }
                assertTrue(postTimes.size > 70, "expected > 70 POSTs over 2.5 h, got ${postTimes.size}")
                assertTrue(maxInAnyHour(postTimes) <= 45, "rolling hour exceeded 45: ${maxInAnyHour(postTimes)}")
            }
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

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    postTimes.add(clockMs.get())
                    return when {
                        request.path?.contains("chat/completions") == true ->
                            MockResponse().setResponseCode(200).setBody(okOpenAiSse())
                        seq.incrementAndGet() == 1 ->
                            MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                                .setBody(FakeMessagesApi.error("overloaded_error", "503"))
                        else -> MockResponse().setResponseCode(200).setBody(okAnthropicBody())
                    }
                }
            }
            server.start()
            val baseUrl = server.url("/").toString().trimEnd('/')
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = MessageRepository(saver = Executor { })
            val owner = OwnerQueue()
            val completed = AtomicInteger(0)
            val sharedBudget = AIHourlyBudget(nowMs = clockMs::get)
            val runner = anthropicSource(baseUrl, sharedBudget, clockMs, scope)
            val wiredSource = object : AIReplySource {
                override val hourlyBudget = sharedBudget
                override fun warmUp(onDone: () -> Unit) = false
                override fun ask(
                    text: String,
                    stillWanted: () -> Boolean,
                    reservation: AIHourlyBudget.Reservation?,
                    onResult: (AIReplyResult) -> Unit,
                ) {
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
                            while (owner.drain { throw it } > 0) Unit
                            Thread.sleep(5)
                        }
                        assertEquals(before + 1, completed.get())
                        while (owner.drain { throw it } > 0) Unit
                    }
                    // Step past the 30 s cooldown triggered by the first 503, staying well within 1 hour.
                    clockMs.addAndGet(if (idx == 0) 35_000L else 1_000L)
                }
                assertEquals(45, postTimes.size)
                assertEquals(45, sharedBudget.usedCount())

                // AIReplyGenerators.forProvider wires the shared budget for BOTH providers.
                for (provider in listOf(AIProvider.ANTHROPIC, AIProvider.OPENAI)) {
                    val beforeWire = postTimes.size
                    val gen = AIReplyGenerators.forProvider(
                        provider = provider,
                        anthropicApiKey = "test-key",
                        openAiApiKey = "test-key",
                        effort = { AIEffort.HIGH },
                        log = {},
                        budget = sharedBudget,
                        anthropicBaseUrl = baseUrl,
                        openAiBaseUrl = "$baseUrl/v1/",
                    )
                    val res = runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
                    assertEquals(NoReplyReason.WITHDRAWN, assertIs<AIReplyResult.NoReply>(res).reason, "$provider obeys shared budget")
                    assertEquals(beforeWire, postTimes.size, "$provider sends 0 wire POSTs at 45/45")
                }
            } finally {
                scope.cancel()
                stopKoin()
                dir.deleteRecursively()
            }
        }
    }
}
