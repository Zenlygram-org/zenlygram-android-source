/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import android.text.format.DateFormat;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.zenlygram.tasks.model.ZgTasksArchive;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Dates and counts of Tasks in the app language (iOS SGTasksL10n date helpers).
 *
 * <p>As on iOS, the date language is Uzbek or Russian when the app is in that language and US English otherwise (the
 * Tasks texts themselves fall back to English in other languages), so a row never mixes two languages. The time zone
 * is the device's, read fresh on every call; the 12/24-hour choice is Telegram's ({@link LocaleController#is24HourFormat}).
 * Main thread only (the formatter cache is not synchronized).
 */
public final class ZgTasksText {

    private ZgTasksText() {
    }

    private static final HashMap<String, SimpleDateFormat> formatters = new HashMap<>();

    /** uz_UZ, ru_RU or en_US, from the app language. */
    public static Locale dateLocale() {
        String language = null;
        try {
            final Locale current = LocaleController.getInstance().getCurrentLocale();
            language = current != null ? current.getLanguage() : null;
        } catch (Exception ignore) {
        }
        if ("uz".equals(language)) {
            return new Locale("uz", "UZ");
        } else if ("ru".equals(language)) {
            return new Locale("ru", "RU");
        }
        return Locale.US;
    }

    public static ZoneId zone() {
        try {
            return ZoneId.systemDefault();
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    private static SimpleDateFormat formatter(String skeleton) {
        final Locale locale = dateLocale();
        final TimeZone tz = TimeZone.getDefault();
        final String key = skeleton + "|" + locale + "|" + tz.getID();
        SimpleDateFormat f = formatters.get(key);
        if (f == null) {
            String pattern;
            try {
                pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
            } catch (Exception e) {
                pattern = skeleton;
            }
            f = new SimpleDateFormat(pattern, locale);
            f.setTimeZone(tz);
            formatters.put(key, f);
        }
        return f;
    }

    private static String format(String skeleton, long epochSeconds) {
        return formatter(skeleton).format(new Date(epochSeconds * 1000L));
    }

    /**
     * A stored deadline, short and exact: "Today, 14:30", "Tomorrow, 14:30", "Mar 14, 14:30" this year and
     * "Mar 14, 2027, 14:30" in any other year.
     */
    public static String deadlineText(long dueAt, long nowSeconds) {
        final boolean h24 = LocaleController.is24HourFormat;
        final ZoneId zone = zone();
        final LocalDate day = Instant.ofEpochSecond(dueAt).atZone(zone).toLocalDate();
        final LocalDate today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate();
        if (day.equals(today) || day.equals(today.plusDays(1))) {
            final String time = format(h24 ? "HHmm" : "hmma", dueAt);
            return LocaleController.getString(day.equals(today) ? R.string.ZG_tasks_relative_today : R.string.ZG_tasks_relative_tomorrow) + ", " + time;
        }
        if (day.getYear() == today.getYear()) {
            return format(h24 ? "MMMdHHmm" : "MMMdhmma", dueAt);
        }
        return format(h24 ? "yMMMdHHmm" : "yMMMdhmma", dueAt);
    }

    /** A day of the calendar, noon of it (always inside the day, also across DST changes). */
    private static long noon(LocalDate day) {
        return ZonedDateTime.of(day.atTime(12, 0), zone()).toEpochSecond();
    }

    /** The selected day kept short ("Today", "Wed, Oct 29", "Wed, Oct 29, 2027"). */
    public static String shortDayTitle(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return LocaleController.getString(R.string.ZG_tasks_relative_today);
        }
        return format(day.getYear() == today.getYear() ? "EEEMMMd" : "yEEEMMMd", noon(day));
    }

    /** The selected day in full, for TalkBack ("Today, Wednesday, October 29, 2026"). */
    public static String fullDayTitle(LocalDate day, LocalDate today) {
        final String text = format("yMMMMEEEEd", noon(day));
        return day.equals(today) ? LocaleController.getString(R.string.ZG_tasks_relative_today) + ", " + text : text;
    }

    /** "October 2026" in the date language (the stand-alone month form where the language has one). */
    public static String monthTitle(int year, int month) {
        return capitalize(format("yMMMM", noon(LocalDate.of(year, month, 15))));
    }

    /** The day-picker wheel label: "Today", "Tomorrow", "Wed, Oct 29" or "Wed, Oct 29, 2027". */
    public static String wheelDayTitle(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return LocaleController.getString(R.string.ZG_tasks_relative_today);
        } else if (day.equals(today.plusDays(1))) {
            return LocaleController.getString(R.string.ZG_tasks_relative_tomorrow);
        }
        return format(day.getYear() == today.getYear() ? "EEEMMMd" : "yEEEMMMd", noon(day));
    }

    /** Month names for the month / year picker (stand-alone forms). */
    public static String monthName(int month) {
        return capitalize(format("LLLL", noon(LocalDate.of(2001, month, 15))));
    }

    /** The first day of the week of the date language, as java.time day numbers (Monday = 1 ... Sunday = 7). */
    public static int firstDayOfWeek() {
        final int calendarDay = Calendar.getInstance(dateLocale()).getFirstDayOfWeek(); // Sunday = 1 ... Saturday = 7
        return calendarDay == Calendar.SUNDAY ? 7 : calendarDay - 1;
    }

    /** Short weekday label for a java.time day number (Monday = 1). */
    public static String weekdaySymbol(int isoDay) {
        final String[] symbols = DateFormatSymbols.getInstance(dateLocale()).getShortWeekdays(); // index 1 = Sunday
        final int index = isoDay == 7 ? Calendar.SUNDAY : isoDay + 1;
        final String s = index < symbols.length ? symbols[index] : "";
        return capitalize(s);
    }

    public static String hourLabel(int hour) {
        if (LocaleController.is24HourFormat) {
            return String.format(Locale.US, "%02d", hour);
        }
        final int h = hour % 12 == 0 ? 12 : hour % 12;
        final String[] ampm = DateFormatSymbols.getInstance(dateLocale()).getAmPmStrings();
        return h + " " + (ampm.length == 2 ? ampm[hour < 12 ? 0 : 1] : (hour < 12 ? "AM" : "PM"));
    }

    /** "3 tasks" / "No tasks" (open and completed together). */
    public static String taskCount(int count) {
        if (count == 0) {
            return LocaleController.getString(R.string.ZG_tasks_calendar_taskCountNone);
        }
        return LocaleController.formatPluralString("ZG_tasks_calendar_taskCount", count);
    }

    /** "3 open tasks" / "No open tasks", the TalkBack value of a filter chip. */
    public static String openCount(int count) {
        if (count == 0) {
            return LocaleController.getString(R.string.ZG_tasks_a11y_openCountNone);
        }
        return LocaleController.formatPluralString("ZG_tasks_a11y_openCount", count);
    }

    /**
     * The Device archive's banner (iOS TasksBoardNode.configureArchive): why the board is read-only, then one line
     * each, in iOS's order, for what is still open: the rows that are not in Cloud yet (or that all of them are), the
     * copy's progress, that the Cloud copy returns to this device, and the rows the copy could not take. Which of the
     * lines apply is decided by {@link ZgTasksArchive} alone.
     */
    public static String archiveText(ZgTasksArchive archive) {
        final StringBuilder detail = new StringBuilder(LocaleController.getString(R.string.ZG_tasks_archive_banner));
        if (archive.reason == ZgTasksArchive.Reason.RETIRED) {
            detail.append("\n").append(LocaleController.getString(R.string.ZG_tasks_migration_done));
        }
        if (archive.newRows > 0) {
            detail.append("\n").append(LocaleController.formatPluralString("ZG_tasks_archive_newRows", archive.newRows));
        }
        if (archive.total > 0) {
            detail.append("\n").append(LocaleController.formatString(R.string.ZG_tasks_migration_progress, archive.done, archive.total));
        }
        if (archive.awaitsCopy) {
            detail.append("\n").append(LocaleController.getString(R.string.ZG_tasks_archive_copyReturns));
        }
        if (archive.keptLocal > 0) {
            detail.append("\n").append(LocaleController.formatPluralString("ZG_tasks_migration_keptLocal", archive.keptLocal));
        }
        return detail.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        final int first = s.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first))) + s.substring(Character.charCount(first));
    }
}
