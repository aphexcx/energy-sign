package cx.aphex.energysign.message

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cx.aphex.energysign.message.ChooserProtocol.CHOOSER_MAX_CHARS
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The pure parts of the chooser contract: the text rule and the chooser message on the wire. */
class ChooserProtocolTest {

    private val long200 = (1..200).joinToString("") { ('A' + it % 26).toString() }

    /** Serialised exactly as the app writes it to the UART. */
    private fun wireJson(message: Message): JsonObject =
        JsonParser.parseString(SignResponder.GSON.toJson(message)).asJsonObject

    // ---- chooser text rule ----

    @Test
    fun `a message shorter than the limit is sent whole`() {
        assertEquals("HELLO DREAMSTATE", ChooserProtocol.chooserText(Message.Marquee.User("HELLO DREAMSTATE")))
    }

    @Test
    fun `a 200 character message is cut to its first 64 characters`() {
        val text = ChooserProtocol.chooserText(Message.Marquee.User(long200))

        assertEquals(64, CHOOSER_MAX_CHARS)
        assertEquals(long200.substring(0, 64), text)
    }

    @Test
    fun `a message of exactly 64 characters is not cut and 65 loses only the last one`() {
        val exactly64 = long200.substring(0, 64)
        val exactly65 = long200.substring(0, 65)

        assertEquals(exactly64, ChooserProtocol.chooserText(Message.Marquee.User(exactly64)))
        assertEquals(exactly64, ChooserProtocol.chooserText(Message.Marquee.User(exactly65)))
    }

    @Test
    fun `no message gives the empty placeholder`() {
        assertEquals("<empty>", ChooserProtocol.chooserText(null))
    }

    @Test
    fun `a stored message with empty text stays empty`() {
        assertEquals("", ChooserProtocol.chooserText(Message.Marquee.User("")))
    }

    @Test
    fun `every marquee type follows the same rule`() {
        val all: List<(String) -> Message.Marquee> = listOf(
            { Message.Marquee.User(it) },
            { Message.Marquee.AIQuery(it) },
            { Message.Marquee.Chonky(it) },
            { Message.Marquee.AIReply(it) },
        )
        all.forEach { make ->
            assertEquals("TWELVE CHARS", ChooserProtocol.chooserText(make("TWELVE CHARS")))
            assertEquals(long200.take(64), ChooserProtocol.chooserText(make(long200)))
        }
    }

    // ---- the chooser message on the wire ----

    private fun stored(n: Int): List<Message.Marquee> = (1..n).map { Message.Marquee.User("MESSAGE $it") }

    @Test
    fun `message 3 of 12 is sent as type H with its text and the count 3 slash 12`() {
        val json = wireJson(ChooserProtocol.chooserMessage(stored(12), 2))

        assertEquals(setOf("type", "str", "flashy"), json.keySet())
        assertEquals("H", json["type"].asString)
        assertEquals("MESSAGE 3", json["str"].asString)
        assertEquals("3/12", json["flashy"].asString)
    }

    @Test
    fun `the chooser JSON is byte for byte what it was before the count became readable`() {
        assertEquals(
            """{"str":"MESSAGE 3","flashy":"3/12","type":"H"}""",
            SignResponder.GSON.toJson(ChooserProtocol.chooserMessage(stored(12), 2))
        )
    }

    @Test
    fun `a 200 character message goes out as 64 characters`() {
        val json = wireJson(ChooserProtocol.chooserMessage(listOf(Message.Marquee.User(long200)), 0))

        assertEquals(long200.take(64), json["str"].asString)
        assertEquals("1/1", json["flashy"].asString)
    }

    @Test
    fun `a Chonky message is no longer cut to 5 characters`() {
        val json = wireJson(ChooserProtocol.chooserMessage(listOf(Message.Marquee.Chonky("CHONKY MESSAGE")), 0))

        assertEquals("CHONKY MESSAGE", json["str"].asString)
        assertEquals("H", json["type"].asString)
    }

    @Test
    fun `a AIReply message is no longer cut to 5 characters`() {
        val json = wireJson(ChooserProtocol.chooserMessage(listOf(Message.Marquee.AIReply("RAVEGPT SAYS HELLO")), 0))

        assertEquals("RAVEGPT SAYS HELLO", json["str"].asString)
        assertEquals("H", json["type"].asString)
    }

    @Test
    fun `an empty list is sent as the empty placeholder with the count it has today`() {
        val json = wireJson(ChooserProtocol.chooserMessage(emptyList(), 0))

        assertEquals(setOf("type", "str", "flashy"), json.keySet())
        assertEquals("<empty>", json["str"].asString)
        assertEquals("1/0", json["flashy"].asString)
    }

    @Test
    fun `an index outside the list is sent as the empty placeholder`() {
        // after the last message is deleted the manager's index is -1
        val json = wireJson(ChooserProtocol.chooserMessage(emptyList(), -1))

        assertEquals("<empty>", json["str"].asString)
        assertEquals("0/0", json["flashy"].asString)
    }
}
