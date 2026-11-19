package cx.aphex.energysign.adb

import cx.aphex.energysign.message.OwnerQueue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
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

    /** What the owner's queue says of a change the owner ran to its end. */
    private val ran = OwnerQueue.Asked.Done(Unit)

    @Test
    fun `bytes the app carried out are answered with ok and their number`() {
        val got = mutableListOf<String>()

        val outcome = AdbCommand.carriedOut(next) {
            got += String(it)
            ran
        }

        assertEquals("ok 5 bytes", outcome.answer)
        assertTrue(outcome.accepted)
        assertNull(outcome.thrown)
        assertEquals(listOf("!next"), got, "handed on once, unchanged")
    }

    @Test
    fun `what the app threw is the answer, by kind and message, and it is not thrown on`() {
        val boom = IndexOutOfBoundsException("Index -1 out of bounds for length 3")

        val outcome = AdbCommand.carriedOut(next) { OwnerQueue.Asked.Threw(boom) }

        assertEquals(
            "failed: the app threw IndexOutOfBoundsException (Index -1 out of bounds for length 3); the command may have been carried out in part",
            outcome.answer,
        )
        assertFalse(outcome.answer.startsWith("refused"), "refused is only said when nothing happened")
        assertFalse(outcome.accepted)
        assertSame(boom, outcome.thrown)
    }

    @Test
    fun `an Error of the app is an answer too - this thread is the hook's, and nothing is gained by dying on it`() {
        val outcome = AdbCommand.carriedOut(next) { throw NoClassDefFoundError("some/Table") }

        assertEquals("failed: the call threw NoClassDefFoundError (some/Table); it is not known whether the command was, or will be, carried out", outcome.answer)
        assertFalse(outcome.accepted)
    }

    @Test
    fun `a command the sign link did not get to is refused in the link's own words`() {
        val outcome = AdbCommand.carriedOut(next) { OwnerQueue.Asked.Withdrawn }

        assertEquals(
            "refused: the sign link did not get to the change within 2000 ms (is the sign link running?)",
            outcome.answer,
        )
        assertFalse(outcome.answer.contains("in part"), "this one is definite: the change was withdrawn and nothing was done")
        assertFalse(outcome.accepted)
        assertEquals(AdbCommand.Kind.REFUSED, outcome.kind)
    }

    // ---- an answer is one of three things, and never the wrong one (gate round 3)

    @Test
    fun `a TimeoutException of the app's own is not the queue's withdrawal - it is failed, never refused`() {
        val own = TimeoutException("the card timed out")

        // Thrown by the command while the owner ran it, and thrown by the call itself: neither
        // is the queue saying "withdrawn", and after either the command may have happened.
        val byTheOwner = AdbCommand.carriedOut(next) { OwnerQueue.Asked.Threw(own) }
        val byTheCall = AdbCommand.carriedOut(next) { throw own }

        assertEquals("failed: the app threw TimeoutException (the card timed out); the command may have been carried out in part", byTheOwner.answer)
        // What the call threw is not "the app threw on the command": the command may not have
        // run at all, or may still run, whole. The answer says that this is not known.
        assertEquals("failed: the call threw TimeoutException (the card timed out); it is not known whether the command was, or will be, carried out", byTheCall.answer)
        for (outcome in listOf(byTheOwner, byTheCall)) {
            assertEquals(AdbCommand.Kind.FAILED, outcome.kind)
            assertSame(own, outcome.thrown)
        }
    }

    @Test
    fun `what the owner threw on the command is failed, whatever kind of exception it is`() {
        val kinds = listOf<Throwable>(
            IllegalStateException("x"), InterruptedException("x"), java.util.concurrent.CancellationException("x"),
            java.util.concurrent.ExecutionException("x", null), NoClassDefFoundError("x"), TimeoutException("x"),
        )
        for (thrown in kinds) {
            for (outcome in listOf(AdbCommand.carriedOut(next) { OwnerQueue.Asked.Threw(thrown) }, AdbCommand.carriedOut(next) { throw thrown })) {
                assertEquals(AdbCommand.Kind.FAILED, outcome.kind, "$thrown")
                assertTrue(outcome.answer.startsWith("failed: the "), outcome.answer)
            }
        }
    }

    @Test
    fun `each kind of answer has a result code of its own`() {
        assertEquals(
            mapOf(AdbCommand.Kind.OK to -1, AdbCommand.Kind.REFUSED to 1, AdbCommand.Kind.FAILED to 2, AdbCommand.Kind.UNDERWAY to 3),
            AdbCommand.Kind.values().associateWith { it.resultCode },
        )
        // 0 is what `am broadcast` prints when the receiver never answered (the app died in the
        // middle of the command): no answer of the hook may look like that, "refused" least of all.
        assertTrue(AdbCommand.Kind.values().none { it.resultCode == 0 })
        assertEquals(AdbCommand.Kind.OK, AdbCommand.carriedOut(next) { ran }.kind)
    }

    // ---- the answer comes within the time Android gives a broadcast (gate round 3)

}
