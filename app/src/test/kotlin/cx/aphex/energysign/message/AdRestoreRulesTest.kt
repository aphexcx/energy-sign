package cx.aphex.energysign.message

import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What is read back from `ads.json` is held to the same rules as what comes from an ad command.
 * The files here are in the shape an older version of the app wrote, which had none of the rules.
 */
class AdRestoreRulesTest {

    private val notes = mutableListOf<String>()

    private fun load(json: String): List<Message> = AdCodec.decode(json) { notes += it }

    private fun oneByOne(fields: String): String = """[{$fields,"type":"O","b":3,"g":2,"r":1}]"""
    private fun chonky(fields: String): String = """[{$fields,"scroll":false,"type":"C","b":3,"g":2,"r":1}]"""

    private val palette = AdPalette(1, 2, 3, 4, 5, 6, 7, 8, 9)
    private val CL = String(intArrayOf(0x1F191), 0, 1)
    private val O = String(intArrayOf(0x1F17E), 0, 1)
    private val CLOCK = String(intArrayOf(0x1F570), 0, 1)

    /** The ad an ad line gives. */
    private fun fromCommand(line: String): Message = AdLineParser.parse(line, palette).single()

    // ---- the size limit

    @Test
    fun `an ad saved by the old app that is too long for the sign is cut when it is read back`() {
        // 120 apostrophes: Gson writes each as ', 720 bytes of text alone
        val saved = oneByOne(""""dly":1000,"str":"${"\\u0027".repeat(120)}"""")

        val ad = assertIs<OneByOneMessage>(load(saved).single())

        val bytes = AdSizeLimit.jsonBytes(ad)
        assertTrue(bytes <= AdSizeLimit.MAX_AD_JSON_BYTES, "$bytes bytes go to the sign")
        assertTrue(ad.str.isNotEmpty() && ad.str.all { it == '\'' }, "the beginning of the text is kept")
        assertEquals(1, notes.size, notes.toString())
        assertTrue(notes.single().startsWith("ad 1: "), notes.single())
    }

    @Test
    fun `a long ad is cut to the same text whether it comes from a command or from the file`() {
        val text = "it's ".repeat(150)

        val restored = load(chonky(""""dly":1000,"str":"${text.replace("'", "\\u0027")}"""")).single()
        val sent = AdLineParser.parse(CL + text, AdPalette(0, 0, 0, 0, 0, 0, 0, chonkyDefault = 0x010203, iconDefault = 0)).single()

        assertEquals(sent.str, restored.str)
        assertEquals(AdSizeLimit.jsonBytes(sent), AdSizeLimit.jsonBytes(restored))
        assertTrue(restored.str.length < text.length)
    }

    @Test
    fun `an ad that fits is read back whole and nothing is reported`() {
        val ad = load(oneByOne(""""dly":2000,"str":"JUST AN AD <3"""")).single()

        assertEquals("JUST AN AD <3", ad.str, "a saved text is not changed, and a saved <3 stays a <3")
        assertEquals(emptyList(), notes)
    }

    // ---- the delay

    @Test
    fun `a delay the old app let overflow comes back as the longest delay`() {
        // 32 clocks: 33000 ms, saved as a Short: -32536
        val ad = assertIs<ChonkySlide>(load(chonky(""""dly":-32536,"str":"LONG"""")).single())

        assertEquals(30_000, ad.delayMs.toInt())
        assertEquals(1, notes.size)
        assertTrue("-32536" in notes.single() && "30000" in notes.single(), notes.single())
    }

    @Test
    fun `every delay the old app could save comes back as a delay an ad line can give`() {
        // the old app saved ((clocks + 1) * 1000) as a Short, with no cap
        val whatAnAdLineGives = (1..30).map { it * 1000 }.toSet()
        (0..70).forEach { clocks ->
            val savedByTheOldApp = ((clocks + 1) * 1000).toShort()

            val restored = (load(oneByOne(""""dly":$savedByTheOldApp,"str":"A"""")).single() as OneByOneMessage).delayMs.toInt()

            assertTrue(restored in whatAnAdLineGives, "$clocks clocks, saved as $savedByTheOldApp, came back as $restored")
            if (clocks <= 29) assertEquals((clocks + 1) * 1000, restored, "up to thirty seconds nothing changes")
            if (clocks in 30..64) assertEquals(30_000, restored, "$clocks clocks, saved as $savedByTheOldApp")
        }
    }

    @Test
    fun `a restored delay follows the one rule, AdRules delay`() {
        mapOf(
            1000 to 1000, 2000 to 2000, 30_000 to 30_000,
            31_000 to 30_000, 32_000 to 30_000, 40_000 to 30_000, 2_000_000_000 to 30_000,
            -1 to 30_000, -32_536 to 30_000, -536 to 30_000,
            0 to 1000, 464 to 1000, 999 to 1000,
            1001 to 1000, 1500 to 1000, 2999 to 2000,
        ).forEach { (saved, expected) ->
            val ad = load(chonky(""""dly":$saved,"str":"A"""")).single() as ChonkySlide
            assertEquals(expected, ad.delayMs.toInt(), "dly $saved")
            assertEquals(AdRules.delay(saved.toLong()), ad.delayMs, "dly $saved")
            assertEquals(0, ad.delayMs % 1000, "whole seconds: the low byte of dly is even")
        }
    }

    @Test
    fun `a delay that is in the rules is not reported, one that is not is`() {
        load(chonky(""""dly":30000,"str":"A""""))
        assertEquals(emptyList(), notes)

        load(chonky(""""dly":31000,"str":"A""""))
        assertEquals(1, notes.size)
    }

    @Test
    fun `an ad line and a file can ask for the same delays, no more`() {
        val fromLines = (0..40).map { clocks -> (fromCommand(O + CLOCK.repeat(clocks) + "A") as OneByOneMessage).delayMs.toInt() }.toSet()

        assertEquals((1..30).map { it * 1000 }.toSet(), fromLines)
        assertEquals(fromLines, (-40_000..40_000 step 250).map { AdRules.delay(it.toLong()).toInt() }.toSet())
    }

    // ---- the text

    @Test
    fun `a saved text keeps only what the sign's fonts have, as the text of an ad line does`() {
        val raw = "CAFÉ “HI” — OK\u0007!\u000B?"
        val asJson = "CAF\\u00c9 \\u201cHI\\u201d \\u2014 OK\\u0007!\\u000b?"

        val restored = load(oneByOne(""""dly":1000,"str":"$asJson"""")).single()
        val sent = fromCommand(O + raw)

        assertEquals("CAF \"HI\" - OK!?", restored.str)
        assertEquals(sent.str, restored.str)
        assertEquals(1, notes.size)
    }

    @Test
    fun `the heart character and every printable character are kept`() {
        val all = (' '..'~').joinToString("").replace("\\", "").replace("\"", "") + Message.HEART

        val restored = load(chonky(""""dly":1000,"str":"$all"""")).single()

        assertEquals(all, restored.str)
        assertEquals(emptyList(), notes)
    }

    @Test
    fun `an ad line cannot put a control character on the sign either`() {
        assertEquals("AB", fromCommand(O + "A\u0001\u0007\u000B\u001FB").str)
        assertEquals("AB", fromCommand(CL + "A\tB").str)
    }

    @Test
    fun `the place of the now-playing track has no text, whatever was saved`() {
        val ads = load("""[{"str":"${"X".repeat(900)}","type":"T"},{"type":"T"}]""")

        assertEquals(listOf("", ""), ads.map { it.str })
        assertTrue(ads.all { it is Message.NowPlayingTrackMessage })
    }

    // ---- strict JSON, and all of it

    @Test
    fun `a file that is not strict JSON is unreadable`() {
        listOf(
            "[{type:T,str:track}]",
            "[{'type':'T','str':''}]",
            """[{"type":"T","str":""},]""",
            """[{"type":"T";"str":""}]""",
            """[{"type"="T","str":""}]""",
            """[{"type":"T","str":""}] // the ads""",
            """/* ads */ [{"type":"T","str":""}]""",
        ).forEach { notStrict ->
            assertFailsWith<AdFileException>("for >$notStrict<") { load(notStrict) }
        }
    }

    @Test
    fun `anything after the list makes the file unreadable`() {
        listOf(
            """[{"type":"T","str":""}] x""",
            """[{"type":"T","str":""}][]""",
            """[{"type":"T","str":""}]}""",
            """[] []""",
            """[]{"type":"T"}""",
            """[]null""",
        ).forEach { tooMuch ->
            assertFailsWith<AdFileException>("for >$tooMuch<") { load(tooMuch) }
        }
    }

    @Test
    fun `white space around the list is fine`() {
        assertEquals(1, load("  \n[ {\"type\" : \"T\", \"str\" : \"\"} ]\n\n").size)
        assertEquals(0, load("[]\n").size)
    }

    // ---- scroll

    @Test
    fun `a scroll that is not true or false is refused`() {
        val e = assertFailsWith<AdFileException> {
            load("""[{"dly":1000,"scroll":"yes","str":"A","type":"C","b":3,"g":2,"r":1}]""")
        }
        assertTrue("scroll" in e.message!!, e.message)
    }

    @Test
    fun `scroll true and false are read, and no scroll is false`() {
        fun scrollOf(scroll: String) =
            (load("""[{"dly":1000,${scroll}"str":"A","type":"C","b":3,"g":2,"r":1}]""").single() as ChonkySlide).shouldScrollToLastLetter

        assertEquals(true, scrollOf(""""scroll":true,"""))
        assertEquals(false, scrollOf(""""scroll":false,"""))
        assertEquals(false, scrollOf(""))
    }
}
