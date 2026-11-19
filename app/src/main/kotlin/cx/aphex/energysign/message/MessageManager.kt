package cx.aphex.energysign.message

import android.content.Context
import cx.aphex.energysign.R
import cx.aphex.energysign.ai.AIQueryDesk
import cx.aphex.energysign.ai.AIReplyResult
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.beatlinkdata.BeatLinkTrack
import cx.aphex.energysign.ext.convertHeartEmojis
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.ext.toNormalized
import cx.aphex.energysign.ai.PostedAIAnswer
import cx.aphex.energysign.ai.NoReplyReason
import cx.aphex.energysign.keyboard.KeyboardManager
import cx.aphex.energysign.message.Message.FlashingAnnouncement.NowPlayingAnnouncement
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.IdentityHashMap


class MessageManager(
    private val context: Context,
    val msgRepo: MessageRepository,
    private val owner: OwnerQueue,
    /**
     * A clock that only counts, in milliseconds: for how long the sign has said "thinking", and
     * for the pauses and the hourly budget of the queries. The app gives the one that also counts
     * while the board sleeps (AppModule); this default is for tests on the JVM.
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
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

    private val replies: AIReplySource by inject()
    private val keyboardManager = KeyboardManager

    /** Read once, here on the main thread: the owner must not ask the context for it at every save. */
    private val filesDir: File = context.filesDir

    /** The ads and the ad period as they were last set, kept across a restart. */
    private val adStore = AdStore(filesDir, warn = { logW(it) })

    /*
     * THREADS. ONE thread owns everything in here, the lists of MessageRepository and the input
     * of KeyboardManager: the sign link thread (see OwnerQueue). It alone calls getNextMessage
     * and chooserShown, and it runs every change. All other entry points are called from other
     * threads. Bluetooth, the main thread (keys) and the threads AI answers come from post their change to the owner
     * and return, so the change has NOT been made yet when they return; they have nobody to
     * tell. The BeatLink server's entry points (a posted message, a posted reply, the thinking
     * state, a track, the list) wait for the owner and return what happened, for the HTTP client. There is no lock on any of this state: "which message is next" is decided by one
     * thread from start to end, and no other thread can hold that thread up by being put to
     * sleep in the middle of a change. This object is built on the main thread, before the link
     * thread starts.
     */

    /**
     * Called once whenever what the chooser should show has changed (entering chooser mode,
     * another message or count, leaving it): the sign link then pushes it to the sign, if
     * pushes are switched on. Called on the owner thread.
     */
    @Volatile
    var onChooserChanged: () -> Unit = {}

    /** Gets the word of an `!attn` command (off, auto, break, zeros; or whatever was typed). Called on the owner thread. */
    @Volatile
    var onAttentionCommand: (word: String) -> Unit = {}

    private val chooserWatch = ChooserChangeWatch(view = { chooserShown() }) { onChooserChanged() }

    /** Hands [change], which may alter the messages, the index or the mode, to the owner; it runs under the chooser watch. */
    private fun change(change: () -> Unit) = owner.post { chooserWatch.watch(change) }

    /**
     * The same, for a caller that tells someone what happened (the HTTP server): waits until the
     * owner has made the change and returns its result or throws its error. Not from the main
     * thread (see OwnerQueue.ask).
     */
    private fun <T> changeAndWait(change: () -> T): T = owner.ask { chooserWatch.watch(change) }

    /**
     * When a query is asked and for how long the sign says "thinking" for it (see AIQueryDesk for
     * the rules). Owner thread only. A reply comes back on some other thread and is POSTED to the
     * owner, one event a reply, so no reply can be lost or overtaken; the same for the end of
     * the warm-up. (Declared after [change]: the warm-up may end at any moment from here on.)
     */
    private val desk: AIQueryDesk<Message.Marquee.AIQuery> = AIQueryDesk(
        start = { query ->
            replies.ask(query.str, stillWanted = { stillListed(query) }) { result ->
                // On the call's thread: the reply is made into its message here, so that the
                // owner has nothing to do with its text. Whatever happens in that, the end of
                // the call is posted.
                val (answered, reply) = MessageRepository.readyReply(result)
                change { replyArrived(query, answered, reply) }
            }
        },
        listedAmong = { known ->
            // One pass, and by identity on both sides: two queries with the same text are two
            // queries. Where each one stands is noted, for whoever changes it right after.
            seenAt.clear()
            msgRepo.marqueeMessages.forEachIndexed { index, message ->
                if (message is Message.Marquee.AIQuery && message in known) seenAt[message] = index
            }
            seenAt.keys
        },
        log = { logD(it) },
        warmingUp = replies.warmUp { change { warmUpEnded() } },
    )

    /**
     * Where the desk saw each query it asked about, in its last pass over the list. Good until
     * the list is changed; whoever uses a place checks that the query still stands there.
     */
    private val seenAt = IdentityHashMap<Message.Marquee.AIQuery, Int>()

    /**
     * Whether [query] is still in the list. Asked by the call's thread as the last thing before
     * the request goes out, and answered by the owner: a query that was deleted while its call
     * was being prepared is not sent. When the owner does not answer, it is not sent either.
     */
    private fun stillListed(query: Message.Marquee.AIQuery): Boolean =
        try {
            owner.ask { msgRepo.marqueeMessages.any { it === query } }
        } catch (e: Exception) {
            false
        }

    /** The thinking notice was asked for from outside (the HTTP route), not for a query of the app's own. */
    private var thinkingFromOutside: Boolean = false


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
            // (the key was posted by KeyboardManager itself, not through change())
            chooserWatch.watch { addNewUserMessage(msg, isQuery = true) }
        }

    }

    private var usrMsgCount: Int = 0

    /** Pushes a new user message onto the top of the messages list. Returns when it is there. */
    fun processNewUserMessage(str: String, isQuery: Boolean = false): Unit =
        changeAndWait { addNewUserMessage(str, isQuery) }

    /** The stored messages as the owner has them now, newest first. Returns a copy. */
    fun storedMessages(): List<Message.Marquee> = owner.ask { msgRepo.marqueeMessages.toList() }

    private fun addNewUserMessage(str: String, isQuery: Boolean) {
        // The new message is what the sign shows next, and in chooser mode the one that is
        // chosen (as before; MessageManagerOwnerTest has it): the index is MOVED to it here,
        // on purpose. Where a message is only put in, the index stays with its message (madeRoomAt).
        currentIdx.update { 0 }

//        String(value).split("++").filter { it.isNotBlank() }.reversed().forEach {
//            pushStringOnList(it.replace('•', '*'))
//        }

        val normalizedStr = str
            .convertHeartEmojis()
            .toNormalized()
        // A text of which nothing is left for the sign has nothing to ask either.
        val newUserMessage = if ((isQuery || str.lowercase().contains("ravegpt")) && normalizedStr.isNotBlank()) {
            //TODO: could turn messages with 'baaahs' into AIQueries
            Message.Marquee.AIQuery(normalizedStr)
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

        // A thinking notice whose time is up goes first of all, so that everything below sees
        // the queue as it is: the heading of a reply and the advertisements for an empty list
        // are both decided by whether anything is waiting to be shown. (The time limit is for
        // the notice, not for the call: the reply is put in when it comes.)
        if (msgRepo.oneTimeMessages.any { it is Message.ColorMessage.AIThinking } && !thinkingWanted()) {
            msgRepo.oneTimeMessages.removeIf { it is Message.ColorMessage.AIThinking }
        }

        if (msgRepo.oneTimeMessages.isEmpty()) {
            when (val curMsg = msgRepo.marqueeMessages.getOrNull(currentIdx.value)) {
//                is Message.Marquee.AIQuery -> {
////                    if (!isGeneratingThought) {
//                    setGeneratingThought(true)
//                    aiViewModel.generateAnswer(curMsg)
//                    return curMsg
////                    }
//                    //isGeneratingThought
////                    partialThought?.let { partialThought ->
////                        return partialThoughtMessage(partialThought)
////                    }
//                }

                is Message.Marquee.AIReply -> {
                    logD("Converting AIReply to User message!")
                    val reply = curMsg.toChonkyMessage()
                    msgRepo.marqueeMessages[currentIdx.value] = reply
                    msgRepo.saveMarqueeMessages(filesDir)
                    // The heading and its reply are ONE piece, and the reply is shown from the
                    // queue, behind its heading: the index goes on past it now. The list may
                    // change in any way while the heading is on the sign (a late reply is put
                    // in, a new message comes, somebody moves the chooser): what follows
                    // "SAYS..." is this reply, or nothing of the heading is shown any more
                    // (the reply was deleted, the keyboard emptied the queue).
                    getIdxAndAdvance()
                    msgRepo.pushInOnePiece(listOf(
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
                        reply,
                    ))
                }

                else -> {}
            }
        }

        if (msgRepo.marqueeMessages.isEmpty() && msgRepo.oneTimeMessages.isEmpty()) {
            logD("No messages to display; injecting advertisement")
            pushAdvertisements()
        }

        // Display one-time messages first
        msgRepo.oneTimeMessages.removeFirstOrNull()?.let { oneTime ->
            if (oneTime is Message.ColorMessage.AIThinking) {
                logD("AI is still thinking, injecting another thinking notification")
                msgRepo.pushOneTimeMessage(oneTime.thinkMore())
            }
            return oneTime
        }

        // Nothing at all to show: no user message and no ad (the ad list was emptied on purpose,
        // which now stays so across a restart, or it holds only the place of a track and none is
        // playing). The sign still gets an answer, not an exception.
        if (msgRepo.marqueeMessages.isEmpty()) {
            return Message.Starfield()
        }

        // Fallthrough to regular marquee mode; display next user message
        msgRepo.onePieceShown() // the queue is empty: whatever was one piece has been shown or dropped
        val idx = getIdxAndAdvance()
        val currentMsg: Message.Marquee = msgRepo.marqueeMessages[idx]
        logD("getNextMessage: messages[$idx] = $currentMsg")
        usrMsgCount++
        if (currentMsg is Message.Marquee.AIQuery) {
            // Asked now, or in line behind the call that is out: the notice goes up. Not asked
            // (it is being answered already, or nothing may be asked at the moment): nothing.
            if (desk.shown(currentMsg, nowMs())) setThinking(true)
        } else {
            if (usrMsgCount % advertiseEvery == 0) {
                logD("Advertise period reached; injecting advertisement")
                pushAdvertisements()
            }
//            if (timeSinceLastAIMessage == 5) {
//                aiViewModel.generateReplyToMultipleMessages(
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
     * of its queue; a stored message needs nothing, it comes round again. A reply that was taken
     * from the queue, behind its heading, goes back like a one-time message. Owner thread only.
     */
    fun returnUnsent(message: Message) {
        // (the thinking message put its follow-up in the queue when it was taken)
        // A reply that was taken from the queue, behind its heading, is not one that "comes
        // round again" now: its heading has been shown.
        val fromTheList = message is Message.Marquee && !msgRepo.isOfOnePiece(message)
        if (!fromTheList && message !is Message.Chooser && message !is Message.KeyboardEcho &&
            message !is Message.ColorMessage.AIThinking
        ) {
            msgRepo.putBackOneTimeMessage(message)
        }
    }

    /**
     * What the chooser shows now, null outside chooser mode. It reads the list, the index and
     * the mode and changes nothing: this, and not getNextMessage, is what a push sends, so a
     * push cannot take a message. Owner thread only.
     */
    fun chooserShown(): Message.Chooser? = if (isInChooserMode) getNextChooserMessage() else null

    /**
     * A message was put into the list at [place], and every message from there on stands one
     * place further back. The index is a MESSAGE, not a number: it goes with its message.
     *
     * In chooser mode it is the message a person is looking at and may delete next, so it
     * follows that message also when the new one took exactly its place. Outside chooser mode
     * it is the message that comes next, which nobody has seen yet: a reply that is put in
     * right there (its query was the last thing shown) is the next message.
     */
    private fun madeRoomAt(place: Int) {
        if (place < 0) return
        val at = currentIdx.value
        if (place < at || (isInChooserMode && place == at)) currentIdx.update { it + 1 }
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
                    val deleted = msgRepo.marqueeMessages.removeAt(currentIdx.value)
                    // A reply that is waiting behind its heading: neither is shown any more.
                    msgRepo.dropOnePieceOf(deleted)
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

                    // before "!A", which it also begins with: whether and how the chooser is pushed to the sign
                    cmd.startsWith(ATTENTION_COMMAND, ignoreCase = true) ->
                        onAttentionCommand(cmd.drop(ATTENTION_COMMAND.length).trim().lowercase())

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
     * The owner copies the list: its n references, no more (an ad never changes once it is
     * made). Turning the ads into text and writing the file (beside it, to storage, renamed
     * into place) are the saver's work. So the owner's part grows with the number of ads only,
     * not with their text, it never waits for storage, and a later ad command cannot get into
     * an earlier save.
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
    fun processNowPlayingTrack(track: BeatLinkTrack) {
        // Made here, on the caller's thread: fitting a long title to the sign serialises it more
        // than once, and the owner has the sign to answer. The owner only stores the result.
        val line = if (track.isEmpty) null else AdRules.nowPlaying(track.artist, track.title, note = { logW(it) })
        changeAndWait { takeInTrack(track, line) }
    }

    /** [line] is the track's line for the sign, made by the caller; null for an empty track. Owner only. */
    private fun takeInTrack(track: BeatLinkTrack, line: Message.NowPlayingTrackMessage?) {
        if (line == null) {
            nowPlayingTrack = null
            pushAdvertisements()
        } else {
            if (track !in playedTracks) {
                nowPlayingTrack = line
                msgRepo.oneTimeMessages.removeIf { it is NowPlayingAnnouncement || it is Message.NowPlayingTrackMessage }

                msgRepo.enqueueOneTimeMessage(NowPlayingAnnouncement)
                msgRepo.enqueueOneTimeMessage(line)
                msgRepo.enqueueOneTimeMessage(Message.Starfield())


                while (playedTracks.size > MAX_PLAYED_TRACKS_MEMORY) {
                    playedTracks.remove(playedTracks.first())
                }
                playedTracks.add(track)
            }
        }
    }

    /* For replies generated by externally running models e.g. Llama running on my laptop */
    /**
     * Returns whether the reply was stored: not when it is blank, and not when there is no message to reply to.
     *
     * The reply is cleaned, fitted to the sign and made into its message HERE, on the caller's
     * thread (the HTTP server's), however long it is. The owner gets a message that is ready and
     * only puts it into the list.
     */
    fun onNewPostedAIReply(postedAIAnswer: PostedAIAnswer): Boolean {
        val reply = MessageRepository.postedReplyMessage(postedAIAnswer.answer)
        return changeAndWait { insertPostedAIReply(reply, postedAIAnswer.inReplyTo) }
    }

    private fun insertPostedAIReply(reply: Message.Marquee.AIReply?, inReplyTo: String): Boolean {
        thinkingFromOutside = false
        endThinkingUnlessWanted()
        val place = if (reply == null) MessageRepository.NOT_PUT_IN else msgRepo.insertPostedAIReply(reply, inReplyTo)
        if (place == MessageRepository.NOT_PUT_IN) {
            logW("AI Answer is blank, or there is no message to reply to; not adding to messages.")
            return false
        }
        madeRoomAt(place)
        msgRepo.saveMarqueeMessages(filesDir)
        logD("Inserted a posted AI reply")
        return true
    }

    /**
     * The call for [query] has ended. On the owner thread: the caller posted this. Every reply
     * is its own event, so two replies that end at the same moment are both dealt with.
     * [reply] is the message for a result that is a reply, made by the call's thread.
     *
     * What this costs the owner, at most: ONE pass over the list, the desk's, which also notes
     * where the query stands; then the insert (the messages behind it move up one place) and
     * the copy of the list's references for the save, as for every save. Nothing here looks at
     * the text of a reply.
     */
    private fun replyArrived(query: Message.Marquee.AIQuery, result: AIReplyResult, reply: Message.Marquee.AIReply?) {
        try {
            // In every case: the change first, the save, and the log line after them. A log
            // call that fails must not leave a result half dealt with or unsaved.
            when (val outcome = desk.finished(query, result, nowMs())) {
                is AIQueryDesk.Outcome.Reply -> {
                    val place = if (reply == null) MessageRepository.NOT_PUT_IN else msgRepo.insertAIReply(query, reply, seenAt[query])
                    if (place != MessageRepository.NOT_PUT_IN) {
                        madeRoomAt(place)
                        msgRepo.saveMarqueeMessages(filesDir)
                        logD("Inserted the AI reply after its query")
                    } else {
                        logD("The query was deleted while its reply was on the way; the reply is dropped.")
                    }
                }

                is AIQueryDesk.Outcome.Retired -> {
                    // Asking again would change nothing: it stays on the sign as the message it was.
                    if (msgRepo.retireQuery(query, seenAt[query])) msgRepo.saveMarqueeMessages(filesDir)
                    logW("No AI reply (${outcome.why}); the message stays as a plain message and is not asked again.")
                }

                is AIQueryDesk.Outcome.Failed -> {
                    // Only when somebody is still looking at the dots; not out of nowhere, later.
                    if (outcome.whileWatched) {
                        msgRepo.oneTimeMessages.removeIf { it is Message.ColorMessage.AIThinking }
                        val slides = arrayOf(
                            Message.ColorMessage.ChonkySlide("NO", context.getColor(R.color.instahandle), delayMs = 650),
                            Message.ColorMessage.ChonkySlide("NO BARS", context.getColor(R.color.instahandle), delayMs = 650),
                            Message.ColorMessage.ChonkySlide("TRYING", context.getColor(R.color.instahandle), delayMs = 400),
                            Message.ColorMessage.ChonkySlide("AGAIN", context.getColor(R.color.instahandle), delayMs = 400),
                            Message.ColorMessage.ChonkySlide("LATER!", context.getColor(R.color.instahandle), delayMs = 400),
                            Message.Starfield()
                        )
                        // A notice that was asked for from outside is still wanted: it took the
                        // place of the query's own, and comes back right behind the slides.
                        if (thinkingFromOutside) msgRepo.pushOneTimeMessage(thinkingNotice())
                        msgRepo.pushOneTimeMessages(*slides)
                    }
                    logW("AI Reply Error: ${outcome.why}")
                }

                AIQueryDesk.Outcome.Gone ->
                    logD("The query was deleted while its reply was on the way; whatever came back is dropped.")

                AIQueryDesk.Outcome.NotOurs ->
                    logW("An AI result came for a call that is not out; it is ignored.")
            }
        } finally {
            endThinkingUnlessWanted()
        }
    }

    private fun warmUpEnded() {
        desk.warmedUp(nowMs())
        endThinkingUnlessWanted()
    }

    /** Returns when the owner has made the change. */
    fun togglePersistentThinkingMessage(thinking: Boolean): Unit = changeAndWait {
        thinkingFromOutside = thinking
        if (thinking) setThinking(true) else endThinkingUnlessWanted()
    }

    /** Whether the sign should still say "thinking": for a query of the app's own within its time, or from outside. */
    private fun thinkingWanted(): Boolean = thinkingFromOutside || desk.thinkingWanted(nowMs())

    private fun endThinkingUnlessWanted() {
        if (!thinkingWanted()) setThinking(false)
    }

    private fun setThinking(thinking: Boolean) {
        // one notice at most
        msgRepo.oneTimeMessages.removeIf { it is Message.ColorMessage.AIThinking }
        if (thinking) {
//            showCaretThink = true
//            showCaretThinkDots = 0
//            msgRepo.marqueeMessages.add(currentIdx.value +1, Message.Marquee.AIThinking())
            msgRepo.pushOneTimeMessage(thinkingNotice())
        }
//        isGeneratingThought = thinking
        // Called in the middle of changes (before a reply is put in and saved): a log that
        // fails must not leave one half made.
        try {
            logD("Set generating thought to $thinking")
        } catch (e: Exception) {
            // Nowhere to report it.
        }
    }

    private fun thinkingNotice(): Message.ColorMessage.AIThinking =
        Message.ColorMessage.AIThinking(colorCycle = context.getColor(R.color.chonkyslide_defaultpink))

//    fun processPartialThought(chunk: String) {
//        if (partialThought == null) {
//            partialThought = ""
//            partialThoughtStartIdx = 0
//        }
//        partialThought += chunk.toNormalized()
//    }

    companion object {
        private const val MAX_PLAYED_TRACKS_MEMORY: Int = 4

        /** `!attn off|auto|break|zeros`: the push switch (AttentionSwitch). */
        private const val ATTENTION_COMMAND: String = "!attn"

    }
}
