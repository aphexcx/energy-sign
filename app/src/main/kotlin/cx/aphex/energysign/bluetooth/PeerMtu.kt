package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import cx.aphex.energysign.SignStartup

/** The optional outgoing GATT connection used to negotiate MTU for one connected phone. */
internal class PeerMtu(
    private val startup: SignStartup,
    private val post: (() -> Unit) -> Unit,
    private val changed: (Int) -> Unit,
    private val request: (BluetoothGatt) -> Boolean = { it.requestMtu(512) },
    private val close: (BluetoothGatt) -> Unit = { it.close() },
) : BluetoothGattCallback() {
    // Access only on the main thread. Binder callbacks can arrive before connectGatt returns.
    private var client: BluetoothGatt? = null

    fun start(connect: (BluetoothGattCallback) -> BluetoothGatt?) {
        client = startup.optional("Bluetooth MTU connection") { checkNotNull(connect(this)) }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post {
        if (gatt !== client) return@post
        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
            stop()
        } else if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (startup.optional("Bluetooth MTU request") { check(request(gatt)) } == null) stop()
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = post {
        if (gatt !== client) return@post
        if (status == BluetoothGatt.GATT_SUCCESS) changed(mtu) else stop()
    }

    fun stop() {
        val old = client ?: return
        client = null
        startup.optional("Bluetooth MTU cleanup") { close(old) }
    }
}
