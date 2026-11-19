package cx.aphex.energysign.message

import com.google.gson.JsonParser
import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdCodecTest {

    /** What the app writes to the sign for [message]: the thing a restart must not change. */
    private fun wire(message: Message): String = SignResponder.GSON.toJson(message)

    private fun wire(ads: List<Message>): List<String> = ads.map(::wire)

    private val everyKindOfAd: List<Message> = listOf(
        ChonkySlide(" HEY", 0xff0080, 2000),
        ChonkySlide("SCROLL", 0x1da1f2, 3000, shouldScrollToLastLetter = true),
        OneByOneMessage("SAY HI!!${Message.HEART}", 0xff055d, 2000),
        OneByOneMessage("it's <b> & a=b", 0x04ff19, 30000),
        Message.NowPlayingTrackMessage(""),
        IconInvaders.Enemy1(0xfed43b),
        IconInvaders.Enemy2(0x4f62d2),
        IconInvaders.Explosion(0xef0c1d),
        IconInvaders.ANJUNA(0x1da1f2),
        IconInvaders.BAAAHS(0xff7700),
        IconInvaders.DREAMSTATE(0x8205b4),
        IconInvaders.EDC(0x04ff19),
    )

    @Test
    fun `every kind of ad comes back as the same class and the same JSON for the sign`() {
        val loaded = AdCodec.decode(AdCodec.encode(everyKindOfAd))

        assertEquals(everyKindOfAd.map { it::class }, loaded.map { it::class })
        assertEquals(wire(everyKindOfAd), wire(loaded))
    }

    @Test
    fun `a list saved twice is the same text`() {
        val once = AdCodec.encode(everyKindOfAd)

        assertEquals(once, AdCodec.encode(AdCodec.decode(once)))
    }

    @Test
    fun `the saved list holds exactly what goes to the sign and no other field`() {
        val saved = JsonParser.parseString(AdCodec.encode(everyKindOfAd)).asJsonArray

        assertEquals(wire(everyKindOfAd).map(JsonParser::parseString), saved.toList())
    }

    @Test
    fun `a one by one ad comes back equal, colour included`() {
        val ad = OneByOneMessage("@SOMEONE", 0xef0c1d, 4000)

        assertEquals(listOf<Message>(ad), AdCodec.decode(AdCodec.encode(listOf(ad))))
    }

    /**
     * Written by hand in the shape of the file on the sign: the keys in the order Android
     * writes them (not the order of this JVM), `scroll` on the chonky ads only, the heart of an
     * older version still a `<3`, and the icon, which has no `dly`, last.
     */
    private val fileFromTheSign = """[{"dly":1000,"scroll":false,"str":" HI.","type":"C","b":242,"g":161,"r":29},""" +
            """{"dly":2000,"scroll":true,"str":"PARTY!","type":"C","b":128,"g":0,"r":255},""" +
            """{"dly":4000,"str":"@SOMEONE","type":"O","b":29,"g":12,"r":239},""" +
            """{"dly":2000,"str":"HUGS <3","type":"O","b":93,"g":5,"r":255},""" +
            """{"str":"","type":"T"},""" +
            """{"str":"1","type":"I","b":59,"g":212,"r":254}]"""

    @Test
    fun `a file in the shape of the one on the sign loads as it is`() {
        val ads = AdCodec.decode(fileFromTheSign)

        assertEquals(6, ads.size)

        val first = assertIs<ChonkySlide>(ads[0])
        assertEquals(" HI.", first.str, "the space at the start stays")
        assertEquals(1000, first.delayMs)
        assertEquals(false, first.shouldScrollToLastLetter)
        assertEquals(listOf(29, 161, 242), listOf(first.r, first.g, first.b))

        val second = assertIs<ChonkySlide>(ads[1])
        assertEquals(true, second.shouldScrollToLastLetter)
        assertEquals(2000, second.delayMs)

        val third = assertIs<OneByOneMessage>(ads[2])
        assertEquals("@SOMEONE", third.str)
        assertEquals(4000, third.delayMs)
        assertEquals(listOf(239, 12, 29), listOf(third.r, third.g, third.b))

        assertEquals("HUGS <3", assertIs<OneByOneMessage>(ads[3]).str, "a saved text is not changed by loading")
        assertEquals("", assertIs<Message.NowPlayingTrackMessage>(ads[4]).str)

        val icon = assertIs<IconInvaders.Enemy1>(ads[5])
        assertEquals(listOf(254, 212, 59), listOf(icon.r, icon.g, icon.b))
    }

    @Test
    fun `what goes to the sign after loading that file is what the file holds`() {
        val expected = JsonParser.parseString(fileFromTheSign).asJsonArray.toList()

        val sent = AdCodec.decode(fileFromTheSign).map { JsonParser.parseString(wire(it)) }

        assertEquals(expected, sent)
    }

    @Test
    fun `a chonky or one by one ad without a dly stays one second`() {
        val ads = AdCodec.decode(
            """[{"str":"A","type":"C","r":1,"g":2,"b":3},{"str":"B","type":"O","r":1,"g":2,"b":3}]"""
        )

        assertEquals(1000, assertIs<ChonkySlide>(ads[0]).delayMs)
        assertEquals(false, (ads[0] as ChonkySlide).shouldScrollToLastLetter)
        assertEquals(1000, assertIs<OneByOneMessage>(ads[1]).delayMs)
    }

    @Test
    fun `an empty list is an empty list`() {
        assertEquals(emptyList(), AdCodec.decode("[]"))
        assertEquals("[]", AdCodec.encode(emptyList()))
    }

    @Test
    fun `broken JSON is refused`() {
        listOf(
            "",
            "   ",
            "[",
            """[{"dly":1000,"str":"CUT OFF","ty""",
            """[{"str":"1","type":"I","b":59,"g":212,"r":254}""",
            "not json at all {",
        ).forEach { broken ->
            assertFailsWith<AdFileException>("for >$broken<") { AdCodec.decode(broken) }
        }
    }

    @Test
    fun `JSON that is not a list of objects is refused`() {
        listOf("null", "{}", "7", "\"ads\"", "[1]", "[null]", "[[]]").forEach { wrong ->
            assertFailsWith<AdFileException>("for >$wrong<") { AdCodec.decode(wrong) }
        }
    }

    @Test
    fun `an unknown type is refused, with the place and the type in the reason`() {
        val e = assertFailsWith<AdFileException> {
            AdCodec.decode("""[{"str":"1","type":"I","r":1,"g":2,"b":3},{"str":"HELLO","type":"D"}]""")
        }

        assertContains(e.message!!, "ad 2")
        assertContains(e.message!!, "\"D\"")
    }

    @Test
    fun `an ad without a type is refused`() {
        assertFailsWith<AdFileException> { AdCodec.decode("""[{"str":"HELLO","dly":1000,"r":1,"g":2,"b":3}]""") }
    }

    @Test
    fun `an unknown icon is refused, with the place and the icon in the reason`() {
        val e = assertFailsWith<AdFileException> {
            AdCodec.decode("""[{"str":"Q","type":"I","r":1,"g":2,"b":3}]""")
        }

        assertContains(e.message!!, "ad 1")
        assertContains(e.message!!, "\"Q\"")
    }

    /** A good chonky ad as the fields of its JSON object. Each fault below changes exactly one of them. */
    private val goodAd: Map<String, String> = linkedMapOf(
        "str" to "\"A\"", "type" to "\"C\"", "dly" to "1000", "scroll" to "false", "r" to "1", "g" to "2", "b" to "3",
    )

    /** The list with [goodAd], its [key] set to the JSON [value], or taken out when that is null. */
    private fun adWith(key: String, value: String?): String {
        val fields = goodAd.toMutableMap()
        if (value == null) fields.remove(key) else fields[key] = value
        return fields.entries.joinToString(",", "[{", "}]") { (name, json) -> "\"$name\":$json" }
    }

    @Test
    fun `the ad the faults are made from is a good one`() {
        val ad = assertIs<ChonkySlide>(AdCodec.decode(adWith("str", "\"A\"")).single())

        assertEquals("A", ad.str)
        // and these two keys may be absent
        assertIs<ChonkySlide>(AdCodec.decode(adWith("dly", null)).single())
        assertIs<ChonkySlide>(AdCodec.decode(adWith("scroll", null)).single())
    }

    @Test
    fun `one value of the wrong kind refuses the list, and the reason names that value`() {
        val absent: String? = null
        listOf(
            "str" to absent, "str" to "7", "str" to "null", "str" to "[]",
            "type" to absent, "type" to "7", "type" to "null",
            "dly" to "\"soon\"", "dly" to "1000.5", "dly" to "null", "dly" to "true",
            "scroll" to "\"yes\"", "scroll" to "1", "scroll" to "null",
            "r" to absent, "r" to "256", "r" to "-1", "r" to "\"1\"", "r" to "1.5", "r" to "null",
            "g" to absent, "g" to "256", "g" to "-1",
            "b" to absent, "b" to "[]", "b" to "300",
        ).forEach { (key, value) ->
            val fault = "$key = ${value ?: "(absent)"}"
            val e = assertFailsWith<AdFileException>(fault) { AdCodec.decode(adWith(key, value)) }
            assertContains(e.message!!, "\"$key\"", message = fault)
        }
    }

    @Test
    fun `an icon needs its colour too`() {
        val e = assertFailsWith<AdFileException> { AdCodec.decode("""[{"str":"1","type":"I","r":1,"g":2}]""") }

        assertContains(e.message!!, "\"b\"")
    }

    @Test
    fun `a key the app does not know is ignored`() {
        val ads = AdCodec.decode("""[{"str":"A","type":"O","dly":2000,"r":1,"g":2,"b":3,"extra":true}]""")

        assertEquals(listOf<Message>(OneByOneMessage("A", 0x010203, 2000)), ads)
        assertTrue("extra" !in wire(ads.single()))
    }
}
