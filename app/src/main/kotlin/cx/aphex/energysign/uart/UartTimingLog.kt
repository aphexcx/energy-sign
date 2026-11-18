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
 *     rx t_us=T n=N bytes=[...] chunks=C first_us=F empty_us=E
 *         One request read from the sign. T: the read that completed it returned. N and bytes:
 *         what was read (bytes outside printable ASCII as \xNN). C: how many reads it came in.
 *         F: the first of those reads returned. E: the last read before it that found nothing
 *         returned, so the first byte became readable between E and F. Two request bytes in one
 *         rx with F - E of a few ms mean the platform delivered them together: it held the
 *         first one back, and nothing in the app can fetch it sooner.
 *     tx t_us=T n=W/L kind=report|message call_us=C rx_us=R
 *         One write. T: write() returned. C: write() was called. W of L bytes were taken.
 *         report is a chooser poll report, message a full message. R: the t_us of the latest
 *         rx line, so T - R is request-to-reply inside the app.
 *     push t_us=T call_us=C break_us=B guard_us=G
 *         One push of the chooser (contract VERSION 3). C: the break was asked for. B: that call
 *         returned. G: the guard after the break ended. T: the message was written (its tx line
 *         comes just before this one).
 *     main t_us=T posted_us=P lag_us=L
 *         How late the main thread ran something posted to it when an rx was seen: what a
 *         request handled on the main thread, as before this link, would have waited.
 *     stat t_us=T window_ms=W polls=P rx=N read_avg_us=A read_max_us=M gap_max_us=G cpu_ms=C
 *         Every ten seconds: P reads of the UART in W ms, N of them with bytes, the mean and
 *         longest time inside read(), the longest time between two reads (the link thread was
 *         kept waiting when this is far above its pause), and the CPU time the link thread used.
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

    /** A request was read: [count] bytes of [buffer], in [chunks] reads from [firstNs] to [lastNs]. */
    fun rx(firstNs: Long, lastNs: Long, emptyNs: Long, chunks: Int, buffer: ByteArray, count: Int) {
        lastRxNs = lastNs
        if (!enabled(lastNs)) return
        rxCount++
        emit(
            "rx t_us=${lastNs / 1000} n=$count bytes=[${printable(buffer, count)}] chunks=$chunks " +
                    "first_us=${firstNs / 1000} empty_us=${emptyNs / 1000}"
        )
    }

    /** A write returned: [wrote] of [of] bytes. */
    fun tx(callNs: Long, returnNs: Long, wrote: Int, of: Int, isPollReport: Boolean) {
        if (!enabled(returnNs)) return
        emit(
            "tx t_us=${returnNs / 1000} n=$wrote/$of kind=${if (isPollReport) "report" else "message"} " +
                    "call_us=${callNs / 1000} rx_us=${lastRxNs / 1000}"
        )
    }

    /** A push finished. */
    fun push(callNs: Long, breakReturnedNs: Long, guardEndNs: Long, doneNs: Long) {
        if (!enabled(doneNs)) return
        emit(
            "push t_us=${doneNs / 1000} call_us=${callNs / 1000} break_us=${breakReturnedNs / 1000} " +
                    "guard_us=${guardEndNs / 1000}"
        )
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
                        "gap_max_us=${gapMaxNs / 1000} cpu_ms=${(cpuNs - windowCpuStartNs) / 1_000_000}"
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
