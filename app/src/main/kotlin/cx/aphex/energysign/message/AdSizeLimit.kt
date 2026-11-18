package cx.aphex.energysign.message

/**
 * Keeps an ad inside what the sign can read.
 *
 * The sign reads a message into a JSON buffer of about 665 bytes, and a `str` of about 570
 * characters. The app writes `<`, `>`, `&`, `=` and `'` as six bytes each (Gson's `<`
 * form), so the limit is on the bytes of the JSON and not on the number of characters.
 */
object AdSizeLimit {
    /** The most bytes the JSON of one ad may have on the wire. */
    const val MAX_AD_JSON_BYTES: Int = 500

    /** The bytes the app writes to the sign for [message]. */
    fun jsonBytes(message: Message): Int = SignResponder.GSON.toJson(message).toByteArray().size

    /**
     * The ad [make] builds from [text], or from the longest beginning of [text] whose ad fits
     * in [MAX_AD_JSON_BYTES]. The text is cut between characters, before it is written as JSON,
     * so never inside a `<`. [onCut] is called once when the text was cut.
     */
    fun <M : Message> fit(text: String, onCut: (String) -> Unit, make: (String) -> M): M {
        val whole = make(text)
        val wholeBytes = jsonBytes(whole)
        if (wholeBytes <= MAX_AD_JSON_BYTES) return whole

        // A longer text never gives a shorter JSON, so the longest beginning that fits is found by halving.
        var fits = 0
        var tooLong = text.length
        while (tooLong - fits > 1) {
            val middle = (fits + tooLong) / 2
            if (jsonBytes(make(text.take(middle))) <= MAX_AD_JSON_BYTES) fits = middle else tooLong = middle
        }
        onCut(
            "Ad cut from ${text.length} to $fits characters: its JSON was $wholeBytes bytes, " +
                    "the limit is $MAX_AD_JSON_BYTES. It starts \"${text.take(24)}\"."
        )
        return make(text.take(fits))
    }
}
