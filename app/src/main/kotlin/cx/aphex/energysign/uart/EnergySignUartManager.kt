package cx.aphex.energysign.uart

import com.google.android.things.pio.PeripheralManager
import com.google.android.things.pio.UartDevice
import com.google.android.things.pio.UartDeviceCallback
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.io.IOException

class EnergySignUartManager(val viewModel: MainViewModel) : UartDeviceCallback {
    private val uartDevice: UartDevice

    val receivedBytes: BehaviorSubject<ByteArray> = BehaviorSubject.createDefault(byteArrayOf())
//    val receivedBytes: Observable<ByteArray> =  energySignBluetoothGattServer.receivedBytes

    init {
        // UART
        uartDevice = configureUart()
        logD("UART device ${UART_DEVICE_NAME} opened and configured!")

        logD("Uart device ${UART_DEVICE_NAME} callback registered")
        uartDevice.registerUartDeviceCallback(this)
    }

    fun stop() {
        logD("Uart device ${UART_DEVICE_NAME} callback unregistered")
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
        logD("Data available on ${uart.name}! Reading...")
        // Read available data from the UART device
        try {
            val bytes = ByteArray(ARDUINO_RECEVIED_STRING_LEN)
            val count = uart.read(bytes, bytes.size)
            logD("Read $count bytes from ${uart.name}: [${String(bytes)}]")
            viewModel.onReadyForNextMessage()
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
            logD("Opening UART device ${UART_DEVICE_NAME}")
            return manager.openUartDevice(UART_DEVICE_NAME).apply {
                // Configure the UART port
                logD("Configuring UART device ${UART_DEVICE_NAME}")
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

    private fun UartDevice.write(buffer: ByteArray) {
        try {
            if (buffer.size > MAX_ARDUINO_JSON_SIZE) {
                logE("JSON string (${buffer.size}) is probably bigger than the maximum size the arduino will accept ($MAX_ARDUINO_JSON_SIZE)! ")
            }

//            val finalBuffer = buffer + '\r'.toByte()
//            val count = write(finalBuffer, finalBuffer.size)
            val count = write(buffer, buffer.size)
            logD("Wrote $count bytes to $name")
        } catch (e: Exception) {
            logW("Unable to write to UART device $name: $e")
        }
    }

    fun write(value: String) {
        logD("Writing >${value}< to ${uartDevice.name}...")

        val bytes = value.toByteArray()
        logD("Bytes: >${bytes.asList()}<")

        uartDevice.write(bytes)
    }

    companion object {

        private const val UART_DEVICE_NAME: String = "UART6"
        private const val UART_BAUD_RATE: Int = 9600
        private const val ARDUINO_RECEVIED_STRING_LEN: Int = 512
        private const val MAX_ARDUINO_JSON_SIZE: Int = 700

    }
}
