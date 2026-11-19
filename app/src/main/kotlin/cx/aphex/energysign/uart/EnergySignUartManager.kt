package cx.aphex.energysign.uart

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.google.android.things.pio.PeripheralManager
import com.google.android.things.pio.UartDevice
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.OwnerQueue
import cx.aphex.energysign.message.Reply
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.File
import java.io.IOException
import java.lang.reflect.Method

/**
 * The UART to the sign. It is opened here; from [start] on, the [SignLink] thread is the only
 * one that reads, writes or closes it, and the only one that touches the message state
 * ([owner]). Everything that decides anything is in [SignLink]; this class is the Android
 * around it.
 *
 * Three switches for measuring on the device, without a rebuild:
 *  - the timing log: `adb shell setprop log.tag.SignUart VERBOSE` (see [UartTimingLog])
 *  - whether and how the chooser is pushed: `adb shell setprop debug.energysign.attn off|auto|break|zeros`
 *    (read when the chooser next changes; wins over the saved switch while it is set; see [ATTN_PROPERTY])
 *  - the priority of the link thread: `adb shell setprop debug.energysign.nice -16`
 *    (read when the app starts; see [NICE_PROPERTY])
 */
class EnergySignUartManager(
    private val viewModel: MainViewModel,
    private val owner: OwnerQueue,
    /** The app's files directory: the push switch is kept there. */
    filesDir: File,
    /** Writes a file later, on the saver's thread. */
    saveLater: (write: () -> Unit) -> Unit,
) {
    private val uartDevice: UartDevice = configureUart()

    val receivedBytes: BehaviorSubject<ByteArray> = BehaviorSubject.createDefault(byteArrayOf())
//    val receivedBytes: Observable<ByteArray> =  energySignBluetoothGattServer.receivedBytes

    private val mainHandler = Handler(Looper.getMainLooper())

    private val timing = UartTimingLog(
        isEnabled = { Log.isLoggable(UartTimingLog.TAG, Log.VERBOSE) },
        emit = { line -> Log.d(UartTimingLog.TAG, line) },
        threadCpuNanos = { Debug.threadCpuTimeNanos() },
    )

    private val wire = object : SignWire {
        override fun read(buffer: ByteArray, length: Int): Int = uartDevice.read(buffer, length)
        override fun write(buffer: ByteArray, length: Int): Int = uartDevice.write(buffer, length)
        override fun sendBreak(millis: Int) = uartDevice.sendBreak(millis)
        override fun close() = uartDevice.close()
    }

    /** Whether and how the chooser is pushed. Off unless switched on; says what is in force when it is built. */
    private val attentionSwitch = AttentionSwitch(
        file = File(filesDir, AttentionSwitch.FILE_NAME),
        property = { property(ATTN_PROPERTY) },
        saveLater = saveLater,
        log = { line -> logD(line) },
    )

    private val link = SignLink(
        wire = wire,
        baud = UART_BAUD_RATE,
        name = UART_DEVICE_NAME,
        events = { owner.drain { error -> logW("A change to the messages failed: $error", error) } },
        answer = ::answer,
        chooserFrame = { viewModel.chooserFrame() },
        pushed = { viewModel.onChooserPushed() },
        attention = attentionSwitch::inForce,
        timing = timing,
        log = { line -> logD(line) },
        warn = { line -> logW(line) },
    )

    private var linkThread: Thread? = null

    init {
        logD("UART device $UART_DEVICE_NAME opened and configured!")
    }

    /** Starts the sign link thread. Everything it calls into must exist by now. */
    fun start() {
        val nice = linkNice()
        owner.wake = link::wake
        linkThread = Thread({
            try {
                Process.setThreadPriority(nice)
            } catch (e: Exception) {
                logW("Sign link: could not set the thread priority to $nice: $e")
            }
            // ScreenLogger's date formatter is thread-local. Its first use belongs here,
            // before getNextMessage logs the first selection inside the reply deadline.
            logD("Uart device $UART_DEVICE_NAME: sign link thread started (nice $nice)")
            link.run()
        }, LINK_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the link thread. It closes the port itself, when it is out of whatever it was doing. */
    fun stop() {
        link.stop()
        owner.wake = {}
        try {
            linkThread?.join(STOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (linkThread?.isAlive == true) {
            logW("Uart device $UART_DEVICE_NAME: sign link thread is still finishing; it closes the port when it is done")
        } else {
            logD("Uart device $UART_DEVICE_NAME: sign link thread stopped, port closed")
        }
    }

    /**
     * What the chooser should show has changed: have it pushed to the sign (contract VERSION 3.1),
     * if pushes are switched on. From any thread; returns at once.
     */
    fun requestPush() = link.requestPush()

    /** The `!attn` command: sets and keeps the push switch. On the link thread. */
    fun setAttention(word: String): Boolean = attentionSwitch.set(word)

    /** Writes one reply to the sign. For the answer to a request, on the link thread. */
    fun send(frame: String, reply: Reply): Boolean = link.send(frame, reply)

    /** One request from the sign, on the link thread. */
    private fun answer(buffer: ByteArray, count: Int) {
        viewModel.onSignRequest(buffer, count)
        val nowNs = System.nanoTime()
        if (timing.enabled(nowNs)) {
            // How long would a request have waited on the main thread, where it used to be handled?
            mainHandler.post { timing.mainLag(nowNs, System.nanoTime()) }
        }
    }

    private fun configureUart(): UartDevice {
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
            logE("Unable to open/configure UART device: $e", e)
            throw e
        }
    }

    /** The system property [key] as it is set now; null when it cannot be read. */
    private fun property(key: String): String? =
        try {
            SYSTEM_PROPERTY_GET?.invoke(null, key, "") as? String
        } catch (e: Exception) {
            null
        }

    private fun linkNice(): Int = property(NICE_PROPERTY)?.trim()?.toIntOrNull()?.coerceIn(-19, 0) ?: DEFAULT_NICE

    companion object {

        private const val UART_DEVICE_NAME: String = "UART6"
        private const val UART_BAUD_RATE: Int = 14400

        private const val LINK_THREAD_NAME: String = "sign-link"
        private const val STOP_JOIN_MS: Long = 1_000

        /**
         * System property that, while it holds one of these words, wins over the saved switch
         * (the `!attn` command, [AttentionSwitch]); it is gone after a restart of the board:
         *  - `off`: no pushes at all, the app behaves as contract 2.1. Also the saved default.
         *  - `auto`: what gets this sign's attention, which is `zeros`.
         *  - `zeros`: 50 ms worth of 0x00 bytes. Measured on the sign: seen 11 times of 11.
         *  - `break`: `UartDevice.sendBreak(50)`. On the Pico the call returns after 1 to 6 ms
         *    and the line is not held LOW (seen 0 times of 11); kept for measuring.
         */
        private const val ATTN_PROPERTY: String = "debug.energysign.attn"

        /**
         * System property with the nice value of the link thread, -19 to 0, read at [start].
         * The default is -8 (THREAD_PRIORITY_URGENT_DISPLAY), which the first run on the sign
         * was measured with. The link thread sleeps nine tenths of the time, but when its sleep
         * ends it must get the processor: on the sign a few sleeps a minute ended 40 to 80 ms
         * late (the stat line's sleep_over_max_us shows them). -16 is the value to try.
         */
        private const val NICE_PROPERTY: String = "debug.energysign.nice"
        private const val DEFAULT_NICE: Int = Process.THREAD_PRIORITY_URGENT_DISPLAY

        /** `android.os.SystemProperties.get(key, default)`: not in the SDK, present on the device. */
        private val SYSTEM_PROPERTY_GET: Method? =
            try {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            } catch (e: Exception) {
                null
            }
    }
}
