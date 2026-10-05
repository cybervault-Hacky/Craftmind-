package com.craftmind.bridge.protocol;

import java.util.ArrayList;
import java.util.List;

/** Authenticated capability report. It is data about support, not an execution result. */
public final class BridgeCapabilities {
    public int protocolVersion;
    public String bridgeId;
    public String identityFingerprint;
    /** App version sent by the authenticated client; public /info responses leave it null. */
    public String clientAppVersion;
    public String bridgeVersion;
    public String edition;
    public String minecraftVersion;
    public int javaRuntimeMajor;
    public String loaderName;
    public String loaderVersion;
    public String fabricApiVersion;
    public List<String> supportedCapabilities = new ArrayList<>();
    public boolean worldAccess;
    public boolean constructionExecute;
    public boolean cancellation;
    public int maximumValidatedOperations;
    public int maximumRequestBytes;
    public int maximumOperationsPerTick;
    public int maximumExecutionSeconds;
    public List<Integer> supportedBuildPlanSchemaVersions = new ArrayList<>();
    public String dimensionId;
    public String worldSessionId;
}
