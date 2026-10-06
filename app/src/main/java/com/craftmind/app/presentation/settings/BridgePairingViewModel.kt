package com.craftmind.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.BuildConfig
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeResolution
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BridgePairingViewModel(
    private val bridge: MinecraftBridgePairingRepository,
    private val compatibilityResolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BridgePairingState(isProfileLoaded = false))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            bridge.profile.collect { profile -> mutableState.update { it.copy(profile = profile, isProfileLoaded = true) } }
        }
        viewModelScope.launch {
            bridge.connectionState.collect { connection ->
                val resolution = resolveRuntime(connection)
                mutableState.update {
                    it.copy(
                        connection = connection,
                        compatibility = resolution?.compatibility,
                        runtimeResolution = resolution,
                    )
                }
            }
        }
    }

    fun dispatch(event: BridgePairingEvent) {
        when (event) {
            is BridgePairingEvent.HostChanged -> mutableState.update { it.copy(host = event.value, message = null) }
            is BridgePairingEvent.PortChanged -> mutableState.update { it.copy(port = event.value.filter(Char::isDigit).take(5), message = null) }
            is BridgePairingEvent.FingerprintChanged -> mutableState.update { it.copy(tlsFingerprint = event.value.take(95), message = null) }
            is BridgePairingEvent.PairingCodeChanged -> mutableState.update { it.copy(pairingCode = event.value.filter { char -> char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' }.take(64), message = null) }
            BridgePairingEvent.Pair -> pair()
            BridgePairingEvent.Connect -> connect()
            BridgePairingEvent.RefreshCapabilities -> refreshCapabilities()
            BridgePairingEvent.Disconnect -> disconnect()
            BridgePairingEvent.RequestRevoke -> mutableState.update { it.copy(showRevokeConfirmation = true) }
            BridgePairingEvent.ConfirmRevoke -> revoke()
            BridgePairingEvent.DismissRevoke -> mutableState.update { it.copy(showRevokeConfirmation = false) }
            BridgePairingEvent.RequestForget -> mutableState.update { it.copy(showForgetConfirmation = true) }
            BridgePairingEvent.ConfirmForget -> forgetLocally()
            BridgePairingEvent.DismissForget -> mutableState.update { it.copy(showForgetConfirmation = false) }
            BridgePairingEvent.DismissMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    private fun pair() {
        val current = mutableState.value
        val port = current.port.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            mutableState.update { it.copy(message = "Enter a port from 1024 to 65535.") }
            return
        }
        val code = current.pairingCode.trim()
        mutableState.update { it.copy(isWorking = true, pairingCode = "", message = null) }
        viewModelScope.launch {
            try {
                bridge.pair(current.host.trim(), port, current.tlsFingerprint.trim(), code)
                mutableState.update {
                    it.copy(isWorking = false, message = "Device paired. TLS identity and authentication are separate from runtime compatibility; pairing alone never enables construction.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isWorking = false, message = describe(error)) }
            }
        }
    }

    private fun connect() {
        mutableState.update { it.copy(isWorking = true, message = null) }
        viewModelScope.launch {
            try {
                bridge.connect()
                mutableState.update {
                    it.copy(isWorking = false, message = "Secure bridge session authenticated. Capability status is reported by the pinned server.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isWorking = false, message = describe(error)) }
            }
        }
    }

    private fun refreshCapabilities() {
        if (mutableState.value.connection !is BridgeConnectionState.Connected) return
        mutableState.update { it.copy(isWorking = true, message = null) }
        viewModelScope.launch {
            try {
                bridge.refreshCapabilities()
                val resolution = resolveRuntime(bridge.connectionState.value)
                val compatibility = resolution?.compatibility
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        compatibility = compatibility,
                        runtimeResolution = resolution,
                        message = when {
                            resolution == null ->
                                "Capabilities refreshed. No authenticated runtime report is available, so nothing was detected and construction stays unavailable."
                            compatibility?.let { it.status == MinecraftCompatibilityStatus.SUPPORTED && it.canExecute } == true ->
                                "Capabilities refreshed. ${resolution.detection.detectedEdition.displayName} " +
                                    "${resolution.detection.detectedVersion.displayIdentifier} was detected automatically and the exact adapter " +
                                    "${resolution.selection.adapterId?.value ?: "none"} was selected."
                            compatibility?.status == MinecraftCompatibilityStatus.SUPPORTED ->
                                "Capabilities refreshed. The runtime is supported, but one or more runtime capabilities or limits are not ready for construction."
                            else -> "Capabilities refreshed. ${resolution.detection.detectionStatusLabel()}; " +
                                "no BuildPlan will be sent for execution.",
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isWorking = false, message = describe(error)) }
            }
        }
    }

    private fun disconnect() {
        mutableState.update { it.copy(isWorking = true, message = null) }
        viewModelScope.launch {
            try {
                bridge.disconnect()
                mutableState.update { it.copy(isWorking = false, message = "Bridge session closed and local session state cleared.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val localOnly = error is MinecraftBridgeFailure && error.reasonCode == "LOCAL_DISCONNECT_ONLY"
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        message = if (localOnly) {
                            "Local session state cleared. The bridge could not confirm closure; its session expires after inactivity."
                        } else describe(error),
                    )
                }
            }
        }
    }

    private fun revoke() {
        mutableState.update { it.copy(isWorking = true, showRevokeConfirmation = false, message = null) }
        viewModelScope.launch {
            try {
                bridge.revoke()
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        host = "",
                        port = "19872",
                        tlsFingerprint = "",
                        pairingCode = "",
                        message = "This device was revoked by the bridge. Its active sessions are invalidated.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isWorking = false, message = describe(error)) }
            }
        }
    }

    private fun forgetLocally() {
        mutableState.update { it.copy(isWorking = true, showForgetConfirmation = false, message = null) }
        viewModelScope.launch {
            try {
                bridge.forgetLocally()
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        host = "",
                        port = "19872",
                        tlsFingerprint = "",
                        pairingCode = "",
                        message = "Saved bridge and this device's private key were removed locally. The server may still list this client; revoke it there if reachable.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isWorking = false, message = describe(error)) }
            }
        }
    }

    /**
     * Runs the Phase 13 pipeline for the currently authenticated session: detection → descriptor validation →
     * deterministic adapter selection → compatibility resolution. The user never selects an edition, version,
     * loader, or adapter, and a cached descriptor is never reused: this is recomputed for every connection state
     * change and every capability refresh.
     */
    private fun resolveRuntime(connection: BridgeConnectionState): MinecraftRuntimeResolution? =
        (connection as? BridgeConnectionState.Connected)?.let { connected ->
            compatibilityResolver.runtimeGate.resolveRuntime(connected.runtimeReport(BuildConfig.VERSION_NAME))
        }

    private fun describe(error: Exception): String {
        val code = (error as? MinecraftBridgeFailure)?.reasonCode ?: "BRIDGE_OPERATION_FAILED"
        return when (code) {
            "BRIDGE_ADDRESS_MUST_BE_PRIVATE_IPV4" -> "Use the server's private IPv4 address. Hostnames, public addresses, and IPv6 are not accepted."
            "BRIDGE_PORT_INVALID" -> "The bridge port must be between 1024 and 65535."
            "BRIDGE_FINGERPRINT_INVALID" -> "Enter the 64-hex SHA-256 TLS fingerprint shown by /craftmind identity."
            "PAIRING_CODE_INVALID" -> "Enter the current one-time pairing code from the Minecraft operator."
            "PAIRING_CLOSED" -> "The Minecraft operator has not opened a pairing window. Run /craftmind pair open in game."
            "PAIRING_EXPIRED", "PAIRING_REJECTED" -> "Pairing was rejected or expired. Verify the one-time code and try again."
            "ALREADY_PAIRED" -> "This device is already trusted by the bridge. Connect, or revoke it before pairing again."
            "BRIDGE_ALREADY_CONFIGURED" -> "A bridge is already saved. Revoke it or explicitly forget it before pairing another."
            "BRIDGE_IDENTITY_MISMATCH", "BRIDGE_IDENTITY_CHANGED" -> "The TLS identity does not match the fingerprint you confirmed. Verify /craftmind identity; the changed identity was not trusted."
            "BRIDGE_CAPABILITIES_UNSUPPORTED", "BRIDGE_CAPABILITIES_INVALID" -> "The bridge reported an unsupported or malformed capability set. No build can be sent."
            "RUNTIME_DETECTION_UNAUTHORIZED" -> "Runtime detection needs an authenticated bridge session. Connect again; nothing was detected and no build can be sent."
            "SESSION_IDENTITY_MISMATCH" -> "The authenticated bridge session or identity changed, so the previous runtime compatibility was invalidated. Reconnect to detect the runtime again."
            "RUNTIME_IDENTITY_CHANGED" -> "The authenticated Minecraft runtime changed after compatibility was resolved. The previous execution eligibility was discarded; detection and compatibility resolution run again."
            "APP_VERSION_MISMATCH" -> "The bridge echoed a different CraftMind app version. Update the app and the bridge together; CraftMind never silently downgrades the protocol."
            "RUNTIME_DESCRIPTOR_INVALID" -> "The runtime report contradicts itself or the bridge protocol, so it was rejected instead of corrected. No build can be sent."
            "RUNTIME_UNKNOWN", "RUNTIME_INCOMPLETE" -> "The bridge did not report enough authoritative runtime information. CraftMind never guesses a version, and no build can be sent."
            "AMBIGUOUS_ADAPTER_MATCH" -> "More than one registered adapter claims this exact runtime, so adapter selection is blocked instead of choosing one."
            "WORLD_SESSION_CHANGED" -> "The bridge world session changed, so prepared work was invalidated. Prepare the build again from the current world."
            "BRIDGE_UPDATE_REQUIRED", "BRIDGE_PROTOCOL_UNSUPPORTED" -> "This server uses the older Bridge 1.1.0 / protocol 1 profile. Update CraftMind and the server bridge together to the matching app plus Bridge 1.2.0 / protocol 2, then reconnect. The saved pairing, device identity, and server trusted-client record are preserved; runtime versions are never inferred."
            "BRIDGE_TIMEOUT" -> "The bridge did not respond before the timeout. Check the private-LAN address and server listener."
            "BRIDGE_UNAVAILABLE" -> "Could not reach the bridge over HTTPS. Check the private network, firewall, and server status."
            "BRIDGE_NOT_PAIRED" -> "No trusted bridge is saved on this device. Pair it with an open operator window first."
            "LOCAL_DISCONNECT_ONLY" -> "Local session state cleared. The bridge could not confirm closure; its session expires after inactivity."
            "LOCAL_PAIRING_SAVE_FAILED_AFTER_SERVER_PAIR" -> "The server accepted pairing, but CraftMind could not save the local profile. Ask the operator to run /craftmind pair list and revoke the new client before retrying."
            "UNAUTHENTICATED" -> "The bridge no longer trusts this device or its session expired. Re-pair only after verifying the server fingerprint."
            else -> "Bridge operation failed ($code). Review the Minecraft operator setup and retry."
        }
    }
}
