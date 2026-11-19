package cx.aphex.energysign.uart

import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.Reply
import cx.aphex.energysign.message.SignResponder
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
 * What the app last put on the line, and how long the sign needs behind it before a push may
 * start. The numbers are the firmware's; the table is at [LineClock].
 */
enum class Behind(val holdMs: Int) {
    /** An attention: with its frame, alone, in part, or one whose call threw. */
    ATTENTION(SignLink.PUSH_SPACING_MS),

    /** The start of a frame (a reply, a report, a pushed frame) without its end, or a frame that may be so. */
    TORN_FRAME(SignLink.TORN_FRAME_MS),
}

/**
 * THE STATE OF THE TRANSMIT LINE: when it is free, and when a push may start. The one place
 * that knows.
 *
 * `write` and `sendBreak` do not wait, so the line is busy after they return: a byte takes ten
 * bit times to leave, behind the bytes that are still leaving; a break holds the line for its
 * length; and after an attention the app owes the sign a silence. Everything the link puts on
 * the line is booked here, and no push starts, and no pushed frame is written, before [freeAtNs].
 *
 * All of it is counted from the RETURN of the call, the latest moment the line can have seen
 * it. A time taken before the call is too early whenever the thread is held up between taking
 * the time and making the call (it was: the frame then went out into the LOW). The price of the
 * later bound is that the line may be free a call's length sooner than this says; never later,
 * as long as the transmitter starts on what it took by the time the call returns.
 *
 * WHEN A PUSH MAY START ([pushAtNs]). A free line is not enough: behind some things the sign is
 * busy with what it got, and an attention sent into that is thrown away with it while the app
 * counts it as given. Every way a write of the app can end, what the sign does then by the
 * firmware's own numbers (the push firmware: lm-t7f, ROUND-3-PR6-fixes-lists; the polling
 * firmware: 60 ms a byte), and from when a push may start:
 *
 *  - A reply or a report, whole: the sign reads it and is done. When the line is free.
 *  - A reply or a report of which NOTHING was taken: the sign still waits for its answer (a
 *    second; a poll 60 ms). When the line is free: the zeros are padding in front of an answer,
 *    and the pushed frame is what the sign reads behind its request.
 *  - A reply or a report of which a PART was taken, or whose write threw (a part may be out):
 *    the sign waits for the next byte, a second with the push firmware, 60 ms with the polling
 *    one, and whatever comes is the rest of that frame to it. 1010 ms behind the last byte
 *    that is or may be out ([Behind.TORN_FRAME]).
 *  - A pushed frame of which a part was taken, of which a piece was taken late, or whose write
 *    threw: the same. The sign gives a frame that came unasked 100 ms a byte and then wants
 *    110 ms of quiet; but when a request of the sign crossed the push, the frame is its answer
 *    and it waits a second. 1010 ms likewise.
 *  - A pushed frame whole, the attention alone, or an attention whose frame was not begun: the
 *    sign reads the frame, or listens 150 ms and asks 10 ms later; it needs 110 ms of quiet to
 *    get over a frame it could not read. 250 ms behind it ([Behind.ATTENTION]).
 *  - An attention in part, with a hole in it, or whose call threw: the sign saw it, listens
 *    150 ms and asks; or it did not see it. 250 ms behind what is or may be out.
 *  - Nothing on the line (the frame could not be made, not one zero was taken): nothing. A
 *    pause from now ([holdFrom]).
 * Requests are read and answered through all of these: only a push waits.
 *
 * Times are `System.nanoTime` values: only their differences mean anything.
 */
class LineClock(private val baud: Int, startNs: Long) {
    /** The line carries nothing of ours from here on. */
    var freeAtNs: Long = startNs
        private set

    private var heldUntilNs: Long = startNs

    /** A push may start from here on: the line is free, and the sign has had what it needs behind what was last on it. */
    val pushAtNs: Long
        get() = later(freeAtNs, heldUntilNs)

    /** The sign needs [behind]'s time after what is on the line now (or may be) has left. A longer hold that stands is kept. */
    fun hold(behind: Behind) {
        heldUntilNs = later(heldUntilNs, freeAtNs + behind.holdMs * NS_PER_MS)
    }

    /** Nothing was put on the line: a push waits [millis] ms from [nowNs]. A longer hold that stands is kept. */
    fun holdFrom(nowNs: Long, millis: Int) {
        heldUntilNs = later(heldUntilNs, nowNs + millis * NS_PER_MS)
    }

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
 *  3. A push starts when the line's state lets it ([LineClock.pushAtNs]: the line is free, and
 *     the sign has got over whatever the app last put on it); until then the loop goes on
 *     reading and answering. From the read of the turn that starts it until the push is over
 *     (the last byte of its frame has left the line, or its guard has passed when it has no
 *     frame), whatever the sign sends is void: it was sent into the attention or into the frame.
 *     With a frame, that frame is what the sign reads behind its request; with none, the sign
 *     gets nothing and asks again. Either way a second frame would be one too many. Those bytes
 *     are read away and none is answered, whether they came while the frame was being made,
 *     into the attention, into the guard, or while the frame was leaving.
 *  4. A push that failed (the attention or the frame was not taken in full) is still wanted and
 *     is tried again. Rule 3 holds for whatever it did put on the line, or may have (a break
 *     call that threw): a request sent into that is void, a request that came after it is
 *     answered, and if that answer is a full message it is the push (rule 2).
 *  5. From the end of what one push put on the line to the start of the next there are
 *     [PUSH_SPACING_MS], after a push that failed too, and [TORN_FRAME_MS] behind a frame of
 *     which the sign has the start and not the end ([Behind]). However fast the changes come,
 *     the sign has a quiet line to ask on in between, and is answered.
 *
 * WHAT IS ON THE LINE IS JUDGED BY WHEN BYTES LEAVE IT. A write is called, hands its bytes over
 * some time before it returns, and the bytes leave behind those that are still leaving. So:
 *  - "the line is free", "the guard is over", "the frame has left" (rule 3's end, rule 5's
 *    start) are the line clock's, counted from the RETURN of every call: the latest it can be.
 *  - "the run of zeros is one run" and "the pushed frame came in one piece" are judged for
 *    every piece by the return of its write against the EARLIEST the pieces before it can have
 *    left (counted from the start of their offers). When a piece may have been handed over
 *    after the line stood still, the push is given up and tried again: it takes nothing.
 *  - A pushed frame that begins late (the link was kept from the wire inside the guard) is not
 *    judged at all: the sign has asked by then, and the frame is what it reads behind its
 *    request (rule 3 reads that request away).
 *  - An ANSWER is judged as master judges it, by the start of each offer ([writeAll]). To call
 *    an answer failed because a piece may have been late would hand its message back, and the
 *    sign may show it twice; to call it sent may lose it. Without an acknowledgement from the
 *    sign neither is right, and the push does not move that line.
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
    /** Runs pending message changes, checking for a stop between them. */
    private val events: (stopped: () -> Boolean) -> Unit,
    /** Answers one request, the first [count] bytes of [buffer], by calling [send] once. The buffer is reused. */
    private val answer: (buffer: ByteArray, count: Int) -> Unit,
    /** The chooser message as it is now, as one frame; null outside chooser mode. Must not change anything. */
    private val chooserFrame: () -> String? = { null },
    /** The frame [chooserFrame] last gave is on the wire in full. Not called for a push that failed, or had no frame. */
    private val pushed: () -> Unit = {},
    /** Whether and how the chooser is pushed. Asked in every turn in which a push is wanted. Off unless this says otherwise. */
    private val attention: () -> Attention = { Attention.OFF },
    private val timing: UartTimingLog,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Sleeps about this long; may return early (whoever waits for a time looks at the clock again). */
    private val sleepNanos: (nanos: Long) -> Unit = LockSupport::parkNanos,
    /** What was read and written, after it was written: the log must not hold up a reply. */
    private val log: (line: String) -> Unit = {},
    private val warn: (line: String) -> Unit = {},
    private val prepareAnswers: (stopped: () -> Boolean) -> Unit = { SignResponder.warmUp(it) },
) : Runnable {
    // ---- The pending change (the table in the class comment: WHEN A CHANGE IS DONE)
    /** How many changes have been recorded ([want]). The only thing another thread may move. */
    private val wanted = AtomicLong(0)

    /**
     * The changes up to this count are done: the sign has them ([frameWritten]), or they were
     * given up on purpose ([dropped]). Assigned in those two functions and nowhere else. A
     * change is pending while this is not [wanted].
     */
    private var settled = 0L

    /** The changes up to this count have had the attention alone: it is not raised again for them. */
    private var aloneGivenFor = -1L

    /** The changes up to this count are in the answer that is being made: counted before it is made. */
    private var answerCarries = 0L

    /** The void time (rule 3) is that of a pushed frame that was called done. */
    private var pushedFrameInVoid = false

    @Volatile
    private var stopped = false
    private val stopRequested = { stopped }

    @Volatile
    private var thread: Thread? = null

    private val buffer = ByteArray(READ_BUFFER_LEN)

    /** A read that began here took everything the sign had sent: what a later read finds came after. */
    private var takenUpToNs = nanoTime()

    /** When the line is free of what the link sent. Everything written or held LOW is booked here. */
    private val clock = LineClock(baud, takenUpToNs)

    /** What the sign sent before this is void (rule 3). */
    private var voidBeforeNs = takenUpToNs

    /** The sign may be in its chooser display, where it listens 60 ms for an answer. Until the link knows better, it may. */
    private var chooserDisplay = true

    /** The first byte of the answer to the request in hand must be with the transmitter by this. */
    private var answerByNs = takenUpToNs

    private var lastHeardNs = takenUpToNs - ACTIVE_WINDOW_MS * NS_PER_MS
    private var lastEmptyReadNs = takenUpToNs
    private var readFailures = 0

    /** The attention of the words `auto` and `zeros`: 0x00 is the start bit and eight data bits LOW, then the stop bit HIGH. */
    private val zeroRun = ByteArray(ATTN_BREAK_MS * baud / 10_000)

    // What send() did for the request being answered; logged after the write.
    private var sentFrame: String? = null
    private var sentAs = Reply.MESSAGE
    private var sentBytes = 0
    private var sentOf = 0

    // What the last writeAll() left: how many bytes were taken; whether the start of something
    // is, or may be, on the line without its end in time behind it; whether the line can say
    // that the sign has all of it, in one piece and begun in time; and whether it was begun late
    private var takenLast = 0
    private var tornLast = false
    private var provenLast = false
    private var lateStartLast = false

    /** When each kind of failure was last said. (A switch that cannot be asked may be said from another thread.) */
    private val lastWarningNs = ConcurrentHashMap<String, Long>()

    /**
     * Asks for the chooser to be pushed to the sign; returns at once. The app calls it on the
     * link thread, from a change the link is running. Another thread may call it; the loop is
     * woken. A change is recorded only while pushes are on: one made while they are off is
     * not pushed, not even when they are switched on a moment later in the same turn.
     */
    fun requestPush() {
        want()
        // The link thread asking for itself (a change it has just made) is awake: a wake-up
        // would only cut its next sleep short
        if (Thread.currentThread() !== thread) wake()
    }

    /** SETS: the sign is behind by one more change. Not while pushes are off: then nothing is owed to it unasked. */
    private fun want() {
        if (switchInForce() != Attention.OFF) wanted.incrementAndGet()
    }

    /** What the switch says now. A switch that cannot be asked is off. */
    private fun switchInForce(): Attention =
        try {
            attention()
        } catch (e: Exception) {
            complain("push", e)
            Attention.OFF
        }

    /**
     * THE ONE PLACE A PENDING CHANGE IS CALLED DONE. Called behind every write of a frame (a
     * reply, a report, a pushed frame), with the count of changes that frame carries ([carries];
     * null for a report, which is not the selection). The changes up to that count are done
     * when, and only when, the line says the sign has the frame ([provenLast]: every byte was
     * taken, no piece may have come late, and the first byte was with the transmitter while the
     * sign listened for it). Changes recorded after the frame was made are not in it and stay
     * pending. Everything else that can happen to a frame leaves the change where it is.
     */
    private fun frameWritten(carries: Long?) {
        if (carries != null && provenLast) settled = carries
    }

    /** The other way a pending change ends, and it is not "done": pushes are off, so it is given up. The sign's next request gets the selection as it is then. */
    private fun dropped() {
        settled = wanted.get()
    }

    /** A push has something to do: a change is pending, and the attention alone has not been given for it. */
    private fun pushWaiting(): Boolean {
        val count = wanted.get()
        return count != settled && count != aloneGivenFor
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

    /** One turn of the loop: the waiting changes, one look at the wire, then a push if one is due. */
    fun step() {
        var doing = "a change"
        try {
            events(stopRequested)
            if (stopped) return
            doing = "read"
            val busy = readRequest()
            readFailures = 0
            if (stopped) return
            doing = "push"
            val mode = duePush()
            if (mode != null) push(mode) else if (!busy) pause()
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
     * the link thread. [reply] says what the frame is to the sign. A full message is also the
     * state a push would carry (rule 2); whether the pending change is done with it is not
     * decided here ([frameWritten]), and not by what this returns: that is about the MESSAGE.
     */
    fun send(frame: String, reply: Reply): Boolean {
        val bytes = frame.toByteArray()
        sentFrame = frame
        sentAs = reply
        sentOf = bytes.size
        val whole = writeAll(bytes, if (reply == Reply.REPORT) Writing.REPORT else Writing.ANSWER, answerByNs)
        sentBytes = takenLast
        // A sign that has the start of a frame waits for the rest: no push into that
        if (tornLast) clock.hold(Behind.TORN_FRAME)
        // A message of another kind ends the sign's chooser display; a chooser message or a report keeps it there
        if (whole) chooserDisplay = reply != Reply.MESSAGE
        frameWritten(if (reply == Reply.REPORT) null else answerCarries)
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
        if (pushedFrameInVoid && cameAfterNs - voidBeforeNs < 0) {
            // SETS: the sign was sending while a pushed frame that was called done was on the
            // line, or just before or behind it. It may not have that frame (it does not hear
            // while it sends; or it did not see the attention and asks because bytes went by):
            // the frame is owed again. Once for one frame, whatever came into it.
            pushedFrameInVoid = false
            wanted.incrementAndGet()
        }
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
        // The answer is made from here on: a change recorded after this is not in it
        answerCarries = wanted.get()
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
     *
     * Called in time is not handed over in time. So every further piece is judged again when
     * its write has RETURNED, the latest it can have been handed over: the line MAY have stood
     * still too long when the pieces before it may have left it by then (the run of zeros,
     * which may not stand still at all once it has begun: a run with a hole is two short LOWs,
     * and the sign may see neither), or left it longer ago than [WRITE_PATIENCE_MS] (a frame).
     *  - What comes UNASKED (the zeros, a pushed frame) is then not whole, and nothing more of
     *    it is offered: giving a push up costs nothing.
     *  - An ANSWER is written on and is whole, as on master (see the class comment for why the
     *    push does not move that line); but it counts as torn, so that no push follows it
     *    into a sign that may be busy with a frame that broke off.
     *
     * True when all of it was taken, and in one piece where that decides. [takenLast] has the
     * count, and [tornLast] says whether the start of it is, or may be, on the line without its
     * end in time behind it (only some of it was taken, a piece may have come late, or a write
     * threw): the sign may be busy with a frame that does not end ([Behind.TORN_FRAME]).
     */
    private fun writeAll(bytes: ByteArray, writing: Writing, firstByNs: Long?): Boolean {
        val callNs = nanoTime()
        val patienceNs = WRITE_PATIENCE_MS * NS_PER_MS
        val frameByNs = callNs + bytes.size * 10 * NS_PER_S / baud + patienceNs
        // The EARLIEST the bytes taken so far can have left the line: ten bit times each, from the start of their offer
        var goneAtNs = callNs
        var done = 0
        // A piece may have been handed over after the line had stood still too long; the first
        // byte was not, or may not have been, with the transmitter by its time; a write threw
        var late = false
        var lateStart = false
        var threw = false
        try {
            while (!stopped) {
                val offerNs = nanoTime()
                if (done == 0 && firstByNs != null && offerNs - firstByNs > 0) {
                    lateStart = true
                    break
                }
                if (offerNs - goneAtNs >= patienceNs || offerNs - frameByNs > 0) break
                if (writing == Writing.ATTENTION && done > 0 && offerNs - goneAtNs > 0) break
                val rest = if (done == 0) bytes else bytes.copyOfRange(done, bytes.size)
                val took = wire.write(rest, rest.size).coerceIn(0, rest.size)
                if (took > 0) {
                    val returnedNs = nanoTime()
                    clock.wrote(returnedNs, took)
                    // The first byte may have been handed over as late as its call returned
                    if (done == 0 && firstByNs != null && returnedNs - firstByNs > 0) lateStart = true
                    // The piece may have gone out as late as its call returned
                    val stoodStillNs = returnedNs - goneAtNs
                    if (done > 0 && (if (writing == Writing.ATTENTION) stoodStillNs > 0 else stoodStillNs >= patienceNs)) late = true
                    goneAtNs = (if (offerNs - goneAtNs > 0) offerNs else goneAtNs) + took * 10 * NS_PER_S / baud
                    done += took
                }
                if (done == bytes.size || (late && writing.unasked)) break
                sleepNanos(ACTIVE_PAUSE_MS * NS_PER_MS)
            }
        } catch (e: Exception) {
            complain("write", e)
            // It may have handed bytes over before it failed: the line counts as carrying the rest
            clock.wrote(nanoTime(), bytes.size - done)
            threw = true
        }
        timing.tx(callNs, nanoTime(), done, bytes.size, writing.kind)
        takenLast = done
        lateStartLast = lateStart
        provenLast = done == bytes.size && !late && !lateStart
        tornLast = threw || late || done in 1 until bytes.size || (lateStart && writing.unasked && done > 0)
        return if (writing.unasked) provenLast else done == bytes.size
    }

    /** What a write to the sign is: the word of the timing log, and whether it answers a request. */
    private enum class Writing(val kind: String, val unasked: Boolean) {
        REPORT("report", false),
        ANSWER("message", false),
        PUSHED_FRAME("message", true),
        ATTENTION("attention", true),
    }

    /**
     * How to get the sign's attention when a push is to start NOW: one is wanted, and the line's
     * state lets it start ([LineClock.pushAtNs]). Null when none is to start.
     *
     * With the switch off a wanted push is dropped here, in the turn that asked for it, before
     * anything else is looked at: the change is not pushed now and not later, and the loop goes
     * on exactly as it does without pushes (the same pauses, the same reads).
     */
    private fun duePush(): Attention? {
        if (!pushWaiting()) return null
        val mode = switchInForce()
        if (mode == Attention.OFF) {
            dropped()
            return null
        }
        return if (nanoTime() - clock.pushAtNs >= 0) mode else null
    }

    /**
     * One push: the attention ([mode] says how), the guard, the chooser frame. This thread
     * sleeps through the attention and the guard: whatever the sign sends in that time is void
     * (rule 3), so there is nothing to read for.
     *
     * Nothing here calls the change done or sets it back: it stays pending through whatever
     * becomes of the push, and only the frame, judged behind its write, can end it ([frameWritten]).
     */
    private fun push(mode: Attention) {
        // The frame is made from here on: a change recorded after this is not in it, and is the next push
        val carries = wanted.get()
        pushedFrameInVoid = false
        val wantedNs = nanoTime()
        val lineWasNs = clock.freeAtNs
        val text = try {
            chooserFrame()
        } catch (e: Exception) {
            // Not thrown on: the loop's pause after a failure would keep the link from the wire
            complain("push", e)
            return pushFailed(null)
        }
        val frame = text?.toByteArray()
        // Not after stop (making the frame took time): a port that is closing is sent no attention
        if (stopped) return
        val callNs = nanoTime()
        val raised = raiseAttention(mode)
        // Rule 3, and rule 4 for an attention that was cut short: what the sign sends into it is void
        voidBeforeNs = clock.freeAtNs
        if (!raised) return pushFailed(if (clock.freeAtNs != lineWasNs) Behind.ATTENTION else null)
        val raisedNs = nanoTime()
        clock.quiet(ATTN_GUARD_MS)
        sleepUntil(clock.freeAtNs)
        if (stopped) return
        val guardEndNs = nanoTime()
        if (frame == null) {
            // Leaving: the attention alone, whole. It proves nothing (the sign may not have
            // seen it), so the change stays pending; but it is not raised a second time for
            // the same change. Rule 3 lasts until the guard is over.
            voidBeforeNs = clock.freeAtNs
            aloneGivenFor = carries
            clock.hold(Behind.ATTENTION)
            lastHeardNs = guardEndNs
            timing.push(wantedNs, callNs, raisedNs, guardEndNs, guardEndNs, 0)
            log("Pushed attention alone to $name")
            return
        }
        // The sign listens for the first byte only so long after the attention, and then asks
        val whole = writeAll(frame, Writing.PUSHED_FRAME, callNs + PUSH_FIRST_BYTE_MS * NS_PER_MS)
        // Rule 3 lasts until the frame has left the line
        voidBeforeNs = clock.freeAtNs
        frameWritten(carries)
        if (!whole) {
            val how = if (lateStartLast) "begun within $PUSH_FIRST_BYTE_MS ms of the attention" else "taken in one piece"
            complain("push", "the frame was not $how ($takenLast of ${frame.size} bytes)")
            // With its start out the sign waits for the rest, and with all of it out but late it may have asked
            // into it; with nothing of it out, this was the attention alone
            return pushFailed(if (tornLast) Behind.TORN_FRAME else Behind.ATTENTION)
        }
        pushedFrameInVoid = true
        // The sign may be in its chooser display with this message now, as with one it asked for
        chooserDisplay = true
        clock.hold(Behind.ATTENTION)
        val doneNs = nanoTime()
        // The short pause from here on: the sign's next request must be told from what came into the frame
        lastHeardNs = doneNs
        timing.push(wantedNs, callNs, raisedNs, guardEndNs, doneNs, frame.size)
        // On the line first, then the log and the screen
        log("Pushed >$text< to $name")
        try {
            pushed()
        } catch (e: Exception) {
            // The frame is out: what the app makes of it must not keep the link from the wire
            complain("push", e)
        }
    }

    /**
     * Rule 4: the push did not go out, and the change is pending as it was. The next try waits
     * for what the sign needs behind what this one put on the line ([behind]); when it put
     * nothing there (null), [ERROR_PAUSE_MS].
     */
    private fun pushFailed(behind: Behind?) {
        if (behind != null) clock.hold(behind) else clock.holdFrom(nanoTime(), ERROR_PAUSE_MS)
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
        // In pieces if the transmitter takes it so, but ONE run on the line: a piece is only
        // offered while the pieces before it are still leaving
        if (!writeAll(zeroRun, Writing.ATTENTION, null)) {
            complain("push", "the attention was not taken in one run ($takenLast of ${zeroRun.size} bytes)")
            return false
        }
        return true
    }

    /** One pause of the loop: short while the sign is talking or a push is waiting its turn, and no longer than that turn is away. */
    private fun pause() {
        val now = nanoTime()
        val pushing = pushWaiting()
        val talking = pushing || now - lastHeardNs < ACTIVE_WINDOW_MS * NS_PER_MS
        var nanos = (if (talking) ACTIVE_PAUSE_MS else IDLE_PAUSE_MS) * NS_PER_MS
        if (pushing) {
            val untilPush = clock.pushAtNs - now
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
        }
    }

    /**
     * A failure: each kind ([what]) once when it starts, then at most every [WARN_EVERY_MS]. A
     * dead port must not flood the log, and it fails in more than one way at a time (a write
     * that throws is also a push that failed): each kind has its own limit.
     */
    private fun complain(what: String, error: Any) {
        val now = nanoTime()
        val last = lastWarningNs[what]
        if (last == null || now - last >= WARN_EVERY_MS * NS_PER_MS) {
            lastWarningNs[what] = now
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
         * From the START of the attention (before the call that begins it: the earliest the line
         * can have gone LOW) to the first byte of the pushed frame being with the transmitter.
         * The sign listens 150 ms for that byte and then asks with `~` (the firmware's lists,
         * lm-t7f ROUND-3-PR6-fixes-lists, rows 4 and 7: "150 ms from the line going HIGH",
         * "150 ms from the LOW"; the stricter of the two is counted here, from the LOW's start).
         * A frame begun later can run into that request. 30 ms are kept in hand for what the
         * link cannot see: where in its call the write handed the byte over, and the sign's own
         * clock. Measured on the sign: an attention and its guard take 70 to 90 ms.
         */
        const val PUSH_FIRST_BYTE_MS: Int = 120

        /**
         * From the end of what one push put on the line to the start of the next. The firmware
         * needs 110 ms of quiet line to get over a frame it could not read, and a sign that
         * still polls does so at least every 250 ms: either gets its turn.
         */
        const val PUSH_SPACING_MS: Int = 250

        /**
         * How long the sign can be busy with a frame of which it got the start and not the end:
         * the second the push firmware gives the next byte of an answer, and the 10 ms more it
         * adds itself when it lets the rest of such a frame pass (its 1010). The polling
         * firmware's 60 ms a byte are well inside it. No push starts in that time.
         */
        const val TORN_FRAME_MS: Int = 1_010

        /** The firmware that listens for pushes asks only after the line has been quiet for this long (its REQUEST_QUIET_MS). */
        const val SIGN_QUIET_BEFORE_REQUEST_MS: Int = 10

        // ---- Reading
        /** Pause between two reads while the sign is talking. A request waits at most this long to be seen. */
        const val ACTIVE_PAUSE_MS: Int = 2

        /** Pause between two reads otherwise. A `~` out of the blue waits a second for its answer. */
        const val IDLE_PAUSE_MS: Int = 10

        /** How long after the last byte from the sign (or a push) the short pause is kept. */
        const val ACTIVE_WINDOW_MS: Int = 2_000

        /**
         * In its chooser display the polling sign listens 60 ms; an answer takes up to 10. A
         * request that may have waited longer is not answered. (The firmware that listens for
         * pushes waits a second there too; the link cannot tell which one it talks to, and
         * keeps the limit that is safe for both.)
         */
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
