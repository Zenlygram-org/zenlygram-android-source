package org.telegram.messenger.zenlygram.customer;

/**
 * Plain-JVM checks of the profile cadence (contract §6.5; design 50 §9.2): the first sync of a binding at once (also in
 * the background); a changed digest after the 10 s debounce and at least 5 min after the last success; an unchanged one
 * only in the foreground and only after {@code max(6 h, min_interval_s)}; nothing before first idle or for a digest the
 * server dropped in this process; a clock that moved back never stretches or skips a wait. Run with run-tests.sh.
 */
public final class ProfileCadenceTests {
    private static int checks;
    private static final String A = repeatHex('a'), B = repeatHex('b'), C = repeatHex('c');
    private static final long NOW_S = 1793527200L; // 2026-11-01T10:00:00Z
    private static final long NOW_MS = 5_000_000L;

    public static void main(String[] args) {
        gates();
        firstSync();
        changedDigest();
        unchangedDigest();
        clockMovedBack();
        settled();
        System.out.println("Profile cadence: " + checks + " checks passed");
    }

    private static void gates() {
        ProfileCadence.Facts f = facts(null, A);
        f.firstIdlePassed = false;
        check(decide(f).action == ProfileCadence.Action.NONE, "never before first idle");
        f = facts(B, null);
        check(decide(f).action == ProfileCadence.Action.NONE, "no snapshot (no user object) → nothing");
        f = facts(null, A);
        f.droppedDigest = A;
        check(decide(f).action == ProfileCadence.Action.NONE, "a dropped digest is never resent in this process");
        f.droppedDigest = B;
        check(decide(f).action == ProfileCadence.Action.SEND, "another digest than the dropped one is sent");
        f = facts(B, A);
        f.droppedDigest = A;
        f.syncedAtS = NOW_S - 86_400;
        check(decide(f).action == ProfileCadence.Action.NONE, "also when the binding was synced before with another digest");
    }

    private static void firstSync() {
        ProfileCadence.Facts f = facts(null, A);
        check(decide(f).action == ProfileCadence.Action.SEND, "never synced for this binding → at once");
        f.foreground = false;
        check(decide(f).action == ProfileCadence.Action.SEND, "also in the background (the link may complete there)");
        f.changeSeenMs = NOW_MS;
        check(decide(f).action == ProfileCadence.Action.SEND, "no debounce for the first sync");
    }

    private static void changedDigest() {
        ProfileCadence.Facts f = facts(A, B);
        f.syncedAtS = NOW_S - 3_600;
        f.changeSeenMs = NOW_MS;
        ProfileCadence.Decision d = decide(f);
        check(d.action == ProfileCadence.Action.WAIT && d.waitMs == ProfileCadence.DEBOUNCE_MS, "a change just seen waits the 10 s debounce: " + d);
        f.nowMs = NOW_MS + 9_999;
        check(decide(f).waitMs == 1, "1 ms before the debounce ends");
        f.nowMs = NOW_MS + 10_000;
        check(decide(f).action == ProfileCadence.Action.SEND, "after the debounce");
        f.changeSeenMs = -1;
        f.nowMs = NOW_MS;
        check(decide(f).waitMs == ProfileCadence.DEBOUNCE_MS, "an unknown first-seen time counts as now");

        f = facts(A, B);
        f.syncedAtS = NOW_S - 60;
        f.changeSeenMs = NOW_MS - 30_000;
        d = decide(f);
        check(d.action == ProfileCadence.Action.WAIT && d.waitMs == 240_000, "≥ 5 min after the last sync wins over a passed debounce: " + d);
        f.syncedAtS = NOW_S - 300;
        check(decide(f).action == ProfileCadence.Action.SEND, "exactly 5 min after the last sync");
        f.syncedAtS = NOW_S - 295;
        f.changeSeenMs = NOW_MS;
        check(decide(f).waitMs == ProfileCadence.DEBOUNCE_MS, "the longer of debounce (10 s) and spacing (5 s)");
        f.foreground = false;
        f.changeSeenMs = NOW_MS - 10_000;
        f.syncedAtS = NOW_S - 600;
        check(decide(f).action == ProfileCadence.Action.SEND, "a change is sent in the background too");
        f.storedDigest = C;
        f.currentDigest = B;
        check(decide(f).action == ProfileCadence.Action.SEND, "any difference counts");
    }

    private static void unchangedDigest() {
        ProfileCadence.Facts f = facts(A, A);
        f.syncedAtS = NOW_S - 21_600;
        check(decide(f).action == ProfileCadence.Action.SEND, "6 h old in the foreground → refresh");
        f.syncedAtS = NOW_S - 21_540;
        ProfileCadence.Decision d = decide(f);
        check(d.action == ProfileCadence.Action.WAIT && d.waitMs == 60_000, "1 min before 6 h: " + d);
        f.foreground = false;
        f.syncedAtS = NOW_S - 86_400 * 3;
        check(decide(f).action == ProfileCadence.Action.NONE, "never refreshed in the background");
        f.foreground = true;
        f.minIntervalS = 28_800;
        f.syncedAtS = NOW_S - 21_600;
        d = decide(f);
        check(d.action == ProfileCadence.Action.WAIT && d.waitMs == 7_200_000, "the server's longer min_interval_s wins: " + d);
        f.minIntervalS = 3_600;
        check(decide(f).action == ProfileCadence.Action.SEND, "a shorter min_interval_s never refreshes faster than 6 h");
        f.minIntervalS = 3_600;
        f.syncedAtS = NOW_S - 3_600;
        check(decide(f).action == ProfileCadence.Action.WAIT, "... so 1 h after the last sync it still waits");
        f.minIntervalS = 0;
        f.syncedAtS = NOW_S - 21_600;
        check(decide(f).action == ProfileCadence.Action.SEND, "an unknown interval is the 6 h default");
    }

    private static void clockMovedBack() {
        ProfileCadence.Facts f = facts(A, A);
        f.syncedAtS = NOW_S + 86_400 * 30;
        ProfileCadence.Decision d = decide(f);
        check(d.action == ProfileCadence.Action.WAIT && d.waitMs == ProfileCadence.REFRESH_SECONDS * 1000L,
                "a last success in the future counts as now (a full interval, never 30 days more): " + d);
        f.currentDigest = B;
        f.changeSeenMs = NOW_MS - 60_000;
        d = decide(f);
        check(d.action == ProfileCadence.Action.WAIT && d.waitMs == ProfileCadence.MIN_SPACING_SECONDS * 1000L, "and only the 5 min spacing for a change: " + d);
        f.syncedAtS = 0;
        check(decide(f).action == ProfileCadence.Action.SEND, "never synced at all (0) is long ago");
    }

    private static void settled() {
        check(!ProfileCadence.settled(null, null), "neither synced nor dropped");
        check(ProfileCadence.settled(A, null) && ProfileCadence.settled(null, A) && ProfileCadence.settled(A, B), "synced or dropped");
    }

    // ---- helpers ----

    private static ProfileCadence.Facts facts(String stored, String current) {
        ProfileCadence.Facts f = new ProfileCadence.Facts();
        f.storedDigest = stored;
        f.currentDigest = current;
        f.nowMs = NOW_MS;
        f.nowS = NOW_S;
        f.syncedAtS = stored == null ? 0 : NOW_S - 60;
        f.foreground = true;
        f.firstIdlePassed = true;
        return f;
    }

    private static ProfileCadence.Decision decide(ProfileCadence.Facts f) { return ProfileCadence.decide(f); }

    private static String repeatHex(char c) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 64; i++) out.append(c);
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
