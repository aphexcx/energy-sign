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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MessageRepository(
    /** Writes the files: one write at a time, in the order they were asked for. */
    private val saver: Executor = Executors.newSingleThreadExecutor { Thread(it, "sign-save").apply { isDaemon = true } },
) {
    val marqueeMessages: MutableList<Message.Marquee> =
        synchronizedList(mutableListOf<Message.Marquee>())

    val oneTimeMessages: MutableList<Message> =
        synchronizedList(mutableListOf<Message>())

    fun pushOneTimeMessages(vararg messages: Message) {
        messages.reversed().forEach { pushOneTimeMessage(it) }
    }

    fun pushOneTimeMessage(message: Message) {
        oneTimeMessages.add(0, message)
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
     * Puts [reply] after [query] and makes the query a plain message. False, and nothing is
     * changed, when the query is not in the list any more (it was deleted while its reply was
     * on the way).
     *
     * [reply] comes ready ([replyMessage]): this runs on the thread that answers the sign, and
     * nothing here looks at the text. One pass over the list to find the query.
     */
    fun insertAIReply(query: Message.Marquee.AIQuery, reply: Message.Marquee.AIReply, seenAt: Int? = null): Boolean {
        val queryMsgIdx = indexOf(query, seenAt)
        if (queryMsgIdx < 0) return false
        marqueeMessages[queryMsgIdx] = query.toUserMessage()
        marqueeMessages.add(queryMsgIdx + 1, reply)
        return true
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
    fun retireQuery(query: Message.Marquee.AIQuery, seenAt: Int? = null): Boolean {
        val queryMsgIdx = indexOf(query, seenAt)
        if (queryMsgIdx < 0) return false
        marqueeMessages[queryMsgIdx] = query.toUserMessage()
        return true
    }

    /**
     * A reply that was written elsewhere and posted to the app: it goes after the message with
     * the text [inReplyTo], or after the newest message when there is none. False, and nothing
     * is changed, when the list is empty.
     *
     * [reply] comes ready ([postedReplyMessage]), as for [insertAIReply]. One pass over the list.
     */
    fun insertPostedAIReply(reply: Message.Marquee.AIReply, inReplyTo: String): Boolean {
        if (marqueeMessages.isEmpty()) return false
        val found = marqueeMessages.indexOfFirst { it.str == inReplyTo }
        val queryMsgIdx = if (found >= 0) found else 0
        // Convert the AIQuery message to a user message
        (marqueeMessages[queryMsgIdx] as? Message.Marquee.AIQuery)?.let {
            marqueeMessages[queryMsgIdx] = it.toUserMessage()
        }
        marqueeMessages.add(queryMsgIdx + 1, reply)
        return true
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
         */
        fun readyReply(
            result: AIReplyResult,
            make: (String) -> Message.Marquee.AIReply = ::replyMessage,
        ): Pair<AIReplyResult, Message.Marquee.AIReply?> =
            try {
                result to (result as? AIReplyResult.Reply)?.let { make(it.text) }
            } catch (e: Throwable) {
                AIReplyResult.NoReply(NoReplyReason.UNEXPECTED_FAILURE, e.javaClass.name) to null
            }

        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val SIGN_DB_FILE_NAME = "signstrings.bin"
        private const val MAX_SIGN_STRINGS: Int = 1000

    }
}
