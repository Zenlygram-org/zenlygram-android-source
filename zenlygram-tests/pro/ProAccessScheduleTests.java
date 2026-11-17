package org.telegram.messenger.zenlygram.pro;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Plain-JVM tests: ProAccessSchedule deadlines checked against the production ProPolicy.evaluate in
 * owner-enabled paid mode (free mode schedules no access deadline; see ProFreeFirstTests). For each
 * scenario the decision must be constant from now until just before the deadline and must change
 * exactly at it. The local grace length is an input (the accepted signed policy's local_grace_days,
 * 7 without one): its boundaries are checked at 0, 1, 7 and 30 days and across a change of length
 * in the middle of a grace. ZenlyPro's timer/emit glue (Handler, ZenlySurfaces) is not covered here.
 */
public final class ProAccessScheduleTests {
    private static int checks;
    private static final long DAY = ProPolicy.DAY;
    /** The local grace of a policy without local_grace_days (ver 1, ver 2 or none): seven days, as before. */
    private static final int WEEK = ProPolicy.DEFAULT_LOCAL_GRACE_DAYS;
    private static final long LOCAL_GRACE = WEEK * DAY;
    private static final String[] CAPS = {"vault", "chatlock", "tasks", "edithistory"};

    public static void main(String[] args) {
        signedUntilEndsAccess();
        expPlusGraceEndsBeforeUntil();
        localWindowStartAndEnd();
        signedGrantInsideLocalWindow();
        localGraceLengths();
        noLocalGraceNoBoundary();
        lengthChangesMidGrace();
        noBoundary();
        delays();
        System.out.println("Pro access schedule: " + checks + " checks passed");
    }

    /** Genuine account claims mirroring the legacy until they are minted with. */
    private static ProPolicy.Claims claims(long until, long exp, long graceDays, String... caps) {
        HashSet<String> values = new HashSet<>(Arrays.asList(caps));
        return new ProPolicy.Claims("acct", "bind", 1L, 0, exp, until, graceDays, values, ProPolicy.AccountGrant.of(values, until));
    }

    private static String decision(ProPolicy.Claims claims, long now, long firstLaunch) {
        return decision(claims, now, firstLaunch, WEEK);
    }

    private static String decision(ProPolicy.Claims claims, long now, long firstLaunch, int graceDays) {
        ProPolicy.Decision decision = ProPolicy.evaluate(claims, now, firstLaunch, true, ProPolicy.Mode.PAID, graceDays);
        StringBuilder result = new StringBuilder(decision.state.name());
        for (String cap : CAPS) result.append(decision.grants(cap) ? '1' : '0');
        return result.toString();
    }

    /** Deadline is exact: constant on [now, boundary), different at boundary. */
    private static void assertExact(ProPolicy.Claims claims, long now, long firstLaunch, long expected, String name) {
        assertExact(claims, now, firstLaunch, WEEK, expected, name);
    }

    /** The same under a local grace of {@code graceDays} days. */
    private static void assertExact(ProPolicy.Claims claims, long now, long firstLaunch, int graceDays, long expected, String name) {
        long boundary = ProAccessSchedule.nextBoundary(claims, now, firstLaunch, ProPolicy.Mode.PAID, graceDays);
        check(boundary == expected, name + ": boundary " + boundary + " != " + expected);
        String start = decision(claims, now, firstLaunch, graceDays);
        check(start.equals(decision(claims, now + (boundary - now) / 2, firstLaunch, graceDays))
                && start.equals(decision(claims, boundary - 1, firstLaunch, graceDays)), name + ": decision constant before deadline");
        check(!start.equals(decision(claims, boundary, firstLaunch, graceDays)), name + ": decision changes exactly at deadline");
    }

    private static long boundary(ProPolicy.Claims claims, long now, long firstLaunch, int graceDays) {
        return ProAccessSchedule.nextBoundary(claims, now, firstLaunch, ProPolicy.Mode.PAID, graceDays);
    }

    private static void signedUntilEndsAccess() {
        long first = 10 * DAY; // local window long over
        assertExact(claims(100 * DAY, 99 * DAY, 14, "vault", "tasks"), 50 * DAY, first, 100 * DAY, "signed until");
    }

    /** JWT exp + grace (not the displayed until) can be the real cutoff. */
    private static void expPlusGraceEndsBeforeUntil() {
        long first = 10 * DAY;
        assertExact(claims(400 * DAY, 100 * DAY, 2, "vault"), 50 * DAY, first, 102 * DAY, "exp + grace hard stop");
        assertExact(claims(400 * DAY, 100 * DAY, 0, "vault"), 50 * DAY, first, 100 * DAY, "exp without grace");
    }

    private static void localWindowStartAndEnd() {
        long first = 1000 * DAY;
        assertExact(null, first - DAY, first, first, "local window start");
        assertExact(null, first + DAY, first, first + LOCAL_GRACE, "local window end");
    }

    /** Signed grant ending inside the local window changes capabilities (ACTIVE -> LOCAL_TRIAL). */
    private static void signedGrantInsideLocalWindow() {
        long first = 1000 * DAY;
        assertExact(claims(first + DAY, first + 30 * DAY, 14, "vault"), first, first, first + DAY, "grant ends inside local window");
    }

    /** The end of the local grace is start + days x 86 400 s at every length a signed policy may set. */
    private static void localGraceLengths() {
        long first = 1000 * DAY;
        for (int days : new int[] {1, 7, 30}) {
            long end = first + days * DAY;
            String name = days + "-day grace";
            assertExact(null, first - DAY, first, days, first, name + " start");
            assertExact(null, first, first, days, end, name + " end, asked at its first second");
            assertExact(null, end - 1, first, days, end, name + " end, asked at its last second");
            check(boundary(null, end, first, days) == ProAccessSchedule.NONE, name + ": nothing left once it is over");
            // A genuine grant that ends inside the grace: its own end first, then the end of the grace.
            if (days > 1) {
                assertExact(claims(first + DAY, first + 60 * DAY, 14, "vault"), first, first, days, first + DAY, name + ": a grant ending inside it");
                assertExact(claims(first + DAY, first + 60 * DAY, 14, "vault"), first + DAY, first, days, end, name + ": then the end of the grace");
            }
        }
        check(boundary(null, first, first, WEEK) == first + LOCAL_GRACE, "the default length is the seven days of before");
    }

    /** 0 days: the local grace never grants, so neither its start nor its end is a boundary. */
    private static void noLocalGraceNoBoundary() {
        long first = 1000 * DAY;
        for (long now : new long[] {first - DAY, first - 1, first, first + 1, first + 3 * DAY, first + 7 * DAY, first + 30 * DAY}) {
            check(boundary(null, now, first, 0) == ProAccessSchedule.NONE, "0 days: no boundary at " + now);
            check("LINK_REQUIRED0000".equals(decision(null, now, first, 0)), "0 days: not active at " + now);
        }
        // Genuine grants keep their own boundaries: only these remain.
        assertExact(claims(first + DAY, first + 60 * DAY, 14, "vault"), first, first, 0, first + DAY, "0 days: a grant's end is the only boundary");
        check(boundary(claims(first + DAY, first + 60 * DAY, 14, "vault"), first + DAY, first, 0) == first + 74 * DAY,
                "0 days: after the grant's end only its exp + grace hard stop is left, no local boundary");
        check(boundary(null, first - DAY, first, -1) == ProAccessSchedule.NONE, "a length below 0 schedules nothing either");
    }

    /**
     * A newly accepted policy with another length: the inputs changed, so the caller (ZenlyPro.policyChangedLocked)
     * re-evaluates and asks for the boundary again. The start is the same; only the end moves.
     */
    private static void lengthChangesMidGrace() {
        long first = 1000 * DAY, now = first + 3 * DAY;
        check("LOCAL_TRIAL1111".equals(decision(null, now, first, 7)) && boundary(null, now, first, 7) == first + 7 * DAY,
                "day 3 of a 7-day grace: running, re-check at its end");
        // Shorter, and already behind: the running grace ends at once and nothing is left to schedule.
        check("LINK_REQUIRED0000".equals(decision(null, now, first, 2)) && boundary(null, now, first, 2) == ProAccessSchedule.NONE,
                "7 to 2 days on day 3: over at once, no boundary");
        check("LINK_REQUIRED0000".equals(decision(null, now, first, 3)) && boundary(null, now, first, 3) == ProAccessSchedule.NONE,
                "7 to 3 days exactly at the new end: over (the end is exclusive)");
        check("LINK_REQUIRED0000".equals(decision(null, now, first, 0)) && boundary(null, now, first, 0) == ProAccessSchedule.NONE,
                "7 to 0 days on day 3: over at once, no boundary");
        // Shorter, but still ahead: it keeps running and the deadline moves earlier.
        assertExact(null, now, first, 4, first + 4 * DAY, "7 to 4 days on day 3: the deadline moves to day 4");
        // Longer: the deadline moves later, from the same start.
        assertExact(null, now, first, 30, first + 30 * DAY, "7 to 30 days on day 3: the deadline moves to day 30");
        // An ended grace is revived by a longer one, and only by one long enough.
        long later = first + 10 * DAY;
        check("LINK_REQUIRED0000".equals(decision(null, later, first, 7)) && boundary(null, later, first, 7) == ProAccessSchedule.NONE,
                "day 10: a 7-day grace is over");
        check("LINK_REQUIRED0000".equals(decision(null, later, first, 10)), "7 to 10 days on day 10: still over (the end is exclusive)");
        check("LOCAL_TRIAL1111".equals(decision(null, later, first, 30)), "7 to 30 days on day 10: revived");
        assertExact(null, later, first, 30, first + 30 * DAY, "revived grace ends 30 days after the unchanged start");
        // 0 to 7: an install that met the paid policy with no grace gets one, counted from when it first met it.
        check("LINK_REQUIRED0000".equals(decision(null, now, first, 0)) && "LOCAL_TRIAL1111".equals(decision(null, now, first, 7)),
                "0 to 7 days on day 3: the grace opens, from the original start");
        check("LINK_REQUIRED0000".equals(decision(null, later, first, 0)) && "LINK_REQUIRED0000".equals(decision(null, later, first, 7)),
                "0 to 7 days on day 10: nothing opens, the 7 days from the start are over");
    }

    private static void noBoundary() {
        long first = 10 * DAY;
        check(ProAccessSchedule.nextBoundary(null, 500 * DAY, first, ProPolicy.Mode.PAID, WEEK) == ProAccessSchedule.NONE, "nothing left to change");
        check(ProAccessSchedule.nextBoundary(claims(100, 50, 0, "vault"), 500 * DAY, first, ProPolicy.Mode.PAID, WEEK) == ProAccessSchedule.NONE,
                "expired grant past every boundary");
        check(ProAccessSchedule.nextBoundary(claims(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, "vault"), 1, 0, ProPolicy.Mode.PAID, WEEK)
                == ProAccessSchedule.NONE, "saturated deadlines do not overflow into a bogus boundary");
    }

    private static void delays() {
        check(ProAccessSchedule.delaySeconds(1005, 1000) == 5, "delay until the boundary");
        check(ProAccessSchedule.delaySeconds(1000 + ProAccessSchedule.MAX_WAIT + 1, 1000) == ProAccessSchedule.MAX_WAIT,
                "far boundary re-checked within MAX_WAIT");
        check(ProAccessSchedule.delaySeconds(ProAccessSchedule.NONE, 1000) == ProAccessSchedule.MAX_WAIT, "no boundary still re-checked");
        check(ProAccessSchedule.delaySeconds(1000, 1000) == 1, "never a zero or negative delay");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
