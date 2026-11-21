package cx.aphex.energysign.message

import com.google.gson.Gson
import java.lang.reflect.Modifier

/*
 * Chooser mode, app side of the wire contract with the sign (firmware: aphexcx/arduino-led-marquee).
 * Contract VERSION 3.1.
 *
 * The SIGN scrolls the chooser preview. The app sends the message text once and holds no scroll
 * state: no timer, no offset, and no memory of what it sent. The sign does not ask whether the
 * selection has changed (contract 2's `?` and its `{"c":N}` report are gone, from both halves):
 * the app pushes the chooser message when it changes (SignLink), and the sign asks `~` when it
 * wants a message.
 *
 * Nothing in this file touches Android, so all of it runs in JVM unit tests.
 */

/** What a reply is to the sign. */
enum class Reply {
    /** A chooser message. The sign is in its chooser display with it. */
    CHOOSER,

    /** A message of any other kind. The sign leaves its chooser display to show it. */
    MESSAGE,
}

object ChooserProtocol {
    /** The sign keeps its own copy of the chooser text; this is all it has room for. */
    const val CHOOSER_MAX_CHARS: Int = 64

    /** Shown in place of the text when there is no message at the chooser index. */
    const val EMPTY_TEXT: String = "<empty>"

    /** The longest frame the sign can take (its buffer). A longer one is not sent. */
    const val MAX_FRAME_BYTES: Int = 700

    /** Request byte of "send the next message". The sign sends it, then waits. */
    const val NEXT_REQUEST: Char = '~'

    /**
     * The chooser text for [message]: from its first character, cut at [CHOOSER_MAX_CHARS], no
     * ellipsis. One rule for every message type; the sign decides what fits and scrolls the rest.
     */
    fun chooserText(message: Message.Marquee?): String =
        message?.str?.take(CHOOSER_MAX_CHARS) ?: EMPTY_TEXT

    /** The chooser message for position [idx] (0-based) of [messages]; the count shown is 1-based. */
    fun chooserMessage(messages: List<Message.Marquee>, idx: Int): Message.Chooser =
        Message.Chooser(
            currentIndex = idx + 1,
            lastIndex = messages.size,
            currentMessage = Message.Marquee.User(chooserText(messages.getOrNull(idx)))
        )

}

/**
 * The frame of a push (contract VERSION 3.1), and what is told when it has been written.
 *
 * A push sends the chooser message unasked. [shown] is all it can reach, and by its type that is
 * the chooser message or nothing: a push cannot take a message from a queue or move an index.
 * [sent] hears of the message once the link says the sign can have it ([written]), as it would
 * hear of a reply: the screen and the Bluetooth read characteristics show what the sign has.
 * A push that failed tells nothing. Link thread only.
 */
class ChooserPush(
    private val shown: () -> Message.Chooser?,
    private val sent: (message: Message.Chooser) -> Unit,
) {
    private var framed: Message.Chooser? = null

    /** The chooser message as it is now, as JSON; null outside chooser mode (the push is then the attention alone). */
    fun frame(): String? {
        framed = shown()
        return framed?.let(SignResponder.GSON::toJson)
    }

    /** The frame that [frame] last gave is on the wire, whole and in time, at a sign that listens for it. */
    fun written() {
        framed?.let(sent)
        framed = null
    }
}

/**
 * Does [cleanUp] after this failure and returns this failure, for the caller to throw on. When
 * the clean-up fails too, its failure is attached to this one (suppressed): it does not take
 * its place, as it would when thrown from a `catch` or a `finally`. An Error of the clean-up
 * is not held back: it goes on as it is.
 */
internal inline fun <T : Throwable> T.keptThrough(crossinline cleanUp: () -> Unit): T {
    try {
        cleanUp()
    } catch (second: Exception) {
        if (second !== this) addSuppressed(second)
    }
    return this
}

/**
 * Answers the sign's requests. Keeps no state: every reply is made from what [nextMessage]
 * returns at that moment, never from what was written before.
 *
 * [nextMessage] is evaluated exactly once per request: a message that is not a chooser message
 * has been consumed by it.
 *
 * [write] gets the reply and what it is to the sign, and says whether all of it went out. A message that did not go out is handed to [unsent]: it was taken for nothing.
 * The same when [write] throws; what it threw goes on to the caller.
 * A message whose frame is longer than the sign can take is not written at all: the sign gets
 * nothing for that request and asks again, and gets the message after it. [tooLong] hears of it;
 * it is not handed back (it would be the next message again, for ever). A stored message stays
 * in the list.
 *
 * Called on the sign link thread, one call at a time, and only for a request the sign really
 * made: [nextMessage] takes the message it returns. A push (contract VERSION 3.1) does not come
 * through here: it sends the chooser message alone ([ChooserPush]), which takes nothing.
 */
class SignResponder(
    private val nextMessage: () -> Message,
    private val write: (frame: String, reply: Reply) -> Boolean,
    private val unsent: (message: Message) -> Unit = {},
    private val tooLong: (message: Message, bytes: Int) -> Unit = { _, _ -> },
) {
    /**
     * Replies to one request of the sign (its bytes are not looked at: the link hands over
     * nothing but a `~`). Returns the message that was sent; null when it could not be written.
     */
    fun onRequest(buffer: ByteArray, count: Int): Message? {
        val message = nextMessage()
        val frame = GSON.toJson(message)
        val bytes = frame.toByteArray().size
        if (bytes > ChooserProtocol.MAX_FRAME_BYTES) {
            tooLong(message, bytes)
            return null
        }
        val written = try {
            write(frame, if (message is Message.Chooser) Reply.CHOOSER else Reply.MESSAGE)
        } catch (e: Throwable) {
            // Not on the wire as far as anybody can say: handed back like a write that says so.
            throw e.keptThrough { unsent(message) }
        }
        if (!written) unsent(message)
        return if (written) message else null
    }

    companion object {
        /** The serialiser for everything the app writes to the sign. */
        val GSON: Gson = Gson()

        /**
         * Gson builds the writer for a class by reflection the first time it writes one. On the
         * Pico that was 140 ms inside the first reply after the app started and 76 ms inside
         * the first icon. The link does this once before its first read, so an answer never
         * races a background warm-up. Returns how many classes it prepared before [stopped].
         */
        fun warmUp(stopped: () -> Boolean = { false }): Int {
            var prepared = 0
            var firstFailure: RuntimeException? = null
            fun visit(type: Class<*>): Boolean {
                if (Message::class.java.isAssignableFrom(type) && !Modifier.isAbstract(type.modifiers)) {
                    if (stopped()) return false
                    try {
                        GSON.getAdapter(type)
                        prepared++
                    } catch (e: RuntimeException) {
                        if (firstFailure == null) firstFailure = e
                    }
                }
                return type.declaredClasses.all(::visit)
            }
            if (visit(Message::class.java) && !stopped()) {
                try {
                    GSON.toJson(ChooserProtocol.chooserMessage(emptyList(), 0))
                } catch (e: RuntimeException) {
                    if (firstFailure == null) firstFailure = e
                }
            }
            firstFailure?.let { throw it }
            return prepared
        }
    }
}
