package cx.aphex.energysign

import android.content.Context
import cx.aphex.energysign.Message.FlashingAnnouncement.NewMessageAnnouncement
import cx.aphex.energysign.Message.FlashingAnnouncement.NowPlayingAnnouncement
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import java.io.File

class MessageManager(val context: Context) {
    private var nowPlayingTrack: Message.NowPlayingTrackMessage? = null

    private var messages: MutableList<Message> = mutableListOf()

    private var currentIdx: Int = 0

    private var chooserIdx: Int = 0

    // Message type state mess **
    private var isChooserModeEnabled: Boolean = false
    private var keyboardInputStartedAtMs: Long = 0
    private var lastKeyboardInputReceivedAtMs: Long = 0
    private var isPaused: Boolean = false
    private var isMicOn: Boolean = true
    private var sendMicChange: Boolean = false
    /**/

    private var keyboardStringBuilder: StringBuilder = StringBuilder()

    init {
        messages = loadUserMessages().toMutableList()
        advertise()
    }

    /** Pushes a new user message onto the top of the messages list. */
    fun processNewUserMessage(userMessage: Message.UserMessage) {
        currentIdx = 0

        pushMessage(userMessage)
        pushMessage(NewMessageAnnouncement)

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        saveUserMessages()
    }


    /** Pushes a message into the messages list at the current index. */
    private fun pushMessage(message: Message) {
        messages.add(currentIdx, message)
    }


    fun getNextMessage(): Message {

        val timeElapsedSinceLastInput =
            System.currentTimeMillis() - lastKeyboardInputReceivedAtMs
        if (timeElapsedSinceLastInput > KEYBOARD_INPUT_TIMEOUT_MS) {
            keyboardStringBuilder.clear()
        }

        if (messages.isEmpty()) {
            advertise()
        }

        val currentMessage = messages[currentIdx]
        logD("getNextMessage: currentMessage is now messages[$currentIdx] = $currentMessage")

        return when {
            timeElapsedSinceLastInput < KEYBOARD_INPUT_TIMEOUT_MS -> {
                if (timeElapsedSinceLastInput > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS)) {
                    // Running out of input time! Display this in input warning mode.
                    Message.KeyboardEcho.InputWarning(keyboardStringBuilder.toString())
                } else {
                    Message.KeyboardEcho.Input(keyboardStringBuilder.toString())
                }
            }
            currentMessage is Message.NowPlayingTrackMessage -> {
                if (!isPaused) { //if not paused, advance index
                    advanceCurrentIdx()
                }
                currentMessage
            }
            currentMessage is Message.ChonkySlide ||
                    currentMessage is Message.Icon ||
//                    currentMessage is Message.NowPlayingTrackMessage ||
                    currentMessage is Message.OneByOneMessage -> {
                messages.removeAt(currentIdx)
                currentMessage
            }
            currentMessage is Message.FlashingAnnouncement -> {
                when (currentMessage) {
                    NewMessageAnnouncement -> logD("Showing the new string alert!")
                    NowPlayingAnnouncement -> logD("Showing the now playing alert!")
                }
                messages.removeAt(currentIdx)
                currentMessage
            }
            sendMicChange -> { //TODO move out of here
                sendMicChange = false
                if (isMicOn) {
                    logD("Enabling beat detector microphone!")
                    Message.UtilityMessage.EnableMic
                } else {
                    logD("Disabling beat detector microphone!")
                    Message.UtilityMessage.DisableMic
                }
            }

            currentMessage is Message.UserMessage -> {
                if (isChooserModeEnabled) {
                    logD("The next string will show as chooser!")
                    Message.Chooser(chooserIdx, messages.lastIndex, currentMessage)
                } else {
                    logD("Sending messages[$currentIdx]")
                    currentMessage
                }.also {
                    if (!isPaused) { //if not paused, advance index
                        advanceCurrentIdx()
                    }
                }
            }
            else -> {
                logW("Unhandled default, sending messages[$currentIdx] = ${messages[currentIdx]}")
                currentMessage
            }
        }
    }

    private fun advanceCurrentIdx() {
        if (currentIdx == messages.lastIndex) {
            currentIdx = 0
        } else {
            currentIdx += 1
        }
    }

    //TODO advertise every 8 usermessages without skipping any
    private fun advertise() {
        pushMessages(
            Message.ChonkySlide("QUARAN", context.getColor(R.color.instagram)),
            Message.ChonkySlide("TRANCE", context.getColor(R.color.instagram)),
            Message.OneByOneMessage("INSTAGRAM:", context.getColor(R.color.instagram)),
            Message.OneByOneMessage(
                "@APHEXCX",
                context.getColor(R.color.instahandle),
                delayMs = 1000
            ),
            Message.Icon.Invaders
        )
    }

    private fun pushMessages(vararg messages: Message) {
        messages.reversed().forEach { pushMessage(it) }
    }

    private fun processUartCommand(value: ByteArray) {
        logD("Procesing Uart command ${String(value)}")
        when (val cmd = String(value)) {
            "!choose" -> {
                chooserIdx = currentIdx
                isChooserModeEnabled = true
            }
            "!prev" -> {
                chooserIdx = if (chooserIdx > 0) chooserIdx - 1 else 0
                currentIdx = if (currentIdx > 0) currentIdx - 1 else 0
            }
            "!next" -> {
                chooserIdx =
                    if (chooserIdx < messages.lastIndex) chooserIdx + 1 else messages.lastIndex
                currentIdx =
                    if (currentIdx < messages.lastIndex) currentIdx + 1 else messages.lastIndex
            }
            "!first" -> {
                chooserIdx = 0
                currentIdx = chooserIdx
            }
            "!last" -> {
                chooserIdx = messages.lastIndex
                currentIdx = chooserIdx
            }
            "!delete" -> {
                messages.removeAt(chooserIdx)
                saveUserMessages()
                //clamp //TODO
                chooserIdx =
                    if (chooserIdx >= messages.lastIndex) messages.lastIndex else chooserIdx
            }
            "!endchoose" -> {
                currentIdx = chooserIdx
                isChooserModeEnabled = false
            }
            "!pause" -> {
                isPaused = true
            }
            "!unpause" -> {
                isPaused = false
            }
            "!micOn" -> {
                isMicOn = true
                sendMicChange = true
            }
            "!micOff" -> {
                isMicOn = false
                sendMicChange = true
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
            messages
                .filterIsInstance<Message.UserMessage>()
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
        nowPlayingTrack?.let { messages.remove(it) }
        messages.removeAll { it is Message.NowPlayingTrackMessage }

        nowPlayingTrack =
            Message.NowPlayingTrackMessage("${track.artist.replace('•', '*')} - ${track.title}")

        pushMessages(
//            Message.ChonkySlide(" NOW ", context.getColor(R.color.instagram)),
//            Message.ChonkySlide("PLAYING", context.getColor(R.color.instagram)),
            NowPlayingAnnouncement,
            nowPlayingTrack!!
        )
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
