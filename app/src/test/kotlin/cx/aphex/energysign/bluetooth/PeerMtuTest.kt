package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import cx.aphex.energysign.SignStartup
import org.junit.jupiter.api.Test
import org.objenesis.ObjenesisStd
import kotlin.test.assertEquals

class PeerMtuTest {
    private val client = ObjenesisStd().newInstance(BluetoothGatt::class.java)
    private val pending = ArrayDeque<() -> Unit>()
    private val observed = mutableListOf<Int>()
    private val closed = mutableListOf<BluetoothGatt>()
    private var accepts = true
    private val peer = PeerMtu(SignStartup {}, pending::addLast, observed::add, { accepts }, closed::add)
    private fun deliver() { while (pending.isNotEmpty()) pending.removeFirst()() }

    @Test
    fun `only a live confirmed MTU reaches the display, and teardown retires the client exactly once`() {
        peer.start { callback ->
            callback.onConnectionStateChange(client, 0, STATE_CONNECTED)
            client
        }
        peer.onMtuChanged(client, 24, 0)
        peer.onMtuChanged(client, 128, BluetoothGatt.GATT_FAILURE)
        peer.onMtuChanged(client, 512, 0)
        deliver()
        peer.stop()
        assertEquals(listOf(24), observed)
        assertEquals(listOf(client), closed)
    }

    @Test
    fun `refused MTU or a disconnect while callbacks are pending uses the default MTU`() {
        for (refused in listOf(true, false)) {
            accepts = !refused
            peer.start { client }
            peer.onConnectionStateChange(client, 0, STATE_CONNECTED)
            if (!refused) peer.stop()
            peer.onMtuChanged(client, 512, 0)
            deliver()
        }
        assertEquals(emptyList(), observed)
        assertEquals(listOf(client, client), closed)
    }

    @Test
    fun missingMtuCallbackReleasesTheClientAndAllowsForegroundRetry() {
        val replacement = ObjenesisStd().newInstance(BluetoothGatt::class.java)
        var expire: (() -> Unit)? = null
        val peer = PeerMtu(SignStartup {}, pending::addLast, observed::add, { true }, closed::add) { action ->
            expire = action
            { expire = null }
        }
        peer.start { client }
        peer.onConnectionStateChange(client, 0, STATE_CONNECTED)
        deliver()
        expire!!.invoke()
        peer.start { replacement } // foreground even before timeout cleanup gets its turn
        peer.onMtuChanged(client, 64, 0)
        peer.onMtuChanged(replacement, 128, 0)
        deliver()
        assertEquals(listOf(client), closed)
        assertEquals(listOf(128), observed)
        peer.stop()
        assertEquals(listOf(client, replacement), closed)
    }
}
