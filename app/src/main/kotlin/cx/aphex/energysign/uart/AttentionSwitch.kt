package cx.aphex.energysign.uart

import cx.aphex.energysign.replaceWith
import java.io.File

/**
 * Whether the app pushes the chooser to the sign, and how: the switch, where it is kept, and
 * who may set it.
 *
 * Pushes are OFF unless they are switched on: a sign with the 2.1 firmware does not listen for
 * them. The command `!attn off|auto|break|zeros` sets the switch and it is kept in a file, so
 * it is the same after the board has restarted (it loses power without warning). The system
 * property, when it holds one of the four words, wins over the file; it is gone after a
 * restart and is for the bench.
 *
 * [set] and [inForce] are called on the sign link thread; the file is written through
 * [saveLater], not by the caller.
 */
class AttentionSwitch(
    private val file: File,
    /** The system property as it is set now; null or anything but the four words counts as not set. */
    private val property: () -> String?,
    private val saveLater: (write: () -> Unit) -> Unit,
    private val log: (line: String) -> Unit,
) {
    @Volatile
    private var saved: Attention = readSaved()

    @Volatile
    private var logged: Attention? = null

    init {
        inForce()
    }

    /** The value that counts now. Says so in the log at the start and whenever it has changed. */
    fun inForce(): Attention {
        val fromProperty = Attention.parse(property())
        val value = fromProperty ?: saved
        if (value != logged) {
            logged = value
            log("Pushes to the sign: ${word(value)}" + if (fromProperty != null) " (from the system property; saved: ${word(saved)})" else "")
        }
        return value
    }

    /** Sets and keeps the switch. [word] must be one of the four; anything else changes nothing and returns false. */
    fun set(word: String): Boolean {
        val value = Attention.parse(word)
        if (value == null) {
            log("Pushes to the sign: '$word' is not one of off, auto, break, zeros; nothing changed")
            return false
        }
        saved = value
        saveLater { file.replaceWith(listOf(word(value))) }
        logged = null // a change is always said, also when the property keeps another value in force
        inForce()
        return true
    }

    private fun readSaved(): Attention =
        try {
            Attention.parse(file.takeIf { it.exists() }?.readText()) ?: Attention.OFF
        } catch (e: Exception) {
            Attention.OFF
        }

    private fun word(value: Attention): String = value.name.lowercase()

    companion object {
        const val FILE_NAME: String = "attention.txt"
    }
}
