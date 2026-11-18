package cx.aphex.energysign.ai

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A stand-in for the API on this device, for the warm-up: it listens on 127.0.0.1 and answers
 * every POST with the same JSON. Nothing leaves the device, and nothing but this app's own
 * warm-up talks to it: it lives for the seconds the warm-up takes and is closed after it.
 */
internal class LocalStandIn(private val answer: String) : AutoCloseable {
    private val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())

    val url: String = "http://127.0.0.1:${server.localPort}"

    private val thread = Thread({
        try {
            while (!server.isClosed) server.accept().use(::serve)
        } catch (e: IOException) {
            // Closed: the warm-up is over.
        }
    }, "ai-warm-up-stand-in").apply {
        isDaemon = true
        start()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        var bodyLength = 0
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", ignoreCase = true)) {
                bodyLength = line.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            }
        }
        repeat(bodyLength) { if (input.read() < 0) return }

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

    /** One header line without its line end, or null at the end of the stream. */
    private fun InputStream.readLine(): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE_CHARS) {
            val byte = read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        return null
    }

    override fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            // Nothing to do.
        }
        thread.interrupt()
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 10_000
        const val MAX_BODY_BYTES = 1_000_000
        const val MAX_LINE_CHARS = 8_192
    }
}
