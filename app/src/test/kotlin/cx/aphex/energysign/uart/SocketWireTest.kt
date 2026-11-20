package cx.aphex.energysign.uart

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertEquals

/**
 * The sign's line as a socket, against a real peer on this computer (what the firmware emulator
 * is to the app in the Android emulator). The clock is a fake one: only `+500ms` moves it.
 *
 * ONE TABLE. A row is a script and what it gives, both read left to right. The steps:
 *  - of the peer: `listen` (open the port), `unlisten`, `accept` (waits for a connection: gives
 *    `accepted`), `accept-not` (looks for one briefly: gives `nobody`), `send:<text>`, `got`
 *    (what reached it), `hangup`, `ended` (waits for the line to end: gives `ended`)
 *  - of the wire: `read` (one read: gives the text, or `0`), `read!` (reads until there is
 *    something), `looked` (reads, which must all give nothing, until a look for the line that
 *    is under way has ended: gives `0`), `write:<text>` (gives how many bytes were taken), `break`, `close`, and `until:<word>`
 *    (reads, which must all give nothing, until the wire has logged that word)
 * What the wire logs is in the result too, in brackets, where it was logged.
 */
class SocketWireTest {
    private class Row(val what: String, val script: String, val gives: String)

    private val table = listOf(
        Row(
            "connect, a request, an answer",
            "listen until:connected accept send:~ read! write:{\"type\":\"F\"} got",
            "(connected) accepted ~ 12 {\"type\":\"F\"}",
        ),
        Row(
            "more than one read holds: the rest stays for the next read",
            "listen until:connected accept send:InvalidInput~ read! read!",
            "(connected) accepted InvalidI nput~",
        ),
        Row(
            "nobody there: nothing is read, nothing is taken, and the next try is 500 ms later",
            "until:not-there write:{} listen read accept-not +500ms until:connected accept send:~ read!",
            "(not-there) 0 0 nobody (connected) accepted ~",
        ),
        Row(
            "the peer gone and back: the line is found again",
            "listen until:connected accept send:~ read! hangup unlisten until:gone write:{} " +
                "listen +500ms until:connected accept send:? read!",
            "(connected) accepted ~ (gone) 0 (connected) accepted ?",
        ),
        Row(
            "a peer that stays away is tried again every 500 ms, and said once",
            "listen until:connected accept hangup unlisten until:gone read +500ms looked read +500ms looked read " +
                "listen +500ms until:connected accept",
            "(connected) accepted (gone) 0 0 0 0 0 (connected) accepted",
        ),
        Row(
            "a break is not bytes: asking for one fails, and the line is as it was",
            "listen until:connected accept break send:~ read! write:{} got",
            "(connected) accepted no-break ~ 2 {}",
        ),
        Row(
            "close ends the line for the peer, and for good",
            "listen until:connected accept close ended +500ms read write:{} accept-not",
            "(connected) accepted ended 0 0 nobody",
        ),
    )

    @Test
    fun `the socket is a line to the sign - connect, a request, an answer, the peer gone and back`() {
        val wrong = table.mapNotNull { row ->
            val gave = try {
                Bench().use { it.run(row.script) }
            } catch (e: Exception) {
                "threw $e"
            }
            if (gave == row.gives) null else "${row.what}\n  script   ${row.script}\n  expected ${row.gives}\n  gave     $gave"
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
    }

    @Test
    fun `where the firmware emulator is, is an IPv4 address and a port, or it is not said`() {
        val rows = listOf(
            "10.0.2.2:14400" to ("10.0.2.2" to 14400),
            " 127.0.0.1:1 " to ("127.0.0.1" to 1),
            "255.255.255.255:65535" to ("255.255.255.255" to 65535),
            // a name would be looked up on the link thread
            "localhost:1" to null,
            "sign.local:14400" to null,
            "[::1]:65535" to null,
            "10.0.2:14400" to null,
            "10.0.2.256:14400" to null,
            "10.0.2.+2:14400" to null,
            "" to null,
            "10.0.2.2" to null,
            "10.0.2.2:" to null,
            ":14400" to null,
            "10.0.2.2:0" to null,
            "10.0.2.2:65536" to null,
            "10.0.2.2:port" to null,
        )
        assertEquals(rows, rows.map { (text, _) -> text to SocketWire.addressOf(text) })
    }

    private val benches = mutableListOf<Bench>()

    @AfterEach
    fun closeAll() = benches.forEach { it.close() }

    private inner class Bench : AutoCloseable {
        private val local = InetAddress.getLoopbackAddress()
        private var now = 0L
        private val gave = mutableListOf<String>()

        // The peer keeps one port number for the whole row, as the firmware emulator does when it is started again
        private var server: ServerSocket? = ServerSocket(0, 1, local)
        private val port = server!!.localPort.also { unlisten() }
        private var peer: Socket? = null

        private val wire = SocketWire(local.hostAddress!!, port, nanoTime = { now }) { line ->
            gave += "(" + line.substringAfter(": ").substringBefore(" (").replace(' ', '-') + ")"
        }
        private val buffer = ByteArray(8)

        init {
            benches += this
        }

        fun run(script: String): String {
            script.split(' ').forEach { step ->
                val text = step.substringAfter(':', "")
                when (step.substringBefore(':')) {
                    "listen" -> server = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(local, port), 1)
                    }
                    "unlisten" -> unlisten()
                    "accept" -> gave += accept(PATIENCE_MS)
                    "accept-not" -> gave += accept(300)
                    "send" -> peer!!.getOutputStream().apply { write(text.toByteArray()); flush() }
                    "got" -> gave += ByteArray(64).let { String(it, 0, peer!!.getInputStream().read(it)) }
                    "hangup" -> peer!!.close()
                    "ended" -> gave += if (peer!!.getInputStream().read() < 0) "ended" else "more"
                    "read" -> gave += read()
                    "read!" -> gave += within { read().takeIf { it != "0" } } ?: "nothing came"
                    // (a look at a port nobody listens on ends when its refusal comes, which may be a read or two later)
                    "looked" -> gave += within { read().takeIf { it != "0" || !wire.looking } } ?: "still looking"
                    "write" -> gave += text.toByteArray().let { wire.write(it, it.size) }.toString()
                    "close" -> wire.close()
                    "break" -> gave += try {
                        wire.sendBreak(50)
                        "a break"
                    } catch (e: java.io.IOException) {
                        "no-break"
                    }
                    "until" -> within {
                        val before = gave.size
                        val read = read()
                        when {
                            gave.size > before -> Unit.takeIf { gave.last() == "($text)" }
                            read != "0" -> Unit.also { gave += "read $read" }
                            else -> null
                        }
                    } ?: run { gave += "never $text" }
                    "+500ms" -> now += 500_000_000L
                    else -> error("no such step: $step")
                }
            }
            return gave.joinToString(" ")
        }

        private fun read(): String = wire.read(buffer, buffer.size).let { if (it == 0) "0" else String(buffer, 0, it) }

        private fun accept(waitMs: Int): String =
            try {
                peer = server!!.apply { soTimeout = waitMs }.accept().apply { soTimeout = PATIENCE_MS }
                "accepted"
            } catch (e: SocketTimeoutException) {
                "nobody"
            }

        private fun unlisten() {
            server?.close()
            server = null
        }

        /** Tries until there is a result, for as long as a loaded computer may need. */
        private fun <T : Any> within(attempt: () -> T?): T? {
            val until = System.nanoTime() + PATIENCE_MS * 1_000_000L
            while (System.nanoTime() < until) {
                attempt()?.let { return it }
                Thread.sleep(2)
            }
            return null
        }

        override fun close() {
            runCatching { wire.close() }
            runCatching { peer?.close() }
            unlisten()
        }
    }

    private companion object {
        const val PATIENCE_MS = 10_000
    }
}
