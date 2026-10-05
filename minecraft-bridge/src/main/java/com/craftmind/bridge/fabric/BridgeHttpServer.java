package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCapabilities;
import com.craftmind.bridge.protocol.BridgeCrypto;
import com.craftmind.bridge.protocol.BridgeEnvelope;
import com.craftmind.bridge.protocol.BridgeProtocol;
import com.craftmind.bridge.protocol.BridgeProtocolCodec;
import com.craftmind.bridge.protocol.BridgeProtocolException;
import com.craftmind.bridge.protocol.ExecutionProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Private-LAN HTTPS endpoint for authenticated pairing, full execution preflight, explicit start, status, and cancel. */
public final class BridgeHttpServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("CraftMindBridge");
    private static final java.util.regex.Pattern APP_VERSION_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9._+-]{1,64}");
    private static final String INFO_PATH = "/v1/bridge/info";
    private static final String PAIR_PATH = "/v1/pair";
    private static final String CHALLENGE_PATH = "/v1/session/challenge";
    private static final String SESSION_PATH = "/v1/session";
    private static final String DISCONNECT_PATH = "/v1/session/disconnect";
    private static final String CAPABILITIES_PATH = "/v1/capabilities";
    private static final String REVOKE_PATH = "/v1/pair/revoke";
    private static final String EXECUTION_PATH = "/v1/executions";
    private static final String PREPARE_PATH = "/v1/executions/prepare";
    private static final String START_PATH = "/v1/executions/start";
    private static final String STATUS_PATH = "/v1/executions/status";
    private static final String CANCEL_PATH = "/v1/executions/cancel";
    private static final long REQUEST_WATCHDOG_MILLIS = 15_000L;
    private final CraftMindBridgeConfig config;
    private final BridgeIdentity identity;
    private final BridgeAuthenticationService authentication;
    private final Supplier<BridgeCapabilities> capabilitiesSupplier;
    private final Supplier<BridgeCapabilities> publicCapabilitiesSupplier;
    private final BridgeExecutionService executionService;
    private final BridgeRateLimiter rateLimiter = new BridgeRateLimiter();
    private final ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(namedFactory("craftmind-bridge-watchdog"));
    private HttpsServer server;
    private ExecutorService executor;

    public BridgeHttpServer(
            CraftMindBridgeConfig config,
            BridgeIdentity identity,
            BridgeAuthenticationService authentication,
            Supplier<BridgeCapabilities> capabilitiesSupplier,
            Supplier<BridgeCapabilities> publicCapabilitiesSupplier,
            BridgeExecutionService executionService) {
        this.config = config;
        this.identity = identity;
        this.authentication = authentication;
        this.capabilitiesSupplier = capabilitiesSupplier;
        this.publicCapabilitiesSupplier = publicCapabilitiesSupplier;
        this.executionService = executionService;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        InetSocketAddress bind = new InetSocketAddress(config.bindAddress(), config.port());
        HttpsServer newServer = HttpsServer.create(bind, 32);
        server = newServer;
        newServer.setHttpsConfigurator(new HttpsConfigurator(identity.sslContext()) {
            @Override public void configure(com.sun.net.httpserver.HttpsParameters parameters) {
                javax.net.ssl.SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                ssl.setNeedClientAuth(false);
                parameters.setSSLParameters(ssl);
            }
        });
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                task -> {
                    Thread thread = new Thread(task, "craftmind-bridge-http-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        newServer.setExecutor(executor);
        newServer.createContext("/", this::handle);
        newServer.start();
        LOGGER.info("CraftMind Bridge protocol v{} listening on configured private interface {}:{}; construction is server-configured and capability-gated.", BridgeProtocol.VERSION,
                config.bindAddress().getHostAddress(), config.port());
    }

    @Override public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        authentication.close();
        watchdog.shutdownNow();
    }

    public static boolean isAllowedPath(String path) {
        return INFO_PATH.equals(path) || PAIR_PATH.equals(path) || CHALLENGE_PATH.equals(path) ||
                SESSION_PATH.equals(path) || DISCONNECT_PATH.equals(path) || CAPABILITIES_PATH.equals(path) || REVOKE_PATH.equals(path) ||
                EXECUTION_PATH.equals(path) || PREPARE_PATH.equals(path) || START_PATH.equals(path) ||
                STATUS_PATH.equals(path) || CANCEL_PATH.equals(path);
    }

    private void handle(HttpExchange exchange) {
        String requestIdForLog = "unparsed";
        byte[] body = null;
        Future<?> timeout = watchdog.schedule(exchange::close, REQUEST_WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
        try {
            if (!(exchange instanceof HttpsExchange)) {
                writePlainError(exchange, 400);
                return;
            }
            InetAddress remoteAddress = exchange.getRemoteAddress().getAddress();
            if (remoteAddress == null || !NetworkAddressPolicy.isAllowedRemote(remoteAddress)) {
                writePlainError(exchange, 403);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                writePlainError(exchange, 405);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null || !isAllowedPath(path)) {
                writePlainError(exchange, 404);
                return;
            }
            if (!rateLimiter.allow(remoteAddress, path, System.currentTimeMillis())) {
                sendError(exchange, 429, BridgeProtocol.ErrorCode.RATE_LIMITED, null);
                return;
            }
            int maximumBytes = (EXECUTION_PATH.equals(path) || PREPARE_PATH.equals(path))
                    ? config.executionLimits().maxRequestBytes() : BridgeProtocol.MAX_CONTROL_MESSAGE_BYTES;
            body = readBounded(exchange, maximumBytes);
            BridgeEnvelope envelope = BridgeProtocolCodec.parseEnvelope(body, maximumBytes);
            requestIdForLog = envelope.requestId;
            checkTimestamp(envelope.timestampEpochMillis);
            dispatch(exchange, path, body, envelope, requestIdForLog);
        } catch (BridgeProtocolException error) {
            int status = statusFor(error.getCode());
            sendError(exchange, status, error.getCode(), requestIdForLog.equals("unparsed") ? null : requestIdForLog);
            if (error.getCode() != BridgeProtocol.ErrorCode.UNSUPPORTED_PROTOCOL) {
                LOGGER.warn("Bridge request rejected requestId={} code={}", requestIdForLog, error.getCode().name());
            }
        } catch (BridgeAuthenticationService.ServiceException error) {
            sendError(exchange, statusFor(error.getCode()), error.getCode(), requestIdForLog.equals("unparsed") ? null : requestIdForLog);
            LOGGER.warn("Bridge request rejected requestId={} code={}", requestIdForLog, error.getCode().name());
        } catch (IOException error) {
            sendError(exchange, 400, BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE,
                    requestIdForLog.equals("unparsed") ? null : requestIdForLog);
        } catch (RuntimeException error) {
            sendError(exchange, 500, BridgeProtocol.ErrorCode.INTERNAL_ERROR,
                    requestIdForLog.equals("unparsed") ? null : requestIdForLog);
            LOGGER.error("Bridge request failed requestId={} code=INTERNAL_ERROR", requestIdForLog);
        } finally {
            timeout.cancel(false);
            BridgeCrypto.zero(body);
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, String path, byte[] body, BridgeEnvelope envelope, String requestId)
            throws BridgeProtocolException, BridgeAuthenticationService.ServiceException, IOException {
        JsonObject payload = envelope.payload;
        if (INFO_PATH.equals(path)) {
            requireMessage(envelope, "bridge.info.request");
            BridgeProtocolCodec.requireExactKeys(payload);
            sendEnvelope(exchange, 200, "bridge.info.response", requestId, publicCapabilitiesSupplier.get());
            return;
        }
        if (PAIR_PATH.equals(path)) {
            requireMessage(envelope, "pair.request");
            BridgeProtocolCodec.requireExactKeys(payload, "pairingCode", "clientId", "displayName", "publicKeyBase64Url",
                    "clientNonce", "issuedAtEpochMillis", "proofSignatureBase64Url");
            BridgeAuthenticationService.PairingRequest pair = new BridgeAuthenticationService.PairingRequest();
            pair.pairingCode = BridgeProtocolCodec.requiredString(payload, "pairingCode", 64);
            pair.clientId = BridgeProtocolCodec.requiredString(payload, "clientId", 40);
            pair.displayName = BridgeProtocolCodec.requiredString(payload, "displayName", 48);
            pair.publicKeyBase64Url = BridgeProtocolCodec.requiredString(payload, "publicKeyBase64Url", 1024);
            pair.clientNonce = BridgeProtocolCodec.requiredString(payload, "clientNonce", 128);
            pair.issuedAtEpochMillis = BridgeProtocolCodec.requiredLong(payload, "issuedAtEpochMillis");
            pair.proofSignatureBase64Url = BridgeProtocolCodec.requiredString(payload, "proofSignatureBase64Url", 256);
            if (pair.issuedAtEpochMillis != envelope.timestampEpochMillis) throw malformed();
            BridgeAuthenticationService.PairingAccepted accepted = authentication.pair(pair);
            JsonObject result = new JsonObject();
            result.addProperty("bridgeId", identity.bridgeId());
            result.addProperty("clientId", accepted.clientId);
            result.addProperty("pairedAtEpochMillis", accepted.pairedAtEpochMillis);
            sendEnvelope(exchange, 200, "pair.accepted", requestId, result);
            return;
        }
        if (CHALLENGE_PATH.equals(path)) {
            requireMessage(envelope, "session.challenge.request");
            BridgeProtocolCodec.requireExactKeys(payload, "clientId");
            BridgeAuthenticationService.ChallengeResponse challenge = authentication.issueChallenge(
                    BridgeProtocolCodec.requiredString(payload, "clientId", 40));
            JsonObject result = new JsonObject();
            result.addProperty("challengeId", challenge.challengeId);
            result.addProperty("nonce", challenge.nonce);
            result.addProperty("expiresAtEpochMillis", challenge.expiresAtEpochMillis);
            sendEnvelope(exchange, 200, "session.challenge.response", requestId, result);
            return;
        }
        if (SESSION_PATH.equals(path)) {
            requireMessage(envelope, "session.authenticate.request");
            BridgeProtocolCodec.requireExactKeys(payload, "clientId", "challengeId", "challengeNonce", "issuedAtEpochMillis", "proofSignatureBase64Url");
            BridgeAuthenticationService.SessionRequest login = new BridgeAuthenticationService.SessionRequest();
            login.clientId = BridgeProtocolCodec.requiredString(payload, "clientId", 40);
            login.challengeId = BridgeProtocolCodec.requiredString(payload, "challengeId", 36);
            login.challengeNonce = BridgeProtocolCodec.requiredString(payload, "challengeNonce", 128);
            login.issuedAtEpochMillis = BridgeProtocolCodec.requiredLong(payload, "issuedAtEpochMillis");
            login.proofSignatureBase64Url = BridgeProtocolCodec.requiredString(payload, "proofSignatureBase64Url", 256);
            if (login.issuedAtEpochMillis != envelope.timestampEpochMillis) throw malformed();
            BridgeAuthenticationService.SessionCreated created = authentication.authenticate(login);
            JsonObject result = new JsonObject();
            result.addProperty("sessionId", created.sessionId);
            result.addProperty("expiresAtEpochMillis", created.expiresAtEpochMillis);
            sendEnvelope(exchange, 200, "session.authenticate.response", requestId, result);
            return;
        }

        String clientId = authorize(exchange, path, body, envelope);
        if (DISCONNECT_PATH.equals(path)) {
            requireMessage(envelope, "session.disconnect.request");
            BridgeProtocolCodec.requireExactKeys(payload);
            authentication.disconnect(singleHeader(exchange.getRequestHeaders(), "X-CraftMind-Session-Id"), clientId);
            JsonObject result = new JsonObject();
            result.addProperty("disconnected", true);
            sendEnvelope(exchange, 200, "session.disconnect.accepted", requestId, result);
            return;
        }
        if (CAPABILITIES_PATH.equals(path)) {
            requireMessage(envelope, "capabilities.request");
            BridgeProtocolCodec.requireExactKeys(payload, "clientAppVersion");
            String clientAppVersion = BridgeProtocolCodec.requiredString(payload, "clientAppVersion", 64);
            if (!APP_VERSION_PATTERN.matcher(clientAppVersion).matches()) throw malformed();
            BridgeCapabilities report = capabilitiesSupplier.get();
            report.clientAppVersion = clientAppVersion;
            sendEnvelope(exchange, 200, "capabilities.response", requestId, report);
            return;
        }
        if (REVOKE_PATH.equals(path)) {
            requireMessage(envelope, "pair.revoke.request");
            BridgeProtocolCodec.requireExactKeys(payload, "clientId");
            String targetClientId = BridgeProtocolCodec.requiredString(payload, "clientId", 40);
            if (!clientId.equals(targetClientId)) throw new BridgeAuthenticationService.ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
            authentication.revoke(clientId);
            JsonObject result = new JsonObject();
            result.addProperty("clientId", clientId);
            result.addProperty("revoked", true);
            sendEnvelope(exchange, 200, "pair.revoke.accepted", requestId, result);
            return;
        }
        if (EXECUTION_PATH.equals(path)) {
            // The legacy one-phase request cannot carry a user-confirmed preflight token and is never executed.
            requireMessage(envelope, "execution.request");
            ExecutionProtocol.RequestRejected rejected = new ExecutionProtocol.RequestRejected();
            rejected.requestId = requestId;
            rejected.reasonCode = BridgeProtocol.ErrorCode.UNSUPPORTED_ORIGIN;
            rejected.safeMessage = "Use the authenticated preflight and explicit confirmation flow supported by this client.";
            sendEnvelope(exchange, 200, "execution.rejected", requestId, rejected);
            return;
        }
        if (PREPARE_PATH.equals(path)) {
            requireMessage(envelope, "execution.prepare.request");
            String executionId = requestedExecutionId(payload, requestId);
            if (executionService == null) {
                sendPreflightRejected(exchange, requestId, executionId, BridgeProtocol.ErrorCode.CONSTRUCTION_DISABLED);
                return;
            }
            ConstructionCoordinator.PrepareResult result = executionService.prepare(clientId, payload, body.length);
            if (result.ready != null) {
                sendEnvelope(exchange, 200, "execution.preflight.ready", requestId, result.ready);
            } else if (result.existing != null) {
                sendEnvelope(exchange, 200, "execution.status.response", requestId, result.existing);
            } else {
                sendPreflightRejected(exchange, requestId, executionId, parseErrorCode(result.reasonCode), result.blockFailure);
            }
            return;
        }
        if (START_PATH.equals(path)) {
            requireMessage(envelope, "execution.start.request");
            BridgeProtocolCodec.requireExactKeys(payload, "executionId", "preflightToken");
            String executionId = BridgeProtocolCodec.requiredString(payload, "executionId", 36);
            String preflightToken = BridgeProtocolCodec.requiredString(payload, "preflightToken", 64);
            ConstructionCoordinator.StartResult result = executionService == null
                    ? ConstructionCoordinator.StartResult.rejected(BridgeProtocol.ErrorCode.CONSTRUCTION_DISABLED.name())
                    : executionService.start(clientId, executionId, preflightToken);
            if (result.accepted) {
                sendEnvelope(exchange, 200, "execution.accepted", requestId, result.snapshot);
            } else {
                sendStartRejected(exchange, requestId, executionId, parseErrorCode(result.reasonCode));
            }
            return;
        }
        if (STATUS_PATH.equals(path)) {
            requireMessage(envelope, "execution.status.request");
            BridgeProtocolCodec.requireExactKeys(payload, "executionId");
            String executionId = BridgeProtocolCodec.requiredString(payload, "executionId", 36);
            ExecutionProtocol.ExecutionSnapshot snapshot = executionService == null ? null
                    : executionService.status(clientId, executionId);
            if (snapshot == null) {
                JsonObject result = new JsonObject();
                result.addProperty("executionId", executionId);
                result.addProperty("reasonCode", BridgeProtocol.ErrorCode.EXECUTION_NOT_FOUND.name());
                sendEnvelope(exchange, 200, "execution.status.not_found", requestId, result);
            } else {
                sendEnvelope(exchange, 200, "execution.status.response", requestId, snapshot);
            }
            return;
        }
        if (CANCEL_PATH.equals(path)) {
            requireMessage(envelope, "execution.cancel.request");
            String executionId;
            if (payload.has("executionId")) {
                BridgeProtocolCodec.requireExactKeys(payload, "executionId");
                executionId = BridgeProtocolCodec.requiredString(payload, "executionId", 36);
            } else {
                // Phase 4 clients used this name; accept it only as an idempotent cancellation alias.
                BridgeProtocolCodec.requireExactKeys(payload, "executionRequestId");
                executionId = BridgeProtocolCodec.requiredString(payload, "executionRequestId", 64);
            }
            ConstructionCoordinator.CancelResult cancelled = executionService == null
                    ? ConstructionCoordinator.CancelResult.notFound() : executionService.cancel(clientId, executionId);
            ExecutionProtocol.CancellationResult result = new ExecutionProtocol.CancellationResult();
            result.executionId = executionId;
            result.outcome = cancelled.outcome;
            result.state = cancelled.state;
            result.reasonCode = cancelled.reasonCode;
            sendEnvelope(exchange, 200, "execution.cancellation.result", requestId, result);
            return;
        }
        writePlainError(exchange, 404);
    }

    private String requestedExecutionId(JsonObject payload, String fallback) {
        try {
            return BridgeProtocolCodec.requiredString(payload, "executionId", 36);
        } catch (BridgeProtocolException error) {
            return fallback;
        }
    }

    private void sendPreflightRejected(HttpExchange exchange, String requestId, String executionId,
                                       BridgeProtocol.ErrorCode reason) throws IOException {
        sendPreflightRejected(exchange, requestId, executionId, reason, null);
    }

    private void sendPreflightRejected(
            HttpExchange exchange,
            String requestId,
            String executionId,
            BridgeProtocol.ErrorCode reason,
            com.craftmind.bridge.protocol.BuildPlanContractValidator.BlockValidationFailure blockFailure) throws IOException {
        ExecutionProtocol.RequestRejected rejected = new ExecutionProtocol.RequestRejected();
        rejected.requestId = executionId;
        rejected.reasonCode = reason;
        rejected.safeMessage = safeMessage(reason);
        if (blockFailure != null && blockFailure.reasonCode == reason) {
            rejected.failedOperationIndex = blockFailure.operationIndex;
            rejected.blockId = blockFailure.blockId;
            rejected.unsupportedStateProperties.addAll(blockFailure.unsupportedStateProperties);
        }
        sendEnvelope(exchange, 200, "execution.preflight.rejected", requestId, rejected);
    }

    private void sendStartRejected(HttpExchange exchange, String requestId, String executionId,
                                   BridgeProtocol.ErrorCode reason) throws IOException {
        ExecutionProtocol.RequestRejected rejected = new ExecutionProtocol.RequestRejected();
        rejected.requestId = executionId;
        rejected.reasonCode = reason;
        rejected.safeMessage = safeMessage(reason);
        sendEnvelope(exchange, 200, "execution.start.rejected", requestId, rejected);
    }

    private BridgeProtocol.ErrorCode parseErrorCode(String value) {
        if (value == null) return BridgeProtocol.ErrorCode.INTERNAL_ERROR;
        try {
            return BridgeProtocol.ErrorCode.valueOf(value);
        } catch (IllegalArgumentException error) {
            return BridgeProtocol.ErrorCode.INTERNAL_ERROR;
        }
    }

    private String authorize(HttpExchange exchange, String path, byte[] body, BridgeEnvelope envelope)
            throws BridgeProtocolException, BridgeAuthenticationService.ServiceException {
        Headers headers = exchange.getRequestHeaders();
        String sessionId = singleHeader(headers, "X-CraftMind-Session-Id");
        String sequenceText = singleHeader(headers, "X-CraftMind-Sequence");
        String signature = singleHeader(headers, "X-CraftMind-Signature");
        final long sequence;
        try {
            sequence = Long.parseLong(sequenceText);
        } catch (NumberFormatException error) {
            throw new BridgeAuthenticationService.ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        }
        return authentication.authorize(sessionId, sequence, envelope.requestId, envelope.timestampEpochMillis,
                "POST", path, body, signature);
    }

    private String singleHeader(Headers headers, String name) throws BridgeAuthenticationService.ServiceException {
        java.util.List<String> values = headers.get(name);
        if (values == null || values.size() != 1 || values.get(0).length() > 256) {
            throw new BridgeAuthenticationService.ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        }
        return values.get(0);
    }

    private void checkTimestamp(long timestamp) throws BridgeProtocolException {
        long now = System.currentTimeMillis();
        if (timestamp < now - BridgeProtocol.MAX_CLOCK_SKEW_MILLIS || timestamp > now + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS) {
            throw new BridgeProtocolException(BridgeProtocol.ErrorCode.INVALID_TIMESTAMP);
        }
    }

    private void requireMessage(BridgeEnvelope envelope, String expected) throws BridgeProtocolException {
        if (!expected.equals(envelope.messageType)) throw malformed();
    }

    private byte[] readBounded(HttpExchange exchange, int maximumBytes) throws IOException, BridgeProtocolException {
        java.util.List<String> contentTypes = exchange.getRequestHeaders().get("Content-Type");
        if (contentTypes == null || contentTypes.size() != 1 ||
                !contentTypes.get(0).toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim().equals("application/json")) {
            throw malformed();
        }
        java.util.List<String> contentEncodings = exchange.getRequestHeaders().get("Content-Encoding");
        if (contentEncodings != null && (contentEncodings.size() != 1 || !"identity".equalsIgnoreCase(contentEncodings.get(0)))) {
            throw malformed();
        }
        java.util.List<String> contentLengths = exchange.getRequestHeaders().get("Content-Length");
        if (contentLengths != null && contentLengths.size() != 1) throw malformed();
        String contentLength = contentLengths == null ? null : contentLengths.get(0);
        if (contentLength != null) {
            try {
                long declaredLength = Long.parseLong(contentLength);
                if (declaredLength < 0L) throw malformed();
                if (declaredLength > maximumBytes) {
                    throw new BridgeProtocolException(BridgeProtocol.ErrorCode.PAYLOAD_TOO_LARGE);
                }
            } catch (NumberFormatException error) {
                throw malformed();
            }
        }
        byte[] scratch = new byte[maximumBytes + 1];
        int count = 0;
        try (InputStream input = exchange.getRequestBody()) {
            while (count < scratch.length) {
                int read = input.read(scratch, count, scratch.length - count);
                if (read < 0) break;
                count += read;
            }
            if (count > maximumBytes) throw new BridgeProtocolException(BridgeProtocol.ErrorCode.PAYLOAD_TOO_LARGE);
            if (count == 0) throw malformed();
            return Arrays.copyOf(scratch, count);
        } finally {
            BridgeCrypto.zero(scratch);
        }
    }

    private void sendEnvelope(HttpExchange exchange, int status, String messageType, String correlationId,
                              Object payload) throws IOException {
        try {
            JsonObject jsonPayload = payload instanceof JsonObject ? (JsonObject) payload : BridgeProtocolCodec.payload(payload);
            BridgeEnvelope envelope = BridgeProtocolCodec.newEnvelope(messageType, UUID.randomUUID().toString(),
                    System.currentTimeMillis(), correlationId, jsonPayload);
            writeResponse(exchange, status, BridgeProtocolCodec.writeEnvelope(envelope));
        } catch (BridgeProtocolException error) {
            throw new IOException("protocol response construction failed");
        }
    }

    private void sendError(HttpExchange exchange, int status, BridgeProtocol.ErrorCode code, String correlationId) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("reasonCode", code.name());
            payload.addProperty("safeMessage", safeMessage(code));
            JsonArray supported = new JsonArray();
            supported.add(BridgeProtocol.VERSION);
            payload.add("supportedProtocolVersions", supported);
            BridgeEnvelope envelope = BridgeProtocolCodec.newEnvelope("protocol.error", UUID.randomUUID().toString(),
                    System.currentTimeMillis(), correlationId, payload);
            writeResponse(exchange, status, BridgeProtocolCodec.writeEnvelope(envelope));
        } catch (Exception ignored) {
            writePlainError(exchange, status);
        }
    }

    private void writeResponse(HttpExchange exchange, int status, byte[] responseBody) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, responseBody.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(responseBody);
        } finally {
            BridgeCrypto.zero(responseBody);
        }
    }

    private void writePlainError(HttpExchange exchange, int status) {
        try {
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // Connection already closed.
        }
    }

    private int statusFor(BridgeProtocol.ErrorCode code) {
        switch (code) {
            case UNSUPPORTED_PROTOCOL: return 426;
            case PAYLOAD_TOO_LARGE: return 413;
            case RATE_LIMITED: return 429;
            case UNAUTHENTICATED:
            case PAIRING_CLOSED:
            case PAIRING_EXPIRED:
            case PAIRING_REJECTED:
            case ALREADY_PAIRED:
            case REPLAY_REJECTED: return 401;
            case INTERNAL_ERROR: return 500;
            default: return 400;
        }
    }

    private String safeMessage(BridgeProtocol.ErrorCode code) {
        switch (code) {
            case UNSUPPORTED_PROTOCOL: return "This bridge supports protocol version 2 only.";
            case UNAUTHENTICATED: return "Authenticate or pair this device before using this bridge route.";
            case PAIRING_CLOSED: return "An operator has not opened a pairing window.";
            case PAIRING_EXPIRED: return "The one-time pairing window expired.";
            case PAIRING_REJECTED: return "Pairing was rejected. Verify the current one-time code and retry.";
            case ALREADY_PAIRED: return "This Android identity is already trusted by this bridge.";
            case REPLAY_REJECTED: return "The request was stale, duplicated, or out of sequence.";
            case RATE_LIMITED: return "Too many requests. Wait before trying again.";
            case CONSTRUCTION_DISABLED: return "Construction is disabled by the operator or unsupported by this server setup.";
            case UNSUPPORTED_BLOCK: return "The requested block is unavailable or disallowed by this server.";
            case UNSUPPORTED_BLOCK_STATE: return "The requested block state is unavailable or disallowed by this server.";
            case ACTIVE_EXECUTION_EXISTS: return "Another build or unexpired preflight is active. Query it or wait for it to finish.";
            case EXECUTION_ID_CONFLICT: return "This execution ID was already used for a different plan or trusted device.";
            case EXECUTION_NOT_FOUND: return "No execution with that ID is available to this trusted device.";
            case PREFLIGHT_EXPIRED: return "The preflight expired. Prepare the plan again and review a new preview.";
            case PREFLIGHT_TOKEN_INVALID: return "The confirmation token is invalid or no longer active.";
            case ORIGIN_UNAVAILABLE: return "An operator must select a build origin in game before construction.";
            case ORIGIN_CHANGED: return "The in-game origin changed after preflight. Review a fresh preview.";
            case WORLD_SESSION_MISMATCH: return "The world session changed. Reconnect and prepare again.";
            case WORLD_UNAVAILABLE: return "The selected Minecraft world is not available.";
            case WORLD_BOUNDS_REJECTED: return "At least one target is outside the world height or border.";
            case CHUNK_NOT_LOADED: return "At least one target chunk is not loaded; the bridge will not load it automatically.";
            case BLOCK_OCCUPIED: return "At least one target block is occupied; no blocks were overwritten during preflight.";
            case ENTITY_IN_BUILD_AREA: return "Move all players and entities clear of the proposed build area, then preflight again.";
            case SPAWN_PROTECTED: return "The proposed build overlaps vanilla spawn protection.";
            case PROTECTED_REGION: return "A placement protection rule rejected the target.";
            case EXECUTION_STORE_UNAVAILABLE: return "Execution status could not be persisted, so the bridge will not start construction.";
            case SERVER_RESTARTED: return "The Minecraft server restarted; this execution was marked failed and was not resumed.";
            case EXECUTION_TIMEOUT: return "The execution exceeded the configured time limit.";
            case PLACEMENT_REJECTED: return "Minecraft rejected a block placement; earlier placements were not rolled back.";
            default: return "The request was rejected by the bridge.";
        }
    }

    private BridgeProtocolException malformed() {
        return new BridgeProtocolException(BridgeProtocol.ErrorCode.MALFORMED_ENVELOPE);
    }

    private static ThreadFactory namedFactory(String name) {
        AtomicInteger id = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + id.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
