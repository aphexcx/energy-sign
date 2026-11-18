package cx.aphex.energysign.ai

/**
 * The API keys of this build, so that none of them can end up in a log line. The code here never
 * logs a key itself; this is for what a library may put into an exception message.
 */
class Secrets(values: List<String>) {
    // Anything shorter is not a key, and taking it out of every line would only mangle the log.
    private val secrets: List<String> = values.map { it.trim() }.filter { it.length >= MIN_LENGTH }

    fun redact(text: String): String = secrets.fold(text) { cleaned, secret -> cleaned.replace(secret, "<key>") }

    /**
     * A log function for the reply path: every line is redacted, and a failure of the log itself
     * is dropped. A reply is worth more than its log line.
     */
    fun sink(log: (String) -> Unit): (String) -> Unit = { line ->
        try {
            log(redact(line))
        } catch (e: RuntimeException) {
            // Nothing to do, and nowhere to say it.
        }
    }

    private companion object {
        const val MIN_LENGTH = 8
    }
}
