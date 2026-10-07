package com.craftmind.app.presentation.builds

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Builds library card model (Phase 15 §8).
 *
 * A pure derivation of the real local history into what a card may say. The library never contains anything that is
 * not saved on this device: no sample builds, no placeholders, no invented execution status. Every label below comes
 * from a [LocalBuildRecord] or from a [LocalBuildExecutionRecord] written by the bridge pipeline.
 */
data class BuildRecordSummary(
    val title: String,
    val summary: String,
    /** Local date and time the version was saved, formatted for the device locale. */
    val savedAtLabel: String,
    /** What the plan was generated from. */
    val sourceLabel: String,
    val dimensionLabel: String,
    val operationCountLabel: String,
    val modelLabel: String,
    /** Version and refinement position within this build's local history. */
    val versionLabel: String,
    /** Refinement provenance, when this version came from a refinement or a restore. */
    val refinementLabel: String?,
    /** Real execution status, when this build has an execution record. */
    val executionStatusLabel: String?,
    val executionTone: CraftMindTone,
    /** True when this record is not the newest version of its build. */
    val hasNewerVersion: Boolean,
)

private val cardDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

/** Formats an epoch-millis timestamp in [zone]; returns an honest placeholder when the value is not usable. */
internal fun formatSavedAt(epochMillis: Long, zone: ZoneId): String =
    if (epochMillis <= 0L) {
        "Saved time not recorded"
    } else {
        runCatching { cardDateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) }
            .getOrDefault("Saved time unavailable")
    }

/** Describes what the plan was generated from, without implying analysis that did not happen. */
internal fun describeRequestSource(record: LocalBuildRecord): String {
    val request = record.request
    val hasPrompt = request.prompt.isNotBlank()
    val analyzedImage = request.imageAnalysisSource != null
    val localImage = request.imageContentUri != null
    val videoReference = request.urlReference != null || request.referenceAnalysisSource != null
    val promptPart = if (hasPrompt) "Prompt" else null
    val visualPart = when {
        analyzedImage -> "analyzed image"
        localImage -> "local image reference"
        videoReference -> "public video reference"
        else -> null
    }
    return when {
        promptPart != null && visualPart != null -> "$promptPart + $visualPart"
        promptPart != null -> "Text prompt"
        visualPart != null -> visualPart.replaceFirstChar { it.uppercase(Locale.ROOT) }
        else -> "No description recorded"
    }
}

/** The newest execution record for a build, if any exists. */
internal fun latestExecutionFor(
    buildId: String,
    executions: List<LocalBuildExecutionRecord>,
): LocalBuildExecutionRecord? = executions
    .filter { it.buildId == buildId }
    .maxByOrNull { it.updatedAtEpochMillis }

/** Execution wording for one record; always tied to the phase the bridge actually reported. */
internal fun describeExecution(execution: LocalBuildExecutionRecord): Pair<String, CraftMindTone> {
    val progress = "${execution.completedOperations}/${execution.totalOperations} operations"
    return when (execution.phase) {
        MinecraftExecutionPhase.COMPLETED ->
            "Built in Minecraft · $progress completed" to CraftMindTone.POSITIVE

        MinecraftExecutionPhase.RUNNING ->
            "Building in Minecraft · $progress reported" to CraftMindTone.BRAND

        MinecraftExecutionPhase.QUEUED ->
            "Queued in Minecraft · $progress reported" to CraftMindTone.INFORMATIVE

        MinecraftExecutionPhase.PREPARED ->
            "Preflight prepared · awaiting your confirmation" to CraftMindTone.INFORMATIVE

        MinecraftExecutionPhase.FAILED -> {
            val reason = execution.reasonCode?.let { " ($it)" } ?: ""
            "Build failed in Minecraft$reason · $progress reported" to CraftMindTone.NEGATIVE
        }

        MinecraftExecutionPhase.CANCELLED ->
            "Build cancelled in Minecraft · $progress reported" to CraftMindTone.CAUTION
    }
}

/** Derives everything a library card shows for one saved version. */
fun buildRecordSummary(
    record: LocalBuildRecord,
    versionCount: Int,
    executions: List<LocalBuildExecutionRecord> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): BuildRecordSummary {
    val plan = record.plan
    val execution = latestExecutionFor(record.buildId, executions)
    val executionDescription = execution?.let { describeExecution(it) }
    val refinementLabel = when {
        record.restoredFromVersion != null ->
            "Restored from version ${record.restoredFromVersion}"

        record.refinementInstruction != null -> {
            val change = record.changeSummary?.takeIf { it.isNotBlank() }
            if (change != null) "Refined · $change" else "Refined · ${record.refinementInstruction}"
        }

        else -> null
    }
    return BuildRecordSummary(
        title = plan.metadata.title.ifBlank { "Untitled build" },
        summary = plan.metadata.summary,
        savedAtLabel = formatSavedAt(record.savedAtEpochMillis, zone),
        sourceLabel = describeRequestSource(record),
        dimensionLabel = "${plan.metadata.dimensions.width} × ${plan.metadata.dimensions.height} × " +
            "${plan.metadata.dimensions.depth} blocks",
        operationCountLabel = "${plan.operations.size} placements",
        modelLabel = "${plan.metadata.providerId} / ${plan.metadata.modelId}",
        versionLabel = if (versionCount > 1) {
            "Version ${record.version} of $versionCount saved"
        } else {
            "Version ${record.version}"
        },
        refinementLabel = refinementLabel,
        executionStatusLabel = executionDescription?.first,
        executionTone = executionDescription?.second ?: CraftMindTone.NEUTRAL,
        hasNewerVersion = versionCount > 1 && record.version < versionCount,
    )
}
