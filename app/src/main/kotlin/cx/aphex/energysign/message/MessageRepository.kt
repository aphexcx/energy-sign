package cx.aphex.energysign.message

import android.content.Context
import cx.aphex.energysign.ai.AIReplyLimits
import cx.aphex.energysign.ai.NoReplyReason
import cx.aphex.energysign.ai.AIReplyResult
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.ai.PostedAIAnswer
import cx.aphex.energysign.message.Message.Companion.VT
import cx.aphex.energysign.message.MessageRepository.Companion.MAX_SIGN_STRINGS
import cx.aphex.energysign.replaceWith
import cx.aphex.energysign.replacement
import io.ktor.util.toUpperCasePreservingASCIIRules
import java.io.File
import java.io.FileOutputStream
import java.util.Collections.synchronizedList
import java.util.RandomAccess
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Predicate

class MessageRepository(
    /** Writes the files: one write at a time, in the order they were asked for. */
    private val saver: Executor = Executors.newSingleThreadExecutor { Thread(it, "sign-save").apply { isDaemon = true } },
) {
    val marqueeMessages: MutableList<Message.Marquee> =
        synchronizedList(mutableListOf<Message.Marquee>())

    // The same heading, icon or command object can be queued more than once. Distinguish
    // occurrences so a newly pushed one always precedes a stored retry, even in that case.
    private class OneTimeQueue : AbstractMutableList<Message>(), RandomAccess {
        private class Entry(val message: Message, val number: Long)
        private val entries = ArrayList<Entry>()
        var lastNumber = 0L
            private set
        fun frontIsNewerThan(number: Long) = (entries.firstOrNull()?.number ?: 0L) > number

        /** How many of the entries are the announcement of a track: kept at every change, so asking walks nothing. */
        var announcements = 0
            private set
        private fun counts(message: Message) = if (message === Message.FlashingAnnouncement.NowPlayingAnnouncement) 1 else 0
        override val size get() = entries.size
        override fun get(index: Int) = entries[index].message
        override fun add(index: Int, element: Message) {
            entries.add(index, Entry(element, ++lastNumber))
            announcements += counts(element)
            modCount++
        }
        /** All of [elements] with ONE move of the entries behind them, however many they are. */
        override fun addAll(index: Int, elements: Collection<Message>): Boolean {
            var counted = 0 // in the one pass that makes the entries: [elements] is walked once
            entries.addAll(index, elements.map { counted += counts(it); Entry(it, ++lastNumber) })
            announcements += counted
            modCount++
            return elements.isNotEmpty()
        }
        override fun set(index: Int, element: Message) =
            entries.set(index, Entry(element, ++lastNumber)).message.also { announcements += counts(element) - counts(it) }
        override fun removeAt(index: Int): Message {
            val removed = entries.removeAt(index)
            announcements -= counts(removed.message)
            modCount++
            return removed.message
        }
        override fun removeIf(filter: Predicate<in Message>): Boolean {
            val removed = entries.removeIf { filter.test(it.message) }
            if (removed) {
                announcements = entries.sumOf { counts(it.message) } // (the removal walked the entries already)
                modCount++
            }
            return removed
        }
        override fun clear() {
            entries.clear()
            announcements = 0
            modCount++
        }
    }

    private val queue = OneTimeQueue()
    val oneTimeMessages: MutableList<Message> = synchronizedList(queue)

    /** Owner only. The selection's own ads or notice have already been queued at this point. */
    internal fun lastOneTimeNumber() = queue.lastNumber
    internal fun hasNewerOneTimeAtFront(number: Long) = queue.frontIsNewerThan(number)

    /** Owner only. Whether the announcement of a track is waiting in the queue; nothing is walked for the answer. */
    internal fun announcementWaiting(): Boolean = queue.announcements > 0

    /** [messages], in their order, to the front of the queue ([pushOneTimeMessage]), in one insert. */
    fun pushOneTimeMessages(vararg messages: Message) {
        oneTimeMessages.addAll(waitingOfOnePiece(), messages.asList())
    }

    /** To the front of the queue; behind what is being shown in one piece ([pushInOnePiece]). */
    fun pushOneTimeMessage(message: Message) {
        oneTimeMessages.add(waitingOfOnePiece(), message)
    }

    /**
     * [messages] to the front of the queue, to be shown one after the other with nothing in
     * between: the heading of a reply and the reply. Until the last of them has been taken,
     * whatever else is pushed to the front goes behind them. One piece at a time.
     */
    fun pushInOnePiece(messages: List<Message>) {
        oneTimeMessages.addAll(0, messages)
        onePiece = messages
    }

    /** [message] was taken from the front and could not be shown: it is the first again, also in front of its own piece. */
    fun putBackOneTimeMessage(message: Message) {
        oneTimeMessages.add(0, message)
    }

    /** Whether [message] is one of the messages last pushed in one piece (itself, not one like it). */
    fun isOfOnePiece(message: Message): Boolean = onePiece.any { it === message }

    /** When [message] is one of a piece, what is still waiting of that piece is taken out of the queue. */
    fun dropOnePieceOf(message: Message) {
        if (!isOfOnePiece(message)) return
        oneTimeMessages.removeIf(::isOfOnePiece)
        onePiece = emptyList()
    }

    /** The last of the piece has been shown: its messages are messages like any other again. */
    fun onePieceShown() {
        onePiece = emptyList()
    }

    private var onePiece: List<Message> = emptyList()

    /** How many messages of the piece are still waiting; they are the first ones in the queue. */
    private fun waitingOfOnePiece(): Int {
        if (onePiece.isEmpty()) return 0
        var waiting = 0
        while (waiting < oneTimeMessages.size && isOfOnePiece(oneTimeMessages[waiting])) waiting++
        return waiting
    }

    fun enqueueOneTimeMessage(message: Message) {
        oneTimeMessages.add(message)
    }

    private fun pushMarqueeMessages(vararg messages: Message.Marquee) {
        messages.reversed().forEach { pushMarqueeMessage(it) }
    }

    /** Pushes a message into the messages list at the first index. */
    fun pushMarqueeMessage(marqueeMessage: Message.Marquee) {
        marqueeMessages.add(0, marqueeMessage)
    }

    /**
     * Puts [reply] after [query] and makes the query a plain message. Returns the place the reply
     * was put at: every message from there on has moved one place back, and whoever keeps a
     * place in the list has to follow its message. [NOT_PUT_IN], and nothing is changed, when
     * the query is not in the list any more (it was deleted while its reply was on the way).
     *
     * [reply] comes ready ([replyMessage]): this runs on the thread that answers the sign, and
     * nothing here looks at the text. One pass over the list to find the query.
     */
    fun insertAIReply(
        query: Message.Marquee.AIQuery, reply: Message.Marquee.AIReply, seenAt: Int? = null,
        asUser: (Message.Marquee.AIQuery) -> Message.Marquee.User = Message.Marquee.AIQuery::toUserMessage,
    ): Int {
        val queryMsgIdx = indexOf(query, seenAt)
        if (queryMsgIdx < 0) return NOT_PUT_IN
        marqueeMessages[queryMsgIdx] = asUser(query)
        marqueeMessages.add(queryMsgIdx + 1, reply)
        return queryMsgIdx + 1
    }

    /**
     * Where [query] stands, or -1. [seenAt] is where the caller saw it in a pass it has just
     * made over the list: when the query still stands there, no second pass is made.
     */
    private fun indexOf(query: Message.Marquee.AIQuery, seenAt: Int?): Int =
        if (seenAt != null && marqueeMessages.getOrNull(seenAt) === query) seenAt
        else marqueeMessages.indexOfFirst { it === query }

    /**
     * [query] will get no reply: it stays where it is, as the plain message it was, and is not a
     * query any more. False when it is not in the list.
     */
    fun retireQuery(
        query: Message.Marquee.AIQuery, seenAt: Int? = null,
        asUser: (Message.Marquee.AIQuery) -> Message.Marquee.User = Message.Marquee.AIQuery::toUserMessage,
    ): Boolean {
        val queryMsgIdx = indexOf(query, seenAt)
        if (queryMsgIdx < 0) return false
        marqueeMessages[queryMsgIdx] = asUser(query)
        return true
    }

    /**
     * A reply that was written elsewhere and posted to the app: it goes after the message with
     * the text [inReplyTo], or after the newest message when there is none. Returns the place it
     * was put at, as [insertAIReply] does; [NOT_PUT_IN], and nothing is changed, when the list
     * is empty.
     *
     * [reply] comes ready ([postedReplyMessage]), as for [insertAIReply]. One pass over the list.
     */
    fun insertPostedAIReply(
        reply: Message.Marquee.AIReply, inReplyTo: String,
        asUser: (Message.Marquee.AIQuery) -> Message.Marquee.User = Message.Marquee.AIQuery::toUserMessage,
    ): Int {
        if (marqueeMessages.isEmpty()) return NOT_PUT_IN
        val found = marqueeMessages.indexOfFirst { it.str == inReplyTo }
        val queryMsgIdx = if (found >= 0) found else 0
        // Convert the AIQuery message to a user message
        (marqueeMessages[queryMsgIdx] as? Message.Marquee.AIQuery)?.let {
            marqueeMessages[queryMsgIdx] = asUser(it)
        }
        marqueeMessages.add(queryMsgIdx + 1, reply)
        return queryMsgIdx + 1
    }

    /**
     * Write out the list of strings to the files in [dir].
     *
     * The caller (the thread that owns the messages and answers the sign) only copies the list
     * of references: the messages do not change once made. Turning them into lines and writing
     * the files is the saver's work, so nothing the caller does here grows with the text. Only
     * the newest list is kept for the saver: a save that is still waiting is replaced by the
     * next one, not queued behind it. Each file is replaced whole or not at all ([replaceWith]):
     * the board loses power without warning.
     */
    fun saveMarqueeMessages(dir: File, open: (File) -> FileOutputStream = ::FileOutputStream) {
        val waiting = newest.getAndSet(Save(dir, ArrayList(marqueeMessages), open))
        if (waiting == null) saveLater(::writeNewest)
    }

    private class Save(val dir: File, val snapshot: List<Message.Marquee>, val open: (File) -> FileOutputStream)

    private val newest = AtomicReference<Save?>()

    private fun writeNewest() {
        val save = newest.getAndSet(null) ?: return
        try {
            File(save.dir, SIGN_DB_FILE_NAME).replaceWith(save.snapshot.asReversed().map { it.toString() }, save.open)
        } catch (e: Throwable) {
            logW("Exception when saving $SIGN_DB_FILE_NAME! ${e.message}")
        }
        try {
            File(save.dir, SIGN_STRINGS_FILE_NAME).replaceWith(save.snapshot.asReversed().map { it.str }, save.open)
        } catch (e: Throwable) {
            logW("Exception when saving $SIGN_STRINGS_FILE_NAME! ${e.message}")
        }
    }

    /** Runs [write], which must have everything it writes already in hand, after the writes asked for before it. */
    fun saveLater(write: () -> Unit) = saver.execute(write)


    /** Return the
     * //TODO last [MAX_SIGN_STRINGS]
     * strings from the sign strings file. */
    fun loadUserMessages(context: Context): List<Message.Marquee> = loadUserMessages(context.filesDir)

    fun loadUserMessages(dir: File): List<Message.Marquee> {
        // What a save that was cut off left beside the files: never read, and in the way of nothing
        File(dir, SIGN_DB_FILE_NAME).replacement.delete()
        File(dir, SIGN_STRINGS_FILE_NAME).replacement.delete()
        with(File(dir, SIGN_DB_FILE_NAME)) {
            when (createNewFile()) {
                true -> logD("$SIGN_DB_FILE_NAME does not exist; created new.")
                else -> logD("$SIGN_DB_FILE_NAME exists. Reading...")
            }

            bufferedReader().use { reader ->
                val list: List<Message.Marquee> =
                    reader.lineSequence() //.take(MAX_SIGN_STRINGS)
                        .map {
                            try {
                                val parts = it.split("$VT")
                                when (Message.MSGTYPE.valueOf(parts[0])) {
                                    Message.MSGTYPE.DEFAULT -> Message.Marquee.fromString<Message.Marquee.User>(it)
                                    Message.MSGTYPE.CHONKYMARQUEE -> Message.Marquee.fromString<Message.Marquee.Chonky>(
                                        it
                                    )

                                    else -> throw IllegalArgumentException("Invalid message type")
                                }
                            } catch (e: Throwable) {
                                logE("Exception when parsing $SIGN_DB_FILE_NAME! ${e.message}")
                                null
                            }
                        }
                        .filterNotNull()
                        .filter { it.str.isNotBlank() }
                        .toList()
                        .asReversed()

                logD(
                    "Read ${list.size} lines from $SIGN_DB_FILE_NAME! Here are the first 10: [${
                        list.take(
                            10
                        ).joinToString(", ") { it.str }
                    }]"
                )
                return list
            }
        }
    }

    companion object {
        /**
         * The message for a reply of the app's own. [fittedAnswer] has been through
         * [AIReplyLimits.fitToSign]. Everything that grows with the text of a reply is done in
         * here and in [postedReplyMessage], and neither is called by the thread that answers the
         * sign: the call's thread makes the one, the HTTP server's thread the other.
         */
        fun replyMessage(fittedAnswer: String): Message.Marquee.AIReply =
            Message.Marquee.AIReply(
                fittedAnswer
                    .convertHeartEmojis()
                    .toNormalized()
                    .toUpperCasePreservingASCIIRules()
            )

        /**
         * The message for a reply that was posted to the app, with the same clean-up and the
         * same size limit as a reply of the app's own; null when nothing is left of it.
         */
        fun postedReplyMessage(answer: String): Message.Marquee.AIReply? =
            AIReplyLimits.fitToSign(answer).takeIf { it.isNotBlank() }?.let(::replyMessage)

        /**
         * [result] with the message for it when it is a reply, for the call's thread to hand to
         * the owner. It never throws, an Error of [make] included: whatever happens here, the
         * owner must be told that the call has ended, or its query would be "out" for ever.
         *
         * When [make] fails, the provider has answered already: the result says so
         * ([NoReplyReason.FAILED_AFTER_ANSWER]), and that ends the query. Any reason that lets
         * a query be asked again would buy the same answer a second time.
         */
        fun readyReply(
            result: AIReplyResult,
            make: (String) -> Message.Marquee.AIReply = ::replyMessage,
        ): Pair<AIReplyResult, Message.Marquee.AIReply?> =
            try {
                result to (result as? AIReplyResult.Reply)?.let { make(it.text) }
            } catch (e: Throwable) {
                AIReplyResult.NoReply(NoReplyReason.FAILED_AFTER_ANSWER, e.javaClass.name) to null
            }

        /** What the two inserts return when nothing was put in. */
        const val NOT_PUT_IN: Int = -1

        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val SIGN_DB_FILE_NAME = "signstrings.bin"
        private const val MAX_SIGN_STRINGS: Int = 1000

    }
}
