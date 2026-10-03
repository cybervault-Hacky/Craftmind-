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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

private enum class SettingsDialog { ABOUT, PRIVACY }

@Composable
fun SettingsScreen(
    appearance: AppearanceMode,
    appVersion: String,
    onAppearanceSelected: (AppearanceMode) -> Unit,
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
                SettingsStatusRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.settings_ai_provider),
                    status = stringResource(R.string.settings_ai_provider_status),
                    description = stringResource(R.string.settings_ai_provider_body),
                )
                SettingsDivider()
                SettingsStatusRow(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.settings_api_key),
                    status = stringResource(R.string.settings_api_key_status),
                    description = stringResource(R.string.settings_api_key_body),
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
                text = stringResource(R.string.settings_status_unavailable),
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
private fun SettingsStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    description: String,
) {
    Row(
        modifier = Modifier.padding(Space.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Space.xxs).size(IconSize.large),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                StatusPill(status)
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(Corners.pill),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
