package cx.aphex.energysign.bluetooth

import android.bluetooth.*
import android.content.ContextWrapper
import cx.aphex.energysign.MainViewModel
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.objenesis.ObjenesisStd
import kotlin.test.*

class BluetoothPeerTest {
    private val context = ContextWrapper(null)
    private val manager = mock(BluetoothManager::class.java)
    private val platform = mock(BluetoothGattServer::class.java)
    private val model = ObjenesisStd().newInstance(MainViewModel::class.java)
    private val device = mock(BluetoothDevice::class.java)
    private val cccd = mock(BluetoothGattDescriptor::class.java)
    private val queue = ArrayDeque<() -> Unit>()
    private val shown = mutableListOf<BluetoothStatusUpdate>()
    private val gatt = EnergySignBluetoothGattServer(context, manager, model)

    init {
        `when`(manager.openGattServer(context, gatt)).thenReturn(platform)
        `when`(cccd.uuid).thenReturn(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR)
        gatt.postPeer = queue::addLast
        gatt.showPeer = { shown += it }
        gatt.start()
    }
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }

    @Test fun reconnectedPhoneKeepsItsAcknowledgedNotificationSubscription() {
        gatt.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        gatt.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        gatt.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        val enable = byteArrayOf(1, 0)
        gatt.onDescriptorWriteRequest(device, 10, cccd, false, true, 0, enable)
        verify(platform).sendResponse(device, 10, BluetoothGatt.GATT_SUCCESS, 0, enable)
        drain()
        gatt.onDescriptorReadRequest(device, 11, 0, cccd)
        verify(platform).sendResponse(device, 11, BluetoothGatt.GATT_SUCCESS, 0, enable)
    }
}
