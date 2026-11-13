package cx.aphex.energysign.message

import com.google.gson.Gson

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

object ChooserProtocol {
    /** The sign keeps its own copy of the chooser text; this is all it has room for. */
    const val CHOOSER_MAX_CHARS: Int = 64

    /** Shown in place of the text when there is no message at the chooser index. */
    const val EMPTY_TEXT: String = "<empty>"

    /** Request byte of a chooser poll. Every other byte means "send the next message". */
    const val POLL_REQUEST: Char = '?'

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
 * Answers the sign's requests. Keeps no state: every reply is made from what [nextMessage]
 * returns at that moment, never from what was written before.
 *
 * [nextMessage] is evaluated exactly once per request. That is safe for a poll because the message
 * source has no side effects while it is in chooser mode; a message that is not a chooser message
 * has been consumed and is always sent.
 *
 * [write] gets the reply and whether it is a poll report (about five a second in chooser mode;
 * the caller keeps those out of the log).
 */
class SignResponder(
    private val nextMessage: () -> Message,
    private val write: (reply: String, isPollReport: Boolean) -> Unit,
) {
    /**
     * Replies to the first [count] bytes of [buffer], read in one UART callback. Returns the
     * message that was sent, or null when a chooser poll was answered with its state report.
     */
    fun onRequest(buffer: ByteArray, count: Int): Message? {
        val message = nextMessage()

        if (message is Message.Chooser && ChooserProtocol.classifyRequest(buffer, count) == SignRequest.CHOOSER_POLL) {
            write(ChooserProtocol.pollReply(ChooserProtocol.checksum(message)), true)
            return null
        }

        // `~`, garbled bytes, or a poll outside chooser mode: the next message in full.
        write(GSON.toJson(message), false)
        return message
    }

    companion object {
        /** The serialiser for everything the app writes to the sign. */
        val GSON: Gson = Gson()
    }
}
