package cx.aphex.energysign.message

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * When the app pushes the chooser to the sign (contract VERSION 3): once for every change of
 * what the chooser should show, and never otherwise.
 *
 * MessageManager cannot be built on the JVM (it needs an Android context and its view models),
 * so this drives the watch with a model of its chooser commands: the same list, index and mode,
 * changed the way processUartCommand changes them. In the app every command goes through one
 * watched entry point (processNewBytes), so what holds for the model's commands holds for all.
 */
class ChooserChangeWatchTest {
    private val messages: MutableList<Message.Marquee> =
        (1..5).map { Message.Marquee.User("MESSAGE $it") }.toMutableList()
    private var inChooserMode = false
    private var idx = 0

    private var pushes = 0

    /** What a push would send at this moment, noted at every push. */
    private val pushedStates = mutableListOf<String>()

    private fun shown(): Message.Chooser? = if (inChooserMode) ChooserProtocol.chooserMessage(messages, idx) else null

    private val watch = ChooserChangeWatch(view = ::shown) {
        pushes++
        pushedStates += shown()?.let { "${it.flashy} ${it.str}" } ?: "left"
    }

    /** The chooser commands, as MessageManager.processUartCommand has them. */
    private fun command(cmd: String) = watch.watch {
        when (cmd) {
            "!choose" -> inChooserMode = true
            "!endchoose" -> inChooserMode = false
            "!next" -> idx = if (idx < messages.lastIndex) idx + 1 else messages.lastIndex
            "!prev" -> idx = if (idx > 0) idx - 1 else 0
            "!first" -> idx = 0
            "!last" -> idx = messages.lastIndex
            "!delete" -> if (messages.isNotEmpty()) {
                messages.removeAt(idx)
                if (idx >= messages.lastIndex) idx = messages.lastIndex
            }
            "!pause" -> Unit // any command that leaves the chooser alone
            else -> error("no such command in the model: $cmd")
        }
    }

    private fun newUserMessage(text: String) = watch.watch {
        idx = 0
        messages.add(0, Message.Marquee.User(text))
    }

    @Test
    fun `every command that changes what the chooser shows pushes exactly once`() {
        val expected = listOf(
            "!choose" to "1/5 MESSAGE 1",
            "!next" to "2/5 MESSAGE 2",
            "!last" to "5/5 MESSAGE 5",
            "!prev" to "4/5 MESSAGE 4",
            "!first" to "1/5 MESSAGE 1",
            "!delete" to "1/4 MESSAGE 2",
            "!endchoose" to "left",
        )
        for ((i, step) in expected.withIndex()) {
            command(step.first)
            assertEquals(i + 1, pushes, "after ${step.first}")
            assertEquals(step.second, pushedStates.last(), "after ${step.first}")
        }
    }

    @Test
    fun `a list change in chooser mode pushes once`() {
        command("!choose")
        newUserMessage("FRESH")
        assertEquals(2, pushes)
        assertEquals("1/6 FRESH", pushedStates.last())
    }

    @Test
    fun `a command that changes nothing on screen does not push`() {
        command("!choose")
        command("!choose") // already choosing
        command("!prev") // already at the first
        command("!first")
        command("!pause")
        command("!last")
        command("!next") // already at the last
        assertEquals(2, pushes) // !choose and !last
    }

    @Test
    fun `nothing is pushed outside chooser mode, except once on leaving it`() {
        command("!next")
        command("!prev")
        command("!delete")
        newUserMessage("FRESH")
        command("!endchoose") // was not choosing
        assertEquals(0, pushes)

        command("!choose")
        command("!endchoose")
        assertEquals(2, pushes)
        assertEquals("left", pushedStates.last())

        command("!next")
        newUserMessage("LATER")
        assertEquals(2, pushes)
    }

    @Test
    fun `a watched change inside a watched change pushes once, when the outer one is done`() {
        command("!choose")
        watch.watch {
            newUserMessage("ONE") // a new message arrives through processNewBytes -> processNewUserMessage
            assertEquals(1, pushes) // not yet: the outer change is still under way
        }
        assertEquals(2, pushes)
        assertEquals("1/6 ONE", pushedStates.last())
    }

    @Test
    fun `a change that throws half way still pushes what changed`() {
        command("!choose")
        runCatching {
            watch.watch {
                idx = 3
                error("save failed")
            }
        }
        assertEquals(2, pushes)
        assertEquals("4/5 MESSAGE 4", pushedStates.last())
        // and the watch is usable afterwards
        command("!next")
        assertEquals(3, pushes)
    }

    @Test
    fun `the same text at another position is a change, the count is part of what is shown`() {
        messages[1] = Message.Marquee.User("MESSAGE 1")
        command("!choose")
        command("!next")
        assertEquals(2, pushes)
        assertEquals("2/5 MESSAGE 1", pushedStates.last())
    }
}
