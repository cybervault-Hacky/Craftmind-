package com.craftmind.app.domain

import com.craftmind.app.domain.model.ReferenceInput
import com.craftmind.app.domain.validation.BuilderInputValidation
import com.craftmind.app.domain.validation.BuilderInputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderInputValidatorTest {
    private val image = ReferenceInput.Image(
        uri = "content://photo/1",
        mimeType = "image/jpeg",
        displayName = "reference.jpg",
        sizeBytes = 512_000,
    )
    private val url = ReferenceInput.Url("https://example.com/build")

    @Test
    fun blankPromptWithoutReferencesIsRejected() {
        assertEquals(
            BuilderInputValidation.MissingInput,
            BuilderInputValidator.validate("  \n", image = null, url = null),
        )
    }

    @Test
    fun promptIsTrimmedAndAccepted() {
        val result = BuilderInputValidator.validate("  stone bridge  ", image = null, url = null)

        assertTrue(result is BuilderInputValidation.Valid)
        assertEquals("stone bridge", (result as BuilderInputValidation.Valid).request.prompt)
        assertTrue(result.request.references.isEmpty())
    }

    @Test
    fun imageOnlyAndUrlOnlyAreValidFutureRequests() {
        val imageOnly = BuilderInputValidator.validate("", image, null)
        val urlOnly = BuilderInputValidator.validate("", null, url)

        assertEquals(listOf(image), (imageOnly as BuilderInputValidation.Valid).request.references)
        assertEquals(listOf(url), (urlOnly as BuilderInputValidation.Valid).request.references)
        assertEquals(null, imageOnly.request.prompt)
        assertEquals(null, urlOnly.request.prompt)
    }

    @Test
    fun textCanBeCombinedWithEitherSingleReferenceType() {
        val textAndImage = BuilderInputValidator.validate("bridge", image, null) as BuilderInputValidation.Valid
        val textAndUrl = BuilderInputValidator.validate("bridge", null, url) as BuilderInputValidation.Valid

        assertEquals("bridge", textAndImage.request.prompt)
        assertEquals(listOf(image), textAndImage.request.references)
        assertEquals("bridge", textAndUrl.request.prompt)
        assertEquals(listOf(url), textAndUrl.request.references)
    }

    @Test
    fun textImageAndUrlCanBeCombinedWithoutLosingInputs() {
        val result = BuilderInputValidator.validate("  tower  ", image, url) as BuilderInputValidation.Valid

        assertEquals("tower", result.request.prompt)
        assertEquals(listOf(image, url), result.request.references)
        assertTrue(result.request.hasInput)
    }

    @Test
    fun promptOverLimitIsRejectedRatherThanSilentlyValidated() {
        val tooLong = "x".repeat(BuilderInputValidator.MAX_PROMPT_LENGTH + 1)

        assertEquals(
            BuilderInputValidation.PromptTooLong,
            BuilderInputValidator.validate(tooLong, image = null, url = null),
        )
    }
}
