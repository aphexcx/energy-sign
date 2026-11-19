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

        assertTrue(wireBytes(fitted) < 700, "${wireBytes(fitted)} bytes")
        assertTrue(words.startsWith(fitted), "the cut text is the start of the reply")
        val lastWord = fitted.substringAfterLast(' ')
        assertTrue(Regex("word\\d+").matches(lastWord), "cut inside a word: '$lastWord'")
        assertEquals(' ', words[fitted.length], "the cut is at a space")
        assertTrue(fitted.length > 300, "cut far too early: ${fitted.length} characters")
    }

    @Test
    fun `line ends, tabs and vertical tabs become spaces - a reply is one line`() {
        // The saved list holds one message a line and parts it at a vertical tab.
        assertEquals("first second third fourth fifth", AIReplyLimits.fitToSign("first\nsecond\r\nthird\u000Bfourth\t\tfifth"))
        assertEquals("a b", AIReplyLimits.fitToSign("\n a \n\n b \n"))
    }

    @Test
    fun `a long reply broken into lines is cut after a whole word`() {
        // Several starts, so that the cut cannot fall between two words by chance.
        for (shift in 1..8) {
            val lines = "x".repeat(shift) + "\n" + (1..400).joinToString("\n") { "word$it" }

            val fitted = AIReplyLimits.fitToSign(lines)

            assertTrue(wireBytes(fitted) < 700, "${wireBytes(fitted)} bytes")
            assertFalse(fitted.contains('\n'))
            assertTrue(Regex("word\\d+").matches(fitted.substringAfterLast(' ')), "shift $shift: cut inside a word")
            assertEquals('\n', lines[fitted.length], "shift $shift: the cut is where a word ended")
            assertTrue(fitted.length > 300, "cut far too early: ${fitted.length} characters")
        }
    }

    @Test
    fun `characters that grow in JSON are counted as they go on the wire`() {
        // Gson writes an apostrophe as six bytes and a quote as two.
        val quotes = (1..200).joinToString(" ") { "it's \"so\" <loud>" }

        val fitted = AIReplyLimits.fitToSign(quotes)

        assertTrue(wireBytes(fitted) < 700, "${wireBytes(fitted)} bytes")
        assertTrue(fitted.isNotBlank())
    }

    @Test
    fun `when not one whole word fits there is nothing - half a word is not a reply`() {
        assertEquals("", AIReplyLimits.fitToSign("A".repeat(5_000)))
        // 640 letters are one byte over what a reply may take; 639 fit.
        assertEquals("", AIReplyLimits.fitToSign("A".repeat(640)))
        assertEquals("A".repeat(639), AIReplyLimits.fitToSign("A".repeat(639)))
    }

    @Test
    fun `a first word that fits is kept when the second one is endless`() {
        assertEquals("Hi", AIReplyLimits.fitToSign("Hi " + "A".repeat(5_000)))
    }

    @Test
    fun `what the sign is sent for a reply is under 700 bytes, measured on the wire`() {
        // 700 is the firmware's number, written out here on purpose: this test must not move
        // when a constant of the app does. The bytes are those SignResponder writes.
        val written = mutableListOf<String>()
        for (reply in listOf(
            (1..400).joinToString(" ") { "word$it" },
            (1..200).joinToString(" ") { "it's \"so\" <loud> & =" },
            "A".repeat(639),
        )) {
            val stored = Message.Marquee.Chonky(AIReplyLimits.fitToSign(reply).uppercase())
            cx.aphex.energysign.message.SignResponder(nextMessage = { stored }, write = { json, _ -> written.add(json); true })
                .onRequest("~".toByteArray(), 1)
        }

        assertEquals(3, written.size)
        for (json in written) {
            val bytes = json.toByteArray(Charsets.UTF_8).size
            assertTrue(bytes < 700, "$bytes bytes on the wire")
            assertTrue(bytes > 600, "only $bytes bytes: cut far too early")
        }
    }

    @Test
    fun `the reply is measured as the sign will get it, after the same clean-up`() {
        // The repository strips what the sign cannot show; the cap must not count those characters.
        val emoji = (1..300).joinToString(" ") { "ok 😀" }

        val fitted = AIReplyLimits.fitToSign(emoji)

        assertEquals(fitted, fitted.convertHeartEmojis().toNormalized(), "nothing left for the repository to change")
        assertFalse(fitted.contains("\uD83D"))
        assertTrue(wireBytes(fitted) < 700)
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

    // ---- words stay words (a minor of the deep gate)

    @Test
    fun `a reply in another language - the literal text the sign gets`() {
        // Spelled out here, not taken from the code under test.
        assertEquals("Qu pasa, Espaa! Ms bajo?", AIReplyLimits.fitToSign("¡Qué pasa, España! ¿Más bajo?"))
    }

    @Test
    fun `what parts two words in Unicode parts them on the sign - a separator is a space before anything is dropped`() {
        assertEquals("one two", AIReplyLimits.fitToSign("one two"), "a line separator")
        assertEquals("one two", AIReplyLimits.fitToSign("one two"), "a paragraph separator")
        assertEquals("one two", AIReplyLimits.fitToSign("one two"), "a no-break space")
        assertEquals("one two", AIReplyLimits.fitToSign("one two"), "a narrow no-break space")
        assertEquals("one two", AIReplyLimits.fitToSign("one　two"), "an ideographic space")
        assertEquals("one two", AIReplyLimits.fitToSign("one\u0085two"), "a next-line control")
    }

    @Test
    fun `two hundred words parted by no-break spaces are words - the reply is cut after a whole one, not thrown away`() {
        val fitted = AIReplyLimits.fitToSign((1..200).joinToString(" ") { "beat" })

        assertTrue(fitted.startsWith("beat beat beat"), fitted.take(30))
        assertTrue(fitted.endsWith("beat"), fitted.takeLast(10))
        assertTrue(fitted.split(" ").all { it == "beat" }, "a word was cut or two were joined")
        assertTrue(fitted.length > 400, "most of what fits is kept: ${fitted.length} characters")
    }

    @Test
    fun `a text of any length is looked at up to a fixed number of characters`() {
        assertEquals(8_192, AIReplyLimits.MAX_INPUT_CHARS)
        // What the sign cannot show, 20 000 times, and then a word: the word is beyond what is looked at.
        assertEquals("", AIReplyLimits.fitToSign("☃".repeat(20_000) + " late"))
        // Within it, it is found.
        assertEquals("early", AIReplyLimits.fitToSign("☃".repeat(8_000) + " early"))

        val startedAt = System.nanoTime()
        val fitted = AIReplyLimits.fitToSign("word ".repeat(2_000_000))
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(fitted.endsWith("word"))
        assertTrue(tookMs < 2_000, "ten megabytes took $tookMs ms: the work grew with the text")
    }

    // ---- found by the review of round 3

    @Test
    fun `a text that is cut where it stops being looked at loses the word at the cut - half a word is not a reply`() {
        // The cut falls inside "hello": what the sign cannot show, and then "he".
        assertEquals("", AIReplyLimits.fitToSign("\u2603".repeat(8_190) + "hello world"))
        // With words before the cut, the whole ones stay and the half one goes.
        val fitted = AIReplyLimits.fitToSign("\u2603".repeat(8_180) + " ok then hello world")
        assertEquals("ok then", fitted)
        // A text that is not cut keeps its last word.
        assertEquals("ok then hello", AIReplyLimits.fitToSign("\u2603".repeat(100) + " ok then hello"))
    }

    // ---- round 3 of the gates

    @Test
    fun `the sign's own heart is kept - it is a character of the sign, not a control character to drop`() {
        val heart = cx.aphex.energysign.message.Message.HEART

        assertEquals("$heart", AIReplyLimits.fitToSign("$heart"))
        assertEquals("I $heart THIS SET", AIReplyLimits.fitToSign(" I $heart THIS SET\n"))
        assertEquals("a b", AIReplyLimits.fitToSign("a\u0007b"), "every other control character still parts words")
        assertEquals("a b", AIReplyLimits.fitToSign("a\u0080b"))
    }
}
