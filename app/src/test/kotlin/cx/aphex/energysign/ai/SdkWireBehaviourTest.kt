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
 * Establishes and asserts what ONE call puts on the wire for stock Anthropic SDK (3.3.0),
 * one-shot Anthropic (`repeatable() = false`), stock OpenAI on Ktor-OkHttp, and one-shot OpenAI.
 */
class SdkWireBehaviourTest {

    private fun oneShotAnthropicBody(body: HttpRequestBody) = object : HttpRequestBody {
        override fun writeTo(outputStream: OutputStream) = body.writeTo(outputStream)
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun repeatable() = false
        override fun close() = body.close()
    }

    private fun oneShotOkHttpBody(body: RequestBody) = object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }

    private fun callAnthropic(baseUrl: String, oneShot: Boolean, onIntercept: () -> Unit) {
        val timeout = Timeout.builder().connect(Duration.ofSeconds(5)).request(Duration.ofSeconds(5)).build()
        val client = AnthropicOkHttpClient.builder()
            .apiKey("test-key-not-real").jsonMapper(jsonMapper()).maxRetries(AnthropicReplyGenerator.MAX_RETRIES)
            .logLevel(LogLevel.OFF).timeout(timeout).baseUrl(baseUrl)
            .addInterceptor(Interceptor.syncOnly { next, request, options ->
                onIntercept()
                val body = if (oneShot) request.body().orElse(null) else null
                next.execute(if (body != null) request.toBuilder().body(oneShotAnthropicBody(body)).build() else request, options)
            }).build()
        val params = MessageCreateParams.builder()
            .model(AnthropicReplyGenerator.MODEL).maxTokens(AnthropicReplyGenerator.MAX_TOKENS)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.HIGH).build())
            .addBeta(AnthropicReplyGenerator.FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault())
            .system("SYSTEM").addUserMessage("hello").build()
        val opts = RequestOptions.builder().timeout(timeout).followRedirects(false).build()
        try {
            runCatching { client.beta().messages().withRawResponse().create(params, opts).use { it.parse() } }
        } finally {
            client.close()
        }
    }

    private fun callOpenAi(baseUrl: String, oneShot: Boolean) {
        val client = OpenAI(OpenAIConfig(
            token = "test-key-not-real",
            logging = LoggingConfig(logLevel = OpenAILogLevel.None),
            host = OpenAIHost(baseUrl = "$baseUrl/v1/"),
            retry = RetryStrategy(maxRetries = 0),
            httpClientConfig = {
                if (oneShot) engine {
                    val okHttp = this as OkHttpConfig
                    okHttp.config { retryOnConnectionFailure(false) }
                    okHttp.addInterceptor { chain ->
                        val req = chain.request()
                        val body = req.body
                        chain.proceed(if (body != null) req.newBuilder().method(req.method, oneShotOkHttpBody(body)).build() else req)
                    }
                }
            },
        ))
        runCatching {
            runBlocking { client.chatCompletions(ChatCompletionRequest(ModelId("gpt-5.1"), listOf(ChatMessage(ChatRole.User, "hi")))).collect {} }
        }
    }

    private fun withRawSocketServer(sendRst: Boolean, block: (String) -> Unit): Int {
        val wireCount = AtomicInteger()
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val accept = Thread {
                while (!server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    wireCount.incrementAndGet()
                    runCatching {
                        sock.soTimeout = 1_000
                        sock.getInputStream().read(ByteArray(4096))
                        if (sendRst) sock.setSoLinger(true, 0)
                    }
                    runCatching { sock.close() }
                }
            }.apply { isDaemon = true; start() }
            try { block("http://127.0.0.1:${server.localPort}") } finally { server.close(); accept.join(2_000) }
        }
        return wireCount.get()
    }

    @Test
    fun `what one call puts on the wire for stock Anthropic, one-shot Anthropic, stock OpenAI, and one-shot OpenAI`() {
        val okAnthropic = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
        val err503A = FakeMessagesApi.Answer(503, FakeMessagesApi.error("api_error", "busy"), headers = mapOf("retry-after" to "0"))
        val okOpenAi = FakeMessagesApi.Answer(
            200,
            """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n",
            headers = mapOf("content-type" to "text/event-stream"),
        )
        val err503O = FakeMessagesApi.Answer(503, """{"error":{"message":"busy","type":"server_error"}}""", headers = mapOf("retry-after" to "0"))
        val err408O = FakeMessagesApi.Answer(408, """{"error":{"message":"timeout","type":"server_error"}}""")

        data class Row(val name: String, val openAi: Boolean, val oneShot: Boolean, val first: FakeMessagesApi.Answer?, val rest: FakeMessagesApi.Answer, val expectedWire: Int)

        val rows = listOf(
            Row("Anthropic stock 503 Retry-After:0 -> 200", false, false, err503A, okAnthropic, 2),
            Row("Anthropic stock 503 Retry-After:0 always", false, false, null, err503A, 2),
            Row("Anthropic stock 429 Retry-After:0", false, false, null, FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "0")), 1),
            Row("Anthropic stock 429 Retry-After:120", false, false, null, FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "120")), 1),
            Row("Anthropic stock 408 -> 200", false, false, FakeMessagesApi.Answer(408, FakeMessagesApi.error("timeout_error", "timeout")), okAnthropic, 1),
            Row("Anthropic one-shot 503 Retry-After:0 -> 200", false, true, err503A, okAnthropic, 1),
            Row("Anthropic one-shot 503 Retry-After:0 always", false, true, null, err503A, 1),
            Row("OpenAI stock 503 Retry-After:0 -> 200", true, false, err503O, okOpenAi, 2),
            Row("OpenAI stock 429 Retry-After:0", true, false, null, FakeMessagesApi.Answer(429, """{"error":{"message":"slow","type":"rate_limit_error"}}""", headers = mapOf("retry-after" to "0")), 1),
            Row("OpenAI stock 408 -> 200", true, false, err408O, okOpenAi, 2),
            Row("OpenAI one-shot 503 Retry-After:0 -> 200", true, true, err503O, okOpenAi, 1),
            Row("OpenAI one-shot 408 -> 200", true, true, err408O, okOpenAi, 1),
        )
        for (r in rows) {
            FakeMessagesApi().use { api ->
                r.first?.let(api.firstAnswers::add)
                api.answer = r.rest
                if (!r.openAi) {
                    val intercepts = AtomicInteger()
                    callAnthropic(api.baseUrl, r.oneShot) { intercepts.incrementAndGet() }
                    assertEquals(r.expectedWire, api.received.size, "${r.name}: wire count")
                    assertEquals(1, intercepts.get(), "${r.name}: SDK addInterceptor runs once")
                } else {
                    callOpenAi(api.baseUrl, r.oneShot)
                    assertEquals(r.expectedWire, api.received.size, "${r.name}: wire count")
                }
            }
        }
        for ((label, openAi, sendRst) in listOf(
            Triple("Anthropic stock TCP RST", false, true),
            Triple("Anthropic stock EOF", false, false),
            Triple("OpenAI stock TCP RST", true, true),
        )) {
            val intercepts = AtomicInteger()
            val wire = withRawSocketServer(sendRst) { url ->
                if (!openAi) callAnthropic(url, oneShot = false) { intercepts.incrementAndGet() } else callOpenAi(url, oneShot = false)
            }
            assertEquals(1, wire, "$label: wire count")
            if (!openAi) assertEquals(1, intercepts.get(), "$label: SDK addInterceptor count")
        }
    }
}
