package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plain-JVM checks of the customer routes' retry timing (contract §2.0, §6.5; design 50 §7.5, §11.6): the 30 s → 6 h
 * full-jitter ladder, Retry-After winning, 409 in flight, the reset on success, and the installation-wide budget — a
 * sliding hour per route at 80 % of the server's per-device limit that never exceeds it for 1..8 accounts hammering at
 * random, with a quarter kept for the selected account. Run with run-tests.sh.
 */
public final class CustomerBackoffTests {
    private static int checks;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) {
        ladder();
        state();
        budgetLimits();
        budgetWindow();
        budgetNeverExceedsEightyPercent();
        selectedAccountFirst();
        System.out.println("Customer backoff: " + checks + " checks passed");
    }

    private static void ladder() {
        long[] caps = {30, 30, 60, 120, 240, 480, 960, 1920, 3840, 7680, 15360, 21600, 21600};
        for (int failures = 0; failures < caps.length; failures++) {
            check(CustomerBackoff.capSeconds(failures) == caps[failures], "cap after " + failures + " failures = " + caps[failures]);
        }
        check(CustomerBackoff.capSeconds(1000) == 21600 && CustomerBackoff.capSeconds(Integer.MAX_VALUE) == 21600, "never above 6 h");
        Random random = new Random(3);
        for (int failures = 1; failures <= 20; failures++) {
            long cap = CustomerBackoff.capSeconds(failures);
            for (int i = 0; i < 200; i++) {
                long delay = CustomerBackoff.delaySeconds(failures, 0, random.nextDouble());
                check(delay >= 0 && delay < cap || cap == 0, "full jitter within [0, cap)");
            }
            check(CustomerBackoff.delaySeconds(failures, 0, 0) == 0, "the jitter can be zero");
            check(CustomerBackoff.delaySeconds(failures, 0, 0.9999999) == cap - 1, "and up to the cap");
        }
        check(CustomerBackoff.delaySeconds(1, 120, 0.5) == 120 && CustomerBackoff.delaySeconds(12, 7, 0.9) == 7, "Retry-After wins");
        check(CustomerBackoff.delaySeconds(3, 999999, 0) == 21600, "a Retry-After is capped at 6 h");
        check(CustomerBackoff.delaySeconds(3, 0, Double.NaN) == 0 && CustomerBackoff.delaySeconds(3, 0, -1) == 0
                && CustomerBackoff.delaySeconds(3, 0, 1.0) == 0, "a unit outside [0, 1) counts as 0");
    }

    private static void state() {
        CustomerBackoff.State idle = CustomerBackoff.State.IDLE;
        check(idle.failures == 0 && idle.ready(0) && idle.waitMs(5) == 0, "idle: ready");
        CustomerBackoff.State first = idle.failed(0, 0.5, 1_000);
        check(first.failures == 1 && first.notBeforeMs == 1_000 + 15_000 && !first.ready(15_999) && first.ready(16_000), "half of 30 s");
        CustomerBackoff.State second = first.failed(0, 0.5, 16_000);
        check(second.failures == 2 && second.notBeforeMs == 16_000 + 30_000, "half of 60 s");
        CustomerBackoff.State retryAfter = second.failed(90, 0.99, 50_000);
        check(retryAfter.failures == 3 && retryAfter.notBeforeMs == 50_000 + 90_000, "Retry-After wins over the jitter");
        CustomerOutcome ok = CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 200, null, null, false);
        check(retryAfter.after(ok, 0, 0.5, 60_000) == CustomerBackoff.State.IDLE, "success resets");
        CustomerOutcome inFlight = CustomerOutcome.of(CustomerOutcome.Route.DELETE, 409, "idempotency_in_flight", null, false);
        CustomerBackoff.State soon = second.after(inFlight, 0, 0.5, 10_000);
        check(soon.failures == 2 && soon.notBeforeMs == 10_000 + 30_000, "409 in flight: 30 s, no failure counted");
        check(second.after(inFlight, 45, 0.5, 10_000).notBeforeMs == 55_000, "or its Retry-After");
        for (String[] answer : new String[][]{{"429", "rate_limited"}, {"503", "profile_unavailable"}, {"404", null}, {"0", "rotation_pending"}}) {
            CustomerOutcome outcome = CustomerOutcome.of(CustomerOutcome.Route.SYNC, Integer.parseInt(answer[0]), answer[1], null, false);
            CustomerBackoff.State next = idle.after(outcome, 0, 0.5, 0);
            check(next.failures == 1 && next.notBeforeMs == 15_000, answer[0] + " counts a failure");
        }
        CustomerOutcome offline = CustomerOutcome.of(CustomerOutcome.Route.SYNC, 0, "connection_failed", null, true);
        check(idle.after(offline, 0, 0, 0).failures == 1, "offline counts a failure");
        for (String[] answer : new String[][]{{"403", "consent_required"}, {"401", "invalid_token"}, {"422", "invalid_request"},
                {"413", "payload_too_large"}, {"0", "session_changed"}}) {
            CustomerOutcome outcome = CustomerOutcome.of(CustomerOutcome.Route.EVENTS, Integer.parseInt(answer[0]), answer[1], null, false);
            check(second.after(outcome, 0, 0.5, 99) == second, answer[0] + " " + answer[1] + " leaves the backoff unchanged");
        }
        CustomerBackoff.State many = idle;
        for (int i = 0; i < 50; i++) many = many.failed(0, 0.9999999, 0);
        check(many.failures == 50 && many.notBeforeMs <= 21_600_000L, "never more than 6 h, however many failures");
    }

    private static void budgetLimits() {
        int[][] limits = {{20, 16}, {12, 9}, {10, 8}, {60, 48}};
        CustomerOutcome.Route[] routes = {CustomerOutcome.Route.CONSENTS, CustomerOutcome.Route.SYNC, CustomerOutcome.Route.ME,
                CustomerOutcome.Route.EVENTS};
        for (int i = 0; i < routes.length; i++) {
            int perHour = CustomerBackoff.InstallBudget.perHour(routes[i]);
            check(perHour == limits[i][1] && perHour == (int) Math.floor(0.8 * limits[i][0]), routes[i] + ": 80 % of " + limits[i][0] + "/h");
            int reserve = CustomerBackoff.InstallBudget.reserve(routes[i]);
            check(reserve >= 1 && reserve < perHour && reserve == (perHour + 3) / 4, routes[i] + ": a quarter kept for the selected account");
        }
        check(CustomerBackoff.InstallBudget.perHour(CustomerOutcome.Route.DELETE) == 0
                && CustomerBackoff.InstallBudget.perHour(CustomerOutcome.Route.BIND_START) == 0, "delete and bind/start are not budgeted here");
        CustomerBackoff.InstallBudget budget = new CustomerBackoff.InstallBudget();
        for (int i = 0; i < 100; i++) check(budget.tryTake(CustomerOutcome.Route.DELETE, false, i), "an unbudgeted route always passes");
        check(CustomerBackoff.INSTALL_BUDGET != null, "one shared budget for the installation");
    }

    private static void budgetWindow() {
        CustomerBackoff.InstallBudget budget = new CustomerBackoff.InstallBudget();
        CustomerOutcome.Route route = CustomerOutcome.Route.SYNC; // 9/h: 6 for other accounts, 3 kept for the selected one
        for (int i = 0; i < 6; i++) check(budget.tryTake(route, false, i * 1000L), "another account's send " + i);
        check(!budget.tryTake(route, false, 7_000), "other accounts stop at 6");
        check(budget.waitMs(route, false, 7_000) == HOUR - 7_000, "until the oldest send leaves the window");
        check(budget.tryTake(route, true, 9_000) && budget.tryTake(route, true, 10_000) && budget.tryTake(route, true, 10_500),
                "the selected account uses the reserve");
        check(!budget.tryTake(route, true, 11_000) && budget.used(route, 11_000) == 9, "and stops at 9");
        check(budget.waitMs(route, true, 11_000) == HOUR - 11_000, "the selected account waits for the oldest");
        check(!budget.tryTake(route, true, HOUR - 1), "still inside the hour");
        check(budget.tryTake(route, true, HOUR), "the oldest left: one more");
        check(budget.used(route, HOUR) == 9, "nine in the window ending now");
        check(budget.used(CustomerOutcome.Route.ME, HOUR) == 0, "routes are separate");
        check(budget.waitMs(CustomerOutcome.Route.EVENTS, false, 0) == 0, "an empty route: no wait");
        // waitMs is exact: trying at now + waitMs succeeds, one ms earlier does not.
        long now = HOUR + 1;
        long wait = budget.waitMs(route, false, now);
        check(wait > 0 && !budget.tryTake(route, false, now + wait - 1) && budget.tryTake(route, false, now + wait), "waitMs is exact");
    }

    /** 1..8 accounts sending at random for 12 hours: no route ever passes 80 % of its server limit in any hour. */
    private static void budgetNeverExceedsEightyPercent() {
        CustomerOutcome.Route[] routes = {CustomerOutcome.Route.CONSENTS, CustomerOutcome.Route.SYNC, CustomerOutcome.Route.ME,
                CustomerOutcome.Route.EVENTS};
        int[] serverLimits = {20, 12, 10, 60};
        for (int accounts = 1; accounts <= 8; accounts++) {
            for (int r = 0; r < routes.length; r++) {
                CustomerBackoff.InstallBudget budget = new CustomerBackoff.InstallBudget();
                Random random = new Random(accounts * 100 + r);
                List<Long> sends = new ArrayList<>();
                int selectedSends = 0;
                for (long now = 0; now < 12 * HOUR; now += 1 + random.nextInt(120_000)) {
                    int account = random.nextInt(accounts);
                    boolean selected = account == 0;
                    if (budget.tryTake(routes[r], selected, now)) {
                        sends.add(now);
                        if (selected) selectedSends++;
                    }
                }
                int max = 0;
                for (int i = 0, j = 0; i < sends.size(); i++) {
                    while (sends.get(i) - sends.get(j) >= HOUR) j++;
                    max = Math.max(max, i - j + 1);
                }
                check(max <= CustomerBackoff.InstallBudget.perHour(routes[r]) && max <= 0.8 * serverLimits[r],
                        accounts + " accounts on " + routes[r] + ": at most " + max + " in any hour");
                check(selectedSends > 0, accounts + " accounts on " + routes[r] + ": the selected account was served");
            }
        }
    }

    /** Others exhaust their share: the selected account still gets the reserve every hour. */
    private static void selectedAccountFirst() {
        CustomerBackoff.InstallBudget budget = new CustomerBackoff.InstallBudget();
        CustomerOutcome.Route route = CustomerOutcome.Route.EVENTS;
        int others = 0;
        for (long now = 0; now < HOUR; now += 1_000) {
            if (budget.tryTake(route, false, now)) others++;
        }
        check(others == 36, "seven other accounts together: 48 − 12 = 36 sends an hour");
        int selected = 0;
        for (long now = HOUR - 500; now < HOUR; now += 10) {
            if (budget.tryTake(route, true, now)) selected++;
        }
        check(selected == 12, "the selected account still has its 12");
        check(!budget.tryTake(route, true, HOUR - 1), "then the hour is full for everyone");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
