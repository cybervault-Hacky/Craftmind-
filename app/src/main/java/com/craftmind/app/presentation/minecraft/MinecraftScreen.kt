package com.craftmind.app.presentation.minecraft

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDetailLines
import com.craftmind.app.designsystem.CraftMindExpandableSection
import com.craftmind.app.designsystem.CraftMindKeyValueRow
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindScreen
import com.craftmind.app.designsystem.CraftMindSectionHeader
import com.craftmind.app.designsystem.CraftMindStatusBadge
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType
import com.craftmind.app.presentation.navigation.MainDestination
import com.craftmind.app.presentation.settings.BridgePairingEvent
import com.craftmind.app.presentation.settings.BridgePairingState
import com.craftmind.app.presentation.settings.MinecraftBridgeSettingsContent
import com.craftmind.app.presentation.settings.TARGET_RUNTIME_DETECTED_AUTOMATICALLY
import com.craftmind.app.presentation.settings.adapterSummaryLines
import com.craftmind.app.presentation.settings.bridgeSummaryLines
import com.craftmind.app.presentation.settings.compatibilitySummaryLines

/**
 * Minecraft destination (Phase 15 §9).
 *
 * One screen answers three questions in order: *is a bridge connected*, *what runtime did it actually report*, and
 * *may CraftMind build there*. Every statement comes from [minecraftConnectionSummary], which is derived only from
 * [BridgePairingState] — the authenticated bridge report, the resolved compatibility result, and the recorded
 * certification. Nothing is inferred, approximated, or animated for effect.
 *
 * Pairing and session controls are the same real controls Settings uses, so there is no second implementation and no
 * mock-up: [MinecraftBridgeSettingsContent] drives the actual bridge repository.
 */
@Composable
fun MinecraftScreen(
    state: BridgePairingState,
    onEvent: (BridgePairingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = remember(state) { minecraftConnectionSummary(state) }
    val resolution = state.runtimeResolution
    var advancedExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    CraftMindScreen(
        title = "Minecraft",
        eyebrow = "Runtime connection",
        subtitle = MainDestination.MINECRAFT.purpose,
        modifier = modifier,
    ) {
        CraftMindCard(emphasized = summary.canBuild) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            ) {
                summary.badge?.let { badge ->
                    CraftMindStatusBadge(label = badge, tone = summary.badgeTone)
                }
                if (summary.canBuild) {
                    CraftMindStatusBadge(
                        label = "Building available",
                        tone = CraftMindTone.POSITIVE,
                        icon = Icons.Default.CheckCircle,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (summary.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(CraftMindLayout.progressSmall),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = summary.headline,
                style = CraftMindType.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary.detail,
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.busyLabel?.let { label ->
                Text(text = label, style = CraftMindType.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            summary.unavailableReason?.let { reason ->
                CraftMindKeyValueRow(label = "Why building is blocked", value = reason)
            }
        }

        state.message?.let { message ->
            CraftMindNotice(
                tone = CraftMindTone.INFORMATIVE,
                message = message,
                onDismiss = { onEvent(BridgePairingEvent.DismissMessage) },
            )
        }

        if (summary.certificationLines.isNotEmpty()) {
            CraftMindCard {
                CraftMindSectionHeader(
                    eyebrow = "Certification",
                    title = "Certification state",
                    subtitle = "Recorded evidence only. Nothing here claims a Minecraft runtime test that did not run.",
                )
                CraftMindDetailLines(summary.certificationLines)
            }
        }

        if (resolution != null) {
            CraftMindCard {
                CraftMindSectionHeader(
                    eyebrow = "Detected runtime",
                    title = "Runtime facts",
                    subtitle = TARGET_RUNTIME_DETECTED_AUTOMATICALLY,
                )
                CraftMindDetailLines(summary.runtimeLines)
                CraftMindDetailLines(resolution.compatibilitySummaryLines())
                CraftMindExpandableSection(
                    title = "Advanced detail",
                    summary = "Bridge identity, adapter selection, and session binding",
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                ) {
                    CraftMindDetailLines(resolution.bridgeSummaryLines() + resolution.adapterSummaryLines())
                    resolution.binding?.let { binding ->
                        CraftMindDetailLines(
                            listOf(
                                "Bound to session ${binding.identity.sessionId}",
                                "Runtime key ${binding.identity.runtimeKey}",
                            ),
                        )
                    }
                }
            }
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Bridge",
                title = "Session and pairing",
                subtitle = "Pairing verifies a pinned TLS identity over private-LAN HTTPS before any runtime data is " +
                    "trusted. These controls act on the real bridge.",
            )
            MinecraftBridgeSettingsContent(
                state = state,
                onEvent = onEvent,
                showRuntimeCompatibility = false,
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "How building is authorized",
                title = "Four gates, each failing closed",
                subtitle = "Selection is not permission: every gate below must pass before a block can be placed.",
            )
            CraftMindDetailLines(minecraftAuthorizationGates)
        }

        if (summary.diagnosticLines.isNotEmpty()) {
            CraftMindExpandableSection(
                title = "Diagnostics",
                summary = "${summary.diagnosticLines.size} reason line(s) from the last resolution",
                expanded = diagnosticsExpanded,
                onToggle = { diagnosticsExpanded = !diagnosticsExpanded },
            ) {
                CraftMindDetailLines(summary.diagnosticLines)
            }
        }
    }
}
