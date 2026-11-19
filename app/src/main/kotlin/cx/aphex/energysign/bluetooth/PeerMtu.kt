package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.StartAlarm
import cx.aphex.energysign.startupAlarm

/** The optional outgoing GATT connection used to negotiate MTU for one connected phone. */
internal class PeerMtu(
    private val startup: SignStartup,
    private val post: (() -> Unit) -> Unit,
    private val changed: (Int) -> Unit,
    private val request: (BluetoothGatt) -> Boolean = { it.requestMtu(512) },
    private val close: (BluetoothGatt) -> Unit = { it.close() },
    arm: StartAlarm = ::startupAlarm,
) : BluetoothGattCallback() {
    private val attempt = BoundedStart(arm)
    private var ticket: BoundedStart.Ticket? = null
    var mtu: Int? = null

    // Access only on the Bluetooth worker. Binder callbacks can arrive before connectGatt returns.
    private var client: BluetoothGatt? = null

    fun start(connect: (BluetoothGattCallback) -> BluetoothGatt?) {
        val started = attempt.begin { expired -> post { if (ticket === expired) stop() } } ?: return
        client?.let { old -> startup.optional("Bluetooth MTU cleanup") { close(old) } }
        ticket = started
        client = startup.optional("Bluetooth MTU connection") { checkNotNull(connect(this)) }
        if (client == null || !started.pending()) stop()
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post {
        if (gatt !== client) return@post
        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
            stop()
        } else if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (ticket?.pending() == true && startup.optional("Bluetooth MTU request") { check(request(gatt)) } == null) stop()
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = post {
        if (gatt !== client) return@post
        if (status != BluetoothGatt.GATT_SUCCESS) stop()
        else if (ticket?.done() == true || ticket?.confirmed == true) changed(mtu)
    }

    fun stop() {
        attempt.reset()
        ticket = null
        val old = client ?: return
        client = null
        startup.optional("Bluetooth MTU cleanup") { close(old) }
    }
}
