package cx.aphex.energysign.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Does the waiting: runs the warm-up and the calls on background threads and hands every result
 * back exactly once. [AIQueryDesk] decides WHEN something is asked; this only carries it out.
 *
 * - One call at a time, whatever the caller does: a second [ask] while a call is out, or while
 *   the warm-up runs, is answered at once with a failure and nothing is sent for it.
 * - Every call has an end. Each part of a call has its own time limit (the set times, the time
 *   check, the request: see [AIReplyService] and the generators), and [deadline] stands behind
 *   their sum: when it passes, whatever can be cancelled is cancelled and the result is a
 *   timeout. The slot is given back only when the call has REALLY ended, also then: a part that
 *   cannot be cancelled is waited for.
 * - Nothing here throws to its caller, and nothing is lost: [ask] always ends in one call of
 *   `onResult`, also when the reply code, or the log, fails.
 */
class AIReplyRunner(
    /** One whole reply: the prompt of this moment, the provider, the clean-up. See [AIReplyService.replyTo]. */
    private val reply: suspend (String) -> AIReplyResult,
    /** The warm-up, or null for none. Blocks until it is done. Runs on its own thread. */
    private val warmUp: (() -> Unit)? = null,
    private val log: (String) -> Unit = {},
    private val deadline: Duration = CALL_DEADLINE,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Starts the thread the warm-up runs on. The app gives it a low priority. */
    private val startWarmUpThread: (Runnable) -> Unit = { Thread(it, "ai-warm-up").apply { isDaemon = true }.start() },
) : AIReplySource {

    /** True while the warm-up or a call is out. */
    private val busy = AtomicBoolean(false)

    override fun warmUp(onDone: () -> Unit): Boolean {
        val warm = warmUp ?: return false
        if (!busy.compareAndSet(false, true)) return false
        startWarmUpThread(
            Runnable {
                val startedAt = System.nanoTime()
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
        )
        return true
    }

    override fun ask(text: String, onResult: (AIReplyResult) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            quietly { onResult(AIReplyResult.NoReply(NoReplyReason.UNEXPECTED_FAILURE, "another call is out")) }
            return
        }
        scope.launch {
            val result = try {
                withTimeoutOrNull(deadline.toMillis()) { reply(text) }
                    ?: AIReplyResult.NoReply(NoReplyReason.TIMEOUT, "no reply within ${deadline.seconds} s")
            } catch (e: Throwable) {
                // Whatever it is, the caller hears of it: a query whose result never comes would
                // hold the one slot for ever.
                AIReplyResult.NoReply(NoReplyReason.UNEXPECTED_FAILURE, e.javaClass.name)
            }
            busy.set(false)
            quietly { onResult(result) }
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
         * The end of every call: behind the limits of its parts, which add up to 100 s (5 s for
         * the set times, 5 s for the time check, 90 s for the request). Much longer than the
         * 30 s the sign says "thinking": the first call after the app starts took about a
         * minute on the sign's board, and a reply that comes late is still used.
         */
        val CALL_DEADLINE: Duration = Duration.ofSeconds(110)
    }
}
