package com.craftmind.app.presentation.builds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.buildplan.LocalBuildRecord

@Composable
fun BuildsScreen(
    state: BuildsState,
    onStartBuilding: () -> Unit,
    onReview: (LocalBuildRecord) -> Unit,
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
            Text("Builds", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "Review accepted plans here. Construction is available only with an authenticated compatible bridge and a separate final confirmation.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.isLoading) {
            CircularProgressIndicator()
        } else if (state.loadFailed) {
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 800.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "Local build records could not be read. No unvalidated plan is shown.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else if (state.currentRecords.isEmpty()) {
            EmptyBuildsCard(onStartBuilding)
        } else {
            state.currentRecords.forEach { record ->
                val versionCount = state.versionsFor(record.buildId).size
                BuildRecordCard(
                    record = record,
                    versionCount = versionCount,
                    onReview = { onReview(record) },
                )
            }
        }
    }
}

@Composable
private fun EmptyBuildsCard(onStartBuilding: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 800.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("No validated plans saved yet.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Plans appear here only after a real provider response passes CraftMind's parser and validation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(onClick = onStartBuilding, shape = RoundedCornerShape(14.dp)) {
                    Text("Open the build composer")
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun BuildRecordCard(record: LocalBuildRecord, versionCount: Int, onReview: () -> Unit) {
    val plan = record.plan
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 800.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(plan.metadata.title, style = MaterialTheme.typography.titleLarge)
            Text(
                plan.metadata.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Version ${record.version} · $versionCount saved version(s) · ${plan.metadata.dimensions.width} × ${plan.metadata.dimensions.height} × ${plan.metadata.dimensions.depth} · ${plan.operations.size} placements · ${plan.metadata.providerId} / ${plan.metadata.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onReview, shape = RoundedCornerShape(14.dp)) { Text("Review details") }
                Text("Plan history · execution status is separate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
