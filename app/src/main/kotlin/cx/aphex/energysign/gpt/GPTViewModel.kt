package cx.aphex.energysign.gpt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aallam.openai.api.BetaOpenAI
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.message.MessageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.CoroutineContext

@OptIn(BetaOpenAI::class, ExperimentalCoroutinesApi::class)
class GPTViewModel(
    private val defaultDispatcher: CoroutineContext = Dispatchers.IO.limitedParallelism(1)
) : ViewModel(), KoinComponent {

    private val messageManager: MessageManager by inject()

    private var fetchAnswerJob: Job? = null

    fun generateAnswer(content: String) {
        logD("generateAnswer called!!!!")

        viewModelScope.launch(defaultDispatcher) {
            messageManager.setGeneratingThought(true)

            val currentAnswerChunks = mutableListOf<String>()

            fetchAnswerJob =
                OpenAIClient.generateAnswer(content, listOf()) //, chatLog.value)
                    .onStart {
                        currentAnswerChunks.clear()
                    }
                    .mapNotNull { chunk ->
                        chunk.choices.firstOrNull()?.delta?.content
                    }
                    .buffer()
                    .flowOn(defaultDispatcher)
                    .onEach { content ->
                        logD(
                            "got $content, currentAnswerChunks= ${currentAnswerChunks}"
                        )
//                        delay(24)
                        currentAnswerChunks.add(content)
                        logD(
                            "added to currentAnswerChunks= ${currentAnswerChunks}"
                        )
                        messageManager.processPartialThought(content)
                    }
                    .onCompletion { cause ->
                        logE("generateAnswer Completed: $cause")
                        messageManager.setGeneratingThought(false)
//                        messageManager.processPartialSheepThought(currentAnswerChunks.joinToString(""))
                        messageManager.processNewSheepThought(currentAnswerChunks.joinToString(""))
                    }
                    .catch { cause ->
                        logE("generateAnswer Exception: $cause")
//                        updateLastChatMessage(cause.message ?: "Error")
                    }
                    .launchIn(viewModelScope)
        }
    }


}
