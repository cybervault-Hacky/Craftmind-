package com.craftmind.app.domain.buildplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildEditRequestValidatorTest {
    @Test
    fun createsBoundedNaturalLanguageRequestFromImmutableSavedVersion() {
        val original = BuildPlanTestFixtures.record(
            plan = BuildPlanTestFixtures.legacyPlan(),
            buildId = "build-legacy",
            version = 1,
            recordId = "build-legacy-v1",
        ).copy(request = BuildRequestSnapshot("A stone house", imageContentUri = "content://local/image", urlReference = "https://example.org/plan"))
        val validator = BuildEditRequestValidator(nowEpochMillis = { 1_700_000_000_500 })

        val request = (validator.create(original, "  Add a small stone wall  ") as BuildEditValidationResult.Valid).request

        assertEquals("Add a small stone wall", request.instruction)
        assertEquals(original.recordId, request.baseRecordId)
        assertEquals(original.buildId, request.buildId)
        assertEquals(original.version, request.baseVersion)
        assertEquals(original.plan, request.basePlan)
        assertEquals(original.request, request.originalRequest)
        assertEquals(1_700_000_000_500, request.createdAtEpochMillis)
    }

    @Test
    fun rejectsEmptyOversizedAndInvalidBaseRequests() {
        val original = BuildPlanTestFixtures.record()
        val validator = BuildEditRequestValidator()
        assertEquals(
            BuildEditValidationError.EMPTY_INSTRUCTION,
            (validator.create(original, " \n ") as BuildEditValidationResult.Invalid).reason,
        )
        assertEquals(
            BuildEditValidationError.INSTRUCTION_TOO_LONG,
            (validator.create(original, "x".repeat(BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH + 1)) as BuildEditValidationResult.Invalid).reason,
        )
        assertEquals(
            BuildEditValidationError.INVALID_BASE_VERSION,
            (validator.create(original.copy(version = 0), "Change the roof") as BuildEditValidationResult.Invalid).reason,
        )
        val invalidPlan = original.copy(plan = original.plan.copy(status = BuildStatus.DRAFT))
        val invalidResult = validator.create(invalidPlan, "Change the roof") as BuildEditValidationResult.Invalid
        assertEquals(BuildEditValidationError.INVALID_BASE_PLAN, invalidResult.reason)
        assertTrue(invalidPlan.plan !== original.plan)

        val unlinkedLegacy = BuildPlanTestFixtures.legacyPlan().copy(
            operations = BuildPlanTestFixtures.legacyPlan().operations.mapIndexed { index, operation ->
                if (index == 0) operation.copy(componentId = null) else operation
            },
        )
        val legacyRecord = original.copy(plan = unlinkedLegacy)
        val legacyResult = validator.create(legacyRecord, "Change the roof") as BuildEditValidationResult.Invalid
        assertEquals(BuildEditValidationError.LEGACY_PLAN_NOT_UPGRADABLE, legacyResult.reason)
    }
}
