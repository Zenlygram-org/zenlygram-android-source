import org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.Counts;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.Filter;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.Kind;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.Matcher;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.civilDay;
import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.countDeadlines;
import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.matcher;
import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskCalendar.monthDays;

/**
 * Test source for the task calendar rules, meant to be compiled and run by run_tests.py with no Android SDK or JUnit
 * dependency; this file states the expected behavior and is not itself evidence of an observed host run. Expected
 * instants are written as offset date-times (independent of the zone rules under test), so a daylight-saving mistake
 * in the model cannot also hide in the expectation. Every zone is passed explicitly: nothing here reads the system
 * clock or the default time zone.
 */
public final class ZenlyTaskCalendarTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final ZoneId HAVANA = ZoneId.of("America/Havana");
    private static final ZoneId APIA = ZoneId.of("Pacific/Apia");
    private static final ZoneId KIRITIMATI = ZoneId.of("Pacific/Kiritimati");
    private static final ZoneId PAGO_PAGO = ZoneId.of("Pacific/Pago_Pago");
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    /** UTC civil day of {@code Long.MAX_VALUE} ms (+292278994-08-17T07:12:55.807Z). */
    private static final LocalDate LAST_DAY = LocalDate.of(292278994, 8, 17);
    /** UTC civil day of {@code Long.MIN_VALUE} ms (-292275055-05-16T16:47:04.192Z). */
    private static final LocalDate FIRST_DAY = LocalDate.of(-292275055, 5, 16);
    private static int checks;

    private interface Action {
        void run();
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) fail(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail(message + ": expected " + expected + " got " + actual);
        }
    }

    private static void throwsOn(Class<? extends Throwable> expected, Action action, String message) {
        checks++;
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) return;
            throw new AssertionError(message + ": expected " + expected.getSimpleName() + " got " + thrown, thrown);
        }
        fail(message + ": expected " + expected.getSimpleName() + ", nothing thrown");
    }

    /** An instant from a local wall time and an explicit UTC offset. */
    private static long at(String localDateTime, String offset) {
        return OffsetDateTime.of(LocalDateTime.parse(localDateTime), ZoneOffset.of(offset)).toInstant().toEpochMilli();
    }

    private static LocalDate day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    public static void main(String[] args) {
        filters();
        civilDays();
        today();
        dayWindows();
        kinds();
        daylightSaving();
        midnightTransitions();
        skippedDay();
        timeZoneChange();
        counts();
        countsAgreeWithMatchers();
        extremes();
        monthGrid();
        gridInvariants();
        immutability();
        nullArguments();
        System.out.println("Task calendar: " + checks + " checks passed");
    }

    private static void filters() {
        equal(Kind.ALL, Filter.all().kind, "all() is the ALL kind");
        equal(null, Filter.all().day, "all() carries no day");
        equal(Kind.UNDATED, Filter.undated().kind, "undated() is the UNDATED kind");
        equal(null, Filter.undated().day, "undated() carries no day");
        Filter selected = Filter.day(day(2026, 10, 25));
        equal(Kind.DAY, selected.kind, "day() is the DAY kind");
        equal(day(2026, 10, 25), selected.day, "day() keeps the civil date");
        equal(Filter.day(day(2026, 10, 25)), selected, "Filters with the same day are equal values");
        equal(Filter.day(day(2026, 10, 25)).hashCode(), selected.hashCode(), "Equal filters hash equally");
        check(!selected.equals(Filter.day(day(2026, 10, 26))), "Different days are different filters");
        check(!Filter.all().equals(Filter.undated()), "All and No deadline are different filters");
        equal(Filter.all(), Filter.all(), "all() is a stable value");
        equal(Filter.undated(), Filter.undated(), "undated() is a stable value");
    }

    private static void civilDays() {
        equal(day(1970, 1, 1), civilDay(0L, UTC), "0 ms is a real date (the epoch), never 'no deadline'");
        equal(day(1969, 12, 31), civilDay(-1L, UTC), "One millisecond before the epoch is the previous day");
        equal(day(1969, 12, 31), civilDay(0L, NEW_YORK), "The epoch is still 31 Dec in New York");
        equal(day(2026, 10, 24), civilDay(at("2026-10-24T23:59:59.999", "+05:00"), TASHKENT),
                "The last millisecond of a local day keeps that day");
        equal(day(2026, 10, 25), civilDay(at("2026-10-25T00:00", "+05:00"), TASHKENT),
                "Local midnight starts the next day");
        equal(day(2026, 10, 24), civilDay(at("2026-10-25T00:00", "+05:00"), UTC),
                "The same instant is still the 24th in UTC");
    }

    private static void today() {
        long lateUtc = at("2026-10-24T20:30", "Z");
        equal(day(2026, 10, 25), civilDay(lateUtc, TASHKENT), "Today is the device zone's civil day, not UTC's");
        equal(YearMonth.of(2026, 11), YearMonth.from(civilDay(at("2026-10-31T20:30", "Z"), TASHKENT)),
                "Today's month follows the device zone across a month boundary");
        equal(YearMonth.of(2027, 1), YearMonth.from(civilDay(at("2026-12-31T19:00", "Z"), TASHKENT)),
                "Today's month follows the device zone across a year boundary");
    }

    /** Asserts that {@code day} in {@code zone} is exactly the half-open instant range [start, end). */
    private static void window(LocalDate day, ZoneId zone, long start, long end, String label) {
        Matcher matcher = matcher(Filter.day(day), zone);
        check(matcher.matches(start), label + ": the first millisecond belongs to the day");
        check(matcher.matches(end - 1), label + ": the last millisecond belongs to the day");
        check(!matcher.matches(start - 1), label + ": the millisecond before belongs to the previous day");
        check(!matcher.matches(end), label + ": the end is exclusive");
        equal(day, civilDay(start, zone), label + ": civil day of the start");
        equal(day, civilDay(end - 1, zone), label + ": civil day of the last millisecond");
        check(!day.equals(civilDay(start - 1, zone)), label + ": civil day before the start");
        check(!day.equals(civilDay(end, zone)), label + ": civil day at the exclusive end");
    }

    private static void dayWindows() {
        window(day(2026, 10, 25), TASHKENT, at("2026-10-25T00:00", "+05:00"), at("2026-10-26T00:00", "+05:00"),
                "Tashkent day");
        window(day(1970, 1, 1), UTC, 0L, 86_400_000L, "Epoch day");
        window(day(1969, 12, 31), UTC, -86_400_000L, 0L, "Day before the epoch");
        // Tashkent local mean time was +04:37:11: a whole-second, not whole-minute, midnight before the epoch.
        // The millisecond before it must floor (not truncate toward zero) into the previous second.
        window(day(1900, 1, 1), TASHKENT, at("1900-01-01T00:00", "+04:37:11"), at("1900-01-02T00:00", "+04:37:11"),
                "Pre-epoch local-mean-time day");

        Matcher selected = matcher(Filter.day(day(2026, 10, 25)), TASHKENT);
        long inside = at("2026-10-25T13:14:15.678", "+05:00");
        check(selected.matches(inside) && selected.matches(inside), "A matcher is reusable across rows");
        check(!selected.matches(null), "No deadline never matches a calendar day");
    }

    private static void kinds() {
        Matcher all = matcher(Filter.all(), NEW_YORK);
        check(all.matches(null), "All includes tasks without a deadline");
        check(all.matches(0L), "All includes a 0 deadline");
        check(all.matches(Long.MIN_VALUE) && all.matches(Long.MAX_VALUE), "All includes every representable deadline");

        Matcher undated = matcher(Filter.undated(), NEW_YORK);
        check(undated.matches(null), "No deadline matches a null deadline");
        check(!undated.matches(0L), "0 is a deadline, not 'no deadline'");
        check(!undated.matches(-1L) && !undated.matches(Long.MAX_VALUE), "No deadline rejects dated tasks");

        check(matcher(Filter.day(day(1970, 1, 1)), UTC).matches(0L), "0 ms is on 1 Jan 1970 in UTC");
        check(!matcher(Filter.day(day(1970, 1, 1)), NEW_YORK).matches(0L), "0 ms is not on 1 Jan 1970 in New York");
        check(matcher(Filter.day(day(1969, 12, 31)), NEW_YORK).matches(0L), "0 ms is on 31 Dec 1969 in New York");
    }

    private static void daylightSaving() {
        long springStart = at("2026-03-08T00:00", "-05:00");
        long springEnd = at("2026-03-09T00:00", "-04:00");
        equal(23 * HOUR, springEnd - springStart, "Spring-forward day is 23 hours, not 24");
        window(day(2026, 3, 8), NEW_YORK, springStart, springEnd, "New York spring-forward day");
        window(day(2026, 3, 7), NEW_YORK, at("2026-03-07T00:00", "-05:00"), springStart, "Day before spring-forward");
        window(day(2026, 3, 9), NEW_YORK, springEnd, at("2026-03-10T00:00", "-04:00"), "Day after spring-forward");
        check(matcher(Filter.day(day(2026, 3, 8)), NEW_YORK).matches(at("2026-03-08T03:30", "-04:00")),
                "After the gap is still the same civil day");

        long fallStart = at("2026-11-01T00:00", "-04:00");
        long fallEnd = at("2026-11-02T00:00", "-05:00");
        equal(25 * HOUR, fallEnd - fallStart, "Fall-back day is 25 hours, not 24");
        window(day(2026, 11, 1), NEW_YORK, fallStart, fallEnd, "New York fall-back day");
        Matcher fall = matcher(Filter.day(day(2026, 11, 1)), NEW_YORK);
        check(fall.matches(at("2026-11-01T01:30", "-04:00")), "The first 01:30 belongs to the fall-back day");
        check(fall.matches(at("2026-11-01T01:30", "-05:00")), "The repeated 01:30 belongs to the fall-back day");
        window(day(2026, 11, 2), NEW_YORK, fallEnd, at("2026-11-03T00:00", "-05:00"), "Day after fall-back");
    }

    private static void midnightTransitions() {
        // Cuba springs forward at midnight: 00:00 -> 01:00, so 10 Mar 2019 starts at its earliest valid time, 01:00.
        long gapStart = at("2019-03-10T01:00", "-04:00");
        long gapEnd = at("2019-03-11T00:00", "-04:00");
        equal(23 * HOUR, gapEnd - gapStart, "Skipped-midnight day is 23 hours");
        window(day(2019, 3, 10), HAVANA, gapStart, gapEnd, "Havana skipped-midnight day");
        window(day(2019, 3, 9), HAVANA, at("2019-03-09T00:00", "-05:00"), gapStart, "Day before skipped midnight");

        // Cuba falls back from 01:00 to 00:00, so midnight happens twice on 3 Nov 2019; the day starts at the first.
        long overlapStart = at("2019-11-03T00:00", "-04:00");
        long overlapEnd = at("2019-11-04T00:00", "-05:00");
        equal(25 * HOUR, overlapEnd - overlapStart, "Repeated-midnight day is 25 hours");
        window(day(2019, 11, 3), HAVANA, overlapStart, overlapEnd, "Havana repeated-midnight day");
        window(day(2019, 11, 2), HAVANA, at("2019-11-02T00:00", "-04:00"), overlapStart, "Day before repeated midnight");
        Matcher overlap = matcher(Filter.day(day(2019, 11, 3)), HAVANA);
        check(overlap.matches(at("2019-11-03T00:30", "-04:00")), "The first 00:30 belongs to the repeated-midnight day");
        check(overlap.matches(at("2019-11-03T00:30", "-05:00")), "The second 00:30 belongs to the same day");
    }

    private static void skippedDay() {
        // Samoa jumped from the end of 29 Dec 2011 (-10:00) straight to 31 Dec (+14:00): 30 Dec never existed there.
        LocalDate skipped = day(2011, 12, 30);
        long jump = at("2011-12-31T00:00", "+14:00");
        equal(at("2011-12-30T00:00", "-10:00"), jump, "The 29th's end is the 31st's start");
        window(day(2011, 12, 29), APIA, at("2011-12-29T00:00", "-10:00"), jump, "Day before the skipped day");
        window(day(2011, 12, 31), APIA, jump, at("2012-01-01T00:00", "+14:00"), "Day after the skipped day");

        Matcher none = matcher(Filter.day(skipped), APIA);
        check(!none.matches(jump - 1) && !none.matches(jump), "The skipped day's window is empty at the jump");
        List<Long> sweep = new ArrayList<>();
        for (long t = at("2011-12-28T00:00", "-10:00"); t < at("2012-01-02T00:00", "+14:00"); t += 30 * MINUTE) {
            sweep.add(t);
            if (none.matches(t)) fail("The skipped day matched " + t);
            if (skipped.equals(civilDay(t, APIA))) fail("An instant mapped to the skipped day: " + t);
        }
        checks++;
        Counts counts = countDeadlines(sweep, APIA);
        equal(0, counts.on(skipped), "No deadline is ever counted on the skipped day");
        check(!counts.days.containsKey(skipped), "The skipped day has no count entry");
        equal(48, counts.on(day(2011, 12, 29)), "The 29th keeps its 24 half-hours");
        equal(48, counts.on(day(2011, 12, 31)), "The 31st keeps its 24 half-hours");
    }

    private static void timeZoneChange() {
        Filter selected = Filter.day(day(2026, 10, 25));
        long earlyTashkent = at("2026-10-25T02:00", "+05:00");
        long eveningNewYork = at("2026-10-25T20:00", "-04:00");
        Matcher inTashkent = matcher(selected, TASHKENT);
        Matcher inNewYork = matcher(selected, NEW_YORK);
        check(inTashkent.matches(earlyTashkent), "25 Oct 02:00 in Tashkent is on the selected day there");
        check(!inNewYork.matches(earlyTashkent), "After moving to New York the same instant is 24 Oct, not selected");
        check(inNewYork.matches(eveningNewYork), "After moving, the selected civil date uses New York's day");
        check(!inTashkent.matches(eveningNewYork), "That instant is already 26 Oct in Tashkent");
        equal(day(2026, 10, 25), selected.day, "The selected civil date survives the zone change unchanged");

        long instant = at("2026-10-26T00:30", "+14:00");
        equal(day(2026, 10, 26), civilDay(instant, KIRITIMATI), "Kiritimati (+14) is already the 26th");
        equal(day(2026, 10, 25), civilDay(instant, UTC), "UTC is the 25th");
        equal(day(2026, 10, 24), civilDay(instant, PAGO_PAGO), "Pago Pago (-11) is still the 24th");
    }

    private static void counts() {
        Counts empty = countDeadlines(Collections.<Long>emptyList(), UTC);
        check(empty.days.isEmpty(), "No tasks, no day counts");
        equal(0, empty.undated, "No tasks, no undated count");
        equal(0, empty.on(day(2026, 10, 25)), "A day without tasks counts 0");

        Counts nulls = countDeadlines(Arrays.asList((Long) null, null), UTC);
        check(nulls.days.isEmpty(), "Null deadlines never land on a day");
        equal(2, nulls.undated, "Every null deadline is counted as no deadline");

        List<Long> due = new ArrayList<>(Arrays.asList(0L, null, 0L, 86_399_999L, 86_400_000L, -1L, null));
        List<Long> before = new ArrayList<>(due);
        Counts utc = countDeadlines(due, UTC);
        equal(3, utc.on(day(1970, 1, 1)), "Duplicates (0, 0) and the last millisecond all count on 1 Jan");
        equal(1, utc.on(day(1970, 1, 2)), "Midnight counts on the next day");
        equal(1, utc.on(day(1969, 12, 31)), "-1 ms counts on 31 Dec");
        equal(2, utc.undated, "Nulls count as no deadline; 0 does not");
        equal(3, utc.days.size(), "Only days with deadlines have entries");
        equal(Arrays.asList(day(1969, 12, 31), day(1970, 1, 1), day(1970, 1, 2)), new ArrayList<>(utc.days.keySet()),
                "Day counts iterate in date order");
        equal(0, utc.on(null), "A padding cell (null date) counts 0");
        equal(before, due, "Counting does not modify the supplied deadlines");

        Counts newYork = countDeadlines(due, NEW_YORK);
        equal(3, newYork.on(day(1969, 12, 31)), "In New York 0, 0 and -1 are all 31 Dec");
        equal(2, newYork.on(day(1970, 1, 1)), "In New York both late-UTC values are 1 Jan");
        equal(2, newYork.undated, "No-deadline count does not depend on the zone");

        due.add(5L);
        due.set(0, null);
        equal(3, utc.on(day(1970, 1, 1)), "Counts are a snapshot: later input changes do not leak in");
        equal(2, utc.undated, "The undated count is a snapshot too");

        // Completed tasks are included by the caller whenever the existing filter shows them; the model counts
        // every supplied deadline, whatever task state produced it.
        long completedDue = at("2026-10-25T09:00", "+05:00");
        long openDue = at("2026-10-25T18:00", "+05:00");
        equal(2, countDeadlines(Arrays.asList(completedDue, openDue), TASHKENT).on(day(2026, 10, 25)),
                "Every projected deadline is counted");
    }

    private static void countsAgreeWithMatchers() {
        List<Long> due = new ArrayList<>();
        long end = at("2026-11-04T00:00", "-05:00");
        int index = 0;
        for (long t = at("2026-10-30T00:00", "-04:00"); t < end; t += 37 * MINUTE + 1) {
            due.add(index++ % 5 == 0 ? null : t);
        }
        due.add(0L);
        due.add(0L);
        Counts counts = countDeadlines(due, NEW_YORK);

        int total = counts.undated;
        for (Map.Entry<LocalDate, Integer> entry : counts.days.entrySet()) total += entry.getValue();
        equal(due.size(), total, "Every deadline is counted exactly once");

        equal(due.size(), matched(matcher(Filter.all(), NEW_YORK), due), "All matches every deadline");
        equal(counts.undated, matched(matcher(Filter.undated(), NEW_YORK), due), "No deadline agrees with its count");
        List<LocalDate> days = new ArrayList<>(Arrays.asList(day(1969, 12, 30), day(1969, 12, 31), day(1970, 1, 1)));
        for (LocalDate d = day(2026, 10, 28); d.isBefore(day(2026, 11, 6)); d = d.plusDays(1)) days.add(d);
        for (LocalDate d : days) {
            equal(counts.on(d), matched(matcher(Filter.day(d), NEW_YORK), due), "Day count agrees with its filter " + d);
        }
    }

    private static int matched(Matcher matcher, List<Long> due) {
        int count = 0;
        for (Long value : due) {
            if (matcher.matches(value)) count++;
        }
        return count;
    }

    private static void extremes() {
        equal(LAST_DAY, civilDay(Long.MAX_VALUE, UTC), "The largest millisecond has a civil day");
        equal(FIRST_DAY, civilDay(Long.MIN_VALUE, UTC), "The smallest millisecond has a civil day");
        equal(LAST_DAY.plusDays(1), civilDay(Long.MAX_VALUE, ZoneOffset.ofHours(18)), "+18:00 shifts the last day");
        equal(FIRST_DAY.minusDays(1), civilDay(Long.MIN_VALUE, ZoneOffset.ofHours(-18)), "-18:00 shifts the first day");
        equal(LAST_DAY, civilDay(Long.MAX_VALUE, NEW_YORK), "A region zone handles the largest millisecond");

        // The end of LAST_DAY lies past Long.MAX_VALUE milliseconds; the window must not overflow.
        check(matcher(Filter.day(LAST_DAY), UTC).matches(Long.MAX_VALUE), "Last day matches the largest millisecond");
        check(matcher(Filter.day(LAST_DAY), NEW_YORK).matches(Long.MAX_VALUE), "Same in a region zone");
        check(!matcher(Filter.day(LAST_DAY), UTC).matches(Long.MIN_VALUE), "Last day rejects the smallest millisecond");
        check(matcher(Filter.day(FIRST_DAY), UTC).matches(Long.MIN_VALUE), "First day matches the smallest millisecond");
        check(!matcher(Filter.day(LAST_DAY.plusDays(1)), UTC).matches(Long.MAX_VALUE),
                "A day after every representable deadline matches none");

        Matcher maxDate = matcher(Filter.day(LocalDate.MAX), UTC);
        check(!maxDate.matches(Long.MAX_VALUE) && !maxDate.matches(0L), "LocalDate.MAX is selectable and matches none");
        Matcher minDate = matcher(Filter.day(LocalDate.MIN), ZoneOffset.ofHours(18));
        check(!minDate.matches(Long.MIN_VALUE) && !minDate.matches(0L), "LocalDate.MIN is selectable and matches none");

        Counts counts = countDeadlines(Arrays.asList(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE), UTC);
        equal(2, counts.on(LAST_DAY), "Extreme deadlines are counted, not dropped");
        equal(1, counts.on(FIRST_DAY), "The smallest deadline is counted");
    }

    private static void monthGrid() {
        List<LocalDate> leapMonday = monthDays(YearMonth.of(2024, 2), DayOfWeek.MONDAY);
        equal(35, leapMonday.size(), "Feb 2024 from Monday is five weeks");
        equal(Arrays.asList(null, null, null, day(2024, 2, 1)), leapMonday.subList(0, 4),
                "1 Feb 2024 is a Thursday: three padding cells, no January dates");
        equal(day(2024, 2, 29), leapMonday.get(31), "Leap day 29 Feb is present");
        equal(Arrays.asList(null, null, null), leapMonday.subList(32, 35), "Trailing padding, no March dates");

        List<LocalDate> leapSunday = monthDays(YearMonth.of(2024, 2), DayOfWeek.SUNDAY);
        equal(35, leapSunday.size(), "Feb 2024 from Sunday is five weeks");
        equal(day(2024, 2, 1), leapSunday.get(4), "Sunday-first weeks put Thursday in column 5");
        equal(null, leapSunday.get(33), "Sunday-first trailing padding");

        List<LocalDate> leapSaturday = monthDays(YearMonth.of(2024, 2), DayOfWeek.SATURDAY);
        equal(day(2024, 2, 1), leapSaturday.get(5), "Saturday-first weeks put Thursday in column 6");
        equal(day(2024, 2, 29), leapSaturday.get(33), "Saturday-first last day");
        equal(null, leapSaturday.get(34), "Saturday-first single trailing cell");

        List<LocalDate> exact = monthDays(YearMonth.of(2021, 2), DayOfWeek.MONDAY);
        equal(28, exact.size(), "Feb 2021 from Monday is exactly four weeks");
        equal(day(2021, 2, 1), exact.get(0), "No leading padding");
        equal(day(2021, 2, 28), exact.get(27), "No trailing padding");

        List<LocalDate> sixWeeks = monthDays(YearMonth.of(2026, 5), DayOfWeek.SUNDAY);
        equal(42, sixWeeks.size(), "May 2026 from Sunday needs six weeks");
        equal(day(2026, 5, 1), sixWeeks.get(5), "1 May 2026 is a Friday");
        equal(day(2026, 5, 31), sixWeeks.get(35), "31 May opens the sixth week");
        equal(35, monthDays(YearMonth.of(2026, 5), DayOfWeek.MONDAY).size(), "From Monday it fits five weeks");

        equal(28, inMonth(YearMonth.of(1900, 2)), "1900 is not a Gregorian leap year");
        equal(29, inMonth(YearMonth.of(2000, 2)), "2000 is a Gregorian leap year");
        equal(29, inMonth(YearMonth.of(2024, 2)), "2024 is a leap year");
        equal(28, inMonth(YearMonth.of(2026, 2)), "2026 is not a leap year");
        equal(28, inMonth(YearMonth.of(2100, 2)), "2100 is not a Gregorian leap year");

        List<LocalDate> december = monthDays(YearMonth.of(2026, 12), DayOfWeek.MONDAY);
        equal(day(2026, 12, 31), december.get(31), "31 Dec 2026 (a Thursday) ends December");
        equal(Arrays.asList(null, null, null), december.subList(32, 35), "No January dates in December's grid");
        List<LocalDate> january = monthDays(YearMonth.of(2027, 1), DayOfWeek.MONDAY);
        equal(Arrays.asList(null, null, null, null, day(2027, 1, 1)), january.subList(0, 5),
                "1 Jan 2027 is a Friday; no December dates in January's grid");
    }

    private static int inMonth(YearMonth month) {
        int count = 0;
        for (LocalDate cell : monthDays(month, DayOfWeek.MONDAY)) {
            if (cell != null) count++;
        }
        return count;
    }

    private static void gridInvariants() {
        for (YearMonth month = YearMonth.of(1899, 1); !month.isAfter(YearMonth.of(2101, 12)); month = month.plusMonths(1)) {
            for (DayOfWeek first : DayOfWeek.values()) gridInvariant(month, first);
        }
    }

    private static void gridInvariant(YearMonth month, DayOfWeek first) {
        String label = month + " from " + first;
        List<LocalDate> grid = monthDays(month, first);
        int size = grid.size();
        if (size % 7 != 0 || size < 28 || size > 42) fail(label + ": not whole weeks: " + size);
        int leading = 0;
        while (grid.get(leading) == null) leading++;
        if (leading >= 7) fail(label + ": a whole padding week at the start");
        int length = month.lengthOfMonth();
        int trailing = size - leading - length;
        if (trailing < 0 || trailing >= 7) fail(label + ": trailing padding " + trailing);
        for (int i = 0; i < size; i++) {
            LocalDate cell = grid.get(i);
            if (i < leading || i >= leading + length) {
                if (cell != null) fail(label + ": padding cell " + i + " holds " + cell);
                continue;
            }
            if (!month.atDay(i - leading + 1).equals(cell)) fail(label + ": cell " + i + " holds " + cell);
            if (cell.getDayOfWeek() != first.plus(i % 7)) fail(label + ": " + cell + " in the wrong column " + i % 7);
        }
        checks++;
    }

    private static void immutability() {
        final List<LocalDate> grid = monthDays(YearMonth.of(2026, 10), DayOfWeek.MONDAY);
        throwsOn(UnsupportedOperationException.class, () -> grid.add(day(2026, 11, 1)), "Grid rejects add");
        throwsOn(UnsupportedOperationException.class, () -> grid.set(0, day(2026, 9, 30)), "Grid rejects set");
        throwsOn(UnsupportedOperationException.class, () -> grid.remove(0), "Grid rejects remove");
        equal(grid, monthDays(YearMonth.of(2026, 10), DayOfWeek.MONDAY), "Grids are deterministic values");

        final Counts counts = countDeadlines(Arrays.asList(0L, 0L), UTC);
        throwsOn(UnsupportedOperationException.class, () -> counts.days.put(day(2026, 1, 1), 1), "Counts reject put");
        throwsOn(UnsupportedOperationException.class, () -> counts.days.remove(day(1970, 1, 1)), "Counts reject remove");
        throwsOn(UnsupportedOperationException.class, () -> counts.days.clear(), "Counts reject clear");
        throwsOn(UnsupportedOperationException.class, () -> counts.days.entrySet().iterator().next().setValue(9),
                "Counts reject entry writes");
        equal(2, counts.on(day(1970, 1, 1)), "Counts are unchanged after rejected writes");
    }

    private static void nullArguments() {
        throwsOn(NullPointerException.class, () -> Filter.day(null), "A day filter needs a date");
        throwsOn(NullPointerException.class, () -> matcher(null, UTC), "A matcher needs a filter");
        throwsOn(NullPointerException.class, () -> matcher(Filter.all(), null), "A matcher needs a zone");
        throwsOn(NullPointerException.class, () -> civilDay(0L, null), "A civil day needs a zone");
        throwsOn(NullPointerException.class, () -> countDeadlines(null, UTC), "Counting needs deadlines");
        throwsOn(NullPointerException.class, () -> countDeadlines(Collections.<Long>emptyList(), null),
                "Counting needs a zone even for an empty list");
        throwsOn(NullPointerException.class, () -> monthDays(null, DayOfWeek.MONDAY), "A grid needs a month");
        throwsOn(NullPointerException.class, () -> monthDays(YearMonth.of(2026, 10), null), "A grid needs a week start");
    }
}
