package cx.aphex.energysign.message

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The frame of a push, and what the app is told once it is written (the screen, Bluetooth). */
class ChooserPushTest {
    private val stored = (1..3).map { Message.Marquee.User("MESSAGE $it") }
    private var idx: Int? = 0
    private var asked = 0
    private val told = mutableListOf<Message.Chooser>()

    private val push = ChooserPush(
        shown = {
            asked++
            idx?.let { ChooserProtocol.chooserMessage(stored, it) }
        },
        sent = { told += it },
    )

    @Test
    fun `the frame is the chooser message a request would get, and making it tells nothing`() {
        idx = 1
        val json = JsonParser.parseString(push.frame()).asJsonObject
        assertEquals("H", json["type"].asString)
        assertEquals("MESSAGE 2", json["str"].asString)
        assertEquals("2/3", json["flashy"].asString)
        assertEquals(emptyList(), told)
        assertEquals(1, asked)
    }

    @Test
    fun `once the frame is written the message that was framed is told, once`() {
        idx = 1
        push.frame()
        idx = 2 // the chooser has moved on since; what is on the wire is message 2
        push.written()
        assertEquals("MESSAGE 2", told.single().str)
        assertEquals("2/3", told.single().flashy)
        push.written()
        assertEquals(1, told.size)
    }

    @Test
    fun `a frame that was never written tells nothing, and the next one tells itself`() {
        idx = 0
        push.frame() // this push failed
        idx = 2
        val second = push.frame()
        push.written()
        assertEquals("MESSAGE 3", told.single().str)
        assertEquals("MESSAGE 3", JsonParser.parseString(second).asJsonObject["str"].asString)
    }

    @Test
    fun `outside chooser mode there is no frame and nothing to tell`() {
        idx = 1
        push.frame()
        idx = null
        assertNull(push.frame())
        push.written()
        assertEquals(emptyList(), told)
    }

    @Test
    fun `what is told is the very message that was framed`() {
        var last: Message.Chooser? = null
        val one = ChooserPush(shown = { ChooserProtocol.chooserMessage(stored, 0).also { last = it } }, sent = { told += it })
        one.frame()
        one.written()
        assertSame(last, told.single())
    }
}
