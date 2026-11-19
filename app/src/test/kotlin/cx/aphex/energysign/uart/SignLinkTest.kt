package cx.aphex.energysign.uart

import cx.aphex.energysign.message.OwnerQueue
import cx.aphex.energysign.message.Reply
import cx.aphex.energysign.message.SignResponder
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
 * The fake app is a chooser text (null outside chooser mode) and a queue of ordinary messages
 * that an answer TAKES, and hands back when its write failed, as SignResponder and
 * MessageManager do; so a message taken for nobody shows.
 *
 * Time only moves when the link sleeps or a fake call is told to take time, so times are exact.
 */
class SignLinkTest {
    private val ms = 1_000_000L
    private val baud = 14400

    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    /** The next sleep of the link lasts this much longer than asked (once): the thread was kept waiting. */
    private var sleepOverrunNs = 0L

    // ---- what the sign sends
    private val arrivals = sortedMapOf<Long, ByteArray>()
    private var reads = 0
    private var readCostNs = 0L
    private var failReads = 0

    // ---- what the app puts on the line
    /** In order: `tx[text]@T` for bytes handed over (T in ms), `log:` and `warn:` lines. */
    private val line = mutableListOf<String>()
    private val txAtMs = mutableListOf<Long>()

    /** The transmitter takes at most this many bytes per write. */
    private var takesPerWrite = Int.MAX_VALUE

    /** The transmitter takes this many more bytes and then nothing, until [stuckUntilNs]. */
    private var takesBeforeStuck = Int.MAX_VALUE
    private var stuckUntilNs = 0L
    private var failWrites = 0

    /** The next write call that takes bytes returns this much later (once): the thread was held up inside the call. */
    private var writeLastsNs = 0L

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
            if (now >= stuckUntilNs) takesBeforeStuck = Int.MAX_VALUE
            val take = minOf(length, takesPerWrite, takesBeforeStuck)
            if (take == 0) return 0
            if (takesBeforeStuck != Int.MAX_VALUE) takesBeforeStuck -= take
            txAtMs += now / ms
            line += "tx[${String(buffer, 0, take)}]@${now / ms}"
            now += writeLastsNs
            writeLastsNs = 0
            return take
        }

        override fun close() {}
    }

    // ---- the app
    /** The chooser text now; null outside chooser mode. */
    private var chooser: String? = "A"
    private val ordinary = ArrayDeque((1..50).map { "M$it" })
    private val taken = mutableListOf<String>()
    private var answers = 0
    private var answerThrows = 0

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
        timing = UartTimingLog(isEnabled = { true }, emit = { timingLines += it }),
        nanoTime = { now },
        sleepNanos = { nanos ->
            sleeps += nanos
            now += nanos + sleepOverrunNs
            sleepOverrunNs = 0
        },
        log = { line += "log:$it" },
        warn = { line += "warn:$it" },
    )

    /** What MainViewModel and SignResponder do: a poll in chooser mode gets a report, everything else a message. */
    private fun answer(buffer: ByteArray, count: Int) {
        answers++
        if (answerThrows > 0) {
            answerThrows--
            throw IllegalStateException("boom")
        }
        val text = chooser
        when {
            text != null && buffer[count - 1] == '?'.code.toByte() -> link.send("{c:$text}", Reply.REPORT)
            text != null -> link.send("H:$text", Reply.CHOOSER)
            else -> {
                val message = ordinary.removeFirst()
                taken += message
                if (!link.send(message, Reply.MESSAGE)) {
                    // back to the front
                    taken.removeAt(taken.lastIndex)
                    ordinary.addFirst(message)
                }
            }
        }
    }

    private fun arrives(atMs: Long, text: String) {
        arrivals[atMs * ms] = (arrivals[atMs * ms] ?: byteArrayOf()) + text.toByteArray(Charsets.US_ASCII)
    }

    /** A command changes the chooser at [atMs] (null: it is left). */
    private fun command(atMs: Long, text: String?) {
        posted.getOrPut(atMs * ms) { mutableListOf() } += { chooser = text }
    }

    private fun runUntil(untilMs: Long) {
        var turns = 0
        while (now < untilMs * ms) {
            link.step()
            // a loop that neither sleeps nor lets time pass is a spin
            check(++turns < 1_000_000) { "the loop does not get on: ${now / ms} ms after $turns turns" }
        }
    }

    private fun wire() = line.filter { it.startsWith("tx[") }
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
        assertTrue(txAtMs.single() in 3_000..3_000 + SignLink.IDLE_PAUSE_MS)
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
        assertEquals(50, wire().size)
        assertTrue(sleeps.all { it == SignLink.ACTIVE_PAUSE_MS * ms })
        val readsPerSecond = reads / 10
        assertTrue(readsPerSecond <= 1000 / SignLink.ACTIVE_PAUSE_MS + 5, "$readsPerSecond reads a second")
        // every read that found nothing was followed by a sleep: the loop never spins
        assertEquals(reads - wire().size, sleeps.size)
    }

    @Test
    fun `a poll is seen at most one short pause after it arrives`() {
        arrives(0, "~")
        for (t in 201 until 5_000 step 197) arrives(t.toLong(), "?")
        runUntil(5_000)
        val late = txAtMs.drop(1).mapIndexed { i, at -> at - (201 + i * 197) }
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
    fun `a change posted before a turn is made before that turn's request is answered`() {
        command(100, null)
        arrives(100, "~")
        runUntil(150)
        assertEquals(listOf("tx[M1]@100"), wire())
    }

    // ================= reads that fail

    @Test
    fun `one failed read is tried again at once, the request behind it is answered while the sign listens`() {
        // the gate's sequence: the microphone command is queued, the chooser is left, a read fails, the poll comes 1 ms later
        arrives(0, "?")
        runUntil(10) // the sign is in its chooser display
        chooser = null
        ordinary.addFirst("micOff")
        failReads = 1
        arrives(11, "?")
        runUntil(60)
        assertEquals("tx[micOff]@12", wire().last()) // 2 ms after the failure, inside the sign's 60 ms
        assertEquals(listOf("micOff"), taken)
        // and so is every later single failure: the count starts again after a read that worked
        repeat(SignLink.QUICK_READ_RETRIES + 5) {
            failReads = 1
            runUntil(now / ms + 50)
        }
        assertFalse(sleeps.contains(SignLink.ERROR_PAUSE_MS * ms))
    }

    @Test
    fun `reads that keep failing are spaced out, and a dead port does not flood the log`() {
        failReads = 62 // a little over five seconds of it
        arrives(5_500, "~")
        runUntil(6_000)
        val quick = SignLink.QUICK_READ_RETRIES
        assertEquals(List(quick) { SignLink.ACTIVE_PAUSE_MS * ms } + List(62 - quick) { SignLink.ERROR_PAUSE_MS * ms }, sleeps.take(62))
        assertEquals(2, warnings().size, warnings().toString()) // at the start, and once more after five seconds
        assertTrue(warnings().first().contains("read failed"))
        assertEquals(1, wire().size) // and it is back at work afterwards
    }

    @Test
    fun `a reply that throws, or a change that throws, does not end the loop`() {
        answerThrows = 1
        arrives(0, "~")
        arrives(50, "~")
        runUntil(100)
        assertEquals(listOf("tx[H:A]@50"), wire())
        assertTrue(warnings().single().contains("reply failed"))

        posted[200 * ms] = mutableListOf({ error("a bad command") })
        arrives(350, "~")
        runUntil(400)
        assertEquals("tx[H:A]@350", wire().last())
        assertTrue(warnings().last().contains("a change failed"), warnings().toString())
    }

    // ================= a request is answered while the sign listens

    /** The sign is in its normal display: it has asked for a message and got one in time. */
    private fun inNormalDisplay() {
        val was = chooser
        chooser = null
        arrives(now / ms, "~")
        link.step()
        chooser = was
        line.clear()
        txAtMs.clear()
        taken.clear()
        answers = 0
    }

    /** The sign is in its chooser display: it has polled and got its report. */
    private fun inChooserDisplay() {
        arrives(now / ms, "?")
        link.step()
        line.clear()
        txAtMs.clear()
        answers = 0
    }

    @Test
    fun `the gate's case, a request found after reads kept failing is old, takes nothing, and the next one gets the message`() {
        inChooserDisplay()
        chooser = null // !micOff, then !endchoose: the app has left, the sign does not know yet
        ordinary.addFirst("micOff")
        failReads = SignLink.QUICK_READ_RETRIES + 2 // 20 ms of quick tries, then two long pauses: 220 ms off the wire
        arrives(1, "?")
        runUntil(300)
        assertEquals(emptyList(), wire()) // the sign gave up 160 ms ago
        assertEquals(0, answers)
        assertEquals("micOff", ordinary.first())
        assertTrue(line.any { it.startsWith("log:Read an old request from UART") }, line.toString())
        arrives(400, "?") // it polls again
        runUntil(500)
        assertEquals(listOf("tx[micOff]@400"), wire())
        assertEquals(listOf("micOff"), taken)
    }

    @Test
    fun `in the chooser display a request found after the link slept 80 ms is old, a poll and a request for the message alike`() {
        // measured on the Pico: the 2 ms sleep runs 40 to 83 ms late a few times a minute
        inChooserDisplay()
        sleepOverrunNs = 80 * ms
        arrives(5, "?")
        runUntil(150)
        assertEquals(emptyList(), wire())
        assertEquals(1, drops().size)

        sleepOverrunNs = 80 * ms
        arrives(155, "~") // the request the sign makes after a failed poll: it listens 60 ms for that too
        runUntil(300)
        assertEquals(emptyList(), wire())
        assertEquals(2, drops().size)

        arrives(300, "~")
        runUntil(350)
        assertEquals(listOf("tx[H:A]@300"), wire())
    }

    @Test
    fun `a request that waited less than the sign listens is answered`() {
        inChooserDisplay()
        sleepOverrunNs = 30 * ms // 32 ms off the wire: the sign is still listening
        arrives(5, "?")
        runUntil(100)
        assertEquals(listOf("tx[{c:A}]@32"), wire())
    }

    @Test
    fun `outside the chooser display the sign listens a second, a request half a second old is answered and one older than that is not`() {
        inNormalDisplay()
        chooser = null
        sleepOverrunNs = 500 * ms
        arrives(5, "~")
        runUntil(600)
        assertEquals(listOf("M2"), taken)

        sleepOverrunNs = 1_000 * ms
        arrives(now / ms + 5, "~")
        runUntil(2_000)
        assertEquals(listOf("M2"), taken)
        assertEquals(1, drops().size)
    }

    @Test
    fun `a poll says the sign is in its chooser display, whatever the app has sent before`() {
        inNormalDisplay()
        chooser = null
        sleepOverrunNs = 80 * ms
        arrives(5, "?")
        runUntil(150)
        assertEquals(emptyList(), taken) // judged by 60 ms, not by a second
    }

    @Test
    fun `a link that has just started counts 60 ms until it has written a message in time`() {
        // the gate's case: the app restarts under a sign that stays in its chooser display and asks with a `~`
        chooser = null
        ordinary.addFirst("micOff")
        sleepOverrunNs = 80 * ms
        arrives(5, "~")
        runUntil(150)
        assertEquals(emptyList(), wire()) // 80 ms old: the sign may have waited only 60
        assertEquals("micOff", ordinary.first())
        arrives(200, "~") // it asks again, and is answered in time
        runUntil(250)
        assertEquals(listOf("micOff"), taken)
        sleepOverrunNs = 500 * ms // from here on the link knows the sign is in its normal display
        arrives(now / ms + 5, "~")
        runUntil(1_200)
        assertEquals(listOf("micOff", "M1"), taken)
    }

    @Test
    fun `the first byte of an answer must leave while the sign listens, a transmitter that takes it too late gets nothing more`() {
        // the gate's case: a request 45 ms old is fresh, but its first byte could only go at 70 ms; the sign left at 60
        inChooserDisplay()
        chooser = null
        ordinary.addFirst("micOff")
        sleepOverrunNs = 43 * ms // read 45 ms after the read before it
        arrives(now / ms + 1, "?")
        takesBeforeStuck = 0
        stuckUntilNs = now + 70 * ms
        runUntil(now / ms + 150)
        assertEquals(emptyList(), wire()) // nothing was written after the sign had stopped listening
        assertEquals(emptyList(), taken)
        assertEquals("micOff", ordinary.first()) // handed back
        assertTrue(line.contains("log:Wrote 0 of 6 bytes to UART"), line.toString())
    }

    @Test
    fun `the first edge, the request that brings the chooser message is judged by a second, every request after it by 60 ms`() {
        inNormalDisplay()
        chooser = "A" // !choose
        runUntil(1_000)
        sleepOverrunNs = 300 * ms
        arrives(1_001, "~")
        runUntil(1_400)
        assertEquals(listOf("tx[H:A]@1302"), wire()) // 300 ms old, answered: the sign was in its normal display
        // the chooser message has put the sign in its chooser display: from here on 80 ms is too old
        sleepOverrunNs = 80 * ms
        arrives(now / ms + 1, "~")
        runUntil(now / ms + 200)
        assertEquals(1, wire().size)
        assertEquals(1, drops().size)
    }

    @Test
    fun `the second edge, after the chooser is left the first answer in time ends the chooser display`() {
        inChooserDisplay()
        command(100, null) // !endchoose
        arrives(200, "?")
        runUntil(250)
        assertEquals(listOf("tx[M1]@200"), wire()) // fresh: the sign gets the message and leaves its chooser display
        sleepOverrunNs = 500 * ms
        arrives(now / ms + 5, "~")
        runUntil(1_200)
        assertEquals(listOf("M1", "M2"), taken) // and from here on it listens a second again
    }

    @Test
    fun `a message that was not written in full does not end the chooser display`() {
        inChooserDisplay()
        command(100, null)
        takesBeforeStuck = 0
        stuckUntilNs = 300 * ms
        arrives(200, "?")
        runUntil(400) // the write of M1 failed: the sign is still in its chooser display
        sleepOverrunNs = 80 * ms
        arrives(405, "~")
        runUntil(600)
        assertEquals(emptyList(), taken)
        assertEquals(emptyList(), wire())
    }

    @Test
    fun `more than a full read of old bytes is old to its last byte`() {
        // 33 bytes with a request byte last: the first read is full, so it says nothing about when the rest came
        inChooserDisplay()
        chooser = null
        sleepOverrunNs = 80 * ms
        arrives(5, "x".repeat(SignLink.READ_BUFFER_LEN) + "?")
        runUntil(200)
        assertEquals(emptyList(), wire())
        assertEquals(emptyList(), taken)
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

    // ================= writes: all of a frame in time, or a failure

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
        chooser = "LONGER"
        takesBeforeStuck = 3
        stuckUntilNs = 200 * ms
        arrives(0, "~")
        runUntil(100)
        assertEquals(listOf("tx[H:L]@0"), wire())
        assertTrue(line.contains("log:Wrote 3 of 8 bytes to UART"))
        // given up when the line had stood still for WRITE_PATIENCE_MS (the 3 bytes were gone at 2), not sooner and not never
        assertTrue(timingLines.any { it.startsWith("tx t_us=${(4 + SignLink.WRITE_PATIENCE_MS) * 1000} n=3/8 ") }, timingLines.toString())
        arrives(300, "~")
        runUntil(400)
        assertEquals(listOf("tx[H:L]@0", "tx[H:LONGER]@300"), wire())
    }

    @Test
    fun `a reply of which nothing is taken is given up after the same patience`() {
        takesBeforeStuck = 0
        stuckUntilNs = 200 * ms
        arrives(0, "~")
        runUntil(100)
        assertEquals(emptyList(), wire())
        assertTrue(timingLines.any { it.startsWith("tx t_us=${SignLink.WRITE_PATIENCE_MS * 1000} n=0/3 ") }, timingLines.toString())
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
        link.step()
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
    fun `the line stands still from the start of the write that took the last byte, a call that returned late does not stretch it`() {
        // one byte is taken at once, but the call returns 30 ms later; the sign's 60 ms run from the byte, not from the return
        inNormalDisplay()
        chooser = null
        val long = "m".repeat(60) // long enough that the time for the whole frame (42 ms + 50) is not what stops it
        ordinary.addFirst(long)
        takesBeforeStuck = 1
        stuckUntilNs = now + 70 * ms // the transmitter takes the rest 70 ms after the first byte: too late
        writeLastsNs = 30 * ms
        arrives(now / ms, "~")
        runUntil(now / ms + 150)
        assertEquals(1, wire().size) // the first byte and nothing more
        assertEquals(emptyList(), taken)
        assertEquals(long, ordinary.first())
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
    fun `a transmitter that takes a byte now and then does not keep the link for longer than the frame needs`() {
        // each small success used to renew the wait; a long frame could hold the one thread for seconds
        inNormalDisplay()
        chooser = "X".repeat(98) // a frame of 100 bytes: 69 ms on the line
        takesPerWrite = 1 // one byte every 2 ms: 200 ms for the frame
        val start = now / ms
        arrives(start, "~")
        link.step()
        val tookMs = now / ms - start
        assertTrue(tookMs <= 69 + SignLink.WRITE_PATIENCE_MS + SignLink.ACTIVE_PAUSE_MS, "the frame held the link for $tookMs ms")
        assertTrue(line.any { it.matches(Regex("log:Wrote \\d+ of 100 bytes to UART")) && !it.contains("Wrote 100 of") }, line.takeLast(2).toString())
    }

    @Test
    fun `a poll report that is cut short is logged all the same`() {
        takesBeforeStuck = 2
        stuckUntilNs = 500 * ms
        arrives(0, "?")
        link.step()
        assertEquals(listOf("tx[{c]@0", "log:Wrote 2 of 5 bytes to UART"), line)
    }

    @Test
    fun `a write that throws is a failed frame, said once, and the loop goes on`() {
        chooser = null
        failWrites = 1
        arrives(0, "~")
        arrives(100, "~")
        runUntil(200)
        assertEquals(listOf("tx[M1]@100"), wire()) // the message of the failed write, again
        assertEquals(listOf("M1"), taken)
        assertTrue(warnings().single().contains("write failed"))
    }

    // ================= stopping

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
    fun `a read, a write and what was read away are each one timing line`() {
        arrives(3, "?")
        arrives(13, "x")
        runUntil(30)
        assertEquals("rx t_us=10000 n=1 bytes=[?] chunks=1 first_us=10000 empty_us=0", timingLines.single { it.startsWith("rx ") })
        assertEquals("tx t_us=10000 n=5/5 kind=report call_us=10000 rx_us=10000", timingLines.single { it.startsWith("tx ") })
        assertEquals("drop t_us=14000 n=1 bytes=[x]", timingLines.single { it.startsWith("drop ") })
    }

    // ================= on a real thread, with the real clock

    /** A link whose every sleep would last five seconds: only a wake-up gets it out in time. */
    private fun sleepy(
        wire: SignWire,
        events: () -> Unit = {},
        answer: (ByteArray, Int) -> Unit = { _, _ -> },
    ) = SignLink(
        wire = wire, baud = baud, name = "UART", events = { events() }, answer = answer,
        timing = UartTimingLog(isEnabled = { false }, emit = {}),
        sleepNanos = { nanos -> LockSupport.parkNanos(maxOf(nanos, 5_000 * ms)) },
    )

    private open class QuietWire : SignWire {
        val reads = AtomicInteger()
        val closedBy = AtomicReference<Thread?>()
        val closes = AtomicInteger()
        override fun read(buffer: ByteArray, length: Int): Int = 0.also { reads.incrementAndGet() }
        override fun write(buffer: ByteArray, length: Int): Int = length
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
    fun `stop during an owner change leaves the rest queued and closes before another read`() {
        val owner = OwnerQueue()
        val quiet = QuietWire()
        val real = SignLink(
            wire = quiet, baud = baud, name = "UART", answer = { _, _ -> },
            events = { stopped -> owner.drain(stopped = stopped) },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
        )
        val ran = mutableListOf<String>()
        owner.post {
            ran += "first"
            real.stop()
        }
        owner.post { ran += "second" }
        real.run()
        assertEquals(listOf("first"), ran)
        assertEquals(0, quiet.reads.get())
        assertEquals(1, quiet.closes.get())
        assertEquals(1, owner.drain())
        assertEquals(listOf("first", "second"), ran)
    }

    @Test
    fun `stop during adapter preparation finishes only the adapter already started and closes the wire`() {
        val quiet = QuietWire()
        var prepared = 0
        lateinit var real: SignLink
        real = SignLink(
            wire = quiet, baud = baud, name = "UART", events = {}, answer = { _, _ -> },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            prepareAnswers = { stopped ->
                prepared = SignResponder.warmUp {
                    val wasStopped = stopped()
                    // The first preparation has permission to start. Stop before the next one.
                    if (!wasStopped) real.stop()
                    wasStopped
                }
                real.stop() // keep the fixture finite even if warmUp ignores the callback
            },
        )
        real.run()
        assertEquals(1, prepared, "only the in-flight adapter should finish")
        assertEquals(0, quiet.reads.get())
        assertEquals(1, quiet.closes.get())
        assertSame(Thread.currentThread(), quiet.closedBy.get())
    }

    @Test
    fun `a request buffered during a cold start cannot borrow a new 50 ms window`() {
        arrives(0, "~")
        arrives(621, "~")
        lateinit var cold: SignLink
        cold = SignLink(
            wire = wire, baud = baud, name = "UART", events = {},
            prepareAnswers = { now += 611 * ms },
            answer = { _, _ ->
                cold.send("FIRST", Reply.MESSAGE)
                cold.stop()
            },
            timing = UartTimingLog(isEnabled = { true }, emit = { timingLines += it }),
            nanoTime = { now }, sleepNanos = { now += it },
        )
        cold.run()
        assertEquals(1, drops().size)
        assertEquals(listOf("tx[FIRST]@621"), wire())
    }

    @Test
    fun `failure to prewarm a writer still lets the link answer requests`() {
        for (failure in listOf(IllegalStateException(), LinkageError())) verifyFailedPreparation(failure)
    }

    @Test
    fun expiredPreparationRetriesOnTheLinkOnceAndKeepsServingReads() {
        var passes = 0
        var turns = 0
        lateinit var real: SignLink
        val quiet = QuietWire()
        real = SignLink(wire = quiet, baud = baud, name = "UART", answer = { _, _ -> },
            events = {
                if (++turns == 4) real.stop() else repeat(3) { real.prepareAgain() }
            },
            prepareAnswers = { stopped ->
                if (++passes == 1) {
                    now += 2_001 * ms
                    assertTrue(stopped())
                } else assertFalse(stopped())
            },
            timing = UartTimingLog(isEnabled = { false }, emit = {}), nanoTime = { now }, sleepNanos = {},
        )
        real.run()
        assertEquals(2, passes)
        assertEquals(3, quiet.reads.get())
    }

    private fun verifyFailedPreparation(failure: Throwable) {
        val quiet = object : QuietWire() {
            override fun read(buffer: ByteArray, length: Int): Int {
                buffer[0] = '~'.code.toByte()
                return 1
            }
        }
        lateinit var real: SignLink
        var wrote = false
        real = SignLink(
            wire = quiet, baud = baud, name = "UART", events = {},
            answer = { _, _ ->
                wrote = real.send("FIRST", Reply.MESSAGE)
                real.stop()
            },
            prepareAnswers = { throw failure },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            nanoTime = { now }, warn = { line += it },
        )
        real.run()
        assertTrue(wrote)
        assertTrue(line.any { it.contains(failure.javaClass.name) && it.contains("sign link continues") })
        assertEquals(1, quiet.closes.get())
    }

    @Test
    fun `the port is not closed under a reply that is still being made`() {
        val inAnswer = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val wire = object : QuietWire() {
            override fun read(buffer: ByteArray, length: Int): Int {
                if (reads.incrementAndGet() != 2) return 0 // establish freshness, then ask once
                buffer[0] = '~'.code.toByte()
                return 1
            }
        }
        var clock = 0L
        val real = SignLink(
            wire = wire, baud = baud, name = "UART", events = {},
            prepareAnswers = { clock += 611 * ms },
            nanoTime = { clock }, sleepNanos = { nanos ->
                clock += nanos
                LockSupport.parkNanos(nanos)
            },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            answer = { _, _ ->
                inAnswer.countDown()
                letGo.await() // the port must stay open until this reply returns
            },
        )
        val thread = Thread(real, "sign-link-test").apply { start() }
        try {
            assertTrue(inAnswer.await(2, TimeUnit.SECONDS))
            real.stop()
            assertEquals(0, wire.closes.get()) // stop() itself closes nothing
        } finally {
            real.stop()
            letGo.countDown()
            thread.join(2_000)
        }
        assertFalse(thread.isAlive)
        assertEquals(1, wire.closes.get())
        assertSame(thread, wire.closedBy.get())
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
            wire = quiet, baud = baud, name = "UART", events = {}, answer = { _, _ -> },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
        )
        val thread = Thread(real, "sign-link-test").apply { start() }
        Thread.sleep(300)
        real.stop()
        thread.join(2_000)
        assertTrue(quiet.reads.get() in 5..60, "${quiet.reads.get()} reads in 300 ms")
    }
}
