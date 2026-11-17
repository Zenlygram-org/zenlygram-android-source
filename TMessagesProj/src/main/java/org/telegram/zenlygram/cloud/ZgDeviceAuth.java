/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The DeviceAuth credential set of the authenticated Zenlygram API (docs/entitlement.md §2, iOS SGEntitlementClient):
 * {@code X-Device-Id}, {@code X-Device-Nonce}, {@code X-Device-Ts}, {@code X-Device-Sig} (+ {@code Authorization:
 * Bearer <refresh_token>}). The signature is ECDSA P-256 / SHA-256 over exactly {@code device_id + "\n" + nonce + "\n"
 * + ts}, sent as STANDARD base64 with padding (base64url is rejected). Every request and every retry needs a fresh
 * nonce: the server remembers (device, nonce) for 600 s.
 *
 * <p>On Android the private key lives in the Android Keystore (non-exportable; StrongBox when present) and its SPKI
 * DER is what {@code /v1/bind/start} would register as {@code device_pubkey}. <b>Not used by this build</b>:
 * {@link ZgCloudConfig#AUTHENTICATED_BACKEND} is false until the server accepts Android binds. Pure Java (the Keystore
 * glue is the caller's), so the byte format is unit-tested.
 */
public final class ZgDeviceAuth {

    private static final SecureRandom random = new SecureRandom();

    private ZgDeviceAuth() {
    }

    /** A new raw device id: 32 random bytes as 64 lowercase hex chars. Minted once, never trimmed or re-cased. */
    public static String newDeviceId() {
        return hex(randomBytes(32));
    }

    /** A fresh nonce: 16 random bytes as hex. */
    public static String newNonce() {
        return hex(randomBytes(16));
    }

    /** The exact signed message. */
    public static byte[] signingInput(String deviceId, String nonce, String ts) {
        return (deviceId + "\n" + nonce + "\n" + ts).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The four device headers for one request, signed by {@code key} (an EC P-256 private key, e.g. from
     * AndroidKeyStore). {@code serverOffsetSeconds} corrects the clock after a {@code 401 clock_skew}.
     */
    public static Map<String, String> headers(String deviceId, PrivateKey key, long nowSeconds, long serverOffsetSeconds) throws Exception {
        final String nonce = newNonce();
        final String ts = String.valueOf(nowSeconds + serverOffsetSeconds);
        final Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(signingInput(deviceId, nonce, ts));
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Device-Id", deviceId);
        headers.put("X-Device-Nonce", nonce);
        headers.put("X-Device-Ts", ts);
        headers.put("X-Device-Sig", ZgBase64.encodeStandard(signature.sign())); // DER; the server accepts DER or raw r||s
        return headers;
    }

    private static byte[] randomBytes(int count) {
        final byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    static String hex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
