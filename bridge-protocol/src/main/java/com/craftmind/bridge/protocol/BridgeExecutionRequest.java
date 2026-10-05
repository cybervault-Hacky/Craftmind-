package com.craftmind.bridge.protocol;

/** Authenticated, validation-only transport contract. This type cannot contain provider credentials or commands. */
public final class BridgeExecutionRequest {
    public String buildId;
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
