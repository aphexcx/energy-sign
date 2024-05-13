package cx.aphex.energysign.message

import android.graphics.Color
import androidx.annotation.ColorInt
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable
import java.lang.reflect.Type

@Serializable
sealed class Message {
    abstract val str: String
    protected abstract val type: MSGTYPE

    companion object {
        const val VT: Char = '\u000B' //vertical tab or \v; single column
        const val HEART: Char = '\u007F' //heart char (DEL)
    }

    sealed class Marquee : Message() {

        @Serializable
        class User(
            override val str: String,
            override val type: MSGTYPE = MSGTYPE.DEFAULT
        ) : Marquee()

        @Serializable
        class GPTQuery(
            override val str: String,
            override val type: MSGTYPE = MSGTYPE.DEFAULT
        ) : Marquee() {
            fun toUserMessage(): User = User(str)
        }

        @Serializable
        class Chonky(
            override val str: String,
            override val type: MSGTYPE = MSGTYPE.CHONKYMARQUEE
        ) : Marquee()

        @Serializable
        class GPTReply(
            override val str: String,
            override val type: MSGTYPE = MSGTYPE.CHONKYMARQUEE
        ) : Marquee() {
            fun toChonkyMessage(): Chonky = Chonky(str)
        }

        override fun toString(): String = "$type$VT$str"

        companion object {
            inline fun <reified T : Marquee> fromString(s: String): T {
                val parts = s.split("$VT")
                val type = MSGTYPE.valueOf(parts[0])
                val str = parts[1]
                return when (T::class) {
                    User::class -> User(str, type)
                    GPTQuery::class -> GPTQuery(str, type)
                    Chonky::class -> Chonky(str, type)
                    else -> throw IllegalArgumentException("Unknown Marquee subclass")
                } as T
            }
        }
    }

    @Serializable
    data class Starfield(
        override val str: String = "",
        val stars: Short = 300,
        override val type: MSGTYPE = MSGTYPE.STARFIELD
    ) : Message()

    @Serializable
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

        data class OneByOneMessage(
            override val str: String,
            @Transient @ColorInt val color: Int,
            @SerializedName("dly") val delayMs: Short = 1000,
            override val type: MSGTYPE = MSGTYPE.ONE_BY_ONE
        ) : ColorMessage(color)

        data class ChonkySlide(
            override val str: String,
            @Transient @ColorInt val colorCycle: Int,
            @SerializedName("dly") val delayMs: Int = 1000,
//            val colorFrom: Color,
//            val colorTo: Color,
            override val type: MSGTYPE = MSGTYPE.CHONKY_SLIDE,
            @SerializedName("scroll") val shouldScrollToLastLetter: Boolean = false
        ) : ColorMessage(colorCycle)

        sealed class IconInvaders(
            override val str: String,
            @ColorInt color: Int
        ) : ColorMessage(color) {
            override val type: MSGTYPE = MSGTYPE.ICON

            class Enemy1(color: Int) : IconInvaders("1", color)
            class Enemy2(color: Int) : IconInvaders("2", color)
            class Explosion(color: Int) : IconInvaders("X", color)
            class ANJUNA(color: Int) : IconInvaders("A", color)
            class BAAAHS(color: Int) : IconInvaders("B", color)
            class DREAMSTATE(color: Int) : IconInvaders("D", color)
            class EDC(color: Int) : IconInvaders("E", color)
        }
    }

    sealed class FlashingAnnouncement(override val str: String, val time: Short = 200) : Message() {
        override val type: MSGTYPE = MSGTYPE.FLASHY

        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
        class CustomFlashyAnnouncement(str: String, time: Short = 200) : FlashingAnnouncement(str, time)
    }

    sealed class CountDownAnnouncement(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.COUNTDOWN

        object NewMessageAnnouncement : CountDownAnnouncement("NEW${VT}MSG$VT")
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
        currentMessage: Marquee
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
        CHONKYMARQUEE('N'),
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
        ): JsonElement {
            return when (src) {
//                is KeyboardEcho -> {
//                    JsonPrimitive(src.str)
//                }
                else -> context.serialize(src)
            }
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): Message {
            return try {
                val type: MSGTYPE =
                    MSGTYPE.MsgTypeSerializer().deserialize(json.asJsonObject["type"], Char::class.java, context)
                when (type) {
                    MSGTYPE.CHONKY_SLIDE -> context.deserialize(json, ColorMessage.ChonkySlide::class.java) as Message
                    MSGTYPE.ONE_BY_ONE -> context.deserialize(json, ColorMessage.OneByOneMessage::class.java) as Message
                    MSGTYPE.FLASHY -> context.deserialize(json, FlashingAnnouncement::class.java)
                    MSGTYPE.COUNTDOWN -> context.deserialize(json, CountDownAnnouncement::class.java)
                    MSGTYPE.UTILITY -> context.deserialize(json, UtilityMessage::class.java)
                    MSGTYPE.CHOOSER -> context.deserialize(json, Chooser::class.java)
                    MSGTYPE.KEYBOARD -> context.deserialize(json, KeyboardEcho::class.java)
                    MSGTYPE.STARFIELD -> context.deserialize(json, Starfield::class.java)
                    MSGTYPE.TRACKID -> context.deserialize(json, NowPlayingTrackMessage::class.java)
                    MSGTYPE.CHONKYMARQUEE -> context.deserialize(json, Marquee.Chonky::class.java)
                    MSGTYPE.ICON -> {
                        when (json.asJsonObject["str"].asString) {
                            "1" -> context.deserialize(json, ColorMessage.IconInvaders.Enemy1::class.java)
                            "2" -> context.deserialize(json, ColorMessage.IconInvaders.Enemy2::class.java)
                            "X" -> context.deserialize(json, ColorMessage.IconInvaders.Explosion::class.java)
                            "A" -> context.deserialize(json, ColorMessage.IconInvaders.ANJUNA::class.java)
                            "B" -> context.deserialize(json, ColorMessage.IconInvaders.BAAAHS::class.java)
                            "D" -> context.deserialize(json, ColorMessage.IconInvaders.DREAMSTATE::class.java)
                            "E" -> context.deserialize(json, ColorMessage.IconInvaders.EDC::class.java)
                            else -> throw JsonParseException("Unknown IconInvader: ${json.asJsonObject["str"].asString}")
                        }
                    }

                    MSGTYPE.DEFAULT -> context.deserialize(json, Marquee.User::class.java)
                }
            } catch (e: JsonParseException) {
                context.deserialize(json, Marquee.User::class.java)
            }
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
