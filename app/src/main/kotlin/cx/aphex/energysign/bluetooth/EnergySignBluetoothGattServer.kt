package cx.aphex.energysign.bluetooth

import android.bluetooth.*
import android.content.Context
import com.jakewharton.rxrelay3.BehaviorRelay
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import java.util.*


data class BluetoothStatusUpdate(val btDeviceName: String?, val mtu: Int?)

/** Gatt Server, plus callbacks to handle incoming requests to the GATT server.
 * All read/write requests for characteristics and descriptors are handled here.
 */
class EnergySignBluetoothGattServer(
    val context: Context,
    val bluetoothManager: BluetoothManager,
    val viewModel: MainViewModel
) : BluetoothGattServerCallback() {

    private var gattServer: BluetoothGattServer? = null
    val receivedBytes: BehaviorRelay<ByteArray> = BehaviorRelay.createDefault(byteArrayOf())

    val bluetoothStatusUpdates: BehaviorRelay<BluetoothStatusUpdate> =
        BehaviorRelay.createDefault(BluetoothStatusUpdate(null, null))

    /* Collection of notification subscribers */
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    fun start() {
        gattServer = bluetoothManager.openGattServer(context, this)
        gattServer?.addService(NordicUartServiceProfile.createNordicUartService())
            ?: logW("Unable to create GATT server")
    }

    fun stop() {
        gattServer?.close()
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
        logD("MTU Changed! New MTU: $mtu")
//        viewModel.onBtStatusUpdate(it)
        bluetoothStatusUpdates.accept(bluetoothStatusUpdates.value.copy(mtu = mtu))
    }

    override fun onConnectionStateChange(
        bluetoothDevice: BluetoothDevice,
        status: Int,
        newState: Int
    ) {
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

//                viewModel.onBtStatusUpdate(
                bluetoothStatusUpdates.accept(
                    BluetoothStatusUpdate(
                        btDeviceName = "📲${bluetoothDevice.name ?: ""}$bluetoothDevice",
                        mtu = null
                    )
                )
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                logD("BluetoothDevice DISCONNECTED: $bluetoothDevice")
                //Remove bluetoothDevice from any active subscriptions
                registeredDevices.remove(bluetoothDevice)
//                viewModel.onBtStatusUpdate(
                bluetoothStatusUpdates.accept(
                    BluetoothStatusUpdate(
                        btDeviceName = "📴",
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
        when (characteristic.uuid) {
//                CHARACTERISTIC_INTERACTOR_UUID -> {
//                    logD("Write Interactor String! Value: [${String(value!!)}]")
//                    processNewReceivedString(value)
//                    bluetoothGattServer?.sendResponse(device,
//                            requestId,
//                            BluetoothGatt.GATT_SUCCESS,
//                            0,
//                            currentString)
//                }
            NordicUartServiceProfile.NORDIC_UART_TX_UUID -> {
                logD("Uart TX characteristic write: [${String(value)}]")
                receivedBytes.accept(value)

                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    "Uart Write response".toByteArray()
                )
            }
            else -> {
                // Invalid characteristic
                logW("Invalid Characteristic Write: " + characteristic.uuid)
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

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        when (characteristic.uuid) {
            StringServiceProfile.CHARACTERISTIC_READER_UUID -> {
                logD("Read String")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    receivedBytes.value
                )
            }
            StringServiceProfile.CHARACTERISTIC_INTERACTOR_UUID -> {
                logD("Read Interactor String???")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    receivedBytes.value
                )
            }
            NordicUartServiceProfile.NORDIC_UART_RX_UUID -> {
                logD("Uart RX characteristic read")
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    viewModel.currentBytes.value //TODO should not pass in viewmodel
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
