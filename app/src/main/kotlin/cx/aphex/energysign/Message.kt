package cx.aphex.energysign

import android.graphics.Color
import androidx.annotation.ColorInt
import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type


sealed class Message(open val str: String, val type: MSGTYPE) {

    class UserMessage(str: String) : Message(str, MSGTYPE.DEFAULT)
    class NowPlayingTrackMessage(str: String) : Message(str, MSGTYPE.DEFAULT)

    abstract class ColorMessage(
        str: String,
        @ColorInt color: Int,
        val r: Int = Color.red(color),
        val g: Int = Color.green(color),
        val b: Int = Color.blue(color),
        @SerializedName("dly") val delayMs: Short = 500,
        type: MSGTYPE
    ) : Message(str, type)

    class OneByOneMessage(
        str: String,
        @ColorInt color: Int,
        delayMs: Short = 500
    ) : ColorMessage(str, color, delayMs = delayMs, type = MSGTYPE.ONE_BY_ONE)

    class ChonkySlide(
        str: String,
        @ColorInt colorCycle: Int,
        delayMs: Short = 500
//        val colorFrom: Color,
//        val colorTo: Color
    ) : ColorMessage(str, colorCycle, delayMs = delayMs, type = MSGTYPE.CHONKY_SLIDE)

    sealed class FlashingAnnouncement(str: String) : Message(str, MSGTYPE.FLASHY) {

        object NewMessageAnnouncement : FlashingAnnouncement("NEW MSG IN")
        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
    }

    sealed class Icon(str: String) : Message(str, MSGTYPE.ICON) {

        object Invaders : Icon("I")
        // could have other icons here too
    }

    /* Messages that control admin-only device modes or settings.
     */
    sealed class UtilityMessage(str: String, val subtype: Char) : Message(str, MSGTYPE.UTILITY) {
        object EnableMic : UtilityMessage("E", 'M')
        object DisableMic : UtilityMessage("D", 'M')
    }

    class Chooser(
        currentIndex: Int,
        lastIndex: Int,
        currentMessage: UserMessage
    ) : Message(currentMessage.str, MSGTYPE.CHOOSER) {
        private val flashy: String = "$currentIndex/${lastIndex}"
    }

    sealed class KeyboardEcho(currentString: String, val mode: Char) :
        Message(currentString + '_', MSGTYPE.KEYBOARD) {
        data class Input(val currentInput: String) :
            KeyboardEcho(currentInput, 'I')

        data class InputWarning(val currentInput: String) :
            KeyboardEcho(currentInput, 'W')
    }

    companion object {
        const val VT: Char = '\u000B' //vertical tab or \v; single column
    }

    @JsonAdapter(MSGTYPE.MsgTypeSerializer::class)
    enum class MSGTYPE(val value: Char) {
        CHONKY_SLIDE('C'),
        ONE_BY_ONE('O'),
        FLASHY('F'),
        UTILITY('U'),
        KEYBOARD('K'),
        CHOOSER('H'),
        ICON('I'),
        DEFAULT('D');

        class MsgTypeSerializer : JsonSerializer<MSGTYPE>, JsonDeserializer<MSGTYPE> {
            override fun serialize(
                src: MSGTYPE,
                typeOfSrc: Type,
                context: JsonSerializationContext
            ): JsonElement =
                context.serialize(src.value)

            override fun deserialize(
                json: JsonElement,
                typeOfT: Type,
                context: JsonDeserializationContext
            ): MSGTYPE =
                try {
                    valueOf(json.asString)
                } catch (e: JsonParseException) {
                    DEFAULT
                }
        }
    }
}


