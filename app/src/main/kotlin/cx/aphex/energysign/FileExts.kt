package cx.aphex.energysign

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
 * Replaces the content of this file with [lines], whole or not at all: the lines are written to
 * a file beside it, forced to storage, and that file is then renamed over this one. If the
 * writing stops half way (an error, the power), this file still holds all of what it held.
 * [open] is how the file beside it is opened (a test opens one that fails).
 */
fun File.replaceWith(lines: Iterable<String>, open: (File) -> FileOutputStream = ::FileOutputStream) {
    val beside = replacement
    open(beside).use { out ->
        val writer = out.bufferedWriter()
        for (line in lines) {
            writer.write(line)
            writer.newLine()
        }
        writer.flush()
        out.fd.sync()
    }
    if (!beside.renameTo(this)) throw IOException("could not rename ${beside.name} to $name")
}
