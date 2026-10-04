package com.craftmind.app.domain.buildplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRequestValidatorTest {
    private val validator = BuildRequestValidator(
        nowEpochMillis = { 1_700_000_000_000L },
        requestId = { "request-test-id" },
    )

    @Test
    fun rejectsEmptyAndWhitespaceOnlyPrompts() {
        assertEquals(
            BuildRequestValidationError.EMPTY_PROMPT,
            validator.validatePrompt(""),
        )
        assertEquals(
            BuildRequestValidationError.EMPTY_PROMPT,
            validator.validatePrompt("  \n\t "),
        )
    }

    @Test
    fun acceptsPromptAtLimitAndRejectsPromptOverLimit() {
        assertEquals(null, validator.validatePrompt("a".repeat(BuildRequestValidator.MAX_PROMPT_LENGTH)))
        assertEquals(
            BuildRequestValidationError.PROMPT_TOO_LONG,
            validator.validatePrompt("a".repeat(BuildRequestValidator.MAX_PROMPT_LENGTH + 1)),
        )
    }

    @Test
    fun acceptsOnlyAbsoluteHttpAndHttpsUrlsWithoutEmbeddedCredentials() {
        val https = validator.validateUrl("  https://example.com/gallery?view=wide  ")
        assertEquals(UrlValidationResult.Valid("https://example.com/gallery?view=wide"), https)
        assertEquals(
            UrlValidationResult.Valid("http://example.org/build"),
            validator.validateUrl("http://example.org/build"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED),
            validator.validateUrl("file:///private/image.jpg"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_CREDENTIALS_NOT_ALLOWED),
            validator.validateUrl("https://user:secret@example.com/page"),
        )
        assertTrue(validator.validateUrl("https://").let { it is UrlValidationResult.Invalid })
        assertTrue(validator.validateUrl("not a url").let { it is UrlValidationResult.Invalid })
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_TOO_LONG),
            validator.validateUrl("https://example.com/" + "a".repeat(BuildRequestValidator.MAX_URL_LENGTH)),
        )
    }

    @Test
    fun createTrimsPromptAndBuildsTextImageAndUrlInputsWithoutLosingImageMetadata() {
        val image = BuildInput.ImageReference(
            contentUri = "content://picker/items/17",
            mediaType = "IMAGE/PNG; charset=binary",
            sizeBytes = 456_789L,
            displayName = "garden-reference.png",
        )

        val result = validator.create(
            BuildRequestDraft(
                prompt = "  A greenhouse beside a stream  ",
                imageReference = image,
                urlReference = BuildInput.UrlReference("https://example.com/garden"),
            ),
        )

        assertTrue(result is BuildRequestValidationResult.Valid)
        val request = (result as BuildRequestValidationResult.Valid).request
        assertEquals("request-test-id", request.requestId)
        assertEquals(1_700_000_000_000L, request.createdAtEpochMillis)
        assertEquals("A greenhouse beside a stream", request.prompt)
        assertEquals(image.copy(mediaType = "image/png"), request.imageReference)
        assertEquals(BuildInput.UrlReference("https://example.com/garden"), request.urlReference)
        assertEquals(
            listOf(
                BuildInput.Text("A greenhouse beside a stream"),
                image.copy(mediaType = "image/png"),
                BuildInput.UrlReference("https://example.com/garden"),
            ),
            request.inputs(),
        )
    }

    @Test
    fun imageValidationRejectsUnsupportedTypesAndOversizedFiles() {
        val unsupported = BuildInput.ImageReference(
            contentUri = "content://picker/1",
            mediaType = "image/gif",
            sizeBytes = 1_000L,
        )
        val oversized = BuildInput.ImageReference(
            contentUri = "content://picker/2",
            mediaType = "image/jpeg",
            sizeBytes = BuildRequestValidator.MAX_IMAGE_SIZE_BYTES + 1,
        )

        assertEquals(BuildRequestValidationError.UNSUPPORTED_IMAGE_TYPE, validator.validateImage(unsupported))
        assertEquals(BuildRequestValidationError.IMAGE_TOO_LARGE, validator.validateImage(oversized))
        assertEquals(null, validator.validateImage(unsupported.copy(mediaType = "image/webp")))
    }

    @Test
    fun createNeverBuildsARequestWhenAnyInputIsInvalid() {
        val result = validator.create(
            BuildRequestDraft(
                prompt = "A treehouse",
                imageReference = BuildInput.ImageReference(
                    contentUri = "content://picker/3",
                    mediaType = "image/jpeg",
                    sizeBytes = BuildRequestValidator.MAX_IMAGE_SIZE_BYTES + 1,
                ),
            ),
        )

        assertEquals(
            BuildRequestValidationResult.Invalid(BuildRequestValidationError.IMAGE_TOO_LARGE),
            result,
        )
    }
}
