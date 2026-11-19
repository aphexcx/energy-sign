package cx.aphex.energysign.bluetooth

import android.bluetooth.*
import android.content.ContextWrapper
import cx.aphex.energysign.TestStartWorker
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
    private val worker = TestStartWorker()
    private val shown = mutableListOf<BluetoothStatusUpdate>()
    private val gatt = EnergySignBluetoothGattServer(context, manager, model, worker)

    init {
        `when`(manager.openGattServer(context, gatt)).thenReturn(platform)
        `when`(cccd.uuid).thenReturn(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR)
        gatt.showPeer = { shown += it }
        gatt.start()
    }
    private fun drain() = worker.drain()

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

    @Test fun mtuOutcomesReleaseOnlyTheAffectedClientAndForegroundRetries() {
        for (outcome in listOf("refused", "disconnect", "error", "deadline", "success")) {
            val phone = mock(BluetoothDevice::class.java)
            val client = mock(BluetoothGatt::class.java)
            val replacement = mock(BluetoothGatt::class.java)
            lateinit var cb: BluetoothGattCallback
            var connects = 0
            `when`(phone.connectGatt(eq(context), eq(true), any())).thenAnswer {
                cb = it.getArgument(2)
                if (++connects == 1) client else replacement
            }
            `when`(client.requestMtu(512)).thenReturn(outcome != "refused")
            gatt.onConnectionStateChange(phone, 0, BluetoothProfile.STATE_CONNECTED)
            drain()
            val old = cb
            cb.onConnectionStateChange(client, 0, BluetoothProfile.STATE_CONNECTED)
            drain()
            when (outcome) {
                "disconnect" -> cb.onConnectionStateChange(client, 0, BluetoothProfile.STATE_DISCONNECTED)
                "error" -> cb.onMtuChanged(client, 128, BluetoothGatt.GATT_FAILURE)
                "deadline" -> worker.advance(20_000)
                "success" -> cb.onMtuChanged(client, 128, 0)
            }
            drain()
            gatt.retryPeerSetup()
            if (outcome == "success") {
                assertEquals(1, connects)
                assertEquals(128, shown.last().mtu)
            } else {
                assertEquals(2, connects, outcome)
                verify(client).close()
                old.onMtuChanged(client, 128, 0)
                cb.onMtuChanged(replacement, 512, 0)
                drain()
                assertEquals(512, shown.last().mtu)
            }
            gatt.onConnectionStateChange(phone, 0, BluetoothProfile.STATE_DISCONNECTED)
            drain()
            verify(client, times(1)).close()
        }
    }

    @Test fun onlyRequestedConnectedBondsRetryAfterDeadline() {
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        `when`(device.createBond()).thenReturn(true)
        gatt.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        gatt.retryPeerSetup()
        verify(device, never()).createBond()
        gatt.onDescriptorReadRequest(device, 1, 0, cccd)
        drain()
        verify(device).createBond()
        worker.advance(20_000)
        gatt.retryPeerSetup()
        verify(device, times(2)).createBond()
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        gatt.bondState(device, BluetoothDevice.BOND_BONDED)
        gatt.onDescriptorReadRequest(device, 2, 0, cccd)
        drain()
        gatt.retryPeerSetup()
        verify(device, times(2)).createBond()
        verify(platform).sendResponse(device, 2, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0, 0))
    }

    @Test fun disconnectShowsRemainingPhoneAndMtu() {
        val other = mock(BluetoothDevice::class.java)
        gatt.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        gatt.onMtuChanged(device, 64)
        gatt.onConnectionStateChange(other, 0, BluetoothProfile.STATE_CONNECTED)
        gatt.onConnectionStateChange(other, 0, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        assertEquals(BluetoothStatusUpdate(device, 64), shown.last())
        gatt.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        assertEquals(BluetoothStatusUpdate(null, null), shown.last())
    }
}
