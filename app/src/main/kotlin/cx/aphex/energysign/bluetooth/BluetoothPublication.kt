package cx.aphex.energysign.bluetooth

import android.bluetooth.le.AdvertiseCallback
import cx.aphex.energysign.OptionalStart
import cx.aphex.energysign.StartWorker
import cx.aphex.energysign.optional

internal interface Radio {
    /** Look up the live adapter before trusting the previous publication. */
    fun adapter(): Any
    val isOn: Boolean
    fun enable(): Boolean
    fun openGatt(added: (Int) -> Unit): Boolean
    fun advertise(named: Boolean, result: (Int) -> Unit)
    fun close()
}

/** Runs on the optional-service worker. Each submission accepts only its own callback. */
internal class BluetoothPublication(
    private val radio: Radio,
    private val worker: StartWorker,
    private val warn: (String) -> Unit,
    private val show: (Int) -> Unit = {},
) {
    private var adapter: Any? = null
    private var enabling = false
    private var submission = 0
    private val publication = OptionalStart("Bluetooth publication", worker, warn, release = {
        enabling = false
        radio.close()
    }, expired = { worker.post { if (radio.isOn) ensure() } }) { attempt ->
        enabling = !radio.isOn
        if (enabling) check(radio.enable()) else {
            val serial = ++submission
            check(radio.openGatt { status ->
                if (serial == submission && attempt.pending()) {
                    if (status == 0) advertise(attempt, true) else attempt.fail("GATT status $status")
                }
            })
        }
    }

    fun ensure() {
        optional("Bluetooth adapter check", warn) {
            try {
                val physical = radio.adapter()
                val on = radio.isOn
                if (physical !== adapter || (on && enabling) || (!on && !enabling)) publication.reset()
                adapter = physical
                publication.ensure()
            } catch (failure: Throwable) {
                publication.reset()
                throw failure
            }
        }
    }

    fun radioOn() { publication.reset(); ensure() }
    fun radioOff() = publication.reset()

    private fun advertise(attempt: OptionalStart.Attempt, named: Boolean) {
        if (!attempt.renewDeadline()) return
        val serial = ++submission
        try {
            radio.advertise(named) { status ->
                if (serial == submission && attempt.pending()) {
                    if (named && status == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) advertise(attempt, false)
                    else {
                        if (status == 0) attempt.done() else attempt.fail("advertising status $status")
                        optional("Bluetooth status display", warn) { show(status) }
                    }
                }
            }
        } catch (failure: Throwable) { attempt.fail(failure.javaClass.name) }
    }
}
