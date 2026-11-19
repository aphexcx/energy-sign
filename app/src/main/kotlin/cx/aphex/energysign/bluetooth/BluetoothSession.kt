package cx.aphex.energysign.bluetooth

import android.bluetooth.le.AdvertiseCallback
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.StartAlarm
import cx.aphex.energysign.startupAlarm

/** All transitions, including Android's posted registration results, run on the Bluetooth worker. */
internal class BluetoothSession(
    private val startup: SignStartup,
    private val register: () -> Unit,
    private val unregister: () -> Unit,
    private val enabled: () -> Boolean,
    private val enable: () -> Boolean,
    private val openGatt: (onAdded: (Int) -> Unit) -> Boolean,
    private val advertise: (includeName: Boolean, onResult: (Int) -> Unit) -> Unit,
    private val closeServices: () -> Unit,
    private val showAdvertiseResult: (Int) -> Unit = {},
    private val post: (() -> Unit) -> Unit = { it() },
) {
    internal var arm: StartAlarm = ::startupAlarm
    private val publication = BoundedStart { arm(it) }
    private val activation = BoundedStart { arm(it) }

    private enum class Phase { REGISTERING_SERVICE, STARTING_ADVERTISER, STARTING_UUID_ONLY, READY }

    /** Android may deliver a callback from an older server after an OFF/ON cycle. */
    private class Attempt(var phase: Phase = Phase.REGISTERING_SERVICE, var ticket: BoundedStart.Ticket? = null)
    private var current: Attempt? = null
    private var listening = false

    fun start(registered: () -> Boolean = { true }) {
        try {
            register()
        } catch (failure: Throwable) {
            startup.optional("Bluetooth receiver cleanup", unregister)
            throw failure
        }
        if (!registered()) {
            unregister()
            return
        }
        listening = true
        checkAdapter()
    }

    fun checkAdapter() {
        if (!listening || current != null) return
        startup.optional("Bluetooth adapter activation") {
            if (enabled()) {
                adapterOn()
            } else {
                val ticket = activation.begin {
                    post {
                        startup.optional("Bluetooth adapter activation") {
                            if (listening && current == null && enabled()) adapterOn()
                        }
                    }
                } ?: return@optional
                try {
                    check(enable()) { "Bluetooth adapter refused to enable" }
                } catch (failure: Throwable) {
                    ticket.fail()
                    throw failure
                }
            }
        }
    }

    fun adapterOn() {
        activation.reset()
        if (!listening || current?.phase == Phase.READY) return
        // A fresh ON also recovers when the preceding registration callback never arrived.
        adapterOff()
        val attempt = Attempt()
        current = attempt
        watch(attempt)
        val accepted = startup.optional("Bluetooth GATT registration") {
            check(openGatt { status -> serviceAdded(attempt, status) }) { "UART service submission refused" }
        }
        if (accepted == null && current === attempt) adapterOff()
    }

    private fun watch(attempt: Attempt) {
        publication.reset()
        attempt.ticket = publication.begin { expired ->
            post {
                if (current === attempt && attempt.ticket === expired) {
                    startup.failed("Bluetooth publication", "callback deadline")
                    adapterOff()
                    startup.optional("Bluetooth adapter check") { if (listening && enabled()) adapterOn() }
                }
            }
        }
    }

    private fun serviceAdded(attempt: Attempt, status: Int) {
        if (current !== attempt || attempt.phase != Phase.REGISTERING_SERVICE || attempt.ticket?.pending() != true) return
        if (status != 0) {
            adapterOff()
            startup.failed("Bluetooth GATT registration", "Android status $status")
            return
        }

        submitAdvertisement(attempt, includeName = true)
    }

    private fun submitAdvertisement(attempt: Attempt, includeName: Boolean) {
        val phase = if (includeName) Phase.STARTING_ADVERTISER else Phase.STARTING_UUID_ONLY
        attempt.phase = phase
        watch(attempt)
        val submitted = startup.optional("Bluetooth advertising") {
            advertise(includeName) { status ->
                if (current === attempt && attempt.phase == phase && attempt.ticket?.pending() == true) {
                    if (includeName && status == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                        submitAdvertisement(attempt, includeName = false)
                    } else {
                        advertisementStarted(attempt, status)
                    }
                }
            }
        }
        if (submitted == null && current === attempt) adapterOff()
    }

    private fun advertisementStarted(attempt: Attempt, status: Int) {
        if (status == 0) {
            if (attempt.ticket?.done() != true) return
            attempt.phase = Phase.READY
        } else {
            adapterOff()
            startup.failed("Bluetooth advertising", "Android status $status")
        }
        startup.optional("Bluetooth status display") { showAdvertiseResult(status) }
    }

    fun adapterOff() {
        activation.reset()
        publication.reset()
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
