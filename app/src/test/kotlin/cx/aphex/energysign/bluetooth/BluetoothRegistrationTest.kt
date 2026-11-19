package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import cx.aphex.energysign.SignStartup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android has separate return values and callbacks for the same registration attempt. */
class BluetoothRegistrationTest {
    private val registrations = mutableListOf<(Int) -> Unit>()
    private val advertisements = mutableListOf<(Int) -> Unit>()
    private val warnings = mutableListOf<String>()
    private var acceptsService = true
    private var closed = 0

    private val session = BluetoothSession(
        startup = SignStartup(warnings::add), register = {}, unregister = {},
        enabled = { true }, enable = { error("already enabled") },
        openGatt = { added -> registrations += added; acceptsService },
        advertise = { result -> advertisements += result },
        closeServices = { closed++ },
    )

    @Test
    fun `addService accepted then onServiceAdded failed closes the server and the next STATE_ON recovers`() {
        session.start()
        registrations.single()(BluetoothGatt.GATT_FAILURE)
        assertEquals(1, closed)
        assertTrue(advertisements.isEmpty(), "Android never registered the UART service")
        assertEquals(1, warnings.size)

        session.adapterOn()
        registrations.last()(BluetoothGatt.GATT_SUCCESS)
        advertisements.single()(0)
        session.adapterOn()
        assertEquals(2, registrations.size)
        assertEquals(1, closed)
    }

    @Test
    fun `advertising starts only after GATT registers and its own failure releases the attempt`() {
        session.start()
        assertTrue(advertisements.isEmpty())
        registrations.single()(0)
        advertisements.single()(4)
        assertEquals(1, closed)
        session.adapterOn()
        assertEquals(2, registrations.size)
    }

    @Test
    fun `STATE_ON can replace either missing callback and stale results cannot change the replacement`() {
        session.start()
        val oldGatt = registrations.single()
        session.adapterOn() // no onServiceAdded
        assertEquals(2, registrations.size)
        oldGatt(0)
        assertTrue(advertisements.isEmpty())
        registrations.last()(0)
        val oldAdvertiser = advertisements.single()
        session.adapterOn() // no advertising callback
        assertEquals(3, registrations.size)
        registrations.last()(0)
        oldAdvertiser(4)
        advertisements.last()(0)
        oldGatt(1)
        session.adapterOn()
        assertEquals(3, registrations.size)
        assertEquals(2, closed)
        assertTrue(warnings.isEmpty() || warnings.all { "pending" in it })
    }

    @Test
    fun `OFF and stop invalidate pending work before Android can deliver its result`() {
        session.start()
        val oldGatt = registrations.single()
        session.adapterOff()
        oldGatt(0)
        assertTrue(advertisements.isEmpty())
        session.adapterOn()
        registrations.last()(0)
        val oldAdvertiser = advertisements.single()
        session.stop()
        oldAdvertiser(0)
        session.adapterOn()
        assertEquals(2, registrations.size)
        assertEquals(2, closed)
    }

    @Test
    fun `a refused request opens no advertiser and a duplicate service result opens just one`() {
        acceptsService = false
        session.start()
        assertTrue(advertisements.isEmpty())
        assertEquals(1, closed)
        acceptsService = true
        session.adapterOn()
        val added = registrations.last()
        added(0)
        added(0)
        assertEquals(1, advertisements.size)
    }
}
