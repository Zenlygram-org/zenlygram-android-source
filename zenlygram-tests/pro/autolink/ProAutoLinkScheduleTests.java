package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.ProAutoLinkMachine.Outcome;
import org.telegram.messenger.zenlygram.pro.ProAutoLinkSchedule.Blocker;
import org.telegram.messenger.zenlygram.pro.ProAutoLinkSchedule.Facts;
import org.telegram.messenger.zenlygram.pro.ProAutoLinkSchedule.State;

import java.util.Arrays;
import java.util.Random;

/**
 * Automatic link eligibility and backoff (ProAutoLinkSchedule, design 50 §8.2/§8.4): every
 * precondition (a binding loss ZenlyCustomer has not applied yet included), the Unlink and
 * consent_stale suppressions against the grant time, the ±20 % ladder, the attest-unavailable
 * streak and its resets, Retry-After, the 24 h bind_conflict pause, the daily bind/start cap,
 * STALE, the clock clamp and the wake time. Run with run-tests.sh.
 */
public final class ProAutoLinkScheduleTests {
    private static int checks;
    private static final long NOW = 1_792_990_000L;
    private static final long GRANT = NOW - 3_600;
    private static final Random MIDDLE = fixed(0.5);

    public static void main(String[] args) {
        eligibility();
        unlinkAndStaleSuppression();
        firstIdle();
        ladder();
        attestStreak();
        retryAfterAndPause();
        dailyCap();
        neutralOutcomes();
        clockClamp();
        waits();
        values();
        System.out.println("Pro auto-link schedule: " + checks + " checks passed");
    }

    private static Random fixed(double value) {
        return new Random() {
            private static final long serialVersionUID = 1L;
            @Override public double nextDouble() { return value; }
        };
    }

    /** Foreground for a minute, jitter 5 s, granted an hour ago: eligible unless a test changes something. */
    private static Facts ready() { return Facts.of(GRANT, 60_000, 5_000); }

    private static Blocker blocker(State state, Facts facts) { return ProAutoLinkSchedule.blocker(state, facts, NOW); }

    private static void eligibility() {
        check(blocker(State.INITIAL, ready()) == Blocker.NONE && ProAutoLinkSchedule.eligible(State.INITIAL, ready(), NOW),
                "every precondition holds: eligible");
        check(blocker(State.INITIAL, ready().foreground(false)) == Blocker.BACKGROUND, "only while the main app is in the foreground");
        check(blocker(State.INITIAL, ready().activated(false)) == Blocker.NOT_ACTIVATED, "only an activated account");
        check(blocker(State.INITIAL, ready().linked(true)) == Blocker.LINKED, "never an account linked at this origin");
        check(blocker(State.INITIAL, ready().configured(false)) == Blocker.NOT_CONFIGURED, "never while unconfigured or test-held");
        check(blocker(State.INITIAL, Facts.of(0, 60_000, 5_000)) == Blocker.NO_GRANT,
                "no effective grant (none, declined, withdrawn, rejected, deletion pending, binding loss after it): never");
        // The gate (ZenlyCustomer.hasEffectiveGrant) applies the binding loss and a pending deletion: it answers 0 for a
        // loss at or after decided_at or a deletion in progress, and the grant time for a loss before the grant.
        check(blocker(State.INITIAL, Facts.of(0, 60_000, 5_000).manualLinkPending(false)) == Blocker.NO_GRANT,
                "a binding loss after the latest grant: never, until a new grant or a manual link");
        check(blocker(State.INITIAL, Facts.of(GRANT, 60_000, 5_000)) == Blocker.NONE,
                "a binding loss before the latest grant (the grant answers it): eligible");
        check(blocker(State.INITIAL, ready().proScreenAlive(true)) == Blocker.PRO_SCREEN, "never while the account's Pro screen is alive");
        check(blocker(State.INITIAL, ready().manualLinkPending(true)) == Blocker.MANUAL_LINK, "never while a manual link is pending");
        check(blocker(State.INITIAL, ready().inFlight(true)) == Blocker.IN_FLIGHT, "at most one automatic link in flight per install");
        check(blocker(State.INITIAL, ready().linked(true).proScreenAlive(true)) == Blocker.LINKED, "a linked account reports LINKED first");
        check(!Blocker.MANUAL_LINK.timed() && !Blocker.NO_GRANT.timed() && Blocker.BACKOFF.timed() && Blocker.FIRST_IDLE.timed()
                && Blocker.PAUSED.timed() && Blocker.DAILY_CAP.timed(), "only waits are lifted by time alone");

        // A server 401 just ended the binding and ZenlyCustomer has not applied that loss yet (ZenlyPro.bindingLossPending):
        // the slot reads unlinked while the grant is still effective, so only this fact holds the automatic link back.
        check(blocker(State.INITIAL, ready().lossPending(true)) == Blocker.LOSS_PENDING,
                "a binding loss ZenlyCustomer has not applied yet: no automatic link starts");
        check(ProAutoLinkSchedule.waitMs(State.INITIAL, ready().lossPending(true), NOW) == -1 && !Blocker.LOSS_PENDING.timed(),
                "a pending loss arms no wake timer: its delivery evaluates the account again");
        check(blocker(State.INITIAL, ready().linked(true).lossPending(true)) == Blocker.LINKED, "a linked account reports LINKED first");
        Facts pending = ready().lossPending(true);
        String[] modifiers = {"foreground", "activated", "linked", "configured", "proScreenAlive", "manualLinkPending", "inFlight"};
        Facts[] unchanged = {pending.foreground(true), pending.activated(true), pending.linked(false), pending.configured(true),
                pending.proScreenAlive(false), pending.manualLinkPending(false), pending.inFlight(false)};
        for (int i = 0; i < unchanged.length; i++) {
            check(blocker(State.INITIAL, unchanged[i]) == Blocker.LOSS_PENDING, "the " + modifiers[i] + " modifier keeps the pending loss");
        }
        Facts[] later = {pending.configured(false), pending.proScreenAlive(true), pending.manualLinkPending(true), pending.inFlight(true),
                Facts.of(0, 60_000, 5_000).lossPending(true), Facts.of(GRANT, 1_000, 5_000).lossPending(true)};
        for (Facts facts : later) {
            check(blocker(State.INITIAL, facts) == Blocker.LOSS_PENDING, "a pending loss is reported before every later precondition");
        }
        check(blocker(State.INITIAL, ready().lossPending(false)) == Blocker.NONE, "no pending loss: eligible");
    }

    private static void unlinkAndStaleSuppression() {
        State unlinked = ProAutoLinkSchedule.userUnlinked(State.INITIAL, NOW - 60);
        check(blocker(unlinked, ready()) == Blocker.USER_UNLINKED, "a user Unlink after the latest grant suppresses automatic links");
        check(blocker(unlinked, Facts.of(NOW - 30, 60_000, 5_000)) == Blocker.NONE, "a grant decided after the Unlink lifts it");
        check(blocker(ProAutoLinkSchedule.userUnlinked(State.INITIAL, GRANT - 1), ready()) == Blocker.NONE,
                "an Unlink before the latest grant does not suppress");
        check(blocker(ProAutoLinkSchedule.userUnlinked(State.INITIAL, GRANT), ready()) == Blocker.USER_UNLINKED,
                "an Unlink in the grant's own second suppresses (user_unlinked_at < decided_at is required)");
        check(ProAutoLinkSchedule.userUnlinked(unlinked, NOW - 600) == unlinked, "an earlier time never moves the Unlink back");
        State relinked = ProAutoLinkSchedule.manualLinked(unlinked);
        check(relinked.userUnlinkedAt == 0 && blocker(relinked, ready()) == Blocker.NONE, "a manual link success clears the Unlink");

        State stale = ProAutoLinkSchedule.staleGrant(State.INITIAL, GRANT);
        check(blocker(stale, ready()) == Blocker.CONSENT_STALE, "409 consent_stale: the refused grant never links again");
        check(blocker(stale, Facts.of(GRANT - 100, 60_000, 5_000)) == Blocker.CONSENT_STALE, "nor does any older grant");
        check(blocker(stale, Facts.of(GRANT + 1, 60_000, 5_000)) == Blocker.NONE, "a new grant links again");
        check(ProAutoLinkSchedule.staleGrant(stale, GRANT - 50) == stale, "an older refused grant changes nothing");
        check(ProAutoLinkSchedule.manualLinked(stale).staleGrantAt == GRANT, "a manual link never makes the refused grant usable");
        State afterStale = ProAutoLinkSchedule.after(stale, Outcome.CONSENT_STALE, NOW, 0, MIDDLE);
        check(afterStale == stale, "CONSENT_STALE has no ladder: only the refused grant is recorded");
        check(ProAutoLinkSchedule.after(stale, Outcome.SUCCESS, NOW, 0, MIDDLE).staleGrantAt == GRANT, "success keeps the refused grant");
    }

    private static void firstIdle() {
        check(blocker(State.INITIAL, Facts.of(GRANT, -1, 0).foreground(true)) == Blocker.FIRST_IDLE, "never before the first foreground");
        check(blocker(State.INITIAL, Facts.of(GRANT, 7_999, 0)) == Blocker.FIRST_IDLE, "never in the first 8 s after the first foreground");
        check(blocker(State.INITIAL, Facts.of(GRANT, 8_000, 0)) == Blocker.NONE, "from 8 s on with no jitter");
        check(blocker(State.INITIAL, Facts.of(GRANT, 20_000, 12_500)) == Blocker.FIRST_IDLE, "the per-install jitter adds to the idle");
        check(blocker(State.INITIAL, Facts.of(GRANT, 20_500, 12_500)) == Blocker.NONE, "and ends exactly at 8 s + jitter");
        check(ProAutoLinkSchedule.waitMs(State.INITIAL, Facts.of(GRANT, 5_000, 2_000), NOW) == 5_000,
                "the wake time is the rest of the idle");
        check(ProAutoLinkSchedule.MAX_JITTER_MS == 30_000 && ProAutoLinkSchedule.FIRST_IDLE_MS == 8_000, "8 s idle, jitter up to 30 s");
    }

    private static void ladder() {
        long[] steps = {60, 300, 1_800, 7_200, 21_600, 86_400, 86_400, 86_400};
        State state = State.INITIAL;
        for (int i = 0; i < steps.length; i++) {
            state = ProAutoLinkSchedule.after(state, Outcome.ATTEST_FAILED, NOW, 0, MIDDLE);
            check(state.failures == i + 1 && state.nextAt == NOW + steps[i], "failure " + (i + 1) + " waits " + steps[i] + " s");
            check(blocker(state, ready()) == Blocker.BACKOFF, "inside the backoff: not eligible");
            check(ProAutoLinkSchedule.blocker(state, ready(), state.nextAt) == Blocker.NONE, "eligible again at next_at");
        }
        State low = ProAutoLinkSchedule.after(State.INITIAL, Outcome.OFFLINE, NOW, 0, fixed(0.0));
        State high = ProAutoLinkSchedule.after(State.INITIAL, Outcome.POLL_TIMEOUT, NOW, 0, fixed(0.999999));
        check(low.nextAt == NOW + 48 && high.nextAt == NOW + 72, "±20 %: 60 s becomes 48 s … 72 s");
        for (int i = 0; i < 200; i++) {
            State random = ProAutoLinkSchedule.after(State.INITIAL, Outcome.FAILED, NOW, 0, new Random(i));
            long wait = random.nextAt - NOW;
            check(wait >= 48 && wait <= 72, "every jittered first step stays within ±20 %");
        }
        State day = State.of(9, 0, 0, 0, null, 0, 0, null);
        long wait = ProAutoLinkSchedule.after(day, Outcome.FAILED, NOW, 0, fixed(0.0)).nextAt - NOW;
        check(wait == Math.round(86_400 * 0.8), "the ladder stays at 24 h (±20 %) for every further failure");
        State success = ProAutoLinkSchedule.after(day, Outcome.SUCCESS, NOW, 0, MIDDLE);
        check(success.failures == 0 && success.attestStreak == 0 && success.nextAt == 0 && success.pausedUntil == 0,
                "SUCCESS resets the failures, the streak and every wait");
        check(ProAutoLinkSchedule.after(State.INITIAL, Outcome.FAILED, NOW, 0, MIDDLE).attestStreak == 0, "ladder failures leave the streak");
    }

    private static void attestStreak() {
        long[] steps = {6 * 3_600, 24 * 3_600, 72 * 3_600, 7 * 86_400, 7 * 86_400};
        State state = State.of(2, 0, 0, 0, null, 0, 0, "1.0.0");
        for (int i = 0; i < steps.length; i++) {
            state = ProAutoLinkSchedule.after(state, Outcome.ATTEST_UNAVAILABLE, NOW, 0, fixed(0.0));
            check(state.attestStreak == i + 1 && state.nextAt == NOW + steps[i],
                    "attest unavailable " + (i + 1) + ": " + steps[i] + " s, never jittered shorter");
            check(state.failures == 2, "the failure count is unchanged by attest unavailable");
        }
        State sameVersion = ProAutoLinkSchedule.loaded(state, "1.0.0", NOW);
        check(sameVersion == state, "the same app version keeps the streak");
        State updated = ProAutoLinkSchedule.loaded(state, "1.1.0", NOW);
        check(updated.attestStreak == 0 && "1.1.0".equals(updated.appVersion), "an app version change resets the streak");
        check(ProAutoLinkSchedule.after(updated, Outcome.ATTEST_UNAVAILABLE, NOW, 0, MIDDLE).nextAt == NOW + 6 * 3_600,
                "after the reset the next attest unavailable waits 6 h again");
        check(ProAutoLinkSchedule.manualLinked(state).attestStreak == 0, "a manual link success resets the streak");
        check(ProAutoLinkSchedule.loaded(State.INITIAL, "1.0.0", NOW).appVersion.equals("1.0.0"), "the first load records the version");
        check(ProAutoLinkSchedule.loaded(state, null, NOW) == state, "an unknown current version changes nothing");
    }

    private static void retryAfterAndPause() {
        State limited = ProAutoLinkSchedule.after(State.INITIAL, Outcome.RATE_LIMITED, NOW, 600, MIDDLE);
        check(limited.nextAt == NOW + 600 && limited.failures == 1, "429: Retry-After wins over a shorter ladder step");
        State shortRetry = ProAutoLinkSchedule.after(State.of(3, 0, 0, 0, null, 0, 0, null), Outcome.RATE_LIMITED, NOW, 5, MIDDLE);
        check(shortRetry.nextAt == NOW + 7_200, "429: the ladder wins over a shorter Retry-After");
        State paused = ProAutoLinkSchedule.after(State.INITIAL, Outcome.BIND_PAUSED, NOW, 3_600, MIDDLE);
        check(paused.nextAt == NOW + 3_600, "503 bind_paused: Retry-After or the ladder, whichever is longer");
        State noHeader = ProAutoLinkSchedule.after(State.INITIAL, Outcome.BIND_PAUSED, NOW, 0, MIDDLE);
        check(noHeader.nextAt == NOW + 60, "503 bind_paused without Retry-After: the ladder");
        State conflict = ProAutoLinkSchedule.after(State.INITIAL, Outcome.BIND_CONFLICT, NOW, 0, MIDDLE);
        check(conflict.pausedUntil == NOW + 86_400 && conflict.failures == 0 && conflict.nextAt == 0, "409 bind_conflict pauses 24 h");
        check(blocker(conflict, ready()) == Blocker.PAUSED, "paused: not eligible");
        check(ProAutoLinkSchedule.blocker(conflict, ready(), NOW + 86_400) == Blocker.NONE, "eligible when the pause ends");
    }

    private static void dailyCap() {
        State state = State.INITIAL;
        for (int i = 0; i < 5; i++) state = ProAutoLinkSchedule.started(state, NOW - 20_000 + i * 1_000);
        check(blocker(state, ready()) == Blocker.NONE, "5 bind/start in 24 h: one more is allowed");
        state = ProAutoLinkSchedule.started(state, NOW - 100);
        check(state.starts().length == 6 && blocker(state, ready()) == Blocker.DAILY_CAP, "the 6th bind/start reaches the daily cap");
        long freeAt = NOW - 20_000 + 86_400;
        check(ProAutoLinkSchedule.blocker(state, ready(), freeAt - 1) == Blocker.DAILY_CAP
                && ProAutoLinkSchedule.blocker(state, ready(), freeAt) == Blocker.NONE, "the cap frees when the oldest start is 24 h old");
        State later = ProAutoLinkSchedule.started(state, NOW + 90_000);
        check(later.starts().length == 1 && later.starts()[0] == NOW + 90_000, "starts older than 24 h are dropped");
        State outcomeKept = ProAutoLinkSchedule.after(state, Outcome.SUCCESS, NOW, 0, MIDDLE);
        check(Arrays.equals(outcomeKept.starts(), state.starts()), "every outcome keeps the starts: the cap counts bind rows");
        State many = State.INITIAL;
        for (int i = 0; i < 100; i++) many = ProAutoLinkSchedule.started(many, NOW - 50_000 + i);
        check(many.starts().length <= ProAutoLinkSchedule.MAX_STARTS_KEPT, "the stored starts stay bounded");
    }

    private static void neutralOutcomes() {
        State state = State.of(3, 1, NOW - 10, 0, new long[]{NOW - 500}, 0, 0, "1.0.0");
        check(ProAutoLinkSchedule.after(state, Outcome.STALE, NOW, 999, MIDDLE) == state, "STALE: no penalty, nothing changes");
        State started = ProAutoLinkSchedule.started(state, NOW);
        check(started.failures == 3 && started.attestStreak == 1 && started.nextAt == NOW - 10, "a start changes only the starts");
    }

    private static void clockClamp() {
        State future = State.of(2, 0, NOW + 30 * 86_400, 0, null, 0, 0, "1.0.0");
        State clamped = ProAutoLinkSchedule.loaded(future, "1.0.0", NOW);
        check(clamped.nextAt == NOW + 300, "a next_at beyond 7 d + 1 h (the clock moved back) becomes now + the current step");
        State streak = State.of(0, 3, NOW + 20 * 86_400, 0, null, 0, 0, "1.0.0");
        check(ProAutoLinkSchedule.loaded(streak, "1.0.0", NOW).nextAt == NOW + 3 * 86_400, "the attest streak's step counts too");
        State week = State.of(0, 4, NOW + 7 * 86_400, 0, null, 0, 0, "1.0.0");
        check(ProAutoLinkSchedule.loaded(week, "1.0.0", NOW) == week, "a genuine 7 d wait is kept");
        State pause = State.of(0, 0, 0, NOW + 40 * 86_400, null, 0, 0, "1.0.0");
        check(ProAutoLinkSchedule.loaded(pause, "1.0.0", NOW).pausedUntil == NOW + 86_400, "a pause beyond the bound becomes 24 h");
        State futureStarts = State.of(0, 0, 0, 0, new long[]{NOW + 5_000, NOW - 100}, 0, 0, "1.0.0");
        long[] starts = ProAutoLinkSchedule.loaded(futureStarts, "1.0.0", NOW).starts();
        check(starts.length == 2 && starts[0] == NOW - 100 && starts[1] == NOW, "a start in the future counts from now");
    }

    private static void waits() {
        State backoff = ProAutoLinkSchedule.after(State.INITIAL, Outcome.FAILED, NOW, 0, MIDDLE);
        check(ProAutoLinkSchedule.waitMs(backoff, ready(), NOW) == 60_000, "backoff: wake at next_at");
        check(ProAutoLinkSchedule.waitMs(State.INITIAL, ready(), NOW) == 0, "eligible: no wait");
        check(ProAutoLinkSchedule.waitMs(backoff, ready().proScreenAlive(true), NOW) == -1, "an event, not a timer, lifts the Pro screen");
        check(ProAutoLinkSchedule.waitMs(backoff, Facts.of(0, 60_000, 0), NOW) == -1, "no timer without a grant");
        State both = State.of(1, 0, NOW + 100, NOW + 900, null, 0, 0, null);
        check(ProAutoLinkSchedule.waitMs(both, ready(), NOW) == 900_000, "the latest of several waits");
        check(ProAutoLinkSchedule.waitMs(backoff, Facts.of(GRANT, 1_000, 0), NOW) == 60_000, "first idle and backoff: the longer one");
    }

    private static void values() {
        State state = State.of(-3, -1, -5, -6, new long[]{-1, 0, NOW}, -7, -8, "x");
        check(state.failures == 0 && state.attestStreak == 0 && state.nextAt == 0 && state.pausedUntil == 0 && state.userUnlinkedAt == 0
                && state.staleGrantAt == 0 && state.starts().length == 1, "stored values are never negative; bad starts are dropped");
        check(State.of(1, 2, 3, 4, new long[]{9, 5}, 6, 7, "v").equals(State.of(1, 2, 3, 4, new long[]{5, 9}, 6, 7, "v")),
                "equal states compare equal (starts sorted)");
        check(!State.of(1, 2, 3, 4, null, 6, 7, "v").equals(State.of(1, 2, 3, 4, null, 6, 7, "w")), "the app version is part of the state");
        long[] copy = State.of(0, 0, 0, 0, new long[]{NOW}, 0, 0, null).starts();
        copy[0] = 1;
        check(State.of(0, 0, 0, 0, new long[]{NOW}, 0, 0, null).starts()[0] == NOW, "starts() hands out a copy");
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
}
