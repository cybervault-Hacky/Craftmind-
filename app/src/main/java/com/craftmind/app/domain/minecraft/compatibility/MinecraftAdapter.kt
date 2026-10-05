package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot

/** Runtime-specific policy plus a thin mapping onto CraftMind's existing secure bridge contract. */
interface MinecraftAdapter {
    val adapterId: MinecraftAdapterId
    val supportedRuntimeDescriptors: List<SupportedMinecraftRuntimeDescriptor>
    val capabilities: Set<MinecraftCapability>

    /** Returns null unless this adapter exactly recognizes the runtime profile. */
    fun compatibilityCheck(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult?

    suspend fun preflight(
        bridge: MinecraftBridgePairingRepository,
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionPreview

    suspend fun execute(
        bridge: MinecraftBridgePairingRepository,
        preview: MinecraftExecutionPreview,
    ): MinecraftExecutionSnapshot

    suspend fun cancel(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftCancellationResult

    suspend fun status(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftExecutionQueryResult
}

sealed interface MinecraftAdapterRegistrationResult {
    data object Registered : MinecraftAdapterRegistrationResult
    data class DuplicateAdapterId(val adapterId: MinecraftAdapterId) : MinecraftAdapterRegistrationResult
    data class DuplicateRuntimeProfile(val adapterId: MinecraftAdapterId, val existingAdapterId: MinecraftAdapterId) :
        MinecraftAdapterRegistrationResult
}

/** A small explicit registry; ambiguous and duplicate profiles are rejected rather than first-match selected. */
class MinecraftAdapterRegistry {
    private val adapters = linkedMapOf<MinecraftAdapterId, MinecraftAdapter>()

    @Synchronized
    fun register(adapter: MinecraftAdapter): MinecraftAdapterRegistrationResult {
        if (adapter.adapterId in adapters) {
            return MinecraftAdapterRegistrationResult.DuplicateAdapterId(adapter.adapterId)
        }
        val overlap = adapters.values.firstOrNull { existing ->
            existing.supportedRuntimeDescriptors.any { oldProfile ->
                adapter.supportedRuntimeDescriptors.any(oldProfile::hasSameRuntimeIdentity)
            }
        }
        if (overlap != null) {
            return MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(adapter.adapterId, overlap.adapterId)
        }
        adapters[adapter.adapterId] = adapter
        return MinecraftAdapterRegistrationResult.Registered
    }

    @Synchronized
    fun adapter(adapterId: MinecraftAdapterId?): MinecraftAdapter? = adapterId?.let(adapters::get)

    @Synchronized
    fun allAdapters(): List<MinecraftAdapter> = adapters.values.toList()

    @Synchronized
    internal fun compatibilityChecks(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): List<MinecraftCompatibilityResult> = adapters.values.mapNotNull { adapter ->
        adapter.compatibilityCheck(runtime, requirements)
    }
}

/** The only production adapter registered in Phase 9. Other editions/loaders remain explicit extension points. */
object DefaultMinecraftCompatibility {
    internal val registry: MinecraftAdapterRegistry by lazy {
        MinecraftAdapterRegistry().apply {
            when (val result = register(JavaFabric1201Adapter())) {
                MinecraftAdapterRegistrationResult.Registered -> Unit
                else -> error("Default Minecraft adapter registry is invalid: $result")
            }
        }
    }

    val resolver: MinecraftCompatibilityResolver by lazy { MinecraftCompatibilityResolver(registry) }
}
