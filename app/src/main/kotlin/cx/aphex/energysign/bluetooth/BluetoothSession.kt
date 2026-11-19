package cx.aphex.energysign.bluetooth

import cx.aphex.energysign.SignStartup

/** All transitions, including Android's posted registration results, run on the main thread. */
internal class BluetoothSession(
    private val startup: SignStartup,
    private val register: () -> Unit,
    private val unregister: () -> Unit,
    private val enabled: () -> Boolean,
    private val enable: () -> Boolean,
    private val openGatt: (onAdded: (Int) -> Unit) -> Boolean,
    private val advertise: (onResult: (Int) -> Unit) -> Unit,
    private val closeServices: () -> Unit,
    private val showAdvertiseResult: (Int) -> Unit = {},
) {
    private enum class Phase { REGISTERING_SERVICE, STARTING_ADVERTISER, READY }

    /** Android may deliver a callback from an older server after an OFF/ON cycle. */
    private class Attempt(var phase: Phase = Phase.REGISTERING_SERVICE)
    private var current: Attempt? = null
    private var listening = false

    fun start() {
        try {
            register()
        } catch (failure: Throwable) {
            startup.optional("Bluetooth receiver cleanup", unregister)
            throw failure
        }
        listening = true
        startup.optional("Bluetooth adapter activation") {
            if (enabled()) adapterOn() else check(enable()) { "Bluetooth adapter refused to enable" }
        }
    }

    fun adapterOn() {
        if (!listening || current?.phase == Phase.READY) return
        // A fresh ON also recovers when the preceding registration callback never arrived.
        adapterOff()
        val attempt = Attempt()
        current = attempt
        val accepted = startup.optional("Bluetooth GATT registration") {
            check(openGatt { status -> serviceAdded(attempt, status) }) { "UART service submission refused" }
        }
        if (accepted == null && current === attempt) adapterOff()
    }

    private fun serviceAdded(attempt: Attempt, status: Int) {
        if (current !== attempt || attempt.phase != Phase.REGISTERING_SERVICE) return
        if (status != 0) {
            adapterOff()
            startup.failed("Bluetooth GATT registration", "Android status $status")
            return
        }

        attempt.phase = Phase.STARTING_ADVERTISER
        val submitted = startup.optional("Bluetooth advertising") {
            advertise { result -> advertisementStarted(attempt, result) }
        }
        if (submitted == null && current === attempt) adapterOff()
    }

    private fun advertisementStarted(attempt: Attempt, status: Int) {
        if (current !== attempt || attempt.phase != Phase.STARTING_ADVERTISER) return
        if (status == 0) {
            attempt.phase = Phase.READY
        } else {
            adapterOff()
            startup.failed("Bluetooth advertising", "Android status $status")
        }
        startup.optional("Bluetooth status display") { showAdvertiseResult(status) }
    }

    fun adapterOff() {
        if (current == null) return
        current = null // Invalidate callbacks before asking Android to release their resources.
        closeServices()
    }

    fun stop() {
        listening = false
        startup.optional("Bluetooth receiver cleanup", unregister)
        adapterOff()
    }
}
