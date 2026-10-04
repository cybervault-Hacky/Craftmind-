package com.craftmind.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.ai.AiBuildGenerator
import com.craftmind.app.domain.ai.AiErrorMapper
import com.craftmind.app.domain.buildplan.BuildRepositoryException
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val engine: AiBuildGenerator,
    private val localBuilds: LocalBuildRepository,
    private val reducer: BuildComposerReducer = BuildComposerReducer(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(BuildComposerState())
    val state: StateFlow<BuildComposerState> = mutableState.asStateFlow()
    private var generationJob: Job? = null

    fun dispatch(event: BuildComposerEvent) {
        when (event) {
            BuildComposerEvent.Generate -> startNewGeneration()
            BuildComposerEvent.Retry -> retryGeneration()
            BuildComposerEvent.CancelGeneration -> cancelGeneration()
            else -> {
                if (mutableState.value.generation is BuildGenerationState.Generating) {
                    cancelGeneration()
                }
                mutableState.update { current -> reducer.reduce(current, event) }
            }
        }
    }

    private fun startNewGeneration() {
        if (mutableState.value.generation is BuildGenerationState.Generating) return
        val prepared = reducer.reduce(mutableState.value, BuildComposerEvent.Generate)
        mutableState.value = prepared
        val request = (prepared.generation as? BuildGenerationState.Prepared)?.request ?: return
        launchGeneration(request)
    }

    private fun retryGeneration() {
        val failed = mutableState.value.generation as? BuildGenerationState.Failed ?: return
        if (!failed.retryable) return
        launchGeneration(failed.request)
    }

    private fun launchGeneration(request: com.craftmind.app.domain.buildplan.BuildRequest) {
        generationJob?.cancel()
        mutableState.update { it.copy(generation = BuildGenerationState.Generating(request)) }
        generationJob = viewModelScope.launch {
            try {
                val response = engine.generate(request)
                var localRecord: com.craftmind.app.domain.buildplan.LocalBuildRecord? = null
                var localSaveFailed = false
                try {
                    localRecord = localBuilds.save(response.plan, request)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: BuildRepositoryException) {
                    localSaveFailed = true
                } catch (_: Exception) {
                    localSaveFailed = true
                }
                mutableState.update { current ->
                    val active = current.generation as? BuildGenerationState.Generating
                    if (active?.request?.requestId != request.requestId) current else current.copy(
                        generation = BuildGenerationState.Ready(
                            request = request,
                            plan = response.plan,
                            localRecord = localRecord,
                            localSaveFailed = localSaveFailed,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                mutableState.update { current ->
                    val active = current.generation as? BuildGenerationState.Generating
                    if (active?.request?.requestId != request.requestId) current else current.copy(
                        generation = BuildGenerationState.Cancelled(request),
                    )
                }
            } catch (error: Exception) {
                val failure = AiErrorMapper.fromThrowable(error)
                mutableState.update { current ->
                    val active = current.generation as? BuildGenerationState.Generating
                    if (active?.request?.requestId != request.requestId) current else current.copy(
                        generation = BuildGenerationState.Failed(request, failure.code, failure.retryable),
                    )
                }
            }
        }
    }

    private fun cancelGeneration() {
        val current = mutableState.value.generation as? BuildGenerationState.Generating ?: return
        generationJob?.cancel()
        mutableState.update { state ->
            if ((state.generation as? BuildGenerationState.Generating)?.request?.requestId != current.request.requestId) {
                state
            } else {
                state.copy(generation = BuildGenerationState.Cancelled(current.request))
            }
        }
    }
}
