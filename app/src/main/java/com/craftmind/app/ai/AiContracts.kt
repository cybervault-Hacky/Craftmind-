package com.craftmind.app.ai

import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildPlan
import com.craftmind.app.domain.model.BuildProgress
import com.craftmind.app.domain.model.BuildRequest
import kotlinx.coroutines.flow.Flow

@JvmInline
value class ProviderId(val value: String)

sealed interface ProviderPlanEvent {
    data class Progress(val value: BuildProgress) : ProviderPlanEvent
    data class PlanReady(val plan: BuildPlan) : ProviderPlanEvent
    data class Failed(val error: BuildError) : ProviderPlanEvent
}

/** Implemented by provider adapters in a later phase; no network provider ships in Phase 1. */
interface AiProvider {
    val id: ProviderId
    fun generatePlan(request: BuildRequest): Flow<ProviderPlanEvent>
}

sealed interface BuildPlanningEvent {
    data class Progress(val value: BuildProgress) : BuildPlanningEvent
    data class Completed(val plan: BuildPlan) : BuildPlanningEvent
    data class Failed(val error: BuildError) : BuildPlanningEvent
    data object Cancelled : BuildPlanningEvent
}

/** Orchestrates provider selection, reference preparation, validation, and streamed planning. */
interface BuildPlanner {
    fun plan(request: BuildRequest): Flow<BuildPlanningEvent>
}

@JvmInline
value class CredentialAlias(val value: String)

/** Opaque in-memory secret wrapper; implementations must never log its contents. */
class SecretValue private constructor(private val characters: CharArray) {
    companion object {
        fun copyOf(value: CharArray): SecretValue = SecretValue(value.copyOf())
    }

    internal fun <T> use(block: (CharArray) -> T): T = try {
        block(characters)
    } finally {
        characters.fill('\u0000')
    }

    override fun toString(): String = "SecretValue([REDACTED])"
}

/** Future secure-storage port. Phase 1 intentionally has no implementation. */
interface CredentialStore {
    suspend fun put(alias: CredentialAlias, value: SecretValue)
    suspend fun get(alias: CredentialAlias): SecretValue?
    suspend fun remove(alias: CredentialAlias)
}
