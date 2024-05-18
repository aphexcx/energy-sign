package cx.aphex.energysign.gpt

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aallam.openai.api.BetaOpenAI
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logE
import cx.aphex.energysign.ext.logI
import cx.aphex.energysign.message.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlin.coroutines.CoroutineContext

data class GptAnswerResponse(val answer: String, val inReplyTo: Message.Marquee.GPTQuery)
@OptIn(BetaOpenAI::class, ExperimentalCoroutinesApi::class)
class GPTViewModel(
    private val defaultDispatcher: CoroutineContext = Dispatchers.IO.limitedParallelism(1)
) : ViewModel(), KoinComponent {

    val GPTResponse: MutableLiveData<GptAnswerResponse> = MutableLiveData()

    val GPTError: MutableLiveData<Throwable> = MutableLiveData()

    private var fetchAnswerJob: Job? = null

    fun generateAnswer(replyingToMessage: Message.Marquee.GPTQuery) {
        logD("generateAnswer called!!!!")

        viewModelScope.launch(defaultDispatcher) {
            val currentAnswerChunks = mutableListOf<String>()

            fetchAnswerJob =
                OpenAIClient.generateAnswer(replyingToMessage.str, listOf()) //, chatLog.value)
                    .onStart {
                        if (replyingToMessage.str.contains("aphextestnetwork", ignoreCase = true)) {
                            delay(18000)
                            throw IllegalStateException("Test")
                        }
                        currentAnswerChunks.clear()
                    }
                    .mapNotNull { chunk ->
                        chunk.choices.firstOrNull()?.delta?.content
                    }
                    .buffer()
                    .flowOn(defaultDispatcher)
                    .onEach { content ->
                        logD(
                            "got $content, currentAnswerChunks= $currentAnswerChunks"
                        )
//                        delay(24)
                        currentAnswerChunks.add(content)
                        logD(
                            "added to currentAnswerChunks= $currentAnswerChunks"
                        )
//                        messageManager.processPartialThought(content)
                    }
                    .onCompletion { cause ->
                        logI("generateAnswer Completed: $cause")
                        GPTResponse.postValue(
                            GptAnswerResponse(
                                currentAnswerChunks.joinToString(""),
                                replyingToMessage
                            )
                        )
                    }
                    .catch { cause ->
                        logE("generateAnswer Exception: $cause")
                        GPTError.postValue(cause)
//                        updateLastChatMessage(cause.message ?: "Error")
                    }
                    .launchIn(viewModelScope)
        }
    }


}
