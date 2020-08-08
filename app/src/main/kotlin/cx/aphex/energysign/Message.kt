package cx.aphex.energysign

import android.graphics.Color
import androidx.annotation.ColorInt

sealed class Message(open val string: String, val type: Char) {

    class UserMessage(string: String) : Message(string, MSGTYPE_DEFAULT)

    abstract class ColorMessage(
        string: String,
        @ColorInt color: Int,
        val r: Int = Color.red(color),
        val g: Int = Color.green(color),
        val b: Int = Color.blue(color),
        type: Char
    ) : Message(string, type)

    class OneByOneMessage(
        string: String,
        @ColorInt color: Int,
        val delayMs: Short = 500
    ) : ColorMessage(string, color, type = MSGTYPE_ONE_BY_ONE)

    class ChonkySlide(
        string: String,
        @ColorInt colorCycle: Int,
        val delayMs: Short = 500
//        val colorFrom: Color,
//        val colorTo: Color
    ) : ColorMessage(string, colorCycle, type = MSGTYPE_CHONKY_SLIDE)

    sealed class FlashingAnnouncement(string: String) :
        Message(string, MSGTYPE_FLASHY) {

        object NewMessageAnnouncement : FlashingAnnouncement("NEW MSG IN")
        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
    }

    /* Messages that control admin-only device modes or settings.
     */
    sealed class UtilityMessage(string: String, val subtype: Char) :
        Message(string, MSGTYPE_UTILITY) {
        object EnableMic : UtilityMessage("E", 'M')
        object DisableMic : UtilityMessage("D", 'M')
    }

    class Chooser(
        currentIndex: Int,
        lastIndex: Int,
        currentMessage: UserMessage
    ) : Message(currentMessage.string, MSGTYPE_CHOOSER) {
        private val flashy: String = "$currentIndex/${lastIndex}"
    }

    sealed class KeyboardEcho(currentString: String, val mode: Char) :
        Message(currentString + '_', MSGTYPE_KEYBOARD) {
        data class Input(val currentInput: String) :
            KeyboardEcho(currentInput, 'I')

        data class InputWarning(val currentInput: String) :
            KeyboardEcho(currentInput, 'W')
    }

    companion object {
        const val VT: Byte = 11 //vertical tab; single column
        const val MSGTYPE_CHONKY_SLIDE: Char = 'C'
        const val MSGTYPE_ONE_BY_ONE: Char = 'O'
        const val MSGTYPE_FLASHY: Char = 'F'
        const val MSGTYPE_UTILITY: Char = 'U'
        const val MSGTYPE_KEYBOARD: Char = 'K'
        const val MSGTYPE_CHOOSER: Char = 'H'
        const val MSGTYPE_DEFAULT: Char = 'D'
    }
}
