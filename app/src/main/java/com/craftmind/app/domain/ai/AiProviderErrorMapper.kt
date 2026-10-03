package com.craftmind.app.domain.ai

import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildErrorCode

fun AiProviderError.toBuildError(): BuildError = BuildError(
    code = when (code) {
        AiProviderErrorCode.INVALID_API_KEY -> BuildErrorCode.INVALID_API_KEY
        AiProviderErrorCode.UNSUPPORTED_MODEL -> BuildErrorCode.UNSUPPORTED_MODEL
        AiProviderErrorCode.PROVIDER_UNAVAILABLE -> BuildErrorCode.PROVIDER_UNAVAILABLE
        AiProviderErrorCode.NO_INTERNET -> BuildErrorCode.NO_INTERNET
        AiProviderErrorCode.REQUEST_TIMED_OUT -> BuildErrorCode.REQUEST_TIMED_OUT
        AiProviderErrorCode.RATE_LIMITED -> BuildErrorCode.RATE_LIMITED
        AiProviderErrorCode.REQUEST_REJECTED -> BuildErrorCode.PROVIDER_REJECTED_REQUEST
        AiProviderErrorCode.MALFORMED_RESPONSE -> BuildErrorCode.MALFORMED_RESPONSE
        AiProviderErrorCode.RESPONSE_TOO_LARGE -> BuildErrorCode.RESPONSE_TOO_LARGE
        AiProviderErrorCode.UNKNOWN -> BuildErrorCode.UNKNOWN
    },
    retryable = retryable,
)
