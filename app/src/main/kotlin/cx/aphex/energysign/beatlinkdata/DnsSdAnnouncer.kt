package cx.aphex.energysign.beatlinkdata

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import cx.aphex.energysign.OptionalStart
import cx.aphex.energysign.StartWorker
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW

internal class DnsSdAnnouncer(context: Context, private val worker: StartWorker) {
    private val manager get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    internal var register: (Int, NsdManager.RegistrationListener) -> Unit = { port, listener ->
        val info = NsdServiceInfo().apply {
            serviceName = "BeatLinkDataConsumer"
            serviceType = "_beatlinkdata._tcp"
            this.port = port
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    internal var unregister: (NsdManager.RegistrationListener) -> Unit = { manager.unregisterService(it) }
    private var listener: NsdManager.RegistrationListener? = null
    private val registration = OptionalStart("DNS-SD registration", worker, { logW(it) }, release = {
        val old = listener
        listener = null
        old?.let(unregister)
    }) { attempt ->
        val callbacks = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = worker.post {
                if (attempt.done()) logD("DNS-SD registered on port $BEATLINK_PORT")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = worker.post {
                if (attempt.live) {
                    listener = null // Android already removed it.
                    attempt.fail("Android status $code")
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = worker.post {
                if (attempt.live) { listener = null; attempt.fail("unregistered") }
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = worker.post {
                logW("DNS-SD removal failed (Android status $code), sign link continues")
            }
        }
        listener = callbacks
        register(BEATLINK_PORT, callbacks)
    }

    fun ensure() = registration.ensure()
}
