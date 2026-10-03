package com.craftmind.app.presentation.builder

import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildProgress
import com.craftmind.app.domain.model.BuildResult
import com.craftmind.app.domain.model.ReferenceInput
import com.craftmind.app.domain.validation.UrlValidationError

sealed interface BuilderInputError {
    data object MissingInput : BuilderInputError
    data object PromptTooLong : BuilderInputError
    data object ImageSelectionInProgress : BuilderInputError
    data object UrlReferenceUnconfirmed : BuilderInputError
}

sealed interface BuilderSubmissionState {
    data object Idle : BuilderSubmissionState
    data object AiNotConnected : BuilderSubmissionState
    data class InProgress(val progress: BuildProgress) : BuilderSubmissionState
    data class Completed(val result: BuildResult) : BuilderSubmissionState
    data class Failed(val error: BuildError) : BuilderSubmissionState
    data object Cancelled : BuilderSubmissionState
}

data class BuilderUiState(
    val prompt: String = "",
    val image: ReferenceInput.Image? = null,
    val url: ReferenceInput.Url? = null,
    val urlDraft: String = "",
    val isUrlEditorVisible: Boolean = false,
    val isInspectingImage: Boolean = false,
    val inputError: BuilderInputError? = null,
    val imageError: ImageValidationError? = null,
    val urlError: UrlValidationError? = null,
    val submission: BuilderSubmissionState = BuilderSubmissionState.Idle,
)
