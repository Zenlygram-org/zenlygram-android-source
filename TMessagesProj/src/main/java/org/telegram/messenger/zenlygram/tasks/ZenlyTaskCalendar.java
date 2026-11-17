package org.telegram.messenger.zenlygram.tasks;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Platform-independent rules of the task calendar: which civil day of the Gregorian calendar a due instant falls on,
 * which tasks a calendar selection shows, how many deadlines each day carries, and the month grid. The calendar UI
 * never converts dates itself; it goes through these functions, so daylight-saving and range behavior is checked on
 * the host ({@code zenlygram-tests/tasks}).
 *
 * <p>Deadlines are Unix milliseconds ({@code null} = no deadline; {@code 0} is a real instant, 1 Jan 1970 UTC). Cloud
 * callers convert their seconds with {@code 1000L} before calling. Every function takes the zone explicitly; nothing
 * here reads the system clock or the default time zone, and nothing is persisted.
 *
 * <p>A civil day is the half-open range [{@code day.atStartOfDay(zone)}, {@code day.plusDays(1).atStartOfDay(zone)}):
 * 23 or 25 hours on daylight-saving days, starting at the earliest valid local time when midnight is skipped or
 * repeated, and empty when the zone skipped the whole date. Zone offsets are whole seconds, so boundaries are
 * compared in epoch seconds against {@code floorDiv(millis, 1000)}: no millisecond overflow at the {@code long}
 * limits, and every millisecond of precision still decides the side of a boundary.
 */
public final class ZenlyTaskCalendar {
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final int DAYS_PER_WEEK = 7;

    private ZenlyTaskCalendar() { }

    /** All tasks, the tasks due on one civil day, or the tasks without a deadline. */
    public enum Kind { ALL, DAY, UNDATED }

    /** An immutable calendar selection. Only {@link Kind#DAY} carries a {@link #day}. */
    public static final class Filter {
        private static final Filter ALL_TASKS = new Filter(Kind.ALL, null);
        private static final Filter UNDATED_TASKS = new Filter(Kind.UNDATED, null);

        public final Kind kind;
        /** The selected civil date for {@link Kind#DAY}; {@code null} otherwise. */
        public final LocalDate day;

        private Filter(Kind kind, LocalDate day) {
            this.kind = kind;
            this.day = day;
        }

        public static Filter all() {
            return ALL_TASKS;
        }

        public static Filter day(LocalDate day) {
            return new Filter(Kind.DAY, Objects.requireNonNull(day, "day"));
        }

        public static Filter undated() {
            return UNDATED_TASKS;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Filter)) return false;
            Filter filter = (Filter) other;
            return kind == filter.kind && Objects.equals(day, filter.day);
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + Objects.hashCode(day);
        }

        @Override
        public String toString() {
            return kind == Kind.DAY ? "Filter{DAY " + day + "}" : "Filter{" + kind + "}";
        }
    }

    /**
     * A filter resolved against one zone. Build it once per projection (and again after a zone change); each
     * {@link #matches} is then a null check and two comparisons, with no calendar computation per row.
     */
    public static final class Matcher {
        private final Kind kind;
        /** Inclusive start of the selected day, epoch seconds ({@link Kind#DAY} only). */
        private final long startSecond;
        /** Exclusive end of the selected day, epoch seconds ({@link Kind#DAY} only). */
        private final long endSecond;

        private Matcher(Kind kind, long startSecond, long endSecond) {
            this.kind = kind;
            this.startSecond = startSecond;
            this.endSecond = endSecond;
        }

        public boolean matches(Long dueAtMillis) {
            if (kind == Kind.ALL) return true;
            if (kind == Kind.UNDATED) return dueAtMillis == null;
            if (dueAtMillis == null) return false;
            long second = Math.floorDiv(dueAtMillis, MILLIS_PER_SECOND);
            return second >= startSecond && second < endSecond;
        }
    }

    public static Matcher matcher(Filter filter, ZoneId zone) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(zone, "zone");
        if (filter.kind != Kind.DAY) return new Matcher(filter.kind, 0L, 0L);
        LocalDate day = filter.day;
        long start = day.atStartOfDay(zone).toEpochSecond();
        // LocalDate.MAX has no next day to end at; every representable deadline lies before its end anyway.
        long end = day.equals(LocalDate.MAX) ? Long.MAX_VALUE : day.plusDays(1).atStartOfDay(zone).toEpochSecond();
        return new Matcher(Kind.DAY, start, end);
    }

    /** The civil date of {@code unixMillis} in {@code zone}; defined for every {@code long}. */
    public static LocalDate civilDay(long unixMillis, ZoneId zone) {
        return Instant.ofEpochMilli(unixMillis).atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
    }

    /** An immutable snapshot of deadline counts: per civil day (in date order) and without a deadline. */
    public static final class Counts {
        /** Deadlines per civil day; only days with at least one deadline have an entry. Unmodifiable. */
        public final Map<LocalDate, Integer> days;
        /** Tasks without a deadline ({@code null}); a {@code 0} deadline is never counted here. */
        public final int undated;

        private Counts(Map<LocalDate, Integer> days, int undated) {
            this.days = days;
            this.undated = undated;
        }

        /** Deadlines on {@code day}; 0 for a day without any and for a {@code null} (padding) cell. */
        public int on(LocalDate day) {
            Integer count = day == null ? null : days.get(day);
            return count == null ? 0 : count;
        }
    }

    /**
     * Counts every supplied deadline once, duplicates included: {@code null} as no deadline, any other value on its
     * civil day in {@code zone}. The input is only read, and the result does not change if the input does later.
     */
    public static Counts countDeadlines(Iterable<Long> dueAtMillis, ZoneId zone) {
        Objects.requireNonNull(dueAtMillis, "dueAtMillis");
        Objects.requireNonNull(zone, "zone");
        TreeMap<LocalDate, Integer> days = new TreeMap<>();
        int undated = 0;
        for (Long due : dueAtMillis) {
            if (due == null) {
                undated++;
                continue;
            }
            LocalDate day = civilDay(due, zone);
            Integer count = days.get(day);
            days.put(day, count == null ? 1 : count + 1);
        }
        return new Counts(Collections.unmodifiableMap(days), undated);
    }

    /**
     * The month as whole weeks starting on {@code firstDayOfWeek}: the month's dates in order, padded with
     * {@code null} (never neighboring months' dates) before the 1st and after the last day. 4 to 6 weeks; unmodifiable.
     */
    public static List<LocalDate> monthDays(YearMonth month, DayOfWeek firstDayOfWeek) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(firstDayOfWeek, "firstDayOfWeek");
        LocalDate first = month.atDay(1);
        int leading = Math.floorMod(first.getDayOfWeek().getValue() - firstDayOfWeek.getValue(), DAYS_PER_WEEK);
        int length = month.lengthOfMonth();
        int weeks = (leading + length + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK;
        LocalDate[] cells = new LocalDate[weeks * DAYS_PER_WEEK];
        for (int i = 0; i < length; i++) {
            cells[leading + i] = first.plusDays(i);
        }
        return Collections.unmodifiableList(Arrays.asList(cells));
    }
}
