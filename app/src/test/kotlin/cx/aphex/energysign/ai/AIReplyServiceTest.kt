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
        var timeChecks = 0
        var onTimeCheck: () -> Unit = {}
        override val name = "fake"
        override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
            requests.add(request)
            return answer(request)
        }

        val timeCheckLimits = mutableListOf<java.time.Duration>()

        override fun syncClock(limit: java.time.Duration) {
            timeChecks++
            timeCheckLimits += limit
            onTimeCheck()
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

    /** The time the service is told, or null for "not known". */
    private var now: Instant? = LocalDateTime.parse("2026-11-20T21:30").atZone(la).toInstant()
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
        now = now!!.plus(Duration.ofMinutes(47))
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
        assertTrue(log.any { it.contains("cannot be right") }, "the log says the time is not believed: $log")
    }

    @Test
    fun `a reply that is too long for the sign is cut before it is handed on, after a whole word`() {
        // 300 words of ten letters and a space: 3,299 characters. The sign takes 700 bytes of
        // JSON for a message, the app allows itself 660, and the JSON around the text takes 21.
        // So 639 characters fit: 58 whole words and their 57 spaces are 637, the 59th does not.
        val long = (1..300).joinToString(" ") { "abcdefghij" }

        val result = runBlocking { service(RecordingGenerator { AIReplyResult.Reply(long) }).replyTo("hello") }

        val reply = assertIs<AIReplyResult.Reply>(result)
        assertEquals((1..58).joinToString(" ") { "abcdefghij" }, reply.text)
        assertEquals(637, reply.text.length)
    }

    @Test
    fun `a reply is one line, whatever the model sends - the saved list is one message a line`() {
        val result = runBlocking {
            service(RecordingGenerator { AIReplyResult.Reply("first line\nsecond\r\nthird\u000Bfourth\tfifth") }).replyTo("hello")
        }

        assertEquals(AIReplyResult.Reply("first line second third fourth fifth"), result)
    }

    @Test
    fun `a reply of which not one whole word fits is no reply`() {
        val result = runBlocking { service(RecordingGenerator { AIReplyResult.Reply("A".repeat(5_000)) }).replyTo("hello") }

        assertEquals(NoReplyReason.EMPTY, assertIs<AIReplyResult.NoReply>(result).reason)
    }

    @Test
    fun `a text with nothing in it is not sent at all`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }

        for (blank in listOf("", "   ", "\n")) {
            val result = runBlocking { service(generator).replyTo(blank) }
            assertEquals(NoReplyReason.NOTHING_TO_ASK, assertIs<AIReplyResult.NoReply>(result).reason)
        }

        assertTrue(generator.requests.isEmpty(), "no request may be made for a blank text")
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
        // The kind of the failure is logged; what a library wrote into its message is not.
        assertTrue(log.any { it.contains("IllegalStateException") }, "$log")
        assertFalse(log.any { it.contains("boom") || it.contains("some/sdk/Class") }, "$log")
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

    // ---- what the prompt says about time is true or absent (class E)

    @Test
    fun `while the time is not known the prompt says nothing about time, and the reply still comes`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        now = null

        val result = runBlocking { service(generator).replyTo("hello") }

        assertEquals(AIReplyResult.Reply("ok"), result)
        val prompt = generator.requests.single().systemPrompt
        assertFalse(prompt.contains("Current local time"), prompt)
        assertFalse(prompt.contains("On stage now"), prompt)
        assertFalse(prompt.contains("Full schedule"), prompt)
        assertTrue(log.any { it.contains("not known") }, "$log")
    }

    @Test
    fun `when the time is not known the provider is asked for it once, before the prompt is made`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        now = null
        generator.onTimeCheck = { now = LocalDateTime.parse("2026-11-20T21:30").atZone(la).toInstant() }

        runBlocking { service(generator).replyTo("first") }
        runBlocking { service(generator).replyTo("second") }

        assertEquals(1, generator.timeChecks, "once the time is known it is not asked for again")
        assertTrue(generator.requests[0].systemPrompt.contains("Current local time: Friday 2026-11-20, 9:30 pm"))
    }

    @Test
    fun `the clock is read after the set times, so slow storage cannot put an old time into the prompt`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val service = AIReplyService(
            generator = generator, log = { log.add(it) }, now = { now }, deviceZone = { la },
            setTimes = {
                // while the table was being read, the set on stage changed
                now = LocalDateTime.parse("2026-11-20T22:00:30").atZone(la).toInstant()
                SetTimesLoad.Loaded(table, "test")
            },
            buildDate = null,
        )

        runBlocking { service.replyTo("hello") }

        val prompt = generator.requests.single().systemPrompt
        assertTrue(prompt.contains("Current local time: Friday 2026-11-20, 10:00 pm"), prompt)
        assertTrue(prompt.contains("- The Dream: Late until 11:00 pm."), prompt)
    }

    @Test
    fun `set times that take too long to read are left behind - the reply goes out without them, in time`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val stuck = java.util.concurrent.CountDownLatch(1)
        val service = AIReplyService(
            generator = generator, log = { log.add(it) }, now = { now }, deviceZone = { la },
            setTimes = {
                stuck.await() // storage that does not answer
                SetTimesLoad.Loaded(table, "test")
            },
            buildDate = null,
            setTimesLimit = Duration.ofMillis(200),
        )

        val startedAt = System.nanoTime()
        val result = runBlocking { service.replyTo("hello") }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        stuck.countDown()

        assertEquals(AIReplyResult.Reply("ok"), result)
        assertTrue(tookMs < 2_000, "the reply waited $tookMs ms for storage")
        val prompt = generator.requests.single().systemPrompt
        assertTrue(prompt.contains("Current local time"), prompt)
        assertFalse(prompt.contains("On stage now"), prompt)
        assertTrue(log.any { it.contains("took longer") }, "$log")
    }

    @Test
    fun `a failure while the context is being built costs the context, not the reply`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val service = AIReplyService(
            generator = generator, log = { log.add(it) }, now = { now },
            deviceZone = { throw java.time.DateTimeException("no such zone") },
            setTimes = { SetTimesLoad.Unusable("none") }, buildDate = null,
        )

        val result = runBlocking { service.replyTo("hello") }

        assertEquals(AIReplyResult.Reply("ok"), result)
        assertFalse(generator.requests.single().systemPrompt.contains("Current local time"))
        assertTrue(log.any { it.contains("goes without it") }, "$log")
    }

    @Test
    fun `a date in the table that no arithmetic can handle does not disable the replies`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        setTimes = SetTimesLoad.Loaded(table.copy(updated = LocalDate.MIN), "test")

        val result = runBlocking { service(generator).replyTo("hello") }

        assertEquals(AIReplyResult.Reply("ok"), result)
        assertTrue(generator.requests.single().systemPrompt.contains("Current local time: Friday 2026-11-20, 9:30 pm"))
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

    // ---- one budget of time for a query, and one read of a file at most (class B)

    @Test
    fun `set times that hang are read by one thread however many queries come - the read is waited for again, not started again`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val threads = java.util.concurrent.atomic.AtomicInteger()
        val stuck = java.util.concurrent.CountDownLatch(1)
        val service = AIReplyService(
            generator = generator, log = { log.add(it) }, now = { now }, deviceZone = { la },
            setTimes = {
                reads.incrementAndGet()
                stuck.await() // storage that does not answer
                SetTimesLoad.Loaded(table, "test")
            },
            buildDate = null,
            setTimesLimit = Duration.ofMillis(100),
            startReadThread = {
                threads.incrementAndGet()
                Thread(it, "test-set-times").apply { isDaemon = true }.start()
            },
        )

        repeat(6) { assertEquals(AIReplyResult.Reply("ok"), runBlocking { service.replyTo("query $it") }) }

        assertEquals(1, reads.get(), "every query started a read of its own")
        assertEquals(1, threads.get(), "one thread hangs in the read, not one a query")
        assertEquals(6, generator.requests.size)
        stuck.countDown()
    }

    @Test
    fun `the query's deadline is the one clock - a step is not waited for longer than the query has left`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        val stuck = java.util.concurrent.CountDownLatch(1)
        val service = AIReplyService(
            generator = generator, log = { log.add(it) }, now = { now }, deviceZone = { la },
            setTimes = {
                stuck.await()
                SetTimesLoad.Loaded(table, "test")
            },
            buildDate = null,
            // The step's own limit is long; the query has much less left.
            setTimesLimit = Duration.ofSeconds(20),
        )
        val deadline = Deadline.after(Duration.ofMillis(400))
        val stillWanted = { true }

        val startedAt = System.nanoTime()
        runBlocking { service.replyTo("hello", deadline, stillWanted) }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        stuck.countDown()

        assertTrue(tookMs in 300..3_000, "the set times were waited for $tookMs ms by a query with 400 ms left")
        // The generator is handed the same deadline and the same question, not ones of its own.
        val request = generator.requests.single()
        assertTrue(request.deadline === deadline, "the request has another deadline than its query")
        assertTrue(request.stillWanted === stillWanted)
    }

    @Test
    fun `the time check is given what the query has left, five seconds at most`() {
        val generator = RecordingGenerator { AIReplyResult.Reply("ok") }
        now = null

        runBlocking { service(generator).replyTo("hello", Deadline.after(Duration.ofSeconds(60))) }
        runBlocking { service(generator).replyTo("hello", Deadline.after(Duration.ofMillis(1_500))) }

        val (plenty, little) = generator.timeCheckLimits
        assertEquals(Duration.ofSeconds(5), plenty)
        assertTrue(little <= Duration.ofMillis(1_500) && little > Duration.ofMillis(500), "the time check was given $little")
    }
}
