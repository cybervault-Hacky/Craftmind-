package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import com.craftmind.bridge.protocol.BridgeProtocol;
import com.craftmind.bridge.protocol.BridgeExecutionRequest;
import com.craftmind.bridge.protocol.BuildPlanDocument;
import com.craftmind.bridge.protocol.ExecutionProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.LongSupplier;

/** One-active-build server-thread coordinator with idempotency and authoritative persisted status. */
final class ConstructionCoordinator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final BridgeExecutionRecordStore store;
    private final BridgeExecutionLimits limits;
    private final LongSupplier clock;
    private final Map<String, BridgeExecutionRecord> volatileOverrides = new HashMap<>();
    private PreparedExecution prepared;
    private ActiveExecution active;

    ConstructionCoordinator(BridgeExecutionRecordStore store, BridgeExecutionLimits limits) {
        this(store, limits, System::currentTimeMillis);
    }

    ConstructionCoordinator(BridgeExecutionRecordStore store, BridgeExecutionLimits limits, LongSupplier clock) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
    }

    synchronized PrepareResult prepare(String clientId, BridgeExecutionRequest request, String payloadHash,
                                       List<BuildPlanTransformer.TransformedOperation> operations,
                                       BridgeBuildOrigin origin, ExecutionWorldAccess worldAccess) {
        long now = Math.max(1L, clock.getAsLong());
        if (request != null && !BridgeExecutionRecordStore.isValidExecutionId(request.executionId)) {
            return PrepareResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_ID_INVALID.name());
        }
        if (request == null || operations == null || origin == null || worldAccess == null ||
                request.buildPlan == null || request.buildPlan.metadata == null ||
                request.buildPlan.metadata.title == null || request.planRecordId == null || request.planVersion < 1 ||
                operations.isEmpty() || operations.size() > limits.maxOperations()) {
            return PrepareResult.rejected(BridgeProtocol.ErrorCode.INVALID_BUILD_REQUEST.name());
        }
        BridgeExecutionRecord existing = findRecord(request.executionId);
        if (existing != null) {
            if (!existing.ownerClientId.equals(clientId) || !existing.payloadHash.equals(payloadHash)) {
                return PrepareResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_ID_CONFLICT.name());
            }
            if ("PREPARED".equals(existing.state) && prepared != null &&
                    prepared.record.executionId.equals(existing.executionId) && now <= prepared.expiresAtEpochMillis) {
                return PrepareResult.ready(prepared.toProtocol(request.buildPlan.metadata.title));
            }
            if ("PREPARED".equals(existing.state)) {
                failRecord(existing, BridgeProtocol.ErrorCode.PREFLIGHT_EXPIRED.name(), null);
                return PrepareResult.rejected(BridgeProtocol.ErrorCode.PREFLIGHT_EXPIRED.name());
            }
            return PrepareResult.existing(snapshot(existing));
        }
        expirePrepared(now);
        if (active != null || prepared != null) return PrepareResult.rejected(BridgeProtocol.ErrorCode.ACTIVE_EXECUTION_EXISTS.name());

        String preflightError = worldAccess.preflight(operations);
        if (preflightError != null) {
            BridgeExecutionRecord rejected = newRecord(clientId, request, payloadHash, operations.size(), origin, now);
            rejected.state = "FAILED";
            rejected.reasonCode = safeReason(preflightError);
            rejected.eventSequence = 1;
            rejected.updatedAtEpochMillis = now;
            if (!save(rejected)) return PrepareResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name());
            return PrepareResult.rejected(rejected.reasonCode);
        }

        String token = randomToken();
        long expiresAt = now + BridgeExecutionLimits.PREFLIGHT_LIFETIME_MILLIS;
        BridgeExecutionRecord record = newRecord(clientId, request, payloadHash, operations.size(), origin, now);
        record.state = "PREPARED";
        record.eventSequence = 1;
        record.updatedAtEpochMillis = now;
        if (!save(record)) return PrepareResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name());
        prepared = new PreparedExecution(record, token, expiresAt, copyOperations(operations));
        return PrepareResult.ready(prepared.toProtocol(request.buildPlan.metadata.title));
    }

    synchronized StartResult start(String clientId, String executionId, String token, ExecutionWorldAccess worldAccess) {
        return start(clientId, executionId, token, worldAccess, null);
    }

    synchronized StartResult start(String clientId, String executionId, String token, ExecutionWorldAccess worldAccess,
                                    String admissionError) {
        long now = Math.max(1L, clock.getAsLong());
        if (!BridgeExecutionRecordStore.isValidExecutionId(executionId)) {
            return StartResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_ID_INVALID.name());
        }
        BridgeExecutionRecord record = findRecord(executionId);
        if (record == null || !record.ownerClientId.equals(clientId)) return StartResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_NOT_FOUND.name());
        if (!"PREPARED".equals(record.state)) {
            if ("QUEUED".equals(record.state) || "RUNNING".equals(record.state) || "COMPLETED".equals(record.state)) {
                return StartResult.existing(snapshot(record));
            }
            return StartResult.rejected(record.reasonCode == null
                    ? BridgeProtocol.ErrorCode.EXECUTION_ID_CONFLICT.name() : record.reasonCode);
        }
        if (prepared == null || !prepared.record.executionId.equals(executionId) ||
                !prepared.record.ownerClientId.equals(clientId) || !sameToken(prepared.token, token)) {
            return StartResult.rejected(BridgeProtocol.ErrorCode.PREFLIGHT_TOKEN_INVALID.name());
        }
        if (now > prepared.expiresAtEpochMillis) {
            failRecord(record, BridgeProtocol.ErrorCode.PREFLIGHT_EXPIRED.name(), null);
            prepared = null;
            return StartResult.rejected(BridgeProtocol.ErrorCode.PREFLIGHT_EXPIRED.name());
        }
        if (admissionError != null) {
            failRecord(record, safeReason(admissionError), null);
            prepared = null;
            return StartResult.rejected(safeReason(admissionError));
        }
        if (active != null) return StartResult.rejected(BridgeProtocol.ErrorCode.ACTIVE_EXECUTION_EXISTS.name());
        String preflightError = worldAccess == null ? BridgeProtocol.ErrorCode.WORLD_UNAVAILABLE.name()
                : worldAccess.preflight(prepared.operations);
        if (preflightError != null) {
            failRecord(record, safeReason(preflightError), null);
            prepared = null;
            return StartResult.rejected(safeReason(preflightError));
        }
        record.state = "QUEUED";
        record.reasonCode = null;
        record.failedOperationIndex = null;
        record.eventSequence++;
        record.updatedAtEpochMillis = now;
        if (!save(record)) return StartResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name());
        active = new ActiveExecution(record.executionId, prepared.operations, worldAccess, now);
        prepared = null;
        return StartResult.accepted(snapshot(record));
    }

    synchronized ExecutionProtocol.ExecutionSnapshot status(String clientId, String executionId) {
        if (!BridgeExecutionRecordStore.isValidExecutionId(executionId)) return null;
        BridgeExecutionRecord record = findRecord(executionId);
        if (record == null || !record.ownerClientId.equals(clientId)) return null;
        return snapshot(record);
    }

    synchronized CancelResult cancel(String clientId, String executionId) {
        if (!BridgeExecutionRecordStore.isValidExecutionId(executionId)) {
            return CancelResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_ID_INVALID.name(), null);
        }
        BridgeExecutionRecord record = findRecord(executionId);
        if (record == null || !record.ownerClientId.equals(clientId)) return CancelResult.notFound();
        if (BridgeExecutionRecordStore.isTerminal(record.state)) return CancelResult.finished(record.state);
        if ("PREPARED".equals(record.state)) {
            record.state = "CANCELLED";
            record.reasonCode = null;
            record.eventSequence++;
            record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, clock.getAsLong());
            if (!save(record)) return CancelResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name(), record.state);
            prepared = null;
            return CancelResult.accepted(record.state);
        }
        if (active == null || !active.executionId.equals(executionId)) {
            return CancelResult.rejected(BridgeProtocol.ErrorCode.EXECUTION_NOT_FOUND.name(), record.state);
        }
        active.cancelRequested = true;
        return CancelResult.accepted(record.state);
    }

    /** Must be called once at END_SERVER_TICK. At most operationsPerTick world writes are attempted. */
    synchronized void tick() {
        tickInternal(null, false);
    }

    synchronized void tick(BridgeBuildOrigin currentOrigin) {
        tickInternal(currentOrigin, true);
    }

    private void tickInternal(BridgeBuildOrigin currentOrigin, boolean verifyOrigin) {
        long currentTime = Math.max(1L, clock.getAsLong());
        expirePrepared(currentTime);
        if (verifyOrigin && prepared != null && !sameOrigin(prepared.record, currentOrigin)) {
            BridgeExecutionRecord preparedRecord = findRecord(prepared.record.executionId);
            if (preparedRecord != null) failRecord(preparedRecord, BridgeProtocol.ErrorCode.ORIGIN_CHANGED.name(), null, currentTime);
            prepared = null;
        }
        if (active == null) return;
        BridgeExecutionRecord record = findRecord(active.executionId);
        if (record == null || BridgeExecutionRecordStore.isTerminal(record.state)) {
            active = null;
            return;
        }
        long now = Math.max(record.createdAtEpochMillis, clock.getAsLong());
        if (verifyOrigin && !sameOrigin(record, currentOrigin)) {
            failActive(record, BridgeProtocol.ErrorCode.ORIGIN_CHANGED.name(), null, now);
            return;
        }
        if (now - active.startedAtEpochMillis > limits.maxExecutionMillis()) {
            failActive(record, BridgeProtocol.ErrorCode.EXECUTION_TIMEOUT.name(), null, now);
            return;
        }
        if (active.cancelRequested) {
            record.state = "CANCELLED";
            record.reasonCode = null;
            record.eventSequence++;
            record.updatedAtEpochMillis = now;
            saveOrFailActive(record, now);
            active = null;
            return;
        }
        if ("QUEUED".equals(record.state)) {
            record.state = "RUNNING";
            record.eventSequence++;
            record.updatedAtEpochMillis = now;
            if (!save(record)) {
                record.state = "FAILED";
                record.reasonCode = BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name();
                record.eventSequence++;
                retainInMemory(record);
                active = null;
                return;
            }
        }
        int operationsThisTick = 0;
        while (active != null && operationsThisTick < limits.operationsPerTick()) {
            if (active.cancelRequested) {
                record = findRecord(active.executionId);
                if (record != null) {
                    record.state = "CANCELLED";
                    record.reasonCode = null;
                    record.eventSequence++;
                    record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, clock.getAsLong());
                    saveOrFailActive(record, record.updatedAtEpochMillis);
                }
                active = null;
                return;
            }
            int index = active.nextOperationIndex;
            if (index >= active.operations.size()) {
                record = findRecord(active.executionId);
                if (record != null) {
                    record.state = "COMPLETED";
                    record.reasonCode = null;
                    record.eventSequence++;
                    record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, clock.getAsLong());
                    if (!save(record)) {
                        record.state = "FAILED";
                        record.reasonCode = BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name();
                        record.eventSequence++;
                        retainInMemory(record);
                    }
                }
                active = null;
                return;
            }
            BuildPlanTransformer.TransformedOperation operation = active.operations.get(index);
            ExecutionWorldAccess.PlacementResult result;
            try {
                result = active.worldAccess.place(operation);
            } catch (RuntimeException error) {
                result = ExecutionWorldAccess.PlacementResult.failed(BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name());
            }
            if (result == null || !result.placed) {
                record = findRecord(active.executionId);
                if (record != null) failActive(record,
                        result == null ? BridgeProtocol.ErrorCode.PLACEMENT_REJECTED.name() : safeReason(result.reasonCode), index,
                        Math.max(record.createdAtEpochMillis, clock.getAsLong()));
                return;
            }
            active.nextOperationIndex++;
            operationsThisTick++;
            record = findRecord(active.executionId);
            if (record == null) {
                active = null;
                return;
            }
            record.completedOperations++;
            record.eventSequence++;
            record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, clock.getAsLong());
            if (!save(record)) {
                record.state = "FAILED";
                record.reasonCode = BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name();
                record.eventSequence++;
                record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, clock.getAsLong());
                retainInMemory(record);
                active = null;
                return;
            }
        }
        if (active != null && active.nextOperationIndex == active.operations.size()) {
            record = findRecord(active.executionId);
            if (record != null) {
                record.state = "COMPLETED";
                record.reasonCode = null;
                record.eventSequence++;
                record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, clock.getAsLong());
                if (!save(record)) {
                    record.state = "FAILED";
                    record.reasonCode = BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name();
                    record.eventSequence++;
                    retainInMemory(record);
                }
            }
            active = null;
        }
    }

    synchronized void serverStopping() {
        long now = Math.max(1L, clock.getAsLong());
        if (active != null) {
            BridgeExecutionRecord record = findRecord(active.executionId);
            if (record != null && !BridgeExecutionRecordStore.isTerminal(record.state)) {
                failRecord(record, BridgeProtocol.ErrorCode.SERVER_RESTARTED.name(), null, now);
            }
            active = null;
        }
        if (prepared != null) {
            BridgeExecutionRecord record = findRecord(prepared.record.executionId);
            if (record != null && !BridgeExecutionRecordStore.isTerminal(record.state)) {
                failRecord(record, BridgeProtocol.ErrorCode.SERVER_RESTARTED.name(), null, now);
            }
            prepared = null;
        }
    }

    synchronized boolean hasActiveExecution() {
        return active != null || prepared != null;
    }

    private void expirePrepared(long now) {
        if (prepared == null || now <= prepared.expiresAtEpochMillis) return;
        BridgeExecutionRecord record = findRecord(prepared.record.executionId);
        if (record != null && "PREPARED".equals(record.state)) failRecord(record, BridgeProtocol.ErrorCode.PREFLIGHT_EXPIRED.name(), null, now);
        prepared = null;
    }

    private boolean sameOrigin(BridgeExecutionRecord record, BridgeBuildOrigin origin) {
        return record != null && origin != null && record.dimensionId.equals(origin.dimensionId) &&
                record.worldSessionId.equals(origin.worldSessionId) && record.resolvedOrigin != null &&
                record.resolvedOrigin.x == origin.position.x && record.resolvedOrigin.y == origin.position.y &&
                record.resolvedOrigin.z == origin.position.z;
    }

    private void failActive(BridgeExecutionRecord record, String reason, Integer operationIndex, long now) {
        record.state = "FAILED";
        record.reasonCode = safeReason(reason);
        record.failedOperationIndex = operationIndex;
        record.eventSequence++;
        record.updatedAtEpochMillis = now;
        saveOrFailActive(record, now);
        active = null;
    }

    private void failRecord(BridgeExecutionRecord record, String reason, Integer operationIndex) {
        failRecord(record, reason, operationIndex, Math.max(record.createdAtEpochMillis, clock.getAsLong()));
    }

    private void failRecord(BridgeExecutionRecord record, String reason, Integer operationIndex, long now) {
        record.state = "FAILED";
        record.reasonCode = safeReason(reason);
        record.failedOperationIndex = operationIndex;
        record.eventSequence++;
        record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, now);
        if (!save(record)) retainInMemory(record);
    }

    private void saveOrFailActive(BridgeExecutionRecord record, long now) {
        if (save(record)) return;
        record.state = "FAILED";
        record.reasonCode = BridgeProtocol.ErrorCode.EXECUTION_STORE_UNAVAILABLE.name();
        record.eventSequence++;
        record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, now);
        if (!save(record)) retainInMemory(record);
    }

    private BridgeExecutionRecord findRecord(String executionId) {
        BridgeExecutionRecord override = volatileOverrides.get(executionId);
        return override == null ? store.find(executionId) : override.copy();
    }

    private boolean save(BridgeExecutionRecord record) {
        try {
            store.save(record);
            volatileOverrides.remove(record.executionId);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private void retainInMemory(BridgeExecutionRecord record) {
        volatileOverrides.put(record.executionId, record.copy());
    }

    private BridgeExecutionRecord newRecord(String clientId, BridgeExecutionRequest request, String payloadHash,
                                            int totalOperations, BridgeBuildOrigin origin, long now) {
        BridgeExecutionRecord record = new BridgeExecutionRecord();
        record.executionId = request.executionId;
        record.payloadHash = payloadHash;
        record.ownerClientId = clientId;
        record.buildId = request.buildId;
        record.planRecordId = request.planRecordId;
        record.planVersion = request.planVersion;
        record.state = "PREPARED";
        record.completedOperations = 0;
        record.totalOperations = totalOperations;
        record.eventSequence = 0;
        record.createdAtEpochMillis = now;
        record.updatedAtEpochMillis = now;
        record.dimensionId = origin.dimensionId;
        record.worldSessionId = origin.worldSessionId;
        record.resolvedOrigin = BridgeExecutionRecord.copyPosition(origin.position);
        return record;
    }

    private static List<BuildPlanTransformer.TransformedOperation> copyOperations(
            List<BuildPlanTransformer.TransformedOperation> operations) {
        return new ArrayList<>(operations);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        BridgeCrypto.zero(bytes);
        return token;
    }

    private static boolean sameToken(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII));
    }

    static String hashPayload(String payload) throws GeneralSecurityException {
        return BridgeCrypto.sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeReason(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : BridgeProtocol.ErrorCode.INTERNAL_ERROR.name();
    }

    private static ExecutionProtocol.ExecutionSnapshot snapshot(BridgeExecutionRecord record) {
        ExecutionProtocol.ExecutionSnapshot result = new ExecutionProtocol.ExecutionSnapshot();
        result.executionId = record.executionId;
        result.buildId = record.buildId;
        result.planRecordId = record.planRecordId;
        result.planVersion = record.planVersion;
        result.state = record.state;
        result.completedOperations = record.completedOperations;
        result.totalOperations = record.totalOperations;
        result.eventSequence = record.eventSequence;
        result.createdAtEpochMillis = record.createdAtEpochMillis;
        result.updatedAtEpochMillis = record.updatedAtEpochMillis;
        result.dimensionId = record.dimensionId;
        result.worldSessionId = record.worldSessionId;
        result.resolvedOrigin = BridgeExecutionRecord.copyPosition(record.resolvedOrigin);
        result.reasonCode = record.reasonCode;
        result.failedOperationIndex = record.failedOperationIndex;
        return result;
    }

    static final class PrepareResult {
        final ExecutionProtocol.PreflightReady ready;
        final ExecutionProtocol.ExecutionSnapshot existing;
        final String reasonCode;
        private PrepareResult(ExecutionProtocol.PreflightReady ready, ExecutionProtocol.ExecutionSnapshot existing, String reasonCode) {
            this.ready = ready;
            this.existing = existing;
            this.reasonCode = reasonCode;
        }
        static PrepareResult ready(ExecutionProtocol.PreflightReady ready) { return new PrepareResult(ready, null, null); }
        static PrepareResult existing(ExecutionProtocol.ExecutionSnapshot existing) { return new PrepareResult(null, existing, null); }
        static PrepareResult rejected(String reason) { return new PrepareResult(null, null, reason); }
    }

    static final class StartResult {
        final ExecutionProtocol.ExecutionSnapshot snapshot;
        final String reasonCode;
        final boolean accepted;
        private StartResult(ExecutionProtocol.ExecutionSnapshot snapshot, String reasonCode, boolean accepted) {
            this.snapshot = snapshot;
            this.reasonCode = reasonCode;
            this.accepted = accepted;
        }
        static StartResult accepted(ExecutionProtocol.ExecutionSnapshot snapshot) { return new StartResult(snapshot, null, true); }
        static StartResult existing(ExecutionProtocol.ExecutionSnapshot snapshot) { return new StartResult(snapshot, null, true); }
        static StartResult rejected(String reason) { return new StartResult(null, reason, false); }
    }

    static final class CancelResult {
        final String outcome;
        final String state;
        final String reasonCode;
        private CancelResult(String outcome, String state, String reasonCode) {
            this.outcome = outcome;
            this.state = state;
            this.reasonCode = reasonCode;
        }
        static CancelResult accepted(String state) { return new CancelResult("CANCELLATION_ACCEPTED", state, null); }
        static CancelResult rejected(String reason, String state) { return new CancelResult("CANCELLATION_REJECTED", state, reason); }
        static CancelResult finished(String state) { return new CancelResult("EXECUTION_ALREADY_FINISHED", state, null); }
        static CancelResult notFound() { return new CancelResult("EXECUTION_NOT_FOUND", null, null); }
    }

    private static final class PreparedExecution {
        final BridgeExecutionRecord record;
        final String token;
        final long expiresAtEpochMillis;
        final List<BuildPlanTransformer.TransformedOperation> operations;
        PreparedExecution(BridgeExecutionRecord record, String token, long expiresAtEpochMillis,
                          List<BuildPlanTransformer.TransformedOperation> operations) {
            this.record = record.copy();
            this.token = token;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            this.operations = List.copyOf(operations);
        }
        ExecutionProtocol.PreflightReady toProtocol(String planTitle) {
            ExecutionProtocol.PreflightReady response = new ExecutionProtocol.PreflightReady();
            response.executionId = record.executionId;
            response.planRecordId = record.planRecordId;
            response.planVersion = record.planVersion;
            response.planTitle = planTitle;
            response.dimensionId = record.dimensionId;
            response.worldSessionId = record.worldSessionId;
            response.resolvedOrigin = BridgeExecutionRecord.copyPosition(record.resolvedOrigin);
            response.originStrategy = "SERVER_SELECTED_ORIGIN";
            response.operationCount = record.totalOperations;
            response.createdAtEpochMillis = record.createdAtEpochMillis;
            response.eventSequence = record.eventSequence;
            response.expiresAtEpochMillis = expiresAtEpochMillis;
            response.preflightToken = token;
            return response;
        }
    }

    private static final class ActiveExecution {
        final String executionId;
        final List<BuildPlanTransformer.TransformedOperation> operations;
        final ExecutionWorldAccess worldAccess;
        final long startedAtEpochMillis;
        int nextOperationIndex;
        boolean cancelRequested;
        ActiveExecution(String executionId, List<BuildPlanTransformer.TransformedOperation> operations,
                        ExecutionWorldAccess worldAccess, long startedAtEpochMillis) {
            this.executionId = executionId;
            this.operations = operations;
            this.worldAccess = worldAccess;
            this.startedAtEpochMillis = startedAtEpochMillis;
        }
    }
}
