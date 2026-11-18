package cx.aphex.energysign.ai

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One reply, start to end: the prompt of this moment, the generator, the length cap, and no crash. */
class AIReplyServiceTest {

    private val la: ZoneId = ZoneId.of("America/Los_Angeles")
    private val log = mutableListOf<String>()

    private class RecordingGenerator(var answer: (AIReplyRequest) -> AIReplyResult) : AIReplyGenerator {
        val requests = mutableListOf<AIReplyRequest>()
        override val name = "fake"
        override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
            requests.add(request)
            return answer(request)
        }
    }

    private val table = SetTimesTable(
        event = "Dreamstate SoCal 2026",
        venue = "Queen Mary Waterfront, Long Beach, CA",
        zone = la,
        updated = LocalDate.parse("2026-11-17"),
        sets = listOf(
            FestivalSet("The Dream", "Early", LocalDateTime.parse("2026-11-20T21:00"), LocalDateTime.parse("2026-11-20T22:00")),
            FestivalSet("The Dream", "Late", LocalDateTime.parse("2026-11-20T22:00"), LocalDateTime.parse("2026-11-20T23:00")),
        ),
    )

    private var now: Instant = LocalDateTime.parse("2026-11-20T21:30").atZone(la).toInstant()
    private var setTimes: SetTimesLoad = SetTimesLoad.Loaded(table, "test")

    private fun service(generator: AIReplyGenerator) = AIReplyService(
        generator = generator,
        log = { log.add(it) },
        now = { now },
        deviceZone = { ZoneId.of("UTC") },
        setTimes = { setTimes },
        buildDate = LocalDate.parse("2026-11-18"),
    )

    @Test
    fun `the system prompt carries the time of this request, not of the first one`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val service = service(generator)

        runBlocking { service.replyTo("first") }
        now = now.plus(Duration.ofMinutes(47))
        runBlocking { service.replyTo("second") }

        val (first, second) = generator.requests
        assertTrue(first.systemPrompt.contains("Current local time: Friday 2026-11-20, 9:30 pm"), first.systemPrompt)
        assertTrue(first.systemPrompt.contains("- The Dream: Early until 10:00 pm. Next: Late at 10:00 pm."), first.systemPrompt)
        assertTrue(second.systemPrompt.contains("Current local time: Friday 2026-11-20, 10:17 pm"), second.systemPrompt)
        assertTrue(second.systemPrompt.contains("- The Dream: Late until 11:00 pm. Nothing after that."), second.systemPrompt)
        assertFalse(second.systemPrompt.contains("9:30 pm ("), second.systemPrompt)
        assertEquals(listOf("first", "second"), generator.requests.map { it.userText })
    }

    @Test
    fun `the set times are read again for every request`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val service = service(generator)

        runBlocking { service.replyTo("first") }
        setTimes = SetTimesLoad.Unusable("the file went away")
        runBlocking { service.replyTo("second") }

        assertTrue(generator.requests[0].systemPrompt.contains("On stage now"))
        assertFalse(generator.requests[1].systemPrompt.contains("On stage now"))
        assertTrue(generator.requests[1].systemPrompt.contains("Current local time: Saturday 2026-11-21, 5:30 am (UTC)."))
        assertTrue(log.any { it.contains("the file went away") }, "one log line says why: $log")
    }

    @Test
    fun `a wrong clock sends the prompt with no date, no time and no festival block, and the reply still comes`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        now = Instant.parse("2008-01-01T08:03:00Z")

        val result = runBlocking { service(generator).replyTo("hello") }

        assertEquals(AIReplyResult.Reply("ok"), result)
        val prompt = generator.requests.single().systemPrompt
        assertFalse(prompt.contains("Current local time"), prompt)
        assertFalse(prompt.contains("2008"), prompt)
        assertFalse(prompt.contains("On stage now"), prompt)
        assertTrue(log.any { it.contains("clock", ignoreCase = true) }, "the log says the clock is not believed: $log")
    }

    @Test
    fun `a reply that is too long for the sign is cut before it is handed on`() {
        val long = (1..400).joinToString(" ") { "word$it" }

        val result = runBlocking { service(RecordingGenerator { AIReplyResult.Reply(long) }).replyTo("hello") }

        val reply = assertIs<AIReplyResult.Reply>(result)
        assertEquals(AIReplyLimits.fitToSign(long), reply.text)
        assertTrue(reply.text.length < long.length)
    }

    @Test
    fun `a reply with nothing the sign can show is no reply`() {
        val result = runBlocking { service(RecordingGenerator { AIReplyResult.Reply(" 😀 ") }).replyTo("hello") }

        assertEquals(NoReplyReason.EMPTY, assertIs<AIReplyResult.NoReply>(result).reason)
    }

    @Test
    fun `no reply from the generator is handed on as it is`() {
        val noReply = AIReplyResult.NoReply(NoReplyReason.REFUSED, "declined")

        assertEquals(noReply, runBlocking { service(RecordingGenerator { noReply }).replyTo("hello") })
    }

    @Test
    fun `a generator that throws something unexpected ends as no reply, not as a crash`() {
        val exception = runBlocking { service(RecordingGenerator { throw IllegalStateException("boom") }).replyTo("hello") }
        val error = runBlocking { service(RecordingGenerator { throw NoClassDefFoundError("some/sdk/Class") }).replyTo("hello") }

        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, assertIs<AIReplyResult.NoReply>(exception).reason)
        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, assertIs<AIReplyResult.NoReply>(error).reason)
        assertTrue(log.any { it.contains("boom") }, "$log")
    }

    @Test
    fun `when the caller is cancelled the cancellation goes through and nothing is returned`() {
        val waitsForEver = object : AIReplyGenerator {
            override val name = "fake"
            override suspend fun generateReply(request: AIReplyRequest): AIReplyResult = awaitCancellation()
        }
        var returned: AIReplyResult? = null

        val job = runBlocking {
            val asking = launch { returned = service(waitsForEver).replyTo("hello") }
            yield()
            asking.cancelAndJoin()
            asking
        }

        assertTrue(job.isCancelled)
        assertNull(returned)
    }

    @Test
    fun `a cancellation that is not the caller's is a failure like any other, so the sign is always told`() {
        // If a library throws one while nobody cancelled us, letting it through would end the
        // coroutine without a word, and the thinking notice would stay on the sign.
        val result = runBlocking {
            service(RecordingGenerator { throw CancellationException("from inside a library") }).replyTo("hello")
        }

        assertEquals(NoReplyReason.UNEXPECTED_FAILURE, assertIs<AIReplyResult.NoReply>(result).reason)
    }

    @Test
    fun `a key never reaches the log or the result, whatever a library puts in its message`() {
        val secrets = Secrets(listOf("made-up-key-12345"))
        fun serviceWith(generator: AIReplyGenerator) = AIReplyService(
            generator = generator, log = secrets.sink { log.add(it) }, redact = secrets::redact,
            now = { now }, deviceZone = { la }, setTimes = { setTimes }, buildDate = null,
        )

        val thrown = runBlocking {
            serviceWith(RecordingGenerator {
                throw IllegalArgumentException("Unexpected char 0x201c at 0 in X-Api-Key value: made-up-key-12345")
            }).replyTo("hello")
        }
        val returned = runBlocking {
            serviceWith(RecordingGenerator {
                AIReplyResult.NoReply(NoReplyReason.KEY_REJECTED, "the server said: bad key made-up-key-12345")
            }).replyTo("hello")
        }

        for (result in listOf(thrown, returned)) {
            assertFalse(assertIs<AIReplyResult.NoReply>(result).detail.contains("made-up-key"), "$result")
        }
        assertTrue(log.isNotEmpty())
        assertFalse(log.any { it.contains("made-up-key") }, "$log")
    }

    @Test
    fun `set times that cannot be read never fail the reply`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val service = AIReplyService(
            generator = generator,
            log = { log.add(it) },
            now = { now },
            deviceZone = { la },
            setTimes = { throw java.io.IOException("disk on fire") },
            buildDate = null,
        )

        assertEquals(AIReplyResult.Reply("ok"), runBlocking { service.replyTo("hello") })
        assertTrue(generator.requests.single().systemPrompt.contains("Current local time: Friday 2026-11-20, 9:30 pm"))
    }
}
