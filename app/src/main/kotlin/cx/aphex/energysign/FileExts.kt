package cx.aphex.energysign

import java.io.File

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
