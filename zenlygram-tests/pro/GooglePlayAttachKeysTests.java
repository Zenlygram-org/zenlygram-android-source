package org.telegram.messenger.zenlygram.pro;

import java.util.Arrays;

/**
 * Plain-JVM lifecycle tests for durable Google attach Idempotency-Keys (run with run-tests.sh).
 * Entropy is injected deterministically and "sessions" are the encoded strings ZenlyPro persists in
 * each binding's encrypted session; tokens are synthetic fixtures.
 */
public final class GooglePlayAttachKeysTests {
    private static int checks;
    private static final String TOKEN = "fixture-purchase-token.AO-J1Oy_123";
    private static final String OTHER_TOKEN = "fixture-purchase-token.AO-J1Oy_456";

    public static void main(String[] args) {
        tokenHashing();
        lostResponseAndRestartReuse();
        definitiveOutcomes();
        explicitRestoreIsNewOperation();
        staleCompletion();
        independentTokens();
        inFlightRetries();
        decodingAndBounds();
        System.out.println("Google attach keys: " + checks + " checks passed");
    }

    /** Fills with one byte value and counts draws, so reuse is proven by "no new entropy drawn". */
    private static final class Entropy implements GooglePlayAttachKeys.Entropy {
        final int value;
        int draws;
        Entropy(int value) { this.value = value; }
        @Override public void fill(byte[] bytes) {
            draws++;
            check(bytes.length == GooglePlayAttachKeys.KEY_BYTES, "128-bit key entropy requested");
            Arrays.fill(bytes, (byte) value);
        }
    }

    private static String key(int value) { return "google-attach-" + repeat(String.format("%02x", value), 16); }

    private static void tokenHashing() {
        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        check(hash.matches("[a-f0-9]{64}") && !hash.contains("fixture"), "ledger is keyed by token hash, never the token");
        check(hash.equals(GooglePlayAttachKeys.tokenHash(TOKEN)) && !hash.equals(GooglePlayAttachKeys.tokenHash(OTHER_TOKEN)),
                "hash is stable per token and distinct across tokens");
        check(rejects(() -> GooglePlayAttachKeys.tokenHash("")) && rejects(() -> GooglePlayAttachKeys.tokenHash("a b")),
                "invalid tokens cannot open an operation");
        check(rejects(() -> GooglePlayAttachKeys.EMPTY.attempt("not-a-hash", false, new Entropy(1))), "attempt requires a token hash");
    }

    private static void lostResponseAndRestartReuse() {
        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        Entropy first = new Entropy(0x01);
        GooglePlayAttachKeys.Attempt attempt = GooglePlayAttachKeys.EMPTY.attempt(hash, false, first);
        check(attempt.idempotencyKey.equals(key(0x01)) && first.draws == 1, "new operation draws one fresh key");
        String persisted = attempt.keys.encode(); // saved before the request is sent
        check(!persisted.contains(TOKEN) && persisted.contains(hash), "persisted session holds hash and key only");
        check(GooglePlayAttachKeys.EMPTY.size() == 0, "previous ledger is not mutated");

        // Response lost, app killed: the restarted process decodes the session and retries automatically.
        Entropy restart = new Entropy(0x02);
        GooglePlayAttachKeys.Attempt retry = GooglePlayAttachKeys.decode(persisted).attempt(hash, false, restart);
        check(retry.idempotencyKey.equals(attempt.idempotencyKey) && restart.draws == 0,
                "lost-response/restart retry reuses the same key without new entropy");
        check(retry.keys.encode().equals(persisted), "reusing a key needs no session rewrite");

        Entropy background = new Entropy(0x03);
        check(retry.keys.attempt(hash, false, background).idempotencyKey.equals(key(0x01)) && background.draws == 0,
                "foreground/background sync retry is the same operation");
    }

    private static void definitiveOutcomes() {
        check(GooglePlayAttachKeys.definitive(200, null) && GooglePlayAttachKeys.definitive(201, null), "2xx ends the operation");
        check(GooglePlayAttachKeys.definitive(422, "invalid_purchase") && GooglePlayAttachKeys.definitive(422, "unsupported_product"),
                "422 rejection ends the operation");
        check(GooglePlayAttachKeys.definitive(409, "purchase_account_mismatch"), "ownership mismatch ends the operation");
        check(!GooglePlayAttachKeys.definitive(409, "idempotency_in_flight"), "in-flight 409 keeps the key for the same retry");
        check(!GooglePlayAttachKeys.definitive(401, "refresh_invalid") && !GooglePlayAttachKeys.definitive(429, "rate_limited")
                && !GooglePlayAttachKeys.definitive(503, "google_unavailable") && !GooglePlayAttachKeys.definitive(500, "server_error")
                && !GooglePlayAttachKeys.definitive(0, null), "auth, throttle, outage and transport loss keep the key");

        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        GooglePlayAttachKeys.Attempt attempt = GooglePlayAttachKeys.EMPTY.attempt(hash, false, new Entropy(0x04));
        GooglePlayAttachKeys done = attempt.keys.completed(hash, attempt.idempotencyKey);
        check(done.keyFor(hash) == null && done.size() == 0, "definitive completion clears the operation");
        Entropy later = new Entropy(0x05);
        check(done.attempt(hash, false, later).idempotencyKey.equals(key(0x05)) && later.draws == 1,
                "after a definitive outcome the next attach is a new operation");
    }

    private static void explicitRestoreIsNewOperation() {
        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        GooglePlayAttachKeys pending = GooglePlayAttachKeys.EMPTY.attempt(hash, false, new Entropy(0x06)).keys;
        Entropy restore = new Entropy(0x07);
        GooglePlayAttachKeys.Attempt explicit = pending.attempt(hash, true, restore);
        check(explicit.idempotencyKey.equals(key(0x07)) && restore.draws == 1, "explicit restore draws a fresh key");
        check(!explicit.idempotencyKey.equals(pending.keyFor(hash)), "restore is distinct from the pending automatic operation");
        check(explicit.keys.size() == 1 && key(0x07).equals(explicit.keys.keyFor(hash)), "restore replaces, not duplicates, the entry");
        check(key(0x06).equals(pending.keyFor(hash)), "earlier ledger value unchanged");
    }

    private static void staleCompletion() {
        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        GooglePlayAttachKeys old = GooglePlayAttachKeys.EMPTY.attempt(hash, false, new Entropy(0x08)).keys;
        GooglePlayAttachKeys newer = old.attempt(hash, true, new Entropy(0x09)).keys;
        GooglePlayAttachKeys afterStale = newer.completed(hash, key(0x08));
        check(afterStale == newer && key(0x09).equals(afterStale.keyFor(hash)), "stale completion never clears the newer operation");
        check(newer.completed(GooglePlayAttachKeys.tokenHash(OTHER_TOKEN), key(0x09)) == newer, "completion of another token is a no-op");
        check(newer.completed(hash, null) == newer, "missing key completes nothing");
    }

    private static void independentTokens() {
        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        GooglePlayAttachKeys one = GooglePlayAttachKeys.EMPTY.attempt(hash, false, new Entropy(0x0a)).keys;
        GooglePlayAttachKeys both = one.attempt(GooglePlayAttachKeys.tokenHash(OTHER_TOKEN), false, new Entropy(0x0c)).keys;
        check(both.size() == 2 && key(0x0a).equals(both.keyFor(hash)), "a second token's operation leaves the first pending");
        GooglePlayAttachKeys afterOther = both.completed(GooglePlayAttachKeys.tokenHash(OTHER_TOKEN), key(0x0c));
        check(afterOther.size() == 1 && key(0x0a).equals(afterOther.keyFor(hash)), "completing one token keeps the other pending");
    }

    /** 409 idempotency_in_flight: same key resent after a short bounded wait; everything else stops. */
    private static void inFlightRetries() {
        check(GooglePlayAttachKeys.inFlightRetryDelayMs(0, 409, "idempotency_in_flight", 0) == 1000, "first wait is one second");
        check(GooglePlayAttachKeys.inFlightRetryDelayMs(1, 409, "idempotency_in_flight", 3) == 3000, "server Retry-After within bound honored");
        check(GooglePlayAttachKeys.inFlightRetryDelayMs(0, 409, "idempotency_in_flight", 30) == -1,
                "longer Retry-After is respected by not hammering; key stays pending");
        check(GooglePlayAttachKeys.inFlightRetryDelayMs(GooglePlayAttachKeys.MAX_IN_FLIGHT_RETRIES, 409, "idempotency_in_flight", 0) == -1,
                "retries are bounded");
        check(GooglePlayAttachKeys.inFlightRetryDelayMs(0, 409, "purchase_account_mismatch", 0) == -1
                && GooglePlayAttachKeys.inFlightRetryDelayMs(0, 200, null, 0) == -1
                && GooglePlayAttachKeys.inFlightRetryDelayMs(0, 429, "rate_limited", 1) == -1, "only in-flight conflicts wait");
    }

    private static void decodingAndBounds() {
        String hash = GooglePlayAttachKeys.tokenHash(TOKEN);
        String valid = hash + "=" + key(0x0d);
        check(GooglePlayAttachKeys.decode(null).size() == 0 && GooglePlayAttachKeys.decode("").size() == 0, "empty session has no keys");
        GooglePlayAttachKeys mixed = GooglePlayAttachKeys.decode("garbage," + hash.toUpperCase() + "=" + key(1) + ","
                + repeat("a", 64) + "=google-attach-xyz," + valid + ",=" + key(2));
        check(mixed.size() == 1 && key(0x0d).equals(mixed.keyFor(hash)), "tampered entries dropped, valid entry kept");
        check(GooglePlayAttachKeys.decode(valid).encode().equals(valid), "encode/decode round-trips exactly");

        GooglePlayAttachKeys many = GooglePlayAttachKeys.EMPTY;
        String firstHash = null;
        for (int i = 0; i <= GooglePlayAttachKeys.MAX_ENTRIES; i++) {
            String h = GooglePlayAttachKeys.tokenHash("fixture-token-" + i);
            if (firstHash == null) firstHash = h;
            many = many.attempt(h, false, new Entropy(i)).keys;
        }
        check(many.size() == GooglePlayAttachKeys.MAX_ENTRIES && many.keyFor(firstHash) == null, "ledger bounded; oldest evicted");
    }

    private interface Action { void run(); }

    private static boolean rejects(Action action) {
        try { action.run(); return false; }
        catch (RuntimeException expected) { return true; }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
