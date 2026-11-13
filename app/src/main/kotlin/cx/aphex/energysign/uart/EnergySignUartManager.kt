package cx.aphex.energysign.uart

import com.google.android.things.pio.PeripheralManager
import com.google.android.things.pio.UartDevice
import com.google.android.things.pio.UartDeviceCallback
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.ChooserProtocol
import cx.aphex.energysign.message.SignRequest
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.IOException

class EnergySignUartManager(val viewModel: MainViewModel) : UartDeviceCallback {
    private val uartDevice: UartDevice

    val receivedBytes: BehaviorSubject<ByteArray> = BehaviorSubject.createDefault(byteArrayOf())
//    val receivedBytes: Observable<ByteArray> =  energySignBluetoothGattServer.receivedBytes

    init {
        // UART
        uartDevice = configureUart()
        logD("UART device $UART_DEVICE_NAME opened and configured!")

        logD("Uart device $UART_DEVICE_NAME callback registered")
        uartDevice.registerUartDeviceCallback(this)
    }

    fun stop() {
        logD("Uart device $UART_DEVICE_NAME callback unregistered")
        uartDevice.unregisterUartDeviceCallback(this)

        try {
            uartDevice.close()
        } catch (e: IOException) {
            logW("Unable to close UART device", e)
        }
    }

    /**
     * When
     * new string alert -> display new string alert and write next string from list
     * else -> write next string from list
     */
    override fun onUartDeviceDataAvailable(uart: UartDevice): Boolean {
        // Read available data from the UART device
        try {
            val bytes = ByteArray(ARDUINO_RECEVIED_STRING_LEN)
            val count = uart.read(bytes, bytes.size)
            // A chooser poll arrives about five times a second: keep it out of the log.
            if (ChooserProtocol.classifyRequest(bytes, count) != SignRequest.CHOOSER_POLL) {
                logD("Read $count bytes from ${uart.name}: [${String(bytes, 0, count.coerceIn(0, bytes.size))}]")
            }
            viewModel.onSignRequest(bytes, count)
        } catch (e: IOException) {
            logW("Unable to read from UART device ${uart.name}: $e")
        }

        // Continue listening for more interrupts
        return true
    }

    override fun onUartDeviceError(uart: UartDevice, error: Int) {
        logW("$uart: Error event $error")
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

    /** A short write is logged even when [log] is off. */
    private fun UartDevice.write(buffer: ByteArray, log: Boolean) {
        try {
            if (buffer.size > MAX_ARDUINO_JSON_SIZE) {
                logE("JSON string (${buffer.size}) is probably bigger than the maximum size the arduino will accept ($MAX_ARDUINO_JSON_SIZE)! ")
            }

//            val finalBuffer = buffer + '\r'.toByte()
//            val count = write(finalBuffer, finalBuffer.size)
            val count = write(buffer, buffer.size)
            if (log || count != buffer.size) {
                logD("Wrote $count of ${buffer.size} bytes to $name")
            }
        } catch (e: Exception) {
            logW("Unable to write to UART device $name: $e")
        }
    }

    /** Writes [value] to the sign. [log] is off for chooser poll reports (about five a second). */
    fun write(value: String, log: Boolean = true) {
        if (log) {
            logD("Writing >${value}< to ${uartDevice.name}...")
        }

        val bytes = value.toByteArray()
//        logD("Bytes: >${bytes.asList()}<")

        uartDevice.write(bytes, log)
    }

    companion object {

        private const val UART_DEVICE_NAME: String = "UART6"
        private const val UART_BAUD_RATE: Int = 14400
        private const val ARDUINO_RECEVIED_STRING_LEN: Int = 32
        private const val MAX_ARDUINO_JSON_SIZE: Int = 700

    }
}
