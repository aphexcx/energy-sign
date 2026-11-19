package cx.aphex.energysign

import cx.aphex.energysign.gpt.GPTViewModel
import cx.aphex.energysign.message.MessageManager
import cx.aphex.energysign.message.MessageRepository
import cx.aphex.energysign.message.OwnerQueue
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { MessageRepository() }
    single { OwnerQueue() }
    single { MessageManager(get(), get(), get()) }
    viewModel { GPTViewModel() }
}
