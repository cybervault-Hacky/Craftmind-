package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge

data class BridgePairingState(
    val host: String = "",
    val port: String = "19872",
    val tlsFingerprint: String = "",
    val pairingCode: String = "",
    val profile: TrustedMinecraftBridge? = null,
    val isProfileLoaded: Boolean = false,
    val connection: BridgeConnectionState = BridgeConnectionState.Disconnected,
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
