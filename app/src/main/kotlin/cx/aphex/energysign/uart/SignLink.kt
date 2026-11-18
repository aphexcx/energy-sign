package cx.aphex.energysign.uart

import cx.aphex.energysign.message.ChooserProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** The serial line to the sign, as much of it as [SignLink] needs. */
interface SignWire {
    /**
     * Reads what the sign has sent into [buffer], at most [length] bytes, without waiting.
     * Returns the number of bytes read, 0 when none are there.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, length: Int): Int

    /**
     * Starts holding the line LOW for [millis] ms, to get the sign's attention between two
     * frames. May return when the line is HIGH again or at once; [SignLink] allows for both.
     */
    @Throws(IOException::class)
    fun attention(millis: Int)
}

/**
 * The one thread that talks to the sign: it reads the sign's requests, has them answered, and
 * pushes the chooser message when it changes. Nothing else reads or writes the UART, so two
 * writes cannot interleave and everything goes out in the order it was decided.
 *
 * WHY A THREAD AND NOT THE UART CALLBACK. `registerUartDeviceCallback(callback)` runs the
 * callback on the thread that registered it (its documentation: a null Handler means the
 * current thread), which was the main thread: the read, the reply and the write all waited
 * behind whatever the main thread was doing, and every line of the on-screen log is a list
 * insert and a smooth scroll there. On the sign the request bytes reached the app late and
 * several at once, and a chooser poll waits only 60 ms. This loop reads the UART itself (`read`
 * does not block), so neither the main thread nor the callback's dispatch is in the path. If
 * bytes still come late and together after this, the platform below `read` is holding them:
 * the timing log's rx lines show which it is.
 *
 * WHAT IT COSTS. One read every [ACTIVE_PAUSE_MS] while the sign has been heard from in the last
 * [ACTIVE_WINDOW_MS] (chooser mode, where the sign asks several times a second), one every
 * [IDLE_PAUSE_MS] otherwise: 500 and 100 reads a second. Every read that finds nothing is
 * followed by a sleep, so the loop never spins; its share of a core is the cost of a read over
 * the pause plus that cost. What a read costs on the device is in the timing log's stat line.
 *
 * WHAT ONE REQUEST IS. The sign sends one byte (`~` or `?`) and waits, so a read that ends in a
 * request byte is answered at once. Anything else is the name of a parse error the firmware
 * prints, twelve letters that arrive over several reads: the loop keeps reading until a request
 * byte ends it, the line has been quiet for [QUIET_GAP_MS], or the buffer is full, and hands
 * the lot over as ONE request, as the callback did when the bytes reached it late and together.
 * The reply logic is not here: [onRequest] gets the bytes and decides by the last one.
 *
 * THE PUSH (chooser contract VERSION 3). [requestPush] may be called from any thread. The loop
 * then pulls the line LOW for [ATTN_BREAK_MS], waits [ATTN_GUARD_MS] and calls [onPush], which
 * writes the message the app would answer to `~` at that moment. Only a flag is kept, never a
 * queue: any number of changes before the message is written are one push of the newest state,
 * and a change after that is the next push. Request bytes that arrived before the message is
 * written are read and dropped: the pushed message is their answer. Answering them as well would
 * send a second message straight behind the first, and outside chooser mode (the push on
 * leaving it) that second message would be taken from the list and never shown.
 */
class SignLink(
    private val wire: SignWire,
    /** Answers one request: the first [count] bytes of [buffer]. The buffer is reused afterwards. */
    private val onRequest: (buffer: ByteArray, count: Int) -> Unit,
    /** Writes the message the app would answer to `~` right now. Called after break and guard. */
    private val onPush: () -> Unit,
    private val timing: UartTimingLog,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Sleeps about this long; may return early (the loop then looks at the clock again). */
    private val sleepNanos: (nanos: Long) -> Unit = LockSupport::parkNanos,
    private val onError: (what: String, error: Throwable) -> Unit = { _, _ -> },
) : Runnable {
    private val pushWanted = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    @Volatile
    private var thread: Thread? = null

    private val buffer = ByteArray(READ_BUFFER_LEN)
    private val chunk = ByteArray(READ_BUFFER_LEN)

    private var heardFromSign = false
    private var lastHeardNs = 0L
    private var lastEmptyReadNs = 0L

    /** Asks for the chooser to be pushed to the sign. From any thread; returns at once. */
    fun requestPush() {
        pushWanted.set(true)
        thread?.let(LockSupport::unpark)
    }

    /** Ends [run]. From any thread; the loop is out of its sleep at once. */
    fun stop() {
        stopped = true
        thread?.let(LockSupport::unpark)
    }

    override fun run() {
        thread = Thread.currentThread()
        while (!stopped && !Thread.currentThread().isInterrupted) {
            step()
        }
    }

    /** One turn of the loop: a push if one is wanted, else one look at the wire. */
    fun step() {
        if (pushWanted.get()) {
            push()
            return
        }
        val answered = try {
            readRequest()
        } catch (e: IOException) {
            onError("read", e)
            sleepNanos(ERROR_PAUSE_MS * NS_PER_MS)
            return
        }
        if (!answered) {
            val talking = heardFromSign && nanoTime() - lastHeardNs < ACTIVE_WINDOW_MS * NS_PER_MS
            sleepNanos((if (talking) ACTIVE_PAUSE_MS else IDLE_PAUSE_MS) * NS_PER_MS)
        }
    }

    /** Reads once; when bytes are there, completes the request and has it answered. */
    private fun readRequest(): Boolean {
        var count = read(buffer)
        if (count <= 0) {
            lastEmptyReadNs = nanoTime()
            return false
        }
        val firstNs = nanoTime()
        var lastNs = firstNs
        var chunks = 1
        while (count < buffer.size && !isRequestByte(buffer[count - 1]) && !stopped) {
            if (nanoTime() - lastNs >= QUIET_GAP_MS * NS_PER_MS) break
            sleepNanos(ACTIVE_PAUSE_MS * NS_PER_MS)
            val more = wire.readInto(chunk, buffer.size - count)
            if (more > 0) {
                chunk.copyInto(buffer, count, 0, more)
                count += more
                lastNs = nanoTime()
                chunks++
            }
        }
        heard(lastNs)
        timing.rx(firstNs, lastNs, lastEmptyReadNs, chunks, buffer, count)
        try {
            onRequest(buffer, count)
        } catch (e: Exception) {
            // A reply that fails must not end the only thread the sign can reach
            onError("reply", e)
        }
        return true
    }

    private fun read(into: ByteArray): Int = wire.readInto(into, into.size)

    private fun SignWire.readInto(into: ByteArray, length: Int): Int {
        val startNs = nanoTime()
        val n = read(into, length)
        timing.poll(startNs, nanoTime())
        return n.coerceAtMost(length)
    }

    private fun push() {
        val callNs = nanoTime()
        try {
            wire.attention(ATTN_BREAK_MS)
        } catch (e: IOException) {
            // No attention, no listener: the sign reads the new state at its next full sync (`~`)
            pushWanted.set(false)
            onError("attention", e)
            return
        }
        val breakReturnedNs = nanoTime()
        // The line is LOW for ATTN_BREAK_MS from the call, whether or not the call waited for it
        val breakEndNs = maxOf(breakReturnedNs, callNs + ATTN_BREAK_MS * NS_PER_MS)
        sleepUntil(breakEndNs + ATTN_GUARD_MS * NS_PER_MS)
        if (stopped) return
        val guardEndNs = nanoTime()
        try {
            dropWaitingRequests()
        } catch (e: IOException) {
            onError("read", e)
        }
        // Changes up to here go out in this push; a change from here on is the next push
        pushWanted.set(false)
        try {
            onPush()
        } catch (e: Exception) {
            onError("push", e)
        }
        val doneNs = nanoTime()
        heard(doneNs)
        timing.push(callNs, breakReturnedNs, guardEndNs, doneNs)
    }

    /** Reads away what the sign sent before a push is written; the push answers it. */
    private fun dropWaitingRequests() {
        repeat(MAX_DROP_READS) {
            val count = read(buffer)
            if (count <= 0) return
            timing.dropped(nanoTime(), buffer, count)
        }
    }

    /** The sign is in a conversation: read at the short pause for the next [ACTIVE_WINDOW_MS]. */
    private fun heard(atNs: Long) {
        heardFromSign = true
        lastHeardNs = atNs
    }

    private fun sleepUntil(deadlineNs: Long) {
        while (!stopped) {
            val left = deadlineNs - nanoTime()
            if (left <= 0) return
            sleepNanos(left)
            if (Thread.currentThread().isInterrupted) stopped = true
        }
    }

    private fun isRequestByte(byte: Byte): Boolean =
        byte == ChooserProtocol.POLL_REQUEST.code.toByte() || byte == ChooserProtocol.NEXT_REQUEST.code.toByte()

    companion object {
        // ---- The push (chooser contract VERSION 3)
        /** How long the line is held LOW to get the sign's attention: longer than one scroll step (35 ms). */
        const val ATTN_BREAK_MS: Int = 50

        /** From the end of the break to the first byte of the message: the sign empties its buffer in it. */
        const val ATTN_GUARD_MS: Int = 20

        // ---- Reading
        /** Pause between two reads while the sign is talking. A request waits at most this long to be seen. */
        const val ACTIVE_PAUSE_MS: Int = 2

        /** Pause between two reads otherwise. A `~` out of the blue waits a second for its answer. */
        const val IDLE_PAUSE_MS: Int = 10

        /** How long after the last byte from the sign (or a push) the short pause is kept. */
        const val ACTIVE_WINDOW_MS: Int = 2_000

        /** Bytes that do not end in a request byte are one request when the line has been quiet this long (8 byte times at 14400 baud). */
        const val QUIET_GAP_MS: Int = 6

        /** Pause after a read that failed. */
        const val ERROR_PAUSE_MS: Int = 100

        /** The most bytes handed over as one request (the size the UART callback read with). */
        const val READ_BUFFER_LEN: Int = 32

        /** At most this many reads are spent on emptying the input before a push is written. */
        private const val MAX_DROP_READS: Int = 4

        private const val NS_PER_MS: Long = 1_000_000L
    }
}
