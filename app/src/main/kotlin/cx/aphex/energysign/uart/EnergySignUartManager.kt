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
import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.SignRequest
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.IOException
import java.lang.reflect.Method
import java.util.concurrent.locks.LockSupport

/**
 * The UART to the sign. It is opened here; from [start] on, the [SignLink] thread is the only
 * one that reads or writes it (see [SignLink] for why this is a thread and not the UART callback).
 *
 * Two switches for measuring on the device, both without a rebuild:
 *  - the timing log: `adb shell setprop log.tag.SignUart VERBOSE` (see [UartTimingLog])
 *  - how a push gets the sign's attention: `adb shell setprop debug.energysign.attn break|zeros|off`,
 *    or empty for the default (read at every push; see [ATTN_PROPERTY])
 */
class EnergySignUartManager(val viewModel: MainViewModel) {
    private val uartDevice: UartDevice = configureUart()

    val receivedBytes: BehaviorSubject<ByteArray> = BehaviorSubject.createDefault(byteArrayOf())
//    val receivedBytes: Observable<ByteArray> =  energySignBluetoothGattServer.receivedBytes

    private val mainHandler = Handler(Looper.getMainLooper())

    private val timing = UartTimingLog(
        isEnabled = { Log.isLoggable(UartTimingLog.TAG, Log.VERBOSE) },
        emit = { line -> Log.d(UartTimingLog.TAG, line) },
        threadCpuNanos = { Debug.threadCpuTimeNanos() },
    )

    /**
     * Set when sendBreak has thrown, or taken far longer than asked: the 0x00 run is used from
     * then on unless the property says `break`. Link thread only.
     */
    private var breakUnusable = false

    /**
     * When the bytes written so far will have left the wire. write() returns before they are
     * sent (it is non-blocking), at 14400 baud a message takes 30 to 70 ms. Link thread only.
     */
    private var wireBusyUntilNs = 0L

    private val wire = object : SignWire {
        override fun read(buffer: ByteArray, length: Int): Int = uartDevice.read(buffer, length)

        /** Returns when the line is HIGH again, in both modes. */
        override fun attention(millis: Int) {
            // Not behind a reply that is still going out: the LOW must start now, and end before the guard
            sleepUntil(wireBusyUntilNs)

            val mode = attentionMode()
            if (mode == ATTN_BREAK || (mode != ATTN_ZEROS && !breakUnusable)) {
                try {
                    val callNs = System.nanoTime()
                    uartDevice.sendBreak(millis)
                    val tookMs = (System.nanoTime() - callNs) / 1_000_000
                    if (tookMs > millis * BREAK_TOO_LONG_FACTOR && !breakUnusable) {
                        breakUnusable = true
                        logW("sendBreak($millis) took $tookMs ms on $UART_DEVICE_NAME, using a run of 0x00 from now on")
                    }
                    return
                } catch (e: IOException) {
                    breakUnusable = true
                    logW("sendBreak($millis) failed on $UART_DEVICE_NAME, using a run of 0x00 from now on: $e")
                }
            }
            // A 0x00 byte is start bit + 8 data bits LOW, then the stop bit HIGH: LOW nine tenths of the time
            val zeros = ByteArray((millis * UART_BAUD_RATE + 9_999) / 10_000)
            val wrote = uartDevice.write(zeros, zeros.size)
            if (wrote != zeros.size) {
                logW("Attention: wrote $wrote of ${zeros.size} zero bytes to $UART_DEVICE_NAME")
            }
            wrote(wrote)
            sleepUntil(wireBusyUntilNs)
        }
    }

    /** [count] more bytes are queued for the wire: ten bit times each. */
    private fun wrote(count: Int) {
        if (count > 0) {
            wireBusyUntilNs = maxOf(wireBusyUntilNs, System.nanoTime()) + count * 10_000_000_000L / UART_BAUD_RATE
        }
    }

    private fun sleepUntil(deadlineNs: Long) {
        while (true) {
            val leftNs = deadlineNs - System.nanoTime()
            if (leftNs <= 0 || Thread.currentThread().isInterrupted) return
            LockSupport.parkNanos(leftNs)
        }
    }

    private val link = SignLink(
        wire = wire,
        onRequest = ::onRequest,
        onPush = { viewModel.onSignPush() },
        timing = timing,
        onError = { what, error -> logW("Sign link: $what failed on $UART_DEVICE_NAME: $error") },
    )

    private var linkThread: Thread? = null

    init {
        logD("UART device $UART_DEVICE_NAME opened and configured!")
    }

    /** Starts the sign link thread. Everything it calls into must exist by now. */
    fun start() {
        linkThread = Thread({
            try {
                // Above the main thread, which draws the on-screen log: a request must not wait for a frame
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (e: Exception) {
                logW("Sign link: could not raise the thread priority: $e")
            }
            link.run()
        }, LINK_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
        logD("Uart device $UART_DEVICE_NAME: sign link thread started")
    }

    fun stop() {
        link.stop()
        try {
            linkThread?.join(STOP_JOIN_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        logD("Uart device $UART_DEVICE_NAME: sign link thread stopped")

        try {
            uartDevice.close()
        } catch (e: IOException) {
            logW("Unable to close UART device", e)
        }
    }

    /**
     * What the chooser should show has changed: have it pushed to the sign (contract VERSION 3).
     * From any thread; returns at once.
     */
    fun requestPush() {
        if (attentionMode() != ATTN_OFF) {
            link.requestPush()
        }
    }

    /**
     * One request from the sign, on the link thread.
     * When
     * new string alert -> display new string alert and write next string from list
     * else -> write next string from list
     */
    private fun onRequest(buffer: ByteArray, count: Int) {
        val nowNs = System.nanoTime()
        if (timing.enabled(nowNs)) {
            // How long would this request have waited on the main thread, where it used to be handled?
            mainHandler.post { timing.mainLag(nowNs, System.nanoTime()) }
        }
        // A chooser poll arrives about five times a second: keep it out of the log.
        if (ChooserProtocol.classifyRequest(buffer, count) != SignRequest.CHOOSER_POLL) {
            logD("Read $count bytes from $UART_DEVICE_NAME: [${String(buffer, 0, count.coerceIn(0, buffer.size))}]")
        }
        viewModel.onSignRequest(buffer, count)
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

    /**
     * Writes [value] to the sign. [log] is off for chooser poll reports (about five a second);
     * a short write is logged even then. Called on the sign link thread only.
     */
    fun write(value: String, log: Boolean = true) {
        if (log) {
            logD("Writing >${value}< to $UART_DEVICE_NAME...")
        }

        val bytes = value.toByteArray()
//        logD("Bytes: >${bytes.asList()}<")
        if (bytes.size > MAX_ARDUINO_JSON_SIZE) {
            logE("JSON string (${bytes.size}) is probably bigger than the maximum size the arduino will accept ($MAX_ARDUINO_JSON_SIZE)! ")
        }

        try {
            val callNs = System.nanoTime()
            val count = uartDevice.write(bytes, bytes.size)
            timing.tx(callNs, System.nanoTime(), count, bytes.size, isPollReport = !log)
            wrote(count)
            if (log || count != bytes.size) {
                logD("Wrote $count of ${bytes.size} bytes to $UART_DEVICE_NAME")
            }
        } catch (e: Exception) {
            logW("Unable to write to UART device $UART_DEVICE_NAME: $e")
        }
    }

    /** [ATTN_PROPERTY] as it is set now; [ATTN_AUTO] when it is unset, unknown or unreadable. */
    private fun attentionMode(): String =
        try {
            (SYSTEM_PROPERTY_GET?.invoke(null, ATTN_PROPERTY, ATTN_AUTO) as? String)
                ?.takeIf { it == ATTN_BREAK || it == ATTN_ZEROS || it == ATTN_OFF }
        } catch (e: Exception) {
            null
        } ?: ATTN_AUTO

    companion object {

        private const val UART_DEVICE_NAME: String = "UART6"
        private const val UART_BAUD_RATE: Int = 14400
        private const val MAX_ARDUINO_JSON_SIZE: Int = 700

        private const val LINK_THREAD_NAME: String = "sign-link"
        private const val STOP_JOIN_MS: Long = 1_000

        /**
         * System property that picks how a push gets the sign's attention:
         *  - unset: `UartDevice.sendBreak(50)`. Its documentation says only "Send a break to the
         *    device", duration in milliseconds: whether the call waits, and whether the platform
         *    really takes milliseconds (the Linux call such an API usually sits on, tcsendbreak,
         *    counts in tenths of a second), is for the `push` timing line to show. If the call
         *    throws, or takes more than [BREAK_TOO_LONG_FACTOR] times what was asked, the 0x00
         *    run is used from then on (until the app restarts).
         *  - `break`: sendBreak always, whatever it did before.
         *  - `zeros`: 50 ms worth of 0x00 bytes instead (the fallback in the contract note).
         *  - `off`: no pushes at all, the app behaves as contract 2.1.
         */
        private const val ATTN_PROPERTY: String = "debug.energysign.attn"
        private const val ATTN_AUTO: String = ""
        private const val BREAK_TOO_LONG_FACTOR: Int = 3
        private const val ATTN_BREAK: String = "break"
        private const val ATTN_ZEROS: String = "zeros"
        private const val ATTN_OFF: String = "off"

        /** `android.os.SystemProperties.get(key, default)`: not in the SDK, present on the device. */
        private val SYSTEM_PROPERTY_GET: Method? =
            try {
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            } catch (e: Exception) {
                null
            }
    }
}
