package com.craftmind.app.presentation.builds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDivider
import com.craftmind.app.designsystem.CraftMindEmptyState
import com.craftmind.app.designsystem.CraftMindErrorState
import com.craftmind.app.designsystem.CraftMindKeyValueRow
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindLoadingState
import com.craftmind.app.designsystem.CraftMindMetaChip
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindScreen
import com.craftmind.app.designsystem.CraftMindSecondaryButton
import com.craftmind.app.designsystem.CraftMindStatusBadge
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.presentation.navigation.MainDestination

/**
 * Builds library (Phase 15 §8).
 *
 * Everything on this screen is local history that really exists: each card is one saved, validated plan version, with
 * its save time, its source, its position in the build's version history, and — when the bridge pipeline wrote one —
 * its real execution status. There are no sample builds and no placeholders, and the empty state says plainly that
 * nothing has been generated yet.
 *
 * @param executionRecords execution facts written by the bridge pipeline, used for real status badges.
 * @param minecraftStatusLabel one honest sentence about whether building is currently available; null when the
 *   runtime state is not loaded.
 */
@Composable
fun BuildsScreen(
    state: BuildsState,
    onStartBuilding: () -> Unit,
    onReview: (LocalBuildRecord) -> Unit,
    modifier: Modifier = Modifier,
    executionRecords: List<LocalBuildExecutionRecord> = emptyList(),
    minecraftStatusLabel: String? = null,
    minecraftStatusTone: CraftMindTone = CraftMindTone.NEUTRAL,
    onOpenMinecraft: () -> Unit = {},
) {
    CraftMindScreen(
        title = "Builds",
        eyebrow = "Local library",
        subtitle = MainDestination.BUILDS.purpose,
        modifier = modifier,
    ) {
        minecraftStatusLabel?.let { label ->
            CraftMindNotice(
                tone = minecraftStatusTone,
                title = "Building in Minecraft",
                message = label,
                actionLabel = "Open Minecraft",
                onAction = onOpenMinecraft,
            )
        }

        when {
            state.isLoading -> CraftMindLoadingState(message = "Reading your local build history…")

            state.loadFailed -> CraftMindErrorState(
                title = "Build history could not be read",
                message = "Local build records could not be read on this device. Nothing is shown rather than " +
                    "displaying an unvalidated or invented plan.",
                diagnostics = listOf(
                    "Local history is stored in app-private storage.",
                    "Generating a new build writes a fresh record and restores this list.",
                ),
                retryLabel = "Open the build composer",
                onRetry = onStartBuilding,
            )

            state.currentRecords.isEmpty() -> CraftMindEmptyState(
                title = "No builds yet",
                message = "Plans appear here only after a real provider response passes CraftMind's parser and " +
                    "BuildPlan v2 validation. Nothing is added before that.",
                icon = Icons.Default.List,
                actionLabel = "Create your first build",
                onAction = onStartBuilding,
            )

            else -> {
                val records = state.currentRecords
                Text(
                    text = "${records.size} ${if (records.size == 1) "build" else "builds"} saved on this device",
                    style = CraftMindType.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                records.forEach { record ->
                    val summary = remember(record, executionRecords) {
                        buildRecordSummary(
                            record = record,
                            versionCount = state.versionsFor(record.buildId).size,
                            executions = executionRecords,
                        )
                    }
                    BuildRecordCard(summary = summary, onReview = { onReview(record) })
                }
            }
        }
    }
}

/** One library card: identity, provenance, scale, version history, and real execution status. */
@Composable
private fun BuildRecordCard(
    summary: BuildRecordSummary,
    onReview: () -> Unit,
) {
    CraftMindCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs)) {
                Text(
                    text = summary.title,
                    style = CraftMindType.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary.savedAtLabel,
                    style = CraftMindType.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (summary.summary.isNotBlank()) {
                Text(
                    text = summary.summary,
                    style = CraftMindType.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CraftMindStatusBadge(label = summary.versionLabel, tone = CraftMindTone.NEUTRAL)
                CraftMindStatusBadge(label = summary.sourceLabel, tone = CraftMindTone.INFORMATIVE)
                summary.executionStatusLabel?.let { status ->
                    CraftMindStatusBadge(label = status, tone = summary.executionTone)
                }
            }

            summary.refinementLabel?.let { label ->
                Text(
                    text = label,
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CraftMindDivider()

            CraftMindKeyValueRow(label = "Size", value = summary.dimensionLabel)
            CraftMindKeyValueRow(label = "Placements", value = summary.operationCountLabel)
            CraftMindKeyValueRow(label = "Generated by", value = summary.modelLabel)
            if (summary.hasNewerVersion) {
                CraftMindKeyValueRow(
                    label = "History",
                    value = "A newer version of this build is saved; opening the review shows the full version list.",
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CraftMindSecondaryButton(
                    text = "Review build plan",
                    onClick = onReview,
                    icon = Icons.Default.ArrowForward,
                )
            }
        }
    }
}
