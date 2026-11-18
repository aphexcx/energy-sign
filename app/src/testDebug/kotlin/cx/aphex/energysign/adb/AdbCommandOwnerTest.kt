package cx.aphex.energysign.adb

import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cx.aphex.energysign.gpt.GPTViewModel
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hook's way into the app, with the REAL MessageManager and OwnerQueue: the bytes go to
 * MessageManager.processNewBytesAndWait, the entry beside the one a Bluetooth write takes
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
    private val repo = MessageRepository(saver = Executor { it.run() })
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
                single { GPTViewModel() }
            })
        }
        manager = MessageManager(context, repo, owner)
        owner.wake = { work.release() }
        link.start()
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
    private fun hookStarts(command: String): LinkedBlockingQueue<AdbCommand.Outcome> {
        val outcomes = LinkedBlockingQueue<AdbCommand.Outcome>()
        AdbCommand.aside(command.toByteArray(Charsets.UTF_8), manager) { outcomes.put(it) }
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
    fun `the commands of the phone do through the hook what they do over Bluetooth`() {
        assertEquals("ok 7 bytes", hook("!choose"))
        assertEquals("ok 5 bytes", hook("!next"))
        assertEquals("ok 5 bytes", hook("!next"))
        assertEquals("3/5 MESSAGE 3", shown())
        assertEquals("ok 5 bytes", hook("!prev"))
        assertEquals("2/5 MESSAGE 2", shown())
        assertEquals("ok 7 bytes", hook("!delete"))
        assertEquals(listOf("MESSAGE 1", "MESSAGE 3", "MESSAGE 4", "MESSAGE 5"), stored())
        assertEquals("ok 10 bytes", hook("!endchoose"))

        assertEquals("ok 18 bytes", hook("hello from adb ✨"))
        assertEquals(5, stored().size)
        assertTrue(stored().first().startsWith("hello from adb", ignoreCase = true), stored().first())
    }

    @Test
    fun `a command the app throws on is answered with what it threw, and the next command is carried out`() {
        // An index the list does not have: !last on an empty list leaves -1, and the test puts messages
        // in behind its back. No path of the app's own leads there; it is what makes a change throw today.
        byOwner { repo.marqueeMessages.clear() }
        hook("!last")
        byOwner { repo.marqueeMessages.addAll((1..3).map { Message.Marquee.User("LATE $it") }) }

        val refused = hook("!delete")

        assertTrue(refused.startsWith("refused: the app threw "), refused)
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
        assertTrue(waitedMs in 1_900..4_000, "waited $waitedMs ms")

        linkRuns = true
        assertEquals(5, stored().size, "a refused command is not carried out behind the sender's back")
    }

    @Test
    fun `from the main thread, which must never wait for the owner, the command is carried out aside`() {
        owner.neverWaitsOn(Thread.currentThread())

        // What the receiver does: onReceive is on the main thread and hands the wait to another.
        assertEquals("ok 7 bytes", hook("!delete"))

        // What it must not do: the owner refuses to be waited for here, and says so.
        val onMain = AdbCommand.carriedOut("!delete".toByteArray(), manager::processNewBytesAndWait).answer
        assertTrue(onMain.startsWith("refused: the app threw IllegalStateException"), onMain)

        owner.neverWaitsOn(Thread({}, "nobody"))
        assertEquals(4, stored().size, "one delete was carried out, the one that waited aside")
    }
}
