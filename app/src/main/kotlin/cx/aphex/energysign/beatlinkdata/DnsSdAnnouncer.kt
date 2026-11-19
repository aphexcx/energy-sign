package cx.aphex.energysign.beatlinkdata

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.StartAlarm
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.startupAlarm
import java.util.concurrent.Executor

/** The HTTP worker owns registrations. Each NSD request gets a distinct listener. */
internal class DnsSdAnnouncer(
    private val context: Context,
    private val worker: Executor,
    private val arm: StartAlarm = ::startupAlarm,
) {
    private val startup = SignStartup { logW(it) }
    private val operation = BoundedStart(arm)
    private var current: NsdManager.RegistrationListener? = null
    private val manager get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    internal var submit: (NsdManager.RegistrationListener) -> Unit = { listener ->
        val info = NsdServiceInfo().apply {
            serviceName = "BeatLinkDataConsumer"
            serviceType = "_beatlinkdata._tcp"
            port = BEATLINK_PORT
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    internal var unregister: (NsdManager.RegistrationListener) -> Unit = { manager.unregisterService(it) }

    fun start() {
        val ticket = operation.begin { expired ->
            startup.failed("DNS-SD registration", "callback deadline")
            worker.execute { if (activeTicket === expired) release() }
        } ?: return
        release()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (ticket.done()) logD("DNS-SD registered on port $BEATLINK_PORT")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                if (ticket.fail()) startup.failed("DNS-SD registration", "Android status $code")
                // Android removes the listener after a failed registration.
                worker.execute { if (current === this) current = null }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) { ticket.fail() }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                startup.failed("DNS-SD removal", "Android status $code")
            }
        }
        current = listener
        activeTicket = ticket
        if (startup.optional("DNS-SD submission") { submit(listener) } == null) {
            ticket.fail()
            release()
        }
    }

    private var activeTicket: BoundedStart.Ticket? = null

    private fun release() {
        val old = current
        current = null
        activeTicket = null
        if (old != null) startup.optional("DNS-SD removal") { unregister(old) }
    }

    fun stop() {
        operation.reset()
        release()
    }
}
