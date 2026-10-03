package com.craftmind.app.domain

import com.craftmind.app.domain.media.ImageReferenceValidator
import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.media.MAX_IMAGE_SIZE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageReferenceValidatorTest {
    @Test
    fun acceptsSupportedImageAndRetainsItsLocalMetadata() {
        val result = ImageReferenceValidator.validate(
            uri = "content://picker/image/42",
            mimeType = "image/jpeg",
            displayName = "house.jpg",
            sizeBytes = 2048,
        )

        assertTrue(result is ImageValidationResult.Accepted)
        val image = (result as ImageValidationResult.Accepted).image
        assertEquals("content://picker/image/42", image.uri)
        assertEquals("image/jpeg", image.mimeType)
        assertEquals("house.jpg", image.displayName)
        assertEquals(2048L, image.sizeBytes)
    }

    @Test
    fun usesFileExtensionOnlyWhenProviderDoesNotSupplyAMimeType() {
        val result = ImageReferenceValidator.validate(
            uri = "content://picker/image/43",
            mimeType = null,
            displayName = "concept.webp",
            sizeBytes = 1024,
        )

        assertEquals("image/webp", (result as ImageValidationResult.Accepted).image.mimeType)
    }

    @Test
    fun rejectsUnsupportedTypesAndMimeExtensionMismatches() {
        assertEquals(
            ImageValidationResult.Rejected(ImageValidationError.UNSUPPORTED_FORMAT),
            ImageReferenceValidator.validate(
                uri = "content://picker/image/44",
                mimeType = "image/gif",
                displayName = "animation.gif",
                sizeBytes = 1024,
            ),
        )
        assertEquals(
            ImageValidationResult.Rejected(ImageValidationError.UNSUPPORTED_FORMAT),
            ImageReferenceValidator.validate(
                uri = "content://picker/image/45",
                mimeType = "image/gif",
                displayName = "image.jpg",
                sizeBytes = 1024,
            ),
        )
    }

    @Test
    fun rejectsOversizedOrUnverifiableFiles() {
        assertEquals(
            ImageValidationResult.Rejected(ImageValidationError.FILE_TOO_LARGE),
            ImageReferenceValidator.validate(
                uri = "content://picker/image/46",
                mimeType = "image/png",
                displayName = "large.png",
                sizeBytes = MAX_IMAGE_SIZE_BYTES + 1,
            ),
        )
        assertEquals(
            ImageValidationResult.Rejected(ImageValidationError.SIZE_UNAVAILABLE),
            ImageReferenceValidator.validate(
                uri = "content://picker/image/47",
                mimeType = "image/png",
                displayName = "unknown.png",
                sizeBytes = null,
            ),
        )
    }
}
