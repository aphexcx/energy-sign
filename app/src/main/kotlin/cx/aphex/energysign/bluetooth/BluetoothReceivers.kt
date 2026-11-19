package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.ext.logW
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable

internal class BluetoothReceivers(private val context: Context) {
    private val registrations = CompositeDisposable()

    private val startup = SignStartup { logW(it) }
    private var pairingRegistered = false

    fun register(adapterState: BroadcastReceiver, bondState: BroadcastReceiver) {
        context.registerReceiver(adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registrations.add(Disposable.fromAction { context.unregisterReceiver(adapterState) })

        ensurePairing(bondState)
    }

    fun ensurePairing(bondState: BroadcastReceiver) {
        if (pairingRegistered) return
        startup.optional("Bluetooth pairing receiver") {
            context.registerReceiver(bondState, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            })
            registrations.add(Disposable.fromAction { context.unregisterReceiver(bondState) })
            pairingRegistered = true
        }
    }

    fun clear() {
        pairingRegistered = false
        registrations.clear()
    }
}
