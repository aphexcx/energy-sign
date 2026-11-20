package cx.aphex.energysign.ai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.exception.OpenAIException
import com.aallam.openai.api.exception.OpenAIIOException
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.exception.OpenAITimeoutException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Replies written by an OpenAI model, behind the common interface: the model and the temperature
 * the app always used, and the answer is still streamed and put together here. What is different
 * from the code this replaces:
 *
 * - A reply is whole or absent. The text is used only when the stream is ONE answer that ends by
 *   saying "stop" and has nothing after that. A stream that ends because of the length limit or a
 *   content filter, that simply ends, that carries a second answer, or that goes on after its
 *   end, gives no reply, whatever text came before.
 * - One attempt, and ONE time limit: the deadline of the query ([AIReplyRequest.deadline]). The
 *   request gets what is left of it when it goes out ([requestTimeout] at most); the HTTP
 *   client's own limits are longer than that, so they do not end it. The client's own
 *   retries are off: it waited between them for as long as a `Retry-After` header said. Whether a
 *   query is asked again is decided in one place, [AIQueryDesk].
 * - What a failure means is decided from the HTTP status alone ([NoReplyReason.forHttpStatus]),
 *   also when the body of the error is not the API's and the client cannot read it.
 * - The client's own logging of requests and headers is off, and a log line of this class holds
 *   the HTTP status and words from a fixed list, never text from a response.
 * - This client hands no response headers on, so it cannot tell the time. [timeFrom] does, when
 *   there is one; without it the time stays unknown and the prompt says nothing about time.
 */
class OpenAIReplyGenerator(
    apiKey: String,
    private val log: (String) -> Unit,
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    /** Only tests set this, to a local fake of the API. */
    private val baseUrl: String? = null,
    /** Asked for the time when it is not known. */
    private val timeFrom: AIReplyGenerator? = null,
    private val minTimeToSend: Duration = AnthropicReplyGenerator.MIN_TIME_TO_SEND,
    /** How much longer than [requestTimeout] the HTTP client's own limits are. Only tests set this. */
    transportMargin: Duration = TRANSPORT_MARGIN,
    /** Shared rolling-hour ceiling on paid HTTP requests leaving the app. */
    private val budget: AIHourlyBudget = AIHourlyBudget({ System.nanoTime() / 1_000_000 }),
) : AIReplyGenerator {

    private val apiKey: String = apiKey.trim()

    override val name: String = "openai/$MODEL"

    /**
     * The HTTP status of the response that failed the request under way, or 0. The client reads
     * the body of an error before it says what the error is, and when that body is not the
     * API's own JSON (a page of something in between, nothing at all) the reading throws and
     * the status is lost with it. So the status is noted here, by the HTTP layer itself, before
     * anybody looks at the body. One request at a time uses this object.
     */
    private val failedWithStatus = AtomicInteger(0)

    /** Carries per-call context into OkHttp's application/network interceptors on Ktor's dispatcher thread. */
    private val activeCall = AtomicReference<WireCallContext?>(null)

    /** What the client is built from. */
    internal val config = OpenAIConfig(
        token = this.apiKey,
        // The default writes every request's headers to the console.
        logging = LoggingConfig(logLevel = LogLevel.None),
        // The HTTP client's own limits: LONGER than the longest a request may take. What ends
        // a request is the deadline of its query, in [generateReply], and only that: a limit of
        // the client that ran out at the same moment would throw, and an answer that had
        // already said "stop" would be lost with it.
        timeout = Timeout(
            request = (requestTimeout + transportMargin).toMillis().milliseconds,
            connect = CONNECT_TIMEOUT,
            socket = (requestTimeout + transportMargin).toMillis().milliseconds,
        ),
        host = baseUrl?.let { OpenAIHost(baseUrl = it) } ?: OpenAIHost.OpenAI,
        retry = RetryStrategy(maxRetries = 0),
        httpClientConfig = {
            // A rejected key is asked once. The client would send a request that got HTTP 401 a second
            // time with the same key (its key cannot be renewed), and the answer would be the same.
            install(Auth) { reAuthorizeOnResponse { false } }
            followRedirects = false
            engine {
                val okHttp = this as? OkHttpConfig
                    ?: error("OpenAIReplyGenerator requires Ktor OkHttp engine so WireBudgetInterceptor gates every wire send")
                // One call is at most one request on the wire: disable redirects and connection-failure
                // retries and mark every POST body one-shot so OkHttp never replays 503/408/421.
                okHttp.config {
                    retryOnConnectionFailure(false)
                    followRedirects(false)
                    followSslRedirects(false)
                }
                okHttp.addInterceptor { chain ->
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                    val req = chain.request()
                    val body = req.body
                    val builder = req.newBuilder().tag(WireCallContext::class.java, activeCall.get())
                    if (body != null) builder.method(req.method, oneShot(body))
                    chain.proceed(builder.build())
                }
                okHttp.addNetworkInterceptor(WireBudgetInterceptor())
            }
            HttpResponseValidator {
                handleResponseExceptionWithRequest { failure, _ ->
                    (failure as? ResponseException)?.let { failedWithStatus.set(it.response.status.value) }
                }
            }
        },
    )

    private val openAI: OpenAI by lazy { OpenAI(config) }

    override suspend fun generateReply(request: AIReplyRequest): AIReplyResult {
        if (apiKey.isEmpty()) {
            return noReply(NoReplyReason.MISSING_KEY, "no OPENAI_API_KEY in this build (local.properties)")
        }
        failedWithStatus.set(0)
        return try {
            // Everything that can take time before the request is done first (the client is built
            // by its first use); then the two questions that decide whether it goes out at all.
            val client = openAI
            if (!request.stillWanted()) {
                return noReply(NoReplyReason.WITHDRAWN, "not sent: the query is not wanted any more")
            }
            val leftMs = request.deadline.remainingMs()
            if (leftMs < minTimeToSend.toMillis()) {
                return noReply(NoReplyReason.TOO_LATE_TO_SEND, "not sent: ${leftMs / 1000} s of the query's time were left")
            }
            val limitMs = minOf(requestTimeout.toMillis(), leftMs)
            val stream = Stream()
            val owned = if (request.reservation == null) {
                budget.reserve() ?: return noReply(NoReplyReason.WITHDRAWN, "not sent: hourly budget is spent")
            } else {
                null
            }
            val reservation = request.reservation ?: owned!!
            activeCall.set(WireCallContext(request.deadline, minTimeToSend, reservation))
            val ended = try {
                withTimeoutOrNull(limitMs) { collect(client, request, stream) } != null
            } catch (e: Throwable) {
                // The wire gate's refusal first: the client wraps it as an OpenAIIOException.
                WireSendBlockedException.findIn(e)?.let { return noReply(it.reason, it.detail) }
                // A limit of the HTTP client ended it after all: what has come is read the same way.
                if (e is OpenAIIOException && (e is OpenAITimeoutException || e.isSocketTimeout())) false else throw e
            } finally {
                activeCall.set(null)
                owned?.release()
            }
            stream.read(ended, limitMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenAITimeoutException) {
            noReply(NoReplyReason.TIMEOUT, e.javaClass.simpleName)
        } catch (e: OpenAIIOException) {
            if (e.isSocketTimeout()) noReply(NoReplyReason.TIMEOUT, "SocketTimeoutException")
            else noReply(NoReplyReason.NETWORK, e.javaClass.simpleName)
        } catch (e: OpenAIAPIException) {
            // Every HTTP status with an error body, whatever class the client makes of it.
            noReply(NoReplyReason.forHttpStatus(e.statusCode), summary(e))
        } catch (e: OpenAIServerException) {
            noReply(NoReplyReason.SERVER_ERROR, e.javaClass.simpleName)
        } catch (e: OpenAIException) {
            noReply(NoReplyReason.PROVIDER_ERROR, e.javaClass.simpleName)
        } catch (e: Exception) {
            // What the client let through as it was. With a status noted, it is an error
            // response whose body the client could not read: the status says what it means.
            val status = failedWithStatus.get()
            if (status > 0) noReply(NoReplyReason.forHttpStatus(status), "HTTP $status, with a body that is not the API's")
            else noReply(NoReplyReason.PROVIDER_ERROR, e.javaClass.simpleName)
        }
    }

    /**
     * The HTTP client's socket limit ran out while the answer was being read. Since Ktor 3 that
     * arrives as a failure of the stream with the timeout as its cause, which the OpenAI client
     * takes for a network failure, not for the timeout it is.
     */
    private fun OpenAIIOException.isSocketTimeout(): Boolean =
        generateSequence(cause) { it.cause }.take(16).any { it is java.net.SocketTimeoutException }

    private fun oneShot(body: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }

    override fun syncClock(limit: Duration) {
        timeFrom?.syncClock(limit)
    }

    /**
     * Before a person waits for it: the time is asked for, which also gets the slow first use of
     * the client that asks out of the way. The OpenAI client itself has no such first use to
     * speak of. Costs nothing, never throws.
     */
    override fun warmUp() {
        timeFrom?.syncClock(WARM_UP_TIME)
    }

    /** What has come of the stream so far. Written by [collect], read when the stream or the time has ended. */
    private inner class Stream {
        val text = StringBuilder()
        var finish: String? = null
        var choices = 0
        var broken: String? = null

        /** What the stream is. [ended]: it came to its end; otherwise the time limit of [limitMs] ended it. */
        fun read(ended: Boolean, limitMs: Long): AIReplyResult = when {
            broken != null -> noReply(NoReplyReason.UNEXPECTED_RESPONSE, broken!!)
            // An answer that said "stop" is whole. When only the END of the stream did not come
            // in time, it is still the answer: it was paid for, and is not thrown away.
            finish == "stop" ->
                if (text.isBlank()) noReply(NoReplyReason.EMPTY, "no text in the answer") else AIReplyResult.Reply(text.toString())

            !ended -> noReply(NoReplyReason.TIMEOUT, "no complete answer within ${limitMs / 1000} s")
            // HTTP 200 with nothing of a completion in it (a page of something that sits in
            // between has no "data:" lines): not the API's answer at all. That may pass.
            choices == 0 -> noReply(NoReplyReason.NOT_A_MESSAGE, "nothing of a completion came back")
            finish == "length" -> noReply(NoReplyReason.TRUNCATED, "the answer stopped at the length limit")
            finish == "content_filter" -> noReply(NoReplyReason.REFUSED, "the answer was stopped by the content filter")
            finish == null -> noReply(NoReplyReason.TRUNCATED, "the stream ended without saying the answer was finished")
            else -> noReply(NoReplyReason.UNEXPECTED_RESPONSE, "a finish reason this app does not handle")
        }
    }

    private suspend fun collect(openAI: OpenAI, request: AIReplyRequest, stream: Stream) {
        openAI.chatCompletions(
            ChatCompletionRequest(
                model = ModelId(MODEL),
                messages = listOf(
                    ChatMessage(role = ChatRole.System, content = request.systemPrompt),
                    ChatMessage(role = ChatRole.User, content = request.userText),
                ),
                temperature = TEMPERATURE,
                n = 1,
            )
        )
            .flowOn(Dispatchers.IO)
            .collect { chunk ->
                // One answer was asked for: a piece of the stream carries one piece of it.
                if (chunk.choices.size > 1) stream.broken = "the stream carries more than the one answer that was asked for"
                for (choice in chunk.choices) {
                    stream.choices++
                    val piece = choice.delta?.content
                    when {
                        // A second answer is not put together with the first, and its end is
                        // not taken for the end of the first.
                        choice.index != 0 -> stream.broken = "the stream carries more than the one answer that was asked for"
                        stream.finish != null -> if (!piece.isNullOrEmpty() || choice.finishReason != null) {
                            stream.broken = "the stream goes on after the end of the answer"
                        }

                        else -> {
                            piece?.let(stream.text::append)
                            choice.finishReason?.let { stream.finish = it.value }
                        }
                    }
                }
            }
    }

    private fun noReply(reason: NoReplyReason, detail: String): AIReplyResult.NoReply {
        log("$name: no reply, $reason [$detail]")
        return AIReplyResult.NoReply(reason, detail)
    }

    companion object {
        const val MODEL = "gpt-5.1"
        const val TEMPERATURE = 0.7
        private val CONNECT_TIMEOUT = 10.seconds
        private val WARM_UP_TIME: Duration = Duration.ofSeconds(30)

        /** How much longer than a request may take the HTTP client's own limits are. */
        val TRANSPORT_MARGIN: Duration = Duration.ofSeconds(10)

        /** The longest one request may take, the same as for the other provider. */
        val REQUEST_TIMEOUT: Duration = AnthropicReplyGenerator.REQUEST_TIMEOUT

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
