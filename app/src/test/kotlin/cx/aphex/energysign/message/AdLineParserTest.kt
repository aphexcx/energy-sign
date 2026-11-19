package cx.aphex.energysign.message

import com.google.gson.JsonParser
import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AdLineParserTest {

    private fun emoji(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    private val VS = emoji(0xFE0F) // the variation selector a phone may put behind an emoji
    private val CL = emoji(0x1F191)
    private val O = emoji(0x1F17E)
    private val TRACK = emoji(0x1F6E4)
    private val INVADER = emoji(0x1F47E)
    private val CLOCK = emoji(0x1F570)
    private val YELLOW_HEART = emoji(0x1F49B)
    private val RED_HEART = emoji(0x2764, 0xFE0F)
    private val RED_CIRCLE = emoji(0x1F534)
    private val BLUE_HEART = emoji(0x1F499)
    private val ORANGE_HEART = emoji(0x1F9E1)
    private val PURPLE_HEART = emoji(0x1F49C)
    private val GREEN_HEART = emoji(0x1F49A)
    private val GROWING_HEART = emoji(0x1F497)

    private val HEART = Message.HEART

    private val palette = AdPalette(
        instagram = 0xFED43B,
        instahandle = 0xEF0C1D,
        twitter = 0x1DA1F2,
        soundcloud = 0xFF7700,
        twitch = 0x8205B4,
        green = 0x04FF19,
        pink = 0xFF055D,
        chonkyDefault = 0xFF0080,
        iconDefault = 0x4F62D2,
    )

    private val warnings = mutableListOf<String>()

    private fun parse(vararg lines: String): List<Message> =
        AdLineParser.parse(lines.joinToString("\n"), palette) { warnings += it }

    private fun chonky(line: String): ChonkySlide = assertIs<ChonkySlide>(parse(line).single())
    private fun oneByOne(line: String): OneByOneMessage = assertIs<OneByOneMessage>(parse(line).single())

    private fun rgb(ad: Message.ColorMessage): Int = (ad.r shl 16) or (ad.g shl 8) or ad.b

    private fun wire(message: Message): String = SignResponder.GSON.toJson(message)

    // ---- the kinds of line

    @Test
    fun `the ad command as the phone sends it becomes the ad list`() {
        // What follows `!` and the first half of the A-button emoji: the rest of that emoji, then one ad per line.
        val command = "\uDD70$VS\n" +
                "$CL$CLOCK HEY\n" +
                "$O$VS${YELLOW_HEART}INSTAGRAM:\n" +
                "$O$VS$CLOCK$VS$CLOCK$VS$CLOCK$VS$RED_HEART@SOMEONE\n" +
                "$TRACK$VS\n" +
                "$INVADER"

        val ads = AdLineParser.parse(command, palette)

        assertEquals(5, ads.size)
        val hey = assertIs<ChonkySlide>(ads[0])
        assertEquals(" HEY", hey.str, "the space at the start is part of the ad")
        assertEquals(2000, hey.delayMs)
        assertEquals(palette.chonkyDefault, rgb(hey))

        val instagram = assertIs<OneByOneMessage>(ads[1])
        assertEquals("INSTAGRAM:", instagram.str)
        assertEquals(1000, instagram.delayMs)
        assertEquals(palette.instagram, rgb(instagram))

        val handle = assertIs<OneByOneMessage>(ads[2])
        assertEquals("@SOMEONE", handle.str)
        assertEquals(4000, handle.delayMs)
        assertEquals(palette.instahandle, rgb(handle))

        assertEquals("", assertIs<Message.NowPlayingTrackMessage>(ads[3]).str)
        assertEquals(palette.iconDefault, rgb(assertIs<IconInvaders.Enemy1>(ads[4])))
    }

    @Test
    fun `lines that start with nothing known are skipped`() {
        assertEquals(emptyList(), parse("", "HELLO", " ${CL}NOT AT THE START", "${CLOCK}${CL}NEITHER"))
    }

    @Test
    fun `no ad lines give an empty list`() {
        assertEquals(emptyList(), AdLineParser.parse("", palette))
        assertEquals(emptyList(), AdLineParser.parse("\uDD70$VS", palette))
    }

    @Test
    fun `lines may end the Windows way`() {
        val ads = AdLineParser.parse("${CL}ONE\r\n${O}TWO\r\n", palette)

        assertEquals(listOf("ONE", "TWO"), ads.map { it.str })
    }

    @Test
    fun `every colour emoji gives its colour and no text`() {
        val colours = mapOf(
            YELLOW_HEART to palette.instagram,
            RED_HEART to palette.instahandle,
            RED_CIRCLE to palette.instahandle,
            BLUE_HEART to palette.twitter,
            ORANGE_HEART to palette.soundcloud,
            PURPLE_HEART to palette.twitch,
            GREEN_HEART to palette.green,
            GROWING_HEART to palette.pink,
        )
        colours.forEach { (colourEmoji, colour) ->
            val c = chonky("$CL${colourEmoji}HEY")
            assertEquals(colour, rgb(c))
            assertEquals("HEY", c.str)

            val o = oneByOne("${O}HEY$colourEmoji")
            assertEquals(colour, rgb(o))
            assertEquals("HEY", o.str)
        }
        assertEquals(palette.chonkyDefault, rgb(chonky("${CL}HEY")))
        assertEquals(palette.instagram, rgb(oneByOne("${O}HEY")))
    }

    @Test
    fun `the last emoji of an invader line chooses the icon`() {
        assertIs<IconInvaders.Enemy1>(parse(INVADER).single())
        assertIs<IconInvaders.Enemy2>(parse("$INVADER${emoji(0x1F47D)}").single())
        assertIs<IconInvaders.Explosion>(parse("$INVADER${emoji(0x1F4A5)}").single())
        assertIs<IconInvaders.ANJUNA>(parse("$INVADER${emoji(0x1F18E)}").single())
        assertIs<IconInvaders.BAAAHS>(parse("$INVADER${emoji(0x1F411)}").single())
        assertIs<IconInvaders.DREAMSTATE>(parse("$INVADER${emoji(0x1F30C)}").single())
        assertIs<IconInvaders.EDC>(parse("$INVADER${emoji(0x1F33C)}").single())

        val coloured = assertIs<IconInvaders.EDC>(parse("$INVADER$GREEN_HEART${emoji(0x1F33C)}").single())
        assertEquals(palette.green, rgb(coloured))
    }

    // ---- the clock

    @Test
    fun `no clock is one second and every clock adds one`() {
        assertEquals(1000, chonky("${CL}HEY").delayMs)
        assertEquals(2000, chonky("$CL${CLOCK}HEY").delayMs)
        assertEquals(4000, chonky("$CL$CLOCK$CLOCK${CLOCK}HEY").delayMs)

        assertEquals(1000, oneByOne("${O}HEY").delayMs)
        assertEquals(2000, oneByOne("$O${CLOCK}HEY").delayMs)
        assertEquals(4000, oneByOne("$O$CLOCK$CLOCK${CLOCK}HEY").delayMs)
    }

    @Test
    fun `a clock counts once with or without its variation selector`() {
        assertEquals(2000, chonky("$CL$CLOCK${VS}HEY").delayMs)
        assertEquals(4000, oneByOne("$O$CLOCK$VS$CLOCK$CLOCK${VS}HEY").delayMs)
        assertEquals("HEY", oneByOne("$O$CLOCK$VS$CLOCK$CLOCK${VS}HEY").str)
    }

    @Test
    fun `a clock in the middle or at the end of the line counts and is not in the text`() {
        val middle = chonky("${CL}TIC${CLOCK}TOC")
        assertEquals(2000, middle.delayMs)
        assertEquals("TICTOC", middle.str)

        val end = oneByOne("${O}LATE$CLOCK$VS")
        assertEquals(2000, end.delayMs)
        assertEquals("LATE", end.str)
    }

    @Test
    fun `forty clocks are capped at thirty seconds`() {
        assertEquals(30_000, chonky(CL + CLOCK.repeat(40) + "LONG").delayMs)
        assertEquals(30_000, oneByOne(O + CLOCK.repeat(40) + "LONG").delayMs)
    }

    @Test
    fun `the cap starts exactly at thirty seconds and no number of clocks goes negative`() {
        assertEquals(29_000, AdLineParser.delayMs(CLOCK.repeat(28)))
        assertEquals(30_000, AdLineParser.delayMs(CLOCK.repeat(29)))
        assertEquals(30_000, AdLineParser.delayMs(CLOCK.repeat(30)))
        // 32 clocks made 33000, which is -32536 as a Short; 65 made 66000, which is 464.
        listOf(31, 32, 33, 65, 66, 500).forEach { clocks ->
            assertEquals(30_000, AdLineParser.delayMs(CLOCK.repeat(clocks)), "$clocks clocks")
        }
    }

    @Test
    fun `other clock faces are not the clock`() {
        assertEquals(1000, oneByOne("$O${emoji(0x23F0)}${emoji(0x1F550)}HEY").delayMs)
    }

    // ---- <3

    @Test
    fun `a typed heart becomes the heart character in a chonky and in a one by one ad`() {
        assertEquals("SAY HI!!$HEART", oneByOne("$O$CLOCK${GROWING_HEART}SAY HI!!<3").str)
        assertEquals("I${HEART}U", chonky("${CL}I<3U").str)
    }

    @Test
    fun `a typed heart at the start, at the end, twice and beside other text`() {
        assertEquals("${HEART}YOU", oneByOne("$O<3YOU").str)
        assertEquals("YOU$HEART", oneByOne("${O}YOU<3").str)
        assertEquals("$HEART$HEART", oneByOne("$O<3<3").str)
        assertEquals("A $HEART B $HEART C", chonky("${CL}A <3 B <3 C").str)
        assertEquals("$HEART", chonky("$CL<3").str)
    }

    @Test
    fun `only the two characters of a typed heart are replaced`() {
        assertEquals("${HEART}3", oneByOne("$O<33").str)
        assertEquals("<$HEART", oneByOne("$O<<3").str)
        assertEquals("< 3", oneByOne("$O< 3").str)
        assertEquals("3<", oneByOne("${O}3<").str)
        assertEquals("<4", oneByOne("$O<4").str)
    }

    @Test
    fun `a heart emoji gives the colour and no heart character`() {
        val ad = oneByOne("$O${GROWING_HEART}YOUR DJ:")
        assertEquals(palette.pink, rgb(ad))
        assertEquals("YOUR DJ:", ad.str)

        val chonkyAd = chonky("$CL${RED_HEART}LOVE")
        assertEquals(palette.instahandle, rgb(chonkyAd))
        assertEquals("LOVE", chonkyAd.str)
    }

    @Test
    fun `a heart emoji for the colour and a typed heart for the text work together`() {
        val ad = chonky("$CL${BLUE_HEART}LOVE <3")

        assertEquals(palette.twitter, rgb(ad))
        assertEquals("LOVE $HEART", ad.str)
    }

    @Test
    fun `only a heart that was typed is a heart, not one that taking out an emoji would make`() {
        // "<", a blue heart for the colour, "3": two characters in blue, as before
        val coloured = oneByOne("$O<${BLUE_HEART}3")
        assertEquals("<3", coloured.str)
        assertEquals(palette.twitter, rgb(coloured))

        assertEquals("<3", chonky("$CL<${CLOCK}3").str)
        assertEquals("<3", chonky("$CL<${VS}3").str)
        assertEquals("<3", oneByOne("$O<é" + "3").str)
        // and a look-alike of "<" is not a typed "<" either
        assertEquals("<3", oneByOne("$O‹" + "3").str)
        // a typed one beside an emoji still is
        assertEquals("$HEART!", oneByOne("$O$BLUE_HEART<3$CLOCK!").str)
    }

    @Test
    fun `the heart goes to the sign as one byte`() {
        val json = wire(oneByOne("${O}A<3B"))

        assertTrue("A${HEART}B" in json, json)
        assertTrue("003c" !in json, json)
    }

    // ---- long ads

    private fun strOnTheWire(ad: Message): String = JsonParser.parseString(wire(ad)).asJsonObject["str"].asString

    @Test
    fun `an ad of ordinary length is sent whole and nothing is logged`() {
        val text = "THIS AD IS WIDER THAN A PANEL AND THE SIGN SCROLLS IT"

        assertEquals(text, chonky(CL + text).str)
        assertEquals(text, oneByOne(O + text).str)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a 600 character ad is cut to fit the limit and that is logged once`() {
        val text = (1..60).joinToString("") { "%09d ".format(it) }
        assertEquals(600, text.length)

        val ad = oneByOne(O + CLOCK + text)

        assertTrue(AdSizeLimit.jsonBytes(ad) <= AdSizeLimit.MAX_AD_JSON_BYTES, "${AdSizeLimit.jsonBytes(ad)} bytes")
        assertTrue(text.startsWith(ad.str), "the ad is the beginning of the text")
        assertEquals(2000, ad.delayMs)
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("600" in warnings.single(), warnings.single())
    }

    @Test
    fun `a cut ad is as long as the limit allows`() {
        val text = "X".repeat(600)

        val ad = chonky(CL + text)

        assertEquals(AdSizeLimit.MAX_AD_JSON_BYTES, AdSizeLimit.jsonBytes(ad), "plain characters are one byte each")
        assertEquals(1, warnings.size)
    }

    @Test
    fun `an ad full of apostrophes is cut by its bytes, between characters`() {
        // Gson writes ' as ': six bytes for one character.
        val text = "'".repeat(600)

        val ad = chonky(CL + text)

        val bytes = AdSizeLimit.jsonBytes(ad)
        assertTrue(bytes <= AdSizeLimit.MAX_AD_JSON_BYTES, "$bytes bytes")
        assertTrue(bytes > AdSizeLimit.MAX_AD_JSON_BYTES - 6, "one more apostrophe would not fit: $bytes bytes")
        assertTrue(ad.str.length < 100, "about 70 apostrophes fit, not ${ad.str.length}")
        assertTrue(ad.str.all { it == '\'' })
        assertEquals(ad.str, strOnTheWire(ad), "the JSON is whole: no escape was cut in two")
        assertEquals(1, warnings.size)
    }

    @Test
    fun `the other characters Gson writes long are counted too`() {
        listOf("<", ">", "&", "=").forEach { costly ->
            warnings.clear()
            val ad = oneByOne(O + costly.repeat(300) + " ")

            assertTrue(AdSizeLimit.jsonBytes(ad) <= AdSizeLimit.MAX_AD_JSON_BYTES, "for $costly")
            assertEquals(ad.str, strOnTheWire(ad), "for $costly")
            assertEquals(1, warnings.size, "for $costly")
        }
    }

    @Test
    fun `an ad line whose answer is exactly 500 bytes is not cut and one character more is cut by one`() {
        // The numbers are written out and the bytes are those of the responder's answer.
        // {"str":"","dly":1000,"type":"O","r":254,"g":212,"b":59} is 55 bytes: 445 plain characters make 500.
        val justFits = oneByOne(O + "A".repeat(445))
        assertEquals(445, justFits.str.length)
        assertEquals(500, bytesOnTheWire(justFits))
        assertEquals(emptyList(), warnings)

        val oneMore = oneByOne(O + "A".repeat(446))
        assertEquals(445, oneMore.str.length)
        assertEquals(500, bytesOnTheWire(oneMore))
        assertEquals(1, warnings.size)
    }

    /** What the sign gets when it asks and [message] is next: the bytes of the real responder's answer. */
    private fun bytesOnTheWire(message: Message): Int {
        var frame: String? = null
        SignResponder(nextMessage = { message }, write = { reply, _ -> frame = reply; true })
            .onRequest(byteArrayOf('~'.code.toByte()), 1)
        return frame!!.toByteArray(Charsets.UTF_8).size
    }

    @Test
    fun `hearts are made before the cut, so a typed heart costs one byte and is never split`() {
        val ad = oneByOne(O + "<3".repeat(600))

        assertTrue(ad.str.all { it == HEART }, "only hearts, no stray < or 3")
        assertEquals(AdSizeLimit.MAX_AD_JSON_BYTES, AdSizeLimit.jsonBytes(ad))
    }

    @Test
    fun `each long ad of a list is cut and logged, the others are left alone`() {
        val ads = parse(CL + "A".repeat(600), "${O}SHORT", O + "B".repeat(600), INVADER)

        assertEquals(4, ads.size)
        assertEquals("SHORT", ads[1].str)
        assertTrue(ads.all { AdSizeLimit.jsonBytes(it) <= AdSizeLimit.MAX_AD_JSON_BYTES })
        assertEquals(2, warnings.size)
    }

    @Test
    fun `the limit leaves room in the sign's buffer`() {
        // The sign's JSON buffer is about 665 bytes and its str about 570 characters.
        assertTrue(AdSizeLimit.MAX_AD_JSON_BYTES <= 665)
        assertTrue(chonky(CL + "X".repeat(600)).str.length <= 570)
    }
}
