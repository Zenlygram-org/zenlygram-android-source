package org.telegram.messenger.zenlygram.pro;

/**
 * Plain-JVM tests of the production replay/ordering rules (run with run-tests.sh). These call the
 * same ProReplayPolicy and ProPushHint.Pending functions ZenlyPro uses; they do NOT exercise
 * ZenlyPro's LOCK/Keystore/HTTP glue (replay branch wiring, key completion, follow-up GET), which
 * has no JVM seam and needs integrated smoke with a backend fake.
 */
public final class ProReplayPolicyTests {
    private static int checks;

    public static void main(String[] args) {
        replayedBodies();
        grantOrdering();
        serverTime();
        owedRefreshAfterReplay();
        System.out.println("Pro replay policy: " + checks + " checks passed");
    }

    private static void replayedBodies() {
        check(!ProReplayPolicy.adoptsBody(true), "a replayed body (possibly predating a revocation) is never adopted");
        check(ProReplayPolicy.adoptsBody(false), "a fresh body may be adopted subject to ordering");
    }

    /** Revocation at iat=200 must survive any older grant delivered later, fresh or not. */
    private static void grantOrdering() {
        check(ProReplayPolicy.acceptsGrant(100, -1), "first grant with no current grant is accepted");
        check(ProReplayPolicy.acceptsGrant(0, -1), "iat 0 accepted when nothing is current");
        check(!ProReplayPolicy.acceptsGrant(100, 200), "older paid grant cannot replace a newer revocation");
        check(!ProReplayPolicy.acceptsGrant(199, 200), "one second older is rejected");
        check(ProReplayPolicy.acceptsGrant(200, 200), "same-second reissue accepted");
        check(ProReplayPolicy.acceptsGrant(201, 200), "newer grant accepted");
        check(ProReplayPolicy.acceptsGrant(Long.MAX_VALUE, 200), "extreme newer iat accepted without overflow");
    }

    private static void serverTime() {
        check(ProReplayPolicy.trustsServerTime(false), "fresh server_time anchors the clock");
        check(!ProReplayPolicy.trustsServerTime(true), "replayed (up to 24 h old) server_time never moves the clock");
    }

    /**
     * R3: a replayed attach records an owed fresh refresh (with its key completion) before the
     * follow-up GET. If that GET fails or the process dies, the owed refresh survives; only a
     * successful fresh refresh that started after it clears it.
     */
    private static void owedRefreshAfterReplay() {
        ProPushHint.Pending session = ProPushHint.Pending.NONE;
        ProPushHint.Pending owed = session.received();             // persisted together with key completion
        long owedSequence = owed.received;
        ProPushHint.Pending afterFailedGet = ProPushHint.Pending.of(owed.received, owed.consumed); // GET failed; restart
        check(afterFailedGet.pending(), "failed follow-up GET leaves the fresh refresh owed across restart");
        check(afterFailedGet.consumedThrough(0) == afterFailedGet, "a refresh that started before the replay does not clear it");
        check(!afterFailedGet.consumedThrough(owedSequence).pending(), "successful fresh GET/refresh clears the owed refresh");
        ProPushHint.Pending hintDuringGet = owed.received();         // a push hint lands while the GET is in flight
        check(hintDuringGet.consumedThrough(owedSequence).pending(), "a hint during the follow-up GET stays owed");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
