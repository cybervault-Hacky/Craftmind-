package com.craftmind.app.presentation.home

import com.craftmind.app.domain.ai.AiBuildGenerator
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiGenerationResponse
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildOriginStrategy
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanMetadata
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun onlyValidatedAiResultMovesGenerationToReadyAndSavesLocalRecord() = runTest(mainDispatcher) {
        val generator = SequencedGenerator { request -> AiGenerationResponse(validatedPlan(request), usage = null) }
        val records = MemoryBuildRepository()
        val viewModel = HomeViewModel(generator, records, reducer())

        viewModel.dispatch(BuildComposerEvent.PromptChanged("A small stone pavilion"))
        viewModel.dispatch(BuildComposerEvent.Generate)
        runCurrent()

        assertTrue(viewModel.state.value.generation is BuildGenerationState.Generating)
        advanceUntilIdle()

        val ready = viewModel.state.value.generation as BuildGenerationState.Ready
        assertEquals(BuildStatus.READY, ready.plan.plan.status)
        assertEquals("A small stone pavilion", ready.request.prompt)
        assertEquals(1, records.saveCount)
        assertTrue(!ready.localSaveFailed)
    }

    @Test
    fun imageOnlyGenerationPersistsTextOnlyProvenanceWithTheValidatedPlan() = runTest(mainDispatcher) {
        val source = BuildImageAnalysisSource(
            providerId = "google_gemini",
            modelId = "gemini-test",
            analysis = BuildImageAnalysis(
                summary = "A compact pavilion.",
                observedDetails = listOf("Four supports are visible."),
                inferredDetails = listOf("The roof may be timber."),
                uncertainties = listOf("The rear is hidden."),
            ),
        )
        val generator = SequencedGenerator { request ->
            AiGenerationResponse(validatedPlan(request), usage = null, imageAnalysisSource = source)
        }
        val records = MemoryBuildRepository()
        val viewModel = HomeViewModel(generator, records, reducer())
        viewModel.dispatch(
            BuildComposerEvent.ImageSelected(
                BuildInput.ImageReference("content://picker/image", "image/png", 1_024L),
            ),
        )
        viewModel.dispatch(BuildComposerEvent.Generate)
        advanceUntilIdle()

        val ready = viewModel.state.value.generation as BuildGenerationState.Ready
        assertEquals("", ready.request.prompt)
        assertEquals(source, ready.imageAnalysisSource)
        assertEquals(source, records.records.value.single().request.imageAnalysisSource)
        assertEquals("google_gemini", records.records.value.single().plan.metadata.providerId)
        assertEquals("gemini-test", records.records.value.single().plan.metadata.modelId)
    }

    @Test
    fun retryOccursOnlyAfterRetryableFailureAndThenCanReachReady() = runTest(mainDispatcher) {
        var calls = 0
        val generator = SequencedGenerator { request ->
            calls++
            if (calls == 1) throw AiProviderException(AiFailure(AiErrorCode.RATE_LIMITED, retryable = true))
            AiGenerationResponse(validatedPlan(request), usage = null)
        }
        val viewModel = HomeViewModel(generator, MemoryBuildRepository(), reducer())
        viewModel.dispatch(BuildComposerEvent.PromptChanged("A small stone pavilion"))
        viewModel.dispatch(BuildComposerEvent.Generate)
        advanceUntilIdle()

        val failed = viewModel.state.value.generation as BuildGenerationState.Failed
        assertEquals(AiErrorCode.RATE_LIMITED, failed.code)
        assertTrue(failed.retryable)

        viewModel.dispatch(BuildComposerEvent.Retry)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(viewModel.state.value.generation is BuildGenerationState.Ready)
    }

    @Test
    fun cancellationStopsProviderWorkAndNeverCreatesAPlan() = runTest(mainDispatcher) {
        var cancelled = false
        val generator = SequencedGenerator {
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        val repository = MemoryBuildRepository()
        val viewModel = HomeViewModel(generator, repository, reducer())
        viewModel.dispatch(BuildComposerEvent.PromptChanged("A small stone pavilion"))
        viewModel.dispatch(BuildComposerEvent.Generate)
        runCurrent()
        viewModel.dispatch(BuildComposerEvent.CancelGeneration)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertTrue(viewModel.state.value.generation is BuildGenerationState.Cancelled)
        assertEquals(0, repository.saveCount)
    }

    @Test
    fun invalidRequestNeverCallsProvider() = runTest(mainDispatcher) {
        var calls = 0
        val generator = SequencedGenerator { calls++; throw AssertionError("provider must not run") }
        val viewModel = HomeViewModel(generator, MemoryBuildRepository(), reducer())

        viewModel.dispatch(BuildComposerEvent.Generate)

        assertEquals(0, calls)
        assertEquals(
            BuildGenerationState.ValidationBlocked(com.craftmind.app.domain.buildplan.BuildRequestValidationError.EMPTY_PROMPT),
            viewModel.state.value.generation,
        )
    }

    private fun reducer() = BuildComposerReducer(
        com.craftmind.app.domain.buildplan.BuildRequestValidator(
            nowEpochMillis = { 123L },
            requestId = { "request-state-test" },
        ),
    )

    private fun validatedPlan(request: BuildRequest): ValidatedBuildPlan {
        val plan = BuildPlan(
            planId = "small-pavilion",
            metadata = BuildPlanMetadata(
                schemaVersion = 1,
                sourceRequestId = request.requestId,
                providerId = "google_gemini",
                modelId = "gemini-test",
                title = "A small stone pavilion",
                summary = "An open garden pavilion with a stone floor.",
                generatedAtEpochMillis = 456L,
                dimensions = BuildDimensions(4, 3, 4),
            ),
            originStrategy = BuildOriginStrategy.CENTERED_GROUND,
            components = listOf(BuildPlanComponent("main", "Pavilion", "Covered sitting area")),
            operations = listOf(
                BuildPlanOperation(
                    sequence = 0,
                    kind = BuildPlanOperationKind.PLACE_BLOCK,
                    blockId = "minecraft:stone",
                    position = BlockPosition(0, 0, 0),
                    componentId = "main",
                ),
            ),
            status = BuildStatus.DRAFT,
        )
        return (DefaultBuildPlanValidator().validate(plan) as BuildPlanValidationResult.Valid).plan
    }

    private class SequencedGenerator(
        private val result: suspend (BuildRequest) -> AiGenerationResponse,
    ) : AiBuildGenerator {
        override suspend fun generate(request: BuildRequest): AiGenerationResponse = result(request)
    }

    private class MemoryBuildRepository : LocalBuildRepository {
        private val mutableRecords = MutableStateFlow<List<LocalBuildRecord>>(emptyList())
        override val records: StateFlow<List<LocalBuildRecord>> = mutableRecords
        var saveCount = 0

        override suspend fun load() = Unit
        override suspend fun save(
            plan: ValidatedBuildPlan,
            request: BuildRequest,
            imageAnalysisSource: com.craftmind.app.domain.buildplan.BuildImageAnalysisSource?,
        ): LocalBuildRecord {
            saveCount++
            val record = LocalBuildRecord(
                recordId = "${request.requestId}:${plan.plan.planId}",
                plan = plan.plan,
                request = com.craftmind.app.domain.buildplan.BuildRequestSnapshot(
                    prompt = request.prompt,
                    imageContentUri = request.imageReference?.contentUri,
                    imageMediaType = request.imageReference?.mediaType,
                    imageDisplayName = request.imageReference?.displayName,
                    imageSizeBytes = request.imageReference?.sizeBytes,
                    urlReference = request.urlReference?.url,
                    imageAnalysisSource = imageAnalysisSource,
                ),
                savedAtEpochMillis = 456L,
            )
            mutableRecords.value = listOf(record) + mutableRecords.value
            return record
        }

        override suspend fun appendRefinement(
            baseRecordId: String,
            plan: ValidatedBuildPlan,
            request: com.craftmind.app.domain.buildplan.BuildEditRequest,
            diff: com.craftmind.app.domain.buildplan.BuildDiff,
        ): LocalBuildRecord = error("Refinement is not used by these generation tests")

        override suspend fun revertTo(buildId: String, targetVersion: Int, expectedCurrentRecordId: String): LocalBuildRecord =
            error("Revert is not used by these generation tests")
    }
}
