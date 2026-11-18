package cx.aphex.energysign.ai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
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
        assertTrue(warnings.single().contains("opnai"))
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
        assertTrue(warnings.single().contains("turbo"))
    }

    @Test
    fun `without an override file the build setting is the effort`(@TempDir dir: File) {
        val warnings = mutableListOf<String>()

        assertEquals(AIEffort.MEDIUM, AIEffort.forRequest(AIEffort.MEDIUM, File(dir, "ai_effort.txt")) { warnings.add(it) })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a level written to the override file is the effort from the next request on`(@TempDir dir: File) {
        val file = File(dir, "ai_effort.txt")

        file.writeText("low\n")
        assertEquals(AIEffort.LOW, AIEffort.forRequest(AIEffort.HIGH, file) {})
        file.writeText("  MAX  ")
        assertEquals(AIEffort.MAX, AIEffort.forRequest(AIEffort.HIGH, file) {})
        file.delete()
        assertEquals(AIEffort.HIGH, AIEffort.forRequest(AIEffort.HIGH, file) {})
    }

    @Test
    fun `an override file that names no level leaves the build setting, with a warning unless it is empty`(@TempDir dir: File) {
        val file = File(dir, "ai_effort.txt")
        val warnings = mutableListOf<String>()

        file.writeText("banana")
        assertEquals(AIEffort.MEDIUM, AIEffort.forRequest(AIEffort.MEDIUM, file) { warnings.add(it) })
        assertEquals(1, warnings.size)

        file.writeText("")
        assertEquals(AIEffort.MEDIUM, AIEffort.forRequest(AIEffort.MEDIUM, file) { warnings.add(it) })
        assertEquals(1, warnings.size)
    }

    @Test
    fun `each provider gets its own generator`() {
        val anthropic = AIReplyGenerators.forProvider(AIProvider.ANTHROPIC, "a", "o", { AIEffort.DEFAULT }) {}
        val openAi = AIReplyGenerators.forProvider(AIProvider.OPENAI, "a", "o", { AIEffort.DEFAULT }) {}

        assertIs<AnthropicReplyGenerator>(anthropic)
        assertIs<OpenAIReplyGenerator>(openAi)
        assertEquals("anthropic/claude-fable-5-2", anthropic.name)
        assertEquals("openai/gpt-5.1", openAi.name)
    }

    @Test
    fun `the OpenAI generator without a key is no reply too`() {
        val log = mutableListOf<String>()

        val result = runBlocking { OpenAIReplyGenerator(apiKey = "", log = { log.add(it) }).generateReply(AIReplyRequest("s", "u")) }

        assertEquals(NoReplyReason.MISSING_KEY, assertIs<AIReplyResult.NoReply>(result).reason)
        assertEquals(1, log.size)
    }

    // ---- how a result reaches the sign: the two things MessageManager already does ----

    @Test
    fun `a reply is delivered as an answer`() {
        assertEquals(AIReplyDelivery.Answer("Hydrate."), AIReplyResult.Reply("Hydrate.").toDelivery())
    }

    @Test
    fun `a refusal, an empty or cut-off answer and a missing key are quiet - the thinking notice goes and nothing else happens`() {
        for (reason in listOf(
            NoReplyReason.REFUSED, NoReplyReason.EMPTY, NoReplyReason.TRUNCATED,
            NoReplyReason.MISSING_KEY, NoReplyReason.UNEXPECTED_RESPONSE,
        )) {
            assertEquals(AIReplyDelivery.Quiet, AIReplyResult.NoReply(reason).toDelivery(), "$reason")
        }
    }

    @Test
    fun `a failed call is delivered as an error, as it always was`() {
        for (reason in NoReplyReason.values().filter { it.isFailure }) {
            val delivery = assertIs<AIReplyDelivery.Failure>(AIReplyResult.NoReply(reason, "why").toDelivery(), "$reason")
            assertEquals(reason, delivery.error.reason)
        }
        assertTrue(NoReplyReason.TIMEOUT.isFailure)
        assertTrue(NoReplyReason.NETWORK.isFailure)
        assertTrue(NoReplyReason.RATE_LIMITED.isFailure)
        assertTrue(NoReplyReason.CERTIFICATE_CLOCK.isFailure)
    }

    // ---- one request per query at a time ----

    @Test
    fun `a query that is being answered is not asked a second time until the first answer is in`() {
        val inFlight = InFlightQueries()
        val query = Any()

        assertTrue(inFlight.start(query))
        assertEquals(false, inFlight.start(query))
        assertTrue(inFlight.start(Any()), "another query is its own request")
        inFlight.finish(query)
        assertTrue(inFlight.start(query), "after the answer the query may be asked again")
    }

    @Test
    fun `two queries with the same text are two queries`() {
        val inFlight = InFlightQueries()

        assertTrue(inFlight.start(String(charArrayOf('h', 'i'))))
        assertTrue(inFlight.start(String(charArrayOf('h', 'i'))))
    }
}
