package com.craftmind.app.domain.ai

import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildResult
import com.craftmind.app.domain.model.BuildRequest
import kotlinx.coroutines.flow.Flow

sealed interface BuildGenerationEvent {
    data object ValidatingRequest : BuildGenerationEvent
    data object Generating : BuildGenerationEvent
    data object ValidatingPlan : BuildGenerationEvent
    data class Ready(val result: BuildResult) : BuildGenerationEvent
    data class Failed(val error: BuildError) : BuildGenerationEvent
}

/** Cold, cancellable generation boundary consumed by the builder ViewModel. */
fun interface BuildPlanGenerationUseCase {
    operator fun invoke(request: BuildRequest): Flow<BuildGenerationEvent>
}
