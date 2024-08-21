package cx.aphex.energysign.message

import androidx.annotation.ColorInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
sealed class Message {
    abstract val str: String

    @SerialName("type")
    protected abstract val type: MSGTYPE

    companion object {
        const val VT: Char = '\u000B' //vertical tab or \v; single column
        const val HEART: Char = '\u007F' //heart char (DEL)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return str == other.str && type == other.type
    }

    override fun hashCode(): Int {
        var result = str.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

    @Serializable
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

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Marquee) return false
            return super.equals(other)
        }

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

    @Serializable
    sealed class ColorMessage : Message() {
        companion object {
            const val DEFAULT_PINK = 0xff0080

            fun red(color: Int): Int = (color shr 16) and 0xFF
            fun green(color: Int): Int = (color shr 8) and 0xFF
            fun blue(color: Int): Int = color and 0xFF
        }

        @Transient
        abstract val color: Int

        @Serializable
        abstract class SerializableColorMessage : ColorMessage() {
            abstract val r: Int
            abstract val g: Int
            abstract val b: Int
        }

        @Serializable
        abstract class SerializableColorMessageWithDelay : SerializableColorMessage() {
            @SerialName("dly")
            abstract val delayMs: Short
        }

        @Serializable
        data class OneByOneMessage(
            override val str: String,
            @Transient @ColorInt override val color: Int = DEFAULT_PINK,
            @SerialName("dly") override val delayMs: Short = 1000,
            override val type: MSGTYPE = MSGTYPE.ONE_BY_ONE
        ) : SerializableColorMessageWithDelay() {
            override val r: Int = red(color)
            override val g: Int = green(color)
            override val b: Int = blue(color)
        }

        @Serializable
        abstract class ChonkyBaseMessage : SerializableColorMessageWithDelay() {
            override val type: MSGTYPE = MSGTYPE.CHONKY_SLIDE
        }

        @Serializable
        data class ChonkySlide(
            override val str: String,
            @Transient @ColorInt override val color: Int = DEFAULT_PINK,
            @SerialName("dly") override val delayMs: Short = 1000,
            @SerialName("scroll") val shouldScrollToLastLetter: Boolean = false
        ) : ChonkyBaseMessage() {
            override val r: Int = red(color)
            override val g: Int = green(color)
            override val b: Int = blue(color)
        }

        @Serializable
        data class GPTThinking(
            override val str: String = ".",
            @Transient @ColorInt override val color: Int = DEFAULT_PINK,
            @SerialName("dly") override val delayMs: Short = 250,
            private val showCaret: Boolean = true,
            private val thinkDots: Int = 0,
        ) : ChonkyBaseMessage() {
            override val r: Int = red(color)
            override val g: Int = green(color)
            override val b: Int = blue(color)

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as GPTThinking
                return str == other.str &&
                        color == other.color &&
                        delayMs == other.delayMs &&
                        showCaret == other.showCaret &&
                        thinkDots == other.thinkDots &&
                        r == other.r &&
                        g == other.g &&
                        b == other.b
            }

            override fun hashCode(): Int {
                var result = str.hashCode()
                result = 31 * result + color
                result = 31 * result + delayMs
                result = 31 * result + showCaret.hashCode()
                result = 31 * result + thinkDots
                result = 31 * result + r
                result = 31 * result + g
                result = 31 * result + b
                return result
            }

            fun thinkMore(): GPTThinking {
                val newStr = ".".repeat(thinkDots)
                    .plus(if (showCaret) '|' else ' ')
                    .padEnd(6, ' ')

                return if (thinkDots == 6) {
                    GPTThinking(newStr, color, 150, !showCaret, thinkDots)
                } else {
                    GPTThinking(newStr, color, delayMs, showCaret = true, thinkDots + 1)
                }
            }
        }

        @Serializable
        sealed class IconInvaders(
            override val str: String,
            @Transient @ColorInt override val color: Int = DEFAULT_PINK
        ) : SerializableColorMessage() {
            override val type: MSGTYPE = MSGTYPE.ICON

            override val r: Int = red(color)
            override val g: Int = green(color)
            override val b: Int = blue(color)

            @Serializable
            class Enemy1(@ColorInt override val color: Int) : IconInvaders("1", color)

            @Serializable
            class Enemy2(@ColorInt override val color: Int) : IconInvaders("2", color)

            @Serializable
            class Explosion(@ColorInt override val color: Int) : IconInvaders("X", color)

            @Serializable
            class ANJUNA(@ColorInt override val color: Int) : IconInvaders("A", color)

            @Serializable
            class BAAAHS(@ColorInt override val color: Int) : IconInvaders("B", color)

            @Serializable
            class DREAMSTATE(@ColorInt override val color: Int) : IconInvaders("D", color)

            @Serializable
            class EDC(@ColorInt override val color: Int) : IconInvaders("E", color)
        }
    }

    @Serializable
    sealed class FlashingAnnouncement : Message() {
        override val type: MSGTYPE = MSGTYPE.FLASHY

        @Serializable
        object NowPlayingAnnouncement : FlashingAnnouncement() {
            override val str: String = "NOW${VT}PLAYING"
            val time: Short = 200
        }

        @Serializable
        class CustomFlashyAnnouncement(
            override val str: String,
            val time: Short = 200
        ) : FlashingAnnouncement()
    }

    @Serializable
    sealed class CountDownAnnouncement(override val str: String) : Message() {
        override val type: MSGTYPE = MSGTYPE.COUNTDOWN

        @Serializable
        object NewMessageAnnouncement : CountDownAnnouncement("NEW${VT}MSG$VT")
    }

    /* Messages that control admin-only device modes or settings.
 */
    @Serializable
    sealed class UtilityMessage(override val str: String, val subtype: Char) : Message() {
        override val type: MSGTYPE = MSGTYPE.UTILITY

        @Serializable
        object EnableMic : UtilityMessage("E", 'M')

        @Serializable
        object DisableMic : UtilityMessage("D", 'M')

        @Serializable
        class BrightnessShift(val amount: Int?) : UtilityMessage(amount?.toString() ?: "", 'B')
    }

    @Serializable
    class Chooser(
        val currentIndex: Int,
        val lastIndex: Int,
        val currentMessage: Marquee
    ) : Message() {
        override val str: String = currentMessage.str
        private val flashy: String = "$currentIndex/${lastIndex}"
        override val type: MSGTYPE = MSGTYPE.CHOOSER
    }

    @Serializable
    sealed class KeyboardEcho(@Transient val currentString: String = "", val mode: Char) : Message() {
        override val str: String = currentString.takeLast(19) + '_'
        override val type: MSGTYPE = MSGTYPE.KEYBOARD

        @Serializable
        data class Input(@Transient val currentInput: String = "") :
            KeyboardEcho(currentInput, 'I')

        @Serializable
        data class InputWarning(@Transient val currentInput: String = "") :
            KeyboardEcho(currentInput, 'W')
    }


    @Serializable(with = MSGTYPESerializer::class)
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
    }

    object MSGTYPESerializer : KSerializer<MSGTYPE> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MSGTYPE", PrimitiveKind.CHAR)

        override fun serialize(encoder: Encoder, value: MSGTYPE) {
            encoder.encodeChar(value.value)
        }

        override fun deserialize(decoder: Decoder): MSGTYPE {
            val char = decoder.decodeChar()
            return MSGTYPE.values().first { it.value == char }
        }
    }

//    @JsonCreator
//    @JvmStatic
//    fun findBySimpleClassName(simpleName: String): Parent? {
//        return Parent::class.sealedSubclasses.first {
//            it.simpleName == simpleName
//        }.objectInstance
//    }

    @Serializable
    sealed class MessageSerializer {
        // The actual implementation will be provided by kotlinx.serialization
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
