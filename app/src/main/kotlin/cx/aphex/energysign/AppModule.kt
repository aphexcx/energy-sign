package cx.aphex.energysign

import android.os.SystemClock
import cx.aphex.energysign.ai.AIReplies
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.ai.aiRepliesOrNone
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import org.koin.dsl.module

/**
 * THE APP'S CLOCK, in ms: the one MessageManager counts on (it goes on while the board sleeps).
 * WHEN SOMETHING ARRIVED is this clock where the thing enters the app, read before anything is
 * logged or handed on: the first statement of the Bluetooth write callback and of the adb
 * hook's receive. A key has its event's own time, on this clock (KeyboardManager.arrivedAt).
 */
fun signClockMs(): Long = SystemClock.elapsedRealtime()

val appModule = module {
    single { MessageRepository() }
    single { OwnerQueue() }
    // The time of the queries (the notice, the pauses, the hourly budget) is counted on the
    // clock that goes on while the board sleeps.
    single { MessageManager(get(), get(), get(), nowMs = ::signClockMs) }
    // Built inside MessageManager's construction, on the main thread, before the sign link
    // starts: whatever fails in building the replies costs the replies, not the sign.
    single<AIReplySource> { aiRepliesOrNone(warn = { logW(it) }) { AIReplies(get()) } }
}
