package cx.aphex.energysign.message

import android.content.Context
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.toFile
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

    /** Pushes a message into the messages list at the current index. */
    fun pushMarqueeMessage(marqueeMessage: Message.Marquee) {
        marqueeMessages.add(0, marqueeMessage)
    }

    /** Write out the list of strings to the file */
    fun saveUserMessages(context: Context) {
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
    fun loadUserMessages(context: Context): MutableList<Message.Marquee.Default> {
        with(File(context.filesDir, SIGN_STRINGS_FILE_NAME)) {
            when (createNewFile()) {
                true -> logD("$SIGN_STRINGS_FILE_NAME does not exist; created new.")
                else -> logD("$SIGN_STRINGS_FILE_NAME exists. Reading...")
            }

            bufferedReader().use { reader ->
                val list: MutableList<Message.Marquee.Default> =
                    reader.lineSequence() //.take(MAX_SIGN_STRINGS)
                        .map { Message.Marquee.Default(it) }
                        .filter { it.str.isNotBlank() }
                        .toMutableList()
                        .asReversed()

                logD(
                    "Read ${list.size} lines from $SIGN_STRINGS_FILE_NAME! Here are the first 10: [${
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
        private const val MAX_SIGN_STRINGS: Int = 1000

    }
}
