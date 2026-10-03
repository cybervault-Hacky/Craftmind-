package com.craftmind.app.data.ai.openai

import com.craftmind.app.domain.ai.AiModelIdValidator
import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.SecretValue
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

internal sealed interface OpenAiHttpCall {
    data class CreateCompletion(val jsonBody: String) : OpenAiHttpCall
    data class RetrieveModel(val modelId: String) : OpenAiHttpCall
}

internal enum class OpenAiTransportFailure {
    NO_INTERNET,
    REQUEST_TIMED_OUT,
    PROVIDER_UNAVAILABLE,
}

internal sealed interface OpenAiHttpResult {
    data class Response(val statusCode: Int, val body: String) : OpenAiHttpResult
    data class Failed(val reason: OpenAiTransportFailure) : OpenAiHttpResult
    data object RequestTooLarge : OpenAiHttpResult
    data object ResponseTooLarge : OpenAiHttpResult
}

internal fun interface OpenAiHttpTransport {
    suspend fun execute(call: OpenAiHttpCall, credential: SecretValue): OpenAiHttpResult
}

/** Fixed HTTPS host, bounded body reading, disabled redirects, no logging interceptor, cancellable calls. */
internal class OkHttpOpenAiHttpTransport(
    private val client: OkHttpClient = newDefaultClient(),
) : OpenAiHttpTransport {
    override suspend fun execute(call: OpenAiHttpCall, credential: SecretValue): OpenAiHttpResult =
        suspendCancellableCoroutine { continuation ->
            val request = try {
                buildRequest(call, credential)
            } catch (_: RequestBodyLimitException) {
                if (continuation.isActive) continuation.resume(OpenAiHttpResult.RequestTooLarge)
                return@suspendCancellableCoroutine
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(OpenAiHttpResult.Failed(OpenAiTransportFailure.PROVIDER_UNAVAILABLE))
                return@suspendCancellableCoroutine
            }
            val okHttpCall = client.newCall(request)
            continuation.invokeOnCancellation { okHttpCall.cancel() }
            okHttpCall.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume(OpenAiHttpResult.Failed(error.toTransportFailure()))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use(::readResponse)
                    } catch (error: IOException) {
                        OpenAiHttpResult.Failed(error.toTransportFailure())
                    } catch (_: Exception) {
                        OpenAiHttpResult.Failed(OpenAiTransportFailure.PROVIDER_UNAVAILABLE)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private fun buildRequest(call: OpenAiHttpCall, credential: SecretValue): Request {
        val (url, method, body) = when (call) {
            is OpenAiHttpCall.CreateCompletion -> {
                if (call.jsonBody.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
                    throw RequestBodyLimitException()
                }
                Triple(
                    apiRoot.newBuilder().addPathSegment("chat").addPathSegment("completions").build(),
                    "POST",
                    call.jsonBody.toRequestBody(JSON_MEDIA_TYPE),
                )
            }
            is OpenAiHttpCall.RetrieveModel -> {
                if (AiModelIdValidator.normalize(call.modelId) != call.modelId) {
                    throw IllegalArgumentException("Invalid model id.")
                }
                Triple(
                    apiRoot.newBuilder().addPathSegment("models").addPathSegment(call.modelId).build(),
                    "GET",
                    null,
                )
            }
        }
        require(url.isHttps && url.host == API_HOST)
        val authHeader = credential.use { chars -> "Bearer ${String(chars)}" }
        return Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Accept", "application/json")
            .method(method, body)
            .build()
    }

    private fun readResponse(response: Response): OpenAiHttpResult {
        val body = response.body ?: return OpenAiHttpResult.Response(response.code, "")
        if (body.contentLength() > MAX_RESPONSE_BYTES) return OpenAiHttpResult.ResponseTooLarge
        val sink = Buffer()
        val source = body.source()
        var total = 0L
        while (true) {
            val bytesToRead = minOf(READ_BUFFER_BYTES.toLong(), MAX_RESPONSE_BYTES + 1L - total)
            if (bytesToRead <= 0L) return OpenAiHttpResult.ResponseTooLarge
            val read = source.read(sink, bytesToRead)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) return OpenAiHttpResult.ResponseTooLarge
        }
        return OpenAiHttpResult.Response(response.code, sink.readByteArray().toString(Charsets.UTF_8))
    }

    private fun IOException.toTransportFailure(): OpenAiTransportFailure = when (this) {
        is SocketTimeoutException -> OpenAiTransportFailure.REQUEST_TIMED_OUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> OpenAiTransportFailure.NO_INTERNET
        is SSLException -> OpenAiTransportFailure.PROVIDER_UNAVAILABLE
        else -> OpenAiTransportFailure.PROVIDER_UNAVAILABLE
    }

    private companion object {
        const val API_HOST = "api.openai.com"
        const val MAX_REQUEST_BYTES = BuildLimits.MAX_PROVIDER_REQUEST_BYTES.toLong()
        const val MAX_RESPONSE_BYTES = BuildLimits.MAX_PLAN_RESPONSE_BYTES.toLong()
        const val READ_BUFFER_BYTES = 8 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val apiRoot: HttpUrl = "https://api.openai.com/v1/".toHttpUrl()

        fun newDefaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

private class RequestBodyLimitException : RuntimeException()
