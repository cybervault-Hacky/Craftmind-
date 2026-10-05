package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeProtocol;
import java.io.IOException;
import java.util.Properties;

/** One bounded source of server-side construction resource limits. */
public final class BridgeExecutionLimits {
    public static final int DEFAULT_MAX_OPERATIONS = BridgeProtocol.MAX_OPERATIONS;
    public static final int DEFAULT_MAX_REQUEST_BYTES = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES;
    public static final int DEFAULT_OPERATIONS_PER_TICK = 32;
    public static final int MAX_OPERATIONS_PER_TICK = 64;
    public static final int DEFAULT_MAX_EXECUTION_SECONDS = 300;
    public static final int MAX_EXECUTION_SECONDS = 900;
    public static final int MAX_PERSISTED_EXECUTIONS = 100;
    public static final int MAX_EXECUTION_STORE_BYTES = 256 * 1024;
    public static final long PREFLIGHT_LIFETIME_MILLIS = 120_000L;
    public static final int MAX_COORDINATE = 30_000_000;
    public static final int MAX_TRANSFORM_Y = 2_048;

    private final int maxOperations;
    private final int maxRequestBytes;
    private final int operationsPerTick;
    private final int maxExecutionSeconds;

    public BridgeExecutionLimits(int maxOperations, int maxRequestBytes, int operationsPerTick, int maxExecutionSeconds) {
        if (maxOperations < 1 || maxOperations > BridgeProtocol.MAX_OPERATIONS ||
                maxRequestBytes < 1024 ||
                maxRequestBytes > BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES ||
                operationsPerTick < 1 || operationsPerTick > MAX_OPERATIONS_PER_TICK ||
                maxExecutionSeconds < 1 || maxExecutionSeconds > MAX_EXECUTION_SECONDS) {
            throw new IllegalArgumentException("execution limits out of range");
        }
        this.maxOperations = maxOperations;
        this.maxRequestBytes = maxRequestBytes;
        this.operationsPerTick = operationsPerTick;
        this.maxExecutionSeconds = maxExecutionSeconds;
    }

    public static BridgeExecutionLimits defaults() {
        return new BridgeExecutionLimits(DEFAULT_MAX_OPERATIONS, DEFAULT_MAX_REQUEST_BYTES,
                DEFAULT_OPERATIONS_PER_TICK, DEFAULT_MAX_EXECUTION_SECONDS);
    }

    static BridgeExecutionLimits fromProperties(Properties properties) throws IOException {
        int operations = integer(properties, "maxOperations", DEFAULT_MAX_OPERATIONS);
        int bytes = integer(properties, "maxRequestBytes", DEFAULT_MAX_REQUEST_BYTES);
        int perTick = integer(properties, "operationsPerTick", DEFAULT_OPERATIONS_PER_TICK);
        int duration = integer(properties, "maxExecutionSeconds", DEFAULT_MAX_EXECUTION_SECONDS);
        try {
            return new BridgeExecutionLimits(operations, bytes, perTick, duration);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid construction limit configuration");
        }
    }

    private static int integer(Properties properties, String key, int fallback) throws IOException {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException error) {
            throw new IOException("invalid construction limit configuration");
        }
    }

    public int maxOperations() { return maxOperations; }
    public int maxRequestBytes() { return maxRequestBytes; }
    public int operationsPerTick() { return operationsPerTick; }
    public int maxExecutionSeconds() { return maxExecutionSeconds; }
    public long maxExecutionMillis() { return maxExecutionSeconds * 1000L; }
}
