package cx.aphex.energysign.ai

import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.errors.AnthropicIoException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [AIHourlyBudget] and [WireBudgetInterceptor] invariants. */
class AIHourlyBudgetTest {

    @Test
    fun `reservation holds ceiling during prep, stamps at wire time, and expires one hour after stamp`() {
        val now = AtomicLong(1_000_000L)
        val budget = AIHourlyBudget(callsPerHour = 2, nowMs = { now.get() })

        val r1 = assertNotNull(budget.reserve())
        val r2 = assertNotNull(budget.reserve())
        assertFalse(budget.hasRoom(), "two reservations fill a 2-call budget")
        assertNull(budget.reserve(), "third reservation must be refused while two are preparing")

        // First call spends 5 s preparing before stamping.
        now.addAndGet(5_000L)
        assertTrue(r1.stamp())
        assertTrue(r1.wasSent)
        assertFalse(r1.stamp(), "same reservation cannot be stamped twice")

        // Second call never sends and releases: room opens immediately.
        assertFalse(r2.release())
        assertFalse(r2.wasSent)
        assertFalse(r2.stamp(), "released reservation is permanently closed against late threads")
        assertTrue(budget.hasRoom())
        assertEquals(1, budget.usedCount())

        // Releasing a stamped reservation is a no-op and keeps the timestamp.
        assertTrue(r1.release())
        assertEquals(1, budget.usedCount())

        // Slot from r1 stays occupied until 1 full hour after stamp time (1_005_000 + 3_600_000).
        now.set(1_005_000L + 3_599_999L)
        assertEquals(1, budget.usedCount())
        now.set(1_005_000L + 3_600_000L)
        assertEquals(0, budget.usedCount())
    }

    @Test
    fun `concurrent reservations and replays never exceed callsPerHour`() {
        val now = AtomicLong(1_000_000L)
        val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { now.get() })
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val stamped = AtomicInteger(0)

        try {
            repeat(threads) { t ->
                pool.submit {
                    start.await()
                    repeat(30) { i ->
                        if ((t + i) % 2 == 0) {
                            val r = budget.reserve()
                            if (r != null) {
                                if (r.stamp()) stamped.incrementAndGet()
                                r.release()
                            }
                        } else {
                            if (budget.tryStampAdditional()) stamped.incrementAndGet()
                        }
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(45, stamped.get(), "concurrent callers must never stamp more than callsPerHour")
            assertEquals(45, budget.usedCount())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `WireBudgetInterceptor fails closed for untagged requests and non-GET under FreeModelGet`() {
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
                // Untagged POST must be blocked before hitting the server.
                val untagged = Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post("{}".toRequestBody())
                    .build()
                val ex1 = assertFailsWith<WireSendBlockedException> {
                    client.newCall(untagged).execute().close()
                }
                assertEquals(WireSendBlockedException.Kind.BUDGET_EXHAUSTED, ex1.kind)
                assertFalse(ex1.afterWireSend)
                assertEquals(0, server.requestCount)

                // POST tagged as FreeModelGet must ALSO be blocked.
                val badFree = Request.Builder()
                    .url(server.url("/v1/messages"))
                    .post("{}".toRequestBody())
                    .tag(WireCallContext::class.java, WireCallContext.FreeModelGet(Deadline.after(Duration.ofSeconds(5)) { now.get() * 1_000_000L }))
                    .build()
                assertFailsWith<WireSendBlockedException> {
                    client.newCall(badFree).execute().close()
                }
                assertEquals(0, server.requestCount)

                // FreeModelGet with 0 ms remaining must be blocked before hitting the server.
                val expiredFree = Request.Builder()
                    .url(server.url("/v1/models/claude"))
                    .get()
                    .tag(WireCallContext::class.java, WireCallContext.FreeModelGet(Deadline.after(Duration.ZERO) { now.get() * 1_000_000L }))
                    .build()
                val ex3 = assertFailsWith<WireSendBlockedException> {
                    client.newCall(expiredFree).execute().close()
                }
                assertEquals(WireSendBlockedException.Kind.TOO_LATE, ex3.kind)
                assertEquals(0, server.requestCount)

                // Valid FreeModelGet GET succeeds and spends 0 hourly budget slots.
                val okFree = Request.Builder()
                    .url(server.url("/v1/models/claude"))
                    .get()
                    .tag(WireCallContext::class.java, WireCallContext.FreeModelGet(Deadline.after(Duration.ofSeconds(5)) { now.get() * 1_000_000L }))
                    .build()
                client.newCall(okFree).execute().use { assertEquals(200, it.code) }
                assertEquals(1, server.requestCount)
                assertEquals(0, budget.usedCount(), "free model GET must not spend paid budget")
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    // ---- Class 1 table: nothing is charged or left decided before the outcome it depends on is known

    @Test
    fun `class 1 table - pre-write failures do not charge budget or attempts, post-write failures do, and sync start failure clears notice`() {
        data class WireFailureRow(
            val name: String,
            val failure: Throwable,
            val expectCharged: Boolean,
        )
        val rows = listOf(
            WireFailureRow("HTTP/2 GOAWAY ConnectionShutdownException", ConnectionShutdownException(), expectCharged = false),
            WireFailureRow("HTTP/2 REFUSED_STREAM", StreamResetException(ErrorCode.REFUSED_STREAM), expectCharged = false),
            WireFailureRow("post-write TCP RST IOException", IOException("Connection reset by peer"), expectCharged = true),
            WireFailureRow("post-write HTTP/2 CANCEL", StreamResetException(ErrorCode.CANCEL), expectCharged = true),
        )

        val stubCall = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1/v1/messages").build())
        for (row in rows) {
            val now = AtomicLong(1_000_000L)
            val budget = AIHourlyBudget(callsPerHour = 2, nowMs = { now.get() })
            val interceptor = WireBudgetInterceptor(budget)
            val reservation = assertNotNull(budget.reserve())
            val ctx = WireCallContext.Paid(
                reservation = reservation,
                deadline = Deadline.after(Duration.ofSeconds(60)) { now.get() * 1_000_000L },
                minTimeToSend = Duration.ofSeconds(15),
            )
            val req = Request.Builder()
                .url("http://127.0.0.1/v1/messages")
                .post("{}".toRequestBody())
                .tag(WireCallContext::class.java, ctx)
                .build()
            val failingChain = object : Interceptor.Chain {
                override fun request(): Request = req
                override fun proceed(request: Request): Response = throw row.failure
                override fun connection(): Connection? = null
                override fun call(): Call = stubCall
                override fun connectTimeoutMillis(): Int = 1_000
                override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
                override fun readTimeoutMillis(): Int = 1_000
                override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
                override fun writeTimeoutMillis(): Int = 1_000
                override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
            }

            assertFailsWith<Throwable>(row.name) { interceptor.intercept(failingChain) }

            assertEquals(row.expectCharged, reservation.wasSent, "${row.name}: wasSent")
            assertEquals(if (row.expectCharged) 1 else 0, ctx.wireSends.get(), "${row.name}: wireSends")
            val wasSentOnRelease = reservation.release()
            assertEquals(row.expectCharged, wasSentOnRelease, "${row.name}: release()")
            assertEquals(if (row.expectCharged) 1 else 0, budget.usedCount(), "${row.name}: budget usedCount")
        }

        // Also Class 1: synchronous throw from AIQueryDesk.start must not leave thinking notice up.
        val now = AtomicLong(1_000_000L)
        val desk = AIQueryDesk<String>(
            start = { _, _ -> throw IllegalStateException("start failed synchronously") },
            listedAmong = { it },
            nowMs = { now.get() },
        )
        assertFailsWith<IllegalStateException> { desk.shown("q", now.get()) }
        assertFalse(desk.thinkingWanted(now.get()), "sync throw from start() must clear noticeFor")
    }

    // ---- Class 2 table: limits are computed AFTER all time-consuming request prep (lazy JSON body serialization)

    @Test
    fun `class 2 table - BudgetedOkHttpClient computes timeout and 15 s check after lazy body serialization`() {
        fun slowBody(clockMs: AtomicLong, serializeMs: Long): HttpRequestBody = object : HttpRequestBody {
            private val bytes: ByteArray by lazy {
                clockMs.addAndGet(serializeMs)
                "{}".toByteArray()
            }
            override fun writeTo(outputStream: OutputStream) = outputStream.write(bytes)
            override fun contentType(): String = "application/json"
            override fun contentLength(): Long = bytes.size.toLong()
            override fun repeatable(): Boolean = true
            override fun close() {}
        }

        // Row 2a: 20 s remaining, lazy serialization takes 4 s -> 16 s left -> OkHttp timeout header MUST be 16, not 20!
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val clockMs = AtomicLong(1_000_000L)
            val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
            val ctx = WireCallContext.Paid(
                reservation = budget.reserve(),
                deadline = Deadline.after(Duration.ofSeconds(20)) { clockMs.get() * 1_000_000L },
                minTimeToSend = Duration.ofSeconds(15),
            )
            val baseUrl = server.url("/").toString().trimEnd('/')
            val backend = object : Backend {
                override fun baseUrl(): String = baseUrl
                override fun close() {}
            }
            val transport = BudgetedOkHttpClient(
                defaultTimeout = Timeout.builder().connect(Duration.ofSeconds(10)).request(Duration.ofSeconds(90)).build(),
                maxRequestTimeout = Duration.ofSeconds(90),
                connectTimeout = Duration.ofSeconds(10),
                backend = backend,
                networkInterceptor = WireBudgetInterceptor(budget),
                currentContext = { ctx },
            )
            try {
                val req = HttpRequest.builder()
                    .method(HttpMethod.POST)
                    .baseUrl(baseUrl)
                    .addPathSegments("v1", "messages")
                    .body(slowBody(clockMs, serializeMs = 4_000L))
                    .build()
                transport.execute(req, RequestOptions.none()).use { assertEquals(200, it.statusCode()) }
                val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("16", recorded.getHeader("X-Stainless-Timeout"), "callTimeout must exclude 4 s spent serializing")
                assertEquals("16", recorded.getHeader("X-Stainless-Read-Timeout"), "readTimeout must exclude 4 s spent serializing")
            } finally {
                transport.close()
            }
        }

        // Row 2b: 20 s remaining, lazy serialization takes 6 s -> 14 s (< 15 s) left -> MUST block BEFORE opening TCP socket!
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
            val budget = AIHourlyBudget(callsPerHour = 45, nowMs = { clockMs.get() })
            val reservation = assertNotNull(budget.reserve())
            val ctx = WireCallContext.Paid(
                reservation = reservation,
                deadline = Deadline.after(Duration.ofSeconds(20)) { clockMs.get() * 1_000_000L },
                minTimeToSend = Duration.ofSeconds(15),
            )
            val backend = object : Backend {
                override fun baseUrl(): String = "http://127.0.0.1:${listener.localPort}"
                override fun close() {}
            }
            val transport = BudgetedOkHttpClient(
                defaultTimeout = Timeout.builder().connect(Duration.ofSeconds(10)).request(Duration.ofSeconds(90)).build(),
                maxRequestTimeout = Duration.ofSeconds(90),
                connectTimeout = Duration.ofSeconds(10),
                backend = backend,
                networkInterceptor = WireBudgetInterceptor(budget),
                currentContext = { ctx },
            )
            try {
                val req = HttpRequest.builder()
                    .method(HttpMethod.POST)
                    .baseUrl("http://127.0.0.1:${listener.localPort}")
                    .addPathSegments("v1", "messages")
                    .body(slowBody(clockMs, serializeMs = 6_000L))
                    .build()
                val ex = assertFailsWith<AnthropicIoException> { transport.execute(req, RequestOptions.none()).close() }
                val blocked = assertNotNull(WireSendBlockedException.findIn(ex))
                assertEquals(WireSendBlockedException.Kind.TOO_LATE, blocked.kind)
                assertFalse(blocked.afterWireSend)
                assertEquals(0, accepted.get(), "serialization crossing below 15 s must block before opening any TCP socket")
                assertFalse(reservation.release(), "nothing sent: reservation must be returned")
            } finally {
                transport.close()
                listener.close()
                acceptThread.join(2_000)
            }
        }
    }
}
