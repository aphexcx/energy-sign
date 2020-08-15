package cx.aphex.energysign

import android.content.Context
import cx.aphex.energysign.Message.FlashingAnnouncement.NewMessageAnnouncement
import cx.aphex.energysign.Message.FlashingAnnouncement.NowPlayingAnnouncement
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import java.io.File
import java.util.Collections.synchronizedList

class MessageManager(val context: Context) {
    private var nowPlayingTrack: Message.NowPlayingTrackMessage? = null

    private val currentIdx: AtomicInt = atomic(0)

    private val userMessages: MutableList<Message.UserMessage> =
        synchronizedList(mutableListOf<Message.UserMessage>())

    private val oneTimeMessages: MutableList<Message> =
        synchronizedList(mutableListOf<Message>())

    // Message type state mess **
    private var isChooserModeEnabled: Boolean = false
    private var keyboardInputStartedAtMs: Long = 0
    private var lastKeyboardInputReceivedAtMs: Long = 0
    private var isPaused: Boolean = false
    /**/

    private var keyboardStringBuilder: StringBuilder = StringBuilder()

    init {
        userMessages.addAll(loadUserMessages())
//        pushAdvertisements()
    }

    private var usrMsgCount: Int = 0

    /** Pushes a new user message onto the top of the messages list. */
    fun processNewUserMessage(userMessage: Message.UserMessage) {
        currentIdx.value = 0

        pushUserMessage(userMessage)
        enqueueOneTimeMessage(NewMessageAnnouncement)

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        saveUserMessages()
    }

    private fun pushOneTimeMessage(message: Message) {
        oneTimeMessages.add(0, message)
    }

    private fun enqueueOneTimeMessage(message: Message) {
        oneTimeMessages.add(message)
    }

    /** Pushes a message into the messages list at the current index. */
    private fun pushUserMessage(userMessage: Message.UserMessage) {
        userMessages.add(0, userMessage)
    }


    @OptIn(ExperimentalStdlibApi::class)
    fun getNextMessage(): Message {
        if (keyboardStringBuilder.isNotEmpty()) {
            val timeElapsedSinceLastInput =
                System.currentTimeMillis() - lastKeyboardInputReceivedAtMs

            if (timeElapsedSinceLastInput > KEYBOARD_INPUT_TIMEOUT_MS) {
                keyboardStringBuilder.clear()
            }

            if (timeElapsedSinceLastInput < KEYBOARD_INPUT_TIMEOUT_MS) {
                if (timeElapsedSinceLastInput > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS)) {
                    // Running out of input time! Display this in input warning mode.
                    return Message.KeyboardEcho.InputWarning(keyboardStringBuilder.toString())
                } else {
                    return Message.KeyboardEcho.Input(keyboardStringBuilder.toString())
                }
            }
        }

        if (oneTimeMessages.isEmpty() && userMessages.isEmpty()) {
            logD("No messages to display; injecting advertisement")
            pushAdvertisements()
        }

        oneTimeMessages.removeFirstOrNull()?.let {
            return it
        }

        if (isChooserModeEnabled) {
            val idx = currentIdx.value
            val currentUsrMsg: Message.UserMessage =
                userMessages[idx].let { it.copy(str = it.str.take(7)) }
            logD("getNextMessage: currentMessage is now messages[$idx] = $currentUsrMsg")
            logD("Sending messages[$idx] as chooser!")
            return Message.Chooser(idx + 1, userMessages.lastIndex + 1, currentUsrMsg)
        } else {
            val idx = getIdxAndAdvance()
            val currentUsrMsg: Message.UserMessage = userMessages[idx]
            logD("getNextMessage: currentMessage is now messages[$idx] = $currentUsrMsg")
            logD("Sending messages[$idx]")
            usrMsgCount++
            if (usrMsgCount % ADVERTISE_EVERY == 0) {
                logD("Advertise period reached; injecting advertisement")
                pushAdvertisements()
            }
            return currentUsrMsg
        }
    }

    private fun getIdxAndAdvance(): Int {
        return currentIdx.getAndUpdate {
            if (isPaused) {
                it
            } else {
                if (it == userMessages.lastIndex) 0
                else it + 1
            }
        }
    }

    //TODO advertise every 8 usermessages without skipping any
    // TODO deal with new usermessages coming in during advertisements
    private fun pushAdvertisements() {
        val nowPlayingMsgs: Array<Message> = nowPlayingTrack?.let {
            listOf<Message>(
                Message.ColorMessage.ChonkySlide("CURRENT", context.getColor(R.color.instagram)),
                Message.ColorMessage.ChonkySlide("TRACK", context.getColor(R.color.instagram)),
                it
            ).toTypedArray()
        } ?: listOf<Message>(
            Message.ColorMessage.OneByOneMessage("PARTY MODE", context.getColor(R.color.twitch)),
            Message.NowPlayingTrackMessage("LED THERE BE LIGHT")
        ).toTypedArray() // emptyArray()

        pushOneTimeMessages(
            Message.ColorMessage.ChonkySlide("QUARAN", context.getColor(R.color.instagram)),
            Message.ColorMessage.ChonkySlide("TRANCE", context.getColor(R.color.instagram)),
            *nowPlayingMsgs,
            Message.ColorMessage.OneByOneMessage("INSTAGRAM:", context.getColor(R.color.instagram)),
            Message.ColorMessage.OneByOneMessage(
                "@APHEXCX",
                context.getColor(R.color.instahandle),
                delayMs = 1000
            ),
            Message.ColorMessage.OneByOneMessage("TWITTER:", context.getColor(R.color.twitter)),
            Message.ColorMessage.OneByOneMessage(
                "@APHEX",
                context.getColor(R.color.twitter),
                delayMs = 1000
            ),
            Message.ColorMessage.OneByOneMessage(
                "SOUNDCLOUD",
                context.getColor(R.color.soundcloud)
            ),
            Message.ColorMessage.OneByOneMessage(
                "@APHEXCX",
                context.getColor(R.color.soundcloud),
                delayMs = 1000
            ),
            Message.Icon.Invaders
        )
    }

    private fun pushOneTimeMessages(vararg messages: Message) {
        messages.reversed().forEach { pushOneTimeMessage(it) }
    }

    private fun pushUserMessages(vararg messages: Message.UserMessage) {
        messages.reversed().forEach { pushUserMessage(it) }
    }

    private fun processUartCommand(value: ByteArray) {
        logD("Procesing Uart command ${String(value)}")
        when (val cmd = String(value)) {
            "!choose" -> {
                isChooserModeEnabled = true
            }
            "!prev" -> {
                currentIdx.update {
                    if (it > 0) it - 1 else 0
                }
            }
            "!next" -> {
                currentIdx.update {
                    if (it < userMessages.lastIndex) it + 1 else userMessages.lastIndex
                }
            }
            "!first" -> {
                currentIdx.value = 0
            }
            "!last" -> {
                currentIdx.value = userMessages.lastIndex
            }
            "!delete" -> {
                userMessages.removeAt(currentIdx.value)
                saveUserMessages()
            }
            "!endchoose" -> {
                isChooserModeEnabled = false
            }
            "!pause" -> {
                isPaused = true
            }
            "!unpause" -> {
                isPaused = false
            }
            "!micOn" -> {
                pushOneTimeMessage(Message.UtilityMessage.EnableMic)
            }
            "!micOff" -> {
                pushOneTimeMessage(Message.UtilityMessage.DisableMic)
            }
        }
    }


    /** Return the
     * //TODO last [MAX_SIGN_STRINGS]
     * strings from the sign strings file. */
    private fun loadUserMessages(): MutableList<Message.UserMessage> {
        with(File(context.filesDir, SIGN_STRINGS_FILE_NAME)) {
            when (createNewFile()) {
                true -> logD("${SIGN_STRINGS_FILE_NAME} does not exist; created new.")
                else -> logD("${SIGN_STRINGS_FILE_NAME} exists. Reading...")
            }

            bufferedReader().use { reader ->
                val list: MutableList<Message.UserMessage> =
                    reader.lineSequence() //.take(MAX_SIGN_STRINGS)
                        .map { Message.UserMessage(it) }
                        .filter { it.str.isNotBlank() }
                        .toMutableList()
                        .asReversed()

                logD(
                    "Read ${list.size} lines from ${SIGN_STRINGS_FILE_NAME}! Here are the first 10: [${list.take(
                        10
                    ).joinToString(", ") { it.str }}]"
                )
                return list
            }
        }
    }

    /** Write out the list of strings to the file */
    private fun saveUserMessages() {
        try {
            userMessages
                .map { it.str }
                .reversed()
                .toFile(File(context.filesDir, SIGN_STRINGS_FILE_NAME))
        } catch (e: Throwable) {
            logW("Exception when saving ${SIGN_STRINGS_FILE_NAME}! ${e.message}")
        }
    }

    internal fun processNewKeyboardKey(key: Char) {
        if (key.toInt() == 0) {
            logD("Invalid key!")
            return
        }
        if (keyboardStringBuilder.isEmpty()) {
            keyboardInputStartedAtMs = System.currentTimeMillis()
        }
        lastKeyboardInputReceivedAtMs = System.currentTimeMillis()
        keyboardStringBuilder.append(key)
    }

    internal fun deleteKey() {
        if (keyboardStringBuilder.isNotEmpty()) {
            lastKeyboardInputReceivedAtMs = System.currentTimeMillis()
            keyboardStringBuilder.deleteCharAt(keyboardStringBuilder.lastIndex)
        }
    }

    internal fun submitKeyboardInput() {
        val totalKeyboardInputTimeElapsed = System.currentTimeMillis() - keyboardInputStartedAtMs
//        if (keyboardStringBuilder.length < 4) {
//            // LONGER!\
//
//
//        } else if (keyboardStringBuilder.toString().split(' ').any { it in Dictionary.ENGLISH_DICT })
        // else if ! any words are in dict
        // NO SPAM!
        if (totalKeyboardInputTimeElapsed > MINIMUM_INPUT_ENTRY_PERIOD
            && keyboardStringBuilder.isNotBlank()
        ) {
            lastKeyboardInputReceivedAtMs = -1
            processNewUserMessage(Message.UserMessage(keyboardStringBuilder.toString()))
            keyboardStringBuilder.clear()
        }
    }

    fun processNewBytes(value: ByteArray) {
        if (String(value).startsWith("!")) {
            processUartCommand(value)
        } else {
            processNewUserMessage(Message.UserMessage(String(value)))
        }
    }

    fun processNowPlayingTrack(track: BeatLinkTrack) {
//        nowPlayingTrack?.let { userMessages.remove(it) }
//        userMessages.removeAll { it is Message.NowPlayingTrackMessage }
        if (track.isEmpty) {
            nowPlayingTrack = null
            pushAdvertisements()
        } else {
            nowPlayingTrack =
                Message.NowPlayingTrackMessage("${track.artist.replace('•', '*')} - ${track.title}")

            pushOneTimeMessages(
                NowPlayingAnnouncement,
                nowPlayingTrack!!
            )
        }

//        pushMessages(
////            Message.ChonkySlide(" NOW ", context.getColor(R.color.instagram)),
////            Message.ChonkySlide("PLAYING", context.getColor(R.color.instagram)),
//            NowPlayingAnnouncement,
//            nowPlayingTrack!!
//        )
    }

    companion object {
        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val MAX_SIGN_STRINGS: Int = 1000

        //How often to advertise, e.g. every 5 marquee scrolls
        private const val ADVERTISE_EVERY: Int = 8

        private const val MINIMUM_INPUT_ENTRY_PERIOD: Int = 5_000
        private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
        private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000
    }
}
