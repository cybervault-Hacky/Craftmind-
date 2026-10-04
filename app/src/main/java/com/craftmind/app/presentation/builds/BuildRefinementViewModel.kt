package com.craftmind.app.presentation.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.ai.AiBuildRefiner
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiErrorMapper
import com.craftmind.app.domain.buildplan.BuildEditRequestValidator
import com.craftmind.app.domain.buildplan.BuildEditValidationResult
import com.craftmind.app.domain.buildplan.BuildRepositoryError
import com.craftmind.app.domain.buildplan.BuildRepositoryException
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Candidate edits are isolated until explicit acceptance; cancellation never writes history. */
class BuildRefinementViewModel(
    private val refiner: AiBuildRefiner,
    private val history: LocalBuildRepository,
    private val requestValidator: BuildEditRequestValidator = BuildEditRequestValidator(),
) : ViewModel() {
    private val mutableState = MutableStateFlow<BuildRefinementState>(BuildRefinementState.Idle)
    val state: StateFlow<BuildRefinementState> = mutableState.asStateFlow()
    private var actionJob: Job? = null

    fun dispatch(event: BuildRefinementEvent) {
        when (event) {
            is BuildRefinementEvent.Refine -> beginValidation(event.base, event.instruction)
            BuildRefinementEvent.Cancel -> cancelCandidate()
            BuildRefinementEvent.Retry -> retry()
            BuildRefinementEvent.Accept -> acceptCandidate()
            BuildRefinementEvent.Discard -> discardCandidate()
            is BuildRefinementEvent.RestoreVersion -> restore(event.current, event.targetVersion)
            BuildRefinementEvent.DismissResult -> dismissResult()
        }
    }

    private fun beginValidation(base: LocalBuildRecord, instruction: String) {
        if (isBusy(mutableState.value)) return
        actionJob?.cancel()
        mutableState.value = BuildRefinementState.ValidatingRequest(base, instruction)
        when (val result = requestValidator.create(base, instruction)) {
            is BuildEditValidationResult.Invalid -> mutableState.value = BuildRefinementState.ValidationFailed(
                base = base,
                instruction = instruction,
                error = result.reason,
            )
            is BuildEditValidationResult.Valid -> startGeneration(base, result.request)
        }
    }

    private fun startGeneration(base: LocalBuildRecord, request: com.craftmind.app.domain.buildplan.BuildEditRequest) {
        actionJob?.cancel()
        mutableState.value = BuildRefinementState.Generating(base, request)
        actionJob = viewModelScope.launch {
            try {
                val response = refiner.refine(request)
                mutableState.update { current ->
                    val active = current as? BuildRefinementState.Generating
                    if (active == null || active.base.recordId != base.recordId || active.request != request) current else {
                        BuildRefinementState.ReadyForReview(base, request, response.plan, response.diff, response.usage)
                    }
                }
            } catch (error: CancellationException) {
                mutableState.update { current ->
                    val active = current as? BuildRefinementState.Generating
                    if (active?.base?.recordId != base.recordId) current else BuildRefinementState.Cancelled(base, request)
                }
            } catch (error: Exception) {
                val failure = AiErrorMapper.fromThrowable(error)
                mutableState.update { current ->
                    val active = current as? BuildRefinementState.Generating
                    if (active?.base?.recordId != base.recordId) current else BuildRefinementState.Failed(
                        base = base,
                        request = request,
                        code = failure.code,
                        retryable = failure.retryable,
                        stage = RefinementFailureStage.PROVIDER,
                    )
                }
            }
        }
    }

    private fun cancelCandidate() {
        when (val current = mutableState.value) {
            is BuildRefinementState.Generating -> {
                actionJob?.cancel()
                mutableState.value = BuildRefinementState.Cancelled(current.base, current.request)
            }
            is BuildRefinementState.ReadyForReview,
            is BuildRefinementState.ValidationFailed,
            is BuildRefinementState.Cancelled,
            is BuildRefinementState.Failed -> {
                actionJob?.cancel()
                mutableState.value = BuildRefinementState.Idle
            }
            else -> Unit
        }
    }

    private fun retry() {
        val failed = mutableState.value as? BuildRefinementState.Failed ?: return
        if (failed.stage != RefinementFailureStage.PROVIDER || !failed.retryable) return
        startGeneration(failed.base, failed.request)
    }

    private fun acceptCandidate() {
        val ready = mutableState.value as? BuildRefinementState.ReadyForReview ?: return
        mutableState.value = BuildRefinementState.Accepting(
            ready.base,
            ready.request,
            ready.candidate,
            ready.diff,
        )
        actionJob = viewModelScope.launch {
            try {
                val record = history.appendRefinement(
                    baseRecordId = ready.base.recordId,
                    plan = ready.candidate,
                    request = ready.request,
                    diff = ready.diff,
                )
                mutableState.value = BuildRefinementState.Accepted(record)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BuildRepositoryException) {
                mutableState.value = BuildRefinementState.Failed(
                    base = ready.base,
                    request = ready.request,
                    code = repositoryError(error.error),
                    retryable = false,
                    stage = RefinementFailureStage.PERSISTENCE,
                )
            } catch (_: Exception) {
                mutableState.value = BuildRefinementState.Failed(
                    base = ready.base,
                    request = ready.request,
                    code = AiErrorCode.BUILD_HISTORY_FAILURE,
                    retryable = false,
                    stage = RefinementFailureStage.PERSISTENCE,
                )
            }
        }
    }

    private fun discardCandidate() {
        when (mutableState.value) {
            is BuildRefinementState.ReadyForReview,
            is BuildRefinementState.ValidationFailed,
            is BuildRefinementState.Cancelled,
            is BuildRefinementState.Failed,
            is BuildRefinementState.Accepted,
            is BuildRefinementState.Reverted,
            is BuildRefinementState.HistoryFailure -> mutableState.value = BuildRefinementState.Idle
            else -> Unit
        }
    }

    private fun restore(current: LocalBuildRecord, targetVersion: Int) {
        if (isBusy(mutableState.value) || targetVersion < 1 || targetVersion >= current.version) return
        mutableState.value = BuildRefinementState.Reverting(current, targetVersion)
        actionJob = viewModelScope.launch {
            try {
                val record = history.revertTo(
                    buildId = current.buildId,
                    targetVersion = targetVersion,
                    expectedCurrentRecordId = current.recordId,
                )
                mutableState.value = BuildRefinementState.Reverted(record)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BuildRepositoryException) {
                mutableState.value = BuildRefinementState.HistoryFailure(current, repositoryError(error.error))
            } catch (_: Exception) {
                mutableState.value = BuildRefinementState.HistoryFailure(current, AiErrorCode.BUILD_HISTORY_FAILURE)
            }
        }
    }

    private fun dismissResult() {
        if (mutableState.value !is BuildRefinementState.Generating &&
            mutableState.value !is BuildRefinementState.Accepting &&
            mutableState.value !is BuildRefinementState.Reverting
        ) {
            actionJob?.cancel()
            mutableState.value = BuildRefinementState.Idle
        }
    }

    private fun isBusy(value: BuildRefinementState): Boolean =
        value is BuildRefinementState.Generating || value is BuildRefinementState.Accepting || value is BuildRefinementState.Reverting

    private fun repositoryError(error: BuildRepositoryError): AiErrorCode = when (error) {
        BuildRepositoryError.STALE_VERSION -> AiErrorCode.BUILD_VERSION_CONFLICT
        BuildRepositoryError.HISTORY_LIMIT_REACHED -> AiErrorCode.BUILD_HISTORY_LIMIT_REACHED
        BuildRepositoryError.NOT_FOUND -> AiErrorCode.BUILD_VERSION_CONFLICT
        BuildRepositoryError.INVALID_RECORD,
        BuildRepositoryError.STORAGE_FAILURE -> AiErrorCode.BUILD_HISTORY_FAILURE
    }
}
