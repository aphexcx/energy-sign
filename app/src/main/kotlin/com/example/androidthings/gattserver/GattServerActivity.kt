/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.gattserver

import android.app.Activity
import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.chibatching.kotpref.Kotpref
import com.example.androidthings.gattserver.NordicUartServiceProfile.NORDIC_UART_RX_UUID
import com.example.androidthings.gattserver.NordicUartServiceProfile.NORDIC_UART_TX_UUID
import com.example.androidthings.gattserver.StringServiceProfile.CHARACTERISTIC_INTERACTOR_UUID
import com.example.androidthings.gattserver.StringServiceProfile.CHARACTERISTIC_READER_UUID
import com.google.android.things.pio.PeripheralManager
import com.google.android.things.pio.UartDevice
import com.google.android.things.pio.UartDeviceCallback
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


private const val TAG = "GattServerActivity"

class GattServerActivity : Activity() {

    private var shouldShowNewStringAlert: Boolean = true
    private var isChooserModeEnabled: Boolean = false
    private var keyboardInputStartedAtMs: Long = 0
    private var isPaused: Boolean = false
    private var isMicOn: Boolean = true
    private var sendMicChange: Boolean = false

    private var currentString: ByteArray = byteArrayOf()
        set(value) {
            field = value
//            shouldShowNewStringAlert = true
            runOnUiThread { stringView.text = STRING_PREFIX + String(value) }
        }

    private var currentStringIdx: Int = 0
    private var chooserIdx: Int = 0

    private lateinit var signStrings: MutableList<String>

    private var keyboardStringBuilder: StringBuilder = StringBuilder()

    /* Local UI */
    private lateinit var localTimeView: TextView
    private lateinit var stringView: TextView
    private lateinit var logList: ListView
    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var advertiserStatusView: TextView

    private lateinit var mtuStatusView: TextView

    private lateinit var deviceStatusView: TextView

    private val logStrings: ArrayList<String> = arrayListOf()

    /* Bluetooth API */
    private lateinit var bluetoothManager: BluetoothManager

    private var bluetoothGattServer: BluetoothGattServer? = null
    /* Collection of notification subscribers */
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    private lateinit var uartDevice: UartDevice

    /**
     * When
     * new string alert -> display new string alert and write next string from list
     * else -> write next string from list
     */
    private val uartCallback: UartDeviceCallback = object : UartDeviceCallback {
        override fun onUartDeviceDataAvailable(uart: UartDevice): Boolean {
            logD("Data available on ${uart.name}! Reading...")
            // Read available data from the UART device
            try {
                val bytes = ByteArray(ARDUINO_STRING_LEN)
                val count = uart.read(bytes, bytes.size)
                logD("Read $count bytes from ${uart.name}: [${String(bytes)}]")
            } catch (e: IOException) {
                logW("Unable to read from UART device ${uart.name}: $e")
            }

//            if (shouldShowNewStringAlert) {
//                currentString = NEW_MSG_ALERT
//                shouldShowNewStringAlert = false
//                //TODO may need to send the alert with the string for display nicety rather than instead of the string
//            } else {
            //TODO maybe enqueue strings to be sent instead of deriving them here

            val keyboardInputTimeElapsed = System.currentTimeMillis() - keyboardInputStartedAtMs
            if (keyboardInputTimeElapsed > KEYBOARD_INPUT_TIMEOUT_MS) {
                keyboardStringBuilder.clear()
            }

            currentString = when {
                keyboardInputTimeElapsed < KEYBOARD_INPUT_TIMEOUT_MS -> {
                    if (keyboardInputTimeElapsed > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS)) {
                        // Running out of input time! Display this in input warning mode.
                        byteArrayOf(EOT) + keyboardStringBuilder.toString().plus('_').toByteArray()
                    } else {
                        byteArrayOf(ENQ) + keyboardStringBuilder.toString().plus('_').toByteArray()
                    }
                }
                signStrings.isEmpty() -> byteArrayOf()
                shouldShowNewStringAlert -> {
                    shouldShowNewStringAlert = false
                    logD("The next string will show the new string alert!")
                    byteArrayOf(BEL) + signStrings[currentStringIdx].toByteArray()
                }
                isChooserModeEnabled -> {
                    logD("The next string will show as chooser!")
                    byteArrayOf(SOH) + "$chooserIdx/${signStrings.lastIndex}".toByteArray() +
                            byteArrayOf(DLE) + signStrings[chooserIdx].toByteArray()
                }
                sendMicChange -> {
                    sendMicChange = false
                    if (isMicOn) {
                        logD("Enabling beat detector microphone!")
                        byteArrayOf(STX) + signStrings[currentStringIdx].toByteArray()
                    } else {
                        logD("Disabling beat detector microphone!")
                        byteArrayOf(ETX) + signStrings[currentStringIdx].toByteArray()
                    }
                }
                else -> {
                    logD("Reading signStrings[$currentStringIdx]")
                    signStrings[currentStringIdx].toByteArray()
                }
            }
            logD("Writing >${String(currentString)}< to ${uart.name}...")

            uartDevice.write(currentString)

            if (!isPaused) { //if not paused, advance index
                currentStringIdx = if (currentStringIdx < signStrings.lastIndex) currentStringIdx + 1 else 0
            }
//            }

            // Continue listening for more interrupts
            return true
        }

        override fun onUartDeviceError(uart: UartDevice, error: Int) {
            logW("$uart: Error event $error")
        }
    }

    override fun onStart() {
        super.onStart()
        logD("Uart device $UART_DEVICE_NAME callback registered")
        uartDevice.registerUartDeviceCallback(uartCallback)
    }

    override fun onStop() {
        super.onStop()
        logD("Uart device $UART_DEVICE_NAME callback unregistered")
        uartDevice.unregisterUartDeviceCallback(uartCallback)
    }

    /**
     * Listens for Bluetooth adapter events to enable/disable
     * advertising and server functionality.
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            logD("bluetoothReceiver: received intent: $state")
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    logD("bluetoothReceiver: STATE_ON")
                    startAdvertising()
                    startServer()
                }
                BluetoothAdapter.STATE_OFF -> {
                    logD("bluetoothReceiver: STATE_OFF")
                    stopServer()
                    stopAdvertising()
                }
            }
        }
    }

    val BLE_PIN: String = "123456"
    /**
     * Listens for Bluetooth pairing requests and bond state changes
     */
    private val bondStateReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            val bluetoothDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val type = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            // type appears to be 3 which is [PAIRING_VARIANT_CONSENT]

            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    logD("Auto-entering pin: $BLE_PIN...")
                    val pinHasBeenSet = bluetoothDevice.setPin(BLE_PIN.toByteArray())
//                    abortBroadcast()
//                    val bondingWillBegin = bluetoothDevice.createBond()
//                    logD("Starting bond=$bondingWillBegin")
                    logD("PIN entered result: $pinHasBeenSet")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    when (bluetoothDevice.bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            logD("Bonding with remote device ${bluetoothDevice.address}...")
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            logD("BONDED with remote device ${bluetoothDevice.address}!")
                        }
                        BluetoothDevice.BOND_NONE -> {
                            logD("Remote device ${bluetoothDevice.address} is no longer bonded.")
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    // MAC address
                    logD("Bluetooth device found!")
                    logD("Device Name: >${device.name}<")
                    logD("deviceHardwareAddress >${device.address}<")
                }

            }
        }
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logD("LE Advertise Started! Name: ${bluetoothManager.adapter.name}")
            advertiserStatusView.text = "💚${bluetoothManager.adapter.name}" // 🏠${bluetoothManager.adapter.address}"
        }

        override fun onStartFailure(errorCode: Int) {
            logW("LE Advertise Failed: $errorCode")
            val error = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                    "ADVERTISE_FAILED_ALREADY_STARTED\n" +
                            "Failed to start advertising as the advertising is already started."
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "ADVERTISE_FAILED_DATA_TOO_LARGE\n" +
                            "Failed to start advertising as the advertise data to be broadcasted is larger than 31 bytes."
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "ADVERTISE_FAILED_FEATURE_UNSUPPORTED\n" +
                            "This feature is not supported on this platform."
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS\n" +
                            "Failed to start advertising because no advertising instance is available."
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                    "ADVERTISE_FAILED_INTERNAL_ERROR\n" +
                            "Operation failed due to an internal error."
                else -> "Unknown Advertise Error, error code not part of the AdvertiseCallback enum "
            }
            logW(error)
            advertiserStatusView.text = "💔$error"
        }
    }

    private fun updateMtuView(mtu: Int) {
        logD("MTU Changed! New MTU: $mtu")
        runOnUiThread {
            mtuStatusView.text = "Ⓜ️$mtu"
        }
    }


    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            updateMtuView(mtu)
        }

        override fun onConnectionStateChange(bluetoothDevice: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                STATE_CONNECTED -> {
                    logD("BluetoothDevice CONNECTED: $bluetoothDevice")
                    bluetoothDevice
                            .connectGatt(this@GattServerActivity, true, object : BluetoothGattCallback() {
                                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                                    super.onMtuChanged(gatt, mtu, status)
                                    updateMtuView(mtu)
                                }

                                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                                    super.onConnectionStateChange(gatt, status, newState)
                                    when (newState) {
                                        STATE_CONNECTED -> {
                                            logD("Connected to remote device ${gatt?.device?.name
                                                    ?: ""}, requesting MTU=512...")
                                            gatt?.requestMtu(512)
                                        }
                                    }
                                }
                            })

                    runOnUiThread {
                        deviceStatusView.text = "📲${bluetoothDevice.name ?: ""}$bluetoothDevice"
                        mtuStatusView.text = "Ⓜ️20?"
                    }
                }
                STATE_DISCONNECTED -> {
                    logD("BluetoothDevice DISCONNECTED: $bluetoothDevice")
                    //Remove bluetoothDevice from any active subscriptions
                    registeredDevices.remove(bluetoothDevice)
                    runOnUiThread {
                        deviceStatusView.text = "📴"
                        mtuStatusView.text = ""
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?,
                                                  requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean,
                                                  responseNeeded: Boolean,
                                                  offset: Int,
                                                  value: ByteArray?) {
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
                NORDIC_UART_TX_UUID -> {
                    logD("Uart TX characteristic write: [${String(value!!)}]")
                    if (String(value).startsWith("!")) { //TODO workaround for services getting jumbled up, just use one service for now
                        processUartCommand(value)
                    } else {
                        processNewReceivedString(value)
                    }
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            "Uart Write response".toByteArray())
                }
                else -> {
                    // Invalid characteristic
                    logW("Invalid Characteristic Write: " + characteristic.uuid)
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null)
                }
            }

        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                CHARACTERISTIC_READER_UUID -> {
                    logD("Read String")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            currentString)
                }
                CHARACTERISTIC_INTERACTOR_UUID -> {
                    logD("Read Interactor String???")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            currentString)
                }
                NORDIC_UART_RX_UUID -> {
                    logD("Uart RX characteristic read")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            signStrings[currentStringIdx].toByteArray())
                }
                else -> {
                    // Invalid characteristic
                    logW("Invalid Characteristic Read: " + characteristic.uuid)
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null)
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
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

                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            returnValue)
                }
//                USER_DESCRIPTION_DESCRIPTOR -> bluetoothGattServer?.sendResponse(device,
//                        requestId,
//                        BluetoothGatt.GATT_SUCCESS,
//                        0,
//                        descriptor.value)
                else -> {
                    logW("Unknown descriptor read request")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice,
                                              requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean,
                                              responseNeeded: Boolean,
                                              offset: Int,
                                              value: ByteArray) {
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    logD("Subscribe device to notifications: $device")
                    registeredDevices.add(device)
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    logD("Unsubscribe device from notifications: $device")
                    registeredDevices.remove(device)
                }

                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0, value) //TODO Right response here?
                }
            } else {
                logW("Unknown descriptor write request")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0, null)
                }
            }
        }
    }

    private fun processUartCommand(value: ByteArray) {
        logD("Procesing Uart command ${String(value)}")
        when (val cmd = String(value)) {
            "!choose" -> {
                chooserIdx = currentStringIdx
                isChooserModeEnabled = true
            }
            "!prev" -> {
                chooserIdx = if (chooserIdx > 0) chooserIdx - 1 else 0
                currentStringIdx = if (currentStringIdx > 0) currentStringIdx - 1 else 0
            }
            "!next" -> {
                chooserIdx = if (chooserIdx < signStrings.lastIndex) chooserIdx + 1 else signStrings.lastIndex
                currentStringIdx = if (currentStringIdx < signStrings.lastIndex) currentStringIdx + 1 else signStrings.lastIndex
            }
            "!first" -> {
                chooserIdx = 0
                currentStringIdx = chooserIdx
            }
            "!last" -> {
                chooserIdx = signStrings.lastIndex
                currentStringIdx = chooserIdx
            }
            "!delete" -> {
                signStrings.removeAt(chooserIdx)
                saveStrings(signStrings)
                //clamp //TODO
                chooserIdx = if (chooserIdx >= signStrings.lastIndex) signStrings.lastIndex else chooserIdx
            }
            "!endchoose" -> {
                currentStringIdx = chooserIdx
                isChooserModeEnabled = false
            }
            "!pause" -> {
                isPaused = true
            }
            "!unpause" -> {
                isPaused = false
            }
            "!micOn" -> {
                isMicOn = true
                sendMicChange = true
            }
            "!micOff" -> {
                isMicOn = false
                sendMicChange = true
            }
        }
    }

    private fun processNewReceivedString(value: ByteArray) {
        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
            pushStringOnList(it)
        }

        saveStrings(signStrings)
        shouldShowNewStringAlert = true
        currentStringIdx = 0
    }

    /** Pushes a string onto the top of the signStrings list. */
    private fun pushStringOnList(value: String) {
        signStrings.add(0, value)
    }

    /** Return the
     * //TODO last [MAX_SIGN_STRINGS]
     * strings from the sign strings file. */
    private fun loadStrings(): MutableList<String> =
            File(filesDir, SIGN_STRINGS_FILE_NAME).run {
                when (createNewFile()) {
                    true -> logD("$SIGN_STRINGS_FILE_NAME does not exist; created new.")
                    else -> logD("$SIGN_STRINGS_FILE_NAME exists. Reading...")
                }

                bufferedReader().use {
                    it.lineSequence()
//                            .take(MAX_SIGN_STRINGS)
                            .toMutableList().asReversed() //TODO check if asreversed is doing the right thing here
                }.also {
                    logD("Read ${it.size} lines from $SIGN_STRINGS_FILE_NAME! Here are the first 10: [${it.take(10).joinToString(", ")}]")
                }
            }

    /** Write out the list of strings to the file */
    private fun saveStrings(strings: List<String>) {
        try {
            strings.reversed().toFile(File(filesDir, SIGN_STRINGS_FILE_NAME))
        } catch (e: Throwable) {
            logW("Exception when saving $SIGN_STRINGS_FILE_NAME! ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)
        Kotpref.init(applicationContext)

        timeFormat = SimpleDateFormat("HH:mm:ss")
        dateFormat = DateFormat.getMediumDateFormat(this)

        localTimeView = findViewById(R.id.text_time)
        stringView = findViewById(R.id.current_string)
        advertiserStatusView = findViewById(R.id.advertiser_status)
        mtuStatusView = findViewById(R.id.mtu_status)
        deviceStatusView = findViewById(R.id.device_status)
        logList = findViewById(R.id.log_list)

        logAdapter = ArrayAdapter(this, R.layout.item_log, logStrings)
        logList.adapter = logAdapter

        signStrings = loadStrings()

        currentString = when {
            signStrings.isNotEmpty() -> signStrings.first()
            else -> "TRONCE"
        }.toByteArray()

        // create Uart device

        // Devices with a display should not go to sleep
//        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        // We can't continue without proper Bluetooth support
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            logD("No bluetooth support, exiting")
            fatal()
        }

        // UART
        uartDevice = configureUart() ?: fatal()
        logD("UART device $UART_DEVICE_NAME opened and configured!")

        // Register for system Bluetooth events
        registerReceiver(bluetoothReceiver, IntentFilter(ACTION_STATE_CHANGED))

        registerReceiver(bondStateReceiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_FOUND) //when new devices are discovered
                    priority = IntentFilter.SYSTEM_HIGH_PRIORITY
                })

        if (!bluetoothAdapter.isEnabled) {
            logD("Bluetooth is currently disabled...enabling")
            bluetoothAdapter.enable()
        } else {
            logD("Bluetooth enabled...starting services")
            startAdvertising()
            startServer()
        }

//        val profileManager = BluetoothProfileManager.getInstance()
//        val enabledProfiles = profileManager.enabledProfiles
//        bluetoothAdapter.startDiscovery()
    }

    private fun fatal(): Nothing {
        finish()
        throw RuntimeException("Fatal")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key: Char = event.keyCharacterMap.get(event.keyCode, event.metaState).toChar()
        if (event.action == ACTION_DOWN) {
            logD("Keypress! ${key}")
            when {
                event.keyCode == KeyEvent.KEYCODE_ENTER -> submitKeyboardInput()
                event.keyCode == KeyEvent.KEYCODE_DEL -> deleteKey()
                event.keyCode == KeyEvent.KEYCODE_SPACE -> processNewKeyboardKey(' ')
                event.isPrintingKey -> processNewKeyboardKey(key)
                else -> return true // super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    private fun processNewKeyboardKey(key: Char) {
        keyboardInputStartedAtMs = System.currentTimeMillis()
        keyboardStringBuilder.append(key)
    }

    private fun deleteKey() {
        if (keyboardStringBuilder.isEmpty()) {
            keyboardInputStartedAtMs = -1
        } else {
            keyboardStringBuilder.deleteCharAt(keyboardStringBuilder.lastIndex)
        }
    }

    private fun submitKeyboardInput() {
        keyboardInputStartedAtMs = -1
        if (keyboardStringBuilder.isNotBlank()) {
            processNewReceivedString(keyboardStringBuilder.toString().toByteArray())
            keyboardStringBuilder.clear()
        }
    }

    fun configureUart(): UartDevice? {
        try {
            val manager = PeripheralManager.getInstance()
            val deviceList = manager.uartDeviceList
            if (deviceList.isEmpty()) {
                logD("No UART port available on this device.")
            } else {
                logD("List of available UART devices: $deviceList")
            }
            logD("Opening UART device $UART_DEVICE_NAME")
            return manager.openUartDevice(UART_DEVICE_NAME).apply {
                // Configure the UART port
                logD("Configuring UART device $UART_DEVICE_NAME")
                setBaudrate(UART_BAUD_RATE)
                setDataSize(8)
                setParity(UartDevice.PARITY_NONE)
                setStopBits(1)
            }
        } catch (e: IOException) {
            logW("Unable to open/configure UART device: $e")
        }
        return null
    }

    fun UartDevice.write(buffer: ByteArray) {
        try {
            val finalBuffer = buffer + '\r'.toByte()
            val count = write(finalBuffer, finalBuffer.size)
            logD("Wrote $count bytes to $name")
        } catch (e: Exception) {
            logW("Unable to write to UART device $name: $e")
        }
    }

    private fun logD(s: String) {
        Log.d(TAG, s)
        addToLog(s)
    }

    private fun logW(s: String) {
        Log.w(TAG, s)
        addToLog("🚨W🚨 $s")
    }

    private fun addToLog(s: String) {
        //TODO do this on an interval, not whenever there's a log line, but this is fine for now
        val time: Long = System.currentTimeMillis()
        runOnUiThread {
            updateTimeView(time)
            val timestamp = timeFormat.format(Date(time))

            if (logAdapter.count > 101) {
                logAdapter.remove(logAdapter.getItem(0))
            }
            logAdapter.add("$timestamp $s")
            logList.smoothScrollToPosition(logAdapter.count)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter.isEnabled) {
            stopServer()
            stopAdvertising()
        }

        try {
            uartDevice.close()
        } catch (e: IOException) {
            Log.w(TAG, "Unable to close UART device", e)
        }

        unregisterReceiver(bluetoothReceiver)
    }

    /**
     * Verify the level of Bluetooth support provided by the hardware.
     * @param bluetoothAdapter System [BluetoothAdapter].
     * @return true if Bluetooth is properly supported, false otherwise.
     */
    private fun checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?): Boolean {

        if (bluetoothAdapter == null) {
            logW("Bluetooth is not supported")
            return false
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            logW("Bluetooth LE is not supported")
            return false
        }

        return true
    }

    /**
     * Begin advertising over Bluetooth that this device is connectable
     * and supports the Current Time Service.
     */
    private fun startAdvertising() {
        bluetoothManager.adapter.name = "G!"
        bluetoothManager.adapter.bluetoothLeAdvertiser?.let {
            logD("Bluetooth LE: Start Advertiser")
            val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build()

            val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(NordicUartServiceProfile.NORDIC_UART_SERVICE_UUID))
                    .build()

            it.startAdvertising(settings, data, advertiseCallback)
        } ?: logW("Failed to create advertiser")
    }

    /**
     * Stop Bluetooth advertisements.
     */
    private fun stopAdvertising() {
        bluetoothManager.adapter.bluetoothLeAdvertiser?.let {
            it.stopAdvertising(advertiseCallback)
        } ?: logW("Failed to create advertiser")
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     * from the Time Profile.
     */
    private fun startServer() {
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        bluetoothGattServer?.apply {
            //            addService(StringServiceProfile.createStringService())
            addService(NordicUartServiceProfile.createNordicUartService())
        } ?: logW("Unable to create GATT server")

        // Initialize the local UI
        updateTimeView(System.currentTimeMillis())
    }

    /**
     * Shut down the GATT server.
     */
    private fun stopServer() {
        bluetoothGattServer?.close()
    }

    private lateinit var timeFormat: SimpleDateFormat
    private lateinit var dateFormat: java.text.DateFormat

    /**
     * Update graphical UI on devices that support it with the current time.
     */
    private fun updateTimeView(timeMillis: Long) {
        val date = Date(timeMillis)
        val displayDate = dateFormat.format(date)
        val displayTime = timeFormat.format(date)
        localTimeView.text = "$displayDate 🕰 $displayTime"
    }

    companion object {
        private const val STRING_PREFIX: String = "🔠"
        private const val UART_DEVICE_NAME: String = "UART6"
        private const val UART_BAUD_RATE: Int = 19200
        private const val ARDUINO_STRING_LEN: Int = 512
        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val MAX_SIGN_STRINGS: Int = 1000
        private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
        private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000
        private val NEW_MSG_ALERT: ByteArray = "~NEWMSGALERT".toByteArray()
        private const val BEL: Byte = 7
        private const val SOH: Byte = 1
        private const val STX: Byte = 2
        private const val ETX: Byte = 3
        private const val EOT: Byte = 4
        private const val ENQ: Byte = 5
        private const val DLE: Byte = 10

    }
}
