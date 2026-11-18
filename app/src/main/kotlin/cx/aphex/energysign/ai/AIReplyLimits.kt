package cx.aphex.energysign.ai

import com.google.gson.Gson
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.message.Message

/**
 * The hard cap on a reply. The sign's firmware takes at most 700 bytes of JSON for one message
 * (MAX_ARDUINO_JSON_SIZE in EnergySignUartManager, which only logs when a message is bigger and
 * sends it anyway). The prompt asks the model for a short reply; this makes sure of it.
 */
object AIReplyLimits {
    /** The firmware's limit for one message, in bytes of JSON. */
    const val MAX_SIGN_JSON_BYTES = 700

    /** What a reply may take, with room to spare under [MAX_SIGN_JSON_BYTES]. */
    const val MAX_REPLY_JSON_BYTES = 660

    private val gson = Gson()

    /**
     * [reply] as it will be stored, cut at a word boundary when its message would be too big.
     *
     * The clean-up the repository applies to every reply (hearts, then ASCII only) is applied
     * here first, so what is measured is what the sign gets; applying it again changes nothing.
     */
    fun fitToSign(reply: String): String {
        val text = reply.convertHeartEmojis().toNormalized().trim()
        if (jsonBytes(text) <= MAX_REPLY_JSON_BYTES) return text

        // The longest start of the text that fits. The size only grows with the length.
        var fits = 0
        var tooLong = text.length
        while (tooLong - fits > 1) {
            val middle = (fits + tooLong) / 2
            if (jsonBytes(text.substring(0, middle)) <= MAX_REPLY_JSON_BYTES) fits = middle else tooLong = middle
        }
        val cutInsideAWord = !text[fits].isWhitespace() && !text[fits - 1].isWhitespace()
        val lastSpace = text.lastIndexOf(' ', fits - 1)
        val end = if (cutInsideAWord && lastSpace > 0) lastSpace else fits
        return text.substring(0, end).trimEnd()
    }

    /** The size of the message the sign receives for [text]: the same class, the same Gson. */
    private fun jsonBytes(text: String): Int =
        gson.toJson(Message.Marquee.Chonky(text)).toByteArray(Charsets.UTF_8).size
}
