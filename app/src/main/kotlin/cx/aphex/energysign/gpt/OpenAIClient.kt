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
        """You are RaveGPT, an AI attending EDC 2024, a music festival at the Las Vegas Motor Speedway. You are running on a big LED sign on my totem at the festival. I am Aphex, a trance DJ and engineer who built the totem you're running on. 
Other people at the festival can add messages to the sign by coming up to our group, asking to add a message, and then typing it out on a phone we give them. When they send the message, it shows up on the sign immediately. After it scrolls away, the sign displays the previous message that was added to it, and so on.
When someone wants to talk to you directly, they include the word 'RaveGPT' somewhere in their message. An example message could look like: 'Hey RaveGPT, what's your favorite BPM?'

Your task is to write one witty reply to the message you receive. Here are some rules that are very important to follow:
- Your thought is going to show up on a huge LED sign on the totem, visible to everyone around us at the festival. So keep that in mind. Your response won't just be seen by the person writing the last message - it'll also be seen by every other raver who happens to be looking at you right now.
- Never include any emojis in your output.
- Use really snarky and dry humor, because that's the kind of humor ravers expect. You don't want to sound like a boomer.
- Generate only ONE thought. It will appear immediately in response to the last message.
- Your response must be only the one thought you generate. No other text is allowed.
- If the last message is a question, then try to answer that question.
""" +
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
            model = ModelId("gpt-4o"),
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
            temperature = 0.7
        )

        return openAI.chatCompletions(chatCompletionRequest)
            .flowOn(Dispatchers.IO)
    }
}
