package cx.aphex.energysign

import android.graphics.Color
import androidx.annotation.ColorInt
import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import java.lang.reflect.Type


sealed class Message(open val string: String, val type: MSGTYPE) {

    class UserMessage(string: String) : Message(string, MSGTYPE.DEFAULT)

    abstract class ColorMessage(
        string: String,
        @ColorInt color: Int,
        val r: Int = Color.red(color),
        val g: Int = Color.green(color),
        val b: Int = Color.blue(color),
        type: MSGTYPE
    ) : Message(string, type)

    class OneByOneMessage(
        string: String,
        @ColorInt color: Int,
        val delayMs: Short = 500
    ) : ColorMessage(string, color, type = MSGTYPE.ONE_BY_ONE)

    class ChonkySlide(
        string: String,
        @ColorInt colorCycle: Int,
        val delayMs: Short = 500
//        val colorFrom: Color,
//        val colorTo: Color
    ) : ColorMessage(string, colorCycle, type = MSGTYPE.CHONKY_SLIDE)

    sealed class FlashingAnnouncement(string: String) :
        Message(string, MSGTYPE.FLASHY) {

        object NewMessageAnnouncement : FlashingAnnouncement("NEW MSG IN")
        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
    }

    sealed class Icon(string: String) :
        Message(string, MSGTYPE.ICON) {

        object Invaders : Icon("I")
        // could have other icons here too
    }

    /* Messages that control admin-only device modes or settings.
     */
    sealed class UtilityMessage(string: String, val subtype: Char) :
        Message(string, MSGTYPE.UTILITY) {
        object EnableMic : UtilityMessage("E", 'M')
        object DisableMic : UtilityMessage("D", 'M')
    }

    class Chooser(
        currentIndex: Int,
        lastIndex: Int,
        currentMessage: UserMessage
    ) : Message(currentMessage.string, MSGTYPE.CHOOSER) {
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
        const val VT: Byte = 11 //vertical tab; single column


    }
}

@JsonAdapter(MSGTYPESerializer::class)
enum class MSGTYPE(val value: Char) {
    CHONKY_SLIDE('C'),
    ONE_BY_ONE('O'),
    FLASHY('F'),
    UTILITY('U'),
    KEYBOARD('K'),
    CHOOSER('H'),
    ICON('I'),
    DEFAULT('D');


    override fun toString(): String {
        return value.toString()
    }


}

class MSGTYPESerializer : JsonSerializer<MSGTYPE>, JsonDeserializer<MSGTYPE> {
    override fun serialize(
        src: MSGTYPE,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        return context.serialize(src.value)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): MSGTYPE {
        return try {
            MSGTYPE.valueOf(json.asString)
        } catch (e: JsonParseException) {
            MSGTYPE.DEFAULT
        }
    }
}
