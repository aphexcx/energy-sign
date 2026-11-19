package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * askFor: what became of a change, as one of three values. The point of it: "withdrawn" is made
 * by the queue alone, so nothing a change does can be taken for it.
 */
class OwnerQueueAskForTest {
    private val queue = OwnerQueue()

    /** Runs [call] on another thread while this thread, the owner, runs what is posted. */
    private fun <T> asked(call: () -> T): T {
        val result = FutureTask(Callable(call))
        Thread(result, "asker").start()
        while (!result.isDone) {
            queue.drain()
            Thread.sleep(1)
        }
        return result.get()
    }

    @Test
    fun `a change the owner ran is Done with what it returned`() {
        assertEquals(7, assertIs<OwnerQueue.Asked.Done<Int>>(asked { queue.askFor { 7 } }).result)
    }

    @Test
    fun `what a change threw is Threw, an Error included, and is not thrown at the asker`() {
        for (thrown in listOf<Throwable>(IllegalStateException("no"), NoClassDefFoundError("x"), InterruptedException("x"))) {
            val result = asked { queue.askFor<Int> { throw thrown } }

            assertSame(thrown, assertIs<OwnerQueue.Asked.Threw>(result).thrown)
        }
    }

    @Test
    fun `a TimeoutException that a change throws is Threw - it is never taken for the queue's withdrawal`() {
        val own = TimeoutException("the card timed out")
        var changed = false

        val result = asked {
            queue.askFor<Unit> {
                changed = true
                throw own
            }
        }

        assertSame(own, assertIs<OwnerQueue.Asked.Threw>(result).thrown)
        assertTrue(changed)
        // ask cannot tell the two apart for its caller: both are a TimeoutException there.
        assertSame(own, asked { runCatching { queue.ask<Unit> { throw own } }.exceptionOrNull() })
    }

    @Test
    fun `a change the owner did not take up in time is Withdrawn, and is not made later`() {
        var made = false

        // Nobody runs the queue: the asker waits its time out on another thread.
        val asker = FutureTask(Callable { queue.askFor(timeoutMs = 50) { made = true } })
        Thread(asker, "asker").start()
        val result = asker.get(5, TimeUnit.SECONDS)

        assertSame(OwnerQueue.Asked.Withdrawn, result)
        queue.drain()
        assertEquals(false, made, "a withdrawn change must never run")
    }

    @Test
    fun `a change the owner has taken up is waited for past the time, and is Done`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val asker = FutureTask(Callable {
            queue.askFor(timeoutMs = 1_000) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
                "made"
            }
        })
        // The owner runs before anything is asked: on a busy machine it must not be the asker's
        // time that runs out while the owner's thread is still being started.
        val owner = Thread({ while (!asker.isDone) { queue.drain(); Thread.sleep(1) } }, "owner").apply { start() }
        Thread(asker, "asker").start()

        assertTrue(started.await(20, TimeUnit.SECONDS))
        Thread.sleep(1_500) // well past the 1000 ms
        assertEquals(false, asker.isDone, "the asker waits for a change that is being made")
        release.countDown()

        assertEquals("made", assertIs<OwnerQueue.Asked.Done<String>>(asker.get(5, TimeUnit.SECONDS)).result)
        owner.join(5_000)
    }

    @Test
    fun `the owner asking itself is answered at once, with Done or Threw`() {
        var inner: OwnerQueue.Asked<Int>? = null
        var innerThrew: OwnerQueue.Asked<Int>? = null
        val boom = IllegalStateException("no")

        asked {
            queue.askFor {
                inner = queue.askFor { 3 }
                innerThrew = queue.askFor { throw boom }
            }
        }

        assertEquals(3, assertIs<OwnerQueue.Asked.Done<Int>>(inner).result)
        assertSame(boom, assertIs<OwnerQueue.Asked.Threw>(innerThrew).thrown)
    }

    @Test
    fun `a thread that must not wait is refused by an exception, before anything is handed over`() {
        queue.neverWaitsOn(Thread.currentThread())
        var made = false

        assertFailsWith<IllegalStateException> { queue.askFor { made = true } }

        queue.drain()
        assertEquals(false, made)
    }
}
