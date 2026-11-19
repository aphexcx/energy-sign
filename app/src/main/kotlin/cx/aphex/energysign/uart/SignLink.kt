package cx.aphex.energysign.uart

import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.Reply
import cx.aphex.energysign.message.SignResponder
import java.io.IOException
import java.util.concurrent.locks.LockSupport

/** The serial line to the sign: the two calls of the UART that [SignLink] uses, and its close. */
interface SignWire {
    /** Reads what the sign has sent into [buffer], at most [length] bytes, without waiting. 0 when there is nothing. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, length: Int): Int

    /** Hands the first [length] bytes of [buffer] to the transmitter without waiting. Returns how many it took, 0 when none. */
    @Throws(IOException::class)
    fun write(buffer: ByteArray, length: Int): Int

    @Throws(IOException::class)
    fun close()
}

/**
 * The one thread that talks to the sign. It reads the sign's requests, has them answered, and
 * runs every change to the message state ([events], see OwnerQueue). Nothing else reads or
 * writes the UART, and it closes it when it stops.
 *
 * WHY A THREAD AND NOT THE UART CALLBACK. With `registerUartDeviceCallback` the sign's request
 * bytes reached the app late and several at once, and a chooser poll waits only 60 ms. Measured
 * on the sign with this loop instead: 654 polls, each one byte, found within 3 ms and answered
 * within 9. The callback ran on the main thread (a null Handler means the registering thread),
 * but the main thread was mostly free (at nine requests in ten it ran a posted task within
 * 1 ms), so it was the callback's delivery that was late, not a busy main thread.
 *
 * WHAT IT COSTS. One read every [ACTIVE_PAUSE_MS] while the sign has been heard from in the last
 * [ACTIVE_WINDOW_MS], one every [IDLE_PAUSE_MS] otherwise, and a sleep after every read that
 * finds nothing, so the loop never spins. Measured: a read costs 0.6 ms, 6 % of one core.
 *
 * WHAT ONE REQUEST IS. The sign sends one byte (`~` or `?`) and then listens. So a read that
 * ends in a request byte is one request, and is answered at once: [answer] gets the bytes and
 * calls [send] (what the reply is, is not decided here). A read that does not end in a request
 * byte is not a request. It is the name of a parse error the firmware prints (`InvalidInput`,
 * in pieces) before it stops listening and shows the error; its `~` comes afterwards. Those
 * bytes are logged and read away. Answering them, as the UART callback did, wrote a message to
 * a sign that was not listening, and that message was taken from its queue for nothing.
 *
 * A REQUEST IS ANSWERED WHILE THE SIGN LISTENS. An answer can take a message from its queue, so
 * it must not go to a sign that has stopped listening. The link knows how long a request can
 * have waited: since the read before the one that found it (longer than a pause only when the
 * thread was kept from the wire: a sleep that overran, reads that kept failing). And it knows
 * how long the sign listens: 60 ms in its chooser display, a second otherwise. The sign may be
 * in its chooser display from the moment it is sent a chooser message or a poll report, or
 * sends a `?` itself, until it is sent a message of another kind in full; and when the link
 * starts it may be (the app can restart under a sign that stays where it is). A request that
 * may have waited longer than [CHOOSER_FRESH_MS] then, or [FRESH_MS] otherwise, is old: it is
 * read away unanswered, nothing is taken, and the sign asks again. The same limit holds for
 * the answer: its first byte must be with the transmitter before the limit, or the frame is a
 * failed one.
 */
class SignLink(
    private val wire: SignWire,
    private val baud: Int,
    /** The name of the port, for the log. */
    private val name: String,
    /** Runs pending message changes, checking for a stop between them. */
    private val events: (stopped: () -> Boolean) -> Unit,
    /** Answers one request, the first [count] bytes of [buffer], by calling [send] once. The buffer is reused. */
    private val answer: (buffer: ByteArray, count: Int) -> Unit,
    private val timing: UartTimingLog,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Sleeps about this long; may return early. */
    private val sleepNanos: (nanos: Long) -> Unit = LockSupport::parkNanos,
    /** What was read and written, after it was written: the log must not hold up a reply. */
    private val log: (line: String) -> Unit = {},
    private val warn: (line: String) -> Unit = {},
    private val prepareAnswers: (stopped: () -> Boolean) -> Unit = { SignResponder.warmUp(it) },
) : Runnable {
    @Volatile
    private var stopped = false
    private val stopRequested = { stopped }

    @Volatile
    private var thread: Thread? = null

    private val buffer = ByteArray(READ_BUFFER_LEN)

    /** A read that began here took everything the sign had sent: what a later read finds came after. */
    private var takenUpToNs = nanoTime()

    /** The sign may be in its chooser display, where it listens 60 ms for an answer. Until the link knows better, it may. */
    private var chooserDisplay = true

    /** The first byte of the answer to the request in hand must be with the transmitter by this. */
    private var answerByNs = takenUpToNs

    private var lastHeardNs = takenUpToNs - ACTIVE_WINDOW_MS * NS_PER_MS
    private var lastEmptyReadNs = takenUpToNs
    private var readFailures = 0

    // What send() did for the request being answered; logged after the write.
    private var sentFrame: String? = null
    private var sentAs = Reply.MESSAGE
    private var sentBytes = 0
    private var sentOf = 0

    private var lastWarning = ""
    private var lastWarningNs = takenUpToNs

    /** Gets the loop out of its sleep: there are events to run. From any thread. */
    fun wake() {
        thread?.let(LockSupport::unpark)
    }

    /** Ends [run]. From any thread. The loop takes no further message and closes the port itself. */
    fun stop() {
        stopped = true
        wake()
    }

    override fun run() {
        thread = Thread.currentThread()
        try {
            // Pay the cold JSON cost before taking a request. Keep the previous read time:
            // a request buffered during preparation may already be too old to answer.
            try {
                if (!stopped) prepareAnswers(stopRequested)
            } catch (e: Exception) {
                // An adapter can still be built on demand. Keep the port serving requests.
                complain("preparing answers", e)
            }
            while (!stopped && !Thread.currentThread().isInterrupted) {
                step()
            }
        } finally {
            // Here and nowhere else: no other thread can close the port under a read or a write
            try {
                wire.close()
            } catch (e: Exception) {
                warn("Unable to close $name: $e")
            }
        }
    }

    /** One turn of the loop: the waiting changes, then one look at the wire. */
    fun step() {
        var doing = "a change"
        try {
            events(stopRequested)
            if (stopped) return
            doing = "read"
            val busy = readRequest()
            readFailures = 0
            if (!busy && !stopped) pause()
        } catch (e: Exception) {
            // Whatever it was (the port, the peripheral service, a change that threw): this is the
            // only thread the sign can reach, so it waits a little and goes on
            complain(doing, e)
            // A read that failed is tried again at once, inside the time the sign listens; the
            // long pause is for a port that keeps failing
            val again = doing == "read" && ++readFailures <= QUICK_READ_RETRIES
            if (!stopped) sleepNanos((if (again) ACTIVE_PAUSE_MS else ERROR_PAUSE_MS) * NS_PER_MS)
        }
    }

    /**
     * Writes one frame to the sign: all of it, or it says that it could not. For [answer], on
     * the link thread. [reply] says what the frame is to the sign.
     */
    fun send(frame: String, reply: Reply): Boolean {
        val bytes = frame.toByteArray()
        sentFrame = frame
        sentAs = reply
        sentOf = bytes.size
        sentBytes = writeAll(bytes, if (reply == Reply.REPORT) "report" else "message")
        val whole = sentBytes == bytes.size
        // A message of another kind ends the sign's chooser display; a chooser message or a report keeps it there
        if (whole) chooserDisplay = reply != Reply.MESSAGE
        return whole
    }

    /**
     * Reads once. Bytes that are a request (the last one is `~` or `?`) and are not old are
     * answered; any others are read away. True when there were bytes.
     */
    private fun readRequest(): Boolean {
        val cameAfterNs = takenUpToNs
        val count = read(buffer, buffer.size)
        val readNs = nanoTime()
        if (count <= 0) {
            lastEmptyReadNs = readNs
            return false
        }
        lastHeardNs = readNs
        val last = buffer[count - 1]
        if (last != POLL && last != NEXT) {
            timing.dropped(readNs, buffer, count)
            log("Read $count bytes from $name that are not a request: [${String(buffer, 0, count)}]")
            return true
        }
        // Only the chooser display sends polls
        if (last == POLL) chooserDisplay = true
        val waitedMs = (readNs - cameAfterNs) / NS_PER_MS
        val freshMs = if (chooserDisplay) CHOOSER_FRESH_MS else FRESH_MS
        answerByNs = cameAfterNs + freshMs * NS_PER_MS
        if (waitedMs > freshMs) {
            // The link was kept from the wire for longer than the sign waits for this answer
            timing.dropped(readNs, buffer, count)
            log("Read an old request from $name (up to $waitedMs ms ago): not answered")
            return true
        }
        // Not after stop: a message taken now would be written to a port that is closing
        if (stopped) return true
        timing.rx(readNs, lastEmptyReadNs, buffer, count)
        sentFrame = null
        try {
            answer(buffer, count)
        } catch (e: Exception) {
            // A reply that fails must not end the only thread the sign can reach
            complain("reply", e)
        }
        logReply(count)
        return true
    }

    private fun logReply(count: Int) {
        val frame = sentFrame ?: return
        val report = sentAs == Reply.REPORT
        if (!report) {
            log("Read $count bytes from $name: [${String(buffer, 0, count)}]")
            log("Writing >$frame< to $name...")
        }
        // A chooser poll report goes out about five times a second: only a short one is logged
        if (!report || sentBytes != sentOf) {
            log("Wrote $sentBytes of $sentOf bytes to $name")
        }
    }

    private fun read(into: ByteArray, length: Int): Int {
        val startNs = nanoTime()
        val n = wire.read(into, length).coerceAtMost(length)
        timing.poll(startNs, nanoTime())
        // A full buffer says nothing about when the rest behind it came
        if (n < length) takenUpToNs = startNs
        return n
    }

    /**
     * Hands all of [bytes] to the transmitter. A write may take only some (it does not wait), so
     * the rest is offered again, but only while the frame can still reach the sign in one piece
     * and in time. The clock is looked at before every offer, and not a byte more is sent
     *  - when the first byte would come after the sign has stopped listening for it ([answerByNs]),
     *  - when the line has stood still for [WRITE_PATIENCE_MS] (the sign gives up 60 ms after a
     *    byte): since the call when nothing was taken yet, since the last byte taken left the
     *    line otherwise, each counted from the START of the offer that took it,
     *  - when the whole frame has had the time it needs on the line and [WRITE_PATIENCE_MS] more:
     *    a transmitter that takes a byte now and then must not keep this thread, which serves
     *    everything, for seconds.
     * Returns how many bytes were taken; fewer than all is a failed frame for the caller to act
     * on. The sign throws the torn start away with its failed read and asks again.
     */
    private fun writeAll(bytes: ByteArray, kind: String): Int {
        val callNs = nanoTime()
        val patienceNs = WRITE_PATIENCE_MS * NS_PER_MS
        val frameByNs = callNs + bytes.size * 10 * NS_PER_S / baud + patienceNs
        // When the bytes taken so far have left the line: ten bit times each
        var goneAtNs = callNs
        var done = 0
        try {
            while (!stopped) {
                val offerNs = nanoTime()
                if (done == 0 && offerNs - answerByNs > 0) break
                if (offerNs - goneAtNs >= patienceNs || offerNs - frameByNs > 0) break
                val rest = if (done == 0) bytes else bytes.copyOfRange(done, bytes.size)
                val took = wire.write(rest, rest.size).coerceIn(0, rest.size)
                if (took > 0) {
                    goneAtNs = (if (offerNs - goneAtNs > 0) offerNs else goneAtNs) + took * 10 * NS_PER_S / baud
                    done += took
                }
                if (done == bytes.size) break
                sleepNanos(ACTIVE_PAUSE_MS * NS_PER_MS)
            }
        } catch (e: Exception) {
            complain("write", e)
        }
        timing.tx(callNs, nanoTime(), done, bytes.size, kind)
        return done
    }

    /** One pause of the loop: short while the sign is talking. */
    private fun pause() {
        val now = nanoTime()
        val talking = now - lastHeardNs < ACTIVE_WINDOW_MS * NS_PER_MS
        val nanos = (if (talking) ACTIVE_PAUSE_MS else IDLE_PAUSE_MS) * NS_PER_MS
        sleepNanos(nanos)
        timing.slept(nanos, nanoTime() - now)
    }

    /** A failure: once when it starts, then at most every [WARN_EVERY_MS]. A dead port must not flood the log. */
    private fun complain(what: String, error: Any) {
        val now = nanoTime()
        if (what != lastWarning || now - lastWarningNs >= WARN_EVERY_MS * NS_PER_MS) {
            lastWarning = what
            lastWarningNs = now
            warn("Sign link: $what failed on $name: $error")
        }
    }

    companion object {
        // ---- Reading
        /** Pause between two reads while the sign is talking. A request waits at most this long to be seen. */
        const val ACTIVE_PAUSE_MS: Int = 2

        /** Pause between two reads otherwise. A `~` out of the blue waits a second for its answer. */
        const val IDLE_PAUSE_MS: Int = 10

        /** How long after the last byte from the sign the short pause is kept. */
        const val ACTIVE_WINDOW_MS: Int = 2_000

        /** In its chooser display the sign listens 60 ms; an answer takes up to 10. A request that may have waited longer is not answered. */
        const val CHOOSER_FRESH_MS: Int = 50

        /** Otherwise the sign listens a second. */
        const val FRESH_MS: Int = 900

        /** So many failed reads in a row are each tried again after [ACTIVE_PAUSE_MS]; after that, [ERROR_PAUSE_MS]. */
        const val QUICK_READ_RETRIES: Int = 10

        /** The most bytes taken in one read (the size the UART callback read with). */
        const val READ_BUFFER_LEN: Int = 32

        // ---- Writing and failing
        /** How long the line may stand still inside a frame before the rest is given up (below the sign's 60 ms a byte). */
        const val WRITE_PATIENCE_MS: Int = 50

        /** Pause after a failure that is not a first failed read. */
        const val ERROR_PAUSE_MS: Int = 100

        const val WARN_EVERY_MS: Int = 5_000

        private val POLL: Byte = ChooserProtocol.POLL_REQUEST.code.toByte()
        private val NEXT: Byte = ChooserProtocol.NEXT_REQUEST.code.toByte()
        private const val NS_PER_MS: Long = 1_000_000L
        private const val NS_PER_S: Long = 1_000_000_000L
    }
}
