package com.craftmind.bridge.protocol;

import com.google.gson.JsonObject;

/** Deterministic protocol envelope. The payload is message-specific JSON validated by its handler. */
public final class BridgeEnvelope {
    public final int protocolVersion;
    public final String messageType;
    public final String requestId;
    public final long timestampEpochMillis;
    public final String correlationId;
    public final JsonObject payload;

    public BridgeEnvelope(
            int protocolVersion,
            String messageType,
            String requestId,
            long timestampEpochMillis,
            String correlationId,
            JsonObject payload) {
        this.protocolVersion = protocolVersion;
        this.messageType = messageType;
        this.requestId = requestId;
        this.timestampEpochMillis = timestampEpochMillis;
        this.correlationId = correlationId;
        this.payload = payload;
    }
}
