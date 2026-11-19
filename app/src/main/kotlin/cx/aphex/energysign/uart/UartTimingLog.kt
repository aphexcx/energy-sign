package cx.aphex.energysign.uart

/**
 * The timing log of the sign link: one line per event, for measuring on the device where the
 * time between the sign's request and the app's answer goes. Off unless [isEnabled] says so.
 *
 * On the device (EnergySignUartManager wires this up) the tag is `SignUart` and the flag is the
 * tag's log level:
 *
 *     adb shell setprop log.tag.SignUart VERBOSE     (on, within a second, no restart)
 *     adb shell setprop log.tag.SignUart INFO        (off)
 *     adb logcat -v usec -s SignUart
 *
 * Every time is the monotonic clock in microseconds (`System.nanoTime() / 1000`), so times of
 * different lines can be subtracted. The lines:
 *
 *     rx t_us=T n=N bytes=[...] chunks=1 first_us=T empty_us=E
 *         One request read from the sign: N bytes, the last of them `~` or `?` (bytes outside
 *         printable ASCII as \xNN). T: the read that found it returned. E: the last read before
 *         it that found nothing returned, so the byte became readable between E and T. Two
 *         request bytes in one rx with T - E of a few ms mean the platform delivered them
 *         together: it held the first one back, and nothing in the app can fetch it sooner.
 *         (chunks and first_us are kept for the tool that reads this log; a request is one read.)
 *     tx t_us=T n=W/L kind=report|message call_us=C rx_us=R
 *         One frame handed to the transmitter. T: the last write() returned. C: the first was
 *         called. W of L bytes were taken; W below L is a failed write. report is a chooser poll
 *         report, message a full message. R: the t_us of the latest rx line, so T - R is
 *         request-to-reply inside the app.
 *     drop t_us=T n=N bytes=[...]
 *         Bytes read away unanswered: they do not end in a request byte (the name of a parse
 *         error the firmware prints before it stops listening), or the request is old (the
 *         link was kept from the wire for longer than the sign listens).
 *     main t_us=T posted_us=P lag_us=L
 *         How late the main thread ran something posted to it when an rx was seen: what a
 *         request handled on the main thread, as before this link, would have waited.
 *     stat t_us=T window_ms=W polls=P rx=N read_avg_us=A read_max_us=M gap_max_us=G sleep_over_max_us=S cpu_ms=C
 *         Every ten seconds: P reads of the UART in W ms, N requests among them, the mean and
 *         longest time inside read(), the longest time between two reads, the most a pause of
 *         the loop overran what it asked for, and the CPU time the link thread used. A large G
 *         with a large S: the thread was not woken in time (something else had the processor).
 *         With a large M instead: a read was slow (the peripheral service). With neither: the
 *         time went into a reply.
 *
 * Called from the link thread, except [mainLag] (main thread); [emit] must be thread-safe.
 */
class UartTimingLog(
    /** The flag. Asked at most once per [FLAG_CHECK_EVERY_NS] of event time. */
    private val isEnabled: () -> Boolean,
    private val emit: (line: String) -> Unit,
    /** CPU time of the calling thread in nanoseconds, for the stat line. */
    private val threadCpuNanos: () -> Long = { 0L },
) {
    @Volatile
    private var on = false

    @Volatile
    private var flagChecked = false

    @Volatile
    private var flagCheckedNs = 0L

    @Volatile
    private var lastRxNs = 0L

    // The stat window; link thread only.
    private var windowOpen = false
    private var windowStartNs = 0L
    private var windowCpuStartNs = 0L
    private var polls = 0L
    private var rxCount = 0L
    private var readTotalNs = 0L
    private var readMaxNs = 0L
    private var gapMaxNs = 0L
    private var sleepOverMaxNs = 0L
    private var previousPollStartNs = 0L

    /** Whether lines are being written, as of [nowNs]. */
    fun enabled(nowNs: Long): Boolean {
        if (!flagChecked || nowNs - flagCheckedNs >= FLAG_CHECK_EVERY_NS) {
            on = isEnabled()
            flagChecked = true
            flagCheckedNs = nowNs
        }
        return on
    }

    /** A request was read at [readNs]: [count] bytes of [buffer]. The read before it that found nothing returned at [emptyNs]. */
    fun rx(readNs: Long, emptyNs: Long, buffer: ByteArray, count: Int) {
        lastRxNs = readNs
        if (!enabled(readNs)) return
        rxCount++
        emit(
            "rx t_us=${readNs / 1000} n=$count bytes=[${printable(buffer, count)}] chunks=1 " +
                    "first_us=${readNs / 1000} empty_us=${emptyNs / 1000}"
        )
    }

    /** A frame was handed to the transmitter: [wrote] of [of] bytes. [kind]: report or message. */
    fun tx(callNs: Long, returnNs: Long, wrote: Int, of: Int, kind: String) {
        if (!enabled(returnNs)) return
        emit("tx t_us=${returnNs / 1000} n=$wrote/$of kind=$kind call_us=${callNs / 1000} rx_us=${lastRxNs / 1000}")
    }

    /** [count] bytes of [buffer] were read away unanswered. */
    fun dropped(atNs: Long, buffer: ByteArray, count: Int) {
        if (!enabled(atNs)) return
        emit("drop t_us=${atNs / 1000} n=$count bytes=[${printable(buffer, count)}]")
    }

    /** A pause of the loop that asked for [askedNs] took [tookNs]. */
    fun slept(askedNs: Long, tookNs: Long) {
        if (on) sleepOverMaxNs = maxOf(sleepOverMaxNs, tookNs - askedNs)
    }

    /** Something posted to the main thread at [postedNs] ran at [ranNs]. Posted only while [enabled]. */
    fun mainLag(postedNs: Long, ranNs: Long) {
        emit("main t_us=${ranNs / 1000} posted_us=${postedNs / 1000} lag_us=${(ranNs - postedNs) / 1000}")
    }

    /** One read of the UART, with or without bytes, from [startNs] to [endNs]. */
    fun poll(startNs: Long, endNs: Long) {
        if (!enabled(endNs)) {
            windowOpen = false
            return
        }
        if (windowOpen && startNs - windowStartNs >= STAT_EVERY_NS) {
            val cpuNs = threadCpuNanos()
            emit(
                "stat t_us=${startNs / 1000} window_ms=${(startNs - windowStartNs) / 1_000_000} polls=$polls " +
                        "rx=$rxCount read_avg_us=${readTotalNs / polls / 1000} read_max_us=${readMaxNs / 1000} " +
                        "gap_max_us=${gapMaxNs / 1000} sleep_over_max_us=${sleepOverMaxNs / 1000} " +
                        "cpu_ms=${(cpuNs - windowCpuStartNs) / 1_000_000}"
            )
            windowOpen = false
        }
        if (!windowOpen) {
            windowOpen = true
            windowStartNs = startNs
            windowCpuStartNs = threadCpuNanos()
            polls = 0
            rxCount = 0
            readTotalNs = 0
            readMaxNs = 0
            gapMaxNs = 0
            sleepOverMaxNs = 0
        } else {
            gapMaxNs = maxOf(gapMaxNs, startNs - previousPollStartNs)
        }
        previousPollStartNs = startNs
        polls++
        readTotalNs += endNs - startNs
        readMaxNs = maxOf(readMaxNs, endNs - startNs)
    }

    private fun printable(buffer: ByteArray, count: Int): String = buildString {
        for (i in 0 until count.coerceIn(0, buffer.size)) {
            val b = buffer[i].toInt() and 0xFF
            if (b in 0x20..0x7E && b != '\\'.code) append(b.toChar()) else append("\\x%02X".format(b))
        }
    }

    companion object {
        /** The logcat tag of every line. */
        const val TAG: String = "SignUart"

        const val FLAG_CHECK_EVERY_NS: Long = 1_000_000_000L
        const val STAT_EVERY_NS: Long = 10_000_000_000L
    }
}
