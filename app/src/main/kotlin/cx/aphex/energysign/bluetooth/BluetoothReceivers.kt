package cx.aphex.energysign.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

internal class BluetoothReceivers(private val context: Context) {
    fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        context.registerReceiver(receiver, filter)
    }

    fun clear() {}
}
