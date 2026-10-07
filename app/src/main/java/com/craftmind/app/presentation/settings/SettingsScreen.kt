package com.craftmind.app.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDetailLines
import com.craftmind.app.designsystem.CraftMindDestructiveButton
import com.craftmind.app.designsystem.CraftMindDivider
import com.craftmind.app.designsystem.CraftMindExpandableSection
import com.craftmind.app.designsystem.CraftMindKeyValueRow
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindPrimaryButton
import com.craftmind.app.designsystem.CraftMindScreen
import com.craftmind.app.designsystem.CraftMindSecondaryButton
import com.craftmind.app.designsystem.CraftMindSectionHeader
import com.craftmind.app.designsystem.CraftMindShapes
import com.craftmind.app.designsystem.CraftMindStatusBadge
import com.craftmind.app.designsystem.CraftMindTertiaryButton
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.presentation.minecraft.minecraftConnectionSummary
import com.craftmind.app.presentation.navigation.MainDestination

/**
 * Settings (Phase 15 §10).
 *
 * Five groups, in the order a user needs them: Appearance, AI providers, Minecraft, Data, About. Secrets stay masked
 * and are never displayed after saving; credential handling itself is unchanged — the key is still encrypted with an
 * Android Keystore key and stored in app-private no-backup storage.
 *
 * Nothing here duplicates a screen: the Minecraft group summarizes real bridge state and links to the Minecraft
 * destination, and the About group links to the About screen.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    providerState: ProviderSettingsState,
    onProviderEvent: (ProviderSettingsEvent) -> Unit,
    bridgeState: BridgePairingState = BridgePairingState(),
    onBridgeEvent: (BridgePairingEvent) -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenAbout: () -> Unit = {},
    onOpenMinecraft: () -> Unit = {},
) {
    CraftMindScreen(
        title = "Settings",
        eyebrow = "Preferences",
        subtitle = MainDestination.SETTINGS.purpose,
        modifier = modifier,
    ) {
        AppearanceGroup(themeMode = themeMode, onThemeModeSelected = onThemeModeSelected)
        ProviderSettingsCard(state = providerState, onEvent = onProviderEvent)
        MinecraftGroup(
            state = bridgeState,
            onEvent = onBridgeEvent,
            onOpenMinecraft = onOpenMinecraft,
        )
        DataGroup()
        AboutGroup(onOpenAbout = onOpenAbout)
    }
}

@Composable
private fun AppearanceGroup(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
) {
    CraftMindCard {
        CraftMindSectionHeader(
            eyebrow = "Appearance",
            title = "Theme",
            subtitle = "Your choice is saved on this device and applied the next time the app draws.",
        )
        Column {
            ThemeMode.entries.forEach { option ->
                val isSelected = themeMode == option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = CraftMindLayout.minTouchTarget)
                        .clickable(role = Role.RadioButton, onClick = { onThemeModeSelected(option) })
                        .semantics { this.selected = isSelected }
                        .padding(end = CraftMindLayout.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Column(
                        modifier = Modifier.padding(start = CraftMindLayout.sm),
                        verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
                    ) {
                        Text(text = option.label(), style = CraftMindType.bodyLarge)
                        Text(text = option.explanation(), style = CraftMindType.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        CraftMindNotice(
            tone = CraftMindTone.INFORMATIVE,
            message = "Motion follows your device's accessibility settings: when animations are removed, CraftMind " +
                "switches state instantly instead of transitioning. No status is ever replaced by an animation.",
        )
    }
}

@Composable
private fun MinecraftGroup(
    state: BridgePairingState,
    onEvent: (BridgePairingEvent) -> Unit,
    onOpenMinecraft: () -> Unit,
) {
    val summary = remember(state) { minecraftConnectionSummary(state) }
    var controlsExpanded by remember { mutableStateOf(false) }
    CraftMindCard {
        CraftMindSectionHeader(
            eyebrow = "Minecraft",
            title = "Bridge and runtime",
            subtitle = "Pairing is optional: AI plans and local history work without a bridge. Building in game " +
                "needs an authenticated, compatible runtime.",
            trailing = {
                CraftMindStatusBadge(label = summary.badge ?: "Loading", tone = summary.badgeTone)
            },
        )
        Text(
            text = summary.headline,
            style = CraftMindType.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = summary.detail,
            style = CraftMindType.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summary.unavailableReason?.let { reason ->
            CraftMindKeyValueRow(label = "Building", value = reason)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
        ) {
            CraftMindSecondaryButton(text = "Open Minecraft", onClick = onOpenMinecraft)
        }
        CraftMindExpandableSection(
            title = "Pairing and session controls",
            summary = if (controlsExpanded) "Hide the bridge controls" else "Pair, connect, refresh, revoke, or forget",
            expanded = controlsExpanded,
            onToggle = { controlsExpanded = !controlsExpanded },
        ) {
            MinecraftBridgeSettingsContent(state = state, onEvent = onEvent)
        }
    }
}

@Composable
private fun DataGroup() {
    CraftMindCard {
        CraftMindSectionHeader(
            eyebrow = "Data",
            title = "Privacy and storage",
            subtitle = "What leaves this device, what stays on it, and what CraftMind never does.",
        )
        CraftMindDetailLines(
            listOf(
                "Your API key is encrypted with a key in Android Keystore and kept in app-private no-backup storage. " +
                    "It is used for direct provider requests and is never sent to the Minecraft bridge or a CraftMind " +
                    "server.",
                "When you generate a plan, your prompt and any chosen visual input go directly to your AI provider " +
                    "over HTTPS. The provider's terms govern provider-side processing and retention.",
                "For a supported public video reference, this device reads bounded byte ranges and sends at most five " +
                    "sampled frames; the video URL itself is not sent to the model. Refinement uses saved text notes " +
                    "and never re-downloads the video.",
                "Accepted plan versions and reference metadata stay in local history on this device. Raw image bytes, " +
                    "video bytes, and sampled frames are never stored in build history.",
                "Pairing uses pinned private-LAN HTTPS. Only an accepted plan can proceed, after a server preflight " +
                    "and your separate confirmation. Placed blocks cannot be rolled back by CraftMind.",
            ),
        )
        CraftMindDivider()
        CraftMindKeyValueRow(label = "Accounts", value = "None. CraftMind has no sign-in and no cloud sync.")
        CraftMindKeyValueRow(label = "Analytics", value = "None. No tracking or telemetry is collected.")
        CraftMindKeyValueRow(label = "Android backup", value = "Disabled for app data.")
        CraftMindKeyValueRow(label = "AI backend", value = "None hosted by CraftMind; requests go to your provider.")
    }
}

@Composable
private fun AboutGroup(onOpenAbout: () -> Unit) {
    CraftMindCard {
        CraftMindSectionHeader(
            eyebrow = "About",
            title = "CraftMind · AI Minecraft Builder",
            subtitle = "Describe it. Show it. Build it.",
        )
        CraftMindKeyValueRow(label = "Developer", value = "Sarthak Bharambe")
        CraftMindKeyValueRow(label = "Application ID", value = com.craftmind.app.BuildConfig.APPLICATION_ID)
        CraftMindKeyValueRow(
            label = "Version",
            value = "${com.craftmind.app.BuildConfig.VERSION_NAME} " +
                "(${com.craftmind.app.BuildConfig.VERSION_CODE})",
        )
        CraftMindKeyValueRow(label = "Certified target", value = "Minecraft Java 1.20.1 · Fabric Loader 0.16.10")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm, Alignment.End),
        ) {
            CraftMindTertiaryButton(text = "Open About", onClick = onOpenAbout)
        }
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
    var capabilitiesExpanded by remember { mutableStateOf(false) }

    CraftMindCard {
        CraftMindSectionHeader(
            eyebrow = "AI providers",
            title = "Provider, key, and model",
            subtitle = "Keys belong to providers. The model choice is stored separately and requires a live " +
                "connection test.",
        )

        when {
            state.providers.isEmpty() -> CraftMindNotice(
                tone = CraftMindTone.INFORMATIVE,
                message = "Loading the AI provider setup saved on this device…",
            )

            state.providers.size > 1 -> Column {
                CraftMindSecondaryButton(
                    text = activeProvider?.displayName ?: "Choose provider",
                    onClick = { providerMenuExpanded = true },
                    icon = Icons.Default.ArrowDropDown,
                )
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                    shape = CraftMindShapes.md,
                ) {
                    state.providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName, style = CraftMindType.bodyLarge) },
                            onClick = {
                                providerMenuExpanded = false
                                onEvent(ProviderSettingsEvent.SelectProvider(provider.id))
                            },
                        )
                    }
                }
            }

            activeProvider != null -> Text(
                text = activeProvider.displayName,
                style = CraftMindType.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            else -> CraftMindNotice(
                tone = CraftMindTone.NEGATIVE,
                title = "No provider adapter installed",
                message = "This build has no supported AI provider adapter, so no plan can be generated.",
            )
        }

        activeProvider?.let { provider ->
            CraftMindNotice(
                tone = if (state.savedCredentialExists) CraftMindTone.POSITIVE else CraftMindTone.CAUTION,
                title = if (state.savedCredentialExists) "API key saved" else "No API key saved",
                message = if (state.savedCredentialExists) {
                    "A key is stored encrypted on this device. Its value is never displayed, and it is never sent to " +
                        "the Minecraft bridge."
                } else {
                    "Add your own key from ${provider.displayName}. CraftMind has no hosted AI backend and no shared key."
                },
            )

            OutlinedTextField(
                value = state.keyDraft,
                onValueChange = { onEvent(ProviderSettingsEvent.KeyDraftChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Provider API key" },
                label = { Text(if (state.savedCredentialExists) "Replace API key" else "Provider API key") },
                placeholder = { Text("Paste a key from ${provider.displayName}") },
                singleLine = true,
                shape = CraftMindShapes.sm,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                supportingText = {
                    Text(
                        text = "Masked while you type, and never shown again after saving.",
                        style = CraftMindType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CraftMindPrimaryButton(
                    text = if (state.savedCredentialExists) "Replace key" else "Save key",
                    onClick = { onEvent(ProviderSettingsEvent.SaveCredential) },
                    enabled = state.keyDraft.isNotBlank(),
                    loading = state.isSavingCredential,
                    fullWidth = false,
                )
                if (state.savedCredentialExists) {
                    CraftMindDestructiveButton(
                        text = "Remove key",
                        onClick = { onEvent(ProviderSettingsEvent.RemoveCredential) },
                        enabled = !state.isSavingCredential,
                    )
                }
            }

            CraftMindSecondaryButton(
                text = if (state.connection is ProviderConnectionState.Testing) "Testing provider…" else "Test connection",
                onClick = { onEvent(ProviderSettingsEvent.TestConnection) },
                enabled = state.savedCredentialExists && !state.isSavingCredential && !state.isSavingSelection,
                loading = state.connection is ProviderConnectionState.Testing,
            )

            ConnectionStatus(state.connection)

            if (state.connection is ProviderConnectionState.Verified) {
                val selectedModel = state.models.firstOrNull { it.id == state.selectedModelId }
                CraftMindDivider()
                Text(
                    text = "Choose model",
                    style = CraftMindType.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column {
                    CraftMindSecondaryButton(
                        text = selectedModel?.displayName ?: "Select a verified model",
                        onClick = { modelMenuExpanded = true },
                        enabled = !state.isSavingSelection,
                        icon = Icons.Default.ArrowDropDown,
                    )
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                        shape = CraftMindShapes.md,
                    ) {
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName, style = CraftMindType.bodyLarge)
                                        Text(
                                            text = model.id,
                                            style = CraftMindType.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = modelCapabilitySummary(model),
                                            style = CraftMindType.labelSmall,
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

                CraftMindExpandableSection(
                    title = "Model capabilities and limits",
                    summary = selectedModel?.displayName ?: "Select a model to see what it can do",
                    expanded = capabilitiesExpanded,
                    onToggle = { capabilitiesExpanded = !capabilitiesExpanded },
                ) {
                    CraftMindDetailLines(
                        buildList {
                            add(
                                if (provider.capabilities.vision) {
                                    "Image requests use one locally prepared JPEG, PNG, or WebP. Video requests are " +
                                        "limited to direct HTTPS MP4/WebM files on raw.githubusercontent.com: byte " +
                                        "ranges only, no redirects, and at most five sampled frames."
                                } else {
                                    "This provider adapter does not support image or video-frame analysis."
                                },
                            )
                            val semanticRefinementSupported = provider.capabilities.textGeneration &&
                                provider.capabilities.structuredOutput ==
                                com.craftmind.app.domain.ai.StructuredOutputMode.JSON_MIME_TYPE &&
                                selectedModel != null &&
                                selectedModel.capabilities.textGeneration &&
                                selectedModel.capabilities.structuredOutput ==
                                com.craftmind.app.domain.ai.StructuredOutputMode.JSON_MIME_TYPE
                            add(
                                when {
                                    selectedModel == null ->
                                        "Select a model to check the text and JSON capabilities required for semantic refinement."

                                    semanticRefinementSupported ->
                                        "The selected model supports the text and structured JSON capabilities required for semantic refinement."

                                    else ->
                                        "The selected model lacks a required text or JSON capability. CraftMind rejects refinement rather than switching or downgrading models."
                                },
                            )
                            selectedModel?.let { model ->
                                add(
                                    when {
                                        model.capabilities.vision && model.capabilities.multipleImages ->
                                            "The selected model is verified for one-image input and bounded multi-image video-frame analysis. Video bytes stay on-device except for bounded HTTPS range reads; sampled frames are sent directly to this provider."

                                        model.capabilities.vision ->
                                            "The selected model is verified for one-image input only. Video analysis is blocked unless you select a model labeled Multi-image Vision."

                                        else ->
                                            "The selected model is text-only for visual references. Image and video analysis are blocked until you choose a model labeled Vision."
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        state.message?.let { message ->
            CraftMindNotice(
                tone = CraftMindTone.INFORMATIVE,
                message = message,
                onDismiss = { onEvent(ProviderSettingsEvent.DismissMessage) },
            )
        }
    }
}

@Composable
private fun ConnectionStatus(state: ProviderConnectionState) {
    val tone = when (state) {
        ProviderConnectionState.Unverified -> CraftMindTone.INFORMATIVE
        ProviderConnectionState.Testing -> CraftMindTone.INFORMATIVE
        is ProviderConnectionState.Verified -> CraftMindTone.POSITIVE
        is ProviderConnectionState.Failed -> CraftMindTone.NEGATIVE
    }
    val text = when (state) {
        ProviderConnectionState.Unverified -> "Not verified yet. Run a live test to check the saved key and model list."
        ProviderConnectionState.Testing -> "A real provider request is in progress."
        is ProviderConnectionState.Verified ->
            "Verified by a live API response · ${state.compatibleModelCount} compatible model(s)."

        is ProviderConnectionState.Failed ->
            "Connection test failed (${state.code.name.lowercase().replace('_', ' ')}). No raw response or secret is shown."
    }
    CraftMindNotice(tone = tone, message = text)
}

/** Capability line shown in the model picker; mirrors the composer's own wording. */
private fun modelCapabilitySummary(model: com.craftmind.app.domain.ai.AiModel): String = when {
    model.capabilities.vision && model.capabilities.multipleImages ->
        "Text + one-image and multi-frame video vision"

    model.capabilities.vision -> "Text + one-image vision · multi-frame video unavailable"
    else -> "Text only · image and video analysis unavailable"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun ThemeMode.explanation(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow this device's light or dark setting."
    ThemeMode.LIGHT -> "Always use the light palette."
    ThemeMode.DARK -> "Always use the dark palette."
}
