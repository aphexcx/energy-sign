package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.ParcelUuid
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.OptionalStart
import cx.aphex.energysign.optional
import cx.aphex.energysign.StartWorker
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW

internal class EnergySignBluetoothManager(
    val context: Context,
    val viewModel: MainViewModel,
    private val worker: StartWorker,
    private val takeWrite: (ByteArray, Boolean) -> Boolean,
) {

    private val warn: (String) -> Unit = { logW(it) }

    private fun post(action: () -> Unit) = worker.post(action)
    private var server: EnergySignBluetoothGattServer? = null
    private var cancelAdvertising: (() -> Unit)? = null

    // HOME can start while Android is still bringing Bluetooth up. Install the listeners
    // first, then look up the service again on each adapter event that needs it.
    private lateinit var bluetoothManager: BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter
        get() = checkNotNull(bluetoothManager.adapter)

    private fun registerService(added: (Int) -> Unit): Boolean {
        // A callback belongs to this server alone, even if Android delivers it after close().
        val attempt = EnergySignBluetoothGattServer(context, bluetoothManager, viewModel, worker, takeWrite) { status ->
            post { added(status) }
        }
        server = attempt
        return attempt.start()
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
                    post { publication.radioOn() }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    logD("bluetoothReceiver: STATE_OFF")
                    post { publication.radioOff() }
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
            post { handlePairing(intent) }
        }

        private fun handlePairing(intent: Intent) {
            optional("Bluetooth pairing broadcast", warn) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return@optional
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        check(device.setPin(BLE_PIN.toByteArray()))
                        logD("Pairing PIN submitted")
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> server?.bondState(device, device.bondState)
                    BluetoothDevice.ACTION_FOUND -> logD("Bluetooth device found: ${device.name.orEmpty()}")
                }
            }
        }
    }

    private val publication: BluetoothPublication = BluetoothPublication(object : Radio {
        override fun adapter(): BluetoothAdapter {
            bluetoothManager = managerForGatt(context)
            return bluetoothAdapter
        }
        override val isOn get() = bluetoothAdapter.isEnabled
        override fun enable() = bluetoothAdapter.enable()
        override fun openGatt(added: (Int) -> Unit) = registerService(added)
        override fun advertise(named: Boolean, result: (Int) -> Unit) = startAdvertising(named, result)
        override fun close() = stopServices()
    }, worker, warn, ::showAdvertiseResult)

    private fun receiver(name: String, receiver: BroadcastReceiver, filter: () -> IntentFilter) =
        OptionalStart(name, worker, warn) { attempt ->
            context.registerReceiver(receiver, filter())
            if (!attempt.done()) context.unregisterReceiver(receiver)
        }

    private val adapterEvents = receiver("Bluetooth adapter receiver", bluetoothReceiver) {
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    }
    private val pairingEvents = receiver("Bluetooth pairing receiver", bondStateReceiver) {
        IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
    }

    fun ensure() {
        adapterEvents.ensure()
        if (!adapterEvents.up) return
        pairingEvents.ensure()
        publication.ensure()
        server?.retryPeerSetup()
    }


    private fun stopServices() {
        val cancel = cancelAdvertising
        cancelAdvertising = null
        val oldServer = server
        server = null
        optional("Bluetooth advertiser cleanup", warn) { cancel?.invoke() }
        optional("Bluetooth GATT cleanup", warn) { oldServer?.stop() }
        optional("Bluetooth status display", warn) { viewModel.updateAdvertiseStatus("📴") }
    }

    private fun showAdvertiseResult(errorCode: Int) {
        if (errorCode == 0) {
            logD("Nordic UART advertising started")
            viewModel.updateAdvertiseStatus("💚${bluetoothAdapter.name}")
        } else {
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
            viewModel.updateAdvertiseStatus("💔$error")
        }
    }

    private fun startAdvertising(includeName: Boolean, result: (Int) -> Unit) {
        // The requested display name is optional; the UART service is identified by its UUID.
        if (includeName) optional("Bluetooth advertised name", warn) { check(bluetoothAdapter.setName("G!")) }
        val advertiser = checkNotNull(bluetoothAdapter.bluetoothLeAdvertiser) { "Bluetooth advertiser unavailable" }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                post { result(0) }
            }

            override fun onStartFailure(errorCode: Int) {
                post { result(errorCode) }
            }
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(NordicUartServiceProfile.NORDIC_UART_SERVICE_UUID))
            .build()

        cancelAdvertising = { advertiser.stopAdvertising(callback) }
        advertiser.startAdvertising(settings, data, callback)
    }
}

// Android caches BluetoothManager (including its initial adapter) per Context. A lookup
// during boot can return a wrapper with a permanently null adapter in the application cache.
internal fun managerForGatt(context: Context): BluetoothManager {
    val fresh = context.createConfigurationContext(Configuration())
    return checkNotNull(fresh.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
}
