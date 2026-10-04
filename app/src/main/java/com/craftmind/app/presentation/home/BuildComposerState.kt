package com.craftmind.app.presentation.home

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestDraft
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidationResult
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.UrlValidationResult
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import java.util.Locale

data class BuildComposerState(
    val prompt: String = "",
    val imageReference: BuildInput.ImageReference? = null,
    val urlReference: BuildInput.UrlReference? = null,
    val urlEditor: UrlEditorState = UrlEditorState.Closed,
    val imageError: BuildRequestValidationError? = null,
    val generation: BuildGenerationState = BuildGenerationState.Idle,
) {
    val hasRequestContent: Boolean
        get() = prompt.isNotBlank() || imageReference != null || urlReference != null
}

sealed interface UrlEditorState {
    data object Closed : UrlEditorState
    data class Editing(val draft: String, val error: BuildRequestValidationError? = null) : UrlEditorState
}

sealed interface BuildGenerationState {
    data object Idle : BuildGenerationState
    data class ValidationBlocked(val error: BuildRequestValidationError) : BuildGenerationState
    data class Prepared(val request: BuildRequest) : BuildGenerationState
    data class Generating(val request: BuildRequest) : BuildGenerationState
    data class Failed(val request: BuildRequest, val code: AiErrorCode, val retryable: Boolean) : BuildGenerationState
    data class Cancelled(val request: BuildRequest) : BuildGenerationState
    data class Ready(
        val request: BuildRequest,
        val plan: ValidatedBuildPlan,
        val localRecord: LocalBuildRecord?,
        val localSaveFailed: Boolean,
    ) : BuildGenerationState
}

sealed interface BuildComposerEvent {
    data class PromptChanged(val value: String) : BuildComposerEvent
    data class ImageSelected(val reference: BuildInput.ImageReference) : BuildComposerEvent
    data object RemoveImage : BuildComposerEvent
    data object OpenUrlEditor : BuildComposerEvent
    data class UrlDraftChanged(val value: String) : BuildComposerEvent
    data object SaveUrl : BuildComposerEvent
    data object CloseUrlEditor : BuildComposerEvent
    data object RemoveUrl : BuildComposerEvent
    data object Generate : BuildComposerEvent
    data object Retry : BuildComposerEvent
    data object CancelGeneration : BuildComposerEvent
    data object DismissGenerationNotice : BuildComposerEvent
}

/** Pure input reducer; the ViewModel alone starts or cancels real provider work. */
class BuildComposerReducer(
    private val validator: BuildRequestValidator = BuildRequestValidator(),
) {
    fun reduce(state: BuildComposerState, event: BuildComposerEvent): BuildComposerState = when (event) {
        is BuildComposerEvent.PromptChanged -> state.copy(prompt = event.value, generation = BuildGenerationState.Idle)

        is BuildComposerEvent.ImageSelected -> {
            val error = validator.validateImage(event.reference)
            if (error == null) {
                state.copy(
                    imageReference = event.reference.copy(
                        mediaType = event.reference.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT),
                    ),
                    imageError = null,
                    generation = BuildGenerationState.Idle,
                )
            } else {
                state.copy(imageError = error, generation = BuildGenerationState.Idle)
            }
        }

        BuildComposerEvent.RemoveImage -> state.copy(
            imageReference = null,
            imageError = null,
            generation = BuildGenerationState.Idle,
        )

        BuildComposerEvent.OpenUrlEditor -> state.copy(
            urlEditor = UrlEditorState.Editing(state.urlReference?.url.orEmpty()),
            generation = BuildGenerationState.Idle,
        )

        is BuildComposerEvent.UrlDraftChanged -> {
            val editing = state.urlEditor as? UrlEditorState.Editing
            if (editing == null) state else state.copy(
                urlEditor = editing.copy(draft = event.value, error = null),
                generation = BuildGenerationState.Idle,
            )
        }

        BuildComposerEvent.SaveUrl -> {
            val editing = state.urlEditor as? UrlEditorState.Editing
            if (editing == null) state else when (val result = validator.validateUrl(editing.draft)) {
                is UrlValidationResult.Valid -> state.copy(
                    urlReference = BuildInput.UrlReference(result.normalizedUrl),
                    urlEditor = UrlEditorState.Closed,
                    generation = BuildGenerationState.Idle,
                )
                is UrlValidationResult.Invalid -> state.copy(urlEditor = editing.copy(error = result.error))
            }
        }

        BuildComposerEvent.CloseUrlEditor -> state.copy(urlEditor = UrlEditorState.Closed)
        BuildComposerEvent.RemoveUrl -> state.copy(
            urlReference = null,
            urlEditor = UrlEditorState.Closed,
            generation = BuildGenerationState.Idle,
        )
        BuildComposerEvent.Generate -> prepare(state)
        BuildComposerEvent.Retry,
        BuildComposerEvent.CancelGeneration -> state
        BuildComposerEvent.DismissGenerationNotice -> state.copy(generation = BuildGenerationState.Idle)
    }

    private fun prepare(state: BuildComposerState): BuildComposerState {
        state.imageError?.let { return state.copy(generation = BuildGenerationState.ValidationBlocked(it)) }
        val urlError = (state.urlEditor as? UrlEditorState.Editing)?.error
        if (urlError != null) return state.copy(generation = BuildGenerationState.ValidationBlocked(urlError))

        return when (
            val result = validator.create(
                BuildRequestDraft(
                    prompt = state.prompt,
                    imageReference = state.imageReference,
                    urlReference = state.urlReference,
                ),
            )
        ) {
            is BuildRequestValidationResult.Invalid -> state.copy(
                generation = BuildGenerationState.ValidationBlocked(result.error),
            )
            is BuildRequestValidationResult.Valid -> state.copy(generation = BuildGenerationState.Prepared(result.request))
        }
    }
}
