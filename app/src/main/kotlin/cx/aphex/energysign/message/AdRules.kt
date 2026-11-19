package cx.aphex.energysign.message

import cx.aphex.energysign.ext.toNormalized

/**
 * What every ad is held to, wherever it comes from: an ad line the phone sends ([AdLineParser])
 * or the saved list read back at a start ([AdCodec]). A file may have been written by an older
 * version of the app, which had none of these rules, so the file is not trusted more than the
 * phone is. The third rule, the size of the JSON, is [AdSizeLimit].
 */
object AdRules {
    /** The longest an ad stays. */
    const val MAX_AD_DELAY_MS: Int = 30_000

    /** The shortest, and the step: an ad stays whole seconds. */
    const val MS_PER_SECOND: Int = 1000

    /**
     * The text of an ad: only what the sign can draw, the characters from the space to `~`, the
     * heart (0x7F) and the vertical tab (0x0B), which is a thin space on the sign. Look-alikes
     * of ASCII characters are replaced by them first; anything else, the other control
     * characters included, is taken out.
     */
    fun text(raw: String): String = raw.toNormalized().filter { it in ' '..Message.HEART || it == Message.VT }

    /**
     * The chonky or one-by-one ad that [make] builds from [text], which has been through [text]
     * already: cut to fit the sign ([AdSizeLimit]), or null when there is no text. An ad without
     * a text is left out, because the firmware reads past the end of an empty string. [note]
     * gets one line when the ad was cut or left out.
     */
    fun <M : Message> textAd(text: String, note: (String) -> Unit, make: (String) -> M): M? {
        if (text.isEmpty()) {
            note("An ad without a text was left out.")
            return null
        }
        return AdSizeLimit.fit(text, note, make)
    }

    /**
     * The line for the track that is playing, "artist - title". Nobody types it: it is put in
     * the place an ad list has for it when the ad round is made, and it is announced when the
     * track starts. Both go to the sign, so it is held to the text rule and to the size limit
     * here, where the message is made. [note] gets one line when it was cut.
     */
    fun nowPlaying(artist: String, title: String, note: (String) -> Unit): Message.NowPlayingTrackMessage =
        AdSizeLimit.fit(text("$artist - $title"), note) { Message.NowPlayingTrackMessage(it) }

    /**
     * The `dly` of an ad for a wish of [ms]: whole seconds, from one to thirty.
     *
     * More than thirty seconds becomes thirty. So does a negative number: `dly` is a Short, and
     * the app used to save 32 clocks (33 seconds) as -32536. A part of a second is dropped, and
     * less than one second is one. Whole seconds also keep the firmware of 2026-11 from hanging:
     * its chonky routine never ends on a `dly` whose low byte is odd.
     */
    fun delay(ms: Long): Short =
        when {
            ms < 0 || ms > MAX_AD_DELAY_MS -> MAX_AD_DELAY_MS.toLong()
            else -> (ms / MS_PER_SECOND * MS_PER_SECOND).coerceAtLeast(MS_PER_SECOND.toLong())
        }.toShort()
}
