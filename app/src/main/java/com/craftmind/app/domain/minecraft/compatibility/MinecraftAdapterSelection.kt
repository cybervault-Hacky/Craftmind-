package com.craftmind.app.domain.minecraft.compatibility

/**
 * Deterministic compatibility-adapter selection (Phase 13).
 *
 * Selection is a pure function of an authoritative, validated [MinecraftRuntimeDescriptor] plus the existing
 * [MinecraftAdapterRegistry]. There is no second registry, no second adapter model, and no nearest-match logic:
 * an adapter is selected only when a registered profile or Bedrock contract matches the reported runtime exactly.
 * Two different adapters that both match produce [MinecraftAdapterSelectionStatus.AMBUIGUOUS] rather than a choice
 * based on registration order, and an unauthenticated or non-detected runtime can never reach selection at all.
 */
enum class MinecraftAdapterSelectionStatus {
    /** Exactly one registered adapter matches this runtime exactly. */
    SELECTED,

    /** No registered adapter matches; CraftMind never falls back to the closest one. */
    NO_MATCH,

    /** More than one registered adapter could match; selection is blocked instead of ordered. */
    AMBIGUOUS,

    /** The runtime descriptor may not be used for selection (unauthenticated, invalid, or not detected). */
    INVALID,
}

/** How the selected adapter recognized the runtime, so callers never confuse the two contract families. */
enum class MinecraftAdapterMatchKind {
    /** Exact edition + version + loader + loader version + bridge identity (+ Java/Fabric API) profile. */
    VERSION_KEYED_PROFILE,

    /** Bedrock contract keyed by bridge identity and platform, never by a certified Minecraft version. */
    BEDROCK_CONTRACT,
}

/**
 * One adapter-selection outcome with structured reasons.
 *
 * A selected adapter is *not* an execution authorization: the compatibility resolver still decides
 * `SUPPORTED`/`EXPERIMENTAL`/`UNSUPPORTED`, certification, capabilities, limits, and content, so a selected
 * legacy or Bedrock adapter can still resolve to `canExecute == false`.
 */
data class CompatibilityAdapterSelection(
    val status: MinecraftAdapterSelectionStatus,
    val adapterId: MinecraftAdapterId? = null,
    val adapter: MinecraftAdapter? = null,
    val matchKind: MinecraftAdapterMatchKind? = null,
    /** The exact registered version-keyed profile that matched; null for contract-keyed Bedrock selection. */
    val matchedProfile: SupportedMinecraftRuntimeDescriptor? = null,
    /** The exact registered Bedrock contract that matched; null for version-keyed Java selection. */
    val matchedBedrockProfile: BedrockRuntimeProfile? = null,
    /** Every adapter that could match, in deterministic adapter-ID order; never registration order. */
    val candidateAdapterIds: List<MinecraftAdapterId> = emptyList(),
    val reasonCodes: Set<MinecraftCompatibilityReasonCode> = emptySet(),
    val reasons: List<String> = emptyList(),
) {
    val isSelected: Boolean get() = status == MinecraftAdapterSelectionStatus.SELECTED && adapter != null && adapterId != null

    /** True only when the matched runtime profile itself authorizes execution; recognition never implies support. */
    val matchedProfileAuthorizesExecution: Boolean
        get() = when {
            matchedProfile != null -> matchedProfile.authorizesExecution
            matchedBedrockProfile != null -> matchedBedrockProfile.certification.authorizesBedrockSupport &&
                matchedBedrockProfile.status == MinecraftCompatibilityStatus.SUPPORTED
            else -> false
        }

    companion object {
        fun invalid(
            reasonCode: MinecraftCompatibilityReasonCode,
            reason: String,
            candidates: List<MinecraftAdapterId> = emptyList(),
        ) = CompatibilityAdapterSelection(
            status = MinecraftAdapterSelectionStatus.INVALID,
            candidateAdapterIds = candidates,
            reasonCodes = setOf(reasonCode),
            reasons = listOf(reason),
        )

        fun noMatch(
            reasonCodes: Set<MinecraftCompatibilityReasonCode> = emptySet(),
            reasons: List<String> = emptyList(),
        ) = CompatibilityAdapterSelection(
            status = MinecraftAdapterSelectionStatus.NO_MATCH,
            reasonCodes = reasonCodes,
            reasons = reasons,
        )
    }
}

/**
 * The deterministic selector. It reads the shared registry, applies exact matching, and returns a typed result;
 * it never mutates a descriptor, never repairs a runtime, and never selects "the closest" adapter.
 */
class MinecraftAdapterSelector(private val registry: MinecraftAdapterRegistry) {
    /**
     * Selection for an already detected runtime. A detection result that is not
     * [MinecraftRuntimeDetectionStatus.DETECTED], or that carries no authenticated runtime identity, can never
     * select an adapter: this is what keeps an unauthenticated or partially reported runtime out of execution.
     */
    fun select(detection: MinecraftRuntimeDetectionResult): CompatibilityAdapterSelection {
        if (!detection.isDetected) {
            val code = detection.reasonCodes.firstOrNull() ?: MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR
            return CompatibilityAdapterSelection.invalid(
                reasonCode = code,
                reason = "Adapter selection is blocked because runtime detection returned ${detection.status.name}. " +
                    "CraftMind never guesses a Minecraft runtime or selects a nearest adapter.",
            )
        }
        if (detection.runtimeIdentity == null) {
            return CompatibilityAdapterSelection.invalid(
                reasonCode = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
                reason = "Adapter selection is blocked because this runtime is not bound to an authenticated bridge session.",
            )
        }
        return select(detection.descriptor)
    }

    /** Exact-match selection against the registered profiles and Bedrock contracts. */
    fun select(runtime: MinecraftRuntimeDescriptor): CompatibilityAdapterSelection {
        val identityProfiles = registry.allProfiles().filter { it.matchesRuntimeIdentity(runtime) }
        val matchedProfiles = identityProfiles.filter { it.matches(runtime) }
        val matchedContracts = if (runtime.edition == MinecraftEdition.BEDROCK) {
            registry.allBedrockProfiles().filter { it.matchesBridgeIdentity(runtime) }
        } else {
            emptyList()
        }

        // Deterministic candidate order: adapter ID, never registration order.
        val candidates = (matchedProfiles.map { it.adapterId to MinecraftAdapterMatchKind.VERSION_KEYED_PROFILE } +
            matchedContracts.map { it.adapterId to MinecraftAdapterMatchKind.BEDROCK_CONTRACT })
            .distinctBy { it.first }
            .sortedBy { it.first.value }

        if (candidates.size > 1) {
            return CompatibilityAdapterSelection(
                status = MinecraftAdapterSelectionStatus.AMBIGUOUS,
                candidateAdapterIds = candidates.map { it.first },
                reasonCodes = setOf(MinecraftCompatibilityReasonCode.AMBIGUOUS_ADAPTER_PROFILE),
                reasons = listOf(
                    "More than one registered adapter claims this exact runtime " +
                        "(${candidates.joinToString { it.first.value }}); adapter selection is blocked instead of " +
                        "choosing by registration order.",
                ),
            )
        }

        if (candidates.isEmpty()) {
            return noMatchDiagnosis(runtime, identityProfiles)
        }

        val (adapterId, matchKind) = candidates.single()
        val adapter = registry.adapter(adapterId)
            ?: return CompatibilityAdapterSelection.invalid(
                reasonCode = MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
                reason = "Adapter $adapterId matched this runtime but is not registered; selection is blocked.",
                candidates = listOf(adapterId),
            )
        return CompatibilityAdapterSelection(
            status = MinecraftAdapterSelectionStatus.SELECTED,
            adapterId = adapterId,
            adapter = adapter,
            matchKind = matchKind,
            matchedProfile = matchedProfiles.firstOrNull { it.adapterId == adapterId },
            matchedBedrockProfile = matchedContracts.firstOrNull { it.adapterId == adapterId },
            candidateAdapterIds = listOf(adapterId),
        )
    }

    /**
     * Explains an exact-match failure. Identity-level matches that fail only on the Java runtime or Fabric API keep
     * their specific reason codes; everything else is a plain no-match that the resolver diagnoses further
     * (unsupported version, loader, release channel, bridge, or protocol).
     */
    private fun noMatchDiagnosis(
        runtime: MinecraftRuntimeDescriptor,
        identityProfiles: List<SupportedMinecraftRuntimeDescriptor>,
    ): CompatibilityAdapterSelection {
        if (identityProfiles.isEmpty()) return CompatibilityAdapterSelection.noMatch()

        val reasons = mutableListOf<String>()
        val reasonCodes = linkedSetOf<MinecraftCompatibilityReasonCode>()
        val javaCompatible = identityProfiles.filter { profile ->
            profile.javaRuntimeRequirement?.supports(runtime.javaRuntimeMajor ?: return@filter false) ?: true
        }
        if (javaCompatible.isEmpty() && identityProfiles.any { it.javaRuntimeRequirement != null }) {
            val requirement = identityProfiles.firstNotNullOf { it.javaRuntimeRequirement }
            reasonCodes += MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME
            reasons += "The bridge reports Java ${runtime.javaRuntimeMajor}; this profile requires Java " +
                "${requirement.requiredMajor} (supported ${requirement.minimumSupportedMajor}–${requirement.maximumSupportedMajor})."
        }
        val profilesWithFabricApiRequirement = identityProfiles.filter { it.requiredFabricApiVersion != null }
        if (profilesWithFabricApiRequirement.isNotEmpty() &&
            profilesWithFabricApiRequirement.none { it.requiredFabricApiVersion == runtime.fabricApiVersion }
        ) {
            val expected = profilesWithFabricApiRequirement.firstNotNullOf { it.requiredFabricApiVersion }
            reasonCodes += MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH
            reasons += "The bridge reports Fabric API ${runtime.fabricApiVersion}; this profile requires exactly $expected."
        }
        if (reasons.isEmpty()) {
            reasonCodes += MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR
            reasons += "The runtime descriptor does not satisfy a registered profile."
        }
        return CompatibilityAdapterSelection.noMatch(
            reasonCodes = reasonCodes,
            reasons = reasons,
        )
    }
}
