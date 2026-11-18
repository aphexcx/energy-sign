package cx.aphex.energysign.adb

import cx.aphex.energysign.message.MessageManager
import java.util.Base64
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * What a COMMAND broadcast asks for: the bytes to hand to the app as one Bluetooth write, or the
 * reason why there are none; and what to answer once the app has had them. No Android class is
 * used in here, so it runs in a JVM test.
 */
sealed class AdbCommand {
    class Bytes(val bytes: ByteArray) : AdbCommand()
    data class Refused(val why: String) : AdbCommand()

    companion object {
        const val ACTION = "cx.aphex.energysign.COMMAND"

        /** A string, sent as its UTF-8 bytes. */
        const val EXTRA_TEXT = "text"

        /**
         * Base64 of the bytes. It wins over [EXTRA_TEXT] when both are there, and it is the way
         * to send line breaks and emoji through two shells unharmed.
         */
        const val EXTRA_B64 = "b64"

        /**
         * The bytes are never changed: nothing is trimmed and no line break is added or taken
         * away, because a Bluetooth write is not either. Only white space INSIDE the base64 text
         * is ignored (some encoders break their output into lines).
         */
        fun fromExtras(text: String?, b64: String?): AdbCommand {
            val bytes = when {
                b64 != null -> try {
                    Base64.getDecoder().decode(b64.filterNot { it.isWhitespace() })
                } catch (e: IllegalArgumentException) {
                    return Refused("bad base64 in $EXTRA_B64 (${e.message})")
                }

                text != null -> text.toByteArray(Charsets.UTF_8)
                else -> return Refused("no extra: give --es $EXTRA_TEXT '...' or --es $EXTRA_B64 <base64>")
            }
            if (bytes.isEmpty()) return Refused("empty: there is nothing to send")
            return Bytes(bytes)
        }

        /**
         * The answer for bytes that were handed on. A line break at the end is pointed out,
         * because the app does not know "!next" followed by a line break as a command, and
         * `echo ... | base64` adds one without saying so.
         */
        fun accepted(bytes: ByteArray): String =
            "ok ${bytes.size} bytes" + if (bytes.last() == '\n'.code.toByte()) " (the last one is a line break)" else ""

        /**
         * Hands [bytes] to [carryOut] and says what came of it. [carryOut] is
         * MessageManager.processNewBytesAndWait: it returns when the thread that owns the messages
         * has carried the command out, throws what that thread threw, and throws a
         * TimeoutException when that thread did not get to the command (no sign link is running).
         * So "ok" means that the owner has run the command and it did not throw; it does not mean
         * queued. It does not mean the app knew the command either: one it does not know
         * ("!nxet", or "!next" with a line break behind it) is run as nothing and is "ok" too,
         * as it is over Bluetooth.
         *
         * Nothing is thrown on from here, an Error included. Whatever the command threw has been
         * thrown and caught where it happened; the caller is the hook's own thread, and to let it
         * die of the report would end the app that the hook is there to drive unattended.
         */
        fun carriedOut(bytes: ByteArray, carryOut: (ByteArray) -> Unit): Outcome = try {
            carryOut(bytes)
            Outcome(accepted(bytes), thrown = null)
        } catch (e: TimeoutException) {
            Outcome("refused: ${e.message} (is the sign link running?)", e)
        } catch (e: Throwable) {
            Outcome("refused: the app threw ${e.javaClass.simpleName} (${e.message})", e)
        }

        /**
         * [carriedOut] on a thread of its own, with the one outcome handed to [done] there. For
         * the receiver: it is called on the main thread, which must never wait for the owner of
         * the messages (OwnerQueue.ask refuses it).
         */
        fun aside(bytes: ByteArray, carryOut: (ByteArray) -> Unit, done: (Outcome) -> Unit): Thread =
            thread(name = "adb-command", isDaemon = true) { done(carriedOut(bytes, carryOut)) }

        /**
         * The hook's one way into the app: [bytes] as one write of the phone, carried out by the
         * owner of [app]'s messages before [done] is told. processNewBytesAndWait hands the owner
         * the same change as processNewBytes, the entry of a Bluetooth write.
         */
        fun aside(bytes: ByteArray, app: MessageManager, done: (Outcome) -> Unit): Thread =
            aside(bytes, app::processNewBytesAndWait, done)
    }

    /** What to answer, and what the app threw when it is a refusal. */
    class Outcome(val answer: String, val thrown: Throwable?) {
        val accepted: Boolean get() = thrown == null
    }
}
