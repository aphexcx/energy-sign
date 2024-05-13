package cx.aphex.energysign.keyboard

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.message.Message
import cx.aphex.energysign.message.MessageRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class KeyboardViewModel : ViewModel(), KoinComponent {
    private val msgRepo: MessageRepository by inject()

    val newSubmittedKeyboardMessage: MutableLiveData<String> = MutableLiveData()


    private var keyboardInputStartedAtMs: Long = 0
    private var lastKeyboardInputReceivedAtMs: Long = 0
    private var showAsWarning: Boolean = false

    private var isInKeyboardInputMode: Boolean = false

    private var keyboardStringBuilder: StringBuilder = StringBuilder()

    private fun notifyNewKeyboardString(input: String) {
        newSubmittedKeyboardMessage.postValue(input)
    }

    fun processNewKeyboardKey(key: Char) {
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
                notifyNewKeyboardString(keyboardStringBuilder.toString())
                endKeyboardInput()
            }
        }
    }

    private fun endKeyboardInput() {
        lastKeyboardInputReceivedAtMs = -1
        keyboardStringBuilder.clear()
        isInKeyboardInputMode = false
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

    fun handleKeyboardInput(): Message.KeyboardEcho? {
        if (isInKeyboardInputMode) {
            msgRepo.oneTimeMessages.clear()

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

    companion object {
        private const val MINIMUM_INPUT_ENTRY_PERIOD: Int = 5_000

        private const val KEYBOARD_INPUT_TIMEOUT_MS: Int = 30_000
        private const val KEYBOARD_INPUT_WARNING_MS: Int = 7_000
    }
}