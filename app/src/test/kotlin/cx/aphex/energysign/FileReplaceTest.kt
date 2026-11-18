package cx.aphex.energysign

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one sequence every saved file goes through ([replaceContent]): the data to storage, the
 * rename, the directory to storage. The message files use it through [replaceWith], the ad
 * files through AdStore (see AdStoreTest for those).
 */
class FileReplaceTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "list.txt")
    private val beside get() = File(dir, "list.txt.tmp")

    private fun state(): String =
        "beside " + (if (beside.exists()) beside.readText() else null) + ", in place " + (if (file.exists()) file.readText() else null)

    @Test
    fun `the data is forced to storage, then the file is renamed, then the directory is forced to storage`() {
        file.writeText("old")
        val steps = mutableListOf<String>()

        file.replaceContent(
            syncData = { out -> steps += "data: " + state(); out.fd.sync() },
            syncDirectory = { directory -> steps += "directory ${directory.canonicalFile == dir.canonicalFile}: " + state() },
            warn = { steps += "warned: $it" },
        ) { it.write("new".toByteArray()) }

        assertEquals(
            listOf(
                "data: beside new, in place old",
                "directory true: beside null, in place new",
            ),
            steps
        )
    }

    @Test
    fun `a first save has the same three steps`() {
        val steps = mutableListOf<String>()

        file.replaceContent(
            syncData = { steps += "data: " + state() },
            syncDirectory = { steps += "directory: " + state() },
        ) { it.write("new".toByteArray()) }

        assertEquals(listOf("data: beside new, in place null", "directory: beside null, in place new"), steps)
    }

    @Test
    fun `the lines of a message file go the same way, the directory last`() {
        file.writeText("old\n")
        val steps = mutableListOf<String>()

        file.replaceWith(listOf("one", "two"), syncDirectory = { steps += "directory: " + state() })

        val twoLines = "one" + System.lineSeparator() + "two" + System.lineSeparator()
        assertEquals(listOf("directory: beside null, in place $twoLines"), steps)
        assertEquals(listOf("one", "two"), file.readLines())
    }

    @Test
    fun `a directory that cannot be forced to storage is reported and the save is done`() {
        file.writeText("old")
        val warnings = mutableListOf<String>()

        file.replaceContent(
            syncDirectory = { throw IOException("not supported here") },
            warn = { warnings += it },
        ) { it.write("new".toByteArray()) } // does not throw

        assertEquals("new", file.readText())
        assertFalse(beside.exists())
        assertEquals(1, warnings.size)
        assertContains(warnings.single(), "list.txt")
        assertContains(warnings.single(), "not supported here")
    }

    @Test
    fun `the default report of that goes to the log and the save is still done`() {
        file.writeText("old")

        file.replaceWith(listOf("new"), syncDirectory = { throw IOException("not supported here") })

        assertEquals(listOf("new"), file.readLines())
    }

    @Test
    fun `data that cannot be forced to storage fails the save, the old content stays and the directory is left alone`() {
        file.writeText("old")
        var directorySynced = false

        assertFailsWith<IOException> {
            file.replaceContent(
                syncData = { throw IOException("no space left") },
                syncDirectory = { directorySynced = true },
            ) { it.write("new".toByteArray()) }
        }

        assertEquals("old", file.readText())
        assertFalse(directorySynced)
    }

    @Test
    fun `a write that fails half way leaves the old content`() {
        file.writeText("old")

        assertFailsWith<IOException> {
            file.replaceContent { out ->
                out.write("ne".toByteArray())
                throw IOException("the power of the test went")
            }
        }

        assertEquals("old", file.readText())
    }

    @Test
    fun `a real directory can be forced to storage, one that is not there cannot`() {
        dir.syncDirectory() // does not throw on this machine

        assertFailsWith<IOException> { File(dir, "not-there").syncDirectory() }
    }

    @Test
    fun `the real steps, all three, on a real directory`() {
        val warnings = mutableListOf<String>()

        file.replaceContent(warn = { warnings += it }) { it.write("new".toByteArray()) }

        assertEquals("new", file.readText())
        assertEquals(emptyList(), warnings)
        assertTrue(dir.list()!!.toList() == listOf("list.txt"))
    }
}
