// Ported from com.anthropic:anthropic-java-client-okhttp:3.3.0 (OkHttpClient.kt); re-check on SDK upgrades.
// Read against 3.12.0: same HttpClient and Backend interfaces. Its one change, path segments percent-encoded
// by the SDK's own table, sends the same bytes for the paths this app calls (v1/messages, v1/models/<id>).
package cx.aphex.energysign.ai

import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpMethod
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpRequestBody
import com.anthropic.core.http.HttpResponse
import com.anthropic.errors.AnthropicIoException
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * OkHttp transport for the Anthropic SDK with [WireBudgetInterceptor] below `ConnectInterceptor`,
 * one-shot request bodies (`isOneShot() = true`, disabling silent OkHttp replays), and per-call
 * timeouts computed AFTER body serialization (`body.contentLength()`).
 */
internal class BudgetedOkHttpClient(
    private val maxRequestTimeout: Duration,
    private val connectTimeout: Duration,
    private val backend: Backend,
    networkInterceptor: Interceptor,
    private val currentContext: () -> WireCallContext?,
) : HttpClient {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .pingInterval(Duration.ofMinutes(1))
        .addNetworkInterceptor(networkInterceptor)
        .build()

    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val prepared = prepareRequest(request)
        val context = currentContext()
            ?: throw AnthropicIoException("Request failed", WireSendBlockedException(NoReplyReason.WITHDRAWN, "not sent: untagged request"))
        val call = try {
            newCall(prepared, context)
        } catch (e: IOException) {
            prepared.body().orElse(null)?.close()
            throw AnthropicIoException("Request failed", e)
        }
        return try {
            prepareResponse(toHttpResponse(call.execute(), call))
        } catch (e: IOException) {
            throw AnthropicIoException("Request failed", e)
        } finally {
            prepared.body().orElse(null)?.close()
        }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> =
        CompletableFuture.supplyAsync { execute(request, requestOptions) }

    override fun close() {
        backend.close()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
    }

    private fun prepareRequest(request: HttpRequest): HttpRequest {
        val backendPrepared = backend.prepareRequest(request)
        return if (backendPrepared.baseUrl.isNotEmpty()) backendPrepared
        else backendPrepared.toBuilder().baseUrl(backend.baseUrl()).build()
    }

    private fun prepareResponse(response: HttpResponse): HttpResponse =
        try { backend.prepareResponse(response) } catch (e: Exception) { response.close(); throw e }

    private fun newCall(request: HttpRequest, context: WireCallContext): Call {
        // Materialize URL and body FIRST: body.contentLength() lazily serializes JSON in the SDK.
        val url = toUrl(request)
        val body = request.body().orElse(null)?.let(::toOkHttpRequestBody)
            ?: if (request.method in BODY_METHODS) EMPTY_ONE_SHOT_BODY else null

        val leftMs = context.deadline.remainingMs()
        val limit = if (context.reservation != null) {
            if (leftMs < context.minTimeToSend.toMillis()) {
                throw WireSendBlockedException(NoReplyReason.TOO_LATE_TO_SEND, "not sent: ${leftMs / 1000} s of the query's time were left")
            }
            minOf(maxRequestTimeout, Duration.ofMillis(leftMs))
        } else {
            if (leftMs <= 0L) throw WireSendBlockedException(NoReplyReason.TIMEOUT, "not sent: free request deadline passed")
            Duration.ofMillis(leftMs)
        }

        val callClient = okHttpClient.newBuilder()
            .connectTimeout(minOf(connectTimeout, limit))
            .readTimeout(limit)
            .writeTimeout(limit)
            .callTimeout(limit)
            .build()

        val builder = Request.Builder().url(url).method(request.method.name, body).tag(WireCallContext::class.java, context)
        for (name in request.headers.names()) {
            for (value in request.headers.values(name)) builder.addHeader(name, value)
        }
        val seconds = limit.seconds.toString()
        if ("X-Stainless-Read-Timeout" !in request.headers.names()) builder.addHeader("X-Stainless-Read-Timeout", seconds)
        if ("X-Stainless-Timeout" !in request.headers.names()) builder.addHeader("X-Stainless-Timeout", seconds)
        return callClient.newCall(builder.build())
    }

    private fun toUrl(request: HttpRequest): String =
        request.baseUrl.toHttpUrl().newBuilder().apply {
            request.pathSegments.forEach(::addPathSegment)
            for (key in request.queryParams.keys()) {
                for (value in request.queryParams.values(key)) addQueryParameter(key, value)
            }
        }.build().toString()

    private fun toOkHttpRequestBody(body: HttpRequestBody): RequestBody {
        val mediaType = body.contentType()?.toMediaTypeOrNull()
        val length = body.contentLength()
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = length
            // One-shot stops OkHttp's silent 503 Retry-After: 0 and HTTP/2 421 replays.
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink.outputStream())
        }
    }

    private fun toHttpResponse(response: Response, call: Call): HttpResponse {
        val headers = Headers.builder().apply {
            for ((name, value) in response.headers) put(name, value)
        }.build()
        val stream: InputStream = response.body?.let { body ->
            val inner = body.byteStream()
            object : InputStream() {
                override fun read() = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
                override fun available() = inner.available()
                override fun close() { try { inner.close() } finally { body.close(); call.cancel() } }
            }
        } ?: ByteArrayInputStream(ByteArray(0))
        return object : HttpResponse {
            override fun statusCode() = response.code
            override fun headers() = headers
            override fun body() = stream
            override fun close() { try { stream.close() } finally { response.close() } }
        }
    }

    private companion object {
        val BODY_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
        val EMPTY_ONE_SHOT_BODY = object : RequestBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {}
        }
    }
}
