package cx.aphex.energysign.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.Headers
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.ErrorType
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.net.Proxy
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
 * time by itself, inside the same time limit, in one case: after a 503 that says
 * `Retry-After: 0`. (The SDK switches the HTTP client's other repeats off: after a 408, and
 * after a connection that died.) It follows no redirect: a 3xx is a failure, and the request
 * goes nowhere but where it was sent.
 *
 * ONE TIME LIMIT, the deadline of the query ([AIReplyRequest.deadline]). The request gets what is
 * left of it when it goes out ([requestTimeout] at most), and that is the only thing that ends a
 * call: nothing stands behind it that could throw an answer away which the provider is still
 * writing. The call blocks its thread until the HTTP client lets go: a caller that stops waiting
 * does not cut it short (a thread that waits on a socket cannot be interrupted), and
 * [generateReply] returns only when the call has really ended. So nothing is ever left running
 * behind a call that has returned, and "one call at a time" means one request on the wire. (A DNS
 * lookup that hangs is the one thing the time limit cannot end; the system ends it.) When too
 * little of the deadline is left to hope for an answer ([MIN_TIME_TO_SEND]), nothing is sent.
 *
 * THE KEY GOES TO THE API AND NOWHERE ELSE. The warm-up's request to the stand-in on this device
 * is made by a second client that was never given the key (see [warmUp]).
 *
 * NOTHING OF THE PROVIDER'S IN THE LOG: a log line holds the HTTP status and words from the fixed
 * lists in this file, never text from a response, a header or an exception message. And what a
 * response IS is decided from its status, its stop reason and its content alone: a field that is
 * read only for the log (the refusal category, the token counts, the error type) is read best
 * effort, and when it cannot be read the line goes without it.
 */
class AnthropicReplyGenerator(
    apiKey: String,
    /** Failures and warnings. */
    private val log: (String) -> Unit,
    /** The line for a reply that came: not a warning. */
    private val info: (String) -> Unit = log,
    /** Asked for every request. It must not read storage: see [ReadOnceAtStart]. */
    private val effort: () -> AIEffort = { AIEffort.DEFAULT },
    /** Told the server's time from the responses that are the API's, when given. */
    private val serverClock: ServerClock? = null,
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    /** Only tests set this, to a local fake of the API. */
    private val baseUrl: String? = null,
    /** The stand-in for the local part of the warm-up. Only tests set this, to see what it is sent. */
    private val standIn: (answer: String) -> LocalStandIn = { LocalStandIn(it) },
    private val minTimeToSend: Duration = MIN_TIME_TO_SEND,
) : AIReplyGenerator {

    private val apiKey: String = apiKey.trim()

    override val name: String = "anthropic/$MODEL"

    /** One for both clients: what the local part of the warm-up loads into it, the real client finds there. */
    private val mapper: JsonMapper by lazy { jsonMapper() }

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(this.apiKey)
            .jsonMapper(mapper)
            .maxRetries(MAX_RETRIES)
            // The SDK can log requests, headers included, when an environment variable says so.
            .logLevel(LogLevel.OFF)
            .timeout(timeouts(requestTimeout))
            .apply { baseUrl?.let { baseUrl(it) } }
            .build()
    }

    override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
        if (apiKey.isEmpty()) {
            return noReply(NoReplyReason.MISSING_KEY, "no ANTHROPIC_API_KEY in this build (local.properties)")
        }
        return withContext(NonCancellable + Dispatchers.IO) { ask(request) }
    }

    private fun ask(request: AIReplyRequest): AIReplyResult =
        try {
            // Everything that can take time before the request is done first (the client is built
            // by its first use); then the two questions that decide whether it goes out at all.
            val client = client
            val effortNow = effort()
            val params = params(request, effortNow)
            if (!request.stillWanted()) {
                noReply(NoReplyReason.WITHDRAWN, "not sent: the query is not wanted any more")
            } else {
                val leftMs = request.deadline.remainingMs()
                if (leftMs < minTimeToSend.toMillis()) {
                    noReply(NoReplyReason.TIMEOUT, "not sent: ${leftMs / 1000} s of the query's time were left")
                } else {
                    val limit = minOf(requestTimeout, Duration.ofMillis(leftMs))
                    client.beta().messages().withRawResponse().create(params, options(limit)).use { response ->
                        // The response has arrived; its body is read below, and that can take a while.
                        val arrivedAt = serverClock?.arrival()
                        read(response.parse(), effortNow).also { result ->
                            // The time is taken from this response only now that it has been read as a message of the API.
                            if ((result as? AIReplyResult.NoReply)?.reason != NoReplyReason.NOT_A_MESSAGE) {
                                tellClock(response.headers(), arrivedAt)
                            }
                        }
                    }
                }
            }
        } catch (e: AnthropicServiceException) {
            failed(e)
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

    /**
     * What [message] is. The stop reason is checked before the content: a refusal comes as HTTP
     * 200, with or without content. Only the stop reason and the content decide; what is read for
     * the log line is read best effort.
     */
    private fun read(message: BetaMessage, effortNow: AIEffort): AIReplyResult {
        val stopReason = message.stopReason().orElse(null)
        return when (stopReason) {
            BetaStopReason.REFUSAL -> {
                val category = forLog { message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null) }
                noReply(NoReplyReason.REFUSED, "category ${category?.takeIf { it in REFUSAL_CATEGORIES } ?: "not given or not known"}")
            }

            BetaStopReason.MAX_TOKENS ->
                noReply(NoReplyReason.TRUNCATED, "cut off at $MAX_TOKENS tokens")

            BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> {
                val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
                if (text.isBlank()) {
                    noReply(NoReplyReason.EMPTY, "no text in the answer")
                } else {
                    forLog {
                        val servedBy = if (message.model().asString() == MODEL) MODEL else "another model (the fallback)"
                        val usage = message.usage()
                        info(
                            "$name: reply from $servedBy at ${effortNow.setting} effort, " +
                                "${usage.inputTokens()} tokens in, ${usage.outputTokens()} out"
                        )
                    }
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
     * 1. One whole request and answer against a stand-in on this device (127.0.0.1, no network,
     *    no proxy): a request like a real one is made into JSON and sent, a message comes back
     *    and is read by the same code as a real reply. That loads the SDK's classes and its JSON
     *    machinery, which is what takes a minute on the sign's board. It is made by a SECOND
     *    client, built here with a made-up key: the key of this build is never in a request that
     *    goes anywhere but to the API. The two clients share the JSON mapper, so what this step
     *    loads into it is there for the real client. What this step does not exercise is the real
     *    client's own HTTP connection; step 2 does.
     * 2. One real request that costs nothing: the description of the model (`GET /v1/models/...`,
     *    no tokens, no charge). That builds the real client, does the DNS lookup and the TLS
     *    handshake, leaves the connection open for the first query, tells [serverClock] the
     *    time, and says in the log whether the key and the model are accepted.
     *
     * Blocks until both are done. Never throws.
     */
    override fun warmUp() {
        if (apiKey.isEmpty()) return
        try {
            standIn(WARM_UP_ANSWER).use { standIn ->
                val local = AnthropicOkHttpClient.builder()
                    .apiKey(WARM_UP_KEY)
                    .baseUrl(standIn.url)
                    // Straight to this device, whatever proxy the system has been given.
                    .proxy(Proxy.NO_PROXY)
                    .jsonMapper(mapper)
                    .maxRetries(MAX_RETRIES)
                    .logLevel(LogLevel.OFF)
                    .timeout(timeouts(WARM_UP_TIME))
                    .build()
                try {
                    val effortNow = effort()
                    val answer = local.beta().messages().withRawResponse()
                        .create(params(AIReplyRequest("warm-up", "warm-up"), effortNow), options(WARM_UP_TIME))
                        .use { read(it.parse(), effortNow) }
                    if (answer !is AIReplyResult.Reply) log("$name: the warm-up answer was not read as a reply")
                } finally {
                    local.close()
                }
            }
        } catch (e: Exception) {
            log("$name: the local part of the warm-up failed (${kinds(e)})")
        }
        askForModel("warm-up", WARM_UP_TIME)
    }

    /** The caller says how long it may take: a query that waits gives it seconds, a warm-up more. */
    override fun syncClock(limit: Duration) {
        if (apiKey.isEmpty() || serverClock == null || serverClock.now() != null) return
        if (limit < MIN_TIME_CHECK) return
        askForModel("time check", limit)
    }

    /**
     * The free request. Tells the clock the time and the log that the key and the model are
     * accepted, both only when what came back IS the description of the model: any page can
     * answer a GET with HTTP 200.
     */
    private fun askForModel(what: String, limit: Duration) {
        try {
            client.models().withRawResponse().retrieve(MODEL, options(limit)).use { response ->
                val arrivedAt = serverClock?.arrival()
                if (response.parse().id() == MODEL) {
                    tellClock(response.headers(), arrivedAt)
                    info("$name: $what: the API answers, and the key and the model are accepted")
                } else {
                    log("$name: $what: what came back does not describe the model")
                }
            }
        } catch (e: AnthropicServiceException) {
            tellClockFromError(e)
            log("$name: $what: ${summary(e)}")
        } catch (e: AnthropicIoException) {
            log("$name: $what: ${describe(reasonForIoFailure(e))} [${kinds(e)}]")
        } catch (e: AnthropicInvalidDataException) {
            log("$name: $what: what came back is not from the API")
        } catch (e: Exception) {
            log("$name: $what failed (${e.javaClass.simpleName})")
        }
    }

    /** An error status. What it means is decided from the number; the rest is for the log. */
    private fun failed(e: AnthropicServiceException): AIReplyResult.NoReply {
        tellClockFromError(e)
        return noReply(NoReplyReason.forHttpStatus(e.statusCode()), summary(e))
    }

    /** [arrivedAt]: the moment the response came, by [ServerClock.arrival]; null for "now". */
    private fun tellClock(headers: Headers, arrivedAt: Long?) {
        forLog {
            serverClock?.syncFromHeaders(
                date = headers.values("date").firstOrNull(), age = headers.values("age").firstOrNull(), arrivedAtMs = arrivedAt,
            )
        }
    }

    /**
     * An error tells the time only when its body is an error of the API: it names one of the
     * error types the API has. (Any string at that place makes an "error type" for the SDK; a
     * page of something in between can have one too.) The SDK has read the small body of the
     * error by the time it is thrown, so the moment taken is the moment it is caught.
     */
    private fun tellClockFromError(e: AnthropicServiceException) {
        forLog {
            val known = e.errorType().map { it.value() != ErrorType.Value._UNKNOWN }.orElse(false)
            if (known) tellClock(e.headers(), arrivedAt = null)
        }
    }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        forLog { log("$name: no reply, ${describe(reason)} [$detail]") }
        return AIReplyResult.NoReply(reason, detail)
    }

    private fun options(limit: Duration): RequestOptions =
        RequestOptions.builder().timeout(timeouts(limit)).followRedirects(false).build()

    companion object {
        /** The model Afik asked for by name. */
        const val MODEL = "claude-fable-5-2"

        /** Room for the thinking that comes before the answer, not the length of the answer. */
        const val MAX_TOKENS = 16_000L

        /**
         * The longest one request may take. Long, on purpose: the sign says "thinking" for 30 s only
         * ([AIQueryDesk.Limits.noticeMs]), but a reply that comes after that is still used. A
         * request gets less when less of its query's deadline is left.
         */
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(90)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /**
         * With less than this left of the query's deadline the request is not sent: it would be
         * paid for and, most likely, not waited for. (A reply takes about 4 s at high effort.)
         */
        val MIN_TIME_TO_SEND: Duration = Duration.ofSeconds(15)

        /** One attempt by the SDK. See the class comment. */
        const val MAX_RETRIES = 0

        /** Server-side fallback on a refusal; the "default" form needs this beta. */
        val FALLBACK_BETA: AnthropicBeta = AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01

        /** The time limit for each of the two requests of the warm-up: the first connection of a cold start is slow. */
        private val WARM_UP_TIME: Duration = Duration.ofSeconds(30)

        /** With less than this to spend, the time is not asked for. */
        private val MIN_TIME_CHECK: Duration = Duration.ofSeconds(1)

        /** The key of the client that talks to the stand-in: made up, and worth nothing anywhere. */
        internal const val WARM_UP_KEY = "warm-up-no-key"

        /** A message as the API sends it, for the local part of the warm-up. */
        private const val WARM_UP_ANSWER =
            """{"id":"msg_warm_up","type":"message","role":"assistant","model":"$MODEL",""" +
                """"content":[{"type":"thinking","thinking":"","signature":""},{"type":"text","text":"warm"}],""" +
                """"stop_reason":"end_turn","stop_sequence":null,"stop_details":null,""" +
                """"usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

        /** The refusal categories the log may name. Anything else is "not given or not known". */
        private val REFUSAL_CATEGORIES = setOf("cyber", "bio", "reasoning_extraction", "frontier_llm", "general_harms")

        /** The whole request within [limit], and the connection within its share of it. */
        private fun timeouts(limit: Duration): Timeout =
            Timeout.builder().connect(minOf(CONNECT_TIMEOUT, limit)).request(limit).build()

        /**
         * Runs [read], which is there for a log line or the clock only. Whatever it throws is
         * dropped: it must never decide what an answer is.
         */
        private inline fun <T> forLog(read: () -> T): T? =
            try {
                read()
            } catch (e: Exception) {
                null
            }

        /** "HTTP 429 RATE_LIMIT_ERROR": the status and the SDK's own name for the error type, nothing from the body. */
        private fun summary(e: AnthropicServiceException): String =
            "HTTP ${e.statusCode()} ${forLog { e.errorType().map { it.value().name }.orElse(null) } ?: "no error type"}"

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
            NoReplyReason.WITHDRAWN -> "the query was withdrawn before anything was sent"
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
