package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-only ES256 signer for synthetic compact JWS fixtures. Keys are generated per run and never
 * leave the test process; production code only ever verifies with pinned public keys.
 *
 * Claims are built as typed values (String, Long, Double, Boolean, null, List, Map), the same
 * normalized shape ZenlyPro's org.json adapter hands to ProMonetization. The host JDK has no JSON
 * parser, so {@link #DECODER} returns the exact value whose serialization was signed; parsing
 * itself is Android's org.json and is exercised by the native ProFreeFirstRuntimeTest.
 */
final class ProTestTokens {
    static final String ISS = "zenlygram", AUD = "zenlygram-monetization";
    private static final Map<String, Map<String, Object>> SIGNED = new HashMap<>();

    /** Decodes only what this fixture serialized; anything else (tampered, truncated) is unparseable. */
    static final ProMonetization.Decoder DECODER = utf8 -> {
        Map<String, Object> value = SIGNED.get(new String(utf8, StandardCharsets.UTF_8));
        if (value == null) throw new IllegalArgumentException("not JSON produced by this fixture");
        return value;
    };

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /** Ordered map from alternating keys and values. */
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    static Map<String, Object> header(String kid) { return map("typ", "JWT", "alg", "ES256", "kid", kid); }

    /**
     * The policy of the Android line exactly as the backend signs it (ver 4: ten claims, platform "android"), with
     * no server trial offered and the default local grace length.
     */
    static Map<String, Object> policyClaims(String mode, long revision, long issuedAt, long expiresAt) {
        return policyClaimsV4(mode, revision, 0, ProPolicy.DEFAULT_LOCAL_GRACE_DAYS, issuedAt, expiresAt);
    }

    static String policy(KeyPair key, String kid, String mode, long revision, long issuedAt, long expiresAt) throws Exception {
        return sign(header(kid), policyClaims(mode, revision, issuedAt, expiresAt), key.getPrivate());
    }

    /** Exact ver-4 claim set: the Android line with its trial_days and local_grace_days. */
    static Map<String, Object> policyClaimsV4(String mode, long revision, long trialDays, long localGraceDays, long issuedAt, long expiresAt) {
        return map("iss", ISS, "aud", AUD, "ver", 4L, "platform", ProMonetization.PLATFORM, "mode", mode, "revision", revision,
                "trial_days", trialDays, "local_grace_days", localGraceDays, "iat", issuedAt, "exp", expiresAt);
    }

    static String policyV4(KeyPair key, String kid, String mode, long revision, long trialDays, long localGraceDays, long issuedAt,
                           long expiresAt) throws Exception {
        return sign(header(kid), policyClaimsV4(mode, revision, trialDays, localGraceDays, issuedAt, expiresAt), key.getPrivate());
    }

    /**
     * A policy of the GENERAL line (what iOS and earlier builds read, and what a server without ver 4 answers):
     * the exact ver 1 (7 claims), ver 2 (with trial_days 0) or ver 3 (with local_grace_days 7 too) claim set.
     * Authentic, and never a policy of this build.
     */
    static Map<String, Object> generalClaims(int version, String mode, long revision, long issuedAt, long expiresAt) {
        Map<String, Object> claims = map("iss", ISS, "aud", AUD, "ver", (long) version, "mode", mode, "revision", revision);
        if (version >= 2) claims.put("trial_days", 0L);
        if (version >= 3) claims.put("local_grace_days", 7L);
        claims.put("iat", issuedAt);
        claims.put("exp", expiresAt);
        return claims;
    }

    static String general(KeyPair key, String kid, int version, String mode, long revision, long issuedAt, long expiresAt) throws Exception {
        return sign(header(kid), generalClaims(version, mode, revision, issuedAt, expiresAt), key.getPrivate());
    }

    static String sign(Map<String, Object> header, Map<String, Object> payload, PrivateKey key) throws Exception {
        return signRaw(segment(header), segment(payload), key);
    }

    /** Signs exact segments, e.g. a segment that is valid base64url but not a registered JSON value. */
    static String signRaw(String headerSegment, String payloadSegment, PrivateKey key) throws Exception {
        String input = headerSegment + "." + payloadSegment;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64(derToJose(signer.sign()));
    }

    /** base64url of the serialized value, registered for DECODER. */
    static String segment(Map<String, Object> value) {
        String json = json(value);
        SIGNED.put(json, value);
        return b64(json.getBytes(StandardCharsets.UTF_8));
    }

    static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    static Map<String, Object> envelope(Object token) {
        return map("policy_token", token, "server_time", "2026-10-20T00:00:00Z");
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return '"' + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        StringBuilder out = new StringBuilder();
        if (value instanceof List) {
            out.append('[');
            for (Object item : (List<?>) value) out.append(out.length() > 1 ? "," : "").append(json(item));
            return out.append(']').toString();
        }
        out.append('{');
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            out.append(out.length() > 1 ? "," : "").append(json(entry.getKey())).append(':').append(json(entry.getValue()));
        }
        return out.append('}').toString();
    }

    /** ASN.1 SEQUENCE { INTEGER r, INTEGER s } to fixed-width JOSE r||s. */
    static byte[] derToJose(byte[] der) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) offset += der[1] & 0x7f;
        byte[] result = new byte[64];
        for (int part = 0; part < 2; part++) {
            int length = der[offset + 1];
            int start = offset + 2;
            int skip = Math.max(0, length - 32);
            int copy = length - skip;
            System.arraycopy(der, start + skip, result, part * 32 + 32 - copy, copy);
            offset = start + length;
        }
        return result;
    }

    private ProTestTokens() { }
}
