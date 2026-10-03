package com.craftmind.app.presentation.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.media.ImageValidationResult
import com.craftmind.app.domain.model.ReferenceInput
import com.craftmind.app.domain.validation.BuilderInputValidation
import com.craftmind.app.domain.validation.BuilderInputValidator
import com.craftmind.app.domain.validation.ReferenceUrlValidator
import com.craftmind.app.domain.validation.UrlValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BuilderViewModel(
    private val imageRepository: ImageReferenceRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BuilderUiState())
    val uiState: StateFlow<BuilderUiState> = _uiState.asStateFlow()

    private var imageInspectionJob: Job? = null
    private var imageSelectionSequence = 0L

    fun onPromptChanged(value: String) {
        val prompt = value.truncateAtCodePointBoundary(BuilderInputValidator.MAX_PROMPT_LENGTH)
        _uiState.update {
            it.copy(
                prompt = prompt,
                inputError = null,
                submission = BuilderSubmissionState.Idle,
            )
        }
    }

    fun onImageSelected(uri: String) {
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
                ImageValidationResult.Rejected(com.craftmind.app.domain.media.ImageValidationError.UNREADABLE)
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
            is UrlValidationResult.Valid -> _uiState.update {
                it.copy(
                    url = ReferenceInput.Url(result.normalizedUrl),
                    urlDraft = result.normalizedUrl,
                    isUrlEditorVisible = false,
                    urlError = null,
                    inputError = null,
                    submission = BuilderSubmissionState.Idle,
                )
            }
            is UrlValidationResult.Invalid -> _uiState.update {
                it.copy(urlError = result.reason, isUrlEditorVisible = true)
            }
        }
    }

    fun onRemoveUrlReference() {
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
        val current = _uiState.value
        if (current.isInspectingImage) {
            _uiState.update {
                it.copy(
                    inputError = BuilderInputError.ImageSelectionInProgress,
                    submission = BuilderSubmissionState.Idle,
                )
            }
            return
        }

        val unconfirmedUrlDraft = current.isUrlEditorVisible &&
            current.urlDraft.isNotBlank() &&
            current.urlDraft != current.url?.normalizedUrl
        if (current.urlError != null || unconfirmedUrlDraft) {
            _uiState.update {
                it.copy(inputError = BuilderInputError.UrlReferenceUnconfirmed, submission = BuilderSubmissionState.Idle)
            }
            return
        }

        when (BuilderInputValidator.validate(current.prompt, current.image, current.url)) {
            BuilderInputValidation.MissingInput -> _uiState.update {
                it.copy(inputError = BuilderInputError.MissingInput, submission = BuilderSubmissionState.Idle)
            }
            BuilderInputValidation.PromptTooLong -> _uiState.update {
                it.copy(inputError = BuilderInputError.PromptTooLong, submission = BuilderSubmissionState.Idle)
            }
            is BuilderInputValidation.Valid -> _uiState.update {
                it.copy(
                    inputError = null,
                    urlError = null,
                    submission = BuilderSubmissionState.AiNotConnected,
                )
            }
        }
    }

    fun dismissSubmissionNotice() {
        _uiState.update { it.copy(submission = BuilderSubmissionState.Idle) }
    }

    class Factory(
        private val imageRepository: ImageReferenceRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BuilderViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return BuilderViewModel(imageRepository) as T
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
