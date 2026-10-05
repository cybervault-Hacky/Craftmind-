package com.craftmind.app.domain.buildplan

import com.craftmind.app.domain.reference.PublicVideoReferenceFailure
import com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy
import com.craftmind.app.domain.reference.PublicVideoUrlValidation
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
    URL_UNSAFE_HOST,
    UNSUPPORTED_PUBLIC_VIDEO_SOURCE,
    URL_QUERY_OR_FRAGMENT_NOT_ALLOWED,
    URL_PORT_NOT_ALLOWED,
    MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED,
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
        if (normalized.length > MAX_URL_LENGTH) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_TOO_LONG)
        }

        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        if (scheme != "https") {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED)
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank() || uri.isOpaque) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }
        if (uri.rawUserInfo != null) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_CREDENTIALS_NOT_ALLOWED)
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_QUERY_OR_FRAGMENT_NOT_ALLOWED)
        }
        if (uri.port !in -1..65535) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.INVALID_URL)
        }
        if (uri.port != -1 && uri.port != 443) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_PORT_NOT_ALLOWED)
        }
        val authority = uri.rawAuthority.lowercase(Locale.ROOT)
        if (authority != host && authority != "$host:443") {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_PORT_NOT_ALLOWED)
        }
        if (host.contains(':') || host.matches(Regex("[0-9.]+")) ||
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa")
        ) {
            return UrlValidationResult.Invalid(BuildRequestValidationError.URL_UNSAFE_HOST)
        }

        return when (val reference = PublicVideoReferenceUrlPolicy.validate(normalized)) {
            is PublicVideoUrlValidation.Valid -> UrlValidationResult.Valid(reference.value.canonicalUrl)
            is PublicVideoUrlValidation.Invalid -> when (reference.failure) {
                PublicVideoReferenceFailure.UNSUPPORTED_SOURCE ->
                    UrlValidationResult.Invalid(BuildRequestValidationError.UNSUPPORTED_PUBLIC_VIDEO_SOURCE)
                else -> UrlValidationResult.Invalid(BuildRequestValidationError.URL_UNSAFE_HOST)
            }
        }
    }

    fun create(draft: BuildRequestDraft): BuildRequestValidationResult {
        if (draft.imageReference != null && draft.urlReference != null) {
            return BuildRequestValidationResult.Invalid(BuildRequestValidationError.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED)
        }
        if (draft.prompt.isBlank() && draft.imageReference == null && draft.urlReference == null) {
            return BuildRequestValidationResult.Invalid(BuildRequestValidationError.EMPTY_PROMPT)
        }
        if (draft.prompt.length > MAX_PROMPT_LENGTH) {
            return BuildRequestValidationResult.Invalid(BuildRequestValidationError.PROMPT_TOO_LONG)
        }

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
        const val MAX_URL_LENGTH: Int = 2_048
        const val MAX_IMAGE_SIZE_BYTES: Long = 12L * 1024L * 1024L
        val SUPPORTED_IMAGE_MEDIA_TYPES: Set<String> = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )
    }
}
