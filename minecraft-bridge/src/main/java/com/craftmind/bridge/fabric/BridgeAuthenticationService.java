package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import com.craftmind.bridge.protocol.BridgeProtocol;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Pairing, one-use challenges, signed sessions, replay rejection, and remote revocation. */
public final class BridgeAuthenticationService {
    private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9 ._()-]{1,48}");
    private static final int MAX_OUTSTANDING_CHALLENGES = 128;
    private static final int MAX_ACTIVE_SESSIONS = 64;
    private final BridgeIdentity identity;
    private final TrustedClientRepository clients;
    private final SecureRandom random;
    private final LongSupplier clock;
    private final Map<String, Challenge> challenges = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private PairingWindow pairingWindow;

    public BridgeAuthenticationService(BridgeIdentity identity, TrustedClientRepository clients) {
        this(identity, clients, new SecureRandom(), System::currentTimeMillis);
    }

    BridgeAuthenticationService(
            BridgeIdentity identity,
            TrustedClientRepository clients,
            SecureRandom random,
            LongSupplier clock) {
        this.identity = identity;
        this.clients = clients;
        this.random = random;
        this.clock = clock;
    }

    /** Creates a one-time high-entropy code held in memory only; caller must deliver it privately to an operator. */
    public synchronized PairingCode openPairing() throws ServiceException {
        clearPairingWindow();
        byte[] raw = new byte[32];
        try {
            random.nextBytes(raw);
            String code = BridgeCrypto.base64Url(raw);
            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            byte[] codeHash = BridgeCrypto.sha256(codeBytes);
            BridgeCrypto.zero(codeBytes);
            long now = clock.getAsLong();
            pairingWindow = new PairingWindow(codeHash, now, now + BridgeProtocol.PAIRING_WINDOW_MILLIS);
            return new PairingCode(code, pairingWindow.expiresAtEpochMillis);
        } catch (GeneralSecurityException error) {
            throw new ServiceException(BridgeProtocol.ErrorCode.INTERNAL_ERROR);
        } finally {
            BridgeCrypto.zero(raw);
        }
    }

    public synchronized PairingAccepted pair(PairingRequest request) throws ServiceException {
        long now = clock.getAsLong();
        PairingWindow pending = pairingWindow;
        if (pending == null) throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_CLOSED);
        if (now < pending.createdAtEpochMillis || now > pending.expiresAtEpochMillis) {
            clearPairingWindow();
            throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_EXPIRED);
        }
        if (request == null || request.pairingCode == null || request.pairingCode.length() > 64 ||
                request.displayName == null || !DISPLAY_NAME.matcher(request.displayName).matches() ||
                request.issuedAtEpochMillis < now - BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                request.issuedAtEpochMillis > now + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS) {
            throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_REJECTED);
        }
        byte[] suppliedCode = request.pairingCode.getBytes(StandardCharsets.UTF_8);
        byte[] suppliedHash;
        try {
            suppliedHash = BridgeCrypto.sha256(suppliedCode);
        } catch (GeneralSecurityException error) {
            BridgeCrypto.zero(suppliedCode);
            throw new ServiceException(BridgeProtocol.ErrorCode.INTERNAL_ERROR);
        }
        BridgeCrypto.zero(suppliedCode);
        if (!java.security.MessageDigest.isEqual(pending.codeHash, suppliedHash)) {
            BridgeCrypto.zero(suppliedHash);
            throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_REJECTED);
        }
        BridgeCrypto.zero(suppliedHash);

        byte[] publicKey = null;
        byte[] proofSignature = null;
        byte[] nonce = null;
        try {
            publicKey = BridgeCrypto.decodeBase64Url(request.publicKeyBase64Url, 1024);
            proofSignature = BridgeCrypto.decodeBase64Url(request.proofSignatureBase64Url, 256);
            nonce = BridgeCrypto.decodeBase64Url(request.clientNonce, 128);
            if (nonce.length < 16 || !BridgeCrypto.clientId(publicKey).equals(request.clientId)) {
                throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_REJECTED);
            }
            if (clients.find(request.clientId) != null) throw new ServiceException(BridgeProtocol.ErrorCode.ALREADY_PAIRED);
            byte[] proof = BridgeCrypto.pairingProof(identity.bridgeId(), request.clientId,
                    request.issuedAtEpochMillis, request.clientNonce, publicKey);
            boolean verified;
            try {
                verified = BridgeCrypto.verifyP256Signature(publicKey, proof, proofSignature);
            } finally {
                BridgeCrypto.zero(proof);
            }
            if (!verified) throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_REJECTED);
            clients.add(new TrustedBridgeClient(request.clientId, request.displayName, request.publicKeyBase64Url, now));
            clearPairingWindow();
            return new PairingAccepted(request.clientId, now);
        } catch (ServiceException error) {
            throw error;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ServiceException(BridgeProtocol.ErrorCode.PAIRING_REJECTED);
        } catch (Exception error) {
            throw new ServiceException(BridgeProtocol.ErrorCode.INTERNAL_ERROR);
        } finally {
            BridgeCrypto.zero(publicKey);
            BridgeCrypto.zero(proofSignature);
            BridgeCrypto.zero(nonce);
        }
    }

    public synchronized ChallengeResponse issueChallenge(String clientId) throws ServiceException {
        if (clients.find(clientId) == null) throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        cleanupExpired();
        if (challenges.size() >= MAX_OUTSTANDING_CHALLENGES) throw new ServiceException(BridgeProtocol.ErrorCode.RATE_LIMITED);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String challengeId = UUID.randomUUID().toString();
        String nonceText = BridgeCrypto.base64Url(nonce);
        BridgeCrypto.zero(nonce);
        long now = clock.getAsLong();
        long expiresAt = now + BridgeProtocol.AUTH_CHALLENGE_MILLIS;
        challenges.put(challengeId, new Challenge(clientId, nonceText, now, expiresAt));
        return new ChallengeResponse(challengeId, nonceText, expiresAt);
    }

    public synchronized SessionCreated authenticate(SessionRequest request) throws ServiceException {
        Challenge challenge = request == null ? null : challenges.remove(request.challengeId);
        long now = clock.getAsLong();
        if (challenge == null || now < challenge.createdAtEpochMillis || now > challenge.expiresAtEpochMillis || request == null ||
                !challenge.clientId.equals(request.clientId) || clients.find(request.clientId) == null ||
                request.issuedAtEpochMillis < now - BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                request.issuedAtEpochMillis > now + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                !challenge.nonce.equals(request.challengeNonce)) {
            throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        }
        if (sessions.size() >= MAX_ACTIVE_SESSIONS) cleanupExpired();
        if (sessions.size() >= MAX_ACTIVE_SESSIONS) throw new ServiceException(BridgeProtocol.ErrorCode.RATE_LIMITED);
        TrustedBridgeClient client = clients.find(request.clientId);
        byte[] key = null;
        byte[] proofSignature = null;
        byte[] nonce = null;
        try {
            key = BridgeCrypto.decodeBase64Url(client.publicKeyBase64Url, 1024);
            proofSignature = BridgeCrypto.decodeBase64Url(request.proofSignatureBase64Url, 256);
            nonce = BridgeCrypto.decodeBase64Url(request.challengeNonce, 128);
            if (nonce.length < 16) throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
            byte[] proof = BridgeCrypto.sessionProof(identity.bridgeId(), request.clientId,
                    request.challengeId, request.challengeNonce, request.issuedAtEpochMillis);
            boolean verified;
            try {
                verified = BridgeCrypto.verifyP256Signature(key, proof, proofSignature);
            } finally {
                BridgeCrypto.zero(proof);
            }
            if (!verified) throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
            byte[] rawSessionId = new byte[32];
            random.nextBytes(rawSessionId);
            String sessionId = BridgeCrypto.base64Url(rawSessionId);
            BridgeCrypto.zero(rawSessionId);
            Session session = new Session(request.clientId, now);
            sessions.put(sessionId, session);
            return new SessionCreated(sessionId, now + BridgeProtocol.SESSION_MAX_AGE_MILLIS);
        } catch (ServiceException error) {
            throw error;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        } finally {
            BridgeCrypto.zero(key);
            BridgeCrypto.zero(proofSignature);
            BridgeCrypto.zero(nonce);
        }
    }

    /** Signature covers exact body bytes, request id, route, timestamp and strictly increasing sequence. */
    public synchronized String authorize(
            String sessionId,
            long sequence,
            String requestId,
            long timestampEpochMillis,
            String method,
            String path,
            byte[] exactBody,
            String signatureBase64Url) throws ServiceException {
        long now = clock.getAsLong();
        Session session = sessions.get(sessionId);
        if (session == null || now < session.createdAtEpochMillis || now < session.lastActivityEpochMillis ||
                now - session.createdAtEpochMillis > BridgeProtocol.SESSION_MAX_AGE_MILLIS ||
                now - session.lastActivityEpochMillis > BridgeProtocol.SESSION_IDLE_TIMEOUT_MILLIS) {
            if (session != null) sessions.remove(sessionId);
            throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        }
        if (sequence < 1 || sequence <= session.lastSequence) throw new ServiceException(BridgeProtocol.ErrorCode.REPLAY_REJECTED);
        if (timestampEpochMillis < now - BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                timestampEpochMillis > now + BridgeProtocol.MAX_CLOCK_SKEW_MILLIS ||
                requestId == null || !requestId.matches("[0-9a-fA-F-]{36}") ||
                method == null || path == null || exactBody == null) {
            throw new ServiceException(BridgeProtocol.ErrorCode.REPLAY_REJECTED);
        }
        TrustedBridgeClient client = clients.find(session.clientId);
        if (client == null) {
            sessions.remove(sessionId);
            throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        }
        byte[] key = null;
        byte[] signature = null;
        byte[] proof = null;
        try {
            key = BridgeCrypto.decodeBase64Url(client.publicKeyBase64Url, 1024);
            signature = BridgeCrypto.decodeBase64Url(signatureBase64Url, 256);
            proof = BridgeCrypto.requestProof(identity.bridgeId(), sessionId, sequence, requestId,
                    timestampEpochMillis, method, path, exactBody);
            if (!BridgeCrypto.verifyP256Signature(key, proof, signature)) {
                throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
            }
            session.lastSequence = sequence;
            session.lastActivityEpochMillis = now;
            return session.clientId;
        } catch (ServiceException error) {
            throw error;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        } finally {
            BridgeCrypto.zero(key);
            BridgeCrypto.zero(signature);
            BridgeCrypto.zero(proof);
        }
    }

    /** Closes exactly one authenticated session without removing the durable trusted public key. */
    public synchronized void disconnect(String sessionId, String clientId) throws ServiceException {
        Session session = sessions.get(sessionId);
        if (session == null || !session.clientId.equals(clientId)) {
            throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
        }
        sessions.remove(sessionId);
    }

    /** Must be called only after an authenticated revocation envelope has passed authorize(). */
    public synchronized boolean revoke(String clientId) throws ServiceException {
        try {
            boolean removed = clients.remove(clientId);
            if (!removed) throw new ServiceException(BridgeProtocol.ErrorCode.UNAUTHENTICATED);
            Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                if (clientId.equals(iterator.next().getValue().clientId)) iterator.remove();
            }
            challenges.values().removeIf(challenge -> clientId.equals(challenge.clientId));
            return true;
        } catch (ServiceException error) {
            throw error;
        } catch (Exception error) {
            throw new ServiceException(BridgeProtocol.ErrorCode.INTERNAL_ERROR);
        }
    }

    public synchronized void closePairing() {
        clearPairingWindow();
    }

    public synchronized void close() {
        clearPairingWindow();
        challenges.clear();
        sessions.clear();
    }

    private void cleanupExpired() {
        long now = clock.getAsLong();
        challenges.values().removeIf(challenge -> now < challenge.createdAtEpochMillis || now > challenge.expiresAtEpochMillis);
        Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next().getValue();
            if (now < session.createdAtEpochMillis || now < session.lastActivityEpochMillis ||
                    now - session.createdAtEpochMillis > BridgeProtocol.SESSION_MAX_AGE_MILLIS ||
                    now - session.lastActivityEpochMillis > BridgeProtocol.SESSION_IDLE_TIMEOUT_MILLIS ||
                    clients.find(session.clientId) == null) iterator.remove();
        }
    }

    private void clearPairingWindow() {
        if (pairingWindow != null) BridgeCrypto.zero(pairingWindow.codeHash);
        pairingWindow = null;
    }

    public static final class PairingCode {
        public final String value;
        public final long expiresAtEpochMillis;
        private PairingCode(String value, long expiresAtEpochMillis) {
            this.value = value;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    public static final class PairingRequest {
        public String pairingCode;
        public String clientId;
        public String displayName;
        public String publicKeyBase64Url;
        public String clientNonce;
        public long issuedAtEpochMillis;
        public String proofSignatureBase64Url;
    }

    public static final class PairingAccepted {
        public final String clientId;
        public final long pairedAtEpochMillis;
        private PairingAccepted(String clientId, long pairedAtEpochMillis) {
            this.clientId = clientId;
            this.pairedAtEpochMillis = pairedAtEpochMillis;
        }
    }

    public static final class ChallengeResponse {
        public final String challengeId;
        public final String nonce;
        public final long expiresAtEpochMillis;
        private ChallengeResponse(String challengeId, String nonce, long expiresAtEpochMillis) {
            this.challengeId = challengeId;
            this.nonce = nonce;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    public static final class SessionRequest {
        public String clientId;
        public String challengeId;
        public String challengeNonce;
        public long issuedAtEpochMillis;
        public String proofSignatureBase64Url;
    }

    public static final class SessionCreated {
        public final String sessionId;
        public final long expiresAtEpochMillis;
        private SessionCreated(String sessionId, long expiresAtEpochMillis) {
            this.sessionId = sessionId;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    public static final class ServiceException extends Exception {
        private final BridgeProtocol.ErrorCode code;
        public ServiceException(BridgeProtocol.ErrorCode code) { super(code.name()); this.code = code; }
        public BridgeProtocol.ErrorCode getCode() { return code; }
    }

    private static final class PairingWindow {
        private final byte[] codeHash;
        private final long createdAtEpochMillis;
        private final long expiresAtEpochMillis;
        private PairingWindow(byte[] codeHash, long createdAtEpochMillis, long expiresAtEpochMillis) {
            this.codeHash = codeHash;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    private static final class Challenge {
        private final String clientId;
        private final String nonce;
        private final long createdAtEpochMillis;
        private final long expiresAtEpochMillis;
        private Challenge(String clientId, String nonce, long createdAtEpochMillis, long expiresAtEpochMillis) {
            this.clientId = clientId;
            this.nonce = nonce;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    private static final class Session {
        private final String clientId;
        private final long createdAtEpochMillis;
        private long lastActivityEpochMillis;
        private long lastSequence;
        private Session(String clientId, long createdAtEpochMillis) {
            this.clientId = clientId;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.lastActivityEpochMillis = createdAtEpochMillis;
        }
    }
}
