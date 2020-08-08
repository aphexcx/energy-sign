package cx.aphex.energysign

import android.content.Context
import cx.aphex.energysign.Message.FlashingAnnouncement.NewMessageAnnouncement
import cx.aphex.energysign.Message.FlashingAnnouncement.NowPlayingAnnouncement
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import java.io.File

class MessageManager(val context: Context) {
    private var messages: MutableList<Message> = mutableListOf()

    private var currentStringIdx: Int = 0

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
    fun processNewUserMessage(value: ByteArray) {
        currentStringIdx = 0

        pushMessage(Message.UserMessage(String(value).replace('•', '*')))
        pushMessage(NewMessageAnnouncement)

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        saveUserMessages()
    }


    /** Pushes a message into the messages list at the current index. */
    private fun pushMessage(message: Message) {
        messages.add(currentStringIdx, message)
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

        val currentMessage = messages[currentStringIdx]

        return when {
            timeElapsedSinceLastInput < KEYBOARD_INPUT_TIMEOUT_MS -> {
                if (timeElapsedSinceLastInput > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS)) {
                    // Running out of input time! Display this in input warning mode.
                    Message.KeyboardEcho.InputWarning(keyboardStringBuilder.toString())
                } else {
                    Message.KeyboardEcho.Input(keyboardStringBuilder.toString())
                }
            }
            currentMessage is Message.ChonkySlide ||
                    currentMessage is Message.OneByOneMessage -> {
                messages.removeAt(currentStringIdx)
                currentMessage
            }
            currentMessage is Message.FlashingAnnouncement -> {
                when (currentMessage) {
                    NewMessageAnnouncement -> logD("Showing the new string alert!")
                    NowPlayingAnnouncement -> logD("Showing the now playing alert!")
                }
                messages.removeAt(currentStringIdx)
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
                    logD("Sending messages[$currentStringIdx]")
                    currentMessage
                }.also {
                    if (!isPaused) { //if not paused, advance index
                        advanceCurrentIdx()
                    }
                }
            }
            else -> {
                logW("Unhandled default, sending messages[$currentStringIdx]")
                currentMessage
            }
        }
    }

    private fun advanceCurrentIdx() {
        if (currentStringIdx == messages.lastIndex) {
            currentStringIdx = 0
        } else {
            currentStringIdx += 1
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
            )
        )
    }

    private fun pushMessages(vararg messages: Message) {
        messages.reversed().forEach { pushMessage(it) }
    }

    private fun processUartCommand(value: ByteArray) {
        logD("Procesing Uart command ${String(value)}")
        when (val cmd = String(value)) {
            "!choose" -> {
                chooserIdx = currentStringIdx
                isChooserModeEnabled = true
            }
            "!prev" -> {
                chooserIdx = if (chooserIdx > 0) chooserIdx - 1 else 0
                currentStringIdx = if (currentStringIdx > 0) currentStringIdx - 1 else 0
            }
            "!next" -> {
                chooserIdx =
                    if (chooserIdx < messages.lastIndex) chooserIdx + 1 else messages.lastIndex
                currentStringIdx =
                    if (currentStringIdx < messages.lastIndex) currentStringIdx + 1 else messages.lastIndex
            }
            "!first" -> {
                chooserIdx = 0
                currentStringIdx = chooserIdx
            }
            "!last" -> {
                chooserIdx = messages.lastIndex
                currentStringIdx = chooserIdx
            }
            "!delete" -> {
                messages.removeAt(chooserIdx)
                saveUserMessages()
                //clamp //TODO
                chooserIdx =
                    if (chooserIdx >= messages.lastIndex) messages.lastIndex else chooserIdx
            }
            "!endchoose" -> {
                currentStringIdx = chooserIdx
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
                        .filter { it.string.isNotBlank() }
                        .toMutableList()
                        .asReversed()

                logD(
                    "Read ${list.size} lines from ${SIGN_STRINGS_FILE_NAME}! Here are the first 10: [${list.take(
                        10
                    ).joinToString(", ") { it.string }}]"
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
                .map { it.string }
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
            processNewUserMessage(keyboardStringBuilder.toString().toByteArray())
            keyboardStringBuilder.clear()
        }
    }

    fun processNewBytes(value: ByteArray) {
        if (String(value).startsWith("!")) {
            processUartCommand(value)
        } else {
            processNewUserMessage(value)
        }
    }

    fun processNowPlayingTrack(track: BeatLinkTrack) {
        pushMessage(Message.UserMessage("${track.artist} - ${track.title}"))
        pushMessage(NowPlayingAnnouncement)
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
