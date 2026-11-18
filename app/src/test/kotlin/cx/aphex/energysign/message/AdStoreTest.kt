package cx.aphex.energysign.message

import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The ads and the ad period across a restart: every test "restarts" by making a new [AdStore] on the same directory. */
class AdStoreTest {

    @TempDir
    lateinit var dir: File

    private val adsFile get() = File(dir, "ads.json")
    private val periodFile get() = File(dir, "ad-period.txt")

    private fun wire(ads: List<Message>): List<String> = ads.map { SignResponder.GSON.toJson(it) }

    private val someAds: List<Message> = listOf(
        ChonkySlide(" HEY", 0xff0080, 2000),
        OneByOneMessage("SAY HI!!${Message.HEART}", 0xff055d, 2000),
        Message.NowPlayingTrackMessage(""),
        IconInvaders.Enemy1(0xfed43b),
    )

    @Test
    fun `with no file yet there is nothing saved, and looking does not make a file`() {
        assertIs<AdStore.SavedAds.NoFile>(AdStore(dir).loadAds())

        assertFalse(adsFile.exists())
    }

    @Test
    fun `the ads that were set come back after a restart`() {
        AdStore(dir).saveAds(someAds)

        val saved = assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds())

        assertEquals(someAds.map { it::class }, saved.ads.map { it::class })
        assertEquals(wire(someAds), wire(saved.ads))
    }

    @Test
    fun `an ad list emptied on purpose stays empty after a restart`() {
        AdStore(dir).saveAds(someAds)
        AdStore(dir).saveAds(emptyList())

        val saved = assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds())

        assertEquals(emptyList(), saved.ads)
    }

    /** What is where at one moment of a save: the file beside (null when there is none) and the file in place. */
    private fun files(): String = "beside " + File(dir, "ads.json.tmp").let { if (it.exists()) it.readText() else null } +
            ", in place " + adsFile.readText()

    @Test
    fun `a save is three steps in this order, the data to storage, the rename, the directory to storage`() {
        AdStore(dir).saveAds(someAds)
        val old = adsFile.readText()
        val fresh = AdCodec.encode(listOf<Message>(IconInvaders.EDC(0x04ff19)))
        val steps = mutableListOf<String>()
        val store = AdStore(
            dir,
            syncData = { out -> steps += "data: " + files(); out.fd.sync() },
            syncDirectory = { directory -> steps += "directory ${directory.name == dir.name}: " + files() },
        )

        store.saveAds(listOf(IconInvaders.EDC(0x04ff19)))

        assertEquals(
            listOf(
                // the new list is whole beside the old one, which is still in place
                "data: beside $fresh, in place $old",
                // the new list is in place and nothing is beside it: the rename has been done
                "directory true: beside null, in place $fresh",
            ),
            steps
        )
        assertEquals(listOf("ads.json"), dir.list()!!.toList())
    }

    @Test
    fun `every save takes both steps once, the period too`() {
        var data = 0
        var directory = 0
        val store = AdStore(dir, syncData = { out -> data++; out.fd.sync() }, syncDirectory = { directory++ })

        store.saveAds(someAds)
        assertEquals(1 to 1, data to directory)
        store.saveAds(emptyList())
        assertEquals(2 to 2, data to directory)
        store.savePeriod(4)
        assertEquals(3 to 3, data to directory)
    }

    @Test
    fun `when the data cannot be forced to storage the save fails and the old list stays in place`() {
        AdStore(dir).saveAds(someAds)
        val old = adsFile.readText()
        var directorySynced = false
        val store = AdStore(
            dir,
            syncData = { throw java.io.IOException("no space left") },
            syncDirectory = { directorySynced = true },
        )

        assertFailsWith<java.io.IOException> { store.saveAds(emptyList()) }

        assertEquals(old, adsFile.readText())
        assertFalse(directorySynced, "nothing was renamed, so there is nothing to force")
        assertEquals(wire(someAds), wire(assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds()).ads))
    }

    @Test
    fun `when the directory cannot be forced to storage the save is done all the same, and that is reported`() {
        AdStore(dir).saveAds(someAds)
        val warnings = mutableListOf<String>()
        val store = AdStore(dir, warn = { warnings += it }, syncDirectory = { throw java.io.IOException("not today") })

        store.saveAds(emptyList()) // does not throw
        store.savePeriod(7)

        assertEquals("[]", adsFile.readText())
        assertEquals("7", periodFile.readText())
        assertEquals(emptyList(), assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds()).ads)
        assertEquals(setOf("ads.json", "ad-period.txt"), dir.list()!!.toSet(), "the new files are in place, nothing beside them")
        assertEquals(2, warnings.size, warnings.toString())
        assertContains(warnings[0], "ads.json")
        assertContains(warnings[0], "not today")
        assertContains(warnings[1], "ad-period.txt")
    }

    @Test
    fun `the real steps work on a real directory`() {
        val warnings = mutableListOf<String>()

        AdStore(dir, warn = { warnings += it }).saveAds(someAds)

        assertEquals(emptyList(), warnings, "the directory of this machine can be forced to storage")
        assertEquals(wire(someAds), wire(assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds()).ads))
    }

    @Test
    fun `a new list replaces the old one and leaves no other file behind`() {
        AdStore(dir).saveAds(someAds)
        AdStore(dir).saveAds(listOf(IconInvaders.EDC(0x04ff19)))

        val saved = assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds())

        assertIs<IconInvaders.EDC>(saved.ads.single())
        assertEquals(listOf("ads.json"), dir.list()!!.toList())
    }

    @Test
    fun `a file that cannot be read is reported with the reason and is not touched`() {
        val broken = """[{"dly":1000,"str":"CUT OFF","ty"""
        adsFile.writeText(broken)

        val saved = assertIs<AdStore.SavedAds.Unreadable>(AdStore(dir).loadAds())

        assertContains(saved.why, "not JSON")
        assertEquals(broken, adsFile.readText())
        assertEquals(listOf("ads.json"), dir.list()!!.toList())
    }

    @Test
    fun `an empty file is unreadable, not an empty list`() {
        adsFile.writeText("")

        assertIs<AdStore.SavedAds.Unreadable>(AdStore(dir).loadAds())
        assertEquals("", adsFile.readText())
    }

    @Test
    fun `something that cannot be opened as a file is unreadable too, and loading does not throw`() {
        adsFile.mkdir()

        assertIs<AdStore.SavedAds.Unreadable>(AdStore(dir).loadAds())
        assertEquals(true, adsFile.isDirectory)
    }

    @Test
    fun `a file with an ad the app does not know is reported whole, with the ad in the reason`() {
        val fromAnotherVersion = """[{"str":"1","type":"I","r":1,"g":2,"b":3},{"str":"HELLO","type":"Z"}]"""
        adsFile.writeText(fromAnotherVersion)

        val saved = assertIs<AdStore.SavedAds.Unreadable>(AdStore(dir).loadAds())

        assertContains(saved.why, "ad 2")
        assertEquals(fromAnotherVersion, adsFile.readText())
    }

    @Test
    fun `the next save replaces a file that could not be read`() {
        adsFile.writeText("garbage")
        val store = AdStore(dir)
        assertIs<AdStore.SavedAds.Unreadable>(store.loadAds())

        store.saveAds(someAds)

        assertEquals(wire(someAds), wire(assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds()).ads))
    }

    @Test
    fun `no ad period is saved at first`() {
        assertNull(AdStore(dir).loadPeriod())
        assertFalse(periodFile.exists())
    }

    @Test
    fun `the ad period comes back after a restart and does not change the ads file`() {
        AdStore(dir).saveAds(someAds)
        val adsBefore = adsFile.readText()

        AdStore(dir).savePeriod(5)

        assertEquals(5, AdStore(dir).loadPeriod())
        assertEquals(adsBefore, adsFile.readText())
        assertEquals(setOf("ads.json", "ad-period.txt"), dir.list()!!.toSet())
    }

    @Test
    fun `a period of zero or less is not a period`() {
        assertFalse(AdStore.isValidPeriod(0))
        assertFalse(AdStore.isValidPeriod(-3))
        assertEquals(true, AdStore.isValidPeriod(1))

        assertFailsWith<IllegalArgumentException> { AdStore(dir).savePeriod(0) }
        assertFailsWith<IllegalArgumentException> { AdStore(dir).savePeriod(-3) }
        assertFalse(periodFile.exists())
    }

    @Test
    fun `a period file that holds no usable period counts as none`() {
        listOf("", "soon", "0", "-4", "2.5", "99999999999").forEach { content ->
            periodFile.writeText(content)

            assertNull(AdStore(dir).loadPeriod(), "for >$content<")
        }
    }

    @Test
    fun `a period file with a line end is read`() {
        periodFile.writeText("12\n")

        assertEquals(12, AdStore(dir).loadPeriod())
    }
}
