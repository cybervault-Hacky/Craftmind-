package com.craftmind.app.domain.media

import com.craftmind.app.domain.model.ImageReference
import java.net.URI
import java.util.Locale

const val MAX_IMAGE_SIZE_BYTES: Long = 15L * 1024L * 1024L

sealed interface ImageValidationResult {
    data class Accepted(val image: ImageReference) : ImageValidationResult
    data class Rejected(val reason: ImageValidationError) : ImageValidationResult
}

enum class ImageValidationError {
    UNSUPPORTED_FORMAT,
    FILE_TOO_LARGE,
    SIZE_UNAVAILABLE,
    UNREADABLE,
}

data class ImageThumbnail(val encodedBytes: ByteArray, val mimeType: String)

interface ImageReferenceRepository {
    /** Reads only metadata through the picker-provided grant; implementations must not persist it. */
    suspend fun inspect(uri: String): ImageValidationResult

    /** Produces a small, downsampled preview; it must never decode the source at full resolution. */
    suspend fun loadThumbnail(uri: String, maxDimensionPixels: Int): ImageThumbnail?
}

object ImageReferenceValidator {
    private val supportedMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun validate(
        uri: String,
        mimeType: String?,
        displayName: String?,
        sizeBytes: Long?,
    ): ImageValidationResult {
        if (!isPickerContentUri(uri)) return ImageValidationResult.Rejected(ImageValidationError.UNREADABLE)
        if (sizeBytes == null || sizeBytes <= 0L) {
            return ImageValidationResult.Rejected(ImageValidationError.SIZE_UNAVAILABLE)
        }
        if (sizeBytes >= MAX_IMAGE_SIZE_BYTES) {
            return ImageValidationResult.Rejected(ImageValidationError.FILE_TOO_LARGE)
        }

        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val extensionMime = mimeTypeFromExtension(extension)
        val providerOmittedSpecificMime = normalizedMime.isNullOrBlank() ||
            normalizedMime == "application/octet-stream"
        val isSupported = if (providerOmittedSpecificMime) {
            extension in supportedExtensions
        } else {
            normalizedMime != null && normalizedMime in supportedMimeTypes &&
                (extension.isEmpty() || extensionMime == normalizedMime)
        }
        if (!isSupported) {
            return ImageValidationResult.Rejected(ImageValidationError.UNSUPPORTED_FORMAT)
        }

        val safeName = displayName
            ?.takeIf(String::isNotBlank)
            ?.substringAfterLast('/')
            ?.take(120)
            ?: "Reference image"
        return ImageValidationResult.Accepted(
            ImageReference(
                uri = uri,
                mimeType = normalizedMime?.takeIf { it in supportedMimeTypes } ?: extensionMime,
                displayName = safeName,
                sizeBytes = sizeBytes,
            ),
        )
    }

    private fun isPickerContentUri(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("content", ignoreCase = true) &&
            !uri.rawAuthority.isNullOrBlank() &&
            uri.rawUserInfo == null
    } catch (_: Exception) {
        false
    }

    private fun mimeTypeFromExtension(extension: String?): String = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
