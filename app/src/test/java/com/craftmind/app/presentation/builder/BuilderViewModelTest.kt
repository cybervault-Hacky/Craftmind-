package com.craftmind.app.presentation.builder

import com.craftmind.app.domain.ai.BuildGenerationEvent
import com.craftmind.app.domain.ai.BuildPlanGenerationUseCase
import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.media.ImageThumbnail
import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildErrorCode
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.ImageReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuilderViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = MutableImageRepository()

    @Test
    fun promptInputIsBoundedAndRemainsLocalInViewState() {
        val viewModel = newViewModel()
        val overLimit = "x".repeat(2_050)

        viewModel.onPromptChanged(overLimit)

        assertEquals(2_000, viewModel.uiState.value.prompt.length)
        assertNull(viewModel.uiState.value.image)
        assertNull(viewModel.uiState.value.url)
    }

    @Test
    fun promptTruncationDoesNotSplitASurrogatePair() {
        val viewModel = newViewModel()

        viewModel.onPromptChanged("a".repeat(1_999) + "😀" + "b")

        assertEquals(1_999, viewModel.uiState.value.prompt.length)
        assertFalse(Character.isHighSurrogate(viewModel.uiState.value.prompt.last()))
    }

    @Test
    fun emptyBuildShowsValidationAndConfiguredTextRequestShowsTypedMissingProviderError() {
        val viewModel = newViewModel()

        viewModel.onBuildPressed()
        assertEquals(BuilderInputError.MissingInput, viewModel.uiState.value.inputError)
        assertEquals(BuilderSubmissionState.Idle, viewModel.uiState.value.submission)

        viewModel.onPromptChanged("A small stone bridge")
        viewModel.onBuildPressed()
        assertNull(viewModel.uiState.value.inputError)
        assertEquals(
            BuilderSubmissionState.Failed(BuildError(BuildErrorCode.MISSING_PROVIDER)),
            viewModel.uiState.value.submission,
        )
    }

    @Test
    fun urlCanBeAddedReplacedAndRemovedThroughViewModelState() {
        val viewModel = newViewModel()

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
        val viewModel = newViewModel()

        viewModel.onUrlEditorVisibilityChanged(true)
        viewModel.onUrlDraftChanged("javascript:alert(1)")
        viewModel.onAddUrlReference()

        assertTrue(viewModel.uiState.value.isUrlEditorVisible)
        assertEquals(com.craftmind.app.domain.validation.UrlValidationError.UNSUPPORTED_SCHEME, viewModel.uiState.value.urlError)
        assertNull(viewModel.uiState.value.url)
    }

    @Test
    fun unconfirmedUrlDraftMustBeAddedOrCancelledBeforeBuild() {
        val viewModel = newViewModel()
        viewModel.onPromptChanged("A small bridge")
        viewModel.onUrlEditorVisibilityChanged(true)
        viewModel.onUrlDraftChanged("not a url yet")

        viewModel.onBuildPressed()

        assertEquals(BuilderInputError.UrlReferenceUnconfirmed, viewModel.uiState.value.inputError)
        assertEquals(BuilderSubmissionState.Idle, viewModel.uiState.value.submission)

        viewModel.onUrlEditorVisibilityChanged(false)
        viewModel.onBuildPressed()
        assertEquals(
            BuilderSubmissionState.Failed(BuildError(BuildErrorCode.UNSUPPORTED_REFERENCE)),
            viewModel.uiState.value.submission,
        )
    }

    @Test
    fun imageCanBeSelectedReplacedAndRemoved() {
        val viewModel = newViewModel()
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
        val viewModel = newViewModel()
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
        val viewModel = newViewModel()
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
    fun imageOnlyRequestIsRejectedAsUnsupportedInsteadOfBeingSilentlyOmitted() {
        val viewModel = newViewModel()
        repository.nextResult = ImageValidationResult.Accepted(image("content://photo/one", "one.jpg"))
        viewModel.onImageSelected("content://photo/one")

        viewModel.onBuildPressed()

        assertEquals(
            BuilderSubmissionState.Failed(BuildError(BuildErrorCode.UNSUPPORTED_REFERENCE)),
            viewModel.uiState.value.submission,
        )
        assertNull(viewModel.uiState.value.inputError)
    }

    @Test
    fun retryIsOnlyAvailableAfterAnExplicitRetryableFailure() {
        var attempts = 0
        val useCase = BuildPlanGenerationUseCase {
            attempts++
            flow {
                emit(
                    BuildGenerationEvent.Failed(
                        BuildError(BuildErrorCode.PROVIDER_UNAVAILABLE, retryable = attempts == 1),
                    ),
                )
            }
        }
        val viewModel = newViewModel(useCase)
        viewModel.onPromptChanged("A cabin")
        viewModel.onBuildPressed()
        assertEquals(1, attempts)

        viewModel.retryGeneration()
        assertEquals(2, attempts)
        assertFalse((viewModel.uiState.value.submission as BuilderSubmissionState.Failed).error.retryable)
        viewModel.retryGeneration()
        assertEquals(2, attempts)
    }

    @Test
    fun generationCanBeCancelledAndDoesNotFabricateCompletion() {
        val useCase = BuildPlanGenerationUseCase {
            flow {
                emit(BuildGenerationEvent.Generating)
                awaitCancellation()
            }
        }
        val viewModel = newViewModel(useCase)
        viewModel.onPromptChanged("A cabin")
        viewModel.onBuildPressed()
        assertEquals(BuilderSubmissionState.Generating, viewModel.uiState.value.submission)

        viewModel.cancelGeneration()

        assertEquals(BuilderSubmissionState.Cancelled, viewModel.uiState.value.submission)
        assertTrue(viewModel.uiState.value.buildHistory.isEmpty())
    }

    private fun newViewModel(
        useCase: BuildPlanGenerationUseCase = BuildPlanGenerationUseCase { request ->
            flow {
                val error = if (request.imageReferences.isNotEmpty() || request.urlReferences.isNotEmpty()) {
                    BuildError(BuildErrorCode.UNSUPPORTED_REFERENCE)
                } else {
                    BuildError(BuildErrorCode.MISSING_PROVIDER)
                }
                emit(BuildGenerationEvent.Failed(error))
            }
        },
    ) = BuilderViewModel(repository, useCase)

    private fun image(
        uri: String,
        name: String,
        mime: String = "image/jpeg",
    ) = ImageReference(
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
