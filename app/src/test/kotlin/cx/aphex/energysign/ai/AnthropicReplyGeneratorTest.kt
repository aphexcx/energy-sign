package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
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
        standIn: (String) -> LocalStandIn = { LocalStandIn(it) },
        minTimeToSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
        serverClock: ServerClock = this.serverClock,
    ) = AnthropicReplyGenerator(
        apiKey = apiKey, log = { log.add(it) }, info = { infoLog.add(it) }, effort = effort, serverClock = serverClock,
        requestTimeout = timeout, baseUrl = baseUrl, standIn = standIn, minTimeToSend = minTimeToSend,
    )

    private fun ask(
        generator: AnthropicReplyGenerator = generator(),
        system: String = "SYSTEM PROMPT",
        user: String = "is the bass loud enough",
        deadline: Deadline = Deadline.after(AIReplyRunner.QUERY_BUDGET),
        stillWanted: () -> Boolean = { true },
    ): AIReplyResult = runBlocking { generator.generateReply(AIReplyRequest(system, user, deadline, stillWanted)) }

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
    fun `an error never tells the time, the API's own error included`() {
        // The body of an error has been read when the error is known, so nobody can say when its
        // headers arrived; and "a real error" cannot be told from a page that copies one.
        api.answer = FakeMessagesApi.Answer(
            529,
            FakeMessagesApi.error("overloaded_error", "Overloaded"),
            headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
        )

        assertNoReply(NoReplyReason.SERVER_ERROR, ask())
        generator().syncClock(Duration.ofSeconds(5)) // the free request gets the same error

        assertEquals(null, serverClock.now())
    }

    @Test
    fun `asking for the time makes one free request for the model, and none once the time is known`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
            headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
        )
        val generator = generator()

        generator.syncClock(java.time.Duration.ofSeconds(5))
        generator.syncClock(java.time.Duration.ofSeconds(5))

        val request = api.received.single()
        assertTrue(request.path.startsWith("/v1/models/claude-fable-5-2"), request.path)
        assertEquals("", request.body, "a GET with no body: no tokens, no charge")
        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
    }

    @Test
    fun `asking for the time never throws, whatever comes back`() {
        api.answer = FakeMessagesApi.Answer(503, "not json at all")
        generator().syncClock(java.time.Duration.ofSeconds(5))
        val port = api.baseUrl
        api.close()
        generator(baseUrl = port).syncClock(java.time.Duration.ofSeconds(5))

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

    // ---- the key goes to the API and nowhere else (class A)

    private val modelDescription =
        """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}"""

    @Test
    fun `the stand-in of the warm-up is asked one whole request, answers it, and never sees a byte of the key`() {
        // Made up, and unlike a real key on purpose (a scanner of this public repository should
        // not take it for one): letters that no request has in it.
        val key = "QQQQQ-ZZZZZ-QQQQQ-JJJJJ-XXXXX-ZZZZZ"
        api.answer = FakeMessagesApi.Answer(200, modelDescription)
        val seen = CopyOnWriteArrayList<String>()

        generator(apiKey = key, standIn = { answer -> LocalStandIn(answer, seen = { seen += it }) }).warmUp()

        // One request went to the stand-in, a request like a real one, and its answer was read as a reply.
        val request = seen.single()
        assertTrue(request.startsWith("POST /v1/messages"), request.take(60))
        assertTrue(request.contains("\"model\":\"claude-fable-5-2\""), "the body is a real request for a reply")
        assertTrue(log.isEmpty(), "the local answer was not read as a reply: $log")
        // No piece of the key is in it, in a header or anywhere else; the made-up key is.
        for (start in 0..key.length - 5) {
            val piece = key.substring(start, start + 5)
            assertFalse(request.contains(piece), "\"$piece\" of the key reached the stand-in")
        }
        assertTrue(request.contains(AnthropicReplyGenerator.WARM_UP_KEY), "the local request carries the made-up key")
        // The key itself went where it belongs: to the API, with the free request.
        assertTrue(api.received.single().headersContaining(key).isNotEmpty())
    }

    @Test
    fun `the local request of the warm-up goes straight to this machine, whatever proxy the system has been given`() {
        api.answer = FakeMessagesApi.Answer(200, modelDescription)
        val nobodyListens = ServerSocket(0).use { it.localPort }
        val seen = CopyOnWriteArrayList<String>()
        val before = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> =
                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(LocalStandIn.IPV4_LOOPBACK, nobodyListens)))

            override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
        })
        try {
            generator(standIn = { answer -> LocalStandIn(answer, seen = { seen += it }) }).warmUp()
        } finally {
            ProxySelector.setDefault(before)
        }

        assertEquals(1, seen.size, "the local request did not reach the stand-in: $log")
        assertFalse(log.any { it.contains("local part") }, "$log")
    }

    @Test
    fun `a redirect is not followed - the request goes nowhere but where it was sent`() {
        FakeMessagesApi().use { elsewhere ->
            elsewhere.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"from elsewhere"}]"""))
            api.answer = FakeMessagesApi.Answer(307, "", headers = mapOf("location" to "${elsewhere.baseUrl}/v1/messages"))

            val result = ask()

            assertIs<AIReplyResult.NoReply>(result, "a redirect is a failure, got $result")
            assertEquals(1, api.received.size)
            assertTrue(elsewhere.received.isEmpty(), "the request, with its text, was sent on to another server")
        }
    }

    // ---- one budget of time for a query (class B)

    @Test
    fun `with too little of the query's time left nothing is sent, and the result says so`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))

        val result = ask(deadline = Deadline.after(Duration.ofSeconds(5)))

        val noReply = assertIs<AIReplyResult.NoReply>(result)
        // Not TIMEOUT: nothing was asked of the provider, so this is no failure of the provider
        // and no reason for the pause that follows one.
        assertEquals(NoReplyReason.TOO_LATE_TO_SEND, noReply.reason)
        assertTrue(noReply.reason.nothingWasSent && !noReply.reason.backOff)
        assertTrue(noReply.detail.contains("not sent"), noReply.detail)
        assertTrue(api.received.isEmpty(), "a request went out with 5 s left: it would be paid for and not waited for")
        assertEquals(Duration.ofSeconds(15), AnthropicReplyGenerator.MIN_TIME_TO_SEND)
    }

    @Test
    fun `the request gets what is left of the query's time, however long a request may take at most`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"too late"}]"""), delayMs = 6_000)
        val generator = generator(timeout = Duration.ofSeconds(90), minTimeToSend = Duration.ofMillis(100))
        // The first use of the client, which is slow in a fresh JVM, is not part of what is timed.
        api.firstAnswers.add(FakeMessagesApi.Answer(400, FakeMessagesApi.error("invalid_request_error", "warm")))
        ask(generator)

        val startedAt = System.nanoTime()
        val result = ask(generator, deadline = Deadline.after(Duration.ofMillis(1_200)))
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNoReply(NoReplyReason.TIMEOUT, result)
        assertEquals(2, api.received.size, "it was sent")
        assertTrue(waitedMs in 900..3_500, "the request ran $waitedMs ms with 1.2 s of the query's time left")
    }

    // ---- no call for a query that is gone (class C)

    @Test
    fun `a query that is not wanted any more is not sent`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
        var asked = 0

        val result = ask(stillWanted = { asked++; false })

        assertNoReply(NoReplyReason.WITHDRAWN, result)
        assertEquals(1, asked)
        assertTrue(api.received.isEmpty(), "the request went out for a query that was deleted")
    }

    @Test
    fun `a query that is still wanted is asked about once, and sent`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"ok"}]"""))
        var asked = 0

        assertEquals(AIReplyResult.Reply("ok"), ask(stillWanted = { asked++; true }))

        assertEquals(1, asked)
        assertEquals(1, api.received.size)
    }

    // ---- what an answer IS never depends on a field that is read for the log (class C)

    @Test
    fun `a refusal is a refusal whatever shape its details have`() {
        val shapes = listOf("\"a string where an object belongs\"", "[1,2]", "42", """{"category":{"nested":true}}""", """{"category":7}""")
        for (details in shapes) {
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "refusal", stopDetails = details))

            assertNoReply(NoReplyReason.REFUSED, ask())
        }
        assertEquals(shapes.size, api.received.size)
    }

    @Test
    fun `a reply is a reply when the numbers and names for its log line are missing or of another kind`() {
        val text = """[{"type":"text","text":"Hydrate."}]"""
        val bodies = listOf(
            // no token counts
            """{"id":"m","type":"message","role":"assistant","model":"claude-fable-5-2","content":$text,"stop_reason":"end_turn","usage":{}}""",
            // no usage at all
            """{"id":"m","type":"message","role":"assistant","model":"claude-fable-5-2","content":$text,"stop_reason":"end_turn"}""",
            // token counts that are no numbers
            """{"id":"m","type":"message","role":"assistant","model":"claude-fable-5-2","content":$text,"stop_reason":"end_turn","usage":{"input_tokens":"many","output_tokens":[]}}""",
            // a model that is no name
            """{"id":"m","type":"message","role":"assistant","model":{"is":"an object"},"content":$text,"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
        )
        for (body in bodies) {
            api.answer = FakeMessagesApi.Answer(200, body)

            assertEquals(AIReplyResult.Reply("Hydrate."), ask(), body)
        }
    }

    @Test
    fun `an error is judged by its status, also when its body is not the API's or names nothing`() {
        api.answer = FakeMessagesApi.Answer(401, "<html>Sign in</html>")
        assertNoReply(NoReplyReason.KEY_REJECTED, ask())
        api.answer = FakeMessagesApi.Answer(400, """{"type":"error","error":{"type":{"not":"a name"},"message":7}}""")
        assertNoReply(NoReplyReason.REQUEST_REJECTED, ask())

        assertTrue(log.any { it.contains("HTTP 401") }, "$log")
        assertTrue(log.any { it.contains("HTTP 400") }, "$log")
    }

    @Test
    fun `a request that is too big or cannot be processed is the request's fault, and is not asked again`() {
        for (status in listOf(413, 422)) {
            api.answer = FakeMessagesApi.Answer(status, FakeMessagesApi.error("invalid_request_error", "no"))

            assertNoReply(NoReplyReason.REQUEST_REJECTED, ask())
        }
    }

    // ---- the time is true or absent (class F)

    private val date = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT")

    @Test
    fun `a page that answers the free request with HTTP 200 tells the clock nothing, and is not taken for the API`() {
        val pages = listOf(
            "<html>Sign in to this network</html>",
            "{}",
            """{"id":"some-other-model","type":"model","display_name":"Other","created_at":"2026-01-01T00:00:00Z"}""",
            // The right id, and not the description of a model: any JSON can carry an id.
            """{"id":"claude-fable-5-2"}""",
            """{"id":"claude-fable-5-2","type":"model"}""",
            """{"id":"claude-fable-5-2","type":"message","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
            """{"id":"claude-fable-5-2","type":"model","display_name":"","created_at":"2026-01-01T00:00:00Z"}""",
            """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"last winter"}""",
        )
        for (page in pages) {
            api.answer = FakeMessagesApi.Answer(200, page, headers = date)

            generator().syncClock(Duration.ofSeconds(5))

            assertEquals(null, serverClock.now(), "the clock was set by: $page")
            assertFalse(infoLog.any { it.contains("accepted") }, "\"accepted\" was logged for: $page")
        }
        assertEquals(pages.size, log.size, "each of them is said to be what it is: $log")
    }

    @Test
    fun `a reply that was kept somewhere on the way is a reply, and does not tell the time`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            FakeMessagesApi.message("""[{"type":"text","text":"Hydrate."}]"""),
            headers = mapOf("date" to "Sat, 21 Nov 2026 00:30:00 GMT", "age" to "3600"),
        )

        assertEquals(AIReplyResult.Reply("Hydrate."), ask())

        assertEquals(null, serverClock.now())
    }

    @Test
    fun `HTTP 200 that is not a message of the API tells the clock nothing`() {
        for (body in listOf("<html>Sign in</html>", """{"type":"message","content":[]}""")) {
            api.answer = FakeMessagesApi.Answer(200, body, headers = date)

            assertNoReply(NoReplyReason.NOT_A_MESSAGE, ask())

            assertEquals(null, serverClock.now(), "the clock was set by: $body")
        }
    }

    @Test
    fun `no error tells the time, whatever its status and its body`() {
        val errors = listOf(
            503 to "<html>Bad gateway</html>",
            503 to FakeMessagesApi.error("proxy_error", "from something in between"),
            529 to FakeMessagesApi.error("overloaded_error", "Overloaded"),
            500 to FakeMessagesApi.error("api_error", "Internal"),
            429 to FakeMessagesApi.error("rate_limit_error", "Slow down"),
            401 to FakeMessagesApi.error("authentication_error", "No"),
            400 to FakeMessagesApi.error("invalid_request_error", "No"),
        )
        for ((status, body) in errors) {
            api.answer = FakeMessagesApi.Answer(status, body, headers = date)
            assertNoReply(NoReplyReason.forHttpStatus(status), ask())
            generator().syncClock(Duration.ofSeconds(5))
            assertEquals(null, serverClock.now(), "HTTP $status with $body set the clock")
        }
    }

    @Test
    fun `HTTP 200 that has a stop reason and is not a whole message of the API tells the clock nothing, whatever becomes of it`() {
        val text = """[{"type":"text","text":"Hydrate."}]"""
        val whole = com.google.gson.JsonParser.parseString(FakeMessagesApi.message(text)).asJsonObject
        fun without(field: String) = whole.deepCopy().apply { remove(field) }.toString()
        fun with(field: String, json: String) = whole.deepCopy().apply { add(field, com.google.gson.JsonParser.parseString(json)) }.toString()
        val partial = listOf(
            // What a gateway in between may answer: a stop reason, and nothing of a message.
            """{"stop_reason":"maintenance"}""",
            """{"stop_reason":"end_turn","content":$text}""",
            without("id"), without("type"), without("role"), without("model"), without("usage"),
            with("id", "\"\""), with("type", "\"error\""), with("role", "\"user\""), with("model", "\"\""),
            with("usage", "{}"), with("usage", """{"input_tokens":"many","output_tokens":3}"""),
            with("stop_reason", "\"maintenance\""),
            // A refusal is judged without its content: here there is none at all.
            whole.deepCopy().apply { remove("content"); addProperty("stop_reason", "refusal") }.toString(),
        )
        for (body in partial) {
            api.answer = FakeMessagesApi.Answer(200, body, headers = date)

            ask() // a reply, or none: decided by the stop reason and the content, as before

            assertEquals(null, serverClock.now(), "the clock was set by: $body")
        }

        api.answer = FakeMessagesApi.Answer(200, whole.toString(), headers = date)
        assertEquals(AIReplyResult.Reply("Hydrate."), ask())
        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now(), "a whole message tells the time")
    }

    @Test
    fun `a refusal is a whole message of the API too, and tells the time`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("[]", stopReason = "refusal"), headers = date)

        assertNoReply(NoReplyReason.REFUSED, ask())

        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now())
    }

    @Test
    fun `a reply with two Age fields, or two Date fields, is a reply and does not tell the time`() {
        val twice = listOf(
            listOf("age" to "0", "age" to "3600"),
            listOf("age" to "3600", "age" to "0"),
            listOf("date" to "Sat, 21 Nov 2026 04:30:00 GMT"), // a second Date, beside the one in [date]
        )
        for (fields in twice) {
            api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"Hydrate."}]"""), headers = date, moreHeaders = fields)

            assertEquals(AIReplyResult.Reply("Hydrate."), ask())

            assertEquals(null, serverClock.now(), "the clock was set with $fields")
        }
    }

    @Test
    fun `asking for the time takes no longer than it is given, and is not tried with next to nothing`() {
        api.answer = FakeMessagesApi.Answer(200, modelDescription, delayMs = 4_000, headers = date)
        val generator = generator()
        // The first use of the client, which is slow in a fresh JVM, is not part of what is timed.
        api.firstAnswers.add(FakeMessagesApi.Answer(400, FakeMessagesApi.error("invalid_request_error", "warm")))
        ask(generator)

        val startedAt = System.nanoTime()
        generator.syncClock(Duration.ofMillis(1_200))
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(waitedMs in 900..3_000, "the time check ran $waitedMs ms with a limit of 1.2 s")

        val requests = api.received.size
        generator().syncClock(Duration.ofMillis(200))
        assertEquals(requests, api.received.size, "with 200 ms to spend the time is not asked for")
        assertEquals(null, serverClock.now())
    }

    // ---- found by the review of round 3

    /** A clock whose every look is later than the one before, by more each time: 1 s, 3 s, 6 s, ... */
    private fun steppingClock(): ServerClock {
        var looks = 0L
        var ms = 0L
        return ServerClock {
            looks++
            ms += looks * 1_000
            ms
        }
    }

    @Test
    fun `the time of a reply is the time it arrived, not the time its body had been read`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"Hydrate."}]"""), headers = date)
        val clock = steppingClock()

        ask(generator(serverClock = clock))

        // Looks at the clock: the arrival (1 s) and this one (3 s). A look when the body had
        // been read would make this one the third (6 s), and the time would read a second late.
        assertEquals(java.time.Instant.parse("2026-11-21T05:30:02Z"), clock.now())
    }

    @Test
    fun `the time of the model description is the time it arrived too`() {
        api.answer = FakeMessagesApi.Answer(200, modelDescription, headers = date)
        val clock = steppingClock()

        // syncClock looks once to see that the time is not known (nothing is counted then: no anchor).
        generator(serverClock = clock).syncClock(Duration.ofSeconds(5))

        assertEquals(java.time.Instant.parse("2026-11-21T05:30:02Z"), clock.now())
    }

    @Test
    fun `asking for the time may take as long as the caller allows - a first connection is slow`() {
        api.answer = FakeMessagesApi.Answer(200, modelDescription, delayMs = 5_300, headers = date)

        generator(timeout = Duration.ofSeconds(90)).syncClock(Duration.ofSeconds(20))

        assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), serverClock.now(), "the check was cut short: $log")
    }

    @Test
    fun `an Error in what is done for the log after an answer came does not cost the answer`() {
        api.answer = FakeMessagesApi.Answer(200, FakeMessagesApi.message("""[{"type":"text","text":"Hydrate."}]"""), headers = date)
        val generator = AnthropicReplyGenerator(
            apiKey = FAKE_KEY, log = { log.add(it) }, info = { throw NoClassDefFoundError("some/log/Class") },
            serverClock = serverClock, requestTimeout = Duration.ofSeconds(5), baseUrl = api.baseUrl,
        )

        assertEquals(AIReplyResult.Reply("Hydrate."), ask(generator))
    }

    companion object {
        /** Not a key: a made-up string, so the test can see where the generator puts it. */
        private const val FAKE_KEY = "test-key-not-real"
    }
}
