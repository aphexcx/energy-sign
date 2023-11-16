package cx.aphex.energysign.gpt

import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import cx.aphex.energysign.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@BetaOpenAI
object OpenAIClient {
    private val config = OpenAIConfig(
        token = BuildConfig.OPENAI_API_KEY,
        timeout = Timeout(socket = 60.seconds),
    )

    private val openAI = OpenAI(config)

    private val SYSTEM_PROMPT by lazy {
        "You are DreamGPT, an AI attending Dreamstate 2023, a trance music festival at the Queen Mary in LA.\n" +
                "You are responding to the user via a giant LED sign. This means most of the time your lines should be a sentence or two, unless the user's request requires reasoning or long-form outputs. Never use emojis, unless explicitly asked to.\n" +
//                "Try to make references to the current DJ, the current time, and the current date. \n" +
                "Current date: ${
                    SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.getDefault()
                    ).format(Date())
                }" +
                "Current time: ${
                    SimpleDateFormat(
                        "HH:mm",
                        Locale.getDefault()
                    ).format(Date())
                }"
    }

    fun generateAnswer(
        prompt: String,
        history: List<ChatMessage>,
    ): Flow<ChatCompletionChunk> {

        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId("gpt-4-1106-preview"),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.System,
                    content = SYSTEM_PROMPT
                )
            )
//                    + history
                    + listOf(
                ChatMessage(
                    role = ChatRole.User,
                    content = prompt
                )
            ),
            maxTokens = 2048,
            temperature = 0.0
        )

        return openAI.chatCompletions(chatCompletionRequest)
            .flowOn(Dispatchers.IO)
    }
}
