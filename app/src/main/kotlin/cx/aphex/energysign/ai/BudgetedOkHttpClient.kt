package cx.aphex.energysign.ai

import com.anthropic.backends.Backend
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * Our own OkHttp transport implementing the Anthropic SDK's [HttpClient] so that
 * [WireBudgetInterceptor] runs as an OkHttp network interceptor directly below
 * `RetryAndFollowUpInterceptor` and directly above `CallServerInterceptor`.
 *
 * Also refreshes per-call OkHttp timeouts from the active [WireCallContext]'s absolute [Deadline]
 * right before constructing the [Call], so time spent preparing or serializing a request never
 * buys a fresh relative timeout past the query's deadline.
 */
internal class BudgetedOkHttpClient(
    private val defaultTimeout: Timeout,
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
        .connectTimeout(defaultTimeout.connect())
        .readTimeout(defaultTimeout.read())
        .writeTimeout(defaultTimeout.write())
        .callTimeout(defaultTimeout.request())
        .addNetworkInterceptor(networkInterceptor)
        .build()

    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        val prepared = prepareRequest(request)
        val context = currentContext()
        val call = try {
            newCall(prepared, requestOptions, context)
        } catch (e: IOException) {
            closeBody(prepared)
            throw AnthropicIoException("Request failed", e)
        }
        return try {
            val response = call.execute()
            prepareResponse(toHttpResponse(response, call))
        } catch (e: IOException) {
            throw AnthropicIoException("Request failed", e)
        } finally {
            closeBody(prepared)
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
        return resolveUrl(backendPrepared)
    }

    private fun resolveUrl(request: HttpRequest): HttpRequest {
        if (request.baseUrl().isNotEmpty()) return request
        return request.toBuilder().baseUrl(backend.baseUrl()).build()
    }

    private fun prepareResponse(response: HttpResponse): HttpResponse =
        try {
            backend.prepareResponse(response)
        } catch (e: Exception) {
            response.close()
            throw e
        }

    private fun newCall(request: HttpRequest, requestOptions: RequestOptions, context: WireCallContext?): Call {
        val clientBuilder = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)

        val effectiveTimeout = when (context) {
            is WireCallContext.Paid -> {
                val leftMs = context.deadline.remainingMs()
                if (leftMs < context.minTimeToSend.toMillis()) {
                    throw WireSendBlockedException.tooLate(
                        leftMs = leftMs,
                        priorHttpStatus = null,
                        afterWireSend = false,
                    )
                }
                val limit = minOf(maxRequestTimeout, Duration.ofMillis(leftMs))
                Timeout.builder()
                    .connect(minOf(connectTimeout, limit))
                    .read(limit)
                    .write(limit)
                    .request(limit)
                    .build()
            }

            is WireCallContext.FreeModelGet -> {
                val leftMs = context.deadline.remainingMs()
                if (leftMs <= 0L) {
                    throw WireSendBlockedException.tooLate(
                        leftMs = 0L,
                        priorHttpStatus = null,
                        afterWireSend = false,
                    )
                }
                val limit = Duration.ofMillis(leftMs)
                Timeout.builder()
                    .connect(minOf(connectTimeout, limit))
                    .read(limit)
                    .write(limit)
                    .request(limit)
                    .build()
            }

            null -> requestOptions.timeout().orElse(defaultTimeout)
        }

        clientBuilder
            .connectTimeout(effectiveTimeout.connect())
            .readTimeout(effectiveTimeout.read())
            .writeTimeout(effectiveTimeout.write())
            .callTimeout(effectiveTimeout.request())

        val callClient = clientBuilder.build()
        return callClient.newCall(toOkHttpRequest(request, callClient, context))
    }

    private fun toOkHttpRequest(request: HttpRequest, client: OkHttpClient, context: WireCallContext?): Request {
        var body: RequestBody? = request.body().orElse(null)?.let(::toOkHttpRequestBody)
        if (body == null && requiresBody(request.method())) {
            body = "".toRequestBody(null)
        }
        val builder = Request.Builder()
            .url(toUrl(request))
            .method(request.method().name, body)
            .tag(WireCallContext::class.java, context)

        for (name in request.headers().names()) {
            for (value in request.headers().values(name)) {
                builder.addHeader(name, value)
            }
        }
        if ("X-Stainless-Read-Timeout" !in request.headers().names() && client.readTimeoutMillis != 0) {
            builder.addHeader("X-Stainless-Read-Timeout", Duration.ofMillis(client.readTimeoutMillis.toLong()).seconds.toString())
        }
        if ("X-Stainless-Timeout" !in request.headers().names() && client.callTimeoutMillis != 0) {
            builder.addHeader("X-Stainless-Timeout", Duration.ofMillis(client.callTimeoutMillis.toLong()).seconds.toString())
        }
        return builder.build()
    }

    private fun toUrl(request: HttpRequest): String {
        val urlBuilder = request.baseUrl().toHttpUrl().newBuilder()
        for (segment in request.pathSegments()) {
            urlBuilder.addPathSegment(segment)
        }
        for (key in request.queryParams().keys()) {
            for (value in request.queryParams().values(key)) {
                urlBuilder.addQueryParameter(key, value)
            }
        }
        return urlBuilder.build().toString()
    }

    private fun toOkHttpRequestBody(body: HttpRequestBody): RequestBody {
        val mediaType: MediaType? = body.contentType()?.toMediaTypeOrNull()
        val length = body.contentLength()
        return object : RequestBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = length
            override fun isOneShot(): Boolean = !body.repeatable()
            override fun writeTo(sink: BufferedSink) {
                body.writeTo(sink.outputStream())
            }
        }
    }

    private fun toHttpResponse(response: Response, call: Call): HttpResponse {
        val headersBuilder = Headers.builder()
        for ((name, value) in response.headers) {
            headersBuilder.put(name, value)
        }
        val headers = headersBuilder.build()
        val rawBody = response.body
        val stream: InputStream = rawBody?.let { body ->
            val inner = body.byteStream()
            object : InputStream() {
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
                override fun available(): Int = inner.available()
                override fun close() {
                    try {
                        inner.close()
                    } finally {
                        body.close()
                        call.cancel()
                    }
                }
            }
        } ?: ByteArrayInputStream(ByteArray(0))

        return object : HttpResponse {
            override fun statusCode(): Int = response.code
            override fun headers(): Headers = headers
            override fun body(): InputStream = stream
            override fun close() {
                try {
                    stream.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    private fun requiresBody(method: HttpMethod): Boolean =
        method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH

    private fun closeBody(request: HttpRequest) {
        request.body().orElse(null)?.close()
    }
}
