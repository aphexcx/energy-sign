package cx.aphex.energysign

import android.app.Application
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import cx.aphex.energysign.beatlinkdata.DnsSdAnnouncer
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.bluetooth.deliverEachWrite
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.OwnerQueue
import cx.aphex.energysign.message.SignResponder
import cx.aphex.energysign.uart.EnergySignUartManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainViewModel(application: Application) : AndroidViewModel(application), KoinComponent {
    val currentString: NonNullMutableLiveData<String> = NonNullMutableLiveData("")

    /** The text of the last message written to the sign, as soon as it is written (Bluetooth reads it from its own thread). */
    @Volatile
    var sentString: String = ""
        private set

    val btAdvertiseStatus: NonNullMutableLiveData<String> = NonNullMutableLiveData("")
    val btDeviceStatus: NonNullMutableLiveData<BluetoothStatusUpdate> =
        NonNullMutableLiveData(BluetoothStatusUpdate(null, null))

    private val owner: OwnerQueue by inject()
    private val messageManager: MessageManager by inject()
    private val energySignBluetoothManager: EnergySignBluetoothManager
    private val energySignUartManager: EnergySignUartManager = EnergySignUartManager(this, owner)

    /** The answer to a REQUEST of the sign, and the only way to MessageManager.getNextMessage. */
    private val signResponder = SignResponder(
        nextMessage = { messageManager.getNextMessage() },
        write = { frame, reply -> energySignUartManager.send(frame, reply) },
        unsent = { message -> messageManager.returnUnsent(message) },
        tooLong = { message, bytes ->
            logE("Not sent: a message of $bytes bytes is more than the sign can take (${ChooserProtocol.MAX_FRAME_BYTES}): ${message.str.take(40)}...")
        },
    )
    private val dnsSdAnnouncer: DnsSdAnnouncer

    init {
//        currentBytes.value = messageManager.getNextMessage().bytes

        // First, before anything can deliver a command: MessageManager is built by its first
        // use, and that must be here on the main thread (it observes LiveData). Commands that
        // arrive before the link thread runs wait in the owner's queue.
        owner.neverWaitsOn(Looper.getMainLooper().thread)
        checkNotNull(messageManager)

        energySignBluetoothManager = EnergySignBluetoothManager(application.applicationContext, this)
        energySignBluetoothManager.start()

        energySignBluetoothManager.receivedBytes.deliverEachWrite(messageManager::processNewBytes)

        dnsSdAnnouncer = DnsSdAnnouncer(application.applicationContext)
        dnsSdAnnouncer.start()

        // From here on the sign link thread owns the message state and asks it for messages.
        energySignUartManager.start()
    }

    override fun onCleared() {
        super.onCleared()
        // Who brings changes stops first, then who carries them out
        energySignBluetoothManager.stop()

        energySignUartManager.stop()
    }

    /**
     * The sign asked for something: [count] request bytes were read into [buffer].
     * `~` gets the next message; a chooser poll (`?`) in chooser mode gets the checksum of the
     * current chooser message, which is nothing new for the screen.
     * Called on the sign link thread.
     */
    fun onSignRequest(buffer: ByteArray, count: Int) {
        signResponder.onRequest(buffer, count)?.let(::published)
    }

    /** [message] is on the wire in full: the screen and Bluetooth show it. */
    private fun published(message: Message) {
        sentString = message.str
        currentString.postValue(message.str)
    }

    fun updateAdvertiseStatus(status: String) {
        btAdvertiseStatus.postValue(status)
    }

    fun onBtStatusUpdate(bluetoothStatusUpdate: BluetoothStatusUpdate) {
        btDeviceStatus.postValue(bluetoothStatusUpdate)
    }
}
