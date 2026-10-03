package com.craftmind.app.domain.validation

import java.net.URI
import java.util.Locale

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult
    data class Invalid(val reason: UrlValidationError) : UrlValidationResult
}

enum class UrlValidationError {
    EMPTY,
    TOO_LONG,
    INVALID_FORMAT,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    CREDENTIALS_NOT_ALLOWED,
}

/** Client-side syntax checks only. Any future network service must validate URLs again server-side. */
object ReferenceUrlValidator {
    const val MAX_URL_LENGTH = 2_048
    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val hasSchemeDelimiter = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun validate(input: String): UrlValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return UrlValidationResult.Invalid(UrlValidationError.EMPTY)
        if (trimmed.length > MAX_URL_LENGTH) {
            return UrlValidationResult.Invalid(UrlValidationError.TOO_LONG)
        }
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlValidationResult.Invalid(UrlValidationError.INVALID_FORMAT)
        }

        val candidate = when {
            hasSchemeDelimiter.containsMatchIn(trimmed) -> trimmed
            looksLikeMalformedHttpScheme(trimmed) -> {
                return UrlValidationResult.Invalid(UrlValidationError.INVALID_FORMAT)
            }
            hasNonHttpScheme(trimmed) -> {
                return UrlValidationResult.Invalid(UrlValidationError.UNSUPPORTED_SCHEME)
            }
            else -> "https://$trimmed"
        }

        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return UrlValidationResult.Invalid(UrlValidationError.INVALID_FORMAT)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.Invalid(UrlValidationError.UNSUPPORTED_SCHEME)
        }
        if (!uri.isAbsolute || uri.rawAuthority.isNullOrBlank()) {
            return UrlValidationResult.Invalid(UrlValidationError.MISSING_HOST)
        }
        if (uri.rawUserInfo != null) {
            return UrlValidationResult.Invalid(UrlValidationError.CREDENTIALS_NOT_ALLOWED)
        }
        if (uri.host.isNullOrBlank()) {
            return UrlValidationResult.Invalid(UrlValidationError.MISSING_HOST)
        }
        if (uri.port !in -1..65_535) {
            return UrlValidationResult.Invalid(UrlValidationError.INVALID_FORMAT)
        }

        val normalized = try {
            uri.normalize().toASCIIString()
        } catch (_: Exception) {
            return UrlValidationResult.Invalid(UrlValidationError.INVALID_FORMAT)
        }
        return UrlValidationResult.Valid(normalized)
    }

    private fun looksLikeMalformedHttpScheme(value: String): Boolean =
        value.startsWith("http:", ignoreCase = true) || value.startsWith("https:", ignoreCase = true)

    private fun hasNonHttpScheme(value: String): Boolean {
        val match = schemePattern.find(value) ?: return false
        val prefix = match.groupValues[1]
        val suffix = value.substring(match.range.last + 1)
        // A numeric suffix is usually a port, including on hosts such as "localhost".
        if (suffix.firstOrNull()?.isDigit() == true) return false
        // A dot before a colon is usually a host plus an explicit port, not a URI scheme.
        return !prefix.contains('.') && !prefix.equals("www", ignoreCase = true)
    }
}
