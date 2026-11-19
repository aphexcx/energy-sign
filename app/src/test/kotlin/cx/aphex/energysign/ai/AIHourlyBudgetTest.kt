package cx.aphex.energysign.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
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
}
