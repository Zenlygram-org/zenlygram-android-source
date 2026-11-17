package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Durable Idempotency-Keys for Google Play attach operations, stored in one binding's encrypted
 * Pro session (so a relink or another Telegram identity never sees them). Entries are keyed by the
 * SHA-256 of the purchase token; the raw token is never stored.
 *
 * One logical operation keeps one key across lost responses, background/foreground retries and
 * process restarts until a definitive server outcome; a deliberately new operation (explicit
 * restore) gets a fresh key. Completion only clears the entry if it still holds the completing key,
 * so a stale completion never clears a newer operation. Immutable: every change returns a new value.
 */
public final class GooglePlayAttachKeys {
    /** Source of key randomness; production uses SecureRandom, tests inject fixed bytes. */
    public interface Entropy { void fill(byte[] bytes); }

    static final int MAX_ENTRIES = 16;
    static final int KEY_BYTES = 16;
    static final GooglePlayAttachKeys EMPTY = new GooglePlayAttachKeys(new LinkedHashMap<String, String>());
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern KEY = Pattern.compile("google-attach-[a-f0-9]{32}");

    /** Insertion-ordered (oldest first) tokenHash -> key. */
    private final Map<String, String> keys;

    private GooglePlayAttachKeys(LinkedHashMap<String, String> keys) {
        this.keys = Collections.unmodifiableMap(keys);
    }

    public static final class Attempt {
        public final GooglePlayAttachKeys keys;
        public final String idempotencyKey;

        Attempt(GooglePlayAttachKeys keys, String idempotencyKey) {
            this.keys = keys;
            this.idempotencyKey = idempotencyKey;
        }
    }

    /** Never throws: malformed or tampered entries are dropped (that operation simply starts anew). */
    public static GooglePlayAttachKeys decode(String encoded) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return EMPTY;
        for (String entry : encoded.split(",", -1)) {
            int separator = entry.indexOf('=');
            if (separator <= 0) continue;
            String hash = entry.substring(0, separator), key = entry.substring(separator + 1);
            if (HASH.matcher(hash).matches() && KEY.matcher(key).matches()) result.put(hash, key);
        }
        return bounded(result);
    }

    public String encode() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            if (result.length() > 0) result.append(',');
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    public String keyFor(String tokenHash) { return keys.get(tokenHash); }
    public int size() { return keys.size(); }

    /** Token hashes whose attach operation has no definitive outcome yet (an automatic resend reuses its key). */
    public Set<String> pendingTokenHashes() { return Collections.unmodifiableSet(new LinkedHashSet<>(keys.keySet())); }

    /** Reuses the pending key for an automatic retry; a new operation always replaces it. */
    public Attempt attempt(String tokenHash, boolean newOperation, Entropy entropy) {
        if (!HASH.matcher(tokenHash == null ? "" : tokenHash).matches()) throw new IllegalArgumentException("Invalid token hash");
        String existing = keys.get(tokenHash);
        if (existing != null && !newOperation) return new Attempt(this, existing);
        LinkedHashMap<String, String> next = new LinkedHashMap<>(keys);
        next.remove(tokenHash);
        String key = newKey(entropy);
        next.put(tokenHash, key);
        return new Attempt(bounded(next), key);
    }

    /** Clears the entry only if it still belongs to the completing operation. */
    public GooglePlayAttachKeys completed(String tokenHash, String key) {
        if (key == null || !key.equals(keys.get(tokenHash))) return this;
        LinkedHashMap<String, String> next = new LinkedHashMap<>(keys);
        next.remove(tokenHash);
        return new GooglePlayAttachKeys(next);
    }

    static final int MAX_IN_FLIGHT_RETRIES = 2;
    static final long MIN_IN_FLIGHT_DELAY_MS = 1000, MAX_IN_FLIGHT_DELAY_MS = 5000;

    /**
     * 409 idempotency_in_flight means the same operation is still running server-side: wait briefly
     * and resend with the SAME key. Returns the delay in ms, or -1 to stop (other outcomes, retries
     * exhausted, or a server Retry-After longer than the bounded wait, which is then respected by
     * leaving the key pending for the next automatic retry).
     */
    public static long inFlightRetryDelayMs(int retriesDone, int httpStatus, String errorCode, int retryAfterSeconds) {
        if (httpStatus != 409 || !"idempotency_in_flight".equals(errorCode) || retriesDone >= MAX_IN_FLIGHT_RETRIES) return -1;
        long requested = Math.max(0, retryAfterSeconds) * 1000L;
        if (requested > MAX_IN_FLIGHT_DELAY_MS) return -1;
        return Math.max(MIN_IN_FLIGHT_DELAY_MS, requested);
    }

    /**
     * Definitive server outcomes end the operation: any 2xx, a 409 ownership mismatch and a 422
     * rejection. Transport loss, 401, 429, 5xx and 409 idempotency_in_flight keep the key so the
     * automatic retry is recognized by the backend as the same operation.
     */
    public static boolean definitive(int httpStatus, String errorCode) {
        if (httpStatus >= 200 && httpStatus < 300) return true;
        if (httpStatus == 422) return true;
        return httpStatus == 409 && "purchase_account_mismatch".equals(errorCode);
    }

    public static String tokenHash(String token) {
        if (!GooglePlayModel.validToken(token)) throw new IllegalArgumentException("Invalid purchase token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String newKey(Entropy entropy) {
        byte[] random = new byte[KEY_BYTES];
        entropy.fill(random);
        return "google-attach-" + hex(random);
    }

    private static GooglePlayAttachKeys bounded(LinkedHashMap<String, String> keys) {
        while (keys.size() > MAX_ENTRIES) keys.remove(keys.keySet().iterator().next());
        return new GooglePlayAttachKeys(keys);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(Locale.US, "%02x", b & 255));
        return result.toString();
    }
}
