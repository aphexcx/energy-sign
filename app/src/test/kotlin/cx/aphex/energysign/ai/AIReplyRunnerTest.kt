package cx.aphex.energysign.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The part that waits: one call at a time, one deadline for all of a query, one result for every ask. */
class AIReplyRunnerTest {

    private val results = LinkedBlockingQueue<AIReplyResult>()

    private fun next(): AIReplyResult = assertNotNull(results.poll(5, TimeUnit.SECONDS), "no result arrived")

    private fun reason(result: AIReplyResult) = assertIs<AIReplyResult.NoReply>(result, "expected no reply, got $result").reason

    /** A runner whose reply only looks at the text. */
    private fun runner(
        warmUp: (() -> Unit)? = null,
        log: (String) -> Unit = {},
        reply: suspend (String) -> AIReplyResult,
    ) = AIReplyRunner(reply = { text, _, _ -> reply(text) }, warmUp = warmUp, log = log)

    @Test
    fun `the reply is handed back, on another thread`() {
        val caller = Thread.currentThread()
        var handedBackOn: Thread? = null
        val runner = runner { text -> AIReplyResult.Reply("re: $text") }

        runner.ask("hello") {
            handedBackOn = Thread.currentThread()
            results.add(it)
        }

        assertEquals(AIReplyResult.Reply("re: hello"), next())
        assertTrue(handedBackOn !== caller)
    }

    @Test
    fun `ask returns at once, however long the reply takes`() {
        val release = CompletableDeferred<Unit>()
        val runner = runner { release.await(); AIReplyResult.Reply("late") }

        val startedAt = System.nanoTime()
        runner.ask("hello") { results.add(it) }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMs < 500, "ask took $tookMs ms")
        assertNull(results.poll(200, TimeUnit.MILLISECONDS), "nothing can have come back yet")
        release.complete(Unit)
        assertEquals(AIReplyResult.Reply("late"), next())
    }

    @Test
    fun `while a call is out a second ask sends nothing and is told so at once`() {
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val runner = runner { calls.incrementAndGet(); release.await(); AIReplyResult.Reply("first") }
        runner.ask("first") { results.add(it) }

        val second = LinkedBlockingQueue<AIReplyResult>()
        runner.ask("second") { second.add(it) }

        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(assertNotNull(second.poll(2, TimeUnit.SECONDS))))
        release.complete(Unit)
        assertEquals(AIReplyResult.Reply("first"), next())
        assertEquals(1, calls.get(), "the second ask must not reach the provider")
    }

    @Test
    fun `after a call has ended the next one goes out`() {
        val runner = runner { AIReplyResult.Reply(it) }

        runner.ask("one") { results.add(it) }
        assertEquals(AIReplyResult.Reply("one"), next())
        runner.ask("two") { results.add(it) }
        assertEquals(AIReplyResult.Reply("two"), next())
    }

    // ---- one budget of time for a query (class B)

    @Test
    fun `a query gets ONE deadline, counted from the moment it is asked, and the reply is handed it`() {
        val handed = LinkedBlockingQueue<Long>()
        val started = CountDownLatch(1)
        val mayStart = CountDownLatch(1)
        val runner = AIReplyRunner(
            reply = { _, deadline, _ ->
                started.countDown()
                handed.add(deadline.remainingMs())
                AIReplyResult.Reply("ok")
            },
            budget = Duration.ofSeconds(100),
            // The work starts late: the time until then is the query's time too.
            scope = CoroutineScope(
                SupervisorJob() + Executor { work ->
                    Thread {
                        mayStart.await()
                        work.run()
                    }.start()
                }.asCoroutineDispatcher()
            ),
        )

        runner.ask("hello") { results.add(it) }
        Thread.sleep(400)
        mayStart.countDown()

        val left = assertNotNull(handed.poll(5, TimeUnit.SECONDS))
        assertTrue(left in 90_000..99_700, "the deadline started when the query was asked, 400 ms before the work: $left ms were left")
        assertEquals(AIReplyResult.Reply("ok"), next())
    }

    @Test
    fun `the question whether the query is still wanted is handed to the reply as it was given`() {
        val asked = AtomicInteger()
        val stillWanted = { asked.incrementAndGet(); false }
        val runner = AIReplyRunner(reply = { _, _, wanted -> AIReplyResult.Reply(if (wanted()) "wanted" else "not wanted") })

        runner.ask("hello", stillWanted) { results.add(it) }

        assertEquals(AIReplyResult.Reply("not wanted"), next())
        assertEquals(1, asked.get())
    }

    @Test
    fun `nothing stands behind the reply - an answer that comes after the budget is still handed back, it is not turned into a timeout`() {
        // The time limit is handed down and each step keeps to it. The runner has no clock of
        // its own that could end the wait while the provider is still writing a paid answer.
        val runner = AIReplyRunner(
            reply = { _, _, _ ->
                delay(700)
                AIReplyResult.Reply("paid for, and late")
            },
            budget = Duration.ofMillis(200),
        )

        runner.ask("hello") { results.add(it) }

        assertEquals(AIReplyResult.Reply("paid for, and late"), next())
    }

    @Test
    fun `the slot is free only when the call has really ended`() {
        val mayEnd = CountDownLatch(1)
        val runner = AIReplyRunner(
            reply = { _, _, _ ->
                mayEnd.await()
                AIReplyResult.Reply("first")
            },
            budget = Duration.ofMillis(100),
        )
        runner.ask("hello") { results.add(it) }
        Thread.sleep(300)

        // The budget has passed, but the call has not come back: nothing else may go out.
        val second = LinkedBlockingQueue<AIReplyResult>()
        runner.ask("second") { second.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(assertNotNull(second.poll(2, TimeUnit.SECONDS))))
        assertNull(results.poll(200, TimeUnit.MILLISECONDS), "no result before the call has ended")

        mayEnd.countDown()
        assertEquals(AIReplyResult.Reply("first"), next())
    }

    @Test
    fun `a reply that throws, an Error included, is still one result`() {
        runner { throw IllegalStateException("boom") }.ask("a") { results.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(next()))

        runner { throw NoClassDefFoundError("some/sdk/Class") }.ask("b") { results.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(next()))
    }

    @Test
    fun `a receiver that throws does not take the runner down, the next ask works`() {
        val runner = runner { AIReplyResult.Reply(it) }
        val first = CountDownLatch(1)

        runner.ask("one") {
            first.countDown()
            throw IllegalStateException("the receiver failed")
        }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        runner.ask("two") { results.add(it) }

        assertEquals(AIReplyResult.Reply("two"), next())
    }

    // ---- the warm-up

    @Test
    fun `with no warm-up there is none, and nothing is called back`() {
        val runner = runner { AIReplyResult.Reply(it) }
        var done = false

        assertFalse(runner.warmUp { done = true })

        Thread.sleep(100)
        assertFalse(done)
    }

    @Test
    fun `the warm-up runs on its own thread, says when it is done, and nothing is sent before that`() {
        val caller = Thread.currentThread()
        val warming = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        var warmedOn: Thread? = null
        val calls = AtomicInteger()
        val runner = runner(
            warmUp = {
                warmedOn = Thread.currentThread()
                warming.countDown()
                mayFinish.await()
            },
        ) { calls.incrementAndGet(); AIReplyResult.Reply(it) }
        val done = CountDownLatch(1)

        assertTrue(runner.warmUp { done.countDown() })
        assertTrue(warming.await(5, TimeUnit.SECONDS))
        assertTrue(warmedOn !== caller)

        runner.ask("too early") { results.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(next()))
        assertEquals(0, calls.get(), "nothing reaches the provider while the warm-up runs")

        mayFinish.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        runner.ask("now") { results.add(it) }
        assertEquals(AIReplyResult.Reply("now"), next())
    }

    @Test
    fun `a warm-up that fails still says it is done`() {
        val runner = runner(warmUp = { throw NoClassDefFoundError("some/sdk/Class") }) { AIReplyResult.Reply(it) }
        val done = CountDownLatch(1)

        assertTrue(runner.warmUp { done.countDown() })

        assertTrue(done.await(5, TimeUnit.SECONDS))
        runner.ask("after") { results.add(it) }
        assertEquals(AIReplyResult.Reply("after"), next())
    }

    @Test
    fun `a log that throws does not keep the warm-up from saying it is done`() {
        val runner = runner(
            warmUp = {},
            log = { throw ArrayIndexOutOfBoundsException("a date formatter used by two threads") },
        ) { AIReplyResult.Reply(it) }
        val done = CountDownLatch(1)

        runner.warmUp { done.countDown() }

        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `what is done first on the warm-up thread may fail - the priority Android refuses - and the warm-up runs and says it is done all the same`() {
        var onThread: Thread? = null
        var warmedOn: Thread? = null
        val runner = AIReplyRunner(
            reply = { text, _, _ -> AIReplyResult.Reply(text) },
            warmUp = { warmedOn = Thread.currentThread() },
            onWarmUpThread = {
                onThread = Thread.currentThread()
                throw SecurityException("no permission to change the priority of this thread")
            },
        )
        val done = CountDownLatch(1)

        assertTrue(runner.warmUp { done.countDown() })

        assertTrue(done.await(5, TimeUnit.SECONDS), "the end of the warm-up was never said")
        assertNotNull(warmedOn, "the warm-up itself did not run")
        assertSame(warmedOn, onThread, "it is the warm-up's own thread whose priority is set")
        runner.ask("after") { results.add(it) }
        assertEquals(AIReplyResult.Reply("after"), next())
    }

    @Test
    fun `when the warm-up thread cannot be started there is no warm-up, and queries are not held up for ever`() {
        val logged = LinkedBlockingQueue<String>()
        val runner = AIReplyRunner(
            reply = { text, _, _ -> AIReplyResult.Reply(text) },
            warmUp = {},
            log = { logged.add(it) },
            warmUpThread = { throw OutOfMemoryError("unable to create new native thread") },
        )
        var done = false

        assertFalse(runner.warmUp { done = true }, "no warm-up runs, and the caller is told so")

        runner.ask("now") { results.add(it) }
        assertEquals(AIReplyResult.Reply("now"), next())
        assertFalse(done)
        assertTrue(logged.single { it.contains("could not be started") }.contains("sign link continues"), "$logged")
    }

    // ---- found by the review of round 3

    @Test
    fun `the deadline of a query is counted on the clock the runner is given`() {
        var nanos = 5_000_000_000L
        val handed = LinkedBlockingQueue<Pair<Long, Long>>()
        val runner = AIReplyRunner(
            reply = { _, deadline, _ ->
                val atStart = deadline.remainingMs()
                nanos += 60_000_000_000L // a minute passes on that clock, the board asleep or not
                handed.add(atStart to deadline.remainingMs())
                AIReplyResult.Reply("ok")
            },
            budget = Duration.ofSeconds(100),
            nanoTime = { nanos },
        )

        runner.ask("hello") { results.add(it) }

        assertEquals(100_000L to 40_000L, handed.poll(5, TimeUnit.SECONDS))
        next()
    }

    // ---- nothing optional can keep the sign link from starting (round 3, deep 8)

    @Test
    fun `replies that cannot be built, whatever is thrown, are no replies - not an exception on the way to the sign link`() {
        val failures = listOf<Throwable>(
            IllegalStateException("boom"), NoClassDefFoundError("some/sdk/Class"), InternalError("unable to create native thread"),
            ExceptionInInitializerError("static"),
        )
        for (failure in failures) {
            val warned = mutableListOf<String>()

            val source = aiRepliesOrNone(warn = { warned += it }) { throw failure }

            assertFalse(source.warmUp { error("there is no warm-up to end") }, "$failure")
            source.ask("hey ravegpt") { results.add(it) }
            val result = assertIs<AIReplyResult.NoReply>(next())
            assertEquals(NoReplyReason.NOT_SET_UP, result.reason)
            assertFalse(result.reason.askAgain || result.reason.backOff, "the query becomes a plain message, quietly")
            assertNull(results.poll(50, TimeUnit.MILLISECONDS), "one result an ask")
            assertTrue(warned.single().contains(failure.javaClass.name), "$warned")
            assertTrue(warned.single().contains("sign link continues"), "$warned")
            assertFalse(warned.single().contains("boom") || warned.single().contains("some/sdk"), "only the kind of failure is logged")
        }
    }

    @Test
    fun `a log that fails while the replies cannot be built changes nothing, and replies that can be built are used as they are`() {
        val quiet = aiRepliesOrNone(warn = { throw IllegalStateException("the log failed") }) { throw IllegalStateException("boom") }
        quiet.ask("hey") { throw IllegalStateException("the caller failed") } // must not throw here either
        assertFalse(quiet.warmUp {})

        val real = AIReplyRunner(reply = { _, _, _ -> AIReplyResult.Reply("ok") })
        assertSame(real, aiRepliesOrNone(warn = { error("nothing to warn of") }) { real })
    }
}
