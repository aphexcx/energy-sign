package cx.aphex.energysign.message

import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.mockito.Mockito
import kotlin.test.assertFailsWith
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

    /** What "reached the sign": nothing does while the fake UART is down. */
    private val delivered = mutableListOf<String>()
    private var uartWorks = true

    /** The transmitter takes nothing: the write says so. */
    private var writeFails = false
    private val handedBack = mutableListOf<Message>()

    /** Messages whose frame the sign cannot take. */
    private val notSent = mutableListOf<String>()

    /** What each reply was said to be to the sign. */
    private val kinds = mutableListOf<Reply>()

    private val responder = SignResponder(
        nextMessage = source::next,
        write = { reply, kind ->
            kinds += kind
            written.add(reply)
            if (uartWorks) delivered.add(reply)
            !writeFails
        },
        unsent = { handedBack += it },
        tooLong = { message, bytes -> notSent += "${message.str.take(8)}:$bytes" },
    )

    private fun request(s: String): Message? = s.toByteArray(Charsets.US_ASCII).let { responder.onRequest(it, it.size) }

    private fun chooserJson(): String = SignResponder.GSON.toJson(ChooserProtocol.chooserMessage(source.stored, source.idx))

    // ---- a request in chooser mode takes nothing, however often it comes ----

    @Test
    fun `requests in a row in chooser mode all get the chooser message, and nothing is taken for them`() {
        source.inChooserMode = true
        repeat(5) { request("~") }
        assertEquals(List(5) { chooserJson() }, written)
        assertEquals(0, source.ordinaryServed)
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

    // ---- what a reply is to the sign

    @Test
    fun `a chooser message and a message of another kind are each said to be what they are`() {
        source.inChooserMode = true
        request("~")
        source.inChooserMode = false
        request("~")
        assertEquals(listOf(Reply.CHOOSER, Reply.MESSAGE), kinds)
    }

    // ---- a write that did not go out

    @Test
    fun `a message that could not be written is handed back and not reported as sent`() {
        source.inChooserMode = false
        writeFails = true

        assertNull(request("~"))

        assertEquals(1, source.ordinaryServed) // it was taken ...
        assertEquals("ORDINARY 1", handedBack.single().str) // ... and goes back
        writeFails = false
        assertEquals("ORDINARY 2", request("~")?.str)
        assertEquals(1, handedBack.size)
    }

    @Test
    fun `a message whose write throws is handed back like one whose write failed, and the error goes on to the caller`() {
        val message = Message.Marquee.User("HELLO")
        val error = IllegalStateException("the port is gone")
        val throwing = SignResponder(nextMessage = { message }, write = { _, _ -> throw error }, unsent = { handedBack += it })

        val thrown = try {
            throwing.onRequest("~".toByteArray(), 1)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertSame(error, thrown)
        assertEquals(listOf<Message>(message), handedBack)
    }

    // ---- a frame the sign cannot take

    @Test
    fun `a message whose frame is longer than the sign can take is not written and not handed back, the next request gets the next message`() {
        val long = "x".repeat(ChooserProtocol.MAX_FRAME_BYTES)
        val longResponder = SignResponder(
            nextMessage = { Message.Marquee.User(if (source.calls++ == 0) long else "SHORT") },
            write = { reply, _ -> written.add(reply) },
            unsent = { handedBack += it },
            tooLong = { message, bytes -> notSent += "${message.str.take(8)}:$bytes" },
        )
        val ask = "~".toByteArray()

        assertNull(longResponder.onRequest(ask, 1))

        assertTrue(written.isEmpty()) // the sign gets nothing and asks again
        assertTrue(handedBack.isEmpty())
        assertEquals(listOf("xxxxxxxx:${ChooserProtocol.MAX_FRAME_BYTES + 21}"), notSent)
        assertEquals("SHORT", longResponder.onRequest(ask, 1)?.str)
        assertEquals(1, written.size)
    }

    @Test
    fun `a frame of exactly the sign's size is written`() {
        val fits = "x".repeat(ChooserProtocol.MAX_FRAME_BYTES - 21) // {"str":"...","type":"D"} is 21 bytes around the text
        val responder = SignResponder(nextMessage = { Message.Marquee.User(fits) }, write = { reply, _ -> written.add(reply) })
        assertEquals(fits, responder.onRequest("~".toByteArray(), 1)?.str)
        assertEquals(ChooserProtocol.MAX_FRAME_BYTES, written.single().toByteArray().size)
    }

    @Test
    fun warmUpReportsTheFirstFailedAdapterAfterPreparingTheOthers() {
        val unavailable = IllegalArgumentException("adapter unavailable")
        val visited = linkedSetOf<Class<*>>()
        Mockito.mockStatic(TypeToken::class.java) { invocation ->
            val type = invocation.arguments.singleOrNull() as? Class<*>
            if (invocation.method.name == "get" && type != null && Message::class.java.isAssignableFrom(type)) {
                if (visited.add(type) && visited.size == 1) throw unavailable
            }
            invocation.callRealMethod()
        }.use {
            assertSame(unavailable, assertFailsWith<IllegalArgumentException> { SignResponder.warmUp() })
            assertTrue(visited.size >= 20, "continue preparing the other message types")
        }
    }

    // ---- the first write of every message class is prepared ahead of time

    @Test
    fun `warming up prepares every message class and changes nothing that is written`() {
        val before = SignResponder.GSON.toJson(Message.Marquee.User("HELLO"))
        val prepared = SignResponder.warmUp()
        // every kind the app can send: marquee texts, colour messages, icons, announcements, utility, chooser, keyboard echo
        assertTrue(prepared >= 20, "$prepared classes")
        assertEquals(before, SignResponder.GSON.toJson(Message.Marquee.User("HELLO")))
        assertEquals(prepared, SignResponder.warmUp())
    }
}
