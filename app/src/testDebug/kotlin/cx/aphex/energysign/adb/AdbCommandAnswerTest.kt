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

    @Test
    fun `when no outcome has come within the time the answer is underway, once, and the real outcome is handed over late`() {
        val release = CountDownLatch(1)
        val answers = LinkedBlockingQueue<AdbCommand.Outcome>()
        val late = LinkedBlockingQueue<AdbCommand.Outcome>()
        val started = System.nanoTime()

        AdbCommand.aside(
            next,
            carryOut = {
                release.await(30, TimeUnit.SECONDS)
                ran
            },
            answerWithinMs = 400, late = { late.put(it) }, done = { answers.put(it) },
        )

        val answer = assertNotNull(answers.poll(5, TimeUnit.SECONDS), "no answer although the time was up: the broadcast would be left open")
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(AdbCommand.Kind.UNDERWAY, answer.kind)
        assertTrue(answer.answer.startsWith("underway: no outcome in time"), answer.answer)
        assertFalse(answer.accepted)
        assertTrue(waitedMs >= 350, "answered after $waitedMs ms with a limit of 400: before the time was up")
        assertNull(late.poll(100, TimeUnit.MILLISECONDS), "the command has not ended yet")

        release.countDown()

        assertEquals("ok 5 bytes", late.poll(5, TimeUnit.SECONDS)?.answer, "how it ended is handed over, for the log")
        assertNull(answers.poll(200, TimeUnit.MILLISECONDS), "one broadcast, one answer")
    }

    @Test
    fun `an outcome that comes in time is the answer, and nothing is said after it`() {
        val answers = LinkedBlockingQueue<AdbCommand.Outcome>()
        val late = LinkedBlockingQueue<AdbCommand.Outcome>()

        // A long limit: on a busy machine the command's thread may not get to run for a while,
        // and that must not make this test fail. The deadline's thread is woken when the command
        // ends, so "no underway after the answer" is seen at once all the same.
        AdbCommand.aside(next, carryOut = { ran }, answerWithinMs = 20_000, late = { late.put(it) }, done = { answers.put(it) })

        assertEquals("ok 5 bytes", answers.poll(5, TimeUnit.SECONDS)?.answer)
        assertNull(answers.poll(700, TimeUnit.MILLISECONDS), "no 'underway' after the answer")
        assertNull(late.poll(50, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `when a thread of the hook cannot be started nothing is carried out, nothing is answered behind the caller's back, and the caller hears of it`() {
        for (failsOn in listOf("adb-command-deadline", "adb-command")) {
            val carried = CopyOnWriteArrayList<String>()
            val answers = LinkedBlockingQueue<AdbCommand.Outcome>()
            val refusal = InternalError("unable to create native thread")

            val thrown = runCatching {
                AdbCommand.aside(
                    next,
                    carryOut = {
                        carried += String(it)
                        ran
                    },
                    answerWithinMs = 200,
                    start = { if (it.name == failsOn) throw refusal else it.start() },
                    done = { answers.put(it) },
                )
            }.exceptionOrNull()

            assertSame(refusal, thrown, "the caller (the receiver) must hear that there is no thread: $failsOn")
            assertNull(answers.poll(800, TimeUnit.MILLISECONDS), "no answer may come from a thread that is left: $failsOn")
            assertTrue(carried.isEmpty(), "the command must not have been carried out: $failsOn")
        }
    }

    @Test
    fun `when the deadline has answered already and the command's thread then cannot be started, there is still one answer and nothing is thrown`() {
        // No time left (the receiver hands on 0 when its 5 s are gone), and no thread either. The
        // receiver answers and finishes its broadcast for every exception from here, and a
        // broadcast that is finished twice ends the app.
        val answers = LinkedBlockingQueue<AdbCommand.Outcome>()
        val carried = CopyOnWriteArrayList<String>()

        val thrown = runCatching {
            AdbCommand.aside(
                next,
                carryOut = {
                    carried += String(it)
                    ran
                },
                answerWithinMs = -5,
                start = {
                    if (it.name == "adb-command") {
                        assertEquals(AdbCommand.Kind.UNDERWAY, answers.poll(10, TimeUnit.SECONDS)?.kind, "the deadline answers first")
                        answers.put(AdbCommand.underway()) // put back, to be counted below
                        throw InternalError("unable to create native thread")
                    }
                    it.start()
                },
                done = { answers.put(it) },
            )
        }.exceptionOrNull()

        assertNull(thrown, "an exception here makes the receiver answer a second time")
        Thread.sleep(300)
        assertEquals(1, answers.size, "one answer")
        assertTrue(carried.isEmpty())
    }

    @Test
    fun `aside runs the command on another thread and hands over the one outcome`() {
        val here = Thread.currentThread()
        val ranOn = CopyOnWriteArrayList<Thread>()
        val outcomes = LinkedBlockingQueue<AdbCommand.Outcome>()

        AdbCommand.aside(
            next,
            carryOut = {
                ranOn += Thread.currentThread()
                ran
            },
            done = { outcomes.put(it) },
        )

        assertEquals("ok 5 bytes", outcomes.poll(5, TimeUnit.SECONDS)?.answer)
        assertEquals(1, ranOn.size)
        assertNotSame(here, ranOn.single(), "the caller is the main thread, which must never wait for the owner")
        assertNull(outcomes.poll(100, TimeUnit.MILLISECONDS), "one broadcast, one answer")
    }

    @Test
    fun `aside hands over an outcome when the command throws, so the broadcast is always finished`() {
        val outcomes = LinkedBlockingQueue<AdbCommand.Outcome>()

        val thread = AdbCommand.aside(next, carryOut = { throw IllegalStateException("no") }, done = { outcomes.put(it) })

        assertEquals("failed: the call threw IllegalStateException (no); it is not known whether the command was, or will be, carried out", outcomes.poll(5, TimeUnit.SECONDS)?.answer)
        thread.join(5_000)
        assertFalse(thread.isAlive)
    }
}
