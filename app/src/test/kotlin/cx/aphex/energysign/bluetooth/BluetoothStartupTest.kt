package cx.aphex.energysign.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BluetoothStartupTest {
    @Test
    fun `a refused registration releases only acquired receivers and can be cleared twice`() {
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
        assertFailsWith<SecurityException> { receivers.register(receiver(), receiver()) }
        receivers.clear()
        receivers.clear()
        assertEquals(acquired, removed)
    }
}
