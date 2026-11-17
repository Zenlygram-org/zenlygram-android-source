package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.List;

/** Test clock: timers run in due order only when {@link #advance} passes them; cancelled ones never run. */
final class ManualScheduler implements TasksRealtimeSocket.Scheduler {
    static final class Timer implements TasksRealtimeSocket.Cancellable {
        final long due;
        final long delay;
        final Runnable action;
        boolean cancelled, ran;

        Timer(long due, long delay, Runnable action) {
            this.due = due;
            this.delay = delay;
            this.action = action;
        }

        @Override public void cancel() { cancelled = true; }
    }

    long now;
    final List<Timer> timers = new ArrayList<>();

    @Override public TasksRealtimeSocket.Cancellable schedule(Runnable action, long delayMs) {
        Timer timer = new Timer(now + delayMs, delayMs, action);
        timers.add(timer);
        return timer;
    }

    void advance(long ms) {
        long target = now + ms;
        while (true) {
            Timer next = null;
            for (Timer timer : timers) {
                if (timer.cancelled || timer.ran || timer.due > target) continue;
                if (next == null || timer.due < next.due) next = timer;
            }
            if (next == null) break;
            now = Math.max(now, next.due);
            next.ran = true;
            next.action.run();
        }
        now = target;
    }

    /** Live (not cancelled, not run) timers. */
    List<Timer> pending() {
        List<Timer> result = new ArrayList<>();
        for (Timer timer : timers) if (!timer.cancelled && !timer.ran) result.add(timer);
        return result;
    }
}
