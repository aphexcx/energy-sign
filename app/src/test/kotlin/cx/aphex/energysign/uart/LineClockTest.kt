package cx.aphex.energysign.uart

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** When the line is free: the arithmetic every wait before an attention and a pushed frame rests on. */
class LineClockTest {
    private val ms = 1_000_000L

    /** [count] bytes on the line at 14400 baud, start and stop bit included. */
    private fun air(count: Int) = count * 10 * 1_000_000_000L / 14400

    private val clock = LineClock(baud = 14400, startNs = 5 * ms)

    @Test
    fun `a line nothing was sent on is free from the start`() {
        assertEquals(5 * ms, clock.freeAtNs)
    }

    @Test
    fun `bytes take ten bit times each from the return of the write that handed them over`() {
        clock.wrote(returnedNs = 100 * ms, count = 72)
        assertEquals(100 * ms + air(72), clock.freeAtNs)
        assertEquals(150, (clock.freeAtNs + ms / 2) / ms) // 72 bytes at 14400 baud are 50 ms
    }

    @Test
    fun `bytes handed over while others are leaving go out behind them`() {
        clock.wrote(returnedNs = 100 * ms, count = 58) // 40 ms
        clock.wrote(returnedNs = 101 * ms, count = 10)
        assertEquals(100 * ms + air(58) + air(10), clock.freeAtNs)
    }

    @Test
    fun `bytes handed over after the line fell free leave from then, not from when it fell free`() {
        clock.wrote(returnedNs = 100 * ms, count = 10) // gone at 107
        clock.wrote(returnedNs = 300 * ms, count = 10)
        assertEquals(300 * ms + air(10), clock.freeAtNs)
    }

    @Test
    fun `no bytes change nothing`() {
        clock.wrote(returnedNs = 100 * ms, count = 0)
        assertEquals(5 * ms, clock.freeAtNs)
    }

    @Test
    fun `a break holds the line for its length from the return of the call`() {
        clock.low(returnedNs = 201 * ms, millis = 50)
        assertEquals(251 * ms, clock.freeAtNs)
    }

    @Test
    fun `a break behind bytes that are still leaving does not free the line before they are gone`() {
        clock.wrote(returnedNs = 100 * ms, count = 144) // 100 ms on the line
        clock.low(returnedNs = 101 * ms, millis = 50)
        assertEquals(100 * ms + air(144), clock.freeAtNs)
    }

    @Test
    fun `the quiet is added to whatever is on the line`() {
        clock.low(returnedNs = 200 * ms, millis = 50)
        clock.quiet(20)
        assertEquals(270 * ms, clock.freeAtNs)
        clock.wrote(returnedNs = 270 * ms, count = 72)
        assertEquals(270 * ms + air(72), clock.freeAtNs)
    }

    @Test
    fun `the clock that wraps around is still a clock`() {
        // System.nanoTime may be negative and may pass zero: only differences count
        val late = LineClock(baud = 14400, startNs = Long.MAX_VALUE - 10 * ms)
        late.wrote(returnedNs = Long.MAX_VALUE - 5 * ms, count = 72) // gone 45 ms past the wrap
        assertEquals(Long.MIN_VALUE + 45 * ms - 1, late.freeAtNs)
        // handed over a millisecond before the wrap: the line is busy until after it, and as numbers
        // that end is the "smaller" one. The bytes go out behind what is leaving all the same.
        late.wrote(returnedNs = Long.MAX_VALUE - 1 * ms, count = 72)
        assertEquals(Long.MIN_VALUE + 95 * ms - 1, late.freeAtNs)
        late.low(returnedNs = Long.MIN_VALUE + 20 * ms, millis = 50)
        assertEquals(Long.MIN_VALUE + 95 * ms - 1, late.freeAtNs)
    }
}
