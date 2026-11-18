package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    private fun chunk(delta: String, finishReason: String = "null"): String =
        """data: {"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"gpt-5.1",""" +
            """"choices":[{"index":0,"delta":$delta,"finish_reason":$finishReason}]}""" + "\n\n"

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

    companion object {
        /** Not a key: a made-up string, so the test can see where the generator puts it. */
        private const val FAKE_KEY = "test-key-not-real"
    }
}
