package cx.aphex.energysign

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cx.aphex.energysign.beatlinkdata.DnsSdAnnouncer
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.SignResponder
import cx.aphex.energysign.uart.EnergySignUartManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainViewModel(application: Application) : AndroidViewModel(application), KoinComponent {
    val currentString: NonNullMutableLiveData<String> = NonNullMutableLiveData("")

    val btAdvertiseStatus: NonNullMutableLiveData<String> = NonNullMutableLiveData("")
    val btDeviceStatus: NonNullMutableLiveData<BluetoothStatusUpdate> =
        NonNullMutableLiveData(BluetoothStatusUpdate(null, null))

    private val messageManager: MessageManager by inject()
    private val energySignBluetoothManager: EnergySignBluetoothManager
    private val energySignUartManager: EnergySignUartManager = EnergySignUartManager(this)
    private val signResponder = SignResponder(
        nextMessage = { messageManager.getNextMessage() },
        // A chooser poll report goes out about five times a second: keep it out of the log.
        write = { reply, isPollReport -> energySignUartManager.write(reply, log = !isPollReport) },
    )
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

    /**
     * The sign asked for something: [count] request bytes were read into [buffer].
     * `~` gets the next message; a chooser poll (`?`) in chooser mode gets the checksum of the
     * current chooser message, which is nothing new for the screen.
     */
    fun onSignRequest(buffer: ByteArray, count: Int) {
        signResponder.onRequest(buffer, count)?.let { sentMessage ->
            currentString.value = sentMessage.str
        }
    }

    fun updateAdvertiseStatus(status: String) {
        btAdvertiseStatus.postValue(status)
    }

    fun onBtStatusUpdate(bluetoothStatusUpdate: BluetoothStatusUpdate) {
        btDeviceStatus.postValue(bluetoothStatusUpdate)
    }
}
