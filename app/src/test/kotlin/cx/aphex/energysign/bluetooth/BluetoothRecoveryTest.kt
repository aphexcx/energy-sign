package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.content.ContextWrapper
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.SignStartup
import org.junit.jupiter.api.Test
import org.objenesis.ObjenesisStd
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothRecoveryTest {
    private val warnings = mutableListOf<String>()
    private val startup = SignStartup(warnings::add)
    private val android = ObjenesisStd()
    private val gatt = EnergySignBluetoothGattServer(
        ContextWrapper(null), android.newInstance(BluetoothManager::class.java), android.newInstance(MainViewModel::class.java),
    )
    private val received = mutableListOf<String>()
    private var registered = false
    private var published = false
    private var uartStarts = 0
    private var enableRequests = 0
    private var gattFailure: Throwable? = null

    private val session = BluetoothSession(
        startup,
        register = { registered = true },
        unregister = { registered = false },
        enabled = { false },
        enable = { enableRequests++; false },
        openServices = {
            published = true
            gattFailure?.let { throw it }
        },
        closeServices = { published = false },
    )

    private fun listen(consume: (ByteArray) -> Unit) =
        gatt.writes.deliverEachWrite(post = consume, confirm = consume)

    private fun start(subscribe: () -> Unit = {
        listen { received.add(String(it)) }
    }) {
        startup.start(
            prepareMessages = {}, launchWarmUp = {}, warmUp = {},
            createBluetooth = { session }, subscribeToWrites = { subscribe() }, startBluetooth = { it.start() },
            startDnsSd = {}, startLink = { uartStarts++ },
        )
    }

    @Test
    fun `Android can enable Bluetooth later after enable returns false, and after GATT fails on STATE_ON`() {
        start()
        assertEquals(1, uartStarts)
        assertEquals(1, enableRequests)
        assertTrue(registered)
        assertFalse(published)

        gattFailure = LinkageError("GATT unavailable")
        session.adapterOn()
        assertTrue(registered)
        assertFalse(published)
        assertEquals(2, warnings.size)

        gattFailure = null
        session.adapterOn()
        assertTrue(published)
        assertEquals(BluetoothGatt.GATT_SUCCESS, gatt.uartWriteStatus("!next".toByteArray()))
        assertEquals(listOf("!next"), received)
        assertEquals(1, enableRequests)
        session.stop()
        assertFalse(registered)
        assertFalse(published)
    }

    @Test
    fun `a refused subscription leaves no writable service and a late write gets failure`() {
        var opened = false
        startup.start(
            prepareMessages = {}, launchWarmUp = {}, warmUp = {}, createBluetooth = { gatt },
            subscribeToWrites = { throw IllegalStateException("subscription refused") },
            startBluetooth = { opened = true }, startDnsSd = {}, startLink = { uartStarts++ },
        )
        val status = gatt.uartWriteStatus("!delete".toByteArray())
        assertEquals(BluetoothGatt.GATT_FAILURE, status)
        assertFalse(opened)
        assertEquals(1, uartStarts)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `an unsupported fragment is refused before it can run as a complete command`() {
        listen { received.add(String(it)) }
        val command = "!delete".toByteArray()
        assertEquals(BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, gatt.uartWriteStatus(command, preparedWrite = true))
        assertEquals(BluetoothGatt.GATT_INVALID_OFFSET, gatt.uartWriteStatus(command, offset = 1))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `failure to hand a write to the consumer is reported and the next write still arrives`() {
        val listener = listen {
            if (it[0] == 0.toByte()) throw IllegalStateException()
            received.add(String(it))
        }
        assertEquals(BluetoothGatt.GATT_FAILURE, gatt.uartWriteStatus(byteArrayOf(0)))
        assertEquals(BluetoothGatt.GATT_SUCCESS, gatt.uartWriteStatus("hello".toByteArray()))
        listener.dispose()
        assertEquals(BluetoothGatt.GATT_FAILURE, gatt.uartWriteStatus("late".toByteArray()))
        assertEquals(listOf("hello"), received)
    }
}
