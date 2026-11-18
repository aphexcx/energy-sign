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
     * The text of an ad: only what the sign's fonts have, the characters from the space to `~`
     * and the heart (0x7F). Look-alikes of ASCII characters are replaced by them first; anything
     * else, control characters included, is taken out.
     */
    fun text(raw: String): String = raw.toNormalized().filter { it in ' '..Message.HEART }

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
