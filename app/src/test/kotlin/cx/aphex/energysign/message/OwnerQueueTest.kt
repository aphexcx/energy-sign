package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
