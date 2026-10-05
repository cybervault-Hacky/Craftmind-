package com.craftmind.app.data.builds

import androidx.test.core.app.ApplicationProvider
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildComponentType
import com.craftmind.app.domain.buildplan.BuildDiffCalculator
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildEditRequestValidator
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildIntent
import com.craftmind.app.domain.buildplan.BuildOriginStrategy
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanMetadata
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRepositoryError
import com.craftmind.app.domain.buildplan.BuildRepositoryException
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicLocalBuildRepositoryTest {
    @Test
    fun persistsAcceptedVersionsAtomicallyAndRejectedStaleWriteLeavesHistoryUntouched() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyFile = File(context.noBackupFilesDir, "local-build-records-v1.json")
        historyFile.delete()
        try {
            val validator = DefaultBuildPlanValidator()
            val repository = AtomicLocalBuildRepository(context, nowEpochMillis = { 1_700_000_000_100 }, historyPolicy = com.craftmind.app.domain.buildplan.BuildHistoryPolicy(validator))
            repository.load()
            val base = validPlan()
            val original = repository.save(
                requireValid(validator, base),
                BuildRequest("request-atomic", "Build a stone pavilion", null, null, 1_700_000_000_000),
            )
            val editRequest = (BuildEditRequestValidator(validator) { 1_700_000_000_200 }
                .create(original, "Change the pavilion floor to brick") as com.craftmind.app.domain.buildplan.BuildEditValidationResult.Valid).request
            val candidate = base.copy(
                metadata = base.metadata.copy(generatedAtEpochMillis = 1_700_000_000_300, providerId = "google", modelId = "gemini-test"),
                operations = listOf(base.operations.first().copy(blockId = "minecraft:bricks")),
            )
            val diff = BuildDiffCalculator().compare(
                base, candidate, "Changed the floor", listOf("main"), emptyList(), editRequest.instruction,
            )
            val second = repository.appendRefinement(original.recordId, requireValid(validator, candidate), editRequest, diff)

            assertEquals(2, second.version)
            assertEquals(2, repository.records.value.size)
            try {
                repository.appendRefinement(original.recordId, requireValid(validator, candidate), editRequest, diff)
                throw AssertionError("Expected stale version rejection")
            } catch (expected: BuildRepositoryException) {
                assertEquals(BuildRepositoryError.STALE_VERSION, expected.error)
            }
            assertEquals(2, repository.records.value.size)

            val reopened = AtomicLocalBuildRepository(context, historyPolicy = com.craftmind.app.domain.buildplan.BuildHistoryPolicy(validator))
            reopened.load()
            assertEquals(2, reopened.records.value.size)
            assertEquals(2, reopened.records.value.maxOf { it.version })
            assertEquals("minecraft:stone", reopened.records.value.last().plan.operations.first().blockId)
            assertTrue(historyFile.exists())
        } finally {
            historyFile.delete()
        }
    }

    @Test
    fun persistsImageOnlyTextProvenanceAndReferenceAcrossReopenWithoutImageBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyFile = File(context.noBackupFilesDir, "local-build-records-v1.json")
        historyFile.delete()
        val request = BuildRequest(
            requestId = "request-atomic-image",
            prompt = "",
            imageReference = BuildInput.ImageReference(
                contentUri = "content://picker/temporary-image",
                mediaType = "image/png",
                sizeBytes = 2_048,
            ),
            urlReference = null,
            createdAtEpochMillis = 1_700_000_000_000,
        )
        val source = BuildImageAnalysisSource(
            providerId = "google_gemini",
            modelId = "gemini-3.5-flash",
            analysis = BuildImageAnalysis(
                summary = "An open pavilion.",
                observedDetails = listOf("Four supports and a shallow roof are visible."),
                inferredDetails = listOf("The roof may be timber."),
                uncertainties = listOf("The rear is hidden."),
            ),
        )
        try {
            val validator = DefaultBuildPlanValidator()
            val repository = AtomicLocalBuildRepository(
                context,
                nowEpochMillis = { 1_700_000_000_100 },
                historyPolicy = com.craftmind.app.domain.buildplan.BuildHistoryPolicy(validator),
            )
            repository.load()
            val plan = validPlan().copy(
                metadata = validPlan().metadata.copy(
                    sourceRequestId = request.requestId,
                    providerId = source.providerId,
                    modelId = source.modelId,
                ),
            )
            val saved = repository.save(requireValid(validator, plan), request, source)

            assertEquals(request.imageReference?.contentUri, saved.request.imageContentUri)
            assertEquals(source, saved.request.imageAnalysisSource)
            val serialized = historyFile.readText()
            assertTrue(serialized.contains(source.providerId))
            assertTrue(serialized.contains(source.modelId))
            assertFalse(serialized.contains("data:image/"))

            val reopened = AtomicLocalBuildRepository(
                context,
                historyPolicy = com.craftmind.app.domain.buildplan.BuildHistoryPolicy(validator),
            )
            reopened.load()
            assertEquals(source, reopened.records.value.single().request.imageAnalysisSource)
            assertEquals(request.imageReference?.contentUri, reopened.records.value.single().request.imageContentUri)
        } finally {
            historyFile.delete()
        }
    }

    private fun requireValid(validator: DefaultBuildPlanValidator, plan: BuildPlan) = when (val result = validator.validate(plan)) {
        is BuildPlanValidationResult.Valid -> result.plan
        is BuildPlanValidationResult.Invalid -> error("Invalid test plan: ${result.issues}")
    }

    private fun validPlan() = BuildPlan(
        planId = "atomic-pavilion",
        metadata = BuildPlanMetadata(
            schemaVersion = 2,
            sourceRequestId = "request-atomic",
            providerId = "google",
            modelId = "gemini-test",
            title = "Stone pavilion",
            summary = "A small stone garden pavilion.",
            generatedAtEpochMillis = 1_700_000_000_000,
            dimensions = BuildDimensions(4, 4, 4),
            intent = BuildIntent("pavilion", style = "stone", floorCount = 1),
        ),
        originStrategy = BuildOriginStrategy.CENTERED_GROUND,
        components = listOf(
            BuildPlanComponent(
                componentId = "main",
                name = "Pavilion",
                purpose = "Covered gathering area",
                bounds = BlockBounds(BlockPosition(0, 0, 0), BuildDimensions(4, 4, 4)),
                type = BuildComponentType.BUILDING,
                parentComponentId = null,
                constructionOrder = 0,
            ),
        ),
        operations = listOf(
            BuildPlanOperation(0, BuildPlanOperationKind.PLACE_BLOCK, "minecraft:stone", BlockPosition(0, 0, 0), componentId = "main"),
        ),
        status = BuildStatus.READY,
    )
}
