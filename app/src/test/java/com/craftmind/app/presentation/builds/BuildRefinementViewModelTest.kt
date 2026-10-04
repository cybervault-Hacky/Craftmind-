package com.craftmind.app.presentation.builds

import com.craftmind.app.domain.ai.AiBuildRefiner
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiRefinementResponse
import com.craftmind.app.domain.buildplan.BuildDiff
import com.craftmind.app.domain.buildplan.BuildDiffCalculator
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildHistoryPolicy
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.BuildRepositoryError
import com.craftmind.app.domain.buildplan.BuildRepositoryException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildRefinementViewModelTest {
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
    fun candidateIsIsolatedUntilExplicitAcceptanceThenAppendsAnImmutableVersion() = runTest(mainDispatcher) {
        val base = BuildPlanTestFixtures.semanticPlan()
        val original = BuildPlanTestFixtures.record(base)
        val refiner = FakeRefiner { response(base) }
        val history = MemoryHistory(original)
        val viewModel = BuildRefinementViewModel(refiner, history)

        viewModel.dispatch(BuildRefinementEvent.Refine(original, "Change the roof to brick"))
        advanceUntilIdle()

        val ready = viewModel.state.value as BuildRefinementState.ReadyForReview
        assertEquals("minecraft:stone", ready.base.plan.operations.first().blockId)
        assertEquals(0, history.appendCalls)
        assertEquals(listOf(original), history.records.value)

        viewModel.dispatch(BuildRefinementEvent.Accept)
        advanceUntilIdle()

        val accepted = viewModel.state.value as BuildRefinementState.Accepted
        assertEquals(2, accepted.record.version)
        assertEquals(2, history.records.value.size)
        assertEquals(original, history.records.value.last())
        assertEquals("minecraft:stone", history.records.value.last().plan.operations.first().blockId)
        assertEquals(1, history.appendCalls)
    }

    @Test
    fun providerFailurePreservesPriorVersionAndRetryRunsOnlyAfterExplicitEvent() = runTest(mainDispatcher) {
        val base = BuildPlanTestFixtures.semanticPlan()
        val original = BuildPlanTestFixtures.record(base)
        var calls = 0
        val refiner = FakeRefiner {
            calls++
            if (calls == 1) throw AiProviderException(AiFailure(AiErrorCode.RATE_LIMITED, retryable = true))
            response(base)
        }
        val history = MemoryHistory(original)
        val viewModel = BuildRefinementViewModel(refiner, history)

        viewModel.dispatch(BuildRefinementEvent.Refine(original, "Change the roof to brick"))
        advanceUntilIdle()
        val failed = viewModel.state.value as BuildRefinementState.Failed
        assertEquals(AiErrorCode.RATE_LIMITED, failed.code)
        assertEquals(1, calls)
        assertEquals(listOf(original), history.records.value)
        assertEquals(0, history.appendCalls)

        viewModel.dispatch(BuildRefinementEvent.Retry)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(viewModel.state.value is BuildRefinementState.ReadyForReview)
        assertEquals(listOf(original), history.records.value)
    }

    @Test
    fun cancellingProviderWorkLeavesPreviousHistoryUntouched() = runTest(mainDispatcher) {
        val base = BuildPlanTestFixtures.semanticPlan()
        val original = BuildPlanTestFixtures.record(base)
        var cancelled = false
        val refiner = FakeRefiner {
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        val history = MemoryHistory(original)
        val viewModel = BuildRefinementViewModel(refiner, history)

        viewModel.dispatch(BuildRefinementEvent.Refine(original, "Change the roof"))
        runCurrent()
        assertTrue(viewModel.state.value is BuildRefinementState.Generating)
        viewModel.dispatch(BuildRefinementEvent.Cancel)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertTrue(viewModel.state.value is BuildRefinementState.Cancelled)
        assertEquals(listOf(original), history.records.value)
        assertEquals(0, history.appendCalls)
    }

    @Test
    fun failedAtomicAcceptanceDoesNotReplacePreviousRecord() = runTest(mainDispatcher) {
        val base = BuildPlanTestFixtures.semanticPlan()
        val original = BuildPlanTestFixtures.record(base)
        val history = MemoryHistory(original).apply { appendFailure = BuildRepositoryException(BuildRepositoryError.STORAGE_FAILURE) }
        val viewModel = BuildRefinementViewModel(FakeRefiner { response(base) }, history)
        viewModel.dispatch(BuildRefinementEvent.Refine(original, "Change the roof to brick"))
        advanceUntilIdle()
        viewModel.dispatch(BuildRefinementEvent.Accept)
        advanceUntilIdle()

        val failed = viewModel.state.value as BuildRefinementState.Failed
        assertEquals(RefinementFailureStage.PERSISTENCE, failed.stage)
        assertEquals(AiErrorCode.BUILD_HISTORY_FAILURE, failed.code)
        assertEquals(listOf(original), history.records.value)
    }

    @Test
    fun restoringPreviousVersionUsesHistoryOnlyAndLeavesAiRefinerUnused() = runTest(mainDispatcher) {
        val base = BuildPlanTestFixtures.semanticPlan()
        val original = BuildPlanTestFixtures.record(base)
        val refined = response(base)
        val current = LocalBuildRecord(
            recordId = "${original.buildId}-v2",
            plan = refined.plan.plan,
            request = original.request,
            savedAtEpochMillis = original.savedAtEpochMillis + 1,
            buildId = original.buildId,
            version = 2,
            parentRecordId = original.recordId,
            changeSummary = refined.diff.summary,
            diff = refined.diff,
        )
        val history = MemoryHistory(current, listOf(current, original))
        var calls = 0
        val refiner = FakeRefiner { calls++; response(base) }
        val viewModel = BuildRefinementViewModel(refiner, history)

        viewModel.dispatch(BuildRefinementEvent.RestoreVersion(current, 1))
        advanceUntilIdle()

        assertTrue(viewModel.state.value is BuildRefinementState.Reverted)
        assertEquals(0, calls)
        assertEquals(0, history.appendCalls)
        assertEquals(1, history.revertCalls)
        assertEquals(3, history.records.value.first().version)
        assertEquals(1, history.records.value.first().restoredFromVersion)
        assertEquals(3, history.records.value.size)
    }

    @Test
    fun invalidEmptyInstructionDoesNotCallProvider() = runTest(mainDispatcher) {
        val original = BuildPlanTestFixtures.record()
        var calls = 0
        val viewModel = BuildRefinementViewModel(FakeRefiner { calls++; response(original.plan) }, MemoryHistory(original))

        viewModel.dispatch(BuildRefinementEvent.Refine(original, "   "))

        val validation = viewModel.state.value as BuildRefinementState.ValidationFailed
        assertEquals(com.craftmind.app.domain.buildplan.BuildEditValidationError.EMPTY_INSTRUCTION, validation.error)
        assertEquals(0, calls)
    }

    private fun response(base: com.craftmind.app.domain.buildplan.BuildPlan): AiRefinementResponse {
        val candidate = base.copy(
            metadata = base.metadata.copy(providerId = "google", modelId = "gemini-test", generatedAtEpochMillis = 1_700_000_000_500),
            components = base.components.map { component ->
                if (component.componentId == "roof") component.copy(purpose = "A brick roof above the home.") else component
            },
            operations = base.operations.mapIndexed { index, operation ->
                if (index == 2) operation.copy(blockId = "minecraft:bricks") else operation
            },
        )
        val diff = BuildDiffCalculator().compare(
            base,
            candidate,
            summary = "Changed the roof to brick",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            instruction = "Change the roof to brick",
        )
        return AiRefinementResponse(BuildPlanTestFixtures.validated(candidate), diff, usage = null)
    }

    private class FakeRefiner(private val result: suspend (BuildEditRequest) -> AiRefinementResponse) : AiBuildRefiner {
        override suspend fun refine(request: BuildEditRequest): AiRefinementResponse = result(request)
    }

    private class MemoryHistory(
        initial: LocalBuildRecord,
        initialRecords: List<LocalBuildRecord> = listOf(initial),
    ) : LocalBuildRepository {
        private val mutableRecords = MutableStateFlow(initialRecords)
        override val records: StateFlow<List<LocalBuildRecord>> = mutableRecords
        var appendCalls = 0
        var revertCalls = 0
        var appendFailure: BuildRepositoryException? = null

        override suspend fun load() = Unit
        override suspend fun save(
            plan: ValidatedBuildPlan,
            request: com.craftmind.app.domain.buildplan.BuildRequest,
        ): LocalBuildRecord = error("Not used")

        override suspend fun appendRefinement(
            baseRecordId: String,
            plan: ValidatedBuildPlan,
            request: BuildEditRequest,
            diff: BuildDiff,
        ): LocalBuildRecord {
            appendCalls++
            appendFailure?.let { throw it }
            val base = mutableRecords.value.first { it.recordId == baseRecordId }
            val appended = LocalBuildRecord(
                recordId = "${base.buildId}-v${base.version + 1}",
                plan = plan.plan,
                request = base.request,
                savedAtEpochMillis = base.savedAtEpochMillis + 1,
                buildId = base.buildId,
                version = base.version + 1,
                parentRecordId = base.recordId,
                refinementInstruction = request.instruction,
                changeSummary = diff.summary,
                diff = diff,
            )
            mutableRecords.value = listOf(appended) + mutableRecords.value
            return appended
        }

        override suspend fun revertTo(buildId: String, targetVersion: Int, expectedCurrentRecordId: String): LocalBuildRecord {
            revertCalls++
            val current = mutableRecords.value.first { it.recordId == expectedCurrentRecordId }
            val target = mutableRecords.value.first { it.buildId == buildId && it.version == targetVersion }
            val reverted = target.copy(
                recordId = "$buildId-v${current.version + 1}",
                savedAtEpochMillis = current.savedAtEpochMillis + 1,
                version = current.version + 1,
                parentRecordId = current.recordId,
                restoredFromVersion = targetVersion,
            )
            mutableRecords.value = listOf(reverted) + mutableRecords.value
            return reverted
        }
    }
}
