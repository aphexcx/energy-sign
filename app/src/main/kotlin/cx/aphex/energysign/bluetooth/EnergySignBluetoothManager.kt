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
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.jakewharton.rxrelay3.PublishRelay
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.MessageManager
import io.reactivex.rxjava3.disposables.Disposable

class EnergySignBluetoothManager(
    val context: Context,
    val viewModel: MainViewModel
) {

    private val startup = SignStartup { logW(it) }
    private val receivers = BluetoothReceivers(context)
    private val mainThread = Handler(Looper.getMainLooper())
    private val writes = PublishRelay.create<GattWrite>()
    private var server: EnergySignBluetoothGattServer? = null
    private var cancelAdvertising: (() -> Unit)? = null

    // HOME can start while Android is still bringing Bluetooth up. Install the listeners
    // first, then look up the service again on each adapter event that needs it.
    private val bluetoothManager: BluetoothManager
        get() = managerForGatt(context)
    private val bluetoothAdapter: BluetoothAdapter
        get() = checkNotNull(bluetoothManager.adapter)

    private var writeSubscription: Disposable? = null

    fun subscribeToWrites(manager: MessageManager) {
        writeSubscription = writes.deliverEachWrite(manager)
    }

    private fun registerService(added: (Int) -> Unit): Boolean {
        // A callback belongs to this server alone, even if Android delivers it after close().
        val attempt = EnergySignBluetoothGattServer(context, bluetoothManager, viewModel, writes) { status ->
            mainThread.post { added(status) }
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
                    session.adapterOn()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    logD("bluetoothReceiver: STATE_OFF")
                    session.adapterOff()
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

            val bluetoothDevice: BluetoothDevice =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val type =
                intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
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
                    // MAC address
                    logD("Bluetooth device found!")
                    logD("Device Name: >${bluetoothDevice.name}<")
                    logD("deviceHardwareAddress >${bluetoothDevice.address}<")
                }
            }
        }
    }

    private val session: BluetoothSession = BluetoothSession(
        startup = startup,
        register = {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                throw UnsupportedOperationException("Bluetooth LE unavailable")
            }
            receivers.register(bluetoothReceiver, bondStateReceiver)
        },
        unregister = receivers::clear,
        enabled = { bluetoothAdapter.isEnabled },
        enable = { bluetoothAdapter.enable() },
        openGatt = ::registerService,
        advertise = ::startAdvertising,
        closeServices = ::stopServices,
        showAdvertiseResult = ::showAdvertiseResult,
    )

    fun start() {
        try {
            session.start()
        } catch (failure: Throwable) {
            detachWrites()
            throw failure
        }
    }

    fun stop() {
        detachWrites()
        session.stop()
    }

    private fun detachWrites() {
        writeSubscription?.dispose()
        writeSubscription = null
    }

    private fun stopServices() {
        val cancel = cancelAdvertising
        cancelAdvertising = null
        val oldServer = server
        server = null
        startup.optional("Bluetooth advertiser cleanup") { cancel?.invoke() }
        startup.optional("Bluetooth GATT cleanup") { oldServer?.stop() }
        startup.optional("Bluetooth status display") { viewModel.updateAdvertiseStatus("📴") }
    }

    private fun showAdvertiseResult(errorCode: Int) {
        if (errorCode == 0) {
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

    private fun startAdvertising(result: (Int) -> Unit) {
        // The requested display name is optional; the UART service is identified by its UUID.
        startup.optional("Bluetooth advertised name") { check(bluetoothAdapter.setName("G!")) }
        val advertiser = checkNotNull(bluetoothAdapter.bluetoothLeAdvertiser) { "Bluetooth advertiser unavailable" }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                mainThread.post { result(0) }
            }

            override fun onStartFailure(errorCode: Int) {
                mainThread.post { result(errorCode) }
            }
        }
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

        cancelAdvertising = { advertiser.stopAdvertising(callback) }
        advertiser.startAdvertising(settings, data, callback)
    }
}

internal fun managerForGatt(context: Context, hasAdapter: (BluetoothManager) -> Boolean = { it.adapter != null }): BluetoothManager =
    checkNotNull(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
