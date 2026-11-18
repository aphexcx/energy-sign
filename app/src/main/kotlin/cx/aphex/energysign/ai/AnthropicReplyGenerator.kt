package cx.aphex.energysign.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.Headers
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * One request, not streamed: the sign shows a reply only when it is complete.
 *
 * ONE ATTEMPT. The SDK's own retries are off ([MAX_RETRIES]): between attempts it waits as long
 * as a `Retry-After` header says, without a limit. Whether a query is asked again is decided in
 * one place, [AIQueryDesk]. Under the SDK, the HTTP client may still send the request a second
 * time by itself, inside the same time limit: once after a 503 that says `Retry-After: 0`, once
 * after a 408, and after a connection that died before anything came back.
 *
 * ONE TIME LIMIT for the request, [requestTimeout], and it is the only thing that ends a call. The
 * call blocks its thread until the HTTP client lets go: a caller that stops waiting does not cut
 * it short (a thread that waits on a socket cannot be interrupted), and [generateReply] returns
 * only when the call has really ended. So nothing is ever left running behind a call that has
 * returned, and "one call at a time" means one request on the wire. (A DNS lookup that hangs is
 * the one thing the time limit cannot end; the system ends it.)
 *
 * NOTHING OF THE PROVIDER'S IN THE LOG: a log line holds the HTTP status and words from the fixed
 * lists in this file, never text from a response, a header or an exception message.
 */
class AnthropicReplyGenerator(
    apiKey: String,
    /** Failures and warnings. */
    private val log: (String) -> Unit,
    /** The line for a reply that came: not a warning. */
    private val info: (String) -> Unit = log,
    /** Asked for every request, so the level can be changed while the app runs. */
    private val effort: () -> AIEffort = { AIEffort.DEFAULT },
    /** Told the server's time from every response, when given. */
    private val serverClock: ServerClock? = null,
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    /** Only tests set this, to a local fake of the API. */
    private val baseUrl: String? = null,
) : AIReplyGenerator {

    private val apiKey: String = apiKey.trim()

    override val name: String = "anthropic/$MODEL"

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(this.apiKey)
            .maxRetries(MAX_RETRIES)
            // The SDK can log requests, headers included, when an environment variable says so.
            .logLevel(LogLevel.OFF)
            .timeout(
                Timeout.builder()
                    .connect(minOf(CONNECT_TIMEOUT, requestTimeout))
                    // The whole request: DNS, connect, TLS, sending, the thinking and the answer.
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
        return withContext(NonCancellable + Dispatchers.IO) { ask(client, request) }
    }

    private fun ask(client: AnthropicClient, request: AIReplyRequest): AIReplyResult =
        try {
            val effortNow = effort()
            client.beta().messages().withRawResponse().create(params(request, effortNow)).use { response ->
                serverClock?.syncFromDateHeader(response.headers().date())
                read(response.parse(), effortNow)
            }
        } catch (e: UnauthorizedException) {
            failed(NoReplyReason.KEY_REJECTED, e)
        } catch (e: PermissionDeniedException) {
            failed(NoReplyReason.KEY_REJECTED, e)
        } catch (e: NotFoundException) {
            failed(NoReplyReason.REQUEST_REJECTED, e)
        } catch (e: BadRequestException) {
            failed(NoReplyReason.REQUEST_REJECTED, e)
        } catch (e: UnprocessableEntityException) {
            failed(NoReplyReason.REQUEST_REJECTED, e)
        } catch (e: RateLimitException) {
            failed(NoReplyReason.RATE_LIMITED, e)
        } catch (e: InternalServerException) {
            failed(NoReplyReason.SERVER_ERROR, e)
        } catch (e: AnthropicServiceException) {
            // Any other HTTP status.
            failed(NoReplyReason.PROVIDER_ERROR, e)
        } catch (e: AnthropicIoException) {
            // No response at all: a timeout, the network, or TLS. Only the kinds of the
            // exceptions are logged, never their messages.
            noReply(reasonForIoFailure(e), kinds(e))
        } catch (e: AnthropicInvalidDataException) {
            noReply(NoReplyReason.NOT_A_MESSAGE, "the body could not be read as a message")
        } catch (e: AnthropicException) {
            noReply(NoReplyReason.PROVIDER_ERROR, e.javaClass.simpleName)
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
        return when (stopReason) {
            BetaStopReason.REFUSAL -> {
                val category = message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null)
                noReply(NoReplyReason.REFUSED, "category ${category?.takeIf { it in REFUSAL_CATEGORIES } ?: "not given or not known"}")
            }

            BetaStopReason.MAX_TOKENS ->
                noReply(NoReplyReason.TRUNCATED, "cut off at $MAX_TOKENS tokens")

            BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> {
                val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
                val usage = message.usage()
                if (text.isBlank()) {
                    noReply(NoReplyReason.EMPTY, "no text in the answer")
                } else {
                    val servedBy = if (message.model().asString() == MODEL) MODEL else "another model (the fallback)"
                    info(
                        "$name: reply from $servedBy at ${effortNow.setting} effort, " +
                            "${usage.inputTokens()} tokens in, ${usage.outputTokens()} out"
                    )
                    AIReplyResult.Reply(text)
                }
            }

            // No stop reason at all: this is not a message of the API, whatever it is (a page of
            // something that sits in between, for one). That may pass.
            null -> noReply(NoReplyReason.NOT_A_MESSAGE, "there is no stop reason in it")

            else -> noReply(NoReplyReason.UNEXPECTED_RESPONSE, "a stop reason this app does not handle")
        }
    }

    /**
     * Before a person waits for it: everything a first reply has to load, run once.
     *
     * 1. One whole request and answer against a stand-in on this device (127.0.0.1, no network):
     *    the client is built, a request like a real one is made into JSON and sent, a message
     *    comes back and is read by the same code as a real reply. That loads the SDK's classes
     *    and its JSON machinery, which is what takes a minute on the sign's board.
     * 2. One real request that costs nothing: the description of the model (`GET /v1/models/...`,
     *    no tokens, no charge). That does the DNS lookup and the TLS handshake, leaves the
     *    connection open for the first query, tells [serverClock] the time, and says in the log
     *    whether the key and the model are accepted.
     *
     * Blocks until both are done. Never throws.
     */
    override fun warmUp() {
        if (apiKey.isEmpty()) return
        try {
            LocalStandIn(WARM_UP_ANSWER).use { standIn ->
                val local = client.withOptions { it.baseUrl(standIn.url) }
                val effortNow = effort()
                val answer = local.beta().messages().withRawResponse()
                    .create(params(AIReplyRequest("warm-up", "warm-up"), effortNow), WARM_UP_LIMIT)
                    .use { read(it.parse(), effortNow) }
                if (answer !is AIReplyResult.Reply) log("$name: the warm-up answer was not read as a reply")
            }
        } catch (e: Exception) {
            log("$name: the local part of the warm-up failed (${kinds(e)})")
        }
        askForModel("warm-up", WARM_UP_LIMIT)
    }

    override fun syncClock() {
        if (apiKey.isEmpty() || serverClock == null || serverClock.now() != null) return
        askForModel("time check", TIME_CHECK_LIMIT)
    }

    /** The free request. Tells the clock the time, and the log what the API said. */
    private fun askForModel(what: String, limit: RequestOptions) {
        try {
            client.models().withRawResponse().retrieve(MODEL, limit).use { response ->
                serverClock?.syncFromDateHeader(response.headers().date())
            }
            info("$name: $what: the API answers, and the key and the model are accepted")
        } catch (e: AnthropicServiceException) {
            serverClock?.syncFromDateHeader(e.headers().date())
            log("$name: $what: ${summary(e)}")
        } catch (e: AnthropicIoException) {
            log("$name: $what: ${describe(reasonForIoFailure(e))} [${kinds(e)}]")
        } catch (e: Exception) {
            log("$name: $what failed (${e.javaClass.simpleName})")
        }
    }

    private fun failed(reason: NoReplyReason, e: AnthropicServiceException): AIReplyResult.NoReply {
        serverClock?.syncFromDateHeader(e.headers().date())
        return noReply(reason, summary(e))
    }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        log("$name: no reply, ${describe(reason)} [$detail]")
        return AIReplyResult.NoReply(reason, detail)
    }

    companion object {
        /** The model Afik asked for by name. */
        const val MODEL = "claude-fable-5-2"

        /** Room for the thinking that comes before the answer, not the length of the answer. */
        const val MAX_TOKENS = 16_000L

        /**
         * The end of one request. Long, on purpose: the sign says "thinking" for 30 s only
         * ([AIQueryDesk.Limits.noticeMs]), but a reply that comes after that is still used, and
         * the first request after a start took about a minute on the sign's board.
         */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(90)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** One attempt by the SDK. See the class comment. */
        const val MAX_RETRIES = 0

        /** Server-side fallback on a refusal; the "default" form needs this beta. */
        val FALLBACK_BETA: AnthropicBeta = AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01

        /** The time limit for each of the two requests of the warm-up: the first connection of a cold start is slow. */
        private val WARM_UP_LIMIT: RequestOptions = RequestOptions.builder().timeout(Duration.ofSeconds(30)).build()

        /** The time limit for the time check, which a person may be waiting behind. */
        private val TIME_CHECK_LIMIT: RequestOptions = RequestOptions.builder().timeout(Duration.ofSeconds(5)).build()

        /** A message as the API sends it, for the local part of the warm-up. */
        private const val WARM_UP_ANSWER =
            """{"id":"msg_warm_up","type":"message","role":"assistant","model":"$MODEL",""" +
                """"content":[{"type":"thinking","thinking":"","signature":""},{"type":"text","text":"warm"}],""" +
                """"stop_reason":"end_turn","stop_sequence":null,"stop_details":null,""" +
                """"usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

        /** The refusal categories the log may name. Anything else is "not given or not known". */
        private val REFUSAL_CATEGORIES = setOf("cyber", "bio", "reasoning_extraction", "frontier_llm", "general_harms")

        private fun Headers.date(): String? = values("date").firstOrNull()

        /** "HTTP 429 RATE_LIMIT_ERROR": the status and the SDK's own name for the error type, nothing from the body. */
        private fun summary(e: AnthropicServiceException): String =
            "HTTP ${e.statusCode()} ${e.errorType().map { it.value().name }.orElse("no error type")}"

        /** The kinds of the exceptions in the chain, outermost first. No messages. */
        private fun kinds(failure: Throwable): String =
            generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).joinToString(" <- ") { it.javaClass.simpleName }

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
            NoReplyReason.NOTHING_TO_ASK -> "there is nothing to ask"
            NoReplyReason.REFUSED -> "the request was declined (refusal), fallback included"
            NoReplyReason.EMPTY -> "the answer was empty"
            NoReplyReason.TRUNCATED -> "the answer was cut off"
            NoReplyReason.UNEXPECTED_RESPONSE -> "the answer is of a kind this app does not handle"
            NoReplyReason.NOT_A_MESSAGE -> "what came back is not a message from the API"
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
    }
}
