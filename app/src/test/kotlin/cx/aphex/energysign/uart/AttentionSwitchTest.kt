package cx.aphex.energysign.uart

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The push switch: off unless switched on, kept across a restart, and the system property over the file. */
class AttentionSwitchTest {
    @TempDir
    lateinit var dir: File

    private var property: () -> String? = { null }
    private val saves = mutableListOf<() -> Unit>()

    /** The saver takes no more work (its executor was shut down). */
    private var saverRefuses = false
    private val log = mutableListOf<String>()

    private fun file() = File(dir, AttentionSwitch.FILE_NAME)

    /** The app starting: the file is read once. */
    private fun started(file: File = file()) = AttentionSwitch(
        file = file,
        property = { property() },
        saveLater = {
            if (saverRefuses) throw java.util.concurrent.RejectedExecutionException("shut down")
            saves += it
        },
        log = { log += it },
    )

    private fun saverRuns() {
        saves.forEach { it() }
        saves.clear()
    }

    @Test
    fun `pushes are off when nothing was ever set, and the log says so once, at the start`() {
        val switch = started()
        assertEquals(listOf("Pushes to the sign: off"), log) // said before anyone looked
        assertEquals(Attention.OFF, switch.inForce())
        assertEquals(Attention.OFF, switch.inForce())
        assertEquals(listOf("Pushes to the sign: off"), log)
        assertFalse(file().exists()) // and looking writes nothing
        assertEquals(emptyList(), saves)
    }

    @Test
    fun `the command sets it, the saver keeps it, and it is the same after a restart`() {
        val switch = started()
        assertTrue(switch.set("auto"))
        assertEquals(Attention.AUTO, switch.inForce())
        assertFalse(file().exists()) // not written by the caller
        saverRuns()
        assertEquals("auto", file().readText().trim())

        log.clear()
        assertEquals(Attention.AUTO, started().inForce()) // the board restarted
        assertEquals(listOf("Pushes to the sign: auto"), log)

        started().set("off")
        saverRuns()
        assertEquals(Attention.OFF, started().inForce())
    }

    @Test
    fun `each of the four words can be set and is there after a restart`() {
        val switch = started()
        for ((word, value) in listOf("zeros" to Attention.ZEROS, "break" to Attention.BREAK, "auto" to Attention.AUTO, "off" to Attention.OFF)) {
            assertTrue(switch.set(word))
            assertEquals(value, switch.inForce())
            saverRuns()
            assertEquals(value, started().inForce())
        }
    }

    @Test
    fun `a bad word changes nothing and saves nothing`() {
        val switch = started()
        switch.set("zeros")
        saverRuns()
        for (bad in listOf("on", "", "ZEROS!", "off now")) {
            assertFalse(switch.set(bad), bad)
        }
        assertEquals(Attention.ZEROS, switch.inForce())
        assertEquals(emptyList(), saves)
        assertEquals(Attention.ZEROS, started().inForce())
        assertTrue(log.any { it.contains("'on' is not one of off, auto, break, zeros") })
    }

    @Test
    fun `the system property wins over the file while it holds one of the four words`() {
        started().set("auto")
        saverRuns()
        property = { "zeros" }
        val switch = started()
        assertEquals(Attention.ZEROS, switch.inForce())
        assertTrue(log.last().contains("zeros (from the system property; saved: auto)"), log.last())

        property = { "something else" }
        assertEquals(Attention.AUTO, switch.inForce())
        property = { "" }
        assertEquals(Attention.AUTO, switch.inForce())
        property = { "off" }
        assertEquals(Attention.OFF, switch.inForce())
        property = { null }
        assertEquals(Attention.AUTO, switch.inForce())
    }

    @Test
    fun `a property that cannot be read counts as not set`() {
        started().set("zeros")
        saverRuns()
        property = { throw IllegalStateException("no such method") }
        assertEquals(Attention.ZEROS, started().inForce())
    }

    @Test
    fun `the property does not change what is saved, and a command under it is still kept`() {
        property = { "break" }
        val switch = started()
        switch.set("zeros")
        assertEquals(Attention.BREAK, switch.inForce())
        assertTrue(log.last().contains("break (from the system property; saved: zeros)"), log.last())
        saverRuns()
        property = { null } // the board restarted: the property is gone
        assertEquals(Attention.ZEROS, started().inForce())
    }

    @Test
    fun `the value in force is logged at the start and at every change, not at every look`() {
        val switch = started()
        repeat(10) { switch.inForce() }
        switch.set("auto")
        repeat(10) { switch.inForce() }
        property = { "off" }
        repeat(10) { switch.inForce() }
        property = { "auto" }
        repeat(10) { switch.inForce() }
        property = { null } // the same value, but now it is the saved one that counts
        repeat(10) { switch.inForce() }
        assertEquals(
            listOf(
                "Pushes to the sign: off",
                "Pushes to the sign: auto",
                "Pushes to the sign: off (from the system property; saved: auto)",
                "Pushes to the sign: auto (from the system property; saved: auto)",
                "Pushes to the sign: auto",
            ),
            log,
        )
    }

    @Test
    fun `setting the word that is already set is said again and saved again`() {
        val switch = started()
        switch.set("zeros")
        switch.set("zeros")
        assertEquals(listOf("Pushes to the sign: off", "Pushes to the sign: zeros", "Pushes to the sign: zeros"), log)
        assertEquals(2, saves.size)
    }

    @Test
    fun `a file that does not hold one of the words is off, and the log says why`() {
        file().writeText("garbage")
        assertEquals(Attention.OFF, started().inForce())
        assertTrue(log.first().contains("attention.txt does not hold one of off, auto, break, zeros"), log.toString())

        file().writeText("zeros\nauto\n") // two words are not one
        assertEquals(Attention.OFF, started().inForce())

        file().writeText("zeros" + "\n".repeat(100)) // longer than a word: damaged, and not read
        assertEquals(Attention.OFF, started().inForce())

        file().writeText("zeros\n")
        assertEquals(Attention.ZEROS, started().inForce())
    }

    @Test
    fun `a file that cannot be read is off, and the app starts all the same`() {
        file().mkdirs() // a directory where the file should be
        assertEquals(Attention.OFF, started().inForce())
        assertTrue(log.first().contains("attention.txt could not be read"), log.toString())
    }

    @Test
    fun `a save that fails throws nothing into the saver, is said, and the word holds until the restart`() {
        val nowhere = File(File(dir, "no-such-directory"), AttentionSwitch.FILE_NAME)
        val switch = started(nowhere)
        assertTrue(switch.set("zeros"))
        saverRuns() // must not throw: on the device that would end the app
        assertTrue(log.last().contains("'zeros' could not be saved"), log.last())
        assertEquals(Attention.ZEROS, switch.inForce())
        assertEquals(Attention.OFF, started(nowhere).inForce())
    }

    @Test
    fun `a saver that takes no more work does not stop the command, the word is in force and both are said`() {
        val switch = started()
        saverRefuses = true
        assertTrue(switch.set("zeros"))
        assertEquals(Attention.ZEROS, switch.inForce())
        assertEquals("Pushes to the sign: zeros", log[1])
        assertTrue(log.last().contains("'zeros' could not be saved"), log.last())
    }

    @Test
    fun `a save that was cut off leaves the word that was saved before`() {
        started().set("zeros")
        saverRuns()
        // the power went while "off" was being written beside the file: the rename never happened
        File(dir, AttentionSwitch.FILE_NAME + ".tmp").writeText("of")
        assertEquals(Attention.ZEROS, started().inForce())
        // and the leftover is not in the way of the next save
        started().set("auto")
        saverRuns()
        assertEquals(Attention.AUTO, started().inForce())
    }
}
