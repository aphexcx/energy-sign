package cx.aphex.energysign.ai

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import java.io.IOException
import java.net.ProtocolException
import java.time.Duration

/**
 * The ONE rolling-hour ceiling on paid HTTP requests leaving the app, shared between
 * [AIQueryDesk] (owner thread) and [WireBudgetInterceptor] (call I/O thread).
 *
 * Invariant under [lock]: after head-pruning timestamps at least [HOUR_MS] old,
 * `sentAt.size + reserved <= callsPerHour`. The budget reads its own monotonic [nowMs] clock
 * under [lock] on every operation; no caller supplies a timestamp.
 *
 * Lifecycle of one place:
 * 1. [reserve] holds a place from handover (or generator entry) so the ceiling holds during prep.
 * 2. [WireBudgetInterceptor] stamps the place via [Reservation.stamp] only once the write outcome
 *    is known (after `chain.proceed` returns, or on a non-pre-write failure). Confirmed pre-write
 *    failures (`ConnectionShutdownException`, `StreamResetException(REFUSED_STREAM)`, or a call
 *    cancelled before writing) leave the place reserved so [Reservation.release] gives it back.
 * 3. [Reservation.release] returns an unstamped place and poisons it against late use; for a
 *    stamped place it is a no-op so the send stays counted for a full [HOUR_MS].
 *
 * Kept in memory only: restarting the process empties the window.
 */
class AIHourlyBudget(
    private val nowMs: () -> Long,
    val callsPerHour: Int = DEFAULT_CALLS_PER_HOUR,
) {
    private val lock = Any()
    private val sentAt = ArrayDeque<Long>()
    private var reserved = 0

    private enum class State { RESERVED, STAMPED, RELEASED }

    inner class Reservation internal constructor() {
        private var state = State.RESERVED

        internal fun isReserved(): Boolean = synchronized(lock) { state == State.RESERVED }

        /** Converts this active reservation into a permanent send timestamp read under [lock]. */
        fun stamp(): Boolean = synchronized(lock) {
            if (state != State.RESERVED) return false
            val now = nowMs()
            pruneLocked(now)
            reserved--
            sentAt.addLast(now)
            state = State.STAMPED
            true
        }

        /**
         * Ends this reservation when the call finishes. Returns `true` if a request was stamped
         * (kept for [HOUR_MS]) or `false` if nothing left (place returned and handle poisoned).
         */
        fun release(): Boolean = synchronized(lock) {
            when (state) {
                State.RESERVED -> {
                    reserved--
                    state = State.RELEASED
                    false
                }
                State.STAMPED -> true
                State.RELEASED -> false
            }
        }
    }

    fun hasRoom(): Boolean = synchronized(lock) {
        pruneLocked(nowMs())
        sentAt.size + reserved < callsPerHour
    }

    fun reserve(): Reservation? = synchronized(lock) {
        pruneLocked(nowMs())
        if (sentAt.size + reserved >= callsPerHour) return null
        reserved++
        Reservation()
    }

    internal fun usedCount(): Int = synchronized(lock) {
        pruneLocked(nowMs())
        sentAt.size + reserved
    }

    private fun pruneLocked(now: Long) {
        while (sentAt.isNotEmpty() && now - sentAt.first() >= HOUR_MS) sentAt.removeFirst()
    }

    companion object {
        /** Room for a reply every two minutes (30) plus the 15 calls cooldowns allow when failing. */
        const val DEFAULT_CALLS_PER_HOUR = 45
        const val HOUR_MS = 3_600_000L
    }
}

/**
 * Per-call context tagged onto an OkHttp request. A non-null [reservation] identifies a paid
 * reply call subject to [minTimeToSend]; `null` identifies a free `GET /v1/models/...` probe.
 */
class WireCallContext(
    val deadline: Deadline,
    val minTimeToSend: Duration,
    val reservation: AIHourlyBudget.Reservation?,
)

/**
 * Thrown when a request is refused before bytes leave. Extends [ProtocolException] so OkHttp's
 * `RetryAndFollowUpInterceptor.isRecoverable` always treats it as non-recoverable.
 */
class WireSendBlockedException(
    val reason: NoReplyReason,
    val detail: String,
) : ProtocolException("$reason: $detail") {
    companion object {
        fun findIn(failure: Throwable): WireSendBlockedException? =
            generateSequence(failure) { it.cause }.take(12).filterIsInstance<WireSendBlockedException>().firstOrNull()
    }
}

/**
 * The ONE wire gate, installed as an OkHttp network interceptor directly below `ConnectInterceptor`
 * and directly above `CallServerInterceptor` for both providers.
 */
class WireBudgetInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val ctx = request.tag(WireCallContext::class.java)
            ?: throw WireSendBlockedException(NoReplyReason.WITHDRAWN, "not sent: untagged request")

        val reservation = ctx.reservation
        if (reservation == null) {
            if (request.method != "GET") {
                throw WireSendBlockedException(NoReplyReason.WITHDRAWN, "not sent: free tag on ${request.method}")
            }
            if (ctx.deadline.remainingMs() <= 0L) {
                throw WireSendBlockedException(NoReplyReason.TIMEOUT, "not sent: free request deadline passed")
            }
            return chain.proceed(request)
        }

        if (chain.call().isCanceled()) throw IOException("Canceled")
        val leftMs = ctx.deadline.remainingMs()
        if (leftMs < ctx.minTimeToSend.toMillis()) {
            throw WireSendBlockedException(
                NoReplyReason.TOO_LATE_TO_SEND,
                "not sent: ${leftMs / 1000} s of the query's time were left",
            )
        }
        if (!reservation.isReserved()) {
            throw WireSendBlockedException(NoReplyReason.WITHDRAWN, "not sent: budget place is no longer open")
        }

        return try {
            chain.proceed(request).also { reservation.stamp() }
        } catch (e: Throwable) {
            if (!isPreWriteFailure(e)) reservation.stamp()
            throw e
        }
    }

    private fun isPreWriteFailure(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(12).any { cause ->
            cause is ConnectionShutdownException ||
                (cause is StreamResetException && cause.errorCode == ErrorCode.REFUSED_STREAM)
        }
}
