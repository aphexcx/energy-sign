package cx.aphex.energysign.bluetooth

import cx.aphex.energysign.SignStartup

/** Android's adapter broadcasts and the initial adapter check run on the main thread. */
internal class BluetoothSession(
    private val startup: SignStartup,
    private val register: () -> Unit,
    private val unregister: () -> Unit,
    private val enabled: () -> Boolean,
    private val enable: () -> Boolean,
    private val openServices: () -> Unit,
    private val closeServices: () -> Unit,
) {
    fun start() {
        try {
            register()
            if (enabled()) openServices() else check(enable()) { "Bluetooth adapter refused to enable" }
        } catch (failure: Throwable) {
            stop()
            throw failure
        }
    }

    fun adapterOn() {
        if (startup.optional("Bluetooth startup", openServices) == null) stop()
    }

    fun adapterOff() = closeServices()

    fun stop() {
        startup.optional("Bluetooth receiver cleanup", unregister)
        closeServices()
    }
}
