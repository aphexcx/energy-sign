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
        val dns = DnsSdAnnouncer(ContextWrapper(null))
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
}
