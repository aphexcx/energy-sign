package cx.aphex.energysign.message

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cx.aphex.energysign.message.ChooserProtocol.CHOOSER_MAX_CHARS
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The pure parts of the chooser contract: the text rule, the request classifier, the checksum, the poll reply. */
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

    // ---- the checksum (CRC-16/CCITT-FALSE) ----

    @Test
    fun `the CRC has the standard check value`() {
        assertEquals(0x29B1, ChooserProtocol.crc16CcittFalse(bytes("123456789")))
        assertEquals(10673, ChooserProtocol.crc16CcittFalse(bytes("123456789")))
    }

    @Test
    fun `the CRC of no bytes is the initial value`() {
        assertEquals(0xFFFF, ChooserProtocol.crc16CcittFalse(ByteArray(0)))
    }

    @Test
    fun `the seven contract vectors`() {
        val quick = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG AND KEEPS ON RUNNING"
        assertEquals(64, quick.length)

        assertEquals(13345, ChooserProtocol.checksum("HELLO W", "3/12"))
        assertEquals(36894, ChooserProtocol.checksum(quick, "7/12"))
        assertEquals(7805, ChooserProtocol.checksum("<empty>", "1/0"))
        assertEquals(18175, ChooserProtocol.checksum("", "1/1"))
        assertEquals(26634, ChooserProtocol.checksum("A", "1/1"))
        assertEquals(47554, ChooserProtocol.checksum("A", "11/1"))
        assertEquals(46267, ChooserProtocol.checksum("I ${Message.HEART} U", "12/345"))
    }

    @Test
    fun `the zero byte keeps the text and the count apart`() {
        // without the separator both would be the bytes "A11/1"
        assertNotEquals(ChooserProtocol.checksum("A1", "1/1"), ChooserProtocol.checksum("A", "11/1"))
    }

    @Test
    fun `a chooser message is summed over the str and flashy it puts on the wire`() {
        val stored = (1..12).map { Message.Marquee.User(if (it == 3) "HELLO W" else "MESSAGE $it") }
        val message = ChooserProtocol.chooserMessage(stored, 2)
        val json = wireJson(message)

        assertEquals(13345, ChooserProtocol.checksum(message))
        assertEquals(ChooserProtocol.checksum(json["str"].asString, json["flashy"].asString), ChooserProtocol.checksum(message))
    }

    @Test
    fun `a message longer than the limit is summed over the 64 characters that are sent`() {
        val quick = "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG AND KEEPS ON RUNNING"
        val stored = (1..12).map { Message.Marquee.User(if (it == 7) quick + " AND RUNNING AND RUNNING" else "MESSAGE $it") }

        assertEquals(36894, ChooserProtocol.checksum(ChooserProtocol.chooserMessage(stored, 6)))
    }

    @Test
    fun `the empty placeholder is summed like any other text`() {
        assertEquals(7805, ChooserProtocol.checksum(ChooserProtocol.chooserMessage(emptyList(), 0)))
    }

    @Test
    fun `text outside ASCII is summed over its UTF-8 bytes`() {
        val utf8 = "\u00e9".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + bytes("1/1")
        assertEquals(6, utf8.size)

        assertEquals(ChooserProtocol.crc16CcittFalse(utf8), ChooserProtocol.checksum("\u00e9", "1/1"))
    }

    // ---- the poll reply ----

    @Test
    fun `the poll reply is exactly the object c with the decimal checksum`() {
        assertEquals("{\"c\":13345}", ChooserProtocol.pollReply(13345))
        assertEquals("{\"c\":0}", ChooserProtocol.pollReply(0))
        assertEquals("{\"c\":65535}", ChooserProtocol.pollReply(65535))
    }

    @Test
    fun `every checksum gives a reply of plain ASCII with no space, sign, exponent or other key`() {
        val shape = Regex("""\{"c":(0|[1-9][0-9]{0,4})\}""")
        (0..0xFFFF).forEach { n ->
            val reply = ChooserProtocol.pollReply(n)
            assertEquals(n.toString(), shape.matchEntire(reply)?.groupValues?.get(1), "reply for $n was $reply")
        }
    }

    @Test
    fun `the poll reply is never the version 1 empty object and never carries a type`() {
        val reply = JsonParser.parseString(ChooserProtocol.pollReply(7805)).asJsonObject

        assertEquals(setOf("c"), reply.keySet())
        assertEquals(7805, reply["c"].asInt)
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
