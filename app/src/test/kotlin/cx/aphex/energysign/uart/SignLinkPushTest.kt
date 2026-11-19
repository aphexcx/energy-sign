package cx.aphex.energysign.uart

import cx.aphex.energysign.message.Reply
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The push of the chooser (contract VERSION 3.1) through the sign link's loop, against a fake
 * line that knows what is ON it, a fake app and a fake clock.
 *
 * The fake line keeps what real hardware would do with a call: bytes handed over leave one after
 * the other, ten bit times each, behind what is still leaving; a break holds the line LOW from
 * the moment the line sees the call. Whatever the link does that the sign could not read is a
 * FAULT (bytes handed over into a LOW or straight behind a run of zeros, attention asked for
 * while bytes are leaving), and every test ends with a look at the faults.
 *
 * The fake app is a chooser text (null outside chooser mode) and a queue of ordinary messages
 * that an answer TAKES, and hands back when its write failed, as SignResponder and
 * MessageManager do; so a message taken for nobody shows.
 *
 * Time only moves when the link sleeps or a fake call is told to take time, so times are exact.
 * A run of 72 zeros is 50 ms on the line at 14400 baud; a frame of three bytes 2 ms.
 */
class SignLinkPushTest {
    private val ms = 1_000_000L
    private val baud = 14400
    private val byteNs = 10 * 1_000_000_000L / baud

    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    /** The next sleep of the link lasts this much longer than asked (once): the thread was kept waiting. */
    private var sleepOverrunNs = 0L

    // ---- what the sign sends
    private val arrivals = sortedMapOf<Long, ByteArray>()
    private var reads = 0
    private var readCostNs = 0L

    // ---- what the app puts on the line
    private class Tx(val atNs: Long, val leavesFromNs: Long, val goneAtNs: Long, val bytes: ByteArray) {
        val zeros: Boolean get() = bytes.all { it == 0.toByte() }
        val text: String get() = String(bytes)
    }

    /** In order: `low@T` for a break, `tx[text]@T` for bytes handed over (T in ms; zeros as `00xN`), `log:`, `warn:` and `told:` lines. */
    private val line = mutableListOf<String>()
    private val txs = mutableListOf<Tx>()
    private val lows = mutableListOf<Long>()
    private val faults = mutableListOf<String>()
    private var txBusyUntilNs = 0L

    /** The line is LOW for attention until here: the end of a break, or of a run of zeros. */
    private var lowUntilNs = 0L
    private var breakBlocks = false
    private var failBreaks = 0

    /** The transmitter takes at most this many bytes per write. */
    private var takesPerWrite = Int.MAX_VALUE

    /** The transmitter takes this many more bytes and then nothing, until [stuckUntilNs]. */
    private var takesBeforeStuck = Int.MAX_VALUE
    private var stuckUntilNs = 0L

    /** So many writes of a frame or a reply throw; so many writes of zeros throw. */
    private var failWrites = 0
    private var failZeroWrites = 0

    /** The next write or break call stalls this long BEFORE the line sees it (once): the thread was taken off the processor. */
    private var stallNs = 0L

    /** Every write call that takes bytes returns this much AFTER the line saw them. */
    private var callLastsNs = 0L

    /** The write call with this number (the first is 1) stalls this long BEFORE the line sees it. */
    private val stallAtWrite = mutableMapOf<Int, Long>()
    private var writeCalls = 0

    private val wire = object : SignWire {
        override fun read(buffer: ByteArray, length: Int): Int {
            reads++
            now += readCostNs
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
            val offersZeros = (0 until length).all { buffer[it] == 0.toByte() }
            if (offersZeros && failZeroWrites > 0) {
                failZeroWrites--
                throw IOException("write failed")
            }
            if (!offersZeros && failWrites > 0) {
                failWrites--
                throw IOException("write failed")
            }
            now += stallNs
            stallNs = 0
            now += stallAtWrite.remove(++writeCalls) ?: 0L
            if (now >= stuckUntilNs) takesBeforeStuck = Int.MAX_VALUE
            val take = minOf(length, takesPerWrite, takesBeforeStuck)
            if (take == 0) return 0
            if (takesBeforeStuck != Int.MAX_VALUE) takesBeforeStuck -= take
            val bytes = buffer.copyOf(take)
            val zeros = offersZeros
            if (!zeros && now < lowUntilNs) {
                faults += "bytes at ${now / ms} ms, into the LOW that lasts until ${lowUntilNs / ms} ms"
            }
            if (zeros && now < txBusyUntilNs && txs.lastOrNull()?.zeros == false) {
                faults += "zeros at ${now / ms} ms, while bytes are leaving until ${txBusyUntilNs / ms} ms"
            }
            val from = maxOf(now, txBusyUntilNs)
            txBusyUntilNs = from + take * byteNs
            if (zeros) lowUntilNs = txBusyUntilNs
            txs += Tx(now, from, txBusyUntilNs, bytes)
            line += "tx[${if (zeros) "00x$take" else String(bytes)}]@${now / ms}"
            now += callLastsNs
            return take
        }

        override fun sendBreak(millis: Int) {
            if (failBreaks > 0) {
                failBreaks--
                throw IOException("no break")
            }
            now += stallNs
            stallNs = 0
            if (now < txBusyUntilNs) faults += "break at ${now / ms} ms, while bytes are leaving until ${txBusyUntilNs / ms} ms"
            lows += now
            line += "low@${now / ms}"
            lowUntilNs = now + millis * ms
            if (breakBlocks) now += millis * ms
        }

        override fun close() {}
    }

    // ---- the app
    /** The chooser text now; null outside chooser mode. */
    private var chooser: String? = "A"
    private val ordinary = ArrayDeque((1..50).map { "M$it" })
    private val taken = mutableListOf<String>()
    private var answers = 0
    private var frameCostNs = 0L
    private var frameThrows = 0
    private var framesMade = 0
    private var switchThrows = 0
    private var toldThrows = 0

    /** The switch. These tests are about pushes that are on; the product's default is off. */
    private var mode = Attention.AUTO

    /** The app is stopped from another thread once the clock has reached this. */
    private var stopAtNs = Long.MAX_VALUE

    /** What the app was told had been pushed to the sign. */
    private val told = mutableListOf<String>()

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
        chooserFrame = {
            if (frameThrows > 0) {
                frameThrows--
                throw IllegalStateException("no frame")
            }
            framesMade++
            now += frameCostNs
            if (now >= stopAtNs) stop()
            chooser?.let { "H:$it" }
        },
        pushed = {
            told += chooser ?: "nothing"
            line += "told:${chooser ?: "nothing"}"
            if (toldThrows > 0) {
                toldThrows--
                throw IllegalStateException("no screen")
            }
        },
        attention = {
            if (switchThrows > 0) {
                switchThrows--
                throw IllegalStateException("no switch")
            }
            mode
        },
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

    /** A command changes what the chooser shows at [atMs] (null: it is left); like MessageManager, the change asks for a push. */
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
            // a loop that neither sleeps nor lets time pass is a spin
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

    /**
     * The sign asks for a message outside chooser mode and gets M1: from here on the link knows
     * the sign is NOT in its chooser display, where a request is good for a second. So a request
     * that is read away afterwards is read away because of the push, not because it is old.
     */
    private fun signShowsAnOrdinaryMessage() {
        chooser = null
        arrives(0, "~")
        runUntil(50)
        assertEquals(listOf("M1"), taken)
    }

    private fun wire() = line.filter { it.startsWith("tx[") || it.startsWith("low@") }
    private fun warnings() = line.filter { it.startsWith("warn:") }
    private fun drops() = timingLines.filter { it.startsWith("drop ") }

    /** The frames among what was handed over: neither zeros nor a poll report. */
    private fun frames() = txs.filter { !it.zeros && !it.text.startsWith("{c:") }

    @AfterEach
    fun `nothing was put on the line that the sign could not read`() {
        assertEquals(emptyList(), faults)
    }

    // ================= the push (contract 3.1): attention, guard, the chooser frame

    @Test
    fun `a push is the run of zeros, the guard, then the chooser frame, once, and nothing without a change`() {
        runUntil(100)
        assertEquals(emptyList(), line)
        command(100, "B")
        runUntil(2_000)
        // 72 zeros are on the line from 100 to 150; the frame comes 20 ms after the last of them
        assertEquals(listOf("tx[00x72]@100", "tx[H:B]@170"), wire())
        assertEquals(72, txs[0].bytes.size)
        assertEquals(SignLink.ATTN_BREAK_MS.toLong(), (txs[0].goneAtNs - txs[0].leavesFromNs + ms / 2) / ms)
        assertTrue(txs[1].atNs - txs[0].goneAtNs >= SignLink.ATTN_GUARD_MS * ms)
    }

    @Test
    fun `auto is the run of zeros, the break is never asked for, and so is the word zeros`() {
        // measured on the sign: the break does not hold the line LOW (0 of 11 seen), the zeros do (11 of 11)
        for (how in listOf(Attention.AUTO, Attention.ZEROS)) {
            mode = how
            failBreaks = 1 // a break would throw, and show
            command(now / ms, "B$how")
            runUntil(now / ms + 1_000)
            assertEquals(emptyList(), lows, how.toString())
            assertEquals(emptyList(), warnings(), how.toString())
            assertEquals("H:B$how", txs.last().text)
            assertTrue(txs[txs.size - 2].zeros)
        }
    }

    @Test
    fun `told to use the break, a push is the break, the guard, then the frame`() {
        mode = Attention.BREAK
        command(0, "B")
        runUntil(500)
        assertEquals(listOf("low@0", "tx[H:B]@70"), wire())
    }

    @Test
    fun `the LOW of a break and the guard are counted from the return of the break call`() {
        mode = Attention.BREAK
        // a call that returns only when the line is HIGH again: the frame comes later, never too early
        breakBlocks = true
        command(0, "B")
        runUntil(1_000)
        assertEquals(listOf("low@0", "tx[H:B]@120"), wire())
    }

    @Test
    fun `a thread held up just before the break call does not write its frame into the LOW`() {
        mode = Attention.BREAK
        // the gate's case: the time is taken, the thread is off the processor for 40 ms, then the call is made
        stallNs = 40 * ms
        command(0, "B")
        runUntil(500)
        assertEquals(40, lows.single() / ms) // the line went LOW at 40, until 90
        assertEquals("tx[H:B]@110", wire().last()) // 90 + the guard
    }

    @Test
    fun `a thread held up inside the write of the zeros does not write its frame into them or their guard`() {
        // the same for the run of zeros: they are on the line from the RETURN of the write
        stallNs = 40 * ms
        command(0, "B")
        runUntil(500)
        assertEquals(listOf("tx[00x72]@40", "tx[H:B]@110"), wire())
        assertTrue(txs[1].atNs - txs[0].goneAtNs >= SignLink.ATTN_GUARD_MS * ms)
    }

    @Test
    fun `a write call that returns late only makes the frame later`() {
        callLastsNs = 5 * ms // handed over at 0, the call returns at 5: the link cannot know which
        command(0, "B")
        runUntil(500)
        assertEquals(listOf("tx[00x72]@0", "tx[H:B]@75"), wire())
    }

    @Test
    fun `changes in a burst are one push of the newest state`() {
        command(0, "B")
        command(0, "C")
        command(0, "D")
        runUntil(1_000)
        assertEquals(listOf("tx[00x72]@0", "tx[H:D]@70"), wire())
        assertEquals(1, framesMade)
    }

    @Test
    fun `a change during a push is the next push, not lost and not queued, and the newest state goes last`() {
        command(0, "B")
        command(10, "C") // while the zeros are leaving
        command(40, "D")
        command(65, "E") // in the guard
        runUntil(2_000)
        val secondAt = 70 + 2 + SignLink.PUSH_SPACING_MS // the frame of the first has left after 2 ms
        assertEquals(listOf("tx[00x72]@0", "tx[H:B]@70", "tx[00x72]@$secondAt", "tx[H:E]@${secondAt + 70}"), wire())
    }

    @Test
    fun `a command posted before the link runs is carried out by its first turn`() {
        command(0, "B")
        link.step()
        assertEquals(listOf("tx[00x72]@0", "tx[H:B]@70"), wire())
    }

    // ================= what the app is told of a push (the screen, Bluetooth)

    @Test
    fun `a pushed frame is told to the app once it is written, and logged, after the write`() {
        command(0, "B")
        runUntil(1_000)
        assertEquals(listOf("B"), told)
        assertEquals(listOf("tx[00x72]@0", "tx[H:B]@70", "log:Pushed >H:B< to UART", "told:B"), line)
    }

    @Test
    fun `leaving chooser mode has no frame, so nothing is told, and the attention is logged`() {
        command(0, null)
        runUntil(1_000)
        assertEquals(emptyList(), told)
        assertEquals(listOf("tx[00x72]@0", "log:Pushed attention alone to UART"), line)
    }

    @Test
    fun `a push that failed tells nothing, the one that then goes out is told once`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 72 + 2 // the zeros and two bytes of the frame
        stuckUntilNs = 150 * ms
        runUntil(200)
        assertEquals(emptyList(), told)
        assertTrue(warnings().single().contains("the frame was not taken in one piece (2 of 9 bytes)"), warnings().toString())
        runUntil(2_000)
        assertEquals(listOf("BCDEFGH"), told)
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
        assertEquals(emptyList(), told)
        assertEquals(listOf("tx[H:C]@500"), wire())
    }

    // ================= time on the line: one owner, counted from the return of every call

    @Test
    fun `a push does not start while a reply is still leaving, and the link goes on reading meanwhile`() {
        // the gate's case: a reply with 40 ms left on the line
        chooser = "X".repeat(56) // 58 bytes, 40 ms
        arrives(0, "~")
        command(1, "Y")
        var ranAt = -1L
        posted.getOrPut(20 * ms) { mutableListOf() } += { ranAt = now }
        val readsBefore = reads
        runUntil(1_000)
        val replyGoneNs = txs[0].goneAtNs
        assertEquals(40, replyGoneNs / ms)
        assertTrue(txs[1].zeros)
        assertTrue(txs[1].atNs >= replyGoneNs, "zeros at ${txs[1].atNs / ms} ms")
        assertEquals("tx[H:Y]@110", wire().last())
        // it waited in the loop, not in a sleep of 40 ms: a change posted at 20 was made at 20
        assertTrue(ranAt in 20 * ms..22 * ms, "ran at ${ranAt / ms} ms")
        assertTrue(reads - readsBefore > 15)
    }

    @Test
    fun `told to use the break, the break is not asked for while a reply is still leaving either`() {
        mode = Attention.BREAK
        chooser = "X".repeat(56)
        arrives(0, "~")
        command(1, "Y")
        runUntil(1_000)
        assertTrue(lows.single() >= txs[0].goneAtNs, "break at ${lows.single() / ms} ms")
        assertTrue(txs[1].atNs >= lows.single() + (SignLink.ATTN_BREAK_MS + SignLink.ATTN_GUARD_MS) * ms)
    }

    @Test
    fun `a write call that stalls before it hands the bytes over does not free the line early`() {
        // the gate's case: the reply's bytes are on the line from the RETURN of the write
        chooser = "X".repeat(56) // 58 bytes, 40 ms
        stallNs = 30 * ms
        arrives(0, "~")
        command(1, "Y")
        runUntil(1_000)
        assertEquals(70, txs[0].goneAtNs / ms) // handed over at 30, gone at 70
        assertTrue(txs[1].atNs >= txs[0].goneAtNs, "zeros at ${txs[1].atNs / ms} ms")
    }

    @Test
    fun `a reply taken in pieces is on the line until its last piece has left`() {
        chooser = "X".repeat(56)
        takesPerWrite = 20 // 58 bytes in three pieces, 2 ms apart; then the zeros in four
        arrives(0, "~")
        command(1, "Y")
        runUntil(1_000)
        val reply = txs.takeWhile { !it.zeros }
        assertEquals(58, reply.sumOf { it.bytes.size })
        assertTrue(txs.first { it.zeros }.atNs >= reply.last().goneAtNs)
        assertEquals("H:Y", txs.last().text)
    }

    // ================= the sign is left alone while it gets over what the app tore (gate round 1, major 1)

    @Test
    fun `a push waits until the sign has got over a reply it was sent only the start of, and the link reads on meanwhile`() {
        // the gate's case: the transmitter takes the first byte of a reply and no more; the writer gives the rest up at 52
        takesBeforeStuck = 1
        stuckUntilNs = 60 * ms
        arrives(0, "~")
        command(10, "B")
        var ranAt = -1L
        posted.getOrPut(500 * ms) { mutableListOf() } += { ranAt = now }
        runUntil(2_500)
        val start = txs[0]
        assertEquals("H", start.text)
        val zeros = txs.first { it.zeros }
        // the sign waits up to a second for the next byte of an answer: an attention into that is thrown away with it
        assertTrue(zeros.atNs - start.goneAtNs >= 1_010 * ms, "zeros ${(zeros.atNs - start.goneAtNs) / ms} ms behind the torn reply")
        assertEquals("H:B", txs.last().text)
        assertEquals(listOf("B"), told)
        // held back in the loop, not in a sleep: a change posted at 500 was made at 500
        assertTrue(ranAt in 500 * ms..502 * ms, "ran at ${ranAt / ms} ms")
    }

    @Test
    fun `the sign that asks again while a push is held back for it is answered at once, and that answer is the push`() {
        takesBeforeStuck = 1
        stuckUntilNs = 60 * ms
        arrives(0, "~")
        command(10, "B")
        arrives(300, "~") // the sign gave the torn answer up and asks again
        runUntil(2_500)
        assertEquals(listOf("tx[H]@0", "tx[H:B]@300"), wire())
        assertEquals(emptyList(), drops())
    }

    @Test
    fun `a reply whose write threw may be on the line in part, a push waits for the sign to get over that too`() {
        failWrites = 1
        arrives(0, "~")
        command(10, "B")
        runUntil(2_500)
        val zeros = txs.first { it.zeros }
        // the three bytes of the reply would have left by 2 ms
        assertTrue(zeros.atNs >= 1_012 * ms, "zeros at ${zeros.atNs / ms} ms")
        assertEquals(listOf("B"), told)
    }

    @Test
    fun `a poll report of which only the start went out holds a push back the same way`() {
        takesBeforeStuck = 2
        stuckUntilNs = 60 * ms
        arrives(0, "?")
        command(10, "B")
        runUntil(2_500)
        assertEquals("{c", txs[0].text)
        val zeros = txs.first { it.zeros }
        assertTrue(zeros.atNs - txs[0].goneAtNs >= 1_010 * ms, "zeros ${(zeros.atNs - txs[0].goneAtNs) / ms} ms behind the torn report")
    }

    @Test
    fun `a reply whose second piece is taken late is still a reply that was sent, but a push is held back behind it`() {
        // an answer is judged as on master (it went out, its message is not handed back); only the push is careful
        chooser = null
        ordinary.addFirst("N".repeat(100))
        takesPerWrite = 40
        // 40 bytes at 0 (gone at 28); the next 40 are offered at 2 and handed over at 88: the line stood still
        // for 60 ms, the sign may have given up. The last 20 follow at 90 and are written all the same.
        stallAtWrite[2] = 86 * ms
        arrives(0, "~")
        command(120, "B")
        runUntil(2_500)
        assertEquals(listOf("N".repeat(100)), taken) // sent, as before this change
        assertEquals(listOf(40, 40, 20), txs.take(3).map { it.bytes.size })
        assertTrue(txs[1].leavesFromNs - txs[0].goneAtNs >= 50 * ms)
        val zeros = txs.first { it.zeros }
        assertTrue(zeros.atNs - txs[2].goneAtNs >= 1_010 * ms, "zeros ${(zeros.atNs - txs[2].goneAtNs) / ms} ms behind the reply that may be torn")
    }

    @Test
    fun `a pushed frame of which nothing was taken leaves the attention alone on the line, the next try is 250 ms behind it`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 72 // the zeros, and then nothing
        stuckUntilNs = 125 * ms
        runUntil(2_000)
        // the guard was over at 70; nothing of the frame is out, so the sign is not waiting for the rest of one
        assertEquals(listOf("tx[00x72]@0", "tx[00x72]@320", "tx[H:BCDEFGH]@390"), wire())
    }

    @Test
    fun `a pushed frame whose pieces come with a pause shorter than the sign's patience is whole`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 72 + 2 // the zeros and two bytes of the frame at 70; the rest 26 ms later
        stuckUntilNs = 95 * ms
        runUntil(500)
        assertEquals(listOf("tx[00x72]@0", "tx[H:]@70", "tx[BCDEFGH]@96"), wire())
        assertEquals(listOf("BCDEFGH"), told)
        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `a reply of which nothing went out tears nothing, the push that follows is what the sign reads behind its request`() {
        takesBeforeStuck = 0
        stuckUntilNs = 60 * ms
        arrives(0, "~")
        command(10, "B")
        runUntil(500)
        assertEquals(listOf("tx[00x72]@60", "tx[H:B]@130"), wire())
    }

    // ================= what is on the line is judged by when bytes leave (gate round 1, major 2)

    @Test
    fun `a piece of the zeros that the transmitter takes late, inside a write that was called in time, leaves a hole, and that is no attention`() {
        // the gate's case: 36 zeros have left after 25 ms; the second write is called at 2 ms and hands its 36 over at 37
        takesPerWrite = 36
        stallAtWrite[2] = 35 * ms
        command(0, "B")
        runUntil(250)
        assertEquals(listOf("tx[00x36]@0", "tx[00x36]@37"), wire())
        assertTrue(txs[1].leavesFromNs > txs[0].goneAtNs) // the hole on the line
        assertEquals(emptyList(), told)
        assertTrue(warnings().single().contains("not taken in one run"), warnings().toString())
        runUntil(1_000)
        assertEquals(listOf("B"), told)
        val again = txs.drop(2).first { it.zeros }
        assertTrue(again.atNs - txs[1].goneAtNs >= SignLink.PUSH_SPACING_MS * ms)
    }

    @Test
    fun `a pushed frame whose second piece is taken late may have stood still too long for the sign, it is a failed push`() {
        val long = "L".repeat(100) // a frame of 102 bytes
        takesPerWrite = 72
        // the writes: 1 the zeros, 2 the first 72 bytes of the frame (at 70, gone at 120), 3 the rest: called at 72, handed over at 182
        stallAtWrite[3] = 110 * ms
        command(0, long)
        runUntil(600)
        assertEquals(emptyList(), told)
        assertTrue(warnings().single().contains("the frame was not taken in one piece"), warnings().toString())
        runUntil(3_000)
        assertEquals(listOf(long), told)
        // and the next try waits until the sign has got over the torn frame
        val again = txs.drop(3).first { it.zeros }
        assertTrue(again.atNs - txs[2].goneAtNs >= 1_010 * ms, "again ${(again.atNs - txs[2].goneAtNs) / ms} ms behind the torn frame")
    }

    // ================= a push takes nothing

    @Test
    fun `leaving chooser mode is the attention alone, takes no message, and the request that follows gets the first one`() {
        ordinary.addFirst("micOff") // a one-time command that must not be lost
        command(100, null)
        runUntil(250)
        assertEquals(listOf("tx[00x72]@100"), wire())
        assertEquals(emptyList(), taken)
        assertEquals(0, answers)
        arrives(320, "~") // the sign found nothing after the LOW and asks
        runUntil(1_000)
        assertEquals(listOf("tx[00x72]@100", "tx[micOff]@320"), wire())
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
        assertEquals(0, answers)
        assertEquals(50, ordinary.size)
        assertTrue(txs.count { it.zeros } >= 2)
    }

    // ================= one request, at most one frame

    @Test
    fun `rule 2, a full answer to a waiting request is the push, no attention is raised on a sign that is waiting`() {
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
        // the report (5 bytes, 3 ms on the line), then the push behind it
        assertEquals(listOf("tx[{c:B}]@0", "tx[00x72]@3", "tx[H:B]@73"), wire())
    }

    @Test
    fun `rule 2 needs the answer to have gone out, a reply that failed leaves the push wanted`() {
        takesBeforeStuck = 0
        stuckUntilNs = 60 * ms
        command(0, "B")
        arrives(0, "~")
        runUntil(1_000)
        assertEquals(listOf("tx[00x72]@60", "tx[H:B]@130"), wire())
    }

    @Test
    fun `rule 3, a request that came while the frame was being made gets no frame of its own`() {
        chooser = null
        command(0, "B")
        frameCostNs = 30 * ms // the gate's case: slow to make the frame
        arrives(10, "~")
        runUntil(250)
        assertEquals(listOf("tx[00x72]@30", "tx[H:B]@100"), wire())
        assertEquals(1, drops().size)
        assertTrue(drops().single().endsWith("n=1 bytes=[~]"))
        assertEquals(0, answers)
    }

    @Test
    fun `rule 3, requests that came into the zeros and into the guard get no frame of their own`() {
        command(0, "B")
        arrives(10, "?") // into the zeros
        arrives(65, "~") // into the guard
        runUntil(250)
        assertEquals(listOf("tx[00x72]@0", "tx[H:B]@70"), wire())
        assertTrue(drops().single().endsWith("n=2 bytes=[?~]"))
        assertTrue(line.any { it == "log:Read 2 bytes from UART that the sign sent into a push: not answered" }, line.toString())
    }

    @Test
    fun `rule 3, a request that came while the frame was leaving gets no frame of its own, one that came after it does`() {
        val long = "L".repeat(70) // a frame of 72 bytes: 50 ms on the line, from 70 to 120
        command(0, long)
        arrives(100, "~")
        runUntil(125)
        assertEquals(listOf("tx[00x72]@0", "tx[H:$long]@70"), wire())
        assertEquals(1, drops().size)
        arrives(130, "~")
        runUntil(200)
        assertEquals(listOf("tx[00x72]@0", "tx[H:$long]@70", "tx[H:$long]@130"), wire())
    }

    @Test
    fun `rule 3, what the sign sent into the attention of leaving takes no message`() {
        command(0, null)
        arrives(20, "~")
        arrives(60, "?")
        runUntil(250)
        assertEquals(listOf("tx[00x72]@0"), wire())
        assertEquals(emptyList(), taken)
        arrives(300, "~")
        runUntil(400)
        assertEquals(listOf("M1"), taken)
    }

    @Test
    fun `rule 3 where the sign listens a second, a request sent into the attention of leaving is read away though it is not old`() {
        signShowsAnOrdinaryMessage()
        command(100, null)
        arrives(120, "~") // into the zeros; 50 ms old when it is read
        runUntil(250)
        assertEquals(listOf("M1"), taken)
        assertEquals(1, drops().size)
        assertTrue(line.any { it == "log:Read 1 bytes from UART that the sign sent into a push: not answered" }, line.toString())
        arrives(300, "~")
        runUntil(400)
        assertEquals(listOf("M1", "M2"), taken)
    }

    @Test
    fun `rule 4 where the sign listens a second, a request sent into half a run of zeros is read away though it is not old`() {
        signShowsAnOrdinaryMessage()
        takesBeforeStuck = 30 // 21 ms of LOW get out, from 100 to 121
        stuckUntilNs = 150 * ms
        command(100, null)
        arrives(110, "~") // 12 ms old when it is read
        runUntil(190)
        assertEquals(listOf("M1"), taken)
        assertEquals(1, drops().size)
        arrives(200, "~") // behind the half: answered, and the answer is the push
        runUntil(1_000)
        assertEquals(listOf("M1", "M2"), taken)
        assertEquals(1, txs.count { it.zeros })
    }

    @Test
    fun `the attention alone does not put the sign into its chooser display, a request half a second late is still answered`() {
        signShowsAnOrdinaryMessage()
        command(100, null)
        runUntil(180)
        sleepOverrunNs = 300 * ms
        runUntil(200)
        arrives(now / ms - 250, "~") // it came while the link was kept from the wire, after the attention was over
        runUntil(now / ms + 10)
        assertEquals(listOf("M1", "M2"), taken)
    }

    @Test
    fun `rule 3, more than one read's worth sent into the attention is void to its last byte`() {
        // 33 bytes with a request byte last: the first read is full, so it says nothing about when the rest came
        command(0, null)
        arrives(10, "x".repeat(SignLink.READ_BUFFER_LEN) + "~")
        runUntil(200)
        assertEquals(listOf("tx[00x72]@0"), wire())
        assertEquals(emptyList(), taken)
        assertEquals(2, drops().size)
    }

    @Test
    fun `one request never takes two messages, with requests thrown at every moment of leaving`() {
        command(1_000, null)
        var asked = 0
        for (t in 0 until 1_300 step 7) {
            arrives(t.toLong(), "~")
            asked++
        }
        runUntil(1_400)
        assertEquals(emptyList(), warnings())
        // before leaving every request got the chooser frame; after, at most one message each
        val answered = txs.count { it.text.startsWith("M") }
        assertEquals(answered, taken.size)
        assertEquals((1..taken.size).map { "M$it" }, taken)
        val readAway = drops().sumOf { it.substringAfter("n=").substringBefore(' ').toInt() }
        assertTrue(readAway > 0)
        assertEquals(txs.count { !it.zeros }, asked - readAway)
    }

    // ================= behind a pushed frame (the firmware waits for 10 ms of quiet line before it asks)

    @Test
    fun `the sign's request 10 ms after a pushed frame has left the line is answered, whatever the frame's length and wherever the loop stands`() {
        readCostNs = 600_000 // measured on the Pico: a read costs 0.6 ms
        callLastsNs = 1 * ms // and a write call takes about a millisecond
        var t = 0L
        for (length in 1..64) {
            val text = "T".repeat(length)
            command(t, text)
            var turns = 0
            while (frames().lastOrNull()?.text != "H:$text") {
                link.step()
                check(++turns < 100_000) { "no push of $length characters" }
            }
            val gone = frames().last().goneAtNs
            val askedAt = gone + SignLink.SIGN_QUIET_BEFORE_REQUEST_MS * ms
            arrivals[askedAt] = "~".toByteArray()
            val framesBefore = frames().size
            runUntil(askedAt / ms + 100)
            assertEquals(emptyList(), drops(), "a frame of $length characters")
            assertEquals(framesBefore + 1, frames().size, "a frame of $length characters")
            // seen by the next read: within a short pause and a read
            assertTrue(frames().last().atNs - askedAt <= 4 * ms, "answered ${(frames().last().atNs - askedAt) / 1000} us late")
            t = now / ms + SignLink.PUSH_SPACING_MS + 37
        }
    }

    @Test
    fun `nothing is written behind a pushed frame, whatever the changes, until the next push is due`() {
        // a change every 3 ms: each wants a push, none may be written into the quiet the sign waits for
        for (t in 0L until 600 step 3) command(t, "C$t")
        runUntil(1_000)
        val frames = frames()
        assertTrue(frames.size >= 2)
        for ((i, tx) in txs.withIndex()) {
            if (tx.zeros || i + 1 == txs.size) continue
            val next = txs[i + 1]
            assertTrue(next.zeros)
            assertTrue(
                next.atNs - tx.goneAtNs >= SignLink.PUSH_SPACING_MS * ms,
                "zeros ${(next.atNs - tx.goneAtNs) / ms} ms behind a frame"
            )
        }
    }

    @Test
    fun `after a pushed chooser frame the sign is taken to be in its chooser display, where it listens 60 ms`() {
        // out of the chooser display first: an ordinary message, written in time
        chooser = null
        arrives(0, "~")
        runUntil(10)
        assertEquals(listOf("M1"), taken)
        // half a second late outside the chooser display is still an answer
        sleepOverrunNs = 500 * ms
        runUntil(300)
        arrives(now / ms - 400, "~")
        runUntil(now / ms + 10)
        assertEquals(listOf("M1", "M2"), taken)
        // now the chooser is pushed, and a request found 80 ms late is old
        command(now / ms, "B")
        runUntil(now / ms + 400)
        assertEquals("H:B", txs.last().text)
        val before = txs.size
        sleepOverrunNs = 80 * ms
        runUntil(now / ms + 20)
        arrives(now / ms - 70, "~")
        runUntil(now / ms + 10)
        assertEquals(before, txs.size)
        assertTrue(line.last().contains("Read an old request"), line.last())
    }

    // ================= a push that did not go out is not done

    @Test
    fun `a run of zeros the transmitter does not take is no attention, no frame is sent after it, and it is tried again`() {
        takesBeforeStuck = 0
        stuckUntilNs = 60 * ms
        command(0, "B")
        runUntil(140)
        assertEquals(emptyList(), wire())
        assertTrue(warnings().any { it.contains("the attention was not taken in one run (0 of 72 bytes)") }, warnings().toString())
        runUntil(1_000)
        // given up after 50 ms, tried again 100 ms after that
        assertEquals(listOf("tx[00x72]@150", "tx[H:B]@220"), wire())
    }

    @Test
    fun `rule 4, half a run of zeros is no attention either, what the sign sent into it is void, and the whole run comes again`() {
        takesBeforeStuck = 30 // 21 ms of LOW get out
        stuckUntilNs = 100 * ms
        command(0, null) // leaving: a request answered now would take a message
        arrives(10, "~")
        runUntil(100)
        assertEquals(listOf("tx[00x30]@0"), wire())
        assertEquals(emptyList(), taken)
        assertEquals(1, drops().size)
        runUntil(1_000)
        // not before the sign has had its quiet behind the half
        val again = txs[1]
        assertEquals(72, again.bytes.size)
        assertTrue(again.atNs - txs[0].goneAtNs >= SignLink.PUSH_SPACING_MS * ms, "again ${(again.atNs - txs[0].goneAtNs) / ms} ms behind the half")
        assertEquals(emptyList(), taken)
    }

    @Test
    fun `a run of zeros taken in pieces close together is one attention, and the frame keeps its guard behind the last piece`() {
        takesPerWrite = 64 // a transmitter with a buffer of 64 bytes
        command(0, "B")
        runUntil(500)
        assertEquals(listOf("tx[00x64]@0", "tx[00x8]@2"), wire().take(2))
        assertEquals(emptyList(), warnings())
        // the second piece went out behind the first with no gap: one run of 50 ms on the line
        assertEquals(txs[0].goneAtNs, txs[1].leavesFromNs)
        assertEquals("H:B", txs[2].text)
        assertEquals(3, txs.size)
        val behind = txs[2].atNs - txs[1].goneAtNs
        assertTrue(behind >= SignLink.ATTN_GUARD_MS * ms && behind < (SignLink.ATTN_GUARD_MS + 1) * ms, "the frame ${behind / 1000} us behind the zeros")
    }

    @Test
    fun `a frame the transmitter takes only the start of is a failed push, tried again in full when the sign has got over the torn one`() {
        command(0, "BCDEFGH")
        takesBeforeStuck = 72 + 2
        stuckUntilNs = 150 * ms
        runUntil(2_000)
        // 70: two bytes, gone at 71; the line stands still for 50 ms and the rest is given up; again 1010 ms behind the two
        assertEquals(listOf("tx[00x72]@0", "tx[H:]@70", "tx[00x72]@1081", "tx[H:BCDEFGH]@1151"), wire())
        assertEquals(1, timingLines.count { it.startsWith("push ") })
    }

    @Test
    fun `rule 4, a request sent into an attention whose frame then failed is void, and one that came after is answered`() {
        // the zeros go out, the frame is not taken; the first request is 90 ms old when it is read
        command(0, "BCDEFGH")
        takesBeforeStuck = 72
        stuckUntilNs = 125 * ms
        arrives(30, "~")
        arrives(130, "~")
        runUntil(2_000)
        // the second request is answered in full, and that answer is the push (rule 2)
        assertEquals(listOf("tx[00x72]@0", "tx[H:BCDEFGH]@130"), wire())
        assertEquals(1, drops().size)
    }

    @Test
    fun `a frame whose write throws is a failed push, tried again`() {
        command(0, "B")
        failWrites = 1 // the zeros go out; the write of the frame throws
        runUntil(2_000)
        // it may have handed a part of the frame over before it threw: the next try waits until the sign has got over that
        assertEquals(listOf("tx[00x72]@0", "tx[00x72]@1082", "tx[H:B]@1152"), wire())
        assertTrue(warnings().first().contains("write failed"), warnings().toString())
        assertEquals(listOf("B"), told)
    }

    @Test
    fun `a run of zeros whose write throws is no attention, no frame is sent after it, and it is tried again`() {
        command(0, "B")
        failZeroWrites = 1
        runUntil(290)
        assertEquals(emptyList(), wire())
        assertTrue(warnings().first().contains("write failed"), warnings().toString())
        runUntil(1_000)
        // it may have handed the zeros over before it threw: they would be on the line until 50, and the next try is 250 ms behind that
        assertEquals(listOf("tx[00x72]@300", "tx[H:B]@370"), wire())
    }

    @Test
    fun `rule 4, a write of the zeros that threw may have put them on the line, a request sent into that time is void`() {
        signShowsAnOrdinaryMessage()
        failZeroWrites = 1
        command(100, null) // leaving: an answer would take a message
        arrives(120, "~") // 2 ms old when it is read: only the time the zeros would be on the line makes it void
        runUntil(250)
        assertEquals(listOf("M1"), taken)
        assertEquals(1, drops().size)
    }

    @Test
    fun `a run of zeros with a hole in it is no attention, no frame is sent after it, and the whole run comes again`() {
        // the transmitter takes 32 bytes at a time, and the link is kept from it for 60 ms after the first piece:
        // 22 ms of LOW, then a line that stands HIGH. A second piece now would be a second short LOW.
        takesPerWrite = 32
        command(0, "B")
        sleepOverrunNs = 60 * ms
        runUntil(200)
        assertEquals(listOf("tx[00x32]@0"), wire())
        assertTrue(warnings().single().contains("the attention was not taken in one run (32 of 72 bytes)"), warnings().toString())
        assertEquals(emptyList(), told)
        runUntil(1_000)
        assertEquals(listOf("B"), told)
        val again = txs.drop(1).takeWhile { it.zeros }
        assertEquals(listOf(32, 32, 8), again.map { it.bytes.size })
        assertTrue(again[0].atNs - txs[0].goneAtNs >= SignLink.PUSH_SPACING_MS * ms)
        again.zipWithNext { a, b -> assertEquals(a.goneAtNs, b.leavesFromNs) } // this time with no hole
    }

    @Test
    fun `told to use the break, a break that throws sends no frame, the push stays wanted and is tried again as a break`() {
        mode = Attention.BREAK
        failBreaks = 1
        command(0, "B")
        runUntil(90)
        assertEquals(emptyList(), wire())
        assertTrue(warnings().single().contains("break failed"))
        runUntil(1_000)
        // it may have pulled the line LOW before it failed: the next try keeps its distance from that
        assertEquals(listOf("low@300", "tx[H:B]@370"), wire())
    }

    @Test
    fun `rule 4, a break that threw may have pulled the line LOW, a request sent into that time is void`() {
        mode = Attention.BREAK
        failBreaks = 1
        command(0, null) // leaving: an answer would take a message
        arrives(20, "~")
        runUntil(90)
        assertEquals(emptyList(), wire())
        assertEquals(emptyList(), taken)
        assertEquals(1, drops().size)
    }

    @Test
    fun `rule 4, a request that came after a failed push is answered, and that answer settles the push`() {
        mode = Attention.BREAK
        failBreaks = 1
        command(0, "B")
        arrives(60, "~")
        runUntil(1_000)
        assertEquals(listOf("tx[H:B]@60"), wire())
        assertEquals(emptyList(), drops())
    }

    @Test
    fun `a frame that cannot be made does not end the loop, the push stays wanted and goes out later`() {
        frameThrows = 1
        command(0, "B")
        arrives(20, "?") // the link is not kept from the wire by the failure: this is answered while the sign listens
        runUntil(90)
        assertTrue(warnings().single().contains("push failed on UART: java.lang.IllegalStateException: no frame"), warnings().toString())
        assertEquals(listOf("tx[{c:B}]@20"), wire())
        arrives(180, "?")
        runUntil(1_000)
        assertEquals(listOf("tx[{c:B}]@20", "tx[00x72]@100", "tx[H:B]@170", "tx[{c:B}]@180"), wire())
        assertEquals(listOf("B"), told)
    }

    @Test
    fun `a switch that cannot be asked is off, said once, and the link goes on answering`() {
        switchThrows = 1
        command(0, "B")
        arrives(20, "?")
        runUntil(500)
        assertEquals(listOf("tx[{c:B}]@20"), wire())
        assertTrue(warnings().single().contains("push failed on UART: java.lang.IllegalStateException: no switch"), warnings().toString())
        assertEquals(0, framesMade)
    }

    @Test
    fun `an app that throws when it is told of a pushed frame does not keep the link from the wire behind that frame`() {
        toldThrows = 1
        command(0, "B")
        arrives(83, "?") // the frame has left at 72; the sign asks 11 ms later
        runUntil(300)
        assertEquals(listOf("tx[00x72]@0", "tx[H:B]@70", "tx[{c:B}]@84"), wire())
        assertTrue(warnings().single().contains("no screen"), warnings().toString())
    }

    // ================= liveness

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
        val reports = txs.count { it.text.startsWith("{c:") }
        assertTrue(reports >= 15, "$reports of 25 polls answered")
        // and the pushes kept their distance: from the end of one frame to the next attention
        val frameEnds = frames().map { it.goneAtNs }
        val attentions = txs.filter { it.zeros }.map { it.atNs }
        assertTrue(attentions.size >= 10)
        for ((end, next) in frameEnds.zip(attentions.drop(1))) {
            assertTrue(next - end >= SignLink.PUSH_SPACING_MS * ms, "${(next - end) / ms} ms between two pushes")
        }
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
        assertEquals(0, framesMade)
    }

    @Test
    fun `switched off while a push waits its turn, the push is dropped, and switching on again does not bring it back`() {
        chooser = "X".repeat(56) // a reply of 40 ms keeps the push waiting
        arrives(0, "~")
        command(1, "Y")
        posted.getOrPut(20 * ms) { mutableListOf() } += { mode = Attention.OFF }
        posted.getOrPut(30 * ms) { mutableListOf() } += { mode = Attention.AUTO }
        runUntil(500)
        assertEquals(1, wire().size) // the reply, and nothing else
        assertEquals(0, framesMade)
    }

    @Test
    fun `switched from the zeros to the break while a push waits its turn, the push is the break`() {
        chooser = "X".repeat(56)
        arrives(0, "~")
        command(1, "Y")
        posted.getOrPut(20 * ms) { mutableListOf() } += { mode = Attention.BREAK }
        runUntil(500)
        assertEquals(40, lows.single() / ms)
        assertEquals("H:Y", txs.last().text)
    }

    @Test
    fun `with pushes off a change while a reply is still leaving moves no read and shortens no pause`() {
        mode = Attention.OFF
        chooser = "X".repeat(56) // 40 ms on the line
        arrives(0, "~")
        command(1, "Y")
        runUntil(200)
        assertEquals(1, wire().size)
        // the loop of master: after a request, a pause of exactly two ms after every empty read
        assertTrue(sleeps.all { it == SignLink.ACTIVE_PAUSE_MS * ms }, sleeps.distinct().toString())
        assertEquals(100, sleeps.size)
    }

    @Test
    fun `the switch is looked at for every push, off, on and off again`() {
        mode = Attention.OFF
        command(0, "B")
        runUntil(500)
        assertEquals(emptyList(), wire())
        mode = Attention.AUTO
        command(500, "C")
        runUntil(1_000)
        assertEquals(listOf("tx[00x72]@500", "tx[H:C]@570"), wire())
        mode = Attention.OFF
        command(1_000, "D")
        runUntil(1_500)
        assertEquals(listOf("tx[00x72]@500", "tx[H:C]@570"), wire())
        mode = Attention.BREAK
        command(1_500, "E")
        runUntil(2_000)
        assertEquals(listOf("tx[00x72]@500", "tx[H:C]@570", "low@1500", "tx[H:E]@1570"), wire())
    }

    @Test
    fun `while a push waits its turn the wire is read at the short pause, a request is seen within it`() {
        mode = Attention.BREAK
        failBreaks = 1 // the push failed at 0 and waits to be tried again; the sign has not been heard from
        command(0, "B")
        arrives(65, "~")
        runUntil(200)
        assertEquals(listOf("tx[H:B]@66"), wire())
    }

    @Test
    fun `with pushes off the loop is the loop of contract 2 point 1, a change costs no extra turn and nothing is written unasked`() {
        mode = Attention.OFF
        runUntil(1_000)
        val idleReads = reads
        for (t in 1_000L until 2_000 step 50) command(t, "C$t")
        runUntil(2_000)
        assertEquals(emptyList(), wire())
        assertEquals(idleReads, reads - idleReads) // a hundred reads a second, as with no change at all
        assertEquals(0, framesMade)
    }

    @Test
    fun `stopped while the zeros are leaving, the push writes no frame`() {
        command(0, "B")
        stopAtNs = 30 * ms
        runUntilStopped()
        assertEquals(listOf("tx[00x72]@0"), wire())
        assertEquals(emptyList(), warnings()) // it is over, not a push that failed
        assertEquals(emptyList(), told)
    }

    @Test
    fun `after stop no push is made, even for a change that is already there`() {
        stopAtNs = 5 * ms
        link.step() // the idle turn in whose pause the app is stopped
        command(5, "B")
        repeat(5) { link.step() }
        assertEquals(emptyList(), wire())
        assertEquals(0, framesMade)
    }

    @Test
    fun `stopped while the frame is being made, the break is not asked for`() {
        // the gate's case: stop() while chooserFrame() runs
        mode = Attention.BREAK
        frameCostNs = 10 * ms
        stopAtNs = 5 * ms
        command(0, "B")
        link.step()
        assertEquals(emptyList(), wire())
        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `stopped while the frame is being made, no zeros are offered and nothing is called a failure`() {
        frameCostNs = 10 * ms
        stopAtNs = 5 * ms
        command(0, "B")
        link.step()
        assertEquals(emptyList(), wire())
        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `stopped while a push waits for the line, no attention is raised`() {
        chooser = "X".repeat(56) // a reply of 40 ms
        arrives(0, "~")
        command(1, "Y")
        stopAtNs = 10 * ms
        runUntilStopped()
        assertEquals(1, wire().size)
        assertEquals(0, txs.count { it.zeros })
    }

    @Test
    fun `the switch has four words, and anything else is no word`() {
        assertEquals(Attention.OFF, Attention.parse("off"))
        assertEquals(Attention.AUTO, Attention.parse("auto"))
        assertEquals(Attention.BREAK, Attention.parse("break"))
        assertEquals(Attention.ZEROS, Attention.parse("zeros"))
        assertEquals(Attention.OFF, Attention.parse(" off\n"))
        assertEquals(null, Attention.parse(""))
        assertEquals(null, Attention.parse(null))
        assertEquals(null, Attention.parse("OFF!"))
        assertEquals(null, Attention.parse("on"))
    }

    @Test
    fun `a link that is told nothing about pushes does not push`() {
        var asked = false
        val plain = SignLink(
            wire = wire, baud = baud, name = "UART", events = {}, answer = { _, _ -> },
            chooserFrame = {
                asked = true
                "H:B"
            },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            nanoTime = { now }, sleepNanos = { now += it },
        )
        plain.requestPush()
        while (now < 500 * ms) plain.step()
        assertEquals(emptyList(), wire())
        assertFalse(asked)
    }

    // ================= the timing log

    @Test
    fun `a push is one timing line, with a line for its zeros and one for its frame, and what was read away has one too`() {
        command(20, "B")
        arrives(30, "~")
        runUntil(200)
        assertEquals(
            listOf(
                "tx t_us=20000 n=72/72 kind=attention call_us=20000 rx_us=0",
                "tx t_us=90000 n=3/3 kind=message call_us=90000 rx_us=0",
            ),
            timingLines.filter { it.startsWith("tx ") },
        )
        assertEquals(
            "push t_us=90000 call_us=20000 break_us=20000 guard_us=90000 wanted_us=20000 n=3",
            timingLines.single { it.startsWith("push ") },
        )
        assertEquals("drop t_us=90000 n=1 bytes=[~]", timingLines.single { it.startsWith("drop ") })
    }

    // ================= on a real thread, with the real clock

    @Test
    fun `a push request gets the loop out of its sleep`() {
        val zerosOut = CountDownLatch(1)
        val quiet = object : SignWire {
            override fun read(buffer: ByteArray, length: Int): Int = 0
            override fun write(buffer: ByteArray, length: Int): Int = length.also { zerosOut.countDown() }
            override fun sendBreak(millis: Int) {}
            override fun close() {}
        }
        // a link whose every pause would last five seconds: only a wake-up gets it out in time
        val sleepy = SignLink(
            wire = quiet, baud = baud, name = "UART", events = {}, answer = { _, _ -> },
            chooserFrame = { "H:B" },
            attention = { Attention.AUTO },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            sleepNanos = { nanos -> LockSupport.parkNanos(if (nanos >= SignLink.IDLE_PAUSE_MS * ms) 5_000 * ms else nanos) },
        )
        val thread = Thread(sleepy, "sign-link-test").apply { isDaemon = true }
        thread.start()
        Thread.sleep(100) // it is in its first long sleep
        sleepy.requestPush()
        assertTrue(zerosOut.await(2, TimeUnit.SECONDS), "the push did not start within two seconds")
        sleepy.stop()
        thread.join(2_000)
        assertFalse(thread.isAlive)
    }

    @Test
    fun `a push asked for by the link thread itself wakes nobody, the pause that follows is a whole one`() {
        val pauses = CopyOnWriteArrayList<Long>()
        val quiet = object : SignWire {
            override fun read(buffer: ByteArray, length: Int): Int = 0
            override fun write(buffer: ByteArray, length: Int): Int = length
            override fun sendBreak(millis: Int) {}
            override fun close() {}
        }
        var turns = 0
        lateinit var self: SignLink
        self = SignLink(
            wire = quiet, baud = baud, name = "UART",
            // as MessageManager does it: on the link thread, inside a change the link is running
            events = { if (++turns == 3) self.requestPush() },
            answer = { _, _ -> },
            attention = { Attention.OFF },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            sleepNanos = { nanos ->
                val from = System.nanoTime()
                LockSupport.parkNanos(nanos)
                pauses += System.nanoTime() - from
            },
        )
        val thread = Thread(self, "sign-link-test").apply { isDaemon = true }
        thread.start()
        Thread.sleep(200)
        self.stop()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        // every pause but the last (which the stop ended) lasted its ten ms: none was cut short
        val whole = pauses.dropLast(1)
        assertTrue(whole.size >= 5, "only ${whole.size} pauses")
        assertTrue(whole.all { it >= 8 * ms }, whole.map { it / 1000 }.toString())
    }
}
