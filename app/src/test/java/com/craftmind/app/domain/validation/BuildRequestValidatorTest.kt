package com.craftmind.app.domain.validation

import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.ImageReference
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.UrlReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRequestValidatorTest {
    private val validator = BuildRequestValidator()

    @Test
    fun normalizesTextAndPreservesImageAndUrlReferencesTogether() {
        val image = ImageReference("content://photos/ref", "image/jpeg", "reference.jpg", 512)
        val url = UrlReference("https://example.com/build")

        val result = validator.validate(BuildRequest(listOf(TextInput("  a small cabin  "), image, url)))

        assertTrue(result is BuildRequestValidationResult.Valid)
        val normalized = (result as BuildRequestValidationResult.Valid).request
        assertEquals(listOf(TextInput("a small cabin"), image, url), normalized.inputs)
    }

    @Test
    fun duplicateInputKindsAndUnsafeReferencesAreRejected() {
        assertEquals(
            BuildRequestValidationIssue.DUPLICATE_INPUT_TYPE,
            (validator.validate(BuildRequest(listOf(TextInput("one"), TextInput("two")))) as BuildRequestValidationResult.Invalid).issue,
        )
        assertEquals(
            BuildRequestValidationIssue.INVALID_URL_REFERENCE,
            (validator.validate(BuildRequest(listOf(UrlReference("javascript:alert(1)")))) as BuildRequestValidationResult.Invalid).issue,
        )
        assertEquals(
            BuildRequestValidationIssue.INVALID_IMAGE_REFERENCE,
            (validator.validate(BuildRequest(listOf(ImageReference("file:///secret", "image/jpeg", "photo.jpg", 12)))) as BuildRequestValidationResult.Invalid).issue,
        )
    }
}
