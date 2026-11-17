package org.telegram.messenger.zenlygram.customer;

/**
 * When an account's profile is synced (contract §6.5; design 50 §9.2), pure and host-tested. A sync is due when:
 * <ol>
 * <li>this binding was never synced (no stored digest) — at once;</li>
 * <li>the snapshot digest changed — 10 s after the change was first seen (debounce) and at least 5 min after the
 * last success;</li>
 * <li>the digest is unchanged, the app is in the foreground and the last success is at least
 * {@code max(6 h, min_interval_s)} old.</li>
 * </ol>
 * Never before first idle, and never again in this process for a digest whose payload the server dropped (409 / 422 /
 * 413 / another 403). The install budget and the route's backoff are the caller's (CustomerPipeline.Admission,
 * CustomerBackoff.State). Wall times are trusted epoch seconds (a last success "in the future" after the clock moved back
 * counts as now); the debounce uses monotonic milliseconds.
 */
public final class ProfileCadence {
    /** A changed digest waits this long for further changes. */
    public static final long DEBOUNCE_MS = 10_000;
    /** A changed digest is sent at most this often. */
    public static final long MIN_SPACING_SECONDS = 300;
    /** An unchanged profile is refreshed at most this often (and never more often than the server's min_interval_s). */
    public static final long REFRESH_SECONDS = 21_600;

    private ProfileCadence() {}

    public enum Action {
        /** Send now. */
        SEND,
        /** Send after {@link Decision#waitMs} (debounce, spacing or refresh age). */
        WAIT,
        /** Nothing to send (settled, before first idle, background refresh, or the digest was dropped). */
        NONE
    }

    /** One decision; immutable. */
    public static final class Decision {
        public final Action action;
        /** For {@link Action#WAIT}: milliseconds until the sync is due (> 0); else 0. */
        public final long waitMs;

        private Decision(Action action, long waitMs) {
            this.action = action;
            this.waitMs = action == Action.WAIT ? Math.max(1, waitMs) : 0;
        }

        static final Decision SEND = new Decision(Action.SEND, 0);
        static final Decision NONE = new Decision(Action.NONE, 0);

        static Decision waitOrSend(long waitMs) { return waitMs <= 0 ? SEND : new Decision(Action.WAIT, waitMs); }

        @Override public String toString() { return action == Action.WAIT ? "WAIT(" + waitMs + ")" : action.name(); }
    }

    /** What the cadence reads; set every field. */
    public static final class Facts {
        /** {@code profile_sync_sha256} of the current binding, or null when it was never synced. */
        public String storedDigest;
        /** The digest of the snapshot built now. */
        public String currentDigest;
        /** A digest whose payload was dropped in this process, or null. */
        public String droppedDigest;
        /** Monotonic ms when {@link #currentDigest} was first seen differing from the stored one (-1 = now). */
        public long changeSeenMs = -1;
        /** Monotonic ms now. */
        public long nowMs;
        /** {@code profile_synced_at} (trusted epoch seconds, 0 = never). */
        public long syncedAtS;
        /** Trusted epoch seconds now. */
        public long nowS;
        /** {@code profile_min_interval_s} (the server's, 21600 by default). */
        public long minIntervalS = REFRESH_SECONDS;
        public boolean foreground;
        public boolean firstIdlePassed;
    }

    /** The decision for {@code f}. */
    public static Decision decide(Facts f) {
        if (!f.firstIdlePassed || f.currentDigest == null) return Decision.NONE;
        if (f.currentDigest.equals(f.droppedDigest)) return Decision.NONE;
        if (f.storedDigest == null) return Decision.SEND;
        long syncedAt = Math.min(Math.max(0, f.syncedAtS), f.nowS);
        if (!f.storedDigest.equals(f.currentDigest)) {
            long seen = f.changeSeenMs < 0 ? f.nowMs : f.changeSeenMs;
            long debounce = seen + DEBOUNCE_MS - f.nowMs;
            long spacing = (syncedAt + MIN_SPACING_SECONDS - f.nowS) * 1000L;
            return Decision.waitOrSend(Math.max(debounce, spacing));
        }
        if (!f.foreground) return Decision.NONE;
        long age = Math.max(REFRESH_SECONDS, f.minIntervalS);
        return Decision.waitOrSend((syncedAt + age - f.nowS) * 1000L);
    }

    /**
     * The profile is settled for this binding (design 50 §10): a sync succeeded (a digest is stored) or the server dropped
     * a payload in this process.
     */
    public static boolean settled(String storedDigest, String droppedDigest) { return storedDigest != null || droppedDigest != null; }
}
