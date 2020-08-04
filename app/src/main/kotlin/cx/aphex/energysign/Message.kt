package cx.aphex.energysign

import android.graphics.Color
import androidx.annotation.ColorInt
import io.ktor.utils.io.core.toByteArray

sealed class Message(open val bytes: ByteArray) {

    class UserMessage(val string: String) : Message(string.toByteArray())

    sealed class FlashingAnnouncement(string: String) :
        Message(BEL + string.toByteArray()) {

        object NewMessageAnnouncement : FlashingAnnouncement("NEW MSG IN")
        object NowPlayingAnnouncement : FlashingAnnouncement("NOW${VT}PLAYING")
    }

    class OneByOneMessage(
        val string: String,
        @ColorInt val color: Int,
        val delayMs: UShort = 500u
    ) : Message(
        byteArrayOf(
            SOH, TEXT_ONE_BY_ONE,
            ACK, Color.red(color).toByte(), Color.green(color).toByte(), Color.blue(color).toByte(),
//            DLE, delayMs.highByte, delayMs.lowByte,
            STX,
            *string.toByteArray()
        )
    )

    class ChonkySlide(
        string: String,
        @ColorInt val colorCycle: Int,
        val delayMs: UShort = 500u
//        val colorFrom: Color,
//        val colorTo: Color
    ) : Message(
        byteArrayOf(
            SOH,
            TEXT_CHONKY_SLIDE,
            ACK,
            Color.red(colorCycle).toByte(),
            Color.green(colorCycle).toByte(),
            Color.blue(colorCycle).toByte(),
//        DLE, *delayMs.toByteArray(),
            STX,
            *string.toByteArray()
        )
    )

    /* Messages that control admin-only device modes or settings.
        Starts with a DC (device control) byte.
     */
    sealed class UtilityMessage(bytes: ByteArray) : Message(bytes) {

        data class Chooser(
            val currentIndex: Int,
            val lastIndex: Int,
            val currentMessage: UserMessage
        ) : UtilityMessage(
            byteArrayOf(DC1) + "$currentIndex/${lastIndex}".toByteArray() +
                    byteArrayOf(DLE) + currentMessage.bytes
        )

        object EnableMic : UtilityMessage(byteArrayOf(DC4, 1))
        object DisableMic : UtilityMessage(byteArrayOf(DC4, 0))
    }

    sealed class KeyboardEcho(currentBytes: ByteArray) : Message(
        currentBytes + byteArrayOf('_'.toByte())
    ) {
        data class Input(val currentInput: String) : KeyboardEcho(
            byteArrayOf(ENQ) + currentInput.toByteArray()
        )

        data class InputWarning(val currentInput: String) : KeyboardEcho(
            byteArrayOf(EOT) + currentInput.toByteArray()
        )
    }

    companion object {
        const val BEL: Byte = 7
        const val SOH: Byte = 1
        const val STX: Byte = 2
        const val ETX: Byte = 3
        const val EOT: Byte = 4
        const val ENQ: Byte = 5
        const val ACK: Byte = 6
        const val DLE: Byte = 16
        const val DC1: Byte = 17
        const val DC4: Byte = 20
        const val VT: Byte = 11 //vertical tab; single column
        const val TEXT_CHONKY_SLIDE: Byte = 'C'.toByte()
        const val TEXT_ONE_BY_ONE: Byte = 'O'.toByte()
    }
}

private fun UShort.toByteArray(): ByteArray {
    return byteArrayOf(this.highByte, this.lowByte)
}

inline val UShort.highByte: Byte get() = ((toInt() and 0xff) shr 8).toByte()

inline val UShort.lowByte: Byte get() = (toInt() and 0xff).toByte()


private operator fun Byte.plus(bytes: ByteArray): ByteArray {
    return byteArrayOf(this) + bytes
}

//private operator fun Byte.plus(char: Char): String {
//    return byteArrayOf(this) + byteArrayOf(char.)
//}
//
//private operator fun Byte.plus(string: String): String {
//    return "${this}$string"
//}
