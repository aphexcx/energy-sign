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

    private var property: String? = null
    private val saves = mutableListOf<() -> Unit>()
    private val log = mutableListOf<String>()

    /** The app starting: the file is read once. */
    private fun started() = AttentionSwitch(
        file = File(dir, AttentionSwitch.FILE_NAME),
        property = { property },
        saveLater = { saves += it },
        log = { log += it },
    )

    private fun saverRuns() {
        saves.forEach { it() }
        saves.clear()
    }

    @Test
    fun `pushes are off when nothing was ever set, and the log says so once`() {
        val switch = started()
        assertEquals(Attention.OFF, switch.inForce())
        assertEquals(Attention.OFF, switch.inForce())
        assertEquals(listOf("Pushes to the sign: off"), log)
    }

    @Test
    fun `the command sets it, the saver keeps it, and it is the same after a restart`() {
        val switch = started()
        assertTrue(switch.set("auto"))
        assertEquals(Attention.AUTO, switch.inForce())
        assertFalse(File(dir, AttentionSwitch.FILE_NAME).exists()) // not written by the caller
        saverRuns()

        assertEquals(Attention.AUTO, started().inForce()) // the board restarted
        assertEquals("Pushes to the sign: auto", log.last())

        started().set("off")
        saverRuns()
        assertEquals(Attention.OFF, started().inForce())
    }

    @Test
    fun `each of the four words can be set`() {
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
        property = "zeros"
        val switch = started()
        assertEquals(Attention.ZEROS, switch.inForce())
        assertTrue(log.last().contains("zeros (from the system property; saved: auto)"), log.last())

        property = "something else"
        assertEquals(Attention.AUTO, switch.inForce())
        property = ""
        assertEquals(Attention.AUTO, switch.inForce())
        property = "off"
        assertEquals(Attention.OFF, switch.inForce())
    }

    @Test
    fun `the property does not change what is saved, and a command under it is still kept`() {
        property = "break"
        val switch = started()
        switch.set("zeros")
        assertEquals(Attention.BREAK, switch.inForce())
        saverRuns()
        property = null // the board restarted: the property is gone
        assertEquals(Attention.ZEROS, started().inForce())
    }

    @Test
    fun `the value in force is logged at the start and at every change, not at every look`() {
        val switch = started()
        repeat(10) { switch.inForce() }
        switch.set("auto")
        repeat(10) { switch.inForce() }
        property = "off"
        repeat(10) { switch.inForce() }
        assertEquals(
            listOf(
                "Pushes to the sign: off",
                "Pushes to the sign: auto",
                "Pushes to the sign: off (from the system property; saved: auto)",
            ),
            log,
        )
    }

    @Test
    fun `a file that cannot be read as one of the words is off`() {
        File(dir, AttentionSwitch.FILE_NAME).writeText("garbage")
        assertEquals(Attention.OFF, started().inForce())
    }
}
