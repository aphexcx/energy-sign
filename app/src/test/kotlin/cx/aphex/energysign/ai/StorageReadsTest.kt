package cx.aphex.energysign.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage that hangs: no reply reads it or waits long for it, and there is never more than one
 * thread hanging in the read of a file (class B).
 */
class StorageReadsTest {

    // ---- a setting read once, at start

    @Test
    fun `a setting is read once, by a thread of its own, and asking for it never reads and never waits`() {
        val reads = AtomicInteger()
        val readOn = CopyOnWriteArrayList<Thread>()
        val read = CountDownLatch(1)
        val setting = ReadOnceAtStart(untilRead = "build", read = {
            reads.incrementAndGet()
            readOn += Thread.currentThread()
            read.countDown()
            "file"
        })

        assertTrue(read.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        repeat(100) { assertEquals("file", setting.get()) }

        assertEquals(1, reads.get(), "asking for the setting must not read the file again")
        assertTrue(readOn.single() !== Thread.currentThread(), "the read is not done by whoever makes the object")
    }

    @Test
    fun `while the read of a setting hangs, and for ever when it never ends, the setting of the build is used at once`() {
        val hang = CountDownLatch(1)
        val setting = ReadOnceAtStart(untilRead = "build", read = {
            hang.await()
            "file"
        })

        val startedAt = System.nanoTime()
        repeat(100) { assertEquals("build", setting.get()) }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(tookMs < 500, "asking took $tookMs ms: it waited for the read")
        hang.countDown()
    }

    @Test
    fun `a read of a setting that throws, an Error included, leaves the setting of the build`() {
        val done = CountDownLatch(2)
        // What a thread lets escape ends the app on Android: the test's threads say what escaped.
        val escaped = CopyOnWriteArrayList<Throwable>()
        val threads: (Runnable) -> Unit = { work ->
            Thread(work).apply { setUncaughtExceptionHandler { _, e -> escaped += e } }.start()
        }
        val broken = ReadOnceAtStart<String>(untilRead = "build", read = { done.countDown(); throw IOException("card error") }, startThread = threads)
        val worse = ReadOnceAtStart<String>(untilRead = "build", read = { done.countDown(); throw NoClassDefFoundError("x") }, startThread = threads)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        assertEquals("build", broken.get())
        assertEquals("build", worse.get())
        assertTrue(escaped.isEmpty(), "the read's failure left its thread: $escaped")
    }

    // ---- a file read again for every query, by one thread at most

    /** Starts the read threads and counts them. */
    private class Threads {
        val started = AtomicInteger()
        val start: (Runnable) -> Unit = {
            started.incrementAndGet()
            Thread(it, "test-read").apply { isDaemon = true }.start()
        }
    }

    @Test
    fun `a read gives what the file says, and the next one reads the file again`() {
        val reads = AtomicInteger()
        val file = OneReadAtATime(read = { "version ${reads.incrementAndGet()}" })

        assertEquals("version 1", runBlocking { file.await(5_000) })
        assertEquals("version 2", runBlocking { file.await(5_000) })
    }

    @Test
    fun `a read that hangs is waited for by every caller for its own limit, and is never started a second time`() {
        val threads = Threads()
        val reads = AtomicInteger()
        val hang = CountDownLatch(1)
        val file = OneReadAtATime(
            read = {
                reads.incrementAndGet()
                hang.await()
                "at last"
            },
            startThread = threads.start,
        )

        // Query after query, each waits its own short limit and goes on without the file.
        repeat(5) {
            val startedAt = System.nanoTime()
            assertNull(runBlocking { file.await(100) })
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(tookMs in 80..2_000, "waited $tookMs ms for a 100 ms limit")
        }

        assertEquals(1, reads.get(), "the hanging read was started again")
        assertEquals(1, threads.started.get(), "one thread hangs, however many callers there were")

        // When the read comes back after all, whoever waits for it gets it; then a fresh read is made.
        Thread {
            Thread.sleep(300) // the caller below is waiting by then
            hang.countDown()
        }.start()
        assertEquals("at last", runBlocking { file.await(5_000) })
        assertEquals(1, reads.get(), "the caller waited for the read that was under way")
        Thread.sleep(50)
        assertEquals("at last", runBlocking { file.await(5_000) })
        assertEquals(2, reads.get())
    }

    @Test
    fun `callers at the same moment share one read`() {
        val threads = Threads()
        val reads = AtomicInteger()
        val go = CountDownLatch(1)
        val file = OneReadAtATime(
            read = {
                reads.incrementAndGet()
                go.await()
                "shared"
            },
            startThread = threads.start,
        )

        val results = runBlocking {
            val callers = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { file.await(5_000) } }
            Thread.sleep(200)
            go.countDown()
            callers.awaitAll()
        }

        assertEquals(List(8) { "shared" }, results)
        assertEquals(1, reads.get())
        assertEquals(1, threads.started.get())
    }

    @Test
    fun `a read that throws is no answer, and the next caller reads again`() {
        val reads = AtomicInteger()
        val file = OneReadAtATime<String>(read = {
            if (reads.incrementAndGet() == 1) throw IOException("card error") else "second try"
        })

        assertNull(runBlocking { file.await(5_000) })
        Thread.sleep(50)
        assertEquals("second try", runBlocking { file.await(5_000) })
    }

    @Test
    fun `when no thread can be started there is no answer, and the next caller tries again`() {
        var refuse = true
        val file = OneReadAtATime(
            read = { "read" },
            startThread = { if (refuse) throw OutOfMemoryError("unable to create new native thread") else Thread(it).start() },
        )

        assertNull(runBlocking { file.await(1_000) })
        refuse = false
        assertEquals("read", runBlocking { file.await(5_000) })
    }
}
