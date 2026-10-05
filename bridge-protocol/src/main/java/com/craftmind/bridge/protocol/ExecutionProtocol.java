package com.craftmind.bridge.protocol;

/** Typed preflight, acknowledgement, status and cancellation vocabulary for real bridge execution. */
public final class ExecutionProtocol {
    private ExecutionProtocol() { }

    public static final class RequestAccepted {
        public String requestId;
        public String state;
        public long acceptedAtEpochMillis;
    }

    public static final class RequestRejected {
        public String requestId;
        public BridgeProtocol.ErrorCode reasonCode;
        public String safeMessage;
        public Integer failedOperationIndex;
        public String blockId;
        public java.util.List<String> unsupportedStateProperties = new java.util.ArrayList<>();
    }

    public static final class PreflightReady {
        public String executionId;
        public String planRecordId;
        public int planVersion;
        public String planTitle;
        public String dimensionId;
        public String worldSessionId;
        public BuildPlanDocument.Position resolvedOrigin;
        public String originStrategy;
        public int operationCount;
        public long createdAtEpochMillis;
        public long eventSequence;
        public long expiresAtEpochMillis;
        /** Short-lived opaque confirmation token; never persisted or written to logs. */
        public String preflightToken;
    }

    public static final class StartExecutionRequest {
        public String executionId;
        public String preflightToken;
    }

    public static final class StatusRequest {
        public String executionId;
    }

    public static final class ExecutionSnapshot {
        public String executionId;
        public String buildId;
        public String planRecordId;
        public int planVersion;
        public String state;
        public int completedOperations;
        public int totalOperations;
        public long eventSequence;
        public long createdAtEpochMillis;
        public long updatedAtEpochMillis;
        public String dimensionId;
        public String worldSessionId;
        public BuildPlanDocument.Position resolvedOrigin;
        public String reasonCode;
        /** Zero-based operation index, present only if one exact operation failed. */
        public Integer failedOperationIndex;
    }

    public static final class CancelExecutionRequest {
        public String executionId;
    }

    public static final class CancellationResult {
        public String executionId;
        /** CANCELLATION_ACCEPTED, CANCELLATION_REJECTED, EXECUTION_ALREADY_FINISHED, or EXECUTION_NOT_FOUND. */
        public String outcome;
        public String state;
        public String reasonCode;
    }

    /** Poll responses are snapshots from the server-side placement coordinator, never client estimates. */
    public static final class ProgressEvent {
        public String executionId;
        public long eventSequence;
        /** EXECUTION_STARTED, PROGRESS_UPDATED, EXECUTION_COMPLETED, EXECUTION_FAILED, EXECUTION_CANCELLED. */
        public String eventType;
        public int completedOperations;
        public int totalOperations;
        public long observedAtEpochMillis;
        public String reasonCode;
        public Integer failedOperationIndex;
    }
}
