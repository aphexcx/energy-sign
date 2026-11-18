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

    private fun ask(system: String = "SYSTEM PROMPT", user: String = "is the bass loud enough"): AIReplyResult =
        runBlocking {
            OpenAIReplyGenerator(apiKey = FAKE_KEY, log = { log.add(it) }, baseUrl = "${api.baseUrl}/v1/")
                .generateReply(AIReplyRequest(system, user))
        }

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
