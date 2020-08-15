package cx.aphex.energysign

import android.graphics.Color
import androidx.annotation.ColorInt
import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type


sealed class Message {
    abstract val str: String
    protected abstract val type: MSGTYPE

    companion object {
        const val VT: Char = '\u000B' //vertical tab or \v; single column
    }

    data class UserMessage(
        override val str: String,
        override val type: MSGTYPE = MSGTYPE.DEFAULT
    ) : Message()

    data class NowPlayingTrackMessage(
        override val str: String,
        override val type: MSGTYPE = MSGTYPE.TRACKID
    ) : Message()

    sealed class ColorMessage(
        @ColorInt color: Int,
        val r: Int = Color.red(color),
        val g: Int = Color.green(color),
        val b: Int = Color.blue(color),
        @SerializedName("dly") open val delayMs: Short = 700
    ) : Message() {

        data class OneByOneMessage(
            override val str: String,
            @Transient @ColorInt val color: Int,
            override val delayMs: Short = 700,
            override val type: MSGTYPE = MSGTYPE.ONE_BY_ONE
        ) : ColorMessage(color, delayMs = delayMs)

        data class ChonkySlide(
            override val str: String,
            @Transient @ColorInt val colorCycle: Int,
            override val delayMs: Short = 700,
//        val colorFrom: Color,
//        val colorTo: Color
            override val type: MSGTYPE = MSGTYPE.CHONKY_SLIDE
        ) : ColorMessage(colorCycle, delayMs = delayMs)
    }

    sealed class FlashingAnnouncement(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.FLASHY

        object NewMessageAnnouncement : FlashingAnnouncement("NEW MSG IN")
        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
    }

    sealed class Icon(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.ICON

        object Invaders : Icon("I")
        // could have other icons here too
    }

    /* Messages that control admin-only device modes or settings.
 */
    sealed class UtilityMessage(override val str: String, val subtype: Char) : Message() {
        override val type: MSGTYPE = MSGTYPE.UTILITY

        object EnableMic : UtilityMessage("E", 'M')
        object DisableMic : UtilityMessage("D", 'M')
    }

    class Chooser(
        currentIndex: Int,
        lastIndex: Int,
        currentMessage: UserMessage
    ) : Message() {
        override val str: String = currentMessage.str
        private val flashy: String = "$currentIndex/${lastIndex}"
        override val type: MSGTYPE = MSGTYPE.CHOOSER
    }

    sealed class KeyboardEcho(currentString: String, val mode: Char) : Message() {
        override val str: String = currentString + '_'
        override val type: MSGTYPE = MSGTYPE.KEYBOARD

        data class Input(val currentInput: String) :
            KeyboardEcho(currentInput, 'I')

        data class InputWarning(val currentInput: String) :
            KeyboardEcho(currentInput, 'W')
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
        TRACKID('T'),
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

