package cx.aphex.energysign.message

import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.ai.PostedAIAnswer
import cx.aphex.energysign.keyboard.KeyboardManager
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The REAL MessageManager and KeyboardManager, with the commands the phone sends, driven the way
 * the app drives them: other threads call the entry points, the owner (here: the test, by hand)
 * runs what they posted and asks for messages.
 *
 * What is faked is Android only: the Context (its files directory; colours are 0), the log
 * (unitTests.returnDefaultValues), and the main-thread check of LiveData.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageManagerOwnerTest {
    private val dir: File = Files.createTempDirectory("es89l-manager").toFile()

    private var filesDirAsked = 0
    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File {
            filesDirAsked++
            return dir
        }
    }

    /** The saver's thread, run by hand. */
    private val unsaved = mutableListOf<Runnable>()
    private val repo = MessageRepository(saver = Executor { unsaved += it })
    private val owner = OwnerQueue()
    private lateinit var manager: MessageManager

    @BeforeAll
    fun android() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        startKoin {
            modules(module {
                single { repo }
                single { owner }
                single<AIReplySource> { FakeReplies() }
            })
        }
        manager = MessageManager(context, repo, owner)
    }

    @AfterAll
    fun done() {
        stopKoin()
        ArchTaskExecutor.getInstance().setDelegate(null)
        dir.deleteRecursively()
    }

    @BeforeEach
    fun fresh() {
        // out of chooser mode and out of keyboard input, with five stored messages and nothing queued
        phone("!endchoose")
        phone("!unpause")
        phone("!first")
        ownerRuns()
        endTyping()
        repo.marqueeMessages.clear()
        repo.marqueeMessages.addAll((1..5).map { Message.Marquee.User("MESSAGE $it") })
        repo.oneTimeMessages.clear()
        unsaved.forEach { it.run() } // the saver gets through what the test before left it
        unsaved.clear()
    }

    /** Escape twice, as at the sign: the first press turns the echo into the warning, the second ends the input. */
    private fun endTyping() {
        KeyboardManager.escapeKey()
        ownerRuns()
        Thread.sleep(3) // the warning starts a millisecond after the first press
        KeyboardManager.handleKeyboardInput()
        KeyboardManager.escapeKey()
        ownerRuns()
        assertNull(KeyboardManager.handleKeyboardInput())
    }

    /** A Bluetooth write, on "another thread": it only posts. */
    private fun phone(command: String) = manager.processNewBytes(command.toByteArray())

    /** The owner thread's turn. */
    private fun ownerRuns() {
        while (owner.drain(onError = { throw it }) > 0) Unit
    }

    /** What a request of the sign gets in chooser mode (it takes nothing there); null outside it. */
    private fun shown(): String? {
        phone("!pause") // outside chooser mode the look must not move the index either
        ownerRuns()
        val queued = repo.oneTimeMessages.toList()
        val next = manager.getNextMessage()
        phone("!unpause")
        ownerRuns()
        if (next is Message.Chooser) return "${next.flashy} ${next.str}"
        // put back what the look took
        repo.oneTimeMessages.clear()
        repo.oneTimeMessages.addAll(queued)
        return null
    }

    /**
     * A call from the HTTP server: on its own thread, which waits for the owner. [beforeOwnerRuns]
     * is looked at while the call is waiting; then this thread, the owner, takes its turns.
     */
    private fun <T> fromServer(beforeOwnerRuns: () -> Unit = {}, call: () -> T): T {
        val result = FutureTask(Callable(call))
        Thread(result, "http").start()
        Thread.sleep(50)
        beforeOwnerRuns()
        while (!result.isDone) {
            ownerRuns()
            Thread.sleep(1)
        }
        return try {
            result.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // ---- one owner (class E)

    @Test
    fun `a command from the phone changes nothing until the owner runs it`() {
        phone("!choose")
        phone("!next")
        phone("!delete")
        assertEquals(5, repo.marqueeMessages.size)
        ownerRuns()
        assertEquals(4, repo.marqueeMessages.size)
        assertEquals("2/4 MESSAGE 3", shown())
    }

    @Test
    fun `a key changes nothing until the owner runs it`() {
        KeyboardManager.processNewKeyboardKey('h')
        KeyboardManager.processNewKeyboardKey('i')
        assertNull(KeyboardManager.handleKeyboardInput())
        ownerRuns()
        assertEquals("hi_", KeyboardManager.handleKeyboardInput()?.str)
        KeyboardManager.deleteKey()
        assertEquals("hi_", KeyboardManager.handleKeyboardInput()?.str)
        ownerRuns()
        assertEquals("h_", KeyboardManager.handleKeyboardInput()?.str)
        KeyboardManager.escapeKey()
        Thread.sleep(3) // the warning starts a millisecond after the press has been carried out
        assertTrue(KeyboardManager.handleKeyboardInput() is Message.KeyboardEcho.Input)
        ownerRuns()
        Thread.sleep(3)
        assertTrue(KeyboardManager.handleKeyboardInput() is Message.KeyboardEcho.InputWarning)
    }

    @Test
    fun `everything from other threads goes through the owner, a new message, an AI state, an AI reply, a track`() {
        val untouched = {
            assertEquals(5, repo.marqueeMessages.size)
            assertEquals(0, repo.oneTimeMessages.size)
        }
        fromServer(untouched) { manager.processNewUserMessage("FRESH") }
        repo.oneTimeMessages.clear() // (the announcement of the new message)
        val stillOnlyThat = {
            assertEquals(6, repo.marqueeMessages.size)
            assertEquals(0, repo.oneTimeMessages.size)
        }
        fromServer(stillOnlyThat) { manager.togglePersistentThinkingMessage(true) }
        repo.oneTimeMessages.clear()
        fromServer(stillOnlyThat) { manager.onNewPostedAIReply(PostedAIAnswer(answer = "an answer", inReplyTo = "MESSAGE 2")) }
        fromServer({ assertEquals(0, repo.oneTimeMessages.size) }) {
            manager.processNowPlayingTrack(BeatLinkTrack(id = 1, title = "Title", artist = "Artist"))
        }
        assertEquals("FRESH", repo.marqueeMessages.first().str)
        assertEquals(7, repo.marqueeMessages.size)
        assertEquals("AN ANSWER", repo.marqueeMessages[repo.marqueeMessages.indexOfFirst { it.str == "MESSAGE 2" } + 1].str)
        assertTrue(repo.oneTimeMessages.any { it is Message.NowPlayingTrackMessage })
        assertTrue(repo.oneTimeMessages.none { it is Message.ColorMessage.AIThinking })
    }

    // ---- the HTTP server is told what happened

    @Test
    fun `a posted message is there when its POST returns, and in the list the next GET gets`() {
        val call = FutureTask(Callable { manager.processNewUserMessage("FRESH") })
        Thread(call, "http").start()
        Thread.sleep(50)
        assertFalse(call.isDone) // the POST is not answered before the owner has made the change
        while (!call.isDone) {
            ownerRuns()
            Thread.sleep(1)
        }
        assertEquals("FRESH", repo.marqueeMessages.first().str)
        assertEquals("FRESH", fromServer { manager.storedMessages() }.first().str)
    }

    @Test
    fun `a posted reply is stored, and a reply for nothing or a blank one is refused, not thrown`() {
        assertTrue(fromServer { manager.onNewPostedAIReply(PostedAIAnswer(answer = "an answer", inReplyTo = "MESSAGE 2")) })
        assertEquals(6, repo.marqueeMessages.size)

        assertFalse(fromServer { manager.onNewPostedAIReply(PostedAIAnswer(answer = "  ", inReplyTo = "MESSAGE 2")) })
        assertEquals(6, repo.marqueeMessages.size)

        repo.marqueeMessages.clear()
        assertFalse(fromServer { manager.onNewPostedAIReply(PostedAIAnswer(answer = "an answer", inReplyTo = "GONE")) })
        assertEquals(0, repo.marqueeMessages.size)
    }

    @Test
    fun `the list a GET gets is a copy the owner made`() {
        val list = fromServer { manager.storedMessages() }
        repo.marqueeMessages.clear()
        assertEquals(5, list.size)
    }

    @Test
    fun `an ad command is parsed by its caller and only the result goes to the owner`() {
        phone("!\uD83C\uDD70\n\uD83D\uDC7E")
        ownerRuns()
        assertTrue(repo.oneTimeMessages.any { it is Message.ColorMessage.IconInvaders })
    }

    // ---- a message that could not be written (class D)

    @Test
    fun `a one-time message that could not be written goes back to the front, a stored one comes round by itself`() {
        phone("!micOff")
        ownerRuns()
        repo.enqueueOneTimeMessage(Message.Starfield())
        val taken = manager.getNextMessage()
        assertSame(Message.UtilityMessage.DisableMic, taken)

        manager.returnUnsent(taken)

        assertSame(Message.UtilityMessage.DisableMic, repo.oneTimeMessages.first())
        assertEquals(2, repo.oneTimeMessages.size)
        assertSame(Message.UtilityMessage.DisableMic, manager.getNextMessage())
        assertTrue(manager.getNextMessage() is Message.Starfield)

        val stored = manager.getNextMessage()
        assertEquals("MESSAGE 1", stored.str)
        manager.returnUnsent(stored)
        assertEquals(0, repo.oneTimeMessages.size)
        assertEquals(5, repo.marqueeMessages.size)
    }

    // ---- the real commands

    @Test
    fun `the chooser commands, as the phone sends them, decide what a request gets`() {
        val expected = listOf(
            "!choose" to "1/5 MESSAGE 1",
            "!next" to "2/5 MESSAGE 2",
            "!last" to "5/5 MESSAGE 5",
            "!prev" to "4/5 MESSAGE 4",
            "!first" to "1/5 MESSAGE 1",
            "!delete" to "1/4 MESSAGE 2",
            "HELLO \u201CWORLD\u201D" to "1/5 HELLO \"WORLD\"",
            "!endchoose" to null,
        )
        for (step in expected) {
            phone(step.first)
            ownerRuns()
            assertEquals(step.second, shown(), "after ${step.first}")
        }
    }

    @Test
    fun `a one-time command queued in chooser mode waits for the first request after it is left`() {
        // the microphone command must reach the sign: nothing but a request takes it
        phone("!choose")
        phone("!micOff")
        ownerRuns()
        repeat(20) { assertTrue(manager.getNextMessage() is Message.Chooser) }
        assertSame(Message.UtilityMessage.DisableMic, repo.oneTimeMessages.single())
        phone("!endchoose")
        ownerRuns()
        assertSame(Message.UtilityMessage.DisableMic, manager.getNextMessage())
        assertEquals("MESSAGE 1", manager.getNextMessage().str)
        assertEquals("MESSAGE 2", manager.getNextMessage().str)
    }

    @Test
    fun `in chooser mode a request takes nothing either`() {
        phone("!choose")
        phone("!micOn")
        ownerRuns()
        repeat(5) { assertEquals("1/5", (manager.getNextMessage() as Message.Chooser).flashy) }
        assertEquals(1, repo.oneTimeMessages.size)
    }

    // ---- the keyboard (class E: the gate's two keyboard cases)

    /** As if the typing had started [ms] ago (an entry is only accepted after five seconds). */
    private fun typingStarted(ms: Long) {
        KeyboardManager::class.java.getDeclaredField("keyboardInputStartedAtMs").apply {
            isAccessible = true
            setLong(KeyboardManager, System.currentTimeMillis() - ms)
        }
    }

    @Test
    fun `Enter takes the text and ends the input in one step, the message is what was typed`() {
        "hello".forEach(KeyboardManager::processNewKeyboardKey)
        ownerRuns()
        typingStarted(6_000)
        KeyboardManager.submitKeyboardInput()
        ownerRuns() // the one step: nothing runs between taking the text and storing the message
        assertEquals("hello", repo.marqueeMessages.first().str)
        assertTrue(repo.marqueeMessages.first() is Message.Marquee.AIQuery)
        assertNull(KeyboardManager.handleKeyboardInput())
        assertEquals(6, repo.marqueeMessages.size)
    }

    @Test
    fun `Enter before five seconds of typing submits nothing and keeps the input`() {
        "hey".forEach(KeyboardManager::processNewKeyboardKey)
        KeyboardManager.submitKeyboardInput()
        ownerRuns()
        assertEquals(5, repo.marqueeMessages.size)
        assertEquals("hey_", KeyboardManager.handleKeyboardInput()?.str)
    }

    @Test
    fun `a first key with nothing stored and one message queued, the next request is answered with the echo`() {
        // the gate's case: the key empties the one-time queue; the choice of the next message must see that whole
        repo.marqueeMessages.clear()
        repo.pushOneTimeMessage(Message.Starfield())
        KeyboardManager.processNewKeyboardKey('a')
        ownerRuns()
        assertEquals("a_", manager.getNextMessage().str)
        // and with the input over and nothing stored, a request still gets something (an advertisement)
        endTyping()
        assertNotNull(manager.getNextMessage())
    }

    // ---- no file is written by the owner (class F)

    @Test
    fun `a command that saves the list leaves the writing to the saver`() {
        val file = File(dir, "signstrings.txt")
        file.writeText("what was on disk before\n")
        phone("!choose")
        phone("!delete")
        ownerRuns()
        assertEquals(4, repo.marqueeMessages.size)
        assertEquals(1, unsaved.size)
        assertEquals("what was on disk before\n", file.readText()) // the owner has written nothing
        unsaved.removeAt(0).run()
        assertEquals(listOf("MESSAGE 5", "MESSAGE 4", "MESSAGE 3", "MESSAGE 2"), file.readLines())
    }

    @Test
    fun `the files directory is asked of the context once, at the start, not at a save`() {
        phone("!choose")
        phone("!delete")
        phone("!delete")
        phone("FRESH")
        ownerRuns()
        assertEquals(1, unsaved.size) // three saves, the newest list for the saver
        assertEquals(1, filesDirAsked)
    }

    // ---- the waiting entry for a caller that says "done" (the adb hook)

    @Test
    fun `the waiting twin of a command returns when the owner has carried it out`() {
        val call = FutureTask(Callable { manager.processNewBytesAndWait("!choose".toByteArray()) })
        Thread(call, "hook").start()
        Thread.sleep(50)
        assertFalse(call.isDone)
        assertTrue(repo.oneTimeMessages.isEmpty())
        while (!call.isDone) {
            ownerRuns()
            Thread.sleep(1)
        }
        assertEquals("1/5 MESSAGE 1", shown())
    }
}
