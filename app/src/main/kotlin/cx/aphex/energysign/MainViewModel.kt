package cx.aphex.energysign

import android.app.Application
import android.os.Looper
import cx.aphex.energysign.beatlinkdata.BeatLinkListener
import androidx.lifecycle.AndroidViewModel
import cx.aphex.energysign.bluetooth.takeWrite
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.ChooserPush
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
    private val warn: (String) -> Unit = { logW(it) }
    private val worker: SignWorker by lazy { SignWorker(warn).also { it.tick(::ensureServices) } }
    private val bluetooth by lazy {
        EnergySignBluetoothManager(application.applicationContext, this, worker, messageManager::takeWrite)
    }
    private val beatLink by lazy { BeatLinkListener(application.applicationContext, worker) }

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

    /** The frame of a PUSH. It can reach the chooser message and nothing else. */
    private val chooserPush = ChooserPush(
        shown = { messageManager.chooserShown() },
        sent = ::published,
    )

    init {
        owner.neverWaitsOn(Looper.getMainLooper().thread)
        // MessageManager is built by its first use, and that must be here, on the main thread
        // (it observes LiveData), with the push wired before the link starts. Commands that
        // arrive before the link thread runs wait in the owner's queue.
        messageManager.onChooserChanged = energySignUartManager::requestPush
        energySignUartManager.start()
    }

    internal fun onActivityStart() {
        optional("Optional service worker", warn) { worker.post(::ensureServices) }
    }

    private fun ensureServices() {
        optional("Bluetooth startup", warn) { bluetooth.ensure() }
        optional("BeatLink startup", warn) { beatLink.ensureListening() }
        energySignUartManager.onForeground()
    }

    override fun onCleared() {
        super.onCleared()
        energySignUartManager.stop()
        // MessageManager outlives this view model: it must not keep the stopped link
        messageManager.onChooserChanged = {}
    }

    /**
     * The sign asked for something: [count] request bytes were read into [buffer].
     * `~` gets the next message (in chooser mode the chooser message).
     * Called on the sign link thread.
     */
    fun onSignRequest(buffer: ByteArray, count: Int) {
        signResponder.onRequest(buffer, count)?.let(::published)
    }

    /** [message] is on the wire in full, as a reply, or as a push to a sign that listens for one: the screen and Bluetooth show it. */
    private fun published(message: Message) {
        sentString = message.str
        currentString.postValue(message.str)
    }

    /**
     * The frame of a push (contract VERSION 3.1): the chooser message as it is now, or null
     * outside chooser mode, when a push is the attention alone. It goes through ChooserPush,
     * which can only reach MessageManager.chooserShown, and not through the responder: a push
     * cannot take a message. Called on the sign link thread.
     */
    fun chooserFrame(): String? = chooserPush.frame()

    /** The frame of the push is on the wire, whole and in time, at a sign known to be in its chooser display (SignLink decides). Called on the sign link thread. */
    fun onChooserPushed() = chooserPush.written()

    fun updateAdvertiseStatus(status: String) {
        btAdvertiseStatus.postValue(status)
    }

    fun onBtStatusUpdate(bluetoothStatusUpdate: BluetoothStatusUpdate) {
        btDeviceStatus.postValue(bluetoothStatusUpdate)
    }
}
