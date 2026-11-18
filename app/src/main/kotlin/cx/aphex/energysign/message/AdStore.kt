package cx.aphex.energysign.message

import cx.aphex.energysign.replaceContent
import cx.aphex.energysign.syncDirectory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What the app keeps about ads across a restart, in [dir] (the app's files directory):
 * the ad list in `ads.json` ([AdCodec]) and, beside it, the ad period in `ad-period.txt`.
 *
 * Loading never writes: a file that cannot be read stays as it is until the next save.
 * Saving is the app's one write-beside, sync, rename, sync-the-directory sequence ([replaceContent]).
 */
class AdStore(
    private val dir: File,
    /** Gets one line when a save is in place but its directory could not be forced to storage. */
    private val warn: (String) -> Unit = {},
    /** The two steps that force a save to storage. A test puts itself in here to see when each is asked for. */
    private val syncData: (FileOutputStream) -> Unit = { it.fd.sync() },
    private val syncDirectory: (File) -> Unit = { it.syncDirectory() },
) {

    /** What [loadAds] found. */
    sealed class SavedAds {
        /** There is no file yet (a first install). */
        object NoFile : SavedAds()

        /** The list as it was saved; it may be empty. */
        class Ads(val ads: List<Message>) : SavedAds()

        /** There is a file and it cannot be read; [why] is one line for the log. */
        class Unreadable(val why: String) : SavedAds()
    }

    private val adsFile = File(dir, ADS_FILE_NAME)
    private val periodFile = File(dir, PERIOD_FILE_NAME)

    /** [note] gets one line for every saved ad that had to be corrected to fit the rules ([AdCodec.decode]). */
    fun loadAds(note: (String) -> Unit = {}): SavedAds {
        if (!adsFile.exists()) return SavedAds.NoFile
        return try {
            SavedAds.Ads(AdCodec.decode(adsFile.readText(), note))
        } catch (e: AdFileException) {
            SavedAds.Unreadable(e.message ?: "no reason given")
        } catch (e: Throwable) {
            // Whatever else goes wrong here, an Error included, must not stop the app from starting.
            SavedAds.Unreadable("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Throws(IOException::class)
    fun saveAds(ads: List<Message>) = adsFile.replaceWith(AdCodec.encode(ads))

    /** The saved ad period, null when none was saved or what is there is not a period. */
    fun loadPeriod(): Int? =
        try {
            if (periodFile.exists()) periodFile.readText().trim().toIntOrNull()?.takeIf(::isValidPeriod) else null
        } catch (e: Throwable) {
            null
        }

    @Throws(IOException::class)
    fun savePeriod(period: Int) {
        require(isValidPeriod(period)) { "An ad period must be 1 or more, not $period" }
        periodFile.replaceWith(period.toString())
    }

    private fun File.replaceWith(text: String) =
        replaceContent(syncData = syncData, syncDirectory = syncDirectory, warn = warn) { it.write(text.toByteArray()) }

    companion object {
        const val ADS_FILE_NAME = "ads.json"
        const val PERIOD_FILE_NAME = "ad-period.txt"

        /** "An ad round every n user messages": n is 1 or more (the app divides by it). */
        fun isValidPeriod(period: Int): Boolean = period >= 1
    }
}
