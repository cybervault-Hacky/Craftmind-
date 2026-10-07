package com.craftmind.app.presentation.minecraft

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.presentation.settings.BridgePairingState
import com.craftmind.app.presentation.settings.availabilityLabel
import com.craftmind.app.presentation.settings.certificationSummaryLines
import com.craftmind.app.presentation.settings.detectionStatusLabel
import com.craftmind.app.presentation.settings.pipelineReasonLines
import com.craftmind.app.presentation.settings.runtimeSummaryLines

/**
 * Minecraft connection summary (Phase 15 §9).
 *
 * A pure derivation of [BridgePairingState] into exactly one screen state, so the Minecraft screen can never show a
 * status the app does not hold and so the wording can be unit-tested on the JVM.
 *
 * The rules carried over from Phase 13 and Phase 14 are preserved here:
 *
 * * The authenticated bridge is the only source of runtime truth. Nothing is guessed, no nearest version is
 *   substituted, and a missing report is reported as missing.
 * * *Supported* is never presented as *certified*, and simulated evidence is never presented as a real runtime test.
 * * Selecting an adapter is not the same as being allowed to build; [MinecraftConnectionSummary.canBuild] comes only
 *   from the resolved execution binding.
 * * A changed runtime invalidates the previous resolution instead of being silently accepted.
 */
enum class MinecraftConnectionStage {
    /** The saved bridge profile has not been read yet. */
    LOADING_PROFILE,

    /** No bridge is paired on this device. */
    NOT_PAIRED,

    /** Pairing or authentication is in flight. */
    CONNECTING,

    /** The bridge refused pairing or authentication. */
    AUTHENTICATION_FAILED,

    /** A bridge is paired, but no session is active. */
    DISCONNECTED,

    /** The authenticated session expired and must be re-established. */
    SESSION_EXPIRED,

    /** Authenticated, but no runtime report has been resolved yet. */
    DETECTING_RUNTIME,

    /** The authenticated runtime changed after a resolution was recorded. */
    RUNTIME_CHANGED,

    /** Authenticated, but the runtime could not be detected or is not usable. */
    RUNTIME_UNAVAILABLE,

    /** Detected and supported, or detected and experimental. */
    COMPATIBLE,
    EXPERIMENTAL,

    /** Detected, but unsupported or unverifiable. */
    INCOMPATIBLE,
}

/** What the Minecraft screen may offer right now, derived from real state rather than from the layout. */
data class MinecraftConnectionActions(
    val showPairingForm: Boolean,
    val showReconnect: Boolean,
    val showRefresh: Boolean,
    val showDisconnect: Boolean,
    val showSessionControls: Boolean,
)

data class MinecraftConnectionSummary(
    val stage: MinecraftConnectionStage,
    val headline: String,
    val detail: String,
    val tone: CraftMindTone,
    /** Short status badge; null while the profile is still loading. */
    val badge: String?,
    val badgeTone: CraftMindTone,
    /** Real runtime facts reported by the authenticated bridge, in display order. */
    val runtimeLines: List<String>,
    /** Certification state, evidence state, and the execution permission line. */
    val certificationLines: List<String>,
    /** Collapsed diagnostics: reason codes and pipeline lines. */
    val diagnosticLines: List<String>,
    /** True only when a resolved binding authorizes execution on this session. */
    val canBuild: Boolean,
    /** Human explanation of why building is unavailable; null when it is available. */
    val unavailableReason: String?,
    /** True while a bridge operation is in flight. */
    val isBusy: Boolean,
    val busyLabel: String?,
    val actions: MinecraftConnectionActions,
)

/** Reason code the bridge repository reports when an authenticated session has expired. */
internal const val AUTH_SESSION_EXPIRED_REASON_CODE = "AUTH_SESSION_EXPIRED"

/** Derives the single screen state for the Minecraft destination. */
fun minecraftConnectionSummary(state: BridgePairingState): MinecraftConnectionSummary {
    val connection = state.connection
    val resolution = state.runtimeResolution
    val compatibility = state.compatibility ?: resolution?.compatibility
    val connected = connection as? BridgeConnectionState.Connected
    val paired = state.profile != null

    val actions = MinecraftConnectionActions(
        showPairingForm = state.isProfileLoaded && !paired,
        showReconnect = paired && connected == null && !state.isWorking,
        showRefresh = connected != null && !state.isWorking,
        showDisconnect = connected != null && !state.isWorking,
        showSessionControls = paired,
    )

    val runtimeLines = resolution?.runtimeSummaryLines().orEmpty()
    val certificationLines = resolution?.certificationSummaryLines(compatibility).orEmpty()
    val diagnosticLines = buildList {
        resolution?.let { addAll(it.pipelineReasonLines()) }
        resolution?.capabilityWarnings?.let { addAll(it) }
        compatibility?.reasons?.let { addAll(it) }
        compatibility?.warnings?.let { addAll(it) }
        (connection as? BridgeConnectionState.Error)?.let { add("Bridge reason code: ${it.reasonCode}") }
    }

    val busyLabel = when {
        !state.isWorking -> null
        connection is BridgeConnectionState.Connecting -> "Authenticating with the paired bridge…"
        connected == null -> "Working with the bridge…"
        else -> "Refreshing authenticated capabilities…"
    }

    val stage = when {
        !state.isProfileLoaded -> MinecraftConnectionStage.LOADING_PROFILE
        connection is BridgeConnectionState.Connecting -> MinecraftConnectionStage.CONNECTING
        state.isWorking && connected == null && paired -> MinecraftConnectionStage.CONNECTING
        connection is BridgeConnectionState.Error ->
            if (connection.reasonCode == AUTH_SESSION_EXPIRED_REASON_CODE) {
                MinecraftConnectionStage.SESSION_EXPIRED
            } else {
                MinecraftConnectionStage.AUTHENTICATION_FAILED
            }

        !paired -> MinecraftConnectionStage.NOT_PAIRED
        connected == null -> MinecraftConnectionStage.DISCONNECTED
        resolution == null -> MinecraftConnectionStage.DETECTING_RUNTIME
        MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED in resolution.reasonCodes ->
            MinecraftConnectionStage.RUNTIME_CHANGED

        !resolution.detection.isDetected -> MinecraftConnectionStage.RUNTIME_UNAVAILABLE
        compatibility?.status == MinecraftCompatibilityStatus.SUPPORTED -> MinecraftConnectionStage.COMPATIBLE
        compatibility?.status == MinecraftCompatibilityStatus.EXPERIMENTAL -> MinecraftConnectionStage.EXPERIMENTAL
        else -> MinecraftConnectionStage.INCOMPATIBLE
    }

    val canBuild = stage == MinecraftConnectionStage.COMPATIBLE && resolution?.canExecute == true

    val headline: String
    val detail: String
    val tone: CraftMindTone
    val badge: String?
    val badgeTone: CraftMindTone
    val unavailableReason: String?

    when (stage) {
        MinecraftConnectionStage.LOADING_PROFILE -> {
            headline = "Reading the saved bridge setup"
            detail = "CraftMind is loading the bridge profile stored in app-private storage on this device."
            tone = CraftMindTone.NEUTRAL
            badge = null
            badgeTone = CraftMindTone.NEUTRAL
            unavailableReason = "The bridge profile has not finished loading."
        }

        MinecraftConnectionStage.NOT_PAIRED -> {
            headline = "No Minecraft bridge paired"
            detail = "AI plan generation and local build history work without a bridge. Pair a supported " +
                "private-LAN Minecraft server when you want to build a plan in your world."
            tone = CraftMindTone.NEUTRAL
            badge = "Not paired"
            badgeTone = CraftMindTone.NEUTRAL
            unavailableReason = "Building needs a paired, authenticated bridge; none is saved on this device."
        }

        MinecraftConnectionStage.CONNECTING -> {
            headline = "Connecting to the bridge"
            detail = "Pairing verifies the TLS pin and completes authentication before any runtime data is trusted. " +
                "Nothing is assumed about the Minecraft runtime during this step."
            tone = CraftMindTone.INFORMATIVE
            badge = "Connecting"
            badgeTone = CraftMindTone.INFORMATIVE
            unavailableReason = "A bridge connection is in progress."
        }

        MinecraftConnectionStage.AUTHENTICATION_FAILED -> {
            val reasonCode = (connection as? BridgeConnectionState.Error)?.reasonCode ?: "unknown"
            headline = "Bridge connection failed"
            detail = "The bridge refused this session ($reasonCode). CraftMind does not fall back to an unauthenticated " +
                "or differently pinned connection, so runtime detection and building stay unavailable."
            tone = CraftMindTone.NEGATIVE
            badge = "Connection failed"
            badgeTone = CraftMindTone.NEGATIVE
            unavailableReason = "Authentication failed; no runtime report can be trusted."
        }

        MinecraftConnectionStage.SESSION_EXPIRED -> {
            headline = "Bridge session expired"
            detail = "The authenticated session is no longer valid. Reconnect to re-run runtime detection, adapter " +
                "selection, and compatibility resolution; the previous resolution is not reused."
            tone = CraftMindTone.CAUTION
            badge = "Session expired"
            badgeTone = CraftMindTone.CAUTION
            unavailableReason = "The authenticated session expired; building requires a live session."
        }

        MinecraftConnectionStage.DISCONNECTED -> {
            val name = state.profile?.displayName ?: "the paired bridge"
            headline = "Disconnected"
            detail = "This device is paired with $name, but no session is active. Reconnect to detect the runtime and " +
                "resolve compatibility again."
            tone = CraftMindTone.NEUTRAL
            badge = "Disconnected"
            badgeTone = CraftMindTone.NEUTRAL
            unavailableReason = "No active bridge session; the runtime cannot be detected."
        }

        MinecraftConnectionStage.DETECTING_RUNTIME -> {
            headline = "Authenticated — waiting for a runtime report"
            detail = "The bridge session is authenticated. CraftMind detects the Minecraft runtime only from that " +
                "authenticated report; until it arrives, no edition, version, or loader is assumed."
            tone = CraftMindTone.INFORMATIVE
            badge = "Authenticated"
            badgeTone = CraftMindTone.INFORMATIVE
            unavailableReason = "No authenticated runtime report has been resolved for this session."
        }

        MinecraftConnectionStage.RUNTIME_CHANGED -> {
            headline = "Runtime changed"
            detail = "The authenticated Minecraft runtime changed after compatibility was resolved. Detection, adapter " +
                "selection, and compatibility resolution are re-run for the new runtime; the earlier authorization no " +
                "longer applies."
            tone = CraftMindTone.CAUTION
            badge = "Runtime changed"
            badgeTone = CraftMindTone.CAUTION
            unavailableReason = "The runtime changed after resolution; eligibility must be established again."
        }

        MinecraftConnectionStage.RUNTIME_UNAVAILABLE -> {
            headline = "Runtime unavailable"
            detail = resolution?.availabilityLabel()
                ?: "The authenticated bridge did not report a usable Minecraft runtime."
            tone = CraftMindTone.NEGATIVE
            badge = "Not detected"
            badgeTone = CraftMindTone.NEGATIVE
            unavailableReason = resolution?.availabilityLabel() ?: "Runtime detection did not complete."
        }

        MinecraftConnectionStage.COMPATIBLE -> {
            headline = if (canBuild) "Compatible — building available" else "Compatible — building blocked"
            detail = if (canBuild) {
                "The runtime was detected automatically, exactly one adapter matched, and the resolved binding " +
                    "authorizes execution. A server preflight and your separate confirmation are still required " +
                    "before any block is placed."
            } else {
                "The runtime is supported, but the resolved compatibility result does not authorize execution. " +
                    "The reason is listed below; CraftMind never widens a limit or substitutes another runtime."
            }
            tone = if (canBuild) CraftMindTone.POSITIVE else CraftMindTone.CAUTION
            badge = if (canBuild) "Compatible · can build" else "Supported · building blocked"
            badgeTone = if (canBuild) CraftMindTone.POSITIVE else CraftMindTone.CAUTION
            unavailableReason = if (canBuild) null else resolution?.availabilityLabel()
        }

        MinecraftConnectionStage.EXPERIMENTAL -> {
            headline = "Experimental runtime"
            detail = "This runtime is recognized but experimental: runtime certification has not been performed, so " +
                "building is unavailable. Nothing here implies the runtime was tested."
            tone = CraftMindTone.CAUTION
            badge = "Experimental"
            badgeTone = CraftMindTone.CAUTION
            unavailableReason = resolution?.availabilityLabel()
                ?: "Experimental runtimes are not authorized for construction."
        }

        MinecraftConnectionStage.INCOMPATIBLE -> {
            headline = "Incompatible runtime"
            detail = "The detected runtime does not match a registered CraftMind compatibility profile. CraftMind does " +
                "not approximate a nearby version or cross editions, so building is unavailable."
            tone = CraftMindTone.NEGATIVE
            badge = "Incompatible"
            badgeTone = CraftMindTone.NEGATIVE
            unavailableReason = resolution?.availabilityLabel() ?: "No registered profile matches this runtime."
        }
    }

    val detectionLine = resolution
        ?.takeIf { !it.detection.isDetected }
        ?.detection
        ?.detectionStatusLabel()
        ?.let { label -> listOf("Detection: $label") }
        .orEmpty()

    return MinecraftConnectionSummary(
        stage = stage,
        headline = headline,
        detail = detail,
        tone = tone,
        badge = badge,
        badgeTone = badgeTone,
        runtimeLines = runtimeLines + detectionLine,
        certificationLines = certificationLines,
        diagnosticLines = diagnosticLines,
        canBuild = canBuild,
        unavailableReason = unavailableReason,
        isBusy = state.isWorking,
        busyLabel = busyLabel,
        actions = actions,
    )
}

/**
 * The gates every build passes before a block is placed, in order.
 *
 * Shown on the Minecraft screen so an unavailable state is understandable instead of mysterious. Each gate names real
 * behaviour implemented in `domain/minecraft/compatibility` and `data/minecraft`.
 */
val minecraftAuthorizationGates: List<String> = listOf(
    "1. Detection — the runtime is read from the authenticated bridge report only; nothing is guessed or defaulted.",
    "2. Adapter selection — exactly one registered adapter must match; an ambiguous match fails closed.",
    "3. Compatibility and limits — capabilities and limits are resolved as min(global, runtime); a missing limit fails closed.",
    "4. Authorization — a session-bound execution binding must authorize construction, and the bridge re-checks it at preflight.",
)
