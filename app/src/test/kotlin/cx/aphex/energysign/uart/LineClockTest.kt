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

    // ---- when a push may start: the line free, and the sign over what was last on it

    @Test
    fun `with nothing held a push may start when the line is free`() {
        assertEquals(5 * ms, clock.pushAtNs)
        clock.wrote(returnedNs = 100 * ms, count = 72)
        assertEquals(clock.freeAtNs, clock.pushAtNs)
    }

    @Test
    fun `behind an attention a push waits 250 ms, behind a torn frame 1010, counted from when the bytes have left`() {
        clock.wrote(returnedNs = 100 * ms, count = 72) // gone at 150
        clock.hold(Behind.ATTENTION)
        assertEquals(100 * ms + air(72) + 250 * ms, clock.pushAtNs)
        assertEquals(100 * ms + air(72), clock.freeAtNs) // the line itself is free as before

        val torn = LineClock(baud = 14400, startNs = 0)
        torn.wrote(returnedNs = 100 * ms, count = 1)
        torn.hold(Behind.TORN_FRAME)
        assertEquals(100 * ms + air(1) + 1_010 * ms, torn.pushAtNs)
        assertEquals(250, Behind.ATTENTION.holdMs)
        assertEquals(1_010, Behind.TORN_FRAME.holdMs)
    }

    @Test
    fun `a longer hold that stands is not cut short by a shorter one, and a hold is not cut short by a free line`() {
        clock.wrote(returnedNs = 100 * ms, count = 1)
        clock.hold(Behind.TORN_FRAME)
        val until = clock.pushAtNs
        clock.hold(Behind.ATTENTION)
        assertEquals(until, clock.pushAtNs)
        clock.holdFrom(nowNs = 200 * ms, millis = 100)
        assertEquals(until, clock.pushAtNs)
        // and a later, longer one moves it
        clock.wrote(returnedNs = 900 * ms, count = 72)
        clock.hold(Behind.ATTENTION)
        assertEquals(900 * ms + air(72) + 250 * ms, clock.pushAtNs)
    }

    @Test
    fun `what is written while a hold stands does not start a push before the line is free of it`() {
        clock.holdFrom(nowNs = 100 * ms, millis = 100) // until 200
        clock.wrote(returnedNs = 190 * ms, count = 72) // a reply, on the line until 240
        assertEquals(190 * ms + air(72), clock.pushAtNs)
    }

    @Test
    fun `with nothing on the line a push waits from now`() {
        clock.holdFrom(nowNs = 300 * ms, millis = 100)
        assertEquals(400 * ms, clock.pushAtNs)
        assertEquals(5 * ms, clock.freeAtNs)
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
