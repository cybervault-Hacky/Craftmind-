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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.settings.ThemeMode

private enum class SettingsInfo {
    AI,
    MINECRAFT,
}

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
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
                text = "Preferences and foundations for future phases.",
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
                            .clickable(
                                role = Role.RadioButton,
                                onClick = { onThemeModeSelected(option) },
                            )
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

        SettingsCard(
            title = "AI providers",
            subtitle = "Provider keys and model selection are separate parts of the future AI setup.",
        ) {
            SettingsActionRow(
                icon = Icons.Default.Settings,
                title = "Provider configuration",
                subtitle = "Not available in Phase 1",
                onClick = { openInfo = SettingsInfo.AI },
            )
        }

        SettingsCard(
            title = "Minecraft",
            subtitle = "Connection and execution will be added after AI build-plan validation.",
        ) {
            SettingsActionRow(
                icon = Icons.Default.Info,
                title = "World connection",
                subtitle = "Not available in Phase 1",
                onClick = { openInfo = SettingsInfo.MINECRAFT },
            )
        }

        SettingsCard(
            title = "About",
            subtitle = "CraftMind · AI Minecraft Builder",
        ) {
            Text(
                text = "Describe it. Show it. Build it.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Phase 1 — Foundation/UI",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "Phase 1 does not collect credentials, send prompts, upload images, fetch URLs, or connect to a Minecraft world.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }

    openInfo?.let { info ->
        val (title, message) = when (info) {
            SettingsInfo.AI -> "AI provider setup is coming later" to
                "No API key or model is configured, collected, or stored in Phase 1. The future architecture keeps each provider's credential separate from the selected model."

            SettingsInfo.MINECRAFT -> "Minecraft is not connected" to
                "The bridge contract is being established, but there is no pairing, connection, status, or block execution in this phase."
        }
        AlertDialog(
            onDismissRequest = { openInfo = null },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { openInfo = null }) { Text("Got it") }
            },
        )
    }
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
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
