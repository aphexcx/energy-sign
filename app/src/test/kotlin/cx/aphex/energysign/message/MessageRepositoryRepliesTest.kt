package cx.aphex.energysign.message

import cx.aphex.energysign.gpt.PostedGptAnswer
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

    @Test
    fun `a reply goes after its query, and the query becomes a plain message`() {
        val query = Message.Marquee.GPTQuery("is it loud")
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), query, Message.Marquee.User("OLDEST")))

        assertTrue(repo.insertGPTReply(query, "Loud enough."))

        assertEquals(listOf("User:NEWEST", "User:is it loud", "GPTReply:LOUD ENOUGH.", "User:OLDEST"), stored())
    }

    @Test
    fun `a reply for a query that is not in the list changes nothing`() {
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), Message.Marquee.User("OLDEST")))

        assertFalse(repo.insertGPTReply(Message.Marquee.GPTQuery("deleted meanwhile"), "Too late."))

        assertEquals(listOf("User:NEWEST", "User:OLDEST"), stored())
    }

    @Test
    fun `a reply for a query that is not in an empty list changes nothing and does not throw`() {
        assertFalse(repo.insertGPTReply(Message.Marquee.GPTQuery("deleted meanwhile"), "Too late."))

        assertTrue(repo.marqueeMessages.isEmpty())
    }

    @Test
    fun `a query with the same text as another is not taken for it`() {
        val asked = Message.Marquee.GPTQuery("same words")
        val other = Message.Marquee.GPTQuery("same words")
        repo.marqueeMessages.addAll(listOf(other, Message.Marquee.User("OLDEST")))

        assertFalse(repo.insertGPTReply(asked, "For the one that was deleted."))

        assertEquals(listOf("GPTQuery:same words", "User:OLDEST"), stored())
    }

    @Test
    fun `retiring a query makes it a plain message where it stands, and one that is gone changes nothing`() {
        val query = Message.Marquee.GPTQuery("is it loud")
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), query))

        assertTrue(repo.retireQuery(query))
        assertEquals(listOf("User:NEWEST", "User:is it loud"), stored())

        assertFalse(repo.retireQuery(query), "it is not a query any more")
        assertFalse(repo.retireQuery(Message.Marquee.GPTQuery("never there")))
        assertEquals(listOf("User:NEWEST", "User:is it loud"), stored())
    }

    @Test
    fun `a posted reply goes after the message it names, or after the newest one`() {
        repo.marqueeMessages.addAll(listOf(Message.Marquee.User("NEWEST"), Message.Marquee.GPTQuery("MIDDLE"), Message.Marquee.User("OLDEST")))

        assertTrue(repo.insertGPTReplyToUserMessage(PostedGptAnswer("for the middle", inReplyTo = "MIDDLE")))
        assertTrue(repo.insertGPTReplyToUserMessage(PostedGptAnswer("for nobody", inReplyTo = "not there")))

        assertEquals(
            listOf("User:NEWEST", "GPTReply:FOR NOBODY", "User:MIDDLE", "GPTReply:FOR THE MIDDLE", "User:OLDEST"),
            stored(),
        )
    }

    @Test
    fun `a posted reply into an empty list, or with nothing in it, changes nothing and does not throw`() {
        assertFalse(repo.insertGPTReplyToUserMessage(PostedGptAnswer("an answer", inReplyTo = "x")))

        repo.marqueeMessages.add(Message.Marquee.User("ONLY"))
        assertFalse(repo.insertGPTReplyToUserMessage(PostedGptAnswer("  \n ", inReplyTo = "ONLY")))
        assertFalse(repo.insertGPTReplyToUserMessage(PostedGptAnswer("☃", inReplyTo = "ONLY")), "nothing the sign can show")

        assertEquals(listOf("User:ONLY"), stored())
    }

    @Test
    fun `a posted reply is one line and fits the sign`() {
        repo.marqueeMessages.add(Message.Marquee.User("ONLY"))

        repo.insertGPTReplyToUserMessage(PostedGptAnswer("line one\nline two\u000Bthree " + "word ".repeat(400), inReplyTo = "ONLY"))

        val reply = repo.marqueeMessages[1]
        assertTrue(reply.str.startsWith("LINE ONE LINE TWO THREE WORD"), reply.str.take(40))
        assertTrue(SignResponder.GSON.toJson(Message.Marquee.Chonky(reply.str)).toByteArray().size < 700)
        assertTrue(reply.str.endsWith("WORD"))
    }
}
