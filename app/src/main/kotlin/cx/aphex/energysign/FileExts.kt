package cx.aphex.energysign

import cx.aphex.energysign.ext.logW
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

fun <T : Any> Iterable<T>.toFile(output: File,
                                 transform: (T) -> String = { it.toString() }) {
    output.bufferedWriter().use { out ->
        this.asSequence().map(transform).forEach {
            out.write(it)
            out.newLine()
        }
    }
}

fun <T : Any> Iterable<T>.toFile(outputFilename: String,
                                 transform: (T) -> String = { it.toString() }) {
    this.toFile(File(outputFilename), transform)
}


fun <T : Any> File.fillWith(things: Iterable<T>, transform: (T) -> String = { it.toString() }) {
    things.toFile(this, transform)
}

/** The file beside [this] that a replacement is written to first. */
val File.replacement: File
    get() = File(parentFile, "$name.tmp")

/**
 * Replaces the content of this file with [lines], whole or not at all: see [replaceContent].
 * [open] is how the file beside it is opened (a test opens one that fails); the other steps
 * are the real ones.
 */
fun File.replaceWith(lines: Iterable<String>, open: (File) -> FileOutputStream = ::FileOutputStream) =
    replaceWith(lines, SaveSteps(open = open))

/** The same, with [steps] of the caller's (a test's). */
fun File.replaceWith(lines: Iterable<String>, steps: SaveSteps) =
    replaceContent(steps) { out ->
        val writer = out.bufferedWriter()
        for (line in lines) {
            writer.write(line)
            writer.newLine()
        }
        writer.flush()
    }

/**
 * Replaces the content of this file with what [write] writes, whole or not at all, and so that
 * it is still there after a power cut. The ONE sequence for every file the app saves:
 *
 *  1. [write] fills a file beside this one ([replacement]), and its data is forced to storage;
 *  2. that file is renamed over this one. If anything stopped before here (an error, the
 *     power), this file still holds all of what it held;
 *  3. the directory is forced to storage, so that the new name is what a power cut leaves.
 *
 * An error in 1 or 2 is thrown: nothing was saved. An error in 3 is not: the new content is in
 * place and stays unless the power goes right now, so the save is done and [warn] gets a line.
 *
 * [steps] are the real steps unless a test stands in one.
 */
fun File.replaceContent(
    steps: SaveSteps = SaveSteps(),
    warn: (String) -> Unit = { logW(it) },
    write: (FileOutputStream) -> Unit,
) {
    val beside = replacement
    steps.open(beside).use { out ->
        write(out)
        steps.syncData(out)
    }
    if (!beside.renameTo(this)) throw IOException("could not rename ${beside.name} to $name")
    try {
        steps.syncDirectory(absoluteFile.parentFile!!)
    } catch (e: Exception) {
        warn("$name is saved, but its directory could not be forced to storage: ${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * The steps of a save that touch storage, in the order [replaceContent] takes them. The values
 * here are the real ones, and the only place they are written down: every file the app saves
 * gets them from here. A test makes one with a step of its own.
 */
class SaveSteps(
    /** Opens the file beside the one to replace. */
    val open: (File) -> FileOutputStream = ::FileOutputStream,
    /** Forces what was written to that file to storage. */
    val syncData: (FileOutputStream) -> Unit = { it.fd.sync() },
    /** Forces the directory to storage, after the rename. */
    val syncDirectory: (File) -> Unit = { it.syncDirectory() },
)

/**
 * Forces this directory to storage: the names in it, so a file that was just renamed into place
 * keeps its new name through a power cut. (Forcing the file's own data does not do that.)
 */
@Throws(IOException::class)
fun File.syncDirectory() {
    FileChannel.open(toPath(), StandardOpenOption.READ).use { it.force(true) }
}
