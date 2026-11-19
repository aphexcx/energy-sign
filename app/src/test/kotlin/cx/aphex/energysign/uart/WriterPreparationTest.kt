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
        preparing.beforeFirstRead()
        repeat(3) { preparing.onForeground() }
        assertEquals(1, calls)
        assertEquals(1, queue.size)
        queue.removeFirst()()
        preparing.onForeground()
        assertEquals(2, calls)
        assertEquals(0, queue.size)
    }
}
