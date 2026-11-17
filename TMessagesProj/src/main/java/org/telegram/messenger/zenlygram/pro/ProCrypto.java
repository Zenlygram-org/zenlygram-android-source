package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** Android-independent ES256 primitives, covered by the host-JDK tests. */
public final class ProCrypto {
    public static PublicKey publicKey(byte[] spki) throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
        if (!(key instanceof ECPublicKey)
                || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256
                || !((ECPublicKey) key).getParams().getOrder().toString(16).equals(
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")) {
            throw new GeneralSecurityException("Only P-256 public keys are supported");
        }
        return key;
    }

    public static boolean verify(PublicKey key, String encodedHeader, String encodedPayload, byte[] joseSignature)
            throws GeneralSecurityException {
        if (joseSignature.length != 64) return false;
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update((encodedHeader + "." + encodedPayload).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(joseToDer(joseSignature));
    }

    public static byte[] deviceMessage(String deviceId, String nonce, String timestamp) {
        return (deviceId + "\n" + nonce + "\n" + timestamp).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The backend's device-ownership proof message (DeviceSignature::proofMessage): nine UTF-8 lines
     * joined by LF, no trailing LF. Hashes are lowercase SHA-256 hex.
     */
    public static byte[] ownershipProofMessage(String path, String rawDeviceId, String keySha256, String nonce,
                                               String timestamp, String payloadSha256, String mode) {
        return ("zenlypay-device-proof-v1\nPOST\n" + path + "\n" + rawDeviceId + "\n" + keySha256 + "\n" + nonce + "\n"
                + timestamp + "\n" + payloadSha256 + "\n" + mode).getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256Hex(byte[] data) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }

    /** JOSE integers are unsigned fixed-width; JCA requires ASN.1 signed INTEGERs. */
    public static byte[] joseToDer(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 64) throw new GeneralSecurityException("Invalid ES256 signature length");
        byte[] r = integer(Arrays.copyOfRange(raw, 0, 32));
        byte[] s = integer(Arrays.copyOfRange(raw, 32, 64));
        byte[] result = new byte[6 + r.length + s.length];
        result[0] = 0x30;
        result[1] = (byte) (result.length - 2);
        result[2] = 0x02;
        result[3] = (byte) r.length;
        System.arraycopy(r, 0, result, 4, r.length);
        result[4 + r.length] = 0x02;
        result[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, result, 6 + r.length, s.length);
        return result;
    }

    private static byte[] integer(byte[] bytes) {
        int first = 0;
        while (first < bytes.length - 1 && bytes[first] == 0) first++;
        int padding = (bytes[first] & 0x80) != 0 ? 1 : 0;
        byte[] result = new byte[bytes.length - first + padding];
        System.arraycopy(bytes, first, result, padding, bytes.length - first);
        return result;
    }

    private ProCrypto() { }
}
