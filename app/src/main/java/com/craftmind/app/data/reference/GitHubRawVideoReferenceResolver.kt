package com.craftmind.app.data.reference

import com.craftmind.app.domain.reference.PublicVideoReferenceException
import com.craftmind.app.domain.reference.PublicVideoReferenceFailure
import com.craftmind.app.domain.reference.PublicVideoReferenceLimits
import com.craftmind.app.domain.reference.PublicVideoReferenceResolver
import com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy
import com.craftmind.app.domain.reference.PublicVideoUrlValidation
import com.craftmind.app.domain.reference.ResolvedPublicVideoReference
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/** Only direct raw GitHub MP4/WebM files are probed; HTML pages and platform video URLs are not opened. */
class GitHubRawVideoReferenceResolver(
    client: OkHttpClient = PublicVideoHttpClient.create(),
) : PublicVideoReferenceResolver {
    private val client = PublicVideoHttpClient.restrictedClient(client)

    override suspend fun resolve(url: String): ResolvedPublicVideoReference {
        val validated = when (val result = PublicVideoReferenceUrlPolicy.validate(url)) {
            is PublicVideoUrlValidation.Valid -> result.value
            is PublicVideoUrlValidation.Invalid -> throw PublicVideoReferenceException(result.failure)
        }
        val request = Request.Builder()
            .url(validated.canonicalUrl)
            .header("Range", "bytes=0-${PublicVideoReferenceLimits.MAX_PROBE_BYTES - 1}")
            .header("Accept-Encoding", "identity")
            .header("Accept", "video/mp4, video/webm, application/octet-stream")
            .get()
            .build()

        try {
            val response = client.newCall(request).await()
            return response.use { httpResponse -> validateProbe(httpResponse, validated) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PublicVideoReferenceException) {
            throw error
        } catch (error: IOException) {
            if (error.causesInclude<UnsafePublicVideoAddressException>()) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSAFE_DESTINATION)
            }
            if (error is SocketTimeoutException || error is java.io.InterruptedIOException) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.TIMEOUT)
            }
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
        } catch (_: Exception) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
        }
    }

    private fun validateProbe(
        response: Response,
        expected: com.craftmind.app.domain.reference.ValidatedPublicVideoUrl,
    ): ResolvedPublicVideoReference {
        if (response.priorResponse != null || response.request.url.scheme != "https" ||
            response.request.url.host.lowercase(Locale.ROOT) != PublicVideoReferenceUrlPolicy.ALLOWED_HOST ||
            response.request.url.toString() != expected.canonicalUrl
        ) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.REDIRECT_NOT_ALLOWED)
        }
        when {
            response.code in 300..399 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.REDIRECT_NOT_ALLOWED)
            response.code == 401 || response.code == 403 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.ACCESS_RESTRICTED)
            response.code == 200 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
            response.code == 416 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
            response.code != 206 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
        }

        val contentRange = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
            ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        val start = contentRange.groupValues[1].toLongOrNull()
            ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        val end = contentRange.groupValues[2].toLongOrNull()
            ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        val total = contentRange.groupValues[3].toLongOrNull()
            ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        if (start != 0L || end < start || end >= PublicVideoReferenceLimits.MAX_PROBE_BYTES || total <= end) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        }
        if (total > PublicVideoReferenceLimits.MAX_VIDEO_FILE_BYTES) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.CONTENT_TOO_LARGE)
        }
        if (total < MIN_VIDEO_BYTES) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSUPPORTED_MEDIA)
        }
        val expectedRangeLength = (end - start + 1).toInt()
        val body = response.body ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
        if (body.contentLength() > expectedRangeLength ||
            !response.header("Content-Encoding").isNullOrBlank() &&
            !response.header("Content-Encoding").equals("identity", ignoreCase = true)
        ) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSUPPORTED_MEDIA)
        }
        val declaredType = body.contentType()?.toString()?.substringBefore(';')?.lowercase(Locale.ROOT)
        if (declaredType != null && declaredType !in setOf(expected.mediaType, OCTET_STREAM)) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSUPPORTED_MEDIA)
        }
        val bytes = body.readExactly(expectedRangeLength)
        try {
            if (!matchesContainer(bytes, expected.mediaType)) {
                throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSUPPORTED_MEDIA)
            }
        } finally {
            bytes.fill(0)
        }
        return ResolvedPublicVideoReference(
            canonicalUrl = expected.canonicalUrl,
            sourceDomain = expected.sourceDomain,
            mediaType = expected.mediaType,
            contentLengthBytes = total,
        )
    }

    private fun matchesContainer(bytes: ByteArray, mediaType: String): Boolean = when (mediaType) {
        "video/mp4" -> bytes.size >= 12 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        "video/webm" -> bytes.size >= 4 && bytes[0] == 0x1a.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte()
        else -> false
    }

    private fun ResponseBody.readExactly(expectedBytes: Int): ByteArray {
        if (expectedBytes !in 1..PublicVideoReferenceLimits.MAX_PROBE_BYTES) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSUPPORTED_MEDIA)
        }
        val result = ByteArray(expectedBytes)
        try {
            byteStream().use { input ->
                var offset = 0
                while (offset < expectedBytes) {
                    val count = input.read(result, offset, expectedBytes - offset)
                    if (count < 0) throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
                    offset += count
                }
                if (input.read() != -1) throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
            }
            return result
        } catch (error: Exception) {
            result.fill(0)
            throw error
        }
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        const val MIN_VIDEO_BYTES = 128L
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response) { _, abandonedResponse, _ -> abandonedResponse.close() }
                } else {
                    response.close()
                }
            }
        },
    )
}

private inline fun <reified T : Throwable> Throwable.causesInclude(): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is T) return true
        current = current.cause
    }
    return false
}
