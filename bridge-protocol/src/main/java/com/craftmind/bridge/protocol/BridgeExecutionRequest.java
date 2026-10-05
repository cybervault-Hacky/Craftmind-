package com.craftmind.bridge.protocol;

/** Authenticated BuildPlan-v2 construction request. It contains no provider credentials or commands. */
public final class BridgeExecutionRequest {
    /** Stable UUID idempotency key, retained across client retries and status queries. */
    public String executionId;
    public String buildId;
    /** Immutable local plan-history identity; execution data is stored separately from this version. */
    public String planRecordId;
    public int planVersion;
    public int buildPlanSchemaVersion;
    public BuildPlanDocument buildPlan;
    public OriginSelection origin;
    public RequestLimits limits;

    public static final class OriginSelection {
        public String kind;
        public String dimensionId;
        public String worldSessionId;
        public BuildPlanDocument.Position position;
    }

    public static final class RequestLimits {
        public int maxOperations;
        public int maxRequestBytes;
    }
}
