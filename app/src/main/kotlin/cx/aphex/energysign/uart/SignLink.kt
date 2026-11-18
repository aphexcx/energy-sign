package cx.aphex.energysign.uart

import cx.aphex.energysign.message.ChooserProtocol
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** The serial line to the sign: the three calls of the UART that [SignLink] uses, and its close. */
interface SignWire {
    /** Reads what the sign has sent into [buffer], at most [length] bytes, without waiting. 0 when there is nothing. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, length: Int): Int

    /** Hands the first [length] bytes of [buffer] to the transmitter without waiting. Returns how many it took, 0 when none. */
    @Throws(IOException::class)
    fun write(buffer: ByteArray, length: Int): Int

    /** Starts holding the line LOW for [millis] ms. May return at once (the Pico: after 1 ms) or when the line is HIGH again. */
    @Throws(IOException::class)
    fun sendBreak(millis: Int)

    @Throws(IOException::class)
    fun close()
}

/** How a push gets the sign's attention. */
enum class Attention {
    /** A break; once a break has failed, the run of 0x00 bytes. */
    AUTO,

    /** A break (`sendBreak`), always. */
    BREAK,

    /** A run of 0x00 bytes as long as the break: LOW nine tenths of the time. */
    ZEROS,

    /** No pushes at all: the app behaves as chooser contract 2.1. */
    OFF;

    companion object {
        /** The value of the switch as typed (`break`, `zeros`, `off`); anything else, or nothing, is [AUTO]. */
        fun of(value: String?): Attention = when (value?.trim()) {
            "break" -> BREAK
            "zeros" -> ZEROS
            "off" -> OFF
            else -> AUTO
        }
    }
}

/**
 * WHEN THE TRANSMIT LINE IS FREE. The one place that knows.
 *
 * `write` and `sendBreak` do not wait, so the line is busy after they return: a byte takes ten
 * bit times to leave, a break holds the line from the moment it is asked for, and the guard is
 * a silence the app owes the sign after the break. Everything the link sends is booked here,
 * and before every break and every pushed frame the link waits for [freeAtNs].
 */
class LineClock(private val baud: Int, startNs: Long) {
    /** The line carries nothing of ours from here on. */
    var freeAtNs: Long = startNs
        private set

    /** [count] bytes were handed to the transmitter at [atNs]: they leave behind what is still leaving. */
    fun wrote(atNs: Long, count: Int) {
        if (count > 0) freeAtNs = later(freeAtNs, atNs) + count * 10 * NS_PER_S / baud
    }

    /** A break of [millis] ms was asked for at [calledNs] and the call returned at [returnedNs]. */
    fun low(calledNs: Long, returnedNs: Long, millis: Int) {
        freeAtNs = later(freeAtNs, later(calledNs + millis * NS_PER_MS, returnedNs))
    }

    /** [millis] ms of silence after what is on the line now. */
    fun guard(millis: Int) {
        freeAtNs += millis * NS_PER_MS
    }

    private fun later(a: Long, b: Long): Long = if (a - b >= 0) a else b

    private companion object {
        const val NS_PER_S = 1_000_000_000L
        const val NS_PER_MS = 1_000_000L
    }
}

/**
 * The one thread that talks to the sign. It reads the sign's requests, has them answered, pushes
 * the chooser message when it changes, and runs every change to the message state ([events],
 * see OwnerQueue). Nothing else reads or writes the UART, and it closes it when it stops.
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
 * THE PUSH (chooser contract VERSION 3.1). [requestPush] asks for one; only a flag is kept, so
 * any number of changes are one push of the newest state. A push holds the line LOW for
 * [ATTN_BREAK_MS], stays silent for [ATTN_GUARD_MS], and then writes [chooserFrame]: the
 * chooser message, or nothing when the app is not in chooser mode (the sign then asks with `~`).
 * [chooserFrame] is all a push can reach: it cannot take a message from a queue or move an
 * index, so whatever becomes of a push, nothing is lost with it.
 *
 * ONE REQUEST, ONE FRAME. The rule, and the only place it is decided ([step], [readRequest]):
 *  1. Every turn reads the wire first. A request found there is answered with one frame.
 *  2. If that frame was a full message, a push that was wanted is dropped: the sign has just
 *     been sent, at its own asking, the state the push would carry. (The break would hit a sign
 *     that is waiting for its answer.)
 *  3. From then until a push is over (the last byte of its frame has left the line, or its
 *     guard has passed when it has no frame) whatever the sign sends is void: it was sent into
 *     the break or into the frame, the sign cannot have been listening for an answer to it, and
 *     it asks again. Those bytes are read away and none is answered, whether they came before
 *     the frame was chosen, between that and the write, or while the frame was leaving.
 *  4. A push that failed (the attention or the frame was not taken in full) is still wanted and
 *     is tried again after [ERROR_PAUSE_MS]. Rule 3 holds for whatever it did put on the line,
 *     or may have (a break call that threw): a request sent into that is void, a request that
 *     came after it is answered.
 *  5. After a push the next one waits [PUSH_SPACING_MS], so that however fast the changes come,
 *     the sign can ask in between and is answered.
 *  6. After a poll report a push waits [REPORT_SETTLE_MS]. A 2.1 sign that sees in the report
 *     that the chooser changed asks `~` at once; that request then gets the full message (and
 *     rule 2 drops the push) instead of running into the LOW.
 * The boundary of rule 3 is as fine as the reads are apart ([ACTIVE_PAUSE_MS]): a sign that
 * asks within a few ms of the end of a pushed frame may go unanswered once and asks again.
 */
class SignLink(
    private val wire: SignWire,
    private val baud: Int,
    /** The name of the port, for the log. */
    private val name: String,
    /** Runs the changes to the message state that are waiting. Called at the start of every turn. */
    private val events: () -> Unit,
    /** Answers one request, the first [count] bytes of [buffer], by calling [send] once. The buffer is reused. */
    private val answer: (buffer: ByteArray, count: Int) -> Unit,
    /** The chooser message as it is now, as JSON; null outside chooser mode. Must not change anything. */
    private val chooserFrame: () -> String?,
    /** The frame [chooserFrame] last gave has been written to the sign in full. Not called for a push that failed, or had no frame. */
    private val pushed: () -> Unit = {},
    /** Read when a push is about to start. */
    private val attention: () -> Attention = { Attention.AUTO },
    private val timing: UartTimingLog,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Sleeps about this long; may return early (the loop then looks at the clock again). */
    private val sleepNanos: (nanos: Long) -> Unit = LockSupport::parkNanos,
    /** What was read and written, after it was written: the log must not hold up a reply. */
    private val log: (line: String) -> Unit = {},
    private val warn: (line: String) -> Unit = {},
) : Runnable {
    private val pushWanted = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    @Volatile
    private var thread: Thread? = null

    private val clock = LineClock(baud, nanoTime())
    private val buffer = ByteArray(READ_BUFFER_LEN)

    /** A read that began here took everything the sign had sent: what a later read finds came after. */
    private var takenUpToNs = nanoTime()

    /** What the sign sent before this is void (rule 3). */
    private var voidBeforeNs = takenUpToNs

    private var pushNotBeforeNs = takenUpToNs
    private var heldForReport = false
    private var lastHeardNs = takenUpToNs - ACTIVE_WINDOW_MS * NS_PER_MS
    private var lastEmptyReadNs = takenUpToNs
    private var breakFailed = false

    // What send() did for the request being answered; logged after the write.
    private var sentFrame: String? = null
    private var sentAsReport = false
    private var sentBytes = 0
    private var sentOf = 0

    private var lastWarning = ""
    private var lastWarningNs = takenUpToNs

    /** Asks for the chooser to be pushed to the sign. From any thread; returns at once. */
    fun requestPush() {
        pushWanted.set(true)
        wake()
    }

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

    /** One turn of the loop: the waiting changes, one look at the wire, then a push if one is due. */
    fun step() {
        var doing = "a change"
        try {
            events()
            doing = "read"
            val busy = readRequest()
            if (stopped) return
            doing = "push"
            if (pushWanted.get() && nanoTime() - pushNotBeforeNs >= 0) {
                push()
            } else if (!busy) {
                pause()
            }
        } catch (e: Exception) {
            // Whatever it was (the port, the peripheral service, a change that threw): this is the
            // only thread the sign can reach, so it waits a little and goes on
            complain(doing, e)
            sleepNanos(ERROR_PAUSE_MS * NS_PER_MS)
        }
    }

    /**
     * Writes one frame to the sign: all of it, or it says that it could not. For [answer], on
     * the link thread. A full message that went out is also the push that was wanted (rule 2).
     */
    fun send(frame: String, isPollReport: Boolean): Boolean {
        val bytes = frame.toByteArray()
        if (bytes.size > MAX_ARDUINO_JSON_SIZE) {
            warn("JSON string (${bytes.size}) is probably bigger than the maximum size the arduino will accept ($MAX_ARDUINO_JSON_SIZE)! ")
        }
        sentFrame = frame
        sentAsReport = isPollReport
        sentOf = bytes.size
        sentBytes = writeAll(bytes, if (isPollReport) "report" else "message")
        val whole = sentBytes == bytes.size
        if (whole && !isPollReport) pushSettled()
        if (isPollReport && pushWanted.get() && !heldForReport) {
            // Rule 6, once per push: a sign that polls without end must not hold a push off for ever
            heldForReport = true
            holdPushUntil(clock.freeAtNs + REPORT_SETTLE_MS * NS_PER_MS)
        }
        return whole
    }

    /** The push that was wanted is on its way or no longer needed; a change from here on is the next one. */
    private fun pushSettled() {
        pushWanted.set(false)
        heldForReport = false
    }

    private fun holdPushUntil(notBeforeNs: Long) {
        if (notBeforeNs - pushNotBeforeNs > 0) pushNotBeforeNs = notBeforeNs
    }

    /**
     * Reads once. Bytes that are a request (the last one is `~` or `?`) and are not void (rule 3)
     * are answered; any others are read away. True when there were bytes.
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
        if (cameAfterNs - voidBeforeNs < 0) {
            timing.dropped(readNs, buffer, count)
            return true
        }
        if (!isRequestByte(buffer[count - 1])) {
            timing.dropped(readNs, buffer, count)
            log("Read $count bytes from $name that are not a request: [${String(buffer, 0, count)}]")
            return true
        }
        // Not after stop: a message taken now would be written to a port that is closing
        if (stopped) return true
        timing.rx(readNs, readNs, lastEmptyReadNs, 1, buffer, count)
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
        if (!sentAsReport) {
            log("Read $count bytes from $name: [${String(buffer, 0, count)}]")
            log("Writing >$frame< to $name...")
        }
        // A chooser poll report goes out about five times a second: only a short one is logged
        if (!sentAsReport || sentBytes != sentOf) {
            log("Wrote $sentBytes of $sentOf bytes to $name")
        }
    }

    private fun read(into: ByteArray, length: Int): Int {
        val startNs = nanoTime()
        val n = wire.read(into, length).coerceAtMost(length)
        timing.poll(startNs, nanoTime())
        if (n < length) takenUpToNs = startNs
        return n
    }

    /**
     * Hands all of [bytes] to the transmitter. A write may take only some (it does not wait):
     * the rest is offered again until [WRITE_PATIENCE_MS] have passed with nothing taken.
     * Returns how many bytes were taken; fewer than all is a failure for the caller to act on.
     */
    private fun writeAll(bytes: ByteArray, kind: String): Int {
        val callNs = nanoTime()
        var progressNs = callNs
        var done = 0
        try {
            while (true) {
                val rest = if (done == 0) bytes else bytes.copyOfRange(done, bytes.size)
                val atNs = nanoTime()
                val took = wire.write(rest, rest.size).coerceIn(0, rest.size)
                if (took > 0) {
                    clock.wrote(atNs, took)
                    done += took
                    progressNs = atNs
                }
                if (done == bytes.size || stopped || nanoTime() - progressNs >= WRITE_PATIENCE_MS * NS_PER_MS) break
                sleepNanos(ACTIVE_PAUSE_MS * NS_PER_MS)
            }
        } catch (e: Exception) {
            complain("write", e)
        }
        timing.tx(callNs, nanoTime(), done, bytes.size, kind)
        return done
    }

    private fun push() {
        val mode = attention()
        // A change from here on is the next push. None can come before the frame is chosen:
        // changes are made by this thread, in events().
        pushSettled()
        if (mode == Attention.OFF) return
        val wantedNs = nanoTime()
        val text = try {
            chooserFrame()
        } catch (e: Exception) {
            pushFailed()
            throw e
        }
        val frame = text?.toByteArray()
        // The break starts when it is asked for, so not while a reply is still leaving
        sleepUntil(clock.freeAtNs)
        if (stopped) return
        val callNs = nanoTime()
        val low = raiseAttention(mode)
        // Rule 3, and rule 4 for a LOW that was cut short: what the sign sends into it is void
        voidBeforeNs = clock.freeAtNs
        if (!low) return pushFailed()
        val returnedNs = nanoTime()
        // The LOW, then the guard, counted from the call
        sleepUntil(clock.freeAtNs)
        if (stopped) return
        val guardEndNs = nanoTime()
        val whole = frame == null || writeAll(frame, "message") == frame.size
        voidBeforeNs = clock.freeAtNs
        if (!whole) {
            complain("push", "the frame was not taken in full")
            return pushFailed()
        }
        holdPushUntil(voidBeforeNs + PUSH_SPACING_MS * NS_PER_MS)
        val doneNs = nanoTime()
        lastHeardNs = doneNs
        timing.push(wantedNs, callNs, returnedNs, guardEndNs, doneNs, frame?.size ?: 0)
        // Like a reply: on the line first, then the log and the screen
        if (text != null) {
            log("Pushed >$text< to $name")
            pushed()
        } else {
            log("Pushed attention alone to $name")
        }
    }

    /** Rule 4: the push is still wanted, and waits a little before it is tried again. */
    private fun pushFailed() {
        pushWanted.set(true)
        holdPushUntil(nanoTime() + ERROR_PAUSE_MS * NS_PER_MS)
    }

    /** Holds the line LOW for [ATTN_BREAK_MS] and books the guard. False when the LOW was not given in full. */
    private fun raiseAttention(mode: Attention): Boolean {
        if (mode == Attention.BREAK || (mode == Attention.AUTO && !breakFailed)) {
            val calledNs = nanoTime()
            try {
                wire.sendBreak(ATTN_BREAK_MS)
            } catch (e: Exception) {
                breakFailed = true
                complain("break", e)
                // It may have pulled the line LOW before it failed: the line counts as held
                clock.low(calledNs, nanoTime(), ATTN_BREAK_MS)
                return false
            }
            clock.low(calledNs, nanoTime(), ATTN_BREAK_MS)
        } else {
            // A 0x00 byte is the start bit and 8 data bits LOW, then the stop bit HIGH.
            // Taken in one piece or it is no attention: two halves with a pause between are two short LOWs
            val zeros = ByteArray((ATTN_BREAK_MS * baud + 9_999) / 10_000)
            val atNs = nanoTime()
            val took = try {
                wire.write(zeros, zeros.size).coerceIn(0, zeros.size)
            } catch (e: Exception) {
                complain("write", e)
                0
            }
            clock.wrote(atNs, took)
            timing.tx(atNs, nanoTime(), took, zeros.size, "attention")
            if (took != zeros.size) {
                complain("push", "the attention run was not taken in full ($took of ${zeros.size} bytes)")
                return false
            }
        }
        clock.guard(ATTN_GUARD_MS)
        return true
    }

    /** One pause of the loop: short while the sign is talking or a push is waiting its turn. */
    private fun pause() {
        val now = nanoTime()
        val pushing = pushWanted.get()
        val talking = pushing || now - lastHeardNs < ACTIVE_WINDOW_MS * NS_PER_MS
        var nanos = (if (talking) ACTIVE_PAUSE_MS else IDLE_PAUSE_MS) * NS_PER_MS
        val untilPush = pushNotBeforeNs - now
        if (pushing && untilPush in 1 until nanos) nanos = untilPush
        sleepNanos(nanos)
        timing.slept(nanos, nanoTime() - now)
    }

    private fun sleepUntil(deadlineNs: Long) {
        while (!stopped) {
            val left = deadlineNs - nanoTime()
            if (left <= 0) return
            sleepNanos(left)
            if (Thread.currentThread().isInterrupted) stopped = true
        }
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

    private fun isRequestByte(byte: Byte): Boolean =
        byte == ChooserProtocol.POLL_REQUEST.code.toByte() || byte == ChooserProtocol.NEXT_REQUEST.code.toByte()

    companion object {
        // ---- The push (chooser contract VERSION 3.1)
        /** How long the line is held LOW to get the sign's attention: longer than one scroll step (35 ms). */
        const val ATTN_BREAK_MS: Int = 50

        /** From the end of the LOW to the first byte of the frame: the sign empties its buffer in it. */
        const val ATTN_GUARD_MS: Int = 20

        /** From the end of one push to the start of the next: a 2.1 sign polls at least every 250 ms, so it gets a turn. */
        const val PUSH_SPACING_MS: Int = 250

        /** From the end of a poll report to a push: time for a 2.1 sign to ask `~` when the report differs (rule 6). */
        const val REPORT_SETTLE_MS: Int = 15

        // ---- Reading
        /** Pause between two reads while the sign is talking. A request waits at most this long to be seen. */
        const val ACTIVE_PAUSE_MS: Int = 2

        /** Pause between two reads otherwise. A `~` out of the blue waits a second for its answer. */
        const val IDLE_PAUSE_MS: Int = 10

        /** How long after the last byte from the sign (or a push) the short pause is kept. */
        const val ACTIVE_WINDOW_MS: Int = 2_000

        /** The most bytes taken in one read (the size the UART callback read with). */
        const val READ_BUFFER_LEN: Int = 32

        // ---- Writing and failing
        /** How long a write is offered again while the transmitter takes nothing (below the sign's 60 ms a byte). */
        const val WRITE_PATIENCE_MS: Int = 50

        /** Pause after a read that failed, and before a failed push is tried again. */
        const val ERROR_PAUSE_MS: Int = 100

        const val WARN_EVERY_MS: Int = 5_000

        private const val MAX_ARDUINO_JSON_SIZE: Int = 700
        private const val NS_PER_MS: Long = 1_000_000L
    }
}
