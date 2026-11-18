package cx.aphex.energysign.message

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import cx.aphex.energysign.message.Message.NowPlayingTrackMessage

/** The saved ad list cannot be turned back into ads. The message says which ad and why. */
class AdFileException(message: String) : Exception(message)

/**
 * The ad list as text and back: the format of `ads.json`.
 *
 * The file is a JSON array with one object per ad, each exactly the JSON the app sends to the
 * sign for that ad (`type` C, O, I or T; `str`; `r`, `g`, `b`; `dly`; `scroll`). There is no
 * other field, so a file written by an older version of the app is read as it is.
 *
 * Reading is strict: a list that holds anything this cannot rebuild is refused whole, with the
 * reason, and never read as a shorter or different list.
 */
object AdCodec {
    private val gson = Gson()

    /** The `dly` of an ad whose JSON has none: what an ad line without a clock gets. */
    private const val DELAY_WHEN_MISSING_MS: Short = 1000

    fun encode(ads: List<Message>): String = gson.toJson(ads)

    /** @throws AdFileException when [json] is not a list of ads. */
    fun decode(json: String): List<Message> {
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: RuntimeException) {
            throw AdFileException("not JSON: ${e.message}")
        }
        if (!root.isJsonArray) throw AdFileException("not a JSON list")

        return root.asJsonArray.mapIndexed { index, element ->
            try {
                if (!element.isJsonObject) throw AdFileException("not a JSON object")
                adFrom(element.asJsonObject)
            } catch (e: AdFileException) {
                throw AdFileException("ad ${index + 1}: ${e.message}")
            } catch (e: RuntimeException) {
                // Gson's own complaints about a value of the wrong kind (a text where a number belongs).
                throw AdFileException("ad ${index + 1}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Ads are built with their constructors (Gson would create them without one, leaving the
     * colour and every missing key at zero).
     */
    private fun adFrom(ad: JsonObject): Message =
        when (val type = ad.text("type")) {
            "C" -> ChonkySlide(
                str = ad.text("str"),
                colorCycle = ad.colour(),
                delayMs = ad.delay(),
                shouldScrollToLastLetter = ad["scroll"]?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
            )

            "O" -> OneByOneMessage(str = ad.text("str"), color = ad.colour(), delayMs = ad.delay())

            "T" -> NowPlayingTrackMessage(ad.text("str"))

            "I" -> {
                val colour = ad.colour()
                when (val icon = ad.text("str")) {
                    "1" -> IconInvaders.Enemy1(colour)
                    "2" -> IconInvaders.Enemy2(colour)
                    "X" -> IconInvaders.Explosion(colour)
                    "A" -> IconInvaders.ANJUNA(colour)
                    "B" -> IconInvaders.BAAAHS(colour)
                    "D" -> IconInvaders.DREAMSTATE(colour)
                    "E" -> IconInvaders.EDC(colour)
                    else -> throw AdFileException("unknown icon \"$icon\"")
                }
            }

            else -> throw AdFileException("unknown type \"$type\"")
        }

    private fun JsonObject.text(key: String): String {
        val value = this[key]
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw AdFileException("\"$key\" is missing or not a text")
        }
        return value.asString
    }

    private fun JsonObject.colour(): Int = (channel("r") shl 16) or (channel("g") shl 8) or channel("b")

    private fun JsonObject.channel(key: String): Int {
        val value = wholeNumber(key) ?: throw AdFileException("\"$key\" is missing")
        if (value !in 0..255) throw AdFileException("\"$key\" is $value, not 0 to 255")
        return value.toInt()
    }

    private fun JsonObject.delay(): Short {
        val value = wholeNumber("dly") ?: return DELAY_WHEN_MISSING_MS
        if (value !in Short.MIN_VALUE..Short.MAX_VALUE) throw AdFileException("\"dly\" is $value, too large")
        return value.toShort()
    }

    /** The whole number under [key], null when the key is absent. */
    private fun JsonObject.wholeNumber(key: String): Long? {
        val value = this[key]?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw AdFileException("\"$key\" is not a number")
        // BigDecimal's own longValueExact: BigInteger's is not there before Android 12 (API 31).
        return value.asBigDecimal.longValueExact()
    }
}
