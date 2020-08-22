package cx.aphex.energysign

import android.content.Context
import cx.aphex.energysign.Message.FlashingAnnouncement.NewMessageAnnouncement
import cx.aphex.energysign.Message.FlashingAnnouncement.NowPlayingAnnouncement
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.ext.toNormalized
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import java.io.File
import java.util.Collections.synchronizedList

class MessageManager(val context: Context) {
    private val advertisements: MutableList<Message> = mutableListOf()
    private val playedTracks: LinkedHashSet<BeatLinkTrack> = linkedSetOf()
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

        advertisements.addAll(
            listOf(
                Message.ColorMessage.ChonkySlide("QUARAN", context.getColor(R.color.instagram)),
                Message.ColorMessage.ChonkySlide("TRANCE", context.getColor(R.color.instagram)),
                Message.ColorMessage.ChonkySlide(" LIVE ", context.getColor(R.color.instagram)),
                Message.ColorMessage.ChonkySlide(" LEDS ", context.getColor(R.color.instagram)),
                Message.NowPlayingTrackMessage(""),
                Message.ColorMessage.OneByOneMessage(
                    "INSTAGRAM:",
                    context.getColor(R.color.instagram)
                ),
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
        )
//        pushAdvertisements()
    }

    private var usrMsgCount: Int = 0

    /** Pushes a new user message onto the top of the messages list. */
    fun processNewUserMessage(str: String) {
        currentIdx.value = 0

        enqueueOneTimeMessage(NewMessageAnnouncement)
        pushUserMessage(Message.UserMessage(str.toNormalized()))

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        saveUserMessages()
    }

    private fun pushOneTimeMessages(vararg messages: Message) {
        messages.reversed().forEach { pushOneTimeMessage(it) }
    }

    private fun pushOneTimeMessage(message: Message) {
        oneTimeMessages.add(0, message)
    }

    private fun enqueueOneTimeMessage(message: Message) {
        oneTimeMessages.add(message)
    }

    private fun pushUserMessages(vararg messages: Message.UserMessage) {
        messages.reversed().forEach { pushUserMessage(it) }
    }

    /** Pushes a message into the messages list at the current index. */
    private fun pushUserMessage(userMessage: Message.UserMessage) {
        userMessages.add(0, userMessage)
    }

    // TODO deal with new usermessages coming in during advertisements
    private fun pushAdvertisements() {
        val nowPlayingMsgs: List<Message> = nowPlayingTrack?.let {
            listOf<Message>(
                Message.ColorMessage.OneByOneMessage(
                    "CURRENT",
                    context.getColor(R.color.twitch)
                ),
                Message.ColorMessage.OneByOneMessage("TRACK:", context.getColor(R.color.twitch)),
                it
            )
        } ?: emptyList()

        // Hack to avoid advertising back to back with another advertisement.
        if (!oneTimeMessages.contains(Message.Icon.Invaders)) {
            val ads = advertisements.flatMap {
                if (it is Message.NowPlayingTrackMessage) {
                    nowPlayingMsgs
                } else {
                    listOf(it)
                }
            }
            pushOneTimeMessages(*ads.toTypedArray())
        }
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
                if (userMessages.isNotEmpty()) {
                    userMessages.removeAt(currentIdx.value)
                    currentIdx.update {
                        if (it >= userMessages.lastIndex) userMessages.lastIndex else it
                    }
                    saveUserMessages()
                }
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
            "!micOff", "!🔇" -> {
                pushOneTimeMessage(Message.UtilityMessage.DisableMic)
            }

            else -> {
                when {
                    cmd.startsWith("!🅰") -> {
//                        !🅰️
//                        🆑2️⃣BAAAHS
//                        🆑AT THE
//                        🆑2️⃣BEACH!
//                        🅾️💗YOUR DJ:
//                        🅾️💗2️⃣Parzival
//                        🅾️🧡SOUNDCLOUD
//                        🅾️🧡2️⃣@PRZVL
//                        🛤️
//                        👾
                        processAdChange(cmd.drop(2))
                    }
                }
            }
        }
    }

    private fun processAdChange(adString: String) {
        val ads = adString.lines().mapNotNull { line ->

            val delay = if (line.contains("2️⃣")) {
                2000
            } else 1000

            when {
                line.startsWith("🆑") -> {
                    val str = line //.substring(line.offsetByCodePoints(0, 2))
                        .toNormalized()
                    //.trim()

                    Message.ColorMessage.ChonkySlide(
                        str,
                        context.getColor(R.color.instagram),
                        delay.toShort()
                    )
                }
                line.startsWith("🅾") -> { // idk why this is 3, i imagine it should be 1
                    val color: Int? = when {
                        line.contains("💛") -> {
                            context.getColor(R.color.instagram)
                        }
                        // ❤️
                        line.contains("🔴") || line.contains("❤️") -> {
                            context.getColor(R.color.instahandle)
                        }
                        line.contains("💙") -> {
                            context.getColor(R.color.twitter)
                        }
                        line.contains("🧡") -> {
                            context.getColor(R.color.soundcloud)
                        }
                        line.contains("💜") -> {
                            context.getColor(R.color.twitch)
                        }
                        line.contains("💚") -> {
                            context.getColor(R.color.green)
                        }
                        line.contains("💗") -> {
                            context.getColor(R.color.pink)
                        }
                        else -> null
                    }
                    val str = line //.substring(line.offsetByCodePoints(0, 3))
                        .toNormalized()
                    //.trim()


                    if (color != null) {
                        Message.ColorMessage.OneByOneMessage(str, color, delay.toShort())
                    } else {
                        Message.ColorMessage.OneByOneMessage(
                            str,
                            context.getColor(R.color.instagram),
                            delay.toShort()
                        )
                    }
                }
                line.startsWith("🛤") -> {
                    Message.NowPlayingTrackMessage("")
                }
                line.startsWith("👾") -> {
                    Message.Icon.Invaders
                }
                else -> null
            }
        }

        replaceAdsWith(ads)
    }

    private fun replaceAdsWith(ads: List<Message>) {
        advertisements.clear()
        advertisements.addAll(ads)
        pushAdvertisements()
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
                    "Read ${list.size} lines from ${SIGN_STRINGS_FILE_NAME}! Here are the first 10: [${
                        list.take(
                            10
                        ).joinToString(", ") { it.str }
                    }]"
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
            processNewUserMessage(keyboardStringBuilder.toString())
            keyboardStringBuilder.clear()
        }
    }

    fun processNewBytes(value: ByteArray) {
        if (String(value).startsWith("!")) {
            processUartCommand(value)
        } else {
            processNewUserMessage(String(value))
        }
    }

    fun processNowPlayingTrack(track: BeatLinkTrack) {
        if (track.isEmpty) {
            nowPlayingTrack = null
            pushAdvertisements()
        } else {
            if (track !in playedTracks) {
                nowPlayingTrack = Message.NowPlayingTrackMessage(
                    "${track.artist.toNormalized()} - ${track.title.toNormalized()}"
                )
                oneTimeMessages.removeIf { it is NowPlayingAnnouncement || it is Message.NowPlayingTrackMessage }

                enqueueOneTimeMessage(NowPlayingAnnouncement)
                enqueueOneTimeMessage(nowPlayingTrack!!)


                while (playedTracks.size > MAX_PLAYED_TRACKS_MEMORY) {
                    playedTracks.remove(playedTracks.first())
                }
                playedTracks.add(track)
            }
        }
    }

    companion object {
        private const val SIGN_STRINGS_FILE_NAME = "signstrings.txt"
        private const val MAX_SIGN_STRINGS: Int = 1000

        //How often to advertise, e.g. every 5 marquee scrolls
        private const val ADVERTISE_EVERY: Int = 8

        private const val MINIMUM_INPUT_ENTRY_PERIOD: Int = 5_000
        private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
        private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000

        private const val MAX_PLAYED_TRACKS_MEMORY: Int = 4

    }
}
