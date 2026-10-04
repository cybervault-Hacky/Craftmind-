package com.craftmind.app.presentation.builds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.buildplan.BuildDiff
import com.craftmind.app.domain.buildplan.BuildDiffWarning
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun PlanReviewScreen(
    plan: BuildPlan,
    request: BuildRequestSnapshot,
    record: LocalBuildRecord?,
    versions: List<LocalBuildRecord>,
    refinementState: BuildRefinementState,
    onRefinementEvent: (BuildRefinementEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var refinementDraft by remember(record?.recordId) { mutableStateOf("") }
    val currentRecord = record?.let { selected ->
        versions.maxByOrNull(LocalBuildRecord::version)?.takeIf { it.recordId == selected.recordId }
    }
    val matchingState = if (record == null) BuildRefinementState.Idle else refinementState.takeIf { state ->
        state.matches(record)
    } ?: BuildRefinementState.Idle
    val ready = matchingState as? BuildRefinementState.ReadyForReview
    val busy = matchingState is BuildRefinementState.ValidatingRequest || matchingState is BuildRefinementState.Generating ||
        matchingState is BuildRefinementState.Accepting || matchingState is BuildRefinementState.Reverting
    val displayedPlan = ready?.candidate?.plan ?: plan
    val dismissReview = {
        if (matchingState is BuildRefinementState.Generating) onRefinementEvent(BuildRefinementEvent.Cancel)
        onDismiss()
    }

    Dialog(
        onDismissRequest = dismissReview,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp).imePadding(),
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
                    ReviewCard(if (ready == null) "New build" else "Candidate refinement") {
                        Text(displayedPlan.metadata.title, style = MaterialTheme.typography.titleLarge)
                        Text(displayedPlan.metadata.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Result: ${displayedPlan.metadata.dimensions.width} × ${displayedPlan.metadata.dimensions.height} × ${displayedPlan.metadata.dimensions.depth} blocks · ${displayedPlan.operations.size} placements",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("Semantic components: ${displayedPlan.components.size}", style = MaterialTheme.typography.bodyMedium)
                        displayedPlan.metadata.intent?.let { intent ->
                            Text(
                                buildList {
                                    add(intent.structureType)
                                    intent.style?.let { add(it) }
                                    intent.floorCount?.let { add("$it floors") }
                                    intent.approximateScale?.let { add(it) }
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            (intent.specialFeatures + intent.rooms).take(8).takeIf { it.isNotEmpty() }?.let { features ->
                                Text("Features: ${features.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Provider/model: ${displayedPlan.metadata.providerId} / ${displayedPlan.metadata.modelId}", style = MaterialTheme.typography.bodySmall)
                        Text("Generated: ${formatTime(displayedPlan.metadata.generatedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
                    }

                    if (ready != null) ProposedChangesCard(ready.diff)

                    ReviewCard("Your request") {
                        Text(request.prompt, style = MaterialTheme.typography.bodyMedium)
                        if (request.imageContentUri != null) {
                            Text("Image reference retained locally; it was not uploaded or analyzed.", style = MaterialTheme.typography.bodySmall)
                        }
                        request.urlReference?.let { url ->
                            Text("URL reference retained locally but not fetched or analyzed: $url", style = MaterialTheme.typography.bodySmall)
                        }
                        ready?.request?.instruction?.let { instruction ->
                            Text("Refinement: $instruction", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ReviewCard("Semantic components (${displayedPlan.components.size})") {
                        displayedPlan.components.sortedBy { it.constructionOrder }.forEach { component ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${component.name} · ${component.type.name}", style = MaterialTheme.typography.titleSmall)
                                Text(component.purpose, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Order ${component.constructionOrder}" + (component.parentComponentId?.let { " · child of $it" } ?: " · root component"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                component.bounds?.let { bounds ->
                                    Text(
                                        "Bounds ${bounds.dimensions.width} × ${bounds.dimensions.height} × ${bounds.dimensions.depth} at ${bounds.origin.x}, ${bounds.origin.y}, ${bounds.origin.z}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    val blockCounts = remember(displayedPlan) {
                        displayedPlan.operations.groupingBy { it.blockId }.eachCount().entries
                            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                            .take(MAX_BLOCK_SUMMARY)
                    }
                    ReviewCard("Block summary") {
                        blockCounts.forEach { (blockId, count) -> Text("$blockId  × $count", style = MaterialTheme.typography.bodySmall) }
                        if (displayedPlan.operations.map { it.blockId }.distinct().size > MAX_BLOCK_SUMMARY) {
                            Text("Showing the $MAX_BLOCK_SUMMARY most-used block types.", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    ReviewCard("Placement preview (first ${minOf(displayedPlan.operations.size, MAX_VISIBLE_OPERATIONS)} of ${displayedPlan.operations.size})") {
                        displayedPlan.operations.take(MAX_VISIBLE_OPERATIONS).forEach { operation ->
                            val state = operation.blockState.entries.joinToString { (key, value) -> "$key=$value" }
                            Text(
                                "${operation.sequence + 1}. ${operation.blockId} at (${operation.position.x}, ${operation.position.y}, ${operation.position.z})" +
                                    if (state.isEmpty()) "" else " [$state]",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (record != null) {
                        RefinementCard(
                            record = record,
                            isCurrent = currentRecord != null,
                            isBusy = busy,
                            draft = refinementDraft,
                            onDraftChanged = { refinementDraft = it.take(BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH) },
                            onRefine = {
                                onRefinementEvent(BuildRefinementEvent.Refine(record, refinementDraft))
                            },
                            versions = versions,
                            state = matchingState,
                            onEvent = onRefinementEvent,
                        )
                    } else {
                        ReviewCard("Refinement") {
                            Text(
                                "This plan could not be saved to local history, so it cannot be refined or versioned yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Button(onClick = dismissReview, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Done reviewing")
                }
            }
        }
    }
}

@Composable
private fun RefinementCard(
    record: LocalBuildRecord,
    isCurrent: Boolean,
    isBusy: Boolean,
    draft: String,
    onDraftChanged: (String) -> Unit,
    onRefine: () -> Unit,
    versions: List<LocalBuildRecord>,
    state: BuildRefinementState,
    onEvent: (BuildRefinementEvent) -> Unit,
) {
    ReviewCard("Refine with AI") {
        Text(
            "Describe a change in your own words. The AI receives this plan's semantic structure and bounded placement context; unmentioned operations remain unchanged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isCurrent) {
            Text("This version is not the latest. Restore it as a new version before refining.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = isCurrent && !isBusy,
            label = { Text("Describe a change…") },
            placeholder = { Text("For example: Add a rooftop helipad") },
            minLines = 2,
            maxLines = 5,
            supportingText = { Text("${draft.length}/${BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH}") },
        )
        if (state !is BuildRefinementState.ValidatingRequest && state !is BuildRefinementState.Generating &&
            state !is BuildRefinementState.ReadyForReview && state !is BuildRefinementState.Accepting &&
            state !is BuildRefinementState.Reverting
        ) {
            Button(
                onClick = onRefine,
                enabled = isCurrent && draft.isNotBlank() && !isBusy,
                shape = RoundedCornerShape(14.dp),
            ) { Text("Generate refinement") }
        }
        when (state) {
            is BuildRefinementState.ValidatingRequest -> Text("Checking the refinement request…", style = MaterialTheme.typography.bodySmall)
            is BuildRefinementState.Generating -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Waiting for a real AI refinement response…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onEvent(BuildRefinementEvent.Cancel) }) { Text("Cancel") }
                }
            }
            is BuildRefinementState.ReadyForReview -> {
                Text("Candidate is validated. Accept only if the proposed changes look right; earlier versions stay saved.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEvent(BuildRefinementEvent.Accept) }, enabled = !isBusy) { Text("Accept candidate") }
                    OutlinedButton(onClick = { onEvent(BuildRefinementEvent.Discard) }, enabled = !isBusy) { Text("Discard") }
                }
            }
            is BuildRefinementState.Accepting -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Saving a new local version…", style = MaterialTheme.typography.bodySmall)
                }
            }
            is BuildRefinementState.ValidationFailed -> {
                Text(validationMessage(state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onEvent(BuildRefinementEvent.DismissResult) }) { Text("Dismiss") }
            }
            is BuildRefinementState.Failed -> {
                Text(refinementMessage(state.code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.retryable && state.stage == RefinementFailureStage.PROVIDER) {
                        OutlinedButton(onClick = { onEvent(BuildRefinementEvent.Retry) }) { Text("Retry") }
                    }
                    TextButton(onClick = { onEvent(BuildRefinementEvent.DismissResult) }) { Text("Dismiss") }
                }
            }
            is BuildRefinementState.Cancelled -> {
                Text("Refinement cancelled. The previous saved version is unchanged.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onEvent(BuildRefinementEvent.DismissResult) }) { Text("Dismiss") }
            }
            is BuildRefinementState.Accepted -> Text("Version ${state.record.version} saved locally. No Minecraft world was changed.", style = MaterialTheme.typography.bodySmall)
            is BuildRefinementState.Reverting -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Restoring saved plan as a new version…", style = MaterialTheme.typography.bodySmall)
                }
            }
            is BuildRefinementState.Reverted -> Text("Version ${state.record.restoredFromVersion} restored as Version ${state.record.version}. This changed only local history.", style = MaterialTheme.typography.bodySmall)
            is BuildRefinementState.HistoryFailure -> {
                Text(refinementMessage(state.code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onEvent(BuildRefinementEvent.DismissResult) }) { Text("Dismiss") }
            }
            BuildRefinementState.Idle -> Unit
        }

        if (versions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Version history", style = MaterialTheme.typography.titleSmall)
                versions.sortedByDescending(LocalBuildRecord::version).forEach { version ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Version ${version.version}${if (version.recordId == record.recordId) " · current" else ""}", style = MaterialTheme.typography.bodyMedium)
                            Text(version.changeSummary ?: version.plan.metadata.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isCurrent && version.version < record.version && !isBusy) {
                            TextButton(onClick = {
                                onEvent(BuildRefinementEvent.RestoreVersion(record, version.version))
                            }) { Text("Restore") }
                        }
                    }
                }
                Text("Restoring creates a new local version. It is not Minecraft undo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProposedChangesCard(diff: BuildDiff) {
    ReviewCard("Changes proposed") {
        Text(diff.summary, style = MaterialTheme.typography.bodyMedium)
        if (diff.addedComponents.isNotEmpty()) {
            Text("Added", style = MaterialTheme.typography.titleSmall)
            diff.addedComponents.forEach { Text("+ ${it.name} · ${it.type.name}", style = MaterialTheme.typography.bodySmall) }
        }
        if (diff.removedComponents.isNotEmpty()) {
            Text("Removed", style = MaterialTheme.typography.titleSmall)
            diff.removedComponents.forEach { Text("− ${it.name}", style = MaterialTheme.typography.bodySmall) }
        }
        if (diff.modifiedComponents.isNotEmpty()) {
            Text("Modified", style = MaterialTheme.typography.titleSmall)
            diff.modifiedComponents.take(MAX_VISIBLE_DIFF_ITEMS).forEach { change ->
                Text(
                    "~ ${change.before.name}: ${change.before.type.name} → ${change.after.type.name}; ${change.before.purpose} → ${change.after.purpose}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (diff.modifiedComponents.size > MAX_VISIBLE_DIFF_ITEMS) {
                Text("${diff.modifiedComponents.size - MAX_VISIBLE_DIFF_ITEMS} more modified components", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            "Block operations: +${diff.addedOperations.size} · −${diff.removedOperations.size} · ~${diff.changedOperations.size}",
            style = MaterialTheme.typography.bodySmall,
        )
        diff.addedOperations.take(MAX_VISIBLE_DIFF_ITEMS).forEach { operation ->
            Text("+ ${operation.blockId} at ${operation.position.label()}", style = MaterialTheme.typography.bodySmall)
        }
        diff.removedOperations.take(MAX_VISIBLE_DIFF_ITEMS).forEach { operation ->
            Text("− ${operation.blockId} at ${operation.position.label()}", style = MaterialTheme.typography.bodySmall)
        }
        diff.changedOperations.take(MAX_VISIBLE_DIFF_ITEMS).forEach { change ->
            Text("~ ${change.before.blockId} → ${change.after.blockId} at ${change.after.position.label()}", style = MaterialTheme.typography.bodySmall)
        }
        if (diff.operationChangeCount > MAX_VISIBLE_DIFF_ITEMS) {
            Text("Each operation section is capped at $MAX_VISIBLE_DIFF_ITEMS entries; total counts are shown above.", style = MaterialTheme.typography.labelSmall)
        }
        if (diff.titleChanged || diff.descriptionChanged || diff.intentChanged) {
            Text("Build intent or description updated.", style = MaterialTheme.typography.bodySmall)
        }
        if (diff.dimensionsBefore != diff.dimensionsAfter) {
            Text(
                "Dimensions: ${diff.dimensionsBefore.width}×${diff.dimensionsBefore.height}×${diff.dimensionsBefore.depth} → ${diff.dimensionsAfter.width}×${diff.dimensionsAfter.height}×${diff.dimensionsAfter.depth}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        diff.warnings.forEach { warning ->
            Text(warningMessage(warning), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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

private fun BuildRefinementState.matches(record: LocalBuildRecord): Boolean = when (this) {
    BuildRefinementState.Idle -> false
    is BuildRefinementState.ValidatingRequest -> base.recordId == record.recordId
    is BuildRefinementState.ValidationFailed -> base.recordId == record.recordId
    is BuildRefinementState.Generating -> base.recordId == record.recordId
    is BuildRefinementState.ReadyForReview -> base.recordId == record.recordId
    is BuildRefinementState.Accepting -> base.recordId == record.recordId
    is BuildRefinementState.Failed -> base.recordId == record.recordId
    is BuildRefinementState.Cancelled -> base.recordId == record.recordId
    is BuildRefinementState.Accepted -> this.record.buildId == record.buildId
    is BuildRefinementState.Reverting -> current.buildId == record.buildId
    is BuildRefinementState.Reverted -> this.record.buildId == record.buildId
    is BuildRefinementState.HistoryFailure -> current.buildId == record.buildId
}

private fun validationMessage(error: com.craftmind.app.domain.buildplan.BuildEditValidationError): String = when (error) {
    com.craftmind.app.domain.buildplan.BuildEditValidationError.EMPTY_INSTRUCTION -> "Describe the change you want before asking the AI to refine this plan."
    com.craftmind.app.domain.buildplan.BuildEditValidationError.INSTRUCTION_TOO_LONG -> "Keep the refinement request within ${BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH} characters."
    com.craftmind.app.domain.buildplan.BuildEditValidationError.INVALID_BASE_VERSION -> "This saved plan version is not available for refinement."
    com.craftmind.app.domain.buildplan.BuildEditValidationError.INVALID_BASE_PLAN -> "The saved plan failed validation and cannot be refined."
    com.craftmind.app.domain.buildplan.BuildEditValidationError.LEGACY_PLAN_NOT_UPGRADABLE -> "This older plan lacks component-to-placement links needed for a safe semantic upgrade."
}

private fun refinementMessage(code: AiErrorCode): String = when (code) {
    AiErrorCode.INVALID_API_KEY -> "The provider rejected its saved API key. Update it in Settings."
    AiErrorCode.PROVIDER_UNAVAILABLE -> "The provider is temporarily unavailable. You may retry this refinement."
    AiErrorCode.MODEL_UNAVAILABLE -> "The selected model is unavailable. No other model or provider was selected."
    AiErrorCode.RATE_LIMITED -> "The provider rate-limited this request. Wait before retrying."
    AiErrorCode.NETWORK_TIMEOUT -> "The provider request timed out. Check your connection and retry."
    AiErrorCode.NETWORK_UNAVAILABLE -> "Could not reach the provider. Check your connection and retry."
    AiErrorCode.INVALID_AI_RESPONSE -> "The provider returned malformed edit data. The previous version is unchanged."
    AiErrorCode.INVALID_BUILD_PLAN -> "The proposed plan failed centralized validation. The previous version is unchanged."
    AiErrorCode.INVALID_BUILD_EDIT -> "The proposed edit referenced invalid components or operations. The previous version is unchanged."
    AiErrorCode.NO_CHANGES_PROPOSED -> "The AI did not propose a validated change. The previous version is unchanged."
    AiErrorCode.REFINEMENT_CONTEXT_TOO_LARGE -> "This plan needs more operation context than the selected model can safely receive. Try a narrower change or a smaller build."
    AiErrorCode.BUILD_VERSION_CONFLICT -> "This is no longer the latest saved version. Reopen the current version before refining."
    AiErrorCode.BUILD_HISTORY_FAILURE -> "The local version could not be saved. The previous valid plan remains available."
    AiErrorCode.BUILD_HISTORY_LIMIT_REACHED -> "Local history is at its version limit. Older versions were not removed."
    AiErrorCode.UNSUPPORTED_SCHEMA_VERSION -> "The provider returned an unsupported edit schema."
    AiErrorCode.BUILD_TOO_LARGE, AiErrorCode.RESPONSE_TOO_LARGE -> "The proposed plan or response exceeded CraftMind's safety limits."
    AiErrorCode.UNSUPPORTED_CAPABILITY -> "The selected model does not support structured semantic refinement. No fallback model was used."
    AiErrorCode.MISSING_CREDENTIAL -> "Save the selected provider's API key in Settings."
    AiErrorCode.CREDENTIAL_STORAGE_FAILURE -> "The encrypted provider key could not be accessed."
    AiErrorCode.NO_PROVIDER_SELECTED -> "Choose a supported provider in Settings."
    AiErrorCode.NO_MODEL_SELECTED -> "Select a verified model in Settings."
    AiErrorCode.NO_MODELS_AVAILABLE -> "The provider returned no compatible models."
    AiErrorCode.CANCELLED -> "The refinement was cancelled. The previous version is unchanged."
    AiErrorCode.UNKNOWN_PROVIDER_ERROR -> "The refinement failed. No raw response or secret was displayed."
}

private fun warningMessage(warning: BuildDiffWarning): String = when (warning) {
    BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS -> "Review carefully: the AI changed components outside the target set it declared."
    BuildDiffWarning.DECLARED_PRESERVED_COMPONENT_CHANGED -> "Review carefully: a component declared preserved also changed."
    BuildDiffWarning.LARGE_CHANGE_FOR_LOCALIZED_REQUEST -> "Review carefully: the placement change is broad for a localized request."
}

private fun com.craftmind.app.domain.buildplan.BlockPosition.label(): String = "($x, $y, $z)"

private fun formatTime(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}.getOrDefault("Time unavailable")

private const val MAX_VISIBLE_OPERATIONS = 100
private const val MAX_VISIBLE_DIFF_ITEMS = 40
private const val MAX_BLOCK_SUMMARY = 20
