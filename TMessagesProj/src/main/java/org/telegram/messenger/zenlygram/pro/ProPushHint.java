package org.telegram.messenger.zenlygram.pro;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure parser for the Zenlygram FCM entitlement hint: data {zenlygram_entitlement_refresh: "1",
 * account_id: <opaque backend account id>}. A hint only names which linked account should run the
 * existing authenticated refresh; every other field (grants, expiry, ...) is ignored, so a push can
 * never grant or extend access.
 */
public final class ProPushHint {
    public static final String KEY = "zenlygram_entitlement_refresh";
    static final String ACCOUNT_KEY = "account_id";
    /** Minimum spacing of hint-triggered refreshes per Telegram account. */
    static final long MIN_INTERVAL_MS = 60000;
    // Opaque (existing ids may be ULIDs): bounded printable ASCII, no format assumption.
    private static final Pattern ACCOUNT_ID = Pattern.compile("[\\x21-\\x7E]{1,128}");

    private ProPushHint() { }

    /** True when this push belongs to Zenlygram, valid or not, so Telegram must not decode it. */
    public static boolean isHint(Map<String, String> data) {
        return data != null && data.containsKey(KEY);
    }

    /** The account id to refresh, or null when the hint is malformed. */
    static String accountId(Map<String, String> data) {
        if (!isHint(data) || !"1".equals(data.get(KEY))) return null;
        String account = data.get(ACCOUNT_KEY);
        return account != null && ACCOUNT_ID.matcher(account).matches() ? account : null;
    }

    /** Throttle using elapsed-realtime milliseconds; lastElapsed < 0 means never refreshed by a hint. */
    static boolean due(long lastElapsed, long nowElapsed) {
        return lastElapsed < 0 || nowElapsed - lastElapsed >= MIN_INTERVAL_MS;
    }

    /** Spacing of foreground refresh attempts per account (offline failures retry no faster). */
    static final long FOREGROUND_RETRY_MS = 300000;

    /**
     * Foreground refresh decision. An owed refresh (push hint, or a Google attach whose fresh grant was
     * not adopted) overrides the daily skip of a recent cached grant; attempts stay five minutes apart.
     */
    static boolean foregroundRefreshDue(boolean linked, boolean owed, long sinceLastAttemptMs, boolean hasGrant, long grantAgeSeconds) {
        if (!linked || sinceLastAttemptMs < FOREGROUND_RETRY_MS) return false;
        return owed || !hasGrant || grantAgeSeconds >= ProPolicy.DAY;
    }

    /**
     * Per-account hint throttle that coalesces instead of dropping: a hint inside the interval
     * schedules exactly one trailing refresh at the interval's end, so a later change (e.g. a refund
     * right after a purchase) is still fetched. Immutable.
     */
    static final class Throttle {
        static final Throttle IDLE = new Throttle(-1, false);
        final long lastElapsed;
        final boolean trailingScheduled;

        private Throttle(long lastElapsed, boolean trailingScheduled) {
            this.lastElapsed = lastElapsed;
            this.trailingScheduled = trailingScheduled;
        }

        /** 0: refresh now; > 0: schedule the trailing refresh after this many ms; -1: already scheduled. */
        long delayFor(long nowElapsed) {
            if (due(lastElapsed, nowElapsed)) return 0;
            if (trailingScheduled) return -1;
            return lastElapsed + MIN_INTERVAL_MS - nowElapsed;
        }

        Throttle refreshed(long nowElapsed) { return new Throttle(nowElapsed, false); }
        Throttle trailingScheduled() { return new Throttle(lastElapsed, true); }
    }

    /**
     * Durable hint bookkeeping for one binding. received counts hints; a refresh captures received
     * when it starts and, on success, marks only that prefix consumed. A hint that lands while the
     * refresh is in flight stays pending across process death. Immutable.
     */
    static final class Pending {
        static final Pending NONE = new Pending(0, 0);
        final long received, consumed;

        private Pending(long received, long consumed) {
            this.received = received;
            this.consumed = consumed;
        }

        /** Tolerates corrupt values: negatives become 0 and consumed never exceeds received. */
        static Pending of(long received, long consumed) {
            long r = Math.max(0, received);
            return new Pending(r, Math.min(r, Math.max(0, consumed)));
        }

        boolean pending() { return received > consumed; }

        Pending received() { return received == Long.MAX_VALUE ? this : new Pending(received + 1, consumed); }

        /**
         * Success of a refresh that started when received was startedAt. A sequence this binding never
         * issued (another binding's, or after a reset) or one already covered changes nothing.
         */
        Pending consumedThrough(long startedAt) {
            if (startedAt <= consumed || startedAt > received) return this;
            return new Pending(received, startedAt);
        }
    }
}
