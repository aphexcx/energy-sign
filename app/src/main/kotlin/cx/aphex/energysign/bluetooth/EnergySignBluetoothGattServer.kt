package cx.aphex.energysign.bluetooth

import android.bluetooth.*
import android.content.Context
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.OptionalStart
import cx.aphex.energysign.StartWorker
import cx.aphex.energysign.optional
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import java.util.*


// Bluetooth Core Specification, Client Characteristic Configuration (0x2902), little endian.
private val NOTIFICATIONS_ON = byteArrayOf(1, 0)
private val NOTIFICATIONS_OFF = byteArrayOf(0, 0)

data class BluetoothStatusUpdate(val btDevice: BluetoothDevice?, val mtu: Int?)

/** Gatt Server, plus callbacks to handle incoming requests to the GATT server.
 * All read/write requests for characteristics and descriptors are handled here.
 */
internal class EnergySignBluetoothGattServer(
    val context: Context,
    val bluetoothManager: BluetoothManager,
    val viewModel: MainViewModel,
    private val worker: StartWorker,
    private val takeWrite: (ByteArray, Boolean) -> Boolean,
    private val serviceAdded: (Int) -> Unit = {},
) : BluetoothGattServerCallback() {

    @Volatile
    private var gattServer: BluetoothGattServer? = null
    @Volatile
    private var closed = false

    private val peers = linkedMapOf<BluetoothDevice, Peer>()

    /** Worker-owned resources of one connected phone. The callbacks also belong to this attempt. */
    private inner class Peer(private val device: BluetoothDevice) {
        var mtu: Int? = null
        private var client: BluetoothGatt? = null
        private var wantsBond = false
        private val negotiation = OptionalStart("Bluetooth MTU", worker, { logW(it) }, release = {
            val old = client
            client = null
            old?.close()
        }) { attempt ->
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = worker.post {
                    if (client !== gatt || !attempt.live) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        attempt.fail("connection status $status/$newState")
                    } else if (newState == BluetoothProfile.STATE_CONNECTED && attempt.pending()) {
                        try {
                            if (!gatt.requestMtu(512)) attempt.fail("request refused")
                        } catch (failure: Throwable) { attempt.fail(failure.javaClass.name) }
                    }
                }
                override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) = worker.post {
                    if (client !== gatt || !attempt.live) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) attempt.fail("Android status $status")
                    else if (attempt.done() || attempt.confirmed) recordMtu(device, value)
                }
            }
            client = checkNotNull(device.connectGatt(context, true, callback))
            attempt.pending() // A synchronous platform return can itself be late.
        }

        private val bond = OptionalStart("Bluetooth bonding", worker, { logW(it) }) { attempt ->
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> attempt.done()
                BluetoothDevice.BOND_BONDING -> Unit
                else -> check(device.createBond())
            }
        }

        fun requestBond() { wantsBond = true; bond.ensure() }
        fun bondChanged(state: Int) {
            if (state == BluetoothDevice.BOND_BONDED) wantsBond = false
            if (state != BluetoothDevice.BOND_BONDING) bond.reset()
        }
        fun ensure() {
            negotiation.ensure()
            if (wantsBond) bond.ensure()
        }
        fun release() { negotiation.reset(); bond.reset() }
    }

    private val registeredDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
    internal var showPeer: (BluetoothStatusUpdate) -> Unit = viewModel::onBtStatusUpdate

    fun start(): Boolean {
        val server = checkNotNull(bluetoothManager.openGattServer(context, this)) { "Bluetooth GATT server unavailable" }
        gattServer = server
        return server.addService(NordicUartServiceProfile.createNordicUartService())
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        if (closed || service.uuid != NordicUartServiceProfile.NORDIC_UART_SERVICE_UUID) return
        if (status == BluetoothGatt.GATT_SUCCESS) logD("Nordic UART service registered")
        serviceAdded(status)
    }

    fun stop() {
        closed = true
        peers.values.forEach { it.release() }
        peers.clear()
        val server = gattServer
        gattServer = null
        registeredDevices.clear()
        if (server != null) optional("Bluetooth status display", { logW(it) }) { showPeer(BluetoothStatusUpdate(null, null)) }
        server?.close()
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) = worker.post {
        if (!closed && device != null) recordMtu(device, mtu)
    }

    private fun recordMtu(device: BluetoothDevice, mtu: Int) {
        val peer = peers[device] ?: return
        peer.mtu = mtu
        showPeer(BluetoothStatusUpdate(device, mtu))
    }

    fun retryPeerSetup() = peers.values.forEach { it.ensure() }
    fun bondState(device: BluetoothDevice, state: Int) { peers[device]?.bondChanged(state) }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        // Descriptor writes and disconnects arrive on the GATT callback path. A deferred removal
        // could erase a subscription acknowledged after this phone reconnects.
        if (newState == BluetoothProfile.STATE_DISCONNECTED) registeredDevices.remove(device)
        worker.post {
            if (closed) return@post
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (status == BluetoothGatt.GATT_SUCCESS && device !in peers) {
                    val peer = Peer(device)
                    peers[device] = peer
                    showPeer(BluetoothStatusUpdate(device, null))
                    peer.ensure()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    peers.remove(device)?.release()
                    val remaining = peers.entries.lastOrNull()
                    showPeer(BluetoothStatusUpdate(remaining?.key, remaining?.value?.mtu))
                }
            }
        }
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice?,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        if (closed) return
        val server = gattServer
        val status = if (characteristic.uuid == NordicUartServiceProfile.NORDIC_UART_TX_UUID) {
            logD("Uart TX characteristic write: [${String(value)}]")
            uartWriteStatus(value, preparedWrite, offset, responseNeeded)
        } else {
            logW("Invalid Characteristic Write: " + characteristic.uuid)
            BluetoothGatt.GATT_FAILURE
        }
        if (responseNeeded) {
            // The SDK (API 37) now says what Android always needed: a response goes to a device.
            if (device == null) {
                logW("Write request without a device: there is nobody to answer")
                return
            }
            val reply = if (status == BluetoothGatt.GATT_SUCCESS) "Uart Write response".toByteArray() else null
            server?.sendResponse(device, requestId, status, 0, reply)
        }
    }

    internal fun uartWriteStatus(
        value: ByteArray, preparedWrite: Boolean = false, offset: Int = 0, responseNeeded: Boolean = true,
    ): Int {
        if (preparedWrite) return BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        if (offset != 0) return BluetoothGatt.GATT_INVALID_OFFSET
        return if (!closed && takeWrite(value, responseNeeded)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (closed) return
        when (characteristic.uuid) {
            StringServiceProfile.CHARACTERISTIC_READER_UUID -> {
                logD("Read String")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    viewModel.sentString.toByteArray()
                )
            }
            StringServiceProfile.CHARACTERISTIC_INTERACTOR_UUID -> {
                logD("Read Interactor String???")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    viewModel.sentString.toByteArray()
                )
            }
            NordicUartServiceProfile.NORDIC_UART_RX_UUID -> {
                logD("Uart RX characteristic read")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    viewModel.sentString.toByteArray() //TODO should not pass in viewmodel
                    //FIXME this appears to not return the current string which is what we want
                )
            }
            else -> {
                // Invalid characteristic
                logW("Invalid Characteristic Read: " + characteristic.uuid)
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    null
                )
            }
        }
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        if (closed) return
        when (descriptor.uuid) {
            CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR -> {
                logD("Config descriptor read")
                worker.post { if (!closed) peers[device]?.requestBond() }

                val returnValue =
                    if (registeredDevices.contains(device)) {
                        NOTIFICATIONS_ON
                    } else {
                        NOTIFICATIONS_OFF
                    }

                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    returnValue
                )
            }
//                USER_DESCRIPTION_DESCRIPTOR -> bluetoothGattServer?.sendResponse(device,
//                        requestId,
//                        BluetoothGatt.GATT_SUCCESS,
//                        0,
//                        descriptor.value)
            else -> {
                logW("Unknown descriptor read request")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0, null
                )
            }
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ) {
        if (closed) return
        val status = when {
            descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            value.contentEquals(NOTIFICATIONS_ON) -> {
                registeredDevices.add(device)
                BluetoothGatt.GATT_SUCCESS
            }
            value.contentEquals(NOTIFICATIONS_OFF) -> {
                registeredDevices.remove(device)
                BluetoothGatt.GATT_SUCCESS
            }
            else -> BluetoothGatt.GATT_FAILURE
        }
        if (responseNeeded) {
            gattServer?.sendResponse(device, requestId, status, 0, if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }
}
