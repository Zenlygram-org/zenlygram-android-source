/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

/**
 * When the "Link Telegram Account" screen may send its next bind/poll (iOS SGLinkAccountLifecycle.deferPoll with the
 * cadence of SGLinkAccountModel.poll): every 3 s for the first minute of a code, then every 5 s, each interval moved
 * by -0.4 ... +0.8 s so that devices do not poll in step. A 429's Retry-After wins over the cadence, and nothing ever
 * shortens a wait that is already set: the server's budget is shared by every request from this network.
 *
 * <p>Times are monotonic milliseconds (SystemClock.elapsedRealtime()), never the wall clock. Hiding the screen does not
 * touch the schedule: the screen simply sends nothing while hidden, and the poll that was due goes out when it is
 * back. Pure, JVM-tested (ZgLinkPollScheduleTest).
 */
final class ZgLinkPollSchedule {

    static final long FAST_INTERVAL_MS = 3_000;
    static final long SLOW_INTERVAL_MS = 5_000;
    /** How long a code is polled at the fast interval. */
    static final long FAST_WINDOW_MS = 60_000;
    static final long JITTER_MIN_MS = -400;
    static final long JITTER_MAX_MS = 800;
    /** A Retry-After is honoured up to this long; a code lives five minutes, so anything longer ends in its expiry. */
    private static final long MAX_RETRY_AFTER_MS = 3_600_000;

    private final long beganAtMs;
    private long notBeforeMs;

    /** A new code at {@code nowMs}: nothing is deferred yet. */
    ZgLinkPollSchedule(long nowMs) {
        beganAtMs = nowMs;
        notBeforeMs = nowMs;
    }

    /** The next poll comes one interval after {@code nowMs}; {@code random} is uniform in [0, 1). */
    void next(long nowMs, double random) {
        defer(nowMs + interval(nowMs - beganAtMs, random));
    }

    /** A 429 at {@code nowMs}: no poll before its Retry-After (at least one second) has passed. */
    void retryAfter(long nowMs, int seconds) {
        defer(nowMs + Math.min(MAX_RETRY_AFTER_MS, Math.max(1, seconds) * 1000L));
    }

    /** How long to wait at {@code nowMs} before the next poll; 0 when it may go out now. */
    long delayMs(long nowMs) {
        return Math.max(0, notBeforeMs - nowMs);
    }

    private void defer(long untilMs) {
        notBeforeMs = Math.max(notBeforeMs, untilMs);
    }

    /** One interval for a code that has been polled for {@code sinceBeganMs}. */
    static long interval(long sinceBeganMs, double random) {
        final long base = sinceBeganMs < FAST_WINDOW_MS ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS;
        final double position = Math.max(0.0, Math.min(1.0, random));
        return base + JITTER_MIN_MS + Math.round(position * (JITTER_MAX_MS - JITTER_MIN_MS));
    }
}
