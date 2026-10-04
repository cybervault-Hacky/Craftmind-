package com.craftmind.app.presentation.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded, local-only thumbnail decoding. The original image is never copied or uploaded. */
@Composable
fun ImageThumbnail(
    contentUri: String,
    modifier: Modifier = Modifier,
    size: Dp = 68.dp,
    description: String = "Attached reference image preview",
) {
    val context = LocalContext.current
    val thumbnail = produceState<ImageBitmap?>(initialValue = null, key1 = contentUri) {
        value = null
        value = withContext(Dispatchers.IO) {
            decodeThumbnail(context, Uri.parse(contentUri))?.asImageBitmap()
        }
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
                    shape = RoundedCornerShape(14.dp),
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

private fun decodeThumbnail(context: android.content.Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, imageInfo, _ ->
                val originalWidth = imageInfo.size.width
                val originalHeight = imageInfo.size.height
                val largestDimension = max(originalWidth, originalHeight).coerceAtLeast(1)
                val scale = minOf(1f, MAX_PREVIEW_PIXELS.toFloat() / largestDimension)
                decoder.setTargetSize(
                    (originalWidth * scale).roundToInt().coerceAtLeast(1),
                    (originalHeight * scale).roundToInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return null

            val largestDimension = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (largestDimension / sample > MAX_PREVIEW_PIXELS && sample <= Int.MAX_VALUE / 2) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
    } catch (_: Exception) {
        null
    }
}

private const val MAX_PREVIEW_PIXELS = 640
