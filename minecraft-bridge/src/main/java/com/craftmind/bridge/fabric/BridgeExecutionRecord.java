package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BuildPlanDocument;

/** Persisted execution metadata only. The accepted BuildPlan remains in the Android immutable plan history. */
final class BridgeExecutionRecord {
    String executionId;
    String payloadHash;
    String ownerClientId;
    String buildId;
    String planRecordId;
    int planVersion;
    String state;
    int completedOperations;
    int totalOperations;
    long eventSequence;
    long createdAtEpochMillis;
    long updatedAtEpochMillis;
    String dimensionId;
    String worldSessionId;
    BuildPlanDocument.Position resolvedOrigin;
    String reasonCode;
    Integer failedOperationIndex;

    BridgeExecutionRecord copy() {
        BridgeExecutionRecord result = new BridgeExecutionRecord();
        result.executionId = executionId;
        result.payloadHash = payloadHash;
        result.ownerClientId = ownerClientId;
        result.buildId = buildId;
        result.planRecordId = planRecordId;
        result.planVersion = planVersion;
        result.state = state;
        result.completedOperations = completedOperations;
        result.totalOperations = totalOperations;
        result.eventSequence = eventSequence;
        result.createdAtEpochMillis = createdAtEpochMillis;
        result.updatedAtEpochMillis = updatedAtEpochMillis;
        result.dimensionId = dimensionId;
        result.worldSessionId = worldSessionId;
        result.resolvedOrigin = resolvedOrigin == null ? null : copyPosition(resolvedOrigin);
        result.reasonCode = reasonCode;
        result.failedOperationIndex = failedOperationIndex;
        return result;
    }

    static BuildPlanDocument.Position copyPosition(BuildPlanDocument.Position position) {
        if (position == null) return null;
        BuildPlanDocument.Position result = new BuildPlanDocument.Position();
        result.x = position.x;
        result.y = position.y;
        result.z = position.z;
        return result;
    }
}
