package com.craftmind.app.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftmind.app.R
import com.craftmind.app.core.designsystem.BrandMark
import com.craftmind.app.core.designsystem.ComponentSize
import com.craftmind.app.core.designsystem.Corners
import com.craftmind.app.core.designsystem.IconSize
import com.craftmind.app.core.designsystem.LayoutBreakpoint
import com.craftmind.app.core.designsystem.Space
import com.craftmind.app.domain.settings.AppearanceMode
import com.craftmind.app.domain.ai.CredentialLimits
import com.craftmind.app.domain.ai.ProviderId
import com.craftmind.app.domain.model.BuildErrorCode

private enum class SettingsDialog { ABOUT, PRIVACY }

@Composable
fun SettingsScreen(
    appearance: AppearanceMode,
    appVersion: String,
    providerState: AiProviderSettingsUiState,
    onAppearanceSelected: (AppearanceMode) -> Unit,
    onProviderSelected: (ProviderId) -> Unit,
    onModelIdChanged: (String) -> Unit,
    onSaveProviderConfiguration: () -> Unit,
    onSaveApiKey: (CharArray) -> Unit,
    onRemoveApiKey: () -> Unit,
    onTestProviderConnection: () -> Unit,
) {
    var activeDialog by remember { mutableStateOf<SettingsDialog?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Space.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    horizontal = if (maxWidth >= LayoutBreakpoint.navigationRail) Space.xxxl else Space.pageHorizontal,
                    vertical = Space.xl,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                BrandMark(size = ComponentSize.brandMark)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.settings_eyebrow),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.1.sp,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.settings_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_connections_section)) {
                AiProviderSettingsPanel(
                    state = providerState,
                    onProviderSelected = onProviderSelected,
                    onModelIdChanged = onModelIdChanged,
                    onSaveConfiguration = onSaveProviderConfiguration,
                    onSaveApiKey = onSaveApiKey,
                    onRemoveApiKey = onRemoveApiKey,
                    onTestConnection = onTestProviderConnection,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_appearance)) {
                Column(
                    modifier = Modifier.padding(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(IconSize.large),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                            Text(
                                text = stringResource(R.string.appearance_theme),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.appearance_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppearanceMode.entries.forEach { mode ->
                            FilterChip(
                                selected = appearance == mode,
                                onClick = { onAppearanceSelected(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = ComponentSize.touchTarget),
                                label = {
                                    Text(
                                        text = appearanceLabel(mode),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_preferences_section)) {
                Row(
                    modifier = Modifier.padding(Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsNone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(IconSize.large),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_notifications),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.settings_notifications_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = false,
                        onCheckedChange = null,
                        enabled = false,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_information_section)) {
                SettingsActionRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_about),
                    detail = stringResource(R.string.settings_version, appVersion),
                    onClick = { activeDialog = SettingsDialog.ABOUT },
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.settings_privacy),
                    detail = stringResource(R.string.privacy_dialog_body),
                    onClick = { activeDialog = SettingsDialog.PRIVACY },
                )
            }

            Text(
                text = stringResource(R.string.settings_phase_two_note),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.xl),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    activeDialog?.let { dialog ->
        val title = when (dialog) {
            SettingsDialog.ABOUT -> stringResource(R.string.about_dialog_title)
            SettingsDialog.PRIVACY -> stringResource(R.string.privacy_dialog_title)
        }
        val body = when (dialog) {
            SettingsDialog.ABOUT -> stringResource(R.string.about_dialog_body)
            SettingsDialog.PRIVACY -> stringResource(R.string.privacy_dialog_body)
        }
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text(stringResource(R.string.dialog_close))
                }
            },
        )
    }
}

@Composable
private fun AiProviderSettingsPanel(
    state: AiProviderSettingsUiState,
    onProviderSelected: (ProviderId) -> Unit,
    onModelIdChanged: (String) -> Unit,
    onSaveConfiguration: () -> Unit,
    onSaveApiKey: (CharArray) -> Unit,
    onRemoveApiKey: () -> Unit,
    onTestConnection: () -> Unit,
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    LaunchedEffect(state.providerId, state.apiKeyConfigured) { apiKeyDraft = "" }

    Column(
        modifier = Modifier.padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(
            text = stringResource(R.string.ai_provider_section_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.ai_provider_section_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BoxWithConstraints {
            val selectedProvider = state.providers.firstOrNull { it.id == state.providerId }
            Button(
                onClick = { providerMenuExpanded = true },
                enabled = !state.isLoading && !state.isSavingConfiguration &&
                    !state.isSavingCredential && state.connection != ProviderConnectionUiState.Testing &&
                    state.providers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedProvider?.displayName ?: stringResource(R.string.select_ai_provider),
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
            DropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false },
                modifier = Modifier.widthIn(max = maxWidth),
            ) {
                state.providers.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            providerMenuExpanded = false
                            onProviderSelected(provider.id)
                        },
                    )
                }
            }
        }
        if (state.providers.isEmpty() && !state.isLoading) {
            Text(
                text = stringResource(R.string.no_ai_providers_available),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = state.modelId,
            onValueChange = onModelIdChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_model_id)) },
            placeholder = { Text(stringResource(R.string.ai_model_id_example)) },
            supportingText = { Text(stringResource(R.string.ai_model_id_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            enabled = state.providerId != null && !state.isSavingConfiguration &&
                state.connection != ProviderConnectionUiState.Testing,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSaveConfiguration,
                enabled = state.providerId != null && !state.isSavingConfiguration &&
                    !state.isSavingCredential && state.connection != ProviderConnectionUiState.Testing,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.save_provider_configuration))
            }
            Button(
                onClick = onTestConnection,
                enabled = state.providerId != null && !state.isSavingConfiguration &&
                    !state.isSavingCredential && state.connection != ProviderConnectionUiState.Testing,
                modifier = Modifier.weight(1f),
            ) {
                if (state.connection == ProviderConnectionUiState.Testing) {
                    CircularProgressIndicator(modifier = Modifier.size(IconSize.small), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.test_provider_connection))
                }
            }
        }

        when (val connection = state.connection) {
            ProviderConnectionUiState.Idle -> Unit
            ProviderConnectionUiState.Testing -> Text(
                text = stringResource(R.string.provider_connection_testing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderConnectionUiState.Connected -> Text(
                text = stringResource(R.string.provider_connection_success),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is ProviderConnectionUiState.Failed -> Text(
                text = stringResource(providerErrorString(connection.error.code)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.settings_api_key),
                modifier = Modifier.weight(1f).padding(start = Space.sm),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (state.apiKeyConfigured) R.string.api_key_configured else R.string.api_key_missing,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.api_key_secure_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it.take(CredentialLimits.MAX_API_KEY_CHARACTERS) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.api_key_input_label)) },
            placeholder = { Text(stringResource(R.string.api_key_input_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            visualTransformation = PasswordVisualTransformation(),
            enabled = state.providerId != null && !state.isSavingCredential && !state.isSavingConfiguration,
            supportingText = if (state.apiKeyConfigured) {
                { Text(stringResource(R.string.api_key_replace_help)) }
            } else null,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.apiKeyConfigured) {
                TextButton(
                    onClick = {
                        apiKeyDraft = ""
                        onRemoveApiKey()
                    },
                    enabled = !state.isSavingCredential && !state.isSavingConfiguration,
                ) { Text(stringResource(R.string.remove_api_key)) }
            }
            Button(
                onClick = {
                    val pending = apiKeyDraft.toCharArray()
                    apiKeyDraft = ""
                    onSaveApiKey(pending)
                },
                enabled = state.providerId != null && apiKeyDraft.isNotBlank() &&
                    !state.isSavingCredential && !state.isSavingConfiguration,
            ) {
                Text(
                    stringResource(
                        if (state.isSavingCredential) R.string.saving_api_key
                        else if (state.apiKeyConfigured) R.string.replace_api_key
                        else R.string.save_api_key,
                    ),
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = stringResource(
                    when (message) {
                        ProviderSettingsMessage.PROVIDER_REQUIRED -> R.string.provider_message_select_first
                        ProviderSettingsMessage.MODEL_REQUIRED -> R.string.provider_message_model_required
                        ProviderSettingsMessage.MODEL_INVALID -> R.string.provider_message_model_invalid
                        ProviderSettingsMessage.API_KEY_EMPTY -> R.string.provider_message_key_empty
                        ProviderSettingsMessage.API_KEY_TOO_LONG -> R.string.provider_message_key_invalid
                        ProviderSettingsMessage.STORAGE_ERROR -> R.string.provider_message_storage_error
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun providerErrorString(code: BuildErrorCode): Int = when (code) {
    BuildErrorCode.INVALID_API_KEY -> R.string.build_error_invalid_api_key
    BuildErrorCode.UNSUPPORTED_MODEL -> R.string.build_error_unsupported_model
    BuildErrorCode.PROVIDER_UNAVAILABLE -> R.string.build_error_provider_unavailable
    BuildErrorCode.NO_INTERNET -> R.string.build_error_no_internet
    BuildErrorCode.REQUEST_TIMED_OUT -> R.string.build_error_timed_out
    BuildErrorCode.RATE_LIMITED -> R.string.build_error_rate_limited
    BuildErrorCode.PROVIDER_REJECTED_REQUEST -> R.string.build_error_rejected
    BuildErrorCode.MALFORMED_RESPONSE -> R.string.build_error_malformed_response
    BuildErrorCode.RESPONSE_TOO_LARGE -> R.string.build_error_response_too_large
    BuildErrorCode.MISSING_API_KEY -> R.string.build_error_missing_api_key
    BuildErrorCode.MISSING_PROVIDER -> R.string.build_error_missing_provider
    BuildErrorCode.UNSUPPORTED_PROVIDER -> R.string.build_error_unsupported_provider
    BuildErrorCode.MISSING_MODEL -> R.string.build_error_missing_model
    BuildErrorCode.BUILD_PLAN_REJECTED -> R.string.build_error_plan_rejected
    BuildErrorCode.CREDENTIAL_STORAGE_FAILED -> R.string.build_error_credential_storage
    else -> R.string.build_error_unknown
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Corners.large),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(ComponentSize.border, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = Space.lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
    )
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.medium))
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSize.large),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.medium),
        )
    }
}

@Composable
private fun appearanceLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.SYSTEM -> stringResource(R.string.appearance_system)
    AppearanceMode.LIGHT -> stringResource(R.string.appearance_light)
    AppearanceMode.DARK -> stringResource(R.string.appearance_dark)
}
