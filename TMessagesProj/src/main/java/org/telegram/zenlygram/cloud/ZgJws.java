/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ES256 compact JWS verification against the pinned key ring (iOS SGEntitlementTokenVerifier + SGEntitlementCrypto,
 * docs/entitlement.md §4.2). Pure Java (java.security + org.json), so it runs in JVM unit tests.
 *
 * <p>Rules, in order: exactly three segments; base64url without padding; header {@code alg} must be ES256 (never
 * selects the algorithm, "none" is refused), {@code typ} absent or JWT; {@code kid} selects a PINNED key (there is no
 * JWKS endpoint and there must never be one); the signature is exactly 64 bytes r||s; the signed input is the ASCII
 * of the ORIGINAL two segments. This class says "authentic"; what the payload means is the caller's job
 * ({@link ZgPublicPolicy}).
 */
public final class ZgJws {

    public enum Failure {
        MALFORMED,
        /** Not one of the pinned kids: usually an old build meeting a rotated key. Keep the last verified token. */
        UNKNOWN_KID,
        BAD_SIGNATURE
    }

    /** A verified token: the decoded header and payload, plus the exact compact form. */
    public static final class Verified {
        public final String compact;
        public final String kid;
        public final JSONObject header;
        public final JSONObject payload;

        Verified(String compact, String kid, JSONObject header, JSONObject payload) {
            this.compact = compact;
            this.kid = kid;
            this.header = header;
            this.payload = payload;
        }
    }

    public static final class Result {
        public final Verified verified;
        public final Failure failure;

        private Result(Verified verified, Failure failure) {
            this.verified = verified;
            this.failure = failure;
        }

        static Result ok(Verified verified) {
            return new Result(verified, null);
        }

        static Result fail(Failure failure) {
            return new Result(null, failure);
        }

        public boolean isOk() {
            return verified != null;
        }
    }

    /** kid -> base64 SPKI DER (or PEM) of a P-256 public key. */
    public static final class KeyRing {
        private final Map<String, String> keysByKid;
        private final Map<String, PublicKey> parsed = new HashMap<>();

        public KeyRing(Map<String, String> keysByKid) {
            this.keysByKid = Collections.unmodifiableMap(new HashMap<>(keysByKid));
        }

        public boolean isEmpty() {
            return keysByKid.isEmpty();
        }

        synchronized PublicKey key(String kid) {
            if (parsed.containsKey(kid)) {
                return parsed.get(kid);
            }
            final String value = keysByKid.get(kid);
            final PublicKey key = value == null ? null : parseP256(value);
            if (value != null) {
                parsed.put(kid, key);
            }
            return key;
        }

        boolean knows(String kid) {
            return keysByKid.containsKey(kid);
        }
    }

    /** The fixed 26-byte SPKI prefix of an uncompressed P-256 key (iOS p256SPKIPrefix). */
    private static final byte[] P256_SPKI_PREFIX = {
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    };

    private ZgJws() {
    }

    public static Result verify(String compact, KeyRing ring) {
        if (compact == null || ring == null) {
            return Result.fail(Failure.MALFORMED);
        }
        final int first = compact.indexOf('.');
        final int second = first < 0 ? -1 : compact.indexOf('.', first + 1);
        if (first < 0 || second < 0 || compact.indexOf('.', second + 1) >= 0) {
            return Result.fail(Failure.MALFORMED);
        }
        final String headerPart = compact.substring(0, first);
        final String payloadPart = compact.substring(first + 1, second);
        final String signaturePart = compact.substring(second + 1);
        final byte[] headerBytes = ZgBase64.decodeUrl(headerPart);
        final byte[] payloadBytes = ZgBase64.decodeUrl(payloadPart);
        final byte[] signature = ZgBase64.decodeUrl(signaturePart);
        if (headerBytes == null || payloadBytes == null || signature == null) {
            return Result.fail(Failure.MALFORMED);
        }
        final JSONObject header = parseObject(headerBytes);
        final JSONObject payload = parseObject(payloadBytes);
        if (header == null || payload == null) {
            return Result.fail(Failure.MALFORMED);
        }
        if (!"ES256".equals(header.opt("alg"))) {
            return Result.fail(Failure.MALFORMED);
        }
        final Object typ = header.opt("typ");
        if (typ != null && !"JWT".equals(typ)) {
            return Result.fail(Failure.MALFORMED);
        }
        final Object kidValue = header.opt("kid");
        if (!(kidValue instanceof String)) {
            return Result.fail(Failure.MALFORMED);
        }
        final String kid = (String) kidValue;
        if (!ring.knows(kid)) {
            return Result.fail(Failure.UNKNOWN_KID);
        }
        final PublicKey key = ring.key(kid);
        if (key == null) {
            return Result.fail(Failure.MALFORMED);
        }
        if (signature.length != 64) {
            return Result.fail(Failure.BAD_SIGNATURE);
        }
        try {
            final Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update((headerPart + "." + payloadPart).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(rawToDer(signature))) {
                return Result.fail(Failure.BAD_SIGNATURE);
            }
        } catch (Exception e) {
            return Result.fail(Failure.BAD_SIGNATURE);
        }
        return Result.ok(new Verified(compact, kid, header, payload));
    }

    /** The exact set of keys of {@code object}. */
    static java.util.Set<String> keys(JSONObject object) {
        final java.util.HashSet<String> set = new java.util.HashSet<>();
        final Iterator<String> it = object.keys();
        while (it.hasNext()) {
            set.add(it.next());
        }
        return set;
    }

    private static JSONObject parseObject(byte[] bytes) {
        try {
            final String text = new String(bytes, StandardCharsets.UTF_8);
            final Object value = new org.json.JSONTokener(text).nextValue();
            return value instanceof JSONObject ? (JSONObject) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** PEM or bare base64 SPKI DER (or a bare 65-byte point) -> a P-256 public key, or null. */
    static PublicKey parseP256(String value) {
        String body = value.trim();
        if (body.startsWith("-----BEGIN")) {
            final StringBuilder sb = new StringBuilder();
            for (String line : body.split("\n")) {
                if (!line.trim().startsWith("-----")) {
                    sb.append(line.trim());
                }
            }
            body = sb.toString();
        }
        byte[] der = ZgBase64.decodeStandard(body.replace(" ", "").replace("\n", "").replace("\r", ""));
        if (der == null) {
            return null;
        }
        if (der.length == 65 && der[0] == 0x04) {
            final byte[] wrapped = new byte[P256_SPKI_PREFIX.length + 65];
            System.arraycopy(P256_SPKI_PREFIX, 0, wrapped, 0, P256_SPKI_PREFIX.length);
            System.arraycopy(der, 0, wrapped, P256_SPKI_PREFIX.length, 65);
            der = wrapped;
        }
        if (der.length != P256_SPKI_PREFIX.length + 65) {
            return null;
        }
        for (int i = 0; i < P256_SPKI_PREFIX.length; i++) {
            if (der[i] != P256_SPKI_PREFIX[i]) {
                return null;
            }
        }
        if (der[P256_SPKI_PREFIX.length] != 0x04) {
            return null;
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            return null;
        }
    }

    /** JOSE r||s (64 bytes) -> ASN.1 DER SEQUENCE { INTEGER r, INTEGER s } for java.security. */
    static byte[] rawToDer(byte[] raw) {
        final byte[] r = derInteger(raw, 0);
        final byte[] s = derInteger(raw, 32);
        final int length = r.length + s.length;
        final byte[] out = new byte[2 + length];
        out[0] = 0x30;
        out[1] = (byte) length;
        System.arraycopy(r, 0, out, 2, r.length);
        System.arraycopy(s, 0, out, 2 + r.length, s.length);
        return out;
    }

    /** One 32-byte big-endian half -> its DER INTEGER (leading zeros stripped, 0x00 added when the top bit is set). */
    static byte[] derInteger(byte[] raw, int offset) {
        int start = offset;
        final int end = offset + 32;
        while (start < end - 1 && raw[start] == 0) {
            start++;
        }
        final boolean pad = (raw[start] & 0x80) != 0;
        final int length = end - start + (pad ? 1 : 0);
        final byte[] out = new byte[2 + length];
        out[0] = 0x02;
        out[1] = (byte) length;
        int p = 2;
        if (pad) {
            out[p++] = 0;
        }
        System.arraycopy(raw, start, out, p, end - start);
        return out;
    }

    /** ASN.1 DER ECDSA signature -> 64-byte r||s (for the device signer, which may send either form). */
    static byte[] derToRaw(byte[] der) {
        if (der == null || der.length < 8 || der[0] != 0x30) {
            return null;
        }
        int p = 2;
        if ((der[1] & 0x80) != 0) {
            p = 2 + (der[1] & 0x7F);
        }
        final byte[] out = new byte[64];
        for (int half = 0; half < 2; half++) {
            if (p + 2 > der.length || der[p] != 0x02) {
                return null;
            }
            final int length = der[p + 1] & 0xFF;
            p += 2;
            if (p + length > der.length) {
                return null;
            }
            int start = p;
            int len = length;
            while (len > 32 && der[start] == 0) {
                start++;
                len--;
            }
            if (len > 32) {
                return null;
            }
            System.arraycopy(der, start, out, half * 32 + (32 - len), len);
            p += length;
        }
        return out;
    }
}
