package com.craftmind.bridge.protocol;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECFieldFp;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical signing strings and standard JCA primitives shared by both protocol endpoints. */
public final class BridgeCrypto {
    private static final Pattern HEX_FINGERPRINT = Pattern.compile("[0-9A-F]{64}");
    private static final Pattern CLIENT_ID = Pattern.compile("cm-[0-9a-f]{24}");

    private BridgeCrypto() { }

    public static byte[] sha256(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    public static String sha256Hex(byte[] value) throws GeneralSecurityException {
        return toHex(sha256(value));
    }

    public static String certificateFingerprint(byte[] certificateDer) throws GeneralSecurityException {
        return formatFingerprint(sha256Hex(certificateDer));
    }

    public static String formatFingerprint(String hex) {
        String normalized = normalizeFingerprint(hex);
        StringBuilder result = new StringBuilder(95);
        for (int i = 0; i < normalized.length(); i += 2) {
            if (i > 0) result.append(':');
            result.append(normalized, i, i + 2);
        }
        return result.toString();
    }

    public static String normalizeFingerprint(String value) {
        if (value == null) return "";
        String normalized = value.replace(":", "").replace(" ", "").toUpperCase(Locale.ROOT);
        return HEX_FINGERPRINT.matcher(normalized).matches() ? normalized : "";
    }

    public static boolean fingerprintMatches(byte[] certificateDer, String expectedFingerprint)
            throws GeneralSecurityException {
        String normalized = normalizeFingerprint(expectedFingerprint);
        if (normalized.isEmpty()) return false;
        byte[] expected = fromHex(normalized);
        byte[] actual = sha256(certificateDer);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            zero(expected);
            zero(actual);
        }
    }

    public static String clientId(byte[] publicKeyX509) throws GeneralSecurityException {
        byte[] digest = sha256(publicKeyX509);
        try {
            return "cm-" + toHex(digest).substring(0, 24).toLowerCase(Locale.ROOT);
        } finally {
            zero(digest);
        }
    }

    public static boolean validClientId(String value) {
        return value != null && CLIENT_ID.matcher(value).matches();
    }

    public static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] decodeBase64Url(String value, int maxEncodedLength) throws GeneralSecurityException {
        if (value == null || value.length() > maxEncodedLength || !value.matches("[A-Za-z0-9_-]+")) {
            throw new GeneralSecurityException("invalid base64url");
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("invalid base64url", error);
        }
    }

    public static byte[] pairingProof(
            String bridgeId,
            String clientId,
            long issuedAtEpochMillis,
            String clientNonce,
            byte[] clientPublicKeyX509) throws GeneralSecurityException {
        return lines("CRAFTMIND-PAIR-V1", bridgeId, clientId, Long.toString(issuedAtEpochMillis),
                clientNonce, sha256Hex(clientPublicKeyX509)).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sessionProof(
            String bridgeId,
            String clientId,
            String challengeId,
            String challengeNonce,
            long issuedAtEpochMillis) {
        return lines("CRAFTMIND-SESSION-V1", bridgeId, clientId, challengeId, challengeNonce,
                Long.toString(issuedAtEpochMillis)).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] requestProof(
            String bridgeId,
            String sessionId,
            long sequence,
            String requestId,
            long timestampEpochMillis,
            String method,
            String path,
            byte[] exactBodyBytes) throws GeneralSecurityException {
        return lines("CRAFTMIND-REQUEST-V1", bridgeId, sessionId, Long.toString(sequence), requestId,
                Long.toString(timestampEpochMillis), method, path, sha256Hex(exactBodyBytes))
                .getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isP256PublicKey(byte[] publicKeyX509) throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeyX509));
        return key instanceof ECPublicKey && isP256(((ECPublicKey) key).getParams());
    }

    public static boolean verifyP256Signature(byte[] publicKeyX509, byte[] message, byte[] signatureBytes)
            throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeyX509));
        if (!(key instanceof ECPublicKey) || !isP256(((ECPublicKey) key).getParams())) return false;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update(message);
        return signature.verify(signatureBytes);
    }

    private static boolean isP256(ECParameterSpec actual) throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        if (actual == null || actual.getCurve().getField().getFieldSize() != 256 ||
                !(actual.getCurve().getField() instanceof ECFieldFp) ||
                !((ECFieldFp) actual.getCurve().getField()).getP().equals(((ECFieldFp) expected.getCurve().getField()).getP())) {
            return false;
        }
        return actual.getCurve().getA().equals(expected.getCurve().getA()) &&
                actual.getCurve().getB().equals(expected.getCurve().getB()) &&
                actual.getGenerator().equals(expected.getGenerator()) &&
                actual.getOrder().equals(expected.getOrder()) && actual.getCofactor() == expected.getCofactor();
    }

    public static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] alphabet = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(chars);
    }

    public static byte[] fromHex(String value) {
        String normalized = normalizeFingerprint(value);
        if (normalized.isEmpty()) return new byte[0];
        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    public static void zero(byte[] value) {
        if (value != null) java.util.Arrays.fill(value, (byte) 0);
    }

    public static void zero(char[] value) {
        if (value != null) java.util.Arrays.fill(value, '\0');
    }

    private static String lines(String... values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) result.append('\n');
            result.append(values[index] == null ? "" : values[index]);
        }
        return result.toString();
    }
}
