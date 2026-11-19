package cx.aphex.energysign.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.Interceptor
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Establishes and asserts how `com.anthropic:anthropic-java:3.3.0` (`maxRetries(0)`,
 * `followRedirects(false)`) and OpenAI's Ktor-OkHttp client behave on the wire vs. inside the
 * Anthropic SDK's `addInterceptor` hook for 503 `Retry-After: 0`, 429, 408, TCP RST, and EOF.
 */
class SdkWireBehaviourTest {

    private fun buildAnthropicClient(baseUrl: String, onIntercept: () -> Unit): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .apiKey("test-key-not-real")
            .jsonMapper(jsonMapper())
            .maxRetries(AnthropicReplyGenerator.MAX_RETRIES)
            .logLevel(LogLevel.OFF)
            .timeout(Timeout.builder().connect(Duration.ofSeconds(5)).request(Duration.ofSeconds(5)).build())
            .baseUrl(baseUrl)
            .addInterceptor(Interceptor.syncOnly { next, request, options ->
                onIntercept()
                next.execute(request, options)
            })
            .build()

    private fun params(): MessageCreateParams =
        MessageCreateParams.builder()
            .model(AnthropicReplyGenerator.MODEL)
            .maxTokens(AnthropicReplyGenerator.MAX_TOKENS)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.HIGH).build())
            .addBeta(AnthropicReplyGenerator.FALLBACK_BETA)
            .fallbacks(BetaFallbacksParam.ofDefault())
            .system("SYSTEM")
            .addUserMessage("hello")
            .build()

    private fun options(): RequestOptions =
        RequestOptions.builder()
            .timeout(Timeout.builder().connect(Duration.ofSeconds(5)).request(Duration.ofSeconds(5)).build())
            .followRedirects(false)
            .build()

    private fun withRawSocketServer(sendRst: Boolean, block: (baseUrl: String) -> Unit): Int {
        val wireCount = AtomicInteger()
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val accept = Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    wireCount.incrementAndGet()
                    try {
                        socket.soTimeout = 1_000
                        socket.getInputStream().read(ByteArray(4096))
                        if (sendRst) socket.setSoLinger(true, 0)
                    } catch (_: Exception) {
                        // ignore
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                block("http://127.0.0.1:${server.localPort}")
            } finally {
                server.close()
                accept.join(2_000)
            }
        }
        return wireCount.get()
    }

    @Test
    fun `anthropic sdk wire count vs sdk addInterceptor on 503 Retry-After 0, 429, 408, RST, and EOF`() {
        val ok200 = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
        val err503 = FakeMessagesApi.Answer(503, FakeMessagesApi.error("api_error", "busy"), headers = mapOf("retry-after" to "0"))
        data class Case(val name: String, val first: FakeMessagesApi.Answer?, val rest: FakeMessagesApi.Answer, val expectedWire: Int)

        val httpCases = listOf(
            Case("503 Retry-After:0 -> 200", err503, ok200, expectedWire = 2),
            Case("503 Retry-After:0 always", null, err503, expectedWire = 2),
            Case("429 Retry-After:0", null, FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "0")), expectedWire = 1),
            Case("429 Retry-After:120", null, FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "120")), expectedWire = 1),
            Case("408 Request Timeout", FakeMessagesApi.Answer(408, FakeMessagesApi.error("timeout_error", "timeout")), ok200, expectedWire = 1),
        )
        for (c in httpCases) {
            val intercepts = AtomicInteger()
            FakeMessagesApi().use { api ->
                c.first?.let(api.firstAnswers::add)
                api.answer = c.rest
                val client = buildAnthropicClient(api.baseUrl) { intercepts.incrementAndGet() }
                try {
                    runCatching { client.beta().messages().withRawResponse().create(params(), options()).use { it.parse() } }
                } finally {
                    client.close()
                }
                assertEquals(c.expectedWire, api.received.size, "${c.name}: wire count")
                assertEquals(1, intercepts.get(), "${c.name}: SDK addInterceptor runs once above OkHttp replay")
            }
        }

        for ((label, rst) in listOf("TCP RST before status" to true, "EOF before status" to false)) {
            val intercepts = AtomicInteger()
            val wire = withRawSocketServer(sendRst = rst) { url ->
                val client = buildAnthropicClient(url) { intercepts.incrementAndGet() }
                try {
                    runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
                } finally {
                    client.close()
                }
            }
            assertEquals(1, wire, "$label: wire count")
            assertEquals(1, intercepts.get(), "$label: SDK addInterceptor count")
        }
    }

    @Test
    fun `openai ktor okhttp wire count on 503 Retry-After 0, 429, 408, and TCP RST`() {
        val okSse = FakeMessagesApi.Answer(
            200,
            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""" + "\n\n" +
                """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
                "data: [DONE]\n\n",
            headers = mapOf("content-type" to "text/event-stream"),
        )
        data class Case(val name: String, val first: FakeMessagesApi.Answer?, val rest: FakeMessagesApi.Answer, val expectedWire: Int)

        val httpCases = listOf(
            Case("503 Retry-After:0 -> 200", FakeMessagesApi.Answer(503, """{"error":{"message":"busy","type":"server_error"}}""", headers = mapOf("retry-after" to "0")), okSse, expectedWire = 2),
            Case("429 Retry-After:0", null, FakeMessagesApi.Answer(429, """{"error":{"message":"slow","type":"rate_limit_error"}}""", headers = mapOf("retry-after" to "0")), expectedWire = 1),
            Case("408 Request Timeout -> 200", FakeMessagesApi.Answer(408, """{"error":{"message":"timeout","type":"server_error"}}"""), okSse, expectedWire = 2),
        )
        for (c in httpCases) {
            FakeMessagesApi().use { api ->
                c.first?.let(api.firstAnswers::add)
                api.answer = c.rest
                val gen = OpenAIReplyGenerator(apiKey = "test-key-not-real", log = {}, baseUrl = "${api.baseUrl}/v1/")
                runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
                assertEquals(c.expectedWire, api.received.size, "OpenAI ${c.name}: wire count")
            }
        }

        val wireRst = withRawSocketServer(sendRst = true) { url ->
            val gen = OpenAIReplyGenerator(apiKey = "test-key-not-real", log = {}, baseUrl = "$url/v1/")
            runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
        }
        assertEquals(1, wireRst, "OpenAI TCP RST before status: wire count")
    }
}
