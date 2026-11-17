package org.telegram.messenger.zenlygram.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Platform-independent rules of the task deadline sheet: a due instant in Unix milliseconds chosen as a local date
 * and time in an explicit time zone. The sheet never converts dates itself; it goes through these functions, so
 * calendar, daylight-saving and precision behavior is checked on the host ({@code zenlygram-tests/tasks}).
 *
 * <p>Storage keeps Unix milliseconds. A stored value is never rounded, clamped or rewritten here: a sheet saved
 * without a change returns the value it was opened with, to the millisecond. Only a value the user actually chose
 * is held to {@link #MIN_CHOICE_MILLIS}..{@link #MAX_CHOICE_MILLIS}.
 *
 * <p>A deadline is a local annotation only: nothing here schedules a reminder or notification.
 */
public final class ZenlyTaskDeadline {
    /** The earliest choosable instant: storage rejects {@code due_at <= 0}. */
    public static final long MIN_CHOICE_MILLIS = 1L;
    /** The latest choosable instant: its whole seconds still fit a signed 32-bit timestamp, as on iOS. */
    public static final long MAX_CHOICE_MILLIS = Integer.MAX_VALUE * 1000L + 999L;

    private ZenlyTaskDeadline() { }

    /** Today / Tomorrow / In a week: calendar days after today (calendar days, not 24-hour periods). */
    public enum Shortcut {
        TODAY(0), TOMORROW(1), NEXT_WEEK(7);

        public final int days;

        Shortcut(int days) { this.days = days; }
    }

    public static boolean isChoosable(long millis) {
        return millis >= MIN_CHOICE_MILLIS && millis <= MAX_CHOICE_MILLIS;
    }

    public static ZonedDateTime local(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone);
    }

    /**
     * The instant of {@code time} on {@code day} in {@code zone}. A clock time missing that day (daylight-saving gap)
     * moves forward by the gap, keeping minutes, seconds and milliseconds; a repeated one takes its first occurrence.
     */
    public static long compose(LocalDate day, LocalTime time, ZoneId zone) {
        return ZonedDateTime.ofLocal(LocalDateTime.of(day, time), zone, null).toInstant().toEpochMilli();
    }

    /** What the sheet suggests without a stored deadline: the next local whole hour after {@code now}, kept choosable. */
    public static long suggestion(long now, ZoneId zone) {
        long next = local(now, zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli();
        return Math.min(Math.max(next, MIN_CHOICE_MILLIS), MAX_CHOICE_MILLIS);
    }

    /**
     * Where {@code shortcut} moves {@code selected}: the local day {@code now} + 0/1/7 calendar days in {@code zone}
     * at the selected time of day (to the millisecond). Already on that day: {@code selected} itself, so a repeated
     * daylight-saving hour keeps its occurrence. Another day: as {@link #compose}. {@code null} when the result is not
     * choosable, never a clamped, different deadline.
     */
    public static Long shortcutTarget(Shortcut shortcut, long selected, long now, ZoneId zone) {
        LocalDate target = local(now, zone).toLocalDate().plusDays(shortcut.days);
        ZonedDateTime current = local(selected, zone);
        long result = current.toLocalDate().equals(target) ? selected : compose(target, current.toLocalTime(), zone);
        return isChoosable(result) ? result : null;
    }

    /**
     * The days the date wheel offers, as epoch days: those of every choosable instant in {@code zone}, widened to the
     * stored deadline's day so an existing value outside that range is still shown as it is (not clamped). A stored
     * value too far away for an int-indexed wheel is left out; the sheet still shows and keeps it exactly.
     */
    public static long[] dayRange(Long stored, ZoneId zone) {
        long first = local(MIN_CHOICE_MILLIS, zone).toLocalDate().toEpochDay();
        long last = local(MAX_CHOICE_MILLIS, zone).toLocalDate().toEpochDay();
        if (stored != null) {
            long day = local(stored, zone).toLocalDate().toEpochDay();
            long widenedFirst = Math.min(first, day);
            long widenedLast = Math.max(last, day);
            if (widenedLast - widenedFirst < Integer.MAX_VALUE) {
                first = widenedFirst;
                last = widenedLast;
            }
        }
        return new long[]{first, last};
    }

    /**
     * The sheet's draft. Immutable: every change returns a new draft, so discarding one (Cancel, back, outside tap)
     * leaves the task editor exactly as it was. Untouched, it holds the stored value (or the suggestion) unchanged.
     */
    public static final class Draft {
        /** The deadline the sheet was opened with, exactly as stored; {@code null} for none. */
        public final Long original;
        public final long selected;
        /** Whether the user changed the date or time (a shortcut onto the current day changes nothing). */
        public final boolean touched;

        private Draft(Long original, long selected, boolean touched) {
            this.original = original;
            this.selected = selected;
            this.touched = touched;
        }

        public static Draft open(Long stored, long now, ZoneId zone) {
            return new Draft(stored, stored != null ? stored : suggestion(now, zone), false);
        }

        public Draft select(long millis) {
            return millis == selected ? this : new Draft(original, millis, true);
        }

        /** {@link #shortcutTarget}; unchanged when the target is not choosable (the sheet disables that shortcut). */
        public Draft withShortcut(Shortcut shortcut, long now, ZoneId zone) {
            Long target = shortcutTarget(shortcut, selected, now, zone);
            return target == null ? this : select(target);
        }

        /** Another day at the selected time of day, to the millisecond (a legacy 23:59:59.999 stays end of day). */
        public Draft withDay(LocalDate day, ZoneId zone) {
            ZonedDateTime current = local(selected, zone);
            return day.equals(current.toLocalDate()) ? this : select(compose(day, current.toLocalTime(), zone));
        }

        /** The selected day at {@code hour}:{@code minute}:00.000, the precision the wheels show. */
        public Draft withClock(int hour, int minute, ZoneId zone) {
            ZonedDateTime current = local(selected, zone);
            LocalTime time = LocalTime.of(hour, minute);
            return time.equals(current.toLocalTime()) ? this : select(compose(current.toLocalDate(), time, zone));
        }

        /** Untouched always saves (the stored value as is); a chosen value only inside the choosable range. */
        public boolean canSave() {
            return !touched || isChoosable(selected);
        }

        public long savedMillis() {
            if (!canSave()) throw new IllegalStateException("deadline_out_of_range");
            return selected;
        }
    }

    /** Receives the sheet's single outcome: the due instant, or {@code null} when the deadline is removed. */
    public interface Commit {
        void deliver(Long dueAt);
    }

    /**
     * One opening of the sheet: the current draft and at most one outcome. Save delivers the draft, Remove delivers
     * {@code null} (only for an existing deadline), Cancel delivers nothing; after any of them (or a repeated tap)
     * every further action is ignored.
     */
    public static final class Session {
        private final Commit commit;
        private Draft draft;
        private boolean finished;

        public Session(Draft draft, Commit commit) {
            this.draft = draft;
            this.commit = commit;
        }

        public Draft draft() { return draft; }

        public boolean isFinished() { return finished; }

        public boolean canRemove() { return draft.original != null; }

        /** Replaces the draft while the sheet is open; ignored once finished. */
        public boolean update(Draft next) {
            if (finished) return false;
            draft = next;
            return true;
        }

        public boolean save() {
            if (finished || !draft.canSave()) return false;
            finished = true;
            commit.deliver(draft.savedMillis());
            return true;
        }

        public boolean remove() {
            if (finished || !canRemove()) return false;
            finished = true;
            commit.deliver(null);
            return true;
        }

        /** Cancel, back, outside tap or the screen closing: no outcome. */
        public boolean cancel() {
            if (finished) return false;
            finished = true;
            return true;
        }
    }
}
