package cx.aphex.energysign.adb

import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Turning the extras of the broadcast into the bytes a Bluetooth write would carry. */
class AdbCommandTest {

    private fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun bytesOf(command: AdbCommand): ByteArray = assertIs<AdbCommand.Bytes>(command, "expected bytes, got $command").bytes

    private fun refusal(command: AdbCommand): String = assertIs<AdbCommand.Refused>(command, "expected a refusal, got $command").why

    @Test
    fun `text is sent as its UTF-8 bytes`() {
        assertContentEquals("!next".toByteArray(Charsets.UTF_8), bytesOf(AdbCommand.fromExtras(text = "!next", b64 = null)))
        assertContentEquals(
            "hello from the mayor".toByteArray(Charsets.UTF_8),
            bytesOf(AdbCommand.fromExtras(text = "hello from the mayor", b64 = null)),
        )
    }

    @Test
    fun `b64 is decoded to the bytes it stands for`() {
        assertContentEquals("!choose".toByteArray(Charsets.UTF_8), bytesOf(AdbCommand.fromExtras(text = null, b64 = b64("!choose"))))
    }

    @Test
    fun `b64 wins when both are there`() {
        assertContentEquals(
            "!prev".toByteArray(Charsets.UTF_8),
            bytesOf(AdbCommand.fromExtras(text = "!next", b64 = b64("!prev"))),
        )
    }

    @Test
    fun `bad b64 is refused even when text is there too - b64 wins, it is not skipped`() {
        val why = refusal(AdbCommand.fromExtras(text = "!next", b64 = "this is *not* base64"))

        assertTrue(why.contains("base64"), why)
    }

    @Test
    fun `neither extra is refused, and the reason names both`() {
        val why = refusal(AdbCommand.fromExtras(text = null, b64 = null))

        assertTrue(why.contains("text") && why.contains("b64"), why)
    }

    @Test
    fun `bad base64 is refused - a wrong character, a wrong length, wrong padding`() {
        for (bad in listOf("!!!!", "IW5le*Q=", "=IW5leHQ=", "IW5leHQ==", "IW5le", "IW5leHQ=IW5leHQ=")) {
            val why = refusal(AdbCommand.fromExtras(text = null, b64 = bad))
            assertTrue(why.contains("base64"), "$bad: $why")
        }
    }

    @Test
    fun `base64 without its padding is read - it can only mean one thing`() {
        assertContentEquals("!next".toByteArray(Charsets.UTF_8), bytesOf(AdbCommand.fromExtras(text = null, b64 = "IW5leHQ")))
        assertContentEquals("!next".toByteArray(Charsets.UTF_8), bytesOf(AdbCommand.fromExtras(text = null, b64 = "IW5leHQ=")))
    }

    @Test
    fun `an empty string is refused, as text and as b64`() {
        assertTrue(refusal(AdbCommand.fromExtras(text = "", b64 = null)).contains("empty"))
        assertTrue(refusal(AdbCommand.fromExtras(text = null, b64 = "")).contains("empty"))
        assertTrue(refusal(AdbCommand.fromExtras(text = "!next", b64 = "")).contains("empty"), "an empty b64 still wins")
    }

    @Test
    fun `base64 broken into lines, as some encoders write it, is read`() {
        val long = "!🅰️\n" + (1..12).joinToString("\n") { "🆑LINE NUMBER $it" }
        val wrapped = Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(long.toByteArray(Charsets.UTF_8)) + "\n"
        assertTrue(wrapped.trim().contains("\n"), "the fixture must be wrapped")

        assertContentEquals(long.toByteArray(Charsets.UTF_8), bytesOf(AdbCommand.fromExtras(text = null, b64 = wrapped)))
    }

    @Test
    fun `an emoji with a variation selector arrives byte for byte`() {
        // U+1F170 U+FE0F: the "A button" the ad command starts with, with its variation selector.
        val ad = "!🅰️"
        val expected = byteArrayOf(
            0x21,
            0xF0.toByte(), 0x9F.toByte(), 0x85.toByte(), 0xB0.toByte(),
            0xEF.toByte(), 0xB8.toByte(), 0x8F.toByte(),
        )

        assertContentEquals(expected, bytesOf(AdbCommand.fromExtras(text = null, b64 = b64(ad))))
        assertContentEquals(expected, bytesOf(AdbCommand.fromExtras(text = ad, b64 = null)))
    }

    @Test
    fun `a multi-line ad command arrives whole - every line break and every emoji`() {
        val ad = listOf(
            "!🅰️",
            "🆑🕰DREAM",
            "🆑STATE",
            "🅾️💗YOUR DJ:",
            "🅾️🕰💗Aphex",
            "🛤️",
            "👾",
        ).joinToString("\n")

        val bytes = bytesOf(AdbCommand.fromExtras(text = null, b64 = b64(ad)))

        assertContentEquals(ad.toByteArray(Charsets.UTF_8), bytes)
        assertEquals(ad, String(bytes, Charsets.UTF_8))
        assertEquals(7, String(bytes, Charsets.UTF_8).lines().size)
    }

    @Test
    fun `the bytes are not touched - a trailing line break stays, and the answer says so`() {
        // `echo '!next' | base64` sends "!next\n", which the app does not know as a command.
        val withBreak = bytesOf(AdbCommand.fromExtras(text = null, b64 = b64("!next\n")))

        assertContentEquals("!next\n".toByteArray(Charsets.UTF_8), withBreak)
        assertEquals("ok 6 bytes (the last one is a line break)", AdbCommand.accepted(withBreak))
        assertEquals("ok 5 bytes", AdbCommand.accepted("!next".toByteArray(Charsets.UTF_8)))
    }

    // ---- every extra: a value of the wrong type is refused, it is not taken for absent (gate round 3)

    private val notStrings: List<Any?> = listOf(1, 1L, 1.5f, true, null, arrayOf("!delete"), intArrayOf(1), listOf("!delete"), "x".toByteArray())

    @Test
    fun `a b64 extra that is not a string is refused - the text beside it is not carried out in its place`() {
        // The gate's example: am broadcast ... --ei b64 1 --es text '!delete'
        val gates = AdbCommand.fromExtras(mapOf("b64" to 1, "text" to "!delete"))
        assertTrue(refusal(gates).contains("b64"), refusal(gates))

        for (wrong in notStrings) {
            val why = refusal(AdbCommand.fromExtras(mapOf("b64" to wrong, "text" to "!delete")))
            assertTrue(why.contains("the extra b64 is there but is not a string"), "b64 = $wrong: $why")
        }
    }

    @Test
    fun `a text extra that is not a string is refused too, alone and beside a good b64`() {
        for (wrong in notStrings) {
            val alone = refusal(AdbCommand.fromExtras(mapOf("text" to wrong)))
            assertTrue(alone.contains("the extra text is there but is not a string"), "text = $wrong: $alone")
            val beside = refusal(AdbCommand.fromExtras(mapOf("text" to wrong, "b64" to b64("!next"))))
            assertTrue(beside.contains("the extra text is there but is not a string"), "text = $wrong beside b64: $beside")
        }
    }

    @Test
    fun `an extra the hook does not know is refused by name - a misspelt b64 does not let the text through`() {
        val misspelt = refusal(AdbCommand.fromExtras(mapOf("b46" to b64("!next"), "text" to "!delete")))
        assertTrue(misspelt.contains("b46"), misspelt)

        val extra = refusal(AdbCommand.fromExtras(mapOf("text" to "!next", "Text" to "!delete")))
        assertTrue(extra.contains("Text"), extra)
    }

    @Test
    fun `extras of the right kind are read as before - text, b64, both with b64 winning, and none`() {
        assertContentEquals("!next".toByteArray(), bytesOf(AdbCommand.fromExtras(mapOf("text" to "!next"))))
        assertContentEquals("!prev".toByteArray(), bytesOf(AdbCommand.fromExtras(mapOf("b64" to b64("!prev")))))
        assertContentEquals("!prev".toByteArray(), bytesOf(AdbCommand.fromExtras(mapOf("text" to "!next", "b64" to b64("!prev")))))
        assertTrue(refusal(AdbCommand.fromExtras(emptyMap())).startsWith("no extra"))
        assertTrue(refusal(AdbCommand.fromExtras(mapOf("text" to ""))).startsWith("empty"))
        assertTrue(refusal(AdbCommand.fromExtras(mapOf("b64" to "not base64!", "text" to "!next"))).startsWith("bad base64"))
    }
}
