package cx.aphex.energysign.ai

import java.time.DateTimeException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The time as the provider's server told it, kept running by a clock that only counts.
 *
 * The sign's board has no clock battery: after a start without network its clock reads 1969 or
 * 2008, and a clock that is merely a day off cannot be told from a right one by looking at it.
 * Android 8.1 gives an app no sign that network time has arrived. But every response of the API
 * carries the server's time in its `Date` header, over TLS. That time is taken here, and from
 * then on the time is "what the server said, plus what has passed since", whatever the device's
 * own clock does. The time is true or absent: a response that is not the API's, or not fresh,
 * tells it nothing (see [syncFromHeaders]).
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
     * Takes the time from the headers of a response, when it tells the time of NOW: a `Date`
     * that can be read, and no `Age` that says the response has been kept somewhere (a cache
     * hands out the `Date` of the moment it stored the response). An `Age` of 0 is fresh; one
     * that cannot be read counts as old.
     *
     * The caller answers for the rest: it calls this only for a response it has read and
     * recognised as the API's own.
     */
    fun syncFromHeaders(date: String?, age: String?, arrivedAtMs: Long? = null) {
        if (age != null && age.trim().toLongOrNull() != 0L) return
        parseHttpDate(date)?.let { sync(it, arrivedAtMs) }
    }

    /** The time now, or null as long as no server has told it. */
    fun now(): Instant? = anchor?.let { it.serverTime.plusMillis(monotonicMs() - it.atMs) }

    companion object {
        /** An HTTP date ("Fri, 20 Nov 2026 05:30:00 GMT"), or null when it is none. */
        fun parseHttpDate(value: String?): Instant? =
            try {
                value?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            } catch (e: DateTimeException) {
                null
            }
    }
}
