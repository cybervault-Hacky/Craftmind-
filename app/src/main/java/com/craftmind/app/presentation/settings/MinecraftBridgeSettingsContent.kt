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
    if (state.profile == null) {
        Text(
            "On the Minecraft server, verify the public TLS fingerprint with /craftmind identity, then open a private one-time window with /craftmind pair open. Enter that fingerprint and the code shown only to the in-game operator.",
            style = MaterialTheme.typography.bodyMedium,
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
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${capabilities.minecraftVersion} · ${capabilities.loaderName} ${capabilities.loaderVersion} · bridge ${capabilities.bridgeVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("World access: ${if (capabilities.worldAccess) "reported on" else "off"}", style = MaterialTheme.typography.bodySmall)
                Text("Build execution: ${if (capabilities.constructionExecute) "reported available" else "disabled"}", style = MaterialTheme.typography.bodySmall)
                Text("Cancellation: ${if (capabilities.cancellation) "reported available" else "unavailable"}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Capabilities come from the pinned-HTTPS response to a signed authenticated request. Session status clears locally before the bridge's five-minute idle expiry. This verifies identity and reports capabilities only; CraftMind does not transmit a plan or construct in Minecraft.",
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
