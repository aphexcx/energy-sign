package cx.aphex.energysign.adb

import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.message.FakeReplies
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hook's way into the app, with the REAL MessageManager and OwnerQueue: the bytes go to
 * MessageManager.processNewBytesAndTell, the entry beside the one a Bluetooth write takes
 * (processNewBytes; both hand the same change to the owner), and the answer is what the owner
 * did with them.
 *
 * There is no Bluetooth server in this test at all: the hook does not go through one, so
 * Bluetooth being off, on, or switched off and on again is nothing to it.
 *
 * The owner here is a thread of the test that does what the sign link thread does with the
 * queue: it runs what was posted, and reports what threw. It can be stopped, to stand for a
 * link that is not running. Android is faked as in MessageManagerOwnerTest (the Context's files
 * directory, the log, the main-thread check of LiveData).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdbCommandOwnerTest {
    private val dir: File = Files.createTempDirectory("es-z7t-hook").toFile()
    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    /**
     * The save runs where it is asked for, on the owner: that lets a test hold the owner in the
     * MIDDLE of a command ([saveWaitsFor]), or make a command throw when part of it is done
     * ([saveThrows]).
     */
    @Volatile
    private var saveReached: CountDownLatch? = null

    @Volatile
    private var saveWaitsFor: CountDownLatch? = null

    @Volatile
    private var saveThrows: Exception? = null
    private val repo = MessageRepository(saver = Executor {
        saveReached?.countDown()
        saveWaitsFor?.await(30, TimeUnit.SECONDS)
        it.run()
        saveThrows?.let { failure -> throw failure }
    })
    private val owner = OwnerQueue()
    private lateinit var manager: MessageManager

    private val work = Semaphore(0)
    private val failedChanges = CopyOnWriteArrayList<Throwable>()

    /** Held by the stand-in for each look at the queue, so that "stopped" is true from the moment it is said. */
    private val look = Any()
    private var linkRuns = true
        get() = synchronized(look) { field }
        set(runs) = synchronized(look) { field = runs }

    @Volatile
    private var open = true
    private val link = Thread({
        while (open) {
            work.tryAcquire(10, TimeUnit.MILLISECONDS)
            synchronized(look) { if (linkRuns) owner.drain { failedChanges += it } }
        }
    }, "sign-link-stand-in").apply { isDaemon = true }

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
                // As in the app: the hook takes the manager from Koin, once the link runs the queue.
                single { manager }
            })
        }
        manager = MessageManager(context, repo, owner)
        owner.wake = { work.release() }
        link.start()
        // The app is up from the link's first look at the queue: before it the hook refuses.
        owner.ask(timeoutMs = 60_000) {}
    }

    @AfterAll
    fun done() {
        open = false
        link.join(5_000)
        stopKoin()
        ArchTaskExecutor.getInstance().setDelegate(null)
        dir.deleteRecursively()
    }

    @BeforeEach
    fun fresh() {
        saveReached = null
        saveWaitsFor = null
        saveThrows = null
        linkRuns = true
        owner.neverWaitsOn(Thread({}, "nobody"))
        hook("!endchoose")
        hook("!unpause")
        byOwner {
            repo.marqueeMessages.clear()
            repo.marqueeMessages.addAll((1..5).map { Message.Marquee.User("MESSAGE $it") })
            repo.oneTimeMessages.clear()
        }
        hook("!first")
        failedChanges.clear()
    }

    /** What the receiver does with the bytes of a broadcast, word for word; the outcome is waited for here. */
    private fun hookStarts(
        command: String,
        answerWithinMs: Long = 60_000,
        late: (AdbCommand.Outcome) -> Unit = {},
    ): LinkedBlockingQueue<AdbCommand.Outcome> {
        val outcomes = LinkedBlockingQueue<AdbCommand.Outcome>()
        AdbCommand.receive(
            command = { AdbCommand.Bytes(command.toByteArray(Charsets.UTF_8)) },
            app = { AdbCommand.entryWhenUp(GlobalContext.get(), 0) },
            answerWithinMs = answerWithinMs,
            late = { outcome, _ -> late(outcome) },
        ) { outcome, _ -> outcomes.put(outcome) }
        return outcomes
    }

    private fun hook(command: String): String =
        hookStarts(command).poll(10, TimeUnit.SECONDS)?.answer ?: "no answer within 10 s"

    private fun <T> byOwner(change: () -> T): T = owner.ask(timeoutMs = 5_000, change = change)

    private fun stored(): List<String> = byOwner { repo.marqueeMessages.map { it.str } }

    /** What a request of the sign gets in chooser mode. */
    private fun shown(): String = byOwner { (manager.getNextMessage() as Message.Chooser).let { "${it.flashy} ${it.str}" } }

    @Test
    fun `the answer comes when the owner has carried the command out, not when it was handed over`() {
        linkRuns = false
        val outcomes = hookStarts("!delete")

        assertNull(outcomes.poll(300, TimeUnit.MILLISECONDS), "nothing has been carried out, so there is nothing to answer yet")
        assertEquals(5, repo.marqueeMessages.size)

        linkRuns = true
        assertEquals("ok 7 bytes", outcomes.poll(5, TimeUnit.SECONDS)?.answer)
        assertEquals(4, repo.marqueeMessages.size, "the answer says what has happened")
    }

    @Test
    fun `the commands of the phone do through the hook what they do over Bluetooth, and each change of what the chooser shows asks for its push`() {
        // What the sign link is told (MainViewModel wires this to the push): once for every change of
        // what the chooser shows, as for a Bluetooth write. A hook that says "ok" and asks for no push
        // leaves a sign that does not poll on the selection of before.
        val pushes = AtomicInteger()
        manager.onChooserChanged = { pushes.incrementAndGet() }
        try {
            assertEquals("ok 7 bytes", hook("!choose"))
            assertEquals(1, pushes.get(), "entering")
            assertEquals("ok 5 bytes", hook("!next"))
            assertEquals(2, pushes.get(), "a step")
            assertEquals("ok 5 bytes", hook("!next"))
            assertEquals("3/5 MESSAGE 3", shown())
            assertEquals(3, pushes.get(), "a step; looking at it is no change")
            assertEquals("ok 5 bytes", hook("!prev"))
            assertEquals("2/5 MESSAGE 2", shown())
            assertEquals(4, pushes.get(), "a step back")
            assertEquals("ok 7 bytes", hook("!delete"))
            assertEquals(listOf("MESSAGE 1", "MESSAGE 3", "MESSAGE 4", "MESSAGE 5"), stored())
            assertEquals(5, pushes.get(), "another message and another count")
            assertEquals("ok 10 bytes", hook("!endchoose"))
            assertEquals(6, pushes.get(), "leaving")

            assertEquals("ok 18 bytes", hook("hello from adb ✨"))
            assertEquals(5, stored().size)
            assertTrue(stored().first().startsWith("hello from adb", ignoreCase = true), stored().first())
            assertEquals(6, pushes.get(), "outside chooser mode the chooser shows nothing: no push")
        } finally {
            manager.onChooserChanged = {}
        }
    }

    @Test
    fun `a command the app throws on is answered with what it threw, and the next command is carried out`() {
        // An index the list does not have: !last on an empty list leaves -1, and the test puts messages
        // in behind its back. No path of the app's own leads there; it is what makes a change throw today.
        byOwner { repo.marqueeMessages.clear() }
        hook("!last")
        byOwner { repo.marqueeMessages.addAll((1..3).map { Message.Marquee.User("LATE $it") }) }

        val refused = hook("!delete")

        assertTrue(refused.startsWith("failed: the app threw "), refused)
        assertTrue(refused.contains("IndexOutOfBounds"), refused)
        assertEquals(3, stored().size, "nothing was deleted")

        assertEquals("ok 6 bytes", hook("!first"))
        assertEquals("ok 7 bytes", hook("!delete"))
        assertEquals(listOf("LATE 2", "LATE 3"), stored())
        assertTrue(failedChanges.isEmpty(), "the hook was told; the link has nothing left to report: $failedChanges")
    }

    @Test
    fun `with no sign link running the command is refused after the wait and is not carried out later`() {
        linkRuns = false
        val started = System.nanoTime()

        val refused = hook("!delete")

        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("refused: the sign link did not get to the change within 2000 ms (is the sign link running?)", refused)
        assertTrue(waitedMs >= 1_900, "waited $waitedMs ms")

        linkRuns = true
        assertEquals(5, stored().size, "a refused command is not carried out behind the sender's back")
    }

    // ---- "refused" means that nothing happened (round 2 of the gate)

    @Test
    fun `a command the owner had started when the wait ran out is answered with what the owner did - it is not called refused`() {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        saveReached = reached
        saveWaitsFor = release

        // (With a limit for the answer that this test does not reach: "underway" has its own test.)
        val outcomes = hookStarts("!delete", answerWithinMs = 60_000)

        assertTrue(reached.await(5, TimeUnit.SECONDS), "the owner has started the delete: the message is out of the list")
        // The owner is held in the middle of the command, for longer than the hook waits for a
        // command to be STARTED. "refused" now would be a lie, and whoever believed it would
        // send the delete again and lose a second message.
        assertNull(outcomes.poll(OwnerQueue.ASK_TIMEOUT_MS + 700, TimeUnit.MILLISECONDS), "no answer while the owner is at it")

        saveReached = null
        saveWaitsFor = null
        release.countDown()

        assertEquals("ok 7 bytes", outcomes.poll(5, TimeUnit.SECONDS)?.answer)
        assertEquals(listOf("MESSAGE 2", "MESSAGE 3", "MESSAGE 4", "MESSAGE 5"), stored(), "one delete, and it was said")
    }

    @Test
    fun `a command that throws when part of it is done is not called refused - the answer says it may have happened`() {
        saveThrows = IllegalStateException("the card failed")

        val answer = hook("!delete")

        saveThrows = null
        assertEquals("failed: the app threw IllegalStateException (the card failed); the command may have been carried out in part", answer)
        assertEquals(4, stored().size, "the message was deleted before the throw: 'refused' would invite a second delete")
    }

    // ---- gate round 3

    @Test
    fun `a command that changed the messages and then threw a TimeoutException of its own is not called refused`() {
        saveThrows = java.util.concurrent.TimeoutException("the card timed out")

        val answer = hook("!delete")

        saveThrows = null
        // "refused" would say: nothing happened, send it again. The message is gone already.
        assertEquals("failed: the app threw TimeoutException (the card timed out); the command may have been carried out in part", answer)
        assertEquals(4, stored().size)
    }

    @Test
    fun `from the main thread, which must never wait for the owner, the command is carried out aside`() {
        owner.neverWaitsOn(Thread.currentThread())

        // What the receiver does: onReceive is on the main thread and hands the wait to another.
        assertEquals("ok 7 bytes", hook("!delete"))

        // What it must not do: the owner refuses to be waited for here, and says so.
        val onMain = AdbCommand.carriedOut("!delete".toByteArray(), manager::processNewBytesAndTell).answer
        assertTrue(onMain.startsWith("failed: the call threw IllegalStateException"), onMain)

        owner.neverWaitsOn(Thread({}, "nobody"))
        assertEquals(4, stored().size, "one delete was carried out, the one that waited aside")
    }
}
