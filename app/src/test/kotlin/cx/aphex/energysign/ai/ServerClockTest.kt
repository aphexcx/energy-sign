package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The time as the server told it, kept running by a clock that only counts. */
class ServerClockTest {

    private var monotonicMs = 5_000L
    private val clock = ServerClock { monotonicMs }

    @Test
    fun `before any server has told the time there is none`() {
        assertNull(clock.now())
    }

    @Test
    fun `the time is what the server said plus what has passed since`() {
        clock.sync(Instant.parse("2026-11-21T05:30:00Z"))

        assertEquals(Instant.parse("2026-11-21T05:30:00Z"), clock.now())
        monotonicMs += 47 * 60_000
        assertEquals(Instant.parse("2026-11-21T06:17:00Z"), clock.now())
    }

    @Test
    fun `a later answer of the server replaces the earlier one`() {
        clock.sync(Instant.parse("2026-11-21T05:30:00Z"))
        monotonicMs += 60_000
        clock.sync(Instant.parse("2026-11-21T05:31:02Z"))

        assertEquals(Instant.parse("2026-11-21T05:31:02Z"), clock.now())
    }

    @Test
    fun `the Date header of a response is read`() {
        clock.syncFromHeaders(date = "Sat, 21 Nov 2026 05:30:00 GMT", age = null)

        assertEquals(Instant.parse("2026-11-21T05:30:00Z"), clock.now())
    }

    @Test
    fun `a header that is missing or is no date changes nothing`() {
        for (bad in listOf(null, "", "   ", "yesterday", "2026-11-21T05:30:00Z", "Sat, 41 Nov 2026 05:30:00 GMT")) {
            clock.syncFromHeaders(date = bad, age = null)
            assertNull(clock.now(), "after \"$bad\"")
        }
        clock.sync(Instant.parse("2026-11-21T05:30:00Z"))
        clock.syncFromHeaders(date = "rubbish", age = null)
        assertEquals(Instant.parse("2026-11-21T05:30:00Z"), clock.now())
    }

    // ---- the time is true or absent (class F)

    @Test
    fun `a response that has been kept somewhere does not tell the time - its Date is the time it was stored`() {
        // 01:30 UTC in truth; a cache hands out what it stored an hour ago.
        clock.syncFromHeaders(date = "Sat, 21 Nov 2026 00:30:00 GMT", age = "3600")
        assertNull(clock.now(), "an old Date must not set the clock")

        clock.syncFromHeaders(date = "Sat, 21 Nov 2026 00:30:00 GMT", age = "1")
        assertNull(clock.now(), "one second old is old")
    }

    @Test
    fun `an Age that cannot be read counts as old, and an Age of 0 is fresh`() {
        for (unreadable in listOf("", "soon", "-", "3600, 0", "1e3")) {
            clock.syncFromHeaders(date = "Sat, 21 Nov 2026 05:30:00 GMT", age = unreadable)
            assertNull(clock.now(), "after Age \"$unreadable\"")
        }

        clock.syncFromHeaders(date = "Sat, 21 Nov 2026 05:30:00 GMT", age = " 0 ")
        assertEquals(Instant.parse("2026-11-21T05:30:00Z"), clock.now())
    }

    @Test
    fun `an old response does not move a clock that is already right`() {
        clock.sync(Instant.parse("2026-11-21T01:30:00Z"))

        clock.syncFromHeaders(date = "Sat, 21 Nov 2026 00:30:00 GMT", age = "3600")

        assertEquals(Instant.parse("2026-11-21T01:30:00Z"), clock.now())
    }

    // ---- found by the review of round 3

    @Test
    fun `the time counts from the moment the response arrived, when that moment is given`() {
        val arrived = clock.arrival()
        monotonicMs += 4_000 // the body was read meanwhile

        clock.syncFromHeaders(date = "Sat, 21 Nov 2026 05:30:00 GMT", age = null, arrivedAtMs = arrived)

        assertEquals(Instant.parse("2026-11-21T05:30:04Z"), clock.now())
        monotonicMs += 1_000
        assertEquals(Instant.parse("2026-11-21T05:30:05Z"), clock.now())
    }
}
