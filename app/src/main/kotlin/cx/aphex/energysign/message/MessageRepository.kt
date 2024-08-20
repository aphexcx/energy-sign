package cx.aphex.energysign.message

import android.content.Context
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.gpt.GptAnswerResponse
import cx.aphex.energysign.gpt.PostedGptAnswer
import cx.aphex.energysign.message.Message.Companion.VT
import cx.aphex.energysign.toFile
import io.ktor.util.toUpperCasePreservingASCIIRules
import java.io.File
import java.util.Collections.synchronizedList

class MessageRepository {
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

    /** Inserts a GPT reply after the query it is replying to, and converts the query to a user message. */
    fun insertGPTReply(gptAnswerResponse: GptAnswerResponse) {
        val replyMsg = Message.Marquee.GPTReply(gptAnswerResponse.answer.toUpperCasePreservingASCIIRules())
        val queryMsgIdx = marqueeMessages.indexOf(gptAnswerResponse.inReplyTo)
        if (queryMsgIdx >= 0 && queryMsgIdx < marqueeMessages.size) {
            marqueeMessages.add(queryMsgIdx + 1, replyMsg)
            // Convert the GPTQuery message to a user message
            marqueeMessages[queryMsgIdx] = gptAnswerResponse.inReplyTo.toUserMessage()
            logD("Inserted GPT reply after messages[${queryMsgIdx}]: ${gptAnswerResponse.inReplyTo}")
        } else {
            marqueeMessages.add(1, replyMsg) // "Reply" to the first message if index is out of bounds
            logD("Inserted GPT reply after first message")
            marqueeMessages[0] = gptAnswerResponse.inReplyTo.toUserMessage()
        }
    }

    fun insertGPTReplyToUserMessage(postedGptAnswer: PostedGptAnswer) {
        val inReplyTo: Message.Marquee =
            marqueeMessages.firstOrNull { it.str == postedGptAnswer.inReplyTo } ?: marqueeMessages.first()

        val replyMsg = Message.Marquee.GPTReply(postedGptAnswer.answer.toUpperCasePreservingASCIIRules())
        val queryMsgIdx = marqueeMessages.indexOf(inReplyTo)
        if (queryMsgIdx >= 0 && queryMsgIdx < marqueeMessages.size) {
            marqueeMessages.add(queryMsgIdx + 1, replyMsg)
            logD("Inserted GPT reply after messages[${queryMsgIdx}]: $inReplyTo")
        } else {
            marqueeMessages.add(1, replyMsg) // "Reply" to the first message if index is out of bounds
            logD("Inserted GPT reply after first message")
        }
    }

    /** Write out the list of strings to the file */
    fun saveMarqueeMessages(context: Context) {
        try {
            marqueeMessages
                .map { it.toString() }
                .reversed()
                .toFile(File(context.filesDir, SIGN_DB_FILE_NAME))
        } catch (e: Throwable) {
            logW("Exception when saving $SIGN_DB_FILE_NAME! ${e.message}")
        }
        try {
            marqueeMessages
                .map { it.str }
                .reversed()
                .toFile(File(context.filesDir, SIGN_STRINGS_FILE_NAME))
        } catch (e: Throwable) {
            logW("Exception when saving $SIGN_STRINGS_FILE_NAME! ${e.message}")
        }
    }


    /** Return the
     * //TODO last [MAX_SIGN_STRINGS]
     * strings from the sign strings file. */
    fun loadUserMessages(context: Context): List<Message.Marquee> {
        with(File(context.filesDir, SIGN_DB_FILE_NAME)) {
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
