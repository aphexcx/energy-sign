package cx.aphex.energysign.uart

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The sign link's loop against a fake line, a fake app and a fake clock.
 *
 * The fake line keeps its own account of what is on it (bytes leave at 14400 baud behind those
 * still leaving, a break is LOW from the call), independent of the link's LineClock, and notes
 * as a fault what a real line would garble: a break asked for while bytes are leaving, bytes
 * handed over during a break. The fake app is a chooser text (null outside chooser mode) and a
 * queue of ordinary messages that an answer TAKES, so a message taken without a request shows.
 *
 * Time only moves when the link sleeps or a fake call is told to take time, so times are exact.
 */
class SignLinkTest {
    private val ms = 1_000_000L
    private val baud = 14400
    private val byteNs = 10 * 1_000_000_000L / baud

    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    // ---- what the sign sends
    private val arrivals = sortedMapOf<Long, ByteArray>()
    private var reads = 0
    private var readCostNs = 0L
    private var failReads = 0

    // ---- what the app puts on the line
    private class Tx(val atNs: Long, val leavesFromNs: Long, val goneAtNs: Long, val bytes: ByteArray)

    /** In order: `low@T` for a break, `tx[text]@T` for bytes handed over (T in ms; zeros as `00xN`), `log:` and `warn:` lines. */
    private val line = mutableListOf<String>()
    private val txs = mutableListOf<Tx>()
    private val lows = mutableListOf<Long>()
    private val faults = mutableListOf<String>()
    private var txBusyUntilNs = 0L
    private var lowUntilNs = 0L
    private var breakBlocks = false
    private var failBreaks = 0

    /** The transmitter takes at most this many bytes per write. */
    private var takesPerWrite = Int.MAX_VALUE

    /** The transmitter takes this many more bytes and then nothing, until [stuckUntilNs]. */
    private var takesBeforeStuck = Int.MAX_VALUE
    private var stuckUntilNs = 0L
    private var failWrites = 0
    private var closes = 0

    /** The next write call stalls this long before it hands its bytes over (once). */
    private var writeStallNs = 0L

    /** The next sleep of the link lasts this much longer than asked (once): the thread was kept waiting. */
    private var sleepOverrunNs = 0L

    private val wire = object : SignWire {
        override fun read(buffer: ByteArray, length: Int): Int {
            reads++
            now += readCostNs
            if (failReads > 0) {
                failReads--
                throw IOException("read failed")
            }
            val due = arrivals.headMap(now + 1)
            if (due.isEmpty()) return 0
            val bytes = due.values.reduce { a, b -> a + b }
            due.clear()
            val n = minOf(bytes.size, length)
            bytes.copyInto(buffer, 0, 0, n)
            if (n < bytes.size) arrivals[now] = bytes.copyOfRange(n, bytes.size)
            return n
        }

        override fun write(buffer: ByteArray, length: Int): Int {
            if (failWrites > 0) {
                failWrites--
                throw IOException("write failed")
            }
            now += writeStallNs
            writeStallNs = 0
            if (now >= stuckUntilNs) takesBeforeStuck = Int.MAX_VALUE
            val take = minOf(length, takesPerWrite, takesBeforeStuck)
            if (take == 0) return 0
            if (takesBeforeStuck != Int.MAX_VALUE) takesBeforeStuck -= take
            if (now < lowUntilNs) faults += "bytes at ${now / ms} ms, into the LOW that lasts until ${lowUntilNs / ms} ms"
            val from = maxOf(now, txBusyUntilNs)
            txBusyUntilNs = from + take * byteNs
            val bytes = buffer.copyOf(take)
            txs += Tx(now, from, txBusyUntilNs, bytes)
            line += "tx[${if (bytes.all { it == 0.toByte() }) "00x$take" else String(bytes)}]@${now / ms}"
            return take
        }

        override fun sendBreak(millis: Int) {
            if (failBreaks > 0) {
                failBreaks--
                throw IOException("no break")
            }
            if (now < txBusyUntilNs) faults += "break at ${now / ms} ms, while bytes are leaving until ${txBusyUntilNs / ms} ms"
            lows += now
            line += "low@${now / ms}"
            lowUntilNs = now + millis * ms
            if (breakBlocks) now += millis * ms
        }

        override fun close() {
            closes++
        }
    }

    // ---- the app
    /** The chooser text now; null outside chooser mode. */
    private var chooser: String? = "A"
    private val ordinary = ArrayDeque((1..50).map { "M$it" })
    private val taken = mutableListOf<String>()
    private var answerCostNs = 0L
    private var frameCostNs = 0L
    private var answerThrows = 0
    private var answers = 0
    private var frameThrows = 0
    private var mode = Attention.AUTO

    /** The app is stopped from another thread once the clock has reached this. */
    private var stopAtNs = Long.MAX_VALUE

    /** What the app was told had been pushed to the sign. */
    private val published = mutableListOf<String>()

    /** Changes to the app's state, by the time they are posted; the link runs them in events(). */
    private val posted = sortedMapOf<Long, MutableList<() -> Unit>>()

    private val timingLines = mutableListOf<String>()

    private val link: SignLink = SignLink(
        wire = wire,
        baud = baud,
        name = "UART",
        events = {
            val due = posted.headMap(now + 1)
            val run = due.values.flatten()
            due.clear()
            run.forEach { it() }
        },
        answer = { buffer, count -> answer(buffer, count) },
        pushed = {
            published += chooser ?: "nothing"
            line += "told:${chooser ?: "nothing"}"
        },
        chooserFrame = {
            if (frameThrows > 0) {
                frameThrows--
                throw IllegalStateException("no frame")
            }
            now += frameCostNs
            chooser?.let { "H:$it" }
        },
        attention = { mode },
        timing = UartTimingLog(isEnabled = { true }, emit = { timingLines += it }),
        nanoTime = { now },
        sleepNanos = { nanos ->
            sleeps += nanos
            now += nanos + sleepOverrunNs
            sleepOverrunNs = 0
            if (now >= stopAtNs) stop()
        },
        log = { line += "log:$it" },
        warn = { line += "warn:$it" },
    )

    private fun stop() = link.stop()

    /** What MainViewModel and SignResponder do: a poll in chooser mode gets a report, everything else a message. */
    private fun answer(buffer: ByteArray, count: Int) {
        answers++
        if (answerThrows > 0) {
            answerThrows--
            throw IllegalStateException("boom")
        }
        now += answerCostNs
        val text = chooser
        when {
            text != null && buffer[count - 1] == '?'.code.toByte() -> link.send("{c:$text}", true)
            text != null -> link.send("H:$text", false)
            else -> {
                val message = ordinary.removeFirst()
                taken += message
                if (!link.send(message, false)) {
                    // what SignResponder and MessageManager.returnUnsent do: back to the front
                    taken.removeAt(taken.lastIndex)
                    ordinary.addFirst(message)
                }
            }
        }
    }

    private fun arrives(atMs: Long, text: String) {
        arrivals[atMs * ms] = (arrivals[atMs * ms] ?: byteArrayOf()) + text.toByteArray(Charsets.US_ASCII)
    }

    /** A command changes what the chooser shows at [atMs]; like MessageManager, the change asks for a push. */
    private fun command(atMs: Long, text: String?) {
        posted.getOrPut(atMs * ms) { mutableListOf() } += {
            chooser = text
            link.requestPush()
        }
    }

    private fun runUntil(untilMs: Long) {
        var turns = 0
        while (now < untilMs * ms) {
            link.step()
            // a loop that neither sleeps nor lets time pass is a spin (or a stopped link)
            check(++turns < 1_000_000) { "the loop does not get on: ${now / ms} ms after $turns turns" }
        }
    }

    /** Turns the loop until it has been stopped (by [stopAtNs]) and a few times more. */
    private fun runUntilStopped() {
        var turns = 0
        while (now < stopAtNs) {
            link.step()
            check(++turns < 1_000_000)
        }
        repeat(5) { link.step() }
    }

    private fun wire() = line.filter { it.startsWith("tx[") || it.startsWith("low@") }
    private fun warnings() = line.filter { it.startsWith("warn:") }
    private fun drops() = timingLines.filter { it.startsWith("drop ") }

    // ================= what counts as one request

    @Test
    fun `a request byte is answered by the read that found it, with no further wait`() {
        arrives(0, "?")
        link.step()
        assertEquals(listOf("tx[{c:A}]@0"), wire())
        assertEquals(1, reads)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun `each request byte that arrives on its own gets its own reply`() {
        arrives(0, "?")
        arrives(60, "~")
        arrives(120, "~")
        runUntil(130)
        assertEquals(listOf("tx[{c:A}]@0", "tx[H:A]@60", "tx[H:A]@120"), wire())
    }

    @Test
    fun `request bytes that are already waiting together are one request, the last byte decides`() {
        arrives(0, "?~")
        link.step()
        assertEquals(listOf("tx[H:A]@0"), wire())
    }

    @Test
    fun `bytes that do not end in a request byte are not a request, they take no message and get no frame`() {
        // the firmware prints the name of a parse error and stops listening; its `~` comes later
        chooser = null
        arrives(0, "Invalid")
        arrives(4, "Input")
        runUntil(3_000)
        assertEquals(emptyList(), wire())
        assertEquals(emptyList(), taken)
        assertEquals(
            listOf(
                "log:Read 7 bytes from UART that are not a request: [Invalid]",
                "log:Read 5 bytes from UART that are not a request: [Input]",
            ),
            line,
        )
        arrives(3_000, "~")
        runUntil(3_100)
        assertEquals(listOf("M1"), taken)
        assertEquals("M1", String(txs.single().bytes))
        assertTrue(txs.single().atNs / ms in 3_000..3_000 + SignLink.IDLE_PAUSE_MS)
    }

    @Test
    fun `an error name with its request byte behind it is one request, whatever pieces it comes in`() {
        chooser = null
        arrives(0, "Inv")
        arrives(2, "alid")
        arrives(4, "Inpu")
        arrives(6, "t~")
        runUntil(100)
        assertEquals(listOf("tx[M1]@6"), wire())
        assertEquals(listOf("M1"), taken)
    }

    @Test
    fun `more stray bytes than the buffer holds take no message either`() {
        chooser = null
        arrives(0, "x".repeat(SignLink.READ_BUFFER_LEN * 2 + 5))
        runUntil(100)
        assertEquals(emptyList(), taken)
        assertEquals(emptyList(), wire())
    }

    // ================= how often the wire is read (what the loop costs)

    @Test
    fun `an idle wire is read a hundred times a second and every empty read is followed by a sleep`() {
        runUntil(10_000)
        assertEquals(reads, sleeps.size)
        assertTrue(sleeps.all { it == SignLink.IDLE_PAUSE_MS * ms })
        assertEquals(1000 / SignLink.IDLE_PAUSE_MS, reads / 10)
    }

    @Test
    fun `while the sign is talking the wire is read at most five hundred times a second`() {
        // a chooser poll every 200 ms, as contract 2.1 has it
        for (t in 0 until 10_000 step 200) arrives(t.toLong(), "?")
        runUntil(10_000)
        assertEquals(50, txs.size)
        assertTrue(sleeps.all { it == SignLink.ACTIVE_PAUSE_MS * ms })
        val readsPerSecond = reads / 10
        assertTrue(readsPerSecond <= 1000 / SignLink.ACTIVE_PAUSE_MS + 5, "$readsPerSecond reads a second")
        // every read that found nothing was followed by a sleep: the loop never spins
        assertEquals(reads - txs.size, sleeps.size)
    }

    @Test
    fun `a poll is seen at most one short pause after it arrives`() {
        arrives(0, "~")
        for (t in 201 until 5_000 step 197) arrives(t.toLong(), "?")
        runUntil(5_000)
        val late = txs.drop(1).mapIndexed { i, tx -> tx.atNs / ms - (201 + i * 197) }
        assertTrue(late.all { it in 0..SignLink.ACTIVE_PAUSE_MS }, "late by $late ms")
    }

    @Test
    fun `the short pause ends when the sign has been silent for the active window`() {
        arrives(0, "~")
        runUntil(SignLink.ACTIVE_WINDOW_MS + 1_000L)
        assertEquals(SignLink.ACTIVE_PAUSE_MS * ms, sleeps.first())
        assertEquals(SignLink.IDLE_PAUSE_MS * ms, sleeps.last())
    }

    @Test
    fun `the cost of a read is on top of the pause, so a slow read lowers the rate`() {
        readCostNs = 1 * ms
        runUntil(1_000)
        // 10 ms pause + 1 ms read: about 91 reads, 9 percent of the time inside read()
        assertTrue(reads in 88..92, "$reads reads")
    }

    @Test
    fun `a sign that polls without end does not hold up a push`() {
        // a poll every 5 ms: the reports alone (3.5 ms each) fill most of the line
        readCostNs = ms / 5
        for (t in 0..400 step 5) arrives(t.toLong(), "?")
        command(0, "B")
        runUntil(150)
        // held back once behind the first report (rule 6), not behind every one
        assertEquals(1, lows.size)
        assertTrue(lows.single() / ms in SignLink.REPORT_SETTLE_MS..SignLink.REPORT_SETTLE_MS + 10, "LOW at ${lows.single() / ms} ms")
    }

    // ================= errors

    @Test
    fun `a failing read is waited out and tried again, and a dead port does not flood the log`() {
        failReads = 60 // six seconds of it
        arrives(0, "~")
        runUntil(7_000)
        assertEquals(listOf("tx[H:A]@6000"), wire())
        assertEquals(2, warnings().size, warnings().toString()) // at the start, and once more after five seconds
        assertTrue(warnings().first().contains("read failed"))
        assertEquals(List(60) { SignLink.ERROR_PAUSE_MS * ms }, sleeps.take(60))
    }

    @Test
    fun `a reply that throws does not end the loop`() {
        answerThrows = 1
        arrives(0, "~")
        arrives(50, "~")
        runUntil(100)
        assertEquals(listOf("tx[H:A]@50"), wire())
        assertEquals(1, warnings().size)
    }

    @Test
    fun `a change that throws, or a frame that cannot be made, does not end the loop`() {
        posted[0] = mutableListOf({ error("a bad command") })
        arrives(150, "~")
        runUntil(300)
        assertEquals(listOf("tx[H:A]@150"), wire())
        assertTrue(warnings().single().contains("a change failed"), warnings().toString())

        frameThrows = 1
        command(300, "B")
        runUntil(1_000)
        // the push stayed wanted and went out at the next try
        assertEquals(listOf("tx[H:A]@150", "low@400", "tx[H:B]@470"), wire())
        assertTrue(warnings().last().contains("push failed"))
    }

    // ================= the log comes after the write

    @Test
    fun `a reply is on the line before anything about it is logged`() {
        chooser = null
        arrives(0, "~")
        link.step()
        assertEquals(
            listOf(
                "tx[M1]@0",
                "log:Read 1 bytes from UART: [~]",
                "log:Writing >M1< to UART...",
                "log:Wrote 2 of 2 bytes to UART",
            ),
            line,
        )
    }

    @Test
    fun `a poll report is not logged`() {
        arrives(0, "?")
        link.step()
        assertEquals(listOf("tx[{c:A}]@0"), line)
    }

    // ================= writes: all of a frame, or a failure (class D)

    @Test
    fun `a reply the transmitter takes in pieces goes out whole`() {
        chooser = "ABCDEFGHIJKLMNOPQRSTUVWXY"
        takesPerWrite = 10
        arrives(0, "~")
        link.step()
        assertEquals(listOf("tx[H:ABCDEFGH]@0", "tx[IJKLMNOPQR]@2", "tx[STUVWXY]@4"), wire())
        assertTrue(line.contains("log:Wrote 27 of 27 bytes to UART"))
    }

    @Test
    fun `a reply the transmitter does not take is a short write, said so, and the loop goes on`() {
        takesBeforeStuck = 3
        stuckUntilNs = 200 * ms
        arrives(0, "~")
        arrives(300, "~")
        runUntil(400)
        assertEquals(listOf("tx[H:A]@0", "tx[H:A]@300"), wire())
        chooser = "LONGER"
        takesBeforeStuck = 3
        stuckUntilNs = 600 * ms
        arrives(400, "~")
        runUntil(500)
        assertEquals("tx[H:L]@400", wire().last())
        assertTrue(line.contains("log:Wrote 3 of 8 bytes to UART"))
        // given up when the line had stood still for WRITE_PATIENCE_MS (the 3 bytes were gone at 402), not sooner and not never
        assertTrue(timingLines.any { it.startsWith("tx t_us=${(400 + 4 + SignLink.WRITE_PATIENCE_MS) * 1000} n=3/8 ") }, timingLines.takeLast(3).toString())
    }

    @Test
    fun `the rest of a frame is not sent after the line has stood still too long, and its message is not lost`() {
        // the gate's case: one byte is taken, the thread is kept waiting 80 ms; the sign gave up after 60
        chooser = null
        ordinary.addFirst("micOff")
        takesBeforeStuck = 1
        stuckUntilNs = 5 * ms
        sleepOverrunNs = 80 * ms
        arrives(0, "~")
        runUntil(150)
        assertEquals(listOf("tx[m]@0"), wire()) // not a byte more
        assertEquals(emptyList(), taken)
        assertEquals("micOff", ordinary.first()) // back at the front
        assertTrue(line.contains("log:Wrote 1 of 6 bytes to UART"))
        // the sign's read failed, it threw the torn start away and asks again
        arrives(200, "~")
        runUntil(300)
        assertEquals(listOf("tx[m]@0", "tx[micOff]@200"), wire())
        assertEquals(listOf("micOff"), taken)
    }

    @Test
    fun `a frame taken in pieces close together is whole, the clock is looked at before every piece`() {
        chooser = "ABCDEFGHIJKLMNOPQRSTUVWXY"
        takesPerWrite = 10
        sleepOverrunNs = 40 * ms // a hole, but shorter than the sign's patience
        arrives(0, "~")
        link.step()
        assertEquals(listOf("tx[H:ABCDEFGH]@0", "tx[IJKLMNOPQR]@42", "tx[STUVWXY]@44"), wire())
    }

    @Test
    fun `a poll report that is cut short is logged all the same`() {
        takesBeforeStuck = 2
        stuckUntilNs = 500 * ms
        arrives(0, "?")
        link.step()
        assertEquals(listOf("tx[{c]@0", "log:Wrote 2 of 5 bytes to UART"), line)
    }

    // ================= the push (contract 3.1): LOW, guard, the chooser frame

    @Test
    fun `a push is the LOW, the guard, then the chooser frame, once, and nothing without a change`() {
        runUntil(100)
        assertEquals(emptyList(), line)
        command(100, "B")
        runUntil(2_000)
        assertEquals(listOf("low@100", "tx[H:B]@170"), wire())
        assertEquals(emptyList(), faults)
    }

    @Test
    fun `the guard counts from the call of the break, whether the call returns at once or when the line is HIGH`() {
        command(0, "B")
        runUntil(500)
        breakBlocks = true
        command(1_000, "C")
        runUntil(2_000)
        assertEquals(listOf("low@0", "tx[H:B]@70", "low@1000", "tx[H:C]@1070"), wire())
        assertEquals(emptyList(), faults)
    }

    @Test
    fun `changes in a burst are one push of the newest state`() {
        command(0, "B")
        command(0, "C")
        command(0, "D")
        runUntil(1_000)
        assertEquals(listOf("low@0", "tx[H:D]@70"), wire())
    }

    @Test
    fun `a change during a push is the next push, not lost and not queued, and the newest state goes last`() {
        command(0, "B")
        command(10, "C") // while the line is LOW
        command(40, "D")
        command(65, "E") // in the guard
        runUntil(2_000)
        val secondAt = 70 + 2 + SignLink.PUSH_SPACING_MS // the frame of the first has left after 2 ms
        assertEquals(listOf("low@0", "tx[H:B]@70", "low@$secondAt", "tx[H:E]@${secondAt + 70}"), wire())
    }

    @Test
    fun `a command posted before the link runs is carried out by its first turn`() {
        command(0, "B")
        link.step()
        assertEquals(listOf("low@0", "tx[H:B]@70"), wire())
    }

    // ================= what the app is told of a push (the screen, Bluetooth)

    @Test
    fun `a pushed frame is told to the app once it is written, and logged, after the write`() {
        command(0, "B")
        runUntil(1_000)
        assertEquals(listOf("B"), published)
        assertEquals(listOf("low@0", "tx[H:B]@70", "log:Pushed >H:B< to UART", "told:B"), line)
    }

    @Test
    fun `leaving chooser mode has no frame, so nothing is told, and the attention is logged`() {
        command(0, null)
        runUntil(1_000)
        assertEquals(emptyList(), published)
        assertEquals(listOf("low@0", "log:Pushed attention alone to UART"), line)
    }

    @Test
    fun `a push that failed tells nothing, the one that then goes out is told once`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 2
        stuckUntilNs = 150 * ms
        runUntil(200)
        assertEquals(emptyList(), published)
        assertTrue(warnings().single().contains("the frame was not taken in full"), warnings().toString())
        runUntil(2_000)
        assertEquals(listOf("BCDEFGH"), published)
    }

    @Test
    fun `a push that is switched off, or settled by a full reply, is not told as a push`() {
        mode = Attention.OFF
        command(0, "B")
        runUntil(500)
        mode = Attention.AUTO
        command(500, "C")
        arrives(500, "~")
        runUntil(1_500)
        assertEquals(emptyList(), published)
        assertEquals(listOf("tx[H:C]@500"), wire())
    }

    // ================= time on the line (class C)

    @Test
    fun `the break is not asked for while a reply is still leaving, and the frame waits for that break and its guard`() {
        // the gate's case: a reply with 40 ms left on the line, a break call that returns at once
        chooser = "X".repeat(56) // 58 bytes, 40 ms
        arrives(0, "~")
        command(1, "Y")
        runUntil(1_000)
        assertEquals(emptyList(), faults)
        val replyGoneNs = txs[0].goneAtNs
        assertEquals(40, replyGoneNs / ms)
        assertTrue(lows.single() >= replyGoneNs, "break at ${lows.single() / ms} ms")
        assertEquals("tx[H:Y]@110", wire().last())
        assertTrue(txs[1].atNs >= lows.single() + (SignLink.ATTN_BREAK_MS + SignLink.ATTN_GUARD_MS) * ms)
    }

    @Test
    fun `a write call that stalls before it hands the bytes over does not free the line early`() {
        // the gate's case: the reply's bytes are on the line from the RETURN of the write
        chooser = "X".repeat(56) // 58 bytes, 40 ms
        writeStallNs = 30 * ms
        arrives(0, "~")
        command(1, "Y")
        runUntil(1_000)
        assertEquals(emptyList(), faults)
        assertEquals(70, txs[0].goneAtNs / ms) // handed over at 30, gone at 70
        assertTrue(lows.single() >= txs[0].goneAtNs, "break at ${lows.single() / ms} ms")
    }

    @Test
    fun `the run of zeros waits for a reply that is leaving too, and the frame for the zeros and the guard`() {
        mode = Attention.ZEROS
        chooser = "X".repeat(56)
        arrives(0, "~")
        command(1, "Y")
        runUntil(1_000)
        val zeros = txs[1]
        assertEquals(72, zeros.bytes.size) // 50 ms of LOW at 14400 baud
        assertTrue(zeros.bytes.all { it == 0.toByte() })
        assertTrue(zeros.atNs >= txs[0].goneAtNs, "zeros at ${zeros.atNs / ms} ms")
        assertEquals(50, (zeros.goneAtNs - zeros.leavesFromNs + ms / 2) / ms)
        assertEquals("H:Y", String(txs[2].bytes))
        assertTrue(txs[2].atNs >= zeros.goneAtNs + SignLink.ATTN_GUARD_MS * ms)
        assertEquals(0, lows.size)
    }

    // ================= a push takes nothing (class A)

    @Test
    fun `leaving chooser mode is the LOW alone, takes no message, and the request that follows gets the first one`() {
        ordinary.addFirst("micOff") // a one-time command that must not be lost
        command(100, null)
        runUntil(250)
        assertEquals(listOf("low@100"), wire())
        assertEquals(emptyList(), taken)
        arrives(320, "~") // the sign found nothing after the LOW and asks
        runUntil(1_000)
        assertEquals(listOf("low@100", "tx[micOff]@320"), wire())
        assertEquals(listOf("micOff"), taken)
    }

    @Test
    fun `pushes never take a message, however the changes fall`() {
        // in and out of chooser mode, changes inside pushes, no request from the sign at all
        var t = 0L
        for (text in listOf("B", null, "C", "D", null, null, "E", null)) {
            command(t, text)
            t += 37
        }
        runUntil(5_000)
        assertEquals(emptyList(), taken)
        assertEquals(50, ordinary.size)
        assertTrue(lows.size >= 2)
    }

    // ================= one request, one frame (class B)

    @Test
    fun `rule 2, a full answer to a waiting request is the push, the line is not pulled LOW on a sign that is waiting`() {
        command(0, "B")
        arrives(0, "~")
        runUntil(1_000)
        assertEquals(listOf("tx[H:B]@0"), wire())
    }

    @Test
    fun `rule 2, an ordinary message in answer to a request also settles the push of leaving`() {
        command(0, null)
        arrives(0, "~")
        runUntil(1_000)
        assertEquals(listOf("tx[M1]@0"), wire())
        assertEquals(listOf("M1"), taken)
    }

    @Test
    fun `rule 2 is for full messages, a poll report does not replace the push`() {
        command(0, "B")
        arrives(0, "?")
        runUntil(1_000)
        // the report (5 bytes, 3 ms on the line), then the push, REPORT_SETTLE_MS behind it (rule 6)
        assertEquals(listOf("tx[{c:B}]@0", "low@18", "tx[H:B]@88"), wire())
        assertEquals(emptyList(), faults)
    }

    @Test
    fun `rule 6, a 2 point 1 sign that reads the change in the report and asks at once gets the message, not the LOW`() {
        command(0, "B")
        arrives(0, "?")
        arrives(9, "~") // it saw another checksum and asks for the message, as its contract says
        runUntil(1_000)
        assertEquals(listOf("tx[{c:B}]@0", "tx[H:B]@10"), wire())
        assertEquals(emptyList(), drops())
    }

    @Test
    fun `rule 6 is for a report that carries the change, a report before the change holds nothing back`() {
        arrives(0, "?")
        command(1, "B")
        runUntil(1_000)
        assertEquals(listOf("tx[{c:A}]@0", "low@3", "tx[H:B]@73"), wire())
        assertEquals(emptyList(), faults)
    }

    @Test
    fun `rule 2 needs the answer to have gone out, a reply that failed leaves the push wanted`() {
        takesBeforeStuck = 0
        stuckUntilNs = 60 * ms
        command(0, "B")
        arrives(0, "~")
        runUntil(1_000)
        assertEquals(listOf("low@50", "tx[H:B]@120"), wire())
    }

    @Test
    fun `rule 3, a request that came while the frame was being chosen gets no frame of its own`() {
        chooser = null
        command(0, "B")
        frameCostNs = 30 * ms // the gate's case: slow to make the frame
        arrives(10, "~")
        runUntil(250)
        assertEquals(listOf("low@30", "tx[H:B]@100"), wire())
        assertEquals(1, drops().size)
        assertTrue(drops().single().endsWith("n=1 bytes=[~]"))
    }

    @Test
    fun `rule 3, requests that came between the choice of the frame and its write get no frame of their own`() {
        command(0, "B")
        arrives(10, "?") // into the LOW
        arrives(65, "~") // into the guard
        runUntil(250)
        assertEquals(listOf("low@0", "tx[H:B]@70"), wire())
        assertTrue(drops().single().endsWith("n=2 bytes=[?~]"))
    }

    @Test
    fun `rule 3, a request that came while the frame was leaving gets no frame of its own, one that came after it does`() {
        val long = "L".repeat(70) // a frame of 72 bytes: 50 ms on the line, from 70 to 120
        command(0, long)
        arrives(100, "~")
        runUntil(125)
        assertEquals(listOf("low@0", "tx[H:$long]@70"), wire())
        assertEquals(1, drops().size)
        arrives(130, "~")
        runUntil(200)
        assertEquals(listOf("low@0", "tx[H:$long]@70", "tx[H:$long]@130"), wire())
    }

    @Test
    fun `rule 3, what the sign sent into the LOW of leaving takes no message`() {
        command(0, null)
        arrives(20, "~")
        arrives(60, "?")
        runUntil(250)
        assertEquals(listOf("low@0"), wire())
        assertEquals(emptyList(), taken)
        arrives(300, "~")
        runUntil(400)
        assertEquals(listOf("M1"), taken)
    }

    @Test
    fun `one request never takes two messages, with requests thrown at every moment of leaving`() {
        chooser = "A"
        command(1_000, null)
        var asked = 0
        for (t in 0 until 1_300 step 7) {
            arrives(t.toLong(), "~")
            asked++
        }
        runUntil(1_400)
        assertEquals(emptyList(), warnings())
        // before leaving every request got the chooser frame; after, at most one message each
        val answered = txs.count { String(it.bytes).startsWith("M") }
        assertEquals(answered, taken.size)
        assertEquals((1..taken.size).map { "M$it" }, taken)
        assertEquals(txs.size, asked - drops().sumOf { it.substringAfter("n=").substringBefore(' ').toInt() })
    }

    // ================= a push that did not go out is not done (class D)

    @Test
    fun `a break that fails sends nothing, the push stays wanted, and is tried again with the run of zeros`() {
        failBreaks = 1
        command(0, "B")
        runUntil(90)
        assertEquals(emptyList(), wire())
        runUntil(1_000)
        assertEquals(listOf("tx[00x72]@100", "tx[H:B]@170"), wire())
        assertTrue(warnings().single().contains("break failed"))
    }

    @Test
    fun `told to use the break, a break that fails is tried again as a break`() {
        mode = Attention.BREAK
        failBreaks = 1
        command(0, "B")
        runUntil(1_000)
        assertEquals(listOf("low@100", "tx[H:B]@170"), wire())
    }

    @Test
    fun `rule 4, a request that came after a failed push is answered, and that answer settles the push`() {
        failBreaks = 1
        command(0, "B")
        arrives(60, "~")
        runUntil(1_000)
        assertEquals(listOf("tx[H:B]@60"), wire())
        assertEquals(emptyList(), drops())
    }

    @Test
    fun `rule 4, a break that threw may have pulled the line LOW, a request sent into that time is void`() {
        failBreaks = 1
        chooser = "A"
        command(0, null) // leaving: an answer would take a message
        arrives(20, "~")
        runUntil(90)
        assertEquals(emptyList(), wire())
        assertEquals(emptyList(), taken)
        assertEquals(1, drops().size)
    }

    @Test
    fun `rule 4, a request sent into a LOW whose frame then failed is void too`() {
        // the LOW goes out, the frame is not taken; the request is 90 ms old when it is read
        command(0, "BCDEFGH")
        takesBeforeStuck = 0
        stuckUntilNs = 150 * ms
        arrives(30, "~")
        runUntil(2_000)
        assertEquals(listOf("low@0", "low@220", "tx[H:BCDEFGH]@290"), wire())
        assertEquals(1, drops().size)
    }

    @Test
    fun `a run of zeros the transmitter does not take is no attention, no frame is sent after it, and it is tried again`() {
        mode = Attention.ZEROS
        takesBeforeStuck = 0
        stuckUntilNs = 60 * ms
        command(0, "B")
        runUntil(90)
        assertEquals(emptyList(), wire())
        runUntil(1_000)
        assertEquals(listOf("tx[00x72]@100", "tx[H:B]@170"), wire())
    }

    @Test
    fun `rule 4, half a run of zeros is no attention either, and what the sign sent into it is void`() {
        mode = Attention.ZEROS
        chooser = "A"
        takesBeforeStuck = 30 // 21 ms of LOW get out
        stuckUntilNs = 60 * ms
        command(0, null) // leaving: a request answered now would take a message
        arrives(10, "~")
        runUntil(90)
        assertEquals(listOf("tx[00x30]@0"), wire())
        assertEquals(emptyList(), taken)
        assertEquals(1, drops().size)
        runUntil(1_000)
        // in one piece or not at all: the half is not topped up, the whole run comes again
        assertEquals(listOf("tx[00x30]@0", "tx[00x72]@100"), wire())
        assertEquals(emptyList(), taken)
    }

    @Test
    fun `a frame the transmitter takes only the start of is a failed push, tried again in full`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 2
        stuckUntilNs = 150 * ms
        runUntil(2_000)
        // 70: two bytes, gone at 71; the line stands still for 50 ms, given up at 122; again 100 ms later
        assertEquals(listOf("low@0", "tx[H:]@70", "low@222", "tx[H:BCDEFGH]@292"), wire())
        assertEquals(1, timingLines.count { it.startsWith("push ") })
    }

    @Test
    fun `rule 4, after a frame that failed a request is answered, not read away`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 2
        stuckUntilNs = 125 * ms
        arrives(130, "~")
        runUntil(2_000)
        assertEquals(listOf("low@0", "tx[H:]@70", "tx[H:BCDEFGH]@130"), wire())
        assertEquals(emptyList(), drops())
    }

    @Test
    fun `a frame whose write throws is a failed push, tried again`() {
        command(0, "B")
        failWrites = 1
        runUntil(2_000)
        assertEquals(listOf("low@0", "low@170", "tx[H:B]@240"), wire())
        assertTrue(warnings().first().contains("write failed"))
    }

    // ================= liveness (class G)

    @Test
    fun `rule 5, however fast the changes come the sign is answered between the pushes`() {
        // a change every 30 ms for five seconds, and a 2.1 sign that polls every 200 ms
        var flip = false
        for (t in 0 until 5_000 step 30) {
            flip = !flip
            command(t.toLong(), if (flip) "B" else "C")
        }
        for (t in 5 until 5_000 step 200) arrives(t.toLong(), "?")
        runUntil(5_000)
        val reports = txs.count { String(it.bytes).startsWith("{c:") }
        assertTrue(reports >= 15, "$reports of 25 polls answered")
        // and the pushes kept their distance
        val gaps = lows.zipWithNext { a, b -> (b - a) / ms }
        assertTrue(gaps.all { it >= SignLink.PUSH_SPACING_MS }, gaps.toString())
        assertEquals(emptyList(), faults)
    }

    @Test
    fun `switched off before the push starts, nothing is sent and a waiting request is still answered`() {
        command(0, "B")
        arrives(0, "?")
        mode = Attention.OFF
        runUntil(500)
        assertEquals(listOf("tx[{c:B}]@0"), wire())
        assertEquals(emptyList(), drops())
        // and it does not come back by itself when the switch is turned on again
        mode = Attention.AUTO
        runUntil(1_000)
        assertEquals(listOf("tx[{c:B}]@0"), wire())
    }

    @Test
    fun `stopped while the line is LOW, the push writes no frame`() {
        command(0, "B")
        stopAtNs = 30 * ms
        runUntilStopped()
        assertEquals(listOf("low@0"), wire())
        assertEquals(emptyList(), warnings()) // it is over, not a push that failed
        assertEquals(emptyList(), published)
    }

    @Test
    fun `stopped while a push waits for the line, it does not pull the line LOW`() {
        chooser = "X".repeat(56) // a reply of 40 ms
        arrives(0, "~")
        command(1, "Y")
        stopAtNs = 10 * ms
        runUntilStopped()
        assertEquals(1, wire().size)
        assertEquals(0, lows.size)
    }

    @Test
    fun `the switch is read as typed, and anything else is the default`() {
        assertEquals(Attention.BREAK, Attention.of("break"))
        assertEquals(Attention.ZEROS, Attention.of("zeros"))
        assertEquals(Attention.OFF, Attention.of("off"))
        assertEquals(Attention.OFF, Attention.of(" off\n"))
        assertEquals(Attention.AUTO, Attention.of(""))
        assertEquals(Attention.AUTO, Attention.of(null))
        assertEquals(Attention.AUTO, Attention.of("OFF!"))
    }

    @Test
    fun `after stop no message is taken, even for a request that is already there`() {
        chooser = null
        arrives(0, "~")
        link.stop()
        link.step()
        assertEquals(0, answers) // not asked at all: nothing is taken, no index moves
        assertEquals(emptyList(), taken)
        assertEquals(emptyList(), wire())
    }

    // ================= the timing log

    @Test
    fun `a read, a write, a push and what was read away are each one timing line`() {
        arrives(3, "?")
        runUntil(20)
        command(20, "B")
        arrives(30, "~")
        runUntil(200)
        assertEquals("rx t_us=10000 n=1 bytes=[?] chunks=1 first_us=10000 empty_us=0", timingLines.single { it.startsWith("rx ") })
        assertEquals("tx t_us=10000 n=5/5 kind=report call_us=10000 rx_us=10000", timingLines.first { it.startsWith("tx ") })
        assertEquals("push t_us=90000 call_us=20000 break_us=20000 guard_us=90000 wanted_us=20000 n=3", timingLines.single { it.startsWith("push ") })
        assertEquals("drop t_us=90000 n=1 bytes=[~]", timingLines.single { it.startsWith("drop ") })
    }

    // ================= on a real thread, with the real clock

    /** A link whose every sleep would last five seconds: only a wake-up gets it out in time. */
    private fun sleepy(
        wire: SignWire,
        events: () -> Unit = {},
        answer: (ByteArray, Int) -> Unit = { _, _ -> },
        chooserFrame: () -> String? = { null },
    ) = SignLink(
        wire = wire, baud = baud, name = "UART", events = events, answer = answer, chooserFrame = chooserFrame,
        timing = UartTimingLog(isEnabled = { false }, emit = {}),
        sleepNanos = { nanos -> LockSupport.parkNanos(maxOf(nanos, 5_000 * ms)) },
    )

    private open class QuietWire : SignWire {
        val reads = AtomicInteger()
        val closedBy = AtomicReference<Thread?>()
        val closes = AtomicInteger()
        override fun read(buffer: ByteArray, length: Int): Int = 0.also { reads.incrementAndGet() }
        override fun write(buffer: ByteArray, length: Int): Int = length
        override fun sendBreak(millis: Int) {}
        override fun close() {
            closes.incrementAndGet()
            closedBy.set(Thread.currentThread())
        }
    }

    @Test
    fun `stop gets the loop out of its sleep, and the loop closes the port itself`() {
        val quiet = QuietWire()
        val real = sleepy(quiet)
        val thread = Thread(real, "sign-link-test").apply { start() }
        while (quiet.reads.get() == 0) Thread.sleep(1)
        Thread.sleep(50) // it is asleep now, for five seconds
        val stopAt = System.nanoTime()
        real.stop()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertTrue(System.nanoTime() - stopAt < 1_000 * ms, "stop took ${(System.nanoTime() - stopAt) / ms} ms")
        assertEquals(1, quiet.closes.get())
        assertSame(thread, quiet.closedBy.get())
    }

    @Test
    fun `the port is not closed under a reply that is still being made`() {
        val inAnswer = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val wire = object : QuietWire() {
            var asked = false
            override fun read(buffer: ByteArray, length: Int): Int {
                if (asked) return 0
                asked = true
                buffer[0] = '~'.code.toByte()
                return 1
            }
        }
        val real = sleepy(wire, answer = { _, _ ->
            inAnswer.countDown()
            letGo.await() // a reply that takes its time
        })
        val thread = Thread(real, "sign-link-test").apply { start() }
        assertTrue(inAnswer.await(2, TimeUnit.SECONDS))
        real.stop()
        Thread.sleep(100)
        assertEquals(0, wire.closes.get()) // stop() itself closes nothing
        letGo.countDown()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertEquals(1, wire.closes.get())
        assertSame(thread, wire.closedBy.get())
    }

    @Test
    fun `a push request gets the loop out of its sleep`() {
        val pushed = CountDownLatch(1)
        val quiet = object : QuietWire() {
            override fun sendBreak(millis: Int) = pushed.countDown()
        }
        val real = sleepy(quiet)
        val thread = Thread(real, "sign-link-test").apply { start() }
        while (quiet.reads.get() == 0) Thread.sleep(1)
        Thread.sleep(50)
        real.requestPush()
        assertTrue(pushed.await(1, TimeUnit.SECONDS), "the push did not start within a second")
        real.stop()
        thread.join(2_000)
    }

    @Test
    fun `wake gets the loop out of its sleep to run what was posted`() {
        val ran = CountDownLatch(1)
        var post = false
        val quiet = QuietWire()
        val real = sleepy(quiet, events = { if (post) ran.countDown() })
        val thread = Thread(real, "sign-link-test").apply { start() }
        while (quiet.reads.get() == 0) Thread.sleep(1)
        Thread.sleep(50)
        post = true
        real.wake()
        assertTrue(ran.await(1, TimeUnit.SECONDS), "the posted change did not run within a second")
        real.stop()
        thread.join(2_000)
    }

    @Test
    fun `on the real clock an idle loop reads a hundred times a second, it does not spin`() {
        val quiet = QuietWire()
        val real = SignLink(
            wire = quiet, baud = baud, name = "UART", events = {}, answer = { _, _ -> }, chooserFrame = { null },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
        )
        val thread = Thread(real, "sign-link-test").apply { start() }
        Thread.sleep(300)
        real.stop()
        thread.join(2_000)
        assertTrue(quiet.reads.get() in 5..60, "${quiet.reads.get()} reads in 300 ms")
    }
}
