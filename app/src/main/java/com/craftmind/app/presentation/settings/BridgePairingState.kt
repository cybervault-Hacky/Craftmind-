package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeResolution

data class BridgePairingState(
    val host: String = "",
    val port: String = "19872",
    val tlsFingerprint: String = "",
    val pairingCode: String = "",
    val profile: TrustedMinecraftBridge? = null,
    val isProfileLoaded: Boolean = false,
    val connection: BridgeConnectionState = BridgeConnectionState.Disconnected,
    val compatibility: MinecraftCompatibilityResult? = null,
    /**
     * Phase 13 automatic runtime pipeline result for the currently authenticated session: detection, adapter
     * selection, compatibility resolution, and the session-bound execution binding. It is display information;
     * execution is authorized again by the bridge repository before any BuildPlan is sent.
     */
    val runtimeResolution: MinecraftRuntimeResolution? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
    val showRevokeConfirmation: Boolean = false,
    val showForgetConfirmation: Boolean = false,
)

sealed interface BridgePairingEvent {
    data class HostChanged(val value: String) : BridgePairingEvent
    data class PortChanged(val value: String) : BridgePairingEvent
    data class FingerprintChanged(val value: String) : BridgePairingEvent
    data class PairingCodeChanged(val value: String) : BridgePairingEvent
    data object Pair : BridgePairingEvent
    data object Connect : BridgePairingEvent
    data object RefreshCapabilities : BridgePairingEvent
    data object Disconnect : BridgePairingEvent
    data object RequestRevoke : BridgePairingEvent
    data object ConfirmRevoke : BridgePairingEvent
    data object DismissRevoke : BridgePairingEvent
    data object RequestForget : BridgePairingEvent
    data object ConfirmForget : BridgePairingEvent
    data object DismissForget : BridgePairingEvent
    data object DismissMessage : BridgePairingEvent
}
