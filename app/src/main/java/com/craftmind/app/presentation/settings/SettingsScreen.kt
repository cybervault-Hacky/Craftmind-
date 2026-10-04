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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Icon
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

private enum class SettingsInfo {
    MINECRAFT,
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    providerState: ProviderSettingsState,
    onProviderEvent: (ProviderSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openInfo by remember { mutableStateOf<SettingsInfo?>(null) }

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
            title = "Minecraft",
            subtitle = "Plan review is available; world pairing and block execution are not implemented.",
        ) {
            SettingsActionRow(
                icon = Icons.Default.Info,
                title = "World connection",
                subtitle = "Not implemented in Phase 2",
                onClick = { openInfo = SettingsInfo.MINECRAFT },
            )
        }

        SettingsCard(
            title = "About",
            subtitle = "CraftMind · AI Minecraft Builder",
        ) {
            Text("Describe it. Show it. Build it.", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Phase 2 · AI plan generation and review",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Prompts go directly to the selected provider over HTTPS. Keys are encrypted with Android Keystore and are never sent to CraftMind. Image and URL references are preserved locally but not analyzed. Minecraft execution is not available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }

    openInfo?.let { info ->
        val (title, message) = when (info) {
            SettingsInfo.MINECRAFT -> "Minecraft is not connected" to
                "CraftMind can show a successfully generated and validated plan for review. It does not pair with Minecraft or place blocks in a world."
        }
        AlertDialog(
            onDismissRequest = { openInfo = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { openInfo = null }) { Text("Got it") } },
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
                Text("Choose model", style = MaterialTheme.typography.titleSmall)
                Column {
                    OutlinedButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = !state.isSavingSelection,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        val selected = state.models.firstOrNull { it.id == state.selectedModelId }
                        Text(selected?.displayName ?: "Select a verified model")
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
                    text = "Text generation with JSON output is supported. Vision, URL fetching, and image analysis are not supported by this adapter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = "More information about $title",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
