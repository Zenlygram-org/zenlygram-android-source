package org.telegram.messenger.zenlygram.pro;

/**
 * Durable Google attach bookkeeping of one binding, stored 1:1 in its encrypted Pro session: the
 * pending Idempotency-Keys (GooglePlayAttachKeys) and the "fresh signed refresh owed" sequence it
 * shares with push hints (ProPushHint.Pending). Immutable; ZenlyPro persists each transition in a
 * single encrypted write, so a key and its owed refresh are always on disk together.
 */
final class GooglePlayAttachRecord {
    final GooglePlayAttachKeys keys;
    final ProPushHint.Pending owed;

    private GooglePlayAttachRecord(GooglePlayAttachKeys keys, ProPushHint.Pending owed) {
        this.keys = keys;
        this.owed = owed;
    }

    /** From the persisted session fields; malformed values start clean rather than failing. */
    static GooglePlayAttachRecord of(String encodedKeys, long received, long consumed) {
        return new GooglePlayAttachRecord(GooglePlayAttachKeys.decode(encodedKeys), ProPushHint.Pending.of(received, consumed));
    }

    String encodedKeys() { return keys.encode(); }
    long received() { return owed.received; }
    long consumed() { return owed.consumed; }
    boolean refreshOwed() { return owed.pending(); }

    /**
     * The journal written before a purchase token leaves the device: the operation key for this token
     * (reused by automatic retries, fresh for an explicit restore; read back with keys.keyFor) and a
     * fresh signed refresh owed up to received(), so a lost answer, a Play acknowledgement or a recent
     * cached grant can never leave a committed purchase unrecovered.
     */
    GooglePlayAttachRecord beforeSend(String tokenHash, boolean newOperation, GooglePlayAttachKeys.Entropy entropy) {
        return new GooglePlayAttachRecord(keys.attempt(tokenHash, newOperation, entropy).keys, owed.received());
    }

    /** Definitive server outcome (replay, ownership mismatch, rejection): the key ends, the refresh stays owed. */
    GooglePlayAttachRecord completed(String tokenHash, String key) {
        GooglePlayAttachKeys done = keys.completed(tokenHash, key);
        return done == keys ? this : new GooglePlayAttachRecord(done, owed);
    }

    /** A fresh signed grant from this attach was adopted: the key ends and everything owed before the send is settled. */
    GooglePlayAttachRecord adopted(String tokenHash, String key, long owedSequence) {
        return new GooglePlayAttachRecord(keys.completed(tokenHash, key), owed.consumedThrough(owedSequence));
    }

    /** A fresh signed grant fetched after the send was adopted. */
    GooglePlayAttachRecord settled(long owedSequence) {
        ProPushHint.Pending next = owed.consumedThrough(owedSequence);
        return next == owed ? this : new GooglePlayAttachRecord(keys, next);
    }
}
