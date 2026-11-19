package cx.aphex.energysign

import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.Relay
import java.text.SimpleDateFormat
import java.util.*

object ScreenLogger {
    //TODO maybe have the netire log in here rather than line by line
    /** Serialized: accept() from any thread, and a caller never waits while another's line is handed on. */
    val logLines: Relay<String> = BehaviorRelay.create<String>().toSerialized()

    /** One per thread: SimpleDateFormat is not thread-safe, and the thread that answers the sign must not queue for it. */
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss")
    }

    /** From any thread (the sign link thread logs too). */
    fun log(s: String) {
        val timestamp = timeFormat.get()!!.format(Date(System.currentTimeMillis()))

        logLines.accept("$timestamp $s")
    }
}
