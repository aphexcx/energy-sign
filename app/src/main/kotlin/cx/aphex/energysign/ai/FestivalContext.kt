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
 * on stage. A pure function of the time, the zone and the table; it is built for every request.
 *
 * Everything is worked out on INSTANTS. The table gives wall-clock times in the festival's zone;
 * each is turned into the instant it means there (see [resolve]) before anything is compared, so
 * "who is on" and "in how long" stay right across midnight and across a change of the clocks.
 * Who is on now and who is next come from the WHOLE table. The festival day is used for one
 * thing only: which sets are printed as today's schedule.
 */
object FestivalContext {

    /**
     * A festival day runs from the afternoon into the small hours, so a set at 00:30 is printed
     * with the day before. A time before this hour counts as the previous festival day.
     */
    private const val DAY_STARTS_AT_HOUR = 6L

    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** A set with its times as instants. */
    private class Timed(val set: FestivalSet, val start: Instant, val end: Instant)

    /**
     * The context block, or "" when [now] cannot be right: then the prompt gets no date, no time
     * and no festival block, because a wrong time is worse than none.
     *
     * [deviceZone] is used only when there is no table; a table brings its own zone.
     * [buildDate] is the UTC date this app was built on.
     */
    fun describe(now: Instant, deviceZone: ZoneId, setTimes: SetTimesLoad, buildDate: LocalDate?): String {
        val table = (setTimes as? SetTimesLoad.Loaded)?.table
        val zone = table?.zone ?: deviceZone
        if (!isClockPlausible(now, zone, table?.updated, buildDate)) return ""

        val lines = mutableListOf("Current local time: ${now.dayAndDate(zone)}, ${now.clock(zone)} (${zone.id}).")
        if (table != null) {
            val sets = table.sets.mapNotNull { set -> set.interval(zone)?.let { (start, end) -> Timed(set, start, end) } }
            if (sets.isNotEmpty()) lines += festivalLines(now, zone, table, sets)
        }
        return lines.joinToString("\n")
    }

    /**
     * A time before the day the app was built, or more than a day before the day the table was
     * last edited, is wrong: the board has no clock battery and reads 1969 or 2008 after a start
     * without network. (The table's date gets a day of slack: nobody says in which zone it was
     * written.) These are lower bounds only. That the time is RIGHT is what [ServerClock] is for.
     */
    fun isClockPlausible(now: Instant, zone: ZoneId, tableUpdated: LocalDate?, buildDate: LocalDate?): Boolean {
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate().toEpochDay()
        val todayUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC).toLocalDate().toEpochDay()
        // Compared as day numbers: no date in a file can make this arithmetic fail.
        if (buildDate != null && todayUtc < buildDate.toEpochDay()) return false
        if (tableUpdated != null && today < tableUpdated.toEpochDay() - 1) return false
        return true
    }

    /**
     * The instant a wall-clock time of the table means in [zone].
     * - Most times happen once.
     * - A time the clocks jump over (they go forward) does not exist: it is read as that time
     *   after the jump, an hour on.
     * - A time that happens twice (the clocks go back) is read as its first occurrence; for the
     *   end of a set, as the first occurrence that comes [after] the set's start.
     */
    fun resolve(local: LocalDateTime, zone: ZoneId, after: Instant?): Instant {
        val offsets = zone.rules.getValidOffsets(local)
        return when (offsets.size) {
            1 -> local.toInstant(offsets[0])
            2 -> {
                val first = local.toInstant(offsets[0])
                if (after != null && !first.isAfter(after)) local.toInstant(offsets[1]) else first
            }

            else -> local.atZone(zone).toInstant()
        }
    }

    private fun festivalLines(now: Instant, zone: ZoneId, table: SetTimesTable, sets: List<Timed>): List<String> {
        val name = listOf(table.event.ifBlank { "The festival" }, table.venue.takeIf { it.isNotBlank() }?.let { "($it)" })
            .filterNotNull().joinToString(" ")
        val firstStart = sets.minOf { it.start }
        val lastEnd = sets.maxOf { it.end }
        val today = now.festivalDay(zone)
        // Today's schedule: by the day a set STARTS on. Nothing else is decided from this list.
        val todays = sets.filter { it.start.festivalDay(zone) == today }
        val onNow = sets.filter { it.isOnAt(now) }
        val insideToday = todays.isNotEmpty() && !now.isBefore(todays.minOf { it.start }) && now.isBefore(todays.maxOf { it.end })

        return when {
            now.isBefore(firstStart) ->
                listOf(
                    "$name has not started yet. The first set is on ${firstStart.dayAndDate(zone)} at " +
                        "${firstStart.clock(zone)}, in ${until(now, firstStart)}."
                ) + schedule(today, todays, zone)

            !now.isBefore(lastEnd) ->
                listOf("$name is over: the last set ended on ${lastEnd.dayAndDate(zone)} at ${lastEnd.clock(zone)}.")

            onNow.isNotEmpty() || insideToday ->
                listOf("$name is running right now.", "On stage now and next:") +
                    nowAndNext(now, zone, today, sets, todays, onNow) +
                    schedule(today, todays, zone)

            else -> {
                // Nothing is on, and something is still to come.
                val next = sets.filter { it.start.isAfter(now) }.minOf { it.start }
                if (next.festivalDay(zone) == today) {
                    listOf("$name: today's first set is at ${next.clock(zone)}, in ${until(now, next)}.") +
                        schedule(today, todays, zone)
                } else {
                    val before = if (todays.isNotEmpty()) "${today.dayName()}'s sets are over. " else ""
                    listOf(
                        "$name: ${before}The next set is on ${next.dayAndDate(zone)} at ${next.clock(zone)}, " +
                            "in ${until(now, next)}."
                    )
                }
            }
        }
    }

    /**
     * One line a stage, in the order the stages come in the table: every stage that has a set
     * on now, a set in today's schedule, or a set still to come (a stage that only opens
     * tomorrow is named with what comes next on it). Who is on and who is next are looked up in
     * ALL sets.
     */
    private fun nowAndNext(
        now: Instant,
        zone: ZoneId,
        today: LocalDate,
        sets: List<Timed>,
        todays: List<Timed>,
        onNow: List<Timed>,
    ): List<String> {
        val stagesToName = (todays + onNow + sets.filter { it.start.isAfter(now) }).map { it.set.stage }.toSet()
        return sets.map { it.set.stage }.distinct().filter { it in stagesToName }.map { stage ->
            val on = onNow.filter { it.set.stage == stage }.sortedBy { it.start }
            val later = sets.filter { it.set.stage == stage && it.start.isAfter(now) }
            val nextStart = later.minOfOrNull { it.start }

            val nowPart =
                if (on.isEmpty()) "nobody right now"
                else on.joinToString(", ") { "${it.set.artist} until ${it.end.clock(zone)}" }
            val nextPart =
                if (nextStart == null) {
                    "Nothing after that."
                } else {
                    val who = later.filter { it.start == nextStart }.joinToString(" and ") { it.set.artist }
                    // A set that is not part of today's schedule is named with its day.
                    val day = if (nextStart.festivalDay(zone) == today) "" else "on ${nextStart.localDate(zone).dayName()} "
                    "Next: $who ${day}at ${nextStart.clock(zone)}."
                }
            "- $stage: $nowPart. $nextPart"
        }
    }

    /** The whole festival day, one line a set, stage by stage in the table's order. Never trimmed. */
    private fun schedule(day: LocalDate, todays: List<Timed>, zone: ZoneId): List<String> =
        if (todays.isEmpty()) {
            emptyList()
        } else {
            listOf("Full schedule for ${day.dayName()} ${day.format(DATE)} (a time after midnight is early the next morning):") +
                todays.groupBy { it.set.stage }.flatMap { (stage, stageSets) ->
                    stageSets.sortedBy { it.start }.map { "$stage: ${it.start.clock(zone)}-${it.end.clock(zone)} ${it.set.artist}" }
                }
        }

    /** A set is on from its start up to, not including, its end. */
    private fun Timed.isOnAt(now: Instant): Boolean = !now.isBefore(start) && now.isBefore(end)

    private fun Instant.local(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(this, zone)

    private fun Instant.localDate(zone: ZoneId): LocalDate = local(zone).toLocalDate()

    private fun Instant.festivalDay(zone: ZoneId): LocalDate = local(zone).minusHours(DAY_STARTS_AT_HOUR).toLocalDate()

    private fun Instant.dayAndDate(zone: ZoneId): String = "${localDate(zone).dayName()} ${localDate(zone).format(DATE)}"

    /**
     * "9:30 pm", "12:00 am": the way the crowd says it, and the form the replies were tried with.
     * Put together by hand, so it does not depend on the locale data of the device.
     */
    private fun Instant.clock(zone: ZoneId): String = local(zone).let {
        "${(it.hour + 11) % 12 + 1}:${it.minute.toString().padStart(2, '0')} ${if (it.hour < 12) "am" else "pm"}"
    }

    private fun LocalDate.dayName(): String = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)

    /** "1 day 16 h 30 min", "14 h", "45 min": the time that really passes between the two. */
    private fun until(from: Instant, to: Instant): String {
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
