package cx.aphex.energysign

import java.util.ArrayDeque
import org.junit.jupiter.api.Test
import kotlin.test.*

internal class TestStartWorker : StartWorker {
    private val jobs = ArrayDeque<() -> Unit>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    var now = 0L
    override fun nowMs() = now
    override fun post(action: () -> Unit) { jobs.addLast(action) }
    override fun after(ms: Long, action: () -> Unit): () -> Unit {
        val timer = now + ms to action
        timers.add(timer)
        return { timers.remove(timer) }
    }
    fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst()() }
    fun advance(ms: Long) {
        now += ms
        val due = timers.filter { it.first <= now }
        timers.removeAll(due.toSet())
        due.forEach { it.second() }
        drain()
    }
}

class OptionalStartTest {
    @Test fun failureAndDeadlineReleaseOnlyTheirAttemptAndTickRetriesIdleWork() {
        for (outcome in listOf("exception", "error", "callback", "deadline", "late return", "reset", "success")) {
            val worker = TestStartWorker()
            val warnings = mutableListOf<String>()
            val attempts = mutableListOf<OptionalStart.Attempt>()
            var releases = 0
            val start = OptionalStart("test", worker, warnings::add, release = { releases++ }) { a ->
                attempts += a
                if (attempts.size > 1) a.done()
                else when (outcome) {
                    "exception" -> throw IllegalStateException("private")
                    "error" -> throw LinkageError("private")
                    "callback" -> a.fail("status 3")
                    "late return" -> { worker.now += 20_001; assertFalse(a.done()) }
                    "success" -> a.done()
                    else -> Unit
                }
            }
            start.ensure()
            if (outcome == "reset") start.reset()
            worker.tick(start::ensure)
            worker.advance(20_000)
            worker.advance(40_000)
            assertTrue(start.up, outcome)
            assertEquals(if (outcome == "success") 1 else 2, attempts.size, outcome)
            assertEquals(if (outcome == "success") 0 else 1, releases, outcome)
            attempts.first().let { if (it !== attempts.last()) { it.fail("stale"); assertFalse(it.done()) } }
            assertTrue(start.up)
            assertEquals(if (outcome in listOf("reset", "success")) 0 else 1, warnings.size, outcome)
            assertTrue(warnings.none { "private" in it })
        }
    }

    @Test fun failedCleanupAndLoggingLeaveIndependentStartsUsable() {
        val worker = TestStartWorker()
        val warnings: (String) -> Unit = { throw LinkageError() }
        var tries = 0
        val start = OptionalStart("optional", worker, warnings, release = { throw LinkageError() }) {
            if (tries++ == 0) throw LinkageError() else it.done()
        }
        start.ensure()
        start.ensure()
        assertTrue(start.up)
        assertNull(optional("construction", warnings) { throw IllegalStateException() })
        val next = java.util.concurrent.CountDownLatch(1)
        val real = SignWorker(warnings)
        real.post { throw LinkageError() }
        real.post { next.countDown() }
        assertTrue(next.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
}
