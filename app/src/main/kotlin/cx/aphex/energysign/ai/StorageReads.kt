package cx.aphex.energysign.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/*
 * Reading the board's storage can hang (a failing card), and a thread that hangs in a read cannot
 * be ended. So a read never runs on a thread that a reply needs, there is at most ONE thread
 * hanging in the read of a file at any time, and whoever needs what the file says waits for a
 * limited time and goes on without it.
 */

/**
 * A setting that is read from storage ONCE, when the app starts, by a thread of its own that
 * nothing waits for. [get] never reads and never waits: it gives [untilRead] until the read has
 * ended, and what was read from then on. A read that hangs or throws leaves [untilRead] in place.
 *
 * Changing the file takes effect at the next start of the app.
 */
class ReadOnceAtStart<T>(
    private val untilRead: T,
    read: () -> T,
    startThread: (Runnable) -> Unit = { Thread(it, "ai-settings").apply { isDaemon = true }.start() },
) {
    private val value = AtomicReference(untilRead)

    init {
        startThread(
            Runnable {
                try {
                    value.set(read())
                } catch (e: Throwable) {
                    // The setting of the build stays.
                }
            }
        )
    }

    fun get(): T = value.get()
}

/**
 * A file that is read again whenever it is needed, by ONE thread at most, however many callers
 * there are and however long a read takes. A caller waits for the read that is under way, or
 * starts one when there is none; when the read is not done within the caller's limit, the caller
 * leaves with null and the read stays under way, for the next caller to wait for. A read that
 * hangs for ever costs one thread, once.
 */
class OneReadAtATime<T : Any>(
    private val read: () -> T,
    private val startThread: (Runnable) -> Unit = { Thread(it, "ai-set-times").apply { isDaemon = true }.start() },
) {
    private val lock = Any()
    private var underWay: CompletableDeferred<T>? = null

    /** What the file says, or null when the read was not done within [limitMs] (or failed). */
    suspend fun await(limitMs: Long): T? {
        val reading = synchronized(lock) {
            underWay ?: CompletableDeferred<T>().also { started ->
                val work = Runnable {
                    val result = try {
                        Result.success(read())
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    synchronized(lock) { if (underWay === started) underWay = null }
                    result.fold(started::complete, started::completeExceptionally)
                }
                underWay = started
                try {
                    startThread(work)
                } catch (e: Throwable) {
                    // No thread could be started: nothing is under way, and the next caller tries again.
                    underWay = null
                    started.completeExceptionally(e)
                }
            }
        }
        return try {
            withTimeoutOrNull(limitMs) { reading.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
}
