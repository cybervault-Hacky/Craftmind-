package com.craftmind.app.presentation.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.craftmind.app.designsystem.CraftMindShapes
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** Bounded, local-only preview decoding; malformed, oversized, or unavailable references show a placeholder. */
@Composable
fun ImageThumbnail(
    reference: BuildInput.ImageReference,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    description: String = "Attached reference image preview",
) {
    val context = LocalContext.current
    val thumbnail = produceState<ImageBitmap?>(
        null,
        reference.contentUri,
        reference.mediaType,
        reference.sizeBytes,
    ) {
        value = null
        value = decodeThumbnail(context, reference)?.asImageBitmap()
    }

    if (thumbnail.value != null) {
        Image(
            bitmap = thumbnail.value!!,
            contentDescription = description,
            modifier = modifier
                .size(size)
                .semantics { this.contentDescription = description },
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CraftMindShapes.md,
                )
                .semantics { this.contentDescription = "Reference image preview unavailable" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun decodeThumbnail(context: Context, reference: BuildInput.ImageReference): Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (reference.sizeBytes != null && reference.sizeBytes > BuildRequestValidator.MAX_IMAGE_SIZE_BYTES) return@withContext null
        val uri = runCatching { Uri.parse(reference.contentUri) }.getOrNull()
            ?.takeIf { it.scheme == "content" }
            ?: return@withContext null
        val expectedMime = reference.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
            .takeIf { it in BuildRequestValidator.SUPPORTED_IMAGE_MEDIA_TYPES }
            ?: return@withContext null
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBounded(BuildRequestValidator.MAX_IMAGE_SIZE_BYTES.toInt())
            } ?: return@withContext null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext null
        }
        try {
            if (sniffMime(bytes) != expectedMime) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            val pixelCount = width.toLong() * height.toLong()
            if (width <= 0 || height <= 0 || width > MAX_INPUT_DIMENSION || height > MAX_INPUT_DIMENSION ||
                pixelCount <= 0L || pixelCount > MAX_INPUT_PIXELS
            ) {
                return@withContext null
            }
            if (bounds.outMimeType != null && bounds.outMimeType !in BuildRequestValidator.SUPPORTED_IMAGE_MEDIA_TYPES) {
                return@withContext null
            }
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize(width, height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inMutable = false
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null
            applyExifOrientation(decoded, bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (ceil(width.toDouble() / sample) > MAX_PREVIEW_PIXELS ||
        ceil(height.toDouble() / sample) > MAX_PREVIEW_PIXELS
    ) {
        sample *= 2
    }
    return sample
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
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
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
            if (allowed <= 0) return byteArrayOf()
            val count = read(buffer, 0, allowed)
            if (count < 0) break
            total += count
            if (total > maxBytes) return byteArrayOf()
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

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private const val MAX_INPUT_DIMENSION = 16_384
private const val MAX_INPUT_PIXELS = 80_000_000L
private const val MAX_PREVIEW_PIXELS = 640
private const val IO_BUFFER_SIZE = 16 * 1024
