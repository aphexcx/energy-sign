package cx.aphex.energysign.bluetooth

import android.util.Log
import cx.aphex.energysign.message.MessageManager
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable

/** One write and its synchronous handoff to MessageManager on the Bluetooth callback thread. */
internal class GattWrite(private val bytes: ByteArray, val responseNeeded: Boolean) {
    var delivered = false
        private set

    fun deliverTo(process: (ByteArray) -> Unit) {
        try {
            process(bytes)
            delivered = true
        } catch (failure: Throwable) {
            Log.w(BLUETOOTH_WRITE_TAG, "A Bluetooth write failed: [${String(bytes)}]", failure)
        }
    }
}

/** Keep delivery synchronous: the GATT callback reads this write's result when accept returns. */
internal fun Observable<GattWrite>.deliverEachWrite(
    post: (ByteArray) -> Unit,
    confirm: (ByteArray) -> Unit,
): Disposable = subscribe { it.deliverTo(if (it.responseNeeded) confirm else post) }

/** Normal Nordic UART writes are posted. A caller requesting a reply hears the owner's result. */
internal fun Observable<GattWrite>.deliverEachWrite(manager: MessageManager): Disposable =
    deliverEachWrite(post = manager::processNewBytes, confirm = manager::processNewBytesAndWait)

/** The tag the Bluetooth write path logs under. */
val BLUETOOTH_WRITE_TAG: String = EnergySignBluetoothGattServer::class.java.simpleName
