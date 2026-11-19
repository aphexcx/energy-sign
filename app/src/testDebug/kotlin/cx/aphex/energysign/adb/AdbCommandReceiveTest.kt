package cx.aphex.energysign.adb

import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.OwnerQueue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One broadcast, from the first thing the receiver does with it to its end:
 * THE HOOK NEVER PROMISES WHAT NOTHING ALIVE HOLDS, AND IT KEEPS WHAT IT PROMISES. Every
 * broadcast gets exactly one answer by the deadline; "underway" is answered only for a command
 * the running app has been handed, and then exactly one line in the log says how it ended, or
 * that the hook gave up waiting; a command that was not handed over by the deadline is refused
 * and is not carried out later.
 *
 * The rule is ONE TABLE ([table]): the state of the app when the broadcast comes, the way the
 * command ends, the answer, and the line after it. The tests behind the table are the things
 * that can fail in the hook itself (a thread that cannot be started, a callback that throws).
 *
 * The hook's clock and the moment its timer's thread gets to run are the test's: no test waits
 * for a deadline to come (two wait 200 and 300 ms, for a time that the test has set to be left),
 * and "nothing more is said" is asked when the hook's threads have ended, not after a sleep.
 *
 * The receiver itself (Android) cannot run here; everything it does after goAsync() is the one
 * call of AdbCommand.receive that these tests make.
 */
class AdbCommandReceiveTest {

    private companion object {
        /** The limit for the answer in these tests: a minute, which no test waits for. */
        const val LIMIT_MS: Long = 60_000
    }

    private val next = "!next".toByteArray(Charsets.UTF_8)

    private class Said(val outcome: AdbCommand.Outcome, val line: String)

    /** One broadcast in the test's hand: its clock, its timer's thread, and all that was said about it. */
    private inner class Broadcast {
        /** What the broadcast was answered with, and what went to the log after an answer. */
        val answers = LinkedBlockingQueue<Said>()
        val logged = LinkedBlockingQueue<Said>()

        /** What the app was handed, and on which thread. */
        val handed = CopyOnWriteArrayList<String>()
        val handedOn = CopyOnWriteArrayList<String>()

        /** What left a thread of the hook uncaught: on Android that ends the app. */
        val escaped = CopyOnWriteArrayList<Throwable>()

        /** The hook's clock, in nanoseconds. It moves when the test moves it. */
        val clock = AtomicLong(7_000_000_007L)

        @Volatile
        private var timer: Thread? = null

        /** The threads of the hook that were let run. */
        private val running = CopyOnWriteArrayList<Thread>()

        fun run(thread: Thread) {
            thread.setUncaughtExceptionHandler { _, e -> escaped += e }
            running += thread
            thread.start()
        }

        /** Starts every thread but the timer's: that one gets to run when the test says so ([timerGetsToRun]). */
        val holdingTheTimer: (Thread) -> Unit = { if (it.name == "adb-command-deadline") timer = it else run(it) }

        /** The thread that hands the command to the app, once the hook has started it. */
        fun commandThread(): Thread = running.single { it.name == "adb-command" }

        fun take(bytes: ByteArray): OwnerQueue.Asked<Unit> {
            handed += String(bytes)
            handedOn += Thread.currentThread().name
            return OwnerQueue.Asked.Done(Unit)
        }

        fun receive(
            command: () -> AdbCommand = { AdbCommand.Bytes(next) },
            app: () -> ((ByteArray) -> OwnerQueue.Asked<Unit>)? = { ::take },
            giveUpAfterMs: Long = 10 * LIMIT_MS,
            start: (Thread) -> Unit = holdingTheTimer,
            late: (AdbCommand.Outcome, String) -> Unit = { outcome, line -> logged.put(Said(outcome, line)) },
            done: (AdbCommand.Outcome, String) -> Unit = { outcome, line -> answers.put(Said(outcome, line)) },
        ) = AdbCommand.receive(command, app, LIMIT_MS, giveUpAfterMs, start, clock::get, late, done)

        /** The timer's thread runs at last, when [msLeft] of the limit are left. */
        fun timerGetsToRun(msLeft: Long) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(LIMIT_MS - msLeft))
            run(assertNotNull(timer, "the hook armed no timer"))
        }

        fun timeIsUp() = timerGetsToRun(msLeft = 0)

        fun answer(): Said = assertNotNull(answers.poll(10, TimeUnit.SECONDS), "the broadcast got no answer: it would be left open")

        fun lineAfterTheAnswer(): Said = assertNotNull(logged.poll(10, TimeUnit.SECONDS), "nothing in the log says how the command ended")

        /**
         * Nothing more is answered and nothing more is logged. Asked when receive has returned:
         * once every thread of the hook has run and ended too, there is nobody left to say anything.
         */
        fun assertNothingMore() {
            // A timer that was never let run gets its turn now, with the limit over: after an
            // answer it must find nothing left to say.
            val held = timer
            if (held != null && held !in running) timeIsUp()
            for (thread in running) {
                thread.join(30_000)
                assertFalse(thread.isAlive, "${thread.name} has not ended")
            }
            assertTrue(answers.isEmpty(), "a second answer: ${answers.map { it.outcome.answer }}")
            assertTrue(logged.isEmpty(), "a second line: ${logged.map { it.line }}")
            assertTrue(escaped.isEmpty(), "something left a thread of the hook: $escaped")
        }
    }

    // ---- the rule, as one table

    /** The app when the broadcast comes, as the hook finds it. */
    private enum class App {
        /** Not running (this broadcast starts the process), or starting: no sign link runs the owner's queue. */
        NOT_UP,

        /** Up, but the receiver's thread does not get to the hand-over before the deadline. */
        UP_TOO_LATE,

        /** Asking the app for its way in throws. */
        THROWS,

        UP,
    }

    /** How a command that the app was handed ends. */
    private enum class Ends {
        AT_ONCE,

        /** The owner threw on it. */
        THROWS,

        /** The owner did not take it up, and the queue withdrew it. */
        NEVER_TAKEN,
        AFTER_THE_DEADLINE,
        NEVER,
    }

    private class Row(
        val app: App,
        /** Null: the command is not handed to the app at all. */
        val ends: Ends?,
        val kind: AdbCommand.Kind,
        /** How the answer begins. */
        val answer: String,
        /** How the one line begins that follows the answer in the log, when one does. */
        val thenInTheLog: String? = null,
    )

    private val table = listOf(
        Row(App.NOT_UP, null, AdbCommand.Kind.REFUSED, "refused: the app is not up yet, send it again"),
        Row(App.UP_TOO_LATE, null, AdbCommand.Kind.REFUSED, "refused: the command was not handed to the app within 60000 ms, and it will not be"),
        Row(App.THROWS, null, AdbCommand.Kind.REFUSED, "refused: the hook failed before the command was handed to the app (NoClassDefFoundError: some/Class)"),
        Row(App.UP, Ends.AT_ONCE, AdbCommand.Kind.OK, "ok 5 bytes"),
        Row(App.UP, Ends.THROWS, AdbCommand.Kind.FAILED, "failed: the app threw IllegalStateException (boom); the command may have been carried out in part"),
        Row(App.UP, Ends.NEVER_TAKEN, AdbCommand.Kind.REFUSED, "refused: the sign link did not get to the change within 2000 ms"),
        Row(App.UP, Ends.AFTER_THE_DEADLINE, AdbCommand.Kind.UNDERWAY, "underway: no outcome in time", thenInTheLog = "adb write, after its answer: ok 5 bytes [!next]"),
        Row(App.UP, Ends.NEVER, AdbCommand.Kind.UNDERWAY, "underway: no outcome in time", thenInTheLog = "adb write, after its answer: gave up after 60 s: the app has not finished the command"),
    )

    @TestFactory
    fun `every state of the app when the broadcast comes and every way a command ends - one answer, and after an underway one line`(): List<DynamicTest> =
        table.map { row ->
            dynamicTest("the app ${row.app}, the command ${row.ends ?: "not handed over"}: ${row.answer.take(60)}") {
                val broadcast = Broadcast()
                // Where the deadline is to find this row: in the look-up of the way in, or in the command.
                val there = CountDownLatch(1)
                val goOn = CountDownLatch(1)
                val slow = row.app == App.UP_TOO_LATE || row.ends == Ends.AFTER_THE_DEADLINE || row.ends == Ends.NEVER
                val wayIn: (ByteArray) -> OwnerQueue.Asked<Unit> = { bytes ->
                    broadcast.take(bytes)
                    when (row.ends) {
                        Ends.THROWS -> OwnerQueue.Asked.Threw(IllegalStateException("boom"))
                        Ends.NEVER_TAKEN -> OwnerQueue.Asked.Withdrawn
                        Ends.AFTER_THE_DEADLINE, Ends.NEVER -> {
                            there.countDown()
                            goOn.await(30, TimeUnit.SECONDS)
                            OwnerQueue.Asked.Done(Unit)
                        }
                        else -> OwnerQueue.Asked.Done(Unit)
                    }
                }
                val main = Thread({
                    broadcast.receive(
                        // The row that never ends is given up 200 ms after its deadline.
                        giveUpAfterMs = if (row.ends == Ends.NEVER) LIMIT_MS + 200 else 10 * LIMIT_MS,
                        app = {
                            when (row.app) {
                                App.NOT_UP -> null
                                App.THROWS -> throw NoClassDefFoundError("some/Class")
                                App.UP_TOO_LATE -> {
                                    there.countDown()
                                    goOn.await(30, TimeUnit.SECONDS)
                                    wayIn
                                }
                                App.UP -> wayIn
                            }
                        },
                    )
                }, "main-stand-in").apply { start() }
                if (slow) {
                    assertTrue(there.await(10, TimeUnit.SECONDS))
                    broadcast.timeIsUp()
                }

                val said = broadcast.answer()
                assertEquals(row.kind, said.outcome.kind, said.outcome.answer)
                assertTrue(said.outcome.answer.startsWith(row.answer), said.outcome.answer)
                assertTrue(said.line.startsWith("adb write: ${row.answer}") && said.line.endsWith(" [!next]"), said.line)

                // The command that never ends has its line before it is let go; the others after.
                if (row.ends == Ends.NEVER) assertTrue(broadcast.lineAfterTheAnswer().line.startsWith(row.thenInTheLog!!))
                assertTrue(broadcast.logged.isEmpty(), "a line that nothing has earned yet: ${broadcast.logged.map { it.line }}")
                goOn.countDown()
                if (row.ends == Ends.AFTER_THE_DEADLINE) assertEquals(row.thenInTheLog, broadcast.lineAfterTheAnswer().line)

                main.join(30_000)
                assertFalse(main.isAlive, "the receiver's thread is held")
                broadcast.assertNothingMore()
                if (row.ends == null) {
                    // "refused" means that nothing happens, later included. Every thread of the
                    // hook has ended by now: nothing is left that could still hand it over.
                    assertTrue(broadcast.handed.isEmpty(), "the hook went on to hand the command over: ${broadcast.handed}")
                } else {
                    assertEquals(listOf("!next"), broadcast.handed.toList(), "handed over once")
                    assertEquals(listOf("adb-command"), broadcast.handedOn.toList(), "the caller is the main thread, which must never wait for the owner")
                }
            }
        }

    @Test
    fun `the app is up only while the thread that runs the owner's queue is alive - and the hook makes no message manager to find that out`() {
        val made = AtomicInteger()
        fun appWith(queue: OwnerQueue) = koinApplication {
            modules(module {
                single { queue }
                single<MessageManager> {
                    made.incrementAndGet()
                    throw IllegalStateException("the manager is being made: it reads the saved messages from storage")
                }
            })
        }.koin

        // The app has not got as far as starting Koin.
        assertNull(AdbCommand.entryWhenUp(null))
        // This broadcast started the process: there is a queue, and no sign link has run it.
        assertNull(AdbCommand.entryWhenUp(appWith(OwnerQueue())))
        // The link was stopped: the thread that ran the queue has ended.
        val stopped = OwnerQueue()
        Thread({ stopped.drain() }, "a-link-that-ended").apply { start() }.join()
        assertNull(AdbCommand.entryWhenUp(appWith(stopped)))
        assertEquals(0, made.get(), "the manager was asked for, and Koin makes it then, for a command that is refused")

        // Up: the main screen made the manager before it started the link; it is there to be taken.
        val running = OwnerQueue().also { it.drain() }
        assertFails { AdbCommand.entryWhenUp(appWith(running)) }
        assertEquals(1, made.get())
    }

    @Test
    fun `what the extras are refused for is the answer, and the app is not asked for anything`() {
        val broadcast = Broadcast()
        var asked = false

        broadcast.receive(command = { AdbCommand.Refused("no extra: give one") }, app = {
            asked = true
            broadcast::take
        })

        val said = assertNotNull(broadcast.answers.poll(), "answered before receive returns")
        assertEquals("refused: no extra: give one", said.outcome.answer)
        assertEquals(AdbCommand.Kind.REFUSED, said.outcome.kind)
        assertEquals("adb write: refused: no extra: give one", said.line)
        assertFalse(asked)
        broadcast.assertNothingMore()
    }

    // ---- one clock, read when the broadcast comes (gate round 2 of this PR, the minor)

    @Test
    fun `the limit runs from the broadcast's arrival - a timer whose thread gets to run late waits only for what is left of it`() {
        val broadcast = Broadcast()
        val taken = CountDownLatch(1)
        val release = CountDownLatch(1)
        broadcast.receive(app = {
            { bytes ->
                taken.countDown()
                release.await(30, TimeUnit.SECONDS)
                broadcast.take(bytes)
            }
        })
        assertTrue(taken.await(10, TimeUnit.SECONDS))

        // A cold start kept the timer's thread from running for all but 300 ms of the minute.
        val from = System.nanoTime()
        broadcast.timerGetsToRun(msLeft = 300)

        assertEquals(AdbCommand.Kind.UNDERWAY, broadcast.answer().outcome.kind, "not a minute after the timer's thread first ran")
        val waitedMs = (System.nanoTime() - from) / 1_000_000
        assertTrue(waitedMs >= 250, "answered $waitedMs ms after the timer ran, with 300 ms of the limit left: before the time was up")
        release.countDown()
        broadcast.lineAfterTheAnswer()
        broadcast.assertNothingMore()
    }

    // ---- what can fail in the hook itself

    @Test
    fun `when the timer's thread cannot be started the broadcast is refused at once - nothing is done without a limit`() {
        val broadcast = Broadcast()
        var asked = false

        broadcast.receive(
            start = { if (it.name == "adb-command-deadline") throw InternalError("unable to create native thread") else broadcast.run(it) },
            command = {
                asked = true
                AdbCommand.Bytes(next)
            },
        )

        val said = assertNotNull(broadcast.answers.poll(), "answered before receive returns")
        assertEquals(AdbCommand.Kind.REFUSED, said.outcome.kind)
        assertTrue(said.outcome.answer.startsWith("refused: the hook could not start its timer (InternalError)"), said.outcome.answer)
        assertFalse(asked, "without a timer nothing that can take long is started")
        assertTrue(broadcast.handed.isEmpty())
        broadcast.assertNothingMore()
    }

    @Test
    fun `when the command's thread cannot be started the answer is a refusal, and the command is not carried out`() {
        val broadcast = Broadcast()

        broadcast.receive(start = { if (it.name == "adb-command") throw InternalError("unable to create native thread") else broadcast.holdingTheTimer(it) })

        val said = broadcast.answer()
        assertEquals(AdbCommand.Kind.REFUSED, said.outcome.kind)
        assertTrue(said.outcome.answer.startsWith("refused: the hook could not start the command's thread (InternalError)"), said.outcome.answer)
        assertTrue(broadcast.handed.isEmpty())
        broadcast.assertNothingMore()
    }

    @Test
    fun `the line that follows an underway is never in the log before that answer is given`() {
        val broadcast = Broadcast()
        val taken = CountDownLatch(1)
        val commandEnds = CountDownLatch(1)
        val answering = CountDownLatch(1)
        val answerGiven = CountDownLatch(1)
        broadcast.receive(
            app = {
                { bytes ->
                    taken.countDown()
                    commandEnds.await(30, TimeUnit.SECONDS)
                    broadcast.take(bytes)
                }
            },
            // Giving the answer takes a moment (the log, the broadcast's result), and the command ends in it.
            done = { outcome, line ->
                answering.countDown()
                answerGiven.await(30, TimeUnit.SECONDS)
                broadcast.answers.put(Said(outcome, line))
            },
        )
        assertTrue(taken.await(10, TimeUnit.SECONDS))
        broadcast.timeIsUp()
        assertTrue(answering.await(10, TimeUnit.SECONDS))
        commandEnds.countDown()

        // The command's thread has its line and stands behind the answer: or it has written it and is gone.
        val command = broadcast.commandThread()
        while (command.isAlive && command.state != Thread.State.BLOCKED) Thread.sleep(1)
        assertTrue(broadcast.logged.isEmpty(), "'after its answer', and the answer is not given yet: ${broadcast.logged.map { it.line }}")

        answerGiven.countDown()
        assertEquals(AdbCommand.Kind.UNDERWAY, broadcast.answer().outcome.kind)
        assertEquals("adb write, after its answer: ok 5 bytes [!next]", broadcast.lineAfterTheAnswer().line)
        broadcast.assertNothingMore()
    }

    @Test
    fun `a callback that throws ends no thread of the hook, and the command is still carried out once`() {
        val broadcast = Broadcast()
        val taken = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)

        // The answer's callback throws on the timer's thread, the log's callback on the command's.
        broadcast.receive(
            app = {
                { bytes ->
                    taken.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    broadcast.take(bytes).also { ended.countDown() }
                }
            },
            done = { _, _ -> release.countDown(); throw IllegalStateException("the answer could not be given") },
            late = { _, _ -> throw NoClassDefFoundError("the log's class") },
        )
        assertTrue(taken.await(10, TimeUnit.SECONDS))
        broadcast.timeIsUp()
        assertTrue(ended.await(10, TimeUnit.SECONDS))
        // ... and on the caller's own thread, for an answer given at once.
        broadcast.receive(command = { AdbCommand.Refused("no") }, done = { _, _ -> throw IllegalStateException("the answer could not be given") })

        broadcast.assertNothingMore()
        assertEquals(listOf("!next"), broadcast.handed.toList())
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
