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
import cx.aphex.energysign.gpt.GPTViewModel
import cx.aphex.energysign.gpt.GptAnswerResponse
import cx.aphex.energysign.keyboard.KeyboardViewModel
import cx.aphex.energysign.message.Message.FlashingAnnouncement.NowPlayingAnnouncement
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File


class MessageManager(
    private val context: Context,
    val msgRepo: MessageRepository,
) :
    KoinComponent {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Message::class.java, Message.MessageSerializer())
        .create()

    private val advertisements: MutableList<Message> = mutableListOf()
    private val playedTracks: LinkedHashSet<BeatLinkTrack> = linkedSetOf()
    private var nowPlayingTrack: Message.NowPlayingTrackMessage? = null

    private val currentIdx: AtomicInt = atomic(0)

    // Message type state mess **
    private var isInChooserMode: Boolean = false

    private var isPaused: Boolean = false
    /**/


//    private var isGeneratingThought: Boolean = false
//    private var partialThought: String? = null
//    private var partialThoughtRetainforAnother: Int = 5
//    private var partialThoughtStartIdx: Int = 0

    //    private var sheepThoughtBuffer = ArrayDeque<Char>()
//    private var thoughtOnPanel: String = ""
//    private var showCaret: Boolean = true
//    private var showCaretThink: Boolean = true
//    private var showCaretThinkDots: Int = 0

    //How often to advertise, e.g. every 5 user messages
    private var advertiseEvery: Int = 8

    private val DEFAULT_ADS = listOf(
        Message.ColorMessage.IconInvaders.Enemy1(context.getColor(R.color.icon_defaultblue)),
        Message.ColorMessage.IconInvaders.Enemy2(context.getColor(R.color.icon_defaultblue))
    )

    private val gptViewModel: GPTViewModel by inject()
    private val keyboardViewModel: KeyboardViewModel by inject()


    init {

        msgRepo.marqueeMessages.addAll(msgRepo.loadUserMessages(context))
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

        keyboardViewModel.newSubmittedKeyboardMessage.observeForever { msg ->
            msg?.let {
                processNewUserMessage(it)
            }
        }

        gptViewModel.GPTResponse.observeForever { reply ->
            reply?.let {
                processGPTResponse(it)
            }
        }

        gptViewModel.GPTError.observeForever {
            logW("GPT Reply Error: ${it.message}")
            togglePersistentThinkingMessage(false)
            msgRepo.pushOneTimeMessages(
                Message.ColorMessage.ChonkySlide("NO", context.getColor(R.color.instahandle), delayMs = 750),
                Message.ColorMessage.ChonkySlide("NO BARS", context.getColor(R.color.instahandle), delayMs = 750),
                Message.ColorMessage.ChonkySlide("TRYING", context.getColor(R.color.instahandle), delayMs = 500),
                Message.ColorMessage.ChonkySlide("AGAIN", context.getColor(R.color.instahandle), delayMs = 500),
                Message.ColorMessage.ChonkySlide("LATER!", context.getColor(R.color.instahandle), delayMs = 500),
                Message.Starfield()
            )
        }
    }

    private var usrMsgCount: Int = 0

    /** Pushes a new user message onto the top of the messages list. */
    fun processNewUserMessage(str: String) {
        currentIdx.value = 0

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        val normalizedStr = str
            .convertHeartEmojis()
            .toNormalized()
        val newUserMessage = if (str.lowercase().contains("ravegpt")) {
            Message.Marquee.GPTQuery(normalizedStr)
        } else {
            Message.Marquee.User(normalizedStr)
        }
        msgRepo.enqueueOneTimeMessage(Message.CountDownAnnouncement.NewMessageAnnouncement)
        msgRepo.pushMarqueeMessage(newUserMessage)
        msgRepo.saveMarqueeMessages(context)
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
            return getNextChooserMessage()
        }
        keyboardViewModel.handleKeyboardInput()?.let {
            return it
        }

        if (msgRepo.oneTimeMessages.isEmpty()) {
            when (val curMsg = msgRepo.marqueeMessages.getOrNull(currentIdx.value)) {
//                is Message.Marquee.GPTQuery -> {
////                    if (!isGeneratingThought) {
//                    setGeneratingThought(true)
//                    gptViewModel.generateAnswer(curMsg)
//                    return curMsg
////                    }
//                    //isGeneratingThought
////                    partialThought?.let { partialThought ->
////                        return partialThoughtMessage(partialThought)
////                    }
//                }

                is Message.Marquee.GPTReply -> {
                    logD("Converting GPTReply to User message!")
                    msgRepo.marqueeMessages[currentIdx.value] = curMsg.toChonkyMessage()
                    msgRepo.saveMarqueeMessages(context)
                    msgRepo.pushOneTimeMessages(
                        Message.ColorMessage.ChonkySlide("RAVE", context.getColor(R.color.green)),
                        Message.ColorMessage.ChonkySlide("RAVEGPT", context.getColor(R.color.green), delayMs = 750),
                        Message.ColorMessage.ChonkySlide(
                            "SAYS.",
                            context.getColor(R.color.green), delayMs = 250
                        ),
                        Message.ColorMessage.ChonkySlide(
                            "SAYS..",
                            context.getColor(R.color.green), delayMs = 250
                        ),
                        Message.ColorMessage.ChonkySlide(
                            "SAYS...",
                            context.getColor(R.color.green), delayMs = 250
                        ),
                        // Message.Starfield(stars = 100),
                        // Message.ColorMessage.IconInvaders.BAAAHS(context.getColor(R.color.instahandle)),
                        // Message.ChonkyMarquee(thought.toUpperCasePreservingASCIIRules())
                    )
                }

                else -> {}
            }
        }

        if (msgRepo.marqueeMessages.isEmpty() && msgRepo.oneTimeMessages.isEmpty()) {
            logD("No messages to display; injecting advertisement")
            pushAdvertisements()
        }

        // Display one-time messages first
        msgRepo.oneTimeMessages.removeFirstOrNull()?.let {
            if (it is Message.ColorMessage.GPTThinking) {
                logD("GPT is still thinking, injecting another thinking notification")
                msgRepo.pushOneTimeMessage(it.thinkMore())
            }
            return it
        }

        // Fallthrough to regular marquee mode; display next user message
        val idx = getIdxAndAdvance()
        val currentMsg: Message.Marquee = msgRepo.marqueeMessages[idx]
        logD("getNextMessage: messages[$idx] = $currentMsg")
        usrMsgCount++
        if (currentMsg is Message.Marquee.GPTQuery) {
            togglePersistentThinkingMessage(true)
            gptViewModel.generateAnswer(currentMsg)
        } else {
            if (usrMsgCount % advertiseEvery == 0) {
                logD("Advertise period reached; injecting advertisement")
                pushAdvertisements()
            }
//            if (timeSinceLastGPTMessage == 5) {
//                gptViewModel.generateReplyToMultipleMessages(
//                    msgRepo.marqueeMessages.filterIsInstance<Message.Marquee.User>().takeLast(5))
//            }
        }
        return currentMsg

    }

//    private fun partialThoughtMessage(partialThought: String): Message.ColorMessage.ChonkySlide {
//        msgRepo.oneTimeMessages.clear()
//
//        if (!isGeneratingThought && partialThoughtStartIdx == partialThought.lastIndex) {
//            if (partialThoughtRetainforAnother > 0) {
//                partialThoughtRetainforAnother -= 1
//            } else {
//                this.partialThought = null
//                msgRepo.pushOneTimeMessage(Message.ColorMessage.IconInvaders.Explosion(context.getColor(R.color.pink)))
//                partialThoughtRetainforAnother = 5
//            }
//        }
//        logD("Returning partial sheep thought!")
//
//        val endIdx = (partialThoughtStartIdx + 1).coerceAtMost(partialThought.lastIndex)
//
//        thoughtOnPanel += partialThought
//            .substring(partialThoughtStartIdx until endIdx)
//            .toUpperCasePreservingASCIIRules()
//
//        partialThoughtStartIdx = endIdx
//
//        val isPanelFull = thoughtOnPanel.length >= 5 //sheepThoughtBuffer.isEmpty()
//
//        return Message.ColorMessage.ChonkySlide(
//            str = thoughtOnPanel.plus(if (showCaret) '|' else '_').padEnd(6, ' '),
//            colorCycle = context.getColor(R.color.chonkyslide_defaultpink),
//            delayMs = 10,
//            shouldScrollToLastLetter = isPanelFull
//        ).also {
//            if (isPanelFull) {
//                thoughtOnPanel = thoughtOnPanel.takeLast(5)
//            }
//            showCaret = true
//        }
//    }

    private fun getNextChooserMessage(): Message.Chooser {
        val idx = currentIdx.value
        val trimmedMessage: Message.Marquee =
            msgRepo.marqueeMessages.getOrNull(idx)?.let {
                when (it) {
                    is Message.Marquee.User -> Message.Marquee.User(str = it.str.take(7))
                    is Message.Marquee.GPTQuery -> Message.Marquee.GPTQuery(str = it.str.take(7))
                    is Message.Marquee.Chonky -> Message.Marquee.Chonky(str = it.str.take(5))
                    is Message.Marquee.GPTReply -> Message.Marquee.GPTReply(str = it.str.take(5))
                }
            }
                ?: Message.Marquee.User("<empty>")
        logD("getNextMessage: messages[$idx] = $trimmedMessage")
        logD("Sending messages[$idx] as chooser!")
        return Message.Chooser(idx + 1, msgRepo.marqueeMessages.lastIndex + 1, trimmedMessage)
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
                    msgRepo.saveMarqueeMessages(context)
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
                        delay.toShort()
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

    /* For replies generated by externally running models e.g. Llama running on my laptop */
    fun onNewPostedGPTReply(reply: String) {
        processGPTResponse(
            GptAnswerResponse(
                answer = reply,
                inReplyTo = msgRepo.marqueeMessages.first() as Message.Marquee.GPTQuery //TODO inReplyTo message may not always be the first one
            )
        )
    }

    private fun processGPTResponse(gptAnswerResponse: GptAnswerResponse) {
        logD("Processing GPT Response: $gptAnswerResponse")
        togglePersistentThinkingMessage(false)
        if (gptAnswerResponse.answer.isBlank()) {
            logW("GPT Response is blank; not adding to messages.")
            return
        }
        msgRepo.insertGPTReply(gptAnswerResponse)
//        getIdxAndAdvance() // FIXME eww not good, have to do this to advance past the gptquery that is now a user message so it doesn't display again before the gptreply
        msgRepo.saveMarqueeMessages(context)
    }

    fun togglePersistentThinkingMessage(thinking: Boolean) {
        if (thinking) {
//            showCaretThink = true
//            showCaretThinkDots = 0
//            msgRepo.marqueeMessages.add(currentIdx.value +1, Message.Marquee.GPTThinking())
            msgRepo.pushOneTimeMessage(Message.ColorMessage.GPTThinking(colorCycle = context.getColor(R.color.chonkyslide_defaultpink)))
        } else {
            msgRepo.oneTimeMessages.removeIf { it is Message.ColorMessage.GPTThinking }
        }
//        isGeneratingThought = thinking
        logD("Set generating thought to $thinking")
    }

//    fun processPartialThought(chunk: String) {
//        if (partialThought == null) {
//            partialThought = ""
//            partialThoughtStartIdx = 0
//        }
//        partialThought += chunk.toNormalized()
//    }

    companion object {
        private const val ADS_FILE_NAME = "ads.json"

        private const val MAX_PLAYED_TRACKS_MEMORY: Int = 4

    }
}
