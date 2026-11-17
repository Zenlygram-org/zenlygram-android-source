package org.telegram.messenger.zenlygram.pro;

/**
 * Commit order of one Google Play attach (ZenlyPro.attachGooglePlay), independent of Android, the
 * network and storage so every interruption point is tested on a plain JVM:
 *
 * 0. preflight: a route still cooling down after a server 429 refuses before anything is written;
 * 1. journal: operation key + owed signed refresh, one write, before the token leaves the device;
 *    a failed write means nothing is sent;
 * 2. send (lost answer: everything stays owed for the automatic retry / next foreground);
 * 3. fresh 2xx: adopt the verified grant FIRST, then end the key and settle what was owed, computed
 *    from the session as it is after adoption; replay: end the key, fetch and adopt the current
 *    grant, then settle; definitive rejection: end the key, nothing granted, refresh stays owed.
 *
 * The Boundary re-checks the account Stamp and binding inside every step, so a result arriving
 * after an account switch, unlink or relink is never written or adopted.
 */
final class GooglePlayAttachFlow {
    private GooglePlayAttachFlow() { }

    static final class Reply {
        final int status;
        final String error;
        final boolean replayed;

        Reply(int status, String error, boolean replayed) {
            this.status = status;
            this.error = error;
            this.replayed = replayed;
        }

        boolean successful() { return status >= 200 && status < 300; }
    }

    interface Change { GooglePlayAttachRecord apply(GooglePlayAttachRecord current); }

    interface Boundary<S> {
        /** Refuses locally (e.g. the route's Retry-After cooldown) before anything is written. */
        void preflight() throws Exception;
        /** Atomically reads the durable record of the current binding, applies change and persists it. */
        GooglePlayAttachRecord update(Change change) throws Exception;
        /** Sends the purchase token with this Idempotency-Key (bounded in-flight waits included). */
        Reply send(String idempotencyKey) throws Exception;
        /** Verifies and persists the fresh signed grant of the last reply; returns its subscription state. */
        S adoptFresh() throws Exception;
        /** Fetches, verifies and persists the current signed grant (the replayed body is never adopted). */
        void adoptCurrent() throws Exception;
        /** Throws the error mapped from the last (unsuccessful) reply. */
        void reject() throws Exception;
    }

    /** Returns the fresh backend state, or null after a replay (its stored state may be stale). */
    static <S> S attach(Boundary<S> session, String tokenHash, boolean newOperation, GooglePlayAttachKeys.Entropy entropy) throws Exception {
        session.preflight();
        // The persisted journal itself names the operation: its key for this token and the owed sequence.
        GooglePlayAttachRecord journal = session.update(current -> current.beforeSend(tokenHash, newOperation, entropy));
        final String key = journal.keys.keyFor(tokenHash);
        final long owedSequence = journal.received();
        Reply reply = session.send(key);
        if (!reply.successful()) {
            if (GooglePlayAttachKeys.definitive(reply.status, reply.error)) {
                session.update(current -> current.completed(tokenHash, key));
            }
            session.reject();
            throw new IllegalStateException("Boundary.reject must throw");
        }
        if (!reply.replayed) {
            S state = session.adoptFresh();
            session.update(current -> current.adopted(tokenHash, key, owedSequence));
            return state;
        }
        session.update(current -> current.completed(tokenHash, key));
        session.adoptCurrent();
        session.update(current -> current.settled(owedSequence));
        return null;
    }
}
