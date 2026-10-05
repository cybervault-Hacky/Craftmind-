package com.craftmind.bridge.protocol;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeCryptoTest {
    @Test
    public void verifiesOnlyP256EcdsaAndRejectsOtherEcCurves() throws Exception {
        KeyPair p256 = keyPair("secp256r1");
        byte[] message = "CraftMind Bridge request proof".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(p256.getPrivate());
        signer.update(message);
        byte[] signature = signer.sign();
        assertTrue(BridgeCrypto.isP256PublicKey(p256.getPublic().getEncoded()));
        assertTrue(BridgeCrypto.verifyP256Signature(p256.getPublic().getEncoded(), message, signature));
        assertFalse(BridgeCrypto.verifyP256Signature(p256.getPublic().getEncoded(),
                "different body".getBytes(StandardCharsets.UTF_8), signature));

        KeyPair p384 = keyPair("secp384r1");
        assertFalse(BridgeCrypto.isP256PublicKey(p384.getPublic().getEncoded()));
        assertFalse(BridgeCrypto.verifyP256Signature(p384.getPublic().getEncoded(), message, signature));
    }

    @Test
    public void normalizesCertificateFingerprintsWithoutAcceptingMalformedValues() {
        String plain = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        assertEquals(plain, BridgeCrypto.normalizeFingerprint(plain.toLowerCase()));
        assertEquals(plain, BridgeCrypto.normalizeFingerprint("01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF"));
        assertEquals("", BridgeCrypto.normalizeFingerprint("not-a-fingerprint"));
    }

    private static KeyPair keyPair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }
}
