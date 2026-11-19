package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.BoundedStart
import cx.aphex.energysign.ext.logW
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

internal class BluetoothReceivers(private val context: Context) {
    private val registrations = CompositeDisposable()

    private val startup = SignStartup { logW(it) }
    private val pairing = BoundedStart()

    fun registerAdapter(adapterState: BroadcastReceiver) {
        context.registerReceiver(adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registrations.add(Disposable.fromAction { context.unregisterReceiver(adapterState) })
    }

    fun ensurePairing(bondState: BroadcastReceiver) {
        val ticket = pairing.begin { startup.failed("Bluetooth pairing receiver", "registration deadline") } ?: return
        val installed = startup.optional("Bluetooth pairing receiver") {
            context.registerReceiver(bondState, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            })
            if (ticket.done()) registrations.add(Disposable.fromAction { context.unregisterReceiver(bondState) })
            else context.unregisterReceiver(bondState)
        }
        if (installed == null) ticket.fail()
    }

    fun clear() {
        pairing.reset()
        registrations.clear()
    }
}
