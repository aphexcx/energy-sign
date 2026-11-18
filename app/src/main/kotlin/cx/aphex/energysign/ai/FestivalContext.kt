package cx.aphex.energysign.ai

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * What is true right now, as text for the prompt: the local time and, from the set times, who is
 * on stage. A pure function of the clock, the zone and the table; it is built for every request.
 */
object FestivalContext {

    /**
     * A festival day runs from the afternoon into the small hours, so a set at 00:30 belongs to
     * the day before. A time before this hour counts as the previous festival day.
     */
    private const val DAY_STARTS_AT_HOUR = 6L

    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * The context block, or "" when the device clock cannot be right: no date, no time and no
     * festival block, because a wrong time is worse than none.
     *
     * [deviceZone] is used only when there is no table; a table brings its own zone.
     * [buildDate] is the UTC date this app was built on.
     */
    fun describe(now: Instant, deviceZone: ZoneId, setTimes: SetTimesLoad, buildDate: LocalDate?): String {
        val table = (setTimes as? SetTimesLoad.Loaded)?.table
        val zone = table?.zone ?: deviceZone
        if (!isClockPlausible(now, zone, table?.updated, buildDate)) return ""

        val local = LocalDateTime.ofInstant(now, zone)
        val lines = mutableListOf("Current local time: ${local.dayAndDate()}, ${local.clock()} (${zone.id}).")
        if (table != null && table.sets.isNotEmpty()) {
            lines += festivalLines(local, table)
        }
        return lines.joinToString("\n")
    }

    /**
     * The device has no battery-backed clock: for some minutes after a power cycle it reads 1969
     * or 2008. A clock earlier than the day the app was built, or earlier than the day the table
     * was last edited, is wrong. The table's date gets a day of slack, because nobody says in
     * which zone it was written.
     */
    fun isClockPlausible(now: Instant, zone: ZoneId, tableUpdated: LocalDate?, buildDate: LocalDate?): Boolean {
        if (buildDate != null && now.isBefore(buildDate.atStartOfDay(ZoneOffset.UTC).toInstant())) return false
        if (tableUpdated != null && LocalDateTime.ofInstant(now, zone).toLocalDate().isBefore(tableUpdated.minusDays(1))) return false
        return true
    }

    private fun festivalLines(now: LocalDateTime, table: SetTimesTable): List<String> {
        val name = listOf(table.event.ifBlank { "The festival" }, table.venue.takeIf { it.isNotBlank() }?.let { "($it)" })
            .filterNotNull().joinToString(" ")
        val sets = table.sets
        val firstSet = sets.minByOrNull { it.start }!!
        val lastEnd = sets.maxOf { it.end }
        val today = now.festivalDay()
        val todaysSets = sets.filter { it.start.festivalDay() == today }

        return when {
            now.isBefore(firstSet.start) -> {
                val line = "$name has not started yet. The first set is on " +
                    "${firstSet.start.dayAndDate()} at ${firstSet.start.clock()}, in ${until(now, firstSet.start)}."
                if (firstSet.start.festivalDay() == today) listOf(line) + schedule(today, todaysSets) else listOf(line)
            }

            !now.isBefore(lastEnd) ->
                listOf("$name is over: the last set ended on ${lastEnd.dayAndDate()} at ${lastEnd.clock()}.")

            todaysSets.isNotEmpty() && now.isBefore(todaysSets.minOf { it.start }) -> {
                val first = todaysSets.minOf { it.start }
                listOf("$name: today's first set is at ${first.clock()}, in ${until(now, first)}.") +
                    schedule(today, todaysSets)
            }

            todaysSets.isNotEmpty() && now.isBefore(todaysSets.maxOf { it.end }) ->
                listOf("$name is running right now.", "On stage now and next:") +
                    nowAndNext(now, todaysSets) +
                    schedule(today, todaysSets)

            else -> {
                // After the last set of this festival day, or a day without sets, with more to come.
                val next = sets.filter { it.start.isAfter(now) }.minByOrNull { it.start } ?: return emptyList()
                val before = if (todaysSets.isNotEmpty()) "${today.dayName()}'s sets are over. " else ""
                listOf(
                    "$name: ${before}The next set is on ${next.start.dayAndDate()} at ${next.start.clock()}, " +
                        "in ${until(now, next.start)}."
                )
            }
        }
    }

    /** One line a stage, in the order the stages come in the table. */
    private fun nowAndNext(now: LocalDateTime, todaysSets: List<FestivalSet>): List<String> =
        todaysSets.groupBy { it.stage }.map { (stage, stageSets) ->
            // A set is on from its start up to, not including, its end.
            val onNow = stageSets.filter { !now.isBefore(it.start) && now.isBefore(it.end) }.sortedBy { it.start }
            val later = stageSets.filter { it.start.isAfter(now) }
            val nextStart = later.minOfOrNull { it.start }
            val next = later.filter { it.start == nextStart }

            val nowPart =
                if (onNow.isEmpty()) "nobody right now"
                else onNow.joinToString(", ") { "${it.artist} until ${it.end.clock()}" }
            val nextPart =
                if (next.isEmpty()) "Nothing after that."
                else "Next: ${next.joinToString(" and ") { it.artist }} at ${nextStart!!.clock()}."
            "- $stage: $nowPart. $nextPart"
        }

    /** The whole festival day, one line a set, stage by stage in the table's order. Never trimmed. */
    private fun schedule(day: LocalDate, todaysSets: List<FestivalSet>): List<String> =
        listOf("Full schedule for ${day.dayName()} ${day.format(DATE)} (a time after midnight is early the next morning):") +
            todaysSets.groupBy { it.stage }.flatMap { (stage, stageSets) ->
                stageSets.sortedBy { it.start }.map { "$stage: ${it.start.clock()}-${it.end.clock()} ${it.artist}" }
            }

    /**
     * "9:30 pm", "12:00 am": the way the crowd says it, and the form the replies were tried with.
     * Put together by hand, so it does not depend on the locale data of the device.
     */
    private fun LocalDateTime.clock(): String =
        "${(hour + 11) % 12 + 1}:${minute.toString().padStart(2, '0')} ${if (hour < 12) "am" else "pm"}"

    private fun LocalDateTime.festivalDay(): LocalDate = minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

    private fun LocalDate.dayName(): String = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)

    private fun LocalDateTime.dayAndDate(): String = "${toLocalDate().dayName()} ${toLocalDate().format(DATE)}"

    /** "1 day 16 h 30 min", "14 h", "45 min". */
    private fun until(from: LocalDateTime, to: LocalDateTime): String {
        val minutes = Duration.between(from, to).toMinutes()
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        val parts = listOfNotNull(
            if (days > 0) "$days ${if (days == 1L) "day" else "days"}" else null,
            if (hours > 0) "$hours h" else null,
            if (mins > 0) "$mins min" else null,
        )
        return if (parts.isEmpty()) "less than a minute" else parts.joinToString(" ")
    }
}
