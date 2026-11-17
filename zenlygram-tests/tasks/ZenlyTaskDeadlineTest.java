import org.telegram.messenger.zenlygram.tasks.ZenlyTaskDeadline;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskDeadline.Draft;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskDeadline.Session;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskDeadline.Shortcut;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskDeadline.*;

/**
 * Run through run_tests.py; no Android SDK or JUnit dependency. Expected instants are written as offset date-times
 * (independent of the zone rules under test), so a daylight-saving mistake in the model cannot also hide in the
 * expectation.
 */
public final class ZenlyTaskDeadlineTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final ZoneId APIA = ZoneId.of("Pacific/Apia");
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(long expected, Long actual, String message) {
        checks++;
        if (actual == null || expected != actual) {
            throw new AssertionError(message + ": expected " + expected + " got " + actual);
        }
    }

    /** An instant from a local wall time and an explicit UTC offset. */
    private static long at(String localDateTime, String offset) {
        return OffsetDateTime.of(LocalDateTime.parse(localDateTime), ZoneOffset.of(offset)).toInstant().toEpochMilli();
    }

    public static void main(String[] args) {
        suggestions();
        shortcuts();
        daylightSaving();
        bounds();
        drafts();
        sessions();
        System.out.println("Task deadline: " + checks + " checks passed");
    }

    private static void suggestions() {
        equal(at("2026-10-24T15:00", "+05:00"), suggestion(at("2026-10-24T14:20:31.123", "+05:00"), TASHKENT),
                "Suggestion is the next local whole hour");
        equal(at("2026-10-24T15:00", "+05:00"), suggestion(at("2026-10-24T14:00", "+05:00"), TASHKENT),
                "On the hour still suggests the next one, never now");
        equal(at("2026-03-08T03:00", "-04:00"), suggestion(at("2026-03-08T01:20", "-05:00"), NEW_YORK),
                "Spring-forward gap: the next real hour boundary is 03:00 EDT");
        equal(at("2026-11-01T01:00", "-05:00"), suggestion(at("2026-11-01T01:20", "-04:00"), NEW_YORK),
                "Fall-back: from the first 01:20 the next boundary is the second 01:00");
        equal(MAX_CHOICE_MILLIS, suggestion(MAX_CHOICE_MILLIS + 86_400_000L, ZoneOffset.UTC),
                "A clock past the range suggests the latest choosable instant, not an unsavable one");
        check(isChoosable(suggestion(-5_000L, ZoneOffset.UTC)), "A broken clock still suggests a savable value");
    }

    private static void shortcuts() {
        long now = at("2026-10-24T10:00", "+05:00");
        long selected = at("2026-10-24T18:45:12.345", "+05:00");
        equal(selected, shortcutTarget(Shortcut.TODAY, selected, now, TASHKENT), "Today on today's draft is unchanged");
        equal(at("2026-10-25T18:45:12.345", "+05:00"), shortcutTarget(Shortcut.TOMORROW, selected, now, TASHKENT),
                "Tomorrow keeps the time of day to the millisecond");
        equal(at("2026-10-31T18:45:12.345", "+05:00"), shortcutTarget(Shortcut.NEXT_WEEK, selected, now, TASHKENT),
                "In a week is seven calendar days");
        long overdue = at("2026-09-01T23:59:59.999", "+05:00");
        equal(at("2026-10-24T23:59:59.999", "+05:00"), shortcutTarget(Shortcut.TODAY, overdue, now, TASHKENT),
                "Today moves an overdue legacy end-of-day deadline to today's end of day");

        long lateUtc = at("2026-10-24T20:30", "Z");
        equal(at("2026-10-25T09:00", "+05:00"), shortcutTarget(Shortcut.TODAY, at("2026-10-24T09:00", "+05:00"), lateUtc, TASHKENT),
                "Today is the device zone's day (already 25 Oct in Tashkent), not UTC's");
        equal(at("2026-10-25T09:00", "Z"), shortcutTarget(Shortcut.TOMORROW, at("2026-10-24T09:00", "Z"), lateUtc, ZoneOffset.UTC),
                "The same instant in UTC is still 24 Oct, so Tomorrow is 25 Oct");
    }

    private static void daylightSaving() {
        long beforeSpring = at("2026-03-05T09:00", "-05:00");
        equal(at("2026-03-12T09:00", "-04:00"), shortcutTarget(Shortcut.NEXT_WEEK, beforeSpring, beforeSpring, NEW_YORK),
                "A week across spring-forward keeps 09:00 local (167 hours, not 168)");
        long beforeFall = at("2026-10-29T09:00", "-04:00");
        equal(at("2026-11-05T09:00", "-05:00"), shortcutTarget(Shortcut.NEXT_WEEK, beforeFall, beforeFall, NEW_YORK),
                "A week across fall-back keeps 09:00 local (169 hours)");

        long gapClock = at("2026-03-07T02:30", "-05:00");
        equal(at("2026-03-08T03:30", "-04:00"), shortcutTarget(Shortcut.TOMORROW, gapClock, gapClock, NEW_YORK),
                "02:30 does not exist on spring-forward day: it moves forward by the gap, keeping minutes");
        long repeatedClock = at("2026-10-31T01:30", "-04:00");
        equal(at("2026-11-01T01:30", "-04:00"), shortcutTarget(Shortcut.TOMORROW, repeatedClock, repeatedClock, NEW_YORK),
                "01:30 happens twice on fall-back day: the first occurrence");
        long secondOccurrence = at("2026-11-01T01:30", "-05:00");
        equal(secondOccurrence, shortcutTarget(Shortcut.TODAY, secondOccurrence, at("2026-11-01T08:00", "-05:00"), NEW_YORK),
                "Today on a draft already on that day keeps the second occurrence");

        long skippedMidnight = at("2018-11-03T00:30", "-03:00");
        equal(at("2018-11-04T01:30", "-02:00"), shortcutTarget(Shortcut.TOMORROW, skippedMidnight, at("2018-11-03T12:00", "-03:00"), SAO_PAULO),
                "A day whose midnight is skipped still resolves to that day");
        long beforeSkippedDay = at("2011-12-29T10:00", "-10:00");
        equal(at("2011-12-31T10:00", "+14:00"), shortcutTarget(Shortcut.TOMORROW, beforeSkippedDay, beforeSkippedDay, APIA),
                "A calendar day that never happened (Samoa 2011-12-30) moves forward, never backward");

        Draft legacy = Draft.open(at("2026-03-07T23:59:59.999", "-05:00"), beforeSpring, NEW_YORK);
        Draft moved = legacy.withDay(LocalDate.of(2026, 3, 8), NEW_YORK);
        equal(at("2026-03-08T23:59:59.999", "-04:00"), moved.selected, "Changing the day keeps a legacy end of day across DST");
        Draft inGap = Draft.open(at("2026-03-08T00:00", "-05:00"), beforeSpring, NEW_YORK).withClock(2, 15, NEW_YORK);
        equal(at("2026-03-08T03:15", "-04:00"), inGap.selected, "A wheel time inside the gap moves forward");
        check(local(inGap.selected, NEW_YORK).getHour() == 3, "The wheels re-read 03:15, the time actually saved");
    }

    private static void bounds() {
        check(!isChoosable(0L) && !isChoosable(-1L), "Storage rejects due_at <= 0");
        check(isChoosable(MIN_CHOICE_MILLIS) && isChoosable(MAX_CHOICE_MILLIS), "Range ends are choosable");
        check(!isChoosable(MAX_CHOICE_MILLIS + 1), "Past whole Int32 seconds is not choosable");
        equal(2_147_483_647L, MAX_CHOICE_MILLIS / 1000L, "Latest choice floors to Int32.MAX seconds");

        long nearEnd = at("2038-01-15T12:00", "Z");
        check(shortcutTarget(Shortcut.NEXT_WEEK, nearEnd, nearEnd, ZoneOffset.UTC) == null, "Out-of-range shortcut is disabled, not clamped");
        check(shortcutTarget(Shortcut.TOMORROW, nearEnd, nearEnd, ZoneOffset.UTC) != null, "In-range shortcut stays available");
        Draft unchanged = Draft.open(nearEnd, nearEnd, ZoneOffset.UTC);
        check(unchanged.withShortcut(Shortcut.NEXT_WEEK, nearEnd, ZoneOffset.UTC) == unchanged, "Disabled shortcut leaves the draft as is");

        Draft early = Draft.open(at("1970-01-02T00:30", "+05:00"), nearEnd, TASHKENT).withDay(LocalDate.of(1970, 1, 1), TASHKENT);
        check(early.touched && early.selected < 0 && !early.canSave(), "A chosen time before 1970 UTC cannot be saved");
        try {
            early.savedMillis();
            throw new AssertionError("An unsavable draft must not produce a value");
        } catch (IllegalStateException expected) { checks++; }

        long[] range = dayRange(null, TASHKENT);
        equal(LocalDate.of(1970, 1, 1).toEpochDay(), range[0], "Wheel starts on the zone's day of the first choosable instant");
        equal(LocalDate.of(2038, 1, 19).toEpochDay(), range[1], "Wheel ends on the zone's day of the last choosable instant");
        check(dayRange(null, ZoneId.of("America/Los_Angeles"))[0] == LocalDate.of(1969, 12, 31).toEpochDay(),
                "West of UTC the first choosable instant falls on 31 Dec 1969");
        long legacyFuture = at("2099-12-31T23:59:59.999", "+05:00");
        long[] widened = dayRange(legacyFuture, TASHKENT);
        equal(LocalDate.of(2099, 12, 31).toEpochDay(), widened[1], "A stored deadline past 2038 widens the wheel, never clamps");
        check(widened[0] == range[0], "Widening one end keeps the other");
        long[] absurd = dayRange(Long.MAX_VALUE, ZoneOffset.UTC);
        check(absurd[1] - absurd[0] < Integer.MAX_VALUE, "An absurd stored value never overflows the int wheel");
    }

    private static void drafts() {
        ZoneId zone = TASHKENT;
        long now = at("2026-10-24T10:05", "+05:00");
        long stored = at("2026-10-26T23:59:59.999", "+05:00");
        Draft draft = Draft.open(stored, now, zone);
        check(!draft.touched && draft.canSave(), "An opened draft is untouched and savable");
        equal(stored, draft.savedMillis(), "Untouched save keeps the stored milliseconds exactly");

        Draft outOfRange = Draft.open(MAX_CHOICE_MILLIS + 12_345L, now, zone);
        equal(MAX_CHOICE_MILLIS + 12_345L, outOfRange.savedMillis(), "Untouched out-of-range legacy value is kept, not truncated");

        Draft fresh = Draft.open(null, now, zone);
        check(fresh.original == null && !fresh.touched, "No deadline opens on a suggestion");
        equal(at("2026-10-24T11:00", "+05:00"), fresh.savedMillis(), "Saving untouched without a deadline stores the suggestion");

        Draft clock = draft.withClock(9, 30, zone);
        equal(at("2026-10-26T09:30", "+05:00"), clock.selected, "Wheel time edits are whole minutes");
        check(clock.touched, "Wheel edits mark the draft");
        check(draft.selected == stored && !draft.touched, "Edits return a new draft; the opened one is untouched (Cancel discards)");
        check(draft.withClock(23, 59, zone).selected == at("2026-10-26T23:59", "+05:00"),
                "Picking 23:59 on a 23:59:59.999 deadline is an explicit whole-minute choice");
        check(draft.withDay(LocalDate.of(2026, 10, 26), zone) == draft, "Picking the same day changes nothing");
        check(draft.withShortcut(Shortcut.TODAY, at("2026-10-26T08:00", "+05:00"), zone) == draft,
                "A shortcut onto the current day keeps the draft untouched");
        Draft back = draft.withClock(9, 30, zone).select(stored);
        equal(stored, back.savedMillis(), "Returning to the stored instant saves it exactly");
    }

    private static void sessions() {
        ZoneId zone = ZoneOffset.UTC;
        long now = at("2026-10-24T10:00", "Z");
        long stored = at("2026-10-24T23:59:59.999", "Z");
        List<Long> delivered = new ArrayList<>();

        Session cancelled = new Session(Draft.open(stored, now, zone), delivered::add);
        cancelled.update(cancelled.draft().withShortcut(Shortcut.NEXT_WEEK, now, zone));
        check(cancelled.cancel() && delivered.isEmpty(), "Cancel / back / outside tap delivers nothing");
        check(!cancelled.save() && !cancelled.remove() && delivered.isEmpty(), "Nothing is delivered after cancelling");
        check(!cancelled.update(Draft.open(null, now, zone)), "A finished sheet ignores late wheel callbacks");

        Session saved = new Session(Draft.open(stored, now, zone), delivered::add);
        saved.update(saved.draft().withShortcut(Shortcut.TOMORROW, now, zone));
        check(saved.save(), "Save delivers");
        check(!saved.save() && !saved.remove() && !saved.cancel(), "A double tap delivers once");
        check(delivered.size() == 1 && delivered.get(0) == at("2026-10-25T23:59:59.999", "Z"), "Save delivers the draft exactly");

        delivered.clear();
        Session untouched = new Session(Draft.open(stored, now, zone), delivered::add);
        check(untouched.save() && delivered.get(0) == stored, "Untouched Save delivers the stored milliseconds");

        delivered.clear();
        Session removed = new Session(Draft.open(stored, now, zone), delivered::add);
        check(removed.canRemove() && removed.remove(), "An existing deadline can be removed");
        check(delivered.size() == 1 && delivered.get(0) == null, "Remove delivers null once");
        check(!removed.remove() && delivered.size() == 1, "Removing twice delivers once");

        delivered.clear();
        Session none = new Session(Draft.open(null, now, zone), delivered::add);
        check(!none.canRemove() && !none.remove() && !none.isFinished(), "Without a deadline there is nothing to remove");

        Session blocked = new Session(Draft.open(at("1970-01-02T00:30", "Z"), now, zone), delivered::add);
        blocked.update(blocked.draft().withDay(LocalDate.of(1969, 12, 31), zone));
        check(!blocked.save() && !blocked.isFinished() && delivered.isEmpty(), "An unsavable draft keeps the sheet open");
        check(blocked.cancel(), "It can still be cancelled");
        check(LocalTime.of(0, 30).equals(local(blocked.draft().selected, zone).toLocalTime()), "The rejected draft is not altered");
    }
}
