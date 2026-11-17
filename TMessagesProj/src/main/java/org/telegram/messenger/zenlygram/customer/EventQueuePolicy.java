package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The event queue's bounds, batches and answers (contract §3.1, §6.5; design 50 §11.5–§11.6), pure and host-tested;
 * ZenlyEventStore and ZenlyEventUploader apply the decisions.
 * <ul>
 * <li>Bounds per (origin scope, account): at most 5000 events, 2 MiB of event JSON and 7 days of age; the oldest go
 * first ({@link #oldestKept}, {@link Trim}).</li>
 * <li>A batch is the oldest run of events recorded under the first sendable event's context (batches split where the
 * context changes), at most {@code limit} (200, halved by each 413 down to 1) events and 256 KiB for the whole
 * envelope. Events more than 7 days before {@code sent_at} or more than 300 s after it are never sent (the server
 * would refuse them as {@code stale} / {@code future}): they are deleted instead ({@link Selector}).</li>
 * <li>The answer ({@link #afterAnswer}): 200 deletes exactly the sent events (accepted, duplicate and rejected alike)
 * and keeps {@code flush_interval_s} (60..3600); {@code "ingest": "paused"} also pauses uploads for 24 h; 413 keeps
 * them and halves the limit; 422 and every other dropped payload deletes them and counts
 * {@code error.occurred{events_upload, <code>}}; 429, 5xx, 404 and offline keep them and back off; consent, binding and
 * session answers keep them for the coordinator to decide.</li>
 * </ul>
 */
public final class EventQueuePolicy {
    public static final int MAX_EVENTS = 5000;
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long MAX_AGE_SECONDS = 7L * 86400;
    /** The server refuses an event more than this far after receipt ({@code future}). */
    public static final long MAX_FUTURE_SECONDS = 300;
    public static final int MAX_BATCH_EVENTS = 200;
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    /** A flush is due at once with this many events queued for an account. */
    public static final int FLUSH_THRESHOLD = 50;
    public static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 300, MIN_FLUSH_INTERVAL_SECONDS = 60, MAX_FLUSH_INTERVAL_SECONDS = 3600;
    /** {@code "ingest": "paused"} stops event uploads this long. */
    public static final long PAUSE_SECONDS = 86400;

    private EventQueuePolicy() {}

    // ---- Bounds ----

    /** Events with a {@code ts} below this go (older than 7 days at {@code nowS}). */
    public static long oldestKept(long nowS) { return nowS - MAX_AGE_SECONDS; }

    /** Whether a queue of {@code count} events and {@code bytes} bytes is above a bound. */
    public static boolean overBounds(long count, long bytes) { return count > MAX_EVENTS || bytes > MAX_BYTES; }

    /**
     * Oldest-first trimming: start with the queue's totals, then {@link #drop} the oldest events (their sizes, oldest
     * first) while {@link #over}; the dropped ones are deleted.
     */
    public static final class Trim {
        private long count;
        private long bytes;

        public Trim(long count, long bytes) {
            this.count = count;
            this.bytes = bytes;
        }

        public boolean over() { return overBounds(count, bytes); }

        public void drop(int eventBytes) {
            count--;
            bytes -= eventBytes;
        }

        public long count() { return count; }

        public long bytes() { return bytes; }
    }

    // ---- Batches ----

    /** One queued event as the selector sees it (its row id, {@code ts}, context and size). */
    public static final class Row {
        public final long rowId;
        public final long ts;
        public final String contextKey;
        public final int bytes;

        public Row(long rowId, long ts, String contextKey, int bytes) {
            this.rowId = rowId;
            this.ts = ts;
            this.contextKey = contextKey;
            this.bytes = bytes;
        }
    }

    /** The UTF-8 size of a stored context's JSON by its key, or -1 when it is missing. */
    public interface Contexts { int bytes(String contextKey); }

    /**
     * Picks the next batch from the queue read oldest first: {@link #offer} each row until it returns false, then
     * send {@link #selected} under {@link #contextKey} and delete {@link #expired}.
     */
    public static final class Selector {
        private final int limit;
        private final long earliest, latest;
        private final Contexts contexts;
        private final ArrayList<Row> selected = new ArrayList<>();
        private final ArrayList<Row> expired = new ArrayList<>();
        private String contextKey;
        private long envelopeBytes;

        /** {@code limit} is clamped to 1..200; {@code sentAtS} is the device clock the envelope carries. */
        public Selector(int limit, long sentAtS, Contexts contexts) {
            this.limit = Math.max(1, Math.min(MAX_BATCH_EVENTS, limit));
            this.earliest = sentAtS - MAX_AGE_SECONDS;
            this.latest = sentAtS + MAX_FUTURE_SECONDS;
            this.contexts = contexts;
        }

        /** Takes the next oldest row; false = the batch is complete (stop reading). */
        public boolean offer(Row row) {
            if (row.ts < earliest || row.ts > latest) {
                expired.add(row);
                return true;
            }
            if (contextKey == null) {
                int contextBytes = contexts.bytes(row.contextKey);
                if (contextBytes < 0) {
                    // An event whose context is gone can never be sent.
                    expired.add(row);
                    return true;
                }
                long first = CustomerEvents.envelopeBytes(contextBytes) + row.bytes;
                if (first > MAX_BATCH_BYTES) return false;
                contextKey = row.contextKey;
                envelopeBytes = first;
                selected.add(row);
                return true;
            }
            if (!contextKey.equals(row.contextKey) || selected.size() >= limit || envelopeBytes + 1 + row.bytes > MAX_BATCH_BYTES) {
                return false;
            }
            envelopeBytes += 1 + row.bytes;
            selected.add(row);
            return true;
        }

        public List<Row> selected() { return Collections.unmodifiableList(selected); }

        public List<Row> expired() { return Collections.unmodifiableList(expired); }

        /** The context of the selected events, or null when nothing was selected. */
        public String contextKey() { return contextKey; }

        /** The UTF-8 size of the envelope of the selected events (0 when nothing was selected). */
        public long envelopeBytes() { return selected.isEmpty() ? 0 : envelopeBytes; }
    }

    /** The batch limit after a 413 for a batch of {@code batchSize}: half of the smaller one, at least 1. */
    public static int halved(int limit, int batchSize) {
        return Math.max(1, Math.min(Math.max(1, limit), Math.max(1, batchSize)) / 2);
    }

    /** {@code flush_interval_s} within 60..3600; anything not positive is the default 300. */
    public static int flushInterval(long seconds) {
        if (seconds <= 0) return DEFAULT_FLUSH_INTERVAL_SECONDS;
        return (int) Math.max(MIN_FLUSH_INTERVAL_SECONDS, Math.min(MAX_FLUSH_INTERVAL_SECONDS, seconds));
    }

    // ---- Answers ----

    /** What one batch's answer does to the queue and the account's upload state. */
    public static final class Effect {
        /** Delete exactly the events of the batch. */
        public final boolean deleteSent;
        /** Pause uploads until this epoch second (0 = unchanged). */
        public final long pauseUntilS;
        /** Store this {@code flush_interval_s} (0 = unchanged). */
        public final int flushIntervalS;
        /** The batch limit from now on. */
        public final int nextLimit;
        /** Count {@code error.occurred{events_upload, code}} (null = nothing). */
        public final String errorCode;
        /** The account's events route backs off ({@link CustomerBackoff}); reset on {@link CustomerOutcome.Kind#OK}. */
        public final boolean backoff;

        Effect(boolean deleteSent, long pauseUntilS, int flushIntervalS, int nextLimit, String errorCode, boolean backoff) {
            this.deleteSent = deleteSent;
            this.pauseUntilS = pauseUntilS;
            this.flushIntervalS = flushIntervalS;
            this.nextLimit = nextLimit;
            this.errorCode = errorCode;
            this.backoff = backoff;
        }
    }

    /**
     * The effect of {@code outcome} for a batch of {@code batchSize} events sent with {@code limit}. For a 200,
     * {@code ingestPaused} and {@code flushIntervalS} (0 = absent) come from its body.
     */
    public static Effect afterAnswer(CustomerOutcome outcome, boolean ingestPaused, long flushIntervalS, int limit, int batchSize, long nowS) {
        int current = Math.max(1, Math.min(MAX_BATCH_EVENTS, limit));
        switch (outcome.kind) {
            case OK:
                return new Effect(true, ingestPaused ? nowS + PAUSE_SECONDS : 0, flushIntervalS > 0 ? flushInterval(flushIntervalS) : 0,
                        current, null, false);
            case TOO_LARGE:
                return new Effect(false, 0, 0, halved(current, batchSize), null, false);
            case DROP:
            case REJECTED:
                return new Effect(true, 0, 0, current, dropCode(outcome), false);
            case RATE_LIMITED:
            case RETRY_SOON:
            case UNAVAILABLE:
            case BACKOFF:
                return new Effect(false, 0, 0, current, null, true);
            default:
                // Consent, binding, erasure and session answers: the events stay for the coordinator to decide.
                return new Effect(false, 0, 0, current, null, false);
        }
    }

    /** The code a dropped batch is counted under: the server's stable code, else {@code http_<status>}. */
    private static String dropCode(CustomerOutcome outcome) {
        if (ErrorCodes.isCode(outcome.code)) return outcome.code;
        return outcome.status > 0 ? ErrorCodes.http(outcome.status) : ErrorCodes.INVALID_REQUEST;
    }
}
