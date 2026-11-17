package org.telegram.messenger.zenlygram.customer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteAbortException;
import android.database.sqlite.SQLiteAccessPermException;
import android.database.sqlite.SQLiteBlobTooBigException;
import android.database.sqlite.SQLiteCantOpenDatabaseException;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteDatatypeMismatchException;
import android.database.sqlite.SQLiteDiskIOException;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteMisuseException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteOutOfMemoryException;
import android.database.sqlite.SQLiteReadOnlyDatabaseException;
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.ApplicationLoader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The statistics queue (design 50 §11.5; contract §6.5): SQLite {@code databases/zenly_customer.db}, version 1, per
 * origin scope ({@code ZenlyPro} storage token) and Telegram user, with the sessions of {@link SessionTracker}.
 * <pre>
 * contexts(context_key PK, json)                      the context JSON events were recorded under, by its SHA-256
 * events(seq PK AUTOINCREMENT, scope, tg, event_id UNIQUE, ts, context_key, json, bytes 2..2048)   oldest first
 * sessions(scope, tg PK, session_id, next_seq 0..1000001, started_at, ended_at)   the most recent session
 * upload_state(scope, tg PK, paused_until, flush_interval_s 60..3600)
 * </pre>
 * Everything runs on one daemon thread, {@code ZenlygramEvents} (the public methods only enqueue; callbacks arrive on
 * the main thread). Every write is one transaction with the session row and the pruning of that (scope, tg): events
 * older than 7 days, then the oldest while there are more than 5000 or more than 2 MiB; contexts no event references
 * are deleted. Each write task first asks its {@link Allowed} gate again, so nothing queued behind a withdrawal, a
 * binding loss or a deletion is ever written, and no batch is handed out while it refuses. Events are disposable
 * (unlike task drafts): a file of an unknown version or a corrupt one is dropped and recreated, and an SQLite failure
 * is counted as {@code error.occurred{storage, sqlite_<n>}}. Consent never lives here. Nothing here logs.
 */
public final class ZenlyEventStore {
    static final String DATABASE = "zenly_customer.db";
    static final int VERSION = 1;
    /** Rows deleted per statement (SQLite allows at most 999 bound arguments). */
    private static final int DELETE_CHUNK = 200;

    private static final ExecutorService EVENTS_DB = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ZenlygramEvents");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ZenlyEventStore shared;

    /** Asked on the store's thread right before a write: false = nothing is written. */
    public interface Allowed { boolean allowed(); }

    /** A result, delivered on the main thread. */
    public interface Callback<T> { void done(T value); }

    /** Main thread: a write left {@code queued} (≥ 50) events of (scope, tg) waiting (a flush trigger, design 50 §11.6). */
    public interface QueueListener { void onQueued(String scope, long telegramUserId, int queued); }

    /** One batch handed out for upload; finish it with {@link #finish}. */
    public static final class Batch {
        public final String scope;
        public final long telegramUserId;
        public final String batchId;
        /** Device clock carried as {@code sent_at}. */
        public final long sentAtS;
        /** Events in the batch. */
        public final int events;
        private final byte[] body;
        final long[] rowIds;
        /** The account's purge generation at hand-out: a purge since makes {@link #finish} a no-op. */
        final int generation;

        Batch(String scope, long telegramUserId, String batchId, long sentAtS, byte[] body, long[] rowIds, int generation) {
            this.scope = scope;
            this.telegramUserId = telegramUserId;
            this.batchId = batchId;
            this.sentAtS = sentAtS;
            this.body = body;
            this.rowIds = rowIds;
            this.events = rowIds.length;
            this.generation = generation;
        }

        /** The envelope (contract §3.1), UTF-8. */
        public byte[] body() { return body.clone(); }
    }

    /** What {@link #prepareBatch} found. */
    public static final class Prepared {
        /** The batch to send, or null: nothing sendable, uploads paused, or the gate refused. */
        public final Batch batch;
        /** Events queued for (scope, tg) before the batch was taken. */
        public final int queued;
        /** Uploads are paused until this epoch second (0 = not paused). */
        public final long pausedUntilS;
        /** The account's {@code flush_interval_s}. */
        public final int flushIntervalS;

        Prepared(Batch batch, int queued, long pausedUntilS, int flushIntervalS) {
            this.batch = batch;
            this.queued = queued;
            this.pausedUntilS = pausedUntilS;
            this.flushIntervalS = flushIntervalS;
        }
    }

    private final Context context;
    private final SessionTracker.Ids ids;
    private volatile QueueListener queueListener;
    // The store's thread only.
    private Helper helper;
    private final HashMap<String, SessionTracker> trackers = new HashMap<>();
    private final HashMap<Long, Integer> generations = new HashMap<>();

    ZenlyEventStore(Context context) { this(context, () -> UUID.randomUUID().toString()); }

    ZenlyEventStore(Context context, SessionTracker.Ids ids) {
        if (context == null || ids == null) throw new IllegalArgumentException("context");
        this.context = context;
        this.ids = ids;
    }

    /** The process's store, or null before the application context exists. */
    public static ZenlyEventStore get() {
        ZenlyEventStore store = shared;
        if (store != null) return store;
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return null;
        synchronized (ZenlyEventStore.class) {
            if (shared == null) shared = new ZenlyEventStore(context);
            return shared;
        }
    }

    public void setQueueListener(QueueListener listener) { queueListener = listener; }

    // ---- Recording ----

    /** Starts the session of (scope, tg) ({@link SessionTracker#start}) and stores its first events. */
    public void startSession(final String scope, final long tg, final CustomerEvents.SessionTrigger trigger, final CustomerEvents.Screen visible,
            final CustomerEvents.Context device, final long nowS, final long elapsedMs, final Allowed allowed) {
        if (trigger == null) return;
        write(scope, tg, device, nowS, allowed, tracker -> tracker.start(trigger, visible, nowS, elapsedMs));
    }

    /** Ends the open session of (scope, tg) ({@link SessionTracker#end}). */
    public void endSession(final String scope, final long tg, final CustomerEvents.SessionEndReason reason, final CustomerEvents.Context device,
            final long nowS, final long elapsedMs, final Allowed allowed) {
        if (reason == null) return;
        write(scope, tg, device, nowS, allowed, tracker -> tracker.end(reason, nowS, elapsedMs));
    }

    /** One event of (scope, tg) under the session rules ({@link SessionTracker#record}). */
    public void record(final String scope, final long tg, final CustomerEvents.Event event, final CustomerEvents.Context device, final long nowS,
            final long elapsedMs, final Allowed allowed) {
        if (event == null) return;
        write(scope, tg, device, nowS, allowed, tracker -> tracker.record(event, nowS, elapsedMs));
    }

    /** Counts one error of the session of (scope, tg) (materialized at session end or before the next batch). */
    public void countError(final String scope, final long tg, final CustomerEvents.ErrorDomain domain, final String code, final Allowed allowed) {
        if (domain == null || !owner(scope, tg)) return;
        enqueue(() -> {
            if (!permitted(allowed)) return;
            tracker(openOrNull(scope, tg), scope, tg).countError(domain, code);
        });
    }

    /** Counts {@code count} Tasks sync issues of {@code kind} for (scope, tg). */
    public void countSyncIssue(final String scope, final long tg, final CustomerEvents.SyncIssueKind kind, final int count, final Allowed allowed) {
        if (kind == null || !owner(scope, tg)) return;
        enqueue(() -> {
            if (!permitted(allowed)) return;
            tracker(openOrNull(scope, tg), scope, tg).countSyncIssue(kind, count);
        });
    }

    /**
     * Withdrawal, decline, rejected grant, binding loss, logout, deletion, remote withdrawal: every queued event,
     * session and upload state of {@code tg} in every scope, its in-memory sessions and aggregates, and any batch handed
     * out before (its {@link #finish} becomes a no-op). When the file cannot be written the whole queue file goes.
     */
    public void deleteAll(final long tg) { deleteAll(tg, null); }

    /** {@link #deleteAll(long)}, then {@code done} on the main thread. */
    public void deleteAll(final long tg, final Runnable done) {
        if (tg <= 0) {
            if (done != null) MAIN.post(done);
            return;
        }
        enqueue(() -> {
            String suffix = ":" + tg;
            for (Iterator<String> keys = trackers.keySet().iterator(); keys.hasNext(); ) {
                if (keys.next().endsWith(suffix)) keys.remove();
            }
            Integer generation = generations.get(tg);
            generations.put(tg, generation == null ? 1 : generation + 1);
            try {
                SQLiteDatabase db = open();
                String[] owner = {Long.toString(tg)};
                db.beginTransaction();
                try {
                    db.delete("events", "tg=?", owner);
                    db.delete("sessions", "tg=?", owner);
                    db.delete("upload_state", "tg=?", owner);
                    collectContexts(db);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (RuntimeException unwritable) {
                // The purge must hold: without a writable file nothing of anyone's queue is kept.
                dropFile();
            }
            if (done != null) MAIN.post(done);
        });
    }

    // ---- Upload ----

    /**
     * The next batch of (scope, tg) at {@code sentAtS} (design 50 §11.6): the session's aggregates are queued first (when
     * {@code allowed}), events outside the {@code sent_at} window are deleted, then {@link EventQueuePolicy.Selector}
     * picks at most {@code limit} events of one context within 256 KiB. Null batch while uploads are paused or when
     * {@code allowed} refuses (its events then stay for the purge that comes with a withdrawal, deletion or binding loss).
     */
    public void prepareBatch(final String scope, final long tg, final int limit, final long sentAtS, final CustomerEvents.Context device,
            final Allowed allowed, final Callback<Prepared> done) {
        if (done == null) return;
        if (!owner(scope, tg)) {
            MAIN.post(() -> done.done(new Prepared(null, 0, 0, EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS)));
            return;
        }
        enqueue(() -> {
            Prepared prepared;
            try {
                prepared = prepare(scope, tg, limit, sentAtS, device, allowed);
            } catch (RuntimeException failure) {
                storageFailure(scope, tg, failure);
                prepared = new Prepared(null, 0, 0, EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS);
            }
            final Prepared result = prepared;
            MAIN.post(() -> done.done(result));
        });
    }

    /**
     * Applies an answer's {@link EventQueuePolicy.Effect} to {@code batch}: deletes exactly its events, stores the pause
     * and {@code flush_interval_s}, counts a dropped batch's error. A no-op after a purge of its account. {@code done}
     * (may be null) gets the events still queued for (scope, tg).
     */
    public void finish(final Batch batch, final EventQueuePolicy.Effect effect, final Callback<Integer> done) {
        if (batch == null || effect == null) return;
        enqueue(() -> {
            int remaining = 0;
            try {
                Integer generation = generations.get(batch.telegramUserId);
                SQLiteDatabase db = open();
                if ((generation == null ? 0 : generation) == batch.generation) {
                    apply(db, batch, effect);
                }
                remaining = count(db, batch.scope, batch.telegramUserId);
            } catch (RuntimeException failure) {
                storageFailure(batch.scope, batch.telegramUserId, failure);
            }
            if (done != null) {
                final int queued = remaining;
                MAIN.post(() -> done.done(queued));
            }
        });
    }

    /** Events queued for (scope, tg). */
    public void queued(final String scope, final long tg, final Callback<Integer> done) {
        if (done == null) return;
        enqueue(() -> {
            int queued = 0;
            try {
                if (owner(scope, tg)) queued = count(open(), scope, tg);
            } catch (RuntimeException failure) {
                storageFailure(scope, tg, failure);
            }
            final int result = queued;
            MAIN.post(() -> done.done(result));
        });
    }

    // ---- The store's thread ----

    private interface Transition { List<SessionTracker.Stamped> apply(SessionTracker tracker); }

    private void write(final String scope, final long tg, final CustomerEvents.Context device, final long nowS, final Allowed allowed,
            final Transition transition) {
        if (!owner(scope, tg) || device == null) return;
        enqueue(() -> {
            if (!permitted(allowed)) return;
            SQLiteDatabase db = openOrNull(scope, tg);
            SessionTracker tracker = tracker(db, scope, tg);
            List<SessionTracker.Stamped> stamped = transition.apply(tracker);
            // Nothing given out (suppressed, deduplicated, no session): the stored row is unchanged too.
            if (db == null || stamped.isEmpty()) return;
            try {
                int queued = store(db, scope, tg, tracker, stamped, device, nowS);
                QueueListener listener = queueListener;
                if (listener != null && !stamped.isEmpty() && queued >= EventQueuePolicy.FLUSH_THRESHOLD) {
                    MAIN.post(() -> listener.onQueued(scope, tg, queued));
                }
            } catch (RuntimeException failure) {
                storageFailure(scope, tg, failure);
            }
        });
    }

    /** Stores {@code stamped} with the session row and prunes, in one transaction; returns the events queued after it. */
    private int store(SQLiteDatabase db, String scope, long tg, SessionTracker tracker, List<SessionTracker.Stamped> stamped,
            CustomerEvents.Context device, long nowS) {
        db.beginTransaction();
        try {
            if (!stamped.isEmpty()) {
                ContentValues values = new ContentValues();
                values.put("context_key", device.key());
                values.put("json", device.json());
                db.insertWithOnConflict("contexts", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                for (SessionTracker.Stamped event : stamped) {
                    values = new ContentValues();
                    values.put("scope", scope);
                    values.put("tg", tg);
                    values.put("event_id", event.eventId);
                    values.put("ts", event.ts);
                    values.put("context_key", device.key());
                    values.put("json", event.json);
                    values.put("bytes", event.bytes);
                    db.insertOrThrow("events", null, values);
                }
            }
            saveSession(db, scope, tg, tracker);
            if (prune(db, scope, tg, nowS)) collectContexts(db);
            int queued = count(db, scope, tg);
            db.setTransactionSuccessful();
            return queued;
        } finally {
            db.endTransaction();
        }
    }

    private Prepared prepare(String scope, long tg, int limit, long sentAtS, CustomerEvents.Context device, Allowed allowed) {
        SQLiteDatabase db = open();
        // Asked once for the whole step: a withdrawal, deletion or binding loss since the flush started hands out no batch.
        boolean recording = permitted(allowed);
        SessionTracker tracker = tracker(db, scope, tg);
        if (tracker.hasAggregates()) {
            // Materialized before the batch is built; discarded when the account may no longer record.
            List<SessionTracker.Stamped> drained = tracker.drainAggregates(sentAtS);
            if (device != null && !drained.isEmpty() && recording) store(db, scope, tg, tracker, drained, device, sentAtS);
        }
        // Every owner's events age out, also those of an origin or account that no longer records anything.
        if (db.delete("events", "ts<?", new String[]{Long.toString(EventQueuePolicy.oldestKept(sentAtS))}) > 0) collectContexts(db);
        String[] owner = {scope, Long.toString(tg)};
        long pausedUntil = 0;
        int interval = EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS;
        try (Cursor cursor = db.rawQuery("SELECT paused_until, flush_interval_s FROM upload_state WHERE scope=? AND tg=?", owner)) {
            if (cursor.moveToFirst()) {
                pausedUntil = cursor.getLong(0);
                interval = EventQueuePolicy.flushInterval(cursor.getLong(1));
            }
        }
        int queued = count(db, scope, tg);
        // The events are left to the purge that comes with a withdrawal, deletion or binding loss.
        if (!recording) return new Prepared(null, queued, 0, interval);
        if (pausedUntil > sentAtS) return new Prepared(null, queued, pausedUntil, interval);
        pausedUntil = 0;

        final HashMap<String, String> contexts = new HashMap<>();
        EventQueuePolicy.Selector selector = new EventQueuePolicy.Selector(limit, sentAtS, key -> {
            String json = contextJson(db, contexts, key);
            return json == null ? -1 : CustomerEvents.utf8Length(json);
        });
        HashMap<Long, String> jsons = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT seq, ts, context_key, bytes, json FROM events WHERE scope=? AND tg=? ORDER BY seq", owner)) {
            while (cursor.moveToNext()) {
                EventQueuePolicy.Row row = new EventQueuePolicy.Row(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getInt(3));
                int before = selector.selected().size();
                if (!selector.offer(row)) break;
                if (selector.selected().size() > before) jsons.put(row.rowId, cursor.getString(4));
            }
        }
        List<EventQueuePolicy.Row> expired = selector.expired();
        if (!expired.isEmpty()) {
            long[] rowIds = new long[expired.size()];
            for (int i = 0; i < rowIds.length; i++) rowIds[i] = expired.get(i).rowId;
            if (deleteRows(db, scope, tg, rowIds) > 0) {
                collectContexts(db);
                queued = count(db, scope, tg);
            }
        }
        List<EventQueuePolicy.Row> selected = selector.selected();
        if (selected.isEmpty()) return new Prepared(null, queued, pausedUntil, interval);
        ArrayList<String> events = new ArrayList<>(selected.size());
        long[] rowIds = new long[selected.size()];
        for (int i = 0; i < rowIds.length; i++) {
            rowIds[i] = selected.get(i).rowId;
            events.add(jsons.get(rowIds[i]));
        }
        String batchId = ids.uuid4();
        String body = CustomerEvents.envelope(batchId, sentAtS, contexts.get(selector.contextKey()), events);
        Integer generation = generations.get(tg);
        Batch batch = new Batch(scope, tg, batchId, sentAtS, body.getBytes(StandardCharsets.UTF_8), rowIds,
                generation == null ? 0 : generation);
        return new Prepared(batch, queued, pausedUntil, interval);
    }

    private void apply(SQLiteDatabase db, Batch batch, EventQueuePolicy.Effect effect) {
        db.beginTransaction();
        try {
            if (effect.deleteSent && deleteRows(db, batch.scope, batch.telegramUserId, batch.rowIds) > 0) collectContexts(db);
            if (effect.pauseUntilS > 0 || effect.flushIntervalS > 0) {
                String[] owner = {batch.scope, Long.toString(batch.telegramUserId)};
                long pausedUntil = 0;
                int interval = EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS;
                try (Cursor cursor = db.rawQuery("SELECT paused_until, flush_interval_s FROM upload_state WHERE scope=? AND tg=?", owner)) {
                    if (cursor.moveToFirst()) {
                        pausedUntil = cursor.getLong(0);
                        interval = EventQueuePolicy.flushInterval(cursor.getLong(1));
                    }
                }
                ContentValues values = new ContentValues();
                values.put("scope", batch.scope);
                values.put("tg", batch.telegramUserId);
                values.put("paused_until", effect.pauseUntilS > 0 ? effect.pauseUntilS : pausedUntil);
                values.put("flush_interval_s", effect.flushIntervalS > 0 ? EventQueuePolicy.flushInterval(effect.flushIntervalS) : interval);
                db.insertWithOnConflict("upload_state", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (effect.errorCode != null) {
            tracker(db, batch.scope, batch.telegramUserId).countError(CustomerEvents.ErrorDomain.EVENTS_UPLOAD, effect.errorCode);
        }
    }

    private static boolean prune(SQLiteDatabase db, String scope, long tg, long nowS) {
        String tgText = Long.toString(tg);
        int deleted = db.delete("events", "scope=? AND tg=? AND ts<?", new String[]{scope, tgText, Long.toString(EventQueuePolicy.oldestKept(nowS))});
        long count = 0, bytes = 0;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM events WHERE scope=? AND tg=?", new String[]{scope, tgText})) {
            if (cursor.moveToFirst()) {
                count = cursor.getLong(0);
                bytes = cursor.getLong(1);
            }
        }
        EventQueuePolicy.Trim trim = new EventQueuePolicy.Trim(count, bytes);
        if (!trim.over()) return deleted > 0;
        long cut = -1;
        try (Cursor cursor = db.rawQuery("SELECT seq, bytes FROM events WHERE scope=? AND tg=? ORDER BY seq", new String[]{scope, tgText})) {
            while (trim.over() && cursor.moveToNext()) {
                cut = cursor.getLong(0);
                trim.drop(cursor.getInt(1));
            }
        }
        if (cut >= 0) deleted += db.delete("events", "scope=? AND tg=? AND seq<=?", new String[]{scope, tgText, Long.toString(cut)});
        return deleted > 0;
    }

    private static int deleteRows(SQLiteDatabase db, String scope, long tg, long[] rowIds) {
        int deleted = 0;
        for (int start = 0; start < rowIds.length; start += DELETE_CHUNK) {
            int end = Math.min(rowIds.length, start + DELETE_CHUNK);
            StringBuilder where = new StringBuilder("scope=? AND tg=? AND seq IN (");
            String[] args = new String[2 + end - start];
            args[0] = scope;
            args[1] = Long.toString(tg);
            for (int i = start; i < end; i++) {
                where.append(i == start ? "?" : ",?");
                args[2 + i - start] = Long.toString(rowIds[i]);
            }
            deleted += db.delete("events", where.append(')').toString(), args);
        }
        return deleted;
    }

    private static void collectContexts(SQLiteDatabase db) {
        db.execSQL("DELETE FROM contexts WHERE context_key NOT IN (SELECT context_key FROM events)");
    }

    private static int count(SQLiteDatabase db, String scope, long tg) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM events WHERE scope=? AND tg=?", new String[]{scope, Long.toString(tg)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private static String contextJson(SQLiteDatabase db, Map<String, String> cache, String key) {
        if (cache.containsKey(key)) return cache.get(key);
        String json = null;
        try (Cursor cursor = db.rawQuery("SELECT json FROM contexts WHERE context_key=?", new String[]{key})) {
            if (cursor.moveToFirst()) json = cursor.getString(0);
        }
        cache.put(key, json);
        return json;
    }

    private static void saveSession(SQLiteDatabase db, String scope, long tg, SessionTracker tracker) {
        if (tracker.sessionId() == null) return;
        ContentValues values = new ContentValues();
        values.put("scope", scope);
        values.put("tg", tg);
        values.put("session_id", tracker.sessionId());
        values.put("next_seq", tracker.nextSeq());
        values.put("started_at", tracker.startedAt());
        if (tracker.endedAt() > 0) {
            values.put("ended_at", tracker.endedAt());
        } else {
            values.putNull("ended_at");
        }
        db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** The session state of (scope, tg): in memory, else its stored row (continued), else none. */
    private SessionTracker tracker(SQLiteDatabase db, String scope, long tg) {
        String key = scope + ":" + tg;
        SessionTracker tracker = trackers.get(key);
        if (tracker != null) return tracker;
        tracker = SessionTracker.fresh(ids);
        if (db != null) {
            try (Cursor cursor = db.rawQuery("SELECT session_id, next_seq, started_at, ended_at FROM sessions WHERE scope=? AND tg=?",
                    new String[]{scope, Long.toString(tg)})) {
                if (cursor.moveToFirst()) {
                    tracker = SessionTracker.resume(ids, cursor.getString(0), cursor.getInt(1), cursor.getLong(2),
                            cursor.isNull(3) ? 0 : cursor.getLong(3));
                }
            } catch (RuntimeException unreadable) {
                // An unreadable row: the account starts without a session.
            }
        }
        trackers.put(key, tracker);
        return tracker;
    }

    private SQLiteDatabase openOrNull(String scope, long tg) {
        try {
            return open();
        } catch (RuntimeException failure) {
            storageFailure(scope, tg, failure);
            return null;
        }
    }

    /** The writable database; a file that cannot be opened is dropped and created again once. */
    private SQLiteDatabase open() {
        if (helper == null) helper = new Helper(context);
        try {
            return helper.getWritableDatabase();
        } catch (RuntimeException unreadable) {
            dropFile();
            helper = new Helper(context);
            return helper.getWritableDatabase();
        }
    }

    private void dropFile() {
        if (helper != null) {
            try {
                helper.close();
            } catch (RuntimeException ignored) {
                // Closing a broken file.
            }
            helper = null;
        }
        try {
            context.deleteDatabase(DATABASE);
        } catch (RuntimeException ignored) {
            // Nothing more can be done for a file that cannot be deleted.
        }
    }

    private void storageFailure(String scope, long tg, RuntimeException failure) {
        if (!owner(scope, tg)) return;
        SessionTracker tracker = trackers.get(scope + ":" + tg);
        if (tracker != null) tracker.countError(CustomerEvents.ErrorDomain.STORAGE, storageCode(failure));
    }

    /** {@code sqlite_<primary result code>} of an SQLite failure when known, else {@code unknown} (contract §3.4). */
    static String storageCode(RuntimeException failure) {
        if (failure instanceof SQLiteFullException) return ErrorCodes.sqlite(13);
        if (failure instanceof SQLiteDiskIOException) return ErrorCodes.sqlite(10);
        if (failure instanceof SQLiteDatabaseCorruptException) return ErrorCodes.sqlite(11);
        if (failure instanceof SQLiteConstraintException) return ErrorCodes.sqlite(19);
        if (failure instanceof SQLiteDatabaseLockedException) return ErrorCodes.sqlite(5);
        if (failure instanceof SQLiteCantOpenDatabaseException) return ErrorCodes.sqlite(14);
        if (failure instanceof SQLiteReadOnlyDatabaseException) return ErrorCodes.sqlite(8);
        if (failure instanceof SQLiteAccessPermException) return ErrorCodes.sqlite(3);
        if (failure instanceof SQLiteOutOfMemoryException) return ErrorCodes.sqlite(7);
        if (failure instanceof SQLiteBlobTooBigException) return ErrorCodes.sqlite(18);
        if (failure instanceof SQLiteDatatypeMismatchException) return ErrorCodes.sqlite(20);
        if (failure instanceof SQLiteMisuseException) return ErrorCodes.sqlite(21);
        if (failure instanceof SQLiteAbortException) return ErrorCodes.sqlite(4);
        return ErrorCodes.UNKNOWN;
    }

    private static boolean owner(String scope, long tg) { return scope != null && !scope.isEmpty() && tg > 0; }

    private static boolean permitted(Allowed allowed) {
        try {
            return allowed == null || allowed.allowed();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    private static void enqueue(Runnable work) {
        try {
            EVENTS_DB.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException ignored) {
                    // Statistics never break the app; the failure was counted where it was known.
                }
            });
        } catch (RuntimeException rejected) {
            // The executor never shuts down; nothing is recorded if it refuses.
        }
    }

    /** Closes the file (tests: the instance is constructed by reflection around an isolated context). */
    void close() {
        try {
            EVENTS_DB.submit(() -> {
                if (helper != null) helper.close();
                helper = null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // The test directory is removed anyway.
        }
    }

    /** Version 1 of the queue file; any other version is dropped and recreated (events are disposable). */
    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            // The default error handler deletes a corrupt file; it is then created again.
            super(context, DATABASE, null, VERSION);
        }

        @Override public void onCreate(SQLiteDatabase db) { create(db); }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { recreate(db); }

        @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) { recreate(db); }

        private static void recreate(SQLiteDatabase db) {
            ArrayList<String[]> objects = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT type, name FROM sqlite_master WHERE type IN ('table','view','trigger') "
                    + "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null)) {
                while (cursor.moveToNext()) objects.add(new String[]{cursor.getString(0), cursor.getString(1)});
            }
            for (String[] object : objects) {
                db.execSQL("DROP " + ("view".equals(object[0]) ? "VIEW" : "trigger".equals(object[0]) ? "TRIGGER" : "TABLE")
                        + " IF EXISTS \"" + object[1].replace("\"", "\"\"") + "\"");
            }
            create(db);
        }

        private static void create(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE contexts (context_key TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL)");
            db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, tg INTEGER NOT NULL CHECK(tg > 0), "
                    + "event_id TEXT NOT NULL UNIQUE, ts INTEGER NOT NULL, context_key TEXT NOT NULL, json TEXT NOT NULL, "
                    + "bytes INTEGER NOT NULL CHECK(bytes BETWEEN 2 AND 2048))");
            db.execSQL("CREATE INDEX events_owner ON events(scope, tg, seq)");
            db.execSQL("CREATE TABLE sessions (scope TEXT NOT NULL, tg INTEGER NOT NULL, session_id TEXT NOT NULL, "
                    + "next_seq INTEGER NOT NULL CHECK(next_seq BETWEEN 0 AND 1000001), started_at INTEGER NOT NULL, ended_at INTEGER, "
                    + "PRIMARY KEY(scope, tg))");
            db.execSQL("CREATE TABLE upload_state (scope TEXT NOT NULL, tg INTEGER NOT NULL, paused_until INTEGER NOT NULL DEFAULT 0, "
                    + "flush_interval_s INTEGER NOT NULL DEFAULT 300 CHECK(flush_interval_s BETWEEN 60 AND 3600), PRIMARY KEY(scope, tg))");
        }
    }
}
