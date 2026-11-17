package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayDeque;
import java.util.EnumMap;

/**
 * Retry timing of the customer routes (contract §6.5; design 50 §7.5, §11.6), pure and host-tested. Times are
 * monotonic milliseconds (the Android glue passes {@code SystemClock.elapsedRealtime()}), so a wall-clock change never
 * shortens or stretches a wait.
 * <ul>
 * <li>{@link State}: per (account, route), exponential backoff from 30 s to 6 h with full jitter — after the n-th
 * consecutive failure the wait is {@code rand(0, min(6 h, 30 s · 2^(n−1)))}; a {@code Retry-After} /
 * {@code retry_after} always wins; 409 {@code idempotency_in_flight} retries after its Retry-After or 30 s; a success
 * resets it.</li>
 * <li>{@link InstallBudget}: one sliding one-hour window per route shared by every account of the installation, at 80 %
 * of the server's per-device limit (contract §2.0: consents 20/h → 16, sync 12/h → 9, me 10/h → 8, events 60/h → 48;
 * the server counts per presented device id, not per account). Every send takes a token; a quarter of each window is
 * kept for the selected account, so other accounts never starve it.</li>
 * </ul>
 */
public final class CustomerBackoff {
    public static final int BASE_SECONDS = 30;
    public static final int MAX_SECONDS = 21600;
    /** 409 {@code idempotency_in_flight} without a Retry-After. */
    public static final int RETRY_SOON_SECONDS = 30;

    /** The installation's budget, shared by the event uploader and the customer coordinator. */
    public static final InstallBudget INSTALL_BUDGET = new InstallBudget();

    private CustomerBackoff() {}

    /** The jitter ceiling in seconds after the {@code failures}-th consecutive failure (1 → 30 s … 6 h). */
    public static long capSeconds(int failures) {
        int doublings = Math.max(0, Math.min(20, failures - 1));
        return Math.min((long) MAX_SECONDS, (long) BASE_SECONDS << doublings);
    }

    /**
     * The wait in seconds after the {@code failures}-th consecutive failure: {@code retryAfterSeconds} when the answer
     * named one (capped at 6 h), else {@code floor(unit · cap)} with {@code unit} uniform in [0, 1).
     */
    public static long delaySeconds(int failures, int retryAfterSeconds, double unit) {
        if (retryAfterSeconds > 0) return Math.min(MAX_SECONDS, retryAfterSeconds);
        double fraction = unit >= 0 && unit < 1 ? unit : 0;
        return (long) Math.floor(fraction * capSeconds(Math.max(1, failures)));
    }

    /** One (account, route)'s backoff; immutable. */
    public static final class State {
        public static final State IDLE = new State(0, 0);

        /** Consecutive failures so far. */
        public final int failures;
        /** Monotonic ms before which the route is not tried again (0 = now). */
        public final long notBeforeMs;

        private State(int failures, long notBeforeMs) {
            this.failures = failures;
            this.notBeforeMs = notBeforeMs;
        }

        /** The route may be tried at {@code nowMs}. */
        public boolean ready(long nowMs) { return nowMs >= notBeforeMs; }

        /** Milliseconds left until {@link #ready} (0 when ready). */
        public long waitMs(long nowMs) { return Math.max(0, notBeforeMs - nowMs); }

        /** One more failure at {@code nowMs}. */
        public State failed(int retryAfterSeconds, double unit, long nowMs) {
            int next = failures >= Integer.MAX_VALUE - 1 ? failures : failures + 1;
            return new State(next, nowMs + delaySeconds(next, retryAfterSeconds, unit) * 1000L);
        }

        /** 409 {@code idempotency_in_flight}: again after the Retry-After or 30 s (no failure counted). */
        public State retrySoon(int retryAfterSeconds, long nowMs) {
            long seconds = retryAfterSeconds > 0 ? Math.min(MAX_SECONDS, retryAfterSeconds) : RETRY_SOON_SECONDS;
            return new State(failures, nowMs + seconds * 1000L);
        }

        /**
         * The state after an answer: {@link CustomerOutcome.Kind#OK} resets it; 429 / 5xx / 404 / offline count a
         * failure; 409 in flight waits briefly; every other answer leaves it unchanged.
         */
        public State after(CustomerOutcome outcome, int retryAfterSeconds, double unit, long nowMs) {
            switch (outcome.kind) {
                case OK:
                    return IDLE;
                case RETRY_SOON:
                    return retrySoon(retryAfterSeconds, nowMs);
                case RATE_LIMITED:
                case UNAVAILABLE:
                case BACKOFF:
                    return failed(retryAfterSeconds, unit, nowMs);
                default:
                    return this;
            }
        }
    }

    /**
     * The installation-wide per-route send budget (thread-safe). {@link #tryTake} records a send when the route's
     * one-hour window has room: the selected account may use the whole budget, every other account all but the
     * reserve.
     */
    public static final class InstallBudget {
        public static final long WINDOW_MS = 3_600_000L;

        private final EnumMap<CustomerOutcome.Route, ArrayDeque<Long>> taken = new EnumMap<>(CustomerOutcome.Route.class);

        /** Sends per rolling hour for {@code route} (80 % of the server's per-device limit); 0 = not budgeted. */
        public static int perHour(CustomerOutcome.Route route) {
            switch (route) {
                case CONSENTS: return 16;
                case SYNC: return 9;
                case ME: return 8;
                case EVENTS: return 48;
                default: return 0;
            }
        }

        /** The part of the hourly budget only the selected account may use. */
        public static int reserve(CustomerOutcome.Route route) { return (perHour(route) + 3) / 4; }

        /** Takes one send of {@code route} at {@code nowMs} when the window allows it; false = wait ({@link #waitMs}). */
        public synchronized boolean tryTake(CustomerOutcome.Route route, boolean selected, long nowMs) {
            int limit = perHour(route);
            if (limit == 0) return true;
            ArrayDeque<Long> window = window(route, nowMs);
            if (window.size() >= capacity(route, selected)) return false;
            window.addLast(nowMs);
            return true;
        }

        /** Milliseconds until {@link #tryTake} can succeed for this caller (0 = now). */
        public synchronized long waitMs(CustomerOutcome.Route route, boolean selected, long nowMs) {
            int limit = perHour(route);
            if (limit == 0) return 0;
            ArrayDeque<Long> window = window(route, nowMs);
            int capacity = capacity(route, selected);
            if (window.size() < capacity) return 0;
            // The (size - capacity + 1)-th oldest send has to leave the window first.
            int index = window.size() - capacity;
            int i = 0;
            for (Long at : window) {
                if (i++ == index) return Math.max(0, at + WINDOW_MS - nowMs);
            }
            return WINDOW_MS;
        }

        /** Sends of {@code route} inside the window ending at {@code nowMs}. */
        public synchronized int used(CustomerOutcome.Route route, long nowMs) {
            return perHour(route) == 0 ? 0 : window(route, nowMs).size();
        }

        private static int capacity(CustomerOutcome.Route route, boolean selected) {
            return selected ? perHour(route) : perHour(route) - reserve(route);
        }

        private ArrayDeque<Long> window(CustomerOutcome.Route route, long nowMs) {
            ArrayDeque<Long> window = taken.get(route);
            if (window == null) taken.put(route, window = new ArrayDeque<>());
            while (!window.isEmpty() && window.peekFirst() <= nowMs - WINDOW_MS) window.pollFirst();
            return window;
        }
    }
}
