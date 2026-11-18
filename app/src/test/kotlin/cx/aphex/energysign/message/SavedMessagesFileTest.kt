package cx.aphex.energysign.message

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The saved list (files/signstrings.bin) holds one line per message: the MSGTYPE name, a vertical
 * tab, the text. No class name is in it, so renaming the message classes cannot lose the list.
 * The fixture below is the file as the app wrote it before the rename.
 */
class SavedMessagesFileTest {

    private lateinit var mockedLog: MockedStatic<Log>

    @BeforeEach
    fun quietLog() {
        mockedLog = Mockito.mockStatic(Log::class.java)
    }

    @AfterEach
    fun restoreLog() = mockedLog.close()

    /** The only thing the repository asks of a Context is where the app's files are. */
    private fun contextWithFilesDir(dir: File): Context =
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }

    /** Oldest first, as the app writes it: a message, a query that was answered, its reply. */
    private val savedBeforeTheRename =
        "DEFAULT\u000BFIRST MESSAGE EVER\n" +
            "DEFAULT\u000Bhey ravegpt is the bass loud enough\n" +
            "CHONKYMARQUEE\u000BTHE BASS IS FINE, YOUR EARPLUGS ARE JUST WORKING.\n" +
            "DEFAULT\u000Bnewest message\n"

    @Test
    fun `a list saved before the rename loads with every message, type and text`(@TempDir dir: File) {
        File(dir, "signstrings.bin").writeText(savedBeforeTheRename)

        val loaded = MessageRepository().loadUserMessages(contextWithFilesDir(dir))

        // Newest first in memory.
        assertEquals(
            listOf(
                "newest message",
                "THE BASS IS FINE, YOUR EARPLUGS ARE JUST WORKING.",
                "hey ravegpt is the bass loud enough",
                "FIRST MESSAGE EVER",
            ),
            loaded.map { it.str },
        )
        assertIs<Message.Marquee.User>(loaded[0])
        assertIs<Message.Marquee.Chonky>(loaded[1])
        assertIs<Message.Marquee.User>(loaded[2])
        assertIs<Message.Marquee.User>(loaded[3])
    }

    @Test
    fun `a query and a reply are written as the same bytes as before the rename`(@TempDir dir: File) {
        val repository = MessageRepository()
        // Pushed oldest first; each push goes to the front.
        repository.pushMarqueeMessage(Message.Marquee.User("FIRST MESSAGE EVER"))
        repository.pushMarqueeMessage(Message.Marquee.GPTQuery("hey ravegpt is the bass loud enough"))
        repository.marqueeMessages.add(0, Message.Marquee.GPTReply("THE BASS IS FINE, YOUR EARPLUGS ARE JUST WORKING."))
        repository.pushMarqueeMessage(Message.Marquee.User("newest message"))

        repository.saveMarqueeMessages(contextWithFilesDir(dir))

        assertEquals(savedBeforeTheRename, File(dir, "signstrings.bin").readText())
    }

    @Test
    fun `saving and loading again keeps the list`(@TempDir dir: File) {
        File(dir, "signstrings.bin").writeText(savedBeforeTheRename)
        val context = contextWithFilesDir(dir)
        val repository = MessageRepository()
        repository.marqueeMessages.addAll(repository.loadUserMessages(context))

        repository.saveMarqueeMessages(context)

        assertEquals(savedBeforeTheRename, File(dir, "signstrings.bin").readText())
    }
}
