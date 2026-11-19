package cx.aphex.energysign.bluetooth

import cx.aphex.energysign.SignStartup

/** Android's initial adapter check and its state broadcasts run on the main thread. */
internal class BluetoothSession(
    private val startup: SignStartup,
    private val register: () -> Unit,
    private val unregister: () -> Unit,
    private val enabled: () -> Boolean,
    private val enable: () -> Boolean,
    private val openServices: () -> Unit,
    private val closeServices: () -> Unit,
) {
    private var listening = false
    private var servicesOpen = false

    fun start() {
        try {
            register()
        } catch (failure: Throwable) {
            // The second receiver can fail to register after the first one succeeded.
            startup.optional("Bluetooth receiver cleanup", unregister)
            throw failure
        }
        listening = true
        startup.optional("Bluetooth adapter activation") {
            if (enabled()) adapterOn() else check(enable()) { "Bluetooth adapter refused to enable" }
        }
    }

    fun adapterOn() {
        if (!listening || servicesOpen) return
        servicesOpen = startup.optional("Bluetooth services", openServices) != null
        // Advertising can already be live when GATT fails. Keep the receivers for the next STATE_ON.
        if (!servicesOpen) closeServices()
    }

    fun adapterOff() {
        if (!servicesOpen) return
        servicesOpen = false
        closeServices()
    }

    fun stop() {
        listening = false
        startup.optional("Bluetooth receiver cleanup", unregister)
        adapterOff()
    }
}
