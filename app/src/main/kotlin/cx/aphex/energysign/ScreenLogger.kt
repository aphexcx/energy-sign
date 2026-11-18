package cx.aphex.energysign

import com.jakewharton.rxrelay3.BehaviorRelay
import java.text.SimpleDateFormat
import java.util.*

object ScreenLogger {
    //TODO maybe have the netire log in here rather than line by line
    val logLines: BehaviorRelay<String> = BehaviorRelay.create()

    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    /**
     * From any thread (the sign link thread logs too). One caller at a time: SimpleDateFormat is
     * not thread-safe, and neither is the relay for concurrent accept() calls.
     */
    fun log(s: String) = synchronized(timeFormat) {
        val timestamp = timeFormat.format(Date(System.currentTimeMillis()))

        logLines.accept("$timestamp $s")
    }
}
