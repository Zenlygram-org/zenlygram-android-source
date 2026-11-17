package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * The client pacer for assignments (iOS SGTasksInvitationPacer, wf15 §12.1): the first line before the server's
 * budgets. Pure: no store and no clock of its own. The ledger keeps content-free counters per account in its meta
 * table — {@code invite_day.<yyyymmdd>} (every manual pick of the UTC day), {@code invite_day.<yyyymmdd>.<recipientTgId>}
 * (per recipient) and {@code invite_minute.<epochMinute>} — incremented when a manual pick is enqueued (a failed send
 * still counts), deleted after two UTC days and with the store. The automatic re-send of a deferred assignment is the
 * user's own pick retried: it neither consults nor charges the pacer. The server stays authoritative (10 per minute,
 * 100 per UTC day, 5 per recipient and UTC day, at most 100 unexpired pending); the client refuses one step earlier, so
 * the owner hears it at pick time as a sentence instead of as a 429.
 */
public final class TasksInvitationPacer {
    /** Which limit one more manual pick would exceed. */
    public enum Limit { PER_PERSON, PER_MINUTE, TOTAL, PENDING }

    /** Per recipient and UTC day (the server refuses the 6th). */
    public static final int RECIPIENT_PER_DAY = 5;
    /** Manual picks per minute (the server's minute bucket is 10). */
    public static final int PICKS_PER_MINUTE = 10;
    /** Per UTC day (the server's day bucket is 100). */
    public static final int PER_DAY = 95;
    /** Owner assignments pending and unexpired (the server holds at most 100). */
    public static final int MAXIMUM_PENDING = 95;
    /** A counter is deleted once it is older than this many UTC days (today and yesterday stay). */
    public static final int COUNTER_LIFETIME_DAYS = 2;

    static final String DAY_PREFIX = "invite_day.", MINUTE_PREFIX = "invite_minute.";

    /** What the account has done so far: the recipient's and the day's counters, this minute's, the pending assignments. */
    public static final class Counts {
        public final int recipientToday, today, thisMinute, pending;

        public Counts(int recipientToday, int today, int thisMinute, int pending) {
            this.recipientToday = recipientToday;
            this.today = today;
            this.thisMinute = thisMinute;
            this.pending = pending;
        }
    }

    private TasksInvitationPacer() { }

    /** The first limit one more manual pick would exceed; null when the pick is allowed. */
    public static Limit refusal(Counts counts) {
        if (counts.recipientToday >= RECIPIENT_PER_DAY) return Limit.PER_PERSON;
        if (counts.thisMinute >= PICKS_PER_MINUTE) return Limit.PER_MINUTE;
        if (counts.today >= PER_DAY) return Limit.TOTAL;
        if (counts.pending >= MAXIMUM_PENDING) return Limit.PENDING;
        return null;
    }

    // ---- counter keys (content-free) ----

    /** The UTC day of {@code epochMs} as yyyymmdd (the server's buckets are UTC days). */
    public static String dayKey(long epochMs) {
        long days = Math.floorDiv(epochMs, 86_400_000L);
        // Civil date from days since 1970-01-01 (proleptic Gregorian).
        long z = days + 719468;
        long era = Math.floorDiv(z, 146097L);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) y++;
        return pad(y, 4) + pad(m, 2) + pad(d, 2);
    }

    private static String pad(long value, int width) {
        StringBuilder out = new StringBuilder(Long.toString(value));
        while (out.length() < width) out.insert(0, '0');
        return out.toString();
    }

    /** The epoch minute of {@code epochMs}. */
    public static long minuteKey(long epochMs) {
        return Math.floorDiv(epochMs, 60_000L);
    }

    public static String dayCounterKey(String day) {
        return DAY_PREFIX + day;
    }

    public static String recipientCounterKey(String day, String recipientTgId) {
        return DAY_PREFIX + day + "." + recipientTgId;
    }

    public static String minuteCounterKey(long minute) {
        return MINUTE_PREFIX + minute;
    }

    /**
     * A counter older than {@link #COUNTER_LIFETIME_DAYS} UTC days (a day key below yesterday's, a minute key below two
     * days ago). A key of neither shape is never reported expired.
     */
    public static boolean isExpired(String counterKey, long nowMs) {
        if (counterKey.startsWith(DAY_PREFIX)) {
            String rest = counterKey.substring(DAY_PREFIX.length());
            int dot = rest.indexOf('.');
            String day = dot < 0 ? rest : rest.substring(0, dot);
            if (day.length() != 8) return false;
            for (int i = 0; i < 8; i++) if (day.charAt(i) < '0' || day.charAt(i) > '9') return false;
            String cutoff = dayKey(nowMs - (COUNTER_LIFETIME_DAYS - 1) * 86_400_000L);
            return day.compareTo(cutoff) < 0;
        }
        if (counterKey.startsWith(MINUTE_PREFIX)) {
            long minute;
            try {
                minute = Long.parseLong(counterKey.substring(MINUTE_PREFIX.length()));
            } catch (NumberFormatException e) {
                return false;
            }
            return minute < minuteKey(nowMs) - COUNTER_LIFETIME_DAYS * 1440L;
        }
        return false;
    }
}
