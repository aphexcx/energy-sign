package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The one setting that picks the provider, and how each result reaches the sign. */
class AIProviderTest {

    @Test
    fun `anthropic is the provider when the setting is absent or blank`() {
        assertEquals(AIProvider.ANTHROPIC, AIProvider.fromSetting(null))
        assertEquals(AIProvider.ANTHROPIC, AIProvider.fromSetting(""))
        assertEquals(AIProvider.ANTHROPIC, AIProvider.fromSetting("   "))
    }

    @Test
    fun `the setting names the provider, in any case`() {
        assertEquals(AIProvider.ANTHROPIC, AIProvider.fromSetting("anthropic"))
        assertEquals(AIProvider.OPENAI, AIProvider.fromSetting("openai"))
        assertEquals(AIProvider.OPENAI, AIProvider.fromSetting(" OpenAI "))
    }

    @Test
    fun `a setting that names no provider is anthropic, with a warning`() {
        val warnings = mutableListOf<String>()

        assertEquals(AIProvider.ANTHROPIC, AIProvider.fromSetting("opnai") { warnings.add(it) })
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("AI_PROVIDER"))
    }

    // ---- the effort: a build setting, and a file on the device that overrides it ----

    @Test
    fun `the effort is high when the setting is absent or blank`() {
        assertEquals(AIEffort.HIGH, AIEffort.DEFAULT)
        assertEquals(AIEffort.HIGH, AIEffort.fromSetting(null))
        assertEquals(AIEffort.HIGH, AIEffort.fromSetting(""))
        assertEquals(AIEffort.HIGH, AIEffort.fromSetting("  "))
    }

    @Test
    fun `the setting names the effort level, in any case`() {
        assertEquals(AIEffort.LOW, AIEffort.fromSetting("low"))
        assertEquals(AIEffort.MEDIUM, AIEffort.fromSetting("medium"))
        assertEquals(AIEffort.HIGH, AIEffort.fromSetting("high"))
        assertEquals(AIEffort.XHIGH, AIEffort.fromSetting(" XHigh "))
        assertEquals(AIEffort.MAX, AIEffort.fromSetting("max"))
    }

    @Test
    fun `a setting that names no level is high, with one warning`() {
        val warnings = mutableListOf<String>()

        assertEquals(AIEffort.HIGH, AIEffort.fromSetting("turbo") { warnings.add(it) })
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("AI_EFFORT"))
    }

    @Test
    fun `without an override file the build setting is the effort`(@TempDir dir: File) {
        val warnings = mutableListOf<String>()

        assertEquals(AIEffort.MEDIUM, AIEffort.fromOverrideFile(AIEffort.MEDIUM, File(dir, "ai_effort.txt")) { warnings.add(it) })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a level written to the override file is the effort from the next request on`(@TempDir dir: File) {
        val file = File(dir, "ai_effort.txt")

        file.writeText("low\n")
        assertEquals(AIEffort.LOW, AIEffort.fromOverrideFile(AIEffort.HIGH, file) {})
        file.writeText("  MAX  ")
        assertEquals(AIEffort.MAX, AIEffort.fromOverrideFile(AIEffort.HIGH, file) {})
        file.delete()
        assertEquals(AIEffort.HIGH, AIEffort.fromOverrideFile(AIEffort.HIGH, file) {})
    }

    @Test
    fun `an override file that names no level leaves the build setting, with a warning unless it is empty`(@TempDir dir: File) {
        val file = File(dir, "ai_effort.txt")
        val warnings = mutableListOf<String>()

        file.writeText("banana")
        assertEquals(AIEffort.MEDIUM, AIEffort.fromOverrideFile(AIEffort.MEDIUM, file) { warnings.add(it) })
        assertEquals(1, warnings.size)

        file.writeText("")
        assertEquals(AIEffort.MEDIUM, AIEffort.fromOverrideFile(AIEffort.MEDIUM, file) { warnings.add(it) })
        assertEquals(1, warnings.size)
    }

    @Test
    fun `each provider gets its own generator`() {
        val budget = AIHourlyBudget({ 0L })
        val anthropic = AIReplyGenerators.forProvider(AIProvider.ANTHROPIC, "a", "o", { AIEffort.DEFAULT }, {}, budget = budget)
        val openAi = AIReplyGenerators.forProvider(AIProvider.OPENAI, "a", "o", { AIEffort.DEFAULT }, {}, budget = budget)

        assertIs<AnthropicReplyGenerator>(anthropic)
        assertIs<OpenAIReplyGenerator>(openAi)
        assertEquals("anthropic/claude-fable-5-2", anthropic.name)
        assertEquals("openai/gpt-5.1", openAi.name)
    }

    @Test
    fun `on the OpenAI path the time comes from the Anthropic API when this build has its key, and from nowhere when it has not`() {
        FakeMessagesApi().use { anthropicApi ->
            anthropicApi.answer = FakeMessagesApi.Answer(
                200,
                """{"id":"claude-fable-5-2","type":"model","display_name":"Claude Fable 5.2","created_at":"2026-01-01T00:00:00Z"}""",
                headers = mapOf("date" to "Sat, 21 Nov 2026 05:30:00 GMT"),
            )
            fun openAi(anthropicKey: String, clock: ServerClock) = AIReplyGenerators.forProvider(
                provider = AIProvider.OPENAI,
                anthropicApiKey = anthropicKey,
                openAiApiKey = "openai-key-made-up",
                effort = { AIEffort.DEFAULT },
                log = {},
                serverClock = clock,
                budget = AIHourlyBudget({ 0L }),
                anthropicBaseUrl = anthropicApi.baseUrl,
                openAiBaseUrl = "http://127.0.0.1:9/v1/",
            )

            val withoutKey = ServerClock { 0L }
            openAi(anthropicKey = "  ", clock = withoutKey).syncClock(java.time.Duration.ofSeconds(5))
            assertEquals(null, withoutKey.now(), "with no Anthropic key the time stays unknown")
            assertTrue(anthropicApi.received.isEmpty())

            val withKey = ServerClock { 0L }
            openAi(anthropicKey = "anthropic-key-made-up", clock = withKey).syncClock(java.time.Duration.ofSeconds(5))
            assertEquals(java.time.Instant.parse("2026-11-21T05:30:00Z"), withKey.now())
            val request = anthropicApi.received.single()
            assertTrue(request.path.startsWith("/v1/models/"), "the free request, not a reply: ${request.path}")
            assertEquals(emptyList(), request.headersContaining("openai-key-made-up"))
        }
    }

    @Test
    fun `each provider is sent its own key and never the other one`() {
        FakeMessagesApi().use { anthropicApi ->
            FakeMessagesApi().use { openAiApi ->
                anthropicApi.answer = FakeMessagesApi.Answer(401, FakeMessagesApi.error("authentication_error", "no"))
                openAiApi.answer = FakeMessagesApi.Answer(401, """{"error":{"message":"no","type":"invalid_request_error"}}""")
                fun generator(provider: AIProvider) = AIReplyGenerators.forProvider(
                    provider = provider,
                    anthropicApiKey = "anthropic-key-made-up",
                    openAiApiKey = "openai-key-made-up",
                    effort = { AIEffort.DEFAULT },
                    log = {},
                    budget = AIHourlyBudget({ 0L }),
                    anthropicBaseUrl = anthropicApi.baseUrl,
                    openAiBaseUrl = "${openAiApi.baseUrl}/v1/",
                )

                runBlocking { generator(AIProvider.ANTHROPIC).generateReply(AIReplyRequest("s", "u")) }
                assertTrue(openAiApi.received.isEmpty(), "choosing anthropic must not reach openai")
                runBlocking { generator(AIProvider.OPENAI).generateReply(AIReplyRequest("s", "u")) }

                val toAnthropic = anthropicApi.received.single()
                val toOpenAi = openAiApi.received.single()
                assertEquals(listOf("authorization"), toAnthropic.headersContaining("anthropic-key-made-up"))
                assertEquals(emptyList(), toAnthropic.headersContaining("openai-key-made-up"))
                assertEquals(listOf("authorization"), toOpenAi.headersContaining("openai-key-made-up"))
                assertEquals(emptyList(), toOpenAi.headersContaining("anthropic-key-made-up"))
            }
        }
    }

    // ---- no setting's VALUE is ever logged (class B): it may be anything, a key included

    @Test
    fun `a wrong setting is reported without the value it holds`() {
        val key = "made-up-key-0123456789-0123456789"
        val warnings = mutableListOf<String>()

        AIProvider.fromSetting(key) { warnings.add(it) }
        AIEffort.fromSetting(key) { warnings.add(it) }

        assertEquals(2, warnings.size)
        assertFalse(warnings.any { it.contains("made-up-key") || it.contains(key.take(8)) }, "$warnings")
    }

    @Test
    fun `a wrong effort file is reported without anything of what it holds`(@TempDir dir: File) {
        val key = "made-up-key-0123456789-0123456789"
        val file = File(dir, "ai_effort.txt").apply { writeText(key) }
        val warnings = mutableListOf<String>()

        assertEquals(AIEffort.MEDIUM, AIEffort.fromOverrideFile(AIEffort.MEDIUM, file) { warnings.add(it) })

        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains("made-up") || warnings.single().contains(key.take(6)), warnings.single())
    }

    @Test
    fun `an effort file too big to be a level name is not read at all`(@TempDir dir: File) {
        val file = File(dir, "ai_effort.txt").apply { writeText("low" + " ".repeat(10_000)) }
        val warnings = mutableListOf<String>()

        assertEquals(AIEffort.HIGH, AIEffort.fromOverrideFile(AIEffort.HIGH, file) { warnings.add(it) })
        assertEquals(1, warnings.size)
    }
}
