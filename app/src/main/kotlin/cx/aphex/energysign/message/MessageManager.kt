package cx.aphex.energysign.message

import android.content.Context
import cx.aphex.energysign.R
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.gpt.GPTViewModel
import cx.aphex.energysign.gpt.GptAnswerResponse
import cx.aphex.energysign.gpt.PostedGptAnswer
import cx.aphex.energysign.keyboard.KeyboardManager
import cx.aphex.energysign.message.Message.FlashingAnnouncement.NowPlayingAnnouncement
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File


class MessageManager(
    private val context: Context,
    val msgRepo: MessageRepository,
    private val owner: OwnerQueue,
) :
    KoinComponent {
    private val advertisements: MutableList<Message> = mutableListOf()
    private val playedTracks: LinkedHashSet<BeatLinkTrack> = linkedSetOf()
    private var nowPlayingTrack: Message.NowPlayingTrackMessage? = null

    private val currentIdx = atomic<Int>(0)

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
    private val keyboardManager = KeyboardManager

    /** Read once, here on the main thread: the owner must not ask the context for it at every save. */
    private val filesDir: File = context.filesDir

    /** The ads and the ad period as they were last set, kept across a restart. */
    private val adStore = AdStore(filesDir)

    /*
     * THREADS. ONE thread owns everything in here, the lists of MessageRepository and the input
     * of KeyboardManager: the sign link thread (see OwnerQueue). It alone calls getNextMessage,
     * and it runs every change. All other entry points are called from other
     * threads. Bluetooth and the main thread (keys, GPT answers) post their change to the owner
     * and return, so the change has NOT been made yet when they return; they have nobody to
     * tell. The BeatLink server's entry points (a posted message, a posted reply, the thinking
     * state, a track, the list) wait for the owner and return what happened, for the HTTP client. There is no lock on any of this state: "which message is next" is decided by one
     * thread from start to end, and no other thread can hold that thread up by being put to
     * sleep in the middle of a change. This object is built on the main thread, before the link
     * thread starts.
     */

    /** Hands [change], which may alter the messages, the index or the mode, to the owner. */
    private fun change(change: () -> Unit) = owner.post(change)

    /**
     * The same, for a caller that tells someone what happened (the HTTP server): waits until the
     * owner has made the change and returns its result or throws its error. Not from the main
     * thread (see OwnerQueue.ask).
     */
    private fun <T> changeAndWait(change: () -> T): T = owner.ask(change = change)


    init {

        msgRepo.marqueeMessages.addAll(msgRepo.loadUserMessages(filesDir))
        adStore.loadPeriod()?.let { advertiseEvery = it }

        advertisements.addAll(
            loadAds()
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

        // On the owner thread, in the same step that took the text and ended the input
        keyboardManager.onSubmitted = { msg ->
            logD("Processing new submitted keyboard message: $msg")
            addNewUserMessage(msg, isQuery = true)
        }

        gptViewModel.GPTResponse.observeForever { reply ->
            reply?.let {
                processGPTResponse(it)
            }
        }

        gptViewModel.GPTError.observeForever {
            change {
                logW("GPT Reply Error: ${it.message}")
                setThinking(false)
                msgRepo.pushOneTimeMessages(
                    Message.ColorMessage.ChonkySlide("NO", context.getColor(R.color.instahandle), delayMs = 650),
                    Message.ColorMessage.ChonkySlide("NO BARS", context.getColor(R.color.instahandle), delayMs = 650),
                    Message.ColorMessage.ChonkySlide("TRYING", context.getColor(R.color.instahandle), delayMs = 400),
                    Message.ColorMessage.ChonkySlide("AGAIN", context.getColor(R.color.instahandle), delayMs = 400),
                    Message.ColorMessage.ChonkySlide("LATER!", context.getColor(R.color.instahandle), delayMs = 400),
                    Message.Starfield()
                )
            }
        }
    }

    private var usrMsgCount: Int = 0

    /** Pushes a new user message onto the top of the messages list. Returns when it is there. */
    fun processNewUserMessage(str: String, isQuery: Boolean = false): Unit =
        changeAndWait { addNewUserMessage(str, isQuery) }

    /** The stored messages as the owner has them now, newest first. Returns a copy. */
    fun storedMessages(): List<Message.Marquee> = owner.ask { msgRepo.marqueeMessages.toList() }

    private fun addNewUserMessage(str: String, isQuery: Boolean) {
        currentIdx.update { 0 }

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        val normalizedStr = str
            .convertHeartEmojis()
            .toNormalized()
        val newUserMessage = if (isQuery || str.lowercase().contains("ravegpt")) {
            //TODO: could turn messages with 'baaahs' into GPTQueries
            Message.Marquee.GPTQuery(normalizedStr)
        } else {
            Message.Marquee.User(normalizedStr)
        }
        msgRepo.enqueueOneTimeMessage(Message.CountDownAnnouncement.NewMessageAnnouncement)
        msgRepo.pushMarqueeMessage(newUserMessage)
        msgRepo.saveMarqueeMessages(filesDir)
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

    /**
     * The message to send to the sign now, TAKEN from its queue or with the index moved on: only
     * ever the answer to a request the sign really made. Owner thread only.
     */
    fun getNextMessage(): Message {
        if (isInChooserMode) {
            return getNextChooserMessage()
        }
        keyboardManager.handleKeyboardInput()?.let {
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
                    msgRepo.saveMarqueeMessages(filesDir)
                    msgRepo.pushOneTimeMessages(
//                        Message.ColorMessage.ChonkySlide("BAAAHS", context.getColor(R.color.green)),
                        Message.ColorMessage.ChonkySlide("RAVEGPT", context.getColor(R.color.green), delayMs = 750),
//                        Message.ColorMessage.ChonkySlide("GARBAGE", context.getColor(R.color.green), delayMs = 750),
//                        Message.ColorMessage.ChonkySlide("MOTHER", context.getColor(R.color.green), delayMs = 750),
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

        // Nothing at all to show: no user message and no ad (the ad list was emptied on purpose,
        // which now stays so across a restart, or it holds only the place of a track and none is
        // playing). The sign still gets an answer, not an exception.
        if (msgRepo.marqueeMessages.isEmpty()) {
            return Message.Starfield()
        }

        // Fallthrough to regular marquee mode; display next user message
        val idx = getIdxAndAdvance()
        val currentMsg: Message.Marquee = msgRepo.marqueeMessages[idx]
        logD("getNextMessage: messages[$idx] = $currentMsg")
        usrMsgCount++
        if (currentMsg is Message.Marquee.GPTQuery) {
            setThinking(true)
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

    /**
     * The chooser message for the current index. No side effects and no logging: the sign polls
     * about five times a second in chooser mode and every poll evaluates this without sending it.
     */
    private fun getNextChooserMessage(): Message.Chooser =
        ChooserProtocol.chooserMessage(msgRepo.marqueeMessages, currentIdx.value)

    /**
     * [message] was taken by getNextMessage and could not be written to the sign. A one-time
     * message (a command such as the microphone switch, an announcement) goes back to the front
     * of its queue; a stored message needs nothing, it comes round again. Owner thread only.
     */
    fun returnUnsent(message: Message) {
        // (the thinking message put its follow-up in the queue when it was taken)
        if (message !is Message.Marquee && message !is Message.Chooser && message !is Message.KeyboardEcho &&
            message !is Message.ColorMessage.GPTThinking
        ) {
            msgRepo.pushOneTimeMessage(message)
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
                    msgRepo.saveMarqueeMessages(filesDir)
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
                        // handled in processNewBytes: its emoji are parsed on the caller's thread
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
                            if (AdStore.isValidPeriod(newPeriod)) {
                                advertiseEvery = newPeriod
                                saveAdPeriod()
                            } else {
                                logW("Ad period $newPeriod refused: it must be 1 or more. It stays $advertiseEvery.")
                            }
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

    /**
     * The advertisements of an ad command. On the caller's thread, not the owner's: the emoji
     * library reads its whole table the first time it is used. Reads no message state.
     */
    private fun parseAds(adString: String): List<Message> {
        val palette = AdPalette(
            instagram = context.getColor(R.color.instagram),
            instahandle = context.getColor(R.color.instahandle),
            twitter = context.getColor(R.color.twitter),
            soundcloud = context.getColor(R.color.soundcloud),
            twitch = context.getColor(R.color.twitch),
            green = context.getColor(R.color.green),
            pink = context.getColor(R.color.pink),
            chonkyDefault = context.getColor(R.color.chonkyslide_defaultpink),
            iconDefault = context.getColor(R.color.icon_defaultblue),
        )
        return AdLineParser.parse(adString, palette, warn = { logW(it) })
    }

    private fun replaceAdsWith(ads: List<Message>) {
        advertisements.clear()
        advertisements.addAll(ads)
        saveAds()
        pushAdvertisements()
    }

    /**
     * The ads as they were last set. Nothing is written here: a file that cannot be read stays
     * as it is until the next ad command replaces the list.
     */
    private fun loadAds(): List<Message> =
        when (val saved = adStore.loadAds(note = { logW("${AdStore.ADS_FILE_NAME}, $it") })) {
            is AdStore.SavedAds.Ads -> {
                logD("Loaded ${saved.ads.size} ads from ${AdStore.ADS_FILE_NAME}.")
                saved.ads
            }

            AdStore.SavedAds.NoFile -> {
                logD("${AdStore.ADS_FILE_NAME} does not exist yet; showing the default ad of <Invaders>.")
                DEFAULT_ADS
            }

            is AdStore.SavedAds.Unreadable -> {
                logW(
                    "Could not load ${AdStore.ADS_FILE_NAME} (${saved.why}); showing the default ads for this run. " +
                            "The file is left as it is until the next ad command."
                )
                DEFAULT_ADS
            }
        }

    /**
     * The owner hands over a copy of the list (its references: an ad never changes once it is
     * made); the saver turns it into text and writes it (beside the file, to the disk, then
     * moved into place). So nothing the owner does here grows with the list, it never waits for
     * storage, and a later ad command cannot get into an earlier save.
     */
    private fun saveAds() {
        val snapshot = ArrayList(advertisements)
        msgRepo.saveLater {
            try {
                adStore.saveAds(snapshot)
                logD("Wrote ${snapshot.size} ads to ${AdStore.ADS_FILE_NAME}.")
            } catch (e: Throwable) {
                logW("Exception when saving ${AdStore.ADS_FILE_NAME}! ${e.message}")
            }
        }
    }

    /** The period is read here, by the owner; the saver writes it. */
    private fun saveAdPeriod() {
        val period = advertiseEvery
        msgRepo.saveLater {
            try {
                adStore.savePeriod(period)
            } catch (e: Throwable) {
                logW("Exception when saving ${AdStore.PERIOD_FILE_NAME}! ${e.message}")
            }
        }
    }


    /** A command or a new message, as the phone writes it. Posted: it has not been carried out when this returns. */
    fun processNewBytes(value: ByteArray) = change(bytesChange(value))

    /** The same, for a caller that says "done" to someone: returns when the owner has carried it out. Not from the main thread. */
    fun processNewBytesAndWait(value: ByteArray): Unit = changeAndWait(bytesChange(value))

    /** What [value] asks for, as a change for the owner. What can be done here, on the caller's thread, is done here. */
    private fun bytesChange(value: ByteArray): () -> Unit {
        val cmd = String(value)
        return if (cmd.startsWith("!🅰")) {
            logD("Procesing Uart command $cmd")
            val ads = parseAds(cmd.drop(2))
            ({ replaceAdsWith(ads) })
        } else if (cmd.startsWith("!")) {
            // logged here, by the caller: the owner has the sign to answer
            logD("Procesing Uart command $cmd")
            ({ processUartCommand(value) })
        } else {
            ({ addNewUserMessage(cmd, isQuery = false) })
        }
    }

    /** Returns when the owner has taken the track in. */
    fun processNowPlayingTrack(track: BeatLinkTrack): Unit = changeAndWait {
        if (track.isEmpty) {
            nowPlayingTrack = null
            pushAdvertisements()
        } else {
            if (track !in playedTracks) {
                nowPlayingTrack = AdRules.nowPlaying(track.artist, track.title, note = { logW(it) })
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
    /** Returns whether the reply was stored: not when it is blank, and not when there is no message to reply to. */
    fun onNewPostedGPTReply(postedGptAnswer: PostedGptAnswer): Boolean =
        changeAndWait { insertPostedGPTReply(postedGptAnswer) }

    private fun insertPostedGPTReply(postedGptAnswer: PostedGptAnswer): Boolean {
        logD("Processing Posted GPT Answer: $postedGptAnswer")
        setThinking(false)
        if (postedGptAnswer.answer.isBlank()) {
            logW("GPT Answer is blank; not adding to messages.")
            return false
        }
        if (msgRepo.marqueeMessages.isEmpty()) {
            logW("GPT Answer has no message to reply to; not adding to messages.")
            return false
        }
        msgRepo.insertGPTReplyToUserMessage(postedGptAnswer)
        msgRepo.saveMarqueeMessages(filesDir)
        return true
    }

    private fun processGPTResponse(gptAnswerResponse: GptAnswerResponse): Unit = change { insertGPTResponse(gptAnswerResponse) }

    private fun insertGPTResponse(gptAnswerResponse: GptAnswerResponse) {
        logD("Processing GPT Response: $gptAnswerResponse")
        setThinking(false)
        if (gptAnswerResponse.answer.isBlank()) {
            logW("GPT Response is blank; not adding to messages.")
            return
        }
        msgRepo.insertGPTReply(gptAnswerResponse)
        msgRepo.saveMarqueeMessages(filesDir)
    }

    /** Returns when the owner has made the change. */
    fun togglePersistentThinkingMessage(thinking: Boolean): Unit = changeAndWait { setThinking(thinking) }

    private fun setThinking(thinking: Boolean) {
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
        private const val MAX_PLAYED_TRACKS_MEMORY: Int = 4

    }
}
