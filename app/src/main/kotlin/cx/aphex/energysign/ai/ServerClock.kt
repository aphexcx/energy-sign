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
 * own clock does.
 */
class ServerClock(
    /** A clock that only counts (milliseconds since some start), untouched by the wall clock. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Anchor(val serverTime: Instant, val atMs: Long)

    @Volatile
    private var anchor: Anchor? = null

    /** A response arrived just now and its `Date` header said [serverTime]. */
    fun sync(serverTime: Instant) {
        anchor = Anchor(serverTime, monotonicMs())
    }

    /** Takes the time from the `Date` header of a response, when there is one that can be read. */
    fun syncFromDateHeader(value: String?) {
        parseHttpDate(value)?.let(::sync)
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
