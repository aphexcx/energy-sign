package cx.aphex.energysign

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun <T> optional(name: String, warn: (String) -> Unit, action: () -> T): T? =
    try {
        action()
    } catch (failure: Throwable) {
        runCatching { warn("$name failed (${failure.javaClass.name}), sign link continues") }
        null
    }

internal interface StartWorker {
    fun post(action: () -> Unit)
    fun after(ms: Long, action: () -> Unit): () -> Unit
    fun nowMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}

internal class SignWorker(private val warn: (String) -> Unit) : StartWorker {
    private val queue = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "sign-optional").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun post(action: () -> Unit) {
        queue.execute { optional("Optional service callback", warn, action) }
    }

    override fun after(ms: Long, action: () -> Unit): () -> Unit {
        val future = queue.schedule({ optional("Optional service timer", warn, action) }, ms, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }
}

/** Called once on the worker. The sign can stay in HOME for its entire lifetime. */
internal fun StartWorker.tick(action: () -> Unit) {
    after(60_000) {
        try { action() } finally { tick(action) }
    }
}

/** Worker-confined. An attempt owns its asynchronous callbacks and releases partial resources on failure. */
internal class OptionalStart(
    private val name: String,
    private val worker: StartWorker,
    private val warn: (String) -> Unit,
    private val release: () -> Unit = {},
    private val expired: () -> Unit = {},
    private val begin: (Attempt) -> Unit,
) {
    private var current: Attempt? = null
    private var cancel = {}
    var up = false
        private set

    inner class Attempt internal constructor() {
        private val began = worker.nowMs()
        val confirmed get() = live && up
        val live get() = current === this
        fun pending(): Boolean {
            if (!live || up) return false
            if (worker.nowMs() - began < 20_000) return true
            expire()
            return false
        }
        fun done(): Boolean {
            if (!pending()) return false
            up = true
            cancel()
            return true
        }
        fun fail(reason: String) {
            if (!live) return
            runCatching { warn("$name failed ($reason), sign link continues") }
            reset()
        }
        internal fun expire() {
            if (!live || up) return
            fail("20 s deadline")
            optional("$name retry", warn, expired)
        }
    }

    fun ensure() {
        if (current != null) return
        val attempt = Attempt()
        current = attempt
        try {
            cancel = worker.after(20_000, attempt::expire)
            begin(attempt)
        } catch (failure: Throwable) {
            attempt.fail(failure.javaClass.name)
        }
    }

    fun reset() {
        if (current == null) return
        current = null
        up = false
        cancel()
        optional("$name cleanup", warn, release)
    }
}
