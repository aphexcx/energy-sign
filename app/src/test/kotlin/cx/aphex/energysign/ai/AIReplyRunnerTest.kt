package cx.aphex.energysign.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runInterruptible
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The part that waits: one call at a time, one deadline for all of a call, one result for every ask. */
class AIReplyRunnerTest {

    private val results = LinkedBlockingQueue<AIReplyResult>()

    private fun next(): AIReplyResult = assertNotNull(results.poll(5, TimeUnit.SECONDS), "no result arrived")

    private fun reason(result: AIReplyResult) = assertIs<AIReplyResult.NoReply>(result, "expected no reply, got $result").reason

    @Test
    fun `the reply is handed back, on another thread`() {
        val caller = Thread.currentThread()
        var handedBackOn: Thread? = null
        val runner = AIReplyRunner(reply = { text -> AIReplyResult.Reply("re: $text") })

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
        val runner = AIReplyRunner(reply = { release.await(); AIReplyResult.Reply("late") })

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
        val runner = AIReplyRunner(reply = { calls.incrementAndGet(); release.await(); AIReplyResult.Reply("first") })
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
        val runner = AIReplyRunner(reply = { AIReplyResult.Reply(it) })

        runner.ask("one") { results.add(it) }
        assertEquals(AIReplyResult.Reply("one"), next())
        runner.ask("two") { results.add(it) }
        assertEquals(AIReplyResult.Reply("two"), next())
    }

    @Test
    fun `a call that does not end is ended at the deadline, and the result is a timeout`() {
        var cancelled = false
        val runner = AIReplyRunner(
            reply = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            deadline = Duration.ofMillis(300),
        )

        val startedAt = System.nanoTime()
        runner.ask("hello") { results.add(it) }
        val result = next()
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(NoReplyReason.TIMEOUT, reason(result))
        assertTrue(tookMs in 250..3_000, "took $tookMs ms for a 300 ms deadline")
        assertTrue(cancelled, "the call itself must be stopped, not left running")
    }

    @Test
    fun `a blocking call is interrupted at the deadline, and the slot is free only when it has really ended`() {
        val interrupted = CountDownLatch(1)
        val mayEnd = CountDownLatch(1)
        val runner = AIReplyRunner(
            reply = {
                runInterruptible {
                    try {
                        Thread.sleep(60_000)
                    } catch (e: InterruptedException) {
                        interrupted.countDown()
                        // a call that takes its time to come back after the interrupt
                        mayEnd.await()
                    }
                    AIReplyResult.Reply("never used")
                }
            },
            deadline = Duration.ofMillis(300),
        )
        runner.ask("hello") { results.add(it) }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the blocking call was not interrupted")

        // The deadline has passed, but the call has not come back: nothing else may go out.
        val second = LinkedBlockingQueue<AIReplyResult>()
        runner.ask("second") { second.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(assertNotNull(second.poll(2, TimeUnit.SECONDS))))
        assertNull(results.poll(200, TimeUnit.MILLISECONDS), "no result before the call has ended")

        mayEnd.countDown()
        assertEquals(NoReplyReason.TIMEOUT, reason(next()))
    }

    @Test
    fun `a reply that throws, an Error included, is still one result`() {
        AIReplyRunner(reply = { throw IllegalStateException("boom") }).ask("a") { results.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(next()))

        AIReplyRunner(reply = { throw NoClassDefFoundError("some/sdk/Class") }).ask("b") { results.add(it) }
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, reason(next()))
    }

    @Test
    fun `a receiver that throws does not take the runner down, the next ask works`() {
        val runner = AIReplyRunner(reply = { AIReplyResult.Reply(it) })
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
        val runner = AIReplyRunner(reply = { AIReplyResult.Reply(it) })
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
        val runner = AIReplyRunner(
            reply = { calls.incrementAndGet(); AIReplyResult.Reply(it) },
            warmUp = {
                warmedOn = Thread.currentThread()
                warming.countDown()
                mayFinish.await()
            },
        )
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
        val runner = AIReplyRunner(reply = { AIReplyResult.Reply(it) }, warmUp = { throw NoClassDefFoundError("some/sdk/Class") })
        val done = CountDownLatch(1)

        assertTrue(runner.warmUp { done.countDown() })

        assertTrue(done.await(5, TimeUnit.SECONDS))
        runner.ask("after") { results.add(it) }
        assertEquals(AIReplyResult.Reply("after"), next())
    }

    @Test
    fun `a log that throws does not keep the warm-up from saying it is done`() {
        val runner = AIReplyRunner(
            reply = { AIReplyResult.Reply(it) },
            warmUp = {},
            log = { throw ArrayIndexOutOfBoundsException("a date formatter used by two threads") },
        )
        val done = CountDownLatch(1)

        runner.warmUp { done.countDown() }

        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
}
