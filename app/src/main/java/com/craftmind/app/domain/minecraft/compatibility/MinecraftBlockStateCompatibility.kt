package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits

/**
 * How a platform-neutral block/state representation was verified for one runtime target.
 *
 * A mapping is never inferred from a Minecraft version, a loader, or a nearby release. Only a mapping that was
 * checked against a real runtime of the target family may be declared in a shipped
 * [MinecraftTargetBlockStateCatalog]; everything else fails closed with `UNSUPPORTED_BLOCK` /
 * `UNSUPPORTED_BLOCK_STATE` instead of being approximated.
 */
enum class BlockStateMappingVerification(val displayName: String) {
    /** Declared but never checked. Rejected by the catalog; never applied. */
    NOT_VERIFIED("Not verified"),

    /** Checked only against published documentation. Informational; not accepted by a shipped catalog. */
    DOCUMENTED_ONLY("Documentation only"),

    /** Checked against a real Minecraft runtime of the target family and recorded in the compatibility registry. */
    RUNTIME_VERIFIED("Verified on a real Minecraft runtime"),
}

/**
 * Explicit translation of one platform-neutral block-state property to its target-runtime representation.
 *
 * Values are never passed through implicitly: a neutral value that is not listed here is reported as an
 * unsupported state instead of being assumed to be spelled the same way by the target runtime.
 */
data class BlockStateTranslation(
    val targetProperty: String,
    /** Exact platform-neutral value -> target-runtime value pairs. */
    val valueTranslations: Map<String, String>,
) {
    init {
        require(PROPERTY_NAME.matches(targetProperty)) { "Invalid target state property name" }
        require(valueTranslations.isNotEmpty()) { "A state translation must declare exact values" }
        require(valueTranslations.all { (neutralValue, targetValue) ->
            PROPERTY_VALUE.matches(neutralValue) && PROPERTY_VALUE.matches(targetValue)
        }) { "Invalid state value token" }
    }

    private companion object {
        val PROPERTY_NAME = Regex("[a-z0-9_]{1,32}")
        val PROPERTY_VALUE = Regex("[a-z0-9_.-]{1,32}")
    }
}

/**
 * One verified target-runtime representation of a platform-neutral block identifier.
 *
 * The mapped identifier is intentionally not a field: a mapping can only prove that the *same* identifier exists
 * with a declared state representation. CraftMind therefore cannot silently replace the user's requested block
 * through a mapping, and a block that cannot be represented is reported as unsupported.
 */
data class MinecraftTargetBlockStateMapping(
    val blockId: String,
    val stateTranslations: Map<String, BlockStateTranslation> = emptyMap(),
    val verification: BlockStateMappingVerification,
) {
    init {
        require(BLOCK_ID.matches(blockId)) { "Invalid platform-neutral block identifier" }
        require(stateTranslations.keys.all { PROPERTY_NAME.matches(it) }) { "Invalid platform-neutral state property" }
    }

    companion object {
        val BLOCK_ID = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")
        private val PROPERTY_NAME = Regex("[a-z0-9_]{1,32}")
    }
}

/** Result of resolving one requested block/state against a runtime target. Never a substitution. */
sealed interface MinecraftBlockStateResolution {
    val requestedBlockId: String

    data class Supported(
        override val requestedBlockId: String,
        val targetStates: Map<String, String>,
    ) : MinecraftBlockStateResolution

    data class UnsupportedBlock(
        override val requestedBlockId: String,
    ) : MinecraftBlockStateResolution

    data class UnsupportedState(
        override val requestedBlockId: String,
        val unsupportedProperties: List<String>,
    ) : MinecraftBlockStateResolution
}

/**
 * Fail-closed block/state compatibility layer for one runtime target family.
 *
 * The same class backs the Bedrock contract and legacy/pre-release Java contracts: it maps platform-neutral
 * blocks onto a declared target representation, always fails closed for unmapped content, and never substitutes a
 * block or a state. A catalog that declares no verified mapping resolves every block to
 * [MinecraftBlockStateResolution.UnsupportedBlock].
 */
class MinecraftTargetBlockStateCatalog(mappings: List<MinecraftTargetBlockStateMapping>) {
    private val byBlockId: Map<String, MinecraftTargetBlockStateMapping>

    init {
        require(mappings.size <= MAXIMUM_MAPPINGS) { "Too many block mappings" }
        require(mappings.none { it.verification == BlockStateMappingVerification.NOT_VERIFIED }) {
            "A block mapping must record how it was verified"
        }
        require(mappings.map { it.blockId }.toSet().size == mappings.size) { "Duplicate block mapping" }
        byBlockId = mappings.associateBy { it.blockId }
    }

    val mappingCount: Int get() = byBlockId.size

    /** True when this catalog declares at least one verified mapping. */
    val isDeclared: Boolean get() = byBlockId.isNotEmpty()

    fun declaredBlockIds(): Set<String> = byBlockId.keys.toSortedSet()

    fun resolve(blockId: String, state: Map<String, String>): MinecraftBlockStateResolution {
        val mapping = byBlockId[blockId] ?: return MinecraftBlockStateResolution.UnsupportedBlock(blockId)
        if (state.size > BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES) {
            return MinecraftBlockStateResolution.UnsupportedState(
                blockId,
                state.keys.sorted().take(BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES),
            )
        }
        val resolved = LinkedHashMap<String, String>()
        val unsupported = mutableListOf<String>()
        state.entries.sortedBy { it.key }.forEach { (property, value) ->
            val translation = mapping.stateTranslations[property]
            val targetValue = translation?.valueTranslations?.get(value)
            if (translation == null || targetValue == null) {
                unsupported += property
            } else {
                resolved[translation.targetProperty] = targetValue
            }
        }
        if (unsupported.isNotEmpty()) {
            return MinecraftBlockStateResolution.UnsupportedState(blockId, unsupported)
        }
        if (resolved.size > BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES) {
            return MinecraftBlockStateResolution.UnsupportedState(blockId, resolved.keys.sorted())
        }
        return MinecraftBlockStateResolution.Supported(blockId, resolved)
    }

    companion object {
        /** The shipped catalog: no verified mapping exists yet, so every block fails closed. */
        val EMPTY = MinecraftTargetBlockStateCatalog(emptyList())

        /** Revision label used when a runtime validates live content itself instead of declaring an app-side map. */
        const val SERVER_VALIDATED_REVISION = "server-validated"

        /** Revision label used when nothing has been verified for this runtime target. */
        const val NONE_DECLARED_REVISION = "none-declared"

        private const val MAXIMUM_MAPPINGS = 4096
    }
}

/**
 * How a profile expects platform-neutral BuildPlan content to be checked.
 *
 * CraftMind never converts one mode into the other: a profile that requires an app-side mapping fails closed when
 * no verified mapping exists, and a profile that declares server-side validation never receives an app-side
 * substitution.
 */
enum class MinecraftContentValidationMode(val displayName: String) {
    /** The authenticated Minecraft-side runtime validates each requested block/state during preflight. */
    SERVER_SIDE_VALIDATION("Validated by the Minecraft-side runtime"),

    /** CraftMind must map every requested block/state through a verified target catalog before execution. */
    APP_SIDE_MAPPING("Mapped by CraftMind from a verified target catalog"),
}
