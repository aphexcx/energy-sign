package cx.aphex.energysign.ext

import java.util.*

fun String.toNormalized(): String {
    var newStr = this

    // Filter out emojis
//    newStr = newStr.filterIndexed { index, c ->
//        c.isLetterOrDigit()
//                || !UCharacter.hasBinaryProperty(
//            newStr.codePointAt(index), UProperty.EMOJI
//        )
//                && !UCharacter.hasBinaryProperty(
//            newStr.codePointAt(index), UProperty.EMOJI_COMPONENT
//        )
//                && !c.isSurrogate()
//    }
//    newStr = EmojiParser.removeAllEmojis(newStr)

    // Convert Unicode chars to similar looking ASCII chars
    unicodeCharMap.forEach { (unichr, replacement) ->
        newStr = newStr.replace(unichr, replacement)
    }

    // Run java text normalizer
//    newStr = Normalizer
//        .normalize(newStr, Normalizer.Form.NFKC)
//        .replace("[^\\p{ASCII}]", "")

    // Strip out all remaining non-ascii chars
    newStr = newStr.replace(Regex("[^\\p{ASCII}]"), "")

    return newStr
}


private val unicodeCharMap: HashMap<String, String> = hashMapOf(
    "\u00AB" to "\"",
    "\u00AD" to "-",
    "\u00B4" to "'",
    "\u00BB" to "\"",
    "\u00F7" to "/",
    "\u01C0" to "|",
    "\u01C3" to "!",
    "\u02B9" to "'",
    "\u02BA" to "\"",
    "\u02BC" to "'",
    "\u02C4" to "^",
    "\u02C6" to "^",
    "\u02C8" to "'",
    "\u02CB" to "`",
    "\u02CD" to "_",
    "\u02DC" to "~",
    "\u0300" to "`",
    "\u0301" to "'",
    "\u0302" to "^",
    "\u0303" to "~",
    "\u030B" to "\"",
    "\u030E" to "\"",
    "\u0331" to "_",
    "\u0332" to "_",
    "\u0338" to "/",
    "\u0589" to ":",
    "\u05C0" to "|",
    "\u05C3" to ":",
    "\u066A" to "%",
    "\u066D" to "*",
    "\u200B" to " ",
    "\u2010" to "-",
    "\u2011" to "-",
    "\u2012" to "-",
    "\u2013" to "-",
    "\u2014" to "-",
    "\u2015" to "--",
    "\u2016" to "||",
    "\u2017" to "_",
    "\u2018" to "'",
    "\u2019" to "'",
    "\u201A" to ",",
    "\u201B" to "'",
    "\u201C" to "\"",
    "\u201D" to "\"",
    "\u201E" to "\"",
    "\u201F" to "\"",
    "\u2032" to "'",
    "\u2033" to "\"",
    "\u2034" to "''",
    "\u2035" to "`",
    "\u2036" to "\"",
    "\u2037" to "''",
    "\u2038" to "^",
    "\u2039" to "<",
    "\u203A" to ">",
    "\u203D" to "?",
    "\u2044" to "/",
    "\u204E" to "*",
    "\u2052" to "%",
    "\u2053" to "~",
    "\u2060" to " ",
    "\u20E5" to "\\",
    "\u2212" to "-",
    "\u2215" to "/",
    "\u2216" to "\\",
    "\u2217" to "*",
    "\u2223" to "|",
    "\u2236" to ":",
    "\u223C" to "~",
    "\u2264" to "<=",
    "\u2265" to ">=",
    "\u2266" to "<=",
    "\u2267" to ">=",
    "\u2303" to "^",
    "\u2329" to "<",
    "\u232A" to ">",
    "\u266F" to "#",
    "\u2731" to "*",
    "\u2758" to "|",
    "\u2762" to "!",
    "\u27E6" to "[",
    "\u27E8" to "<",
    "\u27E9" to ">",
    "\u2983" to "{",
    "\u2984" to "}",
    "\u3003" to "\"",
    "\u3008" to "<",
    "\u3009" to ">",
    "\u301B" to "]",
    "\u301C" to "~",
    "\u301D" to "\"",
    "\u301E" to "\"",
    "\uFEFF" to " "
)
