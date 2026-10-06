package com.craftmind.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.minecraft.BridgeConnectionState

@Composable
internal fun MinecraftBridgeSettingsContent(
    state: BridgePairingState,
    onEvent: (BridgePairingEvent) -> Unit,
) {
    if (!state.isProfileLoaded) {
        Text(
            "Checking saved Minecraft bridge setup…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (state.profile == null) {
        Text("No Minecraft bridge is paired.", style = MaterialTheme.typography.titleMedium)
        Text(
            "AI plan generation and local build history remain available. Pairing is optional and only needed to request an in-game build from an accepted plan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "On the Minecraft server, verify the public TLS fingerprint with /craftmind identity, then open a private one-time window with /craftmind pair open. Enter that fingerprint and the code shown only to the in-game operator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.host,
            onValueChange = { onEvent(BridgePairingEvent.HostChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server private IPv4 address") },
            placeholder = { Text("192.168.1.20") },
            singleLine = true,
            enabled = !state.isWorking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = { onEvent(BridgePairingEvent.PortChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bridge port") },
            singleLine = true,
            enabled = !state.isWorking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.tlsFingerprint,
            onValueChange = { onEvent(BridgePairingEvent.FingerprintChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TLS SHA-256 fingerprint") },
            supportingText = { Text("Compare out-of-band with /craftmind identity. Identity changes are never silently accepted.") },
            singleLine = true,
            enabled = !state.isWorking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = state.pairingCode,
            onValueChange = { onEvent(BridgePairingEvent.PairingCodeChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("One-time pairing code") },
            singleLine = true,
            enabled = !state.isWorking,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        Button(
            onClick = { onEvent(BridgePairingEvent.Pair) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isWorking && state.pairingCode.isNotBlank(),
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Pair device and verify bridge")
            }
        }
    } else {
        val profile = state.profile
        val connected = state.connection as? BridgeConnectionState.Connected
        Text(
            text = when (state.connection) {
                is BridgeConnectionState.Connected -> "Authenticated session · pinned HTTPS"
                BridgeConnectionState.Connecting -> "Authenticating with the saved bridge identity"
                is BridgeConnectionState.Error -> "Not connected · ${state.connection.reasonCode}"
                BridgeConnectionState.Disconnected -> "Paired device saved · not currently connected"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (connected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text("${profile.host}:${profile.port} · ${profile.displayName}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Bridge ID: ${profile.bridgeId}\nClient ID: ${profile.clientId}\nTLS SHA-256: ${profile.tlsFingerprint}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        connected?.capabilities?.let { capabilities ->
            val runtime = capabilities.runtimeDescriptor
            val compatibility = state.compatibility
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MinecraftRuntimeCompatibilityBlock(
                    runtime = runtime,
                    compatibility = compatibility,
                )
                if (!runtime.isBedrock) {
                    Text(
                        "Registered Java profile requirement: " +
                            (compatibility?.limits?.javaRuntimeRequirement?.let { requirement ->
                                "Java ${requirement.requiredMajor} (supported ${requirement.minimumSupportedMajor}–${requirement.maximumSupportedMajor})"
                            } ?: "not matched"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Available limits: ${compatibility?.limits?.maximumValidatedOperations?.let { "$it operations" } ?: "operation limit unknown"} · ${compatibility?.limits?.maximumRequestBytes?.let { "$it request bytes" } ?: "request limit unknown"} · ${compatibility?.limits?.maximumOperationsPerTick?.let { "$it operations/tick" } ?: "per-tick limit unknown"} · ${compatibility?.limits?.maximumExecutionSeconds?.let { "$it seconds" } ?: "execution timeout unknown"} · ${compatibility?.limits?.maximumDimensions?.let { "${it.width}×${it.height}×${it.depth} blocks" } ?: "dimension limit unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("World access: ${if (capabilities.worldAccess) "reported on" else "off"}", style = MaterialTheme.typography.bodySmall)
                Text("Construction: ${if (capabilities.constructionExecute) "reported on" else "disabled"}", style = MaterialTheme.typography.bodySmall)
                Text("Cancellation: ${if (capabilities.cancellation) "reported available" else "unavailable"}", style = MaterialTheme.typography.bodySmall)
                Text("Current origin dimension: ${capabilities.dimensionId ?: "not selected"}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Pairing authenticates and pins this bridge, but does not establish compatibility. BuildPlan v2 is sent only when the resolver returns SUPPORTED, capability and limit checks pass, server preflight succeeds, and you explicitly confirm. Provider keys remain on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onEvent(BridgePairingEvent.Connect) },
                enabled = !state.isWorking && connected == null,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isWorking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (state.connection is BridgeConnectionState.Error) "Reconnect" else "Connect")
            }
            OutlinedButton(
                onClick = { onEvent(BridgePairingEvent.Disconnect) },
                enabled = !state.isWorking && connected != null,
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect") }
        }
        OutlinedButton(
            onClick = { onEvent(BridgePairingEvent.RefreshCapabilities) },
            enabled = !state.isWorking && connected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Refresh authenticated capabilities")
        }
        OutlinedButton(
            onClick = { onEvent(BridgePairingEvent.RequestRevoke) },
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Revoke this device at the bridge")
        }
        TextButton(
            onClick = { onEvent(BridgePairingEvent.RequestForget) },
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Forget saved bridge on this device") }
    }

    state.message?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.connection is BridgeConnectionState.Error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.showRevokeConfirmation) {
        AlertDialog(
            onDismissRequest = { onEvent(BridgePairingEvent.DismissRevoke) },
            title = { Text("Revoke this Android device?") },
            text = {
                Text("CraftMind will authenticate to the pinned bridge, remove this public key from its trusted-client store, and invalidate this device's active sessions. You will need to pair again to reconnect.")
            },
            confirmButton = {
                TextButton(onClick = { onEvent(BridgePairingEvent.ConfirmRevoke) }) { Text("Revoke device") }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(BridgePairingEvent.DismissRevoke) }) { Text("Cancel") }
            },
        )
    }
    if (state.showForgetConfirmation) {
        AlertDialog(
            onDismissRequest = { onEvent(BridgePairingEvent.DismissForget) },
            title = { Text("Forget saved bridge locally?") },
            text = {
                Text("This only deletes this phone's saved endpoint and Android Keystore key. It does not revoke the bridge's trusted-client record. Prefer Revoke while online; otherwise ask a Minecraft operator to run /craftmind pair revoke ${state.profile?.clientId ?: "<clientId>"}.")
            },
            confirmButton = {
                TextButton(onClick = { onEvent(BridgePairingEvent.ConfirmForget) }) { Text("Forget locally") }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(BridgePairingEvent.DismissForget) }) { Text("Cancel") }
            },
        )
    }
}
