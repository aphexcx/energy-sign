package cx.aphex.energysign.adb

import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.OwnerQueue
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.koin.core.Koin

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
         * the owner threw, or the owner did not get to the command in time (its sign link is
         * held up, or is being stopped) and it was withdrawn, never to be carried out.
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
         *   whole) and "underway" (no outcome in time, see [receive]). Look before sending it again.
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

        /** The answer at the deadline for a command the running app has been handed ([receive]). */
        fun underway(): Outcome = Outcome(
            Kind.UNDERWAY,
            "underway: no outcome in time; the command may have been carried out, or may still be. How it ends goes to the log",
            thrown = null,
        )

        /** The line that ends an "underway" whose command has not ended [afterMs] after the broadcast came ([receive]). */
        fun gaveUp(afterMs: Long): Outcome = Outcome(
            Kind.UNDERWAY,
            "gave up after ${afterMs / 1000} s: the app has not finished the command; it may have been carried out, or may still be. Nothing more about it goes to the log",
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
         * way the broadcast was sent, and it is ONE deadline, taken by the receiver's first
         * statements ([receive]): every wait is for what is left of it.
         *
         * WHAT NO BOUND IN HERE CAN COVER: the time before onReceive is called at all. The
         * system's clock for the broadcast runs from the moment it hands the broadcast to the
         * app, and onReceive runs on the main thread when that thread gets to it. When the app
         * is not running yet (the Pico has just started), the main thread first makes the app:
         * on the board that took 47 s once. A background broadcast then still has its answer in
         * time (47 + 5 < 60); a foreground one (10 s) does not, so `--receiver-foreground` is
         * not for a board that is starting. When the system's limit runs out first, `am` prints
         * "result=0" with no data (see [Kind]); what the hook then did with the command, when
         * the main thread got to it, is its line in the log.
         */
        const val ANSWER_WITHIN_MS: Long = 5_000

        /**
         * How long after the broadcast reached the receiver the hook stops waiting for the end
         * of a command it has answered "underway", and says so in the log ([gaveUp]).
         *
         * A change of the owner's may neither wait for another thread nor write a file (the
         * rule of OwnerQueue), so one that is carried out at all ends within milliseconds. A
         * minute is far beyond anything a command does, and short enough that the line comes
         * while whoever sent the command still looks at the log.
         */
        const val GIVE_UP_AFTER_MS: Long = 60_000

        /**
         * ONE BROADCAST, from the first thing the receiver does with it to its end. The rule:
         *
         * THE HOOK NEVER PROMISES WHAT NOTHING ALIVE HOLDS, AND IT KEEPS WHAT IT PROMISES.
         * Every broadcast gets exactly one answer by the deadline. "underway" (how it ends goes
         * to the log) is answered only for a command that the RUNNING app has been handed: the
         * process that will end it and log its end is then the one the HOME activity keeps
         * alive. A command that has not been handed over by the deadline is refused, and it is
         * NOT handed over later. And every command that was answered "underway" gets exactly
         * one line in the log: how it ended, or, when it has not ended [giveUpAfterMs] after
         * the broadcast came, that the hook gave up waiting for it.
         *
         * The receiver calls this on the main thread, as the first thing after goAsync(), and
         * does nothing else. In the order things happen here:
         *
         * 0. THE CLOCK is read, once: [now] is when the broadcast came. The deadline is that
         *    plus [answerWithinMs], the giving up that plus [giveUpAfterMs]; every wait is for
         *    what is left to one of the two.
         * 1. THE TIMER IS ARMED, before anything that can take time: a thread that answers at
         *    the deadline when nobody has. However late that thread gets to run, it does not
         *    wait past the deadline. If it cannot be started, the answer is given at once
         *    ("refused"): nothing that can take long is begun without a limit.
         * 2. [command] reads the extras. Refused: that is the answer.
         * 3. [app] gives the app's way in, or null when the app is not up ([entryWhenUp], which
         *    makes no message manager to find that out). Null: "refused", [NOT_UP].
         * 4. The command's thread is started (the main thread must not wait for the owner),
         *    and THE HAND-OVER is the first thing that thread does: the one decision between
         *    the command and the timer ([Stage]). Whoever is first settles it, and the other
         *    one finds it settled.
         *    The command first: it is the app's from here, and its thread waits for the owner
         *    ([carriedOut]). At the deadline the timer answers "underway" and then waits on,
         *    for the command's end or the giving up.
         *    The timer first: it has answered "refused" ([notHanded]), and the command's
         *    thread hands nothing over, however late step 2 or 3 let it be started.
         *
         * Whatever ends the command on this thread or on the command's (its outcome, a refusal
         * at step 2 or 3, a thread that cannot be started, anything thrown by step 2 or 3) goes
         * through ONE place, passed once: it is the answer, to [done], when the timer has not
         * answered; after an "underway" it is the one line for the log, to [late], and never
         * in the log before that answer is given; and after the timer's refusal or its giving
         * up it is not said at all, because the last word on this command has been said.
         * Anything thrown before the command's thread ran is
         * "refused": the app has not been handed the bytes. [done] and [late] get the outcome
         * and its line for the log ([logLine]); what they throw is dropped, so that no thread
         * of the hook dies of it (that would end the app).
         *
         * The command's thread stays with a command that never ends; there is one such thread
         * at most for each change the owner is stuck in, since the queue withdraws every later
         * one that the owner does not take up.
         *
         * [start] starts a thread and [now] is the clock, in nanoseconds; only tests set them.
         */
        fun receive(
            command: () -> AdbCommand,
            app: () -> ((ByteArray) -> OwnerQueue.Asked<Unit>)?,
            answerWithinMs: Long = ANSWER_WITHIN_MS,
            giveUpAfterMs: Long = GIVE_UP_AFTER_MS,
            start: (Thread) -> Unit = Thread::start,
            now: () -> Long = System::nanoTime,
            late: (Outcome, String) -> Unit,
            done: (Outcome, String) -> Unit,
        ) {
            val came = now() // 0
            val deadline = came + TimeUnit.MILLISECONDS.toNanos(answerWithinMs)
            val givingUp = came + TimeUnit.MILLISECONDS.toNanos(giveUpAfterMs)
            val stage = AtomicReference(Stage.NOT_HANDED)
            val ended = CountDownLatch(1)
            val bytesKnown = AtomicReference<ByteArray?>(null)

            fun tell(to: (Outcome, String) -> Unit, what: String, outcome: Outcome) {
                try {
                    to(outcome, logLine(what, outcome, bytesKnown.get()))
                } catch (e: Throwable) {
                    // The receiver's own failure (the log, the broadcast's result). Nowhere to report it.
                }
            }

            /**
             * The ONE end of this broadcast's command. Called once: by the command's thread, or
             * by this thread on a path that returns right after and has not started that thread.
             */
            fun end(outcome: Outcome) {
                // Who speaks is settled first, and only then is the timer's thread let go: once
                // the word has been claimed here, the timer cannot say anything.
                val was = stage.getAndSet(Stage.CLOSED)
                ended.countDown()
                when (was) {
                    Stage.NOT_HANDED, Stage.HANDED -> tell(done, "adb write", outcome)
                    // Behind the timer's "underway", which may be on its way out this moment.
                    Stage.UNDERWAY -> synchronized(stage) { tell(late, AFTER_ITS_ANSWER, outcome) }
                    Stage.CLOSED -> Unit
                }
            }

            fun refused(why: String, thrown: Throwable? = null) = end(Outcome(Kind.REFUSED, "refused: $why", thrown))

            /** Returns when the command has ended, or at [at] on the clock: nothing left is no wait at all. */
            fun waitForTheEndUntil(at: Long) {
                try {
                    ended.await(at - now(), TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    // then the time is up now
                }
            }

            // 1
            val timer = thread(start = false, name = "adb-command-deadline", isDaemon = true) {
                waitForTheEndUntil(deadline)
                // Nothing was handed over, and from this line on nothing can be (step 4).
                if (stage.compareAndSet(Stage.NOT_HANDED, Stage.CLOSED)) {
                    tell(done, "adb write", Outcome(Kind.REFUSED, "refused: ${notHanded(answerWithinMs)}", thrown = null))
                    return@thread
                }
                // Said before anyone can write the line that it promises (see end).
                val promised = synchronized(stage) {
                    stage.compareAndSet(Stage.HANDED, Stage.UNDERWAY).also { if (it) tell(done, "adb write", underway()) }
                }
                if (promised) {
                    // It is the command's thread that writes the line, or this one, when that
                    // thread does not come back.
                    waitForTheEndUntil(givingUp)
                    if (stage.compareAndSet(Stage.UNDERWAY, Stage.CLOSED)) tell(late, AFTER_ITS_ANSWER, gaveUp(giveUpAfterMs))
                }
            }
            try {
                start(timer)
            } catch (e: Throwable) {
                return refused("the hook could not start its timer (${e.javaClass.simpleName})", e)
            }

            try {
                // 2
                val bytes = when (val asked = command()) {
                    is Refused -> return refused(asked.why)
                    is Bytes -> asked.bytes
                }
                bytesKnown.set(bytes)
                // 3
                val carryOut = app() ?: return refused(NOT_UP)
                // 4
                val runner = thread(start = false, name = "adb-command", isDaemon = true) {
                    // Not when the timer has refused it: then it stays refused.
                    if (stage.compareAndSet(Stage.NOT_HANDED, Stage.HANDED)) end(carriedOut(bytes, carryOut))
                }
                try {
                    start(runner)
                } catch (e: Throwable) {
                    return refused("the hook could not start the command's thread (${e.javaClass.simpleName})", e)
                }
                // Nothing may follow here: from this line on the command is the other thread's.
            } catch (e: Throwable) {
                // Step 2 or 3 threw (4 answers for itself): the app was not handed the bytes.
                refused("the hook failed before the command was handed to the app (${e.javaClass.simpleName}: ${e.message})", e)
            }
        }

        /** Where one broadcast is, for [receive]. Each step is taken by whoever gets to it first. */
        private enum class Stage {
            /** Not answered, and the app has not been handed the command. */
            NOT_HANDED,

            /** Not answered; the command is the running app's. */
            HANDED,

            /** Answered "underway": one line for the log is owed. */
            UNDERWAY,

            /** The last word on the command has been said: its answer, or the line that was owed. */
            CLOSED,
        }

        private const val AFTER_ITS_ANSWER = "adb write, after its answer"

        /**
         * The hook's way into the app (one write of the phone, carried out by the owner of its
         * messages), or null when the app is not up. [koin] is the app's, or null when it has
         * none yet.
         *
         * UP means that the thread which runs the owner's queue is alive
         * ([OwnerQueue.ownerRuns]): the sign link's, which the main screen starts AFTER it has
         * made the message manager, and which ends when the link is stopped. So when the app is
         * up the manager is there to be taken, and when it is not, THE MANAGER IS NOT ASKED
         * FOR. That matters because Koin MAKES the manager when it is first asked for it: in a
         * process that this broadcast started, the hook would read the saved messages from
         * storage to carry out a command that no sign link is there to take, in a process that
         * nothing keeps alive. (The queue itself is made by the question when nobody has asked
         * for it yet; it is an empty list, and the one the app then gets.)
         *
         * A link that is being stopped counts as up until its thread has ended. A command that
         * is handed over then is withdrawn by the queue [OwnerQueue.ASK_TIMEOUT_MS] later and
         * is answered "refused"; only one handed over in the last two of the five seconds would
         * be answered "underway" first, with the withdrawal as its line.
         */
        fun entryWhenUp(koin: Koin?): ((ByteArray) -> OwnerQueue.Asked<Unit>)? {
            if (koin == null || !koin.get<OwnerQueue>().ownerRuns) return null
            return koin.get<MessageManager>()::processNewBytesAndTell
        }

        /** The timer's refusal: it cannot know why the receiver's thread did not get to the hand-over. */
        fun notHanded(withinMs: Long): String =
            "the command was not handed to the app within $withinMs ms, and it will not be (the app is not up yet, or its main thread is held up); send it again"

        /** The refusal for an app that is not up: nothing was handed to it, and nothing will be. */
        const val NOT_UP = "the app is not up yet, send it again (no sign link is running)"

        /**
         * The line for the log: what it is, THE OUTCOME, and then a bounded piece of the command
         * in brackets, on one line. In this order because logcat cuts an entry at about 4000
         * bytes: an ad command can be longer than that, and after an "underway" the line in the
         * log is the only place that says how the command ended. So the outcome comes first,
         * and nothing behind it can push it out. The outcome is bounded too (an exception's
         * message can be of any length); no cut splits a character in two.
         */
        fun logLine(what: String, outcome: Outcome, bytes: ByteArray?): String {
            val said = "$what: ${piece(outcome.answer, MAX_OUTCOME_CHARS_IN_LOG)}"
            if (bytes == null) return said
            // Decoded from a bounded part of the bytes: the work does not grow with the command.
            // (Four bytes are the most one character takes: this many bytes hold at least the
            // characters that are shown, so whatever the cut leaves at their end is never shown.)
            val text = String(bytes, 0, minOf(bytes.size, MAX_COMMAND_CHARS_IN_LOG * 4), Charsets.UTF_8)
            return "$said [${piece(text, MAX_COMMAND_CHARS_IN_LOG)}]"
        }

        /** At most [max] characters of [text], on one line, with "..." when something was left out. */
        private fun piece(text: String, max: Int): String {
            var end = minOf(text.length, max)
            if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end--
            val shown = text.substring(0, end).map { if (it.isISOControl()) ' ' else it }.joinToString("")
            return if (end < text.length) "$shown..." else shown
        }

        private const val MAX_OUTCOME_CHARS_IN_LOG = 600
        private const val MAX_COMMAND_CHARS_IN_LOG = 80
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
