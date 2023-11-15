package cx.aphex.energysign.message

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.vdurmont.emoji.EmojiParser
import cx.aphex.energysign.R
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.message.Message.FlashingAnnouncement.NowPlayingAnnouncement
import io.ktor.util.toUpperCasePreservingASCIIRules
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import java.io.File


class MessageManager(val context: Context, val msgRepo: MessageRepository) {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Message::class.java, Message.MessageSerializer())
        .create()

    private val advertisements: MutableList<Message> = mutableListOf()
    private val playedTracks: LinkedHashSet<BeatLinkTrack> = linkedSetOf()
    private var nowPlayingTrack: Message.NowPlayingTrackMessage? = null

    private val currentIdx: AtomicInt = atomic(0)

    // Message type state mess **
    private var isInChooserMode: Boolean = false
    private var keyboardInputStartedAtMs: Long = 0
    private var lastKeyboardInputReceivedAtMs: Long = 0
    private var isPaused: Boolean = false
    /**/

    private var isInKeyboardInputMode: Boolean = false
    private var showAsWarning: Boolean = false
    private var keyboardStringBuilder: StringBuilder = StringBuilder()

    private var isGeneratingThought: Boolean = false
    private var partialSheepThought: String? = null
    private var partialSheepThoughtRetainforAnother: Int = 5
    private var partialSheepThoughtStartIdx: Int = 0

    //    private var sheepThoughtBuffer = ArrayDeque<Char>()
    private var thoughtOnPanel: String = ""
    private var showCaret: Boolean = true

    //How often to advertise, e.g. every 5 user messages
    private var advertiseEvery: Int = 8

    private val DEFAULT_ADS = listOf(
        Message.ColorMessage.IconInvaders.Enemy1(context.getColor(R.color.icon_defaultblue)),
        Message.ColorMessage.IconInvaders.Enemy2(context.getColor(R.color.icon_defaultblue))
    )

    init {
        msgRepo.marqueeMessages.addAll(msgRepo.loadUserMessages(context))

        //TODO fix deserialization
        val ads: List<Message> = loadAds()

        advertisements.addAll(
            when { // When I fix ad loading, don't need this when anymore, because loading the default ads from ads.json will work on fresh installs.
                ads.isEmpty() -> DEFAULT_ADS
                else -> ads
            }
//            listOf(
//                Message.ColorMessage.ChonkySlide("QUARAN", context.getColor(R.color.instagram)),
//                Message.ColorMessage.ChonkySlide("TRANCE", context.getColor(R.color.instagram)),
//                Message.ColorMessage.ChonkySlide(" LIVE ", context.getColor(R.color.instagram)),
//                Message.ColorMessage.ChonkySlide(" LEDS ", context.getColor(R.color.instagram)),
//                Message.NowPlayingTrackMessage(""),
//                Message.ColorMessage.OneByOneMessage(
//                    "INSTAGRAM:",
//                    context.getColor(R.color.instagram)
//                ),
//                Message.ColorMessage.OneByOneMessage(
//                    "@APHEXCX",
//                    context.getColor(R.color.instahandle),
//                    delayMs = 1000
//                ),
//                Message.ColorMessage.OneByOneMessage("TWITTER:", context.getColor(R.color.twitter)),
//                Message.ColorMessage.OneByOneMessage(
//                    "@APHEX",
//                    context.getColor(R.color.twitter),
//                    delayMs = 1000
//                ),
//                Message.ColorMessage.OneByOneMessage(
//                    "SOUNDCLOUD",
//                    context.getColor(R.color.soundcloud)
//                ),
//                Message.ColorMessage.OneByOneMessage(
//                    "@APHEXCX",
//                    context.getColor(R.color.soundcloud),
//                    delayMs = 1000
//                ),
//                Message.Icon.Invaders
//            )
        )
//        pushAdvertisements()
    }

    private var usrMsgCount: Int = 0

    /** Pushes a new user message onto the top of the messages list. */
    fun processNewUserMessage(str: String) {
        currentIdx.value = 0

        msgRepo.enqueueOneTimeMessage(Message.CountDownAnnouncement.NewMessageAnnouncement)
        msgRepo.pushMarqueeMessage(
            Message.Marquee.Default(
                str
                    .convertHeartEmojis()
                    .toNormalized()
            )
        )

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        msgRepo.saveUserMessages(context)
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
        if (!msgRepo.oneTimeMessages.any { it is Message.ColorMessage.IconInvaders }) {
            val ads = advertisements.flatMap {
                if (it is Message.NowPlayingTrackMessage) {
                    nowPlayingMsgs
                } else {
                    listOf(it)
                }
            }
            msgRepo.pushOneTimeMessages(*ads.toTypedArray())
        }
    }

    fun getNextMessage(): Message {
        if (isInChooserMode) {
            val idx = currentIdx.value
            val currentUsrMsg: Message.Marquee =
                msgRepo.marqueeMessages.getOrNull(idx)?.let {
                    when (it) {
                        is Message.Marquee.Default -> it.copy(str = it.str.take(7))
                        is Message.Marquee.Chonky -> it.copy(str = it.str.take(5))
                    }
                }
                    ?: Message.Marquee.Default("<empty>")
            logD("getNextMessage: currentMessage is now messages[$idx] = $currentUsrMsg")
            logD("Sending messages[$idx] as chooser!")
            return Message.Chooser(idx + 1, msgRepo.marqueeMessages.lastIndex + 1, currentUsrMsg)
        } else {

            if (isInKeyboardInputMode) {
                msgRepo.oneTimeMessages.clear()

                val msSinceLastInput = System.currentTimeMillis() - lastKeyboardInputReceivedAtMs

                showAsWarning =
                    (msSinceLastInput > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS))

                if (msSinceLastInput > KEYBOARD_INPUT_TIMEOUT_MS) {
                    endKeyboardInput()
                    msgRepo.pushOneTimeMessage(Message.Starfield())
                }

                if (msSinceLastInput < KEYBOARD_INPUT_TIMEOUT_MS) {
                    if (showAsWarning) {
                        // Running out of input time! Display this in input warning mode.
                        return Message.KeyboardEcho.InputWarning(keyboardStringBuilder.toString())
                    } else {
                        return Message.KeyboardEcho.Input(keyboardStringBuilder.toString())
                    }
                }
            }

            // Regular marquee mode; display next user message/

            if (msgRepo.oneTimeMessages.isEmpty() && msgRepo.marqueeMessages.isEmpty()) {
                logD("No messages to display; injecting advertisement")
                pushAdvertisements()
            }

            if (msgRepo.oneTimeMessages.isEmpty() && partialSheepThought == null && isGeneratingThought) {
                logD("Sheep is thinking, injecting thinking notification")
                //TODO how do I make sure new user message has been displayed before showing sheep thinking notification?
                return Message.ColorMessage.ChonkySlide(
                    str = "".plus(if (showCaret) '|' else ' ').padEnd(6, ' '),
                    colorCycle = context.getColor(R.color.chonkyslide_defaultpink),
                    delayMs = 10,
                )

//                msgRepo.pushOneTimeMessage(Message.FlashingAnnouncement.CustomFlashyAnnouncement("THINKING.", 50))
//                msgRepo.pushOneTimeMessage(Message.FlashingAnnouncement.CustomFlashyAnnouncement("THINKING..", 50))
//                msgRepo.pushOneTimeMessage(Message.FlashingAnnouncement.CustomFlashyAnnouncement("PONDERING.", 50))
            }

            partialSheepThought?.let { partialThought ->
                msgRepo.oneTimeMessages.clear()

                if (!isGeneratingThought && partialSheepThoughtStartIdx == partialThought.lastIndex) {
                    if (partialSheepThoughtRetainforAnother > 0) {
                        partialSheepThoughtRetainforAnother -= 1
                    } else {
                        partialSheepThought = null
                        msgRepo.pushOneTimeMessage(Message.ColorMessage.IconInvaders.Explosion(context.getColor(R.color.pink)))
                        partialSheepThoughtRetainforAnother = 5
                    }
                }
                logD("Returning partial sheep thought!")

                val endIdx = (partialSheepThoughtStartIdx + 1).coerceAtMost(partialThought.lastIndex)

                thoughtOnPanel += partialThought
                    .substring(partialSheepThoughtStartIdx until endIdx)
                    .toUpperCasePreservingASCIIRules()

                partialSheepThoughtStartIdx = endIdx

                val isPanelFull = thoughtOnPanel.length >= 5 //sheepThoughtBuffer.isEmpty()

                return Message.ColorMessage.ChonkySlide(
                    str = thoughtOnPanel.plus(if (showCaret) '|' else '_').padEnd(6, ' '),
                    colorCycle = context.getColor(R.color.chonkyslide_defaultpink),
                    delayMs = 10,
                    shouldScrollToLastLetter = isPanelFull
                ).also {
                    if (isPanelFull) {
                        thoughtOnPanel = thoughtOnPanel.takeLast(5)
                    }
                    showCaret = true
                }
            }

            msgRepo.oneTimeMessages.removeFirstOrNull()?.let {
                return it
            }

            val idx = getIdxAndAdvance()
            val currentUsrMsg: Message.Marquee = msgRepo.marqueeMessages[idx]
            logD("getNextMessage: currentMessage is now messages[$idx] = $currentUsrMsg")
            logD("Sending messages[$idx]")
            usrMsgCount++
            if (usrMsgCount % advertiseEvery == 0) {
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
                if (it == msgRepo.marqueeMessages.lastIndex) 0
                else it + 1
            }
        }
    }

    private fun processUartCommand(value: ByteArray) {
        logD("Procesing Uart command ${String(value)}")
        when (val cmd = String(value)) {
            "!c",
            "!choose" -> {
                isInChooserMode = true
            }

            "!pr",
            "!prev" -> {
                currentIdx.update {
                    if (it > 0) it - 1 else 0
                }
            }

            "!ne",
            "!next" -> {
                currentIdx.update {
                    if (it < msgRepo.marqueeMessages.lastIndex) it + 1 else msgRepo.marqueeMessages.lastIndex
                }
            }

            "!f",
            "!first" -> {
                currentIdx.value = 0
            }

            "!l",
            "!last" -> {
                currentIdx.value = msgRepo.marqueeMessages.lastIndex
            }

            "!d",
            "!delete" -> {
                if (msgRepo.marqueeMessages.isNotEmpty()) {
                    msgRepo.marqueeMessages.removeAt(currentIdx.value)
                    currentIdx.update {
                        if (it >= msgRepo.marqueeMessages.lastIndex) msgRepo.marqueeMessages.lastIndex else it
                    }
                    msgRepo.saveUserMessages(context)
                }
            }

            "!ec",
            "!endchoose" -> {
                isInChooserMode = false
            }

            "!p",
            "!pause" -> {
                isPaused = true
            }

            "!up",
            "!unpause" -> {
                isPaused = false
            }

            "!micOn" -> {
                msgRepo.pushOneTimeMessage(Message.UtilityMessage.EnableMic)
            }

            "!🔇",
            "!micOff" -> {
                msgRepo.pushOneTimeMessage(Message.UtilityMessage.DisableMic)
            }

            else -> {
                when {
                    cmd.startsWith("!🅰") -> {
//                        !🅰️
//                        🆑🕰BAAAHS
//                        🆑AT THE
//                        🆑🕰BEACH!
//                        🅾️💗YOUR DJ:
//                        🅾️🕰💗Aphex
//                        🅾️💛INSTAGRAM:
//                        🅾️🕰❤️@APHEXCX
//                        🅾️💙TWITTER:
//                        🅾️🕰💙@APHEX
//                        🅾️🧡SOUNDCLOUD
//                        🅾️🕰🧡@APHEXCX
//                        🛤️
//                        👾
                        processAdChange(cmd.drop(2))
                    }

                    cmd.startsWith("!B", ignoreCase = true) -> {
                        msgRepo.pushOneTimeMessage(
                            Message.UtilityMessage.BrightnessShift(
                                cmd.drop(2).trim().toIntOrNull()
                            )
                        )
                    }

                    cmd.startsWith("!A", ignoreCase = true) -> {
                        cmd.drop(2).trim().toIntOrNull()?.let { newPeriod ->
                            advertiseEvery = newPeriod
                            msgRepo.pushOneTimeMessages(
                                Message.FlashingAnnouncement.CustomFlashyAnnouncement("AD EVERY=$advertiseEvery"),
                                Message.Starfield()
                            )
                        }
                    }

                    cmd.startsWith("!s", ignoreCase = true) ||
                            cmd.startsWith("!search", ignoreCase = true) ||
                            cmd.startsWith("!find", ignoreCase = true) -> {
                        findUserMessage(cmd.split(" ").drop(1).joinToString(" "))
                    }
                }
            }
        }
    }

    private fun findUserMessage(query: String) {
        val idx = msgRepo.marqueeMessages.indexOfFirst { it.str.contains(query, ignoreCase = true) }
        if (idx != -1) {
            currentIdx.update {
                idx
            }
        }
    }

    private fun processAdChange(adString: String) {
        val ads = adString.lines().mapNotNull { line ->

            val delay = (EmojiParser.extractEmojis(line).count { it == "🕰" } + 1) * 1000

            when {
                line.startsWith("🆑") -> {
                    val color: Int? = emojiToColor(line)
                    val str = line //.substring(line.offsetByCodePoints(0, 2))
                        .toNormalized()
                    //.trim()

                    Message.ColorMessage.ChonkySlide(
                        str,
                        color ?: context.getColor(R.color.chonkyslide_defaultpink),
                        delay
                    )
                }

                line.startsWith("🅾") -> { // idk why this is 3, i imagine it should be 1
                    val color: Int? = emojiToColor(line)
                    val str = line //.substring(line.offsetByCodePoints(0, 3))
                        .toNormalized()

                    //.trim()

                    Message.ColorMessage.OneByOneMessage(
                        str,
                        color ?: context.getColor(R.color.instagram),
                        delay.toShort()
                    )

                }

                line.startsWith("🛤") -> {
                    Message.NowPlayingTrackMessage("")
                }

                line.startsWith("👾") -> {
                    val color: Int =
                        emojiToColor(line) ?: context.getColor(R.color.icon_defaultblue)
                    val icon = EmojiParser.extractEmojis(line).last()
                    when (icon) {
                        "👾" -> Message.ColorMessage.IconInvaders.Enemy1(color)
                        "👽" -> Message.ColorMessage.IconInvaders.Enemy2(color)
                        "💥" -> Message.ColorMessage.IconInvaders.Explosion(color)
                        "🆎" -> Message.ColorMessage.IconInvaders.ANJUNA(color)
                        "🐑" -> Message.ColorMessage.IconInvaders.BAAAHS(color)
                        "🌌" -> Message.ColorMessage.IconInvaders.DREAMSTATE(color)
                        "🌼" -> Message.ColorMessage.IconInvaders.EDC(color)
                        else -> Message.ColorMessage.IconInvaders.Enemy1(color)
                    }

                }

                else -> null
            }
        }

        replaceAdsWith(ads)
    }

    private fun emojiToColor(line: String): Int? = when {
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

    private fun replaceAdsWith(ads: List<Message>) {
        advertisements.clear()
        advertisements.addAll(ads)
        saveAds()
        pushAdvertisements()
    }

    private fun loadAds(): List<Message> {
        try {
            with(File(context.filesDir, ADS_FILE_NAME)) {
                if (createNewFile()) {
                    logD("$ADS_FILE_NAME does not exist; created new, with default ad of <Invaders>.")
                    writeText(gson.toJson(DEFAULT_ADS))
                    return DEFAULT_ADS
                }
//                val typeOfT: Type =
//                    TypeToken.getParameterized(List::class.java, Message::class.java).type
//                object : TypeToken<List<Message>>() {}.type
                val messagesType = object : TypeToken<List<Message>>() {}.type
                val ads = gson.fromJson<List<Message>>(readText(), messagesType)
//                val jsonString = json.encodeToString(Message.serializer(), message)

//                val ads = json.decodeFromString(Message.serializer(), readText())
                return ads
            }
        } catch (e: Throwable) {
            logW("Exception when loading $ADS_FILE_NAME! ${e.message}")
            return listOf()
        }
    }

    private fun saveAds() {
        try {
            with(File(context.filesDir, ADS_FILE_NAME)) {
                when (createNewFile()) {
                    true -> logD("$ADS_FILE_NAME does not exist; created new.")
                    else -> logD("$ADS_FILE_NAME exists. Writing ads...")
                }
                writeText(gson.toJson(advertisements))
            }
        } catch (e: Throwable) {
            logW("Exception when saving $ADS_FILE_NAME! ${e.message}")
        }
    }


    internal fun processNewKeyboardKey(key: Char) {
        //TODO key.toInt() needed here?
        if (key.code == 0) {
            logD("Invalid key!")
            return
        }
        isInKeyboardInputMode = true
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

    internal fun escapeKey() {
        //TODO fix bug -- pressing esc key causes type flashy error to be displayed

        // If not blank and esc key was pressed:
        // First show as warning if we aren't already
        // Then clear message if we are already warning
        if (keyboardStringBuilder.isNotBlank() && !showAsWarning) {
            // ensures we are in warning period
            lastKeyboardInputReceivedAtMs =
                System.currentTimeMillis() - KEYBOARD_INPUT_TIMEOUT_MS + KEYBOARD_INPUT_WARNING_MS
        } else {
            endKeyboardInput()
            msgRepo.pushOneTimeMessage(Message.Starfield())
        }
    }

    private fun endKeyboardInput() {
        lastKeyboardInputReceivedAtMs = -1
        keyboardStringBuilder.clear()
        isInKeyboardInputMode = false
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

        if (keyboardStringBuilder.isBlank()) {
            endKeyboardInput()
        } else {
            if (totalKeyboardInputTimeElapsed > MINIMUM_INPUT_ENTRY_PERIOD) {
                processNewUserMessage(keyboardStringBuilder.toString())
                endKeyboardInput()
            }
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
                msgRepo.oneTimeMessages.removeIf { it is NowPlayingAnnouncement || it is Message.NowPlayingTrackMessage }

                msgRepo.enqueueOneTimeMessage(NowPlayingAnnouncement)
                msgRepo.enqueueOneTimeMessage(nowPlayingTrack!!)
                msgRepo.enqueueOneTimeMessage(Message.Starfield())


                while (playedTracks.size > MAX_PLAYED_TRACKS_MEMORY) {
                    playedTracks.remove(playedTracks.first())
                }
                playedTracks.add(track)
            }
        }
    }

    fun processNewSheepThought(thought: String) {

//        msgRepo.pushOneTimeMessages(
//            Message.ColorMessage.ChonkySlide("NEW", context.getColor(R.color.chonkyslide_defaultpink)),
//            Message.ColorMessage.ChonkySlide("SHEEP", context.getColor(R.color.chonkyslide_defaultpink)),
//            Message.ColorMessage.ChonkySlide("THOT..", context.getColor(R.color.chonkyslide_defaultpink)),
//            Message.ColorMessage.IconInvaders.BAAAHS(context.getColor(R.color.instahandle)),
//            Message.ChonkyMarquee(thought.toUpperCasePreservingASCIIRules())
//        )
        msgRepo.pushMarqueeMessageBeforeCurrent(Message.Marquee.Chonky(thought.toUpperCasePreservingASCIIRules()))
    }

    fun setGeneratingThought(thinking: Boolean) {
        isGeneratingThought = thinking
    }

    fun processPartialSheepThought(chunk: String) {
        if (partialSheepThought == null) {
            partialSheepThought = ""
            partialSheepThoughtStartIdx = 0
        }
        partialSheepThought += chunk.toNormalized()
    }

    companion object {
        private const val ADS_FILE_NAME = "ads.json"

        private const val MINIMUM_INPUT_ENTRY_PERIOD: Int = 5_000
        private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
        private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000

        private const val MAX_PLAYED_TRACKS_MEMORY: Int = 4

    }
}
