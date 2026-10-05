package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import com.craftmind.bridge.protocol.BridgeProtocol;
import com.craftmind.bridge.protocol.BridgeProtocolCodec;
import com.craftmind.bridge.protocol.BridgeProtocolException;
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
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Atomic local allow-list of Android public keys. This file contains no private key or pairing code. */
public final class FileTrustedClientRepository implements TrustedClientRepository {
    private static final int MAX_FILE_BYTES = 128 * 1024;
    private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9 ._()-]{1,48}");
    private final Path file;
    private final Map<String, TrustedBridgeClient> clients = new LinkedHashMap<>();

    public FileTrustedClientRepository(Path dataDirectory) throws Exception {
        this.file = dataDirectory.resolve("trusted-clients.json");
        load();
    }

    @Override public synchronized TrustedBridgeClient find(String clientId) {
        TrustedBridgeClient value = clients.get(clientId);
        return value == null ? null : copy(value);
    }

    @Override public synchronized List<TrustedBridgeClient> all() {
        List<TrustedBridgeClient> result = new ArrayList<>();
        for (TrustedBridgeClient client : clients.values()) result.add(copy(client));
        return result;
    }

    @Override public synchronized void add(TrustedBridgeClient client) throws Exception {
        validate(client);
        if (clients.containsKey(client.clientId)) throw new IllegalStateException("ALREADY_PAIRED");
        if (clients.size() >= BridgeProtocol.MAX_TRUSTED_CLIENTS) throw new IllegalStateException("TRUSTED_CLIENT_LIMIT");
        clients.put(client.clientId, copy(client));
        try {
            persist();
        } catch (Exception error) {
            clients.remove(client.clientId);
            throw error;
        }
    }

    @Override public synchronized boolean remove(String clientId) throws Exception {
        TrustedBridgeClient removed = clients.remove(clientId);
        if (removed == null) return false;
        try {
            persist();
            return true;
        } catch (Exception error) {
            clients.put(clientId, removed);
            throw error;
        }
    }

    private void load() throws Exception {
        if (!Files.exists(file)) return;
        if (Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES) throw new IOException("trusted client store rejected");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = readBounded(input);
        }
        try {
            JsonElement root = BridgeProtocolCodec.parseJson(bytes, MAX_FILE_BYTES);
            if (!root.isJsonObject()) throw new IOException("trusted client store rejected");
            JsonObject object = root.getAsJsonObject();
            BridgeProtocolCodec.requireExactKeys(object, "schemaVersion", "clients");
            if (BridgeProtocolCodec.requiredInt(object, "schemaVersion") != 1 || !object.get("clients").isJsonArray()) {
                throw new IOException("trusted client store rejected");
            }
            JsonArray array = object.getAsJsonArray("clients");
            if (array.size() > BridgeProtocol.MAX_TRUSTED_CLIENTS) throw new IOException("trusted client store rejected");
            for (JsonElement element : array) {
                if (!element.isJsonObject()) throw new IOException("trusted client store rejected");
                JsonObject entry = element.getAsJsonObject();
                BridgeProtocolCodec.requireExactKeys(entry, "clientId", "displayName", "publicKeyBase64Url", "pairedAtEpochMillis");
                TrustedBridgeClient client = new TrustedBridgeClient(
                        BridgeProtocolCodec.requiredString(entry, "clientId", 40),
                        BridgeProtocolCodec.requiredString(entry, "displayName", 48),
                        BridgeProtocolCodec.requiredString(entry, "publicKeyBase64Url", 1024),
                        BridgeProtocolCodec.requiredLong(entry, "pairedAtEpochMillis"));
                validate(client);
                if (clients.put(client.clientId, client) != null) throw new IOException("trusted client store rejected");
            }
        } catch (BridgeProtocolException | RuntimeException error) {
            clients.clear();
            throw new IOException("trusted client store rejected", error);
        } finally {
            BridgeCrypto.zero(bytes);
        }
    }

    private void validate(TrustedBridgeClient client) throws GeneralSecurityException, IOException {
        if (client == null || !BridgeCrypto.validClientId(client.clientId) ||
                client.displayName == null || !DISPLAY_NAME.matcher(client.displayName).matches() ||
                client.pairedAtEpochMillis <= 0L) throw new IOException("trusted client record rejected");
        byte[] publicKey = BridgeCrypto.decodeBase64Url(client.publicKeyBase64Url, 1024);
        try {
            if (!BridgeCrypto.clientId(publicKey).equals(client.clientId) || !BridgeCrypto.isP256PublicKey(publicKey)) {
                throw new IOException("trusted client identity rejected");
            }
            java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(publicKey));
        } finally {
            BridgeCrypto.zero(publicKey);
        }
    }

    private void persist() throws Exception {
        StoreDocument document = new StoreDocument();
        document.schemaVersion = 1;
        document.clients = all();
        byte[] bytes = BridgeProtocolCodec.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            BridgeCrypto.zero(bytes);
            throw new IOException("trusted client store size limit");
        }
        Path temporary = Files.createTempFile(file.getParent(), "trusted-clients-", ".tmp");
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
                if (total > MAX_FILE_BYTES) throw new IOException("trusted client store size limit");
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

    private static TrustedBridgeClient copy(TrustedBridgeClient client) {
        return new TrustedBridgeClient(client.clientId, client.displayName, client.publicKeyBase64Url, client.pairedAtEpochMillis);
    }

    private static final class StoreDocument {
        int schemaVersion;
        List<TrustedBridgeClient> clients = new ArrayList<>();
    }
}
