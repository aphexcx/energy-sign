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
    arm: StartAlarm = ::startupAlarm,
) {
    private val startup = SignStartup { logW(it) }
    private val operation = BoundedStart(arm)
    @Volatile private var stopped = false
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

    private fun post(action: () -> Unit) {
        if (stopped) return
        try {
            worker.execute(action)
        } catch (failure: java.util.concurrent.RejectedExecutionException) {
            if (!stopped) throw failure
        }
    }

    fun start() {
        val ticket = operation.begin { expired ->
            startup.failed("DNS-SD registration", "callback deadline")
            post { if (activeTicket === expired) release() }
        } ?: return
        release()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                if (ticket.done()) logD("DNS-SD registered on port $BEATLINK_PORT")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                if (ticket.pending()) post {
                    if (current === this && ticket.fail()) {
                        // Android has already removed this registration listener.
                        current = null
                        startup.failed("DNS-SD registration", "Android status $code")
                    }
                }
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
        stopped = true
        operation.reset()
        release()
    }
}
