package cx.aphex.energysign.message

import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage
import cx.aphex.energysign.ClosesAfterWrite
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    // ---- saving: the steps are the app's own ([cx.aphex.energysign.replaceContent], see FileReplaceTest
    // for their order). A test cannot give the store other steps; it can only open the file another way.

    @Test
    fun `the ads and the period are really forced to storage, a descriptor that is gone fails the save and the old file stays`() {
        AdStore(dir).saveAds(someAds)
        AdStore(dir).savePeriod(3)
        val oldAds = adsFile.readText()
        // the store as the app makes it, but for the way the file is opened
        val store = AdStore(dir, open = ::ClosesAfterWrite)

        assertFailsWith<java.io.IOException> { store.saveAds(emptyList()) }
        assertFailsWith<java.io.IOException> { store.savePeriod(9) }

        assertEquals(oldAds, adsFile.readText())
        assertEquals("3", periodFile.readText())
        assertEquals(3, AdStore(dir).loadPeriod())
    }

    @Test
    fun `a directory that cannot be opened to be forced is reported by the app's own step, and the ads and the period are saved`() {
        AdStore(dir).saveAds(someAds)
        val warnings = mutableListOf<String>()
        try {
            assumeTrue(dir.setReadable(false, false) && !dir.canRead(), "this user can read every directory (root?)")
            // the store as the app makes it
            val store = AdStore(dir, warn = { warnings += it })
            store.saveAds(emptyList())
            store.savePeriod(6)
        } finally {
            dir.setReadable(true, false)
        }

        assertEquals("[]", adsFile.readText())
        assertEquals("6", periodFile.readText())
        assertEquals(2, warnings.size, warnings.toString())
        assertContains(warnings[0], "ads.json")
        assertContains(warnings[1], "ad-period.txt")
    }

    @Test
    fun `when the new list cannot be renamed into place the save fails and what is there stays`() {
        // a directory with something in it where ads.json should be
        adsFile.mkdir()
        File(adsFile, "inside").writeText("kept")
        val warnings = mutableListOf<String>()

        val e = assertFailsWith<java.io.IOException> { AdStore(dir, warn = { warnings += it }).saveAds(someAds) }

        assertContains(e.message!!, "rename")
        assertEquals(true, adsFile.isDirectory)
        assertEquals("kept", File(adsFile, "inside").readText())
        assertEquals(emptyList(), warnings)
        assertIs<AdStore.SavedAds.Unreadable>(AdStore(dir).loadAds())
    }

    @Test
    fun `the same for the period`() {
        periodFile.mkdir()
        File(periodFile, "inside").writeText("kept")

        assertFailsWith<java.io.IOException> { AdStore(dir).savePeriod(5) }

        assertEquals("kept", File(periodFile, "inside").readText())
        assertNull(AdStore(dir).loadPeriod())
    }

    // ---- a damaged file

    @Test
    fun `a file with a byte that is not UTF-8 is unreadable, not read with the byte taken out`() {
        // a good list, but for one byte inside the text: 41 FF 42
        val good = """[{"dly":1000,"str":"AB","type":"O","b":3,"g":2,"r":1}]""".toByteArray()
        val at = String(good).indexOf("AB") + 1
        val damaged = good.copyOfRange(0, at) + 0xFF.toByte() + good.copyOfRange(at, good.size)
        adsFile.writeBytes(damaged)

        val saved = assertIs<AdStore.SavedAds.Unreadable>(AdStore(dir).loadAds())

        assertContains(saved.why, "UTF-8")
        assertEquals(damaged.toList(), adsFile.readBytes().toList(), "the file is left as it is")
    }

    @Test
    fun `text that is UTF-8 beyond ASCII is read, and held to the text rule`() {
        adsFile.writeText("""[{"dly":1000,"str":"CAF\u00c9 é OK","type":"O","b":3,"g":2,"r":1}]""")

        val saved = assertIs<AdStore.SavedAds.Ads>(AdStore(dir).loadAds())

        assertEquals("CAF  OK", saved.ads.single().str)
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
