package cx.aphex.energysign.bluetooth

import android.util.Log
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable

/**
 * Hands every write of the phone to [process], for as long as the app runs.
 *
 * A write that [process] throws an Exception on is logged and dropped, and the next write is
 * delivered all the same. With a plain `subscribe { process(it) }` the first write that throws
 * is the last write the app ever takes: RxJava ends the subscription and hands the exception to
 * the thread's handler of uncaught exceptions, which on Android ends the app. An Error is not
 * caught here, as it is not where the owner runs a change (OwnerQueue.drain).
 *
 * [process] runs on the thread that delivers the write. For MessageManager.processNewBytes that
 * is where a command is read (an ad command is parsed there) and posted to the owner of the
 * messages; what the owner throws while carrying it out stays with the owner (OwnerQueue.drain).
 */
fun Observable<ByteArray>.deliverEachWrite(process: (ByteArray) -> Unit): Disposable =
    subscribe { value ->
        try {
            process(value)
        } catch (e: Exception) {
            Log.w(BLUETOOTH_WRITE_TAG, "A write was dropped, the app threw on it: [${String(value)}]", e)
        }
    }

/** The tag the Bluetooth write path logs under. */
val BLUETOOTH_WRITE_TAG: String = EnergySignBluetoothGattServer::class.java.simpleName
