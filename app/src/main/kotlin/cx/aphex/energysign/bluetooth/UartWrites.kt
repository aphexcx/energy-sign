package cx.aphex.energysign.bluetooth

import android.util.Log
import cx.aphex.energysign.message.MessageManager

/** An ordinary write posts; a requested ATT receipt waits for the message owner. */
internal fun MessageManager.takeWrite(bytes: ByteArray, responseNeeded: Boolean): Boolean =
    try {
        if (responseNeeded) processNewBytesAndWait(bytes) else processNewBytes(bytes)
        true
    } catch (failure: Throwable) {
        Log.w(BLUETOOTH_WRITE_TAG, "A Bluetooth write failed (${failure.javaClass.name})")
        false
    }

/** Shared with the debug command receiver. */
val BLUETOOTH_WRITE_TAG: String = EnergySignBluetoothGattServer::class.java.simpleName
