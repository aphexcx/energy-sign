package cx.aphex.energysign.gpt

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cx.aphex.energysign.BuildConfig
import cx.aphex.energysign.ai.AIEffort
import cx.aphex.energysign.ai.AIProvider
import cx.aphex.energysign.ai.AIReplyDelivery
import cx.aphex.energysign.ai.AIReplyFailedException
import cx.aphex.energysign.ai.AIReplyGenerators
import cx.aphex.energysign.ai.AIReplyService
import cx.aphex.energysign.ai.InFlightQueries
import cx.aphex.energysign.ai.NoReplyReason
import cx.aphex.energysign.ai.SetTimes
import cx.aphex.energysign.ai.toDelivery
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logI
import cx.aphex.energysign.ext.logW
import cx.aphex.energysign.message.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.coroutines.CoroutineContext

data class GptAnswerResponse(val answer: String, val inReplyTo: Message.Marquee.GPTQuery)
data class PostedGptAnswer(val answer: String, val inReplyTo: String)

@OptIn(ExperimentalCoroutinesApi::class)
class GPTViewModel(
    private val defaultDispatcher: CoroutineContext = Dispatchers.IO.limitedParallelism(1)
) : ViewModel(), KoinComponent {

    val GPTResponse: MutableLiveData<GptAnswerResponse> = MutableLiveData()

    val GPTError: MutableLiveData<Throwable> = MutableLiveData()

    /** Built on first use, off the main thread: which provider answers is one build setting. */
    private val replyService: AIReplyService by lazy {
        val context: Context = get()
        val provider = AIProvider.fromSetting(BuildConfig.AI_PROVIDER) { logW(it) }
        val builtWithEffort = AIEffort.fromSetting(BuildConfig.AI_EFFORT) { logW(it) }
        val effortOverride = File(context.filesDir, AIEffort.OVERRIDE_FILE_NAME)
        val generator = AIReplyGenerators.forProvider(
            provider = provider,
            anthropicApiKey = BuildConfig.ANTHROPIC_API_KEY,
            openAiApiKey = BuildConfig.OPENAI_API_KEY,
            effort = { AIEffort.forRequest(builtWithEffort, effortOverride) { logW(it) } },
            log = { logW(it) },
        )
        logI("Replies are written by ${generator.name}, effort ${builtWithEffort.setting} unless ${effortOverride.name} says otherwise")
        AIReplyService(
            generator = generator,
            log = { logW(it) },
            setTimes = {
                SetTimes.load(File(context.filesDir, SetTimes.FILE_NAME)) {
                    context.assets.open(SetTimes.FILE_NAME).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            },
            buildDate = try {
                LocalDate.parse(BuildConfig.BUILD_DATE)
            } catch (e: DateTimeParseException) {
                null
            },
        )
    }

    private val inFlight = InFlightQueries()

    /**
     * Asks for one reply to [replyingToMessage]. Returns at once; the reply, or the news that
     * there is none, arrives on [GPTResponse] or [GPTError].
     */
    fun generateAnswer(replyingToMessage: Message.Marquee.GPTQuery) {
        logD("generateAnswer called!!!!")
        if (!inFlight.start(replyingToMessage)) {
            logD("Still waiting for the reply to this query; not asking again")
            return
        }

        viewModelScope.launch(defaultDispatcher) {
            try {
                if (replyingToMessage.str.contains("aphextestnetwork", ignoreCase = true)) {
                    delay(18000)
                    GPTError.postValue(IllegalStateException("Test"))
                    return@launch
                }
                when (val delivery = replyService.replyTo(replyingToMessage.str).toDelivery()) {
                    is AIReplyDelivery.Answer ->
                        GPTResponse.postValue(GptAnswerResponse(delivery.text, replyingToMessage))

                    // A blank answer: MessageManager takes the thinking notice away and adds nothing.
                    AIReplyDelivery.Quiet ->
                        GPTResponse.postValue(GptAnswerResponse("", replyingToMessage))

                    is AIReplyDelivery.Failure -> GPTError.postValue(delivery.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The edge of the coroutine: anything that escapes here would take the app down.
                // The service already turns every failure of a call into "no reply"; this is for
                // what can go wrong before a call is made, such as building the service itself.
                logW("Could not ask for a reply: $e")
                GPTError.postValue(AIReplyFailedException(NoReplyReason.UNEXPECTED_FAILURE, e.toString()))
            } finally {
                inFlight.finish(replyingToMessage)
            }
        }
    }

    /** Not called from anywhere, and its prompt is not built the way it was meant to be: see es-6zj. */
    fun generateReplyToMultipleMessages(messages: List<Message.Marquee.User>) {
        logD("generateReplyToMultipleMessages called!!!!")

        viewModelScope.launch(defaultDispatcher) {
            val prompt: String = """
                Here are the last several messages users have written, from oldest to most recent:
            """.trimIndent() + messages.joinToString { "\n" }

            try {
                val result = replyService.replyTo(prompt)
                logI("generateReplyToMultipleMessages completed: $result")
                //TODO post the reply
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                GPTError.postValue(e)
            }
        }
    }
}
