package cx.aphex.energysign.message

import cx.aphex.energysign.ai.PostedAIAnswer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Putting a reply into the list. The old code put a reply for a query it could not find over
 * the newest message, and threw when the list was empty.
 */
class MessageRepositoryRepliesTest {

    private val repo = MessageRepository(saver = { it.run() })

    private fun stored(): List<String> = repo.marqueeMessages.map { "${it.javaClass.simpleName}:${it.str}" }

    /** A posted reply as MessageManager handles it: made into its message first, then put into the list. */
    private fun posted(answer: String, inReplyTo: String): Boolean =
        MessageRepository.postedReplyMessage(answer)?.let { repo.insertPostedAIReply(it, inReplyTo) } ?: false

    @Test
    fun `a reply goes after its query, and the query becomes a plain message`() {
        val query = Message.Marquee.AIQuery("is it loud")
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), query, Message.Marquee.User("OLDEST")))

        assertTrue(repo.insertAIReply(query, MessageRepository.replyMessage("Loud enough.")))

        assertEquals(listOf("User:NEWEST", "User:is it loud", "AIReply:LOUD ENOUGH.", "User:OLDEST"), stored())
    }

    @Test
    fun `a reply for a query that is not in the list changes nothing`() {
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), Message.Marquee.User("OLDEST")))

        assertFalse(repo.insertAIReply(Message.Marquee.AIQuery("deleted meanwhile"), MessageRepository.replyMessage("Too late.")))

        assertEquals(listOf("User:NEWEST", "User:OLDEST"), stored())
    }

    @Test
    fun `a reply for a query that is not in an empty list changes nothing and does not throw`() {
        assertFalse(repo.insertAIReply(Message.Marquee.AIQuery("deleted meanwhile"), MessageRepository.replyMessage("Too late.")))

        assertTrue(repo.marqueeMessages.isEmpty())
    }

    @Test
    fun `a query with the same text as another is not taken for it`() {
        val asked = Message.Marquee.AIQuery("same words")
        val other = Message.Marquee.AIQuery("same words")
        repo.marqueeMessages.addAll(listOf(other, Message.Marquee.User("OLDEST")))

        assertFalse(repo.insertAIReply(asked, MessageRepository.replyMessage("For the one that was deleted.")))

        assertEquals(listOf("AIQuery:same words", "User:OLDEST"), stored())
    }

    @Test
    fun `retiring a query makes it a plain message where it stands, and one that is gone changes nothing`() {
        val query = Message.Marquee.AIQuery("is it loud")
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), query))

        assertTrue(repo.retireQuery(query))
        assertEquals(listOf("User:NEWEST", "User:is it loud"), stored())

        assertFalse(repo.retireQuery(query), "it is not a query any more")
        assertFalse(repo.retireQuery(Message.Marquee.AIQuery("never there")))
        assertEquals(listOf("User:NEWEST", "User:is it loud"), stored())
    }

    @Test
    fun `a posted reply goes after the message it names, or after the newest one`() {
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), Message.Marquee.AIQuery("MIDDLE"), Message.Marquee.User("OLDEST")))

        assertTrue(posted("for the middle", inReplyTo = "MIDDLE"))
        assertTrue(posted("for nobody", inReplyTo = "not there"))

        assertEquals(
            listOf("User:NEWEST", "AIReply:FOR NOBODY", "User:MIDDLE", "AIReply:FOR THE MIDDLE", "User:OLDEST"),
            stored(),
        )
    }

    @Test
    fun `a posted reply into an empty list, or with nothing in it, changes nothing and does not throw`() {
        assertFalse(posted("an answer", inReplyTo = "x"))

        repo.marqueeMessages.add(Message.Marquee.User("ONLY"))
        assertFalse(posted("  \n ", inReplyTo = "ONLY"))
        assertFalse(posted("☃", inReplyTo = "ONLY"), "nothing the sign can show")

        assertEquals(listOf("User:ONLY"), stored())
    }

    @Test
    fun `a posted reply is one line and fits the sign`() {
        repo.marqueeMessages.add(Message.Marquee.User("ONLY"))

        posted("line one\nline two\u000Bthree " + "word ".repeat(400), inReplyTo = "ONLY")

        val reply = repo.marqueeMessages[1]
        assertTrue(reply.str.startsWith("LINE ONE LINE TWO THREE WORD"), reply.str.take(40))
        assertTrue(SignResponder.GSON.toJson(Message.Marquee.Chonky(reply.str)).toByteArray().size < 700)
        assertTrue(reply.str.endsWith("WORD"))
    }

    // ---- the list is handed messages that are ready (class D)

    @Test
    fun `the message that is handed in is the message that is stored - nothing here works on its text`() {
        val query = Message.Marquee.AIQuery("is it loud")
        repo.marqueeMessages.addAll(listOf(query, Message.Marquee.User("OLDEST")))
        val own = MessageRepository.replyMessage("Loud enough.")
        val fromOutside = MessageRepository.postedReplyMessage("From the laptop.")!!

        repo.insertAIReply(query, own)
        repo.insertPostedAIReply(fromOutside, inReplyTo = "OLDEST")

        assertTrue(repo.marqueeMessages[1] === own)
        assertTrue(repo.marqueeMessages[3] === fromOutside)
    }

    @Test
    fun `a posted reply of which nothing is left is no message at all`() {
        for (nothing in listOf("", "  \n ", "\u2603", "\u2028")) {
            assertEquals(null, MessageRepository.postedReplyMessage(nothing))
        }
        assertEquals("FROM THE LAPTOP.", MessageRepository.postedReplyMessage(" From the laptop. ")!!.str)
    }

    // ---- found by the review of round 3

    @Test
    fun `whatever making the message of a reply throws, an Error included, there is a result to hand to the owner`() {
        val reply = cx.aphex.energysign.ai.AIReplyResult.Reply("Loud enough.")

        val (result, message) = MessageRepository.readyReply(reply)
        assertEquals(reply, result)
        assertEquals("LOUD ENOUGH.", message!!.str)

        for (failure in listOf<Throwable>(IllegalStateException("boom"), OutOfMemoryError("no room"), NoClassDefFoundError("x"))) {
            val (failed, none) = MessageRepository.readyReply(reply) { throw failure }
            assertEquals(cx.aphex.energysign.ai.NoReplyReason.UNEXPECTED_FAILURE, (failed as cx.aphex.energysign.ai.AIReplyResult.NoReply).reason)
            assertEquals(null, none)
        }

        val noReply = cx.aphex.energysign.ai.AIReplyResult.NoReply(cx.aphex.energysign.ai.NoReplyReason.REFUSED)
        assertEquals(noReply to null, MessageRepository.readyReply(noReply) { error("not called for no reply") })
    }

    @Test
    fun `the place a query was seen at is used when it still stands there, and never trusted when it does not`() {
        val query = Message.Marquee.AIQuery("same words")
        val twin = Message.Marquee.AIQuery("same words")
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), twin, query, Message.Marquee.User("OLDEST")))

        // The place is stale: it points at the twin. The query is found where it is.
        assertTrue(repo.insertAIReply(query, MessageRepository.replyMessage("For the second."), seenAt = 1))
        assertEquals(listOf("User:NEWEST", "AIQuery:same words", "User:same words", "AIReply:FOR THE SECOND.", "User:OLDEST"), stored())

        // The right place, a place beyond the list, and no place.
        assertTrue(repo.retireQuery(twin, seenAt = 1))
        val late = Message.Marquee.AIQuery("late")
        repo.marqueeMessages.add(late)
        assertTrue(repo.insertAIReply(late, MessageRepository.replyMessage("Late."), seenAt = 99))
        assertFalse(repo.insertAIReply(Message.Marquee.AIQuery("gone"), MessageRepository.replyMessage("x"), seenAt = 0))
        assertEquals("AIReply:LATE.", stored().last())
    }
}
