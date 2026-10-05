package com.craftmind.app.data.ai

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.craftmind.app.domain.ai.AiImageInput
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.BuildInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentResolverImageInputPreparerTest {
    private val preparer = ContentResolverImageInputPreparer(
        ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun acceptsAndNormalizesJpegPngAndWebpLocally() {
        listOf(
            Bitmap.CompressFormat.JPEG to "image/jpeg",
            Bitmap.CompressFormat.PNG to "image/png",
            Bitmap.CompressFormat.WEBP to "image/webp",
        ).forEach { (format, inputMime) ->
            val bitmap = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
            val source = java.io.ByteArrayOutputStream().use { stream ->
                bitmap.compress(format, 90, stream)
                stream.toByteArray()
            }
            bitmap.recycle()
            val input = preparer.prepareBytes(reference(inputMime, source.size.toLong()), source)
            try {
                assertTrue(input.mediaType == "image/jpeg" || input.mediaType == "image/png")
                assertEquals(80, input.width)
                assertEquals(48, input.height)
                assertTrue(input.byteCount in 1..AiImageInput.MAX_PROVIDER_IMAGE_BYTES)
            } finally {
                input.close()
                source.fill(0)
            }
        }
    }

    @Test
    fun rejectsMimeMismatchCorruptBytesAndUnsafeDimensions() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val jpeg = java.io.ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        val mismatch = expectFailure { preparer.prepareBytes(reference("image/png", jpeg.size.toLong()), jpeg) }
        assertEquals(com.craftmind.app.domain.ai.AiErrorCode.IMAGE_MIME_MISMATCH, mismatch.failure.code)

        val corrupt = expectFailure {
            preparer.prepareBytes(reference("image/jpeg", 4), byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0))
        }
        assertEquals(com.craftmind.app.domain.ai.AiErrorCode.IMAGE_CONTENT_INVALID, corrupt.failure.code)

        val tooWide = Bitmap.createBitmap(16_385, 1, Bitmap.Config.ARGB_8888)
        val wideBytes = java.io.ByteArrayOutputStream().use { stream ->
            tooWide.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        tooWide.recycle()
        val dimensions = expectFailure {
            preparer.prepareBytes(reference("image/png", wideBytes.size.toLong()), wideBytes)
        }
        assertEquals(com.craftmind.app.domain.ai.AiErrorCode.IMAGE_DIMENSIONS_UNSUPPORTED, dimensions.failure.code)
        jpeg.fill(0)
        wideBytes.fill(0)
    }

    @Test
    fun closingPreparedImageMarksItsPayloadUnavailable() {
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        val source = java.io.ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        val input = preparer.prepareBytes(reference("image/jpeg", source.size.toLong()), source)
        input.close()
        assertTrue(input.toString().contains("closed=true"))
        source.fill(0)
    }

    private fun reference(mime: String, size: Long) =
        BuildInput.ImageReference("content://test.local/image", mime, size)

    private fun expectFailure(block: () -> AiImageInput): AiProviderException = try {
        block()
        throw AssertionError("Expected image validation rejection")
    } catch (expected: AiProviderException) {
        expected
    }
}
