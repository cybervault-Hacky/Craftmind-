package com.craftmind.app.domain.reference

import com.craftmind.app.domain.ai.AiImageInput
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.Locale

/** Stable, non-sensitive failures for the deliberately narrow public-video pipeline. */
enum class PublicVideoReferenceFailure {
    UNSAFE_URL,
    UNSAFE_DESTINATION,
    UNSUPPORTED_SOURCE,
    SOURCE_UNAVAILABLE,
    ACCESS_RESTRICTED,
    REDIRECT_NOT_ALLOWED,
    BYTE_RANGES_REQUIRED,
    UNSUPPORTED_MEDIA,
    CONTENT_TOO_LARGE,
    VIDEO_TOO_LONG,
    FRAME_EXTRACTION_FAILED,
    NO_DISTINCT_FRAMES,
    TRANSFER_LIMIT_EXCEEDED,
    TIMEOUT,
}

/** Messages contain only an enum name; URLs, response bodies, DNS results, and headers stay private. */
class PublicVideoReferenceException(
    val failure: PublicVideoReferenceFailure,
) : IOException(failure.name)

data class ValidatedPublicVideoUrl internal constructor(
    val canonicalUrl: String,
    val sourceDomain: String,
    val mediaType: String,
)

sealed interface PublicVideoUrlValidation {
    data class Valid(val value: ValidatedPublicVideoUrl) : PublicVideoUrlValidation
    data class Invalid(val failure: PublicVideoReferenceFailure) : PublicVideoUrlValidation
}

/** URL policy is pure and performs no DNS lookup or network access. */
object PublicVideoReferenceUrlPolicy {
    const val ALLOWED_HOST = "raw.githubusercontent.com"
    private val safePath = Regex("/[A-Za-z0-9._~/-]+")

    fun validate(value: String): PublicVideoUrlValidation {
        if (value.isBlank() || value.length > MAX_URL_LENGTH) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSAFE_URL)
        }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSAFE_URL)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme != "https" || host.isNullOrBlank() || uri.rawAuthority.isNullOrBlank() || uri.isOpaque) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSAFE_URL)
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in -1..65535 || (uri.port != -1 && uri.port != 443)
        ) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSAFE_URL)
        }
        val authority = uri.rawAuthority.lowercase(Locale.ROOT)
        if (authority != host && authority != "$host:443") {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSAFE_URL)
        }
        if (isIpLiteral(host) || isLocalHost(host)) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSAFE_URL)
        }
        if (host != ALLOWED_HOST) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSUPPORTED_SOURCE)
        }

        val path = uri.rawPath.orEmpty()
        if (!safePath.matches(path) || path.contains("//") || path.endsWith('/')) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSUPPORTED_SOURCE)
        }
        val pathSegments = path.removePrefix("/").split('/')
        if (pathSegments.size < 4 || pathSegments.any { it.isEmpty() || it == "." || it == ".." }) {
            return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSUPPORTED_SOURCE)
        }
        val mediaType = when (path.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> return PublicVideoUrlValidation.Invalid(PublicVideoReferenceFailure.UNSUPPORTED_SOURCE)
        }
        return PublicVideoUrlValidation.Valid(
            ValidatedPublicVideoUrl(
                canonicalUrl = "https://$ALLOWED_HOST$path",
                sourceDomain = ALLOWED_HOST,
                mediaType = mediaType,
            ),
        )
    }

    private fun isIpLiteral(host: String): Boolean =
        ':' in host || host.matches(Regex("[0-9.]+")) || host.startsWith("0x", ignoreCase = true)

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home.arpa")

    private const val MAX_URL_LENGTH = 2_048
}

/** The ephemeral result of one successful, bounded HTTP range probe. */
data class ResolvedPublicVideoReference(
    val canonicalUrl: String,
    val sourceDomain: String,
    val mediaType: String,
    val contentLengthBytes: Long,
) {
    init {
        require(canonicalUrl.isNotBlank())
        require(sourceDomain == PublicVideoReferenceUrlPolicy.ALLOWED_HOST)
        require(mediaType in SUPPORTED_VIDEO_MEDIA_TYPES)
        require(contentLengthBytes in 1..PublicVideoReferenceLimits.MAX_VIDEO_FILE_BYTES)
    }
}

data class PublicVideoFrame(
    /** Requested sampling point; the decoder may return the nearest indexed sync frame. */
    val timestampMillis: Long,
    val image: AiImageInput,
)

/** Holds only the bounded in-memory frame set. Closing it zeroes every provider image payload. */
class ExtractedPublicVideoFrames(
    val durationMillis: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    frames: List<PublicVideoFrame>,
) : Closeable {
    val frames: List<PublicVideoFrame> = frames.toList()

    init {
        require(durationMillis in PublicVideoReferenceLimits.MIN_VIDEO_DURATION_MS..PublicVideoReferenceLimits.MAX_VIDEO_DURATION_MS)
        require(videoWidth in 1..PublicVideoReferenceLimits.MAX_VIDEO_WIDTH)
        require(videoHeight in 1..PublicVideoReferenceLimits.MAX_VIDEO_HEIGHT)
        require(videoWidth.toLong() * videoHeight <= PublicVideoReferenceLimits.MAX_VIDEO_PIXELS)
        require(this.frames.size in PublicVideoReferenceLimits.MIN_FRAME_COUNT..PublicVideoReferenceLimits.MAX_FRAME_COUNT)
        require(this.frames.map(PublicVideoFrame::timestampMillis).zipWithNext().all { (a, b) -> a < b })
        require(this.frames.all {
            it.timestampMillis in 0 until durationMillis &&
                it.image.mediaType in AiImageInput.SUPPORTED_MEDIA_TYPES &&
                it.image.width <= PublicVideoReferenceLimits.MAX_FRAME_DIMENSION &&
                it.image.height <= PublicVideoReferenceLimits.MAX_FRAME_DIMENSION &&
                it.image.width.toLong() * it.image.height <= PublicVideoReferenceLimits.MAX_FRAME_PIXELS &&
                it.image.byteCount <= PublicVideoReferenceLimits.MAX_FRAME_IMAGE_BYTES
        })
        require(this.frames.sumOf { it.image.byteCount.toLong() } <= PublicVideoReferenceLimits.MAX_TOTAL_FRAME_IMAGE_BYTES)
    }

    override fun close() = this.frames.forEach { it.image.close() }
}

/** Predictable target timestamps; the final sample is deliberately near the completed end state. */
object PublicVideoFrameSamplingPolicy {
    private val samplePermille = intArrayOf(100, 350, 600, 800, 950)

    fun timestamps(durationMillis: Long): List<Long> {
        if (durationMillis !in PublicVideoReferenceLimits.MIN_VIDEO_DURATION_MS..PublicVideoReferenceLimits.MAX_VIDEO_DURATION_MS) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.VIDEO_TOO_LONG)
        }
        return samplePermille.map { fraction -> durationMillis * fraction / 1_000L }.distinct()
    }
}

interface PublicVideoReferenceResolver {
    /** Accepts only explicitly allowlisted sources and verifies public access with one tiny Range GET. */
    suspend fun resolve(url: String): ResolvedPublicVideoReference
}

interface PublicVideoFrameExtractor {
    /** Retrieves a bounded set of distinct frames into RAM only; implementations must clean up on failure/cancel. */
    suspend fun extract(reference: ResolvedPublicVideoReference): ExtractedPublicVideoFrames
}

object PublicVideoReferenceLimits {
    const val MIN_VIDEO_DURATION_MS = 2_000L
    const val MAX_VIDEO_DURATION_MS = 180_000L
    const val MAX_VIDEO_FILE_BYTES = 128L * 1024L * 1024L
    const val MAX_FRAME_COUNT = 5
    const val MIN_FRAME_COUNT = 2
    const val MAX_VIDEO_WIDTH = 1_920
    const val MAX_VIDEO_HEIGHT = 1_920
    const val MAX_VIDEO_PIXELS = 2_073_600L
    const val MAX_FRAME_DIMENSION = 1_024
    const val MAX_FRAME_PIXELS = 1_048_576L
    const val MAX_FRAME_IMAGE_BYTES = 512 * 1024
    const val MAX_TOTAL_FRAME_IMAGE_BYTES = MAX_FRAME_COUNT * MAX_FRAME_IMAGE_BYTES
    const val MAX_PROBE_BYTES = 4 * 1024
    const val RANGE_BLOCK_BYTES = 64 * 1024
    const val MAX_RANGE_REQUESTS = 128
    const val MAX_TOTAL_TRANSFER_BYTES = 12 * 1024 * 1024L
    const val MAX_EXTRACTION_TIME_MILLIS = 30_000L
    const val MAX_URL_LENGTH = 2_048
}

val SUPPORTED_VIDEO_MEDIA_TYPES: Set<String> = setOf("video/mp4", "video/webm")
