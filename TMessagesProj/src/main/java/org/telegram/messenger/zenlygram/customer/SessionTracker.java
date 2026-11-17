package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The account session of one (origin scope, Telegram user) (contract §3.6; design 50 §11.2), pure and host-tested:
 * session ids, {@code seq}, the per-session rules and aggregates. ZenlyEventStore keeps one per (scope, tg) on its
 * {@code ZenlygramEvents} thread and stores every returned event together with the session row in one transaction;
 * this class is not thread-safe and does no I/O.
 * <ul>
 * <li>{@link #start} begins a new session ({@code session_id} uuid4, {@code app.session_start} with {@code seq} 0) and
 * reports the screen already visible as its first {@code screen.view}; {@link #end} queues the session's aggregates
 * and {@code app.session_end} with the elapsed-realtime foreground seconds (clamped to 86400).</li>
 * <li>{@code seq} strictly increases per session; nothing is given out past 1 000 000, and an event above 2 KiB is
 * dropped without spending one.</li>
 * <li>{@code screen.view} needs a session started in this process (never attributed to an earlier process's
 * session) and is suppressed when the same screen was this session's last view less than 30 s ago;
 * {@code feature.used} is kept once per feature per session.</li>
 * <li>Every other event outside an open session continues the most recent session (also one of an earlier process,
 * {@link #resume}) and its {@code seq}; with no session at all it is dropped.</li>
 * <li>{@code error.occurred} (per domain and code) and {@code tasks.sync_issue} (per kind) are counted (at most 1000)
 * and materialized at session end and before each batch is built ({@link #drainAggregates}).</li>
 * </ul>
 */
public final class SessionTracker {
    /** The same screen again within this many milliseconds is not a new view (contract §3.3). */
    public static final long SCREEN_DEDUP_MS = 30_000;
    /** The highest {@code next_seq} a session row can hold (one past {@link CustomerEvents#MAX_SEQ}). */
    public static final int MAX_NEXT_SEQ = CustomerEvents.MAX_SEQ + 1;

    /** Fresh uuid4 values for session and event ids (injected so tests are deterministic). */
    public interface Ids { String uuid4(); }

    /** One event ready to store: its envelope fields and its exact JSON. */
    public static final class Stamped {
        public final String eventId;
        /** {@code ts} in epoch seconds (device clock). */
        public final long ts;
        public final String sessionId;
        public final int seq;
        public final CustomerEvents.Event event;
        public final String json;
        /** UTF-8 bytes of {@link #json} (2..2048). */
        public final int bytes;

        Stamped(String eventId, long ts, String sessionId, int seq, CustomerEvents.Event event, String json, int bytes) {
            this.eventId = eventId;
            this.ts = ts;
            this.sessionId = sessionId;
            this.seq = seq;
            this.event = event;
            this.json = json;
            this.bytes = bytes;
        }

        @Override public String toString() { return json; }
    }

    private final Ids ids;
    private String sessionId;
    private int nextSeq;
    private long startedAt;
    private long endedAt;
    private boolean open;
    private boolean startedHere;
    private long startedElapsedMs;
    private CustomerEvents.Screen lastScreen;
    private long lastScreenElapsedMs;
    private final EnumSet<CustomerEvents.Feature> featuresUsed = EnumSet.noneOf(CustomerEvents.Feature.class);
    /** "domain code" → count, in first-seen order. */
    private final LinkedHashMap<String, Integer> errors = new LinkedHashMap<>();
    private final EnumMap<CustomerEvents.SyncIssueKind, Integer> syncIssues = new EnumMap<>(CustomerEvents.SyncIssueKind.class);

    private SessionTracker(Ids ids) {
        if (ids == null) throw new IllegalArgumentException("ids");
        this.ids = ids;
    }

    /** No session yet for this (scope, tg). */
    public static SessionTracker fresh(Ids ids) { return new SessionTracker(ids); }

    /**
     * The most recent session of a stored {@code sessions} row (an earlier process or an ended one): events between
     * sessions continue it. A row that is not a session (bad id or {@code next_seq}) gives {@link #fresh}.
     */
    public static SessionTracker resume(Ids ids, String sessionId, int nextSeq, long startedAt, long endedAt) {
        SessionTracker tracker = new SessionTracker(ids);
        if (CustomerJson.isUuid4(sessionId) && nextSeq >= 0 && nextSeq <= MAX_NEXT_SEQ) {
            tracker.sessionId = sessionId;
            tracker.nextSeq = nextSeq;
            tracker.startedAt = Math.max(0, startedAt);
            tracker.endedAt = Math.max(0, endedAt);
        }
        return tracker;
    }

    // ---- The stored row ----

    /** The current or most recent session id, or null when there is none. */
    public String sessionId() { return sessionId; }

    /** The next {@code seq} this session gives out (the row's {@code next_seq}). */
    public int nextSeq() { return nextSeq; }

    /** Epoch seconds the session started ({@code started_at}). */
    public long startedAt() { return startedAt; }

    /** Epoch seconds the session ended, 0 while open or unknown ({@code ended_at} null). */
    public long endedAt() { return endedAt; }

    /** A session runs (started in this process, not ended). */
    public boolean isOpen() { return open; }

    // ---- Transitions ----

    /**
     * Starts a new session: {@code app.session_start} ({@code seq} 0), then {@code screen.view} of {@code visible} (the
     * screen on top when the session begins) when it is not null. An open session is dropped silently (its end and
     * aggregates were missed).
     */
    public List<Stamped> start(CustomerEvents.SessionTrigger trigger, CustomerEvents.Screen visible, long nowS, long elapsedMs) {
        sessionId = ids.uuid4();
        nextSeq = 0;
        startedAt = nowS;
        endedAt = 0;
        open = true;
        startedHere = true;
        startedElapsedMs = elapsedMs;
        lastScreen = null;
        lastScreenElapsedMs = 0;
        featuresUsed.clear();
        errors.clear();
        syncIssues.clear();
        ArrayList<Stamped> out = new ArrayList<>(2);
        add(out, stamp(CustomerEvents.sessionStart(trigger), nowS));
        if (visible != null) out.addAll(record(CustomerEvents.screenView(visible), nowS, elapsedMs));
        return out;
    }

    /**
     * Ends the open session: its aggregates, then {@code app.session_end} with the foreground seconds since
     * {@link #start}. Nothing when no session is open.
     */
    public List<Stamped> end(CustomerEvents.SessionEndReason reason, long nowS, long elapsedMs) {
        if (!open) return Collections.emptyList();
        ArrayList<Stamped> out = new ArrayList<>(drainAggregates(nowS));
        long durationS = Math.max(0, elapsedMs - startedElapsedMs) / 1000;
        add(out, stamp(CustomerEvents.sessionEnd(durationS, reason), nowS));
        open = false;
        endedAt = Math.max(1, nowS);
        return out;
    }

    /** One event of the account under the session rules above; empty when it is suppressed or dropped. */
    public List<Stamped> record(CustomerEvents.Event event, long nowS, long elapsedMs) {
        if (event == null || sessionId == null) return Collections.emptyList();
        if (event.screen != null) {
            if (!startedHere) return Collections.emptyList();
            if (event.screen == lastScreen && elapsedMs - lastScreenElapsedMs < SCREEN_DEDUP_MS && elapsedMs >= lastScreenElapsedMs) {
                return Collections.emptyList();
            }
            Stamped stamped = stamp(event, nowS);
            if (stamped == null) return Collections.emptyList();
            lastScreen = event.screen;
            lastScreenElapsedMs = elapsedMs;
            return Collections.singletonList(stamped);
        }
        if (event.usedFeature != null && featuresUsed.contains(event.usedFeature)) return Collections.emptyList();
        Stamped stamped = stamp(event, nowS);
        if (stamped == null) return Collections.emptyList();
        if (event.usedFeature != null) featuresUsed.add(event.usedFeature);
        return Collections.singletonList(stamped);
    }

    /** Counts one error of the session ({@code code} normalized to a stable code); emitted later as one aggregate. */
    public void countError(CustomerEvents.ErrorDomain domain, String code) {
        if (domain == null) return;
        String key = domain.name() + ' ' + ErrorCodes.normalize(code);
        Integer count = errors.get(key);
        errors.put(key, count == null ? 1 : Math.min(CustomerEvents.COUNT_MAX, count + 1));
    }

    /** Counts {@code count} (at least 1) Tasks sync issues of {@code kind}; emitted later as one aggregate. */
    public void countSyncIssue(CustomerEvents.SyncIssueKind kind, int count) {
        if (kind == null) return;
        Integer current = syncIssues.get(kind);
        long total = (current == null ? 0L : current) + Math.max(1, count);
        syncIssues.put(kind, (int) Math.min(CustomerEvents.COUNT_MAX, total));
    }

    /** Whether aggregates wait to be materialized. */
    public boolean hasAggregates() { return !errors.isEmpty() || !syncIssues.isEmpty(); }

    /**
     * The counted aggregates as {@code error.occurred} (first-seen order) and {@code tasks.sync_issue} (kind order)
     * events of the current or most recent session, and the counters cleared. With no session they are discarded.
     */
    public List<Stamped> drainAggregates(long nowS) {
        if (!hasAggregates()) return Collections.emptyList();
        ArrayList<Stamped> out = new ArrayList<>();
        if (sessionId != null) {
            for (Map.Entry<String, Integer> entry : errors.entrySet()) {
                int space = entry.getKey().indexOf(' ');
                CustomerEvents.ErrorDomain domain = CustomerEvents.ErrorDomain.valueOf(entry.getKey().substring(0, space));
                add(out, stamp(CustomerEvents.errorOccurred(domain, entry.getKey().substring(space + 1), entry.getValue()), nowS));
            }
            for (Map.Entry<CustomerEvents.SyncIssueKind, Integer> entry : syncIssues.entrySet()) {
                add(out, stamp(CustomerEvents.syncIssue(entry.getKey(), entry.getValue()), nowS));
            }
        }
        errors.clear();
        syncIssues.clear();
        return out;
    }

    /** The next {@code seq} of the session for {@code event}, or null (seq exhausted, above 2 KiB). */
    private Stamped stamp(CustomerEvents.Event event, long nowS) {
        if (sessionId == null || nextSeq > CustomerEvents.MAX_SEQ) return null;
        String id = ids.uuid4();
        String json = event.json(id, nowS, sessionId, nextSeq);
        int bytes = CustomerEvents.utf8Length(json);
        if (bytes > CustomerEvents.MAX_EVENT_BYTES) return null;
        Stamped stamped = new Stamped(id, nowS, sessionId, nextSeq, event, json, bytes);
        nextSeq++;
        return stamped;
    }

    private static void add(List<Stamped> out, Stamped stamped) {
        if (stamped != null) out.add(stamped);
    }
}
