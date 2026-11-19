package cx.aphex.energysign

import android.app.Application
import android.os.Looper
import cx.aphex.energysign.beatlinkdata.BeatLinkListener
import androidx.lifecycle.AndroidViewModel
import cx.aphex.energysign.bluetooth.BluetoothStatusUpdate
import cx.aphex.energysign.bluetooth.EnergySignBluetoothManager
import cx.aphex.energysign.ext.NonNullMutableLiveData
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
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
    private val startup = SignStartup { logW(it) }
    private val bluetooth = BluetoothStartup(startup,
        create = { EnergySignBluetoothManager(application.applicationContext, this) },
        subscribe = { it.subscribeToWrites(messageManager) },
        startManager = EnergySignBluetoothManager::start,
        stopManager = EnergySignBluetoothManager::stop,
    )
    private val worker by lazy { SignWorker { logW(it) } }
    private val beatLink = lazy { BeatLinkListener(application.applicationContext, worker) }
    private var firstForeground = true
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

    init {
        startup.start(
            prepareMessages = {
                // Resolve MessageManager on the main thread, before commands or UART requests arrive.
                owner.neverWaitsOn(Looper.getMainLooper().thread)
                checkNotNull(messageManager)
            },
            startBluetooth = bluetooth::start,
            startBeatLink = { worker.post { beatLink.value.ensureListening() } },
            startLink = energySignUartManager::start,
        )
    }

    internal fun onActivityStart() {
        if (firstForeground) {
            firstForeground = false // init already attempted setup for this launch.
        } else {
            energySignUartManager.onForeground()
            startup.optional("BeatLink startup") { worker.post { beatLink.value.ensureListening() } }
            val manager = bluetooth.manager
            if (manager == null) bluetooth.start() else startup.optional("Bluetooth foreground") { manager.onForeground() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Who brings changes stops first, then who carries them out
        try {
            bluetooth.manager?.stop()
        } finally {
            energySignUartManager.stop()
        }
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
