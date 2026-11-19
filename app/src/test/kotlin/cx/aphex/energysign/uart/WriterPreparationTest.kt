package cx.aphex.energysign.uart

import cx.aphex.energysign.SignStartup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WriterPreparationTest {
    @Test
    fun foregroundRetriesAFailedFirstPassOnlyOnTheLinkOwner() {
        var calls = 0
        val queue = ArrayDeque<() -> Unit>()
        val preparing = WriterPreparation(SignStartup {}, {
            if (++calls == 1) throw LinkageError()
        }, queue::addLast)
        preparing.onForeground()
        assertEquals(0, queue.size)
        preparing.beforeFirstRead { false }
        repeat(3) { preparing.onForeground() }
        assertEquals(1, calls)
        assertEquals(1, queue.size)
        queue.removeFirst()()
        preparing.onForeground()
        assertEquals(2, calls)
        assertEquals(0, queue.size)
    }

    @Test
    fun deadlineOrShutdownStopsTheTraversalBeforeTheNextAdapter() {
        val queue = ArrayDeque<() -> Unit>()
        var deadline: (() -> Unit)? = null
        var stop = false
        var calls = 0
        val preparing = WriterPreparation(SignStartup {}, { stopped ->
            calls++
            kotlin.test.assertFalse(stopped())
            if (calls == 1) deadline!!.invoke() else stop = true
            kotlin.test.assertTrue(stopped())
        }, queue::addLast) { fire -> deadline = fire; { deadline = null } }
        preparing.beforeFirstRead { stop }
        preparing.onForeground()
        queue.removeFirst()()
        preparing.onForeground()
        assertEquals(2, calls)
        assertEquals(0, queue.size)
    }
}
