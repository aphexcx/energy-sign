package cx.aphex.energysign.message

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/** Capture the JSON that a real next-message request hands to the sign link. */
internal fun assertSignFrame(message: Message, expectedJson: String) {
    val frames = mutableListOf<String>()
    val responder = SignResponder(nextMessage = { message }, write = { frame, _ -> frames.add(frame) })
    responder.onRequest(byteArrayOf('~'.code.toByte()), 1)

    assertEquals(JsonParser.parseString(expectedJson), JsonParser.parseString(frames.single()))
}

class MessageSerializationTest {

    @Test
    fun testMarqueeUserSerialization() {
        assertSignFrame(Message.Marquee.User("Test Message"), """{"type":"D","str":"Test Message"}""")
    }

    @Test
    fun testMarqueeGPTQuerySerialization() {
        assertSignFrame(Message.Marquee.AIQuery("Test query"), """{"type":"D","str":"Test query"}""")
    }

    @Test
    fun testMarqueeChonkySerialization() {
        assertSignFrame(Message.Marquee.Chonky("Chonky Message"), """{"type":"N","str":"Chonky Message"}""")
    }

    @Test
    fun testMarqueeGPTReplySerialization(@TempDir dir: File) {
        val reply = Message.Marquee.AIReply("AI Reply")
        val repository = MessageRepository(saver = { it.run() })
        repository.pushMarqueeMessage(reply)
        repository.saveMarqueeMessages(dir)

        assertEquals("CHONKYMARQUEE\u000BAI Reply\n", File(dir, "signstrings.bin").readText())
        assertSignFrame(reply.toChonkyMessage(), """{"type":"N","str":"AI Reply"}""")
    }

    @Test
    fun testStarfieldSerialization() {
        assertSignFrame(Message.Starfield("Stars", 500), """{"type":"S","str":"Stars","stars":500}""")
    }

    @Test
    fun testNowPlayingTrackMessageSerialization() {
        assertSignFrame(Message.NowPlayingTrackMessage("Test Track"), """{"type":"T","str":"Test Track"}""")
    }

    @Test
    fun testOneByOneMessageSerialization() {
        assertSignFrame(
            Message.ColorMessage.OneByOneMessage("One By One", 0x123456, 500),
            """{"type":"O","str":"One By One","dly":500,"r":18,"g":52,"b":86}""",
        )
    }

    @Test
    fun testChonkySlideSerialization() {
        assertSignFrame(
            Message.ColorMessage.ChonkySlide("Chonky Slide", 0x2468AC, 750),
            """{"type":"C","str":"Chonky Slide","dly":750,"scroll":false,"r":36,"g":104,"b":172}""",
        )
    }

    @Test
    fun testGPTThinkingSerialization() {
        assertSignFrame(
            Message.ColorMessage.AIThinking("...", 0x123456),
            """{"type":"C","str":"...","dly":250,"scroll":false,"r":18,"g":52,"b":86,"showCaret":true,"thinkDots":0}""",
        )
    }

    @Test
    fun testIconInvadersSerialization() {
        assertSignFrame(
            Message.ColorMessage.IconInvaders.Enemy1(0x234567),
            """{"type":"I","str":"1","r":35,"g":69,"b":103}""",
        )
    }

    @Test
    fun testFlashingAnnouncementSerialization() {
        assertSignFrame(
            Message.FlashingAnnouncement.CustomFlashyAnnouncement("Flash", 300),
            """{"type":"F","str":"Flash","time":300}""",
        )
    }

    @Test
    fun testCountDownAnnouncementSerialization() {
        assertSignFrame(
            Message.CountDownAnnouncement.NewMessageAnnouncement,
            """{"type":"W","str":"NEW\u000bMSG\u000b"}""",
        )
    }

    @Test
    fun testUtilityMessageSerialization() {
        assertSignFrame(Message.UtilityMessage.EnableMic, """{"type":"U","str":"E","subtype":"M"}""")
    }

    @Test
    fun testChooserSerialization() {
        val chooser = Message.Chooser(1, 5, Message.Marquee.User("Choose me"))
        assertSignFrame(chooser, """{"type":"H","str":"Choose me","flashy":"1/5"}""")
    }

    @Test
    fun testKeyboardEchoSerialization() {
        assertSignFrame(
            Message.KeyboardEcho.Input("abcdefghijklmnopqrstuvwxyz"),
            """{"type":"K","str":"hijklmnopqrstuvwxyz_","mode":"I"}""",
        )
    }
}
