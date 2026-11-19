package cx.aphex.energysign.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import android.bluetooth.BluetoothManager
import android.content.res.Configuration
import org.objenesis.ObjenesisStd
import kotlin.test.assertSame

class BluetoothStartupTest {
    @Test
    fun `a refused pairing receiver leaves adapter recovery active until stop`() {
        val acquired = mutableListOf<BroadcastReceiver>()
        val removed = mutableListOf<BroadcastReceiver>()
        val context = object : ContextWrapper(null) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
                if (acquired.size == 1) throw SecurityException()
                acquired += checkNotNull(receiver)
                return null
            }

            override fun unregisterReceiver(receiver: BroadcastReceiver) {
                removed += receiver
            }
        }
        val receivers = BluetoothReceivers(context)
        fun receiver() = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }
        var advertised = 0
        val session = BluetoothSession(cx.aphex.energysign.SignStartup {}, { receivers.register(receiver(), receiver()) },
            receivers::clear, { true }, { false }, { added -> added(0); true },
            { result -> advertised++; result(0) }, {})
        session.start()
        session.adapterOff()
        session.adapterOn()
        assertEquals(2, advertised)
        assertEquals(emptyList(), removed)
        session.stop()
        receivers.clear()
        receivers.clear()
        assertEquals(acquired, removed)
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
        assertSame(healthy, managerForGatt(application) { it === healthy })
    }
}
