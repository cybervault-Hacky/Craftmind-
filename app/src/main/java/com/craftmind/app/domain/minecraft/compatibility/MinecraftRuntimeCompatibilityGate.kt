package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure

/**
 * The automatic runtime pipeline phase (Phase 13).
 *
 * This is not a second connection state machine: the existing `BridgeConnectionState` still owns pairing,
 * connecting, connected, and error. These phases describe where the *runtime* pipeline stopped, so the UI can show
 * detection, validation, adapter selection, and compatibility resolution instead of a single opaque "connected".
 */
enum class MinecraftRuntimePipelinePhase(val displayName: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting"),
    AUTHENTICATING("Authenticating"),
    DETECTING_RUNTIME("Detecting Minecraft runtime"),
    VALIDATING_RUNTIME("Validating runtime descriptor"),
    SELECTING_ADAPTER("Selecting compatibility adapter"),
    RESOLVING_COMPATIBILITY("Resolving compatibility"),
    READY("Ready"),
    INCOMPATIBLE("Incompatible"),
    UNKNOWN("Runtime unknown"),
    ERROR("Error"),
}

/**
 * Compatibility bound to one authenticated session and one exact runtime.
 *
 * A binding is the only thing that may authorize execution: it records the session identity, the runtime identity,
 * the descriptor it was resolved from, the selected adapter, and the compatibility result. If any security-sensitive
 * part changes, the binding is invalid and detection runs again.
 */
data class MinecraftRuntimeCompatibilityBinding(
    val identity: MinecraftRuntimeIdentity,
    val descriptor: MinecraftRuntimeDescriptor,
    val detection: MinecraftRuntimeDetectionResult,
    val selection: CompatibilityAdapterSelection,
    val compatibility: MinecraftCompatibilityResult,
    val resolvedAtEpochMillis: Long,
) {
    /** Execution requires a detected runtime, an exactly selected adapter, and a compatible resolution. */
    val canExecute: Boolean
        get() = detection.isDetected && selection.isSelected && compatibility.canExecute

    /** Typed validity check against the runtime/session that is authenticated right now. */
    fun validityFor(current: MinecraftRuntimeIdentity?, currentAdapterId: MinecraftAdapterId?): MinecraftRuntimeBindingValidity =
        when {
            current == null -> MinecraftRuntimeBindingValidity.SessionChanged(
                detail = "There is no authenticated bridge session to bind this runtime compatibility to.",
            )

            !identity.hasSameSessionIdentity(current) -> MinecraftRuntimeBindingValidity.SessionChanged(
                detail = "The authenticated bridge session changed; cached runtime compatibility is invalidated.",
            )

            !identity.hasSameRuntime(current) -> MinecraftRuntimeBindingValidity.RuntimeChanged(
                previousRuntimeKey = identity.runtimeKey,
                currentRuntimeKey = current.runtimeKey,
            )

            currentAdapterId != null && selection.adapterId != null && currentAdapterId != selection.adapterId ->
                MinecraftRuntimeBindingValidity.AdapterChanged(
                    previousAdapterId = selection.adapterId,
                    currentAdapterId = currentAdapterId,
                )

            identity.worldSessionId != null && identity.worldSessionId != current.worldSessionId ->
                MinecraftRuntimeBindingValidity.WorldSessionChanged(
                    previousWorldSessionId = identity.worldSessionId,
                    currentWorldSessionId = current.worldSessionId,
                )

            !canExecute -> MinecraftRuntimeBindingValidity.NotExecutable(
                detail = "This binding never authorized execution; runtime compatibility is unchanged but not executable.",
            )

            else -> MinecraftRuntimeBindingValidity.Valid
        }
}

/** Why a previously resolved runtime compatibility binding may no longer be used. */
sealed interface MinecraftRuntimeBindingValidity {
    data object Valid : MinecraftRuntimeBindingValidity

    data class SessionChanged(val detail: String) : MinecraftRuntimeBindingValidity

    data class RuntimeChanged(val previousRuntimeKey: String, val currentRuntimeKey: String) : MinecraftRuntimeBindingValidity

    data class AdapterChanged(
        val previousAdapterId: MinecraftAdapterId,
        val currentAdapterId: MinecraftAdapterId,
    ) : MinecraftRuntimeBindingValidity

    data class WorldSessionChanged(
        val previousWorldSessionId: String?,
        val currentWorldSessionId: String?,
    ) : MinecraftRuntimeBindingValidity

    data class NotExecutable(val detail: String) : MinecraftRuntimeBindingValidity

    /** Stable machine-readable reason used by the execution gate and the UI. */
    val reasonCode: String
        get() = when (this) {
            Valid -> "RUNTIME_BINDING_VALID"
            is SessionChanged -> "SESSION_IDENTITY_MISMATCH"
            is RuntimeChanged -> "RUNTIME_IDENTITY_CHANGED"
            is AdapterChanged -> "RUNTIME_IDENTITY_CHANGED"
            is WorldSessionChanged -> "WORLD_SESSION_CHANGED"
            is NotExecutable -> "BUILD_PLAN_NOT_EXECUTABLE"
        }

    val reasonCodes: Set<MinecraftCompatibilityReasonCode>
        get() = when (this) {
            Valid -> emptySet()
            is SessionChanged -> setOf(MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH)
            is RuntimeChanged, is AdapterChanged -> setOf(MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED)
            is WorldSessionChanged -> setOf(MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED)
            is NotExecutable -> emptySet()
        }

    /** Human-readable explanation; it never exposes credentials, tokens, or provider material. */
    val explanation: String
        get() = when (this) {
            Valid -> "The authenticated runtime and session still match the resolved compatibility."
            is SessionChanged -> detail
            is RuntimeChanged -> "The authenticated Minecraft runtime changed from $previousRuntimeKey to " +
                "$currentRuntimeKey. Previous execution eligibility is invalidated and compatibility is resolved again."
            is AdapterChanged -> "The selected compatibility adapter changed from ${previousAdapterId.value} to " +
                "${currentAdapterId.value}; the previous execution eligibility is invalidated."
            is WorldSessionChanged -> "The bridge world session changed; prepared work is invalidated."
            is NotExecutable -> detail
        }
}

/** The full result of one automatic detection → validation → selection → resolution pass. */
data class MinecraftRuntimeResolution(
    val phase: MinecraftRuntimePipelinePhase,
    val detection: MinecraftRuntimeDetectionResult,
    val selection: CompatibilityAdapterSelection,
    val compatibility: MinecraftCompatibilityResult,
    /** Null unless the runtime was detected, an adapter was selected exactly, and compatibility resolved. */
    val binding: MinecraftRuntimeCompatibilityBinding?,
    val capabilityWarnings: List<String> = emptyList(),
) {
    val canExecute: Boolean get() = binding?.canExecute == true

    val isDetected: Boolean get() = detection.isDetected

    val reasonCodes: Set<MinecraftCompatibilityReasonCode>
        get() = detection.reasonCodes + selection.reasonCodes + compatibility.reasonCodes

    /** Stable machine-readable code for logs and the UI; detection failures are reported before plan failures. */
    fun failureReasonCode(): String = when {
        !detection.isDetected -> detection.failureReasonCode()
        selection.status == MinecraftAdapterSelectionStatus.AMBIGUOUS -> "AMBIGUOUS_ADAPTER_MATCH"
        selection.status == MinecraftAdapterSelectionStatus.INVALID -> "ADAPTER_SELECTION_INVALID"
        !selection.isSelected -> compatibility.failureReasonCode()
        !canExecute -> compatibility.failureReasonCode()
        else -> "RUNTIME_READY"
    }
}

/** The outcome of the final pre-execution authorization, including time-of-check/time-of-use protection. */
data class MinecraftExecutionAuthorization(
    val authorized: Boolean,
    val reasonCode: String,
    val reasonCodes: Set<MinecraftCompatibilityReasonCode> = emptySet(),
    val reasons: List<String> = emptyList(),
    /** The freshly resolved binding; execution must use this runtime, never an older cached one. */
    val binding: MinecraftRuntimeCompatibilityBinding? = null,
    val resolution: MinecraftRuntimeResolution? = null,
    val runtimeChanged: Boolean = false,
    val sessionChanged: Boolean = false,
) {
    fun failure(): MinecraftBridgeFailure = MinecraftBridgeFailure(reasonCode, reasons.firstOrNull())
}

/**
 * One entry point for the Phase 13 pipeline:
 *
 * ```text
 * authenticated bridge report → runtime descriptor → detection → descriptor validation → adapter selection
 *   → compatibility resolution → session-bound compatibility → final execution authorization
 * ```
 *
 * The gate reuses the existing detector, selector, registry, and resolver; it adds ordering, session binding,
 * runtime-change invalidation, and time-of-check/time-of-use protection. It never guesses a runtime, never repairs
 * a descriptor, and never authorizes execution from a cached or displayed result alone.
 */
class MinecraftRuntimeCompatibilityGate(
    val detector: MinecraftRuntimeDetector,
    val selector: MinecraftAdapterSelector,
    val resolver: MinecraftCompatibilityResolver,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Runtime-level resolution: no BuildPlan, only the authenticated runtime facts. */
    fun resolveRuntime(report: AuthenticatedMinecraftRuntimeReport): MinecraftRuntimeResolution =
        resolve(report, BuildPlanRequirements.runtimeExecution)

    /** Plan-level resolution used by Build Review and by the execution path. */
    fun resolvePlan(plan: BuildPlan, report: AuthenticatedMinecraftRuntimeReport): MinecraftRuntimeResolution =
        resolve(report, BuildPlanRequirements.from(plan))

    fun resolve(
        report: AuthenticatedMinecraftRuntimeReport,
        requirements: BuildPlanRequirements,
    ): MinecraftRuntimeResolution {
        val now = clock()
        // Detection runs first and only for an authenticated report; selection can never precede it.
        val detection = detector.detect(report, detectedAtEpochMillis = now)
        val selection = selector.select(detection)
        // The resolver stays the single compatibility authority and is fail-closed on its own, so an undetected or
        // unbound runtime still produces an honest, structured result for the UI instead of a fabricated one.
        val compatibility = resolver.resolve(report.descriptor, requirements)
        val identity = detection.runtimeIdentity
        val binding = if (detection.isDetected && selection.isSelected && identity != null) {
            MinecraftRuntimeCompatibilityBinding(
                identity = identity,
                descriptor = report.descriptor,
                detection = detection,
                selection = selection,
                compatibility = compatibility,
                resolvedAtEpochMillis = now,
            )
        } else {
            null
        }
        return MinecraftRuntimeResolution(
            phase = phaseFor(report, detection, selection, compatibility),
            detection = detection,
            selection = selection,
            capabilityWarnings = capabilityWarnings(detection, selection, compatibility),
            compatibility = compatibility,
            binding = binding,
        )
    }

    /**
     * Final authorization before a BuildPlan is sent.
     *
     * It always re-runs detection, validation, adapter selection, and compatibility resolution against the report
     * that is authenticated *now*, then compares the result with the binding compatibility was previously resolved
     * for. A different session, runtime, adapter, or world session aborts execution instead of reusing the earlier
     * answer, which closes the time-of-check/time-of-use gap between preflight and execution.
     */
    fun authorizeExecution(
        report: AuthenticatedMinecraftRuntimeReport,
        requirements: BuildPlanRequirements,
        previousBinding: MinecraftRuntimeCompatibilityBinding? = null,
    ): MinecraftExecutionAuthorization {
        val resolution = resolve(report, requirements)
        val binding = resolution.binding
        if (!resolution.detection.isDetected) {
            return denied(
                resolution = resolution,
                binding = binding,
                reasonCode = resolution.detection.failureReasonCode(),
                reasonCodes = resolution.detection.reasonCodes,
                reasons = resolution.detection.diagnostics.map { "${it.field.displayName}: ${it.detail}" },
                sessionChanged = MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in resolution.detection.reasonCodes,
            )
        }
        // Time-of-check/time-of-use protection runs before any compatibility verdict: the runtime that is
        // authenticated now must still be the runtime compatibility was resolved for, otherwise the earlier answer
        // is meaningless and execution is aborted instead of continued with a stale adapter.
        previousBinding?.let { previous ->
            when (val validity = previous.validityFor(resolution.detection.runtimeIdentity, resolution.selection.adapterId)) {
                MinecraftRuntimeBindingValidity.Valid -> Unit
                is MinecraftRuntimeBindingValidity.NotExecutable -> Unit
                else -> return denied(
                    resolution = resolution,
                    binding = null,
                    reasonCode = validity.reasonCode,
                    reasonCodes = validity.reasonCodes,
                    reasons = listOf(validity.explanation),
                    runtimeChanged = validity is MinecraftRuntimeBindingValidity.RuntimeChanged ||
                        validity is MinecraftRuntimeBindingValidity.AdapterChanged,
                    sessionChanged = validity is MinecraftRuntimeBindingValidity.SessionChanged,
                )
            }
        }
        if (binding == null || !resolution.selection.isSelected) {
            return denied(
                resolution = resolution,
                binding = binding,
                reasonCode = if (resolution.selection.status == MinecraftAdapterSelectionStatus.AMBIGUOUS) {
                    "AMBIGUOUS_ADAPTER_MATCH"
                } else {
                    resolution.compatibility.failureReasonCode()
                },
                reasonCodes = resolution.selection.reasonCodes + resolution.compatibility.reasonCodes,
                reasons = resolution.selection.reasons + resolution.compatibility.reasons,
            )
        }
        if (!resolution.canExecute) {
            return denied(
                resolution = resolution,
                binding = binding,
                reasonCode = resolution.compatibility.failureReasonCode(),
                reasonCodes = resolution.compatibility.reasonCodes,
                reasons = resolution.compatibility.reasons,
            )
        }
        return MinecraftExecutionAuthorization(
            authorized = true,
            reasonCode = "RUNTIME_EXECUTION_AUTHORIZED",
            reasonCodes = emptySet(),
            reasons = emptyList(),
            binding = binding,
            resolution = resolution,
        )
    }

    private fun denied(
        resolution: MinecraftRuntimeResolution,
        binding: MinecraftRuntimeCompatibilityBinding?,
        reasonCode: String,
        reasonCodes: Set<MinecraftCompatibilityReasonCode>,
        reasons: List<String>,
        runtimeChanged: Boolean = false,
        sessionChanged: Boolean = false,
    ) = MinecraftExecutionAuthorization(
        authorized = false,
        reasonCode = reasonCode,
        reasonCodes = reasonCodes,
        reasons = reasons.distinct(),
        binding = null,
        resolution = resolution,
        runtimeChanged = runtimeChanged,
        sessionChanged = sessionChanged,
    )

    /**
     * Capability validation against the authenticated report (§15): a bridge that claims build execution cannot
     * make an uncertified or experimental runtime executable, and capabilities are never taken from a UI or a
     * registry declaration.
     */
    private fun capabilityWarnings(
        detection: MinecraftRuntimeDetectionResult,
        selection: CompatibilityAdapterSelection,
        compatibility: MinecraftCompatibilityResult,
    ): List<String> = buildList {
        val descriptor = detection.descriptor
        if (MinecraftCapability.BUILD_EXECUTION in descriptor.capabilities &&
            !selection.matchedProfileAuthorizesExecution
        ) {
            add(
                "The authenticated bridge reports build execution, but the matched runtime profile does not " +
                    "authorize execution; canExecute stays false.",
            )
        }
        if (MinecraftCapability.BLOCK_STATE_SUPPORT in descriptor.capabilities &&
            !compatibility.planContentSupported
        ) {
            add(
                "The bridge reports block-state support, but the requested content has no verified representation " +
                    "for this exact runtime; CraftMind never substitutes a block or state.",
            )
        }
        if (selection.isSelected && compatibility.missingCapabilities.isNotEmpty()) {
            add(
                "Adapter ${selection.adapterId?.value} was selected, but the authenticated bridge did not report " +
                    compatibility.missingCapabilities.sortedBy(MinecraftCapability::name)
                    .joinToString { it.displayName } + ".",
            )
        }
    }

    private fun phaseFor(
        report: AuthenticatedMinecraftRuntimeReport,
        detection: MinecraftRuntimeDetectionResult,
        selection: CompatibilityAdapterSelection,
        compatibility: MinecraftCompatibilityResult,
    ): MinecraftRuntimePipelinePhase = when {
        !report.authenticated -> MinecraftRuntimePipelinePhase.AUTHENTICATING
        MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in detection.reasonCodes ||
            MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED in detection.reasonCodes ->
            MinecraftRuntimePipelinePhase.ERROR

        detection.status == MinecraftRuntimeDetectionStatus.UNKNOWN -> MinecraftRuntimePipelinePhase.UNKNOWN
        detection.status == MinecraftRuntimeDetectionStatus.INCOMPLETE ->
            MinecraftRuntimePipelinePhase.DETECTING_RUNTIME

        detection.status == MinecraftRuntimeDetectionStatus.INVALID ->
            MinecraftRuntimePipelinePhase.VALIDATING_RUNTIME

        selection.status != MinecraftAdapterSelectionStatus.SELECTED ->
            MinecraftRuntimePipelinePhase.SELECTING_ADAPTER

        !compatibility.canExecute -> MinecraftRuntimePipelinePhase.INCOMPATIBLE
        else -> MinecraftRuntimePipelinePhase.READY
    }
}
