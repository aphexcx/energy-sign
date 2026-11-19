package cx.aphex.energysign.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reading the set times: the file on the device first, the bundled one otherwise, and never a crash. */
class SetTimesTest {

    private val good = """
        {
          "event": "Dreamstate SoCal 2026",
          "venue": "Queen Mary Waterfront, Long Beach, CA",
          "timezone": "America/Los_Angeles",
          "updated": "2026-11-18",
          "sets": [
            {"stage": "The Dream", "artist": "VisionV", "start": "2026-11-20T16:00", "end": "2026-11-20T17:00"},
            {"stage": "The Dream", "artist": "ARTBAT", "start": "2026-11-21T00:00", "end": "2026-11-21T01:00"}
          ]
        }
    """.trimIndent()

    private fun other(artist: String) = good.replace("VisionV", artist)

    @Test
    fun `the format the mayor delivers is read`() {
        val table = assertIs<SetTimesLoad.Loaded>(SetTimes.parse(good, "test")).table

        assertEquals("Dreamstate SoCal 2026", table.event)
        assertEquals("Queen Mary Waterfront, Long Beach, CA", table.venue)
        assertEquals(ZoneId.of("America/Los_Angeles"), table.zone)
        assertEquals(LocalDate.parse("2026-11-18"), table.updated)
        assertEquals(
            listOf(
                FestivalSet("The Dream", "VisionV", LocalDateTime.parse("2026-11-20T16:00"), LocalDateTime.parse("2026-11-20T17:00")),
                FestivalSet("The Dream", "ARTBAT", LocalDateTime.parse("2026-11-21T00:00"), LocalDateTime.parse("2026-11-21T01:00")),
            ),
            table.sets,
        )
    }

    @Test
    fun `broken JSON is unusable, not a crash`() {
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse("{\"event\": \"Dreamstate", "test"))
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse("not json at all", "test"))
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse("[1, 2, 3]", "test"))
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse("{\"sets\": \"none\", \"timezone\": \"America/Los_Angeles\"}", "test"))
    }

    @Test
    fun `an empty file and an empty table are unusable`() {
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse("", "test"))
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse("   \n", "test"))
        assertIs<SetTimesLoad.Unusable>(
            SetTimes.parse("""{"event":"x","venue":"y","timezone":"America/Los_Angeles","updated":"2026-11-18","sets":[]}""", "test")
        )
    }

    @Test
    fun `an unknown or missing zone is unusable`() {
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse(good.replace("America/Los_Angeles", "Mars/Olympus_Mons"), "test"))
        assertIs<SetTimesLoad.Unusable>(SetTimes.parse(good.replace("\"timezone\"", "\"tz\""), "test"))
    }

    @Test
    fun `a set that cannot be read is left out and the rest is kept`() {
        val json = """
            {"event":"x","venue":"y","timezone":"America/Los_Angeles","updated":"not a date","sets":[
              {"stage":"A","artist":"Good","start":"2026-11-20T16:00","end":"2026-11-20T17:00"},
              {"stage":"A","artist":"Bad time","start":"4pm","end":"2026-11-20T17:00"},
              {"stage":"A","artist":"Ends before it starts","start":"2026-11-20T18:00","end":"2026-11-20T17:00"},
              {"stage":"A","start":"2026-11-20T18:00","end":"2026-11-20T19:00"},
              {"stage":7,"artist":["x"],"start":"2026-11-20T18:00","end":"2026-11-20T19:00"},
              "not a set"
            ]}
        """.trimIndent()

        val table = assertIs<SetTimesLoad.Loaded>(SetTimes.parse(json, "test")).table

        assertEquals(listOf("Good"), table.sets.map { it.artist })
        assertEquals(null, table.updated)
    }

    @Test
    fun `the file on the device wins over the bundled one`(@TempDir dir: File) {
        val deviceFile = File(dir, "set_times.json").apply { writeText(other("From The Device")) }

        val loaded = assertIs<SetTimesLoad.Loaded>(SetTimes.load(deviceFile) { other("From The Bundle") })

        assertEquals("From The Device", loaded.table.sets.first().artist)
    }

    @Test
    fun `without a file on the device the bundled one is used`(@TempDir dir: File) {
        val loaded = assertIs<SetTimesLoad.Loaded>(SetTimes.load(File(dir, "set_times.json")) { other("From The Bundle") })

        assertEquals("From The Bundle", loaded.table.sets.first().artist)
    }

    @Test
    fun `a broken or empty file on the device falls back to the bundled one`(@TempDir dir: File) {
        val deviceFile = File(dir, "set_times.json")

        deviceFile.writeText("{ broken")
        assertEquals(
            "From The Bundle",
            assertIs<SetTimesLoad.Loaded>(SetTimes.load(deviceFile) { other("From The Bundle") }).table.sets.first().artist,
        )

        deviceFile.writeText("")
        assertEquals(
            "From The Bundle",
            assertIs<SetTimesLoad.Loaded>(SetTimes.load(deviceFile) { other("From The Bundle") }).table.sets.first().artist,
        )
    }

    @Test
    fun `a file on the device far too big to be a schedule is not read, the bundled one is used`(@TempDir dir: File) {
        // A valid schedule, padded to 600 kB with white space: it would parse if it were read.
        val deviceFile = File(dir, "set_times.json").apply { writeText(other("From The Device") + " ".repeat(600_000)) }

        val loaded = assertIs<SetTimesLoad.Loaded>(SetTimes.load(deviceFile) { other("From The Bundle") })

        assertEquals("From The Bundle", loaded.table.sets.first().artist)
    }

    @Test
    fun `what a broken file holds is not put into the reason`(@TempDir dir: File) {
        val deviceFile = File(dir, "set_times.json").apply { writeText("{\"timezone\": \"made-up-key-12345\", \"sets\": []} made-up-key-12345") }

        val unusable = assertIs<SetTimesLoad.Unusable>(SetTimes.load(deviceFile) { null })
        val wrongZone = assertIs<SetTimesLoad.Unusable>(SetTimes.parse("{\"timezone\": \"made-up-key-12345\", \"sets\": []}", "test"))

        assertFalse(unusable.why.contains("made-up-key"), unusable.why)
        assertFalse(wrongZone.why.contains("made-up-key"), wrongZone.why)
    }

    @Test
    fun `no file anywhere is unusable and says so, without a crash`(@TempDir dir: File) {
        val missing = File(dir, "set_times.json")

        val noBundle = assertIs<SetTimesLoad.Unusable>(SetTimes.load(missing) { null })
        val bundleThrows = assertIs<SetTimesLoad.Unusable>(SetTimes.load(missing) { throw FileNotFoundException("set_times.json") })
        val bundleBroken = assertIs<SetTimesLoad.Unusable>(SetTimes.load(missing) { "{ broken" })

        assertTrue(noBundle.why.isNotBlank())
        assertTrue(bundleThrows.why.isNotBlank())
        assertTrue(bundleBroken.why.isNotBlank())
    }

    // ---- a set ends after it starts, in real time (minors of both gates)

    private fun oneSet(start: String, end: String) = """
        {"timezone": "America/Los_Angeles", "sets": [
          {"stage": "The Dream", "artist": "Night Shift", "start": "$start", "end": "$end"},
          {"stage": "The Dream", "artist": "Anchor", "start": "2026-11-20T16:00", "end": "2026-11-20T17:00"}
        ]}
    """.trimIndent()

    @Test
    fun `in the hour the clocks go back, a set from the first 1-45 to the second 1-15 is a set of half an hour`() {
        // 2026-11-01, Los Angeles: 01:00 to 02:00 happens twice.
        val table = assertIs<SetTimesLoad.Loaded>(SetTimes.parse(oneSet("2026-11-01T01:45", "2026-11-01T01:15"), "test")).table

        val set = table.sets.single { it.artist == "Night Shift" }
        val (from, to) = set.interval(table.zone)!!
        assertEquals(java.time.Duration.ofMinutes(30), java.time.Duration.between(from, to))
        assertEquals(java.time.Instant.parse("2026-11-01T08:45:00Z"), from, "the first 1:45, in daylight time")
        assertEquals(java.time.Instant.parse("2026-11-01T09:15:00Z"), to, "the second 1:15, in standard time")
    }

    @Test
    fun `in the hour the clocks go forward, a set that ends before it starts in real time is left out when the file is read`() {
        // 2027-03-14, Los Angeles: 02:00 to 03:00 does not happen. 02:45 is read as 03:45, after the 03:15 it should end at.
        val load = assertIs<SetTimesLoad.Loaded>(SetTimes.parse(oneSet("2027-03-14T02:45", "2027-03-14T03:15"), "test"))

        assertEquals(listOf("Anchor"), load.table.sets.map { it.artist })
        assertTrue(load.source.contains("1 unreadable"), "the reader says a set was left out: ${load.source}")
    }

    @Test
    fun `a set that ends when it starts, or before, on an ordinary day is left out as before`() {
        for ((start, end) in listOf("2026-11-20T22:00" to "2026-11-20T22:00", "2026-11-20T22:00" to "2026-11-20T21:00")) {
            val load = assertIs<SetTimesLoad.Loaded>(SetTimes.parse(oneSet(start, end), "test"))
            assertEquals(listOf("Anchor"), load.table.sets.map { it.artist }, "$start to $end")
        }
    }

    @Test
    fun `the loader and the prompt take a set's times from the same place`() {
        val table = assertIs<SetTimesLoad.Loaded>(SetTimes.parse(oneSet("2026-11-01T01:45", "2026-11-01T01:15"), "test")).table
        val during = java.time.Instant.parse("2026-11-01T09:00:00Z") // the second 1:00

        val prompt = FestivalContext.describe(during, ZoneId.of("UTC"), SetTimesLoad.Loaded(table, "test"), buildDate = null)

        assertTrue(prompt.contains("Night Shift until 1:15 am"), prompt)
    }
}
