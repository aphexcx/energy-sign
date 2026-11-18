package cx.aphex.energysign.adb

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * One broadcast is one write. The bytes go to MessageManager.processNewBytesAndWait: the same
 * change as a Bluetooth write (MessageManager.processNewBytes), handed to the same owner of the
 * messages, which carries out the commands of both doors one after the other.
 *
 * The answer is the broadcast's result data, which `am broadcast` prints, and it says what the
 * owner DID: "ok <n> bytes" when it has run the command and nothing was thrown (a command the
 * app does not know is run as nothing, and is "ok" too), "refused: <why>" when not (no extra,
 * bad base64, nothing to send, the app threw, the sign link is not running). A command that
 * throws ends neither the app nor this door.
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
        // open until the other thread has the outcome. That thread reads the command (an ad
        // command is parsed there) and then waits for the owner, two seconds at most.
        val pending = goAsync()
        AdbCommand.aside(bytes, manager) { outcome ->
            try {
                val line = "adb write: [${String(bytes)}] ${outcome.answer}"
                if (outcome.accepted) Log.d(BLUETOOTH_WRITE_TAG, line) else Log.w(BLUETOOTH_WRITE_TAG, line, outcome.thrown)
                if (ordered) {
                    pending.resultCode = if (outcome.accepted) Activity.RESULT_OK else Activity.RESULT_CANCELED
                    pending.resultData = outcome.answer
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun commandOf(intent: Intent): AdbCommand =
        if (intent.action != AdbCommand.ACTION) {
            AdbCommand.Refused("the action must be ${AdbCommand.ACTION}")
        } else {
            AdbCommand.fromExtras(
                text = intent.getStringExtra(AdbCommand.EXTRA_TEXT),
                b64 = intent.getStringExtra(AdbCommand.EXTRA_B64),
            )
        }

    /** An answer that needs no other thread: nothing was handed to the app. */
    private fun refuse(why: String, ordered: Boolean) {
        Log.w(BLUETOOTH_WRITE_TAG, "adb write refused: $why")
        if (ordered) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "refused: $why"
        }
    }
}
