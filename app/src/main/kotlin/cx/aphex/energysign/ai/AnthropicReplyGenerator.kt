package cx.aphex.energysign.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.Timeout
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.time.Duration
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Replies written by Claude, through the official SDK (`com.anthropic:anthropic-java`).
 *
 * One request, not streamed: the sign shows a reply only when it is complete. The whole call is
 * one bounded wait of [requestTimeout]: the SDK's own retries are switched off ([MAX_RETRIES]),
 * because between attempts it waits as long as a `Retry-After` header says, without a limit. A
 * query that got no reply is asked again by the sign when its message comes round.
 *
 * [hardLimit] is the stop behind that timeout. The HTTP client cannot interrupt everything it
 * waits for (a DNS lookup that hangs, for one), so the caller is let go at the hard limit and the
 * stuck call is left to end by itself.
 */
class AnthropicReplyGenerator(
    apiKey: String,
    private val log: (String) -> Unit,
    /** Asked for every request, so the level can be changed while the app runs. */
    private val effort: () -> AIEffort = { AIEffort.DEFAULT },
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    private val hardLimit: Duration = requestTimeout.plus(HARD_LIMIT_MARGIN),
    /** Only tests set this, to a local fake of the API. */
    private val baseUrl: String? = null,
) : AIReplyGenerator {

    private val apiKey: String = apiKey.trim()

    override val name: String = "anthropic/$MODEL"

    /** Where the blocking calls run. A failure of one call stays in its own result. */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(this.apiKey)
            .maxRetries(MAX_RETRIES)
            .timeout(
                Timeout.builder()
                    .connect(minOf(CONNECT_TIMEOUT, requestTimeout))
                    // The whole call: DNS, connect, TLS, the request, the thinking and the answer.
                    .request(requestTimeout)
                    .build()
            )
            .apply { baseUrl?.let { baseUrl(it) } }
            .build()
    }

    override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
        if (apiKey.isEmpty()) {
            return noReply(NoReplyReason.MISSING_KEY, "no ANTHROPIC_API_KEY in this build (local.properties)")
        }
        // Not a child of the caller, so that the caller can be let go while the call is stuck.
        val call = calls.async { ask(request) }
        return withTimeoutOrNull(hardLimit.toMillis()) { call.await() }
            ?: noReply(NoReplyReason.TIMEOUT, "still no answer after ${hardLimit.toMillis()} ms; the call was left behind")
    }

    private fun ask(request: AIReplyRequest): AIReplyResult =
        try {
            val effortNow = effort()
            read(client.beta().messages().create(params(request, effortNow)), effortNow)
        } catch (e: UnauthorizedException) {
            noReply(NoReplyReason.KEY_REJECTED, e.summary())
        } catch (e: PermissionDeniedException) {
            noReply(NoReplyReason.KEY_REJECTED, e.summary())
        } catch (e: NotFoundException) {
            noReply(NoReplyReason.REQUEST_REJECTED, e.summary())
        } catch (e: BadRequestException) {
            noReply(NoReplyReason.REQUEST_REJECTED, e.summary())
        } catch (e: UnprocessableEntityException) {
            noReply(NoReplyReason.REQUEST_REJECTED, e.summary())
        } catch (e: RateLimitException) {
            noReply(NoReplyReason.RATE_LIMITED, e.summary())
        } catch (e: InternalServerException) {
            noReply(NoReplyReason.SERVER_ERROR, e.summary())
        } catch (e: AnthropicServiceException) {
            // Any other HTTP status.
            noReply(NoReplyReason.PROVIDER_ERROR, e.summary())
        } catch (e: AnthropicIoException) {
            // No response at all: a timeout, the network, or TLS.
            noReply(reasonForIoFailure(e), "${e.rootCause().javaClass.simpleName}: ${e.rootCause().message}")
        } catch (e: AnthropicInvalidDataException) {
            noReply(NoReplyReason.UNEXPECTED_RESPONSE, e.message.orEmpty())
        } catch (e: AnthropicException) {
            noReply(NoReplyReason.PROVIDER_ERROR, "${e.javaClass.simpleName}: ${e.message}")
        }

    /**
     * Thinking is always on for this model, and depth is set by [effort] alone: no thinking setting,
     * no temperature, top_p or top_k, no assistant prefill and no tool choice (each one is a 400).
     * `fallbacks` in its "default" form lets the API answer with another model when a safety
     * classifier declines this one.
     */
    private fun params(request: AIReplyRequest, effortNow: AIEffort): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .outputConfig(BetaOutputConfig.builder().effort(effortNow.toSdk()).build())
            .addBeta(FALLBACK_BETA)
            .fallbacks(BetaFallbacksParam.ofDefault())
            .system(request.systemPrompt)
            .addUserMessage(request.userText)
            .build()

    /** The stop reason is checked before the content: a refusal comes as HTTP 200, with or without content. */
    private fun read(message: BetaMessage, effortNow: AIEffort): AIReplyResult {
        val stopReason = message.stopReason().orElse(null)
        val servedBy = message.model().asString()
        return when (stopReason) {
            BetaStopReason.REFUSAL -> {
                val category = message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse("none")
                noReply(NoReplyReason.REFUSED, "declined by $servedBy, category $category")
            }

            BetaStopReason.MAX_TOKENS ->
                noReply(NoReplyReason.TRUNCATED, "cut off at $MAX_TOKENS tokens")

            BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> {
                val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
                val usage = message.usage()
                if (text.isBlank()) {
                    noReply(NoReplyReason.EMPTY, "no text in the answer from $servedBy")
                } else {
                    log(
                        "$name: reply from $servedBy at ${effortNow.setting} effort, " +
                            "${usage.inputTokens()} tokens in, ${usage.outputTokens()} out"
                    )
                    AIReplyResult.Reply(text)
                }
            }

            else -> noReply(NoReplyReason.UNEXPECTED_RESPONSE, "stop reason ${stopReason?.asString() ?: "missing"}")
        }
    }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        log("$name: no reply, ${describe(reason)} [$detail]")
        return AIReplyResult.NoReply(reason, detail)
    }

    private fun AnthropicServiceException.summary(): String =
        "HTTP ${statusCode()} ${errorType().map { it.toString() }.orElse("")}".trim()

    companion object {
        /** The model Afik asked for by name. */
        const val MODEL = "claude-fable-5-2"

        /** Room for the thinking that comes before the answer, not the length of the answer. */
        const val MAX_TOKENS = 16_000L

        /** The whole call ends within this, whatever happens. */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** How long after [REQUEST_TIMEOUT] the caller is let go whatever the HTTP client is doing. */
        val HARD_LIMIT_MARGIN: Duration = Duration.ofSeconds(2)

        /** One attempt. See the class comment. */
        const val MAX_RETRIES = 0

        /** Server-side fallback on a refusal; the "default" form needs this beta. */
        val FALLBACK_BETA: AnthropicBeta = AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01

        /** What went wrong when no response came back, read from the types in the cause chain. */
        fun reasonForIoFailure(failure: Throwable): NoReplyReason {
            val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            return when {
                chain.any { it is CertificateNotYetValidException || it is CertificateExpiredException } ->
                    NoReplyReason.CERTIFICATE_CLOCK

                chain.any {
                    it is CertPathValidatorException &&
                        (it.reason == CertPathValidatorException.BasicReason.NOT_YET_VALID ||
                            it.reason == CertPathValidatorException.BasicReason.EXPIRED)
                } -> NoReplyReason.CERTIFICATE_CLOCK

                chain.any { it is CertPathValidatorException || it is CertificateException || it is SSLPeerUnverifiedException } ->
                    NoReplyReason.CERTIFICATE_UNTRUSTED

                chain.any { it is SSLException } -> NoReplyReason.TLS
                // OkHttp's call timeout is an InterruptedIOException; a socket timeout extends it.
                chain.any { it is SocketTimeoutException || it is InterruptedIOException } -> NoReplyReason.TIMEOUT
                else -> NoReplyReason.NETWORK
            }
        }

        /** The words for the log line. The certificate cases say which of the two they are. */
        fun describe(reason: NoReplyReason): String = when (reason) {
            NoReplyReason.MISSING_KEY -> "there is no API key"
            NoReplyReason.REFUSED -> "the request was declined (refusal), fallback included"
            NoReplyReason.EMPTY -> "the answer was empty"
            NoReplyReason.TRUNCATED -> "the answer was cut off by the token limit"
            NoReplyReason.UNEXPECTED_RESPONSE -> "the response was not an answer"
            NoReplyReason.TIMEOUT -> "no answer within the time limit"
            NoReplyReason.NETWORK -> "the network failed"
            NoReplyReason.CERTIFICATE_CLOCK ->
                "the server certificate is not valid at the device's time: the device clock is wrong (it is now ${java.util.Date()})"

            NoReplyReason.CERTIFICATE_UNTRUSTED ->
                "the server certificate does not chain to a root this device trusts (unknown root)"

            NoReplyReason.TLS -> "the TLS connection failed"
            NoReplyReason.RATE_LIMITED -> "rate limited"
            NoReplyReason.SERVER_ERROR -> "the API had an error or is overloaded"
            NoReplyReason.KEY_REJECTED -> "the API key was rejected"
            NoReplyReason.REQUEST_REJECTED -> "the API rejected the request"
            NoReplyReason.PROVIDER_ERROR -> "the API client failed"
            NoReplyReason.UNEXPECTED_FAILURE -> "something unexpected failed"
        }

        private const val MAX_CAUSE_DEPTH = 12

        private fun AIEffort.toSdk(): BetaOutputConfig.Effort = when (this) {
            AIEffort.LOW -> BetaOutputConfig.Effort.LOW
            AIEffort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
            AIEffort.HIGH -> BetaOutputConfig.Effort.HIGH
            AIEffort.XHIGH -> BetaOutputConfig.Effort.XHIGH
            AIEffort.MAX -> BetaOutputConfig.Effort.MAX
        }

        private fun Throwable.rootCause(): Throwable =
            generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).last()
    }
}
