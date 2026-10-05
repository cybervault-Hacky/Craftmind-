package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;

/** Public identity and TLS context; the private key remains encapsulated by the encrypted keystore/TLS key manager. */
public final class BridgeIdentity {
    private final String bridgeId;
    private final String fingerprint;
    private final X509Certificate certificate;
    private final SSLContext sslContext;

    BridgeIdentity(X509Certificate certificate, SSLContext sslContext) throws Exception {
        this.certificate = certificate;
        this.sslContext = sslContext;
        this.fingerprint = BridgeCrypto.certificateFingerprint(certificate.getEncoded());
        this.bridgeId = "bridge-" + BridgeCrypto.sha256Hex(certificate.getPublicKey().getEncoded())
                .substring(0, 32).toLowerCase(java.util.Locale.ROOT);
    }

    public String bridgeId() { return bridgeId; }
    public String fingerprint() { return fingerprint; }
    public X509Certificate certificate() { return certificate; }
    public SSLContext sslContext() { return sslContext; }
}
