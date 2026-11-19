package cx.aphex.energysign.ai

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.ProtocolException
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ONE rolling-hour ceiling on paid HTTP requests leaving the app, shared between
 * [AIQueryDesk] (on the message owner's thread) and the HTTP transport's network interceptor
 * (on the call's I/O thread).
 *
 * Invariant under [lock]: at every moment `now`, after pruning sends at least [HOUR_MS] old,
 * `sentAt.size + activeReservations.size <= callsPerHour`.
 *
 * Lifecycle of one call:
 * 1. The desk calls [reserve] when it hands a call over. While the call is prepared (prompt,
 *    client build, owner approval, TCP/TLS), the reservation occupies a slot so the ceiling
 *    holds during preparation.
 * 2. Immediately before HTTP bytes leave on the wire (inside [WireBudgetInterceptor], an OkHttp
 *    network interceptor running below `RetryAndFollowUpInterceptor`), the first wire request
 *    calls [Reservation.stamp], replacing the active reservation with the wire-send timestamp.
 * 3. Any transport replay on the same call (e.g. OkHttp's `503 Retry-After: 0`, `421`, or
 *    Ktor/OkHttp's `408`/connection replay) must acquire an additional slot via
 *    [tryStampAdditional]; if none is available, the replay is refused before bytes leave.
 * 4. When the call finishes, the desk calls [Reservation.release]. If the reservation was never
 *    stamped (withdrawn, too late, missing key, or failed before HTTP bytes left), the slot is
 *    given back and the reservation is poisoned so no late thread can stamp it. If it was
 *    stamped, [Reservation.release] is a no-op and returns `true`; the send timestamp stays
 *    counted for a full [HOUR_MS].
 *
 * Kept in memory only: restarting the process empties the window.
 */
class AIHourlyBudget(
    val callsPerHour: Int = AIQueryDesk.Limits().callsPerHour,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()

    /** Wire-send timestamps (from [nowMs]) within the last [HOUR_MS]. */
    private val sentAt = ArrayDeque<Long>()

    /** Reservations held by calls in preparation that have not yet stamped or finished. */
    private val activeReservations: MutableSet<Reservation> =
        Collections.newSetFromMap(IdentityHashMap())

    private enum class State { RESERVED, STAMPED, RELEASED }

    /**
     * One reserved place held by a call between handover and physical wire send. Every state
     * transition runs under [AIHourlyBudget.lock].
     */
    inner class Reservation internal constructor() {
        private var state: State = State.RESERVED

        /** Whether at least one wire request was stamped against this reservation. */
        val wasSent: Boolean
            get() = synchronized(lock) { state == State.STAMPED }

        /**
         * Converts this active reservation into a permanent wire-send timestamp at [now] (or
         * [nowMs] evaluated inside [lock] when null). Returns `true` only if this reservation was
         * still [State.RESERVED]; returns `false` if already released (fail closed against late
         * threads) or already stamped.
         */
        fun stamp(now: Long? = null): Boolean = synchronized(lock) {
            if (state != State.RESERVED) return false
            if (!activeReservations.remove(this)) return false
            val at = now ?: nowMs()
            pruneLocked(at)
            sentAt.addLast(at)
            state = State.STAMPED
            true
        }

        /**
         * Ends this reservation when the call finishes. Returns `true` if a request was stamped
         * (leaving the timestamp in the hourly window) or `false` if no request left (returning
         * the reserved place to the budget and permanently closing this reservation).
         */
        fun release(): Boolean = synchronized(lock) {
            when (state) {
                State.RESERVED -> {
                    activeReservations.remove(this)
                    state = State.RELEASED
                    false
                }
                State.STAMPED -> true
                State.RELEASED -> false
            }
        }
    }

    /** Whether there is room for at least one more request at [now] (or [nowMs] inside [lock]). */
    fun hasRoom(now: Long? = null): Boolean = synchronized(lock) {
        val at = now ?: nowMs()
        pruneLocked(at)
        sentAt.size + activeReservations.size < callsPerHour
    }

    /**
     * Reserves one place at [now] (or [nowMs] inside [lock]) if below [callsPerHour], or returns
     * `null` when the ceiling is full.
     */
    fun reserve(now: Long? = null): Reservation? = synchronized(lock) {
        val at = now ?: nowMs()
        pruneLocked(at)
        if (sentAt.size + activeReservations.size >= callsPerHour) return null
        Reservation().also { activeReservations.add(it) }
    }

    /**
     * Atomically claims and stamps a slot at [now] (or [nowMs] inside [lock]) without a prior
     * reservation: used for HTTP client replays (2nd+ wire request of one call) and for direct
     * generator calls made without a desk reservation.
     */
    internal fun tryStampAdditional(now: Long? = null): Boolean = synchronized(lock) {
        val at = now ?: nowMs()
        pruneLocked(at)
        if (sentAt.size + activeReservations.size >= callsPerHour) return false
        sentAt.addLast(at)
        true
    }

    /** Current number of occupied places (stamped sends + active reservations) at [now]. */
    internal fun usedCount(now: Long? = null): Int = synchronized(lock) {
        val at = now ?: nowMs()
        pruneLocked(at)
        sentAt.size + activeReservations.size
    }

    private fun pruneLocked(now: Long) {
        // sentAt has at most callsPerHour entries (45); removing by predicate handles any edge
        // ordering cleanly while never prematurely expiring entries if a clock lags.
        val iterator = sentAt.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next() >= HOUR_MS) iterator.remove()
        }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}

/**
 * Per-call state carried onto an OkHttp [Request] tag so [WireBudgetInterceptor] knows whether
 * a wire request is a paid generation call (with its reservation and deadline) or the free
 * `GET /v1/models/...` clock/warm-up probe.
 */
sealed class WireCallContext {
    class Paid(
        val reservation: AIHourlyBudget.Reservation?,
        val deadline: Deadline,
        val minTimeToSend: Duration,
    ) : WireCallContext() {
        val wireSends = AtomicInteger(0)
        val lastHttpStatus = AtomicInteger(0)
    }

    class FreeModelGet(val deadline: Deadline) : WireCallContext()
}

/**
 * Thrown by [WireBudgetInterceptor] (or [BudgetedOkHttpClient]) when a request is refused before
 * leaving on the wire. Extends [ProtocolException] so OkHttp's `RetryAndFollowUpInterceptor`
 * always treats it as non-recoverable (even when `retryOnConnectionFailure` is enabled, as in
 * Ktor's default OkHttp engine).
 */
class WireSendBlockedException(
    val kind: Kind,
    val leftMs: Long,
    val priorHttpStatus: Int?,
    val afterWireSend: Boolean,
) : ProtocolException("wire send blocked ($kind, afterWireSend=$afterWireSend)") {
    enum class Kind { TOO_LATE, BUDGET_EXHAUSTED }

    companion object {
        fun tooLate(leftMs: Long, priorHttpStatus: Int?, afterWireSend: Boolean) =
            WireSendBlockedException(Kind.TOO_LATE, leftMs, priorHttpStatus, afterWireSend)

        fun budgetExhausted(priorHttpStatus: Int?, afterWireSend: Boolean) =
            WireSendBlockedException(Kind.BUDGET_EXHAUSTED, 0L, priorHttpStatus, afterWireSend)

        /** Finds a [WireSendBlockedException] anywhere in [failure]'s cause chain. */
        fun findIn(failure: Throwable): WireSendBlockedException? =
            generateSequence(failure) { it.cause }
                .take(12)
                .filterIsInstance<WireSendBlockedException>()
                .firstOrNull()
    }
}

/**
 * The ONE counting and gating place where paid requests leave the app, installed as an OkHttp
 * network interceptor (below `RetryAndFollowUpInterceptor`, immediately above
 * `CallServerInterceptor`). Runs for every physical HTTP request on the wire — initial call and
 * every transport replay alike.
 */
class WireBudgetInterceptor(
    private val budget: AIHourlyBudget,
    private val contextOf: (Request) -> WireCallContext? = { it.tag(WireCallContext::class.java) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val context = contextOf(request)

        if (context is WireCallContext.FreeModelGet) {
            if (request.method != "GET") {
                throw WireSendBlockedException.budgetExhausted(priorHttpStatus = null, afterWireSend = false)
            }
            val leftMs = context.deadline.remainingMs()
            if (leftMs <= 0L) {
                throw WireSendBlockedException.tooLate(leftMs = 0L, priorHttpStatus = null, afterWireSend = false)
            }
            return chain.proceed(request)
        }

        if (context !is WireCallContext.Paid) {
            // Fail closed: an untagged request never bypasses the budget.
            throw WireSendBlockedException.budgetExhausted(priorHttpStatus = null, afterWireSend = false)
        }

        val alreadySent = context.wireSends.get() > 0
        val priorStatus = context.lastHttpStatus.get().takeIf { it > 0 }
        val leftMs = context.deadline.remainingMs()
        if (leftMs < context.minTimeToSend.toMillis()) {
            throw WireSendBlockedException.tooLate(
                leftMs = leftMs,
                priorHttpStatus = priorStatus,
                afterWireSend = alreadySent,
            )
        }

        val permitted = if (!alreadySent) {
            context.reservation?.stamp() ?: budget.tryStampAdditional()
        } else {
            budget.tryStampAdditional()
        }
        if (!permitted) {
            throw WireSendBlockedException.budgetExhausted(
                priorHttpStatus = priorStatus,
                afterWireSend = alreadySent,
            )
        }

        // Counted BEFORE writing to the wire so a transport error mid-write still counts as sent.
        context.wireSends.incrementAndGet()
        val response = chain.proceed(request)
        context.lastHttpStatus.set(response.code)
        return response
    }
}
