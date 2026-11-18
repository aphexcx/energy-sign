package cx.aphex.energysign.message

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import cx.aphex.energysign.message.Message.NowPlayingTrackMessage
import java.io.StringReader

/** The saved ad list cannot be turned back into ads. The message says which ad and why. */
class AdFileException(message: String) : Exception(message)

/**
 * The ad list as text and back: the format of `ads.json`.
 *
 * The file is a JSON array with one object per ad, each exactly the JSON the app sends to the
 * sign for that ad (`type` C, O, I or T; `str`; `r`, `g`, `b`; `dly`; `scroll`). There is no
 * other field, so a file written by an older version of the app is read too.
 *
 * Reading has two halves:
 *
 *  - The SHAPE is strict. A document that is not strict JSON, has anything after its list, or
 *    holds an ad this cannot rebuild (an unknown `type` or icon, a value of the wrong kind, a
 *    colour that is not 0 to 255) is refused whole, with the reason. It is never read as a
 *    shorter or different list.
 *  - The VALUES of an ad that can be rebuilt are held to the rules of an ad line ([AdRules],
 *    [AdSizeLimit]): its text, its delay and the size of its JSON. An older version of the app
 *    saved ads without these rules. Such an ad is corrected, or left out when it has no text,
 *    and the rest of the list is kept; each correction is reported. A `<3` that was saved stays
 *    a `<3`: the heart is made when the line is sent.
 */
object AdCodec {
    private val gson = Gson()

    fun encode(ads: List<Message>): String = gson.toJson(ads)

    /**
     * The ads in [json]. [note] gets one line for every ad that had to be corrected to fit the
     * rules.
     *
     * @throws AdFileException when [json] is not a list of ads.
     */
    fun decode(json: String, note: (String) -> Unit = {}): List<Message> {
        val root = parseWhole(json)
        if (!root.isJsonArray) throw AdFileException("not a JSON list")

        return root.asJsonArray.mapIndexedNotNull { index, element ->
            val place = "ad ${index + 1}"
            try {
                if (!element.isJsonObject) throw AdFileException("not a JSON object")
                adFrom(element.asJsonObject) { note("$place: $it") }
            } catch (e: AdFileException) {
                throw AdFileException("$place: ${e.message}")
            } catch (e: RuntimeException) {
                // Gson's own complaints about a value of the wrong kind
                throw AdFileException("$place: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** [json] as one strict JSON value with nothing but white space after it. */
    private fun parseWhole(json: String): JsonElement =
        try {
            val reader = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
            val root = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw AdFileException("something follows the JSON")
            root
        } catch (e: AdFileException) {
            throw e
        } catch (e: Exception) {
            // (a Gson syntax error, or the IOException its reader has for the same)
            throw AdFileException("not JSON: ${e.message}")
        }

    /**
     * Ads are built with their constructors (Gson would create them without one, leaving the
     * colour and every missing key at zero).
     */
    private fun adFrom(ad: JsonObject, note: (String) -> Unit): Message? =
        when (val type = ad.text("type")) {
            "C" -> {
                val colour = ad.colour()
                val delay = ad.delay(note)
                val scroll = ad.flag("scroll")
                AdRules.textAd(ad.adText(note), note) { text -> ChonkySlide(text, colour, delay, shouldScrollToLastLetter = scroll) }
            }

            "O" -> {
                val colour = ad.colour()
                val delay = ad.delay(note)
                AdRules.textAd(ad.adText(note), note) { text -> OneByOneMessage(text, colour, delay) }
            }

            // The place of the now-playing track. An ad line gives it no text and none is ever
            // sent from here (the track is put in its place), so a saved text is not kept.
            "T" -> NowPlayingTrackMessage("")

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

    /** The `str` of a chonky or one-by-one ad, held to [AdRules.text]. */
    private fun JsonObject.adText(note: (String) -> Unit): String {
        val saved = text("str")
        val text = AdRules.text(saved)
        if (text != saved) note("its text held characters the sign cannot show; they were replaced or taken out")
        return text
    }

    private fun JsonObject.colour(): Int = (channel("r") shl 16) or (channel("g") shl 8) or channel("b")

    private fun JsonObject.channel(key: String): Int {
        val value = wholeNumber(key) ?: throw AdFileException("\"$key\" is missing")
        if (value !in 0..255) throw AdFileException("\"$key\" is $value, not 0 to 255")
        return value.toInt()
    }

    /** The `dly`, held to [AdRules.delay]. An ad without one stays a second, like an ad line without a clock. */
    private fun JsonObject.delay(note: (String) -> Unit): Short {
        val saved = wholeNumber("dly") ?: return AdRules.MS_PER_SECOND.toShort()
        val delay = AdRules.delay(saved)
        if (delay.toLong() != saved) note("\"dly\" was $saved, it is $delay now")
        return delay
    }

    /** True or false under [key]; false when the key is absent, as for an ad line. */
    private fun JsonObject.flag(key: String): Boolean {
        val value = this[key] ?: return false
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw AdFileException("\"$key\" is not true or false")
        return value.asBoolean
    }

    /** The whole number under [key], null when the key is absent. */
    private fun JsonObject.wholeNumber(key: String): Long? {
        val value = this[key] ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw AdFileException("\"$key\" is not a number")
        try {
            // BigDecimal's own longValueExact: BigInteger's is not there before Android 12 (API 31).
            return value.asBigDecimal.longValueExact()
        } catch (e: ArithmeticException) {
            throw AdFileException("\"$key\" is ${value.asString}, not a whole number")
        }
    }
}
