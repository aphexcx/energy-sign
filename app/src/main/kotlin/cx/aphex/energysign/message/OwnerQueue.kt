package cx.aphex.energysign.message

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The work queue of the ONE thread that owns the message state: the stored messages, the
 * one-time queue, the chooser index and mode, and the keyboard input.
 *
 * That thread is the sign link thread, the one that answers the sign. Everything else that
 * wants to change the state (a Bluetooth command, a key, an AI answer, a track from BeatLink)
 * posts the change here and returns; the owner runs the changes in the order they were posted,
 * between two looks at the wire. So "what is the next message" and every change to it are made
 * by one thread, one after the other, and need no lock: there is no lock to hold while the sign
 * waits, and none for a thread to be put to sleep with.
 *
 * What an event may do: change state and log. What it must not do: wait for another thread, or
 * write a file (MessageRepository hands those to its own thread).
 */
class OwnerQueue {
    private val events = ConcurrentLinkedQueue<() -> Unit>()

    /** Wakes the owner when there is work. Set by the owner's thread before it starts. */
    @Volatile
    var wake: () -> Unit = {}

    @Volatile
    private var ownerThread: Thread? = null

    @Volatile
    private var mustNotWait: Thread? = null

    /** Hands [event] to the owner. From any thread; returns at once. */
    fun post(event: () -> Unit) {
        events.add(event)
        wake()
    }

    /**
     * Hands [change] to the owner and waits until the owner has run it: returns what it returned,
     * throws what it threw. For a caller that has to tell someone what really happened (an HTTP
     * client). Waits at most [timeoutMs] for the owner to take the change up; a change the owner
     * has not taken up by then is withdrawn (it will not be made later, behind the caller's
     * back) and this throws a TimeoutException. A change the owner has taken up is waited for
     * to its end, whatever the clock says: its real result is the only true answer.
     * Never from a thread named with [neverWaitsOn]; the owner asking itself is answered at once.
     */
    fun <T> ask(timeoutMs: Long = ASK_TIMEOUT_MS, change: () -> T): T {
        val asker = Thread.currentThread()
        if (asker === ownerThread) return change()
        check(asker !== mustNotWait) { "a change must be posted from ${asker.name}, not waited for" }
        val answer = FutureTask(Callable(change))
        val takenUp = AtomicBoolean(false) // set once, by whoever is first: the owner taking it up, or the asker withdrawing it
        post { if (takenUp.compareAndSet(false, true)) answer.run() }
        try {
            return try {
                answer.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (takenUp.compareAndSet(false, true)) {
                    throw TimeoutException("the sign link did not get to the change within $timeoutMs ms")
                }
                answer.get()
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** [thread] (the main thread) must never wait for the owner: [ask] from it throws. */
    fun neverWaitsOn(thread: Thread) {
        mustNotWait = thread
    }

    /**
     * Runs what has been posted, in order, at most [max] events so that the owner gets back to
     * the wire. On shutdown, finish the current event and leave the rest in order. Owner only.
     */
    fun drain(max: Int = MAX_PER_DRAIN, stopped: () -> Boolean = { false }, onError: (Throwable) -> Unit = {}): Int {
        ownerThread = Thread.currentThread()
        var ran = 0
        while (ran < max && !stopped()) {
            val event = events.poll() ?: break
            ran++
            try {
                event()
            } catch (e: Exception) {
                onError(e)
            }
        }
        return ran
    }

    companion object {
        const val MAX_PER_DRAIN: Int = 16

        /** The owner looks at its queue every 10 ms at the latest; this is for an owner that is not running. */
        const val ASK_TIMEOUT_MS: Long = 2_000
    }
}
