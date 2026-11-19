package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.ContextWrapper
import cx.aphex.energysign.MainViewModel
import org.objenesis.ObjenesisStd
import java.util.UUID
import cx.aphex.energysign.SignStartup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android has separate return values and callbacks for the same registration attempt. */
class BluetoothRegistrationTest {
    private val registrations = mutableListOf<(Int) -> Unit>()
    private val names = mutableListOf<Boolean>()
    private val advertisements = mutableListOf<(Int) -> Unit>()
    private val warnings = mutableListOf<String>()
    private var acceptsService = true
    private var closed = 0

    private val session = BluetoothSession(
        startup = SignStartup(warnings::add), register = {}, unregister = {},
        enabled = { true }, enable = { error("already enabled") },
        openGatt = { added -> registrations += added; acceptsService },
        advertise = { name, result -> names += name; advertisements += result },
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
        assertTrue(warnings.isEmpty())
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

    @Test
    fun `a device name too large for legacy advertising falls back once without closing GATT`() {
        session.start()
        registrations.single()(0)
        val named = advertisements.single()
        named(android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE)
        assertEquals(2, advertisements.size)
        assertEquals(listOf(true, false), names)
        named(4) // late result from the superseded submission
        advertisements.last()(0)
        session.adapterOn()
        assertEquals(0, closed)
        assertEquals(1, registrations.size)
    }

    @Test
    fun `only the UART result from the current Android server can start advertising`() {
        val android = ObjenesisStd()
        val manager = android.newInstance(BluetoothManager::class.java)
        val model = android.newInstance(MainViewModel::class.java)
        val servers = mutableListOf<EnergySignBluetoothGattServer>()
        fun service(id: UUID) = object : BluetoothGattService(id, SERVICE_TYPE_PRIMARY) {
            override fun getUuid() = id
        }
        val uart = service(NordicUartServiceProfile.NORDIC_UART_SERVICE_UUID)
        val session = BluetoothSession(SignStartup(warnings::add), {}, {}, { true }, { false },
            openGatt = { added ->
                servers += EnergySignBluetoothGattServer(ContextWrapper(null), manager, model, serviceAdded = added)
                true
            },
            advertise = { _, done -> advertisements += done },
            closeServices = { servers.last().stop() },
        )
        session.start()
        val original = servers.single()
        original.onServiceAdded(0, service(UUID.randomUUID()))
        assertTrue(advertisements.isEmpty())
        original.onServiceAdded(BluetoothGatt.GATT_FAILURE, uart)
        session.adapterOn()
        original.onServiceAdded(0, uart)
        assertTrue(advertisements.isEmpty())
        servers.last().onServiceAdded(0, uart)
        assertEquals(1, advertisements.size)
    }

    @Test
    fun `accepted enable waits for the ON broadcast and an absent broadcast cannot publish GATT`() {
        var enableCalls = 0
        val session = BluetoothSession(SignStartup(warnings::add), {}, {}, { false }, { enableCalls++; true },
            openGatt = { added -> registrations += added; true },
            advertise = { _, done -> advertisements += done }, closeServices = {},
        )
        session.start()
        assertEquals(1, enableCalls)
        assertTrue(registrations.isEmpty())
        assertTrue(advertisements.isEmpty())
        session.adapterOn()
        assertEquals(1, registrations.size)
    }

    @Test
    fun missingCallbacksExpireWhileRadioStaysOn() {
        var alarm: (() -> Unit)? = null
        session.arm = { fire -> alarm = fire; { alarm = null } }
        session.start()
        val stale = registrations.single()
        alarm?.invoke()
        assertEquals(2, registrations.size)
        stale(0)
        registrations.last()(0)
        val staleAd = advertisements.single()
        alarm?.invoke()
        assertEquals(3, registrations.size)
        registrations.last()(0)
        staleAd(4)
        advertisements.last()(0)
        alarm?.invoke()
        session.checkAdapter()
        assertEquals(3, registrations.size)
        assertEquals(2, closed)
    }
}
