package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The OpenAI generator, driven through the real client against a local fake of the chat
 * completions endpoint. Nothing here reaches the live service, and the key is a made-up string.
 */
class OpenAIReplyGeneratorTest {

    private val api = FakeMessagesApi()
    private val log = mutableListOf<String>()

    @AfterEach
    fun stopServer() = api.close()

    private fun generator(apiKey: String = FAKE_KEY, timeout: java.time.Duration = java.time.Duration.ofSeconds(10)) =
        OpenAIReplyGenerator(apiKey = apiKey, log = { log.add(it) }, requestTimeout = timeout, baseUrl = "${api.baseUrl}/v1/")

    private fun ask(system: String = "SYSTEM PROMPT", user: String = "is the bass loud enough"): AIReplyResult =
        runBlocking { generator().generateReply(AIReplyRequest(system, user)) }

    private fun reason(result: AIReplyResult) = assertIs<AIReplyResult.NoReply>(result, "expected no reply, got $result").reason

    private fun chunk(delta: String, finishReason: String = "null", index: Int = 0): String =
        """data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-5.1",""" +
            """"choices":[{"index":$index,"delta":$delta,"finish_reason":$finishReason}]}""" + "\n\n"

    private fun stream(vararg events: String) = FakeMessagesApi.Answer(
        200, events.joinToString("") + "data: [DONE]\n\n", headers = mapOf("content-type" to "text/event-stream"),
    )

    @Test
    fun `the request is what the app always sent - the model, the temperature, the system prompt and the message`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"ok"}"""))

        ask(system = "You are RaveGPT.", user = "is the bass loud enough")

        val request = api.received.single()
        val body = request.json
        assertTrue(request.path.endsWith("/v1/chat/completions"), request.path)
        assertEquals("gpt-5.1", body["model"].asString)
        assertEquals(0.7, body["temperature"].asDouble)
        assertTrue(body["stream"].asBoolean)
        val messages = body["messages"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"].asString })
        assertEquals(listOf("You are RaveGPT.", "is the bass loud enough"), messages.map { it["content"].asString })
        assertEquals(listOf("authorization"), request.headersContaining(FAKE_KEY))
    }

    @Test
    fun `the reply is the streamed pieces put together`() {
        api.answer = stream(
            chunk("""{"role":"assistant","content":""}"""),
            chunk("""{"content":"The bass is fine, "}"""),
            chunk("""{"content":"your earplugs are just working."}"""),
            chunk("{}", finishReason = "\"stop\""),
        )

        assertEquals(AIReplyResult.Reply("The bass is fine, your earplugs are just working."), ask())
    }

    // ---- a reply is whole or absent (class C)

    @Test
    fun `text that stopped at the length limit is not a reply`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"The bass is"}"""), chunk("{}", finishReason = "\"length\""))

        assertEquals(NoReplyReason.TRUNCATED, reason(ask()))
    }

    @Test
    fun `text that was stopped by the content filter is not a reply`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"The bass is"}"""), chunk("{}", finishReason = "\"content_filter\""))

        assertEquals(NoReplyReason.REFUSED, reason(ask()))
    }

    @Test
    fun `a stream that ends without saying the answer is finished is not a reply`() {
        // With the end marker, and without it (the connection simply closes).
        api.answer = stream(chunk("""{"role":"assistant","content":"The bass is"}"""))
        assertEquals(NoReplyReason.TRUNCATED, reason(ask()))

        api.answer = FakeMessagesApi.Answer(
            200, chunk("""{"role":"assistant","content":"The bass is"}"""), headers = mapOf("content-type" to "text/event-stream"),
        )
        assertEquals(NoReplyReason.TRUNCATED, reason(ask()))
    }

    @Test
    fun `a finish reason the app does not know is not a reply`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"The bass"}"""), chunk("{}", finishReason = "\"tool_calls\""))

        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))
    }

    // ---- one attempt, one time limit (class A)

    @Test
    fun `a rate limit is one request, whatever Retry-After says, and ends at once`() {
        api.answer = FakeMessagesApi.Answer(
            429,
            """{"error":{"message":"slow down","type":"rate_limit_error","param":null,"code":null}}""",
            headers = mapOf("retry-after" to "86400"),
        )

        val startedAt = System.nanoTime()
        val result = ask()
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(NoReplyReason.RATE_LIMITED, reason(result))
        assertEquals(1, api.received.size, "the client must not retry")
        assertTrue(waitedMs < 5_000, "waited $waitedMs ms")
    }

    @Test
    fun `a stream that never ends is ended at the time limit`() {
        api.answer = FakeMessagesApi.Answer(
            200, chunk("""{"role":"assistant","content":"The bass is"}"""),
            delayMs = 3_000, headers = mapOf("content-type" to "text/event-stream"),
        )

        val startedAt = System.nanoTime()
        val result = runBlocking { generator(timeout = java.time.Duration.ofMillis(400)).generateReply(AIReplyRequest("s", "u")) }
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(NoReplyReason.TIMEOUT, reason(result))
        assertTrue(waitedMs < 2_000, "waited $waitedMs ms for a 400 ms limit")
    }

    @Test
    fun `without a key nothing is sent`() {
        val result = runBlocking { generator(apiKey = "  ").generateReply(AIReplyRequest("s", "u")) }

        assertEquals(NoReplyReason.MISSING_KEY, reason(result))
        assertTrue(api.received.isEmpty(), "no request may be made without a key")
        assertEquals(1, log.size)
    }

    // ---- nothing of the provider's, and nothing of the client's own, in a log (class B)

    @Test
    fun `what the API writes into an error is not logged and not handed on`() {
        val excerpt = FAKE_KEY.takeLast(6)
        api.answer = FakeMessagesApi.Answer(
            400,
            """{"error":{"message":"Incorrect API key provided: ****$excerpt. do not repeat","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}""",
            headers = mapOf("x-diagnostic" to FAKE_KEY),
        )

        val result = ask()

        val noReply = assertIs<AIReplyResult.NoReply>(result)
        for (text in log + noReply.detail) {
            assertFalse(text.contains(excerpt), text)
            assertFalse(text.contains("do not repeat"), text)
        }
        assertTrue(log.single().contains("HTTP 400 invalid_request_error"), log.single())
    }

    @Test
    fun `an error type the API made up is not copied into the log`() {
        api.answer = FakeMessagesApi.Answer(
            400, """{"error":{"message":"x","type":"made_up_${FAKE_KEY.takeLast(6)}","param":null,"code":null}}""",
        )

        ask()

        assertFalse(log.any { it.contains(FAKE_KEY.takeLast(6)) }, "$log")
    }

    @Test
    fun `the client itself prints nothing - no request line, no header, no key`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"ok"}"""), chunk("{}", finishReason = "\"stop\""))
        val printed = java.io.ByteArrayOutputStream()
        val out = System.out
        val err = System.err
        System.setOut(java.io.PrintStream(printed, true))
        System.setErr(java.io.PrintStream(printed, true))
        try {
            assertEquals(AIReplyResult.Reply("ok"), ask())
        } finally {
            System.setOut(out)
            System.setErr(err)
        }

        val text = printed.toString()
        assertFalse(text.contains(FAKE_KEY), "the key was printed")
        assertFalse(text.contains("Authorization", ignoreCase = true), "headers were printed: $text")
        assertFalse(text.contains("chat/completions"), "the request was printed: $text")
    }

    @Test
    fun `a stream with no text is no reply`() {
        api.answer = stream(chunk("""{"role":"assistant","content":""}"""), chunk("{}", finishReason = "\"stop\""))

        assertEquals(NoReplyReason.EMPTY, assertIs<AIReplyResult.NoReply>(ask()).reason)
    }

    @Test
    fun `a rejected key is no reply, and the key is not logged`() {
        api.answer = FakeMessagesApi.Answer(
            401,
            """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}""",
        )

        assertEquals(NoReplyReason.KEY_REJECTED, assertIs<AIReplyResult.NoReply>(ask()).reason)
        assertFalse(log.any { it.contains(FAKE_KEY) }, "the key was logged: $log")
    }

    @Test
    fun `a request the API rejects is no reply`() {
        api.answer = FakeMessagesApi.Answer(
            400,
            """{"error":{"message":"Unsupported value: temperature","type":"invalid_request_error","param":"temperature","code":null}}""",
        )

        assertEquals(NoReplyReason.REQUEST_REJECTED, assertIs<AIReplyResult.NoReply>(ask()).reason)
    }

    // ---- a reply is ONE answer, whole, with nothing after its end (class E)

    @Test
    fun `one answer is asked for`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"ok"}"""), chunk("{}", finishReason = "\"stop\""))

        ask()

        assertEquals(1, api.received.single().json["n"].asInt)
    }

    @Test
    fun `a second answer in the stream is no reply - its end is not taken for the end of the first`() {
        // The first answer was stopped by the filter; the "stop" belongs to another one.
        api.answer = stream(
            chunk("""{"role":"assistant","content":"The bass is"}"""),
            chunk("{}", finishReason = "\"content_filter\""),
            chunk("""{"role":"assistant","content":"something else"}""", index = 1),
            chunk("{}", finishReason = "\"stop\"", index = 1),
        )
        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))

        // The first answer never ended; the other one did.
        api.answer = stream(
            chunk("""{"role":"assistant","content":"The bass is"}"""),
            chunk("{}", finishReason = "\"stop\"", index = 1),
        )
        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))

        // Two answers that both end well are still not the one answer that was asked for.
        api.answer = stream(
            chunk("""{"role":"assistant","content":"Hydrate."}"""),
            chunk("""{"role":"assistant","content":"Dehydrate."}""", index = 1),
            chunk("{}", finishReason = "\"stop\""),
            chunk("{}", finishReason = "\"stop\"", index = 1),
        )
        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))
    }

    @Test
    fun `text that comes after the end of the answer is no reply`() {
        api.answer = stream(
            chunk("""{"role":"assistant","content":"Hydrate."}"""),
            chunk("{}", finishReason = "\"stop\""),
            chunk("""{"content":" And another thing that was never fin"}"""),
        )

        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))
    }

    @Test
    fun `a second end after the end of the answer is no reply`() {
        api.answer = stream(
            chunk("""{"role":"assistant","content":"Hydrate."}"""),
            chunk("{}", finishReason = "\"stop\""),
            chunk("{}", finishReason = "\"length\""),
        )

        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))
    }

    @Test
    fun `an empty piece after the end, as some servers send it, changes nothing`() {
        api.answer = stream(
            chunk("""{"role":"assistant","content":"Hydrate."}"""),
            chunk("{}", finishReason = "\"stop\""),
            chunk("""{"content":""}"""),
            chunk("{}"),
        )

        assertEquals(AIReplyResult.Reply("Hydrate."), ask())
    }

    // ---- what a failure means (class C)

    @Test
    fun `HTTP 200 with no completion in it is not the API's answer - it may pass, and it is not a cut-off answer`() {
        api.answer = FakeMessagesApi.Answer(200, "<html><body>Sign in to this network</body></html>", headers = mapOf("content-type" to "text/html"))
        assertEquals(NoReplyReason.NOT_A_MESSAGE, reason(ask()))

        // A stream of the API that ended early is something else: that answer was cut off.
        api.answer = stream(chunk("""{"role":"assistant","content":"The bass is"}"""))
        assertEquals(NoReplyReason.TRUNCATED, reason(ask()))
    }

    @Test
    fun `what an error means is decided from its status - a request that cannot be processed is not asked again`() {
        val expected = listOf(
            422 to NoReplyReason.REQUEST_REJECTED,
            404 to NoReplyReason.REQUEST_REJECTED,
            413 to NoReplyReason.REQUEST_REJECTED,
            403 to NoReplyReason.KEY_REJECTED,
            409 to NoReplyReason.PROVIDER_ERROR,
        )
        for ((status, wanted) in expected) {
            api.answer = FakeMessagesApi.Answer(status, """{"error":{"message":"no","type":"some_error","param":null,"code":null}}""")

            assertEquals(wanted, reason(ask()), "HTTP $status")
        }
    }

    @Test
    fun `a query that is not wanted any more is not sent`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"ok"}"""), chunk("{}", finishReason = "\"stop\""))
        var asked = 0

        val result = runBlocking { generator().generateReply(AIReplyRequest("s", "u", stillWanted = { asked++; false })) }

        assertEquals(NoReplyReason.WITHDRAWN, reason(result))
        assertEquals(1, asked)
        assertTrue(api.received.isEmpty(), "the request went out for a query that was deleted")
    }

    // ---- one budget of time for a query (class B)

    @Test
    fun `with too little of the query's time left nothing is sent`() {
        api.answer = stream(chunk("""{"role":"assistant","content":"ok"}"""), chunk("{}", finishReason = "\"stop\""))

        val result = runBlocking {
            generator().generateReply(AIReplyRequest("s", "u", deadline = Deadline.after(java.time.Duration.ofSeconds(5))))
        }

        assertEquals(NoReplyReason.TIMEOUT, reason(result))
        assertTrue(api.received.isEmpty(), "a request went out with 5 s left")
    }

    @Test
    fun `the request gets what is left of the query's time, however long a request may take at most`() {
        api.answer = FakeMessagesApi.Answer(
            200, chunk("""{"role":"assistant","content":"The bass is"}"""),
            delayMs = 6_000, headers = mapOf("content-type" to "text/event-stream"),
        )
        val generator = OpenAIReplyGenerator(
            apiKey = FAKE_KEY, log = { log.add(it) }, requestTimeout = java.time.Duration.ofSeconds(90),
            baseUrl = "${api.baseUrl}/v1/", minTimeToSend = java.time.Duration.ofMillis(100),
        )
        // The first use of the client, which is slow in a fresh JVM, is not part of what is timed.
        api.firstAnswers.add(stream(chunk("""{"role":"assistant","content":"ok"}"""), chunk("{}", finishReason = "\"stop\"")))
        runBlocking { generator.generateReply(AIReplyRequest("s", "u")) }

        val startedAt = System.nanoTime()
        val result = runBlocking {
            generator.generateReply(AIReplyRequest("s", "u", deadline = Deadline.after(java.time.Duration.ofMillis(1_200))))
        }
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(NoReplyReason.TIMEOUT, reason(result))
        assertTrue(waitedMs in 900..3_500, "the request ran $waitedMs ms with 1.2 s of the query's time left")
    }

    @Test
    fun `the HTTP client's own limits are longer than the longest a request may take, so they do not end it, at 30 s or at all`() {
        assertEquals(java.time.Duration.ofSeconds(90), OpenAIReplyGenerator.REQUEST_TIMEOUT)

        // What the client is built from, not a copy of it.
        val transport = generator(timeout = OpenAIReplyGenerator.REQUEST_TIMEOUT).config.timeout

        // Longer than a request may take: the deadline of the query ends a request, not the client.
        assertEquals(100.seconds, transport.request)
        assertEquals(100.seconds, transport.socket)
    }

    // ---- the time (class F)

    @Test
    fun `this client cannot tell the time - it asks the one it was given, with the limit it was given, and nobody when there is none`() {
        val limits = mutableListOf<java.time.Duration>()
        val teller = object : AIReplyGenerator {
            override val name = "teller"
            override suspend fun generateReply(request: AIReplyRequest): AIReplyResult = error("not asked for a reply")
            override fun syncClock(limit: java.time.Duration) {
                limits += limit
            }
        }

        OpenAIReplyGenerator(apiKey = FAKE_KEY, log = {}, baseUrl = "${api.baseUrl}/v1/", timeFrom = teller)
            .syncClock(java.time.Duration.ofSeconds(3))
        generator().syncClock(java.time.Duration.ofSeconds(3))

        assertEquals(listOf(java.time.Duration.ofSeconds(3)), limits)
        assertTrue(api.received.isEmpty(), "asking for the time sends nothing to OpenAI")

        // The warm-up asks for the time too, before anybody waits, and gives it the time a first
        // connection needs; without a teller it does nothing.
        OpenAIReplyGenerator(apiKey = FAKE_KEY, log = {}, baseUrl = "${api.baseUrl}/v1/", timeFrom = teller).warmUp()
        generator().warmUp()
        assertEquals(listOf(java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30)), limits)
        assertTrue(api.received.isEmpty(), "the warm-up buys nothing")
    }

    companion object {
        /** Not a key: a made-up string, so the test can see where the generator puts it. */
        private const val FAKE_KEY = "test-key-not-real"
    }

    // ---- found by the review of round 3

    @Test
    fun `an error whose body is not the API's is judged by its status all the same, in one request`() {
        val html = mapOf("content-type" to "text/html")
        val cases = listOf(
            Triple(401, FakeMessagesApi.Answer(401, "<html>Sign in</html>", headers = html), NoReplyReason.KEY_REJECTED),
            Triple(403, FakeMessagesApi.Answer(403, "Forbidden", headers = mapOf("content-type" to "text/plain")), NoReplyReason.KEY_REJECTED),
            Triple(422, FakeMessagesApi.Answer(422, ""), NoReplyReason.REQUEST_REJECTED),
            Triple(404, FakeMessagesApi.Answer(404, """{"error":"not found"}"""), NoReplyReason.REQUEST_REJECTED),
            Triple(413, FakeMessagesApi.Answer(413, "[]"), NoReplyReason.REQUEST_REJECTED),
            Triple(429, FakeMessagesApi.Answer(429, "<html>Too many</html>", headers = html), NoReplyReason.RATE_LIMITED),
        )
        val generator = generator()
        for ((status, answer, wanted) in cases) {
            api.answer = answer
            log.clear()
            val before = api.received.size

            val result = runBlocking { generator.generateReply(AIReplyRequest("s", "u")) }

            assertEquals(wanted, reason(result), "HTTP $status")
            assertEquals(1, api.received.size - before, "HTTP $status: requests")
            assertTrue(log.single().contains("HTTP $status"), "the status is in the log line: $log")
        }
    }

    @Test
    fun `the status of one request is not taken for the next one's`() {
        val generator = generator()
        api.answer = FakeMessagesApi.Answer(401, "<html>Sign in</html>", headers = mapOf("content-type" to "text/html"))
        assertEquals(NoReplyReason.KEY_REJECTED, reason(runBlocking { generator.generateReply(AIReplyRequest("s", "u")) }))
        log.clear()

        // The next request fails before anything is sent, with something the client knows nothing
        // of. No response came this time: the 401 of the request before says nothing about it.
        val result = runBlocking {
            generator.generateReply(AIReplyRequest("s", "u", stillWanted = { throw IllegalStateException("the owner is gone") }))
        }

        assertEquals(NoReplyReason.PROVIDER_ERROR, reason(result), "a key that was rejected a request ago is not this request's failure")
        assertFalse(log.any { it.contains("401") }, "$log")
        assertEquals(1, api.received.size)
    }

    @Test
    fun `an answer that said stop is the reply also when the end of the stream does not come in time - it is not bought again`() {
        // Everything of the answer has come, "stop" included; the stream is then left open and silent.
        api.answer = FakeMessagesApi.Answer(
            200,
            chunk("""{"role":"assistant","content":"Hydrate."}""") + chunk("{}", finishReason = "\"stop\""),
            headers = mapOf("content-type" to "text/event-stream"), thenStalls = true,
        )

        val startedAt = System.nanoTime()
        val result = runBlocking { generator(timeout = java.time.Duration.ofMillis(1_500)).generateReply(AIReplyRequest("s", "u")) }
        val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(AIReplyResult.Reply("Hydrate."), result)
        assertTrue(waitedMs < 6_000, "waited $waitedMs ms")
    }

    @Test
    fun `an answer that said stop is the reply also when a limit of the HTTP client itself runs out first`() {
        api.answer = FakeMessagesApi.Answer(
            200,
            chunk("""{"role":"assistant","content":"Hydrate."}""") + chunk("{}", finishReason = "\"stop\""),
            headers = mapOf("content-type" to "text/event-stream"), thenStalls = true,
        )
        // The other way round from the app, to show the case: the query has a minute, and the
        // HTTP client gives up after one second.
        val generator = OpenAIReplyGenerator(
            apiKey = FAKE_KEY, log = { log.add(it) }, requestTimeout = java.time.Duration.ofSeconds(60),
            baseUrl = "${api.baseUrl}/v1/", transportMargin = java.time.Duration.ofSeconds(-59),
        )

        val result = runBlocking { generator.generateReply(AIReplyRequest("s", "u")) }

        assertEquals(AIReplyResult.Reply("Hydrate."), result)
    }

    @Test
    fun `text without its stop is still no reply when the time runs out`() {
        api.answer = FakeMessagesApi.Answer(
            200, chunk("""{"role":"assistant","content":"The bass is"}"""),
            headers = mapOf("content-type" to "text/event-stream"), thenStalls = true,
        )

        val result = runBlocking { generator(timeout = java.time.Duration.ofMillis(1_500)).generateReply(AIReplyRequest("s", "u")) }

        assertEquals(NoReplyReason.TIMEOUT, reason(result))
    }

    @Test
    fun `two pieces of an answer in one piece of the stream, both calling themselves the first, are no reply`() {
        val twoInOne = """data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-5.1",""" +
            """"choices":[{"index":0,"delta":{"role":"assistant","content":"Hydrate."},"finish_reason":null},""" +
            """{"index":0,"delta":{"content":" Or do not."},"finish_reason":null}]}""" + "\n\n"
        api.answer = stream(twoInOne, chunk("{}", finishReason = "\"stop\""))

        assertEquals(NoReplyReason.UNEXPECTED_RESPONSE, reason(ask()))
    }
}
