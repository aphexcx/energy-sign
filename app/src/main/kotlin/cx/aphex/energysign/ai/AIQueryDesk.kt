package cx.aphex.energysign.ai

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Decides when a query is asked and how long the sign says "thinking" for it.
 *
 * It has no thread and no lock of its own. Every function here is called by the ONE thread that
 * owns the messages (see OwnerQueue); what comes from other threads (a call that ended, the
 * warm-up that ended) is posted to that thread first. [Q] is the query message; two queries are
 * the same only when they are the same object. The clock ([nowMs] and the timestamps handed in)
 * must be one that also counts while the device sleeps; the desk reads [nowMs] itself when it
 * takes the next query from the waiting line, so a history scan cannot leave it comparing against
 * a stale timestamp.
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
 * - A failure that may pass leaves the query a query, [Limits.maxAttempts] attempts at most.
 *   Only [NoReplyReason.WITHDRAWN] and [NoReplyReason.TOO_LATE_TO_SEND] are not an attempt;
 *   pre-write network/TLS failures return their budget place while still counting as an attempt.
 * - After a failure of the provider ([NoReplyReason.backOff]) nothing is asked for a while,
 *   longer after each failure in a row ([Limits.coolDownsMs]). An answer ends the row.
 * - A BUDGET ([AIHourlyBudget]): [AIHourlyBudget.callsPerHour] paid HTTP requests leaving the app
 *   in any rolling hour (3,600,000 ms), shared with [WireBudgetInterceptor]. One-shot bodies keep
 *   one call to at most one wire request. The desk reserves a place at handover; the wire gate
 *   stamps it once the write outcome is known (kept for a full hour — nothing gives it back) and
 *   returns unstamped places when nothing left. Pauses happen inside this ceiling. While spent,
 *   queries show as plain messages with no notice. Kept in memory only: a restart empties it.
 *
 * What this costs the owner: [shown] and [thinkingWanted] look at the line only
 * ([Limits.maxWaiting] entries at most); [finished] and [warmedUp] make ONE pass over the list
 * of messages ([listedAmong]), whatever the number of queries they know of.
 */
class AIQueryDesk<Q : Any>(
    /**
     * Sends [Q] to the provider with its reserved hourly-budget place. Returns at once; the
     * result comes back through [finished].
     */
    private val start: (Q, AIHourlyBudget.Reservation) -> Unit,
    /**
     * Which of the given queries are still in the list: ONE pass over the list. What is given
     * and what comes back compare by identity. A query that was deleted is not sent.
     */
    private val listedAmong: (Set<Q>) -> Set<Q>,
    /** Monotonic clock in milliseconds, read fresh when dequeuing from the waiting line. */
    private val nowMs: () -> Long,
    private val log: (String) -> Unit = {},
    private val limits: Limits = Limits(),
    /** True while the warm-up runs: nothing is sent until [warmedUp]. */
    warmingUp: Boolean = false,
    /**
     * Shared rolling-hour wire-request budget, or null to create a local one on [nowMs].
     */
    budget: AIHourlyBudget? = null,
) {
    class Limits(
        /** How long the sign says "thinking" for one query. */
        val noticeMs: Long = 30_000,
        /** How many attempts one query may get when its calls fail in a way that may pass. */
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

    /** Sent calls made so far for the queries that are still queries. */
    private val attempts = IdentityHashMap<Q, Int>()
    private var out: Q? = null
    private var outReservation: AIHourlyBudget.Reservation? = null
    private var warming = warmingUp
    private val line = ArrayDeque<Waiting<Q>>()

    /** The query the notice is up for, and the moment it comes down. */
    private var noticeFor: Q? = null
    private var noticeUntil = 0L

    private var failuresInARow = 0
    private var noCallBefore = Long.MIN_VALUE

    /** Shared rolling-hour ceiling on paid HTTP requests leaving the app. */
    internal val hourlyBudget: AIHourlyBudget = budget ?: AIHourlyBudget(nowMs)
    private var saidBudgetIsSpent = false

    /**
     * The sign is showing [query] now. True: put up the thinking notice; the query was sent, or
     * stands in line. False: show nothing; either it is being answered already or nothing may be
     * asked at the moment.
     */
    fun shown(query: Q, now: Long): Boolean {
        // Whoever waited past their notice is not waiting any more: they hold no place in the
        // line, and they are no reason to turn the same query away when it comes round again.
        line.removeAll { now >= it.until }

        if (out === query || line.any { it.query === query }) return false
        if (now < noCallBefore) return false

        val reservation = if (free()) {
            hourlyBudget.reserve()
        } else {
            if (line.size >= limits.maxWaiting) return false
            null
        }
        val admitted = if (free()) reservation != null else hourlyBudget.hasRoom()
        if (!admitted) {
            sayBudgetIsSpentOnce()
            return false
        }
        saidBudgetIsSpent = false
        noticeFor = query
        noticeUntil = now + limits.noticeMs
        if (reservation != null) launch(query, reservation) else line.addLast(Waiting(query, noticeUntil))
        return true
    }

    /** Whether the thinking notice should still be on the sign. Asked whenever the sign takes it. */
    fun thinkingWanted(now: Long): Boolean {
        val query = noticeFor ?: return false
        if (now < noticeUntil) return true
        noticeFor = null
        if (out === query) {
            say("Still no reply after ${limits.noticeMs / 1000} s: the sign goes on, the call stays out and its reply is used when it comes")
        }
        return false
    }

    /** The call for [query] has ended with [result]. Says what to do with it, and sends whoever is next. */
    fun finished(query: Q, result: AIReplyResult, now: Long): Outcome {
        if (out !== query) return Outcome.NotOurs
        out = null
        outReservation?.release()
        outReservation = null
        val watched = noticeFor === query && now < noticeUntil
        if (noticeFor === query) noticeFor = null

        // Everything is decided and changed first; what there is to say is said at the end. A
        // log that fails cannot leave a result half dealt with.
        var toSay: String? = null
        if (result is AIReplyResult.NoReply && result.reason.backOff) {
            failuresInARow++
            noCallBefore = now + limits.coolDownsMs[(failuresInARow - 1).coerceAtMost(limits.coolDownsMs.lastIndex)]
            toSay = "Failure $failuresInARow in a row (${result.reason}): nothing is asked for the next ${(noCallBefore - now) / 1000} s"
        } else if (result is AIReplyResult.Reply || !(result as AIReplyResult.NoReply).reason.nothingWasSent) {
            // The provider answered: the next failure is the first in a row again. (A result
            // for which nothing was sent is no answer of the provider: the row stands.)
            failuresInARow = 0
        }

        val alive = stillListed(query)
        val outcome = when {
            query !in alive -> {
                attempts.remove(query)
                Outcome.Gone
            }

            result is AIReplyResult.Reply -> {
                attempts.remove(query)
                Outcome.Reply(result.text)
            }

            else -> {
                val reason = (result as AIReplyResult.NoReply).reason
                // Only our own withdrawal / lateness (nothingWasSent) is not an attempt; network
                // and TLS failures before the write return their budget place but still count as
                // an attempt so a query retires after maxAttempts during an outage.
                val used = if (reason.nothingWasSent) {
                    attempts[query] ?: 0
                } else {
                    ((attempts[query] ?: 0) + 1).also { attempts[query] = it }
                }
                if (reason.askAgain && used < limits.maxAttempts) {
                    Outcome.Failed(reason, whileWatched = watched)
                } else {
                    attempts.remove(query)
                    Outcome.Retired(reason)
                }
            }
        }
        sendNext(alive)
        toSay?.let(::say)
        return outcome
    }

    /** The warm-up is over: whoever stands in line is sent now. */
    @Suppress("UNUSED_PARAMETER")
    fun warmedUp(now: Long) {
        warming = false
        sendNext(stillListed(null))
    }

    private fun free(): Boolean = out == null && !warming

    private fun sayBudgetIsSpentOnce() {
        if (!saidBudgetIsSpent) {
            saidBudgetIsSpent = true
            say("${hourlyBudget.callsPerHour} calls went out in the last hour: nothing is asked until the oldest of them is an hour old")
        }
    }

    private fun launch(query: Q, reservation: AIHourlyBudget.Reservation) {
        out = query
        outReservation = reservation
        try {
            start(query, reservation)
        } catch (e: Throwable) {
            if (out === query && outReservation === reservation) {
                out = null
                outReservation = null
            }
            if (noticeFor === query) noticeFor = null
            reservation.release()
            throw e
        }
    }

    /**
     * Of [ended] (when there is one), the queries in line and the queries whose calls are being
     * counted: those that are still in the list. One pass over the list.
     */
    private fun stillListed(ended: Q?): Set<Q> {
        val known: MutableSet<Q> = Collections.newSetFromMap(IdentityHashMap())
        ended?.let(known::add)
        line.forEach { known.add(it.query) }
        known.addAll(attempts.keys)
        if (known.isEmpty()) return known
        // By identity here too, whatever kind of set comes back.
        val listed: MutableSet<Q> = Collections.newSetFromMap(IdentityHashMap())
        listed.addAll(listedAmong(known))
        return listed
    }

    /**
     * Sends the first in line that is still worth sending. Reads [nowMs] fresh at dequeue so a
     * query whose wait expired during the history scan is never promoted. Whoever is not worth
     * sending leaves the line unsent. Then forgets what is known about queries gone from the list.
     */
    private fun sendNext(alive: Set<Q>) {
        while (free()) {
            val next = line.removeFirstOrNull() ?: break
            val checkNow = nowMs()
            val reservation = if (checkNow >= noCallBefore && checkNow < next.until && next.query in alive) {
                hourlyBudget.reserve()
            } else {
                null
            }
            if (reservation != null) {
                saidBudgetIsSpent = false
                launch(next.query, reservation)
            } else if (noticeFor === next.query) {
                noticeFor = null
            }
        }
        attempts.keys.retainAll { it in alive }
    }

    /** A line for the log. The log failing changes nothing here. */
    private fun say(text: String) {
        try {
            log(text)
        } catch (e: Exception) {
            // Nowhere to report it.
        }
    }
}
