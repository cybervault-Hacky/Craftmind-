package com.craftmind.app.presentation.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRepository
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Coordinates explicit review/confirmation while treating the authenticated bridge as execution truth. */
class BuildExecutionViewModel(
    private val bridge: MinecraftBridgePairingRepository,
    private val builds: LocalBuildRepository,
    private val executions: LocalBuildExecutionRepository,
    private val executionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val pollIntervalMillis: Long = 2_000L,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BuildExecutionState())
    val state: StateFlow<BuildExecutionState> = mutableState.asStateFlow()
    private var pollingJob: Job? = null
    private var lastAuthenticatedMarker: String? = null

    init {
        viewModelScope.launch {
            try {
                executions.load()
                builds.load()
                mutableState.update { it.copy(records = executions.records.value, isLoading = false, loadFailed = false) }
                (bridge.connectionState.value as? BridgeConnectionState.Connected)?.let { reconcilePending(it.bridge.bridgeId) }
                executions.records.collect { records ->
                    mutableState.update { it.copy(records = records, isLoading = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
        viewModelScope.launch {
            bridge.connectionState.collect { connection ->
                val connected = connection as? BridgeConnectionState.Connected
                if (connected != null) {
                    val marker = "${connected.bridge.bridgeId}:${connected.authenticatedAtEpochMillis}"
                    if (marker != lastAuthenticatedMarker) {
                        lastAuthenticatedMarker = marker
                        reconcilePending(connected.bridge.bridgeId)
                    }
                } else {
                    val tracking = mutableState.value.flow as? BuildExecutionFlow.Tracking ?: return@collect
                    if (!isTerminal(tracking.record.phase)) {
                        mutableState.update { current ->
                            val flow = current.flow as? BuildExecutionFlow.Tracking
                            if (flow?.record?.executionId != tracking.record.executionId) current
                            else current.copy(flow = flow.copy(connectionReasonCode = "BRIDGE_DISCONNECTED"))
                        }
                    }
                }
            }
        }
    }

    fun dispatch(event: BuildExecutionEvent) {
        when (event) {
            is BuildExecutionEvent.Prepare -> prepare(event.record, executionIdFactory())
            BuildExecutionEvent.RetryPrepare -> retryPrepare()
            BuildExecutionEvent.Confirm -> confirm()
            BuildExecutionEvent.DismissPreview -> dismissPreview()
            is BuildExecutionEvent.RefreshStatus -> refreshStatus(event.executionId)
            is BuildExecutionEvent.Cancel -> cancel(event.executionId)
            BuildExecutionEvent.Dismiss -> dismiss()
        }
    }

    private fun prepare(record: LocalBuildRecord, executionId: String) {
        if (mutableState.value.flow is BuildExecutionFlow.Preparing ||
            mutableState.value.flow is BuildExecutionFlow.Starting) return
        if (mutableState.value.isLoading || mutableState.value.loadFailed) {
            mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(record, executionId, "EXECUTION_STORAGE_FAILURE")) }
            return
        }
        viewModelScope.launch {
            try {
                builds.load()
                val current = builds.records.value.filter { it.buildId == record.buildId }.maxByOrNull { it.version }
                if (current?.recordId != record.recordId) {
                    mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(record, executionId, "BUILD_VERSION_STALE")) }
                    return@launch
                }
                val connected = bridge.connectionState.value as? BridgeConnectionState.Connected
                if (connected?.capabilities?.executionCompatible != true) {
                    mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(record, executionId, "CONSTRUCTION_DISABLED")) }
                    return@launch
                }
                if (record.plan.status != BuildStatus.READY ||
                    record.plan.metadata.schemaVersion != 2 ||
                    record.plan.operations.isEmpty() ||
                    record.plan.operations.size > connected.capabilities.maximumValidatedOperations) {
                    mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(record, executionId, "BUILD_PLAN_NOT_EXECUTABLE")) }
                    return@launch
                }
                mutableState.update { it.copy(flow = BuildExecutionFlow.Preparing(record.recordId, executionId)) }
                requestPreflight(record, executionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(record, executionId, reason(error), retryPrepare = true)) }
            }
        }
    }

    private suspend fun requestPreflight(record: LocalBuildRecord, executionId: String) {
        try {
            val preview = bridge.prepareExecution(record, executionId)
            val connected = bridge.connectionState.value as? BridgeConnectionState.Connected
                ?: throw MinecraftBridgeFailure("BRIDGE_SESSION_UNAVAILABLE")
            val preparedRecord = preview.toLocalRecord(record, connected.bridge.bridgeId)
            var storageError: String? = null
            try {
                executions.save(preparedRecord)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                storageError = "EXECUTION_STORAGE_FAILURE"
            }
            mutableState.update { current ->
                current.copy(flow = BuildExecutionFlow.PreviewReady(record.recordId, preview, storageError))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val reasonCode = reason(error)
            val uncertain = reasonCode in UNCERTAIN_TRANSPORT_FAILURES
            if (uncertain) {
                val query = try {
                    bridge.queryExecution(executionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (query is MinecraftExecutionQueryResult.Found) {
                    val bridgeId = (bridge.connectionState.value as? BridgeConnectionState.Connected)?.bridge?.bridgeId
                        ?: bridge.profile.first()?.bridgeId.orEmpty()
                    persistAndTrack(query.snapshot.toLocalRecord(bridgeId))
                    return
                }
            }
            val canRetry = reasonCode !in NON_RETRYABLE_PREFLIGHT_FAILURES
            mutableState.update {
                it.copy(flow = BuildExecutionFlow.Failed(record, executionId, reasonCode,
                    retryPrepare = canRetry, retrySameId = uncertain))
            }
        }
    }

    private fun retryPrepare() {
        val failed = mutableState.value.flow as? BuildExecutionFlow.Failed ?: return
        val record = failed.planRecord ?: return
        if (!failed.retryPrepare) return
        // An uncertain transport result is retried with the same id for idempotency; known rejections get a new id.
        prepare(record, if (failed.retrySameId) failed.executionId ?: executionIdFactory() else executionIdFactory())
    }

    private fun confirm() {
        val previewFlow = mutableState.value.flow as? BuildExecutionFlow.PreviewReady ?: return
        val preview = previewFlow.preview
        if (mutableState.value.isLoading || mutableState.value.loadFailed || previewFlow.errorCode != null) {
            mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(null, preview.executionId, "EXECUTION_STORAGE_FAILURE")) }
            return
        }
        val connected = bridge.connectionState.value as? BridgeConnectionState.Connected
        val preparedBridgeId = mutableState.value.records.firstOrNull { it.executionId == preview.executionId }?.bridgeId
        if (connected?.capabilities?.executionCompatible != true ||
            (preparedBridgeId != null && connected.bridge.bridgeId != preparedBridgeId)) {
            mutableState.update { it.copy(flow = BuildExecutionFlow.Failed(null, preview.executionId, "CONSTRUCTION_DISABLED")) }
            return
        }
        val confirmedBridgeId = connected.bridge.bridgeId
        if (preview.expiresAtEpochMillis <= System.currentTimeMillis()) {
            mutableState.update {
                it.copy(flow = BuildExecutionFlow.Failed(null, preview.executionId, "PREFLIGHT_EXPIRED", retryPrepare = true))
            }
            return
        }
        val planRecord = builds.records.value.firstOrNull { it.recordId == preview.planRecordId }
        val current = planRecord?.let { saved ->
            builds.records.value.filter { it.buildId == saved.buildId }.maxByOrNull { it.version }
        }
        if (planRecord == null || current?.recordId != preview.planRecordId) {
            mutableState.update {
                it.copy(flow = BuildExecutionFlow.Failed(planRecord, preview.executionId, "BUILD_VERSION_STALE"))
            }
            return
        }
        mutableState.update { it.copy(flow = BuildExecutionFlow.Starting(preview.planRecordId, preview)) }
        viewModelScope.launch {
            try {
                val snapshot = bridge.startExecution(preview)
                val bridgeId = (bridge.connectionState.value as? BridgeConnectionState.Connected)?.bridge?.bridgeId
                    ?: mutableState.value.records.firstOrNull { it.executionId == preview.executionId }?.bridgeId
                    ?: confirmedBridgeId
                val local = snapshot.toLocalRecord(bridgeId)
                persistAndTrack(local)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val snapshot = try {
                    bridge.queryExecution(preview.executionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (snapshot is MinecraftExecutionQueryResult.Found) {
                    val bridgeId = (bridge.connectionState.value as? BridgeConnectionState.Connected)?.bridge?.bridgeId
                        ?: mutableState.value.records.firstOrNull { it.executionId == preview.executionId }?.bridgeId
                        ?: confirmedBridgeId
                    persistAndTrack(snapshot.snapshot.toLocalRecord(bridgeId))
                } else {
                    val existing = mutableState.value.records.firstOrNull { it.executionId == preview.executionId }
                        ?: preview.toLocalRecord(planRecord, confirmedBridgeId)
                    try {
                        executions.save(existing)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep the authenticated bridge's last-known execution ID in memory if disk is unavailable.
                    }
                    track(existing, connectionReasonCode = reason(error))
                }
            }
        }
    }

    private fun dismissPreview() {
        val preview = (mutableState.value.flow as? BuildExecutionFlow.PreviewReady)?.preview
        if (preview != null) {
            viewModelScope.launch {
                val connected = bridge.connectionState.value as? BridgeConnectionState.Connected
                if (connected?.capabilities?.executionCompatible == true) {
                    try {
                        bridge.cancelExecution(preview.executionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The preview remains a server-side prepared record until cancellation or expiry is confirmed.
                    }
                }
                refreshStatusNow(preview.executionId)
                mutableState.update { current ->
                    if ((current.flow as? BuildExecutionFlow.PreviewReady)?.preview?.executionId == preview.executionId) {
                        current.copy(flow = BuildExecutionFlow.Idle)
                    } else current
                }
            }
        } else {
            dismiss()
        }
    }

    private fun refreshStatus(executionId: String) {
        val record = mutableState.value.records.firstOrNull { it.executionId == executionId } ?: return
        track(record, refreshing = true)
        viewModelScope.launch { refreshStatusNow(executionId) }
    }

    private suspend fun refreshStatusNow(executionId: String, cancellationRequested: Boolean = false) {
        val existing = mutableState.value.records.firstOrNull { it.executionId == executionId } ?: return
        try {
            when (val result = bridge.queryExecution(executionId)) {
                is MinecraftExecutionQueryResult.Found -> persistAndTrack(
                    result.snapshot.toLocalRecord(existing.bridgeId), cancellationRequested,
                )
                MinecraftExecutionQueryResult.NotFound -> track(
                    existing, bridgeRecordMissing = true, cancellationRequested = cancellationRequested,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            track(existing, connectionReasonCode = reason(error), cancellationRequested = cancellationRequested)
        }
    }

    private fun cancel(executionId: String) {
        val existing = mutableState.value.records.firstOrNull { it.executionId == executionId } ?: return
        val connected = bridge.connectionState.value as? BridgeConnectionState.Connected
        if (connected?.capabilities?.executionCompatible != true) {
            track(existing, connectionReasonCode = "CONSTRUCTION_DISABLED")
            return
        }
        viewModelScope.launch {
            try {
                val result = bridge.cancelExecution(executionId)
                when (result.outcome) {
                    "CANCELLATION_ACCEPTED" -> {
                        track(existing, cancellationRequested = true)
                        refreshStatusNow(executionId, cancellationRequested = true)
                    }
                    "EXECUTION_ALREADY_FINISHED", "EXECUTION_NOT_FOUND" -> refreshStatusNow(executionId)
                    else -> track(existing, connectionReasonCode = result.reasonCode ?: result.outcome)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                track(existing, connectionReasonCode = reason(error), cancellationRequested = true)
            }
        }
    }

    private suspend fun reconcilePending(bridgeId: String) {
        val pending = executions.records.value.filter {
            it.bridgeId == bridgeId && !isTerminal(it.phase)
        }
        pending.forEach { record ->
            try {
                when (val result = bridge.queryExecution(record.executionId)) {
                    is MinecraftExecutionQueryResult.Found -> persistAndTrack(result.snapshot.toLocalRecord(record.bridgeId))
                    MinecraftExecutionQueryResult.NotFound -> track(record, bridgeRecordMissing = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                track(record, connectionReasonCode = reason(error))
            }
        }
        val current = mutableState.value.flow as? BuildExecutionFlow.Tracking
        if (current != null && !isTerminal(current.record.phase)) startPolling(current.record.executionId)
    }

    private suspend fun persistAndTrack(record: LocalBuildExecutionRecord, cancellationRequested: Boolean = false) {
        var stored = record
        var storageError: String? = null
        try {
            executions.save(record)
            stored = executions.records.value.firstOrNull { it.executionId == record.executionId } ?: record
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storageError = "EXECUTION_STORAGE_FAILURE"
        }
        track(stored, connectionReasonCode = storageError, cancellationRequested = cancellationRequested && !isTerminal(stored.phase))
        if (!isTerminal(stored.phase)) startPolling(stored.executionId) else pollingJob?.cancel()
    }

    private fun track(
        record: LocalBuildExecutionRecord,
        refreshing: Boolean = false,
        connectionReasonCode: String? = null,
        bridgeRecordMissing: Boolean = false,
        cancellationRequested: Boolean = false,
    ) {
        mutableState.update { current ->
            current.copy(flow = BuildExecutionFlow.Tracking(record, refreshing, connectionReasonCode, bridgeRecordMissing, cancellationRequested))
        }
        if (!isTerminal(record.phase) && connectionReasonCode == null && !bridgeRecordMissing) startPolling(record.executionId)
    }

    private fun startPolling(executionId: String) {
        if (pollingJob?.isActive == true && (mutableState.value.flow as? BuildExecutionFlow.Tracking)?.record?.executionId == executionId) return
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(pollIntervalMillis)
                val tracking = mutableState.value.flow as? BuildExecutionFlow.Tracking ?: return@launch
                if (tracking.record.executionId != executionId || isTerminal(tracking.record.phase) || tracking.bridgeRecordMissing) return@launch
                if (bridge.connectionState.value !is BridgeConnectionState.Connected) {
                    track(
                        tracking.record,
                        connectionReasonCode = "BRIDGE_DISCONNECTED",
                        cancellationRequested = tracking.cancellationRequested,
                    )
                    continue
                }
                refreshStatusNow(executionId, cancellationRequested = tracking.cancellationRequested)
            }
        }
    }

    private fun dismiss() {
        pollingJob?.cancel()
        pollingJob = null
        mutableState.update { it.copy(flow = BuildExecutionFlow.Idle) }
    }

    private fun MinecraftExecutionPreview.toLocalRecord(record: LocalBuildRecord, bridgeId: String) =
        LocalBuildExecutionRecord(
            executionId = executionId,
            buildId = record.buildId,
            planRecordId = record.recordId,
            planVersion = record.version,
            bridgeId = bridgeId,
            phase = MinecraftExecutionPhase.PREPARED,
            completedOperations = 0,
            totalOperations = operationCount,
            eventSequence = eventSequence,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            dimensionId = dimensionId,
            worldSessionId = worldSessionId,
            resolvedOrigin = resolvedOrigin,
        )

    private fun MinecraftExecutionSnapshot.toLocalRecord(bridgeId: String) = LocalBuildExecutionRecord(
        executionId = executionId,
        buildId = buildId,
        planRecordId = planRecordId,
        planVersion = planVersion,
        bridgeId = bridgeId,
        phase = phase,
        completedOperations = completedOperations,
        totalOperations = totalOperations,
        eventSequence = eventSequence,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        dimensionId = dimensionId,
        worldSessionId = worldSessionId,
        resolvedOrigin = resolvedOrigin,
        reasonCode = reasonCode,
        failedOperationIndex = failedOperationIndex,
    )

    private fun isTerminal(phase: MinecraftExecutionPhase): Boolean = phase in setOf(
        MinecraftExecutionPhase.COMPLETED, MinecraftExecutionPhase.FAILED, MinecraftExecutionPhase.CANCELLED,
    )

    private fun reason(error: Exception): String =
        (error as? MinecraftBridgeFailure)?.reasonCode ?: "EXECUTION_OPERATION_FAILED"

    private companion object {
        val UNCERTAIN_TRANSPORT_FAILURES = setOf(
            "BRIDGE_TIMEOUT", "BRIDGE_UNAVAILABLE", "BRIDGE_EMPTY_RESPONSE", "BRIDGE_RESPONSE_MISMATCH",
            "BRIDGE_RESPONSE_INVALID", "BRIDGE_RESPONSE_TIMESTAMP_INVALID", "AUTH_SESSION_EXPIRED",
            "BRIDGE_SESSION_UNAVAILABLE", "BRIDGE_HTTP_500", "BRIDGE_HTTP_502", "BRIDGE_HTTP_503", "BRIDGE_HTTP_504",
        )
        val NON_RETRYABLE_PREFLIGHT_FAILURES = setOf(
            "BUILD_PLAN_NOT_EXECUTABLE", "BUILD_VERSION_STALE", "EXECUTION_ID_INVALID",
        )
    }
}
