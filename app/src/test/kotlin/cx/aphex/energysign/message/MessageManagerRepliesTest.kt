package cx.aphex.energysign.message

import android.content.Context
import android.content.ContextWrapper
import cx.aphex.energysign.ai.AIReplyResult
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.ai.NoReplyReason
import cx.aphex.energysign.gpt.PostedGptAnswer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The REAL MessageManager with a stand-in for the provider: what the sign is given while a
 * query is being answered, and what becomes of every result. Driven the way the app drives it:
 * results come from "another thread" (they only post), the owner (the test, by hand) runs what
 * was posted and asks for messages. The clock only moves when the test says so.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageManagerRepliesTest {
    private val dir: File = Files.createTempDirectory("es6zj-replies").toFile()

    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    /** The saver's thread, run by hand. */
    private val unsaved = mutableListOf<Runnable>()
    private val repo = MessageRepository(saver = Executor { unsaved += it })
    private val owner = OwnerQueue()
    private val replies = FakeReplies()
    private var nowMs = 1_000_000L
    private lateinit var manager: MessageManager

    @BeforeAll
    fun koin() {
        startKoin {
            modules(module {
                single { repo }
                single { owner }
                single<AIReplySource> { replies }
            })
        }
    }

    @AfterAll
    fun done() {
        stopKoin()
        dir.deleteRecursively()
    }

    @BeforeEach
    fun fresh() = build(warmUp = false)

    /** A manager as the app builds it at its start, with three stored messages. */
    private fun build(warmUp: Boolean) {
        runSaves()
        File(dir, "signstrings.bin").delete()
        repo.marqueeMessages.clear()
        repo.oneTimeMessages.clear()
        replies.asked.clear()
        replies.hasWarmUp = warmUp
        manager = MessageManager(context, repo, owner, nowMs = { nowMs })
        ownerRuns()
        repo.marqueeMessages.addAll(listOf("NEWEST", "MIDDLE", "OLDEST").map { Message.Marquee.User(it) })
        runSaves()
    }

    /**
     * The saver's turn: what was asked for is written. (The repository keeps one save waiting at
     * most, so a save that is dropped unrun would keep every later one from being queued.)
     */
    private fun runSaves() {
        val waiting = unsaved.toList()
        unsaved.clear()
        waiting.forEach { it.run() }
    }

    /** The owner thread's turn. An event that throws fails the test. */
    private fun ownerRuns() {
        while (owner.drain(onError = { throw it }) > 0) Unit
    }

    /** Somebody types a message with the word in it; the owner takes it in. */
    private fun ask(text: String = "hey ravegpt is the bass loud enough"): Message.Marquee.GPTQuery {
        manager.processNewUserMessage(text)
        ownerRuns()
        return assertIs<Message.Marquee.GPTQuery>(repo.marqueeMessages.first())
    }

    /** What the sign is given next, as the link thread asks for it. */
    private fun next(): Message = manager.getNextMessage()

    /** Asks for messages until a stored one comes: the announcement of a new message goes first. */
    private fun untilStored(): Message.Marquee {
        repeat(20) { (next() as? Message.Marquee)?.let { return it } }
        error("no stored message came")
    }

    private fun thinkingQueued(): Boolean = repo.oneTimeMessages.any { it is Message.ColorMessage.GPTThinking }

    private fun stored(): List<String> = repo.marqueeMessages.map { "${it.javaClass.simpleName}:${it.str}" }

    // ---- asked once, with the notice

    @Test
    fun `a query is sent when the sign shows it, once, and the sign says thinking until the reply is in`() {
        val query = ask()
        assertTrue(replies.asked.isEmpty(), "nothing is sent before the sign shows the query")

        assertEquals(query, untilStored())
        assertEquals(listOf(query.str), replies.asked.map { it.text })
        repeat(6) { assertIs<Message.ColorMessage.GPTThinking>(next()) }
        assertEquals(1, replies.asked.size)
    }

    // ---- delivery (class D)

    @Test
    fun `a reply changes nothing until the owner runs it, then it stands after its query and the query is a plain message`() {
        val query = ask()
        untilStored()
        next()

        replies.asked.single().answer(AIReplyResult.Reply("Loud enough."))

        assertEquals("GPTQuery:${query.str}", stored().first(), "the other thread only posted")
        assertTrue(thinkingQueued())
        ownerRuns()
        assertEquals(listOf("User:${query.str}", "GPTReply:LOUD ENOUGH.", "User:NEWEST", "User:MIDDLE", "User:OLDEST"), stored())
        assertFalse(thinkingQueued(), "the notice comes down with the reply")
    }

    @Test
    fun `the reply is saved by the saver, not by the thread that delivers it`() {
        ask()
        untilStored()
        runSaves()

        replies.asked.single().answer(AIReplyResult.Reply("Loud enough."))
        ownerRuns()

        assertFalse(File(dir, "signstrings.bin").readText().contains("LOUD ENOUGH"), "nothing is written on the owner thread")
        assertEquals(1, unsaved.size)
        runSaves()
        assertTrue(File(dir, "signstrings.bin").readText().contains("CHONKYMARQUEE\u000BLOUD ENOUGH."))
    }

    @Test
    fun `a reply of the app's own and a reply posted from outside, both before the owner runs, are both there`() {
        val query = ask()
        untilStored()

        // Two results from two threads before the owner gets to either: one event each.
        replies.asked.single().answer(AIReplyResult.Reply("Loud enough."))
        manager.onNewPostedGPTReply(PostedGptAnswer("An answer from the laptop.", inReplyTo = "MIDDLE"))
        ownerRuns()

        assertEquals(
            listOf(
                "User:${query.str}", "GPTReply:LOUD ENOUGH.", "User:NEWEST",
                "User:MIDDLE", "GPTReply:AN ANSWER FROM THE LAPTOP.", "User:OLDEST",
            ),
            stored(),
        )
    }

    @Test
    fun `a result that arrives twice is used once`() {
        ask()
        untilStored()
        val answer = replies.asked.single().answer

        answer(AIReplyResult.Reply("Loud enough."))
        answer(AIReplyResult.Reply("Loud enough."))
        ownerRuns()

        assertEquals(1, stored().count { it == "GPTReply:LOUD ENOUGH." })
    }

    // ---- the time limit is for the notice, not for the call (requirement b)

    @Test
    fun `after 30 seconds the sign goes on with its messages, and the reply is still put in when it comes`() {
        val query = ask()
        untilStored()
        assertIs<Message.ColorMessage.GPTThinking>(next())

        nowMs += 29_000
        assertIs<Message.ColorMessage.GPTThinking>(next())
        nowMs += 1_000
        assertEquals("NEWEST", (next() as Message.Marquee).str, "the notice is over: the next stored message")
        assertFalse(thinkingQueued())
        assertEquals(1, replies.asked.size)

        // The query comes round again while its call is still out: it is not bought a second time.
        repeat(8) { next() }
        assertEquals(1, replies.asked.size)

        nowMs += 35_000 // the reply of a slow first call, a minute after it was asked for
        replies.asked.single().answer(AIReplyResult.Reply("Sorry, I was loading."))
        ownerRuns()
        assertTrue(stored().containsAll(listOf("User:${query.str}", "GPTReply:SORRY, I WAS LOADING.")), "${stored()}")
        assertEquals(stored().indexOf("User:${query.str}") + 1, stored().indexOf("GPTReply:SORRY, I WAS LOADING."))
    }

    // ---- a query that was deleted meanwhile

    @Test
    fun `a reply for a query that was deleted is dropped, and the list is as it was`() {
        ask()
        untilStored()
        manager.processNewBytes("!first".toByteArray())
        manager.processNewBytes("!delete".toByteArray())
        ownerRuns()
        val before = stored()
        assertEquals(listOf("User:NEWEST", "User:MIDDLE", "User:OLDEST"), before)

        replies.asked.single().answer(AIReplyResult.Reply("Too late."))
        ownerRuns()

        assertEquals(before, stored(), "the newest message must not be overwritten")
        assertFalse(thinkingQueued())
    }

    @Test
    fun `a reply for a deleted query into an empty list does not crash`() {
        ask()
        untilStored()
        repo.marqueeMessages.clear()

        replies.asked.single().answer(AIReplyResult.Reply("Too late."))
        ownerRuns() // an exception here fails the test

        assertTrue(repo.marqueeMessages.isEmpty())
    }

    @Test
    fun `a reply posted from outside into an empty list does not crash either`() {
        repo.marqueeMessages.clear()

        manager.onNewPostedGPTReply(PostedGptAnswer("An answer.", inReplyTo = "nothing"))
        ownerRuns()

        assertTrue(repo.marqueeMessages.isEmpty())
    }

    // ---- a refused query stops being a query (requirement c)

    @Test
    fun `a refused query stays on the sign as the plain message it was and is never sent again`() {
        val query = ask()
        untilStored()

        replies.asked.single().answer(AIReplyResult.NoReply(NoReplyReason.REFUSED))
        ownerRuns()

        assertEquals(listOf("User:${query.str}", "User:NEWEST", "User:MIDDLE", "User:OLDEST"), stored())
        assertFalse(thinkingQueued())
        assertTrue(repo.oneTimeMessages.none { it is Message.ColorMessage.ChonkySlide }, "no error notice for a refusal")
        repeat(20) { next() } // five rounds through the list
        assertEquals(1, replies.asked.size)
        runSaves()
        assertTrue(File(dir, "signstrings.bin").readText().contains("DEFAULT\u000B${query.str}"))
    }

    @Test
    fun `an empty answer, a cut-off answer and a rejected key retire the query too`() {
        for (reason in listOf(NoReplyReason.EMPTY, NoReplyReason.TRUNCATED, NoReplyReason.KEY_REJECTED, NoReplyReason.MISSING_KEY)) {
            build(warmUp = false)
            nowMs += 3_600_000 // well clear of the pause after a failure of the round before
            val query = ask("ravegpt $reason")
            untilStored()

            replies.asked.single().answer(AIReplyResult.NoReply(reason))
            ownerRuns()

            assertEquals("User:${query.str}", stored().first(), "$reason")
        }
    }

    // ---- a failure that may pass

    @Test
    fun `a failed call while the dots are up shows the old notice, and the query stays a query`() {
        val query = ask()
        untilStored()
        next()

        replies.asked.single().answer(AIReplyResult.NoReply(NoReplyReason.NETWORK))
        ownerRuns()

        assertEquals("GPTQuery:${query.str}", stored().first())
        assertFalse(thinkingQueued())
        assertEquals(
            listOf("NO", "NO BARS", "TRYING", "AGAIN", "LATER!"),
            repo.oneTimeMessages.filterIsInstance<Message.ColorMessage.ChonkySlide>().map { it.str },
        )
    }

    @Test
    fun `a call that fails after the sign has moved on shows nothing`() {
        ask()
        untilStored()
        nowMs += 31_000
        next()

        replies.asked.single().answer(AIReplyResult.NoReply(NoReplyReason.TIMEOUT))
        ownerRuns()

        assertTrue(repo.oneTimeMessages.none { it is Message.ColorMessage.ChonkySlide }, "${repo.oneTimeMessages}")
    }

    @Test
    fun `after a failure nothing is asked for half a minute, then the query is asked again, three times at most`() {
        val query = ask()
        untilStored()
        replies.asked.last().answer(AIReplyResult.NoReply(NoReplyReason.NETWORK))
        ownerRuns()

        nowMs += 29_000
        repeat(12) { next() } // the query comes round, three times
        assertEquals(1, replies.asked.size, "nothing is asked during the pause")

        nowMs += 600_000
        repeat(12) { next() }
        assertEquals(2, replies.asked.size)
        replies.asked.last().answer(AIReplyResult.NoReply(NoReplyReason.SERVER_ERROR))
        ownerRuns()

        nowMs += 600_000
        repeat(12) { next() }
        assertEquals(3, replies.asked.size)
        replies.asked.last().answer(AIReplyResult.NoReply(NoReplyReason.RATE_LIMITED))
        ownerRuns()

        assertEquals("User:${query.str}", stored().first { it.endsWith(query.str) }, "after the third failure it is a plain message")
        nowMs += 600_000
        repeat(12) { next() }
        assertEquals(3, replies.asked.size)
    }

    // ---- whole or absent (class C)

    @Test
    fun `a text of which nothing is left for the sign is not a query`() {
        // Typed at the keyboard, so it would be a query: but none of it is something the sign can show.
        manager.processNewUserMessage("☃☃☃", isQuery = true)
        ownerRuns()
        repeat(8) { next() }

        assertFalse(repo.marqueeMessages.any { it is Message.Marquee.GPTQuery })
        assertTrue(replies.asked.isEmpty())
    }

    @Test
    fun `a reply posted from outside gets the same clean-up and the same size limit`() {
        manager.onNewPostedGPTReply(PostedGptAnswer("line one\nline two\u000Bthree " + "word ".repeat(400), inReplyTo = "MIDDLE"))
        ownerRuns()

        val reply = assertIs<Message.Marquee.GPTReply>(repo.marqueeMessages[repo.marqueeMessages.indexOfFirst { it.str == "MIDDLE" } + 1])
        assertTrue(reply.str.startsWith("LINE ONE LINE TWO THREE WORD"), reply.str.take(40))
        assertFalse(reply.str.contains('\n') || reply.str.contains('\u000B'))
        assertTrue(SignResponder.GSON.toJson(reply.toChonkyMessage()).toByteArray().size < 700)
        assertTrue(reply.str.endsWith("WORD"), "cut after a whole word: ...${reply.str.takeLast(12)}")
    }

    // ---- the warm-up (requirement a)

    @Test
    fun `while the warm-up runs a query waits with the notice, and is sent when the warm-up is over`() {
        build(warmUp = true)
        val query = ask()
        untilStored()

        assertTrue(replies.asked.isEmpty(), "nothing is sent during the warm-up")
        assertIs<Message.ColorMessage.GPTThinking>(next())

        nowMs += 10_000
        replies.endWarmUp() // from the warm-up's thread: it only posts
        assertTrue(replies.asked.isEmpty())
        ownerRuns()

        assertEquals(listOf(query.str), replies.asked.map { it.text })
        assertTrue(thinkingQueued())
    }

    @Test
    fun `a query that waited for the warm-up longer than the sign says thinking is not sent, and is asked when it comes round`() {
        build(warmUp = true)
        ask()
        untilStored()
        nowMs += 31_000
        assertFalse(next() is Message.ColorMessage.GPTThinking)

        replies.endWarmUp()
        ownerRuns()
        assertTrue(replies.asked.isEmpty(), "its turn came too late: it cost nothing")

        repeat(8) { next() }
        assertEquals(1, replies.asked.size)
    }

    // ---- the notice that is asked for from outside is not ours to take down

    @Test
    fun `a thinking notice switched on over HTTP stays until it is switched off`() {
        manager.togglePersistentThinkingMessage(true)
        ownerRuns()
        nowMs += 120_000

        repeat(5) { assertIs<Message.ColorMessage.GPTThinking>(next()) }

        manager.togglePersistentThinkingMessage(false)
        ownerRuns()
        assertFalse(next() is Message.ColorMessage.GPTThinking)
    }
}
