package cx.aphex.energysign.adb

import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.OwnerQueue
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * What a COMMAND broadcast asks for: the bytes to hand to the app as one Bluetooth write, or the
 * reason why there are none; and what to answer once the app has had them. No Android class is
 * used in here, so it runs in a JVM test.
 */
sealed class AdbCommand {
    class Bytes(val bytes: ByteArray) : AdbCommand()
    data class Refused(val why: String) : AdbCommand()

    companion object {
        const val ACTION = "cx.aphex.energysign.COMMAND"

        /** A string, sent as its UTF-8 bytes. */
        const val EXTRA_TEXT = "text"

        /**
         * Base64 of the bytes. It wins over [EXTRA_TEXT] when both are there, and it is the way
         * to send line breaks and emoji through two shells unharmed.
         */
        const val EXTRA_B64 = "b64"

        /** The end of every answer for a command the app threw on. */
        const val MAYBE_IN_PART = "; the command may have been carried out in part"

        /**
         * [extras] is every extra of the broadcast, by name, as it came. An extra counts when it
         * is THERE, whatever its value: one that is there and is not a string (`--ei b64 1`, a
         * null from `--esn`, an array) is refused, it is not taken for absent, or the other
         * extra would be carried out in its place. An extra with a name the hook does not know
         * is refused too: a misspelt `b64` beside a `text` must not let the text through.
         */
        fun fromExtras(extras: Map<String, Any?>): AdbCommand {
            extras.keys.firstOrNull { it != EXTRA_TEXT && it != EXTRA_B64 }?.let { unknown ->
                return Refused("unknown extra '$unknown': the hook knows $EXTRA_TEXT and $EXTRA_B64 only")
            }
            for (name in listOf(EXTRA_B64, EXTRA_TEXT)) {
                if (name in extras && extras[name] !is String) {
                    return Refused("the extra $name is there but is not a string: give it with --es $name '...'")
                }
            }
            return fromExtras(text = extras[EXTRA_TEXT] as String?, b64 = extras[EXTRA_B64] as String?)
        }

        /**
         * The bytes are never changed: nothing is trimmed and no line break is added or taken
         * away, because a Bluetooth write is not either. Only white space INSIDE the base64 text
         * is ignored (some encoders break their output into lines).
         */
        fun fromExtras(text: String?, b64: String?): AdbCommand {
            val bytes = when {
                b64 != null -> try {
                    Base64.getDecoder().decode(b64.filterNot { it.isWhitespace() })
                } catch (e: IllegalArgumentException) {
                    return Refused("bad base64 in $EXTRA_B64 (${e.message})")
                }

                text != null -> text.toByteArray(Charsets.UTF_8)
                else -> return Refused("no extra: give --es $EXTRA_TEXT '...' or --es $EXTRA_B64 <base64>")
            }
            if (bytes.isEmpty()) return Refused("empty: there is nothing to send")
            return Bytes(bytes)
        }

        /**
         * The answer for bytes that were handed on. A line break at the end is pointed out,
         * because the app does not know "!next" followed by a line break as a command, and
         * `echo ... | base64` adds one without saying so.
         */
        fun accepted(bytes: ByteArray): String =
            "ok ${bytes.size} bytes" + if (bytes.last() == '\n'.code.toByte()) " (the last one is a line break)" else ""

        /**
         * Hands [bytes] to [carryOut] and says what came of it. [carryOut] is
         * MessageManager.processNewBytesAndTell, which waits by OwnerQueue.askFor: it returns
         * when the thread that owns the messages has carried the command out, however long that
         * takes once that thread has STARTED it, and says which of three things happened: done,
         * the owner threw, or the owner did not get to the command in time (no sign link is
         * running) and it was withdrawn, never to be carried out.
         *
         * AN ANSWER IS ONE OF THREE THINGS, AND NEVER THE WRONG ONE:
         * - "ok": the owner has run the command and it did not throw. Not "queued". It does not
         *   mean the app knew the command either: one it does not know ("!nxet", or "!next" with
         *   a line break behind it) is run as nothing and is "ok" too, as it is over Bluetooth.
         * - "refused": it is CERTAIN that nothing happened, and sending it again is safe. From
         *   here there is ONE way to it: the owner's queue says [OwnerQueue.Asked.Withdrawn],
         *   which only the queue makes, when it has won the change back from the owner. No
         *   exception leads to "refused", whatever its type: a command may throw any exception
         *   the queue could, a TimeoutException included. (The other refusals are made before
         *   anything is handed to the app: see [fromExtras] and the receiver.)
         * - anything else, the command MAY have happened: "failed: the app threw" (the owner
         *   threw on the command; where in it is not known here, so part of it may have been
         *   done: a message deleted, and then the save failed), "failed: the call threw" (thrown
         *   around the hand-over; the command may not have run at all, or may still be run,
         *   whole) and "underway" (no outcome in time, see [aside]). Look before sending it again.
         *
         * Nothing is thrown on from here, an Error included. Whatever the command threw has been
         * thrown and caught where it happened; the caller is the hook's own thread, and to let it
         * die of the report would end the app that the hook is there to drive unattended.
         */
        fun carriedOut(bytes: ByteArray, carryOut: (ByteArray) -> OwnerQueue.Asked<Unit>): Outcome = try {
            when (val asked = carryOut(bytes)) {
                is OwnerQueue.Asked.Done -> Outcome(Kind.OK, accepted(bytes), thrown = null)
                is OwnerQueue.Asked.Threw -> failed(asked.thrown)
                OwnerQueue.Asked.Withdrawn -> Outcome(Kind.REFUSED, "refused: $NOT_TAKEN_UP (is the sign link running?)", thrown = null)
            }
        } catch (e: Throwable) {
            // Thrown by the call itself, before or around the hand-over (the command could not be
            // read, this thread may not wait, the wait was interrupted). After some of these the
            // command may still be carried out, whole, so none of them is "refused".
            Outcome(Kind.FAILED, "failed: the call threw ${e.javaClass.simpleName} (${e.message})$NOT_KNOWN", e)
        }

        private fun failed(thrown: Throwable): Outcome =
            Outcome(Kind.FAILED, "failed: the app threw ${thrown.javaClass.simpleName} (${thrown.message})$MAYBE_IN_PART", thrown)

        /** The answer when no outcome has come in time ([aside]). */
        fun underway(): Outcome = Outcome(
            Kind.UNDERWAY,
            "underway: no outcome in time; the command may have been carried out, or may still be. How it ends goes to the log",
            thrown = null,
        )

        /** The end of the answer for something the call threw, and not the owner on the command. */
        const val NOT_KNOWN = "; it is not known whether the command was, or will be, carried out"

        /** What the queue's withdrawal is called in the answer. */
        const val NOT_TAKEN_UP = "the sign link did not get to the change within ${OwnerQueue.ASK_TIMEOUT_MS} ms"

        /**
         * How long after the broadcast reached the receiver the hook answers at the latest.
         *
         * Android gives a broadcast receiver a time limit that goAsync() does not lift: when
         * the broadcast is not finished by then, the system reports "Timeout of broadcast" (an
         * ANR), goes on without the answer and may kill the app. In Android 8.1, which the
         * Pico's Android Things is built on, the limit is 10 s for a foreground broadcast and
         * 60 s for a background one (ActivityManagerService.BROADCAST_FG_TIMEOUT = 10*1000,
         * BROADCAST_BG_TIMEOUT = 60*1000, counted by BroadcastQueue from the moment the system
         * hands the broadcast to the app). `am broadcast` sends a background one unless it is
         * given `--receiver-foreground`. The bound here is half of the SHORTER limit, whichever
         * way the broadcast was sent: the other half is for the main thread to get to
         * onReceive at all (it lagged up to 1.6 s on the Pico while the app started) and for
         * the answer to travel back.
         */
        const val ANSWER_WITHIN_MS: Long = 5_000

        /**
         * [carriedOut] on a thread of its own, with ONE answer handed to [done]. For the
         * receiver: it is called on the main thread, which must never wait for the owner of the
         * messages (OwnerQueue.ask refuses it), and it has to finish its broadcast in time
         * ([ANSWER_WITHIN_MS]).
         *
         * The answer is the outcome when it comes within [answerWithinMs]. When it does not
         * (the owner is still at the command, or has not been reached), the answer is
         * [underway], given by a second thread at that moment, and the outcome goes to [late]
         * when it comes, for the log: the command is not cut short, and nobody is told
         * "refused" for something that is happening.
         *
         * When one of the two threads cannot be started ([start]; only tests set it), the
         * command is not carried out. Then this throws, and [done] has not been and will not be
         * called; or, when the deadline's thread had already answered "underway" by then (no
         * time was left), it does not throw: [done] is called once, whatever happens.
         */
        fun aside(
            bytes: ByteArray,
            carryOut: (ByteArray) -> OwnerQueue.Asked<Unit>,
            answerWithinMs: Long = ANSWER_WITHIN_MS,
            late: (Outcome) -> Unit = {},
            start: (Thread) -> Unit = Thread::start,
            done: (Outcome) -> Unit,
        ): Thread {
            val answered = AtomicBoolean(false) // set once, by whoever answers the broadcast
            val ended = CountDownLatch(1)
            val command = thread(start = false, name = "adb-command", isDaemon = true) {
                val outcome = carriedOut(bytes, carryOut)
                // Who answers is settled first, and only then is the deadline's thread let go:
                // once this thread has claimed the answer, the deadline cannot give one.
                val inTime = answered.compareAndSet(false, true)
                ended.countDown()
                if (inTime) done(outcome) else late(outcome)
            }
            val deadline = thread(start = false, name = "adb-command-deadline", isDaemon = true) {
                try {
                    ended.await(answerWithinMs, TimeUnit.MILLISECONDS) // zero or less: no wait at all
                } catch (e: InterruptedException) {
                    // then the time is up now
                }
                if (answered.compareAndSet(false, true)) done(underway())
            }
            // The deadline first: when it cannot be started, nothing has been. When the command's
            // thread cannot be started, the deadline's is told that there is nothing to answer
            // for. Either way the caller hears of it, and the command has not been carried out.
            start(deadline)
            try {
                start(command)
            } catch (e: Throwable) {
                // Nobody answers for a command that never ran: unless the deadline has answered
                // already, and then the caller must not answer a second time.
                val unanswered = answered.compareAndSet(false, true)
                ended.countDown()
                if (unanswered) throw e
            }
            return command
        }

        /**
         * The hook's one way into the app: [bytes] as one write of the phone, carried out by the
         * owner of [app]'s messages before [done] is told. processNewBytesAndTell hands the owner
         * the same change as processNewBytes, the entry of a Bluetooth write.
         */
        fun aside(
            bytes: ByteArray,
            app: MessageManager,
            answerWithinMs: Long = ANSWER_WITHIN_MS,
            late: (Outcome) -> Unit = {},
            done: (Outcome) -> Unit,
        ): Thread = aside(
            bytes,
            carryOut = app::processNewBytesAndTell,
            answerWithinMs = answerWithinMs, late = late, done = done,
        )
    }

    /**
     * Which of the answers an outcome is, with the result code `am broadcast` prints for it.
     *
     * None of them is 0. `am broadcast` sends its broadcast with the code 0 and no data, and
     * prints just that back when the receiver never answered: the app died in the middle of the
     * command, or the system's own time limit for the broadcast ran out. So "result=0" with no
     * data is NO ANSWER, and after it the command may have happened. If "refused" were 0 too,
     * the code alone would call that "certain that nothing happened".
     */
    enum class Kind(val resultCode: Int) {
        OK(-1),
        REFUSED(1),
        FAILED(2),
        UNDERWAY(3),
    }

    /** What to answer, and what the app threw when it threw. */
    class Outcome(val kind: Kind, val answer: String, val thrown: Throwable?) {
        val accepted: Boolean get() = kind == Kind.OK
    }
}
