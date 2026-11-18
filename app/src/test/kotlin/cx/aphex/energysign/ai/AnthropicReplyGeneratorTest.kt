package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Anthropic generator, driven through the real SDK client against a local fake of the
 * Messages API. Nothing here reaches the live service, and the key is a made-up string.
 */
class AnthropicReplyGeneratorTest {

    private val api = FakeMessagesApi()
    private val log = mutableListOf<String>()

    @AfterEach
    fun stopServer() = api.close()

    private fun generator(
        apiKey: String = FAKE_KEY,
        timeout: Duration = Duration.ofSeconds(5),
        baseUrl: String = api.baseUrl,
        effort: () -> AIEffort = { AIEffort.DEFAULT },
        hardLimit: Duration = timeout.plusSeconds(2),
    ) = AnthropicReplyGenerator(
        apiKey = apiKey, log = { log.add(it) }, effort = effort, requestTimeout = timeout, hardLimit = hardLimit,
        baseUrl = baseUrl,
    )

    private fun ask(
        generator: AnthropicReplyGenerator = generator(),
        system: String = "SYSTEM PROMPT",
        user: String = "is the bass loud enough",
    ): AIReplyResult = runBlocking { generator.generateReply(AIReplyRequest(system, user)) }

    private fun assertNoReply(expected: NoReplyReason, result: AIReplyResult) {
        val noReply = assertIs<AIReplyResult.NoReply>(result, "expected no reply, got $result")
        assertEquals(expected, noReply.reason)
    }

    // ---- the request ----

    @Test
    fun `the request names the model, the effort and the fallback, and carries no sampling or thinking setting`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))

        ask(system = "You are RaveGPT. Current time: 23:41", user = "is the bass loud enough")

        val request = api.received.single()
        val body = request.json
        assertTrue(request.path.startsWith("/v1/messages"), request.path)
        assertEquals("claude-fable-5-2", body["model"].asString)
        assertEquals("claude-fable-5-2", AnthropicReplyGenerator.MODEL)
        assertEquals("high", body["output_config"].asJsonObject["effort"].asString)
        assertEquals("default", body["fallbacks"].asString)
        assertTrue(
            request.header("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"),
            "beta header was ${request.header("anthropic-beta")}",
        )
        for (forbidden in listOf("temperature", "top_p", "top_k", "thinking", "tool_choice", "tools", "stop_sequences")) {
            assertFalse(body.has(forbidden), "the request must not send $forbidden: ${request.body}")
        }
        assertFalse(body.has("stream") && body["stream"].asBoolean, "the request is not streamed")
        assertEquals(16000, body["max_tokens"].asInt)
    }

    @Test
    fun `the effort is the one set for this request, under the name the API knows`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
        var effort = AIEffort.LOW
        val generator = generator(effort = { effort })

        for (level in AIEffort.values()) {
            effort = level
            ask(generator)
        }

        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            api.received.map { it.json["output_config"].asJsonObject["effort"].asString },
        )
    }

    @Test
    fun `the request carries the system prompt it was given and one user message, with no assistant prefill`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))

        ask(system = "You are RaveGPT. Current time: 23:41", user = "is the bass loud enough")

        val body = api.received.single().json
        val system = body["system"]
        val systemText =
            if (system.isJsonPrimitive) system.asString
            else system.asJsonArray.joinToString("") { it.asJsonObject["text"].asString }
        assertEquals("You are RaveGPT. Current time: 23:41", systemText)

        val messages = body["messages"].asJsonArray
        assertEquals(1, messages.size())
        val message = messages[0].asJsonObject
        assertEquals("user", message["role"].asString)
        val content = message["content"]
        val userText =
            if (content.isJsonPrimitive) content.asString
            else content.asJsonArray.joinToString("") { it.asJsonObject["text"].asString }
        assertEquals("is the bass loud enough", userText)
    }

    @Test
    fun `the key goes in the request header and never into the log`() {
        api.answer = FakeMessagesApi.Answer(401, FakeMessagesApi.error("authentication_error", "invalid x-api-key"))

        ask()

        // The SDK decides which header carries the key; it must be exactly one, and nowhere else.
        val request = api.received.single()
        assertEquals(listOf("authorization"), request.headersContaining(FAKE_KEY))
        assertFalse(request.body.contains(FAKE_KEY), "the key is in the request body")
        assertFalse(request.path.contains(FAKE_KEY), "the key is in the URL")
        assertFalse(log.any { it.contains(FAKE_KEY) }, "the key was logged: $log")
    }

    // ---- the reply ----

    @Test
    fun `the reply is the text of the text blocks, in order, without the thinking block`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message(
                """[{"type":"thinking","thinking":"","signature":"c2ln"},
                    {"type":"text","text":"The bass is fine, "},
                    {"type":"text","text":"your earplugs are just working."}]"""
            ),
        )

        assertEquals(AIReplyResult.Reply("The bass is fine, your earplugs are just working."), ask())
    }

    @Test
    fun `a reply served by the fallback model is still a reply`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message(
                """[{"type":"fallback","from":{"model":"claude-fable-5-2"},"to":{"model":"claude-opus-4-8"}},
                    {"type":"text","text":"Hydrate."}]""",
                model = "claude-opus-4-8",
            ),
        )

        assertEquals(AIReplyResult.Reply("Hydrate."), ask())
    }

    // ---- no reply ----

    @Test
    fun `a refusal is no reply, even when content came with it`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message(
                """[{"type":"text","text":"I was about to say"}]""",
                stopReason = "refusal",
                stopDetails = """{"type":"refusal","category":"cyber","explanation":"declined"}""",
            ),
        )

        assertNoReply(NoReplyReason.REFUSED, ask())
    }

    @Test
    fun `a refusal with no content and no details is no reply`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "refusal"))

        assertNoReply(NoReplyReason.REFUSED, ask())
    }

    @Test
    fun `an answer with no text is no reply`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"thinking","thinking":"","signature":"c2ln"},{"type":"text","text":"  \n "}]"""),
        )

        assertNoReply(NoReplyReason.EMPTY, ask())
    }

    @Test
    fun `an answer cut off by the token limit is no reply, even with text so far`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"The bass is fi"}]""", stopReason = "max_tokens"),
        )

        assertNoReply(NoReplyReason.TRUNCATED, ask())
    }

    @Test
    fun `a stop reason the app does not handle is no reply`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"half a turn"}]""", stopReason = "pause_turn"),
        )

        assertNoReply(NoReplyReason.UNEXPECTED_RESPONSE, ask())
    }

    @Test
    fun `a missing key is no reply, with one log line and no request`() {
        val result = ask(generator(apiKey = "  "))

        assertNoReply(NoReplyReason.MISSING_KEY, result)
        assertTrue(api.received.isEmpty(), "no request may be made without a key")
        assertEquals(1, log.size, "one log line, got $log")
    }

    @Test
    fun `a server that does not answer in time is no reply, and the wait is bounded`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"too late"}]"""),
            delayMs = 4_000,
        )

        val startedAt = System.nanoTime()
        val result = ask(generator(timeout = Duration.ofMillis(400)))
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNoReply(NoReplyReason.TIMEOUT, result)
        assertTrue(waitedMs < 2_500, "waited $waitedMs ms for a 400 ms timeout")
        assertEquals(1, api.received.size, "a timeout is not retried")
    }

    @Test
    fun `the wait ends at the hard limit even when the HTTP client has not given up`() {
        // The client's own timeout cannot interrupt everything (a DNS lookup, for one). Here the
        // client would wait 20 s for a slow server; the caller is let go after 400 ms.
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"too late"}]"""),
            delayMs = 4_000,
        )

        val startedAt = System.nanoTime()
        val result = ask(generator(timeout = Duration.ofSeconds(20), hardLimit = Duration.ofMillis(400)))
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNoReply(NoReplyReason.TIMEOUT, result)
        assertTrue(waitedMs < 2_500, "waited $waitedMs ms for a 400 ms hard limit")
    }

    @Test
    fun `a key that cannot be sent in a header is no reply, and the key stays out of the log`() {
        val key = "\u201Cmade-up-key-12345\u201D"
        val secrets = Secrets(listOf(key))
        val generator = AnthropicReplyGenerator(apiKey = key, log = secrets.sink { log.add(it) }, baseUrl = api.baseUrl)
        val service = AIReplyService(generator = generator, log = secrets.sink { log.add(it) }, redact = secrets::redact)

        val result = runBlocking { service.replyTo("hello") }

        val noReply = assertIs<AIReplyResult.NoReply>(result)
        assertFalse(noReply.detail.contains("made-up-key"), noReply.detail)
        assertFalse(log.any { it.contains("made-up-key") }, "$log")
        assertTrue(api.received.isEmpty(), "nothing can have been sent")
    }

    @Test
    fun `a rate limit is no reply and is not retried, whatever Retry-After says`() {
        api.answer = FakeMessagesApi.Answer(
            429,
            FakeMessagesApi.error("rate_limit_error", "slow down"),
            headers = mapOf("retry-after" to "120"),
        )

        val startedAt = System.nanoTime()
        val result = ask()
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNoReply(NoReplyReason.RATE_LIMITED, result)
        assertEquals(1, api.received.size, "the SDK must not retry")
        assertTrue(waitedMs < 4_000, "waited $waitedMs ms")
    }

    @Test
    fun `an overloaded or failing server is no reply and is not retried`() {
        api.answer = FakeMessagesApi.Answer(529, FakeMessagesApi.error("overloaded_error", "Overloaded"))

        assertNoReply(NoReplyReason.SERVER_ERROR, ask())
        assertEquals(1, api.received.size, "the SDK must not retry")
    }

    @Test
    fun `a rejected key is no reply`() {
        api.answer = FakeMessagesApi.Answer(401, FakeMessagesApi.error("authentication_error", "invalid x-api-key"))

        assertNoReply(NoReplyReason.KEY_REJECTED, ask())
    }

    @Test
    fun `a request the API rejects is no reply`() {
        api.answer = FakeMessagesApi.Answer(400, FakeMessagesApi.error("invalid_request_error", "temperature is not supported"))

        assertNoReply(NoReplyReason.REQUEST_REJECTED, ask())
    }

    @Test
    fun `a model the key cannot use is no reply`() {
        api.answer = FakeMessagesApi.Answer(404, FakeMessagesApi.error("not_found_error", "model: claude-fable-5-2"))

        assertNoReply(NoReplyReason.REQUEST_REJECTED, ask())
    }

    @Test
    fun `a response that is not a message is no reply`() {
        api.answer = FakeMessagesApi.Answer(200, """{"hello":"there"}""")

        assertNoReply(NoReplyReason.UNEXPECTED_RESPONSE, ask())
    }

    @Test
    fun `nobody listening is no reply`() {
        val closedPort = api.baseUrl
        api.close()

        assertNoReply(NoReplyReason.NETWORK, ask(generator(baseUrl = closedPort)))
    }

    // ---- certificate failures say which they are ----

    @Test
    fun `a certificate that is not valid yet is reported as a clock problem`() {
        val failure = SSLHandshakeException("Chain validation failed").apply {
            initCause(
                CertificateException(
                    "Chain validation failed",
                    CertPathValidatorException("timestamp check failed", CertificateNotYetValidException("not valid until 2026")),
                )
            )
        }

        assertEquals(NoReplyReason.CERTIFICATE_CLOCK, AnthropicReplyGenerator.reasonForIoFailure(failure))
    }

    @Test
    fun `an expired certificate is reported as a clock problem`() {
        val failure = SSLHandshakeException("Chain validation failed").apply {
            initCause(CertificateException("x", CertPathValidatorException("y", CertificateExpiredException("expired"))))
        }

        assertEquals(NoReplyReason.CERTIFICATE_CLOCK, AnthropicReplyGenerator.reasonForIoFailure(failure))
    }

    @Test
    fun `a chain with no trusted root is reported as an untrusted certificate`() {
        val failure = SSLHandshakeException("java.security.cert.CertPathValidatorException").apply {
            initCause(CertificateException(CertPathValidatorException("Trust anchor for certification path not found.")))
        }

        assertEquals(NoReplyReason.CERTIFICATE_UNTRUSTED, AnthropicReplyGenerator.reasonForIoFailure(failure))
    }

    @Test
    fun `the log line for a certificate failure says which kind it is`() {
        val clock = AnthropicReplyGenerator.describe(NoReplyReason.CERTIFICATE_CLOCK)
        val root = AnthropicReplyGenerator.describe(NoReplyReason.CERTIFICATE_UNTRUSTED)

        assertTrue(clock.contains("clock", ignoreCase = true), clock)
        assertTrue(root.contains("root", ignoreCase = true), root)
        assertFalse(root.contains("clock", ignoreCase = true), root)
    }

    @Test
    fun `an unknown host and a timed out socket are told apart`() {
        assertEquals(
            NoReplyReason.NETWORK,
            AnthropicReplyGenerator.reasonForIoFailure(java.net.UnknownHostException("api.anthropic.com")),
        )
        assertEquals(
            NoReplyReason.TIMEOUT,
            AnthropicReplyGenerator.reasonForIoFailure(java.net.SocketTimeoutException("timeout")),
        )
    }

    companion object {
        /** Not a key: a made-up string, so the test can see where the generator puts it. */
        private const val FAKE_KEY = "test-key-not-real"
    }
}
