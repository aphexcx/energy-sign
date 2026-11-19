package cx.aphex.energysign.message

import com.google.gson.Gson
import java.lang.reflect.Modifier

/*
 * Chooser mode, app side of the wire contract with the sign (firmware: aphexcx/arduino-led-marquee).
 * Contract VERSION 2.
 *
 * The SIGN scrolls the chooser preview. The app sends the message text once and holds no scroll
 * state: no timer, no offset, and no memory of what it sent. While the sign shows a chooser
 * message it asks `?` instead of `~` ("send the next message"), and the app answers with a state
 * report: `{"c":N}`, N = the checksum of the chooser message it would send right now. The sign
 * compares N with the checksum of what it is showing and asks `~` when they differ.
 *
 * Nothing in this file touches Android, so all of it runs in JVM unit tests.
 */

/** What the sign asked for in one UART callback. */
enum class SignRequest {
    /** `~`, or anything that is not a poll: send the next message in full. */
    NEXT_MESSAGE,

    /** `?`: the sign is showing a chooser message and asks which one the app is on now. */
    CHOOSER_POLL,
}

/** What a reply is to the sign. */
enum class Reply {
    /** The state report to a chooser poll. The sign stays in its chooser display. */
    REPORT,

    /** A chooser message. The sign is in its chooser display with it. */
    CHOOSER,

    /** A message of any other kind. The sign leaves its chooser display to show it. */
    MESSAGE,
}

object ChooserProtocol {
    /** The sign keeps its own copy of the chooser text; this is all it has room for. */
    const val CHOOSER_MAX_CHARS: Int = 64

    /** Shown in place of the text when there is no message at the chooser index. */
    const val EMPTY_TEXT: String = "<empty>"

    /** The longest frame the sign can take (its buffer). A longer one is not sent. */
    const val MAX_FRAME_BYTES: Int = 700

    /** Request byte of a chooser poll. Every other byte means "send the next message". */
    const val POLL_REQUEST: Char = '?'

    /** Request byte of "send the next message". The sign sends this one or [POLL_REQUEST], then waits. */
    const val NEXT_REQUEST: Char = '~'

    /**
     * The chooser text for [message]: from its first character, cut at [CHOOSER_MAX_CHARS], no
     * ellipsis. One rule for every message type; the sign decides what fits and scrolls the rest.
     */
    fun chooserText(message: Message.Marquee?): String =
        message?.str?.take(CHOOSER_MAX_CHARS) ?: EMPTY_TEXT

    /** The chooser message for position [idx] (0-based) of [messages]; the count shown is 1-based. */
    fun chooserMessage(messages: List<Message.Marquee>, idx: Int): Message.Chooser =
        Message.Chooser(
            currentIndex = idx + 1,
            lastIndex = messages.size,
            currentMessage = Message.Marquee.User(chooserText(messages.getOrNull(idx)))
        )

    /**
     * Classifies the first [count] bytes of [buffer], read from the sign in one UART callback.
     * Only the LAST byte read decides: `?` is a chooser poll. Everything else is a normal request,
     * including an empty read and the error names the firmware prints before its next `~`.
     */
    fun classifyRequest(buffer: ByteArray, count: Int): SignRequest {
        val lastByte = buffer.getOrNull(count.coerceAtMost(buffer.size) - 1)
        return if (lastByte == POLL_REQUEST.code.toByte()) SignRequest.CHOOSER_POLL else SignRequest.NEXT_MESSAGE
    }

    /**
     * CRC-16/CCITT-FALSE of [bytes]: width 16, polynomial 0x1021, initial value 0xFFFF, no
     * reflection, no final xor. The check value over the ASCII bytes `123456789` is 0x29B1.
     */
    fun crc16CcittFalse(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /**
     * The contract's checksum of a chooser message, 0..65535: the CRC of the UTF-8 bytes of
     * [str] (as sent, already cut to [CHOOSER_MAX_CHARS]), one zero byte, the UTF-8 bytes of
     * [flashy]. The sign computes the same over the bytes it stored.
     */
    fun checksum(str: String, flashy: String): Int =
        crc16CcittFalse(str.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + flashy.toByteArray(Charsets.UTF_8))

    /** The checksum of [message], over the same `str` and `flashy` that go into its JSON. */
    fun checksum(message: Message.Chooser): Int = checksum(message.str, message.flashy)

    /**
     * The reply to a chooser poll while the app is in chooser mode: exactly `{"c":N}`, N in
     * decimal, no spaces and no other key. Built by hand because an 8-bit parser reads it.
     */
    fun pollReply(checksum: Int): String = "{\"c\":$checksum}"
}

/**
 * Does [cleanUp] after this failure and returns this failure, for the caller to throw on. When
 * the clean-up fails too, its failure is attached to this one (suppressed): it does not take
 * its place, as it would when thrown from a `catch` or a `finally`. An Error of the clean-up
 * is not held back: it goes on as it is.
 */
internal inline fun <T : Throwable> T.keptThrough(crossinline cleanUp: () -> Unit): T {
    try {
        cleanUp()
    } catch (second: Exception) {
        if (second !== this) addSuppressed(second)
    }
    return this
}

/**
 * Answers the sign's requests. Keeps no state: every reply is made from what [nextMessage]
 * returns at that moment, never from what was written before.
 *
 * [nextMessage] is evaluated exactly once per request. That is safe for a poll because the message
 * source has no side effects while it is in chooser mode; a message that is not a chooser message
 * has been consumed and is always sent.
 *
 * [write] gets the reply and what it is to the sign (a poll report goes out about five times a
 * second in chooser mode; the caller keeps those out of the log), and says whether all of it
 * went out. A message that did not go out is handed to [unsent]: it was taken for nothing.
 * The same when [write] throws; what it threw goes on to the caller.
 * A message whose frame is longer than the sign can take is not written at all: the sign gets
 * nothing for that request and asks again, and gets the message after it. [tooLong] hears of it;
 * it is not handed back (it would be the next message again, for ever). A stored message stays
 * in the list.
 *
 * Called on the sign link thread, one call at a time, and only for a request the sign really
 * made: [nextMessage] takes the message it returns.
 */
class SignResponder(
    private val nextMessage: () -> Message,
    private val write: (frame: String, reply: Reply) -> Boolean,
    private val unsent: (message: Message) -> Unit = {},
    private val tooLong: (message: Message, bytes: Int) -> Unit = { _, _ -> },
) {
    /**
     * Replies to the first [count] bytes of [buffer], one request of the sign. Returns the
     * message that was sent; null when a chooser poll was answered with its state report, and
     * when the message could not be written.
     */
    fun onRequest(buffer: ByteArray, count: Int): Message? {
        val message = nextMessage()

        if (message is Message.Chooser && ChooserProtocol.classifyRequest(buffer, count) == SignRequest.CHOOSER_POLL) {
            write(ChooserProtocol.pollReply(ChooserProtocol.checksum(message)), Reply.REPORT)
            return null
        }

        // `~`, or a poll outside chooser mode: the next message in full.
        val frame = GSON.toJson(message)
        val bytes = frame.toByteArray().size
        if (bytes > ChooserProtocol.MAX_FRAME_BYTES) {
            tooLong(message, bytes)
            return null
        }
        val written = try {
            write(frame, if (message is Message.Chooser) Reply.CHOOSER else Reply.MESSAGE)
        } catch (e: Throwable) {
            // Not on the wire as far as anybody can say: handed back like a write that says so.
            throw e.keptThrough { unsent(message) }
        }
        if (!written) unsent(message)
        return if (written) message else null
    }

    companion object {
        /** The serialiser for everything the app writes to the sign. */
        val GSON: Gson = Gson()

        /**
         * Gson builds the writer for a class by reflection the first time it writes one. On the
         * Pico that was 140 ms inside the first reply after the app started and 76 ms inside
         * the first icon. The link does this once before its first read, so an answer never
         * races a background warm-up. Returns how many classes it prepared before [stopped].
         */
        fun warmUp(stopped: () -> Boolean = { false }): Int {
            var prepared = 0
            var firstFailure: RuntimeException? = null
            fun visit(type: Class<*>): Boolean {
                if (Message::class.java.isAssignableFrom(type) && !Modifier.isAbstract(type.modifiers)) {
                    if (stopped()) return false
                    try {
                        GSON.getAdapter(type)
                        prepared++
                    } catch (e: RuntimeException) {
                        if (firstFailure == null) firstFailure = e
                    }
                }
                return type.declaredClasses.all(::visit)
            }
            if (visit(Message::class.java) && !stopped()) {
                try {
                    GSON.toJson(ChooserProtocol.chooserMessage(emptyList(), 0))
                } catch (e: RuntimeException) {
                    if (firstFailure == null) firstFailure = e
                }
            }
            firstFailure?.let { throw it }
            return prepared
        }
    }
}
