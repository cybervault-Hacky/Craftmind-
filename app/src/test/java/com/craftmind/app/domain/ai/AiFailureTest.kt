package com.craftmind.app.domain.ai

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFailureTest {
    @Test
    fun mapsProviderStatusesToSafeAndIntentionalRetryPolicy() {
        assertEquals(AiErrorCode.INVALID_API_KEY, AiErrorMapper.forHttpStatus(401).code)
        assertFalse(AiErrorMapper.forHttpStatus(401).retryable)
        assertEquals(AiErrorCode.RATE_LIMITED, AiErrorMapper.forHttpStatus(429).code)
        assertTrue(AiErrorMapper.forHttpStatus(429).retryable)
        assertEquals(AiErrorCode.PROVIDER_UNAVAILABLE, AiErrorMapper.forHttpStatus(503).code)
        assertTrue(AiErrorMapper.forHttpStatus(503).retryable)
    }

    @Test
    fun exceptionMessageContainsOnlySafeErrorCode() {
        val error = AiProviderException(AiFailure(AiErrorCode.INVALID_API_KEY, retryable = false))
        assertEquals("INVALID_API_KEY", error.message)
        assertFalse(error.toString().contains("secret-key"))
    }

    @Test
    fun classifiesTransportTimeoutAsRetryableWithoutRawDetails() {
        val failure = AiErrorMapper.fromThrowable(SocketTimeoutException("secret-token in diagnostic"))
        assertEquals(AiErrorCode.NETWORK_TIMEOUT, failure.code)
        assertTrue(failure.retryable)
    }
}
