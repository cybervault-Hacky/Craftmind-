package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits

/**
 * How a platform-neutral block/state representation was verified for one Bedrock runtime target.
 *
 * A mapping is never inferred. Only a mapping that was checked against a real Bedrock runtime may be declared
 * in a shipped [BedrockBlockStateCatalog]; everything else fails closed with `UNSUPPORTED_BLOCK` /
 * `UNSUPPORTED_BLOCK_STATE` instead of being approximated.
 */
enum class BedrockMappingVerification(val displayName: String) {
    /** Declared but never checked. Rejected by the catalog; never applied. */
    NOT_VERIFIED("Not verified"),

    /** Checked only against published documention. Informational; not accepted by a shipped catalog. */
    DOCUMENTED_ONLY("Documentation only"),

    /** Checked against a real Minecraft Bedrock runtime and recorded in the Bedrock compatibility registry. */
    RUNTIME_VERIFIED("Verified on a real Bedrock runtime"),
}

/**
 * Explicit translation of one platform-neutral block-state property to its Bedrock representation.
 *
 * Values are never passed through implicitly: a neutral value that is not listed here is reported as an
 * unsupported state instead of being assumed to be spelled the same way in Bedrock.
 */
data class BedrockStateTranslation(
    val bedrockProperty: String,
    /** Exact platform-neutral value -> Bedrock value pairs. */
    val valueTranslations: Map<String, String>,
) {
    init {
        require(PROPERTY_NAME.matches(bedrockProperty)) { "Invalid Bedrock state property name" }
        require(valueTranslations.isNotEmpty()) { "A Bedrock state translation must declare exact values" }
        require(valueTranslations.all { (neutralValue, bedrockValue) ->
            PROPERTY_VALUE.matches(neutralValue) && PROPERTY_VALUE.matches(bedrockValue)
        }) { "Invalid Bedrock state value token" }
    }

    private companion object {
        val PROPERTY_NAME = Regex("[a-z0-9_]{1,32}")
        val PROPERTY_VALUE = Regex("[a-z0-9_.-]{1,32}")
    }
}

/**
 * One verified Bedrock representation of a platform-neutral block identifier.
 *
 * The mapped identifier is intentionally not a field: a mapping can only prove that the *same* identifier exists
 * with a declared state representation. CraftMind therefore cannot silently replace the user's requested block
 * through a mapping, and a block that cannot be represented is reported as unsupported.
 */
data class BedrockBlockStateMapping(
    val blockId: String,
    val stateTranslations: Map<String, BedrockStateTranslation> = emptyMap(),
    val verification: BedrockMappingVerification,
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

/** Result of resolving one requested block/state against a Bedrock runtime target. Never a substitution. */
sealed interface BedrockBlockStateResolution {
    val requestedBlockId: String

    data class Supported(
        override val requestedBlockId: String,
        val bedrockStates: Map<String, String>,
    ) : BedrockBlockStateResolution

    data class UnsupportedBlock(
        override val requestedBlockId: String,
    ) : BedrockBlockStateResolution

    data class UnsupportedState(
        override val requestedBlockId: String,
        val unsupportedProperties: List<String>,
    ) : BedrockBlockStateResolution
}

/**
 * Version-aware, fail-closed block/state compatibility layer for one Bedrock runtime target.
 *
 * The shipped catalog is [EMPTY] on purpose: no Bedrock block/state mapping has been verified against a real
 * Bedrock runtime in this repository, so every requested block resolves to [BedrockBlockStateResolution.UnsupportedBlock]
 * and no BuildPlan can be executed. Entries are added only together with recorded verification.
 */
class BedrockBlockStateCatalog(mappings: List<BedrockBlockStateMapping>) {
    private val byBlockId: Map<String, BedrockBlockStateMapping>

    init {
        require(mappings.size <= MAXIMUM_MAPPINGS) { "Too many Bedrock block mappings" }
        require(mappings.none { it.verification == BedrockMappingVerification.NOT_VERIFIED }) {
            "A Bedrock block mapping must record how it was verified"
        }
        require(mappings.map { it.blockId }.toSet().size == mappings.size) { "Duplicate Bedrock block mapping" }
        byBlockId = mappings.associateBy { it.blockId }
    }

    val mappingCount: Int get() = byBlockId.size

    /** True when this catalog declares at least one verified mapping. */
    val isDeclared: Boolean get() = byBlockId.isNotEmpty()

    fun declaredBlockIds(): Set<String> = byBlockId.keys.toSortedSet()

    fun resolve(blockId: String, state: Map<String, String>): BedrockBlockStateResolution {
        val mapping = byBlockId[blockId] ?: return BedrockBlockStateResolution.UnsupportedBlock(blockId)
        if (state.size > BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES) {
            return BedrockBlockStateResolution.UnsupportedState(
                blockId,
                state.keys.sorted().take(BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES),
            )
        }
        val resolved = LinkedHashMap<String, String>()
        val unsupported = mutableListOf<String>()
        state.entries.sortedBy { it.key }.forEach { (property, value) ->
            val translation = mapping.stateTranslations[property]
            val bedrockValue = translation?.valueTranslations?.get(value)
            if (translation == null || bedrockValue == null) {
                unsupported += property
            } else {
                resolved[translation.bedrockProperty] = bedrockValue
            }
        }
        if (unsupported.isNotEmpty()) {
            return BedrockBlockStateResolution.UnsupportedState(blockId, unsupported)
        }
        if (resolved.size > BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES) {
            return BedrockBlockStateResolution.UnsupportedState(blockId, resolved.keys.sorted())
        }
        return BedrockBlockStateResolution.Supported(blockId, resolved)
    }

    companion object {
        /** The shipped catalog: no verified Bedrock mapping exists yet, so every block fails closed. */
        val EMPTY = BedrockBlockStateCatalog(emptyList())

        private const val MAXIMUM_MAPPINGS = 4096
    }
}
