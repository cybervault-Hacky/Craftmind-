package com.craftmind.bridge.fabric;

import com.craftmind.bridge.protocol.BridgeCrypto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates/loads a TLS identity whose private key is encrypted in PKCS12 using an external passphrase. */
public final class BridgeIdentityStore {
    private static final String KEY_ALIAS = "craftmind-bridge-identity";
    private static final int MIN_PASSWORD_LENGTH = 16;
    private static final long MAX_KEYSTORE_BYTES = 1024L * 1024L;
    private static final long CERTIFICATE_VALIDITY_MILLIS = 10L * 365L * 24L * 60L * 60L * 1000L;
    private final SecureRandom secureRandom;

    public BridgeIdentityStore() {
        this(new SecureRandom());
    }

    BridgeIdentityStore(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public BridgeIdentity loadOrCreate(CraftMindBridgeConfig config) throws IdentityStoreException {
        String environmentValue = System.getenv(CraftMindBridgeConfig.KEYSTORE_PASSWORD_ENV);
        if (environmentValue == null || environmentValue.length() < MIN_PASSWORD_LENGTH) {
            throw new IdentityStoreException("IDENTITY_PASSWORD_REQUIRED");
        }
        char[] password = environmentValue.toCharArray();
        byte[] encryptedStore = null;
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            if (Files.exists(config.keyStorePath())) {
                if (Files.isSymbolicLink(config.keyStorePath()) ||
                        !Files.isRegularFile(config.keyStorePath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                        Files.size(config.keyStorePath()) < 1L || Files.size(config.keyStorePath()) > MAX_KEYSTORE_BYTES) {
                    throw new IdentityStoreException("IDENTITY_STORE_INVALID");
                }
                try (InputStream input = Files.newInputStream(config.keyStorePath())) {
                    keyStore.load(input, password);
                }
            } else {
                createKeyStore(keyStore, config, password);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                keyStore.store(output, password);
                encryptedStore = output.toByteArray();
                writeAtomically(config.keyStorePath(), encryptedStore);
            }
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(
                    KEY_ALIAS, new KeyStore.PasswordProtection(password));
            if (entry == null || !(entry.getCertificate() instanceof X509Certificate)) {
                throw new IdentityStoreException("IDENTITY_STORE_INVALID");
            }
            X509Certificate certificate = (X509Certificate) entry.getCertificate();
            verifyCertificate(certificate, entry.getPrivateKey(), config.bindAddress().getHostAddress());
            SSLContext context = createTlsContext(keyStore, password);
            return new BridgeIdentity(certificate, context);
        } catch (IdentityStoreException error) {
            throw error;
        } catch (Exception error) {
            throw new IdentityStoreException("IDENTITY_STORE_UNAVAILABLE", error);
        } finally {
            BridgeCrypto.zero(password);
            BridgeCrypto.zero(encryptedStore);
        }
    }

    private void createKeyStore(KeyStore keyStore, CraftMindBridgeConfig config, char[] password) throws Exception {
        keyStore.load(null, password);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), secureRandom);
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = createCertificate(pair, config.bindAddress().getHostAddress());
        keyStore.setKeyEntry(KEY_ALIAS, pair.getPrivate(), password, new Certificate[]{certificate});
    }

    private X509Certificate createCertificate(KeyPair pair, String bindAddress) throws Exception {
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=CraftMind Bridge");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(160, secureRandom).setBit(159),
                new Date(now - 60_000L),
                new Date(now + CERTIFICATE_VALIDITY_MILLIS),
                subject,
                pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, bindAddress)));
        builder.addExtension(Extension.basicConstraints, true, new org.bouncycastle.asn1.x509.BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
        certificate.verify(pair.getPublic());
        return certificate;
    }

    private void verifyCertificate(X509Certificate certificate, java.security.PrivateKey privateKey, String bindAddress) throws Exception {
        certificate.checkValidity();
        certificate.verify(certificate.getPublicKey());
        if (!"EC".equalsIgnoreCase(certificate.getPublicKey().getAlgorithm()) ||
                !BridgeCrypto.isP256PublicKey(certificate.getPublicKey().getEncoded())) {
            throw new IdentityStoreException("IDENTITY_STORE_INVALID");
        }
        boolean sanMatches = false;
        List<List<?>> alternativeNames = certificate.getSubjectAlternativeNames();
        if (alternativeNames != null) {
            for (List<?> name : alternativeNames) {
                if (name.size() == 2 && Integer.valueOf(7).equals(name.get(0)) && bindAddress.equals(name.get(1))) {
                    sanMatches = true;
                    break;
                }
            }
        }
        if (!sanMatches) throw new IdentityStoreException("IDENTITY_ADDRESS_CHANGED_REQUIRES_REPAIR");
        byte[] challenge = new byte[32];
        secureRandom.nextBytes(challenge);
        byte[] proof = null;
        try {
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(challenge);
            proof = signer.sign();
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            if (!verifier.verify(proof)) throw new IdentityStoreException("IDENTITY_STORE_INVALID");
        } finally {
            Arrays.fill(challenge, (byte) 0);
            BridgeCrypto.zero(proof);
        }
    }

    private SSLContext createTlsContext(KeyStore keyStore, char[] password) throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, secureRandom);
        return context;
    }

    private void writeAtomically(Path target, byte[] encryptedStore) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "bridge-identity-", ".tmp");
        try {
            setPrivateFilePermissions(temporary);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(encryptedStore);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void setPrivateFilePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs inherit from the operator-owned config directory.
        }
    }

    public static final class IdentityStoreException extends Exception {
        private final String code;
        public IdentityStoreException(String code) { super(code); this.code = code; }
        public IdentityStoreException(String code, Throwable cause) { super(code, cause); this.code = code; }
        public String getCode() { return code; }
    }
}
