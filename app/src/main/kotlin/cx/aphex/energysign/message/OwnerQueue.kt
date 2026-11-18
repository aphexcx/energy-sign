package cx.aphex.energysign.message

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The work queue of the ONE thread that owns the message state: the stored messages, the
 * one-time queue, the chooser index and mode, and the keyboard input.
 *
 * That thread is the sign link thread, the one that answers the sign. Everything else that
 * wants to change the state (a Bluetooth command, a key, a GPT answer, a track from BeatLink)
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

    /** Hands [event] to the owner. From any thread; returns at once. */
    fun post(event: () -> Unit) {
        events.add(event)
        wake()
    }

    /**
     * Runs what has been posted, in order, at most [max] events so that the owner gets back to
     * the wire. An event that throws is reported and does not stop the others. Owner only.
     */
    fun drain(max: Int = MAX_PER_DRAIN, onError: (Throwable) -> Unit = {}): Int {
        var ran = 0
        while (ran < max) {
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
    }
}
