package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Saving the stored messages: the thread that owns them (and answers the sign) only copies the
 * list; the files are written by the saver, later and in order.
 */
class MessageRepositorySaveTest {
    @TempDir
    lateinit var dir: File

    /** The saver's thread, run by hand. */
    private val waiting = mutableListOf<Runnable>()
    private val repo = MessageRepository(saver = Executor { waiting += it })

    private fun strings() = File(dir, "signstrings.txt").readLines()

    @Test
    fun `saving writes nothing on the calling thread`() {
        repo.pushMarqueeMessage(Message.Marquee.User("ONE"))
        repo.saveMarqueeMessages(dir)
        assertFalse(File(dir, "signstrings.txt").exists())
        assertFalse(File(dir, "signstrings.bin").exists())
        assertEquals(1, waiting.size)
        waiting.removeAt(0).run()
        assertEquals(listOf("ONE"), strings())
        assertEquals(1, File(dir, "signstrings.bin").readLines().size)
    }

    @Test
    fun `a save holds the list as it was when it was asked for`() {
        repo.pushMarqueeMessage(Message.Marquee.User("ONE"))
        repo.saveMarqueeMessages(dir)
        repo.pushMarqueeMessage(Message.Marquee.User("TWO")) // after the save was asked for
        repo.marqueeMessages.removeAt(1)
        waiting.removeAt(0).run()
        assertEquals(listOf("ONE"), strings())
    }

    @Test
    fun `only the newest list is kept for the saver, saves that were still waiting are not written one by one`() {
        repo.pushMarqueeMessage(Message.Marquee.User("ONE"))
        repo.saveMarqueeMessages(dir)
        repo.pushMarqueeMessage(Message.Marquee.User("TWO"))
        repo.saveMarqueeMessages(dir)
        repo.marqueeMessages.removeAt(1)
        repo.saveMarqueeMessages(dir)
        assertEquals(1, waiting.size) // three saves, one piece of work for the saver
        waiting.removeAt(0).run()
        assertEquals(listOf("TWO"), strings())

        // and a save asked for after the saver has taken its list is the next piece of work
        repo.pushMarqueeMessage(Message.Marquee.User("THREE"))
        repo.saveMarqueeMessages(dir)
        assertEquals(1, waiting.size)
        waiting.removeAt(0).run()
        assertEquals(listOf("TWO", "THREE"), strings())
    }

    @Test
    fun `the default saver is one thread of its own`() {
        val real = MessageRepository()
        val names = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val done = java.util.concurrent.CountDownLatch(3)
        repeat(3) {
            real.saveLater {
                names += Thread.currentThread().name
                done.countDown()
            }
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(listOf("sign-save", "sign-save", "sign-save"), names.toList())
    }

    @Test
    fun `what the owner does for a save does not grow with the text, the lines are made by the saver`() {
        // far more than the sign will ever hold: 300 000 messages of 256 characters
        val text = "x".repeat(256)
        repeat(300_000) { repo.marqueeMessages.add(Message.Marquee.User(text)) }
        // what making the lines of one of the two files costs on this machine
        val linesUs = (1..5).minOf {
            val t0 = System.nanoTime()
            check(repo.marqueeMessages.map { it.toString() }.size == 300_000)
            (System.nanoTime() - t0) / 1000
        }
        // and what the owner does for a save: a copy of 300 000 references
        val ownerUs = (1..5).minOf {
            val t0 = System.nanoTime()
            repo.saveMarqueeMessages(dir)
            (System.nanoTime() - t0) / 1000
        }
        println("a save of 300 000 messages on this machine: the owner's share is $ownerUs us, making one file's lines $linesUs us")
        assertTrue(ownerUs * 3 < linesUs, "the owner's share took $ownerUs us, making the lines of one file takes $linesUs us")
        assertEquals(1, waiting.size)
    }

    // ---- whole or not at all

    /** A file that takes [bytes] bytes and then fails: the power went, the storage is full. */
    private fun dyingAfter(bytes: Int): (File) -> FileOutputStream = { file ->
        object : FileOutputStream(file) {
            var left = bytes
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > left) {
                    super.write(b, off, left)
                    flush()
                    throw IOException("the writer died")
                }
                left -= len
                super.write(b, off, len)
            }
        }
    }

    @Test
    fun `a writer that dies after some bytes leaves the old files readable and whole`() {
        repo.pushMarqueeMessage(Message.Marquee.User("ONE"))
        repo.pushMarqueeMessage(Message.Marquee.User("TWO"))
        repo.saveMarqueeMessages(dir)
        waiting.removeAt(0).run()
        val before = File(dir, "signstrings.bin").readBytes()

        repo.pushMarqueeMessage(Message.Marquee.User("THREE AND A GOOD DEAL MORE"))
        repo.saveMarqueeMessages(dir, open = dyingAfter(bytes = 9))
        waiting.removeAt(0).run()

        assertEquals(listOf("ONE", "TWO"), strings())
        assertTrue(before.contentEquals(File(dir, "signstrings.bin").readBytes()))
        // and the app that starts after it reads the old list, and clears away what the cut-off save left
        assertTrue(File(dir, "signstrings.bin.tmp").exists())
        val loaded = MessageRepository(saver = { it.run() }).loadUserMessages(dir)
        assertEquals(listOf("TWO", "ONE"), loaded.map { it.str })
        assertFalse(File(dir, "signstrings.bin.tmp").exists())
        assertFalse(File(dir, "signstrings.txt.tmp").exists())
    }

    @Test
    fun `a save that went through leaves nothing beside the files`() {
        repo.pushMarqueeMessage(Message.Marquee.User("ONE"))
        repo.saveMarqueeMessages(dir)
        waiting.removeAt(0).run()
        assertEquals(setOf("signstrings.bin", "signstrings.txt"), dir.list()!!.toSet())
        assertEquals(listOf("ONE"), MessageRepository(saver = { it.run() }).loadUserMessages(dir).map { it.str })
    }
}
