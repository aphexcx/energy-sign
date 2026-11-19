package cx.aphex.energysign

import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SignStartupTest {
    private val order = listOf(
        "messages", "JSON warm-up thread", "Bluetooth construction", "Bluetooth writes",
        "Bluetooth start", "DNS-SD construction", "DNS-SD start", "UART",
    )

    private class Run(val failAt: String? = null, val failure: Throwable = IllegalStateException()) {
        val calls = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        lateinit var background: Runnable

        fun step(name: String) {
            calls += name
            if (failAt == name) throw failure
        }

        fun start(warn: (String) -> Unit = warnings::add) {
            val startup = SignStartup(warn)
            val bluetooth = BluetoothStartup(startup, { step("Bluetooth construction") },
                { step("Bluetooth writes") }, { step("Bluetooth start") }, {})
            startup.start(
                prepareMessages = { step("messages") },
                launchWarmUp = { step("JSON warm-up thread"); background = it },
                warmUp = { step("JSON warm-up") },
                startBluetooth = bluetooth::start,
                startDnsSd = { step("DNS-SD construction"); step("DNS-SD start") },
                startLink = { step("UART") },
            )
        }
    }

    @Test
    fun `healthy startup keeps its order, with JSON work on the launched thread`() {
        val run = Run()
        run.start()
        assertEquals(order, run.calls)
        run.background.run()
        assertEquals(order + "JSON warm-up", run.calls)
        assertEquals(emptyList(), run.warnings)
    }

    @TestFactory
    fun `an optional failure leaves the link and independent services running`() =
        (order.drop(1).dropLast(1) + "JSON warm-up").flatMap { step ->
            listOf(IllegalStateException("private detail"), LinkageError("private detail")).map { failure ->
                dynamicTest("$step throws ${failure.javaClass.simpleName}") {
                    val run = Run(step, failure)
                    run.start()
                    if (step == "JSON warm-up") run.background.run()
                    val skipped = when (step) {
                        "Bluetooth construction" -> listOf("Bluetooth start", "Bluetooth writes")
                        "Bluetooth writes" -> listOf("Bluetooth start")
                        "DNS-SD construction" -> listOf("DNS-SD start")
                        else -> emptyList()
                    }
                    val expected = if (step == "JSON warm-up") order + step else order
                    assertEquals(expected - skipped.toSet(), run.calls)
                    val warning = run.warnings.single()
                    assertContains(warning, step.substringBefore(" "))
                    assertContains(warning, failure.javaClass.simpleName)
                    assertContains(warning, "sign link continues")
                    kotlin.test.assertFalse("private detail" in warning)
                }
            }
        }

    @Test
    fun `a failed warning cannot suppress the sign link`() {
        val run = Run("Bluetooth start")
        run.start { throw LinkageError() }
        assertEquals("UART", run.calls.last())
    }

    @Test
    fun `message loading and UART startup failures reach the caller`() {
        for (step in listOf("messages", "UART")) {
            val run = Run(step)
            assertSame(run.failure, assertFailsWith<IllegalStateException> { run.start() })
            assertEquals(order.takeWhile { it != step } + step, run.calls)
            assertEquals(emptyList(), run.warnings)
        }
    }

    @Test
    fun `foreground startup recovers after a refused subscription or registration without duplicating a healthy manager`() {
        for (failingStage in listOf("create", "subscribe", "register")) {
            val warnings = mutableListOf<String>()
            var recovered = false
            val calls = mutableListOf<String>()
            fun step(stage: String) {
                calls += stage
                if (stage == failingStage && !recovered) throw SecurityException()
            }
            val bluetooth = BluetoothStartup(SignStartup(warnings::add), { step("create"); Any() },
                { step("subscribe") }, { step("register") }, { step("cleanup") })
            bluetooth.start()
            kotlin.test.assertNull(bluetooth.manager)
            assertEquals(failingStage != "create", "cleanup" in calls)
            recovered = true
            calls.clear()
            bluetooth.start()
            bluetooth.start()
            assertEquals(listOf("create", "subscribe", "register"), calls)
            assertEquals(1, warnings.size)
        }
    }
}
