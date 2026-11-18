package cx.aphex.energysign

import cx.aphex.energysign.ai.AIReplies
import cx.aphex.energysign.ai.AIReplySource
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import org.koin.dsl.module

val appModule = module {
    single { MessageRepository() }
    single { OwnerQueue() }
    single { MessageManager(get(), get(), get()) }
    single<AIReplySource> { AIReplies(get()) }
}
