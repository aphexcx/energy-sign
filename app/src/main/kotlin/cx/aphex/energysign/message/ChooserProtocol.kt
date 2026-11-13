package cx.aphex.energysign.message

import com.google.gson.Gson

/*
 * Chooser mode, app side of the wire contract with the sign (firmware: aphexcx/arduino-led-marquee).
 *
 * The SIGN scrolls the chooser preview. The app sends the message text once and holds no scroll
 * state: no timer, no offset. While the sign shows a chooser message it asks `?` ("anything
 * changed?") instead of `~` ("send the next message"), and the app answers `{}` when nothing has.
 *
 * Nothing in this file touches Android, so all of it runs in JVM unit tests.
 */

/** What the sign asked for in one UART callback. */
enum class SignRequest {
    /** `~`, or anything that is not a poll: send the next message in full. */
    NEXT_MESSAGE,

    /** `?`: the sign is showing a chooser message and asks whether it changed. */
    CHOOSER_POLL,
}

object ChooserProtocol {
    /** The sign keeps its own copy of the chooser text; this is all it has room for. */
    const val CHOOSER_MAX_CHARS: Int = 64

    /** Shown in place of the text when there is no message at the chooser index. */
    const val EMPTY_TEXT: String = "<empty>"

    /** Request byte of a chooser poll. Every other byte means "send the next message". */
    const val POLL_REQUEST: Char = '?'

    /** Reply to a chooser poll when the sign already has the current chooser message. */
    const val UNCHANGED_REPLY: String = "{}"

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
     * What to write in reply to a chooser poll: [UNCHANGED_REPLY] only when the app is in chooser
     * mode AND the chooser JSON it would send now ([nextJson]) is equal to the last message JSON
     * it wrote to the UART. Otherwise [nextJson], the same reply a normal request gets.
     */
    fun pollReply(inChooserMode: Boolean, nextJson: String, lastJsonWritten: String?): String =
        if (inChooserMode && nextJson == lastJsonWritten) UNCHANGED_REPLY else nextJson
}

/**
 * Answers the sign's requests. Holds the one piece of state the poll needs: the last message JSON
 * that was written to the UART.
 *
 * [nextMessage] is evaluated exactly once per request. That is safe for a poll because the message
 * source has no side effects while it is in chooser mode; a message that is not a chooser message
 * has been consumed and is always sent.
 *
 * [write] returns whether the whole reply reached the UART.
 */
class SignResponder(
    private val nextMessage: () -> Message,
    private val write: (String) -> Boolean,
) {
    private var lastJsonWritten: String? = null

    /**
     * Replies to the first [count] bytes of [buffer], read in one UART callback. Returns the
     * message that was sent, or null when a poll was answered with [ChooserProtocol.UNCHANGED_REPLY].
     */
    fun onRequest(buffer: ByteArray, count: Int): Message? {
        val message = nextMessage()
        val json = GSON.toJson(message)

        val isUnchangedPoll = ChooserProtocol.classifyRequest(buffer, count) == SignRequest.CHOOSER_POLL &&
                ChooserProtocol.pollReply(message is Message.Chooser, json, lastJsonWritten) == ChooserProtocol.UNCHANGED_REPLY
        if (isUnchangedPoll) {
            write(ChooserProtocol.UNCHANGED_REPLY)
            return null
        }

        // A message that did not reach the sign must not count as "the sign already has it".
        lastJsonWritten = if (write(json)) json else null
        return message
    }

    companion object {
        /** The serialiser for everything the app writes to the sign. */
        val GSON: Gson = Gson()
    }
}
