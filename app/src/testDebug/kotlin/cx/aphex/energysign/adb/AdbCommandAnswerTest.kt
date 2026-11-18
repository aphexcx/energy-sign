package cx.aphex.energysign.adb

import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The answer to a broadcast says what the app DID with the bytes, and it is given from a thread of its own. */
class AdbCommandAnswerTest {

    private val next = "!next".toByteArray(Charsets.UTF_8)

    @Test
    fun `bytes the app carried out are answered with ok and their number`() {
        val got = mutableListOf<String>()

        val outcome = AdbCommand.carriedOut(next) { got += String(it) }

        assertEquals("ok 5 bytes", outcome.answer)
        assertTrue(outcome.accepted)
        assertNull(outcome.thrown)
        assertEquals(listOf("!next"), got, "handed on once, unchanged")
    }

    @Test
    fun `what the app threw is the answer, by kind and message, and it is not thrown on`() {
        val boom = IndexOutOfBoundsException("Index -1 out of bounds for length 3")

        val outcome = AdbCommand.carriedOut(next) { throw boom }

        assertEquals("refused: the app threw IndexOutOfBoundsException (Index -1 out of bounds for length 3)", outcome.answer)
        assertFalse(outcome.accepted)
        assertSame(boom, outcome.thrown)
    }

    @Test
    fun `an Error of the app is an answer too - this thread is the hook's, and nothing is gained by dying on it`() {
        val outcome = AdbCommand.carriedOut(next) { throw NoClassDefFoundError("some/Table") }

        assertEquals("refused: the app threw NoClassDefFoundError (some/Table)", outcome.answer)
        assertFalse(outcome.accepted)
    }

    @Test
    fun `a command the sign link did not get to is refused in the link's own words`() {
        val outcome = AdbCommand.carriedOut(next) {
            throw TimeoutException("the sign link did not get to the change within 2000 ms")
        }

        assertEquals(
            "refused: the sign link did not get to the change within 2000 ms (is the sign link running?)",
            outcome.answer,
        )
        assertFalse(outcome.accepted)
        assertNotNull(outcome.thrown)
    }

    @Test
    fun `aside runs the command on another thread and hands over the one outcome`() {
        val here = Thread.currentThread()
        val ranOn = CopyOnWriteArrayList<Thread>()
        val outcomes = LinkedBlockingQueue<AdbCommand.Outcome>()

        AdbCommand.aside(next, carryOut = { ranOn += Thread.currentThread() }, done = { outcomes.put(it) })

        assertEquals("ok 5 bytes", outcomes.poll(5, TimeUnit.SECONDS)?.answer)
        assertEquals(1, ranOn.size)
        assertNotSame(here, ranOn.single(), "the caller is the main thread, which must never wait for the owner")
        assertNull(outcomes.poll(100, TimeUnit.MILLISECONDS), "one broadcast, one answer")
    }

    @Test
    fun `aside hands over an outcome when the command throws, so the broadcast is always finished`() {
        val outcomes = LinkedBlockingQueue<AdbCommand.Outcome>()

        val thread = AdbCommand.aside(next, carryOut = { throw IllegalStateException("no") }, done = { outcomes.put(it) })

        assertEquals("refused: the app threw IllegalStateException (no)", outcomes.poll(5, TimeUnit.SECONDS)?.answer)
        thread.join(5_000)
        assertFalse(thread.isAlive)
    }
}
