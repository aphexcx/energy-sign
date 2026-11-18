package cx.aphex.energysign.uart

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The timing log: what a line says, that it says nothing while off, and how often it asks. */
class UartTimingLogTest {
    private val us = 1_000L
    private val ms = 1_000_000L

    private val lines = mutableListOf<String>()
    private var on = true
    private var asked = 0
    private var cpuNs = 0L

    private val log = UartTimingLog(
        isEnabled = {
            asked++
            on
        },
        emit = { lines += it },
        threadCpuNanos = { cpuNs },
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `a read line has its time in microseconds, the count and the bytes`() {
        log.rx(firstNs = 5_000 * us, lastNs = 5_000 * us, emptyNs = 3_100 * us, chunks = 1, buffer = ascii("?"), count = 1)
        assertEquals(listOf("rx t_us=5000 n=1 bytes=[?] chunks=1 first_us=5000 empty_us=3100"), lines)
    }

    @Test
    fun `bytes that are not printable are written as hex, and only the bytes that were read`() {
        log.rx(0, 0, 0, 1, byteArrayOf(0x7E, 0x00, 0x0A, 0xFF.toByte(), 0x41, 0x42), 5)
        assertEquals("rx t_us=0 n=5 bytes=[~\\x00\\x0A\\xFFA] chunks=1 first_us=0 empty_us=0", lines.single())
    }

    @Test
    fun `a write line has the time the write returned, the call, the count and the read it follows`() {
        log.rx(1_000 * us, 1_000 * us, 0, 1, ascii("?"), 1)
        log.tx(callNs = 1_200 * us, returnNs = 1_900 * us, wrote = 11, of = 11, isPollReport = true)
        log.tx(callNs = 3_000 * us, returnNs = 3_500 * us, wrote = 40, of = 47, isPollReport = false)
        assertEquals("tx t_us=1900 n=11/11 kind=report call_us=1200 rx_us=1000", lines[1])
        assertEquals("tx t_us=3500 n=40/47 kind=message call_us=3000 rx_us=1000", lines[2])
    }

    @Test
    fun `a push line has the call, the end of the break, the end of the guard and the end of the write`() {
        log.push(callNs = 10 * ms, breakReturnedNs = 60 * ms, guardEndNs = 80 * ms, doneNs = 83 * ms)
        assertEquals("push t_us=83000 call_us=10000 break_us=60000 guard_us=80000", lines.single())
    }

    @Test
    fun `a main thread line says how late the main thread ran`() {
        log.mainLag(postedNs = 1_000 * us, ranNs = 251_000 * us)
        assertEquals("main t_us=251000 posted_us=1000 lag_us=250000", lines.single())
    }

    @Test
    fun `every ten seconds one line sums up the reads and what they cost`() {
        // 500 reads a second for ten seconds, 200 us each, one of them slow, one late
        var t = 0L
        var n = 0
        while (t < 10_000 * ms) {
            val cost = if (n == 7) 900 * us else 200 * us
            log.poll(startNs = t, endNs = t + cost)
            t += if (n == 9) 30 * ms else 2 * ms
            n++
        }
        cpuNs = 1_234 * ms
        log.poll(startNs = t, endNs = t + 200 * us)
        val stat = lines.single()
        assertTrue(stat.startsWith("stat t_us="), stat)
        assertTrue(stat.contains(" polls=$n "), stat)
        assertTrue(stat.contains(" read_avg_us=200 "), stat)
        assertTrue(stat.contains(" read_max_us=900 "), stat)
        assertTrue(stat.contains(" gap_max_us=30000 "), stat)
        assertTrue(stat.endsWith(" cpu_ms=1234"), stat)
    }

    @Test
    fun `nothing is written while the flag is off, and the flag is asked at most once a second`() {
        on = false
        var t = 0L
        repeat(5_000) {
            log.poll(t, t + 100 * us)
            log.rx(t, t, t, 1, ascii("~"), 1)
            log.tx(t, t, 1, 1, false)
            t += 2 * ms
        }
        assertEquals(emptyList(), lines)
        assertTrue(asked in 10..11, "asked $asked times in 10 s")
    }

    @Test
    fun `the flag takes effect while running, within a second`() {
        on = false
        log.rx(0, 0, 0, 1, ascii("~"), 1)
        on = true
        log.rx(500 * ms, 500 * ms, 0, 1, ascii("~"), 1)
        assertEquals(emptyList(), lines)
        log.rx(1_001 * ms, 1_001 * ms, 0, 1, ascii("~"), 1)
        assertEquals(1, lines.size)
    }
}
