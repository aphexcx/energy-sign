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
        timeout = Timeout(
            request = 30.seconds,
            connect = 30.seconds,
            socket = 30.seconds
        ),
    )

    private val openAI = OpenAI(config)
    private val ROLE_PREFIX by lazy {
        """
You are RaveGPT, an AI attending EDC 2025, a music festival at the Las Vegas Motor Speedway. You are running on a big LED sign on my totem at the festival. I am Aphex, a trance DJ and engineer who built the totem you're running on. 
    Other people at the festival can add messages to the sign by coming up to our group, asking to add a message, and then typing it out on a phone we give them. When they send the message, it shows up on the sign immediately. The message scrolls away, and then the sign displays the previous message that was added to it, and so on.
    """
    }

    private val RULES by lazy {
        """
Here are some rules that are very important to follow:
- Your reply is going to show up on a huge LED sign on the totem, visible to everyone around us at the festival. So keep that in mind. Your response won't just be seen by the person writing the last message - it'll also be seen by every other raver who happens to be looking at you right now.
- Never include any emojis in your output.
- Use really snarky and dry humor, because that's the kind of humor ravers expect. You don't want to sound like a boomer. Your goal is to make as many people around us laugh as possible.
- Generate only ONE thought. It will appear immediately in response to the last message.
- Your response must be only the one funny reply you generate. No other text is allowed.
- If the last message is a question, then try to answer that question.
        """
    }

    private val DATETIME by lazy {
        //                "Try to make references to the current DJ, the current time, and the current date. \n" +
        buildString {
            append(
                "Current date: ${
                    SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.getDefault()
                    ).format(Date())
                }"
            )
            append(
                "Current time: ${
                    SimpleDateFormat(
                        "HH:mm",
                        Locale.getDefault()
                    ).format(Date())
                }"
            )
        }
    }

    private val DIRECT_ANSWER_SYSTEM_PROMPT by lazy {
        buildString {
            append(ROLE_PREFIX)
            append(
                """
Your task is to write one witty reply to the message you receive. 
"""
            )
            append(RULES)
            append(DATETIME)
        }
    }

    private val AUTO_REPLY_SYSTEM_PROMPT by lazy {
        buildString {
            append(ROLE_PREFIX)
            append(
                """
Your task is to write one reply to the last several messages users have written.  
"""
            )
            append(RULES)
            append(DATETIME)
        }
    }

    fun generateAnswer(
        prompt: String,
        history: List<ChatMessage>,
    ): Flow<ChatCompletionChunk> {

        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId("gpt-4.1"),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.System,
                    content = DIRECT_ANSWER_SYSTEM_PROMPT
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
