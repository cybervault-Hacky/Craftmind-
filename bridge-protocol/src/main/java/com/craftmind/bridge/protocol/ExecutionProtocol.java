package com.craftmind.bridge.protocol;

/** Typed future request/acknowledgement/progress/cancellation vocabulary. No producer executes it in Phase 4. */
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
    }

    public static final class CancelExecutionRequest {
        public String executionRequestId;
    }

    public static final class CancellationResult {
        public String executionRequestId;
        /** CANCELLATION_ACCEPTED, CANCELLATION_REJECTED, or EXECUTION_ALREADY_FINISHED. */
        public String outcome;
        public String reasonCode;
    }

    public static final class ProgressEvent {
        public String executionRequestId;
        public long eventSequence;
        /** EXECUTION_STARTED, PROGRESS_UPDATED, EXECUTION_COMPLETED, EXECUTION_FAILED, EXECUTION_CANCELLED. */
        public String eventType;
        public int completedOperations;
        public int totalOperations;
        public long observedAtEpochMillis;
        public String reasonCode;
    }
}
