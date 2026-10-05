package com.craftmind.app.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.settings.ThemeMode

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    providerState: ProviderSettingsState,
    onProviderEvent: (ProviderSettingsEvent) -> Unit,
    bridgeState: BridgePairingState = BridgePairingState(),
    onBridgeEvent: (BridgePairingEvent) -> Unit = {},
    modifier: Modifier = Modifier,
) {

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "Configure an AI provider, its key, and a verified model independently.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(
            title = "Appearance",
            subtitle = "Choose how CraftMind looks on this device.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ThemeMode.entries.forEach { option ->
                    val selected = themeMode == option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.RadioButton, onClick = { onThemeModeSelected(option) })
                            .semantics { this.selected = selected }
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = option.label(),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }

        ProviderSettingsCard(state = providerState, onEvent = onProviderEvent)

        SettingsCard(
            title = "Minecraft bridge",
            subtitle = "Pair this Android device with a private-LAN Fabric server. Pairing verifies identity; a BuildPlan can be sent only from plan review after preflight and a separate final confirmation.",
        ) {
            MinecraftBridgeSettingsContent(state = bridgeState, onEvent = onBridgeEvent)
        }

        SettingsCard(
            title = "About",
            subtitle = "CraftMind · AI Minecraft Builder",
        ) {
            Text("Describe it. Review it. Refine it.", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Phase 7 · bounded public-video analysis · Phase 5 construction safety",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "AI requests go directly to the selected provider over HTTPS; provider keys never enter the Minecraft bridge. The bridge uses pinned private-LAN HTTPS and an Android Keystore device key. Accepted plan versions remain immutable; separate execution records report only bridge-reported status. Block placement requires server preflight and a separate final confirmation, and cannot be rolled back by CraftMind.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }

}

@Composable
private fun ProviderSettingsCard(
    state: ProviderSettingsState,
    onEvent: (ProviderSettingsEvent) -> Unit,
) {
    val activeProvider = state.providers.firstOrNull { it.id == state.activeProviderId }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    SettingsCard(
        title = "AI provider",
        subtitle = "Keys belong to providers. Model choice is stored separately and requires a live connection test.",
    ) {
        if (state.providers.size > 1) {
            Column {
                OutlinedButton(onClick = { providerMenuExpanded = true }) {
                    Text(activeProvider?.displayName ?: "Choose provider")
                }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                ) {
                    state.providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                providerMenuExpanded = false
                                onEvent(ProviderSettingsEvent.SelectProvider(provider.id))
                            },
                        )
                    }
                }
            }
        } else if (activeProvider != null) {
            Text(activeProvider.displayName, style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                "No supported provider adapter is installed.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        activeProvider?.let { provider ->
            Text(
                text = if (state.savedCredentialExists) {
                    "An API key is saved encrypted on this device. Its value is never displayed."
                } else {
                    "No saved API key. Add your provider-owned key below."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.keyDraft,
                onValueChange = { onEvent(ProviderSettingsEvent.KeyDraftChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.savedCredentialExists) "Replace API key" else "Provider API key") },
                placeholder = { Text("Paste a key from ${provider.displayName}") },
                singleLine = true,
                enabled = !state.isSavingCredential,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onEvent(ProviderSettingsEvent.SaveCredential) },
                    enabled = state.keyDraft.isNotBlank() && !state.isSavingCredential,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.isSavingCredential) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (state.savedCredentialExists) "Replace key" else "Save key")
                    }
                }
                if (state.savedCredentialExists) {
                    OutlinedButton(
                        onClick = { onEvent(ProviderSettingsEvent.RemoveCredential) },
                        enabled = !state.isSavingCredential,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Remove key") }
                }
            }

            OutlinedButton(
                onClick = { onEvent(ProviderSettingsEvent.TestConnection) },
                enabled = state.savedCredentialExists &&
                    !state.isSavingCredential && !state.isSavingSelection &&
                    state.connection !is ProviderConnectionState.Testing,
                shape = RoundedCornerShape(14.dp),
            ) {
                if (state.connection is ProviderConnectionState.Testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Testing provider…")
                } else {
                    Text("Test connection")
                }
            }

            ConnectionStatus(state.connection)

            if (state.connection is ProviderConnectionState.Verified) {
                val selectedModel = state.models.firstOrNull { it.id == state.selectedModelId }
                Text("Choose model", style = MaterialTheme.typography.titleSmall)
                Column {
                    OutlinedButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = !state.isSavingSelection,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(selectedModel?.displayName ?: "Select a verified model")
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName)
                                        Text(model.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            when {
                                                model.capabilities.vision && model.capabilities.multipleImages -> "Text + one-image and multi-frame video vision"
                                                model.capabilities.vision -> "Text + one-image vision · multi-frame video unavailable"
                                                else -> "Text only · image/video analysis unavailable"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    modelMenuExpanded = false
                                    onEvent(ProviderSettingsEvent.SelectModel(model.id))
                                },
                            )
                        }
                    }
                }
                Text(
                    text = if (provider.capabilities.vision) {
                        "Image requests use one locally prepared JPEG, PNG, or WebP. Video requests are separately limited to direct HTTPS MP4/WebM files on raw.githubusercontent.com: byte ranges only, no redirects, and at most five sampled frames. A model must be individually labeled Multi-image Vision; frames go directly to that selected provider."
                    } else {
                        "This provider adapter does not support image or video-frame analysis."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val semanticRefinementSupported = provider.capabilities.textGeneration &&
                    provider.capabilities.structuredOutput == com.craftmind.app.domain.ai.StructuredOutputMode.JSON_MIME_TYPE &&
                    selectedModel != null &&
                    selectedModel.capabilities.textGeneration &&
                    selectedModel.capabilities.structuredOutput == com.craftmind.app.domain.ai.StructuredOutputMode.JSON_MIME_TYPE
                Text(
                    text = when {
                        selectedModel == null -> "Select a model to check the text and JSON capabilities required for semantic refinement."
                        semanticRefinementSupported -> "Selected model supports the required text and structured JSON capabilities for semantic refinement."
                        else -> "Selected model lacks a required text or JSON capability. CraftMind will reject refinement without switching or downgrading."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (semanticRefinementSupported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedModel?.let { model ->
                    Text(
                        when {
                            model.capabilities.vision && model.capabilities.multipleImages -> "Selected model is verified for one-image input and bounded multi-image video-frame analysis. Video bytes stay on-device except for bounded HTTPS range reads; sampled images are sent directly to this provider. Refinement uses saved text notes only and never re-downloads the video. Provider retention is governed by its terms."
                            model.capabilities.vision -> "Selected model is verified for one-image input only. Video analysis is blocked unless you select a model labeled Multi-image Vision."
                            else -> "Selected model is text-only for visual references. Image/video analysis is blocked until you choose a model labeled Vision."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (model.capabilities.vision) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.message?.let { message ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatus(state: ProviderConnectionState) {
    val text = when (state) {
        ProviderConnectionState.Unverified -> "Not verified. Run a live test to check the saved key and model list."
        ProviderConnectionState.Testing -> "A real provider request is in progress."
        is ProviderConnectionState.Verified -> "Verified by live API response · ${state.compatibleModelCount} compatible model(s)."
        is ProviderConnectionState.Failed -> "Connection test failed (${state.code.name.lowercase().replace('_', ' ')})."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is ProviderConnectionState.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
