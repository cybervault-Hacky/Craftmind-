package com.craftmind.bridge.fabric;

/** Server-side record contains only a public client identity; no Android private key is received or stored. */
public final class TrustedBridgeClient {
    public String clientId;
    public String displayName;
    public String publicKeyBase64Url;
    public long pairedAtEpochMillis;

    public TrustedBridgeClient() { }

    public TrustedBridgeClient(String clientId, String displayName, String publicKeyBase64Url, long pairedAtEpochMillis) {
        this.clientId = clientId;
        this.displayName = displayName;
        this.publicKeyBase64Url = publicKeyBase64Url;
        this.pairedAtEpochMillis = pairedAtEpochMillis;
    }
}
