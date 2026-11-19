package cx.aphex.energysign.adb

import cx.aphex.energysign.message.OwnerQueue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One broadcast, from the first thing the receiver does with it to its end:
 * EVERY BROADCAST GETS EXACTLY ONE ANSWER INSIDE THE LIMIT, AND EVERY COMMAND THAT WAS ANSWERED
 * "underway" GETS EXACTLY ONE LINE IN THE LOG THAT SAYS HOW IT ENDED, whatever is slow and
 * whatever fails: reading the extras, getting the app's message manager (on a cold start that
 * reads the saved messages from storage, on the receiver's thread), a thread that cannot be
 * started, a callback that throws.
 *
 * The receiver itself (Android) cannot run here; everything it does after goAsync() is the one
 * call of AdbCommand.receive that these tests make.
 */
class AdbCommandReceiveTest {

    private val next = "!next".toByteArray(Charsets.UTF_8)
    private val ran = OwnerQueue.Asked.Done(Unit)

    private class Said(val outcome: AdbCommand.Outcome, val line: String)

    /** What the broadcast was answered with, and what went to the log after an answer. */
    private val answers = LinkedBlockingQueue<Said>()
    private val logged = LinkedBlockingQueue<Said>()
    private val carried = CopyOnWriteArrayList<String>()

    /** What left a thread of the hook uncaught: on Android that ends the app. */
    private val escaped = CopyOnWriteArrayList<Throwable>()

    private val threads: (Thread) -> Unit = {
        it.setUncaughtExceptionHandler { _, e -> escaped += e }
        it.start()
    }

    private fun carry(bytes: ByteArray): OwnerQueue.Asked<Unit> {
        carried += String(bytes)
        return ran
    }

    private fun receive(
        command: () -> AdbCommand = { AdbCommand.Bytes(next) },
        app: () -> ((ByteArray) -> OwnerQueue.Asked<Unit>)? = { ::carry },
        answerWithinMs: Long = 60_000,
        start: (Thread) -> Unit = threads,
        late: (AdbCommand.Outcome, String) -> Unit = { outcome, line -> logged.put(Said(outcome, line)) },
        done: (AdbCommand.Outcome, String) -> Unit = { outcome, line -> answers.put(Said(outcome, line)) },
    ) = AdbCommand.receive(command, app, answerWithinMs, start, late, done)

    private fun answer(): Said = assertNotNull(answers.poll(10, TimeUnit.SECONDS), "the broadcast got no answer: it would be left open")

    /** Nothing more is answered and nothing more is logged. */
    private fun assertNothingMore() {
        Thread.sleep(300)
        assertTrue(answers.isEmpty(), "a second answer: ${answers.map { it.outcome.answer }}")
        assertTrue(logged.isEmpty(), "a second line: ${logged.map { it.line }}")
        assertTrue(escaped.isEmpty(), "something left a thread of the hook: $escaped")
    }

    // ---- when all is well

    @Test
    fun `a command is carried out on another thread and answered once, with a line for the log`() {
        val here = Thread.currentThread()
        val ranOn = CopyOnWriteArrayList<Thread>()

        receive(app = {
            { bytes ->
                ranOn += Thread.currentThread()
                carry(bytes)
            }
        })

        val said = answer()
        assertEquals("ok 5 bytes", said.outcome.answer)
        assertEquals("adb write: ok 5 bytes [!next]", said.line)
        assertEquals(listOf("!next"), carried.toList())
        assertNotSame(here, ranOn.single(), "the caller is the main thread, which must never wait for the owner")
        assertNothingMore()
    }

    @Test
    fun `what the extras are refused for is the answer, and the app is not asked for anything`() {
        var asked = false

        receive(command = { AdbCommand.Refused("no extra: give one") }, app = {
            asked = true
            ::carry
        })

        val said = answer()
        assertEquals("refused: no extra: give one", said.outcome.answer)
        assertEquals(AdbCommand.Kind.REFUSED, said.outcome.kind)
        assertEquals("adb write: refused: no extra: give one", said.line)
        assertFalse(asked)
        assertNothingMore()
    }

    @Test
    fun `an app that has no message manager is a refusal`() {
        receive(app = { null })

        assertEquals("refused: the app is not ready (it has no MessageManager)", answer().outcome.answer)
        assertTrue(carried.isEmpty())
        assertNothingMore()
    }

    // ---- the limit holds from the first statement on (gate round 1 of this PR, the major)

    @Test
    fun `while the app's message manager is still being made the broadcast is answered underway inside the limit, and one line says how the command ended`() {
        // A cold start: the manager reads the saved messages from storage when it is made, on
        // the receiver's thread, and storage can take its time.
        val storage = CountDownLatch(1)
        val started = System.nanoTime()
        val receiver = Thread({
            receive(answerWithinMs = 500, app = {
                storage.await(30, TimeUnit.SECONDS)
                ::carry
            })
        }, "main-stand-in").apply { start() }

        val said = answer()
        val afterMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(AdbCommand.Kind.UNDERWAY, said.outcome.kind, said.outcome.answer)
        assertTrue(afterMs >= 450, "answered after $afterMs ms with a limit of 500: before the time was up")
        assertTrue(said.line.startsWith("adb write: underway: no outcome in time"), said.line)
        assertTrue(carried.isEmpty() && logged.isEmpty(), "nothing has happened yet")

        storage.countDown()
        receiver.join(10_000)

        val end = assertNotNull(logged.poll(10, TimeUnit.SECONDS), "a command that was answered 'underway' must get its line in the log")
        assertEquals("adb write, after its answer: ok 5 bytes [!next]", end.line)
        assertEquals(listOf("!next"), carried.toList())
        assertNothingMore()
    }

    @Test
    fun `while the extras are still being read the broadcast is answered underway inside the limit too`() {
        val slow = CountDownLatch(1)
        val receiver = Thread({
            receive(answerWithinMs = 400, command = {
                slow.await(30, TimeUnit.SECONDS)
                AdbCommand.Refused("bad base64 in b64")
            })
        }, "main-stand-in").apply { start() }

        assertEquals(AdbCommand.Kind.UNDERWAY, answer().outcome.kind)
        slow.countDown()
        receiver.join(10_000)

        val end = assertNotNull(logged.poll(10, TimeUnit.SECONDS))
        assertEquals("adb write, after its answer: refused: bad base64 in b64", end.line)
        assertNothingMore()
    }

    @Test
    fun `a command the owner is slow with is answered underway, and its end goes to the log once`() {
        val release = CountDownLatch(1)

        receive(answerWithinMs = 400, app = {
            { bytes ->
                release.await(30, TimeUnit.SECONDS)
                carry(bytes)
            }
        })

        assertEquals(AdbCommand.Kind.UNDERWAY, answer().outcome.kind)
        assertNull(logged.poll(100, TimeUnit.MILLISECONDS), "the command has not ended yet")
        release.countDown()

        assertEquals("adb write, after its answer: ok 5 bytes [!next]", logged.poll(10, TimeUnit.SECONDS)?.line)
        assertNothingMore()
    }

    @Test
    fun `an outcome that comes in time is the one answer - no underway after it, nothing in the log after it`() {
        receive(answerWithinMs = 60_000)

        assertEquals("ok 5 bytes", answer().outcome.answer)
        assertNothingMore()
    }

    // ---- whatever throws, and wherever

    @Test
    fun `reading the extras or getting the manager may throw anything - the answer is a refusal, and nothing is carried out`() {
        val failures = listOf<Throwable>(IllegalStateException("boom"), NoClassDefFoundError("some/Class"), StackOverflowError())
        for (failure in failures) {
            receive(command = { throw failure })
            val reading = answer()
            assertEquals(AdbCommand.Kind.REFUSED, reading.outcome.kind, "$failure")
            assertTrue(reading.outcome.answer.startsWith("refused: the hook failed before the command was handed to the app"), reading.outcome.answer)
            assertTrue(reading.outcome.answer.contains(failure.javaClass.simpleName), reading.outcome.answer)

            receive(app = { throw failure })
            val getting = answer()
            assertEquals(AdbCommand.Kind.REFUSED, getting.outcome.kind, "$failure")
            assertTrue(getting.line.endsWith("[!next]"), "the command is known by then: ${getting.line}")
        }
        assertTrue(carried.isEmpty())
        assertNothingMore()
    }

    @Test
    fun `when the timer's thread cannot be started the broadcast is refused at once - nothing is done without a limit`() {
        var asked = false

        receive(
            start = { if (it.name == "adb-command-deadline") throw InternalError("unable to create native thread") else threads(it) },
            command = {
                asked = true
                AdbCommand.Bytes(next)
            },
        )

        val said = assertNotNull(answers.poll(), "answered before receive returns")
        assertEquals(AdbCommand.Kind.REFUSED, said.outcome.kind)
        assertTrue(said.outcome.answer.startsWith("refused: the hook could not start its timer (InternalError)"), said.outcome.answer)
        assertFalse(asked, "without a timer nothing that can take long is started")
        assertTrue(carried.isEmpty())
        assertNothingMore()
    }

    @Test
    fun `when the command's thread cannot be started the answer is a refusal, and the command is not carried out`() {
        receive(start = { if (it.name == "adb-command") throw InternalError("unable to create native thread") else threads(it) })

        val said = answer()
        assertEquals(AdbCommand.Kind.REFUSED, said.outcome.kind)
        assertTrue(said.outcome.answer.startsWith("refused: the hook could not start the command's thread (InternalError)"), said.outcome.answer)
        assertTrue(carried.isEmpty())
        assertNothingMore()
    }

    @Test
    fun `when the command's thread cannot be started after the broadcast was answered underway, the log says that the command never ran`() {
        // The caller was told "underway: ... how it ends goes to the log". The log must not stay silent.
        receive(
            answerWithinMs = 0,
            start = {
                if (it.name == "adb-command") {
                    assertEquals(AdbCommand.Kind.UNDERWAY, answers.poll(10, TimeUnit.SECONDS)?.outcome?.kind, "the timer answers first")
                    throw InternalError("unable to create native thread")
                }
                threads(it)
            },
        )

        val end = assertNotNull(logged.poll(10, TimeUnit.SECONDS), "nothing in the log says how the command ended")
        assertEquals(AdbCommand.Kind.REFUSED, end.outcome.kind)
        assertTrue(end.line.startsWith("adb write, after its answer: refused: the hook could not start the command's thread"), end.line)
        assertTrue(carried.isEmpty())
        assertNothingMore()
    }

    @Test
    fun `a callback that throws ends no thread of the hook, and the command is still carried out once`() {
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)

        // The answer's callback throws on the timer's thread, the log's callback on the command's.
        receive(
            answerWithinMs = 300,
            app = {
                { bytes ->
                    release.await(30, TimeUnit.SECONDS)
                    carry(bytes).also { ended.countDown() }
                }
            },
            done = { _, _ -> release.countDown(); throw IllegalStateException("the answer could not be given") },
            late = { _, _ -> throw NoClassDefFoundError("the log's class") },
        )
        assertTrue(ended.await(10, TimeUnit.SECONDS))
        // ... and on the caller's own thread, for an answer given at once.
        receive(command = { AdbCommand.Refused("no") }, done = { _, _ -> throw IllegalStateException("the answer could not be given") })

        Thread.sleep(300)
        assertTrue(escaped.isEmpty(), "an exception left a thread of the hook: on Android that ends the app: $escaped")
        assertEquals(listOf("!next"), carried.toList())
    }

    // ---- the line in the log (gate round 1 of this PR, a minor)

    @Test
    fun `the log line says the outcome first and shows a bounded piece of the command - a long command cannot push the outcome out of the line`() {
        val huge = ("!🅰\n" + "🆑DREAMSTATE\n".repeat(20_000)).toByteArray(Charsets.UTF_8)
        val ok = AdbCommand.Outcome(AdbCommand.Kind.OK, AdbCommand.accepted(huge), thrown = null)

        val line = AdbCommand.logLine("adb write, after its answer", ok, huge)

        assertTrue(line.startsWith("adb write, after its answer: ok ${huge.size} bytes"), line.take(80))
        assertTrue(line.toByteArray(Charsets.UTF_8).size < 2_000, "the line is ${line.toByteArray().size} bytes: logcat cuts an entry at about 4000")
        assertFalse(line.contains('\n') || line.contains('\r'), "one line")
        assertTrue(line.contains("DREAMSTATE"), "the start of the command is shown: $line")
        assertTrue(line.endsWith("...]"), "and that it was cut: ${line.takeLast(20)}")
    }

    @Test
    fun `a long message of an exception is bounded in the log line as well, and a cut never splits an emoji`() {
        val long = AdbCommand.Outcome(AdbCommand.Kind.FAILED, "failed: the app threw X (" + "é".repeat(50_000) + ")", thrown = null)
        val line = AdbCommand.logLine("adb write", long, next)
        assertTrue(line.startsWith("adb write: failed: the app threw X ("), line.take(60))
        assertTrue(line.toByteArray(Charsets.UTF_8).size < 3_000, "${line.toByteArray().size} bytes")
        assertTrue(line.endsWith("[!next]"), line.takeLast(20))

        for (lead in 0..3) {
            val emoji = ("x".repeat(lead) + "👾".repeat(200)).toByteArray(Charsets.UTF_8)
            val cut = AdbCommand.logLine("adb write", AdbCommand.Outcome(AdbCommand.Kind.OK, "ok", null), emoji)
            assertFalse(cut.any { it.isSurrogate() } && cut.windowed(2).none { it[0].isHighSurrogate() && it[1].isLowSurrogate() }, cut)
            assertEquals(cut.count { it.isHighSurrogate() }, cut.count { it.isLowSurrogate() }, "half an emoji in: $cut")
        }
        assertEquals("adb write: refused: no extra", AdbCommand.logLine("adb write", AdbCommand.Outcome(AdbCommand.Kind.REFUSED, "refused: no extra", null), null))
    }
}
