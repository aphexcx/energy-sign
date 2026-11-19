package cx.aphex.energysign.bluetooth

import android.bluetooth.*
import android.content.Context
import com.jakewharton.rxrelay3.PublishRelay
import cx.aphex.energysign.MainViewModel
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

    /* Collection of notification subscribers */
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    fun start(): Boolean {
        val server = checkNotNull(bluetoothManager.openGattServer(context, this)) { "Bluetooth GATT server unavailable" }
        gattServer = server
        return server.addService(NordicUartServiceProfile.createNordicUartService())
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        if (!closed && service.uuid == NordicUartServiceProfile.NORDIC_UART_SERVICE_UUID) serviceAdded(status)
    }

    fun stop() {
        closed = true
        val server = gattServer
        gattServer = null
        server?.close()
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
        if (closed) return
        logD("MTU Changed! New MTU: $mtu")
        viewModel.onBtStatusUpdate(
            BluetoothStatusUpdate(
                btDevice = device,
                mtu = mtu
            )
        )
    }

    override fun onConnectionStateChange(
        bluetoothDevice: BluetoothDevice,
        status: Int,
        newState: Int
    ) {
        if (closed) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                logD("BluetoothDevice CONNECTED: $bluetoothDevice")
                bluetoothDevice
                    .connectGatt(
                        context,
                        true,
                        object : BluetoothGattCallback() {
                            override fun onMtuChanged(
                                gatt: BluetoothGatt?,
                                mtu: Int,
                                status: Int
                            ) {
                                super.onMtuChanged(gatt, mtu, status)
                                this@EnergySignBluetoothGattServer.onMtuChanged(
                                    bluetoothDevice,
                                    mtu
                                )
                            }

                            override fun onConnectionStateChange(
                                gatt: BluetoothGatt?,
                                status: Int,
                                newState: Int
                            ) {
                                super.onConnectionStateChange(gatt, status, newState)
                                when (newState) {
                                    BluetoothProfile.STATE_CONNECTED -> {
                                        logD(
                                            "Connected to remote device ${gatt?.device?.name
                                                ?: ""}, requesting MTU=512..."
                                        )
                                        gatt?.requestMtu(512)
                                    }
                                }
                            }
                        })
                viewModel.onBtStatusUpdate(
                    BluetoothStatusUpdate(
                        btDevice = bluetoothDevice,
                        mtu = null
                    )
                )
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                logD("BluetoothDevice DISCONNECTED: $bluetoothDevice")
                //Remove bluetoothDevice from any active subscriptions
                registeredDevices.remove(bluetoothDevice)
                viewModel.onBtStatusUpdate(
                    BluetoothStatusUpdate(
                        btDevice = bluetoothDevice,
                        mtu = null
                    )
                )
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
                val result = device.createBond()
                if (result) {
                    logD("Creating bond with remote device ${device.address}...")
                } else {
                    logW("Immediate error when attempting to create a bond with remote device ${device.address} :(")
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
        if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR) {
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                logD("Subscribe device to notifications: $device")
                registeredDevices.add(device)
            } else if (Arrays.equals(
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
                    value
                )
            ) {
                logD("Unsubscribe device from notifications: $device")
                registeredDevices.remove(device)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0, value
                ) //TODO Right response here?
            }
        } else {
            logW("Unknown descriptor write request")
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0, null
                )
            }
        }
    }

}

