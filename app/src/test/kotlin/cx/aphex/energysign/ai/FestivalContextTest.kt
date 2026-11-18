package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The block of "what is true right now" that goes into the prompt: (clock, zone, table) in, text out.
 * Every test uses a fixed clock and a small made-up table.
 */
class FestivalContextTest {

    private val la: ZoneId = ZoneId.of("America/Los_Angeles")

    private fun set(stage: String, artist: String, start: String, end: String) =
        FestivalSet(stage, artist, LocalDateTime.parse(start), LocalDateTime.parse(end))

    private val sets = listOf(
        // Friday
        set("The Dream", "Opener", "2026-11-20T16:00", "2026-11-20T17:00"),
        set("The Dream", "Early", "2026-11-20T21:00", "2026-11-20T22:00"),
        set("The Dream", "Late", "2026-11-20T22:00", "2026-11-20T23:00"),
        set("The Dream", "Friday Closer", "2026-11-20T23:00", "2026-11-21T01:00"),
        set("The Void", "Void One", "2026-11-20T16:00", "2026-11-20T17:00"),
        set("The Void", "Void Two", "2026-11-20T21:45", "2026-11-20T22:30"),
        // After midnight: Friday night's last hour carries Saturday's date.
        set("The Void", "Void Late", "2026-11-21T00:00", "2026-11-21T01:00"),
        // Two sets on one stage that overlap in the data.
        set("Art Car", "Double A", "2026-11-20T21:00", "2026-11-20T22:00"),
        set("Art Car", "Double B", "2026-11-20T21:15", "2026-11-20T22:15"),
        // Saturday
        set("The Dream", "Saturday Opener", "2026-11-21T16:00", "2026-11-21T17:00"),
        set("The Dream", "Saturday Closer", "2026-11-21T23:00", "2026-11-22T01:00"),
        set("The Void", "Saturday Void", "2026-11-21T20:00", "2026-11-21T21:00"),
    )

    private val table = SetTimesTable(
        event = "Dreamstate SoCal 2026",
        venue = "Queen Mary Waterfront, Long Beach, CA",
        zone = la,
        updated = LocalDate.parse("2026-11-17"),
        sets = sets,
    )

    private val buildDate: LocalDate = LocalDate.parse("2026-11-18")

    private fun at(localDateTime: String, zone: ZoneId = la): Instant =
        LocalDateTime.parse(localDateTime).atZone(zone).toInstant()

    private fun describe(
        localDateTime: String,
        setTimes: SetTimesLoad = SetTimesLoad.Loaded(table, "test"),
        deviceZone: ZoneId = la,
        buildDate: LocalDate? = this.buildDate,
    ): String = FestivalContext.describe(at(localDateTime), deviceZone, setTimes, buildDate)

    private fun String.lineFor(stage: String): String =
        lines().firstOrNull { it.startsWith("- $stage:") } ?: error("no now/next line for $stage in:\n$this")

    // ---- the local time ----

    @Test
    fun `the block starts with the local date, weekday and time, with the zone`() {
        val block = describe("2026-11-20T21:30")

        assertEquals("Current local time: Friday 2026-11-20, 9:30 pm (America/Los_Angeles).", block.lines().first())
    }

    @Test
    fun `the local time is given in the table's zone, whatever zone the device is set to`() {
        // 5:30 am UTC on the 21st is 9:30 pm on the 20th in Long Beach.
        val block = FestivalContext.describe(
            Instant.parse("2026-11-21T05:30:00Z"), ZoneId.of("UTC"), SetTimesLoad.Loaded(table, "test"), buildDate,
        )

        assertEquals("Current local time: Friday 2026-11-20, 9:30 pm (America/Los_Angeles).", block.lines().first())
    }

    // ---- before, between, after ----

    @Test
    fun `days before the festival there is one line saying how long until the first set, and no on now`() {
        val block = describe("2026-11-18T23:30")

        assertTrue(block.contains("has not started yet"), block)
        assertTrue(block.contains("The first set is on Friday 2026-11-20 at 4:00 pm, in 1 day 16 h 30 min."), block)
        assertFalse(block.contains("On stage now"), block)
        assertFalse(block.contains("Full schedule"), block)
        assertEquals(2, block.lines().size, block)
    }

    @Test
    fun `on the first day before the first set the day's schedule is given, and no on now`() {
        val block = describe("2026-11-20T13:15")

        assertTrue(block.contains("has not started yet"), block)
        assertTrue(block.contains("at 4:00 pm, in 2 h 45 min."), block)
        assertFalse(block.contains("On stage now"), block)
        assertTrue(block.contains("Full schedule for Friday 2026-11-20"), block)
        assertTrue(block.contains("The Dream: 4:00 pm-5:00 pm Opener"), block)
    }

    @Test
    fun `between the two days, after the last set of the night, the next set is named and nothing is on`() {
        val block = describe("2026-11-21T02:00")

        assertTrue(block.contains("Friday's sets are over."), block)
        assertTrue(block.contains("The next set is on Saturday 2026-11-21 at 4:00 pm, in 14 h."), block)
        assertFalse(block.contains("On stage now"), block)
    }

    @Test
    fun `between the two days, on the second afternoon, today's first set and schedule are given`() {
        val block = describe("2026-11-21T10:00")

        assertTrue(block.contains("today's first set is at 4:00 pm, in 6 h."), block)
        assertFalse(block.contains("On stage now"), block)
        assertTrue(block.contains("Full schedule for Saturday 2026-11-21"), block)
        assertTrue(block.contains("The Dream: 4:00 pm-5:00 pm Saturday Opener"), block)
        assertFalse(block.contains("Friday Closer"), block)
    }

    @Test
    fun `after the last set the festival is over, in one line`() {
        val block = describe("2026-11-22T01:00")

        assertTrue(block.contains("is over: the last set ended on Sunday 2026-11-22 at 1:00 am."), block)
        assertFalse(block.contains("On stage now"), block)
        assertFalse(block.contains("Full schedule"), block)
        assertEquals(2, block.lines().size, block)
    }

    // ---- while it is running ----

    @Test
    fun `during a set each stage says who is on until when and who is next and when`() {
        val block = describe("2026-11-20T21:30")

        assertTrue(block.contains("is running right now."), block)
        assertEquals("- The Dream: Early until 10:00 pm. Next: Late at 10:00 pm.", block.lineFor("The Dream"))
    }

    @Test
    fun `exactly on a boundary the set that ends is over and the one that starts is on`() {
        val block = describe("2026-11-20T22:00")

        assertEquals("- The Dream: Late until 11:00 pm. Next: Friday Closer at 11:00 pm.", block.lineFor("The Dream"))
    }

    @Test
    fun `a stage with a gap says nobody is on and who is next`() {
        val block = describe("2026-11-20T21:30")

        assertEquals("- The Void: nobody right now. Next: Void Two at 9:45 pm.", block.lineFor("The Void"))
    }

    @Test
    fun `two sets that overlap on one stage are both shown`() {
        val block = describe("2026-11-20T21:30")

        assertEquals(
            "- Art Car: Double A until 10:00 pm, Double B until 10:15 pm. Nothing after that.",
            block.lineFor("Art Car"),
        )
    }

    @Test
    fun `a set that crosses midnight is still on after midnight, and the day is still the night before`() {
        val block = describe("2026-11-21T00:30")

        assertEquals("Current local time: Saturday 2026-11-21, 12:30 am (America/Los_Angeles).", block.lines().first())
        // Who is next comes from the whole table, not from the night that is ending.
        assertEquals(
            "- The Dream: Friday Closer until 1:00 am. Next: Saturday Opener on Saturday at 4:00 pm.",
            block.lineFor("The Dream"),
        )
        assertEquals(
            "- The Void: Void Late until 1:00 am. Next: Saturday Void on Saturday at 8:00 pm.",
            block.lineFor("The Void"),
        )
        assertTrue(block.contains("Full schedule for Friday 2026-11-20"), block)
        assertTrue(block.contains("The Void: 12:00 am-1:00 am Void Late"), block)
        // Saturday's sets are not in the printed schedule of Friday's night.
        assertFalse(block.contains("4:00 pm-5:00 pm Saturday Opener"), block)
    }

    @Test
    fun `the whole schedule of the festival day is given, one line a set, in stage and time order, untrimmed`() {
        val block = describe("2026-11-20T21:30")

        val schedule = block.substringAfter("Full schedule for Friday 2026-11-20").lines().drop(1)
        assertEquals(
            listOf(
                "The Dream: 4:00 pm-5:00 pm Opener",
                "The Dream: 9:00 pm-10:00 pm Early",
                "The Dream: 10:00 pm-11:00 pm Late",
                "The Dream: 11:00 pm-1:00 am Friday Closer",
                "The Void: 4:00 pm-5:00 pm Void One",
                "The Void: 9:45 pm-10:30 pm Void Two",
                "The Void: 12:00 am-1:00 am Void Late",
                "Art Car: 9:00 pm-10:00 pm Double A",
                "Art Car: 9:15 pm-10:15 pm Double B",
            ),
            schedule,
        )
    }

    @Test
    fun `a full day of forty sets is not trimmed`() {
        val many = (0 until 40).map { i ->
            val start = LocalDateTime.parse("2026-11-20T16:00").plusMinutes(10L * i)
            FestivalSet("Stage ${i % 4}", "Artist $i", start, start.plusMinutes(10))
        }
        val block = describe("2026-11-20T16:05", SetTimesLoad.Loaded(table.copy(sets = many), "test"))

        assertEquals(40, block.lines().count { Regex("""^Stage \d: \d{1,2}:\d\d [ap]m-\d{1,2}:\d\d [ap]m Artist \d+$""").matches(it) })
    }

    // ---- no usable table ----

    @Test
    fun `without a table there is the local time in the device's zone and nothing about the festival`() {
        val block = describe("2026-11-20T21:30", SetTimesLoad.Unusable("no file"), deviceZone = ZoneId.of("America/Denver"))

        assertEquals(
            listOf("Current local time: Friday 2026-11-20, 10:30 pm (America/Denver)."),
            block.lines(),
        )
    }

    @Test
    fun `an empty table gives no festival block`() {
        val block = describe("2026-11-20T21:30", SetTimesLoad.Loaded(table.copy(sets = emptyList()), "test"))

        assertEquals(1, block.lines().size, block)
    }

    // ---- a wrong clock ----

    @Test
    fun `a clock before the table's updated date gives no date, no time and no festival block`() {
        // With no build date, so that this lower bound is the only one in play.
        assertEquals("", describe("2008-01-01T00:03", buildDate = null))
        assertEquals("", describe("1969-12-31T16:03", buildDate = null))
        assertEquals("", describe("2026-11-15T23:59", buildDate = null), "two days before the table was edited")
        assertTrue(
            describe("2026-11-16T00:00", buildDate = null).startsWith("Current local time: Monday 2026-11-16"),
            "the day before the table was edited is within the slack",
        )
    }

    @Test
    fun `a date in the table that is as early as a date can be breaks nothing`() {
        val block = describe("2026-11-20T21:30", SetTimesLoad.Loaded(table.copy(updated = LocalDate.MIN), "test"), buildDate = LocalDate.MIN)

        assertTrue(block.startsWith("Current local time: Friday 2026-11-20, 9:30 pm"), block)
    }

    @Test
    fun `a clock before the build date gives nothing, even without a table`() {
        assertEquals("", describe("2026-11-10T12:00", SetTimesLoad.Unusable("no file")))
        assertEquals("", describe("2026-11-10T12:00", SetTimesLoad.Loaded(table.copy(updated = null), "test")))
    }

    @Test
    fun `a right clock on the build day or the updated day is believed`() {
        // The build date is a UTC date: late on the 17th in Long Beach is already the 18th in UTC.
        assertTrue(describe("2026-11-17T23:50").startsWith("Current local time: Tuesday 2026-11-17, 11:50 pm"))
        assertTrue(
            describe("2026-11-17T08:00", buildDate = null).startsWith("Current local time: Tuesday 2026-11-17, 8:00 am")
        )
    }

    @Test
    fun `with no build date and no table the clock is taken as it is`() {
        val block = describe("2008-01-01T00:03", SetTimesLoad.Unusable("no file"), buildDate = null)

        assertTrue(block.startsWith("Current local time: Tuesday 2008-01-01, 12:03 am"), block)
    }

    // ---- on instants, over the whole table (class E)

    private fun tableOf(vararg sets: FestivalSet) =
        SetTimesLoad.Loaded(SetTimesTable("Fest", "", la, updated = null, sets = sets.toList()), "test")

    private fun describeAt(instant: String, setTimes: SetTimesLoad): String =
        FestivalContext.describe(Instant.parse(instant), ZoneId.of("UTC"), setTimes, null)

    @Test
    fun `a set that is on is on, whatever day it started - the day decides the printed schedule only`() {
        val setTimes = tableOf(
            set("Stage", "Sunrise Set", "2026-11-21T05:30", "2026-11-21T06:30"),
            set("Stage", "Afternoon", "2026-11-21T16:00", "2026-11-21T17:00"),
        )

        val block = describe("2026-11-21T06:05", setTimes, buildDate = null)

        assertTrue(block.contains("is running right now."), block)
        assertEquals("- Stage: Sunrise Set until 6:30 am. Next: Afternoon at 4:00 pm.", block.lineFor("Stage"))
        assertFalse(block.contains("today's first set"), block)
    }

    @Test
    fun `in how long is the time that really passes, also when the clocks go forward in between`() {
        // Los Angeles, 14 March 2027: at 2:00 am the clocks go to 3:00 am. From 1:30 am
        // (09:30 UTC) to 3:30 am (10:30 UTC) is one hour, not two.
        val setTimes = tableOf(set("Stage", "Early Bird", "2027-03-14T03:30", "2027-03-14T04:30"))

        val block = describeAt("2027-03-14T09:30:00Z", setTimes)

        assertTrue(block.startsWith("Current local time: Sunday 2027-03-14, 1:30 am"), block)
        assertTrue(block.contains("at 3:30 am, in 1 h."), block)
    }

    @Test
    fun `a set that runs through the hour that happens twice stays on through both`() {
        // Los Angeles, 1 November 2026: at 2:00 am the clocks go back to 1:00 am. The set starts
        // at the first 1:30 am (08:30 UTC) and ends at 2:30 am (10:30 UTC): two hours.
        val setTimes = tableOf(set("Stage", "Long Night", "2026-11-01T01:30", "2026-11-01T02:30"))

        for (utc in listOf("08:30", "08:59", "09:00", "09:29", "09:30", "10:29")) {
            val block = describeAt("2026-11-01T$utc:00Z", setTimes)
            assertEquals("- Stage: Long Night until 2:30 am. Nothing after that.", block.lineFor("Stage"), "at $utc UTC")
        }
        assertTrue(describeAt("2026-11-01T08:29:00Z", setTimes).contains("has not started yet"))
        assertTrue(describeAt("2026-11-01T10:30:00Z", setTimes).contains("is over"))
    }

    @Test
    fun `a time that happens twice is its first time, and an end is the first time after the start`() {
        val night = LocalDateTime.parse("2026-11-01T01:15")

        assertEquals(Instant.parse("2026-11-01T08:15:00Z"), FestivalContext.resolve(night, la, after = null))
        assertEquals(
            Instant.parse("2026-11-01T08:15:00Z"),
            FestivalContext.resolve(night, la, after = Instant.parse("2026-11-01T07:45:00Z")),
            "an end after a start before the repeated hour: the first 1:15",
        )
        assertEquals(
            Instant.parse("2026-11-01T09:15:00Z"),
            FestivalContext.resolve(night, la, after = Instant.parse("2026-11-01T08:45:00Z")),
            "an end after a start at the first 1:45: the second 1:15",
        )
    }

    @Test
    fun `a time the clocks jump over is read as that time after the jump`() {
        // 2:30 am does not exist on 14 March 2027 in Los Angeles.
        assertEquals(
            Instant.parse("2027-03-14T10:30:00Z"),
            FestivalContext.resolve(LocalDateTime.parse("2027-03-14T02:30"), la, after = null),
        )
        assertEquals(
            Instant.parse("2027-03-14T09:30:00Z"),
            FestivalContext.resolve(LocalDateTime.parse("2027-03-14T01:30"), la, after = null),
        )
    }

    @Test
    fun `with the table of the festival, the last hour of Friday night names Saturday's first set as next`() {
        val bundled = SetTimes.parse(java.io.File("src/main/assets/set_times.json").readText(), "bundled")

        val block = describe("2026-11-21T00:30", bundled, buildDate = null)

        assertEquals("- The Dream: ARTBAT until 1:00 am. Next: Das Pharaoh on Saturday at 4:00 pm.", block.lineFor("The Dream"))
        assertFalse(block.contains("Nothing after that") && block.lineFor("The Dream").contains("Nothing after that"), block)
    }
}
