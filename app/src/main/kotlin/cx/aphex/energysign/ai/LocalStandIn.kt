package cx.aphex.energysign.ai

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A stand-in for the API on this device, for the warm-up: it answers every request with the same
 * JSON. It lives for the seconds the warm-up takes and is closed after it.
 *
 * It listens on ONE address, [bindTo], and [url] is made from the socket that was really bound:
 * the address and the port in it are the ones this object holds, never ones that were only asked
 * for. (`InetAddress.getLoopbackAddress()` is `::1` on Android; a client told "127.0.0.1" would
 * then talk to whatever else listens on that port.)
 *
 * The client that talks to it carries a made-up key (see AnthropicReplyGenerator.warmUp), so
 * nothing that is worth anything reaches this socket, whoever connects to it.
 */
class LocalStandIn internal constructor(
    private val answer: String,
    /** The listening socket, bound. It is this object's from here on. */
    private val server: ServerSocket,
    /** Everything one request sent, head and body, as it came. For tests. */
    private val seen: (String) -> Unit,
    /** Starts the serving thread. Only tests set this. */
    startThread: (Thread) -> Unit,
) : AutoCloseable {
    constructor(answer: String, bindTo: InetAddress = IPV4_LOOPBACK, seen: (String) -> Unit = {}) :
        this(answer, ServerSocket(0, 2, bindTo), seen, Thread::start)

    /** The address the socket is bound to. */
    val address: InetAddress = server.inetAddress

    val url: String = "http://${if (address is Inet6Address) "[${address.hostAddress}]" else address.hostAddress}:${server.localPort}"

    /** The connection being served, so that [close] can end it too. */
    @Volatile
    private var active: Socket? = null

    private val thread = Thread({
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break // closed: the warm-up is over
            }
            // One connection that fails, or stalls until its time is up, ends that connection
            // only: the next one is served.
            try {
                socket.use {
                    active = it
                    if (!server.isClosed) serve(it)
                }
            } catch (e: IOException) {
                // This peer is done with.
            } finally {
                active = null
            }
        }
    }, "ai-warm-up-stand-in").apply { isDaemon = true }

    init {
        try {
            startThread(thread)
        } catch (e: Throwable) {
            // No thread, so nobody will ever serve or close this socket, and the caller gets no
            // object to close: it is closed here. The caller hears why there is no stand-in.
            try {
                server.close()
            } catch (closing: IOException) {
                // nothing
            }
            throw e
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val request = ByteArrayOutputStream()
        var bodyLength = 0
        // The head: a peer that keeps sending header lines is cut off at MAX_HEAD_BYTES.
        while (true) {
            val line = input.readLine(MAX_HEAD_BYTES - request.size()) ?: return
            request.write(line.toByteArray(Charsets.ISO_8859_1))
            request.write('\n'.code)
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) {
                bodyLength = line.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            }
        }
        repeat(bodyLength) {
            val byte = input.read()
            if (byte < 0) return
            request.write(byte)
        }
        seen(request.toString(Charsets.ISO_8859_1.name()))

        val body = answer.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: ${body.size}\r\n" +
                    "connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            write(body)
            flush()
        }
    }

    /** One header line without its line end; null at the end of the stream or when [room] is used up. */
    private fun InputStream.readLine(room: Int): String? {
        val line = StringBuilder()
        while (line.length < room) {
            val byte = read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        return null
    }

    /** Ends the listener and the connection that is being served, if there is one. */
    override fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            // Nothing to do.
        }
        try {
            active?.close()
        } catch (e: IOException) {
            // Nothing to do.
        }
        thread.interrupt()
    }

    companion object {
        /** 127.0.0.1, by its bytes: no name is looked up and no system setting chooses the family. */
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** How long one read of a connection may stall. A connection is served alone, so this is short. */
        private const val SOCKET_TIMEOUT_MS = 3_000
        private const val MAX_BODY_BYTES = 1_000_000

        /** All header lines of one request together. */
        private const val MAX_HEAD_BYTES = 16_384
    }
}
