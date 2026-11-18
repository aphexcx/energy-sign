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
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Replies written by an OpenAI model, behind the common interface: the model, the temperature
 * and the 30 s timeouts the app always used, and the answer is still streamed and put together
 * here. What is different from the code this replaces:
 *
 * - A reply is whole or absent. The text is used only when the stream ends by saying "stop". A
 *   stream that ends because of the length limit or a content filter, or that simply ends, gives
 *   no reply, whatever text came before.
 * - One attempt, and one time limit for all of it ([requestTimeout]). The client's own retries
 *   are off: it waited between them for as long as a `Retry-After` header said. Whether a query
 *   is asked again is decided in one place, [AIQueryDesk].
 * - The client's own logging of requests and headers is off, and a log line of this class holds
 *   the HTTP status and words from a fixed list, never text from a response.
 */
class OpenAIReplyGenerator(
    apiKey: String,
    private val log: (String) -> Unit,
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    /** Only tests set this, to a local fake of the API. */
    private val baseUrl: String? = null,
) : AIReplyGenerator {

    private val apiKey: String = apiKey.trim()

    override val name: String = "openai/$MODEL"

    private val openAI: OpenAI by lazy {
        OpenAI(
            OpenAIConfig(
                token = this.apiKey,
                // The default writes every request's headers to the console.
                logging = LoggingConfig(logLevel = LogLevel.None),
                timeout = Timeout(request = TIMEOUT, connect = TIMEOUT, socket = TIMEOUT),
                host = baseUrl?.let { OpenAIHost(baseUrl = it) } ?: OpenAIHost.OpenAI,
                retry = RetryStrategy(maxRetries = 0),
            )
        )
    }

    override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
        if (apiKey.isEmpty()) {
            return noReply(NoReplyReason.MISSING_KEY, "no OPENAI_API_KEY in this build (local.properties)")
        }
        return try {
            withTimeoutOrNull(requestTimeout.toMillis()) { ask(request) }
                ?: noReply(NoReplyReason.TIMEOUT, "no complete answer within ${requestTimeout.seconds} s")
        } catch (e: OpenAITimeoutException) {
            noReply(NoReplyReason.TIMEOUT, e.javaClass.simpleName)
        } catch (e: OpenAIIOException) {
            noReply(NoReplyReason.NETWORK, e.javaClass.simpleName)
        } catch (e: AuthenticationException) {
            noReply(NoReplyReason.KEY_REJECTED, summary(e))
        } catch (e: PermissionException) {
            noReply(NoReplyReason.KEY_REJECTED, summary(e))
        } catch (e: RateLimitException) {
            noReply(NoReplyReason.RATE_LIMITED, summary(e))
        } catch (e: InvalidRequestException) {
            noReply(NoReplyReason.REQUEST_REJECTED, summary(e))
        } catch (e: OpenAIAPIException) {
            noReply(NoReplyReason.PROVIDER_ERROR, summary(e))
        } catch (e: OpenAIServerException) {
            noReply(NoReplyReason.SERVER_ERROR, e.javaClass.simpleName)
        } catch (e: OpenAIException) {
            noReply(NoReplyReason.PROVIDER_ERROR, e.javaClass.simpleName)
        }
    }

    private suspend fun ask(request: AIReplyRequest): AIReplyResult {
        val text = StringBuilder()
        var finish: String? = null
        openAI.chatCompletions(
            ChatCompletionRequest(
                model = ModelId(MODEL),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = request.systemPrompt),
                    ChatMessage(role = ChatRole.User, content = request.userText),
                ),
                temperature = TEMPERATURE,
            )
        )
            .flowOn(Dispatchers.IO)
            .collect { chunk ->
                val choice = chunk.choices.firstOrNull() ?: return@collect
                choice.delta?.content?.let(text::append)
                choice.finishReason?.let { finish = it.value }
            }
        return when (finish) {
            "stop" -> if (text.isBlank()) noReply(NoReplyReason.EMPTY, "no text in the answer") else AIReplyResult.Reply(text.toString())
            "length" -> noReply(NoReplyReason.TRUNCATED, "the answer stopped at the length limit")
            "content_filter" -> noReply(NoReplyReason.REFUSED, "the answer was stopped by the content filter")
            null -> noReply(NoReplyReason.TRUNCATED, "the stream ended without saying the answer was finished")
            else -> noReply(NoReplyReason.UNEXPECTED_RESPONSE, "a finish reason this app does not handle")
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

        /** The end of one request, the same as for the other provider. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(90)

        /** The error types the log may name. Anything else is left out. */
        private val ERROR_TYPES = setOf(
            "invalid_request_error", "authentication_error", "permission_error", "rate_limit_error",
            "insufficient_quota", "server_error", "tokens", "requests",
        )

        /** "HTTP 400 invalid_request_error": the status and a type from the list above, never the message. */
        private fun summary(e: OpenAIAPIException): String =
            "HTTP ${e.statusCode}" + (e.error.detail?.type?.takeIf { it in ERROR_TYPES }?.let { " $it" } ?: "")
    }
}
