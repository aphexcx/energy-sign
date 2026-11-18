package cx.aphex.energysign.ai

import com.google.gson.Gson
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.message.Message
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hard cap: whatever the model writes, the JSON the sign receives stays under 700 bytes. */
class AIReplyLimitsTest {

    /** The bytes the sign would get for this reply: the same Gson, the same message class. */
    private fun wireBytes(reply: String): Int =
        Gson().toJson(Message.Marquee.Chonky(reply.uppercase())).toByteArray(Charsets.UTF_8).size

    @Test
    fun `a reply that fits is not changed`() {
        val reply = "The bass is fine, your earplugs are just working."

        assertEquals(reply, AIReplyLimits.fitToSign(reply))
    }

    @Test
    fun `a reply that is too long is cut at a word boundary and fits`() {
        val words = (1..400).joinToString(" ") { "word$it" }

        val fitted = AIReplyLimits.fitToSign(words)

        assertTrue(wireBytes(fitted) < AIReplyLimits.MAX_SIGN_JSON_BYTES, "${wireBytes(fitted)} bytes")
        assertTrue(words.startsWith(fitted), "the cut text is the start of the reply")
        val lastWord = fitted.substringAfterLast(' ')
        assertTrue(Regex("word\\d+").matches(lastWord), "cut inside a word: '$lastWord'")
        assertEquals(' ', words[fitted.length], "the cut is at a space")
        assertTrue(fitted.length > 300, "cut far too early: ${fitted.length} characters")
    }

    @Test
    fun `characters that grow in JSON are counted as they go on the wire`() {
        // Gson writes an apostrophe as six bytes and a quote as two.
        val quotes = (1..200).joinToString(" ") { "it's \"so\" <loud>" }

        val fitted = AIReplyLimits.fitToSign(quotes)

        assertTrue(wireBytes(fitted) < AIReplyLimits.MAX_SIGN_JSON_BYTES, "${wireBytes(fitted)} bytes")
        assertTrue(fitted.isNotBlank())
    }

    @Test
    fun `one endless word is cut hard and still fits`() {
        val fitted = AIReplyLimits.fitToSign("A".repeat(5000))

        assertTrue(wireBytes(fitted) < AIReplyLimits.MAX_SIGN_JSON_BYTES, "${wireBytes(fitted)} bytes")
        assertTrue(fitted.length > 300)
    }

    @Test
    fun `the reply is measured as the sign will get it, after the same clean-up`() {
        // The repository strips what the sign cannot show; the cap must not count those characters.
        val emoji = (1..300).joinToString(" ") { "ok 😀" }

        val fitted = AIReplyLimits.fitToSign(emoji)

        assertEquals(fitted, fitted.convertHeartEmojis().toNormalized(), "nothing left for the repository to change")
        assertFalse(fitted.contains("\uD83D"))
        assertTrue(wireBytes(fitted) < AIReplyLimits.MAX_SIGN_JSON_BYTES)
    }

    @Test
    fun `a reply in another language gets the same clean-up the repository gives every message`() {
        val spanish = "\u00A1Qu\u00E9 pasa, Espa\u00F1a! \u00BFM\u00E1s bajo?"

        val fitted = AIReplyLimits.fitToSign(spanish)

        assertEquals(spanish.convertHeartEmojis().toNormalized().trim(), fitted)
        assertTrue(fitted.all { it.code < 128 }, "only ASCII reaches the sign: $fitted")
        assertTrue(fitted.contains("pasa"), fitted)
    }

    @Test
    fun `white space around the reply is dropped`() {
        assertEquals("Hydrate.", AIReplyLimits.fitToSign("  \n Hydrate. \n"))
        assertEquals("", AIReplyLimits.fitToSign(" \n "))
    }
}
