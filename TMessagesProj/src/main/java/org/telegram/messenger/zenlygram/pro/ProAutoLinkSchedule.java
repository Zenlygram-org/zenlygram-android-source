package org.telegram.messenger.zenlygram.pro;

import java.util.Arrays;
import java.util.Random;

/**
 * When one account may link automatically and when it tries again (design 50 §8.2, §8.4, contract
 * §6.6), pure and host-tested. Times are trusted epoch seconds (ZenlyPro.trustedNowSeconds(): the clock
 * every consent decided_at uses, so an Unlink or a refused grant compares with a grant correctly even on
 * a device whose clock is off); only the first-idle wait is elapsed milliseconds of this process.
 * ProAutoLink keeps one State per Telegram user and origin (autolink.v1.<tg>@<authority>).
 */
public final class ProAutoLinkSchedule {
    /** First idle: this long after the main app's first foreground in this process, plus the per-install jitter. */
    public static final long FIRST_IDLE_MS = 8_000, MAX_JITTER_MS = 30_000;
    /** bind/start calls per account in any 24 h (each leaves a bind row carrying the consent on the server). */
    public static final int MAX_STARTS_PER_DAY = 6;
    static final long HOUR = 3_600, DAY = 86_400;
    /** ATTEST_FAILED, POLL_TIMEOUT, OFFLINE and other failures: 60 s, 5 min, 30 min, 2 h, 6 h, then 24 h, each ±20 %. */
    static final long[] LADDER = {60, 5 * 60, 30 * 60, 2 * HOUR, 6 * HOUR, DAY};
    /** Consecutive bind/start answers without an attest offer: 6 h, 24 h, 72 h, then 7 d for every further one. */
    static final long[] ATTEST_LADDER = {6 * HOUR, DAY, 3 * DAY, 7 * DAY};
    /** 409 bind_conflict pauses the account. */
    static final long CONFLICT_PAUSE = DAY;
    /** No legitimate wait reaches this far ahead: such a stored time means the clock moved back. */
    static final long MAX_AHEAD = 7 * DAY + HOUR;
    static final double JITTER = 0.2;
    /** Bound of the stored bind/start times (only the last 24 h count). */
    static final int MAX_STARTS_KEPT = 32;

    private ProAutoLinkSchedule() { }

    /** Why an account may not start an automatic link now; NONE when it may. Time-only reasons come last. */
    public enum Blocker {
        NONE, BACKGROUND, NOT_ACTIVATED, LINKED, LOSS_PENDING, NOT_CONFIGURED, NO_GRANT, USER_UNLINKED, CONSENT_STALE,
        PRO_SCREEN, MANUAL_LINK, IN_FLIGHT, FIRST_IDLE, BACKOFF, PAUSED, DAILY_CAP;

        /** Lifted by time alone (a wake timer re-checks); anything else needs an event. */
        public boolean timed() { return this == FIRST_IDLE || this == BACKOFF || this == PAUSED || this == DAILY_CAP; }
    }

    /** Persisted per Telegram user and origin. Immutable; values are never negative. */
    public static final class State {
        public static final State INITIAL = new State(0, 0, 0, 0, new long[0], 0, 0, null);

        /** Failures counted by the ladder (and by RATE_LIMITED / BIND_PAUSED). */
        public final int failures;
        /** Consecutive ATTEST_UNAVAILABLE outcomes under appVersion. */
        public final int attestStreak;
        public final long nextAt, pausedUntil;
        /** The latest user Unlink of this account: grants decided at or before it never link automatically. */
        public final long userUnlinkedAt;
        /** decided_at of a grant bind/start refused with 409 consent_stale: it and every older grant never link again. */
        public final long staleGrantAt;
        /** The app version the attest streak was counted under; null before the first attempt. */
        public final String appVersion;
        private final long[] starts;

        private State(int failures, int attestStreak, long nextAt, long pausedUntil, long[] starts, long userUnlinkedAt,
                      long staleGrantAt, String appVersion) {
            this.failures = Math.max(0, failures);
            this.attestStreak = Math.max(0, attestStreak);
            this.nextAt = Math.max(0, nextAt);
            this.pausedUntil = Math.max(0, pausedUntil);
            this.userUnlinkedAt = Math.max(0, userUnlinkedAt);
            this.staleGrantAt = Math.max(0, staleGrantAt);
            this.appVersion = appVersion;
            long[] sorted = starts == null ? new long[0] : starts.clone();
            Arrays.sort(sorted);
            int from = Math.max(0, sorted.length - MAX_STARTS_KEPT);
            int skip = from;
            while (skip < sorted.length && sorted[skip] <= 0) skip++;
            this.starts = Arrays.copyOfRange(sorted, skip, sorted.length);
        }

        /** A state as stored (ProAutoLink's codec); invalid values are made safe (negatives → 0, starts sorted). */
        public static State of(int failures, int attestStreak, long nextAt, long pausedUntil, long[] starts,
                               long userUnlinkedAt, long staleGrantAt, String appVersion) {
            return new State(failures, attestStreak, nextAt, pausedUntil, starts, userUnlinkedAt, staleGrantAt, appVersion);
        }

        /** bind/start times, oldest first. */
        public long[] starts() { return starts.clone(); }

        State with(int failures, int attestStreak, long nextAt, long pausedUntil, long[] starts, long userUnlinkedAt,
                   long staleGrantAt, String appVersion) {
            return new State(failures, attestStreak, nextAt, pausedUntil, starts, userUnlinkedAt, staleGrantAt, appVersion);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof State)) return false;
            State that = (State) other;
            return failures == that.failures && attestStreak == that.attestStreak && nextAt == that.nextAt
                    && pausedUntil == that.pausedUntil && userUnlinkedAt == that.userUnlinkedAt && staleGrantAt == that.staleGrantAt
                    && (appVersion == null ? that.appVersion == null : appVersion.equals(that.appVersion))
                    && Arrays.equals(starts, that.starts);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(new long[]{failures, attestStreak, nextAt, pausedUntil, userUnlinkedAt, staleGrantAt,
                    appVersion == null ? 0 : appVersion.hashCode(), Arrays.hashCode(starts)});
        }

        @Override public String toString() {
            return "State{failures=" + failures + ", attestStreak=" + attestStreak + ", nextAt=" + nextAt + ", pausedUntil="
                    + pausedUntil + ", starts=" + Arrays.toString(starts) + ", userUnlinkedAt=" + userUnlinkedAt
                    + ", staleGrantAt=" + staleGrantAt + ", appVersion=" + appVersion + "}";
        }
    }

    /** What the adapter observed for one account; every modifier returns a copy. */
    public static final class Facts {
        final boolean foreground, activated, linked, lossPending, configured, proScreenAlive, manualLinkPending, inFlight;
        final long sinceFirstForegroundMs, jitterMs, grantDecidedAt;

        private Facts(boolean foreground, boolean activated, boolean linked, boolean lossPending, boolean configured,
                      boolean proScreenAlive, boolean manualLinkPending, boolean inFlight, long sinceFirstForegroundMs,
                      long jitterMs, long grantDecidedAt) {
            this.foreground = foreground;
            this.activated = activated;
            this.linked = linked;
            this.lossPending = lossPending;
            this.configured = configured;
            this.proScreenAlive = proScreenAlive;
            this.manualLinkPending = manualLinkPending;
            this.inFlight = inFlight;
            this.sinceFirstForegroundMs = sinceFirstForegroundMs;
            this.jitterMs = jitterMs;
            this.grantDecidedAt = grantDecidedAt;
        }

        /**
         * A foreground, activated, unlinked and configured account without a binding loss pending (a server 401 just
         * ended the account's binding and ZenlyCustomer has not applied that loss yet), a Pro screen, a manual link or
         * an attempt in flight, whose latest effective grant was decided at grantDecidedAt (0: no effective grant);
         * sinceFirstForegroundMs is -1 before the first foreground of this process.
         */
        public static Facts of(long grantDecidedAt, long sinceFirstForegroundMs, long jitterMs) {
            return new Facts(true, true, false, false, true, false, false, false, sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts foreground(boolean value) {
            return new Facts(value, activated, linked, lossPending, configured, proScreenAlive, manualLinkPending, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts activated(boolean value) {
            return new Facts(foreground, value, linked, lossPending, configured, proScreenAlive, manualLinkPending, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts linked(boolean value) {
            return new Facts(foreground, activated, value, lossPending, configured, proScreenAlive, manualLinkPending, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts lossPending(boolean value) {
            return new Facts(foreground, activated, linked, value, configured, proScreenAlive, manualLinkPending, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts configured(boolean value) {
            return new Facts(foreground, activated, linked, lossPending, value, proScreenAlive, manualLinkPending, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts proScreenAlive(boolean value) {
            return new Facts(foreground, activated, linked, lossPending, configured, value, manualLinkPending, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts manualLinkPending(boolean value) {
            return new Facts(foreground, activated, linked, lossPending, configured, proScreenAlive, value, inFlight,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }

        public Facts inFlight(boolean value) {
            return new Facts(foreground, activated, linked, lossPending, configured, proScreenAlive, manualLinkPending, value,
                    sinceFirstForegroundMs, jitterMs, grantDecidedAt);
        }
    }

    /**
     * The first precondition of §8.2 that fails, NONE when an automatic link may start now. LOSS_PENDING: a server 401
     * just ended the account's binding and ZenlyCustomer has not applied that loss yet (no timer: its delivery evaluates
     * the account again).
     */
    public static Blocker blocker(State state, Facts facts, long nowS) {
        if (!facts.foreground) return Blocker.BACKGROUND;
        if (!facts.activated) return Blocker.NOT_ACTIVATED;
        if (facts.linked) return Blocker.LINKED;
        if (facts.lossPending) return Blocker.LOSS_PENDING;
        if (!facts.configured) return Blocker.NOT_CONFIGURED;
        if (facts.grantDecidedAt <= 0) return Blocker.NO_GRANT;
        if (state.userUnlinkedAt >= facts.grantDecidedAt) return Blocker.USER_UNLINKED;
        if (state.staleGrantAt >= facts.grantDecidedAt) return Blocker.CONSENT_STALE;
        if (facts.proScreenAlive) return Blocker.PRO_SCREEN;
        if (facts.manualLinkPending) return Blocker.MANUAL_LINK;
        if (facts.inFlight) return Blocker.IN_FLIGHT;
        if (firstIdleRemainingMs(facts) > 0) return Blocker.FIRST_IDLE;
        if (state.nextAt > nowS) return Blocker.BACKOFF;
        if (state.pausedUntil > nowS) return Blocker.PAUSED;
        if (dailyCapFreeAt(state, nowS) > nowS) return Blocker.DAILY_CAP;
        return Blocker.NONE;
    }

    public static boolean eligible(State state, Facts facts, long nowS) {
        return blocker(state, facts, nowS) == Blocker.NONE;
    }

    /**
     * Milliseconds until time alone no longer holds this account back: 0 when it is eligible now, -1 when
     * something other than time blocks it (an event, not a timer, changes that).
     */
    public static long waitMs(State state, Facts facts, long nowS) {
        Blocker blocker = blocker(state, facts, nowS);
        if (blocker == Blocker.NONE) return 0;
        if (!blocker.timed()) return -1;
        long seconds = Math.max(Math.max(state.nextAt, state.pausedUntil), dailyCapFreeAt(state, nowS)) - nowS;
        return Math.max(firstIdleRemainingMs(facts), seconds > 0 ? seconds * 1000L : 0);
    }

    /**
     * A stored state read at nowS: an app version change resets the attest streak (and records the version);
     * a wait more than MAX_AHEAD ahead (the clock moved back) becomes now + the current step; bind/start times
     * older than 24 h are dropped and future ones count from now.
     */
    public static State loaded(State state, String appVersion, long nowS) {
        boolean versionChanged = appVersion != null && !appVersion.equals(state.appVersion);
        int streak = versionChanged ? 0 : state.attestStreak;
        long nextAt = state.nextAt > ProPolicy.saturatingAdd(nowS, MAX_AHEAD) ? nowS + currentStep(state) : state.nextAt;
        long pausedUntil = state.pausedUntil > ProPolicy.saturatingAdd(nowS, MAX_AHEAD) ? nowS + CONFLICT_PAUSE : state.pausedUntil;
        State result = state.with(state.failures, streak, nextAt, pausedUntil, recentStarts(state, nowS), state.userUnlinkedAt,
                state.staleGrantAt, versionChanged ? appVersion : state.appVersion);
        return result.equals(state) ? state : result;
    }

    /** A bind/start is sent at nowS: it counts against the daily cap for 24 h, whatever its outcome. */
    public static State started(State state, long nowS) {
        long[] recent = recentStarts(state, nowS);
        long[] next = Arrays.copyOf(recent, recent.length + 1);
        next[recent.length] = nowS;
        return state.with(state.failures, state.attestStreak, state.nextAt, state.pausedUntil, next, state.userUnlinkedAt,
                state.staleGrantAt, state.appVersion);
    }

    /**
     * The next state after an attempt's outcome (§8.4). random supplies the ±20 % ladder jitter. CONSENT_STALE
     * and STALE change nothing here: a refused grant is recorded with staleGrant().
     */
    public static State after(State state, ProAutoLinkMachine.Outcome outcome, long nowS, int retryAfterSeconds, Random random) {
        switch (outcome) {
            case SUCCESS:
                return state.with(0, 0, 0, 0, state.starts, 0, state.staleGrantAt, state.appVersion);
            case ATTEST_UNAVAILABLE: {
                int streak = state.attestStreak + 1;
                long wait = ATTEST_LADDER[Math.min(streak, ATTEST_LADDER.length) - 1];
                return state.with(state.failures, streak, nowS + wait, state.pausedUntil, state.starts, state.userUnlinkedAt,
                        state.staleGrantAt, state.appVersion);
            }
            case RATE_LIMITED:
            case BIND_PAUSED: {
                int failures = state.failures + 1;
                long wait = Math.max(Math.max(0, retryAfterSeconds), jittered(ladderStep(failures), random));
                return state.with(failures, state.attestStreak, nowS + wait, state.pausedUntil, state.starts, state.userUnlinkedAt,
                        state.staleGrantAt, state.appVersion);
            }
            case BIND_CONFLICT:
                return state.with(state.failures, state.attestStreak, state.nextAt, nowS + CONFLICT_PAUSE, state.starts,
                        state.userUnlinkedAt, state.staleGrantAt, state.appVersion);
            case CONSENT_STALE:
            case STALE:
                return state;
            default: { // ATTEST_FAILED, POLL_TIMEOUT, OFFLINE, FAILED
                int failures = state.failures + 1;
                return state.with(failures, state.attestStreak, nowS + jittered(ladderStep(failures), random), state.pausedUntil,
                        state.starts, state.userUnlinkedAt, state.staleGrantAt, state.appVersion);
            }
        }
    }

    /** 409 consent_stale refused the grant decided at grantDecidedAt: that grant and every older one never link again. */
    public static State staleGrant(State state, long grantDecidedAt) {
        if (grantDecidedAt <= state.staleGrantAt) return state;
        return state.with(state.failures, state.attestStreak, state.nextAt, state.pausedUntil, state.starts, state.userUnlinkedAt,
                grantDecidedAt, state.appVersion);
    }

    /** The user unlinked this account at nowS: no automatic link until a newer grant or a manual link. */
    public static State userUnlinked(State state, long nowS) {
        if (nowS <= state.userUnlinkedAt) return state;
        return state.with(state.failures, state.attestStreak, state.nextAt, state.pausedUntil, state.starts, nowS,
                state.staleGrantAt, state.appVersion);
    }

    /** A manual link succeeded: the Unlink suppression and the attest streak end. */
    public static State manualLinked(State state) {
        return state.with(state.failures, 0, state.nextAt, state.pausedUntil, state.starts, 0, state.staleGrantAt,
                state.appVersion);
    }

    /** The ladder step (seconds, before jitter) for the given failure count (≥ 1). */
    static long ladderStep(int failures) {
        return LADDER[Math.min(Math.max(failures, 1), LADDER.length) - 1];
    }

    /** base ± 20 %: random.nextDouble() 0 gives 0.8 × base, 0.5 gives base, values towards 1 give up to 1.2 × base. */
    static long jittered(long base, Random random) {
        double factor = 1 - JITTER + 2 * JITTER * random.nextDouble();
        return Math.max(1, Math.round(base * factor));
    }

    /** The step a clamped wait restarts from: the larger of the failure ladder's and the attest streak's. */
    static long currentStep(State state) {
        long step = state.failures > 0 ? ladderStep(state.failures) : LADDER[0];
        if (state.attestStreak > 0) step = Math.max(step, ATTEST_LADDER[Math.min(state.attestStreak, ATTEST_LADDER.length) - 1]);
        return step;
    }

    /** When the daily cap frees a bind/start (nowS or earlier when it is not reached). */
    static long dailyCapFreeAt(State state, long nowS) {
        long[] recent = recentStarts(state, nowS);
        if (recent.length < MAX_STARTS_PER_DAY) return nowS;
        return recent[recent.length - MAX_STARTS_PER_DAY] + DAY;
    }

    private static long firstIdleRemainingMs(Facts facts) {
        if (facts.sinceFirstForegroundMs < 0) return FIRST_IDLE_MS + Math.max(0, facts.jitterMs);
        return Math.max(0, FIRST_IDLE_MS + Math.max(0, facts.jitterMs) - facts.sinceFirstForegroundMs);
    }

    /** bind/start times of the last 24 h at nowS, oldest first; a time after nowS (the clock moved back) counts from now. */
    private static long[] recentStarts(State state, long nowS) {
        long[] kept = new long[state.starts.length];
        int count = 0;
        for (long start : state.starts) {
            long at = Math.min(start, nowS);
            if (at > nowS - DAY) kept[count++] = at;
        }
        long[] result = Arrays.copyOf(kept, count);
        Arrays.sort(result);
        return result;
    }
}
