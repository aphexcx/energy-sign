package cx.aphex.energysign.message

import android.content.ContextWrapper
import cx.aphex.energysign.R
import cx.aphex.energysign.ScreenLogger
import cx.aphex.energysign.SignStartup
import cx.aphex.energysign.ai.AIReplySource
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MessageStartupTest {
    @TempDir
    lateinit var dir: File

    @TestFactory
    fun `the link can read saved text after optional message setup fails`() =
        listOf("track heading", "default ads", "AI reply warm-up").flatMap { stage ->
            listOf(IllegalStateException(), LinkageError()).map { failure ->
                dynamicTest("$stage throws ${failure.javaClass.simpleName}") {
                    val context = object : ContextWrapper(null) {
                        override fun getFilesDir() = dir
                        // AGP removes final from Android methods in its JVM test jar.
                        @Suppress("OVERRIDING_FINAL_MEMBER")
                        override fun getColor(id: Int): Int {
                            if (stage == "default ads" && id == R.color.icon_defaultblue) throw failure
                            if (stage == "track heading" && id == R.color.twitch) throw failure
                            return 0
                        }
                    }
                    val replies = object : AIReplySource by FakeReplies() {
                        override fun warmUp(onDone: () -> Unit): Boolean {
                            if (stage == "AI reply warm-up") throw failure
                            return false
                        }
                    }

                    File(dir, "signstrings.bin").writeText("DEFAULT\u000BSAVED")
                    val warnings = mutableListOf<String>()
                    val log = ScreenLogger.logLines.subscribe { if ("sign link continues" in it) warnings += it }
                    warnings.clear() // BehaviorRelay replays the previous line when subscribed.
                    startKoin { modules(module { single<AIReplySource> { replies } }) }
                    try {
                        lateinit var messages: MessageManager
                        val sent = mutableListOf<String>()
                        SignStartup { error(it) }.start(
                            prepareMessages = { messages = MessageManager(context, MessageRepository(), OwnerQueue()) },
                             startBluetooth = {},
                            startDnsSd = {}, startLink = { sent += messages.getNextMessage().str },
                        )
                        assertEquals(listOf("SAVED"), sent)
                        assertContains(warnings.single(), stage, ignoreCase = true)
                    } finally {
                        stopKoin()
                        log.dispose()
                    }
                }
            }
        }
}
