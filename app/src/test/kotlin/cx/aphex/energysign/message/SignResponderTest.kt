package cx.aphex.energysign.message

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The request -> reply sequence the view model runs for every UART callback,
 * driven with a fake message source and a fake UART.
 *
 * The expected checksums are literals worked out outside the app (CRC-16/CCITT-FALSE over
 * str, one zero byte, flashy), so these tests do not lean on the app's own checksum code.
 */
class SignResponderTest {

    /** Stands in for MessageManager: chooser mode is side-effect free, normal mode consumes a message. */
    private class FakeSource {
        val stored: MutableList<Message.Marquee> =
            (1..12).map { Message.Marquee.User(if (it == 3) "HELLO W" else "MESSAGE $it") }.toMutableList()
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

    /** Everything the responder tried to write, in order. */
    private val written = mutableListOf<String>()

    /** The subset the responder marked as poll reports (the view model keeps those out of the log). */
    private val markedAsPollReport = mutableListOf<String>()

    /** What "reached the sign": nothing does while the fake UART is down. */
    private val delivered = mutableListOf<String>()
    private var uartWorks = true

    private val responder = SignResponder(
        nextMessage = source::next,
        write = { reply, isPollReport ->
            written.add(reply)
            if (isPollReport) markedAsPollReport.add(reply)
            if (uartWorks) delivered.add(reply)
        },
    )

    private fun request(s: String): Message? = s.toByteArray(Charsets.US_ASCII).let { responder.onRequest(it, it.size) }

    private fun chooserJson(): String = SignResponder.GSON.toJson(ChooserProtocol.chooserMessage(source.stored, source.idx))

    private companion object {
        const val REPORT_1_OF_12 = """{"c":60031}""" // MESSAGE 1, 1/12
        const val REPORT_2_OF_12 = """{"c":48963}""" // MESSAGE 2, 2/12
        const val REPORT_3_OF_12 = """{"c":13345}""" // HELLO W, 3/12 (a contract vector)
        const val REPORT_1_OF_11 = """{"c":5372}""" // MESSAGE 2, 1/11
        const val REPORT_1_OF_13 = """{"c":47005}""" // BRAND NEW, 1/13
        const val REPORT_EMPTY = """{"c":10573}""" // <empty>, 0/0
    }

    // ---- the poll in chooser mode: always a state report ----

    @Test
    fun `a poll in chooser mode is answered with the checksum of the current chooser message`() {
        source.inChooserMode = true
        source.idx = 2
        request("~")

        val sent = request("?")

        assertEquals(listOf(chooserJson(), REPORT_3_OF_12), written)
        assertNull(sent, "a state report is not a message for the screen")
    }

    @Test
    fun `the reply to a poll is byte for byte the object c and nothing else`() {
        source.inChooserMode = true
        source.idx = 2

        request("?")

        assertEquals(
            listOf('{', '"', 'c', '"', ':', '1', '3', '3', '4', '5', '}').map { it.code.toByte() },
            written.single().toByteArray(Charsets.UTF_8).toList()
        )
    }

    @Test
    fun `the very first request being a poll gets a report, not the message`() {
        source.inChooserMode = true

        request("?")

        assertEquals(listOf(REPORT_1_OF_12), written)
    }

    @Test
    fun `polls in a row all get the same report`() {
        source.inChooserMode = true

        repeat(5) { request("?") }

        assertEquals(List(5) { REPORT_1_OF_12 }, written)
    }

    @Test
    fun `a poll after a normal request gets the report for the message that request was sent`() {
        source.inChooserMode = true
        request("~")

        request("?")
        request("?")

        assertEquals(listOf(chooserJson(), REPORT_1_OF_12, REPORT_1_OF_12), written)
    }

    @Test
    fun `a poll after a failed write still gets only the report`() {
        // The app does not know what reached the sign and does not guess. The sign compares the
        // report with what it shows and asks for the full message itself when they differ.
        source.inChooserMode = true
        uartWorks = false
        request("~")

        uartWorks = true
        request("?")
        request("?")

        assertEquals(listOf(chooserJson(), REPORT_1_OF_12, REPORT_1_OF_12), written)
        assertEquals(listOf(REPORT_1_OF_12, REPORT_1_OF_12), delivered)
    }

    @Test
    fun `a poll after next reports the new message, every time`() {
        source.inChooserMode = true
        request("~")
        val first = chooserJson()

        source.idx = 1 // !next
        request("?")
        request("?")

        assertEquals(listOf(first, REPORT_2_OF_12, REPORT_2_OF_12), written)
    }

    @Test
    fun `a poll after delete reports the changed text and count`() {
        source.inChooserMode = true
        request("?")

        source.stored.removeAt(0) // !delete: same index, new text, 11 messages
        request("?")

        assertEquals(listOf(REPORT_1_OF_12, REPORT_1_OF_11), written)
    }

    @Test
    fun `a poll after a new incoming message reports the changed count`() {
        source.inChooserMode = true
        source.idx = 2
        request("?")

        source.stored.add(0, Message.Marquee.User("BRAND NEW")) // a new message goes on top and the index returns to 0
        source.idx = 0
        request("?")

        assertEquals(listOf(REPORT_3_OF_12, REPORT_1_OF_13), written)
    }

    @Test
    fun `a poll with no message at the index reports the empty placeholder`() {
        source.inChooserMode = true
        source.stored.clear()
        source.idx = -1 // where the manager's index is after the last message was deleted

        request("?")

        assertEquals(listOf(REPORT_EMPTY), written)
    }

    @Test
    fun `stepping away and back gives the first report again`() {
        source.inChooserMode = true
        request("?")
        source.idx = 1
        request("?")

        source.idx = 0 // !prev
        request("?")

        assertEquals(listOf(REPORT_1_OF_12, REPORT_2_OF_12, REPORT_1_OF_12), written)
    }

    @Test
    fun `no poll in chooser mode is ever answered with an empty object or a message`() {
        source.inChooserMode = true
        request("?")
        request("~")
        request("?")
        source.idx = 5
        request("?")
        uartWorks = false
        request("~")
        request("?")
        uartWorks = true
        source.stored.removeAt(5)
        request("InvalidInput?")
        request("~?")

        val pollReplies = written.filterNot { it.contains("\"type\"") }
        assertEquals(6, pollReplies.size)
        pollReplies.forEach { reply ->
            assertTrue(Regex("""\{"c":(0|[1-9][0-9]{0,4})\}""").matches(reply), "poll reply was $reply")
            assertEquals(setOf("c"), JsonParser.parseString(reply).asJsonObject.keySet())
        }
        // and the two normal requests were the only full messages
        assertEquals(2, written.count { it.contains("\"type\":\"H\"") })
    }

    @Test
    fun `the app side of the gate's sequence ends on the new message`() {
        // The sign shows message 1. A poll's reply is late, the selection changes, the sign's
        // recovery request and every poll after it are answered from what the app holds NOW.
        source.inChooserMode = true
        request("~")
        request("?") // this reply is the one that arrives late

        source.idx = 1 // the selection changes
        request("~") // the sign's recovery request
        val second = chooserJson()
        request("?")
        request("?")

        assertEquals(listOf(REPORT_1_OF_12, second, REPORT_2_OF_12, REPORT_2_OF_12), written.drop(1))
    }

    // ---- the poll consumes nothing ----

    @Test
    fun `a poll evaluates the message source once and returns nothing to display`() {
        source.inChooserMode = true
        val callsBefore = source.calls

        val sent = request("?")

        assertEquals(1, source.calls - callsBefore)
        assertNull(sent)
    }

    @Test
    fun `a normal request after polls gets the same chooser message in full`() {
        source.inChooserMode = true
        source.idx = 2
        request("~")
        val before = written.single()

        repeat(3) { request("?") }
        val sent = request("~")

        assertEquals(before, written.last())
        assertEquals("""{"str":"HELLO W","flashy":"3/12","type":"H"}""", written.last())
        assertTrue(sent is Message.Chooser)
        assertEquals(2, source.idx)
        assertEquals(0, source.ordinaryServed)
    }

    // ---- the poll outside chooser mode: answered like a normal request ----

    @Test
    fun `a poll outside chooser mode gets the next message in full and consumes it`() {
        val callsBefore = source.calls

        val sent = request("?")

        assertEquals(listOf("""{"str":"ORDINARY 1","type":"D"}"""), written)
        assertEquals(1, source.calls - callsBefore, "a consumed message must be sent, never evaluated twice")
        assertEquals("ORDINARY 1", sent?.str)

        request("~")
        assertEquals("""{"str":"ORDINARY 2","type":"D"}""", written.last())
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
        assertEquals(1, source.calls - callsBefore)
        assertEquals("ORDINARY 1", sent?.str)
    }

    @Test
    fun `coming back to the same chooser message after an ordinary one gets a report, not the message`() {
        source.inChooserMode = true
        request("~")
        source.inChooserMode = false
        request("~")

        source.inChooserMode = true // !choose again, same message as before
        request("?")
        request("?")

        assertEquals(listOf(REPORT_1_OF_12, REPORT_1_OF_12), written.drop(2))
    }

    // ---- the normal request: unchanged ----

    @Test
    fun `a normal request in chooser mode writes the full chooser message`() {
        source.inChooserMode = true

        val sent = request("~")

        assertEquals(listOf(chooserJson()), written)
        assertTrue(sent is Message.Chooser)
    }

    @Test
    fun `a normal request always gets the full message even when nothing changed`() {
        source.inChooserMode = true

        request("~")
        request("~")

        assertEquals(listOf(chooserJson(), chooserJson()), written)
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

    // ---- what the view model keeps out of the log ----

    @Test
    fun `only the reports are marked as poll reports`() {
        source.inChooserMode = true
        request("~")
        request("?")
        request("?")
        source.inChooserMode = false
        request("?") // answered with a message: must stay in the log
        request("~")

        assertEquals(listOf(REPORT_1_OF_12, REPORT_1_OF_12), markedAsPollReport)
        assertFalse(markedAsPollReport.any { it.contains("type") })
        assertEquals(5, written.size)
    }
}
