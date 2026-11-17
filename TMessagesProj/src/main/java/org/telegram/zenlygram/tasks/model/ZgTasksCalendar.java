/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The day / undated projection behind the Calendar view of the board (iOS TasksCalendarModel). It only narrows what
 * the board already shows by each task's deadline; it never loads or stores anything.
 *
 * <p>A day is the half-open local day [start of day, start of the next day) in the given time zone, so 23- and
 * 25-hour DST days are handled and a day is never a fixed 86,400 seconds. The selected day is a civil
 * {@link LocalDate} (year / month / day), so a time zone or language change keeps the same named day.
 */
public final class ZgTasksCalendar {

    private ZgTasksCalendar() {
    }

    /** Which deadline the agenda shows on top of the board's list filter. */
    public static final class Filter {
        public static final Filter ALL = new Filter(0, null);
        public static final Filter NO_DEADLINE = new Filter(2, null);

        /** 0 = every task, 1 = due on {@link #day}, 2 = no deadline. */
        final int kind;
        public final LocalDate day;

        private Filter(int kind, LocalDate day) {
            this.kind = kind;
            this.day = day;
        }

        public static Filter day(LocalDate day) {
            return new Filter(1, day);
        }

        public boolean isDay() {
            return kind == 1;
        }

        public boolean isNoDeadline() {
            return kind == 2;
        }
    }

    /** A filter resolved once against a time zone, so projecting a board needs one zone computation per call. */
    public static final class Matcher {
        private final int kind;
        private final long start;
        private final long end;

        public Matcher(Filter filter, ZoneId zone) {
            if (filter.kind == 1) {
                long s;
                long e;
                int k = 1;
                try {
                    s = filter.day.atStartOfDay(zone).toEpochSecond();
                    e = filter.day.plusDays(1).atStartOfDay(zone).toEpochSecond();
                } catch (RuntimeException ex) {
                    s = 0;
                    e = 0;
                    k = 3; // the zone has no such day: nothing is due on it
                }
                this.kind = k;
                this.start = s;
                this.end = e;
            } else {
                this.kind = filter.kind;
                this.start = 0;
                this.end = 0;
            }
        }

        /** Undated tasks match only "No deadline"; timestamp 0 is a real day. */
        public boolean matches(Long dueAt) {
            switch (kind) {
                case 0:
                    return true;
                case 2:
                    return dueAt == null;
                case 1:
                    return dueAt != null && start <= dueAt && dueAt < end;
                default:
                    return false;
            }
        }
    }

    /** The local day that contains {@code epochSeconds}. */
    public static LocalDate dayOf(long epochSeconds, ZoneId zone) {
        return Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate();
    }

    /** Per-day counts for the day markers, and the undated count; a null deadline is never counted as 1970-01-01. */
    public static final class DueCounts {
        public final Map<LocalDate, Integer> days;
        public final int undated;

        DueCounts(Map<LocalDate, Integer> days, int undated) {
            this.days = days;
            this.undated = undated;
        }

        public int count(LocalDate day) {
            final Integer n = days.get(day);
            return n != null ? n : 0;
        }
    }

    public static DueCounts dueDateCounts(List<Long> dueDates, ZoneId zone) {
        final HashMap<LocalDate, Integer> days = new HashMap<>();
        int undated = 0;
        for (Long dueAt : dueDates) {
            if (dueAt == null) {
                undated++;
                continue;
            }
            final LocalDate day = dayOf(dueAt, zone);
            final Integer n = days.get(day);
            days.put(day, n != null ? n + 1 : 1);
        }
        return new DueCounts(days, undated);
    }
}
