package com.craftmind.app.domain.ai

import java.io.Closeable
import java.util.Locale

/** In-memory provider payload. The owner must close it as soon as the provider call completes. */
class AiImageInput(
    mediaType: String,
    val width: Int,
    val height: Int,
    bytes: ByteArray,
) : Closeable {
    init {
        require(mediaType.lowercase(Locale.ROOT) in SUPPORTED_MEDIA_TYPES)
        require(width in 1..MAX_PROVIDER_IMAGE_DIMENSION && height in 1..MAX_PROVIDER_IMAGE_DIMENSION)
        require(width.toLong() * height.toLong() <= MAX_PROVIDER_IMAGE_PIXELS)
        require(bytes.isNotEmpty() && bytes.size <= MAX_PROVIDER_IMAGE_BYTES)
    }

    val mediaType: String = mediaType.lowercase(Locale.ROOT)
    private val payload = bytes.copyOf()
    @Volatile private var closed = false

    val byteCount: Int get() = payload.size

    @Synchronized
    internal fun <T> useBytes(block: (ByteArray) -> T): T {
        check(!closed) { "Image payload is no longer available" }
        return block(payload)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            payload.fill(0)
            closed = true
        }
    }

    override fun toString(): String =
        "AiImageInput(mediaType=$mediaType, width=$width, height=$height, byteCount=${payload.size}, closed=$closed)"

    companion object {
        val SUPPORTED_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        const val MAX_PROVIDER_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_PROVIDER_IMAGE_DIMENSION = 4_096
        const val MAX_PROVIDER_IMAGE_PIXELS = 8_000_000L
    }
}
