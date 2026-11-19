package cx.aphex.energysign.bluetooth

import android.util.Log
import cx.aphex.energysign.message.MessageManager

/** An ordinary write posts; a requested ATT receipt waits for the message owner. Either way it counts from [arrivedAt]. */
internal fun MessageManager.takeWrite(bytes: ByteArray, responseNeeded: Boolean, arrivedAt: Long): Boolean =
    try {
        if (responseNeeded) processNewBytesAndWait(bytes, arrivedAt) else processNewBytes(bytes, arrivedAt)
        true
    } catch (failure: Throwable) {
        Log.w(BLUETOOTH_WRITE_TAG, "A Bluetooth write failed (${failure.javaClass.name})")
        false
    }

/** Shared with the debug command receiver. */
val BLUETOOTH_WRITE_TAG: String = EnergySignBluetoothGattServer::class.java.simpleName
