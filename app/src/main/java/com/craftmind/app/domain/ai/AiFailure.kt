package com.craftmind.app.domain.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/** Safe, provider-independent failure identifiers. Raw HTTP bodies and stack traces never reach UI. */
enum class AiErrorCode {
    INVALID_API_KEY,
    PROVIDER_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    RATE_LIMITED,
    NETWORK_TIMEOUT,
    NETWORK_UNAVAILABLE,
    INVALID_AI_RESPONSE,
    INVALID_BUILD_PLAN,
    UNSUPPORTED_SCHEMA_VERSION,
    BUILD_TOO_LARGE,
    RESPONSE_TOO_LARGE,
    UNSUPPORTED_CAPABILITY,
    MISSING_CREDENTIAL,
    CREDENTIAL_STORAGE_FAILURE,
    NO_PROVIDER_SELECTED,
    NO_MODEL_SELECTED,
    NO_MODELS_AVAILABLE,
    CANCELLED,
    UNKNOWN_PROVIDER_ERROR,
}

data class AiFailure(
    val code: AiErrorCode,
    val retryable: Boolean,
)

class AiProviderException(val failure: AiFailure) : Exception(failure.code.name)

object AiErrorMapper {
    fun fromThrowable(error: Throwable): AiFailure = when (error) {
        is AiProviderException -> error.failure
        is SocketTimeoutException -> AiFailure(AiErrorCode.NETWORK_TIMEOUT, retryable = true)
        is InterruptedIOException -> AiFailure(AiErrorCode.NETWORK_TIMEOUT, retryable = true)
        is UnknownHostException -> AiFailure(AiErrorCode.NETWORK_UNAVAILABLE, retryable = true)
        is IOException -> AiFailure(AiErrorCode.NETWORK_UNAVAILABLE, retryable = true)
        is CancellationException -> AiFailure(AiErrorCode.CANCELLED, retryable = true)
        else -> AiFailure(AiErrorCode.UNKNOWN_PROVIDER_ERROR, retryable = false)
    }

    fun forHttpStatus(statusCode: Int): AiFailure = when (statusCode) {
        401, 403 -> AiFailure(AiErrorCode.INVALID_API_KEY, retryable = false)
        404 -> AiFailure(AiErrorCode.MODEL_UNAVAILABLE, retryable = false)
        408, 504 -> AiFailure(AiErrorCode.NETWORK_TIMEOUT, retryable = true)
        413 -> AiFailure(AiErrorCode.RESPONSE_TOO_LARGE, retryable = false)
        429 -> AiFailure(AiErrorCode.RATE_LIMITED, retryable = true)
        in 500..599 -> AiFailure(AiErrorCode.PROVIDER_UNAVAILABLE, retryable = true)
        400 -> AiFailure(AiErrorCode.UNSUPPORTED_CAPABILITY, retryable = false)
        else -> AiFailure(AiErrorCode.UNKNOWN_PROVIDER_ERROR, retryable = false)
    }
}
