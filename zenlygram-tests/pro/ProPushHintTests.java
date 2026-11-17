package org.telegram.messenger.zenlygram.pro;

import java.util.HashMap;
import java.util.Map;

/** Plain-JVM tests for the FCM entitlement hint parser (run with run-tests.sh). */
public final class ProPushHintTests {
    private static int checks;

    public static void main(String[] args) {
        Map<String, String> hint = new HashMap<>();
        hint.put(ProPushHint.KEY, "1");
        hint.put("account_id", "01HZX3Q7M9V2K4T6R8W0Y1B3C5");
        check(ProPushHint.isHint(hint), "Zenlygram hint recognized");
        check("01HZX3Q7M9V2K4T6R8W0Y1B3C5".equals(ProPushHint.accountId(hint)), "opaque ULID account id accepted as-is");

        Map<String, String> uuid = copy(hint, "account_id", "6f1c2a4e-1b2c-4d5e-8f90-123456789abc");
        check("6f1c2a4e-1b2c-4d5e-8f90-123456789abc".equals(ProPushHint.accountId(uuid)), "no single id format is assumed");

        Map<String, String> granting = copy(hint, "ent", "[\"vault\",\"tasks\"]");
        granting.put("until", "2099-01-01T00:00:00Z");
        check("01HZX3Q7M9V2K4T6R8W0Y1B3C5".equals(ProPushHint.accountId(granting)),
                "grant/expiry fields are ignored; only the account id is read");

        check(ProPushHint.accountId(copy(hint, ProPushHint.KEY, "true")) == null, "only the exact value 1 triggers");
        check(ProPushHint.isHint(copy(hint, ProPushHint.KEY, "0")), "malformed hint is still consumed, not passed to Telegram");
        check(ProPushHint.accountId(copy(hint, "account_id", "")) == null, "empty account id ignored");
        check(ProPushHint.accountId(copy(hint, "account_id", "a b")) == null, "whitespace account id ignored");
        check(ProPushHint.accountId(copy(hint, "account_id", "акк")) == null, "non-ASCII account id ignored");
        check(ProPushHint.accountId(copy(hint, "account_id", repeat("a", 129))) == null, "over-long account id ignored");
        check(ProPushHint.accountId(copy(hint, "account_id", repeat("a", 128))) != null, "128-char account id accepted");
        Map<String, String> missing = new HashMap<>(hint);
        missing.remove("account_id");
        check(ProPushHint.accountId(missing) == null, "missing account id ignored");

        Map<String, String> telegram = new HashMap<>();
        telegram.put("p", "encrypted-telegram-payload");
        check(!ProPushHint.isHint(telegram) && !ProPushHint.isHint(null), "Telegram pushes are not intercepted");

        check(ProPushHint.due(-1, 0), "first hint refreshes immediately");
        check(!ProPushHint.due(1000, 1000 + ProPushHint.MIN_INTERVAL_MS - 1), "hint flood is throttled");
        check(ProPushHint.due(1000, 1000 + ProPushHint.MIN_INTERVAL_MS), "throttle reopens at exact boundary");
        coalescing();
        durableSequence();
        System.out.println("Pro push hint: " + checks + " checks passed");
    }

    /**
     * Simulates the per-account driver in ZenlyPro: delay 0 refreshes now, > 0 schedules one trailing
     * refresh, -1 coalesces. A purchase hint then a refund hint 10 s later must yield two refreshes.
     */
    private static void coalescing() {
        ProPushHint.Throttle throttle = ProPushHint.Throttle.IDLE;
        int refreshes = 0;
        long trailingAt = -1;
        long[] hints = {0, 10000, 20000, 30000};
        for (long now : hints) {
            long delay = throttle.delayFor(now);
            if (delay == 0) { throttle = throttle.refreshed(now); refreshes++; }
            else if (delay > 0) { throttle = throttle.trailingScheduled(); trailingAt = now + delay; }
        }
        check(refreshes == 1 && trailingAt == ProPushHint.MIN_INTERVAL_MS, "later hints coalesce into one trailing refresh at 60 s");
        check(throttle.trailingScheduled && throttle.delayFor(40000) == -1, "only one trailing refresh is ever scheduled");
        throttle = throttle.refreshed(trailingAt);
        refreshes++;
        check(refreshes == 2 && !throttle.trailingScheduled, "the refund hint is still applied, not dropped");
        check(throttle.delayFor(trailingAt + 1000) > 0, "a hint right after the trailing refresh schedules another");
        ProPushHint.Throttle immediate = ProPushHint.Throttle.IDLE.refreshed(0).trailingScheduled().refreshed(ProPushHint.MIN_INTERVAL_MS);
        check(!immediate.trailingScheduled, "an immediate refresh at the boundary cancels the pending trailing one");
    }

    /**
     * Revocation-latency race: hint1 -> refresh R1 starts -> hint2 (coalesced) -> R1 succeeds -> process
     * dies before the trailing refresh. Only the persisted (received, consumed) pair survives restart.
     */
    private static void durableSequence() {
        ProPushHint.Pending state = ProPushHint.Pending.NONE;
        check(!state.pending(), "fresh binding has nothing pending");
        state = state.received();                       // hint1
        long r1StartedAt = state.received;              // R1 captures the sequence when it starts
        state = state.received();                       // hint2 lands while R1 is in flight
        state = state.consumedThrough(r1StartedAt);     // R1 succeeds
        ProPushHint.Pending restarted = ProPushHint.Pending.of(state.received, state.consumed); // persisted, reloaded
        check(restarted.pending(), "hint2 stays pending after R1 success and a process restart");
        long r2StartedAt = restarted.received;
        ProPushHint.Pending cleared = restarted.consumedThrough(r2StartedAt);
        check(!cleared.pending(), "a refresh that started after hint2 clears it");

        ProPushHint.Pending one = ProPushHint.Pending.NONE.received();
        check(!one.consumedThrough(one.received).pending(), "normal case: one hint, one successful refresh, nothing pending");
        check(one.consumedThrough(0) == one, "a refresh that started before any hint consumes nothing");
        ProPushHint.Pending later = one.consumedThrough(1);
        check(later.consumedThrough(0) == later, "a slower, older refresh finishing later never rewinds consumption");

        ProPushHint.Pending bindingA = ProPushHint.Pending.NONE.received().received().received();
        ProPushHint.Pending bindingB = ProPushHint.Pending.NONE.received();
        check(bindingB.consumedThrough(bindingA.received) == bindingB,
                "a sequence from another binding (never issued here) cannot clear this binding's hint");
        check(bindingB.pending() && bindingA.pending(), "each binding keeps its own pending state");
        check(!ProPushHint.Pending.of(-5, 7).pending() && ProPushHint.Pending.of(3, 9).consumed == 3,
                "corrupt persisted values are clamped, never invent a consumed future");
    }

    private static Map<String, String> copy(Map<String, String> source, String key, String value) {
        Map<String, String> result = new HashMap<>(source);
        result.put(key, value);
        return result;
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
