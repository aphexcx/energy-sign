package cx.aphex.energysign

import android.graphics.Color
import androidx.annotation.ColorInt
import com.google.gson.*
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

@JsonAdapter(Message.MessageSerializer::class)
sealed class Message {
    abstract val str: String
    protected abstract val type: MSGTYPE

    companion object {
        const val VT: Char = '\u000B' //vertical tab or \v; single column
        const val HEART: Char = '\u007F' //heart char (DEL)
    }

    data class UserMessage(
        override val str: String,
        override val type: MSGTYPE = MSGTYPE.DEFAULT
    ) : Message()

    data class Starfield(
        override val str: String = "",
        val stars: Int = 300,
        override val type: MSGTYPE = MSGTYPE.STARFIELD
    ) : Message()

    data class NowPlayingTrackMessage(
        override val str: String,
        override val type: MSGTYPE = MSGTYPE.TRACKID
    ) : Message()

    sealed class ColorMessage(
        @ColorInt color: Int,
        val r: Int = Color.red(color),
        val g: Int = Color.green(color),
        val b: Int = Color.blue(color)
    ) : Message() {
        abstract val delayMs: Short

        data class OneByOneMessage(
            override val str: String,
            @Transient @ColorInt val color: Int,
            @SerializedName("dly") override val delayMs: Short = 1000,
            override val type: MSGTYPE = MSGTYPE.ONE_BY_ONE
        ) : ColorMessage(color)

        data class ChonkySlide(
            override val str: String,
            @Transient @ColorInt val colorCycle: Int,
            @SerializedName("dly") override val delayMs: Short = 1000,
//            val colorFrom: Color,
//            val colorTo: Color,
            override val type: MSGTYPE = MSGTYPE.CHONKY_SLIDE
        ) : ColorMessage(colorCycle)
    }

    sealed class FlashingAnnouncement(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.FLASHY

        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
        class CustomFlashyAnnouncement(str: String) : FlashingAnnouncement(str)
    }

    sealed class CountDownAnnouncement(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.COUNTDOWN

        object NewMessageAnnouncement : CountDownAnnouncement("NEW${VT}MSG${VT}")
    }

    sealed class IconInvaders(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.ICON

        object Enemy1 : IconInvaders("1")
        object Enemy2 : IconInvaders("2")
        object Explosion : IconInvaders("E")
        object ANJUNA : IconInvaders("A")
        object BAAAHS : IconInvaders("B")
    }

    /* Messages that control admin-only device modes or settings.
 */
    sealed class UtilityMessage(override val str: String, val subtype: Char) : Message() {
        override val type: MSGTYPE = MSGTYPE.UTILITY

        object EnableMic : UtilityMessage("E", 'M')
        object DisableMic : UtilityMessage("D", 'M')
        class BrightnessShift(amount: Int?) : UtilityMessage(amount?.toString() ?: "", 'B')
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
        override val str: String = currentString.takeLast(19) + '_'
        override val type: MSGTYPE = MSGTYPE.KEYBOARD

        data class Input(@Transient val currentInput: String) :
            KeyboardEcho(currentInput, 'I')

        data class InputWarning(@Transient val currentInput: String) :
            KeyboardEcho(currentInput, 'W')
    }


    @JsonAdapter(MSGTYPE.MsgTypeSerializer::class)
    enum class MSGTYPE(val value: Char) {
        CHONKY_SLIDE('C'),
        ONE_BY_ONE('O'),
        FLASHY('F'),
        COUNTDOWN('W'),
        STARFIELD('S'),
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
                    values().firstOrNull { it.value == json.asString.firstOrNull() } ?: DEFAULT
                } catch (e: JsonParseException) {
                    DEFAULT
                }
        }
    }

//    @JsonCreator
//    @JvmStatic
//    fun findBySimpleClassName(simpleName: String): Parent? {
//        return Parent::class.sealedSubclasses.first {
//            it.simpleName == simpleName
//        }.objectInstance
//    }

    class MessageSerializer : JsonSerializer<Message>, JsonDeserializer<Message> {
        override fun serialize(
            src: Message,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement =
            context.serialize(src)

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): Message =
            try {
                val type: MSGTYPE = MSGTYPE.MsgTypeSerializer()
                    .deserialize(json.asJsonObject["type"], Char::class.java, context)
                when (type) {
                    MSGTYPE.CHONKY_SLIDE -> context.deserialize(
                        json,
                        ColorMessage.ChonkySlide::class.java
                    )
                    MSGTYPE.ONE_BY_ONE -> context.deserialize(
                        json,
                        ColorMessage.OneByOneMessage::class.java
                    )
                    MSGTYPE.FLASHY -> context.deserialize(json, FlashingAnnouncement::class.java)
                    MSGTYPE.UTILITY -> context.deserialize(json, UtilityMessage::class.java)
                    MSGTYPE.KEYBOARD -> context.deserialize(json, KeyboardEcho::class.java)
                    MSGTYPE.CHOOSER -> context.deserialize(json, Chooser::class.java)
                    MSGTYPE.ICON -> context.deserialize(json, IconInvaders::class.java)
                    MSGTYPE.TRACKID -> context.deserialize(json, NowPlayingTrackMessage::class.java)
                    MSGTYPE.DEFAULT -> context.deserialize(json, UserMessage::class.java)
                    else -> context.deserialize(json, UserMessage::class.java)
                }
            } catch (e: JsonParseException) {
                context.deserialize(json, UserMessage::class.java)
            }
    }

}
//
//object Json {
//    val gson: Gson =
//        GsonBuilder().registerTypeAdapterFactory(
//            object : TypeAdapterFactory {
//                override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
//                    val kclass = Reflection.getOrCreateKotlinClass(type.rawType)
//                    return if (kclass.sealedSubclasses.any()) {
//                        SealedClassTypeAdapter<T>(kclass, gson)
//                    } else
//                        gson.getDelegateAdapter(this, type)
//                }
//            }).create()
//
//    inline fun <reified T> fromJson(x: String): T = this.gson.fromJson(x, T::class.java)
//
//    fun <T> fromJsonWithClass(x: String, classObj: Class<T>): T =
//        this.gson.fromJson(x, classObj)
//
//    fun <T> toJson(item: T): String = this.gson.toJson(item)
//}
//
//class SealedClassTypeAdapter<T : Any>(val kclass: KClass<Any>, val gson: Gson) : TypeAdapter<T>() {
//    override fun read(jsonReader: JsonReader): T? {
//        jsonReader.beginObject() //start reading the object
//        val nextName = jsonReader.nextName() //get the name on the object
//        val innerClass = kclass.sealedSubclasses.firstOrNull {
//            it.simpleName!!.contains(nextName)
//        }
//            ?: throw Exception("$nextName is not found to be a data class of the sealed class ${kclass.qualifiedName}")
//        val x = gson.fromJson<T>(jsonReader, innerClass.javaObjectType)
//        jsonReader.endObject()
//        //if there a static object, actually return that back to ensure equality and such!
//        return innerClass.objectInstance as T? ?: x
//    }
//
//    override fun write(out: JsonWriter, value: T) {
//        val jsonString = gson.toJson(value)
//        out.beginObject()
//        out.name(value.javaClass.canonicalName?.splitToSequence(".")?.last() ?: "").jsonValue(jsonString)
//        out.endObject()
//    }
//
//}
