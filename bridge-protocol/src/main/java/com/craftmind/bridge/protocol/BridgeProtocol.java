package com.craftmind.bridge.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stable protocol constants shared by the Android app and the Fabric server mod. */
public final class BridgeProtocol {
    public static final int VERSION = 2;
    public static final int BUILD_PLAN_SCHEMA_VERSION = 2;
    public static final int MAX_CONTROL_MESSAGE_BYTES = 64 * 1024;
    public static final int MAX_EXECUTION_REQUEST_BYTES = 1024 * 1024;
    public static final int MIN_EXECUTION_REQUEST_BYTES = 1024;
    public static final int MAX_DIMENSION_WIDTH = 96;
    public static final int MAX_DIMENSION_HEIGHT = 64;
    public static final int MAX_DIMENSION_DEPTH = 96;
    public static final int MAX_COMPONENTS = 64;
    public static final int MAX_OPERATIONS = 4096;
    public static final int MAX_OPERATIONS_PER_TICK = 64;
    public static final int MAX_EXECUTION_SECONDS = 900;
    public static final int MAX_BLOCK_STATE_PROPERTIES = 8;
    public static final int MAX_REQUEST_ID_LENGTH = 64;
    public static final int MAX_BRIDGE_ID_LENGTH = 80;
    public static final int MAX_BUILD_ID_LENGTH = 80;
    public static final int MAX_PLAN_RECORD_ID_LENGTH = 128;
    public static final int MAX_PAYLOAD_DEPTH = 32;
    public static final int MAX_JSON_TOKENS = 100_000;
    public static final long MAX_CLOCK_SKEW_MILLIS = 120_000L;
    public static final long PAIRING_WINDOW_MILLIS = 180_000L;
    public static final long AUTH_CHALLENGE_MILLIS = 30_000L;
    public static final long SESSION_IDLE_TIMEOUT_MILLIS = 5 * 60_000L;
    public static final long SESSION_MAX_AGE_MILLIS = 30 * 60_000L;
    public static final int MAX_TRUSTED_CLIENTS = 32;
    public static final Set<String> EXECUTION_STATES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "PREPARED", "QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")));

    private BridgeProtocol() { }

    public enum ErrorCode {
        MALFORMED_ENVELOPE,
        UNSUPPORTED_PROTOCOL,
        PAYLOAD_TOO_LARGE,
        INVALID_REQUEST_ID,
        INVALID_TIMESTAMP,
        UNAUTHENTICATED,
        PAIRING_CLOSED,
        PAIRING_EXPIRED,
        PAIRING_REJECTED,
        ALREADY_PAIRED,
        REPLAY_REJECTED,
        RATE_LIMITED,
        INVALID_BUILD_REQUEST,
        UNSUPPORTED_BUILD_PLAN_SCHEMA,
        INVALID_BUILD_PLAN,
        UNSUPPORTED_BLOCK,
        UNSUPPORTED_BLOCK_STATE,
        BUILD_TOO_LARGE,
        UNSUPPORTED_ORIGIN,
        LIMIT_EXCEEDED,
        CONSTRUCTION_DISABLED,
        CANCELLATION_UNAVAILABLE,
        EXECUTION_ID_INVALID,
        EXECUTION_ID_CONFLICT,
        ACTIVE_EXECUTION_EXISTS,
        EXECUTION_NOT_FOUND,
        PREFLIGHT_EXPIRED,
        PREFLIGHT_TOKEN_INVALID,
        ORIGIN_UNAVAILABLE,
        ORIGIN_CHANGED,
        WORLD_UNAVAILABLE,
        WORLD_SESSION_MISMATCH,
        WORLD_BOUNDS_REJECTED,
        CHUNK_NOT_LOADED,
        BLOCK_OCCUPIED,
        ENTITY_IN_BUILD_AREA,
        SPAWN_PROTECTED,
        PROTECTED_REGION,
        EXECUTION_STORE_UNAVAILABLE,
        EXECUTION_TIMEOUT,
        PLACEMENT_REJECTED,
        SERVER_RESTARTED,
        INTERNAL_ERROR
    }
}
