package cx.aphex.energysign.message

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cx.aphex.energysign.message.ChooserProtocol.CHOOSER_MAX_CHARS
import cx.aphex.energysign.message.ChooserProtocol.UNCHANGED_REPLY
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The pure parts of the chooser contract: the text rule, the request classifier, the poll decision. */
class ChooserProtocolTest {

    private val long200 = (1..200).joinToString("") { ('A' + it % 26).toString() }

    private fun bytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    private fun classify(s: String): SignRequest = bytes(s).let { ChooserProtocol.classifyRequest(it, it.size) }

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
            { Message.Marquee.GPTQuery(it) },
            { Message.Marquee.Chonky(it) },
            { Message.Marquee.GPTReply(it) },
        )
        all.forEach { make ->
            assertEquals("TWELVE CHARS", ChooserProtocol.chooserText(make("TWELVE CHARS")))
            assertEquals(long200.take(64), ChooserProtocol.chooserText(make(long200)))
        }
    }

    // ---- request classifier ----

    @Test
    fun `a lone question mark is a chooser poll`() {
        assertEquals(SignRequest.CHOOSER_POLL, classify("?"))
    }

    @Test
    fun `a lone tilde is a normal request`() {
        assertEquals(SignRequest.NEXT_MESSAGE, classify("~"))
    }

    @Test
    fun `the last byte decides when both request bytes were read together`() {
        assertEquals(SignRequest.CHOOSER_POLL, classify("~?"))
        assertEquals(SignRequest.NEXT_MESSAGE, classify("?~"))
        assertEquals(SignRequest.CHOOSER_POLL, classify("??"))
    }

    @Test
    fun `firmware error text followed by a tilde is a normal request`() {
        assertEquals(SignRequest.NEXT_MESSAGE, classify("InvalidInput~"))
        assertEquals(SignRequest.NEXT_MESSAGE, classify("IncompleteInput\r\n~"))
    }

    @Test
    fun `firmware error text followed by a question mark is a poll`() {
        assertEquals(SignRequest.CHOOSER_POLL, classify("InvalidInput?"))
    }

    @Test
    fun `a question mark that is not the last byte is a normal request`() {
        assertEquals(SignRequest.NEXT_MESSAGE, classify("?\n"))
        assertEquals(SignRequest.NEXT_MESSAGE, classify("?InvalidInput"))
    }

    @Test
    fun `bytes that are neither request byte are a normal request`() {
        assertEquals(SignRequest.NEXT_MESSAGE, classify("NoMemory"))
        assertEquals(SignRequest.NEXT_MESSAGE, ChooserProtocol.classifyRequest(byteArrayOf(0, -1, 0x7F), 3))
    }

    @Test
    fun `an empty read is a normal request`() {
        assertEquals(SignRequest.NEXT_MESSAGE, ChooserProtocol.classifyRequest(ByteArray(0), 0))
        assertEquals(SignRequest.NEXT_MESSAGE, ChooserProtocol.classifyRequest(ByteArray(32), 0))
    }

    @Test
    fun `a negative read count is a normal request`() {
        assertEquals(SignRequest.NEXT_MESSAGE, ChooserProtocol.classifyRequest(bytes("?"), -1))
    }

    @Test
    fun `only the bytes that were read count, not the rest of the buffer`() {
        val poll = ByteArray(32).also { it[0] = '?'.code.toByte() }
        assertEquals(SignRequest.CHOOSER_POLL, ChooserProtocol.classifyRequest(poll, 1))

        // a '?' left behind the read count must not turn a '~' into a poll
        assertEquals(SignRequest.NEXT_MESSAGE, ChooserProtocol.classifyRequest(bytes("~?"), 1))
    }

    @Test
    fun `a read count larger than the buffer is clamped to the buffer`() {
        assertEquals(SignRequest.CHOOSER_POLL, ChooserProtocol.classifyRequest(bytes("~?"), 99))
        assertEquals(SignRequest.NEXT_MESSAGE, ChooserProtocol.classifyRequest(bytes("?~"), 99))
    }

    // ---- poll decision ----

    private val chooserJson = """{"str":"HELLO","flashy":"3/12","type":"H"}"""

    @Test
    fun `a poll in chooser mode with nothing changed is answered with two bytes`() {
        assertEquals("{}", UNCHANGED_REPLY)
        assertEquals(UNCHANGED_REPLY, ChooserProtocol.pollReply(true, chooserJson, chooserJson))
    }

    @Test
    fun `a poll in chooser mode with a changed chooser message gets the full message`() {
        val previous = """{"str":"HELLO","flashy":"2/12","type":"H"}"""

        assertEquals(chooserJson, ChooserProtocol.pollReply(true, chooserJson, previous))
    }

    @Test
    fun `a poll before anything was written gets the full message`() {
        assertEquals(chooserJson, ChooserProtocol.pollReply(true, chooserJson, null))
    }

    @Test
    fun `a poll outside chooser mode always gets the full message`() {
        val ordinary = """{"str":"HELLO","type":"D"}"""

        assertEquals(ordinary, ChooserProtocol.pollReply(false, ordinary, ordinary))
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
    fun `a GPTReply message is no longer cut to 5 characters`() {
        val json = wireJson(ChooserProtocol.chooserMessage(listOf(Message.Marquee.GPTReply("RAVEGPT SAYS HELLO")), 0))

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
