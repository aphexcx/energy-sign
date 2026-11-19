package cx.aphex.energysign.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Does the waiting: runs the warm-up and the calls on background threads and hands every result
 * back exactly once. [AIQueryDesk] decides WHEN something is asked; this only carries it out.
 *
 * - One call at a time, whatever the caller does: a second [ask] while a call is out, or while
 *   the warm-up runs, is answered at once with a failure and nothing is sent for it.
 * - ONE time limit for a query, [budget], counted from the moment it is asked. It is handed down
 *   as a [Deadline] to everything the reply does (see [AIReplyService] and the generators), and
 *   each step takes what is left of it. There is no second clock here: nothing stands behind the
 *   request that could end the wait while the provider is still writing an answer, so an answer
 *   that was paid for is never thrown away and bought again. The slot is given back when the
 *   call has REALLY ended.
 * - Nothing here throws to its caller, and nothing is lost: [ask] always ends in one call of
 *   `onResult`, also when the reply code, or the log, fails. The warm-up always ends in one call
 *   of `onDone`, whatever fails on its thread.
 */
class AIReplyRunner(
    /** One whole reply: the prompt of this moment, the provider, the clean-up. See [AIReplyService.replyTo]. */
    private val reply: suspend (text: String, deadline: Deadline, stillWanted: () -> Boolean, reservation: AIHourlyBudget.Reservation?) -> AIReplyResult,
    /** The warm-up, or null for none. Blocks until it is done. Runs on its own thread. */
    private val warmUp: (() -> Unit)? = null,
    private val log: (String) -> Unit = {},
    private val budget: Duration = QUERY_BUDGET,
    /**
     * The clock the deadline of a query is counted on, in nanoseconds. The app gives the one
     * that goes on while the board sleeps, the same that counts the notice and the pauses.
     */
    private val nanoTime: () -> Long = System::nanoTime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Makes the thread the warm-up runs on. */
    private val warmUpThread: (Runnable) -> Thread = { Thread(it, "ai-warm-up").apply { isDaemon = true } },
    /**
     * Run on that thread before the warm-up; the app lowers the thread's priority here. Best
     * effort: when it fails, the warm-up runs all the same.
     */
    private val onWarmUpThread: () -> Unit = {},
) : AIReplySource {

    /** True while the warm-up or a call is out. */
    private val busy = AtomicBoolean(false)

    override fun warmUp(onDone: () -> Unit): Boolean {
        val warm = warmUp ?: return false
        if (!busy.compareAndSet(false, true)) return false
        // The whole body of the thread is guarded: whatever fails in it, the slot is given back
        // and onDone is called.
        val work = Runnable {
            val startedAt = System.nanoTime()
            quietly(onWarmUpThread)
            val failure = quietly { warm() }
            busy.set(false)
            quietly {
                log(
                    "Warm-up " + (if (failure == null) "done" else "failed (${failure.javaClass.simpleName})") +
                        " after ${(System.nanoTime() - startedAt) / 1_000_000} ms"
                )
            }
            quietly(onDone)
        }
        return try {
            warmUpThread(work).start()
            true
        } catch (e: Throwable) {
            // No thread: there is no warm-up, and the first query pays for the slow first use.
            busy.set(false)
            quietly { log("The warm-up could not be started (${e.javaClass.simpleName})") }
            false
        }
    }

    override fun ask(
        text: String,
        stillWanted: () -> Boolean,
        reservation: AIHourlyBudget.Reservation?,
        onResult: (AIReplyResult) -> Unit,
    ) {
        if (!busy.compareAndSet(false, true)) {
            quietly { onResult(AIReplyResult.NoReply(NoReplyReason.UNEXPECTED_FAILURE, "another call is out")) }
            return
        }
        val deadline = Deadline.after(budget, nanoTime)
        try {
            scope.launch {
                val result = try {
                    reply(text, deadline, stillWanted, reservation)
                } catch (e: Throwable) {
                    // Whatever it is, the caller hears of it: a query whose result never comes would
                    // hold the one slot for ever.
                    AIReplyResult.NoReply(NoReplyReason.UNEXPECTED_FAILURE, e.javaClass.name)
                }
                busy.set(false)
                quietly { onResult(result) }
            }
        } catch (e: Throwable) {
            busy.set(false)
            quietly { onResult(AIReplyResult.NoReply(NoReplyReason.UNEXPECTED_FAILURE, e.javaClass.name)) }
        }
    }

    /** Runs [block]; a failure is returned and goes no further. There is nowhere left to report it. */
    private fun quietly(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }

    companion object {
        /**
         * All the time one query gets, from the moment it is asked: 5 s for the set times, 5 s
         * for the time check, 90 s for the request. When the steps before the request take
         * longer (the first use of the provider's client took about a minute on the sign's
         * board, which is what the warm-up is for), the request gets what is left. Much longer
         * than the 30 s the sign says "thinking": a reply that comes late is still used.
         */
        val QUERY_BUDGET: Duration = Duration.ofSeconds(100)
    }
}
