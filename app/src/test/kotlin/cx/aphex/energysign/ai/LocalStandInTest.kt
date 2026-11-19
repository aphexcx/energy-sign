package cx.aphex.energysign.ai

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stand-in for the API on this device: where it listens, what it says where it listens, and its end (class A). */
class LocalStandInTest {

    private fun post(url: String, body: String = """{"hello":"there"}""", readTimeoutMs: Int = 3_000): String {
        val connection = URI("$url/v1/messages").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 3_000
        connection.readTimeout = readTimeoutMs
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `it listens on 127_0_0_1, given by its bytes, and that is the address in its URL`() {
        LocalStandIn("""{"ok":true}""").use { standIn ->
            assertEquals("127.0.0.1", standIn.address.hostAddress)
            assertTrue(standIn.url.startsWith("http://127.0.0.1:"), standIn.url)
            assertEquals("""{"ok":true}""", post(standIn.url))
        }
    }

    @Test
    fun `the URL is made from the socket that was bound - bound to the IPv6 loopback, it names the IPv6 loopback`() {
        val v6 = InetAddress.getByName("::1")
        val canBind = try {
            ServerSocket(0, 1, v6).close()
            true
        } catch (e: IOException) {
            false
        }
        assumeTrue(canBind, "this machine has no IPv6 loopback")

        LocalStandIn("""{"ok":true}""", bindTo = v6).use { standIn ->
            assertTrue(standIn.url.startsWith("http://[") && standIn.url.contains("]:"), standIn.url)
            assertEquals(v6, InetAddress.getByName(URI(standIn.url).host.trim('[', ']')))
            // What the URL says is where it really is: a client that is given the URL reaches it.
            assertEquals("""{"ok":true}""", post(standIn.url))
            // And it is NOT on the other loopback: a client told "127.0.0.1" would talk to somebody else, or to nobody.
            val port = URI(standIn.url).port
            val onV4 = try {
                Socket().use { it.connect(InetSocketAddress(LocalStandIn.IPV4_LOOPBACK, port), 500) }
                true
            } catch (e: IOException) {
                false
            }
            assertFalse(onV4, "the stand-in must listen on the one address it was bound to")
        }
    }

    @Test
    fun `it shows what a request sent, head and body`() {
        val seen = CopyOnWriteArrayList<String>()
        LocalStandIn("{}", seen = { seen += it }).use { standIn ->
            post(standIn.url, body = """{"model":"x"}""")
        }

        val request = seen.single()
        assertTrue(request.startsWith("POST /v1/messages"), request.take(40))
        assertTrue(request.endsWith("""{"model":"x"}"""), request.takeLast(40))
    }

    @Test
    fun `closing it ends a connection that is still sending its head`() {
        val standIn = LocalStandIn("{}")
        val peer = Socket()
        peer.connect(InetSocketAddress(standIn.address, URI(standIn.url).port), 2_000)
        peer.soTimeout = 3_000
        peer.getOutputStream().apply {
            write("POST /v1/messages HTTP/1.1\r\nx-slow: one\r\n".toByteArray())
            flush()
        }
        Thread.sleep(200)

        standIn.close()

        // The other end sees the connection end, at once: it is not kept until the peer stops sending.
        val startedAt = System.nanoTime()
        val read = try {
            peer.getInputStream().read()
        } catch (e: IOException) {
            -1
        }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        peer.close()
        assertEquals(-1, read)
        assertTrue(tookMs < 2_000, "the connection was still open $tookMs ms after the stand-in was closed")
    }

    @Test
    fun `a head that does not end is cut off, it is not read for ever`() {
        val seen = CopyOnWriteArrayList<String>()
        LocalStandIn("{}", seen = { seen += it }).use { standIn ->
            Socket().use { peer ->
                peer.connect(InetSocketAddress(standIn.address, URI(standIn.url).port), 2_000)
                peer.soTimeout = 5_000
                val out = peer.getOutputStream()
                out.write("POST /v1/messages HTTP/1.1\r\n".toByteArray())
                val line = ("x-filler: " + "a".repeat(500) + "\r\n").toByteArray()
                val startedAt = System.nanoTime()
                val ended = try {
                    // 100 kB of header lines: far more than a head may have.
                    repeat(200) { out.write(line) }
                    out.flush()
                    peer.getInputStream().read()
                } catch (e: java.net.SocketTimeoutException) {
                    Int.MAX_VALUE // nothing came and nothing ended: the stand-in is still reading
                } catch (e: IOException) {
                    -1
                }
                val tookMs = (System.nanoTime() - startedAt) / 1_000_000
                assertEquals(-1, ended, "the stand-in went on reading a head of 100 kB, or answered it")
                assertTrue(tookMs < 3_000, "it took $tookMs ms to drop a head that does not end")
            }
        }
        assertTrue(seen.isEmpty(), "a request that was cut off is not a request")
    }

    // ---- found by the review of round 3

    @Test
    fun `a connection that breaks does not end the stand-in - the next request is answered`() {
        LocalStandIn("""{"ok":true}""").use { standIn ->
            // Somebody else connects first, sends half a line and tears the connection down.
            Socket().use { stray ->
                stray.connect(InetSocketAddress(standIn.address, URI(standIn.url).port), 2_000)
                stray.setSoLinger(true, 0)
                stray.getOutputStream().apply {
                    write("GARBAGE".toByteArray())
                    flush()
                }
            }
            Thread.sleep(200)

            assertEquals("""{"ok":true}""", post(standIn.url))
        }
    }

    @Test
    fun `a connection that says nothing holds the stand-in up for seconds, not for good`() {
        LocalStandIn("""{"ok":true}""").use { standIn ->
            Socket().use { silent ->
                silent.connect(InetSocketAddress(standIn.address, URI(standIn.url).port), 2_000)
                Thread.sleep(100)

                val startedAt = System.nanoTime()
                val answer = post(standIn.url, readTimeoutMs = 10_000)
                val tookMs = (System.nanoTime() - startedAt) / 1_000_000

                assertEquals("""{"ok":true}""", answer)
                assertTrue(tookMs < 6_000, "the request behind a silent connection waited $tookMs ms")
            }
        }
    }

    // ---- round 3 of the gates

    @Test
    fun `a stand-in whose thread cannot be started gives its socket back`() {
        for (refusal in listOf<Throwable>(InternalError("unable to create native thread"), IllegalStateException("no"))) {
            val socket = java.net.ServerSocket(0, 2, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

            val thrown = runCatching { LocalStandIn("{}", socket, seen = {}, startThread = { throw refusal }) }.exceptionOrNull()

            assertTrue(thrown === refusal, "the caller hears why there is no stand-in: $thrown")
            assertTrue(socket.isClosed, "the listening socket was left open after $refusal")
        }
    }
}
