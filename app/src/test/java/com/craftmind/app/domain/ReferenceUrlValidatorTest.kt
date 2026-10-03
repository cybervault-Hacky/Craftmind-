package com.craftmind.app.domain

import com.craftmind.app.domain.validation.ReferenceUrlValidator
import com.craftmind.app.domain.validation.UrlValidationError
import com.craftmind.app.domain.validation.UrlValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceUrlValidatorTest {
    @Test
    fun trimsInputAndAddsHttpsWhenSchemeIsOmitted() {
        val result = ReferenceUrlValidator.validate("  www.example.com/plan  ")

        assertEquals(
            UrlValidationResult.Valid("https://www.example.com/plan"),
            result,
        )
    }

    @Test
    fun acceptsBareLocalhostWithAnExplicitPort() {
        assertEquals(
            UrlValidationResult.Valid("https://localhost:8080/reference"),
            ReferenceUrlValidator.validate("localhost:8080/reference"),
        )
    }

    @Test
    fun acceptsHttpAndHttpsUrlsAndNormalizesDotSegments() {
        val https = ReferenceUrlValidator.validate("https://example.com/a/../b?q=1")
        val http = ReferenceUrlValidator.validate("http://example.com/reference")

        assertEquals(
            UrlValidationResult.Valid("https://example.com/b?q=1"),
            https,
        )
        assertEquals(
            UrlValidationResult.Valid("http://example.com/reference"),
            http,
        )
    }

    @Test
    fun rejectsUnsupportedSchemesAndEmbeddedCredentials() {
        assertEquals(
            UrlValidationResult.Invalid(UrlValidationError.UNSUPPORTED_SCHEME),
            ReferenceUrlValidator.validate("javascript:alert(1)"),
        )
        assertEquals(
            UrlValidationResult.Invalid(UrlValidationError.UNSUPPORTED_SCHEME),
            ReferenceUrlValidator.validate("ftp://example.com/file"),
        )
        assertEquals(
            UrlValidationResult.Invalid(UrlValidationError.CREDENTIALS_NOT_ALLOWED),
            ReferenceUrlValidator.validate("https://user:pass@example.com/private"),
        )
    }

    @Test
    fun rejectsEmptyMalformedAndOverlongValues() {
        assertEquals(
            UrlValidationResult.Invalid(UrlValidationError.EMPTY),
            ReferenceUrlValidator.validate("  "),
        )
        assertTrue(ReferenceUrlValidator.validate("https:// bad host").let {
            it is UrlValidationResult.Invalid && it.reason == UrlValidationError.INVALID_FORMAT
        })
        assertEquals(
            UrlValidationResult.Invalid(UrlValidationError.TOO_LONG),
            ReferenceUrlValidator.validate("a".repeat(ReferenceUrlValidator.MAX_URL_LENGTH + 1)),
        )
    }
}
