package cx.aphex.energysign

import com.jakewharton.rxrelay3.BehaviorRelay
import java.text.SimpleDateFormat
import java.util.*

object ScreenLogger {
    //TODO maybe have the netire log in here rather than line by line
    val logLines: BehaviorRelay<String> = BehaviorRelay.create()

    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    fun log(s: String) {
        val timestamp = timeFormat.format(Date(System.currentTimeMillis()))

        logLines.accept("$timestamp $s")
    }
}
