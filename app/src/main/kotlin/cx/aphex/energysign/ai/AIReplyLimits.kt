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
     * [reply] as it will be shown and stored, or "" when nothing whole can be.
     *
     * 1. The clean-up every message gets (hearts, then ASCII only), so what is measured is what
     *    the sign gets.
     * 2. ONE LINE. A line end, a tab or a vertical tab becomes a space. The saved list holds one
     *    message a line and parts it at a vertical tab: either one inside a reply would cost the
     *    rest of the reply at the next start.
     * 3. Too long: cut after the last whole word that fits. When not even the first word fits,
     *    the result is "": half a word is not a reply.
     */
    fun fitToSign(reply: String): String {
        val text = reply.convertHeartEmojis().toNormalized()
            .map { if (it.code < ' '.code) ' ' else it }.joinToString("")
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
