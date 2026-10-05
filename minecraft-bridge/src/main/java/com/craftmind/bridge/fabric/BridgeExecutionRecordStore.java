package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import com.craftmind.bridge.protocol.BridgeProtocol;
import com.craftmind.bridge.protocol.BridgeProtocolCodec;
import com.craftmind.bridge.protocol.BridgeProtocolException;
import com.craftmind.bridge.protocol.BuildPlanDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded atomic execution ledger. It never stores a plan body, provider prompt, credential, or token. */
final class BridgeExecutionRecordStore {
    private static final Pattern EXECUTION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final Pattern CLIENT_ID = Pattern.compile("cm-[0-9a-f]{24}");
    private static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}");
    private static final Set<String> STATES = Set.of("PREPARED", "QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> FIELDS = Set.of("executionId", "payloadHash", "ownerClientId", "buildId", "planRecordId",
            "planVersion", "state", "completedOperations", "totalOperations", "eventSequence", "createdAtEpochMillis",
            "updatedAtEpochMillis", "dimensionId", "worldSessionId", "resolvedOrigin", "reasonCode", "failedOperationIndex");

    private final Path file;
    private final LinkedHashMap<String, BridgeExecutionRecord> records = new LinkedHashMap<>();

    BridgeExecutionRecordStore(Path dataDirectory) throws IOException {
        this.file = dataDirectory.resolve("execution-records.json");
        load();
        markInterruptedExecutions();
    }

    synchronized BridgeExecutionRecord find(String executionId) {
        BridgeExecutionRecord record = records.get(executionId);
        return record == null ? null : record.copy();
    }

    synchronized List<BridgeExecutionRecord> all() {
        List<BridgeExecutionRecord> result = new ArrayList<>();
        for (BridgeExecutionRecord record : records.values()) result.add(record.copy());
        return result;
    }

    synchronized void save(BridgeExecutionRecord record) throws IOException {
        validate(record);
        LinkedHashMap<String, BridgeExecutionRecord> backup = new LinkedHashMap<>();
        for (Map.Entry<String, BridgeExecutionRecord> entry : records.entrySet()) {
            backup.put(entry.getKey(), entry.getValue().copy());
        }
        if (!records.containsKey(record.executionId) && records.size() >= BridgeExecutionLimits.MAX_PERSISTED_EXECUTIONS) {
            String removable = null;
            for (Map.Entry<String, BridgeExecutionRecord> entry : records.entrySet()) {
                if (isTerminal(entry.getValue().state)) {
                    removable = entry.getKey();
                    break;
                }
            }
            if (removable == null) throw new IOException("execution ledger capacity reached");
            records.remove(removable);
        }
        records.put(record.executionId, record.copy());
        try {
            persist();
        } catch (IOException error) {
            records.clear();
            records.putAll(backup);
            throw error;
        }
    }

    private void load() throws IOException {
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(file) || Files.size(file) > BridgeExecutionLimits.MAX_EXECUTION_STORE_BYTES) {
            throw new IOException("execution ledger rejected");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = readBounded(input);
        }
        try {
            JsonElement root = BridgeProtocolCodec.parseJson(bytes, BridgeExecutionLimits.MAX_EXECUTION_STORE_BYTES);
            if (!root.isJsonObject()) throw new IOException("execution ledger rejected");
            JsonObject document = root.getAsJsonObject();
            BridgeProtocolCodec.requireExactKeys(document, "schemaVersion", "records");
            if (BridgeProtocolCodec.requiredInt(document, "schemaVersion") != 1 || !document.get("records").isJsonArray()) {
                throw new IOException("execution ledger rejected");
            }
            JsonArray array = document.getAsJsonArray("records");
            if (array.size() > BridgeExecutionLimits.MAX_PERSISTED_EXECUTIONS) throw new IOException("execution ledger rejected");
            for (JsonElement element : array) {
                if (!element.isJsonObject() || !element.getAsJsonObject().keySet().equals(FIELDS)) {
                    throw new IOException("execution ledger rejected");
                }
                BridgeExecutionRecord record = parseRecord(element.getAsJsonObject());
                if (records.put(record.executionId, record) != null) throw new IOException("execution ledger rejected");
            }
        } catch (BridgeProtocolException | RuntimeException error) {
            records.clear();
            throw new IOException("execution ledger rejected", error);
        } finally {
            BridgeCrypto.zero(bytes);
        }
    }

    private BridgeExecutionRecord parseRecord(JsonObject object) throws BridgeProtocolException, IOException {
        BridgeExecutionRecord result = new BridgeExecutionRecord();
        result.executionId = BridgeProtocolCodec.requiredString(object, "executionId", 36);
        result.payloadHash = BridgeProtocolCodec.requiredString(object, "payloadHash", 64);
        result.ownerClientId = BridgeProtocolCodec.requiredString(object, "ownerClientId", 80);
        result.buildId = BridgeProtocolCodec.requiredString(object, "buildId", 80);
        result.planRecordId = BridgeProtocolCodec.requiredString(object, "planRecordId", 128);
        result.planVersion = BridgeProtocolCodec.requiredInt(object, "planVersion");
        result.state = BridgeProtocolCodec.requiredString(object, "state", 16);
        result.completedOperations = BridgeProtocolCodec.requiredInt(object, "completedOperations");
        result.totalOperations = BridgeProtocolCodec.requiredInt(object, "totalOperations");
        result.eventSequence = BridgeProtocolCodec.requiredLong(object, "eventSequence");
        result.createdAtEpochMillis = BridgeProtocolCodec.requiredLong(object, "createdAtEpochMillis");
        result.updatedAtEpochMillis = BridgeProtocolCodec.requiredLong(object, "updatedAtEpochMillis");
        result.dimensionId = BridgeProtocolCodec.requiredString(object, "dimensionId", 130);
        result.worldSessionId = BridgeProtocolCodec.requiredString(object, "worldSessionId", 128);
        JsonElement origin = object.get("resolvedOrigin");
        if (origin == null || !origin.isJsonObject()) throw new IOException("execution ledger rejected");
        JsonObject position = origin.getAsJsonObject();
        BridgeProtocolCodec.requireExactKeys(position, "x", "y", "z");
        result.resolvedOrigin = new BuildPlanDocument.Position();
        result.resolvedOrigin.x = BridgeProtocolCodec.requiredInt(position, "x");
        result.resolvedOrigin.y = BridgeProtocolCodec.requiredInt(position, "y");
        result.resolvedOrigin.z = BridgeProtocolCodec.requiredInt(position, "z");
        result.reasonCode = BridgeProtocolCodec.nullableString(object, "reasonCode", 64);
        JsonElement failedIndex = object.get("failedOperationIndex");
        if (failedIndex == null) throw new IOException("execution ledger rejected");
        if (!failedIndex.isJsonNull()) {
            JsonObject one = new JsonObject();
            one.add("value", failedIndex);
            result.failedOperationIndex = BridgeProtocolCodec.requiredInt(one, "value");
        }
        validate(result);
        return result;
    }

    private void markInterruptedExecutions() throws IOException {
        long now = Math.max(1L, System.currentTimeMillis());
        boolean changed = false;
        for (BridgeExecutionRecord record : records.values()) {
            if ("PREPARED".equals(record.state) || "QUEUED".equals(record.state) || "RUNNING".equals(record.state)) {
                boolean wasPrepared = "PREPARED".equals(record.state);
                record.state = "FAILED";
                record.reasonCode = BridgeProtocol.ErrorCode.SERVER_RESTARTED.name();
                record.updatedAtEpochMillis = Math.max(record.createdAtEpochMillis, now);
                record.eventSequence++;
                if (wasPrepared) record.completedOperations = 0;
                validate(record);
                changed = true;
            }
        }
        if (changed) persist();
    }

    private void persist() throws IOException {
        StoreDocument document = new StoreDocument();
        document.schemaVersion = 1;
        document.records = all();
        byte[] bytes = BridgeProtocolCodec.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BridgeExecutionLimits.MAX_EXECUTION_STORE_BYTES) {
            BridgeCrypto.zero(bytes);
            throw new IOException("execution ledger size limit");
        }
        Path temporary = Files.createTempFile(file.getParent(), "execution-records-", ".tmp");
        try {
            setPrivatePermissions(temporary);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
            BridgeCrypto.zero(bytes);
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        try {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > BridgeExecutionLimits.MAX_EXECUTION_STORE_BYTES) throw new IOException("execution ledger size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            BridgeCrypto.zero(buffer);
        }
    }

    private void setPrivatePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Files inherit the server operator's private config directory ACL on non-POSIX hosts.
        }
    }

    private void validate(BridgeExecutionRecord record) throws IOException {
        if (record == null || record.executionId == null || !EXECUTION_ID.matcher(record.executionId).matches() ||
                record.payloadHash == null || !HASH.matcher(record.payloadHash).matches() ||
                record.ownerClientId == null || !CLIENT_ID.matcher(record.ownerClientId).matches() ||
                record.buildId == null || !BUILD_ID.matcher(record.buildId).matches() ||
                record.planRecordId == null || !RECORD_ID.matcher(record.planRecordId).matches() ||
                record.planVersion < 1 || record.planVersion > 100_000 || !STATES.contains(record.state) ||
                record.totalOperations < 1 || record.totalOperations > BridgeProtocol.MAX_OPERATIONS ||
                record.completedOperations < 0 || record.completedOperations > record.totalOperations ||
                record.eventSequence < 0 || record.createdAtEpochMillis <= 0 || record.updatedAtEpochMillis < record.createdAtEpochMillis ||
                record.dimensionId == null || !DIMENSION.matcher(record.dimensionId).matches() ||
                record.worldSessionId == null || !record.worldSessionId.matches("[A-Za-z0-9_-]{1,128}") ||
                record.resolvedOrigin == null ||
                (record.failedOperationIndex != null && (record.failedOperationIndex < 0 || record.failedOperationIndex >= record.totalOperations)) ||
                (record.reasonCode != null && !record.reasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) ||
                ("COMPLETED".equals(record.state) && record.completedOperations != record.totalOperations)) {
            throw new IOException("execution record rejected");
        }
    }

    static boolean isValidExecutionId(String executionId) {
        return executionId != null && EXECUTION_ID.matcher(executionId).matches();
    }

    static boolean isTerminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    private static final class StoreDocument {
        int schemaVersion;
        List<BridgeExecutionRecord> records = new ArrayList<>();
    }
}
