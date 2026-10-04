package com.craftmind.app.domain.buildplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildHistoryPolicyTest {
    private val validator = DefaultBuildPlanValidator()
    private val policy = BuildHistoryPolicy(validator)

    @Test
    fun acceptsRefinementAsNewImmutableVersionOnlyWithValidDiff() {
        val originalPlan = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(sourceRequestId = "request-history"),
        )
        val originalRequest = buildRequest()
        val first = policy.appendInitial(emptyList(), BuildPlanTestFixtures.validated(originalPlan), originalRequest, 1_700_000_000_100)
        val base = first.appended
        val editRequest = validEditRequest(base)
        val candidate = changedPlan(originalPlan)
        val diff = BuildDiffCalculator().compare(
            originalPlan,
            candidate,
            summary = "Changed the roof to brick",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            instruction = editRequest.instruction,
        )

        val accepted = policy.appendRefinement(
            records = first.records,
            baseRecordId = base.recordId,
            plan = BuildPlanTestFixtures.validated(candidate),
            request = editRequest,
            diff = diff,
            savedAtEpochMillis = 1_700_000_000_200,
        )

        assertEquals(1, base.version)
        assertEquals(originalPlan, base.plan)
        assertEquals(2, accepted.appended.version)
        assertEquals(base.recordId, accepted.appended.parentRecordId)
        assertEquals(listOf(accepted.appended.recordId, base.recordId), accepted.records.map(LocalBuildRecord::recordId))
        assertEquals("minecraft:stone", accepted.records.last().plan.operations.first().blockId)
        assertTrue(policy.isValidHistory(accepted.records))
    }

    @Test
    fun staleBaseOrInvalidCandidateCannotAlterHistory() {
        val originalPlan = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(sourceRequestId = "request-history"),
        )
        val first = policy.appendInitial(emptyList(), BuildPlanTestFixtures.validated(originalPlan), buildRequest(), 1_700_000_000_100)
        val base = first.appended
        val candidate = changedPlan(originalPlan)
        val diff = BuildDiffCalculator().compare(originalPlan, candidate, "Change roof", listOf("roof"), listOf("house"), "Change roof")
        val editRequest = validEditRequest(base)
        val second = policy.appendRefinement(first.records, base.recordId, BuildPlanTestFixtures.validated(candidate), editRequest, diff, 1_700_000_000_200)

        val stale = try {
            policy.appendRefinement(second.records, base.recordId, BuildPlanTestFixtures.validated(candidate), editRequest, diff, 1_700_000_000_300)
            throw AssertionError("Expected stale version rejection")
        } catch (expected: BuildRepositoryException) {
            expected
        }
        assertEquals(BuildRepositoryError.STALE_VERSION, stale.error)
        assertEquals(2, second.records.size)
        assertEquals(1, first.records.size)
        assertNotEquals(first.records, second.records)

        val noOpDiff = BuildPlanTestFixtures.diff(candidate, candidate, "No-op")
        val noOp = try {
            policy.appendRefinement(
                second.records,
                second.appended.recordId,
                BuildPlanTestFixtures.validated(candidate),
                validEditRequest(second.appended),
                noOpDiff,
                1_700_000_000_400,
            )
            throw AssertionError("Expected no-op candidate rejection")
        } catch (expected: BuildRepositoryException) {
            expected
        }
        assertEquals(BuildRepositoryError.INVALID_RECORD, noOp.error)
        assertEquals(2, second.records.size)
    }

    @Test
    fun localRevertCreatesANewVersionWithoutDeletingHistory() {
        val originalPlan = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(sourceRequestId = "request-history"),
        )
        val first = policy.appendInitial(emptyList(), BuildPlanTestFixtures.validated(originalPlan), buildRequest(), 1_700_000_000_100)
        val candidate = changedPlan(originalPlan)
        val editRequest = validEditRequest(first.appended)
        val diff = BuildPlanTestFixtures.diff(originalPlan, candidate)
        val second = policy.appendRefinement(first.records, first.appended.recordId, BuildPlanTestFixtures.validated(candidate), editRequest, diff, 1_700_000_000_200)

        val reverted = policy.appendRevert(
            records = second.records,
            buildId = first.appended.buildId,
            targetVersion = 1,
            expectedCurrentRecordId = second.appended.recordId,
            savedAtEpochMillis = 1_700_000_000_300,
        )

        assertEquals(3, reverted.appended.version)
        assertEquals(1, reverted.appended.restoredFromVersion)
        assertEquals(originalPlan.operations, reverted.appended.plan.operations)
        assertEquals(3, reverted.records.size)
        assertTrue(policy.isValidHistory(reverted.records))

        val repeatedRestore = policy.appendRevert(
            records = reverted.records,
            buildId = first.appended.buildId,
            targetVersion = 1,
            expectedCurrentRecordId = reverted.appended.recordId,
            savedAtEpochMillis = 1_700_000_000_400,
        )
        assertEquals(4, repeatedRestore.appended.version)
        assertEquals(false, repeatedRestore.appended.diff?.hasChanges)
        assertTrue(policy.isValidHistory(repeatedRestore.records))
    }

    @Test
    fun revertRejectsStaleCurrentVersionAndUnknownTarget() {
        val originalPlan = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(sourceRequestId = "request-history"),
        )
        val first = policy.appendInitial(emptyList(), BuildPlanTestFixtures.validated(originalPlan), buildRequest(), 1_700_000_000_100)

        val error = try {
            policy.appendRevert(first.records, first.appended.buildId, 4, "stale-id", 1_700_000_000_200)
            throw AssertionError("Expected stale current version rejection")
        } catch (expected: BuildRepositoryException) {
            expected
        }
        assertEquals(BuildRepositoryError.STALE_VERSION, error.error)
        assertEquals(1, first.records.size)
    }

    @Test
    fun v1FlatRecordsRemainReadableAsIndependentLocalHistories() {
        val legacy = BuildPlanTestFixtures.legacyPlan().copy(
            metadata = BuildPlanTestFixtures.legacyPlan().metadata.copy(sourceRequestId = "legacy-request"),
        )
        val legacyRecord = LocalBuildRecord(
            recordId = "legacy-request:legacy-plan",
            plan = legacy,
            request = BuildRequestSnapshot("Build a legacy home"),
            savedAtEpochMillis = 1_700_000_000_100,
        )
        assertTrue(policy.isValidHistory(listOf(legacyRecord)))
    }

    private fun buildRequest() = BuildRequest(
        requestId = "request-history",
        prompt = "Build a compact courtyard home",
        imageReference = null,
        urlReference = null,
        createdAtEpochMillis = 1_700_000_000_000,
    )

    private fun validEditRequest(base: LocalBuildRecord) = when (
        val result = BuildEditRequestValidator(validator) { 1_700_000_000_150 }.create(base, "Change the roof to brick")
    ) {
        is BuildEditValidationResult.Valid -> result.request
        is BuildEditValidationResult.Invalid -> error("Expected a valid local request: ${result.reason}")
    }

    private fun changedPlan(base: BuildPlan): BuildPlan = base.copy(
        metadata = base.metadata.copy(
            providerId = "google",
            modelId = "gemini-test",
            generatedAtEpochMillis = 1_700_000_000_200,
        ),
        components = base.components.map { component ->
            if (component.componentId == "roof") component.copy(purpose = "A brick roof above the home.") else component
        },
        operations = base.operations.mapIndexed { index, operation ->
            if (index == 2) operation.copy(blockId = "minecraft:bricks") else operation
        },
        status = BuildStatus.READY,
    )
}
