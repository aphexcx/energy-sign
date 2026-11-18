package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    private val serverClock = ServerClock { 0L }

    /** What is logged when all is well. [log] holds failures and warnings only. */
    private val infoLog = mutableListOf<String>()

    private fun generator(
        apiKey: String = FAKE_KEY,
        timeout: Duration = Duration.ofSeconds(5),
        baseUrl: String = api.baseUrl,
        effort: () -> AIEffort = { AIEffort.DEFAULT },
    ) = AnthropicReplyGenerator(
        apiKey = apiKey, log = { log.add(it) }, info = { infoLog.add(it) }, effort = effort, serverClock = serverClock,
        requestTimeout = timeout, baseUrl = baseUrl,
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
                """[{"type":"thinking","thinking":"THINKING MUST NOT BE SHOWN","signature":"c2ln"},
                    {"type":"text","text":"The bass is fine, "},
                    {"type":"text","text":"your earplugs are just working."}]"""
            ),
        )

        // The thinking block has text in it here: taking it for part of the answer would show.
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
    fun `a refusal is recognised before the content is looked at - content that cannot be read does not matter`() {
        // "content" is not a list here. Code that reads the content first fails on it and never
        // gets to the stop reason; the answer must still be "refused".
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message(""""this is not a list of blocks"""", stopReason = "refusal"),
        )

        assertNoReply(NoReplyReason.REFUSED, ask())
    }

    @Test
    fun `a cut-off answer is recognised before the content is looked at`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message(""""this is not a list of blocks"""", stopReason = "max_tokens"),
        )

        assertNoReply(NoReplyReason.TRUNCATED, ask())
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
    fun `a server that does not answer in time is no reply, and the request ends at its time limit`() {
        // Nothing but the request's own time limit ends this: the fake answers after 2 s, in
        // full, and nobody outside stops the call. Without the limit the late answer would be
        // taken for the reply.
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"too late"}]"""),
            delayMs = 2_000,
        )

        val startedAt = System.nanoTime()
        val result = ask(generator(timeout = Duration.ofMillis(400)))
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNoReply(NoReplyReason.TIMEOUT, result)
        assertTrue(waitedMs < 1_500, "waited $waitedMs ms for a 400 ms timeout")
        assertEquals(1, api.received.size, "a timeout is not retried")
    }

    @Test
    fun `a caller that stops waiting does not leave a call running behind it - the generator returns when the call has ended`() {
        // The caller gives up after 200 ms; the request's own limit is 1 s; the fake would
        // answer after 3 s. The generator comes back at the request's limit: not before (the
        // call would still be out), and not at 3 s (the limit holds).
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"too late"}]"""),
            delayMs = 3_000,
        )
        val generator = generator(timeout = Duration.ofSeconds(1))

        val startedAt = System.nanoTime()
        val result = runBlocking {
            withTimeoutOrNull(200) { generator.generateReply(AIReplyRequest("s", "u")) }
        }
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(null, result)
        assertTrue(waitedMs in 800..2_500, "the generator came back after $waitedMs ms")
        assertTrue(log.any { it.contains("no answer within the time limit") }, "the call itself ended by its limit: $log")
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

    // ---- nothing of the provider's reaches the log (class B)

    @Test
    fun `what the API writes into an error is not logged and not handed on, only the status and the kind`() {
        // The last characters of the key, as an API might echo them. No exact-match redaction
        // can catch a piece of a key: the message must not be used at all.
        val excerpt = FAKE_KEY.takeLast(6)
        api.answer = FakeMessagesApi.Answer(
            400,
            FakeMessagesApi.error("invalid_request_error", "bad credential ending in $excerpt, do not repeat"),
            headers = mapOf("x-diagnostic" to FAKE_KEY, "request-id" to "req_$excerpt"),
        )

        val result = ask()

        val noReply = assertIs<AIReplyResult.NoReply>(result)
        assertEquals(NoReplyReason.REQUEST_REJECTED, noReply.reason)
        for (text in log + noReply.detail) {
            assertFalse(text.contains(excerpt), text)
            assertFalse(text.contains("do not repeat"), text)
        }
        assertTrue(log.single().contains("HTTP 400 INVALID_REQUEST_ERROR"), log.single())
    }

    @Test
    fun `an error type the SDK does not know is not copied into the log`() {
        api.answer = FakeMessagesApi.Answer(418, FakeMessagesApi.error("made_up_${FAKE_KEY.takeLast(6)}", "x"))

        ask()

        assertFalse(log.any { it.contains(FAKE_KEY.takeLast(6)) }, "$log")
        assertTrue(log.single().contains("HTTP 418"), log.single())
    }

    @Test
    fun `a refusal names its category only when it is one of the known ones`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("[]", stopReason = "refusal", stopDetails = """{"type":"refusal","category":"cyber","explanation":"secret-explanation"}"""),
        )
        ask()
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("[]", stopReason = "refusal", stopDetails = """{"type":"refusal","category":"made-up-category","explanation":"x"}"""),
        )
        ask()

        assertTrue(log[0].contains("category cyber"), log[0])
        assertFalse(log.any { it.contains("secret-explanation") || it.contains("made-up-category") }, "$log")
    }

    @Test
    fun `a model name the API made up is not copied into the log`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"Hydrate."}]""", model = "model-${FAKE_KEY.takeLast(6)}"),
        )

        assertEquals(AIReplyResult.Reply("Hydrate."), ask())
        assertFalse((log + infoLog).any { it.contains(FAKE_KEY.takeLast(6)) }, "$log $infoLog")
        assertTrue(infoLog.single().contains("another model"), infoLog.single())
    }

    // ---- the server's time (class E)

    @Test
    fun `the time the server gives with a reply is kept`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""),
            headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
        )

        ask()

        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
    }

    @Test
    fun `the time the server gives with an error is kept too`() {
        api.answer = FakeMessagesApi.Answer(
            529,
            FakeMessagesApi.error("overloaded_error", "Overloaded"),
            headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
        )

        ask()

        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
    }

    @Test
    fun `asking for the time makes one free request for the model, and none once the time is known`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
            headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
        )
        val generator = generator()

        generator.syncClock()
        generator.syncClock()

        val request = api.received.single()
        assertTrue(request.path.startsWith("/v1/models/claude-fable-5-2"), request.path)
        assertEquals("", request.body, "a GET with no body: no tokens, no charge")
        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
    }

    @Test
    fun `asking for the time never throws, whatever comes back`() {
        api.answer = FakeMessagesApi.Answer(503, "not json at all")
        generator().syncClock()
        val port = api.baseUrl
        api.close()
        generator(baseUrl = port).syncClock()

        assertEquals(null, serverClock.now())
    }

    // ---- the warm-up (requirement a)

    @Test
    fun `the warm-up runs one whole request and answer on this machine, then asks the API for the model, and buys nothing`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
            headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
        )

        generator().warmUp()

        // The API itself saw one request, the free one. The message that was made and read went
        // to the stand-in on this machine.
        val request = api.received.single()
        assertTrue(request.path.startsWith("/v1/models/claude-fable-5-2"), request.path)
        assertFalse(api.received.any { it.path.contains("/v1/messages") }, "the warm-up must not ask for a reply")
        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
        assertTrue(log.isEmpty(), "nothing went wrong: $log")
        assertTrue(infoLog.any { it.contains("the key and the model are accepted") }, "$infoLog")
    }

    @Test
    fun `the warm-up never throws, with the API down or with no key`() {
        val port = api.baseUrl
        api.close()

        generator(baseUrl = port).warmUp()
        generator(apiKey = "", baseUrl = port).warmUp()

        assertTrue(log.any { it.contains("warm-up") }, "the failure of the free request is logged: $log")
        assertFalse(log.any { it.contains("local part") }, "the local part does not need the API: $log")
    }

    @Test
    fun `under the one attempt of the SDK the HTTP client may send a request twice, never more`() {
        // A 503 that says "Retry-After: 0" is repeated once by the HTTP client itself, inside the
        // same time limit. This is the most the comment on the class promises.
        api.answer = FakeMessagesApi.Answer(503, FakeMessagesApi.error("api_error", "busy"), headers = mapOf("retry-after" to "0"))

        assertNoReply(NoReplyReason.SERVER_ERROR, ask())

        assertTrue(api.received.size in 1..2, "${api.received.size} requests")
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
        assertNoReply(NoReplyReason.NOT_A_MESSAGE, ask())

        // A page from something in between (a captive portal), with status 200.
        api.answer = FakeMessagesApi.Answer(200, "<html><body>Sign in to the festival wifi</body></html>")
        assertNoReply(NoReplyReason.NOT_A_MESSAGE, ask())
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
