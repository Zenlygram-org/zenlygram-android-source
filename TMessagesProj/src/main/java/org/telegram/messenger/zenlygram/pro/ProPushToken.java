package org.telegram.messenger.zenlygram.pro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Pure rules for registering the Firebase token with each linked Pro binding through
 * POST /v1/device/push-token. The raw token is never persisted or logged: each account's encrypted
 * session keeps only a SHA-256 fingerprint of the token it last registered, so a token change, a new
 * binding (relink) or a different Telegram identity in the slot all register again.
 */
public final class ProPushToken {
    static final int MAX_LENGTH = 4096;
    static final long BASE_BACKOFF_MS = 60000;
    static final long MAX_BACKOFF_MS = 3600000;
    private static final int MAX_SHIFT = 6;

    private ProPushToken() { }

    /** Contract: opaque printable ASCII U+0021..U+007E, 1..4096 chars; never trimmed or reshaped. */
    public static boolean valid(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_LENGTH) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;
        }
        return true;
    }

    static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True when this binding has not yet registered exactly this token. */
    static boolean needsRegistration(String token, String registeredFingerprint) {
        return valid(token) && !fingerprint(token).equals(registeredFingerprint);
    }

    /** Immutable per-binding retry schedule (elapsed-realtime milliseconds, never wall clock). */
    static final class Schedule {
        static final Schedule IDLE = new Schedule(0, 0, null);
        final int failures;
        final long nextAttemptElapsed;
        /** Fingerprint the backend rejected (422); that exact token is not retried. */
        final String rejected;

        private Schedule(int failures, long nextAttemptElapsed, String rejected) {
            this.failures = failures;
            this.nextAttemptElapsed = nextAttemptElapsed;
            this.rejected = rejected;
        }

        boolean allows(String fingerprint, long nowElapsed) {
            return !fingerprint.equals(rejected) && nowElapsed >= nextAttemptElapsed;
        }

        Schedule succeeded() { return IDLE; }

        /** Transient failure: exponential backoff from one minute to one hour, or the server's Retry-After if longer. */
        Schedule failed(long nowElapsed, int retryAfterSeconds) {
            long backoff = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << Math.min(failures, MAX_SHIFT));
            long delay = Math.max(backoff, Math.max(0, retryAfterSeconds) * 1000L);
            return new Schedule(failures + 1, ProPolicy.saturatingAdd(nowElapsed, delay), rejected);
        }

        /** Permanent rejection of one token; a new token from Firebase is tried immediately. */
        Schedule rejectedToken(String fingerprint) { return new Schedule(0, 0, fingerprint); }
    }
}
