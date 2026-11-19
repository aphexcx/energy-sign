package cx.aphex.energysign.message

import com.google.gson.JsonParser
import cx.aphex.energysign.message.Message.ColorMessage.AIThinking
import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageSerializationDetailedTest {

    private fun saveAndRestoreAd(ad: Message, expectedJson: String): Message {
        val file = AdCodec.encode(listOf(ad))
        assertEquals(JsonParser.parseString("[$expectedJson]"), JsonParser.parseString(file))

        val restored = AdCodec.decode(file).single()
        assertSignFrame(restored, expectedJson)
        return restored
    }

    @Test
    fun testGPTThinkingSerialization() {
        // The thinking notice lives in the one-time queue. Its next frame goes straight to the sign.
        val notice = AIThinking("...", 0x123456, delayMs = 350, showCaret = false, thinkDots = 3)
        assertSignFrame(
            notice,
            """{"type":"C","str":"...","dly":350,"scroll":false,"r":18,"g":52,"b":86,"showCaret":false,"thinkDots":3}""",
        )
    }

    @Test
    fun testOneByOneMessageSerialization() {
        val restored = saveAndRestoreAd(
            OneByOneMessage("Test", 0xFF123456.toInt(), 2000),
            """{"type":"O","str":"Test","dly":2000,"r":18,"g":52,"b":86}""",
        )
        assertEquals(0x123456, assertIs<OneByOneMessage>(restored).color and 0xFFFFFF)
    }

    @Test
    @Disabled("AdCodec restores 0xFF123456 as 0x00123456 on master. Re-enable with the opaque color fix in PR #6.")
    fun `restoring a one by one ad preserves the opaque Android color`() {
        val ad = OneByOneMessage("Test", 0xFF123456.toInt(), 2000)
        val restored = assertIs<OneByOneMessage>(AdCodec.decode(AdCodec.encode(listOf(ad))).single())
        assertEquals(ad.color, restored.color)
    }

    @Test
    fun testChonkySlideSerialization() {
        val restored = saveAndRestoreAd(
            ChonkySlide("Chonky", 0x2468AC, 3000, shouldScrollToLastLetter = true),
            """{"type":"C","str":"Chonky","dly":3000,"scroll":true,"r":36,"g":104,"b":172}""",
        )
        assertIs<ChonkySlide>(restored)
    }

    @Test
    fun testIconInvadersSerialization() {
        val restored = saveAndRestoreAd(
            IconInvaders.Enemy1(0x234567),
            """{"type":"I","str":"1","r":35,"g":69,"b":103}""",
        )
        assertIs<IconInvaders.Enemy1>(restored)
    }
}
