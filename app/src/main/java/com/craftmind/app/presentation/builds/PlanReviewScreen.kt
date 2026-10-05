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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.presentation.settings.BridgePairingState
import kotlinx.coroutines.delay
import java.net.URI
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
    bridgeState: BridgePairingState = BridgePairingState(),
    executionState: BuildExecutionState = BuildExecutionState(),
    onExecutionEvent: (BuildExecutionEvent) -> Unit = {},
    onDismiss: () -> Unit,
    compatibilityResolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
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
    val executionLocked = record?.let { selected ->
        val flowLocked = when (val flow = executionState.flow) {
            is BuildExecutionFlow.Preparing -> flow.planRecordId == selected.recordId
            is BuildExecutionFlow.PreviewReady -> flow.planRecordId == selected.recordId
            is BuildExecutionFlow.Starting -> flow.planRecordId == selected.recordId
            is BuildExecutionFlow.Tracking -> flow.record.planRecordId == selected.recordId &&
                flow.record.phase in setOf(MinecraftExecutionPhase.PREPARED, MinecraftExecutionPhase.QUEUED, MinecraftExecutionPhase.RUNNING)
            is BuildExecutionFlow.Failed, BuildExecutionFlow.Idle -> false
        }
        flowLocked || executionState.records.any {
            it.planRecordId == selected.recordId && it.phase in setOf(
                MinecraftExecutionPhase.PREPARED, MinecraftExecutionPhase.QUEUED, MinecraftExecutionPhase.RUNNING,
            )
        }
    } ?: false
    val displayedPlan = ready?.candidate?.plan ?: plan
    val bridgeConnection = bridgeState.connection as? BridgeConnectionState.Connected
    val runtimeDescriptor = bridgeConnection?.capabilities?.runtimeDescriptor
    val planCompatibility = remember(displayedPlan, runtimeDescriptor) {
        runtimeDescriptor?.let { compatibilityResolver.resolve(displayedPlan, it) }
    }
    val planRequirements = remember(displayedPlan) { BuildPlanRequirements.from(displayedPlan) }
    val registeredProfileSummary = remember(compatibilityResolver) {
        compatibilityResolver.registeredAdapters().flatMap { adapter ->
            adapter.supportedRuntimeDescriptors.map { profile ->
                buildList {
                    add("${profile.edition.displayName} ${profile.version.displayIdentifier}")
                    add("${profile.loader.displayName} ${profile.loaderVersion}")
                    profile.requiredFabricApiVersion?.let { add("Fabric API $it") }
                    add("bridge ${profile.bridgeVersion} · protocol ${profile.bridgeProtocolVersion}")
                    profile.javaRuntimeRequirement?.let {
                        add("Java ${it.requiredMajor} (supported ${it.minimumSupportedMajor}–${it.maximumSupportedMajor})")
                    }
                }.joinToString(" · ", prefix = "${adapter.adapterId.value}: ")
            }
        }.ifEmpty { listOf("none registered") }.joinToString("; ")
    }
    val dismissReview = {
        if (matchingState is BuildRefinementState.Generating) onRefinementEvent(BuildRefinementEvent.Cancel)
        if (executionState.flow is BuildExecutionFlow.PreviewReady) onExecutionEvent(BuildExecutionEvent.DismissPreview)
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
                    "AI-generated and validated · construction requires independent server preflight and final confirmation",
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
                        if (request.prompt.isNotBlank()) {
                            Text(request.prompt, style = MaterialTheme.typography.bodyMedium)
                        } else if (request.imageContentUri != null) {
                            Text("Image-only request; no written prompt was supplied.", style = MaterialTheme.typography.bodyMedium)
                        } else if (request.referenceAnalysisSource != null) {
                            Text("Video-only request; no written prompt was supplied.", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (request.imageContentUri != null) {
                            val source = request.imageAnalysisSource
                            if (source == null) {
                                Text(
                                    "A local image reference is recorded, but no initial image analysis is stored. Refinement never resends this image.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    "Initial image source: sent directly to ${source.providerId} / ${source.modelId}. The raw image is not stored by CraftMind; the local reference may later be unavailable.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Refinement uses the validated plan and these saved text notes only; it does not resend or reanalyze the image. The notes are not a visual-accuracy guarantee.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        request.urlReference?.let { url ->
                            val source = request.referenceAnalysisSource
                            if (source == null) {
                                Text(
                                    "URL reference retained locally (${safeReferenceHostPath(url)}), but no public video was retrieved or analyzed. Refinement will not fetch it.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    "Public video analyzed once: ${source.sourceDomain} · ${source.mediaType} · ${formatDuration(source.durationMillis)} · ${source.frameCount} distinct frames. Source path: ${safeReferenceHostPath(url)}.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "The raw video is not saved by CraftMind. Sampled frame images were sent directly to ${source.providerId} / ${source.modelId}; that provider's terms govern its processing and retention. Refinement uses only saved text notes and never re-downloads the video.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        ready?.request?.instruction?.let { instruction ->
                            Text("Refinement: $instruction", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    request.imageAnalysisSource?.let { source ->
                        ReviewCard("Initial image analysis · AI-generated, not verified") {
                            Text("Source: ${source.providerId} / ${source.modelId}", style = MaterialTheme.typography.labelMedium)
                            Text(source.analysis.summary, style = MaterialTheme.typography.bodyMedium)
                            if (source.analysis.observedDetails.isNotEmpty()) {
                                Text("Observed details · may be inaccurate", style = MaterialTheme.typography.titleSmall)
                                source.analysis.observedDetails.forEach { detail ->
                                    Text("• $detail", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (source.analysis.inferredDetails.isNotEmpty()) {
                                Text("Inferred details · uncertain suggestions", style = MaterialTheme.typography.titleSmall)
                                source.analysis.inferredDetails.forEach { detail ->
                                    Text("• $detail", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (source.analysis.uncertainties.isNotEmpty()) {
                                Text("Uncertainties", style = MaterialTheme.typography.titleSmall)
                                source.analysis.uncertainties.forEach { detail ->
                                    Text("• $detail", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    request.referenceAnalysisSource?.let { source ->
                        ReviewCard("Initial video-frame analysis · AI-generated, not verified") {
                            Text(
                                "Source: ${source.sourceDomain} · ${source.mediaType} · ${formatDuration(source.durationMillis)} · ${source.frameCount} frames · ${source.providerId} / ${source.modelId}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                "Approximate sample points: ${source.sampledTimestampsMillis.joinToString { formatDuration(it) }}. Frames are stages/views of one video; the latest clear frame is prioritized but is not assumed complete.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(source.analysis.summary, style = MaterialTheme.typography.bodyMedium)
                            if (source.analysis.observedDetails.isNotEmpty()) {
                                Text("Observed details · may be inaccurate", style = MaterialTheme.typography.titleSmall)
                                source.analysis.observedDetails.forEach { detail ->
                                    Text("• $detail", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (source.analysis.inferredDetails.isNotEmpty()) {
                                Text("Inferred details · uncertain suggestions", style = MaterialTheme.typography.titleSmall)
                                source.analysis.inferredDetails.forEach { detail ->
                                    Text("• $detail", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (source.analysis.uncertainties.isNotEmpty()) {
                                Text("Unknown, occluded, or conflicting details", style = MaterialTheme.typography.titleSmall)
                                source.analysis.uncertainties.forEach { detail ->
                                    Text("• $detail", style = MaterialTheme.typography.bodySmall)
                                }
                            }
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

                    ConstructionExecutionCard(
                        record = record,
                        plan = displayedPlan,
                        currentRecord = currentRecord,
                        candidateReview = ready != null,
                        bridgeState = bridgeState,
                        executionState = executionState,
                        onEvent = onExecutionEvent,
                    )

                    if (record != null) {
                        RefinementCard(
                            record = record,
                            isCurrent = currentRecord != null,
                            isBusy = busy || executionLocked,
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
private fun ConstructionExecutionCard(
    record: LocalBuildRecord?,
    plan: BuildPlan,
    currentRecord: LocalBuildRecord?,
    candidateReview: Boolean,
    bridgeState: BridgePairingState,
    executionState: BuildExecutionState,
    onEvent: (BuildExecutionEvent) -> Unit,
) {
    val flow = executionState.flow
    val matchingFlow = when (flow) {
        is BuildExecutionFlow.Preparing -> flow.takeIf { it.planRecordId == record?.recordId }
        is BuildExecutionFlow.PreviewReady -> flow.takeIf { it.planRecordId == record?.recordId }
        is BuildExecutionFlow.Starting -> flow.takeIf { it.planRecordId == record?.recordId }
        is BuildExecutionFlow.Tracking -> flow.takeIf { it.record.planRecordId == record?.recordId }
        is BuildExecutionFlow.Failed -> flow.takeIf { it.planRecord?.recordId == record?.recordId }
        BuildExecutionFlow.Idle -> null
    }
    val preview = (matchingFlow as? BuildExecutionFlow.PreviewReady)?.preview
    var now by remember(preview?.executionId) { mutableLongStateOf(System.currentTimeMillis()) }
    var showFinalConfirmation by remember(preview?.executionId) { mutableStateOf(false) }
    LaunchedEffect(preview?.executionId, preview?.expiresAtEpochMillis) {
        val expiry = preview?.expiresAtEpochMillis ?: return@LaunchedEffect
        while (now < expiry) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }

    val compatibleBridge = bridgeConnection?.takeIf {
        planCompatibility?.let { result ->
            result.status == MinecraftCompatibilityStatus.SUPPORTED && result.canExecute
        } == true && it.bridge.bridgeId == it.capabilities.bridgeId
    }
    val constructionEnabled = compatibleBridge != null
    val eligibleSavedVersion = record != null && !candidateReview && currentRecord?.recordId == record.recordId &&
        record.plan.status == com.craftmind.app.domain.buildplan.BuildStatus.READY &&
        record.plan.metadata.schemaVersion == 2
    val savedExecution = record?.let { selected ->
        executionState.records.filter { it.planRecordId == selected.recordId }.maxByOrNull { it.updatedAtEpochMillis }
    }
    val activeRecord = (matchingFlow as? BuildExecutionFlow.Tracking)?.record ?: savedExecution
    val activeExecution = executionState.records.any { execution ->
        execution.phase in setOf(MinecraftExecutionPhase.PREPARED, MinecraftExecutionPhase.QUEUED, MinecraftExecutionPhase.RUNNING) &&
            (compatibleBridge == null || execution.bridgeId == compatibleBridge.bridge.bridgeId)
    }
    val canStartPreflight = eligibleSavedVersion && constructionEnabled && !activeExecution &&
        !executionState.isLoading && !executionState.loadFailed &&
        matchingFlow !is BuildExecutionFlow.Preparing && matchingFlow !is BuildExecutionFlow.Starting &&
        matchingFlow !is BuildExecutionFlow.PreviewReady

    ReviewCard("Minecraft compatibility and available limits") {
        Text(
            "Compatibility: ${planCompatibility?.status?.name ?: MinecraftCompatibilityStatus.UNKNOWN.name}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            runtimeDescriptor?.let {
                "Runtime: CraftMind app ${it.appVersion ?: "not echoed"} · ${it.edition.displayName} · Minecraft ${it.version.displayIdentifier} · Java ${it.javaRuntimeMajor ?: "not reported"} · ${it.loader.displayName} ${it.loaderVersion ?: "unknown"} · Fabric API ${it.fabricApiVersion ?: "not reported"} · bridge ${it.bridgeVersion ?: "unknown"} · protocol ${it.bridgeProtocolVersion ?: "unknown"}"
            } ?: "Runtime: unknown — connect to a pinned bridge to resolve compatibility.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Selected adapter: ${planCompatibility?.adapterId?.value ?: "none"}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Plan requires: ${planRequirements.requiredCapabilities.sortedBy { it.name }.joinToString { it.displayName }}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Available capabilities: ${planCompatibility?.capabilities?.sortedBy { it.name }?.joinToString { it.displayName }?.ifEmpty { "none" } ?: "not resolved"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Missing capabilities: ${planCompatibility?.missingCapabilities?.sortedBy { it.name }?.joinToString { it.displayName }?.ifEmpty { "none" } ?: "not resolved"}",
            style = MaterialTheme.typography.bodySmall,
            color = if (planCompatibility?.missingCapabilities?.isNotEmpty() == true) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Bridge limits: ${runtimeDescriptor?.maximumValidatedOperations?.let { "$it operations" } ?: "operation limit unknown"} · ${runtimeDescriptor?.maximumRequestBytes?.let { "$it request bytes" } ?: "request limit unknown"} · ${runtimeDescriptor?.maximumOperationsPerTick?.let { "$it operations/tick" } ?: "per-tick limit unknown"} · ${runtimeDescriptor?.maximumExecutionSeconds?.let { "$it seconds" } ?: "execution timeout unknown"}; adapter dimensions ${planCompatibility?.limits?.maximumDimensions?.let { "${it.width}×${it.height}×${it.depth}" } ?: "not matched"}.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Registered profile(s): $registeredProfileSummary. Runtime Java and Fabric API values are bridge-reported; declared dependency/toolchain values are not used as substitutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        planCompatibility?.reasonCodes?.sortedBy { it.name }?.takeIf { it.isNotEmpty() }?.let { codes ->
            Text("Reason codes: ${codes.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        planCompatibility?.reasons?.forEach { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        planCompatibility?.warnings?.forEach { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    ReviewCard("Minecraft construction · explicit confirmation required") {
        Text(
            "This sends only the saved, validated, platform-neutral BuildPlan v2 through the exactly matched adapter and existing authenticated bridge. Preflight places zero blocks. Construction begins only after the separate final confirmation below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidateReview) {
            Text("Accept this candidate as a new immutable version before preparing it for construction.", style = MaterialTheme.typography.bodySmall)
        } else if (record == null) {
            Text("This plan was not saved to local history, so it cannot be sent for construction.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (record.plan.metadata.schemaVersion != 2 || record.plan.status != com.craftmind.app.domain.buildplan.BuildStatus.READY) {
            Text("Only a validated BuildPlan v2 in READY state can be prepared.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (currentRecord == null) {
            Text("Only the latest accepted immutable version can be constructed. History is loading or this version is stale.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }

        if (!constructionEnabled) {
            Text(
                "Disabled: compatibility must resolve to SUPPORTED for this plan, the exact registered adapter must match, all required capabilities and limits must pass, and authenticated server preflight must succeed. Pairing alone is not compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (executionState.loadFailed) {
            Text("Local execution history could not be read. Construction is disabled until storage is available.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        when (val current = matchingFlow) {
            is BuildExecutionFlow.Preparing -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Bridge preflight is checking the world and every placement. No blocks are placed during this step.", style = MaterialTheme.typography.bodySmall)
                }
            }
            is BuildExecutionFlow.PreviewReady -> {
                val remainingSeconds = ((current.preview.expiresAtEpochMillis - now).coerceAtLeast(0L) / 1_000L)
                val world = bridgeWorldLabel(
                    bridgeState,
                    compatibleBridge?.bridge?.bridgeId ?: savedExecution?.bridgeId.orEmpty(),
                    current.preview.worldSessionId,
                )
                Text("Resolved world: $world", style = MaterialTheme.typography.bodySmall)
                Text("Dimension: ${current.preview.dimensionId}", style = MaterialTheme.typography.bodySmall)
                Text("Operator-selected origin: ${current.preview.resolvedOrigin.label()} · server-resolved", style = MaterialTheme.typography.bodySmall)
                Text("BuildPlan origin strategy: ${plan.originStrategy.name}", style = MaterialTheme.typography.bodySmall)
                Text("Immutable plan: ${current.preview.planTitle} · version ${current.preview.planVersion} · ${current.preview.operationCount} placements", style = MaterialTheme.typography.bodySmall)
                Text("Preflight token expires in about ${remainingSeconds}s. Starting is never automatic.", style = MaterialTheme.typography.bodySmall)
                current.errorCode?.let { Text("Local execution status could not be persisted ($it). Do not continue until local storage is available.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showFinalConfirmation = true },
                        enabled = constructionEnabled && eligibleSavedVersion && !executionState.isLoading &&
                            !executionState.loadFailed && current.errorCode == null && remainingSeconds > 0,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Review final confirmation") }
                    OutlinedButton(
                        onClick = { onEvent(BuildExecutionEvent.DismissPreview) },
                        enabled = constructionEnabled,
                    ) { Text("Cancel preview") }
                }
                if (showFinalConfirmation) {
                    val runtimeLabel = runtimeDescriptor?.let {
                        "CraftMind app ${it.appVersion ?: "not echoed"} · ${it.edition.displayName} · Minecraft ${it.version.displayIdentifier} · Java ${it.javaRuntimeMajor ?: "not reported"} · ${it.loader.displayName} ${it.loaderVersion ?: "unknown"} · Fabric API ${it.fabricApiVersion ?: "not reported"} · bridge ${it.bridgeVersion ?: "unknown"} / protocol ${it.bridgeProtocolVersion ?: "unknown"}"
                    } ?: "runtime unknown"
                    val adapterLabel = planCompatibility?.adapterId?.value ?: "none"
                    val limitsLabel = planCompatibility?.limits?.let {
                        "${it.maximumValidatedOperations ?: "unknown"} operations · ${it.maximumRequestBytes ?: "unknown"} request bytes · ${it.maximumOperationsPerTick ?: "unknown"} operations/tick · ${it.maximumExecutionSeconds ?: "unknown"} seconds · ${it.maximumDimensions?.let { dimensions -> "${dimensions.width}×${dimensions.height}×${dimensions.depth} blocks" } ?: "dimension limit unknown"}"
                    } ?: "unavailable"
                    val reportedCapabilities = planCompatibility?.capabilities
                        ?.sortedBy { it.name }?.joinToString { it.displayName }?.ifEmpty { "none" } ?: "not resolved"
                    val capabilityGaps = planCompatibility?.missingCapabilities
                        ?.sortedBy { it.name }?.joinToString { it.displayName }?.ifEmpty { "none" } ?: "not resolved"
                    val reasons = planCompatibility?.reasons?.joinToString("; ")?.ifEmpty { "none" } ?: "not resolved"
                    val summary = "Compatibility: ${planCompatibility?.status?.name ?: "UNKNOWN"}\nRuntime: $runtimeLabel\nAdapter: $adapterLabel\nBridge-reported capabilities: $reportedCapabilities\nCapability gaps: $capabilityGaps\nCompatibility reasons: $reasons\nAvailable limits: $limitsLabel\nWorld: $world\nDimension: ${current.preview.dimensionId}\nOrigin: ${current.preview.resolvedOrigin.label()}\nPlan: ${current.preview.planTitle} · version ${current.preview.planVersion}\nOperations: ${current.preview.operationCount}\nStrategy: ${plan.originStrategy.name}"
                    AlertDialog(
                        onDismissRequest = { showFinalConfirmation = false },
                        title = { Text("Authorize block placement?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(summary, style = MaterialTheme.typography.bodySmall)
                                Text("The authenticated server will begin placing blocks in bounded batches. Failed or cancelled work can leave partial changes; CraftMind does not provide rollback.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showFinalConfirmation = false
                                    onEvent(BuildExecutionEvent.Confirm)
                                },
                                enabled = constructionEnabled && eligibleSavedVersion && !executionState.isLoading &&
                                    !executionState.loadFailed && remainingSeconds > 0 && current.errorCode == null,
                            ) { Text("Confirm and start construction") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showFinalConfirmation = false }) { Text("Not now") }
                        },
                    )
                }
            }
            is BuildExecutionFlow.Starting -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Sending the explicit confirmation to the bridge. Waiting for its execution record; no progress is assumed.", style = MaterialTheme.typography.bodySmall)
                }
            }
            is BuildExecutionFlow.Tracking -> ExecutionStatus(
                record = current.record,
                refreshing = current.refreshing,
                connectionReasonCode = current.connectionReasonCode,
                bridgeRecordMissing = current.bridgeRecordMissing,
                cancellationRequested = current.cancellationRequested,
                constructionEnabled = constructionEnabled,
                onRefresh = { onEvent(BuildExecutionEvent.RefreshStatus(current.record.executionId)) },
                onCancel = { onEvent(BuildExecutionEvent.Cancel(current.record.executionId)) },
            )
            is BuildExecutionFlow.Failed -> {
                Text("Bridge operation did not reach a usable preview: ${current.reasonCode}. No successful placement is claimed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                current.detailMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (current.retryPrepare && constructionEnabled && eligibleSavedVersion) {
                    OutlinedButton(onClick = { onEvent(BuildExecutionEvent.RetryPrepare) }) { Text("Run a new preflight") }
                }
                activeRecord?.let { record ->
                    ExecutionStatus(
                        record = record,
                        refreshing = false,
                        connectionReasonCode = null,
                        bridgeRecordMissing = false,
                        cancellationRequested = false,
                        constructionEnabled = constructionEnabled,
                        onRefresh = { onEvent(BuildExecutionEvent.RefreshStatus(record.executionId)) },
                        onCancel = { onEvent(BuildExecutionEvent.Cancel(record.executionId)) },
                    )
                }
            }
            BuildExecutionFlow.Idle, null -> {
                activeRecord?.let { record ->
                    ExecutionStatus(
                        record = record,
                        refreshing = false,
                        connectionReasonCode = null,
                        bridgeRecordMissing = false,
                        cancellationRequested = false,
                        constructionEnabled = constructionEnabled,
                        onRefresh = { onEvent(BuildExecutionEvent.RefreshStatus(record.executionId)) },
                        onCancel = { onEvent(BuildExecutionEvent.Cancel(record.executionId)) },
                    )
                }
            }
        }

        val activeRecordIsTerminal = activeRecord?.phase in setOf(
            MinecraftExecutionPhase.COMPLETED, MinecraftExecutionPhase.FAILED, MinecraftExecutionPhase.CANCELLED,
        )
        val matchingTrackingIsTerminal = (matchingFlow as? BuildExecutionFlow.Tracking)?.record?.phase in setOf(
            MinecraftExecutionPhase.COMPLETED, MinecraftExecutionPhase.FAILED, MinecraftExecutionPhase.CANCELLED,
        )
        if (matchingFlow !is BuildExecutionFlow.Preparing && matchingFlow !is BuildExecutionFlow.PreviewReady &&
            matchingFlow !is BuildExecutionFlow.Starting &&
            (matchingFlow !is BuildExecutionFlow.Tracking || matchingTrackingIsTerminal) &&
            (matchingFlow !is BuildExecutionFlow.Failed || !matchingFlow.retryPrepare) &&
            (activeRecord == null || activeRecordIsTerminal)
        ) {
            Button(
                onClick = { record?.let { onEvent(BuildExecutionEvent.Prepare(it)) } },
                enabled = canStartPreflight,
                shape = RoundedCornerShape(14.dp),
            ) { Text("Preflight with Minecraft bridge") }
        }
    }
}

@Composable
private fun ExecutionStatus(
    record: LocalBuildExecutionRecord,
    refreshing: Boolean,
    connectionReasonCode: String?,
    bridgeRecordMissing: Boolean,
    cancellationRequested: Boolean,
    constructionEnabled: Boolean,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    Text("Bridge execution: ${record.phase.name}", style = MaterialTheme.typography.titleSmall)
    Text("Bridge-reported operation count: ${record.completedOperations} / ${record.totalOperations} · event ${record.eventSequence}", style = MaterialTheme.typography.bodySmall)
    Text("Resolved world: ${record.dimensionId} · session ${record.worldSessionId} · origin ${record.resolvedOrigin.label()}", style = MaterialTheme.typography.bodySmall)
    Text("Bridge record updated: ${formatTime(record.updatedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
    when (record.phase) {
        MinecraftExecutionPhase.PREPARED -> Text("Preflight only: no blocks have been placed. After app restart the short-lived confirmation token is not resumed automatically.", style = MaterialTheme.typography.bodySmall)
        MinecraftExecutionPhase.QUEUED -> Text("The bridge accepted the explicit start and queued this build. Counts below come from bridge status.", style = MaterialTheme.typography.bodySmall)
        MinecraftExecutionPhase.RUNNING -> Text("The Minecraft server is processing bounded batches. Progress below is bridge-reported; disconnecting will not auto-resume or duplicate work.", style = MaterialTheme.typography.bodySmall)
        MinecraftExecutionPhase.COMPLETED -> Text("The bridge reported completion. CraftMind has no independent visual verification.", style = MaterialTheme.typography.bodySmall)
        MinecraftExecutionPhase.FAILED -> Text("The bridge reported failure. Partial world changes may remain; no rollback is available.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        MinecraftExecutionPhase.CANCELLED -> Text("The bridge reported cancellation. Completed blocks remain; no rollback is available.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
    }
    record.reasonCode?.let { reason ->
        Text("Bridge reason: $reason${record.failedOperationIndex?.let { index -> " · operation ${index + 1}" }.orEmpty()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (reason == "SERVER_RESTARTED") {
            Text("This is the last persisted bridge checkpoint. A server crash may have happened between a world write and its saved count, so the actual world can differ. Inspect the world in game before any new build; CraftMind will not resume or roll back.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (connectionReasonCode != null) Text("Latest bridge action/status issue: $connectionReasonCode. The saved state is only the last bridge report.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
    if (bridgeRecordMissing) Text("The authenticated bridge returned no record for this execution ID. Local history is retained; CraftMind will not restart or mark it complete.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (cancellationRequested) Text("Cancellation was requested; wait for a bridge-reported terminal status.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh, enabled = constructionEnabled && !refreshing) {
            if (refreshing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text("Refresh bridge status")
        }
        if (record.phase in setOf(MinecraftExecutionPhase.PREPARED, MinecraftExecutionPhase.QUEUED, MinecraftExecutionPhase.RUNNING)) {
            TextButton(onClick = onCancel, enabled = constructionEnabled) { Text("Request cancellation") }
        }
    }
}

private fun bridgeWorldLabel(bridgeState: BridgePairingState, bridgeId: String, worldSessionId: String): String {
    val profile = bridgeState.profile?.takeIf { it.bridgeId == bridgeId }
    val base = profile?.let { "${it.displayName} · ${it.host}:${it.port}" } ?: "bridge $bridgeId"
    return "$base · world session $worldSessionId"
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
    AiErrorCode.VISION_UNSUPPORTED -> "Image capability is not used for refinement; no image was resent."
    AiErrorCode.INVALID_BUILD_REQUEST -> "The original request metadata is invalid. The saved plan remains unchanged."
    AiErrorCode.IMAGE_UNREADABLE, AiErrorCode.IMAGE_CONTENT_INVALID, AiErrorCode.IMAGE_TOO_LARGE,
    AiErrorCode.IMAGE_DIMENSIONS_UNSUPPORTED, AiErrorCode.IMAGE_MIME_MISMATCH -> "Image data is never resent during refinement; the saved plan remains unchanged."
    AiErrorCode.MULTI_IMAGE_UNSUPPORTED, AiErrorCode.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED,
    AiErrorCode.REFERENCE_UNSAFE_URL, AiErrorCode.REFERENCE_UNSAFE_DESTINATION,
    AiErrorCode.REFERENCE_UNSUPPORTED_SOURCE, AiErrorCode.REFERENCE_UNAVAILABLE,
    AiErrorCode.REFERENCE_ACCESS_RESTRICTED, AiErrorCode.REFERENCE_REDIRECT_BLOCKED,
    AiErrorCode.REFERENCE_RANGE_UNSUPPORTED, AiErrorCode.REFERENCE_MEDIA_UNSUPPORTED,
    AiErrorCode.REFERENCE_TOO_LARGE, AiErrorCode.REFERENCE_DURATION_UNSUPPORTED,
    AiErrorCode.REFERENCE_FRAME_EXTRACTION_FAILED, AiErrorCode.REFERENCE_NO_DISTINCT_FRAMES,
    AiErrorCode.REFERENCE_TRANSFER_LIMIT, AiErrorCode.REFERENCE_TIMEOUT ->
        "Video analysis runs only during initial generation. Refinement uses saved text notes and never fetches the video again."
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

private fun safeReferenceHostPath(value: String): String = runCatching {
    val uri = URI(value)
    val host = uri.host ?: return@runCatching "Public video URL"
    "$host${uri.rawPath.orEmpty()}"
}.getOrDefault("Public video URL")

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(Locale.ROOT, minutes, seconds)
}

private const val MAX_VISIBLE_OPERATIONS = 100
private const val MAX_VISIBLE_DIFF_ITEMS = 40
private const val MAX_BLOCK_SUMMARY = 20
