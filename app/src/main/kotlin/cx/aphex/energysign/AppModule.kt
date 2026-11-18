package cx.aphex.energysign

import android.os.SystemClock
import cx.aphex.energysign.ai.AIReplies
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import org.koin.dsl.module

val appModule = module {
    single { MessageRepository() }
    single { OwnerQueue() }
    // The time of the queries (the notice, the pauses, the hourly budget) is counted on the
    // clock that goes on while the board sleeps.
    single { MessageManager(get(), get(), get(), nowMs = { SystemClock.elapsedRealtime() }) }
    single<AIReplySource> { AIReplies(get()) }
}
