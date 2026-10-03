package com.craftmind.app.domain

import com.craftmind.app.domain.model.ImageReference
import com.craftmind.app.domain.model.TextInput
import com.craftmind.app.domain.model.UrlReference
import com.craftmind.app.domain.validation.BuilderInputValidation
import com.craftmind.app.domain.validation.BuilderInputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderInputValidatorTest {
    private val image = ImageReference(
        uri = "content://photo/1",
        mimeType = "image/jpeg",
        displayName = "reference.jpg",
        sizeBytes = 512_000,
    )
    private val url = UrlReference("https://example.com/build")

    @Test
    fun blankPromptWithoutReferencesIsRejected() {
        assertEquals(
            BuilderInputValidation.MissingInput,
            BuilderInputValidator.validate("  \n", image = null, url = null),
        )
    }

    @Test
    fun promptIsTrimmedAndAcceptedAsTypedInput() {
        val result = BuilderInputValidator.validate("  stone bridge  ", image = null, url = null)

        assertTrue(result is BuilderInputValidation.Valid)
        assertEquals(listOf(TextInput("stone bridge")), (result as BuilderInputValidation.Valid).request.inputs)
    }

    @Test
    fun imageOnlyAndUrlOnlyAreRepresentable() {
        val imageOnly = BuilderInputValidator.validate("", image, null) as BuilderInputValidation.Valid
        val urlOnly = BuilderInputValidator.validate("", null, url) as BuilderInputValidation.Valid

        assertEquals(listOf(image), imageOnly.request.inputs)
        assertEquals(listOf(url), urlOnly.request.inputs)
        assertEquals(null, imageOnly.request.text)
        assertEquals(null, urlOnly.request.text)
    }

    @Test
    fun textCanBeCombinedWithEitherReferenceType() {
        val textAndImage = BuilderInputValidator.validate("bridge", image, null) as BuilderInputValidation.Valid
        val textAndUrl = BuilderInputValidator.validate("bridge", null, url) as BuilderInputValidation.Valid

        assertEquals(listOf(TextInput("bridge"), image), textAndImage.request.inputs)
        assertEquals(listOf(TextInput("bridge"), url), textAndUrl.request.inputs)
    }

    @Test
    fun textImageAndUrlCanBeCombinedWithoutLosingInputs() {
        val result = BuilderInputValidator.validate("  tower  ", image, url) as BuilderInputValidation.Valid

        assertEquals(listOf(TextInput("tower"), image, url), result.request.inputs)
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
