package com.craftmind.app.presentation.builds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun PlanReviewScreen(
    plan: BuildPlan,
    request: BuildRequestSnapshot,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Plan review", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "AI-generated and validated · review only · not executed in Minecraft",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ReviewCard("Overview") {
                        Text(plan.metadata.title, style = MaterialTheme.typography.titleLarge)
                        Text(plan.metadata.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Size: ${plan.metadata.dimensions.width} × ${plan.metadata.dimensions.height} × ${plan.metadata.dimensions.depth} blocks",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Placement operations: ${plan.operations.size}", style = MaterialTheme.typography.bodyMedium)
                        Text("Origin: ${plan.originStrategy.name}", style = MaterialTheme.typography.bodyMedium)
                        Text("Provider/model: ${plan.metadata.providerId} / ${plan.metadata.modelId}", style = MaterialTheme.typography.bodySmall)
                        Text("Generated: ${formatTime(plan.metadata.generatedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
                    }

                    ReviewCard("Your request") {
                        Text(request.prompt, style = MaterialTheme.typography.bodyMedium)
                        request.imageContentUri?.let {
                            Text("Image reference retained locally; it was not uploaded or analyzed.", style = MaterialTheme.typography.bodySmall)
                        }
                        request.urlReference?.let { url ->
                            Text("URL reference retained locally but not fetched or analyzed: $url", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ReviewCard("Components (${plan.components.size})") {
                        plan.components.forEach { component ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(component.name, style = MaterialTheme.typography.titleSmall)
                                Text(component.purpose, style = MaterialTheme.typography.bodySmall)
                                component.bounds?.let { bounds ->
                                    Text(
                                        "Area ${bounds.dimensions.width} × ${bounds.dimensions.height} × ${bounds.dimensions.depth} at ${bounds.origin.x}, ${bounds.origin.y}, ${bounds.origin.z}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    val blockCounts = remember(plan) {
                        plan.operations.groupingBy { it.blockId }.eachCount().entries
                            .sortedWith(compareByDescending { it.value }.thenBy { it.key })
                            .take(MAX_BLOCK_SUMMARY)
                    }
                    ReviewCard("Block palette") {
                        blockCounts.forEach { (blockId, count) ->
                            Text("$blockId  × $count", style = MaterialTheme.typography.bodySmall)
                        }
                        if (plan.operations.map { it.blockId }.distinct().size > MAX_BLOCK_SUMMARY) {
                            Text("Showing the ${MAX_BLOCK_SUMMARY} most-used block types.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ReviewCard("Placement preview (first ${minOf(plan.operations.size, MAX_VISIBLE_OPERATIONS)} of ${plan.operations.size})") {
                        plan.operations.take(MAX_VISIBLE_OPERATIONS).forEach { operation ->
                            val state = operation.blockState.entries.joinToString { (key, value) -> "$key=$value" }
                            Text(
                                "${operation.sequence + 1}. ${operation.blockId} at (${operation.position.x}, ${operation.position.y}, ${operation.position.z})" +
                                    if (state.isEmpty()) "" else " [$state]",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Done reviewing")
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun formatTime(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}.getOrDefault("Time unavailable")

private const val MAX_VISIBLE_OPERATIONS = 100
private const val MAX_BLOCK_SUMMARY = 20
