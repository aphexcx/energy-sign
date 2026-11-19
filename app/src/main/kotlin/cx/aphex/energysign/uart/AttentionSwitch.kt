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
 * property (`debug.energysign.attn`), while it holds one of the four words, wins over the file;
 * it is gone after a restart of the board and is for the bench.
 *
 * The value in force is said in the log when the app starts and whenever it has changed. A
 * change of the property is seen at the next look ([inForce]); the link looks whenever the
 * chooser changes.
 *
 * The file is read once, where this is built. [set] and [inForce] are called on the sign link
 * thread; the file is written through [saveLater], not by the caller.
 */
class AttentionSwitch(
    private val file: File,
    /** The system property as it is set now; null or anything but the four words counts as not set. */
    private val property: () -> String?,
    /** Runs a write later, on the thread that writes the app's files. */
    private val saveLater: (write: () -> Unit) -> Unit,
    private val log: (line: String) -> Unit,
) {
    @Volatile
    private var saved: Attention = readSaved()

    /** What the log was last told: the value in force, and whether the property gave it. */
    @Volatile
    private var logged: Pair<Attention, Boolean>? = null

    init {
        inForce()
    }

    /** The value that counts now. Says so in the log at the start and whenever it has changed. */
    fun inForce(): Attention {
        val fromProperty = try {
            Attention.parse(property())
        } catch (e: Exception) {
            null
        }
        val value = fromProperty ?: saved
        val now = value to (fromProperty != null)
        if (now != logged) {
            logged = now
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
        saveLater {
            // On the saver's thread: nothing may escape into it
            try {
                file.replaceWith(listOf(word(value)))
            } catch (e: Throwable) {
                log("Pushes to the sign: '${word(value)}' could not be saved, it holds until the app restarts: $e")
            }
        }
        logged = null // a change is always said, also when the property keeps another value in force
        inForce()
        return true
    }

    private fun readSaved(): Attention {
        try {
            if (!file.exists()) return Attention.OFF
            Attention.parse(file.readText())?.let { return it }
            log("Pushes to the sign: ${file.name} does not hold one of off, auto, break, zeros; off")
        } catch (e: Exception) {
            log("Pushes to the sign: ${file.name} could not be read ($e); off")
        }
        return Attention.OFF
    }

    private fun word(value: Attention): String = value.name.lowercase()

    companion object {
        const val FILE_NAME: String = "attention.txt"
    }
}
