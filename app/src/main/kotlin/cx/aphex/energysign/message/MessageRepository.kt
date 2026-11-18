package cx.aphex.energysign.message

import android.content.Context
import cx.aphex.energysign.ai.AIReplyLimits
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
     * Puts the AI reply [answer] after [query] and makes the query a plain message. False, and
     * nothing is changed, when the query is not in the list any more (it was deleted while its
     * reply was on the way).
     */
    fun insertAIReply(query: Message.Marquee.AIQuery, answer: String): Boolean {
        val queryMsgIdx = marqueeMessages.indexOfFirst { it === query }
        if (queryMsgIdx < 0) return false
        marqueeMessages[queryMsgIdx] = query.toUserMessage()
        marqueeMessages.add(queryMsgIdx + 1, replyMessage(answer))
        logD("Inserted AI reply after messages[${queryMsgIdx}]")
        return true
    }

    /**
     * [query] will get no reply: it stays where it is, as the plain message it was, and is not a
     * query any more. False when it is not in the list.
     */
    fun retireQuery(query: Message.Marquee.AIQuery): Boolean {
        val queryMsgIdx = marqueeMessages.indexOfFirst { it === query }
        if (queryMsgIdx < 0) return false
        marqueeMessages[queryMsgIdx] = query.toUserMessage()
        return true
    }

    /**
     * A reply that was written elsewhere and posted to the app: it goes after the message with
     * the text it replies to, or after the newest message when there is none. It gets the same
     * clean-up and the same size limit as a reply of the app's own. False, and nothing is
     * changed, when there is nothing left of it after that or the list is empty.
     */
    fun insertAIReplyToUserMessage(postedAIAnswer: PostedAIAnswer): Boolean {
        val answer = AIReplyLimits.fitToSign(postedAIAnswer.answer)
        if (answer.isBlank() || marqueeMessages.isEmpty()) return false
        val found = marqueeMessages.indexOfFirst { it.str == postedAIAnswer.inReplyTo }
        val queryMsgIdx = if (found >= 0) found else 0
        // Convert the AIQuery message to a user message
        (marqueeMessages[queryMsgIdx] as? Message.Marquee.AIQuery)?.let {
            marqueeMessages[queryMsgIdx] = it.toUserMessage()
        }
        marqueeMessages.add(queryMsgIdx + 1, replyMessage(answer))
        logD("Inserted AI reply after messages[${queryMsgIdx}]")
        return true
    }

    private fun replyMessage(answer: String): Message.Marquee.AIReply =
        Message.Marquee.AIReply(
            answer
                .convertHeartEmojis()
                .toNormalized()
                .toUpperCasePreservingASCIIRules()
        )

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
        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val SIGN_DB_FILE_NAME = "signstrings.bin"
        private const val MAX_SIGN_STRINGS: Int = 1000

    }
}
