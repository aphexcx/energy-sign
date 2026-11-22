package cx.aphex.energysign.message

import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.google.gson.JsonParser
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.ai.PostedAIAnswer
import cx.aphex.energysign.beatlinkdata.currentTrack
import cx.aphex.energysign.bluetooth.takeWrite
import cx.aphex.energysign.keyboard.KeyboardManager
import cx.aphex.energysign.uart.SignLink
import cx.aphex.energysign.uart.SignWire
import cx.aphex.energysign.uart.UartTimingLog
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.routing.routing
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Executor
import kotlin.random.Random
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

    /** How often the manager said that what the chooser shows has changed (each is a push). */
    private var pushes = 0

    private val countPushes: () -> Unit = { pushes++ }

    private val immediateTasks = object : TaskExecutor() {
        override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
        override fun postToMainThread(runnable: Runnable) = runnable.run()
        override fun isMainThread(): Boolean = true
    }

    @BeforeAll
    fun android() {
        ArchTaskExecutor.getInstance().setDelegate(immediateTasks)
        startKoin {
            modules(module {
                single { repo }
                single { owner }
                single<AIReplySource> { FakeReplies() }
            })
        }
        manager = MessageManager(context, repo, owner)
        manager.onChooserChanged = countPushes
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
            manager.processNowPlayingTrack(BeatLinkTrack(id = 1, title = "Title", artist = "Artist"), manager.acceptTrackUpdate())
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

    private fun startWithTwoStoredMessages(): App {
        val home = newFilesDir()
        File(home, "signstrings.bin").writeText("DEFAULT\u000bMESSAGE 2\nDEFAULT\u000bMESSAGE 1\n")
        return App(home)
    }

    @Test
    fun `a one-time message that could not be written goes back to the front and a stored one retries at its place`() {
        val app = startWithTwoStoredMessages()
        val repo = app.repo
        val manager = app.manager
        app.phone("!micOff")
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
        assertEquals(2, repo.marqueeMessages.size)
        assertSame(stored, manager.getNextMessage())
        assertSame(repo.marqueeMessages[1], manager.getNextMessage())
    }

    @Test
    fun `a marquee taken from the one time queue retries there even when it also has a stored place`() {
        val app = startWithTwoStoredMessages()
        val repo = app.repo
        val manager = app.manager
        val queued = repo.marqueeMessages.last()
        repo.pushOneTimeMessage(queued)
        assertSame(queued, manager.getNextMessage())
        manager.returnUnsent(queued)
        assertSame(queued, repo.oneTimeMessages.firstOrNull())
        assertSame(queued, manager.getNextMessage())
        assertSame(repo.marqueeMessages.first(), manager.getNextMessage())
    }

    @Test
    fun `deleting a failed stored message before the retry leaves the cursor on its successor`() {
        val app = startWithTwoStoredMessages()
        val manager = app.manager
        val failed = manager.getNextMessage()
        manager.returnUnsent(failed)
        app.phone("!choose")
        app.phone("!delete")
        app.phone("!endchoose")
        ownerRuns()
        assertTrue(app.repo.marqueeMessages.none { it === failed })
        assertEquals("MESSAGE 2", manager.getNextMessage().str)
    }

    @Test
    fun `failed writes near the end of a long history restore the slot used for each attempt`() {
        val app = startWithTwoStoredMessages()
        val history = app.repo.marqueeMessages
        history.addAll((3..77).map { Message.Marquee.User("MESSAGE $it") })
        val selected = history[75]
        history[1] = selected // a search by identity would find the earlier occurrence
        app.phone("!last")
        app.phone("!prev")
        ownerRuns()

        repeat(3) {
            assertSame(selected, app.manager.getNextMessage())
            app.manager.returnUnsent(selected)
        }
        assertSame(selected, app.manager.getNextMessage())
        assertSame(history.last(), app.manager.getNextMessage())
    }

    @Test
    fun `a failed last stored message retries before the ads it triggered, even after repeated failures`() {
        val app = startWithTwoStoredMessages()
        val repo = app.repo
        val manager = app.manager
        app.phone("!A1")
        app.phone("!last")
        ownerRuns()
        repo.oneTimeMessages.clear()
        val last = repo.marqueeMessages.last()
        assertSame(last, manager.getNextMessage())
        val ads = repo.oneTimeMessages.toList()
        assertTrue(ads.isNotEmpty())

        repeat(2) {
            manager.returnUnsent(last)
            assertSame(last, manager.getNextMessage())
            assertEquals(ads, repo.oneTimeMessages.toList())
        }
        ads.forEach { assertSame(it, manager.getNextMessage()) }
        assertSame(repo.marqueeMessages.first(), manager.getNextMessage())
    }

    @Test
    fun `pushed commands interrupt a repeatedly failing stored frame and its ads run once afterward`() {
        val app = startWithTwoStoredMessages()
        app.phone("!A1")
        ownerRuns()
        app.repo.oneTimeMessages.clear()
        val manager = app.manager
        val first = manager.getNextMessage()
        val ads = app.repo.oneTimeMessages.toList()
        assertTrue(ads.isNotEmpty())
        manager.returnUnsent(first)

        app.phone("!micOff")
        ownerRuns()
        assertSame(Message.UtilityMessage.DisableMic, manager.getNextMessage())
        assertSame(first, manager.getNextMessage())
        manager.returnUnsent(first)
        app.phone("!B3")
        ownerRuns()
        assertTrue(manager.getNextMessage() is Message.UtilityMessage.BrightnessShift)
        assertSame(first, manager.getNextMessage())
        ads.forEach { assertSame(it, manager.getNextMessage()) }
        assertSame(app.repo.marqueeMessages.last(), manager.getNextMessage())
    }

    @Test
    fun `a new occurrence of an ad can preempt its own stored retry while the original and the tail stay ordered`() {
        val app = startWithTwoStoredMessages()
        app.phone("!A1")
        ownerRuns()
        app.repo.oneTimeMessages.clear()
        val first = app.manager.getNextMessage()
        val generated = app.repo.oneTimeMessages.toList()
        app.manager.returnUnsent(first)

        val sameIcon = generated.first()
        val track = Message.NowPlayingTrackMessage("tail")
        app.repo.pushOneTimeMessage(sameIcon)
        app.repo.enqueueOneTimeMessage(track)
        assertSame(sameIcon, app.manager.getNextMessage())
        assertSame(first, app.manager.getNextMessage())
        generated.forEach { assertSame(it, app.manager.getNextMessage()) }
        assertSame(track, app.manager.getNextMessage())
    }

    @Test
    fun `track updates between attempts keep their queued order and the failed stored cursor`() {
        val app = startWithTwoStoredMessages()
        val first = app.manager.getNextMessage()
        app.manager.returnUnsent(first)
        app.post(BeatLinkTrack(id = 1, artist = "DJ", title = "Old"))
        app.post(BeatLinkTrack(id = 2, artist = "DJ", title = "New"))

        val queued = app.repo.oneTimeMessages.toList()
        assertEquals(listOf("DJ - New"), queued.filterIsInstance<Message.NowPlayingTrackMessage>().map { it.str })
        queued.forEach { assertSame(it, app.manager.getNextMessage()) }
        assertSame(first, app.manager.getNextMessage())
        assertSame(app.repo.marqueeMessages.last(), app.manager.getNextMessage())
    }

    @Test
    fun `a queued heading and reply stay together ahead of a retry and the command behind them`() {
        val app = startWithTwoStoredMessages()
        val first = app.manager.getNextMessage()
        app.manager.returnUnsent(first)
        val heading = Message.ColorMessage.ChonkySlide("SAYS", 0x123456)
        val reply = Message.Marquee.Chonky("ANSWER")
        app.repo.pushInOnePiece(listOf(heading, reply))
        app.phone("!micOff")
        ownerRuns()

        assertSame(heading, app.manager.getNextMessage())
        assertSame(reply, app.manager.getNextMessage())
        assertSame(Message.UtilityMessage.DisableMic, app.manager.getNextMessage())
        assertSame(first, app.manager.getNextMessage())
    }

    @Test
    fun `a stored frame can fail three retries before the next stored frame gets its turn`() {
        val app = startWithTwoStoredMessages()
        val first = app.repo.marqueeMessages.first()
        repeat(4) {
            assertSame(first, app.manager.getNextMessage())
            app.manager.returnUnsent(first)
        }
        assertSame(app.repo.marqueeMessages.last(), app.manager.getNextMessage())
        assertSame(first, app.manager.getNextMessage(), "the failed frame stays in its normal rotation")
    }

    @Test
    fun `a link starting with main busy for five seconds writes the first stored frame whole`() {
        var clock = 0L
        var preparedOn: Thread? = null
        var firstReadOn: Thread? = null
        var preparedBeforeRead = false
        val frames = mutableListOf<String>()
        var writeAt = Long.MAX_VALUE
        val wire = object : SignWire {
            override fun read(buffer: ByteArray, length: Int): Int {
                if (firstReadOn == null) {
                    firstReadOn = Thread.currentThread()
                    preparedBeforeRead = preparedOn === firstReadOn
                    return 0 // an empty read establishes freshness after the cold start
                }
                buffer[0] = '~'.code.toByte()
                return 1
            }
            override fun write(buffer: ByteArray, length: Int): Int {
                frames += String(buffer, 0, length)
                writeAt = System.nanoTime()
                return length
            }
            override fun close() {}
        }
        val manager = startWithTwoStoredMessages().manager
        lateinit var link: SignLink
        val responder = SignResponder(
            nextMessage = manager::getNextMessage,
            write = { frame, reply -> link.send(frame, reply) },
            unsent = manager::returnUnsent,
        )
        link = SignLink(
            wire = wire, baud = 14400, name = "test", events = { ownerRuns() },
            prepareAnswers = {
                SignResponder.warmUp()
                preparedOn = Thread.currentThread()
                clock += 611_000_000L // the measured cold Gson cost
            },
            answer = { buffer, count ->
                if (preparedOn !== Thread.currentThread()) clock += 611_000_000L
                responder.onRequest(buffer, count)
                link.stop()
            },
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            nanoTime = { clock }, sleepNanos = { clock += it },
        )

        val main = Thread.currentThread()
        val mainTasks = ConcurrentLinkedQueue<Runnable>()
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = immediateTasks.executeOnDiskIO(runnable)
            override fun postToMainThread(runnable: Runnable) { mainTasks.add(runnable) }
            override fun isMainThread() = Thread.currentThread() === main
        })
        val thread = Thread(link, "test-sign-link")
        val mainBusyFrom = System.nanoTime()
        try {
            thread.start()
            Thread.sleep(5_000) // this thread built the manager and cannot run a callback
        } finally {
            link.stop()
            while (true) (mainTasks.poll() ?: break).run()
            thread.join(2_000)
            ArchTaskExecutor.getInstance().setDelegate(immediateTasks)
        }
        assertFalse(thread.isAlive)
        assertEquals(listOf(SignResponder.GSON.toJson(Message.Marquee.User("MESSAGE 1"))), frames)
        assertTrue(preparedBeforeRead)
        assertSame(thread, preparedOn)
        assertTrue(writeAt - mainBusyFrom < 5_000_000_000L)
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
        assertEquals(1, pushes)
    }

    // ---- the push (contract 3.1): when the manager asks for one, and that it takes nothing

    @Test
    fun `every command that changes what the chooser shows asks for exactly one push`() {
        val expected = listOf(
            "!choose" to "1/5 MESSAGE 1",
            "!next" to "2/5 MESSAGE 2",
            "!last" to "5/5 MESSAGE 5",
            "!prev" to "4/5 MESSAGE 4",
            "!first" to "1/5 MESSAGE 1",
            "!s message 3" to "3/5 MESSAGE 3",
            "!delete" to "3/4 MESSAGE 4",
            "HELLO" to "1/5 HELLO",
            "!endchoose" to null,
        )
        for ((i, step) in expected.withIndex()) {
            phone(step.first)
            ownerRuns()
            assertEquals(i + 1, pushes, "after ${step.first}")
            assertEquals(step.second, manager.chooserShown()?.let { "${it.flashy} ${it.str}" }, "after ${step.first}")
        }
    }

    @Test
    fun `a command that changes nothing on the sign asks for no push, and nothing is asked for outside chooser mode`() {
        phone("!next")
        phone("!delete")
        phone("FRESH")
        phone("!endchoose") // was not choosing
        ownerRuns()
        assertEquals(0, pushes)

        phone("!choose")
        ownerRuns()
        assertEquals(1, pushes)
        phone("!choose") // already choosing
        phone("!first") // already at the first
        phone("!prev")
        phone("!pause")
        phone("!unpause")
        phone("!micOn")
        phone("!B 3")
        phone("!\uD83C\uDD70\n\uD83D\uDC7E") // an ad command
        ownerRuns()
        fromServer { manager.togglePersistentThinkingMessage(true) }
        fromServer { manager.togglePersistentThinkingMessage(false) }
        fromServer { manager.processNowPlayingTrack(BeatLinkTrack(id = 7, title = "Title", artist = "Artist"), manager.acceptTrackUpdate()) }
        fromServer { manager.storedMessages() }
        assertEquals(1, pushes)
    }

    @Test
    fun `several commands the owner runs in one turn are one change each, the link makes one push of them`() {
        phone("!choose")
        phone("!next")
        phone("!next")
        ownerRuns()
        assertEquals(3, pushes) // each asks; SignLink makes one push of what is pending
    }

    @Test
    fun `a change from the server's threads asks for a push too, a new message and a reply that lengthens the list`() {
        phone("!choose")
        ownerRuns()
        fromServer { manager.processNewUserMessage("FRESH") }
        assertEquals(2, pushes)
        assertEquals("1/6 FRESH", manager.chooserShown()?.let { "${it.flashy} ${it.str}" })
        fromServer { manager.onNewPostedAIReply(PostedAIAnswer(answer = "an answer", inReplyTo = "MESSAGE 2")) }
        assertEquals(3, pushes)
        assertEquals("1/7", manager.chooserShown()?.flashy)
        // a reply that was refused changed nothing
        fromServer { manager.onNewPostedAIReply(PostedAIAnswer(answer = " ", inReplyTo = "MESSAGE 2")) }
        assertEquals(3, pushes)
    }

    @Test
    fun `a message typed at the sign in chooser mode asks for a push when Enter stores it, not while it is typed`() {
        phone("!choose")
        ownerRuns()
        "hello".forEach(KeyboardManager::processNewKeyboardKey)
        ownerRuns()
        assertEquals(1, pushes)
        typingStarted(6_000)
        KeyboardManager.submitKeyboardInput()
        ownerRuns()
        assertEquals(2, pushes)
        assertEquals("1/6 hello", manager.chooserShown()?.let { "${it.flashy} ${it.str}" })
    }

    @Test
    fun `what a push sends takes nothing, a one-time command queued before leaving goes to the first real request`() {
        // the gate's case: !micOff, then !endchoose, on a sign that is not listening for the push
        phone("!choose")
        phone("!micOff")
        ownerRuns()
        val queued = repo.oneTimeMessages.toList()
        assertSame(Message.UtilityMessage.DisableMic, queued.single())

        repeat(20) { assertEquals("1/5", manager.chooserShown()?.flashy) } // any number of pushes in chooser mode
        phone("!endchoose")
        ownerRuns()
        repeat(20) { assertNull(manager.chooserShown()) } // and of the push on leaving: no frame at all

        assertEquals(queued, repo.oneTimeMessages.toList())
        assertEquals(5, repo.marqueeMessages.size)
        assertEquals(emptyList(), unsaved)
        // the sign asks: now, and only now, it is taken
        assertSame(Message.UtilityMessage.DisableMic, manager.getNextMessage())
        assertEquals("MESSAGE 1", manager.getNextMessage().str)
    }

    @Test
    fun `a stored message whose answer failed and a push that failed do not hand that message out twice`() {
        // Two things can now be "still owed" about one stored message: its answer that was not
        // written is tried again at its place, and a push of the chooser that stands on it is
        // tried again too. The push only ever reads; it must leave the retry as it is.
        val app = startWithTwoStoredMessages()
        var asked = 0
        app.manager.onChooserChanged = { asked++ }
        val first = app.manager.getNextMessage()
        assertEquals("MESSAGE 1", first.str)
        app.manager.returnUnsent(first) // its write failed: it is the next message again

        app.phone("!choose")
        ownerRuns()
        assertEquals(1, asked)
        // the push is made, fails and is made again, any number of times: each reads the chooser, and no more
        repeat(5) { assertEquals("1/2 MESSAGE 1", app.manager.chooserShown()?.let { "${it.flashy} ${it.str}" }) }
        app.phone("!endchoose")
        ownerRuns()
        assertEquals(2, asked)
        repeat(5) { assertNull(app.manager.chooserShown()) } // the attention alone, failing or not

        // the sign asks: the message once (the retry of its answer), then the one after it
        assertSame(first, app.manager.getNextMessage())
        assertEquals("MESSAGE 2", app.manager.getNextMessage().str)
        assertEquals("MESSAGE 1", app.manager.getNextMessage().str) // the next round
        assertEquals(2, app.repo.marqueeMessages.size)
        assertTrue(app.repo.oneTimeMessages.isEmpty())
    }

    @Test
    fun `outside chooser mode what a push would send is nothing, whatever is stored or queued`() {
        assertNull(manager.chooserShown())
        repo.marqueeMessages.clear()
        assertNull(manager.chooserShown())
        phone("!choose")
        ownerRuns()
        assertEquals("1/0 <empty>", manager.chooserShown()?.let { "${it.flashy} ${it.str}" })
    }

    // ---- there is no push switch: the old command is no command

    @Test
    fun `attn, the command of the switch that was, changes nothing, it is not taken for the ad period command and asks for no push`() {
        phone("!attn zeros")
        phone("!attn")
        phone("!attn  off ")
        phone("!ATTN Auto")
        phone("!attn 5") // it begins like the ad period command, and is no number behind it
        ownerRuns()
        assertEquals(0, repo.oneTimeMessages.size) // an ad period command would have announced itself
        assertEquals(emptyList(), unsaved) // and saved the period
        assertEquals(0, pushes)
    }

    // ---- the whole push: the real manager, the real frame maker and the real link, on a fake line

    @Test
    fun `from the phone's command to the line, entering is pushed once and published by the sign's own request, a step is zeros and its frame and is published, leaving is zeros alone, and nothing is taken`() {
        var now = 0L
        val written = mutableListOf<ByteArray>()
        val arrivals = ArrayDeque<Byte>()
        val wire = object : SignWire {
            override fun read(buffer: ByteArray, length: Int): Int {
                var n = 0
                while (n < length && arrivals.isNotEmpty()) buffer[n++] = arrivals.removeFirst()
                return n
            }

            override fun write(buffer: ByteArray, length: Int): Int = length.also { written += buffer.copyOf(length) }
            override fun close() {}
        }
        // wired as MainViewModel and EnergySignUartManager wire them
        val current = mutableListOf<String>()
        lateinit var link: SignLink
        val responder = SignResponder(
            nextMessage = { manager.getNextMessage() },
            write = { frame, reply -> link.send(frame, reply) },
            unsent = { manager.returnUnsent(it) },
        )
        val push = ChooserPush(shown = { manager.chooserShown() }, sent = { current += it.str })
        link = SignLink(
            wire = wire, baud = 14400, name = "UART",
            events = { owner.drain(onError = { throw it }) },
            answer = { buffer, count -> responder.onRequest(buffer, count)?.let { current += it.str } },
            chooserFrame = push::frame,
            pushed = push::written,
            timing = UartTimingLog(isEnabled = { false }, emit = {}),
            nanoTime = { now },
            sleepNanos = { now += it },
        )
        manager.onChooserChanged = link::requestPush
        try {
            phone("!choose")
            phone("!next")
            link.step()
            // one push for both commands: the run of zeros, then the frame of the newest state
            assertEquals(2, written.size)
            assertEquals(72, written[0].size)
            assertTrue(written[0].all { it == 0.toByte() })
            val frame = JsonParser.parseString(String(written[1])).asJsonObject
            assertEquals("H", frame["type"].asString)
            assertEquals("MESSAGE 2", frame["str"].asString)
            assertEquals("2/5", frame["flashy"].asString)
            // the very bytes a request would have got, and nothing behind the closing brace
            assertEquals(SignResponder.GSON.toJson(manager.chooserShown()), String(written[1]))
            assertEquals('}'.code.toByte(), written[1].last())
            // ENTERING is never taken from a push: the sign listens for one only in its chooser display, and it
            // was not there. The screen and Bluetooth are told nothing yet
            assertEquals(emptyList(), current)

            // the sign asks at the end of what it was showing: that answer is the selection, and it is published
            written.clear()
            repeat(300) { link.step() }
            assertEquals(emptyList(), written.map { it.size }) // once: nothing more unasked
            arrivals += '~'.code.toByte()
            link.step()
            assertEquals(SignResponder.GSON.toJson(manager.chooserShown()), String(written.single()))
            assertEquals(listOf("MESSAGE 2"), current)

            // a step in the chooser, to a sign that is in its chooser display: pushed, and the pushed text is the current text
            written.clear()
            phone("!next")
            repeat(300) { link.step() }
            assertEquals(2, written.size)
            assertTrue(written[0].all { it == 0.toByte() })
            assertEquals("MESSAGE 3", JsonParser.parseString(String(written[1])).asJsonObject["str"].asString)
            assertEquals(listOf("MESSAGE 2", "MESSAGE 3"), current)

            written.clear()
            phone("!micOff")
            phone("!endchoose")
            repeat(300) { link.step() } // more than the distance two pushes keep
            assertEquals(1, written.size)
            assertEquals(72, written[0].size)
            assertTrue(written[0].all { it == 0.toByte() })
            assertSame(Message.UtilityMessage.DisableMic, repo.oneTimeMessages.single()) // not taken by the push
            assertEquals(listOf("MESSAGE 2", "MESSAGE 3"), current)

            // the sign found nothing behind the attention and asks: that request takes the command
            written.clear()
            arrivals += '~'.code.toByte()
            link.step()
            assertEquals(SignResponder.GSON.toJson(Message.UtilityMessage.DisableMic), String(written.single()))
            assertTrue(repo.oneTimeMessages.isEmpty())
            assertEquals(3, current.size)
        } finally {
            manager.onChooserChanged = countPushes
        }
    }

    // ---- ads: the command, the restart, the period (PR 5)

    /**
     * The app as it is after a start on [home], its files directory: a MessageManager of its own, with its own
     * lists and its own saver (run by hand), on the owner of this test. Building a second one
     * on the same directory is a restart.
     */
    private inner class App(
        val home: File,
        /** On the lists this test's keyboard types at: there is one keyboard, and it has one repository (the test's own). */
        typedAt: Boolean = false,
        /** Track updates come on the owner's own thread, which is answered at once: for the tables of many cells. */
        private val fromTheOwner: Boolean = false,
        /** The app's clock is [now], which the test moves: for the game. */
        clocked: Boolean = false,
        /** The game of the app, when the test has to know its obstacles. */
        game: JumpGame = JumpGame(),
    ) {
        var now = 1_000L
        val unsaved = mutableListOf<Runnable>()
        val repo = if (typedAt) this@MessageManagerOwnerTest.repo else MessageRepository(saver = Executor { unsaved += it })
        val manager: MessageManager

        init {
            // (a MessageManager takes the keyboard's Enter for itself; the one of this test keeps it)
            val submitted = KeyboardManager.onSubmitted
            val clock = KeyboardManager.clock
            val taken = KeyboardManager.takenByTheGame
            val files = object : ContextWrapper(null) {
                override fun getFilesDir(): File = home
            }
            manager = if (clocked) MessageManager(files, repo, owner, nowMs = { now }, jumpGame = game) else MessageManager(files, repo, owner)
            KeyboardManager.onSubmitted = submitted
            // (and the keys of the game are those of the app the keyboard types at)
            if (!typedAt) {
                KeyboardManager.clock = clock
                KeyboardManager.takenByTheGame = taken
            }
        }

        fun phone(command: String) = manager.processNewBytes(command.toByteArray())

        /** A track update as the route makes it: it takes its turn, then the public entry, on a thread of its own, while the owner (this thread) takes its turns. */
        fun post(track: BeatLinkTrack, turn: MessageManager.TrackTurn = manager.acceptTrackUpdate()) =
            if (fromTheOwner) manager.processNowPlayingTrack(track, turn)
            else waitingCall("beatlink") { manager.processNowPlayingTrack(track, turn) }

        fun saverRuns() {
            while (unsaved.isNotEmpty()) unsaved.removeAt(0).run()
        }

        val adsFile get() = File(home, "ads.json")
        val periodFile get() = File(home, "ad-period.txt")
    }

    private fun newFilesDir(): File = Files.createTempDirectory(dir.toPath(), "ads").toFile()

    private val A = "🅰" // the A button: the ad command
    private val CL = "🆑" // a chonky ad
    private val O = "🅾" // a one-by-one ad
    private val CLOCK = "🕰"
    private val INVADER = "👾"

    @Test
    fun `an ad command changes nothing until the owner runs it, then the next requests get the ads`() {
        val app = App(newFilesDir())

        app.phone("!$A\n${CL}I <3 U\n$O$CLOCK${CLOCK}BYE")
        assertEquals(0, app.repo.oneTimeMessages.size)

        ownerRuns()
        val first = app.manager.getNextMessage()
        val second = app.manager.getNextMessage()

        assertTrue(first is Message.ColorMessage.ChonkySlide)
        assertEquals("I ${Message.HEART} U", first.str)
        assertTrue(second is Message.ColorMessage.OneByOneMessage)
        assertEquals("BYE", second.str)
        assertEquals(3000, (second as Message.ColorMessage.OneByOneMessage).delayMs.toInt())
    }

    @Test
    fun `the owner writes no ad file, the saver does, and the file is whole or not there`() {
        val app = App(newFilesDir())

        app.phone("!$A\n${CL}HELLO")
        ownerRuns()

        assertEquals(1, app.unsaved.size)
        assertTrue(!app.adsFile.exists(), "nothing on disk before the saver runs")

        app.saverRuns()

        assertEquals(listOf("HELLO"), AdCodec.decode(app.adsFile.readText()).map { it.str })
        assertEquals(listOf("ads.json"), app.home.list()!!.filter { it.startsWith("ads") }, "nothing left beside it")
    }

    @Test
    fun `a later change to the ads does not get into an earlier save`() {
        val app = App(newFilesDir())

        app.phone("!$A\n${CL}FIRST")
        ownerRuns()
        app.phone("!$A\n${CL}SECOND")
        ownerRuns()
        assertEquals(2, app.unsaved.size)

        app.unsaved.removeAt(0).run()
        assertEquals(listOf("FIRST"), AdCodec.decode(app.adsFile.readText()).map { it.str })
        app.saverRuns()
        assertEquals(listOf("SECOND"), AdCodec.decode(app.adsFile.readText()).map { it.str })
    }

    @Test
    fun `the ads that were set are shown again after a restart`() {
        val filesDir = newFilesDir()
        App(filesDir).run {
            phone("!$A\n${CL}HELLO\n$O${CLOCK}WORLD\n$INVADER")
            ownerRuns()
            saverRuns()
        }

        val restarted = App(filesDir)

        // nothing stored and nothing queued: a request gets the ad round
        val shown = (1..3).map { restarted.manager.getNextMessage() }
        assertTrue(shown[0] is Message.ColorMessage.ChonkySlide)
        assertEquals("HELLO", shown[0].str)
        assertTrue(shown[1] is Message.ColorMessage.OneByOneMessage)
        assertEquals("WORLD", shown[1].str)
        assertEquals(2000, (shown[1] as Message.ColorMessage.OneByOneMessage).delayMs.toInt())
        assertTrue(shown[2] is Message.ColorMessage.IconInvaders.Enemy1)
        assertEquals(emptyList(), restarted.unsaved, "starting writes nothing")
    }

    @Test
    fun `a first start shows the default ads and writes no file`() {
        val app = App(newFilesDir())

        assertTrue(app.manager.getNextMessage() is Message.ColorMessage.IconInvaders.Enemy1)
        assertTrue(app.manager.getNextMessage() is Message.ColorMessage.IconInvaders.Enemy2)
        assertTrue(!app.adsFile.exists())
        assertEquals(emptyList(), app.unsaved)
    }

    @Test
    fun `with no ads and no messages a request is answered with a starfield, before and after a restart`() {
        val filesDir = newFilesDir()
        val app = App(filesDir)
        app.phone("!$A") // the ad list emptied on purpose
        ownerRuns()
        app.saverRuns()
        assertEquals(0, app.repo.marqueeMessages.size)

        repeat(3) { assertTrue(app.manager.getNextMessage() is Message.Starfield) }

        val restarted = App(filesDir)
        assertEquals("[]", restarted.adsFile.readText())
        repeat(3) { assertTrue(restarted.manager.getNextMessage() is Message.Starfield, "the defaults do not come back") }

        // and a message that arrives is shown at once: its announcement, then the message, and no starfield before them
        restarted.manager.processNewUserMessage("HELLO")
        ownerRuns()
        val next = (1..3).map { restarted.manager.getNextMessage() }
        assertSame(Message.CountDownAnnouncement.NewMessageAnnouncement, next[0])
        assertTrue(next[1] is Message.Marquee.User)
        assertEquals("HELLO", next[1].str)
        assertEquals("HELLO", next[2].str, "the one stored message comes round again")
    }

    @Test
    fun `an ad list that is only the place of a track, with no track and no messages, gets a starfield too`() {
        val filesDir = newFilesDir()
        val app = App(filesDir)
        app.phone("!$A\n\uD83D\uDEE4")
        ownerRuns()
        app.saverRuns()
        assertEquals("""[{"str":"","type":"T"}]""", app.adsFile.readText())

        // the ad list is not empty, and still there is nothing to show
        repeat(3) { assertTrue(app.manager.getNextMessage() is Message.Starfield) }

        val restarted = App(filesDir)
        repeat(3) { assertTrue(restarted.manager.getNextMessage() is Message.Starfield) }

        restarted.manager.processNewUserMessage("HELLO")
        ownerRuns()
        assertSame(Message.CountDownAnnouncement.NewMessageAnnouncement, restarted.manager.getNextMessage())
        assertEquals("HELLO", restarted.manager.getNextMessage().str)
    }

    @Test
    fun `an ad file that cannot be read gives the default ads and is left as it is`() {
        val filesDir = newFilesDir()
        val broken = "[{\"str\":\"CUT OFF\",\"ty"
        File(filesDir, "ads.json").writeText(broken)

        val app = App(filesDir)

        assertTrue(app.manager.getNextMessage() is Message.ColorMessage.IconInvaders.Enemy1)
        assertEquals(broken, app.adsFile.readText())
        assertEquals(emptyList(), app.unsaved)
    }

    private fun adEveryFlash(app: App): String? =
        app.repo.oneTimeMessages.filterIsInstance<Message.FlashingAnnouncement>().singleOrNull()?.str

    @Test
    fun `the ad period is set by the owner, written by the saver and there again after a restart`() {
        val filesDir = newFilesDir()
        val app = App(filesDir)

        app.phone("!A3")
        assertNull(adEveryFlash(app))
        ownerRuns()

        assertEquals("AD EVERY=3", adEveryFlash(app))
        assertTrue(!app.periodFile.exists(), "nothing on disk before the saver runs")
        app.saverRuns()
        assertEquals("3", app.periodFile.readText())

        // a restart: a refused period shows the value that stays, which is the restored one
        val restarted = App(filesDir)
        restarted.phone("!A0")
        ownerRuns()
        assertEquals("AD EVERY=3", adEveryFlash(restarted))
    }

    @Test
    fun `a period of zero or less is refused, nothing is saved and the next messages are still served`() {
        val app = App(newFilesDir())
        app.repo.marqueeMessages.addAll((1..3).map { Message.Marquee.User("MESSAGE $it") })

        app.phone("!A0")
        ownerRuns()
        assertEquals("AD EVERY=8", adEveryFlash(app))
        app.repo.oneTimeMessages.clear()
        app.phone("!A-2")
        ownerRuns()
        assertEquals("AD EVERY=8", adEveryFlash(app))
        app.repo.oneTimeMessages.clear()

        assertEquals(emptyList(), app.unsaved)
        // with a period of 0 the first stored message threw (a division by zero)
        assertEquals(
            listOf("MESSAGE 1", "MESSAGE 2", "MESSAGE 3", "MESSAGE 1"),
            (1..4).map { app.manager.getNextMessage().str }
        )
    }

    @Test
    fun `after a restart on a file of the old app, what goes to the sign is held to the rules of an ad line`() {
        // As the old app saved them: an ad of 120 apostrophes (720 bytes of text as JSON) and an
        // ad with 32 clocks, whose 33000 ms had overflowed to -32536.
        val filesDir = newFilesDir()
        val legacy = "[{\"dly\":1000,\"str\":\"" + "\u0027".repeat(120) + "\",\"type\":\"O\",\"b\":3,\"g\":2,\"r\":1}," +
                "{\"dly\":-32536,\"scroll\":false,\"str\":\"LONG\",\"type\":\"C\",\"b\":3,\"g\":2,\"r\":1}]"
        File(filesDir, "ads.json").writeText(legacy)

        val restarted = App(filesDir)
        // nothing stored and nothing queued: the requests get the ad round, twice
        val sent = (1..4).map { restarted.manager.getNextMessage() }

        sent.forEach { message ->
            val bytes = SignResponder.GSON.toJson(message).toByteArray().size
            assertTrue(bytes <= AdSizeLimit.MAX_AD_JSON_BYTES, "$bytes bytes went to the sign")
        }
        val apostrophes = sent[0] as Message.ColorMessage.OneByOneMessage
        assertTrue(apostrophes.str.isNotEmpty() && apostrophes.str.all { it == '\'' })
        val long = sent[1] as Message.ColorMessage.ChonkySlide
        assertEquals("LONG", long.str)
        assertEquals(30_000, long.delayMs.toInt())
        assertEquals(listOf(apostrophes.str, "LONG"), sent.drop(2).map { it.str }, "the next ad round is the same")
        // reading changes nothing on disk; the next ad command writes the file anew
        assertEquals(legacy, restarted.adsFile.readText())
        assertEquals(emptyList(), restarted.unsaved)
    }

    @Test
    fun `an ad without a text never goes to the sign, from a command or from the file`() {
        // from a command: the second line is a chonky ad with a clock and no text
        val filesDir = newFilesDir()
        val app = App(filesDir)
        app.phone("!$A\n${CL}ONE\n$CL$CLOCK\n${O}TWO")
        ownerRuns()
        app.saverRuns()

        assertEquals(listOf("ONE", "TWO", "ONE", "TWO"), (1..4).map { app.manager.getNextMessage().str })
        assertEquals(listOf("ONE", "TWO"), AdCodec.decode(app.adsFile.readText()).map { it.str })

        // from a file of the old app, which saved the empty ad
        val oldDir = newFilesDir()
        File(oldDir, "ads.json").writeText(
            "[{\"dly\":2000,\"scroll\":false,\"str\":\"\",\"type\":\"C\",\"b\":3,\"g\":2,\"r\":1}," +
                    "{\"dly\":1000,\"str\":\"TWO\",\"type\":\"O\",\"b\":3,\"g\":2,\"r\":1}]"
        )
        val restarted = App(oldDir)

        assertEquals(listOf("TWO", "TWO", "TWO"), (1..3).map { restarted.manager.getNextMessage().str })
    }

    @Test
    fun `after a restart the saved period is the one the ads are shown by`() {
        val filesDir = newFilesDir()
        App(filesDir).run {
            phone("!A3")
            ownerRuns()
            saverRuns()
        }

        val restarted = App(filesDir)
        restarted.repo.marqueeMessages.addAll((1..7).map { Message.Marquee.User("MESSAGE $it") })

        // three stored messages, then the ad round (the two default icons, "1" and "2"), and again
        assertEquals(
            listOf(
                "MESSAGE 1", "MESSAGE 2", "MESSAGE 3", "1", "2",
                "MESSAGE 4", "MESSAGE 5", "MESSAGE 6", "1", "2",
                "MESSAGE 7",
            ),
            (1..11).map { restarted.manager.getNextMessage().str }
        )
    }

    @Test
    fun `a new period is the one the ads are shown by at once, and a refused one changes nothing`() {
        val app = App(newFilesDir())
        app.repo.marqueeMessages.addAll((1..5).map { Message.Marquee.User("MESSAGE $it") })
        app.phone("!A2")
        app.phone("!A0")
        ownerRuns()
        app.repo.oneTimeMessages.clear() // the two "AD EVERY=2" flashes and their stars

        assertEquals(
            listOf("MESSAGE 1", "MESSAGE 2", "1", "2", "MESSAGE 3", "MESSAGE 4", "1", "2", "MESSAGE 5"),
            (1..9).map { app.manager.getNextMessage().str }
        )
    }

    @Test
    fun `the saver keeps only the newest message list, and every ad save and period save beside it`() {
        // The message list has one place at the saver, where a newer list takes the place of one
        // that is waiting. Ad saves and period saves do not share that place: each is a job of
        // its own, in the order it was asked for.
        val app = App(newFilesDir())
        app.phone("!$A\n${CL}FIRST")   // an ad save
        app.phone("ONE")               // a message save
        app.phone("!A4")               // a period save
        app.phone("TWO")               // a message save: takes the place of the one waiting
        app.phone("!$A\n${CL}SECOND")  // an ad save
        app.phone("!A6")               // a period save
        app.phone("THREE")             // a message save, again in the one place
        ownerRuns()

        // ad, the message list (one job for the three saves), period, ad, period
        assertEquals(5, app.unsaved.size)

        app.unsaved.removeAt(0).run()
        assertEquals(listOf("FIRST"), AdCodec.decode(app.adsFile.readText()).map { it.str })
        assertTrue(!app.periodFile.exists())

        app.unsaved.removeAt(0).run()
        assertEquals(listOf("ONE", "TWO", "THREE"), File(app.home, "signstrings.txt").readLines(), "the newest list, whole")
        assertEquals(listOf("FIRST"), AdCodec.decode(app.adsFile.readText()).map { it.str }, "a message save writes no ad file")

        app.unsaved.removeAt(0).run()
        assertEquals("4", app.periodFile.readText())

        app.unsaved.removeAt(0).run()
        assertEquals(listOf("SECOND"), AdCodec.decode(app.adsFile.readText()).map { it.str })
        assertEquals("4", app.periodFile.readText())

        app.unsaved.removeAt(0).run()
        assertEquals("6", app.periodFile.readText())
        assertEquals(listOf("ONE", "TWO", "THREE"), File(app.home, "signstrings.txt").readLines())
    }

    @Test
    fun `the line of a track holds only what the sign can show, in the ad round and in the announcement`() {
        // a bell in the artist; a NUL and a vertical tab in the title: the tab is a thin space and stays
        val app = App(newFilesDir())
        app.post(BeatLinkTrack(id = 2, title = "A\u0000B\u000bC", artist = "D\u0007J"))
        ownerRuns()
        app.phone("!$A\n\uD83D\uDEE4")
        ownerRuns()

        // the ad round (CURRENT, TRACK:, the track), then the announcement (NOW PLAYING, the track, stars)
        val sent = (1..6).map { com.google.gson.JsonParser.parseString(frameFor(app)).asJsonObject }

        assertEquals(listOf("O", "O", "T", "F", "T", "S"), sent.map { it["type"].asString })
        assertEquals("DJ - AB\u000bC", sent[2]["str"].asString, "the line in the ad round")
        assertEquals("DJ - AB\u000bC", sent[4]["str"].asString, "the line of the announcement")
    }

    /** A call that waits for the owner, on a thread of its own, while this thread (the owner) takes its turns. */
    private fun <T> waitingCall(name: String, call: () -> T): T {
        val result = FutureTask(Callable(call))
        Thread(result, name).start()
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

    /** The lines of the log while [block] runs that hold [marker] (not the last line of before, which a new listener is handed). */
    private fun logged(marker: String, block: () -> Unit): List<String> {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watching = cx.aphex.energysign.ScreenLogger.logLines.subscribe { line -> if (marker in line) lines += line }
        lines.clear()
        try {
            block()
        } finally {
            watching.dispose()
        }
        return lines.toList()
    }

    private val stopOfTheTrack = BeatLinkTrack(id = 0, title = "", artist = "", isEmpty = true)

    @Test
    fun `the line of a track is made, and cut to fit, by the thread that brings it, while the owner does nothing`() {
        // Through the public entry. When the line is made, a line of the log says so, with the
        // length the line was cut to: so the thread that logs it is the thread that made it.
        val app = App(newFilesDir())
        val turn = app.manager.acceptTrackUpdate()
        val madeBy = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watching = cx.aphex.energysign.ScreenLogger.logLines.subscribe { line ->
            if ("Track update ${turn.number}: its line is made" in line) madeBy += Thread.currentThread().name + ": " + line.substringAfter("made, ")
        }
        madeBy.clear() // (a new listener is handed the last line of before)
        try {
            val call = FutureTask(Callable {
                app.manager.processNowPlayingTrack(BeatLinkTrack(id = 7, title = "B".repeat(750), artist = "DJ"), turn)
            })
            Thread(call, "beatlink").start()

            // The owner is this thread, and it runs nothing here: it only waits to see the line made.
            val deadline = System.currentTimeMillis() + 5_000
            while (madeBy.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(1)
            // {"str":"","type":"T"} is 21 bytes: 479 characters make 500, "DJ - " and 474 of the 750 B
            assertEquals(listOf("beatlink: 479 characters."), madeBy.toList())
            assertFalse(call.isDone, "the caller waits for the owner to take the track in")
            assertEquals(0, app.repo.oneTimeMessages.size, "the owner has done nothing yet")

            while (!call.isDone) {
                ownerRuns()
                Thread.sleep(1)
            }
            call.get()
        } finally {
            watching.dispose()
        }

        assertEquals(1, madeBy.size, "the line was made once: the owner made none of its own")
        val track = app.repo.oneTimeMessages.filterIsInstance<Message.NowPlayingTrackMessage>().single()
        assertEquals("DJ - " + "B".repeat(474), track.str)
        assertEquals(500, SignResponder.GSON.toJson(track).toByteArray(Charsets.UTF_8).size)
        // and the ad round takes that same message: nothing is fitted again for it
        app.phone("!$A\n\uD83D\uDEE4")
        ownerRuns()
        val lines = app.repo.oneTimeMessages.filterIsInstance<Message.NowPlayingTrackMessage>()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it === track })
    }

    @Test
    fun `a track update that a newer one overtook is left out, a stop is not undone by the track before it`() {
        // ("Current" is playing at the start: a stop that was not taken in would leave it showing)
        val app = appWithCurrentPlaying()

        // The long track comes in first and takes its turn; before it is taken in, the stop
        // comes in, takes the next turn, and is at the owner first.
        val turnOfTheTrack = app.manager.acceptTrackUpdate()
        val turnOfTheStop = app.manager.acceptTrackUpdate()
        app.post(stopOfTheTrack, turnOfTheStop)
        app.repo.oneTimeMessages.clear() // the ad round a stop asks for
        app.post(BeatLinkTrack(id = 9, title = "C".repeat(750), artist = "DJ"), turnOfTheTrack)

        // the stopped track is not announced and is not playing
        assertEquals(emptyList(), app.repo.oneTimeMessages.toList())
        app.phone("!$A\n\uD83D\uDEE4\n${O}AD")
        ownerRuns()
        assertEquals(listOf("AD"), app.repo.oneTimeMessages.map { it.str }, "no track line in the ad round")
    }

    @Test
    fun `turns are given in the order they are asked for, and updates that come in order are all taken in`() {
        val app = App(newFilesDir())
        val before = app.manager.trackTurnsTaken
        val first = app.manager.acceptTrackUpdate()
        val second = app.manager.acceptTrackUpdate()
        assertEquals(before + 2, app.manager.trackTurnsTaken)

        fun queuedLines() = app.repo.oneTimeMessages.filterIsInstance<Message.NowPlayingTrackMessage>().map { it.str }

        app.post(BeatLinkTrack(id = 11, title = "One", artist = "DJ"), first)
        // the first is taken in, although a second turn has been given out already
        assertEquals(listOf("DJ - One"), queuedLines())

        app.post(BeatLinkTrack(id = 12, title = "Two", artist = "DJ"), second)
        // the newer track took the place of the older one in the queue
        assertEquals(listOf("DJ - Two"), queuedLines())

        app.post(BeatLinkTrack(id = 13, title = "Three", artist = "DJ"))
        assertEquals(listOf("DJ - Three"), queuedLines())
    }

    @Test
    fun `a track that arrives after a newer track is left out too, not only one that arrives after a stop`() {
        val app = App(newFilesDir())
        val older = app.manager.acceptTrackUpdate()
        val newer = app.manager.acceptTrackUpdate()

        app.post(BeatLinkTrack(id = 31, title = "Newer", artist = "DJ"), newer)
        app.post(BeatLinkTrack(id = 32, title = "Older", artist = "DJ"), older)

        assertEquals(listOf("DJ - Newer"), app.repo.oneTimeMessages.filterIsInstance<Message.NowPlayingTrackMessage>().map { it.str })
    }

    @Test
    fun `the cut of a track's line is reported once, when the track is taken in, not again for the same track`() {
        val app = App(newFilesDir())
        val long = BeatLinkTrack(id = 21, title = "Q".repeat(750), artist = "DJ")

        val afterTheFirst = logged("DJ - QQQQ") { app.post(long) }
        assertEquals(1, afterTheFirst.size, afterTheFirst.toString())
        assertTrue("Ad cut from" in afterTheFirst.single())
        assertEquals(1, app.repo.oneTimeMessages.count { it is Message.NowPlayingTrackMessage }, "the first post is announced")

        val afterTheSecond = logged("DJ - QQQQ") { app.post(long) } // the same track again: it is not shown again

        assertEquals(emptyList(), afterTheSecond)
        assertEquals(1, app.repo.oneTimeMessages.count { it is Message.NowPlayingTrackMessage }, "and its announcement is still the one queued")
    }

    @Test
    fun `a track update that is left out reports nothing about its line`() {
        val app = App(newFilesDir())
        val turnOfTheTrack = app.manager.acceptTrackUpdate()
        val turnOfTheStop = app.manager.acceptTrackUpdate()

        val cuts = logged("DJ - ZZZZ") {
            app.post(stopOfTheTrack, turnOfTheStop)
            app.post(BeatLinkTrack(id = 22, title = "Z".repeat(750), artist = "DJ"), turnOfTheTrack)
        }

        assertEquals(emptyList(), cuts)
    }

    // ---- WHAT IS PLAYING: THE TABLE.
    // The rule: what is playing is decided by the update that was ACCEPTED last, alone, whatever
    // it is and in whatever order the updates reach the owner. Announcing is a second question.

    private enum class Kind(val short: String) {
        NEW("a new track"), PLAYED_BEFORE("a track played before"), SAME_AGAIN("the track that is playing"), STOP("a stop")
    }

    private val oldTrack = BeatLinkTrack(id = 100, title = "Old", artist = "DJ")
    private val currentTrack = BeatLinkTrack(id = 101, title = "Current", artist = "DJ")
    private val trackAds = "!$A\n🛤\n${O}AD"

    /** The update of [kind] that is the [position]th of a sequence (two new tracks of one sequence are two tracks). */
    private fun updateOf(kind: Kind, position: Int): BeatLinkTrack = when (kind) {
        Kind.NEW -> BeatLinkTrack(id = 200 + position, title = "New $position", artist = "DJ")
        Kind.PLAYED_BEFORE -> oldTrack
        Kind.SAME_AGAIN -> currentTrack
        Kind.STOP -> stopOfTheTrack
    }

    /** The sign asks until nothing is queued: everything that was queued has been handed to it. */
    private fun signReadsTheQueue(app: App) {
        var asked = 0
        while (app.repo.oneTimeMessages.isNotEmpty()) {
            app.manager.getNextMessage()
            check(++asked < 100) { "the queue does not get shorter: ${app.repo.oneTimeMessages.map { it.str }}" }
        }
    }

    private fun lineOf(track: BeatLinkTrack): String? = if (track.isEmpty) null else "${track.artist} - ${track.title}"

    /** The start of every cell: "Old" was played, then "Current", which is playing; both were shown; nothing is queued. The ad list is the place of the track and one ad. */
    private fun appWithCurrentPlaying(adRoundWaiting: Boolean = false, typedAt: Boolean = false, fromTheOwner: Boolean = false): App {
        val app = App(newFilesDir(), typedAt, fromTheOwner)
        app.phone(trackAds)
        ownerRuns()
        app.post(oldTrack)
        signReadsTheQueue(app) // the sign is handed its announcement: an emptied queue would not be that
        app.post(currentTrack)
        signReadsTheQueue(app) // and this one
        if (adRoundWaiting) {
            app.phone(trackAds)
            ownerRuns()
            assertEquals(listOf("CURRENT", "TRACK:", "DJ - Current", "AD"), app.repo.oneTimeMessages.map { it.str })
        }
        return app
    }

    /**
     * What is wrong with what the sign will be shown, when [playing] should be playing (null:
     * nothing) and that track should be announced or not ([announced]). Empty when all is right.
     * Looks at the queue, then at the next ad round.
     */
    private fun wrongOnTheSign(app: App, playing: String?, announced: Boolean): List<String> {
        val wrong = mutableListOf<String>()
        val queue = app.repo.oneTimeMessages.toList()
        val lines = queue.filterIsInstance<Message.NowPlayingTrackMessage>().map { it.str }
        if (lines.any { it != playing }) wrong += "queued: the line of another track $lines"
        val announcements = queue.indices.filter { queue[it] === Message.FlashingAnnouncement.NowPlayingAnnouncement }
        if (announcements.size != (if (announced) 1 else 0)) wrong += "queued: ${announcements.size} announcement(s)"
        announcements.forEach { i ->
            if ((queue.getOrNull(i + 1) as? Message.NowPlayingTrackMessage)?.str != playing) wrong += "queued: an announcement without the line of what is playing"
        }
        queue.forEachIndexed { i, message ->
            if (message.str == "TRACK:" && (queue.getOrNull(i + 1) as? Message.NowPlayingTrackMessage)?.str != playing) {
                wrong += "queued: the heading of an ad round's track without the line of what is playing"
            }
        }
        // its stars come and go with the announcement: none are left behind by a track that is gone
        val stars = queue.count { it is Message.Starfield }
        if (stars != announcements.size) wrong += "queued: $stars starfield(s) for ${announcements.size} announcement(s)"
        // and stops do not pile up ad rounds
        val ads = queue.count { it.str == "AD" }
        if (ads > 1) wrong += "queued: the ad $ads times"
        app.repo.oneTimeMessages.clear()
        app.phone(trackAds)
        ownerRuns()
        val adRound = app.repo.oneTimeMessages.map { it.str }
        val right = if (playing == null) listOf("AD") else listOf("CURRENT", "TRACK:", playing, "AD")
        if (adRound != right) wrong += "the next ad round shows $adRound"
        return wrong
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    /**
     * Every one of [sequences] (the updates in the order they are ACCEPTED) with every order in
     * which its updates can reach the owner. Returns the cells that are wrong, as lines.
     */
    private fun wrongCells(sequences: List<List<Kind>>, adRoundWaiting: Boolean = false): List<String> {
        val wrong = mutableListOf<String>()
        for (sequence in sequences) {
            for (arrival in permutations(sequence.indices.toList())) {
                val app = appWithCurrentPlaying(adRoundWaiting)
                val updates = sequence.mapIndexed { i, kind -> updateOf(kind, i + 1) }
                val turns = sequence.map { app.manager.acceptTrackUpdate() }
                arrival.forEach { i -> app.post(updates[i], turns[i]) }
                // the last ACCEPTED decides, and it is announced when it is a track never played before
                val problems = wrongOnTheSign(app, playing = lineOf(updates.last()), announced = sequence.last() == Kind.NEW)
                if (problems.isNotEmpty()) {
                    wrong += "accepted: " + sequence.joinToString(", then ") { it.short } +
                            " | reaching the owner in the order " + arrival.map { it + 1 } + " | " + problems.joinToString("; ")
                }
            }
        }
        return wrong
    }

    private fun sequencesOf(length: Int): List<List<Kind>> =
        if (length == 0) listOf(emptyList()) else sequencesOf(length - 1).flatMap { head -> Kind.values().map { head + it } }

    @Test
    fun `the start of the table is as the table says`() {
        val app = appWithCurrentPlaying()

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Current", announced = false))
    }

    @Test
    fun `every single update decides what is playing`() {
        assertEquals(emptyList(), wrongCells(sequencesOf(1)), "wrong cells")
    }

    @Test
    fun `every pair of updates in both orders of arrival, the one accepted last decides what is playing`() {
        val wrong = wrongCells(sequencesOf(2))

        assertEquals(emptyList(), wrong, "${wrong.size} wrong cells of 32")
    }

    @Test
    fun `the same single updates and pairs with an ad round of the playing track waiting in the queue`() {
        // (the round's heading and line must go when the track changes, and stay when it does not)
        val wrong = wrongCells(sequencesOf(1), adRoundWaiting = true) + wrongCells(sequencesOf(2), adRoundWaiting = true)

        assertEquals(emptyList(), wrong, "${wrong.size} wrong cells of 36")
    }

    @Test
    fun `an ad round that is waiting keeps its track when the same track is posted again, and loses it whole when another comes`() {
        val same = appWithCurrentPlaying(adRoundWaiting = true)
        same.post(currentTrack)
        assertEquals(listOf("CURRENT", "TRACK:", "DJ - Current", "AD"), same.repo.oneTimeMessages.map { it.str })

        val other = appWithCurrentPlaying(adRoundWaiting = true)
        other.post(oldTrack) // played before: it is not announced, it only takes the place
        assertEquals(listOf("AD"), other.repo.oneTimeMessages.map { it.str })

        val fresh = appWithCurrentPlaying(adRoundWaiting = true)
        fresh.post(updateOf(Kind.NEW, 1))
        assertEquals(listOf("AD", "NOW${Message.VT}PLAYING", "DJ - New 1", ""), fresh.repo.oneTimeMessages.map { it.str })
    }

    @Test
    fun `every three updates in all six orders of arrival, starting with a new track or a track played before`() {
        val wrong = wrongCells(sequencesOf(3).filter { it.first() == Kind.NEW || it.first() == Kind.PLAYED_BEFORE })

        assertEquals(emptyList(), wrong, "${wrong.size} wrong cells of 192")
    }

    @Test
    fun `every three updates in all six orders of arrival, starting with the track that is playing or a stop`() {
        val wrong = wrongCells(sequencesOf(3).filter { it.first() == Kind.SAME_AGAIN || it.first() == Kind.STOP })

        assertEquals(emptyList(), wrong, "${wrong.size} wrong cells of 192")
    }

    @Test
    fun `A, then B, then a stop and a repeat of A, the repeat reaching the owner first or last, A is what plays`() {
        // the sequence the gate found: the stop was accepted third, the repeat of A fourth
        for (repeatFirst in listOf(true, false)) {
            val app = appWithCurrentPlaying() // A is "Old", B is "Current"
            val turnOfTheStop = app.manager.acceptTrackUpdate()
            val turnOfTheRepeat = app.manager.acceptTrackUpdate()
            if (repeatFirst) {
                app.post(oldTrack, turnOfTheRepeat)
                app.post(stopOfTheTrack, turnOfTheStop)
            } else {
                app.post(stopOfTheTrack, turnOfTheStop)
                app.post(oldTrack, turnOfTheRepeat)
            }

            assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Old", announced = false), "the repeat first: $repeatFirst")
        }
    }

    @Test
    fun `A, then B, then a repeat of A and a stop, the stop was accepted last, nothing plays`() {
        for (stopFirst in listOf(true, false)) {
            val app = appWithCurrentPlaying()
            val turnOfTheRepeat = app.manager.acceptTrackUpdate()
            val turnOfTheStop = app.manager.acceptTrackUpdate()
            if (stopFirst) {
                app.post(stopOfTheTrack, turnOfTheStop)
                app.post(oldTrack, turnOfTheRepeat)
            } else {
                app.post(oldTrack, turnOfTheRepeat)
                app.post(stopOfTheTrack, turnOfTheStop)
            }

            assertEquals(emptyList(), wrongOnTheSign(app, playing = null, announced = false), "the stop first: $stopFirst")
        }
    }

    @Test
    fun `a new track posted twice is announced once, and the announcement is not lost to the second post`() {
        for (secondFirst in listOf(false, true)) {
            val app = appWithCurrentPlaying()
            val fresh = updateOf(Kind.NEW, 1)
            val first = app.manager.acceptTrackUpdate()
            val second = app.manager.acceptTrackUpdate()
            if (secondFirst) {
                app.post(fresh, second)
                app.post(fresh, first)
            } else {
                app.post(fresh, first)
                assertEquals(1, app.repo.oneTimeMessages.count { it === Message.FlashingAnnouncement.NowPlayingAnnouncement }, "after the first post")
                app.post(fresh, second)
            }

            assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - New 1", announced = true), "the second first: $secondFirst")
        }
    }

    @Test
    fun `a stop takes the announcement and the ad round's line of the stopped track out of the queue`() {
        val app = appWithCurrentPlaying()
        app.post(updateOf(Kind.NEW, 1))             // announced: NOW PLAYING, its line, stars
        app.phone(trackAds)                          // and an ad round with its line is queued
        ownerRuns()
        assertEquals(2, app.repo.oneTimeMessages.count { it is Message.NowPlayingTrackMessage }, "before the stop: the line is queued twice")

        app.post(stopOfTheTrack)

        assertEquals(emptyList(), wrongOnTheSign(app, playing = null, announced = false))
    }

    @Test
    fun `an ad that says CURRENT or TRACK itself is an ad, a change of track does not take it out`() {
        // (in this test every colour is 0, so the owner's two ads EQUAL the two heading lines of
        // an ad round; they are told apart by which objects they are)
        val app = App(newFilesDir())
        val ads = "!$A\n${O}CURRENT\n${O}TRACK:\n\uD83D\uDEE4\n${O}AD"
        app.phone(ads)
        ownerRuns()
        app.post(currentTrack)
        app.repo.oneTimeMessages.clear()
        app.phone(ads)
        ownerRuns()
        assertEquals(listOf("CURRENT", "TRACK:", "CURRENT", "TRACK:", "DJ - Current", "AD"), app.repo.oneTimeMessages.map { it.str })

        app.post(oldTrack) // another track takes the place; it was not played here before, so it is announced

        // the round keeps the owner's two ads and loses the two heading lines and the line
        assertEquals(
            listOf("CURRENT", "TRACK:", "AD", "NOW${Message.VT}PLAYING", "DJ - Old", ""),
            app.repo.oneTimeMessages.map { it.str }
        )
    }

    // ---- WHAT IS QUEUED FOR A TRACK IS ONE PIECE: its announcement, its line, its stars, the
    // heading and line in an ad round, and its mark as "played". It goes or stays as one.

    /** Posts [changes] updates, the sign reading nothing in between, and returns the length of the queue after each. */
    private fun queueLengths(app: App, changes: Int, update: (Int) -> BeatLinkTrack): List<Int> =
        (1..changes).map { i ->
            app.post(update(i))
            app.repo.oneTimeMessages.size
        }

    private val trackA = BeatLinkTrack(id = 501, title = "A", artist = "DJ")
    private val trackB = BeatLinkTrack(id = 502, title = "B", artist = "DJ")

    @Test
    fun `while the sign reads nothing, two tracks taking turns leave one announcement in the queue, after 2, 3 and 10 changes`() {
        for (changes in listOf(2, 3, 10)) {
            val app = appWithCurrentPlaying()

            val lengths = queueLengths(app, changes) { i -> if (i % 2 == 1) trackA else trackB }

            // NOW PLAYING, the line, its stars: three, however many changes there were
            assertEquals(List(changes) { 3 }, lengths, "$changes changes")
            val last = if (changes % 2 == 1) "DJ - A" else "DJ - B"
            assertEquals(listOf("NOW${Message.VT}PLAYING", last, ""), app.repo.oneTimeMessages.map { it.str }, "$changes changes")
            assertEquals(emptyList(), wrongOnTheSign(app, playing = last, announced = true), "$changes changes")
        }
    }

    @Test
    fun `the same in chooser mode and paused`() {
        for (mode in listOf("!choose", "!pause")) {
            val app = appWithCurrentPlaying()
            app.phone(mode)
            ownerRuns()

            val lengths = queueLengths(app, 10) { i -> if (i % 2 == 1) trackA else trackB }

            assertEquals(List(10) { 3 }, lengths, mode)
            app.phone(if (mode == "!choose") "!endchoose" else "!unpause")
            ownerRuns()
            assertEquals(listOf("NOW${Message.VT}PLAYING", "DJ - B", ""), app.repo.oneTimeMessages.map { it.str }, mode)
        }
    }

    @Test
    fun `while the sign reads nothing, a track and a stop taking turns do not pile up ad rounds`() {
        val app = appWithCurrentPlaying()

        val lengths = queueLengths(app, 10) { i -> if (i % 2 == 1) trackA else stopOfTheTrack }

        // after a track: the one ad round a stop asked for, and the announcement; after a stop: the ad round
        assertTrue(lengths.all { it <= 4 }, lengths.toString())
        assertEquals(listOf("AD"), app.repo.oneTimeMessages.map { it.str })
        assertEquals(emptyList(), wrongOnTheSign(app, playing = null, announced = false))
    }

    @Test
    fun `stars that are not a track's own stay in the queue when the track changes`() {
        val app = appWithCurrentPlaying()
        app.post(trackA)                                   // NOW PLAYING, its line, its stars
        app.repo.enqueueOneTimeMessage(Message.Starfield()) // somebody else's stars, equal to look at

        app.post(trackB)

        assertEquals(listOf("", "NOW${Message.VT}PLAYING", "DJ - B", ""), app.repo.oneTimeMessages.map { it.str })
    }

    @Test
    fun `tracks played before taking turns queue nothing at all`() {
        val app = appWithCurrentPlaying()

        val lengths = queueLengths(app, 10) { i -> if (i % 2 == 1) oldTrack else currentTrack }

        assertEquals(List(10) { 0 }, lengths)
    }

    @Test
    fun `one line under two ids, stopped before the sign read the announcement, is announced when either id comes back`() {
        for (comesBack in listOf(601, 602)) {
            val app = appWithCurrentPlaying()
            app.post(BeatLinkTrack(id = 601, title = "Song", artist = "DJ"))
            app.post(BeatLinkTrack(id = 602, title = "Song", artist = "DJ"))
            app.post(stopOfTheTrack) // the sign had not read the announcement

            app.post(BeatLinkTrack(id = comesBack, title = "Song", artist = "DJ"))

            assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true), "id $comesBack comes back")
        }
    }

    @Test
    fun `one line under two ids, announced and read by the sign, is not announced again under either id`() {
        for (comesBack in listOf(601, 602)) {
            val app = appWithCurrentPlaying()
            app.post(BeatLinkTrack(id = 601, title = "Song", artist = "DJ"))
            assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, app.manager.getNextMessage()) // the sign reads it
            app.post(BeatLinkTrack(id = 602, title = "Song", artist = "DJ"))
            app.post(stopOfTheTrack)

            app.post(BeatLinkTrack(id = comesBack, title = "Song", artist = "DJ"))

            assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = false), "id $comesBack comes back")
        }
    }

    @Test
    fun `the table of pairs again with every track posted under two ids, in all 24 orders of arrival`() {
        // accepted: the first update under one id, the same under another id, the second under one id, under another
        val wrong = mutableListOf<String>()
        for (sequence in sequencesOf(2)) {
            val updates = sequence.flatMapIndexed { i, kind ->
                val update = updateOf(kind, i + 1)
                listOf(update, if (update.isEmpty) update else update.copy(id = update.id + 1000))
            }
            for (arrival in permutations(updates.indices.toList())) {
                val app = appWithCurrentPlaying()
                val turns = updates.map { app.manager.acceptTrackUpdate() }
                arrival.forEach { i -> app.post(updates[i], turns[i]) }
                val problems = wrongOnTheSign(app, playing = lineOf(updates.last()), announced = sequence.last() == Kind.NEW)
                if (problems.isNotEmpty()) wrong += sequence.joinToString(", then ") { it.short } + " | arriving " + arrival.map { it + 1 } + " | " + problems.joinToString("; ")
            }
        }

        assertEquals(emptyList(), wrong, "${wrong.size} wrong cells of 384")
    }

    // ---- found by an independent review of the rule

    private fun announcements(app: App) = app.repo.oneTimeMessages.count { it === Message.FlashingAnnouncement.NowPlayingAnnouncement }

    @Test
    fun `a track whose announcement was pushed out before it was shown is announced when it comes back`() {
        val app = appWithCurrentPlaying()
        val fresh = updateOf(Kind.NEW, 1)
        app.post(fresh)
        assertEquals(1, announcements(app))
        app.post(stopOfTheTrack) // before the sign got to the announcement
        assertEquals(0, announcements(app))

        app.post(fresh)

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - New 1", announced = true))
    }

    @Test
    fun `a track whose announcement the sign has taken is not announced again when it comes back`() {
        val app = appWithCurrentPlaying()
        val fresh = updateOf(Kind.NEW, 1)
        app.post(fresh)
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, app.manager.getNextMessage()) // the sign asks
        app.post(stopOfTheTrack)

        app.post(fresh)

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - New 1", announced = false))
    }

    @Test
    fun `after the sign has taken the announcement, a stop keeps the line of the stopped track from following it`() {
        val app = appWithCurrentPlaying()
        app.post(updateOf(Kind.NEW, 1))
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, app.manager.getNextMessage())

        app.post(stopOfTheTrack)

        val next = (1..3).map { app.manager.getNextMessage() }
        assertTrue(next.none { it is Message.NowPlayingTrackMessage }, next.map { it.str }.toString())
        assertEquals("AD", next.first().str, "the ad round the stop asks for")
    }

    @Test
    fun `two tracks that are the same line to the sign are announced once`() {
        val app = appWithCurrentPlaying()
        app.post(BeatLinkTrack(id = 301, title = "Song", artist = "DJ"))
        app.post(BeatLinkTrack(id = 302, title = "Song", artist = "DJ"))                   // another id
        app.post(BeatLinkTrack(id = 303, title = "Song", artist = "DJ", isId = true))     // another flag
        app.post(BeatLinkTrack(id = 304, title = "Song\u4e2d", artist = "DJ"))             // differs only in what the sign cannot show

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `a second stop, when nothing is playing, adds nothing to the queue`() {
        val app = appWithCurrentPlaying()
        app.post(stopOfTheTrack)
        assertEquals(listOf("AD"), app.repo.oneTimeMessages.map { it.str }, "the stop of a track asks for an ad round")

        app.post(stopOfTheTrack)
        app.post(stopOfTheTrack)

        assertEquals(listOf("AD"), app.repo.oneTimeMessages.map { it.str })
    }

    @Test
    fun `the five tracks played last are remembered, by when they were played last, and the sixth back is announced again`() {
        val app = App(newFilesDir())
        app.phone(trackAds)
        ownerRuns()
        val tracks = (1..6).map { BeatLinkTrack(id = 400 + it, title = "T$it", artist = "DJ") }
        fun announcedWhenPosted(track: BeatLinkTrack): Boolean {
            app.post(track)
            return (announcements(app) == 1).also { signReadsTheQueue(app) } // what is announced is shown
        }

        // six tracks never played: each is announced
        assertEquals(List(6) { true }, tracks.map { announcedWhenPosted(it) })
        // played: T1 is forgotten (five are remembered: T2..T6). T2, the oldest, comes back: remembered, and now the newest
        assertEquals(false, announcedWhenPosted(tracks[1]))
        // T1 comes back: forgotten, so announced; the oldest by last play, which is T3 now, is forgotten for it
        assertEquals(true, announcedWhenPosted(tracks[0]))
        // T2 is still remembered (because it was played again), and T4; T3 is not
        assertEquals(false, announcedWhenPosted(tracks[1]))
        assertEquals(false, announcedWhenPosted(tracks[3]))
        assertEquals(true, announcedWhenPosted(tracks[2]))
    }

    // ---- WHAT LEAVES THE QUEUE UNSHOWN (gate r1 on the second follow-up).
    // The rule: a line counts as played only if the sign was HANDED its announcement, or the
    // announcement is still in the queue. Whatever takes an announcement out of the queue
    // unshown takes the mark with it, whoever does it; and what only cuts something else out
    // of the queue leaves both.

    /** Something that takes messages out of the queue, or handles the queue, and is not the sign being handed one. */
    private class Leaving(
        val what: String,
        /** Whether a waiting announcement is gone after it. */
        val emptiesTheQueue: Boolean,
        /** Whether it is the keyboard's doing (the app of the cell is then the one the keyboard types at). */
        val typedAt: Boolean = false,
        val happens: (App) -> Unit,
    )

    private val aKeyPress = Leaving("a key press", emptiesTheQueue = true, typedAt = true) {
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()
    }

    /** Not a path of today: whoever empties the queue tomorrow, without a word to anybody. */
    private val emptiedByAnyoneElse = Leaving("the queue emptied by anyone else", emptiesTheQueue = true) { app ->
        app.repo.oneTimeMessages.clear()
    }

    /** The sign asks, and the answer cannot be written: the message that was taken goes back. */
    private val aWriteThatFails = Leaving("a write to the sign that fails", emptiesTheQueue = false) { app ->
        if (app.repo.oneTimeMessages.isNotEmpty()) failedWrite(app)
    }

    private val theThinkingNoticeEnding = Leaving("the thinking notice put up and taken down", emptiesTheQueue = false) { app ->
        app.manager.togglePersistentThinkingMessage(true)
        app.manager.togglePersistentThinkingMessage(false)
    }

    /** What `!delete` does to the queue when the deleted message is a reply waiting behind its heading. */
    private val aDeletedReplysPiece = Leaving("the heading and the reply of a deleted reply dropped", emptiesTheQueue = false) { app ->
        val reply = Message.Marquee.Chonky("A REPLY")
        app.repo.pushInOnePiece(listOf(Message.ColorMessage.ChonkySlide("SAYS...", 0), reply))
        app.repo.dropOnePieceOf(reply)
    }

    /** In chooser mode a request of the sign is answered and takes nothing out of the queue. */
    private val requestsInTheChooser = Leaving("two requests of the sign in chooser mode, then a pause and its end", emptiesTheQueue = false) { app ->
        app.phone("!choose")
        ownerRuns()
        for (request in "~~") {
            val answered = SignResponder(nextMessage = { app.manager.getNextMessage() }, write = { _, _ -> true })
                .onRequest(byteArrayOf(request.code.toByte()), 1)
            check(answered == null || answered is Message.Chooser) { "in chooser mode the sign was sent ${answered?.str}" }
        }
        listOf("!endchoose", "!pause", "!unpause").forEach { app.phone(it) }
        ownerRuns()
    }

    /** One request of the sign whose answer cannot be written: the write says so, or it throws [thrown]. Returns what was taken. */
    private fun failedWrite(app: App, thrown: RuntimeException? = null): Message? {
        var taken: Message? = null
        val responder = SignResponder(
            nextMessage = { app.manager.getNextMessage().also { taken = it } },
            write = { _, _ -> if (thrown != null) throw thrown else false },
            unsent = { app.manager.returnUnsent(it) },
        )
        try {
            responder.onRequest(byteArrayOf('~'.code.toByte()), 1)
        } catch (e: RuntimeException) {
            if (e !== thrown) throw e
        }
        return taken
    }

    /** After a cell that typed: the input is ended (which leaves stars), and nothing is queued for the next cell. */
    private fun typingOver() {
        endTyping()
        repo.oneTimeMessages.clear()
    }

    /**
     * The table of ids against lines (every pair of updates, each posted under two ids, in all
     * 24 orders of arrival) with [leaving] placed after the first, the second, the third or the
     * fourth update to reach the owner. Then the lines come back, under ids of their own.
     * The sign reads nothing in a cell, so no announcement of a cell is ever handed to it.
     * Returns the cells that are wrong, as lines; 16 x 24 x 4 = 1536 cells.
     */
    private fun wrongCellsWith(leaving: Leaving): List<String> {
        val wrong = mutableListOf<String>()
        for (sequence in sequencesOf(2)) {
            val updates = sequence.flatMapIndexed { i, kind ->
                val update = updateOf(kind, i + 1)
                listOf(update, if (update.isEmpty) update else update.copy(id = update.id + 1000))
            }
            val last = updates.last()
            val lastIsNew = sequence.last() == Kind.NEW
            for (arrival in permutations(updates.indices.toList())) for (after in arrival.indices) {
                val savesWaiting = unsaved.size
                val app = appWithCurrentPlaying(typedAt = leaving.typedAt, fromTheOwner = true)
                val problems = mutableListOf<String>()
                try {
                    val turns = updates.map { app.manager.acceptTrackUpdate() }
                    arrival.forEachIndexed { n, i ->
                        app.post(updates[i], turns[i])
                        if (n == after) leaving.happens(app)
                    }
                    // The announcement of the update accepted last is waiting when that is a new
                    // track; not when the queue was emptied after that update reached the owner.
                    val emptiedAfterTheLast = leaving.emptiesTheQueue && after >= arrival.indexOf(updates.lastIndex)
                    val waiting = if (lastIsNew && !emptiedAfterTheLast) 1 else 0
                    if (announcements(app) != waiting) problems += "${announcements(app)} announcement(s) waiting, not $waiting"
                    // Its line comes back under another id: announced, or still announced, when it is new.
                    if (!last.isEmpty) app.post(last.copy(id = last.id + 2000))
                    problems += wrongOnTheSign(app, playing = lineOf(last), announced = lastIsNew)
                    // And the line of the first update: no announcement of it was handed to the sign either.
                    if (sequence.first() == Kind.NEW) {
                        app.post(updates.first().copy(id = updates.first().id + 3000))
                        val queue = app.repo.oneTimeMessages.toList()
                        val at = queue.indexOfFirst { it === Message.FlashingAnnouncement.NowPlayingAnnouncement }
                        if (announcements(app) != 1 || queue.getOrNull(at + 1)?.str != lineOf(updates.first())) {
                            problems += "the first line came back and is not announced: ${queue.map { it.str }}"
                        }
                    }
                } finally {
                    if (leaving.typedAt) {
                        typingOver()
                        // The ad saves of the cell went to this test's own saver; they are for a
                        // directory nobody reads again, and 3000 of them cost the next test a
                        // quarter of a minute. (No cell changes a stored message: no save of the
                        // list, of which the saver keeps one waiting, is among them.)
                        unsaved.subList(savesWaiting, unsaved.size).clear()
                    }
                }
                if (problems.isNotEmpty()) {
                    wrong += sequence.joinToString(", then ") { it.short } + " | arriving " + arrival.map { it + 1 } +
                            " | ${leaving.what} after the ${after + 1}. | " + problems.joinToString("; ")
                }
            }
        }
        return wrong
    }

    private fun assertNoWrongCells(leaving: Leaving) {
        val wrong = wrongCellsWith(leaving)

        assertEquals(emptyList(), wrong.take(12), "${wrong.size} wrong cells of 1536 with ${leaving.what} (the first twelve are shown)")
    }

    @Test
    fun `the table of ids against lines with a key press between any two of its events`() = assertNoWrongCells(aKeyPress)

    @Test
    fun `the table of ids against lines with the queue emptied by anyone else between any two of its events`() = assertNoWrongCells(emptiedByAnyoneElse)

    @Test
    fun `the table of ids against lines with a failed write to the sign between any two of its events`() = assertNoWrongCells(aWriteThatFails)

    @Test
    fun `the table of ids against lines with the thinking notice put up and taken down between any two of its events`() = assertNoWrongCells(theThinkingNoticeEnding)

    @Test
    fun `the table of ids against lines with the piece of a deleted reply dropped between any two of its events`() = assertNoWrongCells(aDeletedReplysPiece)

    @Test
    fun `the table of ids against lines with requests of the sign in chooser mode between any two of its events`() = assertNoWrongCells(requestsInTheChooser)

    private val song = BeatLinkTrack(id = 701, title = "Song", artist = "DJ")

    @Test
    fun `a track is queued, a key is pressed before the sign reads it, the track stops, the same line under a new id is announced`() {
        // the sequence of gate r1
        val app = appWithCurrentPlaying(typedAt = true)
        app.post(song)
        assertEquals(1, announcements(app))
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()
        assertEquals(0, announcements(app), "a key press empties the queue, and a waiting announcement with it")
        app.post(stopOfTheTrack)

        app.post(song.copy(id = 702))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `a key press takes the announcement, and the same track posted again, with no stop, is announced`() {
        val app = appWithCurrentPlaying(typedAt = true)
        app.post(song)
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()

        app.post(song)

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `the sign was handed the announcement, a key press takes the rest, the line is played and not announced again`() {
        val app = appWithCurrentPlaying(typedAt = true)
        app.post(song)
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, app.manager.getNextMessage()) // the sign reads it
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()
        app.post(stopOfTheTrack)

        app.post(song.copy(id = 702))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = false))
    }

    @Test
    fun `an announcement whose write failed is waiting again, it is not played, and a key press takes it like any other`() {
        val app = appWithCurrentPlaying(typedAt = true)
        app.post(song)
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, failedWrite(app))
        assertEquals(listOf("NOW${Message.VT}PLAYING", "DJ - Song", ""), app.repo.oneTimeMessages.map { it.str }, "it is the first again")
        app.post(song.copy(id = 702))
        assertEquals(1, announcements(app), "and while it waits, the same line is not announced a second time")
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()

        app.post(song.copy(id = 703))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `an announcement whose write throws is waiting again like one whose write failed`() {
        val app = appWithCurrentPlaying(typedAt = true)
        app.post(song)
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, failedWrite(app, thrown = IllegalStateException("the port is gone")))
        assertEquals(listOf("NOW${Message.VT}PLAYING", "DJ - Song", ""), app.repo.oneTimeMessages.map { it.str }, "it is the first again")
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()

        app.post(song.copy(id = 702))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `of six tracks played, the oldest is forgotten also when it is the very next to come`() {
        val app = App(newFilesDir())
        app.phone(trackAds)
        ownerRuns()
        val tracks = (1..6).map { BeatLinkTrack(id = 900 + it, title = "N$it", artist = "DJ") }
        tracks.forEach { track ->
            app.post(track)
            signReadsTheQueue(app)
        }

        app.post(tracks[0])

        assertEquals(1, announcements(app), "five are remembered, and it is the sixth back")
    }

    @Test
    fun `an announcement whose write failed and that is written at the next request is played`() {
        val app = appWithCurrentPlaying()
        app.post(song)
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, failedWrite(app))
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, app.manager.getNextMessage())
        app.post(stopOfTheTrack)

        app.post(song.copy(id = 702))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = false))
    }

    @Test
    fun `an announcement that was never written costs the memory of the played tracks nothing`() {
        val app = App(newFilesDir(), typedAt = true)
        app.phone(trackAds)
        ownerRuns()
        val tracks = (1..6).map { BeatLinkTrack(id = 800 + it, title = "M$it", artist = "DJ") }
        tracks.take(5).forEach { track ->
            app.post(track)
            signReadsTheQueue(app) // five announcements handed to the sign: the five that are remembered
        }
        app.post(tracks[5])
        failedWrite(app) // the sixth: taken, not written, back in the queue
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns() // and gone with a key press

        app.post(tracks[0]) // the oldest of the five

        assertEquals(0, announcements(app), "the first of the five is still remembered: nothing was played after the five")
    }

    @Test
    fun `the announcement was written and the write of the line behind it failed, the track is played all the same`() {
        val app = appWithCurrentPlaying()
        app.post(song)
        assertSame(Message.FlashingAnnouncement.NowPlayingAnnouncement, app.manager.getNextMessage()) // the sign reads it
        assertEquals("DJ - Song", failedWrite(app)?.str) // its line: taken, not written, the first again
        app.post(stopOfTheTrack)

        app.post(song.copy(id = 702))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = false))
    }

    @Test
    fun `the sign takes an ad that waits in front of an announcement, and the track is not played by that`() {
        val app = appWithCurrentPlaying(typedAt = true)
        app.post(stopOfTheTrack) // an ad round
        app.post(song)           // and behind it the announcement
        assertEquals(listOf("AD", "NOW${Message.VT}PLAYING", "DJ - Song", ""), app.repo.oneTimeMessages.map { it.str })
        assertEquals("AD", app.manager.getNextMessage().str) // the sign reads the ad, not the announcement
        KeyboardManager.processNewKeyboardKey('x')
        ownerRuns()

        app.post(song.copy(id = 702))

        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `a reply deleted while its heading waits takes its heading with it and leaves the announcement behind them`() {
        // the real command, in the chooser: the one cut out of the queue that is not a track's
        val app = appWithCurrentPlaying()
        app.repo.marqueeMessages.addAll(listOf(Message.Marquee.AIReply("AN ANSWER"), Message.Marquee.User("A MESSAGE")))
        assertEquals("RAVEGPT", app.manager.getNextMessage().str) // the heading starts; the rest of it and the reply wait
        app.post(song)
        assertEquals(
            listOf("SAYS.", "SAYS..", "SAYS...", "AN ANSWER", "NOW${Message.VT}PLAYING", "DJ - Song", ""),
            app.repo.oneTimeMessages.map { it.str }
        )

        listOf("!choose", "!first", "!delete", "!endchoose").forEach { app.phone(it) }
        ownerRuns()

        assertEquals(listOf("NOW${Message.VT}PLAYING", "DJ - Song", ""), app.repo.oneTimeMessages.map { it.str })
        app.post(song.copy(id = 702))
        assertEquals(emptyList(), wrongOnTheSign(app, playing = "DJ - Song", announced = true))
    }

    @Test
    fun `an ad command while an announcement waits puts its round in front and the announcement stays the only one`() {
        val app = appWithCurrentPlaying()
        app.post(song)

        app.phone(trackAds)
        ownerRuns()
        app.post(song.copy(id = 702))

        assertEquals(
            listOf("CURRENT", "TRACK:", "DJ - Song", "AD", "NOW${Message.VT}PLAYING", "DJ - Song", ""),
            app.repo.oneTimeMessages.map { it.str }
        )
    }

    // ---- what a track update, a stop or an ad command costs the thread that answers the sign
    // (the minors of gates r1 and r2, as their class): no walk through the queue or the ad list
    // that grows with the other, and a number of walks that does not grow with either.

    /** A list handed back in place of the one it wraps, so that every walk through it and every insert into its middle is counted. */
    private class Counted(private val list: MutableList<Message>) : MutableList<Message> by list {
        var walks = 0
        var inserts = 0
        override fun iterator(): MutableIterator<Message> = list.iterator().also { walks++ }
        override fun listIterator(): MutableListIterator<Message> = list.listIterator().also { walks++ }
        override fun listIterator(index: Int): MutableListIterator<Message> = list.listIterator(index).also { walks++ }
        override fun contains(element: Message): Boolean = list.contains(element).also { walks++ }
        override fun indexOf(element: Message): Int = list.indexOf(element).also { walks++ }
        override fun get(index: Int): Message = list[index].also { walks++ }
        override fun add(index: Int, element: Message) = list.add(index, element).also { inserts++ }
        override fun addAll(index: Int, elements: Collection<Message>): Boolean = list.addAll(index, elements).also { inserts++ }
        // (to the list itself, as the app's call goes: the walk of this interface's default would go round the queue's own removal)
        override fun removeIf(filter: java.util.function.Predicate<in Message>): Boolean = list.removeIf(filter).also { walks++ }
    }

    @Suppress("UNCHECKED_CAST")
    private fun counted(owner: Any, field: String): Counted {
        val listField = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }
        return Counted(listField.get(owner) as MutableList<Message>).also { listField.set(owner, it) }
    }

    /** One event at the owner, with what it may cost: walks through the queue, walks through the ad list, inserts into the queue. */
    private class Cost(
        val of: String, val queueWalks: Int, val adListWalks: Int, val inserts: Int,
        /** How many announcements wait afterwards: the event did what it is for, and only that. */
        val announced: Int,
        val before: (App, ads: Int) -> Unit = { _, _ -> },
        val event: (App, ads: Int) -> Unit,
    )

    @Test
    fun `a track update, a stop and an ad command walk the queue and the ad list a fixed number of times, whatever their lengths`() {
        fun adCommand(ads: Int) = "!$A\n" + (1..ads).joinToString("\n") { "${O}AD $it" }
        val rows = listOf(
            Cost("the track that is playing, again, its announcement waiting behind the queue", 0, 0, 0, announced = 1,
                before = { app, _ -> app.post(song) }) { app, _ -> app.post(song.copy(id = 702)) },
            Cost("the track that is playing, again, announced long ago", 0, 0, 0, announced = 0) { app, _ -> app.post(currentTrack) },
            // one walk: the piece of the track before is taken out of the queue
            Cost("another track", 1, 0, 0, announced = 1) { app, _ -> app.post(song) },
            // the announcement of the track before is taken out and counted out, in the one walk
            Cost("another track while the announcement of the one before waits", 1, 0, 0, announced = 1,
                before = { app, _ -> app.post(song) }) { app, _ -> app.post(trackA) },
            // the piece of the track; is an ad round waiting; (it is:) nothing more
            Cost("a stop while an ad round waits", 2, 0, 0, announced = 0,
                before = { app, ads -> app.phone(adCommand(ads)); ownerRuns() }) { app, _ -> app.post(stopOfTheTrack) },
            // the piece of the track; is an ad round waiting; is an icon waiting; then the round is made (one walk of the ads) and put in at once
            Cost("a stop that asks for an ad round", 3, 1, 1, announced = 0) { app, _ -> app.post(stopOfTheTrack) },
            // is an icon waiting; the ads are copied for the save and the round is made; the round is put in at once
            Cost("an ad command", 1, 2, 1, announced = 0) { app, ads -> app.phone(adCommand(ads)); ownerRuns() },
            // nothing is put in, so whoever is reading the queue (the one walk) reads on
            Cost("an ad round of nothing, put in while the queue is being read", 1, 0, 1, announced = 0) { app, _ ->
                val reading = app.repo.oneTimeMessages.iterator()
                app.repo.pushOneTimeMessages()
                reading.next()
            },
        )
        val wrong = mutableListOf<String>()
        for (row in rows) for ((queued, ads) in listOf(10 to 3, 200 to 30)) {
            val app = appWithCurrentPlaying()
            app.phone(adCommand(ads))
            ownerRuns()
            signReadsTheQueue(app)
            row.before(app, ads)
            repeat(queued) { app.repo.pushOneTimeMessage(Message.UtilityMessage.EnableMic) } // in front of whatever waits
            val queue = counted(app.repo, "oneTimeMessages")
            val adList = counted(app.manager, "advertisements")

            val failed = runCatching { row.event(app, ads) }.exceptionOrNull()
            if (failed != null) wrong += "${row.of}, $queued queued and $ads ads: $failed"

            val cost = listOf(queue.walks, adList.walks, queue.inserts, announcements(app))
            if (cost != listOf(row.queueWalks, row.adListWalks, row.inserts, row.announced)) {
                wrong += "${row.of}, $queued queued and $ads ads: queue walks, ad list walks, inserts, announcements waiting = $cost"
            }
        }

        assertEquals(emptyList(), wrong)
    }

    // ---- a second failure does not take the place of the first (the minor of gate r2, as its class)

    @Test
    fun `when what is done after a failure fails too, the caller gets the first failure with the second attached`() {
        val rows: List<Pair<String, (first: RuntimeException, second: RuntimeException) -> Throwable?>> = listOf(
            "the responder: the write throws, and handing the message back throws" to { first, second ->
                val responder = SignResponder(
                    nextMessage = { Message.Marquee.User("HELLO") },
                    write = { _, _ -> throw first },
                    unsent = { throw second },
                )
                runCatching { responder.onRequest(byteArrayOf('~'.code.toByte()), 1) }.exceptionOrNull()
            },
            "the manager: a reply cannot be saved, and ending the thinking notice after it throws" to { first, second ->
                var saveFails = false
                var clockFails = false
                val lists = MessageRepository(saver = Executor {
                    if (saveFails) {
                        clockFails = true
                        throw first
                    }
                })
                val home = newFilesDir()
                val submitted = KeyboardManager.onSubmitted
                val clock = KeyboardManager.clock
                val taken = KeyboardManager.takenByTheGame
                val manager = MessageManager(
                    object : ContextWrapper(null) {
                        override fun getFilesDir(): File = home
                    },
                    lists, owner, nowMs = { if (clockFails) throw second else 0L },
                )
                KeyboardManager.onSubmitted = submitted
                KeyboardManager.clock = clock
                KeyboardManager.takenByTheGame = taken
                val replies = org.koin.core.context.GlobalContext.get().get<AIReplySource>() as FakeReplies
                val askedBefore = replies.asked.size
                lists.marqueeMessages.add(Message.Marquee.AIQuery("A QUESTION"))
                manager.getNextMessage() // the query is shown, and asked
                check(replies.asked.size == askedBefore + 1) { "the query was not asked" }
                saveFails = true
                replies.asked.last().answer(cx.aphex.energysign.ai.AIReplyResult.Reply("AN ANSWER"))
                var thrown: Throwable? = null
                while (owner.drain(onError = { thrown = it }) > 0) Unit
                thrown
            },
        )
        val wrong = rows.mapNotNull { (where, failing) ->
            val first = IllegalStateException("the first")
            val second = IllegalArgumentException("the second")
            val thrown = failing(first, second)
            if (thrown === first && thrown.suppressed.toList() == listOf<Throwable>(second)) null
            else "$where: the caller got $thrown, with ${thrown?.suppressed?.toList()} attached"
        }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a stop asks for an ad round unless one of the ads of now is waiting, as they were set last and as a restart loads them`() {
        val home = newFilesDir()
        val app = App(home)
        app.phone("!$A\n${O}FIRST")
        ownerRuns()
        val adOfBefore = app.repo.oneTimeMessages.single()
        app.phone("!$A\n${O}SECOND")
        ownerRuns()
        app.saverRuns()
        for (running in listOf(app, App(home))) { // as set, and as loaded from the file
            running.repo.oneTimeMessages.clear()
            running.post(song)
            signReadsTheQueue(running)
            running.post(stopOfTheTrack)
            assertEquals(listOf("SECOND"), running.repo.oneTimeMessages.map { it.str }, "the stop's ad round")
            val adOfNow = running.repo.oneTimeMessages.single() as Message.ColorMessage.OneByOneMessage
            running.post(song)
            running.post(stopOfTheTrack)
            assertEquals(listOf("SECOND"), running.repo.oneTimeMessages.map { it.str }, "and no second one while it waits")
            // what was an ad before, and what only looks like an ad of now, are no waiting ad round
            running.repo.oneTimeMessages.clear()
            running.repo.enqueueOneTimeMessage(adOfBefore)
            running.repo.enqueueOneTimeMessage(adOfNow.copy())
            running.post(song)
            running.post(stopOfTheTrack)
            assertEquals(listOf("SECOND", "FIRST", "SECOND"), running.repo.oneTimeMessages.map { it.str })
        }
    }

    // ---- THE JUMP GAME ON THE SIGN (es-1jr): the game answers the sign's requests itself and
    // never takes from the queue, so what arrives during a game waits and is shown once.

    private val INTRO = listOf("D", "READY?", "JUMP!")
    private val GAME_OVER = listOf("X", "GAME${Message.VT}OVER", "SCORE 0", "BEST 0", "")
    private val STORED = "(a stored message)"

    /** An app the test's keyboard types at, with the test's clock, one stored message and nothing queued. */
    private fun gameApp(home: File = newFilesDir(), game: JumpGame = JumpGame()): App {
        repo.marqueeMessages.clear()
        repo.marqueeMessages.add(Message.Marquee.User("A MESSAGE"))
        repo.oneTimeMessages.clear()
        return App(home, typedAt = true, clocked = true, game = game)
    }

    /** One request of the sign, [ms] after the last: what it gets, in a word. */
    private fun asks(app: App, ms: Long = 50): String {
        app.now += ms
        return when (val answer = app.manager.getNextMessage()) {
            is Message.GameFrame -> if (answer.str[0] != '0') "frame, in the air" else "frame"
            is Message.KeyboardEcho -> "echo ${answer.str}"
            is Message.Chooser -> "chooser"
            is Message.Marquee -> STORED
            else -> answer.str
        }
    }

    /** The sign goes on asking, nobody plays: everything it gets up to the next stored message, frames that follow each other as one word. */
    private fun untilStored(app: App): List<String> {
        val got = mutableListOf<String>()
        repeat(80) {
            val answer = asks(app, 400).removeSuffix(", in the air")
            if (answer != "frame" || got.lastOrNull() != "frame") got += answer
            if (answer == STORED) return got
        }
        return got + "(and no end)"
    }

    private fun command(app: App, text: String) {
        app.phone(text)
        ownerRuns()
    }

    /** !game, the intro's three messages, the runner running in, and the first frame of the game itself. */
    private fun intoPlay(app: App) {
        command(app, "!game")
        check((1..3).map { asks(app) } == INTRO)
        check(asks(app) == "frame") // the runner runs in
        check(asks(app, 600) == "frame") // and the game is on
    }

    /** The states a game can be in when something happens, and how to get an app there. */
    private val gameStates: Map<String, (App) -> Unit> = linkedMapOf(
        "no game" to { _ -> },
        "the intro" to { app -> command(app, "!game"); check(asks(app) == "D") },
        "a game, on the ground" to { app -> intoPlay(app) },
        "a game, in the air" to { app -> intoPlay(app); command(app, "!jump"); check(asks(app, 100) == "frame, in the air") },
        // nobody plays: the hit, its picture for a moment, then the show's first message
        "the closing show" to { app -> intoPlay(app); check(asks(app, 10_000) == "frame"); check(asks(app, 800) == "X") },
        "the chooser" to { app -> command(app, "!choose") },
        "somebody typing" to { _ -> KeyboardManager.processNewKeyboardKey('x'); ownerRuns() },
    )

    private fun afterAGameCell() {
        typingOver()
        ownerRuns()
    }

    @Test
    fun `a typed line - the game's start, typed and ended with Enter, starts the game, and every other line is what it was`() {
        /**
         * [line] typed at the sign's keyboard, [before] done first, and Enter [typedFor] ms after
         * the first key (a message needs five seconds): what came of it, as the list and the
         * sign's next request show it.
         */
        fun typed(line: String, typedFor: Long = 6_000, before: () -> Unit = {}): String {
            before()
            ownerRuns()
            line.forEach(KeyboardManager::processNewKeyboardKey)
            ownerRuns()
            typingStarted(typedFor)
            KeyboardManager.submitKeyboardInput()
            ownerRuns()
            val first = repo.marqueeMessages.first()
            val next = manager.getNextMessage()
            return when {
                first is Message.Marquee.AIQuery -> "a question to RaveGPT"
                next is Message.KeyboardEcho -> "nothing yet: the echo stays"
                first.str != "MESSAGE 1" -> "a message"
                else -> "the sign gets ${next.str}"
            }.also {
                phone("!choose") // whatever came of it ends here: the chooser takes the sign, and a game with it
                ownerRuns()
                typingOver()
                fresh()
            }
        }
        // the game's intro begins with this; it is what the sign gets next (the echo is gone)
        val starts = "the sign gets ${INTRO[0]}"
        val rows = listOf(
            // the game's start, in the one spelling the phone's command has
            Triple("!game", { typed("!game") }, starts),
            Triple("!game, Enter 2 s after its first key", { typed("!game", typedFor = 2_000) }, starts),
            // typing takes the sign from a game (the first key ends it): the Enter starts the next one
            Triple("!game, typed during a game's intro", { typed("!game") { phone("!game") } }, starts),
            // near misses
            Triple("!gamex", { typed("!gamex") }, "a question to RaveGPT"),
            Triple("! game", { typed("! game") }, "a question to RaveGPT"),
            Triple("!game with a space after it", { typed("!game ") }, "a question to RaveGPT"),
            Triple("a question that mentions !game", { typed("what is !game") }, "a question to RaveGPT"),
            // a plain question, and other ! commands: as before
            Triple("hello", { typed("hello") }, "a question to RaveGPT"),
            Triple("!choose", { typed("!choose") }, "a question to RaveGPT"),
            Triple("!game reset", { typed("!game reset") }, "a question to RaveGPT"),
            Triple("hello, Enter 2 s after its first key", { typed("hello", typedFor = 2_000) }, "nothing yet: the echo stays"),
        )
        val wrong = rows.mapNotNull { (what, run, expected) ->
            val got = run()
            if (got == expected) null else "$what: $got, not $expected"
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
    }

    @Test
    fun `the button and the start, in every state of the game`() {
        val presses: Map<String, (App) -> Unit> = linkedMapOf(
            "!game" to { app -> command(app, "!game") },
            "!jump" to { app -> command(app, "!jump") },
            "!j" to { app -> command(app, "!j") },
            // THE PHONE'S MIDDLE BUTTON: the jump of !j while anything of a game is on, and otherwise what it was
            "!endchoose" to { app -> command(app, "!endchoose") },
            "!ec" to { app -> command(app, "!ec") },
            "the up arrow" to { _ -> KeyboardManager.upArrow(); ownerRuns() },
            "the space bar" to { _ -> KeyboardManager.processNewKeyboardKey(' '); ownerRuns() },
            // A HELD KEY IS ONE PRESS for the game: its repeats are nothing. Typing keeps its repeats.
            "the up arrow, held down (a repeat)" to { _ -> KeyboardManager.upArrow(repeated = true); ownerRuns() },
            "the space bar, held down (a repeat)" to { _ -> KeyboardManager.processNewKeyboardKey(' ', repeated = true); ownerRuns() },
        )
        // what the sign's next request gets, for !game, !jump, !j, !endchoose, !ec, the up arrow, the space bar, and the repeats of the two keys
        val jumps = "frame, in the air"
        val expected = mapOf(
            // !game and the up arrow start a game; a jump without a game is nothing (!endchoose starts none); a space is typed
            "no game" to listOf("D", STORED, STORED, STORED, STORED, "D", "echo  _", STORED, "echo  _"),
            // nothing starts twice and nobody jumps before the game: the intro goes on, whatever is pressed
            "the intro" to List(9) { "READY?" },
            // a second !game is nothing; the space bar is the button while a game is on, and types nothing
            "a game, on the ground" to listOf("frame", jumps, jumps, jumps, jumps, jumps, jumps, "frame", "frame"),
            "a game, in the air" to List(9) { jumps },
            // !game is the next game at once; a jump that comes a moment after the hit is nothing, on any key
            "the closing show" to listOf("D") + List(8) { GAME_OVER[1] },
            // the sign is somebody else's: no game begins behind it, not later either; !endchoose gives it back itself
            "the chooser" to listOf(STORED, STORED, STORED, STORED, STORED, STORED, "echo  _", STORED, "echo  _"),
            "somebody typing" to listOf("", "", "", "", "", "", "echo x _", "", "echo x _"),
        )
        val wrong = mutableListOf<String>()
        for ((state, reach) in gameStates) presses.entries.forEachIndexed { i, (press, pressed) ->
            val app = gameApp()
            try {
                reach(app)
                pressed(app)
                // what was taken by somebody else is given back first: was a game started behind it?
                // (!endchoose and !ec give the chooser back themselves: the sign's next request shows that they did)
                if (state == "the chooser" && press != "!endchoose" && press != "!ec") command(app, "!endchoose")
                if (state == "somebody typing" && !press.startsWith("the space bar")) endTyping()
                val got = asks(app)
                if (got != expected.getValue(state)[i]) wrong += "$state, $press: the sign gets '$got', not '${expected.getValue(state)[i]}'"
            } finally {
                afterAGameCell()
            }
        }
        // A KEY THE GAME TOOK IS THE GAME'S UNTIL IT IS PRESSED ANEW, whatever the game has become meanwhile: held
        // from a game into the messages, its repeats type nothing and the queue is as it was.
        val waited = "NEW${Message.VT}MSG${Message.VT}"
        val keys: Map<String, (Boolean) -> Unit> = linkedMapOf(
            "the up arrow" to { repeated -> KeyboardManager.upArrow(repeated); ownerRuns() },
            "the space bar" to { repeated -> KeyboardManager.processNewKeyboardKey(' ', repeated); ownerRuns() },
        )
        for ((key, press) in keys) for (state in listOf("the intro", "a game, on the ground", "the closing show")) {
            val app = gameApp()
            try {
                gameStates.getValue(state)(app)
                press(false) // the key goes down, and stays down
                untilStored(app) // the game and its show end, the messages are back
                command(app, "HELLO") // and one waits in the queue
                press(true)
                val got = asks(app)
                if (got != waited) wrong += "$key, held down from $state into the messages: the sign gets '$got', not the message that waited"
                // let go and pressed anew it is an ordinary key again: the up arrow starts a game, a space is typed
                press(false)
                val anew = asks(app)
                val ordinary = if (key == "the up arrow") "D" else "echo  _"
                if (anew != ordinary) wrong += "$key, let go and pressed anew after $state: the sign gets '$anew', not '$ordinary'"
            } finally {
                afterAGameCell()
            }
        }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `what arrives during a game either takes the sign and ends the game, or waits and is shown once after it`() {
        class Arrives(
            val what: String,
            /** Null: the game goes on, and [then] is shown after it. Otherwise what the next request gets in place of the game. */
            val takesTheSign: String? = null,
            /** What the sign gets after the game (or after the sign was given back), before the next stored message. */
            val then: List<String> = emptyList(),
            /** For what took the sign: it is given back. */
            val givenBack: (App) -> Unit = {},
            val happens: (App) -> Unit,
        )
        val newMessage = "NEW${Message.VT}MSG${Message.VT}"
        val arrivals = listOf(
            Arrives("the chooser", takesTheSign = "chooser", givenBack = { command(it, "!endchoose") }) { command(it, "!choose") },
            // (the stars are the keyboard's own, at the end of typing)
            Arrives("a typed key", takesTheSign = "echo x_", then = listOf(""), givenBack = { endTyping() }) { KeyboardManager.processNewKeyboardKey('x'); ownerRuns() },
            Arrives("an ad command", then = listOf("AD")) { command(it, "!$A\n${O}AD") },
            Arrives("!pause") { command(it, "!pause") },
            Arrives("a new message", then = listOf(newMessage)) { command(it, "HELLO") },
            Arrives("a new message, then the space bar", then = listOf(newMessage)) { command(it, "HELLO"); KeyboardManager.processNewKeyboardKey(' '); ownerRuns() },
            Arrives("a message that is a query for the AI", then = listOf(newMessage)) { command(it, "ravegpt are you there") },
            Arrives("a track", then = listOf("NOW${Message.VT}PLAYING", "DJ - Song", "")) { it.post(song) },
            Arrives("an answer to the sign that could not be written") { app -> failedWrite(app) },
        )
        // in each state: what the next request gets when the game goes on, and what follows it to the end of the game
        val goesOn = mapOf(
            "the intro" to (listOf("READY?", "JUMP!", "frame") + GAME_OVER),
            "a game, on the ground" to (listOf("frame", "frame") + GAME_OVER),
            "the closing show" to GAME_OVER.drop(1),
        )
        val wrong = mutableListOf<String>()
        for ((state, rest) in goesOn) for (arrival in arrivals) {
            val app = gameApp()
            try {
                gameStates.getValue(state)(app)
                arrival.happens(app)
                val first = asks(app).removeSuffix(", in the air")
                if (arrival.takesTheSign != null) arrival.givenBack(app)
                val got = listOf(first) + (if (first == STORED) emptyList() else untilStored(app))
                val expected = when (arrival.takesTheSign) {
                    null -> rest + arrival.then + STORED
                    STORED -> listOf(STORED)
                    else -> listOf(arrival.takesTheSign) + arrival.then + STORED
                }
                if (got != expected) wrong += "$state, ${arrival.what}: the sign gets $got, not $expected"
            } finally {
                afterAGameCell()
            }
        }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `every event counts from the moment it arrived, however late the owner carries it out`() {
        // A game whose every obstacle is a post. Nobody presses: the first one hits 2,140 ms into the game.
        // A press at 2,110 ms clears it (the gate's case), and the second post ends the game with a score of 1.
        fun inPlay(): Pair<App, Long> {
            val app = gameApp(game = JumpGame(JumpGame.Rules(obstacles = listOf(JumpGame.Rules().obstacles[1])), Random(7)))
            intoPlay(app)
            return app to app.now
        }
        /** The score and the best as the sign is shown them, from here to the next stored message. */
        fun shown(app: App): List<String> = untilStored(app).zipWithNext().mapNotNull { (before, it) ->
            if (before == "HIGH SCORE") "a new best of ${it.trim()}" else it.takeIf { it.startsWith("SCORE") || it.startsWith("BEST") }
        }
        val presses: Map<String, (App) -> Unit> = linkedMapOf(
            "!jump from the phone" to { app -> app.phone("!jump") },
            // (also once the sign was answered past the hit and the game closed: its closing show is part of game.busy)
            "!endchoose from the phone" to { app -> app.phone("!endchoose") },
            "the up arrow" to { _ -> KeyboardManager.upArrow() },
            "the space bar" to { _ -> KeyboardManager.processNewKeyboardKey(' ') },
        )
        val wrong = mutableListOf<String>()
        fun cell(name: String, expected: Any, got: () -> Any) {
            try {
                val outcome = got()
                if (outcome != expected) wrong += "$name: $outcome, not $expected"
            } finally {
                afterAGameCell()
            }
        }
        // how late the press is carried out, and whether the sign's requests of the meantime were answered before it
        for ((press, arrives) in presses) for ((late, signAsksMeanwhile) in listOf(0L to false, 50L to false, 50L to true, 80L to false, 80L to true, 3_000L to false)) {
            cell("$press at 2,110 ms, carried out $late ms later${if (signAsksMeanwhile) ", the sign's requests of the meantime answered first" else ""}", listOf("a new best of 1")) {
                val (app, began) = inPlay()
                while (app.now + 40 <= began + 2_110) asks(app, 40)
                app.now = began + 2_110
                arrives(app)
                if (signAsksMeanwhile) (began + 2_120..began + 2_110 + late step 40).forEach { app.now = it; app.manager.getNextMessage() }
                app.now = began + 2_110 + late
                ownerRuns()
                shown(app)
            }
        }
        // THE STAMP IS TAKEN WHERE THE EVENT ENTERS THE APP, one definition a source. The event is of 2,110 ms; what the
        // app does before it hands it on (the dispatch, the log lines) takes 40 ms, which is past the hit at 2,140 ms.
        val jump = "!jump".toByteArray()
        val sources: Map<String, (App, Long) -> Unit> = linkedMapOf(
            // a key: its event's own time, on the app's clock (the event is 40 ms old when it is dispatched)
            "the up arrow" to { _, _ -> KeyboardManager.upArrow(arrivedAt = KeyboardManager.arrivedAt(eventTimeMs = 70_000, uptimeMs = 70_040)) },
            "the space bar" to { _, _ -> KeyboardManager.processNewKeyboardKey(' ', arrivedAt = KeyboardManager.arrivedAt(eventTimeMs = 70_000, uptimeMs = 70_040)) },
            // the phone: the clock as the first statement of the Bluetooth write callback read it
            "!jump from the phone" to { app, entered -> app.manager.takeWrite(jump, responseNeeded = false, arrivedAt = entered) },
            "!jump from the phone, its receipt asked for" to { app, entered -> waitingCall("bluetooth") { app.manager.takeWrite(jump, responseNeeded = true, arrivedAt = entered) } },
            // the adb hook: the clock as the first statement of its receive read it
            "!jump from the adb hook" to { app, entered -> waitingCall("hook") { app.manager.processNewBytesAndTell(jump, entered) } },
        )
        for ((source, handsOn) in sources) cell("$source, an event of 2,110 ms that the app hands on 40 ms later", listOf("a new best of 1")) {
            val (app, began) = inPlay()
            while (app.now + 40 <= began + 2_110) asks(app, 40)
            app.now = began + 2_150
            handsOn(app, began + 2_110)
            app.now = began + 2_190
            ownerRuns()
            shown(app)
        }
        cell("a key's event of 40 ms ago; and one that says it is of a time still to come arrived now", listOf(960L, 1_000L)) {
            gameApp() // (its clock, which the keyboard reads, is at 1,000)
            listOf(KeyboardManager.arrivedAt(eventTimeMs = 70_000, uptimeMs = 70_040), KeyboardManager.arrivedAt(eventTimeMs = 70_500, uptimeMs = 70_000))
        }
        for (late in listOf(0L, 80L, 3_000L)) {
            // the sign stopped asking; the game ended by the clock, unseen; then:
            cell("!game five seconds into a game the sign stopped asking for, carried out $late ms later: the sign's next request gets", "D") {
                val (app, began) = inPlay()
                app.now = began + 5_000
                app.phone("!game")
                app.now += late
                ownerRuns()
                asks(app)
            }
            cell("a game with a score of 1 ended unseen, then the chooser, carried out $late ms later: the best the next game shows", listOf("SCORE 0", "BEST 1")) {
                val (app, began) = inPlay()
                app.now = began + 2_110
                command(app, "!jump")
                app.now = began + 8_000
                app.phone("!choose")
                app.now += late
                ownerRuns()
                command(app, "!endchoose")
                command(app, "!game")
                shown(app)
            }
        }
        // (the post is this game's only shape, so its letter on the wire is the first, a)
        // THE SIGN WAS ANSWERED PAST THE HIT before an event that had arrived before the hit is carried out.
        // The game: a press at 2,110 ms clears the first post, the second ends it with a score of 1, a new best.
        val posts = JumpGame.Rules(obstacles = listOf(JumpGame.Rules().obstacles[1]))
        fun endOf(vararg presses: Long): Pair<Long, Int> {
            val twin = JumpGame(posts, Random(7)).also { it.start(0) }
            presses.forEach { twin.jump(it) }
            twin.frame(60_000)
            return twin.endedAt to twin.score
        }
        val hit = endOf(2_110).first
        /** [arrives] 20 ms before that hit; the sign is answered 10 ms after the hit, the game is closed there; then the event is carried out. */
        fun overtaken(arrives: (App) -> Unit): App {
            val (app, began) = inPlay()
            app.now = began + 2_110
            command(app, "!jump")
            app.now = began + hit - 20
            arrives(app)
            app.now = began + hit + 10
            app.manager.getNextMessage()
            ownerRuns()
            return app
        }
        cell("a second press 20 ms before the hit, carried out after the sign was answered past it: the game is closed once, with what that press made of it",
            listOf("a new best of ${endOf(2_110, hit - 20).second}")) {
            shown(overtaken { it.phone("!jump") })
        }
        cell("!game reset 20 ms before the hit, carried out after it: the reset came first, the new best after it", listOf("SCORE 0", "BEST 1")) {
            val app = overtaken { it.phone("!game reset") }
            untilStored(app)
            command(app, "!game")
            shown(app)
        }
        cell("the chooser 20 ms before the hit, carried out after it: the game was ended by the chooser, it has no score", listOf("SCORE 0", "BEST 0")) {
            val app = overtaken { it.phone("!choose") }
            command(app, "!endchoose")
            command(app, "!game")
            shown(app)
        }
        cell("!game 20 ms before the hit, carried out after it: a game was being played, nothing starts; the closing show goes on", false) {
            val app = overtaken { it.phone("!game") }
            "D" in untilStored(app)
        }
        cell("a press that arrived in the intro and is carried out when the game has begun is no jump", "frame") {
            val app = gameApp()
            command(app, "!game")
            check((1..3).map { asks(app) } == INTRO)
            check(asks(app) == "frame") // the runner runs in
            app.now += 590
            app.phone("!jump")
            app.now += 10
            app.manager.getNextMessage() // the game begins with this request
            ownerRuns()
            asks(app, 100)
        }
        cell("the sign asks again three seconds into a game: the picture of the hit is the one of the hit (the post at column 12, the starfield where it stood then, the score), not the last it asked for", "00a12p22e2s16n0") {
            val (app, began) = inPlay()
            app.now = began + 3_000
            app.manager.getNextMessage().str
        }

        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `the game's best is there again after a restart, and a game is not`() {
        val home = newFilesDir()
        /** A game from !game to the next stored message, nobody playing: its score and its best as the sign showed them. */
        fun scoreAndBest(app: App): List<String> {
            command(app, "!game")
            return untilStored(app).filter { it.startsWith("SCORE") || it.startsWith("BEST") }
        }
        val rows = mutableListOf<Pair<String, Any?>>()
        var app = gameApp(home)
        intoPlay(app)
        app = gameApp(home) // a restart in the middle of a game
        rows += "after a restart in the middle of a game the sign gets" to asks(app)
        // a game with a score: the player clears the first obstacle and no more
        intoPlay(app)
        var jumped = false
        var shown = false // the show is over at the first stored message after it
        val show = mutableListOf<String>()
        repeat(900) { // nine seconds: the first obstacle is cleared, the second ends the game, the show follows
            val answer = app.manager.getNextMessage().also { app.now += 10 }
            if (answer is Message.GameFrame) {
                val column = Regex("[abc](-?\\d+)").find(answer.str)?.groupValues?.get(1)?.toInt()
                if (!jumped && column != null && column <= 23) {
                    command(app, "!jump")
                    jumped = true
                }
            } else if (answer is Message.Marquee) shown = show.isNotEmpty() else if (!shown) show += answer.str
        }
        rows += "a first score of 1 is a new best, with its own show" to show
        saveAll()
        app = gameApp(home)
        rows += "a game without a score after the restart" to scoreAndBest(app)
        command(app, "!game reset")
        rows += "!game reset, and a game without a score" to scoreAndBest(app)
        saveAll()
        app = gameApp(home)
        rows += "and after a restart" to scoreAndBest(app)

        assertEquals(
            listOf<Pair<String, Any?>>(
                "after a restart in the middle of a game the sign gets" to STORED,
                "a first score of 1 is a new best, with its own show" to listOf("X", "HIGH SCORE", "  1   ", ""),
                "a game without a score after the restart" to listOf("SCORE 0", "BEST 1"),
                "!game reset, and a game without a score" to listOf("SCORE 0", "BEST 0"),
                "and after a restart" to listOf("SCORE 0", "BEST 0"),
            ),
            rows,
        )
        afterAGameCell()
    }

    /** The saver of the test's own repository gets through what it was given. */
    private fun saveAll() {
        unsaved.toList().forEach { it.run() }
        unsaved.clear()
    }

    /** The real route on a real server (Netty, a free port) for the time of [block], which gets the port. */
    private fun <T> withTrackServer(app: App, block: (port: Int) -> T): T {
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 0) {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { gson {} }
            routing { currentTrack(app.manager) }
        }.start(wait = false)
        try {
            return block(kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(100, 500)
        }
    }

    /** Posts [json] to the route and returns the status line of the answer. */
    private fun postTrack(port: Int, json: String): String {
        val post = Post(port, "the post of $json")
        post.headers(json.toByteArray().size)
        post.body(json)
        return waitingCall("test-reads-answer") { post.status() }
    }

    @Test
    fun `a posted track without an artist or without a title is taken in with what it has`() {
        // (the server fills the track with Gson: a field that is not in the JSON is null, whatever Kotlin's type says)
        val app = appWithCurrentPlaying()
        withTrackServer(app) { port ->
            assertEquals("HTTP/1.1 200 OK", postTrack(port, """{"id":7,"title":"Only A Title"}"""))
            assertEquals(emptyList(), wrongOnTheSign(app, playing = "Only A Title", announced = true))

            assertEquals("HTTP/1.1 200 OK", postTrack(port, """{"id":8,"artist":"Only An Artist","title":null}"""))
            assertEquals(emptyList(), wrongOnTheSign(app, playing = "Only An Artist", announced = true))
        }
    }

    @Test
    fun `a posted track with nothing the sign can show is taken in as nothing playing, not left out`() {
        val app = appWithCurrentPlaying()
        withTrackServer(app) { port ->
            assertEquals("HTTP/1.1 200 OK", postTrack(port, """{"id":9}"""))
            assertEquals(emptyList(), wrongOnTheSign(app, playing = null, announced = false))

            app.post(currentTrack)
            assertEquals("HTTP/1.1 200 OK", postTrack(port, """{"id":10,"artist":"","title":"\u4e2d\u6587"}"""))
            assertEquals(emptyList(), wrongOnTheSign(app, playing = null, announced = false))
        }
    }

    @Test
    fun `an ad list that cannot be renamed into place is reported as not saved`() {
        // ads.json is a directory with something in it: nothing can be renamed over it
        val filesDir = newFilesDir()
        File(filesDir, "ads.json").mkdir()
        File(File(filesDir, "ads.json"), "inside").writeText("kept")
        val app = App(filesDir)

        val aboutTheSave = logged("ads.json") {
            app.phone("!$A\n${CL}HELLO")
            ownerRuns()
            app.saverRuns()
        }

        assertTrue(aboutTheSave.any { "Exception when saving ads.json" in it }, aboutTheSave.toString())
        assertTrue(aboutTheSave.none { "Wrote" in it }, aboutTheSave.toString())
        assertEquals("kept", File(File(filesDir, "ads.json"), "inside").readText())
        // the list is in use all the same, for this run
        assertEquals("HELLO", app.manager.getNextMessage().str)
    }

    @Test
    fun `a directory that could not be forced to storage after an ad save is in the app's log, and the ads are saved`() {
        val filesDir = newFilesDir()
        val app = App(filesDir)
        val aboutTheSave = try {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                filesDir.setReadable(false, false) && !filesDir.canRead(), "this user can read every directory (root?)"
            )
            logged("ads.json") {
                app.phone("!$A\n${CL}HELLO")
                ownerRuns()
                app.saverRuns()
            }
        } finally {
            filesDir.setReadable(true, false)
        }

        assertEquals(1, aboutTheSave.count { "could not be forced to storage" in it }, aboutTheSave.toString())
        assertEquals(1, aboutTheSave.count { "Wrote 1 ads" in it }, aboutTheSave.toString())
        assertEquals(listOf("HELLO"), AdCodec.decode(app.adsFile.readText()).map { it.str })
    }

    // ---- POST /currentTrack, the real route on a real server: the order of two posts that overlap

    /** One HTTP exchange on a socket of its own: [send] writes the request, in as many parts as it likes. Returns the status line. */
    private class Post(port: Int, private val what: String) {
        private val socket = java.net.Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        private val out = socket.getOutputStream()

        fun headers(bodyLength: Int) {
            out.write(
                ("POST /currentTrack HTTP/1.1\r\nHost: sign\r\nContent-Type: application/json\r\n" +
                        "Content-Length: $bodyLength\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.flush()
        }

        fun body(json: String) {
            out.write(json.toByteArray())
            out.flush()
        }

        /** The first line of the answer, e.g. "HTTP/1.1 200 OK". Waits for it. */
        fun status(): String =
            try {
                socket.getInputStream().bufferedReader().readLine() ?: throw java.io.IOException("the server closed the connection without an answer")
            } catch (e: java.io.IOException) {
                throw AssertionError("no answer to $what within ${socket.soTimeout / 1000} s: $e")
            } finally {
                socket.close()
            }
    }

    @Test
    fun `of two posts to currentTrack that overlap, the one that was accepted later wins, a stop is not undone by the track before it`() {
        // ("Current" is playing at the start: a stop that was not taken in would leave it showing)
        val app = appWithCurrentPlaying()
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 0) {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { gson {} }
            routing { currentTrack(app.manager) }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
            val trackJson = """{"id":9,"title":"${"C".repeat(750)}","artist":"DJ"}"""
            val stopJson = """{"id":0,"title":"","artist":"","isEmpty":true}"""

            // The track's post is accepted: its headers are there, the route has it. Its body is still on the way.
            val turnsBefore = app.manager.trackTurnsTaken
            val track = Post(port, "the post of the track (its body was held back)")
            track.headers(trackJson.toByteArray().size)
            // (the route gives the update its place in the order when it accepts the request; this waits for that)
            val deadline = System.currentTimeMillis() + 2_000
            while (app.manager.trackTurnsTaken < turnsBefore + 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)

            // Now the stop comes, whole, and is answered.
            val stop = Post(port, "the post of the stop")
            stop.headers(stopJson.toByteArray().size)
            stop.body(stopJson)
            assertEquals("HTTP/1.1 200 OK", waitingCall("test-reads-stop") { stop.status() })
            app.repo.oneTimeMessages.clear() // the ad round a stop asks for

            // Only now does the rest of the track's post arrive.
            track.body(trackJson)
            assertEquals("HTTP/1.1 200 OK", waitingCall("test-reads-track") { track.status() })

            // The stop was the later of the two: nothing is playing, the track is not announced
            assertEquals(emptyList(), app.repo.oneTimeMessages.toList())
            app.phone("!$A\n🛤\n${O}AD")
            ownerRuns()
            assertEquals(listOf("AD"), app.repo.oneTimeMessages.map { it.str }, "no track line in the ad round")
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun `an empty track from the caller's thread ends the now-playing line`() {
        val app = App(newFilesDir())
        app.phone("!$A\n\uD83D\uDEE4\n${O}AD")
        ownerRuns()
        app.repo.oneTimeMessages.clear()

        app.post(BeatLinkTrack(id = 8, title = "Song", artist = "DJ"))
        // the track is in: it is announced
        assertEquals(listOf("NOW${Message.VT}PLAYING", "DJ - Song", ""), app.repo.oneTimeMessages.map { it.str })

        app.post(stopOfTheTrack)

        // the announcement is gone with the track, its stars too, and the ad round a stop asks for has no track in it
        assertEquals(listOf("AD"), app.repo.oneTimeMessages.map { it.str })
    }

    /** One request of the sign, answered by the real responder: the text that goes out on the wire. */
    private fun frameFor(app: App): String {
        var frame: String? = null
        SignResponder(nextMessage = { app.manager.getNextMessage() }, write = { reply, _ -> frame = reply; true })
            .onRequest(byteArrayOf('~'.code.toByte()), 1)
        return frame!!
    }

    @Test
    fun `the now-playing line is held to the limit, in the ad round and when the track is announced`() {
        // A track with a title of 750 characters, then an ad list that is the place of the track.
        val app = App(newFilesDir())
        app.post(BeatLinkTrack(id = 1, title = "A".repeat(750), artist = "DJ"))
        ownerRuns()
        app.phone("!$A\n🛤")
        ownerRuns()

        // the ad round (CURRENT, TRACK:, the track), then the announcement queued before it (NOW PLAYING, the track, stars)
        val frames = (1..6).map { frameFor(app) }
        val sent = frames.map { com.google.gson.JsonParser.parseString(it).asJsonObject }

        assertEquals(listOf("O", "O", "T", "F", "T", "S"), sent.map { it["type"].asString })
        frames.forEachIndexed { i, frame ->
            val bytes = frame.toByteArray(Charsets.UTF_8).size
            assertTrue(bytes <= 500, "answer ${i + 1} is $bytes bytes")
        }
        listOf(sent[2], sent[4]).forEach { track ->
            assertTrue(track["str"].asString.startsWith("DJ - AAAA"), "the beginning of the line is kept")
        }
        assertEquals(500, frames[2].toByteArray(Charsets.UTF_8).size, "as much of it as fits")
    }
}
