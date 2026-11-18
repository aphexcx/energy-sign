package cx.aphex.energysign.ai

import java.util.IdentityHashMap

/**
 * Decides when a query is asked and how long the sign says "thinking" for it.
 *
 * It has no thread, no clock and no lock of its own. Every function here is called by the ONE
 * thread that owns the messages (see OwnerQueue); what comes from other threads (a call that
 * ended, the warm-up that ended) is posted to that thread first. [Q] is the query message; two
 * queries are the same only when they are the same object.
 *
 * The rules:
 * - ONE call at a time. While a call is out, or the warm-up runs, a query that is shown waits
 *   for its turn, with the thinking notice.
 * - The time limit is for the NOTICE, not for the call. When it passes, the sign goes on with
 *   its messages; the call that is out keeps running and its reply is delivered when it comes.
 *   A query that was still WAITING when its limit passed is taken out of the line: it was never
 *   sent and it cost nothing. It is asked again when it comes round.
 * - A call is never bought twice for an answer: a query that is out or waiting is not asked
 *   again, and a query that got an answer of any kind ([NoReplyReason.askAgain] false) is retired.
 * - A failure that may pass leaves the query a query, [Limits.maxAttempts] calls at most.
 * - After a failure of the provider ([NoReplyReason.backOff]) nothing is asked for a while,
 *   longer after each failure in a row ([Limits.coolDownsMs]). That bounds the calls an hour
 *   whatever is wrong and however many queries there are.
 */
class AIQueryDesk<Q : Any>(
    /** Sends [Q] to the provider. Returns at once; the result comes back through [finished]. */
    private val start: (Q) -> Unit,
    /** Whether [Q] is still in the list. A query that was deleted is not sent. */
    private val listed: (Q) -> Boolean,
    private val log: (String) -> Unit = {},
    private val limits: Limits = Limits(),
    /** True while the warm-up runs: nothing is sent until [warmedUp]. */
    warmingUp: Boolean = false,
) {
    class Limits(
        /** How long the sign says "thinking" for one query. */
        val noticeMs: Long = 30_000,
        /** How many calls one query may get when its calls fail in a way that may pass. */
        val maxAttempts: Int = 3,
        /** The pause after the first, second, ... failure in a row; the last one is the longest there is. */
        val coolDownsMs: List<Long> = listOf(30_000, 60_000, 120_000, 240_000, 300_000),
        /** How many queries may stand in line behind the call that is out. */
        val maxWaiting: Int = 4,
    )

    /** What became of a call. */
    sealed class Outcome {
        /** A reply to put after the query. */
        data class Reply(val text: String) : Outcome()

        /** The query is finished with nothing to show: it becomes the plain message it was. */
        data class Retired(val why: NoReplyReason) : Outcome()

        /**
         * The call failed in a way that may pass; the query stays a query. [whileWatched]: the
         * notice for this query was still up, so somebody is looking at the dots right now.
         */
        data class Failed(val why: NoReplyReason, val whileWatched: Boolean) : Outcome()

        /** The query is not in the list any more; whatever came back is dropped. */
        object Gone : Outcome()

        /** Not the call that is out (a result that arrived twice, or for something never sent). */
        object NotOurs : Outcome()
    }

    private class Waiting<Q>(val query: Q, val until: Long)

    /** Calls made so far for the queries that are still queries. */
    private val attempts = IdentityHashMap<Q, Int>()
    private var out: Q? = null
    private var warming = warmingUp
    private val line = ArrayDeque<Waiting<Q>>()

    /** The query the notice is up for, and the moment it comes down. */
    private var noticeFor: Q? = null
    private var noticeUntil = 0L

    private var failuresInARow = 0
    private var noCallBefore = Long.MIN_VALUE

    /**
     * The sign is showing [query] now. True: put up the thinking notice; the query was sent, or
     * stands in line. False: show nothing; either it is being answered already or nothing may be
     * asked at the moment.
     */
    fun shown(query: Q, now: Long): Boolean {
        if (out === query || line.any { it.query === query }) return false
        if (now < noCallBefore) return false
        if (!free() && line.size >= limits.maxWaiting) return false

        noticeFor = query
        noticeUntil = now + limits.noticeMs
        if (free()) send(query) else line.addLast(Waiting(query, noticeUntil))
        return true
    }

    /** Whether the thinking notice should still be on the sign. Asked whenever the sign takes it. */
    fun thinkingWanted(now: Long): Boolean {
        val query = noticeFor ?: return false
        if (now < noticeUntil) return true
        noticeFor = null
        if (out === query) {
            log("Still no reply after ${limits.noticeMs / 1000} s: the sign goes on, the call stays out and its reply is used when it comes")
        }
        return false
    }

    /** The call for [query] has ended with [result]. Says what to do with it, and sends whoever is next. */
    fun finished(query: Q, result: AIReplyResult, now: Long): Outcome {
        if (out !== query) return Outcome.NotOurs
        out = null
        val watched = noticeFor === query && now < noticeUntil
        if (noticeFor === query) noticeFor = null

        if (result is AIReplyResult.NoReply && result.reason.backOff) {
            failuresInARow++
            noCallBefore = now + limits.coolDownsMs[(failuresInARow - 1).coerceAtMost(limits.coolDownsMs.lastIndex)]
            log("Failure ${failuresInARow} in a row (${result.reason}): nothing is asked for the next ${(noCallBefore - now) / 1000} s")
        } else {
            // The provider answered: the next failure is the first in a row again.
            failuresInARow = 0
        }

        val outcome = when {
            !listed(query) -> {
                attempts.remove(query)
                Outcome.Gone
            }

            result is AIReplyResult.Reply -> {
                attempts.remove(query)
                Outcome.Reply(result.text)
            }

            result is AIReplyResult.NoReply && result.reason.askAgain && (attempts[query] ?: 0) < limits.maxAttempts ->
                Outcome.Failed(result.reason, whileWatched = watched)

            else -> {
                attempts.remove(query)
                Outcome.Retired((result as AIReplyResult.NoReply).reason)
            }
        }
        sendNext(now)
        return outcome
    }

    /** The warm-up is over: whoever stands in line is sent now. */
    fun warmedUp(now: Long) {
        warming = false
        sendNext(now)
    }

    private fun free(): Boolean = out == null && !warming

    private fun send(query: Q) {
        attempts[query] = (attempts[query] ?: 0) + 1
        out = query
        start(query)
    }

    /**
     * Sends the first in line that is still worth sending. Whoever is not (its notice has come
     * down, it was deleted, or nothing may be asked now) leaves the line unsent.
     */
    private fun sendNext(now: Long) {
        while (free()) {
            val next = line.removeFirstOrNull() ?: break
            if (now >= noCallBefore && now < next.until && listed(next.query)) {
                send(next.query)
            } else if (noticeFor === next.query) {
                noticeFor = null
            }
        }
        // Forget what is known about queries that are gone from the list.
        attempts.keys.retainAll { it === out || listed(it) }
    }
}
