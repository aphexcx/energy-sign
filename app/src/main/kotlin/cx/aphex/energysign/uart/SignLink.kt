package cx.aphex.energysign.uart

import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.Reply
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

    /** Asks for the line to be held LOW for [millis] ms. Returns when it likes: at once, or when the line is HIGH again. */
    @Throws(IOException::class)
    fun sendBreak(millis: Int)

    @Throws(IOException::class)
    fun close()
}

/** How a push gets the sign's attention. The four words of the switch ([AttentionSwitch]). */
enum class Attention {
    /** No pushes at all: the app behaves as chooser contract 2.1. The default. */
    OFF,

    /**
     * Whatever gets this sign's attention. That is the run of zeros ([ZEROS]): measured on the
     * sign on 2026-11-18, the sign saw the run 11 times of 11 and the break 0 times of 11.
     */
    AUTO,

    /**
     * `UartDevice.sendBreak`. On the sign's board the call returns after 1 to 6 ms and the line
     * is not held LOW, so the sign does not see it. Kept as a word of the switch for measuring.
     */
    BREAK,

    /** A run of 0x00 bytes as long as the break would be: the line is LOW nine tenths of that time. */
    ZEROS;

    companion object {
        /** One of the four words `off`, `auto`, `break`, `zeros`; null for anything else. */
        fun parse(word: String?): Attention? = when (word?.trim()) {
            "off" -> OFF
            "auto" -> AUTO
            "break" -> BREAK
            "zeros" -> ZEROS
            else -> null
        }
    }
}

/**
 * WHEN THE TRANSMIT LINE IS FREE. The one place that knows.
 *
 * `write` and `sendBreak` do not wait, so the line is busy after they return: a byte takes ten
 * bit times to leave, behind the bytes that are still leaving; a break holds the line for its
 * length; and after an attention the app owes the sign a silence. Everything the link puts on
 * the line is booked here, and no push starts, and no pushed frame is written, before [freeAtNs].
 *
 * All of it is counted from the RETURN of the call, the latest moment the line can have seen
 * it. A time taken before the call is too early whenever the thread is held up between taking
 * the time and making the call (it was: the frame then went out into the LOW). The price of the
 * later bound is that the line may be free a call's length sooner than this says, never later.
 *
 * Times are `System.nanoTime` values: only their differences mean anything.
 */
class LineClock(private val baud: Int, startNs: Long) {
    /** The line carries nothing of ours from here on. */
    var freeAtNs: Long = startNs
        private set

    /** [count] bytes were handed to the transmitter by a write that returned at [returnedNs]. */
    fun wrote(returnedNs: Long, count: Int) {
        if (count > 0) freeAtNs = later(freeAtNs, returnedNs) + count * 10 * NS_PER_S / baud
    }

    /** A break of [millis] ms was asked for by a call that returned at [returnedNs]. */
    fun low(returnedNs: Long, millis: Int) {
        freeAtNs = later(freeAtNs, returnedNs + millis * NS_PER_MS)
    }

    /** [millis] ms of silence behind what is on the line now. */
    fun quiet(millis: Int) {
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
 * A REQUEST IS ANSWERED WHILE THE SIGN LISTENS. An answer can take a message from its queue, so
 * it must not go to a sign that has stopped listening. The link knows how long a request can
 * have waited: since the read before the one that found it (longer than a pause only when the
 * thread was kept from the wire: a sleep that overran, reads that kept failing). And it knows
 * how long the sign listens: 60 ms in its chooser display, a second otherwise. The sign may be
 * in its chooser display from the moment it is sent a chooser message (as an answer or pushed)
 * or a poll report, or sends a `?` itself, until it is sent a message of another kind in full;
 * and when the link starts it may be (the app can restart under a sign that stays where it is).
 * A request that may have waited longer than [CHOOSER_FRESH_MS] then, or [FRESH_MS] otherwise,
 * is old: it is read away unanswered, nothing is taken, and the sign asks again. The same limit
 * holds for the answer: its first byte must be with the transmitter before the limit, or the
 * frame is a failed one.
 *
 * THE PUSH (chooser contract VERSION 3.1; off unless [attention] says otherwise). The app knows
 * the moment the chooser changes, so the sign need not ask while a text moves. [requestPush]
 * asks for a push; only a flag is kept, so any number of changes are one push of the newest
 * state. A push gets the sign's attention (a run of zeros [ATTN_BREAK_MS] long: the sign sees
 * the line LOW between two frames), stays silent for [ATTN_GUARD_MS], and then writes
 * [chooserFrame]: the chooser message, or nothing at all when the app is not in chooser mode
 * (the sign finds nothing after the attention and asks with `~`, and that real request takes
 * the next message). [chooserFrame] is all a push can reach: it cannot take a message from a
 * queue or move an index, so whatever becomes of a push, nothing is lost with it. The link
 * writes nothing behind the frame's closing brace.
 *
 * ONE REQUEST, AT MOST ONE FRAME. The rules, and the only place they are decided ([step],
 * [readRequest], [push]):
 *  1. Every turn runs the waiting changes, then reads the wire, before anything else. A request
 *     found there is answered with one frame.
 *  2. If that frame was a full message, a push that was wanted is dropped: the sign has just
 *     been sent, at its own asking, the state the push would carry. (The attention would hit a
 *     sign that is waiting for its answer.) A poll report is not that state: the push stays.
 *  3. A push starts when the line is free of what was sent before ([LineClock]); until then the
 *     loop goes on reading. From the read of the turn that starts it until the push is over
 *     (the last byte of its frame has left the line, or its guard has passed when it has no
 *     frame), whatever the sign sends is void: it was sent into the attention or into the frame,
 *     the sign cannot have been listening for an answer to it, and it asks again. Those bytes
 *     are read away and none is answered, whether they came while the frame was being made,
 *     into the attention, into the guard, or while the frame was leaving.
 *  4. A push that failed (the attention or the frame was not taken in full) is still wanted and
 *     is tried again. Rule 3 holds for whatever it did put on the line, or may have (a break
 *     call that threw): a request sent into that is void, a request that came after it is
 *     answered, and if that answer is a full message it is the push (rule 2).
 *  5. From the end of what one push put on the line to the start of the next there are
 *     [PUSH_SPACING_MS], after a push that failed too. However fast the changes come, the sign
 *     has a quiet line to ask on in between, and is answered.
 *
 * BEHIND A PUSHED FRAME. The firmware that listens for pushes sends a request only when the line
 * has been quiet for [SIGN_QUIET_BEFORE_REQUEST_MS]. Rule 3 ends where the frame ends, by the
 * line clock; so a request can be told from what came into the frame as long as the link looks
 * at the wire once inside those 10 ms. It does: after a push the loop keeps its short pause.
 * The line clock may say the frame is gone a call's length late (see [LineClock]), a read costs
 * under a millisecond and the pause is [ACTIVE_PAUSE_MS]; that leaves room. A link kept from
 * the wire for longer than that just then (a sleep that overran by ten ms) leaves that one
 * request unanswered, takes nothing for it, and the sign asks again.
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
    /** The chooser message as it is now, as one frame; null outside chooser mode. Must not change anything. */
    private val chooserFrame: () -> String? = { null },
    /** The frame [chooserFrame] last gave is on the wire in full. Not called for a push that failed, or had no frame. */
    private val pushed: () -> Unit = {},
    /** Whether and how the chooser is pushed. Read when a push is about to start. Off unless this says otherwise. */
    private val attention: () -> Attention = { Attention.OFF },
    private val timing: UartTimingLog,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Sleeps about this long; may return early (whoever waits for a time looks at the clock again). */
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

    private val buffer = ByteArray(READ_BUFFER_LEN)

    /** A read that began here took everything the sign had sent: what a later read finds came after. */
    private var takenUpToNs = nanoTime()

    /** When the line is free of what the link sent. Everything written or held LOW is booked here. */
    private val clock = LineClock(baud, takenUpToNs)

    /** What the sign sent before this is void (rule 3). */
    private var voidBeforeNs = takenUpToNs

    /** No push starts before this (rule 5, and the pause after a push that failed). */
    private var pushNotBeforeNs = takenUpToNs

    /** The sign may be in its chooser display, where it listens 60 ms for an answer. Until the link knows better, it may. */
    private var chooserDisplay = true

    /** The first byte of the answer to the request in hand must be with the transmitter by this. */
    private var answerByNs = takenUpToNs

    private var lastHeardNs = takenUpToNs - ACTIVE_WINDOW_MS * NS_PER_MS
    private var lastEmptyReadNs = takenUpToNs
    private var readFailures = 0

    /** The attention of the words `auto` and `zeros`: 0x00 is the start bit and eight data bits LOW, then the stop bit HIGH. */
    private val zeroRun = ByteArray((ATTN_BREAK_MS * baud + 9_999) / 10_000)

    // What send() did for the request being answered; logged after the write.
    private var sentFrame: String? = null
    private var sentAs = Reply.MESSAGE
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
            readFailures = 0
            if (stopped) return
            doing = "push"
            val pushing = pushDue() && push()
            if (!busy && !pushing) pause()
        } catch (e: Exception) {
            // Whatever it was (the port, the peripheral service, a change that threw): this is the
            // only thread the sign can reach, so it waits a little and goes on
            complain(doing, e)
            // A read that failed is tried again at once, inside the time the sign listens; the
            // long pause is for a port that keeps failing
            val again = doing == "read" && ++readFailures <= QUICK_READ_RETRIES
            sleepNanos((if (again) ACTIVE_PAUSE_MS else ERROR_PAUSE_MS) * NS_PER_MS)
        }
    }

    /**
     * Writes one frame to the sign: all of it, or it says that it could not. For [answer], on
     * the link thread. [reply] says what the frame is to the sign. A full message that went out
     * is also the push that was wanted (rule 2).
     */
    fun send(frame: String, reply: Reply): Boolean {
        val bytes = frame.toByteArray()
        sentFrame = frame
        sentAs = reply
        sentOf = bytes.size
        sentBytes = writeAll(bytes, if (reply == Reply.REPORT) "report" else "message", answerByNs)
        val whole = sentBytes == bytes.size
        // A message of another kind ends the sign's chooser display; a chooser message or a report keeps it there
        if (whole) chooserDisplay = reply != Reply.MESSAGE
        if (whole && reply != Reply.REPORT) pushWanted.set(false)
        return whole
    }

    /**
     * Reads once. Bytes that are a request (the last one is `~` or `?`), were not sent into a
     * push (rule 3) and are not old are answered; any others are read away. True when there
     * were bytes.
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
        if (cameAfterNs - voidBeforeNs < 0) {
            // Rule 3: it may have been sent into a push, and the sign was not listening for an answer to it
            timing.dropped(readNs, buffer, count)
            log("Read $count bytes from $name that the sign sent into a push: not answered")
            return true
        }
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
     *  - when the first byte would come after the sign has stopped listening for it ([firstByNs];
     *    null for what is not an answer: an attention, a pushed frame),
     *  - when the line has stood still for [WRITE_PATIENCE_MS] (the sign gives up 60 ms after a
     *    byte): since the call when nothing was taken yet, since the last byte taken left the
     *    line otherwise, each counted from the START of the offer that took it,
     *  - when the whole frame has had the time it needs on the line and [WRITE_PATIENCE_MS] more:
     *    a transmitter that takes a byte now and then must not keep this thread, which serves
     *    everything, for seconds.
     * The standstill is counted from the start of an offer because that is the EARLIEST the
     * bytes can have left: the sign may have given up by then. When the line is FREE again is
     * the other question, with the other bound, and the line clock's: every byte taken is
     * booked there from the return of its write.
     * Returns how many bytes were taken; fewer than all is a failed frame for the caller to act
     * on. The sign throws the torn start away with its failed read and asks again.
     */
    private fun writeAll(bytes: ByteArray, kind: String, firstByNs: Long?): Int {
        val callNs = nanoTime()
        val patienceNs = WRITE_PATIENCE_MS * NS_PER_MS
        val frameByNs = callNs + bytes.size * 10 * NS_PER_S / baud + patienceNs
        // When the bytes taken so far have left the line: ten bit times each
        var goneAtNs = callNs
        var done = 0
        try {
            while (!stopped) {
                val offerNs = nanoTime()
                if (done == 0 && firstByNs != null && offerNs - firstByNs > 0) break
                if (offerNs - goneAtNs >= patienceNs || offerNs - frameByNs > 0) break
                val rest = if (done == 0) bytes else bytes.copyOfRange(done, bytes.size)
                val took = wire.write(rest, rest.size).coerceIn(0, rest.size)
                if (took > 0) {
                    clock.wrote(nanoTime(), took)
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

    /** A push is wanted, its distance from the last one is kept (rule 5), and the line is free of what was sent before. */
    private fun pushDue(): Boolean {
        if (!pushWanted.get()) return false
        val now = nanoTime()
        return now - pushNotBeforeNs >= 0 && now - clock.freeAtNs >= 0
    }

    /**
     * One push: the attention, the guard, the chooser frame. False when the switch is off (the
     * line was not touched and no time passed). This thread sleeps through the attention and the
     * guard: the sign is not listening for an answer then, so there is nobody to keep waiting.
     */
    private fun push(): Boolean {
        val mode = attention()
        // A change from here on is the next push. None can come before the frame is made:
        // changes are made by this thread, in events().
        pushWanted.set(false)
        if (mode == Attention.OFF) return false
        val wantedNs = nanoTime()
        val lineWasNs = clock.freeAtNs
        val text = try {
            chooserFrame()
        } catch (e: Exception) {
            pushFailed(lineWasNs)
            throw e
        }
        val frame = text?.toByteArray()
        val callNs = nanoTime()
        val raised = raiseAttention(mode)
        // Rule 3, and rule 4 for an attention that was cut short: what the sign sends into it is void
        voidBeforeNs = clock.freeAtNs
        if (!raised) return pushFailed(lineWasNs)
        val raisedNs = nanoTime()
        clock.quiet(ATTN_GUARD_MS)
        sleepUntil(clock.freeAtNs)
        if (stopped) return true
        val guardEndNs = nanoTime()
        val whole = frame == null || writeAll(frame, "message", null) == frame.size
        // Rule 3 lasts until the frame has left the line; with no frame, until the guard is over
        voidBeforeNs = clock.freeAtNs
        if (!whole) {
            complain("push", "the frame was not taken in full")
            return pushFailed(lineWasNs)
        }
        // The sign is in its chooser display with this message, as with one it asked for
        if (frame != null) chooserDisplay = true
        holdPushUntil(clock.freeAtNs + PUSH_SPACING_MS * NS_PER_MS)
        val doneNs = nanoTime()
        // The short pause from here on: the sign's next request must be told from what came into the frame
        lastHeardNs = doneNs
        timing.push(wantedNs, callNs, raisedNs, guardEndNs, doneNs, frame?.size ?: 0)
        // Like a reply: on the line first, then the log and the screen
        if (text != null) {
            log("Pushed >$text< to $name")
            pushed()
        } else {
            log("Pushed attention alone to $name")
        }
        return true
    }

    /**
     * Rule 4: the push is still wanted. It waits [ERROR_PAUSE_MS], and when this try put
     * anything on the line (it was free at [lineWasNs] before), the distance of rule 5 from that.
     */
    private fun pushFailed(lineWasNs: Long): Boolean {
        pushWanted.set(true)
        holdPushUntil(nanoTime() + ERROR_PAUSE_MS * NS_PER_MS)
        if (clock.freeAtNs != lineWasNs) holdPushUntil(clock.freeAtNs + PUSH_SPACING_MS * NS_PER_MS)
        return true
    }

    private fun holdPushUntil(notBeforeNs: Long) {
        if (notBeforeNs - pushNotBeforeNs > 0) pushNotBeforeNs = notBeforeNs
    }

    /**
     * Gets the sign's attention: the line LOW for [ATTN_BREAK_MS], booked on the line clock.
     * False when it was not given in full; what did get onto the line is booked all the same.
     */
    private fun raiseAttention(mode: Attention): Boolean {
        if (mode == Attention.BREAK) {
            try {
                wire.sendBreak(ATTN_BREAK_MS)
            } catch (e: Exception) {
                complain("break", e)
                // It may have pulled the line LOW before it failed: the line counts as held
                clock.low(nanoTime(), ATTN_BREAK_MS)
                return false
            }
            clock.low(nanoTime(), ATTN_BREAK_MS)
            return true
        }
        // In pieces if the transmitter takes it so: pieces that follow each other while the
        // first are still leaving are one run on the line
        val took = writeAll(zeroRun, "attention", null)
        if (took != zeroRun.size) {
            complain("push", "the attention was not taken in full ($took of ${zeroRun.size} bytes)")
            return false
        }
        return true
    }

    /** One pause of the loop: short while the sign is talking or a push is waiting its turn, and no longer than that turn is away. */
    private fun pause() {
        val now = nanoTime()
        val pushing = pushWanted.get()
        val talking = pushing || now - lastHeardNs < ACTIVE_WINDOW_MS * NS_PER_MS
        var nanos = (if (talking) ACTIVE_PAUSE_MS else IDLE_PAUSE_MS) * NS_PER_MS
        if (pushing) {
            val turnNs = if (pushNotBeforeNs - clock.freeAtNs > 0) pushNotBeforeNs else clock.freeAtNs
            val untilPush = turnNs - now
            if (untilPush in 1 until nanos) nanos = untilPush
        }
        sleepNanos(nanos)
        timing.slept(nanos, nanoTime() - now)
    }

    /** Sleeps until the clock says [deadlineNs], or the link is stopped. */
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

    companion object {
        // ---- The push (chooser contract VERSION 3.1)
        /** How long the line is held LOW to get the sign's attention: longer than one scroll step (35 ms). */
        const val ATTN_BREAK_MS: Int = 50

        /** From the end of the attention to the first byte of the frame: the sign empties its buffer in it. */
        const val ATTN_GUARD_MS: Int = 20

        /**
         * From the end of what one push put on the line to the start of the next. The firmware
         * needs 110 ms of quiet line to get over a frame it could not read, and a sign that
         * still polls does so at least every 250 ms: either gets its turn.
         */
        const val PUSH_SPACING_MS: Int = 250

        /** The firmware that listens for pushes asks only after the line has been quiet for this long (its REQUEST_QUIET_MS). */
        const val SIGN_QUIET_BEFORE_REQUEST_MS: Int = 10

        // ---- Reading
        /** Pause between two reads while the sign is talking. A request waits at most this long to be seen. */
        const val ACTIVE_PAUSE_MS: Int = 2

        /** Pause between two reads otherwise. A `~` out of the blue waits a second for its answer. */
        const val IDLE_PAUSE_MS: Int = 10

        /** How long after the last byte from the sign (or a push) the short pause is kept. */
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

        /** Pause after a failure that is not a first failed read, and before a failed push is tried again. */
        const val ERROR_PAUSE_MS: Int = 100

        const val WARN_EVERY_MS: Int = 5_000

        private val POLL: Byte = ChooserProtocol.POLL_REQUEST.code.toByte()
        private val NEXT: Byte = ChooserProtocol.NEXT_REQUEST.code.toByte()
        private const val NS_PER_MS: Long = 1_000_000L
        private const val NS_PER_S: Long = 1_000_000_000L
    }
}
