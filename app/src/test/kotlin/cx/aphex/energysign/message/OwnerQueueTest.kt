package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The queue through which every change to the message state reaches the one thread that owns it. */
class OwnerQueueTest {
    private val queue = OwnerQueue()
    private val ran = mutableListOf<String>()

    @Test
    fun `posting runs nothing, the owner runs it, in the order it was posted`() {
        queue.post { ran += "a" }
        queue.post { ran += "b" }
        queue.post { ran += "c" }
        assertEquals(emptyList(), ran)
        assertEquals(3, queue.drain())
        assertEquals(listOf("a", "b", "c"), ran)
        assertEquals(0, queue.drain())
    }

    @Test
    fun `every post wakes the owner`() {
        var woken = 0
        queue.wake = { woken++ }
        queue.post {}
        queue.post {}
        assertEquals(2, woken)
    }

    @Test
    fun `one drain runs a limited number, so the owner gets back to the wire, and the rest keep their order`() {
        repeat(40) { i -> queue.post { ran += "$i" } }
        assertEquals(OwnerQueue.MAX_PER_DRAIN, queue.drain())
        assertEquals((0 until OwnerQueue.MAX_PER_DRAIN).map { "$it" }, ran)
        while (queue.drain() > 0) Unit
        assertEquals((0 until 40).map { "$it" }, ran)
    }

    @Test
    fun `a change that throws is reported and the ones behind it still run`() {
        val errors = mutableListOf<String>()
        queue.post { ran += "a" }
        queue.post { error("boom") }
        queue.post { ran += "c" }
        queue.drain { errors += it.message.orEmpty() }
        assertEquals(listOf("a", "c"), ran)
        assertEquals(listOf("boom"), errors)
    }

    @Test
    fun `a change posted by a change runs after those already waiting`() {
        queue.post {
            ran += "a"
            queue.post { ran += "from a" }
        }
        queue.post { ran += "b" }
        queue.drain()
        assertEquals(listOf("a", "b", "from a"), ran)
    }

    // ---- asking: for a caller that has to tell someone what happened

    /** Runs [call] on another thread (an HTTP handler) while this thread, the owner, drains. */
    private fun <T> asked(ownerWaitsMs: Long = 0, call: () -> T): Result<T> {
        val result = FutureTask(Callable { runCatching(call) })
        Thread(result, "http").start()
        Thread.sleep(ownerWaitsMs)
        while (!result.isDone) {
            queue.drain { throw AssertionError("an asked change must tell its asker, not the owner's log", it) }
            Thread.sleep(1)
        }
        return result.get()
    }

    @Test
    fun `asking returns what the change returned, once the owner has run it`() {
        queue.drain() // this thread is the owner
        var made = false
        val answer = asked(ownerWaitsMs = 50) {
            queue.ask {
                made = true
                ran += Thread.currentThread().name
                42
            }
        }
        assertEquals(42, answer.getOrThrow())
        assertTrue(made)
        assertEquals(listOf(Thread.currentThread().name), ran) // made by the owner, not by the asker
    }

    @Test
    fun `asking throws what the change threw`() {
        queue.drain()
        val answer = asked { queue.ask<Int> { throw NoSuchElementException("nothing to reply to") } }
        assertTrue(answer.exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun `an owner that does not get to the change in time, the asker is told, and the change is not made later`() {
        var made = false
        val answer = runCatching { queue.ask(timeoutMs = 50) { made = true } }
        assertTrue(answer.exceptionOrNull() is TimeoutException, answer.toString())
        queue.drain() // the owner turns up afterwards
        assertFalse(made)
    }

    @Test
    fun `a change the owner has taken up when the time runs out is waited for, its asker gets the real result`() {
        // the gate's case: the owner starts the change just before the bound; a 503 here and a retry would store it twice
        var made = 0
        val answer = asked(ownerWaitsMs = 30) {
            queue.ask(timeoutMs = 60) {
                Thread.sleep(150) // still under way when the 60 ms are over
                made++
                "stored"
            }
        }
        assertEquals("stored", answer.getOrThrow())
        assertEquals(1, made)
    }

    @Test
    fun `the owner asking itself is answered at once, it does not wait for itself`() {
        queue.drain()
        assertEquals(7, queue.ask(timeoutMs = 50) { 7 })
    }

    @Test
    fun `a thread that must not wait is refused, and can still post`() {
        queue.neverWaitsOn(Thread.currentThread())
        val answer = runCatching { queue.ask(timeoutMs = 50) { ran += "asked" } }
        assertTrue(answer.exceptionOrNull() is IllegalStateException)
        queue.post { ran += "posted" }
        queue.drain()
        assertEquals(listOf("posted"), ran)
    }
}
