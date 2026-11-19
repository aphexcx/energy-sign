package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

internal class BluetoothReceivers(private val context: Context) {
    private val registrations = CompositeDisposable()

    fun register(adapterState: BroadcastReceiver, bondState: BroadcastReceiver) {
        context.registerReceiver(adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registrations.add(Disposable.fromAction { context.unregisterReceiver(adapterState) })

        context.registerReceiver(bondState, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        })
        registrations.add(Disposable.fromAction { context.unregisterReceiver(bondState) })
    }

    fun clear() = registrations.clear()
}
