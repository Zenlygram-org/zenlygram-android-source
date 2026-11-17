package org.telegram.messenger.zenlygram.pro;

/**
 * Pure deadline math for the per-account access-change signal. It does not change ProPolicy: it
 * only lists every instant at which ProPolicy.evaluate can change its answer for the given inputs
 * (genuine account_until, signed exp + grace hard stop, and in paid mode the start and end of the
 * local grace), so the caller can re-evaluate exactly then instead of polling. Free mode
 * has no access deadline: only the end of a genuine paid period it displays.
 *
 * The local grace length is an input like the mode (the last accepted signed policy's, see
 * ProPolicy.evaluate): a newly accepted policy means new inputs, so the caller asks again and re-arms.
 * A length of 0 has no local grace and therefore neither of its two boundaries.
 */
public final class ProAccessSchedule {
    /** Re-check at least this often (seconds) even without a boundary: trusted time can be re-anchored. */
    static final long MAX_WAIT = 6 * 3600L;
    static final long NONE = Long.MAX_VALUE;

    private ProAccessSchedule() { }

    /** Earliest ProPolicy boundary strictly after now (epoch seconds), or NONE. */
    public static long nextBoundary(ProPolicy.Claims claims, long now, long firstLaunch, ProPolicy.Mode mode, int localGraceDays) {
        long next = NONE;
        if (claims != null && claims.account.present) {
            next = earliestAfter(next, claims.account.until, now);
            next = earliestAfter(next, claims.hardStop(), now);
        }
        if (mode == ProPolicy.Mode.PAID && firstLaunch > 0 && localGraceDays > 0) {
            next = earliestAfter(next, firstLaunch, now);
            next = earliestAfter(next, ProPolicy.localGraceEnd(firstLaunch, localGraceDays), now);
        }
        return next;
    }

    /** Seconds until the caller should re-evaluate: the next boundary, bounded by MAX_WAIT, at least 1. */
    public static long delaySeconds(long nextBoundary, long now) {
        if (nextBoundary == NONE || nextBoundary - now > MAX_WAIT) return MAX_WAIT;
        return Math.max(1, nextBoundary - now);
    }

    private static long earliestAfter(long current, long candidate, long now) {
        return candidate > now && candidate < current ? candidate : current;
    }
}
