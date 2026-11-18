package cx.aphex.energysign.ai

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * The time as the provider's server told it, kept running by a clock that only counts.
 *
 * The sign's board has no clock battery: after a start without network its clock reads 1969 or
 * 2008, and a clock that is merely a day off cannot be told from a right one by looking at it.
 * Android 8.1 gives an app no sign that network time has arrived. But every response of the API
 * carries the server's time in its `Date` header, over TLS. That time is taken here, and from
 * then on the time is "what the server said, plus what has passed since", whatever the device's
 * own clock does. The time is true or absent: a response that is not the API's, or not fresh,
 * tells it nothing (see [syncFromHeaderFields]).
 *
 * WHO MAY TELL IT, as the callers keep it: only a response that was read to its end and found to
 * be whole and of the expected kind (a message, the description of the model), with the moment
 * its headers arrived taken BEFORE its body was read. Never an error response: the body of an
 * error has been read by the time the error is known, so nobody can say when its headers came,
 * and "an error of the API" cannot be told from a page that copies one.
 */
class ServerClock(
    /** A clock that only counts (milliseconds since some start), untouched by the wall clock. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Anchor(val serverTime: Instant, val atMs: Long)

    @Volatile
    private var anchor: Anchor? = null

    /**
     * This moment, on the clock that only counts: taken when a response ARRIVES, and handed to
     * [syncFromHeaders] once the response has been read and recognised. Reading it can take
     * seconds (the first one after a start), and the time must not run behind by that.
     */
    fun arrival(): Long = monotonicMs()

    /** A response arrived at [arrivedAtMs] (by [arrival]; now when not given) and its `Date` header said [serverTime]. */
    fun sync(serverTime: Instant, arrivedAtMs: Long? = null) {
        anchor = Anchor(serverTime, arrivedAtMs ?: monotonicMs())
    }

    /**
     * Takes the time from the headers of a response, when they tell the time of NOW and say one
     * thing only. [dates] and [ages] are ALL the `Date` and `Age` fields of the response, as
     * they came:
     * - exactly one `Date`, and it is an HTTP date to the letter ([parseHttpDate]);
     * - no `Age` at all, or exactly one that reads `0`. An `Age` says the response has been kept
     *   somewhere, and a cache hands out the `Date` of the moment it stored the response. Any
     *   other value is old, one that cannot be read too; and two `Age` fields are old whatever
     *   they say: something on the way has added one.
     *
     * The caller answers for the rest: see the class comment.
     */
    fun syncFromHeaderFields(dates: List<String>, ages: List<String>, arrivedAtMs: Long? = null) {
        if (dates.size != 1 || ages.size > 1) return
        if (ages.isNotEmpty() && ages[0].trim() != "0") return
        parseHttpDate(dates[0])?.let { sync(it, arrivedAtMs) }
    }

    /** The same, for a response with one `Date` field at most and one `Age` field at most. */
    fun syncFromHeaders(date: String?, age: String?, arrivedAtMs: Long? = null) =
        syncFromHeaderFields(listOfNotNull(date), listOfNotNull(age), arrivedAtMs)

    /** The time now, or null as long as no server has told it. */
    fun now(): Instant? = anchor?.let { it.serverTime.plusMillis(monotonicMs() - it.atMs) }

    companion object {
        /**
         * The one form an HTTP date has today (RFC 9110, IMF-fixdate), and nothing near it: the
         * day with two digits, the year with four, always GMT. STRICT: a date that does not
         * exist (31 November) and a weekday that is not that date's are refused, not made to fit.
         */
        private val HTTP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US).withResolverStyle(ResolverStyle.STRICT)

        /** An HTTP date ("Fri, 20 Nov 2026 05:30:00 GMT"), or null when it is not exactly one. */
        fun parseHttpDate(value: String?): Instant? =
            try {
                value?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { LocalDateTime.parse(it, HTTP_DATE).toInstant(ZoneOffset.UTC) }
            } catch (e: DateTimeException) {
                null
            }
    }
}
