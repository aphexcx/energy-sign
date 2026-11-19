package cx.aphex.energysign.beatlinkdata

import android.content.ContextWrapper
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.ktor.server.engine.ApplicationEngine
import java.net.BindException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class BeatLinkListenerTest {
    @Test fun expiredBlockedBindAllowsReplacementBeforeItReturns() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var expire: () -> Unit
        val listener = BeatLinkListener(ContextWrapper(null), executor) { action -> expire = action; {} }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacement = CountDownLatch(1)
        val first = mock(ApplicationEngine::class.java)
        val next = mock(ApplicationEngine::class.java)
        `when`(first.start(false)).thenAnswer { entered.countDown(); release.await(); first }
        `when`(next.start(false)).thenAnswer { replacement.countDown(); next }
        try {
            listener.ensureListening { first }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            expire()
            listener.ensureListening { next }
            assertTrue(replacement.await(1, TimeUnit.SECONDS), "replacement blocked behind the old bind")
        } finally {
            release.countDown()
            listener.close()
            executor.shutdown()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

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

    @Test
    fun aBindReturningAfterItsDeadlineIsClosedAndForegroundRetries() {
        var expire: (() -> Unit)? = null
        val listener = BeatLinkListener(ContextWrapper(null), Executor { it.run() }) { action ->
            expire = action
            { expire = null }
        }
        val stale = mock(ApplicationEngine::class.java)
        val healthy = mock(ApplicationEngine::class.java)
        `when`(stale.start(false)).thenAnswer { expire!!.invoke(); stale }
        `when`(healthy.start(false)).thenReturn(healthy)
        listener.ensureListening { stale }
        verify(stale).stop(0, 0)
        listener.ensureListening { healthy }
        listener.ensureListening { error("duplicate bind") }
        verify(healthy).start(false)
        listener.close()
    }
}
