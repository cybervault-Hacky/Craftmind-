package com.craftmind.app.domain.buildplan

/** Pure version-history rules used by the atomic storage adapter and domain tests. */
class BuildHistoryPolicy(
    private val validator: BuildPlanValidator = DefaultBuildPlanValidator(),
    private val diffCalculator: BuildDiffCalculator = BuildDiffCalculator(),
) {
    data class Change(val records: List<LocalBuildRecord>, val appended: LocalBuildRecord)

    fun appendInitial(
        records: List<LocalBuildRecord>,
        plan: ValidatedBuildPlan,
        request: BuildRequest,
        savedAtEpochMillis: Long,
        imageAnalysisSource: BuildImageAnalysisSource? = null,
        referenceAnalysisSource: BuildReferenceAnalysisSource? = null,
    ): Change {
        ensureValidPlan(plan.plan)
        if ((request.prompt.isBlank() && request.imageReference == null && request.urlReference == null) ||
            (request.imageReference != null && request.urlReference != null) || savedAtEpochMillis <= 0L ||
            plan.plan.metadata.sourceRequestId != request.requestId ||
            (request.imageReference == null) != (imageAnalysisSource == null) ||
            (imageAnalysisSource != null && (
                !imageAnalysisSource.isWellFormed() ||
                    imageAnalysisSource.providerId != plan.plan.metadata.providerId ||
                    imageAnalysisSource.modelId != plan.plan.metadata.modelId
                )) ||
            (request.urlReference == null) != (referenceAnalysisSource == null) ||
            (referenceAnalysisSource != null && (
                !referenceAnalysisSource.isWellFormed(request.urlReference?.url) ||
                    referenceAnalysisSource.providerId != plan.plan.metadata.providerId ||
                    referenceAnalysisSource.modelId != plan.plan.metadata.modelId
                ))
        ) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        }
        val buildId = "build-${request.requestId}"
        if (records.any { it.buildId == buildId }) throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        ensureCanAddSeries(records)
        val record = LocalBuildRecord(
            recordId = "$buildId-v1",
            buildId = buildId,
            version = 1,
            plan = plan.plan,
            request = request.toSnapshot(imageAnalysisSource, referenceAnalysisSource),
            savedAtEpochMillis = savedAtEpochMillis,
        )
        return Change((listOf(record) + records).toList(), record)
    }

    fun appendRefinement(
        records: List<LocalBuildRecord>,
        baseRecordId: String,
        plan: ValidatedBuildPlan,
        request: BuildEditRequest,
        diff: BuildDiff,
        savedAtEpochMillis: Long,
    ): Change {
        val base = records.firstOrNull { it.recordId == baseRecordId }
            ?: throw BuildRepositoryException(BuildRepositoryError.NOT_FOUND)
        ensureIsCurrent(records, base)
        if (request.baseRecordId != base.recordId || request.buildId != base.buildId ||
            request.baseVersion != base.version || request.basePlan != base.plan ||
            request.instruction.isBlank() || request.instruction.length > BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH ||
            request.createdAtEpochMillis <= 0L || savedAtEpochMillis <= 0L || diff.summary.isBlank() ||
            plan.plan.metadata.sourceRequestId != base.plan.metadata.sourceRequestId ||
            diff.dimensionsBefore != base.plan.metadata.dimensions ||
            diff.dimensionsAfter != plan.plan.metadata.dimensions || !diff.hasChanges
        ) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        }
        ensureValidPlan(plan.plan)
        val nextVersion = base.version + 1
        ensureCanAddVersion(records, base.buildId, nextVersion)
        val record = LocalBuildRecord(
            recordId = "${base.buildId}-v$nextVersion",
            buildId = base.buildId,
            version = nextVersion,
            parentRecordId = base.recordId,
            plan = plan.plan,
            request = base.request,
            savedAtEpochMillis = savedAtEpochMillis,
            refinementInstruction = request.instruction,
            changeSummary = diff.summary,
            diff = diff,
        )
        return Change((listOf(record) + records).toList(), record)
    }

    fun appendRevert(
        records: List<LocalBuildRecord>,
        buildId: String,
        targetVersion: Int,
        expectedCurrentRecordId: String,
        savedAtEpochMillis: Long,
    ): Change {
        val series = records.filter { it.buildId == buildId }
        val current = series.maxByOrNull(LocalBuildRecord::version)
            ?: throw BuildRepositoryException(BuildRepositoryError.NOT_FOUND)
        if (current.recordId != expectedCurrentRecordId) {
            throw BuildRepositoryException(BuildRepositoryError.STALE_VERSION)
        }
        if (targetVersion !in 1 until current.version || savedAtEpochMillis <= 0L) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        }
        val target = series.firstOrNull { it.version == targetVersion }
            ?: throw BuildRepositoryException(BuildRepositoryError.NOT_FOUND)
        ensureValidPlan(target.plan)
        val nextVersion = current.version + 1
        ensureCanAddVersion(records, buildId, nextVersion)
        val restoredPlan = (validator.validate(target.plan) as? BuildPlanValidationResult.Valid)?.plan
            ?: throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        val summary = "Restored saved Version $targetVersion"
        val diff = diffCalculator.compare(current.plan, restoredPlan.plan, summary = summary)
        val record = LocalBuildRecord(
            recordId = "$buildId-v$nextVersion",
            buildId = buildId,
            version = nextVersion,
            parentRecordId = current.recordId,
            plan = restoredPlan.plan,
            request = target.request,
            savedAtEpochMillis = savedAtEpochMillis,
            changeSummary = summary,
            diff = diff,
            restoredFromVersion = targetVersion,
        )
        return Change((listOf(record) + records).toList(), record)
    }

    fun isValidHistory(records: List<LocalBuildRecord>): Boolean {
        if (records.size > BuildPlanLimits.MAX_BUILDS_IN_HISTORY * BuildPlanLimits.MAX_REVISIONS_PER_BUILD) return false
        if (records.map(LocalBuildRecord::recordId).toSet().size != records.size) return false
        if (records.map { it.buildId to it.version }.toSet().size != records.size) return false
        if (records.map(LocalBuildRecord::buildId).toSet().size > BuildPlanLimits.MAX_BUILDS_IN_HISTORY) return false
        if (records.any {
                it.version !in 1..BuildPlanLimits.MAX_REVISIONS_PER_BUILD || it.buildId.isBlank() ||
                    it.savedAtEpochMillis <= 0L
            }
        ) return false

        val byId = records.associateBy(LocalBuildRecord::recordId)
        return records.all { record ->
            val group = records.filter { it.buildId == record.buildId }
            val versionOne = group.firstOrNull { it.version == 1 }
            val parentValid = if (record.version == 1) {
                record.parentRecordId == null
            } else {
                val parent = record.parentRecordId?.let(byId::get)
                parent != null && parent.buildId == record.buildId && parent.version == record.version - 1
            }
            // Older history may retain local image/URL references without having analyzed them.
            val imageSourceWellFormed = record.request.imageAnalysisSource?.let { source ->
                record.request.imageContentUri != null && source.isWellFormed()
            } != false
            val referenceSourceWellFormed = record.request.referenceAnalysisSource?.let { source ->
                record.request.urlReference != null && source.isWellFormed(record.request.urlReference)
            } != false
            val sourceMatches = versionOne != null && record.plan.metadata.sourceRequestId.isNotBlank() &&
                record.plan.metadata.sourceRequestId == versionOne.plan.metadata.sourceRequestId &&
                record.request == versionOne.request &&
                (record.request.prompt.isNotBlank() || record.request.imageContentUri != null || record.request.urlReference != null) &&
                imageSourceWellFormed && referenceSourceWellFormed
            val initialImageSource = versionOne?.request?.imageAnalysisSource
            val imageSourceMatchesInitialPlan = when {
                initialImageSource == null -> true
                versionOne == null -> false
                else -> initialImageSource.providerId == versionOne.plan.metadata.providerId &&
                    initialImageSource.modelId == versionOne.plan.metadata.modelId
            }
            val initialReferenceSource = versionOne?.request?.referenceAnalysisSource
            val referenceSourceMatchesInitialPlan = when {
                initialReferenceSource == null -> true
                versionOne == null -> false
                else -> initialReferenceSource.providerId == versionOne.plan.metadata.providerId &&
                    initialReferenceSource.modelId == versionOne.plan.metadata.modelId
            }
            val planValid = validator.validate(record.plan) is BuildPlanValidationResult.Valid &&
                record.plan.status == BuildStatus.READY
            val diffValid = record.version == 1 || record.diff?.let { it.hasChanges || record.restoredFromVersion != null } == true
            val restoredVersionValid = record.restoredFromVersion?.let { targetVersion ->
                targetVersion in 1 until record.version && group.any { it.version == targetVersion }
            } ?: true
            val lineageValid = record.version == 1 || versionOne != null
            parentValid && sourceMatches && imageSourceMatchesInitialPlan && referenceSourceMatchesInitialPlan &&
                planValid && diffValid && restoredVersionValid && lineageValid &&
                (record.version == 1 || record.recordId == "${record.buildId}-v${record.version}")
        }
    }

    private fun ensureIsCurrent(records: List<LocalBuildRecord>, base: LocalBuildRecord) {
        val current = records.filter { it.buildId == base.buildId }.maxByOrNull(LocalBuildRecord::version)
        if (current?.recordId != base.recordId) throw BuildRepositoryException(BuildRepositoryError.STALE_VERSION)
    }

    private fun ensureValidPlan(plan: BuildPlan) {
        if (plan.status != BuildStatus.READY || validator.validate(plan) !is BuildPlanValidationResult.Valid) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        }
    }

    private fun ensureCanAddSeries(records: List<LocalBuildRecord>) {
        if (records.map(LocalBuildRecord::buildId).toSet().size >= BuildPlanLimits.MAX_BUILDS_IN_HISTORY) {
            throw BuildRepositoryException(BuildRepositoryError.HISTORY_LIMIT_REACHED)
        }
    }

    private fun ensureCanAddVersion(records: List<LocalBuildRecord>, buildId: String, nextVersion: Int) {
        if (nextVersion > BuildPlanLimits.MAX_REVISIONS_PER_BUILD ||
            records.size >= BuildPlanLimits.MAX_BUILDS_IN_HISTORY * BuildPlanLimits.MAX_REVISIONS_PER_BUILD
        ) {
            throw BuildRepositoryException(BuildRepositoryError.HISTORY_LIMIT_REACHED)
        }
        if (records.any { it.buildId == buildId && it.version == nextVersion }) {
            throw BuildRepositoryException(BuildRepositoryError.INVALID_RECORD)
        }
    }
}

private fun BuildRequest.toSnapshot(
    imageAnalysisSource: BuildImageAnalysisSource?,
    referenceAnalysisSource: BuildReferenceAnalysisSource?,
) = BuildRequestSnapshot(
    prompt = prompt,
    imageContentUri = imageReference?.contentUri,
    imageMediaType = imageReference?.mediaType,
    imageDisplayName = imageReference?.displayName,
    imageSizeBytes = imageReference?.sizeBytes,
    urlReference = urlReference?.url,
    imageAnalysisSource = imageAnalysisSource,
    referenceAnalysisSource = referenceAnalysisSource,
)
