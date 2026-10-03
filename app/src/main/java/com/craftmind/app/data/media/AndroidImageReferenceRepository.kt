package com.craftmind.app.data.media

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.media.ImageReferenceValidator
import com.craftmind.app.domain.media.ImageThumbnail
import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.media.MAX_IMAGE_SIZE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** ContentResolver adapter for the Android system photo picker. It never uploads selected media. */
class AndroidImageReferenceRepository(
    private val contentResolver: ContentResolver,
) : ImageReferenceRepository {

    override suspend fun inspect(uri: String): ImageValidationResult = withContext(Dispatchers.IO) {
        val parsedUri = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext ImageValidationResult.Rejected(ImageValidationError.UNREADABLE)

        try {
            val metadata = readMetadata(parsedUri)
            val validation = ImageReferenceValidator.validate(
                uri = parsedUri.toString(),
                mimeType = metadata.mimeType,
                displayName = metadata.displayName,
                sizeBytes = metadata.sizeBytes,
            )
            validation
        } catch (_: Exception) {
            ImageValidationResult.Rejected(ImageValidationError.UNREADABLE)
        }
    }

    override suspend fun loadThumbnail(
        uri: String,
        maxDimensionPixels: Int,
    ): ImageThumbnail? = withContext(Dispatchers.IO) {
        if (maxDimensionPixels <= 0) return@withContext null
        val parsedUri = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null

        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(parsedUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            } ?: return@withContext null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val sampleSize = calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimensionPixels = maxDimensionPixels,
            )
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = contentResolver.openInputStream(parsedUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return@withContext null

            ByteArrayOutputStream().use { output ->
                val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                if (encoded) ImageThumbnail(output.toByteArray(), "image/png") else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readMetadata(uri: Uri): ImageMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        query(uri)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }

        val mimeType = contentResolver.getType(uri)
            ?: displayName
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        if (sizeBytes == null) sizeBytes = resolveSize(uri)
        return ImageMetadata(
            displayName = displayName ?: "Reference image",
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        )
    }

    private fun query(uri: Uri): Cursor? = contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )

    private fun resolveSize(uri: Uri): Long? {
        val descriptorSize = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0L }
        if (descriptorSize != null) return descriptorSize

        // Some cloud-backed providers do not publish SIZE. Count only until the limit is exceeded.
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (total <= MAX_IMAGE_SIZE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) return@use total
                    total += read
                }
                total
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimensionPixels: Int): Int {
        var sample = 1
        while (width / sample > maxDimensionPixels || height / sample > maxDimensionPixels) {
            sample *= 2
        }
        return sample
    }

    private data class ImageMetadata(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long?,
    )
}
