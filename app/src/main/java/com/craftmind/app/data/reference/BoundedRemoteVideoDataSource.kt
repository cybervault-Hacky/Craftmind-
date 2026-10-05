package com.craftmind.app.data.reference

import android.media.MediaDataSource
import com.craftmind.app.domain.reference.PublicVideoReferenceException
import com.craftmind.app.domain.reference.PublicVideoReferenceFailure
import com.craftmind.app.domain.reference.PublicVideoReferenceLimits
import com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy
import com.craftmind.app.domain.reference.PublicVideoUrlValidation
import com.craftmind.app.domain.reference.ResolvedPublicVideoReference
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * Android random-access source backed only by bounded HTTPS byte ranges. It keeps a tiny RAM LRU;
 * it never downloads or writes the complete video.
 */
internal class BoundedRemoteVideoDataSource(
    private val reference: ResolvedPublicVideoReference,
    client: OkHttpClient,
) : MediaDataSource() {
    private val client = PublicVideoHttpClient.restrictedClient(client)
    private val lock = Any()
    private val cache = LinkedHashMap<Long, ByteArray>(CACHE_BLOCKS, 0.75f, true)
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)
    private val failure = AtomicReference<PublicVideoReferenceException?>(null)
    private val requestCount = AtomicInteger(0)
    private val reservedTransferBytes = AtomicLong(0L)
    private val deadlineNanos = System.nanoTime() +
        TimeUnit.MILLISECONDS.toNanos(PublicVideoReferenceLimits.MAX_EXTRACTION_TIME_MILLIS)

    init {
        val validated = PublicVideoReferenceUrlPolicy.validate(reference.canonicalUrl)
        val validValue = (validated as? PublicVideoUrlValidation.Valid)?.value
        if (validValue == null || validValue.canonicalUrl != reference.canonicalUrl ||
            validValue.sourceDomain != reference.sourceDomain || validValue.mediaType != reference.mediaType ||
            reference.contentLengthBytes !in 1..PublicVideoReferenceLimits.MAX_VIDEO_FILE_BYTES
        ) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSAFE_URL)
        }
    }

    override fun getSize(): Long = reference.contentLengthBytes

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IOException("Invalid media read")
        }
        if (size == 0) return 0
        if (position >= reference.contentLengthBytes) return -1
        if (cancelled.get() || closed.get()) throw InterruptedIOException("Video read cancelled")

        val bytesToRead = minOf(size.toLong(), reference.contentLengthBytes - position).toInt()
        synchronized(lock) {
            var copied = 0
            while (copied < bytesToRead) {
                if (cancelled.get() || closed.get()) throw InterruptedIOException("Video read cancelled")
                val absolutePosition = position + copied
                val blockStart = (absolutePosition / PublicVideoReferenceLimits.RANGE_BLOCK_BYTES) *
                    PublicVideoReferenceLimits.RANGE_BLOCK_BYTES
                val block = cachedBlock(blockStart)
                val inBlockOffset = (absolutePosition - blockStart).toInt()
                if (inBlockOffset !in block.indices) return if (copied == 0) -1 else copied
                val copyLength = minOf(bytesToRead - copied, block.size - inBlockOffset)
                block.copyInto(buffer, offset + copied, inBlockOffset, inBlockOffset + copyLength)
                copied += copyLength
            }
            return copied
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeCall.getAndSet(null)?.cancel()
    }

    fun failWith(error: PublicVideoReferenceException) {
        failure.compareAndSet(null, error)
        cancel()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            cancel()
            synchronized(lock) {
                cache.values.forEach { it.fill(0) }
                cache.clear()
            }
        }
    }

    fun failureOrNull(): PublicVideoReferenceException? = failure.get()
    fun isCancelled(): Boolean = cancelled.get() || closed.get()

    private fun cachedBlock(start: Long): ByteArray {
        cache[start]?.let { return it }
        ensureActive()
        if (requestCount.incrementAndGet() > PublicVideoReferenceLimits.MAX_RANGE_REQUESTS) {
            throw recordFailure(PublicVideoReferenceFailure.TRANSFER_LIMIT_EXCEEDED)
        }
        val byteCount = minOf(
            PublicVideoReferenceLimits.RANGE_BLOCK_BYTES.toLong(),
            reference.contentLengthBytes - start,
        ).toInt()
        val reservedBytes = byteCount.toLong() + 1L
        val totalReserved = reservedTransferBytes.addAndGet(reservedBytes)
        if (totalReserved > PublicVideoReferenceLimits.MAX_TOTAL_TRANSFER_BYTES) {
            throw recordFailure(PublicVideoReferenceFailure.TRANSFER_LIMIT_EXCEEDED)
        }
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) throw recordFailure(PublicVideoReferenceFailure.TIMEOUT)

        val request = Request.Builder()
            .url(reference.canonicalUrl)
            .header("Range", "bytes=$start-${start + byteCount - 1}")
            .header("Accept-Encoding", "identity")
            .header("Accept", "${reference.mediaType}, application/octet-stream")
            .get()
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(remainingNanos, TimeUnit.NANOSECONDS)
        if (cancelled.get() || closed.get()) throw InterruptedIOException("Video read cancelled")
        activeCall.set(call)
        if (cancelled.get() || closed.get()) call.cancel()

        var receivedBytes: ByteArray? = null
        var cached = false
        try {
            val response = call.execute()
            val bytes = response.use { httpResponse -> readRange(httpResponse, start, byteCount) }
            receivedBytes = bytes
            ensureActive()
            cache[start] = bytes
            cached = true
            while (cache.size > CACHE_BLOCKS) {
                val eldest = cache.entries.iterator().next()
                eldest.value.fill(0)
                cache.remove(eldest.key)
            }
            return bytes
        } catch (error: PublicVideoReferenceException) {
            if (!cached) receivedBytes?.fill(0)
            val winningFailure = failure.get()
                ?: if (isCancelled()) failure.get() ?: error else recordFailure(error.failure)
            throw winningFailure
        } catch (error: IOException) {
            if (!cached) receivedBytes?.fill(0)
            if (isCancelled()) throw InterruptedIOException("Video read cancelled")
            val code = when {
                error.causesInclude<UnsafePublicVideoAddressException>() -> PublicVideoReferenceFailure.UNSAFE_DESTINATION
                error is SocketTimeoutException || error is InterruptedIOException -> PublicVideoReferenceFailure.TIMEOUT
                else -> PublicVideoReferenceFailure.SOURCE_UNAVAILABLE
            }
            throw recordFailure(code)
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun readRange(response: Response, start: Long, byteCount: Int): ByteArray {
        if (response.priorResponse != null || response.request.url.scheme != "https" ||
            response.request.url.host.lowercase(Locale.ROOT) != PublicVideoReferenceUrlPolicy.ALLOWED_HOST ||
            response.request.url.toString() != reference.canonicalUrl
        ) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.REDIRECT_NOT_ALLOWED)
        }
        when {
            response.code in 300..399 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.REDIRECT_NOT_ALLOWED)
            response.code == 401 || response.code == 403 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.ACCESS_RESTRICTED)
            response.code == 200 || response.code == 416 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
            response.code != 206 -> throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
        }
        val range = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
            ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        val actualStart = range.groupValues[1].toLongOrNull()
        val actualEnd = range.groupValues[2].toLongOrNull()
        val total = range.groupValues[3].toLongOrNull()
        val expectedEnd = start + byteCount - 1
        if (actualStart != start || actualEnd != expectedEnd || total != reference.contentLengthBytes) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        }
        val body = response.body ?: throw PublicVideoReferenceException(PublicVideoReferenceFailure.SOURCE_UNAVAILABLE)
        if (body.contentLength() > byteCount ||
            !response.header("Content-Encoding").isNullOrBlank() &&
            !response.header("Content-Encoding").equals("identity", ignoreCase = true)
        ) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.BYTE_RANGES_REQUIRED)
        }
        val declaredType = body.contentType()?.toString()?.substringBefore(';')?.lowercase(Locale.ROOT)
        if (declaredType != null && declaredType !in setOf(reference.mediaType, "application/octet-stream")) {
            throw PublicVideoReferenceException(PublicVideoReferenceFailure.UNSUPPORTED_MEDIA)
        }
        return body.readExactly(byteCount)
    }

    private fun ensureActive() {
        if (cancelled.get() || closed.get()) throw InterruptedIOException("Video read cancelled")
        if (System.nanoTime() >= deadlineNanos) throw recordFailure(PublicVideoReferenceFailure.TIMEOUT)
    }

    private fun recordFailure(code: PublicVideoReferenceFailure): PublicVideoReferenceException {
        val error = PublicVideoReferenceException(code)
        return if (failure.compareAndSet(null, error)) error else failure.get() ?: error
    }

    private fun ResponseBody.readExactly(expectedBytes: Int): ByteArray {
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
        const val CACHE_BLOCKS = 4
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
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
