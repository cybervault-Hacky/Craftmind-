package com.craftmind.app.presentation.builds

import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildDiff
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRepository
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildExecutionViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() { Dispatchers.setMain(mainDispatcher) }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test
    fun preflightRequiresFreshCompatibleCapabilitiesAndDoesNotStartPlacement() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val bridge = FakeBridge().apply { connectionState.value = connected(executionEnabled = false) }
        val executions = MemoryExecutions()
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()

        viewModel.dispatch(BuildExecutionEvent.Prepare(record))
        runCurrent()

        assertEquals(0, bridge.prepareCalls)
        assertEquals(0, bridge.startCalls)
        assertEquals("CONSTRUCTION_DISABLED", (viewModel.state.value.flow as BuildExecutionFlow.Failed).reasonCode)
    }

    @Test
    fun preparedPlanNeedsSeparateExplicitConfirmationBeforeStart() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val bridge = FakeBridge().apply { connectionState.value = connected() }
        val executions = MemoryExecutions()
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()

        viewModel.dispatch(BuildExecutionEvent.Prepare(record))
        runCurrent()

        val ready = viewModel.state.value.flow as BuildExecutionFlow.PreviewReady
        assertEquals("123e4567-e89b-42d3-a456-426614174000", ready.preview.executionId)
        assertEquals(1, bridge.prepareCalls)
        assertEquals(0, bridge.startCalls)
        assertEquals(MinecraftExecutionPhase.PREPARED, executions.records.value.single().phase)
        assertEquals(0, executions.records.value.single().completedOperations)

        viewModel.dispatch(BuildExecutionEvent.Confirm)
        runCurrent()

        assertEquals(1, bridge.startCalls)
        assertEquals(MinecraftExecutionPhase.QUEUED, (viewModel.state.value.flow as BuildExecutionFlow.Tracking).record.phase)
        assertEquals(0, (viewModel.state.value.flow as BuildExecutionFlow.Tracking).record.completedOperations)
    }

    @Test
    fun uncertainPreflightIsQueriedAndNeverAutomaticallyStartsAStoredPreparedExecution() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val bridge = FakeBridge().apply {
            connectionState.value = connected()
            prepareFailure = MinecraftBridgeFailure("BRIDGE_TIMEOUT")
            snapshot = snapshot.copy(
                phase = MinecraftExecutionPhase.PREPARED,
                completedOperations = 0,
                eventSequence = 1,
                updatedAtEpochMillis = snapshot.createdAtEpochMillis,
            )
        }
        val executions = MemoryExecutions()
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()

        viewModel.dispatch(BuildExecutionEvent.Prepare(record))
        runCurrent()

        assertEquals(1, bridge.prepareCalls)
        assertEquals(1, bridge.queryCalls)
        assertEquals(0, bridge.startCalls)
        val tracking = viewModel.state.value.flow as BuildExecutionFlow.Tracking
        assertEquals(MinecraftExecutionPhase.PREPARED, tracking.record.phase)
        assertEquals(0, tracking.record.completedOperations)
        assertEquals(MinecraftExecutionPhase.PREPARED, executions.records.value.single().phase)
    }

    @Test
    fun uncertainPreflightRetryReusesTheSameIdForIdempotency() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val firstId = "123e4567-e89b-42d3-a456-426614174000"
        val secondId = "223e4567-e89b-42d3-a456-426614174001"
        val bridge = FakeBridge().apply {
            connectionState.value = connected()
            prepareFailure = MinecraftBridgeFailure("BRIDGE_TIMEOUT")
            queryNotFound = true
        }
        val viewModel = createViewModel(
            bridge,
            MemoryBuilds(record),
            MemoryExecutions(),
            listOf(firstId, secondId).iterator(),
        )
        runCurrent()

        viewModel.dispatch(BuildExecutionEvent.Prepare(record))
        runCurrent()
        val failed = viewModel.state.value.flow as BuildExecutionFlow.Failed
        assertTrue(failed.retrySameId)
        assertEquals(firstId, failed.executionId)

        bridge.queryNotFound = false
        viewModel.dispatch(BuildExecutionEvent.RetryPrepare)
        runCurrent()

        assertEquals(listOf(firstId, firstId), bridge.prepareIds)
        assertEquals(firstId, (viewModel.state.value.flow as BuildExecutionFlow.PreviewReady).preview.executionId)
        assertEquals(0, bridge.startCalls)
    }

    @Test
    fun cancellationWaitsForAndPersistsBridgeConfirmedTerminalState() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val bridge = FakeBridge().apply { connectionState.value = connected() }
        val executions = MemoryExecutions()
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()
        viewModel.dispatch(BuildExecutionEvent.Prepare(record))
        runCurrent()
        viewModel.dispatch(BuildExecutionEvent.Confirm)
        runCurrent()

        bridge.snapshot = bridge.snapshot.copy(
            phase = MinecraftExecutionPhase.CANCELLED,
            completedOperations = 2,
            eventSequence = bridge.snapshot.eventSequence + 1,
            updatedAtEpochMillis = bridge.snapshot.updatedAtEpochMillis + 1,
        )
        viewModel.dispatch(BuildExecutionEvent.Cancel(bridge.snapshot.executionId))
        runCurrent()

        assertEquals(1, bridge.cancelCalls)
        val stored = executions.records.value.single()
        assertEquals(MinecraftExecutionPhase.CANCELLED, stored.phase)
        assertEquals(2, stored.completedOperations)
        assertEquals(bridge.snapshot.eventSequence, stored.eventSequence)
    }

    @Test
    fun reconnectQueriesInterruptedBridgeTruthAndNeverAutomaticallyStartsOrResumes() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val bridge = FakeBridge()
        val executions = MemoryExecutions(listOf(localRecord(record, phase = MinecraftExecutionPhase.RUNNING)))
        bridge.snapshot = bridge.snapshot.copy(
            phase = MinecraftExecutionPhase.FAILED,
            completedOperations = 1,
            eventSequence = bridge.snapshot.eventSequence + 2,
            updatedAtEpochMillis = bridge.snapshot.updatedAtEpochMillis + 10,
            reasonCode = "SERVER_RESTARTED",
            failedOperationIndex = null,
        )
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()
        assertEquals(0, bridge.queryCalls)

        bridge.connectionState.value = connected()
        runCurrent()

        assertEquals(1, bridge.queryCalls)
        assertEquals(0, bridge.startCalls)
        assertEquals(MinecraftExecutionPhase.FAILED, executions.records.value.single().phase)
        assertEquals(1, executions.records.value.single().completedOperations)
        assertEquals("SERVER_RESTARTED", executions.records.value.single().reasonCode)
    }

    @Test
    fun lostStartResponseIsResolvedByQueryRatherThanBlindRetry() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val bridge = FakeBridge().apply {
            connectionState.value = connected()
            startFailure = MinecraftBridgeFailure("BRIDGE_TIMEOUT")
            snapshot = snapshot.copy(
                phase = MinecraftExecutionPhase.RUNNING,
                completedOperations = 1,
                eventSequence = 2,
                updatedAtEpochMillis = snapshot.updatedAtEpochMillis + 1,
            )
        }
        val executions = MemoryExecutions()
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()
        viewModel.dispatch(BuildExecutionEvent.Prepare(record))
        runCurrent()
        viewModel.dispatch(BuildExecutionEvent.Confirm)
        runCurrent()

        assertEquals(1, bridge.startCalls)
        assertEquals(1, bridge.queryCalls)
        assertEquals(0, bridge.prepareCalls)
        assertEquals(MinecraftExecutionPhase.RUNNING, (viewModel.state.value.flow as BuildExecutionFlow.Tracking).record.phase)
        assertEquals(1, executions.records.value.single().completedOperations)
    }

    @Test
    fun notFoundKeepsLastKnownRecordAndDoesNotMarkItFailed() = runTest(mainDispatcher) {
        val record = BuildPlanTestFixtures.record()
        val stored = localRecord(record, phase = MinecraftExecutionPhase.RUNNING)
        val bridge = FakeBridge().apply { connectionState.value = connected(); queryNotFound = true }
        val executions = MemoryExecutions(listOf(stored))
        val viewModel = createViewModel(bridge, MemoryBuilds(record), executions)
        runCurrent()

        viewModel.dispatch(BuildExecutionEvent.RefreshStatus(stored.executionId))
        runCurrent()

        val flow = viewModel.state.value.flow as BuildExecutionFlow.Tracking
        assertTrue(flow.bridgeRecordMissing)
        assertEquals(MinecraftExecutionPhase.RUNNING, executions.records.value.single().phase)
        assertFalse(executions.records.value.single().phase == MinecraftExecutionPhase.FAILED)
        assertEquals(0, bridge.startCalls)
    }

    @Test
    fun stalePlanVersionCannotBePreflighted() = runTest(mainDispatcher) {
        val old = BuildPlanTestFixtures.record(version = 1)
        val latest = BuildPlanTestFixtures.record(
            plan = old.plan.copy(metadata = old.plan.metadata.copy(title = "new version")),
            version = 2,
            parentRecordId = old.recordId,
        )
        val bridge = FakeBridge().apply { connectionState.value = connected() }
        val viewModel = createViewModel(bridge, MemoryBuilds(old, latest), MemoryExecutions())
        runCurrent()

        viewModel.dispatch(BuildExecutionEvent.Prepare(old))
        runCurrent()

        assertEquals(0, bridge.prepareCalls)
        assertEquals("BUILD_VERSION_STALE", (viewModel.state.value.flow as BuildExecutionFlow.Failed).reasonCode)
    }

    private fun createViewModel(
        bridge: FakeBridge,
        builds: MemoryBuilds,
        executions: MemoryExecutions,
        executionIds: Iterator<String> = listOf("123e4567-e89b-42d3-a456-426614174000").iterator(),
    ): BuildExecutionViewModel {
        val store = ViewModelStore().also(stores::add)
        return androidx.lifecycle.ViewModelProvider(
            store,
            BuildExecutionViewModelFactory(bridge, builds, executions) {
                if (executionIds.hasNext()) executionIds.next() else "123e4567-e89b-42d3-a456-426614174000"
            },
        )[BuildExecutionViewModel::class.java]
    }

    private fun localRecord(
        record: LocalBuildRecord,
        phase: MinecraftExecutionPhase,
    ) = LocalBuildExecutionRecord(
        executionId = "123e4567-e89b-42d3-a456-426614174000",
        buildId = record.buildId,
        planRecordId = record.recordId,
        planVersion = record.version,
        bridgeId = BRIDGE.bridgeId,
        phase = phase,
        completedOperations = if (phase == MinecraftExecutionPhase.RUNNING) 0 else 0,
        totalOperations = record.plan.operations.size,
        eventSequence = 1,
        createdAtEpochMillis = 1_700_000_000_000,
        updatedAtEpochMillis = 1_700_000_000_000,
        dimensionId = "minecraft:overworld",
        worldSessionId = WORLD_SESSION,
        resolvedOrigin = BuildPlanTestFixtures.operation(0, "minecraft:stone", 0, 0, 0, "house").position,
    )

    private class MemoryBuilds(vararg records: LocalBuildRecord) : LocalBuildRepository {
        override val records = MutableStateFlow(records.toList())
        override suspend fun load() = Unit
        override suspend fun save(
            plan: ValidatedBuildPlan,
            request: BuildRequest,
            imageAnalysisSource: com.craftmind.app.domain.buildplan.BuildImageAnalysisSource?,
            referenceAnalysisSource: com.craftmind.app.domain.buildplan.BuildReferenceAnalysisSource?,
        ): LocalBuildRecord = error("unused")
        override suspend fun appendRefinement(baseRecordId: String, plan: ValidatedBuildPlan, request: BuildEditRequest, diff: BuildDiff): LocalBuildRecord = error("unused")
        override suspend fun revertTo(buildId: String, targetVersion: Int, expectedCurrentRecordId: String): LocalBuildRecord = error("unused")
    }

    private class MemoryExecutions(initial: List<LocalBuildExecutionRecord> = emptyList()) : LocalBuildExecutionRepository {
        override val records = MutableStateFlow(initial)
        override suspend fun load() = Unit
        override suspend fun save(record: LocalBuildExecutionRecord) {
            val old = records.value.firstOrNull { it.executionId == record.executionId }
            if (old != null && record.eventSequence < old.eventSequence) return
            records.value = (listOf(record) + records.value.filterNot { it.executionId == record.executionId })
                .sortedByDescending(LocalBuildExecutionRecord::updatedAtEpochMillis)
        }
    }

    private class FakeBridge : MinecraftBridgePairingRepository {
        override val connectionState = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Disconnected)
        override val profile: Flow<TrustedMinecraftBridge?> = flowOf(BRIDGE)
        var prepareCalls = 0
        val prepareIds = mutableListOf<String>()
        var startCalls = 0
        var queryCalls = 0
        var cancelCalls = 0
        var queryNotFound = false
        var prepareFailure: Exception? = null
        var startFailure: Exception? = null
        var snapshot = MinecraftExecutionSnapshot(
            executionId = "123e4567-e89b-42d3-a456-426614174000",
            buildId = "build-demo",
            planRecordId = "build-demo-v1",
            planVersion = 1,
            phase = MinecraftExecutionPhase.QUEUED,
            completedOperations = 0,
            totalOperations = 4,
            eventSequence = 2,
            createdAtEpochMillis = 1_700_000_000_000,
            updatedAtEpochMillis = 1_700_000_000_001,
            dimensionId = "minecraft:overworld",
            worldSessionId = WORLD_SESSION,
            resolvedOrigin = BuildPlanTestFixtures.operation(0, "minecraft:stone", 0, 0, 0, "house").position,
            reasonCode = null,
            failedOperationIndex = null,
        )

        override suspend fun pair(host: String, port: Int, tlsFingerprint: String, pairingCode: String) = error("unused")
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun refreshCapabilities() = Unit
        override suspend fun prepareExecution(record: LocalBuildRecord, executionId: String): MinecraftExecutionPreview {
            prepareCalls++
            prepareIds += executionId
            prepareFailure?.let { failure -> prepareFailure = null; throw failure }
            return preview(record, executionId)
        }
        override suspend fun startExecution(preview: MinecraftExecutionPreview): MinecraftExecutionSnapshot {
            startCalls++
            startFailure?.let { throw it }
            return snapshot.copy(executionId = preview.executionId, buildId = "build-demo", planRecordId = preview.planRecordId,
                planVersion = preview.planVersion, totalOperations = preview.operationCount,
                resolvedOrigin = preview.resolvedOrigin, dimensionId = preview.dimensionId, worldSessionId = preview.worldSessionId)
        }
        override suspend fun queryExecution(executionId: String): MinecraftExecutionQueryResult {
            queryCalls++
            if (queryNotFound) return MinecraftExecutionQueryResult.NotFound
            return MinecraftExecutionQueryResult.Found(snapshot.copy(executionId = executionId))
        }
        override suspend fun cancelExecution(executionId: String): MinecraftCancellationResult {
            cancelCalls++
            return MinecraftCancellationResult("CANCELLATION_ACCEPTED", snapshot.phase, null)
        }
        override suspend fun revoke() = Unit
        override suspend fun forgetLocally() = Unit
    }

    private fun preview(record: LocalBuildRecord, executionId: String) = MinecraftExecutionPreview(
        executionId = executionId,
        preflightToken = "A".repeat(43),
        planRecordId = record.recordId,
        planVersion = record.version,
        planTitle = record.plan.metadata.title,
        dimensionId = "minecraft:overworld",
        worldSessionId = WORLD_SESSION,
        resolvedOrigin = BuildPlanTestFixtures.operation(0, "minecraft:stone", 0, 0, 0, "house").position,
        originStrategy = "SERVER_SELECTED_ORIGIN",
        operationCount = record.plan.operations.size,
        createdAtEpochMillis = 1_700_000_000_000,
        eventSequence = 1,
        expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
    )

    private companion object {
        const val WORLD_SESSION = "world-session-test"
        val BRIDGE = TrustedMinecraftBridge(
            host = "192.168.1.20",
            port = 19872,
            tlsFingerprint = "00".repeat(32),
            bridgeId = "bridge-0123456789abcdef0123456789abcdef",
            clientId = "client-test",
            displayName = "Test server",
            pairedAtEpochMillis = 1_700_000_000_000,
        )
        fun connected(executionEnabled: Boolean = true) = BridgeConnectionState.Connected(
            bridge = BRIDGE,
            capabilities = BridgeCapabilitiesSnapshot(
                protocolVersion = 1,
                bridgeId = BRIDGE.bridgeId,
                identityFingerprint = BRIDGE.tlsFingerprint,
                bridgeVersion = "1.1.0",
                minecraftVersion = "1.20.1",
                loaderName = "Fabric",
                loaderVersion = "0.16.10",
                worldAccess = true,
                constructionExecute = executionEnabled,
                cancellation = true,
                maximumValidatedOperations = 4096,
                maximumRequestBytes = 1_048_576,
                supportedBuildPlanSchemaVersions = listOf(2),
                dimensionId = "minecraft:overworld",
                worldSessionId = WORLD_SESSION,
            ),
            authenticatedAtEpochMillis = 1_700_000_000_000,
        )
    }
}
