package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Keys are taken out of every line before it is logged. */
class SecretsTest {

    @Test
    fun `every secret is taken out of the text, wherever it stands`() {
        val secrets = Secrets(listOf("made-up-key-12345", "another-made-up-key"))

        assertEquals(
            "header value: <key>, and again <key>; also <key>.",
            secrets.redact("header value: made-up-key-12345, and again made-up-key-12345; also another-made-up-key."),
        )
    }

    @Test
    fun `a secret with white space around it in the build setting is still found`() {
        assertEquals("x <key> y", Secrets(listOf("  made-up-key-12345 \n")).redact("x made-up-key-12345 y"))
    }

    @Test
    fun `a missing or tiny setting takes nothing out`() {
        val secrets = Secrets(listOf("", "   ", "abc"))

        assertEquals("abc def, and nothing else", secrets.redact("abc def, and nothing else"))
    }

    @Test
    fun `the log sink redacts, and a log that throws does not take the reply with it`() {
        val secrets = Secrets(listOf("made-up-key-12345"))
        val lines = mutableListOf<String>()

        secrets.sink { lines.add(it) }("the key is made-up-key-12345")
        secrets.sink { throw ArrayIndexOutOfBoundsException("a date formatter used by two threads") }("anything")

        assertEquals(listOf("the key is <key>"), lines)
    }
}
