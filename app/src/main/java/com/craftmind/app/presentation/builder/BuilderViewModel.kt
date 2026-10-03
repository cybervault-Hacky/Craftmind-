package com.craftmind.app.presentation.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.ai.BuildGenerationEvent
import com.craftmind.app.domain.ai.BuildPlanGenerationUseCase
import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildErrorCode
import com.craftmind.app.domain.model.BuildRequest
import com.craftmind.app.domain.model.UrlReference
import com.craftmind.app.domain.validation.BuilderInputValidation
import com.craftmind.app.domain.validation.BuilderInputValidator
import com.craftmind.app.domain.validation.ReferenceUrlValidator
import com.craftmind.app.domain.validation.UrlValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BuilderViewModel(
    private val imageRepository: ImageReferenceRepository,
    private val generateBuildPlan: BuildPlanGenerationUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BuilderUiState())
    val uiState: StateFlow<BuilderUiState> = _uiState.asStateFlow()

    private var imageInspectionJob: Job? = null
    private var generationJob: Job? = null
    private var imageSelectionSequence = 0L
    private var lastBuildRequest: BuildRequest? = null

    fun onPromptChanged(value: String) {
        onEditableInputChanged()
        val prompt = value.truncateAtCodePointBoundary(BuilderInputValidator.MAX_PROMPT_LENGTH)
        _uiState.update {
            it.copy(prompt = prompt, inputError = null, submission = BuilderSubmissionState.Idle)
        }
    }

    fun onImageSelected(uri: String) {
        onEditableInputChanged()
        imageInspectionJob?.cancel()
        val sequence = ++imageSelectionSequence
        _uiState.update {
            it.copy(
                isInspectingImage = true,
                imageError = null,
                inputError = null,
                submission = BuilderSubmissionState.Idle,
            )
        }
        imageInspectionJob = viewModelScope.launch {
            val result = try {
                imageRepository.inspect(uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ImageValidationResult.Rejected(ImageValidationError.UNREADABLE)
            }
            if (sequence != imageSelectionSequence) return@launch
            _uiState.update { current ->
                when (result) {
                    is ImageValidationResult.Accepted -> current.copy(
                        image = result.image,
                        isInspectingImage = false,
                        imageError = null,
                    )
                    is ImageValidationResult.Rejected -> current.copy(
                        isInspectingImage = false,
                        imageError = result.reason,
                    )
                }
            }
        }
    }

    fun onRemoveImage() {
        onEditableInputChanged()
        imageSelectionSequence++
        imageInspectionJob?.cancel()
        imageInspectionJob = null
        _uiState.update {
            it.copy(
                image = null,
                isInspectingImage = false,
                imageError = null,
                inputError = null,
                submission = BuilderSubmissionState.Idle,
            )
        }
    }

    fun onUrlEditorVisibilityChanged(visible: Boolean) {
        onEditableInputChanged()
        _uiState.update { current ->
            current.copy(
                isUrlEditorVisible = visible,
                urlDraft = if (visible && current.url != null) current.url.normalizedUrl else current.urlDraft,
                urlError = null,
                inputError = null,
                submission = BuilderSubmissionState.Idle,
            )
        }
    }

    fun onUrlDraftChanged(value: String) {
        onEditableInputChanged()
        _uiState.update {
            it.copy(
                urlDraft = value.truncateAtCodePointBoundary(ReferenceUrlValidator.MAX_URL_LENGTH),
                urlError = null,
                inputError = null,
                submission = BuilderSubmissionState.Idle,
            )
        }
    }

    fun onAddUrlReference() {
        when (val result = ReferenceUrlValidator.validate(_uiState.value.urlDraft)) {
            is UrlValidationResult.Valid -> {
                onEditableInputChanged()
                _uiState.update {
                    it.copy(
                        url = UrlReference(result.normalizedUrl),
                        urlDraft = result.normalizedUrl,
                        isUrlEditorVisible = false,
                        urlError = null,
                        inputError = null,
                        submission = BuilderSubmissionState.Idle,
                    )
                }
            }
            is UrlValidationResult.Invalid -> _uiState.update {
                it.copy(urlError = result.reason, isUrlEditorVisible = true)
            }
        }
    }

    fun onRemoveUrlReference() {
        onEditableInputChanged()
        _uiState.update {
            it.copy(
                url = null,
                urlDraft = "",
                urlError = null,
                inputError = null,
                submission = BuilderSubmissionState.Idle,
            )
        }
    }

    fun onBuildPressed() {
        if (generationJob?.isActive == true) return
        val current = _uiState.value
        if (current.isInspectingImage) {
            _uiState.update {
                it.copy(inputError = BuilderInputError.ImageSelectionInProgress, submission = BuilderSubmissionState.Idle)
            }
            return
        }
        val unconfirmedUrlDraft = current.isUrlEditorVisible &&
            current.urlDraft.isNotBlank() && current.urlDraft != current.url?.normalizedUrl
        if (current.urlError != null || unconfirmedUrlDraft) {
            _uiState.update {
                it.copy(inputError = BuilderInputError.UrlReferenceUnconfirmed, submission = BuilderSubmissionState.Idle)
            }
            return
        }

        when (val validation = BuilderInputValidator.validate(current.prompt, current.image, current.url)) {
            BuilderInputValidation.MissingInput -> _uiState.update {
                it.copy(inputError = BuilderInputError.MissingInput, submission = BuilderSubmissionState.Idle)
            }
            BuilderInputValidation.PromptTooLong -> _uiState.update {
                it.copy(inputError = BuilderInputError.PromptTooLong, submission = BuilderSubmissionState.Idle)
            }
            is BuilderInputValidation.Valid -> {
                lastBuildRequest = validation.request
                startGeneration(validation.request)
            }
        }
    }

    fun cancelGeneration() {
        val job = generationJob ?: return
        if (!job.isActive) return
        generationJob = null
        job.cancel()
        _uiState.update { it.copy(submission = BuilderSubmissionState.Cancelled, inputError = null) }
    }

    fun retryGeneration() {
        val failure = _uiState.value.submission as? BuilderSubmissionState.Failed ?: return
        if (!failure.error.retryable) return
        val request = lastBuildRequest ?: return
        if (generationJob?.isActive == true) return
        startGeneration(request)
    }

    fun dismissSubmissionNotice() {
        if (generationJob?.isActive == true) return
        _uiState.update { it.copy(submission = BuilderSubmissionState.Idle) }
    }

    private fun startGeneration(request: BuildRequest) {
        generationJob?.cancel()
        _uiState.update { it.copy(inputError = null, submission = BuilderSubmissionState.Validating) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                generateBuildPlan(request).collect { event ->
                    _uiState.update { current ->
                        when (event) {
                            BuildGenerationEvent.ValidatingRequest -> current.copy(submission = BuilderSubmissionState.Validating)
                            BuildGenerationEvent.Generating -> current.copy(submission = BuilderSubmissionState.Generating)
                            BuildGenerationEvent.ValidatingPlan -> current.copy(submission = BuilderSubmissionState.ValidatingPlan)
                            is BuildGenerationEvent.Failed -> current.copy(submission = BuilderSubmissionState.Failed(event.error))
                            is BuildGenerationEvent.Ready -> current.copy(
                                submission = BuilderSubmissionState.Ready(event.result),
                                buildHistory = listOf(event.result),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(submission = BuilderSubmissionState.Failed(BuildError(BuildErrorCode.UNKNOWN, retryable = true)))
                }
            } finally {
                if (generationJob === coroutineContext[Job]) generationJob = null
            }
        }
        generationJob = job
        job.start()
    }

    private fun onEditableInputChanged() {
        generationJob?.cancel()
        generationJob = null
        lastBuildRequest = null
    }

    class Factory(
        private val imageRepository: ImageReferenceRepository,
        private val generateBuildPlan: BuildPlanGenerationUseCase,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BuilderViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return BuilderViewModel(imageRepository, generateBuildPlan) as T
        }
    }
}

private fun String.truncateAtCodePointBoundary(maxLength: Int): String {
    if (length <= maxLength) return this
    var end = maxLength
    if (
        end > 0 && end < length &&
        Character.isHighSurrogate(this[end - 1]) && Character.isLowSurrogate(this[end])
    ) {
        end -= 1
    }
    return substring(0, end)
}
