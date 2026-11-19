package cx.aphex.energysign

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal typealias StartAlarm = (() -> Unit) -> (() -> Unit)

/** One pending operation, one confirmed result. Failure and expiration allow a new attempt.
 * The watchdog only invalidates a ticket. Owners release resources on their own serial worker.
 */
internal class BoundedStart(private val alarm: StartAlarm = ::startupAlarm) {
    private var current: Ticket? = null

    inner class Ticket internal constructor() {
        internal var cancel = {}
        internal var confirmed = false

        fun pending(): Boolean = synchronized(this@BoundedStart) { current === this && !confirmed }

        fun done(): Boolean = synchronized(this@BoundedStart) {
            if (!pending()) return false
            confirmed = true
            cancel()
            true
        }

        fun fail(): Boolean = synchronized(this@BoundedStart) {
            if (current !== this) return false
            reset()
            true
        }
    }

    @Synchronized
    fun begin(onTimeout: (Ticket) -> Unit): Ticket? {
        if (current != null) return null
        val ticket = Ticket()
        current = ticket
        try {
            ticket.cancel = alarm {
                val expired = synchronized(this) { ticket.pending() && ticket.fail() }
                if (expired) onTimeout(ticket)
            }
        } catch (failure: Throwable) {
            reset()
            throw failure
        }
        return ticket
    }

    @Synchronized
    fun reset() {
        current?.cancel?.invoke()
        current = null
    }
}

private val watchdog by lazy {
    ScheduledThreadPoolExecutor(1) { task -> Thread(task, "sign-start-deadlines").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
}

internal fun startupAlarm(fire: () -> Unit): () -> Unit {
    val future = watchdog.schedule(fire, 20, TimeUnit.SECONDS)
    return { future.cancel(false) }
}

internal fun serialWorker(name: String) = Executors.newSingleThreadExecutor { task ->
    Thread(task, name).apply { isDaemon = true }
}
