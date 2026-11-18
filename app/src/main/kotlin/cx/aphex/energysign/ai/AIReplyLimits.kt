package cx.aphex.energysign.ai

import com.google.gson.Gson
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.message.Message

/**
 * What a reply must be before it is shown or saved: text the sign can show, on one line, small
 * enough for the sign, and WHOLE. The sign's firmware takes at most 700 bytes of JSON for one
 * message (MAX_ARDUINO_JSON_SIZE in EnergySignUartManager, which only logs when a message is
 * bigger and sends it anyway). The prompt asks the model for a short reply; this makes sure of it.
 */
object AIReplyLimits {
    /** The firmware's limit for one message, in bytes of JSON. */
    const val MAX_SIGN_JSON_BYTES = 700

    /** What a reply may take, with room to spare under [MAX_SIGN_JSON_BYTES]. */
    const val MAX_REPLY_JSON_BYTES = 660

    private val gson = Gson()
    private val SPACES = Regex(" +")

    /**
     * The most of a reply that is looked at. What can be shown of a reply is some hundred
     * characters; this only keeps the work on a text of any length within bounds.
     */
    const val MAX_INPUT_CHARS = 8_192

    /**
     * [reply] as it will be shown and stored, or "" when nothing whole can be.
     *
     * 1. ONE LINE, and words stay words: everything that parts words becomes a plain space
     *    FIRST. That is a line end, a tab or a vertical tab (the saved list holds one message a
     *    line and parts it at a vertical tab: either one inside a reply would cost the rest of
     *    the reply at the next start), every other control character, and every space or
     *    separator of Unicode (a no-break space, a line separator): the next step drops what is
     *    not ASCII, and two words must not become one by it. One character that Unicode calls
     *    a control character is none on the sign: [Message.HEART] (U+007F) is its heart, and
     *    it stays.
     * 2. The clean-up every message gets (hearts, then ASCII only), so what is measured is what
     *    the sign gets.
     * 3. Too long: cut after the last whole word that fits. When not even the first word fits,
     *    the result is "": half a word is not a reply.
     *
     * The work grows with the length of [reply]. It is never done by the thread that answers
     * the sign: see MessageRepository.replyMessage.
     */
    fun fitToSign(reply: String): String {
        val text = reply.take(MAX_INPUT_CHARS)
            .map { if (it != Message.HEART && (it.isWhitespace() || it.isISOControl())) ' ' else it }.joinToString("")
            // A text that was longer than what is looked at may have been cut inside a word:
            // the word at the cut goes. Half a word is not a reply.
            .let { if (reply.length > MAX_INPUT_CHARS) it.substringBeforeLast(' ', "") else it }
            .convertHeartEmojis().toNormalized()
            .replace(SPACES, " ")
            .trim()
        if (fits(text)) return text

        // The longest start of the text that fits. The size only grows with the length.
        var fitting = 0
        var tooLong = text.length
        while (tooLong - fitting > 1) {
            val middle = (fitting + tooLong) / 2
            if (fits(text.substring(0, middle))) fitting = middle else tooLong = middle
        }
        // The start ends at the end of a word when a space comes next; otherwise it is cut back
        // to the space before it. With no space before it there is no whole word at all.
        val end = if (text[fitting] == ' ') fitting else text.lastIndexOf(' ', (fitting - 1).coerceAtLeast(0))
        return if (end <= 0) "" else text.substring(0, end).trimEnd()
    }

    private fun fits(text: String): Boolean = jsonBytes(text) <= MAX_REPLY_JSON_BYTES

    /** The size of the message the sign receives for [text]: the same class, the same Gson. */
    private fun jsonBytes(text: String): Int =
        gson.toJson(Message.Marquee.Chonky(text)).toByteArray(Charsets.UTF_8).size
}
