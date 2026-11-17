package org.telegram.zenlygram.pro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests of the manual link's poll cadence (iOS: 3 s for a minute, then 5 s, -0.4 ... +0.8 s, Retry-After wins). */
public class ZgLinkPollScheduleTest {

    private static final long T0 = 5_000_000L;

    @Test
    public void intervalIsThreeSecondsForAMinuteThenFive() {
        assertEquals(2_600, ZgLinkPollSchedule.interval(0, 0.0));
        assertEquals(3_800, ZgLinkPollSchedule.interval(0, 1.0));
        assertEquals(3_200, ZgLinkPollSchedule.interval(30_000, 0.5));
        assertEquals(2_600, ZgLinkPollSchedule.interval(59_999, 0.0));
        assertEquals(4_600, ZgLinkPollSchedule.interval(60_000, 0.0));
        assertEquals(5_800, ZgLinkPollSchedule.interval(60_000, 1.0));
        assertEquals(5_200, ZgLinkPollSchedule.interval(299_000, 0.5));
    }

    @Test
    public void jitterStaysInItsBounds() {
        for (int i = 0; i <= 1000; i++) {
            final double random = i / 1000.0;
            final long fast = ZgLinkPollSchedule.interval(10_000, random);
            final long slow = ZgLinkPollSchedule.interval(100_000, random);
            assertTrue(fast >= 3_000 - 400 && fast <= 3_000 + 800);
            assertTrue(slow >= 5_000 - 400 && slow <= 5_000 + 800);
        }
        // A random source out of range never widens the bounds.
        assertEquals(2_600, ZgLinkPollSchedule.interval(0, -1.0));
        assertEquals(3_800, ZgLinkPollSchedule.interval(0, 2.0));
    }

    @Test
    public void aNewCodeMayBePolledAtOnce() {
        // The silent confirmation was confirmed: its first poll does not wait.
        final ZgLinkPollSchedule schedule = new ZgLinkPollSchedule(T0);
        assertEquals(0, schedule.delayMs(T0));
        assertEquals(0, schedule.delayMs(T0 + 10));
    }

    @Test
    public void eachPollIsOneIntervalAfterTheLastAnswer() {
        final ZgLinkPollSchedule schedule = new ZgLinkPollSchedule(T0);
        schedule.next(T0, 0.5);
        assertEquals(3_200, schedule.delayMs(T0));
        assertEquals(200, schedule.delayMs(T0 + 3_000));
        assertEquals(0, schedule.delayMs(T0 + 3_200));
        // The answer came at +3.5 s: the next one is an interval after that.
        schedule.next(T0 + 3_500, 0.0);
        assertEquals(2_600, schedule.delayMs(T0 + 3_500));
        // After the first minute the interval is five seconds.
        schedule.next(T0 + 61_000, 0.5);
        assertEquals(5_200, schedule.delayMs(T0 + 61_000));
    }

    @Test
    public void retryAfterWinsOverTheCadence() {
        final ZgLinkPollSchedule schedule = new ZgLinkPollSchedule(T0);
        schedule.retryAfter(T0, 30);
        schedule.next(T0, 0.5);
        assertEquals(30_000, schedule.delayMs(T0));
        // A later, shorter wish never shortens it: the budget is shared.
        schedule.next(T0 + 10_000, 0.0);
        assertEquals(20_000, schedule.delayMs(T0 + 10_000));
        schedule.retryAfter(T0 + 10_000, 5);
        assertEquals(20_000, schedule.delayMs(T0 + 10_000));
        assertEquals(0, schedule.delayMs(T0 + 30_000));
    }

    @Test
    public void aShortRetryAfterStillLeavesTheCadence() {
        final ZgLinkPollSchedule schedule = new ZgLinkPollSchedule(T0);
        schedule.retryAfter(T0, 1);
        schedule.next(T0, 0.0);
        assertEquals(2_600, schedule.delayMs(T0));
        // Zero or negative seconds count as one.
        final ZgLinkPollSchedule other = new ZgLinkPollSchedule(T0);
        other.retryAfter(T0, 0);
        assertEquals(1_000, other.delayMs(T0));
        other.retryAfter(T0, -7);
        assertEquals(1_000, other.delayMs(T0));
    }

    @Test
    public void anAbsurdRetryAfterIsBounded() {
        final ZgLinkPollSchedule schedule = new ZgLinkPollSchedule(T0);
        schedule.retryAfter(T0, Integer.MAX_VALUE);
        assertEquals(3_600_000, schedule.delayMs(T0));
    }

    @Test
    public void hidingTheScreenDoesNotMoveTheSchedule() {
        final ZgLinkPollSchedule schedule = new ZgLinkPollSchedule(T0);
        schedule.next(T0, 0.5);
        // Hidden for two minutes: the poll that was due goes out as soon as the screen is back.
        assertEquals(0, schedule.delayMs(T0 + 120_000));
        // And the code is past its first minute by then.
        schedule.next(T0 + 120_000, 0.5);
        assertEquals(5_200, schedule.delayMs(T0 + 120_000));
    }
}
