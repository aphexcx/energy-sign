package cx.aphex.energysign.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** One set. [start] and [end] are wall-clock times in the table's zone, with the real calendar date. */
data class FestivalSet(val stage: String, val artist: String, val start: LocalDateTime, val end: LocalDateTime)

data class SetTimesTable(
    val event: String,
    val venue: String,
    val zone: ZoneId,
    /** The day the table was last edited; a device clock before it is not believed. */
    val updated: LocalDate?,
    val sets: List<FestivalSet>,
)

sealed class SetTimesLoad {
    data class Loaded(val table: SetTimesTable, val source: String) : SetTimesLoad()

    /** No table: the prompt gets no festival block. [why] is for the log. */
    data class Unusable(val why: String) : SetTimesLoad()
}

/**
 * Reads the set times:
 * `{"event", "venue", "timezone", "updated", "sets": [{"stage", "artist", "start", "end"}]}`.
 *
 * Nothing here throws: a missing, empty or broken file is [SetTimesLoad.Unusable]. A single set
 * that cannot be read is left out and the rest of the table is kept.
 */
object SetTimes {
    /** The file the app looks for in its own files directory, and the name of the bundled asset. */
    const val FILE_NAME = "set_times.json"

    /** The bundled table is about 10 kB. */
    private const val MAX_FILE_BYTES = 512L * 1024

    /**
     * The table to use now: [deviceFile] when it is there and can be read, so the schedule can be
     * replaced on the device without a rebuild; otherwise the bundled one.
     */
    fun load(deviceFile: File, bundled: () -> String?): SetTimesLoad {
        val fromDevice = readDeviceFile(deviceFile)
        if (fromDevice is SetTimesLoad.Loaded) return fromDevice

        val bundledText = try {
            bundled()
        } catch (e: IOException) {
            return SetTimesLoad.Unusable("${fromDevice.why()}; bundled $FILE_NAME cannot be read (${e.javaClass.simpleName})")
        }
        if (bundledText == null) {
            return SetTimesLoad.Unusable("${fromDevice.why()}; there is no bundled $FILE_NAME")
        }
        return when (val fromBundle = parse(bundledText, "bundled $FILE_NAME")) {
            is SetTimesLoad.Loaded -> fromBundle
            is SetTimesLoad.Unusable -> SetTimesLoad.Unusable("${fromDevice.why()}; ${fromBundle.why}")
        }
    }

    private fun SetTimesLoad.why(): String = (this as? SetTimesLoad.Unusable)?.why.orEmpty()

    private fun readDeviceFile(file: File): SetTimesLoad {
        val source = "files/${file.name}"
        if (!file.isFile) return SetTimesLoad.Unusable("no $source")
        // A schedule is some kilobytes. A file far bigger than that is not one, and reading it
        // would hold up the reply that waits for this.
        if (file.length() > MAX_FILE_BYTES) return SetTimesLoad.Unusable("$source is too big to be a schedule")
        val text = try {
            file.readText()
        } catch (e: IOException) {
            return SetTimesLoad.Unusable("$source cannot be read (${e.javaClass.simpleName})")
        }
        return parse(text, source)
    }

    fun parse(json: String, source: String): SetTimesLoad {
        if (json.isBlank()) return SetTimesLoad.Unusable("$source is empty")
        val parsed: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            return SetTimesLoad.Unusable("$source is not JSON")
        }
        val root: JsonObject = parsed.takeIf { it.isJsonObject }?.asJsonObject
            ?: return SetTimesLoad.Unusable("$source is not a JSON object")

        val zoneName = root.string("timezone") ?: return SetTimesLoad.Unusable("$source names no timezone")
        val zone = try {
            ZoneId.of(zoneName)
        } catch (e: DateTimeException) {
            return SetTimesLoad.Unusable("$source names a timezone that does not exist")
        }

        val setsJson = root.get("sets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return SetTimesLoad.Unusable("$source has no list of sets")
        val sets = setsJson.mapNotNull { it.toSetOrNull() }
        if (sets.isEmpty()) return SetTimesLoad.Unusable("$source has no set that can be read")

        return SetTimesLoad.Loaded(
            SetTimesTable(
                event = root.string("event").orEmpty(),
                venue = root.string("venue").orEmpty(),
                zone = zone,
                updated = root.string("updated")?.let { parseOrNull { LocalDate.parse(it) } },
                sets = sets,
            ),
            source = if (sets.size == setsJson.size()) source else "$source (${setsJson.size() - sets.size} unreadable sets left out)",
        )
    }

    private fun JsonElement.toSetOrNull(): FestivalSet? {
        val set = takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val stage = set.string("stage")?.takeIf { it.isNotBlank() } ?: return null
        val artist = set.string("artist")?.takeIf { it.isNotBlank() } ?: return null
        val start = set.string("start")?.let { parseOrNull { LocalDateTime.parse(it) } } ?: return null
        val end = set.string("end")?.let { parseOrNull { LocalDateTime.parse(it) } } ?: return null
        if (!end.isAfter(start)) return null
        return FestivalSet(stage.trim(), artist.trim(), start, end)
    }

    /** The string under [name], or null when it is absent or not a string. */
    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private inline fun <T> parseOrNull(parse: () -> T): T? =
        try {
            parse()
        } catch (e: DateTimeParseException) {
            null
        }
}
