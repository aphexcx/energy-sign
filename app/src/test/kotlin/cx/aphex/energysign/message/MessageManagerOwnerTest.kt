package cx.aphex.energysign.message

import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.ai.PostedAIAnswer
import cx.aphex.energysign.beatlinkdata.currentTrack
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
            wire = wire, baud = 14400, name = "test", events = ::ownerRuns,
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
    }

    // ---- ads: the command, the restart, the period (PR 5)

    /**
     * The app as it is after a start on [home], its files directory: a MessageManager of its own, with its own
     * lists and its own saver (run by hand), on the owner of this test. Building a second one
     * on the same directory is a restart.
     */
    private inner class App(val home: File) {
        val unsaved = mutableListOf<Runnable>()
        val repo = MessageRepository(saver = Executor { unsaved += it })
        val manager: MessageManager

        init {
            // (a MessageManager takes the keyboard's Enter for itself; the one of this test keeps it)
            val submitted = KeyboardManager.onSubmitted
            manager = MessageManager(
                object : ContextWrapper(null) {
                    override fun getFilesDir(): File = home
                },
                repo,
                owner,
            )
            KeyboardManager.onSubmitted = submitted
        }

        fun phone(command: String) = manager.processNewBytes(command.toByteArray())

        /** A track update as the route makes it: it takes its turn, then the public entry, on a thread of its own, while the owner (this thread) takes its turns. */
        fun post(track: BeatLinkTrack, turn: MessageManager.TrackTurn = manager.acceptTrackUpdate()) =
            waitingCall("beatlink") { manager.processNowPlayingTrack(track, turn) }

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

    private fun lineOf(track: BeatLinkTrack): String? = if (track.isEmpty) null else "${track.artist} - ${track.title}"

    /** The start of every cell: "Old" was played, then "Current", which is playing; both were shown; nothing is queued. The ad list is the place of the track and one ad. */
    private fun appWithCurrentPlaying(adRoundWaiting: Boolean = false): App {
        val app = App(newFilesDir())
        app.phone(trackAds)
        ownerRuns()
        app.post(oldTrack)
        app.repo.oneTimeMessages.clear() // the sign has shown its announcement
        app.post(currentTrack)
        app.repo.oneTimeMessages.clear() // and this one
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
            app.repo.oneTimeMessages.clear()
            app.post(track)
            return announcements(app) == 1
        }

        // six tracks never played: each is announced
        assertEquals(List(6) { true }, tracks.map { announcedWhenPosted(it) })
        // played: T1 is forgotten (five are remembered: T2..T6). T3 comes back: remembered, and now the newest
        assertEquals(false, announcedWhenPosted(tracks[2]))
        // T1 comes back: forgotten, so announced; the oldest by last play, T2, is forgotten for it
        assertEquals(true, announcedWhenPosted(tracks[0]))
        // T4 and T3 are still remembered (T3 because it was played again), T2 is not
        assertEquals(false, announcedWhenPosted(tracks[3]))
        assertEquals(false, announcedWhenPosted(tracks[2]))
        assertEquals(true, announcedWhenPosted(tracks[1]))
    }

    /** The real route on a real server (Netty, a free port) for the time of [block], which gets the port. */
    private fun <T> withTrackServer(app: App, block: (port: Int) -> T): T {
        val server = io.ktor.server.engine.embeddedServer(io.ktor.server.netty.Netty, port = 0) {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { gson {} }
            routing { currentTrack(app.manager) }
        }.start(wait = false)
        try {
            return block(kotlinx.coroutines.runBlocking { server.resolvedConnectors().first().port })
        } finally {
            server.stop(100, 500)
        }
    }

    /** Posts [json] to the route and returns the status line of the answer. */
    private fun postTrack(port: Int, json: String): String {
        val post = Post(port)
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
    private class Post(port: Int) {
        private val socket = java.net.Socket("127.0.0.1", port)
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
        fun status(): String = socket.getInputStream().bufferedReader().readLine().also { socket.close() }
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
            val port = kotlinx.coroutines.runBlocking { server.resolvedConnectors().first().port }
            val trackJson = """{"id":9,"title":"${"C".repeat(750)}","artist":"DJ"}"""
            val stopJson = """{"id":0,"title":"","artist":"","isEmpty":true}"""

            // The track's post is accepted: its headers are there, the route has it. Its body is still on the way.
            val turnsBefore = app.manager.trackTurnsTaken
            val track = Post(port)
            track.headers(trackJson.toByteArray().size)
            // (the route gives the update its place in the order when it accepts the request; this waits for that)
            val deadline = System.currentTimeMillis() + 2_000
            while (app.manager.trackTurnsTaken < turnsBefore + 1 && System.currentTimeMillis() < deadline) Thread.sleep(5)

            // Now the stop comes, whole, and is answered.
            val stop = Post(port)
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

        // the announcement is gone with the track (its stars stay), and the ad round a stop asks for has no track in it
        assertEquals(listOf("AD", ""), app.repo.oneTimeMessages.map { it.str })
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
