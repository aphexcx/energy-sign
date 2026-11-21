package cx.aphex.energysign.uart

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * The line to a sign that is a program: the firmware emulator gives the sign's serial link as a
 * TCP port, carrying exactly the bytes the UART carries. This is the app's end of it, for a
 * device without the board's UART (the Android emulator, where the computer's own localhost is
 * 10.0.2.2). On the board nothing makes one.
 *
 * It is a [SignWire] like the UART: no call waits, and all of them are the link thread's. A
 * peer that is not there is a sign that says nothing: a read finds 0 bytes and a write takes 0
 * (so the message stays the app's), and the line is looked for again every [RETRY_MS], so the
 * firmware emulator can be started after the app, and started again. Nothing here opens a
 * connection before the first read: the main thread, which makes this object, must not.
 */
class SocketWire(
    private val host: String,
    private val port: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    /** The line's changes: found, not there, gone. Each said once. */
    private val log: (line: String) -> Unit = {},
) : SignWire {
    private var channel: SocketChannel? = null
    private var connected = false
    private var closed = false
    private var nextTryNs = nanoTime()

    /** Whether a look for the line is under way: it was begun, and has neither found the line nor failed yet. */
    internal val looking: Boolean get() = channel != null && !connected

    /** What the last change was, so that a peer that stays away is said once. */
    private var said = ""

    override fun read(buffer: ByteArray, length: Int): Int = onTheLine { it.read(ByteBuffer.wrap(buffer, 0, length)) }

    override fun write(buffer: ByteArray, length: Int): Int = onTheLine { it.write(ByteBuffer.wrap(buffer, 0, length)) }

    override fun close() {
        closed = true
        drop()
    }

    /** [io] on the connected line. 0 when there is none; the end of the line (-1) and a failure drop it. */
    private fun onTheLine(io: (SocketChannel) -> Int): Int {
        try {
            val line = line() ?: return 0
            val count = io(line)
            if (count >= 0) return count
            lost("gone", "it hung up")
        } catch (e: Exception) {
            lost(if (connected) "gone" else "not there", e.toString())
        }
        return 0
    }

    /** The connected line. Without one: a look for it when it is time, and null until it is found. */
    private fun line(): SocketChannel? {
        if (closed) return null
        val open = channel ?: run {
            if (nanoTime() - nextTryNs < 0) return null
            SocketChannel.open().also {
                channel = it
                it.configureBlocking(false)
                it.socket().tcpNoDelay = true
                it.connect(InetSocketAddress(host, port))
            }
        }
        if (!connected && open.finishConnect()) {
            connected = true
            say("connected")
        }
        return open.takeIf { connected }
    }

    private fun lost(what: String, why: String) {
        drop()
        nextTryNs = nanoTime() + RETRY_MS * NS_PER_MS
        if (said != "connected" && said.isNotEmpty()) return
        say("$what ($why); looking for it every $RETRY_MS ms")
    }

    private fun say(what: String) {
        said = what.substringBefore(' ')
        log("Sign emulator at $host:$port: $what")
    }

    private fun drop() {
        connected = false
        try {
            channel?.close()
        } catch (e: Exception) {
            // It is gone either way
        }
        channel = null
    }

    companion object {
        /** How long after a line was lost, or not found, it is looked for again. */
        const val RETRY_MS: Int = 500

        /**
         * "a.b.c.d:port" as its two parts; null for anything else (nothing set, no port, a port that
         * is none, a host name). Only an IPv4 address: [InetSocketAddress] would look a name up,
         * and that is on the link thread, at every try.
         */
        fun addressOf(text: String): Pair<String, Int>? {
            val host = text.substringBeforeLast(':', "").trim()
            val port = text.substringAfterLast(':').trim().toIntOrNull()
            val ipv4 = host.split('.').let { parts ->
                parts.size == 4 && parts.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } && it.toInt() <= 255 }
            }
            return if (!ipv4 || port == null || port !in 1..65535) null else host to port
        }
        private const val NS_PER_MS: Long = 1_000_000L
    }
}
