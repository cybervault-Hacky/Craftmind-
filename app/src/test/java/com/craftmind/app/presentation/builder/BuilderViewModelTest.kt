package com.craftmind.app.presentation.builder

import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.media.ImageThumbnail
import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.model.ReferenceInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuilderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = MutableImageRepository()

    @Test
    fun promptInputIsBoundedAndRemainsLocalInViewState() {
        val viewModel = BuilderViewModel(repository)
        val overLimit = "x".repeat(2_050)

        viewModel.onPromptChanged(overLimit)

        assertEquals(2_000, viewModel.uiState.value.prompt.length)
        assertNull(viewModel.uiState.value.image)
        assertNull(viewModel.uiState.value.url)
    }

    @Test
    fun promptTruncationDoesNotSplitASurrogatePair() {
        val viewModel = BuilderViewModel(repository)

        viewModel.onPromptChanged("a".repeat(1_999) + "😀" + "b")

        assertEquals(1_999, viewModel.uiState.value.prompt.length)
        assertFalse(Character.isHighSurrogate(viewModel.uiState.value.prompt.last()))
    }

    @Test
    fun emptyBuildShowsValidationAndValidTextShowsHonestUnavailableState() {
        val viewModel = BuilderViewModel(repository)

        viewModel.onBuildPressed()
        assertEquals(BuilderInputError.MissingInput, viewModel.uiState.value.inputError)
        assertEquals(BuilderSubmissionState.Idle, viewModel.uiState.value.submission)

        viewModel.onPromptChanged("A small stone bridge")
        viewModel.onBuildPressed()
        assertNull(viewModel.uiState.value.inputError)
        assertEquals(BuilderSubmissionState.AiNotConnected, viewModel.uiState.value.submission)
    }

    @Test
    fun urlCanBeAddedReplacedAndRemovedThroughViewModelState() {
        val viewModel = BuilderViewModel(repository)

        viewModel.onUrlEditorVisibilityChanged(true)
        viewModel.onUrlDraftChanged("example.com/first")
        viewModel.onAddUrlReference()
        assertEquals("https://example.com/first", viewModel.uiState.value.url?.normalizedUrl)
        assertFalse(viewModel.uiState.value.isUrlEditorVisible)

        viewModel.onUrlEditorVisibilityChanged(true)
        viewModel.onUrlDraftChanged("https://example.org/second")
        viewModel.onAddUrlReference()
        assertEquals("https://example.org/second", viewModel.uiState.value.url?.normalizedUrl)

        viewModel.onRemoveUrlReference()
        assertNull(viewModel.uiState.value.url)
        assertEquals("", viewModel.uiState.value.urlDraft)
    }

    @Test
    fun invalidUrlStaysInEditorAndExposesAValidationReason() {
        val viewModel = BuilderViewModel(repository)

        viewModel.onUrlEditorVisibilityChanged(true)
        viewModel.onUrlDraftChanged("javascript:alert(1)")
        viewModel.onAddUrlReference()

        assertTrue(viewModel.uiState.value.isUrlEditorVisible)
        assertEquals(com.craftmind.app.domain.validation.UrlValidationError.UNSUPPORTED_SCHEME, viewModel.uiState.value.urlError)
        assertNull(viewModel.uiState.value.url)
    }

    @Test
    fun unconfirmedUrlDraftMustBeAddedOrCancelledBeforeBuild() {
        val viewModel = BuilderViewModel(repository)
        viewModel.onPromptChanged("A small bridge")
        viewModel.onUrlEditorVisibilityChanged(true)
        viewModel.onUrlDraftChanged("not a url yet")

        viewModel.onBuildPressed()

        assertEquals(BuilderInputError.UrlReferenceUnconfirmed, viewModel.uiState.value.inputError)
        assertEquals(BuilderSubmissionState.Idle, viewModel.uiState.value.submission)

        viewModel.onUrlEditorVisibilityChanged(false)
        viewModel.onBuildPressed()
        assertEquals(BuilderSubmissionState.AiNotConnected, viewModel.uiState.value.submission)
    }

    @Test
    fun imageCanBeSelectedReplacedAndRemoved() {
        val viewModel = BuilderViewModel(repository)
        repository.nextResult = ImageValidationResult.Accepted(image("content://photo/one", "one.jpg"))
        viewModel.onImageSelected("content://photo/one")
        assertEquals("one.jpg", viewModel.uiState.value.image?.displayName)
        assertFalse(viewModel.uiState.value.isInspectingImage)

        repository.nextResult = ImageValidationResult.Accepted(image("content://photo/two", "two.png", "image/png"))
        viewModel.onImageSelected("content://photo/two")
        assertEquals("two.png", viewModel.uiState.value.image?.displayName)

        viewModel.onRemoveImage()
        assertNull(viewModel.uiState.value.image)
        assertFalse(viewModel.uiState.value.isInspectingImage)
    }

    @Test
    fun rejectedReplacementPreservesTheLastValidAttachmentAndShowsError() {
        val viewModel = BuilderViewModel(repository)
        repository.nextResult = ImageValidationResult.Accepted(image("content://photo/one", "one.jpg"))
        viewModel.onImageSelected("content://photo/one")

        repository.nextResult = ImageValidationResult.Rejected(ImageValidationError.FILE_TOO_LARGE)
        viewModel.onImageSelected("content://photo/large")

        assertEquals("one.jpg", viewModel.uiState.value.image?.displayName)
        assertEquals(ImageValidationError.FILE_TOO_LARGE, viewModel.uiState.value.imageError)
        assertFalse(viewModel.uiState.value.isInspectingImage)
    }

    @Test
    fun buildIsBlockedWhileImageMetadataIsBeingChecked() {
        val viewModel = BuilderViewModel(repository)
        repository.pendingInspection = CompletableDeferred()

        viewModel.onImageSelected("content://photo/pending")
        assertTrue(viewModel.uiState.value.isInspectingImage)

        viewModel.onBuildPressed()
        assertEquals(BuilderInputError.ImageSelectionInProgress, viewModel.uiState.value.inputError)
        assertEquals(BuilderSubmissionState.Idle, viewModel.uiState.value.submission)

        viewModel.onRemoveImage()
        assertFalse(viewModel.uiState.value.isInspectingImage)
    }

    @Test
    fun imageOnlyRequestIsAcceptedButNeverCreatesAPlan() {
        val viewModel = BuilderViewModel(repository)
        repository.nextResult = ImageValidationResult.Accepted(image("content://photo/one", "one.jpg"))
        viewModel.onImageSelected("content://photo/one")

        viewModel.onBuildPressed()

        assertEquals(BuilderSubmissionState.AiNotConnected, viewModel.uiState.value.submission)
        assertNull(viewModel.uiState.value.inputError)
    }

    private fun image(
        uri: String,
        name: String,
        mime: String = "image/jpeg",
    ) = ReferenceInput.Image(
        uri = uri,
        mimeType = mime,
        displayName = name,
        sizeBytes = 1024,
    )

    private class MutableImageRepository : ImageReferenceRepository {
        var nextResult: ImageValidationResult = ImageValidationResult.Rejected(ImageValidationError.UNREADABLE)
        var pendingInspection: CompletableDeferred<ImageValidationResult>? = null

        override suspend fun inspect(uri: String): ImageValidationResult =
            pendingInspection?.await() ?: nextResult

        override suspend fun loadThumbnail(uri: String, maxDimensionPixels: Int): ImageThumbnail? = null
    }
}
