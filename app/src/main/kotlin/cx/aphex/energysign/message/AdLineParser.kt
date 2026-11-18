package cx.aphex.energysign.message

import com.vdurmont.emoji.EmojiParser
import cx.aphex.energysign.message.Message.ColorMessage.ChonkySlide
import cx.aphex.energysign.message.Message.ColorMessage.IconInvaders
import cx.aphex.energysign.message.Message.ColorMessage.OneByOneMessage

/** The colours an ad line can have, as colour ints. The app fills this from its colour resources. */
data class AdPalette(
    val instagram: Int,
    val instahandle: Int,
    val twitter: Int,
    val soundcloud: Int,
    val twitch: Int,
    val green: Int,
    val pink: Int,
    /** A chonky ad without a colour emoji. */
    val chonkyDefault: Int,
    /** An icon without a colour emoji. */
    val iconDefault: Int,
)

/**
 * Turns the text of the ad command into the ad list. One ad per line; the first emoji of the
 * line says which kind:
 *
 *  - the CL button: a chonky ad (type C)
 *  - the O button: a one-by-one ad (type O)
 *  - the railway track: the place of the now-playing track
 *  - the invader: an icon; the last emoji of the line says which
 *
 * Any other line is skipped. A heart-coloured emoji anywhere in the line gives the colour, and
 * every clock emoji adds a second to the time the ad stays. The text of an ad is the line with
 * everything the sign's fonts lack taken out ([AdRules.text]); it is not trimmed, so a space at
 * its start stays. The text, the delay and the size of an ad follow [AdRules] and [AdSizeLimit],
 * the same rules the saved list is held to when it is read back ([AdCodec]).
 */
object AdLineParser {
    /** The mantelpiece clock. Counted by code point, so with or without a variation selector behind it. */
    private const val CLOCK: Int = 0x1F570

    private val CL_BUTTON = emoji(0x1F191)
    private val O_BUTTON = emoji(0x1F17E)
    private val RAILWAY_TRACK = emoji(0x1F6E4)
    private val INVADER = emoji(0x1F47E)
    private val ALIEN = emoji(0x1F47D)
    private val COLLISION = emoji(0x1F4A5)
    private val AB_BUTTON = emoji(0x1F18E)
    private val SHEEP = emoji(0x1F411)
    private val MILKY_WAY = emoji(0x1F30C)
    private val BLOSSOM = emoji(0x1F33C)

    private val YELLOW_HEART = emoji(0x1F49B)
    private val RED_CIRCLE = emoji(0x1F534)
    private val RED_HEART = emoji(0x2764, 0xFE0F)
    private val BLUE_HEART = emoji(0x1F499)
    private val ORANGE_HEART = emoji(0x1F9E1)
    private val PURPLE_HEART = emoji(0x1F49C)
    private val GREEN_HEART = emoji(0x1F49A)
    private val GROWING_HEART = emoji(0x1F497)

    /**
     * The ads in [adString]. [warn] gets one line for every ad whose text had to be cut
     * ([AdSizeLimit]).
     */
    fun parse(adString: String, palette: AdPalette, warn: (String) -> Unit = {}): List<Message> =
        adString.lines().mapNotNull { line ->
            when {
                line.startsWith(CL_BUTTON) -> {
                    val color = colorOf(line, palette) ?: palette.chonkyDefault
                    val delay = delayMs(line)
                    AdRules.textAd(adText(line), warn) { text -> ChonkySlide(text, color, delay) }
                }

                line.startsWith(O_BUTTON) -> {
                    val color = colorOf(line, palette) ?: palette.instagram
                    val delay = delayMs(line)
                    AdRules.textAd(adText(line), warn) { text -> OneByOneMessage(text, color, delay) }
                }

                line.startsWith(RAILWAY_TRACK) -> Message.NowPlayingTrackMessage("")

                line.startsWith(INVADER) -> {
                    val color = colorOf(line, palette) ?: palette.iconDefault
                    when (EmojiParser.extractEmojis(line).last()) {
                        INVADER -> IconInvaders.Enemy1(color)
                        ALIEN -> IconInvaders.Enemy2(color)
                        COLLISION -> IconInvaders.Explosion(color)
                        AB_BUTTON -> IconInvaders.ANJUNA(color)
                        SHEEP -> IconInvaders.BAAAHS(color)
                        MILKY_WAY -> IconInvaders.DREAMSTATE(color)
                        BLOSSOM -> IconInvaders.EDC(color)
                        else -> IconInvaders.Enemy1(color)
                    }
                }

                else -> null
            }
        }

    /**
     * How long the ad of [line] stays: one second, and one more for every clock in the line, at
     * most [AdRules.MAX_AD_DELAY_MS] (32 clocks used to overflow the Short that `dly` is).
     */
    fun delayMs(line: String): Short {
        val clocks = line.codePoints().filter { it == CLOCK }.count()
        return AdRules.delay((clocks + 1) * AdRules.MS_PER_SECOND)
    }

    /**
     * The text the sign shows for [line]. `<3` becomes the sign's heart character. That is done
     * after the emoji are gone, and only for a typed `<3`: a heart emoji in an ad line chooses
     * the colour and draws nothing.
     */
    fun adText(line: String): String = AdRules.text(line).replace("<3", Message.HEART.toString())

    private fun colorOf(line: String, palette: AdPalette): Int? = when {
        line.contains(YELLOW_HEART) -> palette.instagram
        line.contains(RED_CIRCLE) || line.contains(RED_HEART) -> palette.instahandle
        line.contains(BLUE_HEART) -> palette.twitter
        line.contains(ORANGE_HEART) -> palette.soundcloud
        line.contains(PURPLE_HEART) -> palette.twitch
        line.contains(GREEN_HEART) -> palette.green
        line.contains(GROWING_HEART) -> palette.pink
        else -> null
    }

    private fun emoji(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)
}
