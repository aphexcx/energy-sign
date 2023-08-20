package cx.aphex.energysign

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import cx.aphex.energysign.beatlinkdata.DnsSdAnnouncer
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.uart.EnergySignUartManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainViewModel(application: Application) : AndroidViewModel(application), KoinComponent {
    private val gson: Gson = Gson()

    val currentString: NonNullMutableLiveData<String> = NonNullMutableLiveData("")

    val btAdvertiseStatus: NonNullMutableLiveData<String> = NonNullMutableLiveData("")
    val btDeviceStatus: NonNullMutableLiveData<BluetoothStatusUpdate> =
        NonNullMutableLiveData(BluetoothStatusUpdate(null, null))

    private val messageManager: MessageManager by inject()
    private val energySignBluetoothManager: EnergySignBluetoothManager
    private val energySignUartManager: EnergySignUartManager = EnergySignUartManager(this)
    private val dnsSdAnnouncer: DnsSdAnnouncer

    init {
//        currentBytes.value = messageManager.getNextMessage().bytes

        energySignBluetoothManager = EnergySignBluetoothManager(application.applicationContext, this)
        energySignBluetoothManager.start()

        energySignBluetoothManager.receivedBytes.subscribe { value ->
            messageManager.processNewBytes(value)
        }

        dnsSdAnnouncer = DnsSdAnnouncer(application.applicationContext)
        dnsSdAnnouncer.start()
    }

    override fun onCleared() {
        super.onCleared()
        energySignUartManager.stop()

        energySignBluetoothManager.stop()
    }

    fun onReadyForNextMessage() {
        val message: Message = messageManager.getNextMessage()
        currentString.value = message.str

        val jsonString = gson.toJson(message)

        energySignUartManager.write(jsonString)
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

    fun escapeKey() {
        messageManager.escapeKey()
    }

    fun processNewKeyboardKey(key: Char) {
        messageManager.processNewKeyboardKey(key)
    }
}
