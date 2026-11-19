package cx.aphex.energysign.ai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.logging.LogLevel as OpenAILogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.core.http.Interceptor
import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Establishes and asserts what ONE call puts on the wire for:
 * - stock `com.anthropic:anthropic-java:3.3.0` (`maxRetries(0)`, `followRedirects(false)`),
 * - the same with the request body marked one-shot (`repeatable() = false`),
 * - stock OpenAI on Ktor-OkHttp (`RetryStrategy(maxRetries = 0)`), and
 * - the same with `retryOnConnectionFailure(false)` and a one-shot request body.
 */
class SdkWireBehaviourTest {

    private fun oneShotAnthropicBody(body: HttpRequestBody): HttpRequestBody = object : HttpRequestBody {
        override fun writeTo(outputStream: OutputStream) = body.writeTo(outputStream)
        override fun contentType(): String? = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun repeatable(): Boolean = false
        override fun close() = body.close()
    }

    private fun oneShotOkHttpBody(body: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }

    private fun anthropicClient(baseUrl: String, oneShot: Boolean, onIntercept: () -> Unit): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .apiKey("test-key-not-real")
            .jsonMapper(jsonMapper())
            .maxRetries(AnthropicReplyGenerator.MAX_RETRIES)
            .logLevel(LogLevel.OFF)
            .timeout(Timeout.builder().connect(Duration.ofSeconds(5)).request(Duration.ofSeconds(5)).build())
            .baseUrl(baseUrl)
            .addInterceptor(Interceptor.syncOnly { next, request, options ->
                onIntercept()
                val outgoing = if (oneShot) {
                    val body = request.body().orElse(null)
                    if (body != null) request.toBuilder().body(oneShotAnthropicBody(body)).build() else request
                } else {
                    request
                }
                next.execute(outgoing, options)
            })
            .build()

    private fun openAiClient(baseUrl: String, oneShot: Boolean): OpenAI = OpenAI(
        OpenAIConfig(
            token = "test-key-not-real",
            logging = LoggingConfig(logLevel = OpenAILogLevel.None),
            host = OpenAIHost(baseUrl = "$baseUrl/v1/"),
            retry = RetryStrategy(maxRetries = 0),
            httpClientConfig = {
                if (oneShot) {
                    engine {
                        val okHttp = this as OkHttpConfig
                        okHttp.config { retryOnConnectionFailure(false) }
                        okHttp.addInterceptor { chain ->
                            val req = chain.request()
                            val body = req.body
                            val outgoing = if (body != null) req.newBuilder().method(req.method, oneShotOkHttpBody(body)).build() else req
                            chain.proceed(outgoing)
                        }
                    }
                }
            },
        )
    )

    private fun anthropicParams(): MessageCreateParams =
        MessageCreateParams.builder()
            .model(AnthropicReplyGenerator.MODEL)
            .maxTokens(AnthropicReplyGenerator.MAX_TOKENS)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.HIGH).build())
            .addBeta(AnthropicReplyGenerator.FALLBACK_BETA)
            .fallbacks(BetaFallbacksParam.ofDefault())
            .system("SYSTEM")
            .addUserMessage("hello")
            .build()

    private fun anthropicOptions(): RequestOptions =
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
                    runCatching {
                        socket.soTimeout = 1_000
                        socket.getInputStream().read(ByteArray(4096))
                        if (sendRst) socket.setSoLinger(true, 0)
                    }
                    runCatching { socket.close() }
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
    fun `what one call puts on the wire for stock Anthropic, one-shot Anthropic, stock OpenAI, and one-shot OpenAI`() {
        val okAnthropic = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
        val err503 = FakeMessagesApi.Answer(503, FakeMessagesApi.error("api_error", "busy"), headers = mapOf("retry-after" to "0"))
        val okOpenAi = FakeMessagesApi.Answer(
            200,
            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""" + "\n\n" +
                """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
                "data: [DONE]\n\n",
            headers = mapOf("content-type" to "text/event-stream"),
        )
        data class Row(
            val name: String,
            val openAi: Boolean,
            val oneShot: Boolean,
            val first: FakeMessagesApi.Answer?,
            val rest: FakeMessagesApi.Answer,
            val expectedWire: Int,
        )

        val rows = listOf(
            // Stock Anthropic SDK (maxRetries=0, followRedirects=false)
            Row("Anthropic stock 503 Retry-After:0 -> 200", false, false, err503, okAnthropic, expectedWire = 2),
            Row("Anthropic stock 503 Retry-After:0 always", false, false, null, err503, expectedWire = 2),
            Row("Anthropic stock 429 Retry-After:0", false, false, null, FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "0")), expectedWire = 1),
            Row("Anthropic stock 429 Retry-After:120", false, false, null, FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "120")), expectedWire = 1),
            Row("Anthropic stock 408 -> 200", false, false, FakeMessagesApi.Answer(408, FakeMessagesApi.error("timeout_error", "timeout")), okAnthropic, expectedWire = 1),
            // Anthropic SDK with one-shot body (repeatable=false -> OkHttp isOneShot=true)
            Row("Anthropic one-shot 503 Retry-After:0 -> 200", false, true, err503, okAnthropic, expectedWire = 1),
            Row("Anthropic one-shot 503 Retry-After:0 always", false, true, null, err503, expectedWire = 1),
            // Stock OpenAI on Ktor-OkHttp (RetryStrategy maxRetries=0)
            Row("OpenAI stock 503 Retry-After:0 -> 200", true, false, FakeMessagesApi.Answer(503, """{"error":{"message":"busy","type":"server_error"}}""", headers = mapOf("retry-after" to "0")), okOpenAi, expectedWire = 2),
            Row("OpenAI stock 429 Retry-After:0", true, false, null, FakeMessagesApi.Answer(429, """{"error":{"message":"slow","type":"rate_limit_error"}}""", headers = mapOf("retry-after" to "0")), expectedWire = 1),
            Row("OpenAI stock 408 -> 200", true, false, FakeMessagesApi.Answer(408, """{"error":{"message":"timeout","type":"server_error"}}"""), okOpenAi, expectedWire = 2),
            // OpenAI with retryOnConnectionFailure(false) + one-shot RequestBody
            Row("OpenAI one-shot 503 Retry-After:0 -> 200", true, true, FakeMessagesApi.Answer(503, """{"error":{"message":"busy","type":"server_error"}}""", headers = mapOf("retry-after" to "0")), okOpenAi, expectedWire = 1),
            Row("OpenAI one-shot 408 -> 200", true, true, FakeMessagesApi.Answer(408, """{"error":{"message":"timeout","type":"server_error"}}"""), okOpenAi, expectedWire = 1),
        )

        for (r in rows) {
            FakeMessagesApi().use { api ->
                r.first?.let(api.firstAnswers::add)
                api.answer = r.rest
                if (!r.openAi) {
                    val intercepts = AtomicInteger()
                    val client = anthropicClient(api.baseUrl, r.oneShot) { intercepts.incrementAndGet() }
                    try {
                        runCatching { client.beta().messages().withRawResponse().create(anthropicParams(), anthropicOptions()).use { it.parse() } }
                    } finally {
                        client.close()
                    }
                    assertEquals(r.expectedWire, api.received.size, "${r.name}: wire count")
                    assertEquals(1, intercepts.get(), "${r.name}: SDK addInterceptor runs once")
                } else {
                    val client = openAiClient(api.baseUrl, r.oneShot)
                    runCatching {
                        runBlocking {
                            client.chatCompletions(
                                ChatCompletionRequest(ModelId("gpt-5.1"), listOf(ChatMessage(ChatRole.User, "hi")))
                            ).collect {}
                        }
                    }
                    assertEquals(r.expectedWire, api.received.size, "${r.name}: wire count")
                }
            }
        }

        // Raw socket faults (TCP RST and clean EOF before status line): 1 wire request each.
        for ((label, openAi, sendRst) in listOf(
            Triple("Anthropic stock TCP RST", false, true),
            Triple("Anthropic stock EOF", false, false),
            Triple("OpenAI stock TCP RST", true, true),
        )) {
            val intercepts = AtomicInteger()
            val wire = withRawSocketServer(sendRst = sendRst) { url ->
                if (!openAi) {
                    val client = anthropicClient(url, oneShot = false) { intercepts.incrementAndGet() }
                    try {
                        runCatching { client.beta().messages().withRawResponse().create(anthropicParams(), anthropicOptions()).close() }
                    } finally {
                        client.close()
                    }
                } else {
                    val client = openAiClient(url, oneShot = false)
                    runCatching {
                        runBlocking {
                            client.chatCompletions(
                                ChatCompletionRequest(ModelId("gpt-5.1"), listOf(ChatMessage(ChatRole.User, "hi")))
                            ).collect {}
                        }
                    }
                }
            }
            assertEquals(1, wire, "$label: wire count")
            if (!openAi) assertEquals(1, intercepts.get(), "$label: SDK addInterceptor count")
        }
    }
}
