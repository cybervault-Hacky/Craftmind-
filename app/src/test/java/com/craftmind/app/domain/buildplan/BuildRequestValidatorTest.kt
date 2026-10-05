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
    fun acceptsOnlyAbsoluteHttpsUrlsWithoutCredentialsOrLocalDestinations() {
        val video = validator.validateUrl("  https://raw.githubusercontent.com/owner/repo/main/video.mp4  ")
        assertEquals(
            UrlValidationResult.Valid("https://raw.githubusercontent.com/owner/repo/main/video.mp4"),
            video,
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.UNSUPPORTED_PUBLIC_VIDEO_SOURCE),
            validator.validateUrl("https://example.com/gallery"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.UNSUPPORTED_PUBLIC_VIDEO_SOURCE),
            validator.validateUrl("https://raw.githubusercontent.com/owner/repo/main/index.html"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED),
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
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_UNSAFE_HOST),
            validator.validateUrl("https://127.0.0.1/video.mp4"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_UNSAFE_HOST),
            validator.validateUrl("https://localhost/video.mp4"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_QUERY_OR_FRAGMENT_NOT_ALLOWED),
            validator.validateUrl("https://example.com/video.mp4?token=secret"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_QUERY_OR_FRAGMENT_NOT_ALLOWED),
            validator.validateUrl("https://example.com/video.mp4#frame"),
        )
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_PORT_NOT_ALLOWED),
            validator.validateUrl("https://example.com:8443/video.mp4"),
        )
        assertTrue(validator.validateUrl("https://").let { it is UrlValidationResult.Invalid })
        assertTrue(validator.validateUrl("not a url").let { it is UrlValidationResult.Invalid })
        assertEquals(
            UrlValidationResult.Invalid(BuildRequestValidationError.URL_TOO_LONG),
            validator.validateUrl("https://example.com/" + "a".repeat(BuildRequestValidator.MAX_URL_LENGTH)),
        )
    }

    @Test
    fun rejectsCombiningImageAndVideoReferencesInsteadOfSilentlyDroppingEither() {
        val image = BuildInput.ImageReference(
            contentUri = "content://picker/items/17",
            mediaType = "IMAGE/PNG; charset=binary",
            sizeBytes = 456_789L,
            displayName = "garden-reference.png",
        )

        assertEquals(
            BuildRequestValidationResult.Invalid(BuildRequestValidationError.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED),
            validator.create(
                BuildRequestDraft(
                    prompt = "A greenhouse beside a stream",
                    imageReference = image,
                    urlReference = BuildInput.UrlReference("https://example.com/garden"),
                ),
            ),
        )
    }

    @Test
    fun acceptsVideoReferenceWithOptionalTextAndNormalizesHttpsUrl() {
        val videoUrl = "  https://raw.githubusercontent.com/user/repo/main/video.mp4  "
        val result = validator.create(BuildRequestDraft(prompt = "  Analyze this build  ", urlReference = BuildInput.UrlReference(videoUrl)))

        assertTrue(result is BuildRequestValidationResult.Valid)
        val request = (result as BuildRequestValidationResult.Valid).request
        assertEquals("Analyze this build", request.prompt)
        assertEquals(BuildInput.UrlReference(videoUrl.trim()), request.urlReference)
        assertEquals(listOf(BuildInput.Text("Analyze this build"), BuildInput.UrlReference(videoUrl.trim())), request.inputs())
    }

    @Test
    fun acceptsVideoOnlyRequestButStillRejectsEmptyRequest() {
        val url = BuildInput.UrlReference("https://raw.githubusercontent.com/user/repo/main/video.mp4")
        val result = validator.create(BuildRequestDraft(prompt = "", urlReference = url))
        assertTrue(result is BuildRequestValidationResult.Valid)
        val request = (result as BuildRequestValidationResult.Valid).request
        assertEquals("", request.prompt)
        assertEquals(url, request.urlReference)
        assertEquals(
            BuildRequestValidationResult.Invalid(BuildRequestValidationError.EMPTY_PROMPT),
            validator.create(BuildRequestDraft(prompt = "")),
        )
    }

    @Test
    fun acceptsImageOnlyRequestButStillRejectsEmptyRequest() {
        val image = BuildInput.ImageReference(
            contentUri = "content://picker/items/only-image",
            mediaType = "image/webp",
            sizeBytes = 512L,
        )
        val result = validator.create(BuildRequestDraft(prompt = "", imageReference = image))
        assertTrue(result is BuildRequestValidationResult.Valid)
        val request = (result as BuildRequestValidationResult.Valid).request
        assertEquals("", request.prompt)
        assertEquals(image, request.imageReference)
        assertEquals(
            BuildRequestValidationResult.Invalid(BuildRequestValidationError.EMPTY_PROMPT),
            validator.create(BuildRequestDraft(prompt = "")),
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
