package com.craftmind.app.presentation.home

import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildComposerReducerTest {
    private val reducer = BuildComposerReducer(
        BuildRequestValidator(nowEpochMillis = { 123L }, requestId = { "request-123" }),
    )

    @Test
    fun generateWithoutPromptMovesToValidationBlockedState() {
        val next = reducer.reduce(BuildComposerState(), BuildComposerEvent.Generate)

        assertEquals(
            BuildGenerationState.ValidationBlocked(BuildRequestValidationError.EMPTY_PROMPT),
            next.generation,
        )
    }

    @Test
    fun validGeneratePreparesValidatedInputWithoutPretendingTheProviderAlreadyResponded() {
        val withPrompt = reducer.reduce(
            BuildComposerState(),
            BuildComposerEvent.PromptChanged("  A stone observatory  "),
        )
        val next = reducer.reduce(withPrompt, BuildComposerEvent.Generate)

        assertTrue(next.generation is BuildGenerationState.Prepared)
        val prepared = next.generation as BuildGenerationState.Prepared
        assertEquals("A stone observatory", prepared.request.prompt)
        assertEquals("request-123", prepared.request.requestId)
    }

    @Test
    fun editingPromptDismissesTheUnavailableNoticeAndInvalidImageIsNotAttached() {
        val initial = reducer.reduce(
            BuildComposerState(),
            BuildComposerEvent.PromptChanged("A greenhouse"),
        )
        val prepared = reducer.reduce(initial, BuildComposerEvent.Generate)
        val edited = reducer.reduce(prepared, BuildComposerEvent.PromptChanged("A larger greenhouse"))

        val invalidImage = reducer.reduce(
            edited,
            BuildComposerEvent.ImageSelected(
                BuildInput.ImageReference(
                    contentUri = "content://picker/large",
                    mediaType = "image/png",
                    sizeBytes = BuildRequestValidator.MAX_IMAGE_SIZE_BYTES + 5,
                ),
            ),
        )

        assertEquals(BuildGenerationState.Idle, invalidImage.generation)
        assertNull(invalidImage.imageReference)
        assertEquals(BuildRequestValidationError.IMAGE_TOO_LARGE, invalidImage.imageError)
        assertEquals(
            BuildGenerationState.ValidationBlocked(BuildRequestValidationError.IMAGE_TOO_LARGE),
            reducer.reduce(invalidImage, BuildComposerEvent.Generate).generation,
        )
    }

    @Test
    fun videoOnlyRequestCanBePreparedWithoutInferringOrDroppingAnyInput() {
        var state = reducer.reduce(BuildComposerState(), BuildComposerEvent.OpenUrlEditor)
        state = reducer.reduce(state, BuildComposerEvent.UrlDraftChanged("https://raw.githubusercontent.com/owner/repo/main/video.mp4"))
        state = reducer.reduce(state, BuildComposerEvent.SaveUrl)
        val prepared = reducer.reduce(state, BuildComposerEvent.Generate)

        assertTrue(prepared.generation is BuildGenerationState.Prepared)
        val request = (prepared.generation as BuildGenerationState.Prepared).request
        assertEquals("", request.prompt)
        assertEquals(BuildInput.UrlReference("https://raw.githubusercontent.com/owner/repo/main/video.mp4"), request.urlReference)
        assertNull(request.imageReference)
    }

    @Test
    fun composerRejectsCombiningImageAndVideoInsteadOfIgnoringOne() {
        val image = BuildInput.ImageReference("content://picker/image", "image/png", 100)
        var state = reducer.reduce(BuildComposerState(), BuildComposerEvent.ImageSelected(image))
        state = reducer.reduce(state, BuildComposerEvent.OpenUrlEditor)
        state = reducer.reduce(state, BuildComposerEvent.UrlDraftChanged("https://raw.githubusercontent.com/owner/repo/main/video.mp4"))
        state = reducer.reduce(state, BuildComposerEvent.SaveUrl)

        assertEquals(image, state.imageReference)
        assertNull(state.urlReference)
        assertEquals(
            BuildRequestValidationError.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED,
            (state.urlEditor as UrlEditorState.Editing).error,
        )

        state = reducer.reduce(state, BuildComposerEvent.RemoveImage)
        assertNull((state.urlEditor as UrlEditorState.Editing).error)
        state = reducer.reduce(state, BuildComposerEvent.SaveUrl)
        assertEquals(BuildInput.UrlReference("https://raw.githubusercontent.com/owner/repo/main/video.mp4"), state.urlReference)
    }

    @Test
    fun videoUrlEditorValidatesAndStoresNormalizedReferenceThenCanRemoveIt() {
        var state = reducer.reduce(BuildComposerState(), BuildComposerEvent.OpenUrlEditor)
        state = reducer.reduce(state, BuildComposerEvent.UrlDraftChanged("  ftp://example.com/page"))
        state = reducer.reduce(state, BuildComposerEvent.SaveUrl)
        assertTrue(state.urlEditor is UrlEditorState.Editing)
        assertEquals(
            BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED,
            (state.urlEditor as UrlEditorState.Editing).error,
        )

        state = reducer.reduce(state, BuildComposerEvent.UrlDraftChanged(" https://raw.githubusercontent.com/owner/repo/main/video.mp4 "))
        state = reducer.reduce(state, BuildComposerEvent.SaveUrl)
        assertEquals(BuildInput.UrlReference("https://raw.githubusercontent.com/owner/repo/main/video.mp4"), state.urlReference)
        assertEquals(UrlEditorState.Closed, state.urlEditor)

        state = reducer.reduce(state, BuildComposerEvent.RemoveUrl)
        assertNull(state.urlReference)
    }
}
