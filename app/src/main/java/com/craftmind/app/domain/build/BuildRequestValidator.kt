package com.craftmind.app.domain.build

import java.net.URI
import java.util.Locale
import java.util.UUID

/** Validation failures are stable domain values; presentation maps them to human-readable copy. */
enum class BuildRequestValidationError {
    EMPTY_PROMPT,
    PROMPT_TOO_LONG,
    INVALID_URL,
    URL_SCHEME_NOT_ALLOWED,
    URL_CREDENTIALS_NOT_ALLOWED,
    URL_TOO_LONG,
    UNSUPPORTED_IMAGE_TYPE,
    IMAGE_TOO_LARGE,
    INVALID_IMAGE_REFERENCE,
}

sealed interface BuildRequestValidationResult {
    data class Valid(val request: BuildRequest) : BuildRequestValidationResult
    data class Invalid(val error: BuildRequestValidationError) : BuildRequestValidationResult
}

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult
    data class Invalid(val error: BuildRequestValidationError) : UrlValidationResult
}

/** Central domain validation shared by the composer and future provider boundary. */
class BuildRequestValidator(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) {
    fun validatePrompt(prompt: String): BuildRequestValidationError? {
        if (prompt.isBlank()) return BuildRequestValidationError.EMPTY_PROMPT
        if (prompt.length > MAX_PROMPT_LENGTH) return BuildRequestValidationError.PROMPT_TOO_LONG
        return null
    }

    fun validateImage(reference: BuildInput.ImageReference): BuildRequestValidationError? {
        if (reference.contentUri.isBlank()) return BuildRequestValidationError.INVALID_IMAGE_REFERENCE
        val mediaType = reference.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mediaType !in SUPPORTED_IMAGE_MEDIA_TYPES) {
            return BuildRequestValidationError.UNSUPPORTED_IMAGE_TYPE
        }
        if (reference.sizeBytes != null && reference.sizeBytes < 0L) {
            return BuildRequestValidationError.INVALID_IMAGE_REFERENCE
        }
        if (reference.sizeBytes != null && reference.sizeBytes > MAX_IMAGE_SIZE_BYTES) {
            return BuildRequestValidationError.IMAGE_TOO_LARGE
        }
        return null
    }

    /** Parses locally only. It never opens a socket, resolves a host, or follows redirects. */
    fun validateUrl(value: String): UrlValidationResult {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }

        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED)
        }
        if (uri.host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank()) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }
        if (uri.rawUserInfo != null) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_CREDENTIALS_NOT_ALLOWED)
        }
        if (uri.port !in -1..65535) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }

        return UrlValidationResult.Valid(normalized)
    }

    fun create(draft: BuildRequestDraft): BuildRequestValidationResult {
        validatePrompt(draft.prompt)?.let { return BuildRequestValidationResult.Invalid(it) }

        draft.imageReference?.let { image ->
            validateImage(image)?.let { return BuildRequestValidationResult.Invalid(it) }
        }

        val normalizedUrl = draft.urlReference?.let { reference ->
            when (val result = validateUrl(reference.url)) {
                is UrlValidationResult.Valid -> BuildInput.UrlReference(result.normalizedUrl)
                is UrlValidationResult.Invalid -> return BuildRequestValidationResult.Invalid(result.error)
            }
        }

        return BuildRequestValidationResult.Valid(
            BuildRequest.create(
                prompt = draft.prompt.trim(),
                imageReference = draft.imageReference?.copy(
                    mediaType = draft.imageReference.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT),
                ),
                urlReference = normalizedUrl,
                nowEpochMillis = nowEpochMillis,
                requestId = requestId,
            ),
        )
    }

    companion object {
        const val MAX_PROMPT_LENGTH: Int = 800
        const val MAX_IMAGE_SIZE_BYTES: Long = 12L * 1024L * 1024L
        val SUPPORTED_IMAGE_MEDIA_TYPES: Set<String> = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )
    }
}
