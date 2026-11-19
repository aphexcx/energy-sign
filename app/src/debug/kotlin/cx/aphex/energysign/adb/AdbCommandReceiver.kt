package cx.aphex.energysign.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cx.aphex.energysign.bluetooth.BLUETOOTH_WRITE_TAG
import cx.aphex.energysign.signClockMs
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
 * says what the owner DID. THE HOOK NEVER PROMISES WHAT NOTHING ALIVE HOLDS:
 * - "ok <n> bytes" (code -1): the owner has run the command and nothing was thrown (a command
 *   the app does not know is run as nothing, and is "ok" too).
 * - "refused: <why>" (code 1): it is CERTAIN that nothing happened and that nothing will;
 *   sending it again is safe. Every way to it, and why it is certain there:
 *     the wrong action, extras that cannot be read, an extra of the wrong type or with an
 *     unknown name, no extra, bad base64, nothing to send: decided before the app is touched;
 *     "the app is not up yet, send it again": no sign link is running the owner's queue (the
 *     process was started by this very broadcast, or the main screen has not got that far),
 *     and the hook makes no message manager to carry the command out itself;
 *     "the command was not handed to the app within 5000 ms, and it will not be": the
 *     receiver's thread did not get to the hand-over in time, and when it gets there it finds
 *     the command refused and hands nothing over;
 *     the hook could not start its timer or the command's thread, or failed before the command
 *     was handed to the app: the command's thread, which makes the hand-over, never ran;
 *     the sign link did not get to it: the owner's queue withdrew the change, and says so with
 *     a value only the queue makes (OwnerQueue.Asked.Withdrawn).
 * - anything else: the command MAY have happened. "failed: ..." (code 2): the app threw on
 *   the command, and part of it may have been done; or the call threw, and it is not known.
 *   "underway: ..." (code 3): the running app has been handed the command and there is no
 *   outcome within 5 s of the broadcast's arrival here; the command is not cut short, and
 *   how it ends goes to the log, written by the app that the HOME activity keeps alive:
 *   ONE line, "adb write, after its answer: ...", with the outcome, or with "gave up after
 *   60 s" when the command has not ended a minute after the broadcast came.
 *   And "result=0" with NO data is not an answer of this hook at all: the app died, or the
 *   system's time limit for the broadcast ran out before the hook answered (see AdbCommand.Kind).
 * A command that throws ends neither the app nor this door.
 *
 * The 5 s are ONE deadline, taken by the first statements of onReceive, before the extras are
 * read and before the app is asked for its way in. What the system spends BEFORE onReceive
 * (starting the app when it is not running, a busy main thread) is not the hook's to bound:
 * see AdbCommand.ANSWER_WITHIN_MS for what happens then.
 *
 * Every line in the log has the outcome first and a bounded piece of the command behind it
 * (AdbCommand.logLine): "adb write: ok 5 bytes [!next]".
 */
class AdbCommandReceiver : BroadcastReceiver() {

    /**
     * Four statements, and then everything is AdbCommand.receive, which takes its deadline and
     * arms its timer before it does anything else: from there on the broadcast is answered within
     * AdbCommand.ANSWER_WITHIN_MS whatever this thread is held up by. The four before it read the
     * clock (when the command arrived) and a flag and take the broadcast's result object; they
     * wait for nothing.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val arrived = signClockMs() // first of all: the command arrived now, whatever this hook does before it hands it on
        // `am broadcast` sends an ordered broadcast; anything else has nowhere to put an answer.
        // Asked now: after goAsync() this receiver no longer knows.
        val ordered = isOrderedBroadcast
        // The broadcast stays open until some thread has an answer; this one need not be it.
        val pending: PendingResult? = goAsync()
        if (pending == null) {
            // Not called for a broadcast at all: there is nothing to answer and nothing is done.
            Log.w(BLUETOOTH_WRITE_TAG, "adb write refused: not called for a broadcast")
            return
        }

        fun log(outcome: AdbCommand.Outcome, line: String) {
            if (outcome.accepted) Log.d(BLUETOOTH_WRITE_TAG, line) else Log.w(BLUETOOTH_WRITE_TAG, line, outcome.thrown)
        }
        AdbCommand.receive(
            command = { commandOf(intent) },
            // Null when the app is not up: the hook makes no message manager (Koin would, here,
            // when the main screen has not).
            app = { AdbCommand.entryWhenUp(GlobalContext.getOrNull(), arrived) },
            late = ::log,
        ) { outcome, line ->
            try {
                log(outcome, line)
                if (ordered) {
                    pending.resultCode = outcome.kind.resultCode
                    pending.resultData = outcome.answer
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
}
