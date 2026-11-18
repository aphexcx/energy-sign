package cx.aphex.energysign.message

import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.gpt.GPTViewModel
import cx.aphex.energysign.gpt.PostedGptAnswer
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
import java.util.concurrent.Executor
import kotlin.test.assertEquals
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

    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    /** The saver's thread, run by hand. */
    private val unsaved = mutableListOf<Runnable>()
    private val repo = MessageRepository(saver = Executor { unsaved += it })
    private val owner = OwnerQueue()
    private lateinit var manager: MessageManager

    private var pushes = 0

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
                single { GPTViewModel() }
            })
        }
        manager = MessageManager(context, repo, owner)
        manager.onChooserChanged = { pushes++ }
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
        unsaved.clear()
        pushes = 0
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

    private fun shown(): String? = manager.chooserShown()?.let { "${it.flashy} ${it.str}" }

    // ---- one owner (class E)

    @Test
    fun `a command from the phone changes nothing until the owner runs it`() {
        phone("!choose")
        phone("!next")
        assertNull(shown())
        assertEquals(0, pushes)
        ownerRuns()
        assertEquals("2/5 MESSAGE 2", shown())
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
    fun `everything from other threads goes through the owner, a new message, a GPT state, a GPT reply, a track`() {
        manager.processNewUserMessage("FRESH")
        manager.togglePersistentThinkingMessage(true)
        manager.onNewPostedGPTReply(PostedGptAnswer(answer = "an answer", inReplyTo = "MESSAGE 2"))
        manager.processNowPlayingTrack(BeatLinkTrack(id = 1, title = "Title", artist = "Artist"))
        assertEquals(5, repo.marqueeMessages.size)
        assertEquals(0, repo.oneTimeMessages.size)
        ownerRuns()
        assertEquals("FRESH", repo.marqueeMessages.first().str)
        assertEquals(7, repo.marqueeMessages.size)
        assertEquals("AN ANSWER", repo.marqueeMessages[repo.marqueeMessages.indexOfFirst { it.str == "MESSAGE 2" } + 1].str)
        assertTrue(repo.oneTimeMessages.any { it is Message.NowPlayingTrackMessage })
        // (the posted reply ended the thinking message that the line before it had started)
        assertTrue(repo.oneTimeMessages.none { it is Message.ColorMessage.GPTThinking })
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

    // ---- the real commands and the push (classes A and H)

    @Test
    fun `every command that changes what the chooser shows asks for exactly one push`() {
        val expected = listOf(
            "!choose" to "1/5 MESSAGE 1",
            "!next" to "2/5 MESSAGE 2",
            "!last" to "5/5 MESSAGE 5",
            "!prev" to "4/5 MESSAGE 4",
            "!first" to "1/5 MESSAGE 1",
            "!delete" to "1/4 MESSAGE 2",
            "!endchoose" to null,
        )
        for ((i, step) in expected.withIndex()) {
            phone(step.first)
            ownerRuns()
            assertEquals(i + 1, pushes, "after ${step.first}")
            assertEquals(step.second, shown(), "after ${step.first}")
        }
    }

    @Test
    fun `a command that changes nothing on the sign asks for no push, and nothing is pushed outside chooser mode`() {
        phone("!next")
        phone("!delete")
        manager.processNewUserMessage("FRESH")
        phone("!endchoose") // was not choosing
        ownerRuns()
        assertEquals(0, pushes)

        phone("!choose")
        ownerRuns()
        phone("!choose") // already choosing
        phone("!first") // already at the first
        phone("!pause")
        ownerRuns()
        assertEquals(1, pushes)
    }

    @Test
    fun `a new message in chooser mode is a change of the list and asks for one push`() {
        phone("!choose")
        ownerRuns()
        phone("HELLO “WORLD”")
        ownerRuns()
        assertEquals(2, pushes)
        assertEquals("1/6 HELLO \"WORLD\"", shown())
    }

    @Test
    fun `what a push sends takes nothing, a one-time command queued before leaving goes to the first real request`() {
        // the gate's case: !micOff, then !endchoose, on a sign that is not listening for the push
        phone("!choose")
        phone("!micOff")
        ownerRuns()
        val queued = repo.oneTimeMessages.toList()
        assertSame(Message.UtilityMessage.DisableMic, queued.single())

        repeat(20) { manager.chooserShown() } // any number of pushes in chooser mode
        phone("!endchoose")
        ownerRuns()
        repeat(20) { assertNull(manager.chooserShown()) } // and of the push on leaving: no frame at all

        assertEquals(queued, repo.oneTimeMessages.toList())
        assertEquals(5, repo.marqueeMessages.size)
        assertEquals(emptyList(), unsaved)
        // the sign asks: now, and only now, it is taken
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
        assertTrue(repo.marqueeMessages.first() is Message.Marquee.GPTQuery)
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
        phone("!choose")
        phone("!delete")
        ownerRuns()
        assertEquals(4, repo.marqueeMessages.size)
        assertEquals(1, unsaved.size)
        val file = File(dir, "signstrings.txt")
        val before = if (file.exists()) file.readText() else null
        assertEquals(before, if (file.exists()) file.readText() else null)
        unsaved.removeAt(0).run()
        assertEquals(listOf("MESSAGE 5", "MESSAGE 4", "MESSAGE 3", "MESSAGE 2"), file.readLines())
    }
}
