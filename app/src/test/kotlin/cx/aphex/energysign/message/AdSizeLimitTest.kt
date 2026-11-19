package cx.aphex.energysign.message

import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size limit on its own, with numbers that do not come from the code under test: the bytes
 * are those the real responder writes for a request, and the limit is written out, 500.
 */
class AdSizeLimitTest {

    private val warnings = mutableListOf<String>()

    /** A one-by-one ad of [text] in a colour and with a delay whose JSON without the text is 55 bytes. */
    private fun ad(text: String): OneByOneMessage =
        AdSizeLimit.fit(text, { warnings += it }) { OneByOneMessage(it, 0xFED43B, 1000) }

    /** What the sign gets when it asks and [message] is next: the bytes of the responder's answer. */
    private fun bytesOnTheWire(message: Message): Int {
        var frame: String? = null
        SignResponder(nextMessage = { message }, write = { reply, _ -> frame = reply; true })
            .onRequest(byteArrayOf('~'.code.toByte()), 1)
        return frame!!.toByteArray(Charsets.UTF_8).size
    }

    @Test
    fun `the JSON around the text of this ad is 55 bytes`() {
        assertEquals(55, bytesOnTheWire(OneByOneMessage("", 0xFED43B, 1000)))
        assertEquals(
            """{"str":"","dly":1000,"type":"O","r":254,"g":212,"b":59}""",
            SignResponder.GSON.toJson(OneByOneMessage("", 0xFED43B, 1000))
        )
    }

    @Test
    fun `an answer of exactly 500 bytes goes out whole`() {
        val ad = ad("A".repeat(445))

        assertEquals(445, ad.str.length)
        assertEquals(500, bytesOnTheWire(ad))
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `an answer that would be 501 bytes is cut to 500`() {
        val ad = ad("A".repeat(446))

        assertEquals(445, ad.str.length)
        assertEquals(500, bytesOnTheWire(ad))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a character that is six bytes on the wire is counted as six`() {
        // 74 ampersands are 444 bytes, 75 are 450: 55 + 450 is over
        val fits = ad("&".repeat(74))
        assertEquals(74, fits.str.length)
        assertEquals(499, bytesOnTheWire(fits))
        assertEquals(emptyList(), warnings)

        val cut = ad("&".repeat(75))
        assertEquals(74, cut.str.length)
        assertEquals(499, bytesOnTheWire(cut))
        assertEquals(1, warnings.size)
    }

    @Test
    fun `no answer is over 500 bytes, whatever the text`() {
        listOf("A", "&", "'", "<", "=", "\u007F", "\u000B", "it's <b> & ", "\\", "\"").forEach { piece ->
            val bytes = bytesOnTheWire(ad(piece.repeat(900)))
            assertTrue(bytes <= 500, "$bytes bytes for a text of >$piece<")
            assertTrue(bytes > 500 - 6 * piece.length, "as much as fits: $bytes bytes for >$piece<")
        }
    }

    @Test
    fun `a cut never falls between the two halves of a character`() {
        val smiley = String(intArrayOf(0x1F600), 0, 1) // two chars in a String, four bytes on the wire
        // 55 + 442 + 4 = 501: the smiley does not fit, and half of it must not stay
        val ad = ad("A".repeat(442) + smiley)

        assertEquals("A".repeat(442), ad.str)
        assertEquals(497, bytesOnTheWire(ad))

        val smileys = ad(smiley.repeat(300))
        assertEquals(smiley.repeat(111), smileys.str, "111 whole smileys: 55 + 444")
        assertEquals(499, bytesOnTheWire(smileys))
    }
}
