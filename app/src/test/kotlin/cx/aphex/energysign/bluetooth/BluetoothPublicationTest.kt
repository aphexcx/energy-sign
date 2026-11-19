package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.TestStartWorker
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.objenesis.ObjenesisStd
import kotlin.test.*

class BluetoothPublicationTest {
    private class FakeRadio : Radio {
        var identity = Any()
        override fun adapter(): Any = identity
        override var isOn = true
        var enableAccepted = true
        var gattAccepted = true
        var opensThrow = false
        var enables = 0
        var closes = 0
        val gatt = mutableListOf<(Int) -> Unit>()
        val ads = mutableListOf<Pair<Boolean, (Int) -> Unit>>()
        override fun enable(): Boolean { enables++; return enableAccepted }
        override fun openGatt(added: (Int) -> Unit): Boolean {
            gatt += added
            if (opensThrow) throw LinkageError()
            return gattAccepted
        }
        override fun advertise(named: Boolean, result: (Int) -> Unit) { ads += named to result }
        override fun close() { closes++ }
        fun confirm() { gatt.last()(0); ads.last().second(0) }
    }

    @Test fun publicationFollowsPhysicalRadioAndAdapterIdentity() {
        for (event in listOf("healthy foreground", "missed off", "fresh ON", "replaced adapter")) {
            val radio = FakeRadio()
            val publication = BluetoothPublication(radio, TestStartWorker(), {})
            publication.ensure()
            radio.confirm()
            when (event) {
                "missed off" -> {
                    radio.isOn = false
                    publication.ensure()
                    assertEquals(1, radio.closes)
                    assertEquals(1, radio.enables)
                    radio.isOn = true
                    publication.radioOn()
                }
                "fresh ON" -> publication.radioOn()
                "replaced adapter" -> { radio.identity = Any(); publication.ensure() }
                else -> publication.ensure()
            }
            assertEquals(if (event == "healthy foreground") 1 else 2, radio.gatt.size, event)
        }
    }

    @Test fun failedOrMissingRadioResultsReleaseTheirAttemptAndPermitRecovery() {
        for (fault in listOf("enable refused", "lost ON", "GATT throw", "GATT refused", "GATT status",
                "GATT deadline", "advertise status", "advertise deadline", "too large", "OFF", "ON")) {
            val radio = FakeRadio()
            val worker = TestStartWorker()
            val pub = BluetoothPublication(radio, worker, {})
            radio.isOn = fault !in listOf("enable refused", "lost ON")
            radio.enableAccepted = fault != "enable refused"
            radio.gattAccepted = fault != "GATT refused"
            radio.opensThrow = fault == "GATT throw"
            pub.ensure()
            assertTrue(radio.ads.isEmpty())
            if (!radio.isOn) {
                assertTrue(radio.gatt.isEmpty())
                radio.isOn = true
            }
            when (fault) {
                "GATT status" -> radio.gatt.single()(3)
                "advertise status", "advertise deadline", "too large" -> {
                    radio.gatt.single()(0)
                    radio.gatt.single()(0)
                    val ad = radio.ads.single().second
                    if (fault == "advertise status") ad(4)
                    if (fault == "too large") {
                        ad(ADVERTISE_FAILED_DATA_TOO_LARGE)
                        assertEquals(listOf(true, false), radio.ads.map { it.first })
                        ad(4)
                        radio.ads.last().second(0)
                    }
                }
                "OFF" -> pub.radioOff()
                "ON" -> pub.radioOn()
            }
            if (fault in listOf("lost ON", "GATT deadline", "advertise deadline")) worker.advance(20_000)
            radio.gattAccepted = true
            radio.opensThrow = false
            pub.ensure()
            if (fault != "too large") {
                val oldAds = radio.ads.size
                radio.gatt.dropLast(1).forEach { it(0) }
                assertEquals(oldAds, radio.ads.size, fault)
                radio.confirm()
            }
            val opens = radio.gatt.size
            worker.advance(60_000)
            pub.ensure()
            assertEquals(opens, radio.gatt.size, fault)
            assertTrue(radio.ads.isNotEmpty(), fault)
        }
    }

    @Test fun refusedPairingReceiverCanRetryWhileAdapterReceiverStaysInstalled() {
        val registered = mutableListOf<BroadcastReceiver>()
        val pm = mock(PackageManager::class.java)
        `when`(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)).thenReturn(true)
        var refused = false
        val app = object : ContextWrapper(null) {
            override fun getPackageManager() = pm
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
                if (registered.size == 1 && !refused) { refused = true; throw SecurityException() }
                registered += checkNotNull(receiver)
                return null
            }
        }
        val worker = TestStartWorker()
        val manager = EnergySignBluetoothManager(app, ObjenesisStd().newInstance(MainViewModel::class.java), worker) { _, _, _ -> true }
        manager.ensure()
        assertEquals(1, registered.size)
        manager.ensure()
        manager.ensure()
        assertEquals(2, registered.distinct().size)
        assertEquals(2, registered.size)
        val phone = mock(android.bluetooth.BluetoothDevice::class.java)
        val pairing = mock(Intent::class.java)
        `when`(pairing.action).thenReturn(android.bluetooth.BluetoothDevice.ACTION_PAIRING_REQUEST)
        `when`(pairing.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)).thenReturn(phone)
        registered.last().onReceive(app, pairing)
        worker.drain()
        verify(phone).setPin(manager.BLE_PIN.toByteArray())
    }

    @Test
    fun `a cached manager with no adapter at boot is reacquired from a fresh context`() {
        val android = ObjenesisStd()
        val stale = android.newInstance(BluetoothManager::class.java)
        val healthy = android.newInstance(BluetoothManager::class.java)
        val fresh = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any = healthy
        }
        val application = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any = stale
            override fun createConfigurationContext(config: Configuration): Context = fresh
        }
        assertSame(healthy, managerForGatt(application))
    }
}
