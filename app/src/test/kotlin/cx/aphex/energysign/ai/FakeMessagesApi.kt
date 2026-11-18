package cx.aphex.energysign.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A local stand-in for the Messages API, so the real SDK client can be driven over real HTTP
 * without ever reaching the live service. It records what it was sent and answers with whatever
 * the test queued.
 */
class FakeMessagesApi : AutoCloseable {

    class Received(val path: String, private val headers: okhttp3.Headers, val body: String) {
        val json: JsonObject get() = JsonParser.parseString(body).asJsonObject

        fun header(name: String): String? = headers.values(name).takeIf { it.isNotEmpty() }?.joinToString(",")

        /** The names of the headers whose value contains [text]. */
        fun headersContaining(text: String): List<String> =
            headers.names().filter { name -> headers.values(name).any { it.contains(text) } }.map { it.lowercase() }
    }

    class Answer(
        val status: Int,
        val body: String,
        val delayMs: Long = 0,
        val headers: Map<String, String> = emptyMap(),
    )

    val received: MutableList<Received> = CopyOnWriteArrayList()

    @Volatile
    var answer: Answer = Answer(500, error("api_error", "no answer queued"))

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.add(Received(request.path.orEmpty(), request.headers, request.body.readUtf8()))
                val reply = answer
                return MockResponse()
                    .setResponseCode(reply.status)
                    .setHeader("content-type", "application/json")
                    .apply { reply.headers.forEach { (name, value) -> setHeader(name, value) } }
                    .setBody(reply.body)
                    .setHeadersDelay(reply.delayMs, TimeUnit.MILLISECONDS)
            }
        }
        start()
    }

    val baseUrl: String = server.url("/").toString().trimEnd('/')

    override fun close() = server.shutdown()

    companion object {
        /** A complete Messages API response with the given content blocks and stop reason. */
        fun message(
            content: String,
            stopReason: String = "end_turn",
            model: String = "claude-fable-5-2",
            stopDetails: String = "null",
        ): String =
            """
            {"id":"msg_test","type":"message","role":"assistant","model":"$model",
             "content":$content,"stop_reason":"$stopReason","stop_sequence":null,
             "stop_details":$stopDetails,
             "usage":{"input_tokens":120,"output_tokens":30}}
            """.trimIndent()

        fun error(type: String, message: String): String =
            """{"type":"error","error":{"type":"$type","message":"$message"}}"""
    }
}
