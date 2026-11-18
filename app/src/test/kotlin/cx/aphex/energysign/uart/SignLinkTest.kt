package cx.aphex.energysign.uart

import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sign link's loop against a fake wire and a fake clock: what counts as one request, how
 * often the wire is read, what a push does on the wire and in which order, and how it stops.
 *
 * Time only moves when the link sleeps or when the fake wire says a call took time, so every
 * number here is exact.
 */
class SignLinkTest {
    private val ms = 1_000_000L

    /** One thing that happened on the wire or in the app, in order. */
    private val events = mutableListOf<String>()

    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    /** Bytes the sign has sent, by the time they become readable. */
    private val arrivals = sortedMapOf<Long, ByteArray>()
    private var reads = 0
    private var readCostNs = 0L
    private var attentionBlocks = true
    private var failReads = 0
    private var failAttention = false

    /** What the app would send right now; a test changes it like a command would. */
    private var state = "A"

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

        override fun attention(millis: Int) {
            if (failAttention) throw IOException("no break")
            events += "attention($millis)@${now / ms}"
            if (attentionBlocks) now += millis * ms
        }
    }

    private val errors = mutableListOf<String>()
    private val timingLines = mutableListOf<String>()

    private val link = SignLink(
        wire = wire,
        onRequest = { buffer, count -> events += "reply[${String(buffer, 0, count, Charsets.US_ASCII)}]:$state@${now / ms}" },
        onPush = { events += "push:$state@${now / ms}" },
        timing = UartTimingLog(isEnabled = { true }, emit = { timingLines += it }),
        nanoTime = { now },
        sleepNanos = { nanos ->
            sleeps += nanos
            now += nanos
        },
        onError = { what, _ -> errors += what },
    )

    private fun arrives(atMs: Long, text: String) {
        arrivals[atMs * ms] = text.toByteArray(Charsets.US_ASCII)
    }

    private fun runUntil(untilMs: Long) {
        while (now < untilMs * ms) link.step()
    }

    // ---- what counts as one request

    @Test
    fun `a request byte is answered by the read that found it, with no further wait`() {
        arrives(0, "?")
        link.step()
        assertEquals(listOf("reply[?]:A@0"), events)
        assertEquals(1, reads)
        assertEquals(emptyList(), sleeps)
    }

    @Test
    fun `each request byte that arrives on its own gets its own reply`() {
        arrives(0, "?")
        arrives(60, "~")
        arrives(120, "~")
        runUntil(130)
        assertEquals(3, events.size)
        assertTrue(events[0].startsWith("reply[?]"))
        assertTrue(events[1].startsWith("reply[~]"))
        assertTrue(events[2].startsWith("reply[~]"))
    }

    @Test
    fun `request bytes that are already waiting together are one request, the last byte decides`() {
        arrives(0, "?~")
        link.step()
        assertEquals(listOf("reply[?~]:A@0"), events)
    }

    @Test
    fun `an error name that arrives in pieces with its request byte is one request`() {
        // 14400 baud is 0.69 ms a byte: twelve letters take over 8 ms and come in several reads
        arrives(0, "Inv")
        arrives(2, "alid")
        arrives(4, "Inpu")
        arrives(6, "t~")
        runUntil(20)
        assertEquals(listOf("reply[InvalidInput~]:A@6"), events)
    }

    @Test
    fun `an error name with no request byte after it is one request once the line has been quiet`() {
        arrives(0, "Invalid")
        arrives(4, "Input")
        runUntil(40)
        assertEquals(1, events.size)
        assertTrue(events[0].startsWith("reply[InvalidInput]"))
        // delivered QUIET_GAP_MS after its last byte, not before and not much later
        val deliveredAtMs = events[0].substringAfter('@').toLong()
        assertTrue(deliveredAtMs in (4 + SignLink.QUIET_GAP_MS)..(4 + SignLink.QUIET_GAP_MS + SignLink.ACTIVE_PAUSE_MS))
    }

    @Test
    fun `more bytes than the buffer holds are answered in buffer-sized requests`() {
        arrives(0, "x".repeat(SignLink.READ_BUFFER_LEN + 5))
        runUntil(40)
        assertEquals(2, events.size)
        assertTrue(events[0].startsWith("reply[${"x".repeat(SignLink.READ_BUFFER_LEN)}]"))
        assertTrue(events[1].startsWith("reply[xxxxx]"))
    }

    // ---- how often the wire is read (what the loop costs)

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
        assertEquals(50, events.size)
        assertTrue(sleeps.all { it == SignLink.ACTIVE_PAUSE_MS * ms })
        val readsPerSecond = reads / 10
        assertTrue(readsPerSecond <= 1000 / SignLink.ACTIVE_PAUSE_MS + 5, "$readsPerSecond reads a second")
        // every read that found nothing was followed by a sleep: the loop never spins
        assertEquals(reads - events.size, sleeps.size)
    }

    @Test
    fun `a poll is seen at most one short pause after it arrives`() {
        arrives(0, "~")
        for (t in 201 until 5_000 step 197) arrives(t.toLong(), "?")
        runUntil(5_000)
        val late = events.drop(1).mapIndexed { i, e -> e.substringAfter('@').toLong() - (201 + i * 197) }
        assertTrue(late.all { it in 0..SignLink.ACTIVE_PAUSE_MS }, "late by $late ms")
    }

    @Test
    fun `the short pause ends when the sign has been silent for the active window`() {
        arrives(0, "~")
        runUntil(SignLink.ACTIVE_WINDOW_MS + 1_000L)
        assertEquals(SignLink.IDLE_PAUSE_MS * ms, sleeps.last())
        assertEquals(SignLink.ACTIVE_PAUSE_MS * ms, sleeps.first())
    }

    @Test
    fun `the cost of a read is on top of the pause, so a slow read lowers the rate`() {
        readCostNs = 1 * ms
        runUntil(1_000)
        // 10 ms pause + 1 ms read: about 91 reads, 9 percent of the time inside read()
        assertTrue(reads in 88..92, "$reads reads")
    }

    // ---- errors

    @Test
    fun `a failing read is reported, waited out and tried again`() {
        failReads = 3
        arrives(0, "~")
        runUntil(1_000)
        assertEquals(listOf("read", "read", "read"), errors)
        assertEquals(List(3) { SignLink.ERROR_PAUSE_MS * ms }, sleeps.take(3))
        assertEquals(1, events.size)
    }

    @Test
    fun `a reply that throws does not end the loop`() {
        var first = true
        val failing = SignLink(
            wire = wire,
            onRequest = { _, _ ->
                if (first) {
                    first = false
                    throw IllegalStateException("boom")
                }
                events += "reply"
            },
            onPush = {},
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            nanoTime = { now },
            sleepNanos = { now += it },
            onError = { what, _ -> errors += what },
        )
        arrives(0, "~")
        arrives(50, "~")
        while (now < 100 * ms) failing.step()
        assertEquals(listOf("reply"), errors)
        assertEquals(listOf("reply"), events)
    }

    // ---- the push (contract VERSION 3): break, guard, the message; latest wins; one writer

    @Test
    fun `a push is the break, the guard, then the message, and nothing without a request for it`() {
        runUntil(100)
        assertEquals(emptyList(), events)
        link.requestPush()
        link.step()
        assertEquals(
            listOf("attention(50)@100", "push:A@${100 + SignLink.ATTN_BREAK_MS + SignLink.ATTN_GUARD_MS}"),
            events,
        )
        runUntil(1_000)
        assertEquals(2, events.size) // pushed once
    }

    @Test
    fun `the guard starts when the break ends even if the break call returns at once`() {
        attentionBlocks = false
        link.requestPush()
        link.step()
        assertEquals(listOf("attention(50)@0", "push:A@70"), events)
    }

    @Test
    fun `changes in a burst are one push of the newest state`() {
        state = "B"
        link.requestPush()
        state = "C"
        link.requestPush()
        state = "D"
        link.requestPush()
        runUntil(1_000)
        assertEquals(listOf("attention(50)@0", "push:D@70"), events)
    }

    @Test
    fun `a change during a push is not lost and not queued, the newest state is pushed last`() {
        val pushed = mutableListOf<String>()
        lateinit var busy: SignLink
        var changesLeft = listOf("B", "C", "D")
        busy = SignLink(
            wire = wire,
            onRequest = { _, _ -> },
            onPush = {
                pushed += state
                // three more commands arrive while this push is being written
                if (changesLeft.isNotEmpty()) {
                    for (s in changesLeft) {
                        state = s
                        busy.requestPush()
                    }
                    changesLeft = emptyList()
                }
            },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            nanoTime = { now },
            sleepNanos = { now += it },
        )
        busy.requestPush()
        while (now < 1_000 * ms) busy.step()
        assertEquals(listOf("A", "D"), pushed)
    }

    @Test
    fun `a push and a reply never interleave, a request that came during the push is answered after it`() {
        link.requestPush()
        arrives(10, "~") // lands while the line is held low
        runUntil(200)
        assertEquals(listOf("attention(50)@0", "push:A@70", "reply[~]:A@70"), events)
    }

    @Test
    fun `a push goes before a request that is already waiting`() {
        arrives(0, "~")
        state = "B"
        link.requestPush()
        runUntil(200)
        assertEquals(listOf("attention(50)@0", "push:B@70", "reply[~]:B@70"), events)
    }

    @Test
    fun `when the line cannot be pulled low nothing is written and the loop goes on`() {
        failAttention = true
        link.requestPush()
        arrives(150, "~")
        runUntil(300)
        assertEquals(listOf("attention"), errors)
        assertEquals(1, events.size)
        assertTrue(events[0].startsWith("reply[~]"))
    }

    // ---- the timing log

    @Test
    fun `every read with bytes, and every push, is one timing line`() {
        arrives(3, "?")
        runUntil(20)
        link.requestPush()
        link.step()
        val rx = timingLines.single { it.startsWith("rx ") }
        assertEquals("rx t_us=10000 n=1 bytes=[?] chunks=1 first_us=10000 empty_us=0", rx)
        val push = timingLines.single { it.startsWith("push ") }
        assertTrue(push.contains("call_us=") && push.contains("break_us=") && push.contains("guard_us="), push)
    }

    // ---- stopping, on a real thread with the real clock

    @Test
    fun `stop ends the loop from another thread, at once`() {
        val polls = AtomicInteger()
        val started = CountDownLatch(1)
        val real = SignLink(
            wire = object : SignWire {
                override fun read(buffer: ByteArray, length: Int): Int {
                    polls.incrementAndGet()
                    started.countDown()
                    return 0
                }

                override fun attention(millis: Int) {}
            },
            onRequest = { _, _ -> },
            onPush = {},
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
        )
        val thread = Thread(real, "sign-link-test").apply { start() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(300)
        val stopAt = System.nanoTime()
        real.stop()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertTrue(System.nanoTime() - stopAt < 100 * ms, "stop took ${(System.nanoTime() - stopAt) / ms} ms")
        // idle: one read every 10 ms. A spinning loop would have made millions.
        assertTrue(polls.get() in 5..60, "${polls.get()} reads in 300 ms")
    }

    @Test
    fun `a push request wakes a sleeping loop on a real thread`() {
        val pushedAt = java.util.concurrent.atomic.AtomicLong()
        val pushed = CountDownLatch(1)
        val real = SignLink(
            wire = object : SignWire {
                override fun read(buffer: ByteArray, length: Int): Int = 0
                override fun attention(millis: Int) = Thread.sleep(millis.toLong())
            },
            onRequest = { _, _ -> },
            onPush = {
                pushedAt.set(System.nanoTime())
                pushed.countDown()
            },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
        )
        val thread = Thread(real, "sign-link-test").apply { start() }
        Thread.sleep(100)
        val asked = System.nanoTime()
        real.requestPush()
        assertTrue(pushed.await(2, TimeUnit.SECONDS))
        real.stop()
        thread.join(2_000)
        val tookMs = (pushedAt.get() - asked) / ms
        // 50 ms break + 20 ms guard, plus scheduling; never a second
        assertTrue(tookMs in 70..200, "push took $tookMs ms")
    }
}
