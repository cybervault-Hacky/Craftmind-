package com.craftmind.bridge.protocol;

import java.util.ArrayList;
import java.util.List;

/** Authenticated capability report. It is data about support, not an execution result. */
public final class BridgeCapabilities {
    public int protocolVersion;
    public String bridgeId;
    public String identityFingerprint;
    public String bridgeVersion;
    public String minecraftVersion;
    public String loaderName;
    public String loaderVersion;
    public boolean worldAccess;
    public boolean constructionExecute;
    public boolean cancellation;
    public int maximumValidatedOperations;
    public int maximumRequestBytes;
    public List<Integer> supportedBuildPlanSchemaVersions = new ArrayList<>();
    public String dimensionId;
    public String worldSessionId;
}
