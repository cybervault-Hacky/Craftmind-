package com.craftmind.app.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiImageInput
import com.craftmind.app.domain.ai.AiImageInputPreparer
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads one temporary Android content URI, validates/decode-scales it locally, and returns RAM-only bytes. */
class ContentResolverImageInputPreparer(context: Context) : AiImageInputPreparer {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun prepare(reference: BuildInput.ImageReference): AiImageInput {
        var preparedImage: AiImageInput? = null
        return try {
            withContext(Dispatchers.IO) {
                val declaredSize = reference.sizeBytes
                if (declaredSize != null && declaredSize > BuildRequestValidator.MAX_IMAGE_SIZE_BYTES) {
                    throw failure(AiErrorCode.IMAGE_TOO_LARGE)
                }
                val uri = runCatching { Uri.parse(reference.contentUri) }.getOrNull()
                if (uri == null || uri.scheme != "content") throw failure(AiErrorCode.IMAGE_UNREADABLE)

                val normalizedMime = reference.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
                if (normalizedMime !in BuildRequestValidator.SUPPORTED_IMAGE_MEDIA_TYPES) {
                    throw failure(AiErrorCode.IMAGE_CONTENT_INVALID)
                }
                val raw = try {
                    resolver.openInputStream(uri)?.use { it.readBounded(BuildRequestValidator.MAX_IMAGE_SIZE_BYTES.toInt()) }
                        ?: throw failure(AiErrorCode.IMAGE_UNREADABLE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AiProviderException) {
                    throw error
                } catch (_: Exception) {
                    throw failure(AiErrorCode.IMAGE_UNREADABLE)
                }
                try {
                    prepareBytes(reference, raw).also { preparedImage = it }
                } finally {
                    raw.fill(0)
                }
            }
        } catch (error: Throwable) {
            // withContext may discard its result if cancellation wins while switching dispatchers.
            preparedImage?.close()
            throw error
        }
    }

    /** Visible for instrumented image-format and resize tests; sourceBytes remains caller-owned. */
    internal fun prepareBytes(reference: BuildInput.ImageReference, sourceBytes: ByteArray): AiImageInput {
        if (sourceBytes.isEmpty() || sourceBytes.size > BuildRequestValidator.MAX_IMAGE_SIZE_BYTES) {
            throw failure(if (sourceBytes.size > BuildRequestValidator.MAX_IMAGE_SIZE_BYTES) AiErrorCode.IMAGE_TOO_LARGE else AiErrorCode.IMAGE_CONTENT_INVALID)
        }
        val actualMime = sniffMime(sourceBytes) ?: throw failure(AiErrorCode.IMAGE_CONTENT_INVALID)
        val expectedMime = reference.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (actualMime != expectedMime) throw failure(AiErrorCode.IMAGE_MIME_MISMATCH)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        val pixels = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixels <= 0L) {
            throw failure(AiErrorCode.IMAGE_CONTENT_INVALID)
        }
        if (width > MAX_INPUT_DIMENSION || height > MAX_INPUT_DIMENSION || pixels > MAX_INPUT_PIXELS) {
            throw failure(AiErrorCode.IMAGE_DIMENSIONS_UNSUPPORTED)
        }
        if (bounds.outMimeType != null && bounds.outMimeType !in BuildRequestValidator.SUPPORTED_IMAGE_MEDIA_TYPES) {
            throw failure(AiErrorCode.IMAGE_CONTENT_INVALID)
        }

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = false
        }
        var bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
            ?: throw failure(AiErrorCode.IMAGE_CONTENT_INVALID)
        val oriented = applyExifOrientation(bitmap, sourceBytes)
        if (oriented !== bitmap) {
            bitmap.recycle()
            bitmap = oriented
        }
        val bounded = fitProviderBounds(bitmap)
        if (bounded !== bitmap) {
            bitmap.recycle()
            bitmap = bounded
        }

        try {
            var quality = INITIAL_JPEG_QUALITY
            repeat(MAX_ENCODE_ATTEMPTS) { attempt ->
                val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val qualityForFormat = if (format == Bitmap.CompressFormat.JPEG) quality else 100
                val encoded = encode(bitmap, format, qualityForFormat)
                if (encoded.size in 1..AiImageInput.MAX_PROVIDER_IMAGE_BYTES) {
                    val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
                    val prepared = try {
                        AiImageInput(mime, bitmap.width, bitmap.height, encoded)
                    } finally {
                        encoded.fill(0)
                    }
                    return prepared
                }
                encoded.fill(0)
                if (format == Bitmap.CompressFormat.JPEG && quality > MIN_JPEG_QUALITY) {
                    quality = (quality - JPEG_QUALITY_STEP).coerceAtLeast(MIN_JPEG_QUALITY)
                }
                if (attempt == MAX_ENCODE_ATTEMPTS - 1 || max(bitmap.width, bitmap.height) <= MIN_RESIZE_DIMENSION) {
                    return@repeat
                }
                val pixelScale = sqrt(AiImageInput.MAX_PROVIDER_IMAGE_PIXELS.toDouble() / (bitmap.width.toLong() * bitmap.height))
                    .toFloat().coerceAtMost(1f)
                val dimensionScale = AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION.toFloat() / max(bitmap.width, bitmap.height)
                val scale = minOf(RESIZE_FACTOR, dimensionScale, pixelScale).coerceAtMost(RESIZE_FACTOR)
                val targetWidth = floor(bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetHeight = floor(bitmap.height * scale).toInt().coerceAtLeast(1)
                val smaller = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (smaller !== bitmap) {
                    bitmap.recycle()
                    bitmap = smaller
                }
            }
            throw failure(AiErrorCode.IMAGE_TOO_LARGE)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val output = WipingByteArrayOutputStream()
        try {
            if (!bitmap.compress(format, quality, output)) throw failure(AiErrorCode.IMAGE_CONTENT_INVALID)
            return output.takeBytes()
        } finally {
            output.wipe()
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (ceil(width.toDouble() / sample) > AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION ||
            ceil(height.toDouble() / sample) > AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION ||
            (width.toLong() / sample) * (height.toLong() / sample) > AiImageInput.MAX_PROVIDER_IMAGE_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }

    private fun fitProviderBounds(bitmap: Bitmap): Bitmap {
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        if (bitmap.width <= AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION &&
            bitmap.height <= AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION &&
            pixelCount <= AiImageInput.MAX_PROVIDER_IMAGE_PIXELS
        ) {
            return bitmap
        }
        val dimensionScale = AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION.toFloat() / max(bitmap.width, bitmap.height)
        val pixelScale = sqrt(AiImageInput.MAX_PROVIDER_IMAGE_PIXELS.toDouble() / pixelCount).toFloat()
        val scale = minOf(dimensionScale, pixelScale, 1f)
        val targetWidth = floor(bitmap.width * scale).toInt().coerceIn(1, AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION)
        val targetHeight = floor(bitmap.height * scale).toInt().coerceIn(1, AiImageInput.MAX_PROVIDER_IMAGE_DIMENSION)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun applyExifOrientation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: IllegalArgumentException) {
            bitmap
        }
    }

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> "image/png"
        bytes.size >= 12 && bytes.asciiEquals(0, "RIFF") && bytes.asciiEquals(8, "WEBP") -> "image/webp"
        else -> null
    }

    private fun ByteArray.asciiEquals(offset: Int, value: String): Boolean =
        value.indices.all { this[offset + it] == value[it].code.toByte() }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = WipingByteArrayOutputStream()
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var total = 0
        try {
            while (true) {
                val allowed = (maxBytes + 1 - total).coerceAtMost(buffer.size)
                if (allowed <= 0) throw failure(AiErrorCode.IMAGE_TOO_LARGE)
                val count = read(buffer, 0, allowed)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw failure(AiErrorCode.IMAGE_TOO_LARGE)
                output.write(buffer, 0, count)
            }
            return output.takeBytes()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        fun takeBytes(): ByteArray = toByteArray().also { wipe() }
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun failure(code: AiErrorCode) = AiProviderException(
        AiFailure(code = code, retryable = false),
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        const val MAX_INPUT_DIMENSION = 16_384
        const val MAX_INPUT_PIXELS = 80_000_000L
        const val MIN_RESIZE_DIMENSION = 512
        const val RESIZE_FACTOR = 0.84f
        const val INITIAL_JPEG_QUALITY = 90
        const val MIN_JPEG_QUALITY = 76
        const val JPEG_QUALITY_STEP = 7
        const val MAX_ENCODE_ATTEMPTS = 9
        const val IO_BUFFER_SIZE = 16 * 1024
    }
}
