package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * When a query is asked, what the sign shows meanwhile, and what becomes of the result: driven
 * by hand, with a clock that only moves when the test says so.
 */
class AIQueryDeskTest {

    /** A query is the object, not its text: two with the same text are two. */
    private class Query(val text: String) {
        override fun toString() = text
    }

    private val sent = mutableListOf<Query>()
    private val list = mutableListOf<Query>()
    private var now = 1_000_000L

    private fun query(text: String) = Query(text).also { list.add(it) }

    /** How often the desk went through the list of messages. */
    private var passes = 0
    private val said = mutableListOf<String>()
    private var logFails = false

    private fun desk(warmingUp: Boolean = false, limits: AIQueryDesk.Limits = AIQueryDesk.Limits()) =
        AIQueryDesk<Query>(
            start = { sent.add(it) },
            listedAmong = { known ->
                passes++
                list.filter { it in known }.toSet()
            },
            log = { if (logFails) throw IllegalStateException("the log failed") else said.add(it) },
            limits = limits,
            warmingUp = warmingUp,
        )

    private val reply = AIReplyResult.Reply("HYDRATE.")
    private fun noReply(reason: NoReplyReason) = AIReplyResult.NoReply(reason)

    // ---- one call at a time (class A)

    @Test
    fun `a query that is shown is sent once, and the notice goes up`() {
        val desk = desk()
        val q = query("is the bass loud enough")

        assertTrue(desk.shown(q, now))

        assertEquals(listOf(q), sent)
        assertTrue(desk.thinkingWanted(now))
    }

    @Test
    fun `a query whose call is out is not sent again, however often it comes round`() {
        val desk = desk()
        val q = query("q")
        desk.shown(q, now)

        repeat(5) {
            now += 20_000
            assertFalse(desk.shown(q, now), "no second notice for a query that is being answered")
        }

        assertEquals(1, sent.size)
    }

    @Test
    fun `while a call is out a second query waits its turn, and is sent when the first has ended`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)

        now += 2_000
        assertTrue(desk.shown(b, now), "the second query gets its notice while it waits")
        assertEquals(listOf(a), sent, "only one call is out")

        now += 3_000
        assertIs<AIQueryDesk.Outcome.Reply>(desk.finished(a, reply, now))
        assertEquals(listOf(a, b), sent)
        assertTrue(desk.thinkingWanted(now), "the notice stays for the query that was just sent")
    }

    @Test
    fun `a query that stands in line is not put in line twice`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        desk.shown(b, now)

        assertFalse(desk.shown(b, now + 1_000))

        desk.finished(a, reply, now + 2_000)
        desk.finished(b, reply, now + 3_000)
        assertEquals(listOf(a, b), sent)
    }

    @Test
    fun `a waiting query whose notice has come down is never sent, it cost nothing`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        desk.shown(b, now)

        now += 31_000 // b waited longer than the sign says "thinking"
        assertFalse(desk.thinkingWanted(now))
        desk.finished(a, reply, now)

        assertEquals(listOf(a), sent, "b's turn came too late: it was not sent")
        now += 60_000
        assertTrue(desk.shown(b, now), "it is asked when it comes round again")
        assertEquals(listOf(a, b), sent)
    }

    @Test
    fun `a waiting query that was deleted is not sent`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        desk.shown(b, now)
        list.remove(b)

        desk.finished(a, reply, now + 1_000)

        assertEquals(listOf(a), sent)
        assertFalse(desk.thinkingWanted(now + 1_000), "the notice for the deleted query comes down")
    }

    @Test
    fun `the line has an end`() {
        val desk = desk(limits = AIQueryDesk.Limits(maxWaiting = 2))
        desk.shown(query("out"), now)

        assertTrue(desk.shown(query("first in line"), now))
        assertTrue(desk.shown(query("second in line"), now))
        assertFalse(desk.shown(query("one too many"), now))
    }

    @Test
    fun `during the warm-up nothing is sent, the first query waits and goes out when it is over`() {
        val desk = desk(warmingUp = true)
        val q = query("q")

        assertTrue(desk.shown(q, now))
        assertTrue(sent.isEmpty())

        now += 10_000
        desk.warmedUp(now)
        assertEquals(listOf(q), sent)
    }

    // ---- the time limit is for the notice, not for the call (requirement b)

    @Test
    fun `when the limit passes the notice comes down and the call stays out`() {
        val desk = desk()
        val q = query("q")
        desk.shown(q, now)

        assertTrue(desk.thinkingWanted(now + 29_999))
        assertFalse(desk.thinkingWanted(now + 30_000))

        assertFalse(desk.shown(q, now + 45_000), "it is not bought again while its call is out")
        assertEquals(1, sent.size)
    }

    @Test
    fun `a reply that comes after the notice came down is delivered all the same`() {
        val desk = desk()
        val q = query("q")
        desk.shown(q, now)
        assertFalse(desk.thinkingWanted(now + 31_000))

        val outcome = desk.finished(q, reply, now + 65_000)

        assertEquals(AIQueryDesk.Outcome.Reply("HYDRATE."), outcome)
    }

    @Test
    fun `a reply for a query that was deleted meanwhile is dropped`() {
        val desk = desk()
        val q = query("q")
        desk.shown(q, now)
        list.remove(q)

        assertSame(AIQueryDesk.Outcome.Gone, desk.finished(q, reply, now + 5_000))
    }

    @Test
    fun `a result for a call that is not out is not acted on`() {
        val desk = desk()
        val q = query("q")
        val other = query("other")
        desk.shown(q, now)

        assertSame(AIQueryDesk.Outcome.NotOurs, desk.finished(other, reply, now))
        desk.finished(q, reply, now)
        assertSame(AIQueryDesk.Outcome.NotOurs, desk.finished(q, reply, now), "a result that arrives twice counts once")
    }

    @Test
    fun `the notice belongs to the query shown last, an earlier reply does not take it down`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        now += 25_000
        desk.shown(b, now) // waits; the notice is b's now, for 30 s from here

        now += 10_000 // a's own 30 s are over, b's are not
        desk.finished(a, reply, now)

        assertTrue(desk.thinkingWanted(now))
    }

    // ---- a query that gets an answer stops being a query (requirement c)

    @Test
    fun `a refusal, an empty or cut-off answer, a rejected request and a missing key retire the query at once`() {
        for (reason in listOf(
            NoReplyReason.REFUSED, NoReplyReason.EMPTY, NoReplyReason.TRUNCATED, NoReplyReason.UNEXPECTED_RESPONSE,
            NoReplyReason.KEY_REJECTED, NoReplyReason.REQUEST_REJECTED, NoReplyReason.MISSING_KEY, NoReplyReason.NOTHING_TO_ASK,
        )) {
            val desk = desk()
            val q = query("q $reason")
            desk.shown(q, now)

            assertEquals(AIQueryDesk.Outcome.Retired(reason), desk.finished(q, noReply(reason), now + 1_000), "$reason")
            assertFalse(reason.askAgain, "$reason")
        }
    }

    @Test
    fun `a failure that may pass leaves the query a query, three calls at most, then it is retired`() {
        val desk = desk()
        val q = query("q")

        desk.shown(q, now)
        assertEquals(
            AIQueryDesk.Outcome.Failed(NoReplyReason.NETWORK, whileWatched = true),
            desk.finished(q, noReply(NoReplyReason.NETWORK), now + 1_000),
        )

        now += 10 * 60_000
        assertTrue(desk.shown(q, now))
        assertIs<AIQueryDesk.Outcome.Failed>(desk.finished(q, noReply(NoReplyReason.RATE_LIMITED), now + 1_000))

        now += 10 * 60_000
        assertTrue(desk.shown(q, now))
        assertEquals(
            AIQueryDesk.Outcome.Retired(NoReplyReason.SERVER_ERROR),
            desk.finished(q, noReply(NoReplyReason.SERVER_ERROR), now + 1_000),
        )
        assertEquals(3, sent.size)
    }

    @Test
    fun `a failure after the notice came down is not shown to anybody`() {
        val desk = desk()
        val q = query("q")
        desk.shown(q, now)
        desk.thinkingWanted(now + 31_000)

        assertEquals(
            AIQueryDesk.Outcome.Failed(NoReplyReason.TIMEOUT, whileWatched = false),
            desk.finished(q, noReply(NoReplyReason.TIMEOUT), now + 90_000),
        )
    }

    // ---- after a failure nothing is asked for a while (the bound on calls an hour)

    @Test
    fun `after a failure nothing is asked for a while, and no notice goes up`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        desk.finished(a, noReply(NoReplyReason.NETWORK), now)

        assertFalse(desk.shown(b, now + 29_999))
        assertFalse(desk.thinkingWanted(now + 29_999))
        assertEquals(1, sent.size)

        assertTrue(desk.shown(b, now + 30_000))
        assertEquals(listOf(a, b), sent)
    }

    @Test
    fun `the pause grows with every failure in a row and has a longest value`() {
        val desk = desk()
        val sentAt = mutableListOf<Long>()
        // A new query is shown every second, and every call fails at once.
        while (sentAt.size < 8) {
            val q = query("q")
            if (desk.shown(q, now)) {
                sentAt.add(now)
                desk.finished(q, noReply(NoReplyReason.SERVER_ERROR), now)
            }
            now += 1_000
        }

        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L),
            sentAt.zipWithNext { earlier, later -> later - earlier },
        )
    }

    @Test
    fun `when every call fails, the pauses alone allow at most 15 calls in an hour, however many queries there are`() {
        for (reason in listOf(NoReplyReason.NETWORK, NoReplyReason.RATE_LIMITED, NoReplyReason.SERVER_ERROR, NoReplyReason.KEY_REJECTED)) {
            sent.clear()
            list.clear()
            val desk = desk()
            val start = now
            // A new query is shown every second for an hour, and every call fails at once.
            while (now < start + 3_600_000) {
                val q = query("q")
                if (desk.shown(q, now)) desk.finished(q, noReply(reason), now)
                now += 1_000
            }

            assertTrue(sent.size <= 15, "$reason: ${sent.size} calls in an hour")
            assertTrue(sent.size >= 10, "$reason: only ${sent.size} calls, the pause is longer than meant")
        }
    }

    @Test
    fun `an answer of the model, a reply or not, starts the count of failures afresh`() {
        for (answer in listOf<AIReplyResult>(reply, noReply(NoReplyReason.REFUSED), noReply(NoReplyReason.EMPTY))) {
            val desk = desk()
            val a = query("a")
            desk.shown(a, now)
            desk.finished(a, noReply(NoReplyReason.NETWORK), now) // the first failure: a pause of 30 s
            now += 30_000
            val b = query("b")
            assertTrue(desk.shown(b, now))
            desk.finished(b, answer, now) // the provider answers again
            assertTrue(desk.shown(query("c"), now), "after $answer the next query is asked at once")
            desk.finished(sent.last(), noReply(NoReplyReason.NETWORK), now)

            // This failure is the first in a row again: 30 s, not the 60 s of a second one.
            assertFalse(desk.shown(query("d"), now + 29_999), "after $answer")
            assertTrue(desk.shown(query("d"), now + 30_000), "after $answer")
            now += 3_600_000
        }
    }

    @Test
    fun `a failure sends nobody who stands in line, and their notice comes down`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        desk.shown(b, now + 1_000)

        desk.finished(a, noReply(NoReplyReason.NETWORK), now + 2_000)

        assertEquals(listOf(a), sent)
        assertFalse(desk.thinkingWanted(now + 2_000))
    }

    // ---- a budget of calls an hour that nothing gives back (class C)

    @Test
    fun `the limits the rules were agreed with are the defaults`() {
        val limits = AIQueryDesk.Limits()

        assertEquals(30_000L, limits.noticeMs)
        assertEquals(3, limits.maxAttempts)
        assertEquals(4, limits.maxWaiting)
        assertEquals(45, limits.callsPerHour)
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L), limits.coolDownsMs)
    }

    @Test
    fun `failure and answer in turn buy no more than the budget in any hour - an answer gives nothing back`() {
        // Every second call fails and every other one is answered, so the pause after a failure
        // is always the shortest one: by the pauses alone this is a call every 31 s, 116 an hour.
        val desk = desk()
        val start = now
        var turn = 0
        while (now < start + 3_600_000) {
            val q = query("q")
            if (desk.shown(q, now)) desk.finished(q, if (turn++ % 2 == 0) noReply(NoReplyReason.SERVER_ERROR) else reply, now)
            now += 31_000
        }

        assertEquals(45, sent.size, "calls in the first hour")

        // The hour moves on: each call that becomes an hour old makes room for one more, and no more.
        val secondHourStart = now
        while (now < secondHourStart + 3_600_000) {
            val q = query("q")
            if (desk.shown(q, now)) desk.finished(q, if (turn++ % 2 == 0) noReply(NoReplyReason.SERVER_ERROR) else reply, now)
            now += 31_000
        }
        assertEquals(90, sent.size, "calls in two hours")
    }

    @Test
    fun `no hour, wherever it starts, holds more calls than the budget`() {
        val desk = desk(limits = AIQueryDesk.Limits(callsPerHour = 5))
        val sentAt = mutableListOf<Long>()
        val start = now
        // Answers only, as fast as queries come: nothing but the budget holds these back.
        while (now < start + 3 * 3_600_000) {
            val q = query("q")
            if (desk.shown(q, now)) {
                sentAt += now
                desk.finished(q, reply, now)
            }
            now += 7_000
        }

        assertTrue(sentAt.size >= 15, "the budget must come back: ${sentAt.size} calls in three hours")
        for (from in sentAt) {
            val inThatHour = sentAt.count { it >= from && it < from + 3_600_000 }
            assertTrue(inThatHour <= 5, "$inThatHour calls in the hour from $from")
        }
    }

    @Test
    fun `while the budget is spent a query gets no notice and no call, and the log says so once`() {
        val desk = desk(limits = AIQueryDesk.Limits(callsPerHour = 2))
        val first = now
        for (text in listOf("a", "b")) {
            val q = query(text)
            assertTrue(desk.shown(q, now))
            desk.finished(q, reply, now)
            now += 1_000
        }

        val late = query("c")
        repeat(3) {
            assertFalse(desk.shown(late, now), "the budget is spent")
            assertFalse(desk.thinkingWanted(now), "no notice for a query that is not asked")
            now += 60_000
        }
        assertEquals(2, sent.size)
        assertEquals(1, said.count { it.contains("in the last hour") }, "$said")

        now = first + 3_600_000 - 1
        assertFalse(desk.shown(late, now), "the first call is not an hour old yet")
        now = first + 3_600_000
        assertTrue(desk.shown(late, now), "the first call is an hour old: there is room for one")
        assertEquals(3, sent.size)
    }

    @Test
    fun `a query in line is not sent when the budget is spent by the time its turn comes`() {
        val desk = desk(warmingUp = true, limits = AIQueryDesk.Limits(callsPerHour = 1))
        val a = query("a")
        val b = query("b")
        assertTrue(desk.shown(a, now))
        assertTrue(desk.shown(b, now + 1))

        desk.warmedUp(now + 2)
        assertEquals(listOf(a), sent)
        desk.finished(a, reply, now + 3)

        assertEquals(listOf(a), sent, "b would be the second call of the hour")
        assertFalse(desk.thinkingWanted(now + 4), "b left the line: its notice comes down")
    }

    @Test
    fun `a call counts against the budget whatever becomes of it - deleted, failed or never answered by a model`() {
        val desk = desk(limits = AIQueryDesk.Limits(callsPerHour = 3, coolDownsMs = listOf(0)))
        val gone = query("gone")
        desk.shown(gone, now)
        list.remove(gone)
        assertEquals(AIQueryDesk.Outcome.Gone, desk.finished(gone, reply, now))

        val failed = query("failed")
        desk.shown(failed, now + 1)
        desk.finished(failed, noReply(NoReplyReason.NETWORK), now + 2)

        val noKey = query("no key")
        desk.shown(noKey, now + 3)
        desk.finished(noKey, noReply(NoReplyReason.MISSING_KEY), now + 4)

        assertFalse(desk.shown(query("fourth"), now + 5))
        assertEquals(3, sent.size)
    }

    // ---- the log is not part of the rules (class C, a minor of the deep gate)

    @Test
    fun `a log that fails leaves nothing half done - the result is dealt with and whoever waits is seen to`() {
        val desk = desk()
        val a = query("a")
        val b = query("b")
        desk.shown(a, now)
        desk.shown(b, now + 1)
        logFails = true

        val outcome = desk.finished(a, noReply(NoReplyReason.KEY_REJECTED), now + 2)

        assertEquals(AIQueryDesk.Outcome.Retired(NoReplyReason.KEY_REJECTED), outcome)
        // b was in line behind a failure: it left the line unsent, and is free to be asked after the pause.
        assertEquals(listOf(a), sent)
        now += 30_002
        assertTrue(desk.shown(b, now), "b is not stuck in a line nobody drains")
        assertEquals(listOf(a, b), sent)

        // The other two lines the desk writes: the notice that came down, and the budget.
        now += 30_000
        assertFalse(desk.thinkingWanted(now))
        val small = desk(limits = AIQueryDesk.Limits(callsPerHour = 0))
        assertFalse(small.shown(query("c"), now))
    }

    // ---- a query that waited too long is not in the way (minors of both gates)

    @Test
    fun `a query whose wait ran out is put in line again when it comes round, and is sent when the warm-up ends`() {
        val desk = desk(warmingUp = true)
        val q = query("asked at the start")
        assertTrue(desk.shown(q, now))

        now += 59_000 // its 30 s are long over; the warm-up still runs
        assertTrue(desk.shown(q, now), "the old place in line must not turn it away")

        now += 6_000
        desk.warmedUp(now)
        assertEquals(listOf(q), sent)
    }

    @Test
    fun `those who waited too long hold no place in the line`() {
        val desk = desk(warmingUp = true)
        repeat(4) { assertTrue(desk.shown(query("early $it"), now + it)) }
        assertFalse(desk.shown(query("one too many"), now + 10), "the line has four places")

        now += 31_000
        val fresh = query("fresh")
        assertTrue(desk.shown(fresh, now), "the four have waited out their time")

        desk.warmedUp(now + 1)
        assertEquals(listOf(fresh), sent)
    }

    // ---- what a result costs the owner of the messages (class D)

    @Test
    fun `the end of a call is one pass over the list, however many queries the desk keeps count of and however many wait`() {
        val desk = desk(limits = AIQueryDesk.Limits(callsPerHour = 1_000, coolDownsMs = listOf(0)))
        // A hundred queries that failed once: each stays a query, and the desk counts its calls.
        repeat(100) {
            val q = query("failed once $it")
            desk.shown(q, now)
            desk.finished(q, noReply(NoReplyReason.NETWORK), now)
            now += 1
        }
        // And a full line behind the call that is out.
        val out = query("out")
        desk.shown(out, now)
        repeat(4) { desk.shown(query("waiting $it"), now) }

        passes = 0
        desk.finished(out, reply, now + 1)

        assertEquals(1, passes, "passes over the list for one result")
    }

    @Test
    fun `showing a query and asking about the notice cost no pass over the list, and the end of the warm-up costs one`() {
        val desk = desk(warmingUp = true)
        repeat(3) { desk.shown(query("waiting $it"), now) }
        desk.thinkingWanted(now + 1)
        assertEquals(0, passes)

        desk.warmedUp(now + 2)

        assertEquals(1, passes)
    }

    @Test
    fun `what the desk knows of a query is forgotten when the query is gone from the list`() {
        val desk = desk(limits = AIQueryDesk.Limits(coolDownsMs = listOf(0)))
        val q = query("fails")
        repeat(2) {
            desk.shown(q, now)
            desk.finished(q, noReply(NoReplyReason.NETWORK), now)
        }
        // Deleted; another result comes in, and with it the look at the list.
        list.remove(q)
        val other = query("other")
        desk.shown(other, now)
        desk.finished(other, reply, now)

        // The same message object is in the list again (nothing in the app does this; it shows what was kept).
        list.add(q)
        repeat(2) {
            desk.shown(q, now)
            assertIs<AIQueryDesk.Outcome.Failed>(desk.finished(q, noReply(NoReplyReason.NETWORK), now), "its count started afresh")
        }
    }

    @Test
    fun `a result for which nothing was sent says nothing about the provider - the row of failures stands`() {
        for (notSent in listOf(NoReplyReason.WITHDRAWN, NoReplyReason.MISSING_KEY, NoReplyReason.NOTHING_TO_ASK)) {
            sent.clear()
            val desk = desk()
            val a = query("a")
            desk.shown(a, now)
            desk.finished(a, noReply(NoReplyReason.NETWORK), now) // the first failure: a pause of 30 s
            now += 30_000
            val b = query("b")
            assertTrue(desk.shown(b, now))
            desk.finished(b, noReply(notSent), now) // no request went out: no answer of the provider
            val c = query("c")
            assertTrue(desk.shown(c, now), "$notSent is no failure of the provider: no pause")
            desk.finished(c, noReply(NoReplyReason.NETWORK), now) // the SECOND failure in a row: 60 s

            now += 59_000
            assertFalse(desk.shown(query("d"), now), "$notSent put the pause back to 30 s")
            now += 1_000
            assertTrue(desk.shown(query("e"), now))
            now += 1_000_000
        }
        assertEquals(
            setOf(NoReplyReason.WITHDRAWN, NoReplyReason.MISSING_KEY, NoReplyReason.NOTHING_TO_ASK),
            NoReplyReason.values().filter { it.nothingWasSent }.toSet(),
        )
    }

    @Test
    fun `every reason says whether asking again can help and whether the provider failed`() {
        // The table the rules are built on, spelled out: a change to it must be made on purpose.
        val expected = mapOf(
            NoReplyReason.MISSING_KEY to (false to false),
            NoReplyReason.NOTHING_TO_ASK to (false to false),
            NoReplyReason.WITHDRAWN to (true to false),
            NoReplyReason.REFUSED to (false to false),
            NoReplyReason.EMPTY to (false to false),
            NoReplyReason.TRUNCATED to (false to false),
            NoReplyReason.UNEXPECTED_RESPONSE to (false to false),
            NoReplyReason.NOT_A_MESSAGE to (true to true),
            NoReplyReason.TIMEOUT to (true to true),
            NoReplyReason.NETWORK to (true to true),
            NoReplyReason.CERTIFICATE_CLOCK to (true to true),
            NoReplyReason.CERTIFICATE_UNTRUSTED to (true to true),
            NoReplyReason.TLS to (true to true),
            NoReplyReason.RATE_LIMITED to (true to true),
            NoReplyReason.SERVER_ERROR to (true to true),
            NoReplyReason.KEY_REJECTED to (false to true),
            NoReplyReason.REQUEST_REJECTED to (false to true),
            NoReplyReason.PROVIDER_ERROR to (true to true),
            NoReplyReason.UNEXPECTED_FAILURE to (true to true),
        )

        assertEquals(expected, NoReplyReason.values().associateWith { it.askAgain to it.backOff })
    }
}
