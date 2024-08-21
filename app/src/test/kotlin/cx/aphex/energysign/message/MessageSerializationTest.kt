package cx.aphex.energysign.message

import android.graphics.Color
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import kotlin.test.assertEquals

class MessageSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "_class"
    }

    private lateinit var mockedColor: MockedStatic<Color>

    @BeforeEach
    fun setup() {
        mockedColor = Mockito.mockStatic(Color::class.java)
        mockedColor.`when`<Int> { Color.red(Mockito.anyInt()) }.thenReturn(255)
        mockedColor.`when`<Int> { Color.green(Mockito.anyInt()) }.thenReturn(0)
        mockedColor.`when`<Int> { Color.blue(Mockito.anyInt()) }.thenReturn(0)
    }

    @Test
    fun testMarqueeUserSerialization() {
        val message = Message.Marquee.User("Test Message")
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.Marquee.User>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testMarqueeGPTQuerySerialization() {
        val message = Message.Marquee.GPTQuery("Test GPT Query")
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.Marquee.GPTQuery>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testMarqueeChonkySerialization() {
        val message = Message.Marquee.Chonky("Chonky Message")
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.Marquee.Chonky>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testMarqueeGPTReplySerialization() {
        val message = Message.Marquee.GPTReply("GPT Reply")
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.Marquee.GPTReply>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testStarfieldSerialization() {
        val message = Message.Starfield("Starfield", 500)
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.Starfield>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testNowPlayingTrackMessageSerialization() {
        val message = Message.NowPlayingTrackMessage("Now Playing: Test Track")
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.NowPlayingTrackMessage>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testOneByOneMessageSerialization() {
        val message = Message.ColorMessage.OneByOneMessage("One By One", 0xFF0000, 500)
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.ColorMessage.OneByOneMessage>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testChonkySlideSerialization() {
        val message = Message.ColorMessage.ChonkySlide("Chonky Slide", 0x00FF00, 750)
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.ColorMessage.ChonkySlide>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testGPTThinkingSerialization() {
        val message = Message.ColorMessage.GPTThinking("...", 0x0000FF)
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.ColorMessage.GPTThinking>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testIconInvadersSerialization() {
        val message = Message.ColorMessage.IconInvaders.Enemy1(0xFFFF00)
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.ColorMessage.IconInvaders.Enemy1>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testFlashingAnnouncementSerialization() {
        val message = Message.FlashingAnnouncement.CustomFlashyAnnouncement("Flash", 300)
        val jsonString = json.encodeToString(message)
        val deserializedMessage =
            json.decodeFromString<Message.FlashingAnnouncement.CustomFlashyAnnouncement>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testCountDownAnnouncementSerialization() {
        val message = Message.CountDownAnnouncement.NewMessageAnnouncement
        val jsonString = json.encodeToString(message)
        val deserializedMessage =
            json.decodeFromString<Message.CountDownAnnouncement.NewMessageAnnouncement>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testUtilityMessageSerialization() {
        val message = Message.UtilityMessage.EnableMic
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.UtilityMessage.EnableMic>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testChooserSerialization() {
        val message = Message.Chooser(1, 5, Message.Marquee.User("Choose me"))
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.Chooser>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @Test
    fun testKeyboardEchoSerialization() {
        val message = Message.KeyboardEcho.Input("Test Input")
        val jsonString = json.encodeToString(message)
        val deserializedMessage = json.decodeFromString<Message.KeyboardEcho.Input>(jsonString)
        assertEquals(message, deserializedMessage)
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        mockedColor.close()
    }
}
