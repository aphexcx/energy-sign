package cx.aphex.energysign.beatlinkdata

import android.content.ContextWrapper
import java.util.concurrent.Executor
import io.ktor.server.engine.ApplicationEngine
import java.net.BindException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.assertEquals

class BeatLinkListenerTest {
    @Test
    fun `a refused port releases the partial engine and a later bind starts exactly one listener`() {
        val service = BeatLinkListener(ContextWrapper(null), Executor { it.run() })
        val first = mock(ApplicationEngine::class.java)
        val next = mock(ApplicationEngine::class.java)
        `when`(first.start(false)).thenAnswer { throw BindException() }
        `when`(next.start(false)).thenReturn(next)
        var creations = 0
        val create = { if (++creations == 1) first else next }
        repeat(3) { service.ensureListening(create) }
        assertEquals(2, creations)
        verify(first).stop(0, 0)
        verify(next).start(false)
        service.close()
        verify(next).stop(0, 0)
    }

    @Test
    fun foregroundAndDestructionOnlyEnqueueNettyWork() {
        val jobs = ArrayDeque<Runnable>()
        val service = BeatLinkListener(ContextWrapper(null), Executor(jobs::addLast))
        val engine = mock(ApplicationEngine::class.java)
        `when`(engine.start(false)).thenReturn(engine)
        repeat(3) { service.ensureListening { engine } }
        verify(engine, never()).start(false)
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        verify(engine).start(false)
        service.close()
        verify(engine, never()).stop(0, 0)
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        verify(engine).stop(0, 0)
    }
}
