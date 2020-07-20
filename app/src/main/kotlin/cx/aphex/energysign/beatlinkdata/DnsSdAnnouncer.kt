package cx.aphex.energysign.beatlinkdata

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.ServerSocket

/**
 * Announces that we are a beat-link data consumer and want to get POSTED updates
 * from any beat-link data providers on the network.
 */
class DnsSdAnnouncer(val context: Context) : NsdManager.RegistrationListener {

    private lateinit var nsdManager: NsdManager
    var localPort: Int = -1
    private lateinit var serviceName: String
    private lateinit var serverSocket: ServerSocket
    private lateinit var serviceInfo: NsdServiceInfo

    fun start() {
        initializeServerSocket()
        registerService(localPort)
    }

    private fun registerService(port: Int) {
        // Create the NsdServiceInfo object, and populate it.
        serviceInfo = NsdServiceInfo().apply {
            // The name is subject to change based on conflicts
            // with other services advertised on the same network.
            serviceName = "BeatLinkDataConsumer"
            serviceType = "_beatlinkdata._tcp"
//            host = serverSocket.inetAddress
            setPort(port)

        }

        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, this@DnsSdAnnouncer)
        }
    }

    private fun initializeServerSocket() {
        // Initialize a server socket on the next available port.
        serverSocket = ServerSocket(0).also { socket ->
            // Store the chosen port.
            localPort = socket.localPort
            socket.close()

            //TODO use this port for server
        }
    }

    override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
        // Save the service name. Android may have changed it in order to
        // resolve a conflict, so update the name you initially requested
        // with the name Android actually used.
        serviceName = NsdServiceInfo.serviceName
        Log.d("DnsSdAnnouncerService", "Service registered: $serviceInfo")
    }

    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        // Registration failed! Put debugging code here to determine why.
        Log.d(
            "DnsSdAnnouncerService",
            "Service registration failed: $serviceInfo error code: $errorCode"
        )
    }

    override fun onServiceUnregistered(arg0: NsdServiceInfo) {
        // Service has been unregistered. This only happens when you call
        // NsdManager.unregisterService() and pass in this listener.
    }

    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        // Unregistration failed. Put debugging code here to determine why.
    }

}
