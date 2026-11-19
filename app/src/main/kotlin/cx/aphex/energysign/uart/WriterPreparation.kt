package cx.aphex.energysign.uart

import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.StartAlarm
import cx.aphex.energysign.startupAlarm

/** Preparation runs on the link before its first read. A failed pass can be retried on that
 * same owner at foreground without racing a reply or creating a second warm-up thread. */
internal class WriterPreparation(
    private val startup: SignStartup,
    private val prepare: (stopped: () -> Boolean) -> Unit,
    private val post: (() -> Unit) -> Unit,
    alarm: StartAlarm = ::startupAlarm,
) {
    private val operation = BoundedStart(alarm)
    @Volatile private var linkStopped: (() -> Boolean)? = null

    fun beforeFirstRead(stopped: () -> Boolean) {
        val ticket = begin()
        linkStopped = stopped
        if (ticket != null) run(ticket, stopped)
    }

    fun onForeground() {
        val stopped = linkStopped ?: return
        if (stopped()) return
        val ticket = begin() ?: return
        if (startup.optional("JSON preparation retry") { post { run(ticket, stopped) } } == null) ticket.fail()
    }

    private fun begin() = startup.optional("JSON preparation deadline") {
        operation.begin { startup.failed("JSON writer preparation", "adapter deadline") }
    }

    private fun run(ticket: BoundedStart.Ticket, stopped: () -> Boolean) {
        if (!ticket.pending() || stopped()) {
            ticket.fail()
            return
        }
        val completed = startup.optional("JSON writer preparation") {
            prepare { stopped() || !ticket.pending() }
        }
        if (completed == null || stopped()) ticket.fail() else ticket.done()
    }
}
