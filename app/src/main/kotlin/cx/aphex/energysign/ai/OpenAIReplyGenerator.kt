package cx.aphex.energysign.ai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.exception.AuthenticationException
import com.aallam.openai.api.exception.InvalidRequestException
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.exception.OpenAIException
import com.aallam.openai.api.exception.OpenAIIOException
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.exception.OpenAITimeoutException
import com.aallam.openai.api.exception.PermissionException
import com.aallam.openai.api.exception.RateLimitException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration.Companion.seconds

/**
 * Replies written by an OpenAI model: what the app did before, behind the common interface. The
 * same model, temperature, 30 s timeouts and client retries (the client's default, so this wait
 * is not bounded the way the Anthropic one is), and the answer is still streamed and put together
 * here. Two things differ: an answer that broke off half way is no reply instead of half a
 * reply, and failures are reported by kind.
 */
class OpenAIReplyGenerator(
    apiKey: String,
    private val log: (String) -> Unit,
    /** Only tests set this, to a local fake of the API. */
    private val baseUrl: String? = null,
) : AIReplyGenerator {

    private val apiKey: String = apiKey.trim()

    override val name: String = "openai/$MODEL"

    private val openAI: OpenAI by lazy {
        OpenAI(
            OpenAIConfig(
                token = this.apiKey,
                timeout = Timeout(request = TIMEOUT, connect = TIMEOUT, socket = TIMEOUT),
                host = baseUrl?.let { OpenAIHost(baseUrl = it) } ?: OpenAIHost.OpenAI,
            )
        )
    }

    override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
        if (apiKey.isEmpty()) {
            return noReply(NoReplyReason.MISSING_KEY, "no OPENAI_API_KEY in this build (local.properties)")
        }
        return try {
            val text = openAI.chatCompletions(
                ChatCompletionRequest(
                    model = ModelId(MODEL),
                    messages = listOf(
                        ChatMessage(role = ChatRole.System, content = request.systemPrompt),
                        ChatMessage(role = ChatRole.User, content = request.userText),
                    ),
                    temperature = TEMPERATURE,
                )
            )
                .mapNotNull { chunk -> chunk.choices.firstOrNull()?.delta?.content }
                .flowOn(Dispatchers.IO)
                .toList()
                .joinToString("")
            if (text.isBlank()) noReply(NoReplyReason.EMPTY, "no text in the answer") else AIReplyResult.Reply(text)
        } catch (e: OpenAITimeoutException) {
            noReply(NoReplyReason.TIMEOUT, e.message.orEmpty())
        } catch (e: OpenAIIOException) {
            noReply(NoReplyReason.NETWORK, e.message.orEmpty())
        } catch (e: AuthenticationException) {
            noReply(NoReplyReason.KEY_REJECTED, "HTTP ${e.statusCode}")
        } catch (e: PermissionException) {
            noReply(NoReplyReason.KEY_REJECTED, "HTTP ${e.statusCode}")
        } catch (e: RateLimitException) {
            noReply(NoReplyReason.RATE_LIMITED, "HTTP ${e.statusCode}")
        } catch (e: InvalidRequestException) {
            noReply(NoReplyReason.REQUEST_REJECTED, "HTTP ${e.statusCode} ${e.error.detail?.message.orEmpty()}")
        } catch (e: OpenAIAPIException) {
            noReply(NoReplyReason.PROVIDER_ERROR, "HTTP ${e.statusCode}")
        } catch (e: OpenAIServerException) {
            noReply(NoReplyReason.SERVER_ERROR, e.message.orEmpty())
        } catch (e: OpenAIException) {
            noReply(NoReplyReason.PROVIDER_ERROR, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        log("$name: no reply, $reason [$detail]")
        return AIReplyResult.NoReply(reason, detail)
    }

    companion object {
        const val MODEL = "gpt-5.1"
        const val TEMPERATURE = 0.7
        private val TIMEOUT = 30.seconds
    }
}
