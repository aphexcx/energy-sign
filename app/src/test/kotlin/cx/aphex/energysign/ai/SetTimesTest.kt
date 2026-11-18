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
}
