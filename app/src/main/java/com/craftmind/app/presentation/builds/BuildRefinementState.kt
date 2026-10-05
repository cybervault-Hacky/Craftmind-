package com.craftmind.app.presentation.builds

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiUsage
import com.craftmind.app.domain.buildplan.BuildDiff
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildEditValidationError
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan

enum class RefinementFailureStage {
    PROVIDER,
    PERSISTENCE,
}

sealed interface BuildRefinementState {
    data object Idle : BuildRefinementState
    data class ValidatingRequest(val base: LocalBuildRecord, val instruction: String) : BuildRefinementState
    data class ValidationFailed(
        val base: LocalBuildRecord,
        val instruction: String,
        val error: BuildEditValidationError,
    ) : BuildRefinementState
    data class Generating(val base: LocalBuildRecord, val request: BuildEditRequest) : BuildRefinementState
    data class ReadyForReview(
        val base: LocalBuildRecord,
        val request: BuildEditRequest,
        val candidate: ValidatedBuildPlan,
        val diff: BuildDiff,
        val usage: AiUsage?,
    ) : BuildRefinementState
    data class Accepting(
        val base: LocalBuildRecord,
        val request: BuildEditRequest,
        val candidate: ValidatedBuildPlan,
        val diff: BuildDiff,
    ) : BuildRefinementState
    data class Failed(
        val base: LocalBuildRecord,
        val request: BuildEditRequest,
        val code: AiErrorCode,
        val retryable: Boolean,
        val stage: RefinementFailureStage,
    ) : BuildRefinementState
    data class Cancelled(val base: LocalBuildRecord, val request: BuildEditRequest) : BuildRefinementState
    data class Accepted(val record: LocalBuildRecord) : BuildRefinementState
    data class Reverting(val current: LocalBuildRecord, val targetVersion: Int) : BuildRefinementState
    data class Reverted(val record: LocalBuildRecord) : BuildRefinementState
    data class HistoryFailure(val current: LocalBuildRecord, val code: AiErrorCode) : BuildRefinementState
}

sealed interface BuildRefinementEvent {
    data class Refine(val base: LocalBuildRecord, val instruction: String) : BuildRefinementEvent
    data object Cancel : BuildRefinementEvent
    data object Retry : BuildRefinementEvent
    data object Accept : BuildRefinementEvent
    data object Discard : BuildRefinementEvent
    data class RestoreVersion(val current: LocalBuildRecord, val targetVersion: Int) : BuildRefinementEvent
    data object DismissResult : BuildRefinementEvent
}
