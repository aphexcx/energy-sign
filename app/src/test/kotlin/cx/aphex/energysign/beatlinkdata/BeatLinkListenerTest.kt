package cx.aphex.energysign.beatlinkdata

import android.content.ContextWrapper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import cx.aphex.energysign.TestStartWorker
import io.ktor.server.engine.ApplicationEngine
import java.net.BindException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.*

class BeatLinkListenerTest {
    @Test fun expiredBlockedBindAllowsReplacementBeforeItReturns() {
        val worker = TestStartWorker()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stale = mock(ApplicationEngine::class.java)
        val healthy = mock(ApplicationEngine::class.java)
        `when`(stale.start(false)).thenAnswer { entered.countDown(); release.await(); stale }
        `when`(healthy.start(false)).thenReturn(healthy)
        val tasks = ArrayDeque<Runnable>()
        var built = 0
        val listener = BeatLinkListener(ContextWrapper(null), worker, Executor(tasks::addLast)) {
            if (built++ == 0) stale else healthy
        }
        var advertised = 0
        listener.discovery.register = { port, _ -> advertised = port }
        listener.ensureListening()
        val blocked = Thread(tasks.removeFirst()).apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.advance(20_000)
            listener.ensureListening()
            tasks.removeFirst().run()
            worker.drain()
            assertEquals(8080, advertised)
        } finally {
            release.countDown()
            blocked.join(2_000)
        }
        worker.drain()
        tasks.removeFirst().run()
        verify(stale).stop(0, 0)
        verify(healthy, never()).stop(0, 0)
    }

    @Test fun bindFailureCleansUpOffWorkerAndHealthyForegroundReusesServer() {
        val worker = TestStartWorker()
        val tasks = ArrayDeque<Runnable>()
        val first = mock(ApplicationEngine::class.java)
        val next = mock(ApplicationEngine::class.java)
        `when`(first.start(false)).thenAnswer { throw BindException() }
        `when`(next.start(false)).thenReturn(next)
        var builds = 0
        val listener = BeatLinkListener(ContextWrapper(null), worker, Executor(tasks::addLast)) {
            if (builds++ == 0) first else next
        }
        var ads = 0
        listener.discovery.register = { _, _ -> ads++ }
        repeat(3) { listener.ensureListening() }
        assertEquals(0, builds)
        assertEquals(0, ads)
        tasks.removeFirst().run()
        worker.drain()
        verify(first, never()).stop(0, 0)
        tasks.removeFirst().run()
        verify(first).stop(0, 0)
        listener.ensureListening()
        tasks.removeFirst().run()
        worker.drain()
        listener.ensureListening()
        assertEquals(2, builds)
        assertEquals(1, ads)
    }

    @Test fun discoveryRetriesRefusalOrMissingConfirmationAndIgnoresOldCallbacks() {
        for (timeout in listOf(false, true)) {
            val worker = TestStartWorker()
            val dns = DnsSdAnnouncer(ContextWrapper(null), worker)
            val calls = mutableListOf<NsdManager.RegistrationListener>()
            val removed = mutableListOf<NsdManager.RegistrationListener>()
            dns.register = { port, listener -> assertEquals(8080, port); calls += listener }
            dns.unregister = { removed += it }
            dns.ensure()
            val old = calls.single()
            if (timeout) worker.advance(20_000) else old.onRegistrationFailed(NsdServiceInfo(), 3)
            worker.drain()
            dns.ensure()
            assertEquals(2, calls.size)
            old.onServiceRegistered(NsdServiceInfo())
            calls.last().onServiceRegistered(NsdServiceInfo())
            worker.drain()
            worker.advance(60_000)
            dns.ensure()
            assertEquals(2, calls.size)
            assertEquals(if (timeout) listOf(old) else emptyList(), removed)
        }
    }
}
