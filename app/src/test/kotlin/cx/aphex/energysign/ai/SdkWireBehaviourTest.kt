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
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Establishes how `com.anthropic:anthropic-java:3.3.0` (built the way [AnthropicReplyGenerator]
 * builds it: `maxRetries(0)`, `followRedirects(false)`) behaves on the wire vs. inside an SDK
 * `addInterceptor` hook for 503 `Retry-After: 0`, 429, and a connection reset before the status line.
 */
class SdkWireBehaviourTest {

    private fun buildClient(baseUrl: String, onIntercept: () -> Unit): AnthropicClient =
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

    @Test
    fun `anthropic sdk wire count vs sdk addInterceptor on 503 Retry-After 0, 429, and connection reset`() {
        // 1a. 503 Retry-After: 0 then 200
        val wire503Then200: Int
        val intercept503Then200 = AtomicInteger()
        FakeMessagesApi().use { api ->
            api.firstAnswers.add(
                FakeMessagesApi.Answer(503, FakeMessagesApi.error("api_error", "busy"), headers = mapOf("retry-after" to "0"))
            )
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
            val client = buildClient(api.baseUrl) { intercept503Then200.incrementAndGet() }
            try {
                client.beta().messages().withRawResponse().create(params(), options()).use { it.parse() }
            } finally {
                client.close()
            }
            wire503Then200 = api.received.size
        }

        // 1b. 503 Retry-After: 0 repeated
        val wire503Always: Int
        val intercept503Always = AtomicInteger()
        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(503, FakeMessagesApi.error("api_error", "busy"), headers = mapOf("retry-after" to "0"))
            val client = buildClient(api.baseUrl) { intercept503Always.incrementAndGet() }
            try {
                runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
            } finally {
                client.close()
            }
            wire503Always = api.received.size
        }

        // 2a. 429 Retry-After: 0
        val wire429Zero: Int
        val intercept429Zero = AtomicInteger()
        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "0"))
            val client = buildClient(api.baseUrl) { intercept429Zero.incrementAndGet() }
            try {
                runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
            } finally {
                client.close()
            }
            wire429Zero = api.received.size
        }

        // 2b. 429 Retry-After: 120
        val wire429Delay: Int
        val intercept429Delay = AtomicInteger()
        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(429, FakeMessagesApi.error("rate_limit_error", "slow"), headers = mapOf("retry-after" to "120"))
            val client = buildClient(api.baseUrl) { intercept429Delay.incrementAndGet() }
            try {
                runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
            } finally {
                client.close()
            }
            wire429Delay = api.received.size
        }

        // 2c. 408 Request Timeout (tests retryOnConnectionFailure=false in Anthropic's OkHttpClient)
        val wire408: Int
        val intercept408 = AtomicInteger()
        FakeMessagesApi().use { api ->
            api.firstAnswers.add(FakeMessagesApi.Answer(408, FakeMessagesApi.error("timeout_error", "timeout")))
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
            val client = buildClient(api.baseUrl) { intercept408.incrementAndGet() }
            try {
                runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
            } finally {
                client.close()
            }
            wire408 = api.received.size
        }

        // 3a. Connection reset (TCP RST via SO_LINGER=0) before the status line
        val wireRst = AtomicInteger()
        val interceptRst = AtomicInteger()
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val acceptThread = Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    wireRst.incrementAndGet()
                    try {
                        socket.soTimeout = 1_000
                        val input = BufferedInputStream(socket.getInputStream())
                        // Read at least the request line/headers so the client really sent bytes.
                        val buf = ByteArray(4096)
                        input.read(buf)
                        // Force TCP RST before any status line is written.
                        socket.setSoLinger(true, 0)
                    } catch (_: Exception) {
                        // ignore
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = buildClient("http://127.0.0.1:${server.localPort}") { interceptRst.incrementAndGet() }
            try {
                runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
            } finally {
                client.close()
                server.close()
                acceptThread.join(2_000)
            }
        }

        // 3b. Clean EOF before the status line
        val wireEof = AtomicInteger()
        val interceptEof = AtomicInteger()
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val acceptThread = Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    wireEof.incrementAndGet()
                    try {
                        socket.soTimeout = 1_000
                        val buf = ByteArray(4096)
                        socket.getInputStream().read(buf)
                    } catch (_: Exception) {
                        // ignore
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.apply { isDaemon = true; start() }

            val client = buildClient("http://127.0.0.1:${server.localPort}") { interceptEof.incrementAndGet() }
            try {
                runCatching { client.beta().messages().withRawResponse().create(params(), options()).close() }
            } finally {
                client.close()
                server.close()
                acceptThread.join(2_000)
            }
        }

        println(
            """
            |=== SDK WIRE PROBE (anthropic-java:3.3.0) ===
            |503 Retry-After:0 -> 200 : wire=$wire503Then200, sdkInterceptor=${intercept503Then200.get()}
            |503 Retry-After:0 always : wire=$wire503Always, sdkInterceptor=${intercept503Always.get()}
            |429 Retry-After:0        : wire=$wire429Zero, sdkInterceptor=${intercept429Zero.get()}
            |429 Retry-After:120      : wire=$wire429Delay, sdkInterceptor=${intercept429Delay.get()}
            |408 Request Timeout      : wire=$wire408, sdkInterceptor=${intercept408.get()}
            |TCP RST before status    : wire=${wireRst.get()}, sdkInterceptor=${interceptRst.get()}
            |EOF before status        : wire=${wireEof.get()}, sdkInterceptor=${interceptEof.get()}
            """.trimMargin()
        )

        // Exact observations established by running against local servers:
        assertEquals(2, wire503Then200, "503 Retry-After:0 -> 200 wire count")
        assertEquals(1, intercept503Then200.get(), "503 Retry-After:0 -> 200 SDK interceptor runs once above OkHttp replay")
        assertEquals(2, wire503Always, "503 Retry-After:0 always wire count")
        assertEquals(1, intercept503Always.get(), "503 Retry-After:0 always SDK interceptor count")
        assertEquals(1, wire429Zero, "429 Retry-After:0 wire count")
        assertEquals(1, intercept429Zero.get(), "429 Retry-After:0 SDK interceptor count")
        assertEquals(1, wire429Delay, "429 Retry-After:120 wire count")
        assertEquals(1, intercept429Delay.get(), "429 Retry-After:120 SDK interceptor count")
        assertEquals(1, wire408, "408 Request Timeout wire count")
        assertEquals(1, intercept408.get(), "408 Request Timeout SDK interceptor count")
        assertEquals(1, wireRst.get(), "TCP RST before status wire count")
        assertEquals(1, interceptRst.get(), "TCP RST before status SDK interceptor count")
        assertEquals(1, wireEof.get(), "EOF before status wire count")
        assertEquals(1, interceptEof.get(), "EOF before status SDK interceptor count")
    }

    @Test
    fun `openai ktor okhttp wire count on 503 Retry-After 0, 429, and connection reset`() {
        val wire503: Int
        FakeMessagesApi().use { api ->
            api.firstAnswers.add(
                FakeMessagesApi.Answer(503, """{"error":{"message":"busy","type":"server_error"}}""", headers = mapOf("retry-after" to "0"))
            )
            api.answer = FakeMessagesApi.Answer(
                200,
                """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""" + "\n\n" +
                    """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
                    "data: [DONE]\n\n",
                headers = mapOf("content-type" to "text/event-stream"),
            )
            val gen = OpenAIReplyGenerator(apiKey = "test-key-not-real", log = {}, baseUrl = "${api.baseUrl}/v1/")
            runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
            wire503 = api.received.size
        }

        val wire429: Int
        FakeMessagesApi().use { api ->
            api.answer = FakeMessagesApi.Answer(429, """{"error":{"message":"slow","type":"rate_limit_error"}}""", headers = mapOf("retry-after" to "0"))
            val gen = OpenAIReplyGenerator(apiKey = "test-key-not-real", log = {}, baseUrl = "${api.baseUrl}/v1/")
            runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
            wire429 = api.received.size
        }

        val wire408: Int
        FakeMessagesApi().use { api ->
            api.firstAnswers.add(FakeMessagesApi.Answer(408, """{"error":{"message":"timeout","type":"server_error"}}"""))
            api.answer = FakeMessagesApi.Answer(
                200,
                """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}""" + "\n\n" +
                    """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
                    "data: [DONE]\n\n",
                headers = mapOf("content-type" to "text/event-stream"),
            )
            val gen = OpenAIReplyGenerator(apiKey = "test-key-not-real", log = {}, baseUrl = "${api.baseUrl}/v1/")
            runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
            wire408 = api.received.size
        }

        val wireRst = AtomicInteger()
        ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            val acceptThread = Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    wireRst.incrementAndGet()
                    try {
                        socket.soTimeout = 1_000
                        socket.getInputStream().read(ByteArray(4096))
                        socket.setSoLinger(true, 0)
                    } catch (_: Exception) {
                        // ignore
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.apply { isDaemon = true; start() }

            val gen = OpenAIReplyGenerator(apiKey = "test-key-not-real", log = {}, baseUrl = "http://127.0.0.1:${server.localPort}/v1/")
            runBlocking { gen.generateReply(AIReplyRequest("s", "u")) }
            server.close()
            acceptThread.join(2_000)
        }

        println(
            """
            |=== OPENAI KTOR-OKHTTP WIRE PROBE (master baseline) ===
            |503 Retry-After:0 -> 200 : wire=$wire503
            |429 Retry-After:0        : wire=$wire429
            |408 Request Timeout      : wire=$wire408
            |TCP RST before status    : wire=${wireRst.get()}
            """.trimMargin()
        )
    }
}
