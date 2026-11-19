package cx.aphex.energysign.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import cx.aphex.energysign.bluetooth.BLUETOOTH_WRITE_TAG
import cx.aphex.energysign.message.MessageManager
import org.koin.core.context.GlobalContext

/**
 * DEBUG BUILDS ONLY (this file and its manifest entry are in src/debug). Hands the app the bytes
 * a Bluetooth write would, so the sign can be driven over adb with nobody standing at it:
 *
 *     adb shell 'am broadcast -n cx.aphex.energysign/.adb.AdbCommandReceiver \
 *         -a cx.aphex.energysign.COMMAND --es text "!next"'
 *
 * The manifest entry demands android.permission.DUMP of the sender. The adb shell holds it; an
 * app on the device cannot get it unless someone grants it over adb. The same commands are
 * accepted today from any Bluetooth device in range, so this door is the narrower one.
 *
 * One broadcast is one write. The bytes go to MessageManager.processNewBytesAndTell: the same
 * change as a Bluetooth write (MessageManager.processNewBytes), handed to the same owner of the
 * messages, which carries out the commands of both doors one after the other.
 *
 * The answer is the broadcast's result (`am broadcast` prints its code and its data), and it
 * says what the owner DID. It is one of three things and never the wrong one:
 * - "ok <n> bytes" (code -1): the owner has run the command and nothing was thrown (a command
 *   the app does not know is run as nothing, and is "ok" too).
 * - "refused: <why>" (code 1): it is CERTAIN that nothing happened; sending it again is safe.
 *   Every way to it, and why it is certain there:
 *     the wrong action, extras that cannot be read, an extra of the wrong type or with an
 *     unknown name, no extra, bad base64, nothing to send: decided before the app is touched;
 *     the app has no MessageManager: there is nobody to hand the bytes to;
 *     the hook could not start its threads: the command's thread never ran;
 *     the sign link did not get to it: the owner's queue withdrew the change, and says so with
 *     a value only the queue makes (OwnerQueue.Asked.Withdrawn).
 * - anything else: the command MAY have happened. "failed: ..." (code 2): the app threw on
 *   the command, and part of it may have been done; or the call threw, and it is not known.
 *   "underway: ..." (code 3): no outcome within 5 s of the broadcast's arrival here; the
 *   command is not cut short, and how it ends goes to the log. And "result=0" with NO data is
 *   not an answer of this hook at all: the app died, or the system's time limit for the
 *   broadcast ran out before the hook answered (see AdbCommand.Kind).
 * A command that throws ends neither the app nor this door.
 *
 * The 5 s are counted from the first line of onReceive. What the system spends before that
 * (starting the app when it is not running, a busy main thread) is not the hook's to bound.
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val arrivedAtMs = SystemClock.elapsedRealtime()
        // `am broadcast` sends an ordered broadcast; anything else has nowhere to put an answer.
        // Asked now: after goAsync() this receiver no longer knows.
        val ordered = isOrderedBroadcast

        val bytes = when (val command = commandOf(intent)) {
            is AdbCommand.Refused -> {
                refuse(command.why, ordered)
                return
            }

            is AdbCommand.Bytes -> command.bytes
        }
        // Here, on the main thread: MessageManager is built by its first use, and only the main
        // thread may build it (it observes LiveData). Normally the main screen has done that.
        val manager = try {
            GlobalContext.getOrNull()?.get<MessageManager>()
        } catch (e: Exception) {
            Log.w(BLUETOOTH_WRITE_TAG, "adb write: the app has no MessageManager", e)
            null
        }
        if (manager == null) {
            refuse("the app is not ready (it has no MessageManager)", ordered)
            return
        }

        // The wait for the owner of the messages is not for the main thread: the broadcast stays
        // open until another thread has an answer. That thread reads the command (an ad command
        // is parsed there) and then waits for the owner: two seconds at most for the owner to
        // start it, and then for as long as the owner takes to finish it. The BROADCAST does not
        // wait that long: it is answered within AdbCommand.ANSWER_WITHIN_MS of its arrival, with
        // "underway" when there is no outcome yet (Android's own limit: see that constant).
        val pending = goAsync()
        fun log(what: String, outcome: AdbCommand.Outcome) {
            val line = "$what: [${String(bytes)}] ${outcome.answer}"
            if (outcome.accepted) Log.d(BLUETOOTH_WRITE_TAG, line) else Log.w(BLUETOOTH_WRITE_TAG, line, outcome.thrown)
        }
        try {
            AdbCommand.aside(
                bytes, manager,
                answerWithinMs = (AdbCommand.ANSWER_WITHIN_MS - (SystemClock.elapsedRealtime() - arrivedAtMs)).coerceAtLeast(0),
                late = { outcome -> log("adb write, after its answer", outcome) },
            ) { outcome ->
                try {
                    log("adb write", outcome)
                    if (ordered) {
                        pending.resultCode = outcome.kind.resultCode
                        pending.resultData = outcome.answer
                    }
                } finally {
                    pending.finish()
                }
            }
        } catch (e: Throwable) {
            // No thread for the hook: the command's thread never ran, so nothing was carried out.
            try {
                Log.w(BLUETOOTH_WRITE_TAG, "adb write refused: the hook could not start its threads", e)
                if (ordered) {
                    pending.resultCode = AdbCommand.Kind.REFUSED.resultCode
                    pending.resultData = "refused: the hook could not start its threads (${e.javaClass.simpleName})"
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun commandOf(intent: Intent): AdbCommand {
        if (intent.action != AdbCommand.ACTION) return AdbCommand.Refused("the action must be ${AdbCommand.ACTION}")
        // Every extra there is, with the value it has: getStringExtra would hand back null for
        // one of another type, and "there, but not a string" must not read as "not there".
        val extras: Map<String, Any?> = try {
            intent.extras?.let { bundle -> bundle.keySet().associateWith { bundle.get(it) } } ?: emptyMap()
        } catch (e: RuntimeException) {
            return AdbCommand.Refused("the extras could not be read (${e.javaClass.simpleName})")
        }
        return AdbCommand.fromExtras(extras)
    }

    /** An answer that needs no other thread: nothing was handed to the app. */
    private fun refuse(why: String, ordered: Boolean) {
        Log.w(BLUETOOTH_WRITE_TAG, "adb write refused: $why")
        if (ordered) {
            resultCode = AdbCommand.Kind.REFUSED.resultCode
            resultData = "refused: $why"
        }
    }
}
