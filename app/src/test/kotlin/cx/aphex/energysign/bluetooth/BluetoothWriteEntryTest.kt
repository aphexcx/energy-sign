package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.vdurmont.emoji.EmojiParser
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.message.FakeReplies
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.objenesis.ObjenesisStd
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Bluetooth entry from end to end: a write to the UART characteristic of the REAL
 * EnergySignBluetoothGattServer, handed on the way MainViewModel wires it ([deliverEachWrite] to
 * MessageManager.processNewBytes), into the REAL MessageManager and OwnerQueue.
 *
 * What it is here for: a command that throws must leave this path alive for the next command,
 * wherever it throws: on the owner's thread when it is carried out, or on the Bluetooth thread
 * while it is read (an ad command is parsed there).
 *
 * The test is both threads in turn: it makes the write, as the Bluetooth thread, and then runs
 * what was posted, as the owner. Android is faked as in MessageManagerOwnerTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BluetoothWriteEntryTest {
    private val dir: File = Files.createTempDirectory("es-z7t-bluetooth").toFile()
    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val repo = MessageRepository(saver = Executor { it.run() })
    private val owner = OwnerQueue()
    private lateinit var manager: MessageManager
    private lateinit var server: EnergySignBluetoothGattServer

    /** What the link would log as "A change to the messages failed". */
    private val failedChanges = mutableListOf<Throwable>()

    /** What RxJava would hand to the thread's handler of uncaught exceptions: on Android, the end of the app. */
    private val unhandled = CopyOnWriteArrayList<Throwable>()

    private val uartTx = object : BluetoothGattCharacteristic(NordicUartServiceProfile.NORDIC_UART_TX_UUID, 0, 0) {
        override fun getUuid(): UUID = NordicUartServiceProfile.NORDIC_UART_TX_UUID
    }

    @BeforeAll
    fun android() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        startKoin {
            modules(module {
                single { repo }
                single { owner }
                single<AIReplySource> { FakeReplies() }
            })
        }
        manager = MessageManager(context, repo, owner)
        RxJavaPlugins.setErrorHandler { unhandled += it }

        // A server as the app builds it; its collaborators are not used on the path of a write.
        val objenesis = ObjenesisStd()
        server = EnergySignBluetoothGattServer(
            ContextWrapper(null),
            objenesis.newInstance(BluetoothManager::class.java),
            objenesis.newInstance(MainViewModel::class.java),
        )
        // The call MainViewModel makes. That line itself is run by no test: the view model
        // cannot be built on the JVM.
        server.writes.deliverEachWrite(manager)
    }

    @AfterAll
    fun done() {
        RxJavaPlugins.reset()
        stopKoin()
        ArchTaskExecutor.getInstance().setDelegate(null)
        dir.deleteRecursively()
    }

    @BeforeEach
    fun fresh() {
        phoneWrites("!endchoose")
        phoneWrites("!unpause")
        phoneWrites("!first")
        ownerRuns()
        repo.marqueeMessages.clear()
        repo.marqueeMessages.addAll((1..5).map { Message.Marquee.User("MESSAGE $it") })
        repo.oneTimeMessages.clear()
        failedChanges.clear()
        unhandled.clear()
    }

    /** A write to the UART characteristic, as the Bluetooth stack delivers it. */
    private fun phoneWrites(command: String) {
        server.onCharacteristicWriteRequest(null, 1, uartTx, false, false, 0, command.toByteArray(Charsets.UTF_8))
    }

    /** The owner thread's turn. */
    private fun ownerRuns() {
        while (owner.drain { failedChanges += it } > 0) Unit
    }

    private fun stored(): List<String> = repo.marqueeMessages.map { it.str }

    @Test
    fun `a requested acknowledgement is refused if the owner never takes the command`() {
        val before = stored()
        val result = FutureTask { server.uartWriteStatus("!delete".toByteArray(), responseNeeded = true) }
        Thread(result).start()
        assertEquals(BluetoothGatt.GATT_FAILURE, result.get(10, TimeUnit.SECONDS))
        ownerRuns()
        assertEquals(before, stored())
    }

    @Test
    fun `a write to the characteristic reaches the app`() {
        phoneWrites("!next")
        phoneWrites("!delete")
        assertEquals(5, stored().size, "posted, not carried out yet")

        ownerRuns()

        assertEquals(listOf("MESSAGE 1", "MESSAGE 3", "MESSAGE 4", "MESSAGE 5"), stored())
    }

    @Test
    fun `a command the owner throws on is reported, and the next command over Bluetooth is carried out`() {
        // An index the list does not have: !last on an empty list leaves -1, and the test puts messages
        // in behind its back. No path of the app's own leads there; it is what makes a change throw today.
        repo.marqueeMessages.clear()
        phoneWrites("!last")
        ownerRuns()
        repo.marqueeMessages.addAll((1..3).map { Message.Marquee.User("LATE $it") })

        phoneWrites("!delete")
        ownerRuns()

        assertEquals(1, failedChanges.size, "$failedChanges")
        assertTrue(failedChanges.single() is IndexOutOfBoundsException, "$failedChanges")
        assertEquals(3, stored().size, "nothing was deleted")

        phoneWrites("!first")
        phoneWrites("!delete")
        ownerRuns()

        assertEquals(listOf("LATE 2", "LATE 3"), stored())
        assertEquals(emptyList(), unhandled.toList())
    }

    @Test
    fun `a command that throws on the Bluetooth thread, while it is read, does not end the app, and the next command is carried out`() {
        // The ad command is parsed on the thread that delivers the write. No input was found that
        // makes the parser throw today, so the emoji library it calls is made to, with an Exception.
        // The parser asks that library for an icon line only (the 👾 line), so there is one here;
        // and the test says that the library WAS asked, or it would prove nothing.
        Mockito.mockStatic(EmojiParser::class.java).use { emoji ->
            emoji.`when`<List<String>> { EmojiParser.extractEmojis(anyString()) }
                .thenThrow(IllegalStateException("the emoji table could not be read"))

            phoneWrites("!🅰️\n🆑🕰DREAM\n🆑STATE\n👾💥")

            emoji.verify({ EmojiParser.extractEmojis(anyString()) }, Mockito.atLeastOnce())
        }
        ownerRuns()

        assertEquals(emptyList(), unhandled.toList(), "nothing may reach the thread's handler of uncaught exceptions")
        assertTrue(server.writes.hasObservers(), "the app must still be listening for writes")
        assertEquals(emptyList(), failedChanges.toList(), "the command never reached the owner")

        phoneWrites("!next")
        phoneWrites("!delete")
        ownerRuns()

        assertEquals(listOf("MESSAGE 1", "MESSAGE 3", "MESSAGE 4", "MESSAGE 5"), stored())
    }
}
