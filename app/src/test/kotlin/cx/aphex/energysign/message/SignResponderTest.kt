package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The request -> reply sequence the view model runs for every UART callback,
 * driven with a fake message source and a fake UART.
 */
class SignResponderTest {

    /** Stands in for MessageManager: chooser mode is side-effect free, normal mode consumes a message. */
    private class FakeSource {
        val stored: MutableList<Message.Marquee> = (1..12).map { Message.Marquee.User("MESSAGE $it") }.toMutableList()
        var inChooserMode: Boolean = false
        var idx: Int = 0
        var ordinaryServed: Int = 0
        var calls: Int = 0

        fun next(): Message {
            calls++
            return if (inChooserMode) {
                ChooserProtocol.chooserMessage(stored, idx)
            } else {
                Message.Marquee.User("ORDINARY ${++ordinaryServed}")
            }
        }
    }

    private val source = FakeSource()
    private val written = mutableListOf<String>()
    private var uartWorks = true

    private val responder = SignResponder(
        nextMessage = source::next,
        write = { json -> written.add(json); uartWorks },
    )

    private fun request(s: String): Message? = s.toByteArray(Charsets.US_ASCII).let { responder.onRequest(it, it.size) }

    private fun chooserJson(): String = SignResponder.GSON.toJson(ChooserProtocol.chooserMessage(source.stored, source.idx))

    @Test
    fun `a normal request in chooser mode writes the full chooser message`() {
        source.inChooserMode = true

        val sent = request("~")

        assertEquals(listOf(chooserJson()), written)
        assertTrue(sent is Message.Chooser)
    }

    @Test
    fun `a poll after an unchanged chooser message writes only the two bytes`() {
        source.inChooserMode = true
        request("~")

        val sent = request("?")

        assertEquals(listOf(chooserJson(), "{}"), written)
        assertNull(sent)
    }

    @Test
    fun `polls keep answering with two bytes for as long as nothing changes`() {
        source.inChooserMode = true
        request("~")

        repeat(5) { request("?") }

        assertEquals(listOf(chooserJson()) + List(5) { "{}" }, written)
    }

    @Test
    fun `a poll after next writes the new chooser message once and then two bytes`() {
        source.inChooserMode = true
        request("~")
        val first = chooserJson()

        source.idx = 1 // !next
        request("?")
        request("?")

        assertEquals(listOf(first, chooserJson(), "{}"), written)
    }

    @Test
    fun `a poll after delete picks up the changed count`() {
        source.inChooserMode = true
        request("~")
        val first = chooserJson()

        source.stored.removeAt(0) // !delete: same index, new text, 11 messages
        request("?")

        assertEquals(listOf(first, chooserJson()), written)
        assertTrue(written.last().contains("1/11"))
    }

    @Test
    fun `a poll after a new incoming message picks up the changed count`() {
        source.inChooserMode = true
        source.idx = 4
        request("~")
        val first = chooserJson()

        source.stored.add(0, Message.Marquee.User("BRAND NEW")) // a new message goes on top and the index returns to 0
        source.idx = 0
        request("?")

        assertEquals(listOf(first, chooserJson()), written)
        assertTrue(written.last().contains("1/13"))
    }

    @Test
    fun `a normal request always gets the full message even when nothing changed`() {
        source.inChooserMode = true

        request("~")
        request("~")

        assertEquals(listOf(chooserJson(), chooserJson()), written)
    }

    @Test
    fun `the very first request being a poll gets the full message`() {
        source.inChooserMode = true

        request("?")

        assertEquals(listOf(chooserJson()), written)
    }

    @Test
    fun `a poll after chooser mode ended gets the next ordinary message exactly once`() {
        source.inChooserMode = true
        request("~")
        val chooser = chooserJson()

        source.inChooserMode = false // !endchoose
        val callsBefore = source.calls
        val sent = request("?")

        assertEquals(listOf(chooser, """{"str":"ORDINARY 1","type":"D"}"""), written)
        assertEquals(1, source.calls - callsBefore, "a consumed message must be sent, never evaluated twice")
        assertEquals("ORDINARY 1", sent?.str)
    }

    @Test
    fun `coming back to the same chooser message after an ordinary one sends it in full again`() {
        source.inChooserMode = true
        request("~")
        source.inChooserMode = false
        request("~")

        source.inChooserMode = true // !choose again, same message as before
        request("?")
        request("?")

        assertEquals(chooserJson(), written[2])
        assertEquals("{}", written[3])
    }

    @Test
    fun `an unchanged poll evaluates the message source once and returns nothing to display`() {
        source.inChooserMode = true
        request("~")
        val callsBefore = source.calls

        val sent = request("?")

        assertEquals(1, source.calls - callsBefore)
        assertNull(sent)
    }

    @Test
    fun `a chooser message whose write failed is sent again on the next poll`() {
        source.inChooserMode = true
        uartWorks = false
        request("~")

        uartWorks = true
        request("?")
        request("?")

        assertEquals(listOf(chooserJson(), chooserJson(), "{}"), written)
    }

    @Test
    fun `the message that was written is returned for the screen`() {
        val sent = request("~")

        assertEquals("ORDINARY 1", sent?.str)
        assertSame(Message.Marquee.User::class, sent!!::class)
    }

    @Test
    fun `garbled request bytes are answered like a normal request`() {
        source.inChooserMode = true
        request("~")

        request("InvalidInput")

        assertEquals(listOf(chooserJson(), chooserJson()), written)
    }
}
