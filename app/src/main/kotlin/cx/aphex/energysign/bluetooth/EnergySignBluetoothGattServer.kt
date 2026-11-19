package cx.aphex.energysign.bluetooth

import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.content.Context
import com.jakewharton.rxrelay3.PublishRelay
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import java.util.*


data class BluetoothStatusUpdate(val btDevice: BluetoothDevice?, val mtu: Int?)

/** Gatt Server, plus callbacks to handle incoming requests to the GATT server.
 * All read/write requests for characteristics and descriptors are handled here.
 */
internal class EnergySignBluetoothGattServer(
    val context: Context,
    val bluetoothManager: BluetoothManager,
    val viewModel: MainViewModel,
    internal val writes: PublishRelay<GattWrite> = PublishRelay.create(),
    private val serviceAdded: (Int) -> Unit = {},
) : BluetoothGattServerCallback() {

    @Volatile
    private var gattServer: BluetoothGattServer? = null
    @Volatile
    private var closed = false

    private val startup = SignStartup { logW(it) }
    private val mainThread = Handler(Looper.getMainLooper())
    private val mtuClients = linkedMapOf<BluetoothDevice, PeerMtu>()
    private val requestedBonds = mutableMapOf<BluetoothDevice, BoundedStart>()

    /* Collection of notification subscribers */
    private val registeredDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    internal var postPeer: (() -> Unit) -> Unit = { mainThread.post(it) }
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
        mtuClients.values.forEach { it.stop() }
        mtuClients.clear()
        requestedBonds.values.forEach { it.reset() }
        requestedBonds.clear()
        if (gattServer != null) startup.optional("Bluetooth status display") { showPeer(BluetoothStatusUpdate(null, null)) }
        registeredDevices.clear()
        val server = gattServer
        gattServer = null
        server?.close()
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
        postPeer {
            if (!closed && device != null) recordMtu(device, mtu)
        }
    }

    private fun recordMtu(device: BluetoothDevice, mtu: Int) {
        val peer = mtuClients[device] ?: return
        peer.mtu = mtu
        showPeer(BluetoothStatusUpdate(device, mtu))
    }

    fun retryPeerSetup() {
        mtuClients.forEach { (device, peer) -> connectMtu(device, peer) }
        requestedBonds.keys.forEach(::ensureBond)
    }

    private fun connectMtu(device: BluetoothDevice, peer: PeerMtu) {
        peer.start { callback -> device.connectGatt(context, true, callback) }
    }

    private fun ensureBond(device: BluetoothDevice) {
        val operation = requestedBonds[device] ?: return
        val ticket = operation.begin { startup.failed("Bluetooth bonding", "bond-state deadline") } ?: return
        val result = startup.optional("Bluetooth bonding") {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> ticket.done()
                BluetoothDevice.BOND_BONDING -> Unit
                else -> check(device.createBond())
            }
        }
        if (result == null) ticket.fail()
    }

    fun bondState(device: BluetoothDevice, state: Int) {
        val operation = requestedBonds[device] ?: return
        if (state == BluetoothDevice.BOND_NONE) operation.reset()
        if (state == BluetoothDevice.BOND_BONDED) {
            operation.reset()
            requestedBonds.remove(device)
        }
    }

    override fun onConnectionStateChange(
        bluetoothDevice: BluetoothDevice,
        status: Int,
        newState: Int
    ) {
        postPeer post@{
            if (closed) return@post
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS || bluetoothDevice in mtuClients) return@post
                    val peer = PeerMtu(startup,
                        post = postPeer,
                        changed = { mtu -> recordMtu(bluetoothDevice, mtu) },
                    )
                    mtuClients[bluetoothDevice] = peer
                    showPeer(BluetoothStatusUpdate(bluetoothDevice, null))
                    connectMtu(bluetoothDevice, peer)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mtuClients.remove(bluetoothDevice)?.stop()
                    registeredDevices.remove(bluetoothDevice)
                    requestedBonds.remove(bluetoothDevice)?.reset()
                    val remaining = mtuClients.entries.lastOrNull()
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
            val reply = if (status == BluetoothGatt.GATT_SUCCESS) "Uart Write response".toByteArray() else null
            server?.sendResponse(device, requestId, status, 0, reply)
        }
    }

    internal fun uartWriteStatus(
        value: ByteArray, preparedWrite: Boolean = false, offset: Int = 0, responseNeeded: Boolean = true,
    ): Int {
        if (preparedWrite) return BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        if (offset != 0) return BluetoothGatt.GATT_INVALID_OFFSET
        val write = GattWrite(value, responseNeeded)
        writes.accept(write)
        return if (write.delivered) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
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
                postPeer {
                    if (!closed && device in mtuClients) {
                        requestedBonds.getOrPut(device) { BoundedStart() }
                        ensureBond(device)
                    }
                }

                val returnValue =
//                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (registeredDevices.contains(device)) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
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
            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                registeredDevices.add(device)
                BluetoothGatt.GATT_SUCCESS
            }
            value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
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
