package cx.aphex.energysign

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.beatlinkdata.DnsSdAnnouncer
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.uart.EnergySignUartManager

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    val currentBytes: NonNullMutableLiveData<ByteArray> = NonNullMutableLiveData(byteArrayOf())

    val btAdvertiseStatus: NonNullMutableLiveData<String> = NonNullMutableLiveData("")
    val btDeviceStatus: NonNullMutableLiveData<BluetoothStatusUpdate> =
        NonNullMutableLiveData(BluetoothStatusUpdate(null, null))

    private val messageManager: MessageManager = MessageManager(context)
    private val energySignBluetoothManager: EnergySignBluetoothManager
    private val energySignUartManager: EnergySignUartManager = EnergySignUartManager(this)
    private val dnsSdAnnouncer: DnsSdAnnouncer

    init {
//        currentBytes.value = messageManager.getNextMessage().bytes

        energySignBluetoothManager = EnergySignBluetoothManager(context, this)
        energySignBluetoothManager.start()

        energySignBluetoothManager.receivedBytes.subscribe { value ->
            messageManager.processNewBytes(value)
        }

        dnsSdAnnouncer = DnsSdAnnouncer(context)
        dnsSdAnnouncer.start()
    }

    override fun onCleared() {
        super.onCleared()
        energySignUartManager.stop()

        energySignBluetoothManager.stop()
    }

    fun onReadyForNextMessage() {
        currentBytes.value = messageManager.getNextMessage().bytes
        energySignUartManager.write(currentBytes.value)
    }

    fun updateAdvertiseStatus(status: String) {
        btAdvertiseStatus.postValue(status)
    }

    fun onBtStatusUpdate(bluetoothStatusUpdate: BluetoothStatusUpdate) {
        btDeviceStatus.postValue(bluetoothStatusUpdate)
    }

    fun submitKeyboardInput() {
        messageManager.submitKeyboardInput()
    }

    fun deleteKey() {
        messageManager.deleteKey()
    }

    fun processNewKeyboardKey(key: Char) {
        messageManager.processNewKeyboardKey(key)
    }

    fun nowPlaying(track: BeatLinkTrack) {
        messageManager.processNowPlayingTrack(track)
    }
}
