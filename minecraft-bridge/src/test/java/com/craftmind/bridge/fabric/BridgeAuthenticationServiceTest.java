package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import com.craftmind.bridge.protocol.BridgeProtocol;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BridgeAuthenticationServiceTest {
    @Test
    public void pairsAuthenticatesSignsOrderedRequestsRejectsReplayAndRevokesClient() throws Exception {
        long[] now = {1_700_000_000_000L};
        SecureRandom random = new SecureRandom();
        BridgeIdentity bridgeIdentity = testIdentity(random);
        MemoryClients clients = new MemoryClients();
        BridgeAuthenticationService service = new BridgeAuthenticationService(bridgeIdentity, clients, random, () -> now[0]);
        ClientKeys phone = clientKeys();

        BridgeAuthenticationService.PairingCode code = service.openPairing();
        BridgeAuthenticationService.PairingAccepted paired = service.pair(pairingRequest(bridgeIdentity, phone, code.value, now[0]));
        assertEquals(phone.clientId, paired.clientId);
        assertEquals(1, clients.all().size());

        BridgeAuthenticationService.ChallengeResponse challenge = service.issueChallenge(phone.clientId);
        BridgeAuthenticationService.SessionRequest login = sessionRequest(bridgeIdentity, phone, challenge, now[0]);
        BridgeAuthenticationService.SessionCreated session = service.authenticate(login);
        assertFalse(session.sessionId.isEmpty());
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED, () -> service.authenticate(login));

        String requestId = java.util.UUID.randomUUID().toString();
        byte[] body = "{\"protocolVersion\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] requestProof = BridgeCrypto.requestProof(bridgeIdentity.bridgeId(), session.sessionId, 1,
                requestId, now[0], "POST", "/v1/capabilities", body);
        String requestSignature = sign(phone.keyPair, requestProof);
        assertEquals(phone.clientId, service.authorize(session.sessionId, 1, requestId, now[0],
                "POST", "/v1/capabilities", body, requestSignature));
        expectCode(BridgeProtocol.ErrorCode.REPLAY_REJECTED, () -> service.authorize(session.sessionId, 1, requestId, now[0],
                "POST", "/v1/capabilities", body, requestSignature));

        now[0]--;
        String rollbackRequestId = java.util.UUID.randomUUID().toString();
        byte[] rollbackBody = "{\"protocolVersion\":1,\"sequence\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] rollbackProof = BridgeCrypto.requestProof(bridgeIdentity.bridgeId(), session.sessionId, 2,
                rollbackRequestId, now[0], "POST", "/v1/capabilities", rollbackBody);
        String rollbackSignature = sign(phone.keyPair, rollbackProof);
        BridgeCrypto.zero(rollbackProof);
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED, () -> service.authorize(session.sessionId, 2,
                rollbackRequestId, now[0], "POST", "/v1/capabilities", rollbackBody, rollbackSignature));
        now[0]++;

        service.revoke(phone.clientId);
        assertTrue(clients.all().isEmpty());
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED, () -> service.issueChallenge(phone.clientId));
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED, () -> service.authorize(session.sessionId, 2, requestId,
                now[0], "POST", "/v1/capabilities", body, requestSignature));
    }

    @Test
    public void rejectsWrongPairingCodeExpiredPairingAndExpiredChallenge() throws Exception {
        long[] now = {1_700_000_000_000L};
        SecureRandom random = new SecureRandom();
        BridgeIdentity bridgeIdentity = testIdentity(random);
        MemoryClients clients = new MemoryClients();
        BridgeAuthenticationService service = new BridgeAuthenticationService(bridgeIdentity, clients, random, () -> now[0]);
        ClientKeys phone = clientKeys();
        BridgeAuthenticationService.PairingCode code = service.openPairing();
        expectCode(BridgeProtocol.ErrorCode.PAIRING_REJECTED,
                () -> service.pair(pairingRequest(bridgeIdentity, phone, "incorrect-code", now[0])));
        now[0] += BridgeProtocol.PAIRING_WINDOW_MILLIS + 1;
        expectCode(BridgeProtocol.ErrorCode.PAIRING_EXPIRED,
                () -> service.pair(pairingRequest(bridgeIdentity, phone, code.value, now[0])));

        now[0] = 1_700_000_000_000L;
        BridgeAuthenticationService.PairingCode next = service.openPairing();
        service.pair(pairingRequest(bridgeIdentity, phone, next.value, now[0]));
        BridgeAuthenticationService.ChallengeResponse challenge = service.issueChallenge(phone.clientId);
        now[0] += BridgeProtocol.AUTH_CHALLENGE_MILLIS + 1;
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED,
                () -> service.authenticate(sessionRequest(bridgeIdentity, phone, challenge, now[0])));
    }

    @Test
    public void expiresSessionsAtIdleAndMaximumAgeBounds() throws Exception {
        long[] now = {1_700_000_000_000L};
        SecureRandom random = new SecureRandom();
        BridgeIdentity bridgeIdentity = testIdentity(random);
        MemoryClients clients = new MemoryClients();
        BridgeAuthenticationService service = new BridgeAuthenticationService(bridgeIdentity, clients, random, () -> now[0]);
        ClientKeys phone = clientKeys();
        BridgeAuthenticationService.PairingCode code = service.openPairing();
        service.pair(pairingRequest(bridgeIdentity, phone, code.value, now[0]));

        BridgeAuthenticationService.ChallengeResponse idleChallenge = service.issueChallenge(phone.clientId);
        BridgeAuthenticationService.SessionCreated idleSession = service.authenticate(
                sessionRequest(bridgeIdentity, phone, idleChallenge, now[0]));
        now[0] += BridgeProtocol.SESSION_IDLE_TIMEOUT_MILLIS + 1;
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED,
                () -> service.authorize(idleSession.sessionId, 1, java.util.UUID.randomUUID().toString(), now[0],
                        "POST", "/v1/capabilities", new byte[]{1}, "invalid"));

        BridgeAuthenticationService.ChallengeResponse ageChallenge = service.issueChallenge(phone.clientId);
        BridgeAuthenticationService.SessionCreated ageSession = service.authenticate(
                sessionRequest(bridgeIdentity, phone, ageChallenge, now[0]));
        now[0] += BridgeProtocol.SESSION_MAX_AGE_MILLIS + 1;
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED,
                () -> service.authorize(ageSession.sessionId, 1, java.util.UUID.randomUUID().toString(), now[0],
                        "POST", "/v1/capabilities", new byte[]{1}, "invalid"));
    }

    @Test
    public void certificateFingerprintDetectsChangedIdentity() throws Exception {
        SecureRandom random = new SecureRandom();
        BridgeIdentity first = testIdentity(random);
        BridgeIdentity second = testIdentity(random);
        assertTrue(BridgeCrypto.fingerprintMatches(first.certificate().getEncoded(), first.fingerprint()));
        assertFalse(BridgeCrypto.fingerprintMatches(second.certificate().getEncoded(), first.fingerprint()));
    }

    @Test
    public void disconnectClosesSessionButKeepsPairingAndRejectsFurtherRequests() throws Exception {
        long now = 1_700_000_000_000L;
        SecureRandom random = new SecureRandom();
        BridgeIdentity bridgeIdentity = testIdentity(random);
        MemoryClients clients = new MemoryClients();
        BridgeAuthenticationService service = new BridgeAuthenticationService(bridgeIdentity, clients, random, () -> now);
        ClientKeys phone = clientKeys();
        BridgeAuthenticationService.PairingCode code = service.openPairing();
        service.pair(pairingRequest(bridgeIdentity, phone, code.value, now));
        BridgeAuthenticationService.ChallengeResponse challenge = service.issueChallenge(phone.clientId);
        BridgeAuthenticationService.SessionCreated session = service.authenticate(
                sessionRequest(bridgeIdentity, phone, challenge, now));

        service.disconnect(session.sessionId, phone.clientId);
        assertEquals(1, clients.all().size());
        expectCode(BridgeProtocol.ErrorCode.UNAUTHENTICATED,
                () -> service.authorize(session.sessionId, 1, java.util.UUID.randomUUID().toString(), now,
                        "POST", "/v1/capabilities", new byte[]{1}, "invalid"));
        assertNotNull(service.issueChallenge(phone.clientId));
    }

    private static BridgeAuthenticationService.PairingRequest pairingRequest(
            BridgeIdentity identity, ClientKeys client, String code, long now) throws Exception {
        BridgeAuthenticationService.PairingRequest request = new BridgeAuthenticationService.PairingRequest();
        request.pairingCode = code;
        request.clientId = client.clientId;
        request.displayName = "Android phone";
        request.publicKeyBase64Url = BridgeCrypto.base64Url(client.keyPair.getPublic().getEncoded());
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        request.clientNonce = BridgeCrypto.base64Url(nonce);
        BridgeCrypto.zero(nonce);
        request.issuedAtEpochMillis = now;
        byte[] proof = BridgeCrypto.pairingProof(identity.bridgeId(), client.clientId, now,
                request.clientNonce, client.keyPair.getPublic().getEncoded());
        request.proofSignatureBase64Url = sign(client.keyPair, proof);
        BridgeCrypto.zero(proof);
        return request;
    }

    private static BridgeAuthenticationService.SessionRequest sessionRequest(
            BridgeIdentity identity, ClientKeys client,
            BridgeAuthenticationService.ChallengeResponse challenge, long now) throws Exception {
        BridgeAuthenticationService.SessionRequest request = new BridgeAuthenticationService.SessionRequest();
        request.clientId = client.clientId;
        request.challengeId = challenge.challengeId;
        request.challengeNonce = challenge.nonce;
        request.issuedAtEpochMillis = now;
        byte[] proof = BridgeCrypto.sessionProof(identity.bridgeId(), client.clientId,
                challenge.challengeId, challenge.nonce, now);
        request.proofSignatureBase64Url = sign(client.keyPair, proof);
        BridgeCrypto.zero(proof);
        return request;
    }

    private static String sign(KeyPair pair, byte[] message) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate());
        signature.update(message);
        return BridgeCrypto.base64Url(signature.sign());
    }

    private static ClientKeys clientKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return new ClientKeys(pair, BridgeCrypto.clientId(pair.getPublic().getEncoded()));
    }

    private static BridgeIdentity testIdentity(SecureRandom random) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair pair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=CraftMind Bridge Test");
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now),
                new Date(now - 60_000), new Date(now + 86_400_000), subject, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
        X509Certificate certificate = new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA")
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(pair.getPrivate())));
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, random);
        return new BridgeIdentity(certificate, context);
    }

    private static void expectCode(BridgeProtocol.ErrorCode code, CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (BridgeAuthenticationService.ServiceException error) {
            assertEquals(code, error.getCode());
            return;
        }
        throw new AssertionError("Expected " + code);
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class ClientKeys {
        private final KeyPair keyPair;
        private final String clientId;
        private ClientKeys(KeyPair keyPair, String clientId) { this.keyPair = keyPair; this.clientId = clientId; }
    }

    private static final class MemoryClients implements TrustedClientRepository {
        private final Map<String, TrustedBridgeClient> clients = new HashMap<>();
        @Override public TrustedBridgeClient find(String id) { return clients.get(id); }
        @Override public List<TrustedBridgeClient> all() { return new ArrayList<>(clients.values()); }
        @Override public void add(TrustedBridgeClient client) { clients.put(client.clientId, client); }
        @Override public boolean remove(String id) { return clients.remove(id) != null; }
    }
}
