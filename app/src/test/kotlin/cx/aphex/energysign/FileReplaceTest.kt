package cx.aphex.energysign

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A file whose descriptor is closed as soon as something has been written to it. Whoever then
 * really forces it to storage fails; whoever only says so does not notice. That is how a test
 * can tell that the production step is the real one.
 */
class ClosesAfterWrite(file: File) : FileOutputStream(file) {
    override fun write(b: ByteArray) {
        super.write(b)
        close()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        super.write(b, off, len)
        close()
    }
}

/**
 * A channel that is no file: it only notes what it is asked to force, and that it was closed.
 * With it a test sees the force itself, which leaves no trace a test could find on a real disk.
 */
class WatchedChannel(private val onForce: (metaData: Boolean) -> Unit = {}) : FileChannel() {
    val forced = mutableListOf<Boolean>()
    var closed = false

    override fun force(metaData: Boolean) {
        forced += metaData
        onForce(metaData)
    }

    override fun implCloseChannel() {
        closed = true
    }

    private fun notAFile(): Nothing = throw UnsupportedOperationException("only force and close are expected")
    override fun read(dst: ByteBuffer): Int = notAFile()
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = notAFile()
    override fun read(dst: ByteBuffer, position: Long): Int = notAFile()
    override fun write(src: ByteBuffer): Int = notAFile()
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = notAFile()
    override fun write(src: ByteBuffer, position: Long): Int = notAFile()
    override fun position(): Long = notAFile()
    override fun position(newPosition: Long): FileChannel = notAFile()
    override fun size(): Long = notAFile()
    override fun truncate(size: Long): FileChannel = notAFile()
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = notAFile()
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = notAFile()
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = notAFile()
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = notAFile()
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock = notAFile()
}

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

    // ---- the order of the steps (with steps of the test's own, to look from inside them)

    @Test
    fun `the data is forced to storage, then the file is renamed, then the directory is forced to storage`() {
        file.writeText("old")
        val seen = mutableListOf<String>()

        file.replaceContent(
            SaveSteps(
                syncData = { seen += "data: " + state() },
                syncDirectory = { directory -> seen += "directory ${directory.canonicalFile == dir.canonicalFile}: " + state() },
            ),
            warn = { seen += "warned: $it" },
        ) { it.write("new".toByteArray()) }

        assertEquals(
            listOf(
                "data: beside new, in place old",
                "directory true: beside null, in place new",
            ),
            seen
        )
    }

    @Test
    fun `a first save has the same three steps`() {
        val seen = mutableListOf<String>()

        file.replaceContent(
            SaveSteps(syncData = { seen += "data: " + state() }, syncDirectory = { seen += "directory: " + state() }),
        ) { it.write("new".toByteArray()) }

        assertEquals(listOf("data: beside new, in place null", "directory: beside null, in place new"), seen)
    }

    @Test
    fun `the lines of a message file go the same way, the directory last`() {
        file.writeText("old\n")
        val seen = mutableListOf<String>()

        file.replaceWith(listOf("one", "two"), SaveSteps(syncDirectory = { seen += "directory: " + state() }))

        val twoLines = "one" + System.lineSeparator() + "two" + System.lineSeparator()
        assertEquals(listOf("directory: beside null, in place $twoLines"), seen)
        assertEquals(listOf("one", "two"), file.readLines())
    }

    // ---- a step that fails

    @Test
    fun `a directory that cannot be forced to storage is reported and the save is done`() {
        file.writeText("old")
        val warnings = mutableListOf<String>()

        file.replaceContent(
            SaveSteps(syncDirectory = { throw IOException("not supported here") }),
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

        file.replaceWith(listOf("new"), SaveSteps(syncDirectory = { throw IOException("not supported here") }))

        assertEquals(listOf("new"), file.readLines())
    }

    @Test
    fun `data that cannot be forced to storage fails the save, the old content stays and the directory is left alone`() {
        file.writeText("old")
        var directorySynced = false

        assertFailsWith<IOException> {
            file.replaceContent(
                SaveSteps(syncData = { throw IOException("no space left") }, syncDirectory = { directorySynced = true }),
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

    // ---- the PRODUCTION steps: nothing of the test's own stands in the step under test

    @Test
    fun `the production data step really forces the file, a descriptor that is gone fails the save before the rename`() {
        file.writeText("old")
        var directorySynced = false

        // only the way the file is opened is the test's; the data step is the default one
        assertFailsWith<IOException> {
            file.replaceContent(SaveSteps(open = ::ClosesAfterWrite, syncDirectory = { directorySynced = true })) {
                it.write("new".toByteArray())
            }
        }

        assertEquals("old", file.readText(), "nothing was renamed")
        assertFalse(directorySynced)
    }

    @Test
    fun `the same for the lines of a message file`() {
        file.writeText("old\n")

        assertFailsWith<IOException> { file.replaceWith(listOf("new"), open = ::ClosesAfterWrite) }

        assertEquals(listOf("old"), file.readLines())
    }

    @Test
    fun `the production directory step really opens the directory, one that may not be read is reported and the save is done`() {
        file.writeText("old")
        val warnings = mutableListOf<String>()
        // Without the right to read it a directory can still take a new file and a rename,
        // but it cannot be opened to be forced to storage.
        try {
            assumeTrue(dir.setReadable(false, false) && !dir.canRead(), "this user can read every directory (root?)")
            // every step is the default one
            file.replaceContent(warn = { warnings += it }) { it.write("new".toByteArray()) }
        } finally {
            dir.setReadable(true, false)
        }

        assertEquals("new", file.readText(), "the save is done")
        assertFalse(beside.exists())
        assertEquals(1, warnings.size, warnings.toString())
        assertContains(warnings.single(), "list.txt")
        assertContains(warnings.single(), "could not be forced to storage")
    }

    @Test
    fun `the same for the lines of a message file, where the report goes to the log`() {
        val messages = File(dir, "messages-of-this-test.txt")
        messages.writeText("old\n")
        val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watching = ScreenLogger.logLines.subscribe { line -> if ("messages-of-this-test.txt" in line) logged += line }
        try {
            assumeTrue(dir.setReadable(false, false) && !dir.canRead(), "this user can read every directory (root?)")
            messages.replaceWith(listOf("new")) // every step is the default one
        } finally {
            dir.setReadable(true, false)
            watching.dispose()
        }

        assertEquals(listOf("new"), messages.readLines())
        assertEquals(1, logged.size, logged.toString())
        assertContains(logged.single(), "could not be forced to storage")
    }

    // ---- the force itself, and the rename

    @Test
    fun `forcing a directory opens it, forces it with its metadata, and closes it`() {
        val channel = WatchedChannel()
        val opened = mutableListOf<File>()

        dir.syncDirectory(open = { opened += it; channel })

        assertEquals(listOf(dir), opened)
        assertEquals(listOf(true), channel.forced, "one force, metadata included")
        assertTrue(channel.closed)
    }

    @Test
    fun `a directory that opens but cannot be forced, the force was asked for, one warning, the renamed file in place`() {
        file.writeText("old")
        val channel = WatchedChannel(onForce = { throw IOException("the disk would not") })
        val warnings = mutableListOf<String>()

        // the production function for the directory, with nothing but the channel of the test's
        file.replaceContent(
            SaveSteps(syncDirectory = { directory -> directory.syncDirectory(open = { channel }) }),
            warn = { warnings += it },
        ) { it.write("new".toByteArray()) } // does not throw

        assertEquals(listOf(true), channel.forced, "the force was asked for")
        assertTrue(channel.closed)
        assertEquals("new", file.readText(), "the renamed file is in place")
        assertFalse(beside.exists())
        assertEquals(1, warnings.size, warnings.toString())
        assertContains(warnings.single(), "the disk would not")
    }

    @Test
    fun `a rename that fails is a failed save, the target stays as it is and the directory is never forced`() {
        // a directory with something in it, where the file should be: nothing can be renamed over it
        file.mkdir()
        File(file, "inside").writeText("kept")
        var directoryForced = false
        var dataForced = false

        val e = assertFailsWith<IOException> {
            file.replaceContent(SaveSteps(syncData = { dataForced = true }, syncDirectory = { directoryForced = true })) {
                it.write("new".toByteArray())
            }
        }

        assertContains(e.message!!, "rename")
        assertTrue(dataForced, "the file beside was written and forced; the rename is what failed")
        assertFalse(directoryForced)
        assertTrue(file.isDirectory)
        assertEquals("kept", File(file, "inside").readText())
    }

    @Test
    fun `the same with the production steps and the lines of a message file`() {
        file.mkdir()
        File(file, "inside").writeText("kept")

        assertFailsWith<IOException> { file.replaceContent { it.write("new".toByteArray()) } }
        assertFailsWith<IOException> { file.replaceWith(listOf("new")) }

        assertEquals("kept", File(file, "inside").readText())
    }

    @Test
    fun `a real directory can be forced to storage, one that is not there cannot`() {
        dir.syncDirectory() // does not throw on this machine

        assertFailsWith<IOException> { File(dir, "not-there").syncDirectory() }
    }

    @Test
    fun `the production steps, all three, on a real directory`() {
        val warnings = mutableListOf<String>()

        file.replaceContent(warn = { warnings += it }) { it.write("new".toByteArray()) }

        assertEquals("new", file.readText())
        assertEquals(emptyList(), warnings)
        assertEquals(listOf("list.txt"), dir.list()!!.toList())
    }
}
