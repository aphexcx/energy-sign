package cx.aphex.energysign.keyboard

import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/*
 * THREADS. The input belongs to the thread that owns the message state (see OwnerQueue). Keys
 * arrive on the main thread: each key function posts its change to the owner and returns. The
 * owner alone reads the echo (handleKeyboardInput, from MessageManager.getNextMessage).
 */
object KeyboardManager : KoinComponent {
    private val msgRepo: MessageRepository by inject()
    private val owner: OwnerQueue by inject()

    /**
     * Gets the text of a message that was submitted with Enter. Called on the owner thread, in
     * the same step that took the text and ended the input, so nothing can clear it in between.
     */
    @Volatile
    var onSubmitted: (String) -> Unit = {}


    /**
     * EVERY KEY THE JUMP GAME MAY TAKE goes through here: the up arrow ([UP_ARROW], which types
     * nothing and starts no input) and every key that would be typed, before it is. Asked on
     * the owner thread with the key, whether it is a repeat of a key held down, and when the
     * press arrived ([clock], read on the main thread); true when the game took the key for
     * itself, and then nothing is typed. Whoever owns the game sets both.
     */
    @Volatile
    var takenByTheGame: (key: Char, repeated: Boolean, arrivedAt: Long) -> Boolean = { _, _, _ -> false }

    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** The up arrow, the button of the jump game. Called on the main thread, like every key. */
    fun upArrow(repeated: Boolean = false) {
        val arrived = clock()
        owner.post { takenByTheGame(UP_ARROW, repeated, arrived) }
    }

    /** Whether somebody is typing (the echo is on the sign). Owner thread only. */
    val typing: Boolean get() = isInKeyboardInputMode

    private var keyboardInputStartedAtMs: Long = 0
    private var lastKeyboardInputReceivedAtMs: Long = 0
    private var showAsWarning: Boolean = false

    private var isInKeyboardInputMode: Boolean = false
    private var isMessageSubmitted: Boolean = false

    private var keyboardStringBuilder: StringBuilder = StringBuilder()

    fun processNewKeyboardKey(key: Char, repeated: Boolean = false) {
        val arrived = clock()
        owner.post { typed(key, repeated, arrived) }
    }

    private fun typed(key: Char, repeated: Boolean, arrived: Long) {
        //TODO key.toInt() needed here?
        if (key.code == 0) {
            logD("Invalid key!")
            return
        }
        if (takenByTheGame(key, repeated, arrived)) return
        isInKeyboardInputMode = true
        isMessageSubmitted = false
        msgRepo.oneTimeMessages.clear()
        if (keyboardStringBuilder.isEmpty()) {
            keyboardInputStartedAtMs = System.currentTimeMillis()
        }
        lastKeyboardInputReceivedAtMs = System.currentTimeMillis()
        keyboardStringBuilder.append(key)
    }

    fun submitKeyboardInput(): Unit = owner.post {
        val totalKeyboardInputTimeElapsed = System.currentTimeMillis() - keyboardInputStartedAtMs
//        if (keyboardStringBuilder.length < 4) {
//            // LONGER!\
//
//
//        } else if (keyboardStringBuilder.toString().split(' ').any { it in Dictionary.ENGLISH_DICT })
        // else if ! any words are in dict
        // NO SPAM!

        if (keyboardStringBuilder.isBlank() || isMessageSubmitted) {
            endKeyboardInput()
        } else {
            if (totalKeyboardInputTimeElapsed > MINIMUM_INPUT_ENTRY_PERIOD) {
                val newSubmission = keyboardStringBuilder.toString()
                isMessageSubmitted = true
                endKeyboardInput()
                onSubmitted(newSubmission)
            }
        }
    }

    private fun endKeyboardInput() {
        lastKeyboardInputReceivedAtMs = -1
        keyboardStringBuilder.clear()
        isInKeyboardInputMode = false
    }

    fun deleteKey(): Unit = owner.post {
        if (keyboardStringBuilder.isNotEmpty()) {
            lastKeyboardInputReceivedAtMs = System.currentTimeMillis()
            keyboardStringBuilder.deleteCharAt(keyboardStringBuilder.lastIndex)
        }
    }


    fun escapeKey(): Unit = owner.post {
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

    /** The echo of what is being typed, null when nothing is. Owner thread only. */
    fun handleKeyboardInput(): Message.KeyboardEcho? {
        if (isInKeyboardInputMode) {

            val msSinceLastInput = System.currentTimeMillis() - lastKeyboardInputReceivedAtMs

            showAsWarning =
                (msSinceLastInput > (KEYBOARD_INPUT_TIMEOUT_MS - KEYBOARD_INPUT_WARNING_MS))

            if (msSinceLastInput > KEYBOARD_INPUT_TIMEOUT_MS) {
                endKeyboardInput()
                msgRepo.pushOneTimeMessage(Message.Starfield())
            }

            return if (msSinceLastInput < KEYBOARD_INPUT_TIMEOUT_MS) {
                if (showAsWarning) {
                    // Running out of input time! Display this in input warning mode.
                    Message.KeyboardEcho.InputWarning(keyboardStringBuilder.toString())
                } else {
                    Message.KeyboardEcho.Input(keyboardStringBuilder.toString())
                }
            } else {
                null
            }
        } else {
            return null
        }
    }

    /** The up arrow as a key for [takenByTheGame]. */
    const val UP_ARROW: Char = '\u2191'

    private const val MINIMUM_INPUT_ENTRY_PERIOD: Int = 5_000

    private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
    private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000

}
