package cx.aphex.energysign.beatlinkdata

import android.content.ContextWrapper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class DnsSdRecoveryTest {
    @Test
    fun foregroundRetriesFailedRegistrationAndKeepsConfirmedRegistration() {
        val dns = DnsSdAnnouncer(ContextWrapper(null), java.util.concurrent.Executor { it.run() })
        val calls = mutableListOf<NsdManager.RegistrationListener>()
        dns.submit = { calls += it }
        dns.start()
        calls.single().onRegistrationFailed(NsdServiceInfo(), 3)
        dns.start()
        assertEquals(2, calls.size)
        assertNotSame(calls.first(), calls.last())
        calls.last().onServiceRegistered(NsdServiceInfo())
        dns.start()
        assertEquals(2, calls.size)
    }

    @Test
    fun absentCallbackReleasesRegistrationAndOldSuccessCannotClaimReplacement() {
        var expire: (() -> Unit)? = null
        val dns = DnsSdAnnouncer(ContextWrapper(null), java.util.concurrent.Executor { it.run() }) { action ->
            expire = action
            { expire = null }
        }
        val calls = mutableListOf<NsdManager.RegistrationListener>()
        val releases = mutableListOf<NsdManager.RegistrationListener>()
        dns.submit = { calls.add(it) }
        dns.unregister = { releases.add(it) }
        dns.start()
        val old = calls.single()
        expire!!.invoke()
        assertEquals(listOf(old), releases)
        dns.start()
        old.onServiceRegistered(NsdServiceInfo())
        expire!!.invoke()
        dns.start()
        calls.last().onServiceRegistered(NsdServiceInfo())
        dns.start()
        assertEquals(3, calls.size)
        dns.stop()
        assertEquals(calls, releases)
    }
}
