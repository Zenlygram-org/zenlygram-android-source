/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Pure rules for a task's deadline (iOS TasksTaskMetadata): the storable range, the date the picker opens on, the
 * Today / Tomorrow / In a Week shortcuts, and the bidi isolation of a contact name. java.time only (desugared on old
 * Android), so JVM tests run it with any time zone.
 */
public final class ZgTasksDeadline {

    /** Storage keeps a non-negative Unix timestamp that fits in an int: 1970-01-01 .. 2038-01-19 UTC. */
    public static final long MIN_TIMESTAMP = 0;
    public static final long MAX_TIMESTAMP = Integer.MAX_VALUE;

    private ZgTasksDeadline() {
    }

    public static boolean isStorable(long seconds) {
        return seconds >= MIN_TIMESTAMP && seconds <= MAX_TIMESTAMP;
    }

    /** Whole seconds of an instant in milliseconds, rounded down; null when storage cannot keep it (never clamped). */
    public static Long timestampForMillis(long epochMillis) {
        final long seconds = floorDiv(epochMillis, 1000L);
        return isStorable(seconds) ? seconds : null;
    }

    /**
     * The instant the picker opens on: the stored deadline exactly (even when overdue), otherwise the start of the next
     * hour of {@code nowSeconds} (the next real hour boundary, also across a DST change), kept inside the storable range.
     */
    public static long initialDate(Long current, long nowSeconds, ZoneId zone) {
        if (current != null && current >= MIN_TIMESTAMP) {
            return current;
        }
        long suggestion;
        try {
            final ZonedDateTime now = Instant.ofEpochSecond(nowSeconds).atZone(zone);
            suggestion = now.truncatedTo(ChronoUnit.HOURS).plusHours(1).toEpochSecond();
            if (suggestion <= nowSeconds) {
                suggestion = floorDiv(nowSeconds, 3600L) * 3600L + 3600L;
            }
        } catch (RuntimeException e) {
            suggestion = floorDiv(nowSeconds, 3600L) * 3600L + 3600L;
        }
        return Math.min(Math.max(suggestion, MIN_TIMESTAMP), MAX_TIMESTAMP);
    }

    /** The quick date choices of the deadline sheet: calendar days after today (not 24-hour periods). */
    public enum Shortcut {
        TODAY(0),
        TOMORROW(1),
        NEXT_WEEK(7);

        public final int dayOffset;

        Shortcut(int dayOffset) {
            this.dayOffset = dayOffset;
        }
    }

    /**
     * Where a shortcut moves {@code selectedSeconds}: today + 0/1/7 calendar days in {@code zone}, at the selected hour,
     * minute and second.
     * <ul>
     *   <li>Already on that day: the selected instant itself (a repeated DST hour keeps its occurrence).</li>
     *   <li>Another day: a clock time missing there (DST gap) moves forward by the gap; a repeated one takes its first
     *       occurrence.</li>
     * </ul>
     * Null when an input or the result is outside the storable range: never a clamped, different deadline.
     */
    public static Long shortcutDate(Shortcut shortcut, long selectedSeconds, long nowSeconds, ZoneId zone) {
        if (!isStorable(nowSeconds) || !isStorable(selectedSeconds)) {
            return null;
        }
        try {
            final LocalDate today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate();
            final LocalDate target = today.plusDays(shortcut.dayOffset);
            final ZonedDateTime selected = Instant.ofEpochSecond(selectedSeconds).atZone(zone);
            if (selected.toLocalDate().equals(target)) {
                return selectedSeconds;
            }
            final LocalTime clock = selected.toLocalTime().truncatedTo(ChronoUnit.SECONDS);
            // ZonedDateTime.of: a gap shifts forward by its length, an overlap takes the earlier offset (first one).
            final long result = ZonedDateTime.of(target, clock, zone).toEpochSecond();
            return isStorable(result) ? result : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A contact name for display beside other text: bidi controls of the name are removed and line breaks become
     * spaces, then the whole is isolated (FSI ... PDI), so a right-to-left name cannot reorder the line around it.
     */
    public static String isolatedDisplayText(String text) {
        final StringBuilder sb = new StringBuilder(text != null ? text.length() + 2 : 2);
        sb.append('⁨');
        if (text != null) {
            for (int i = 0; i < text.length(); ) {
                final int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (isBidiControl(cp)) {
                    continue;
                }
                if (isNewline(cp)) {
                    sb.append(' ');
                } else {
                    sb.appendCodePoint(cp);
                }
            }
        }
        sb.append('⁩');
        return sb.toString();
    }

    /** Math.floorDiv without the API 24 requirement. */
    static long floorDiv(long x, long y) {
        final long q = x / y;
        return (x % y != 0 && ((x ^ y) < 0)) ? q - 1 : q;
    }

    static boolean isBidiControl(int cp) {
        return cp == 0x061C || cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }

    static boolean isNewline(int cp) {
        return (cp >= 0x0A && cp <= 0x0D) || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }
}
