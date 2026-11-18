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
        clock.syncFromDateHeader("Sat, 21 Nov 2026 05:30:00 GMT")

        assertEquals(Instant.parse("2026-11-21T05:30:00Z"), clock.now())
    }

    @Test
    fun `a header that is missing or is no date changes nothing`() {
        for (bad in listOf(null, "", "   ", "yesterday", "2026-11-21T05:30:00Z", "Sat, 41 Nov 2026 05:30:00 GMT")) {
            clock.syncFromDateHeader(bad)
            assertNull(clock.now(), "after \"$bad\"")
        }
        clock.sync(Instant.parse("2026-11-21T05:30:00Z"))
        clock.syncFromDateHeader("rubbish")
        assertEquals(Instant.parse("2026-11-21T05:30:00Z"), clock.now())
    }
}
