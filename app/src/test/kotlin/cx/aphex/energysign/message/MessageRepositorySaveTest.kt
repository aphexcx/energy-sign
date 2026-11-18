package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
    fun `saves are written in the order they were asked for, so the newest list is what stays`() {
        repo.pushMarqueeMessage(Message.Marquee.User("ONE"))
        repo.saveMarqueeMessages(dir)
        repo.pushMarqueeMessage(Message.Marquee.User("TWO"))
        repo.saveMarqueeMessages(dir)
        repo.marqueeMessages.removeAt(0)
        repo.saveLater { File(dir, "marker").writeText(strings().joinToString()) }
        repo.saveMarqueeMessages(dir)
        waiting.forEach { it.run() }
        // the file is oldest first; the marker saw the second save and not yet the third
        assertEquals("ONE, TWO", File(dir, "marker").readText())
        assertEquals(listOf("ONE"), strings())
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
    fun `saving the 77 messages of the sign costs the owner a copy, the files cost the saver`() {
        // the list on the sign on 2026-11-17: 77 messages, here 60 characters each
        repeat(77) { repo.pushMarqueeMessage(Message.Marquee.User("MESSAGE $it ".padEnd(60, 'x'))) }
        repeat(200) { repo.saveMarqueeMessages(dir) } // warm
        waiting.clear()

        val rounds = 200
        val t0 = System.nanoTime()
        repeat(rounds) { repo.saveMarqueeMessages(dir) }
        val ownerUs = (System.nanoTime() - t0) / 1000 / rounds
        val t1 = System.nanoTime()
        waiting.forEach { it.run() }
        val saverUs = (System.nanoTime() - t1) / 1000 / rounds

        println("save of 77 messages on this machine: owner $ownerUs us (the copy), saver $saverUs us (two files)")
        assertEquals(77, strings().size)
        // the bound is loose on purpose: what matters is that the owner's share has no file in it
        assertTrue(ownerUs < 5_000, "the owner's share took $ownerUs us")
    }
}
