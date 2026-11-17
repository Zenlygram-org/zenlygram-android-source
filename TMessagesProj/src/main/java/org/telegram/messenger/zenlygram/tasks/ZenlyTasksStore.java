package org.telegram.messenger.zenlygram.tasks;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel.*;

/** Local-only SQLite storage, isolated by Telegram user ID rather than reusable account slot. */
public final class ZenlyTasksStore {
    public interface Callback<T> {
        void complete(T value, Failure failure);
    }

    /**
     * MIGRATING: the account's migration step is copying the board (transient; the UI keeps the input and shows
     * ZenlyTasksMigrationBusy). ARCHIVED: the board is a read-only archive of its cloud copy (design 30 §7.5).
     */
    public enum Failure { STORAGE, INVALID, ACCOUNT_CHANGED, PRO_REQUIRED, NOT_FOUND, MIGRATING, ARCHIVED }

    /** Per-account write state of the checked local writes; ZenlyTasksSyncOwner installs it (default OPEN). */
    public interface WriteFence { WriteState state(int account); }

    private static final WriteFence OPEN_FENCE = account -> WriteState.OPEN;
    private static volatile WriteFence writeFence = OPEN_FENCE;

    /**
     * Whether a NEW task may be written on the device board. The app installs its own create gate (ZgPro: this board
     * is one per install, so any signed-in account's grant allows it); until then, and if that gate throws, the
     * account's own signed decision answers. Any thread.
     */
    public interface CreateGate { boolean allows(int account); }

    private static volatile CreateGate createGate;

    public static void setCreateGate(CreateGate gate) { createGate = gate; }

    private static boolean mayCreate(int account) {
        CreateGate gate = createGate;
        if (gate != null) {
            try { return gate.allows(account); }
            catch (RuntimeException ignored) { /* the signed decision below answers */ }
        }
        return ZenlyPro.get(account).canCreate(ZenlyPro.Capability.TASKS);
    }
    /** Blocking provenance calls from the cloud executor wait at most this long (design 30 §6.4). */
    private static final long PROVENANCE_WAIT_MS = 2_000;

    private interface Work<T> { T run(); }
    private static final HashMap<Long, ZenlyTasksStore> instances = new HashMap<>();
    private static final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ZenlyTasks");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final long userId;
    private final Database database;

    public static synchronized ZenlyTasksStore get(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId == 0) throw new IllegalStateException("Account is not signed in");
        ZenlyTasksStore store = instances.get(userId);
        if (store == null) {
            store = new ZenlyTasksStore(userId);
            instances.put(userId, store);
        }
        return store;
    }

    private ZenlyTasksStore(long userId) {
        this.userId = userId;
        database = new Database(ApplicationLoader.applicationContext, userId);
    }

    /** Any thread. Returns the previous fence; {@code null} restores the default (every account OPEN). */
    public static WriteFence setWriteFence(WriteFence fence) {
        WriteFence previous = writeFence;
        writeFence = fence == null ? OPEN_FENCE : fence;
        return previous;
    }

    /** Store queue, before any checked local write: the account's fence decides; a throwing fence fails closed. */
    private static void requireWritable(int account) {
        WriteState state;
        try {
            state = writeFence.state(account);
        } catch (RuntimeException e) {
            state = WriteState.MIGRATING;
        }
        if (state == WriteState.MIGRATING) throw new StoreException(Failure.MIGRATING);
        if (state == WriteState.ARCHIVED) throw new StoreException(Failure.ARCHIVED);
    }

    private <T> void execute(int account, Work<T> work, Callback<T> callback) {
        execute(account, false, work, callback);
    }

    /** {@code checked}: a user's local write, refused by the write fence before the database is touched. */
    private <T> void execute(int account, boolean checked, Work<T> work, Callback<T> callback) {
        queue.execute(() -> {
            T value = null;
            Failure failure = null;
            try {
                if (UserConfig.getInstance(account).getClientUserId() != userId) {
                    failure = Failure.ACCOUNT_CHANGED;
                } else {
                    if (checked) requireWritable(account);
                    value = work.run();
                }
            } catch (StoreException e) {
                failure = e.failure;
            } catch (IllegalArgumentException e) {
                failure = Failure.INVALID;
            } catch (RuntimeException e) {
                // Do not log task titles, descriptions, SQL values or account identifiers.
                failure = Failure.STORAGE;
            }
            if (callback == null) return;
            T result = value;
            Failure error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (UserConfig.getInstance(account).getClientUserId() != userId) {
                    callback.complete(null, Failure.ACCOUNT_CHANGED);
                } else {
                    callback.complete(result, error);
                }
            });
        });
    }

    public void load(int account, Callback<Snapshot> callback) {
        execute(account, () -> {
            SQLiteDatabase db = database.getReadableDatabase();
            ArrayList<Folder> folders = new ArrayList<>();
            ArrayList<Task> tasks = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT id,title,created_at FROM folders ORDER BY CASE WHEN id='inbox' THEN 0 ELSE 1 END,created_at,id", null)) {
                while (cursor.moveToNext()) folders.add(new Folder(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
            }
            try (Cursor cursor = db.rawQuery("SELECT id,folder_id,title,description,due_at,priority,completed,completed_at,created_at,updated_at FROM tasks", null)) {
                while (cursor.moveToNext()) {
                    tasks.add(new Task(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                            cursor.getString(3), cursor.isNull(4) ? null : cursor.getLong(4), cursor.getInt(5),
                            cursor.getInt(6) != 0, cursor.isNull(7) ? null : cursor.getLong(7),
                            cursor.getLong(8), cursor.getLong(9)));
                }
            }
            return new Snapshot(folders, tasks);
        }, callback);
    }

    public void saveFolder(int account, String id, String name, Callback<Void> callback) {
        execute(account, true, () -> {
            String title = title(name, MAX_FOLDER_TITLE);
            if (INBOX_ID.equals(id)) throw new IllegalArgumentException("Inbox is fixed");
            SQLiteDatabase db = database.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("title", title);
            if (id == null) {
                values.put("id", UUID.randomUUID().toString());
                values.put("created_at", System.currentTimeMillis());
                db.insertOrThrow("folders", null, values);
            } else if (db.update("folders", values, "id=?", new String[]{id}) != 1) {
                throw new StoreException(Failure.NOT_FOUND);
            }
            return null;
        }, callback);
    }

    /** Explicit folder deletion removes its tasks atomically through the foreign-key cascade. */
    public void deleteFolder(int account, String id, Callback<Void> callback) {
        execute(account, true, () -> {
            if (INBOX_ID.equals(id) || id == null) throw new IllegalArgumentException("Inbox is fixed");
            if (database.getWritableDatabase().delete("folders", "id=?", new String[]{id}) != 1) {
                throw new StoreException(Failure.NOT_FOUND);
            }
            return null;
        }, callback);
    }

    public void saveTask(int account, String id, String folderId, String name, String notes,
                         Long dueAt, int priority, boolean completed, Callback<Void> callback) {
        execute(account, true, () -> {
            validateTask(name, notes, dueAt, priority);
            if (folderId == null) throw new IllegalArgumentException("Folder required");
            if (id == null && !mayCreate(account)) {
                throw new StoreException(Failure.PRO_REQUIRED);
            }
            SQLiteDatabase db = database.getWritableDatabase();
            long now = System.currentTimeMillis();
            db.beginTransaction();
            try {
                Long completedAt = completed ? now : null;
                if (id != null) {
                    try (Cursor old = db.rawQuery("SELECT completed,completed_at FROM tasks WHERE id=?", new String[]{id})) {
                        if (!old.moveToFirst()) throw new StoreException(Failure.NOT_FOUND);
                        if (completed && old.getInt(0) != 0 && !old.isNull(1)) completedAt = old.getLong(1);
                    }
                }
                try (Cursor folder = db.rawQuery("SELECT id FROM folders WHERE id=?", new String[]{folderId})) {
                    if (!folder.moveToFirst()) throw new StoreException(Failure.NOT_FOUND);
                }
                ContentValues values = new ContentValues();
                values.put("folder_id", folderId);
                values.put("title", title(name, MAX_TITLE));
                values.put("description", description(notes));
                values.put("due_at", dueAt);
                values.put("priority", priority);
                values.put("completed", completed ? 1 : 0);
                values.put("completed_at", completedAt);
                values.put("updated_at", now);
                if (id == null) {
                    values.put("id", UUID.randomUUID().toString());
                    values.put("created_at", now);
                    db.insertOrThrow("tasks", null, values);
                } else if (db.update("tasks", values, "id=?", new String[]{id}) != 1) {
                    throw new StoreException(Failure.NOT_FOUND);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return null;
        }, callback);
    }

    public void setCompleted(int account, String id, boolean completed, Callback<Void> callback) {
        execute(account, true, () -> {
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("completed", completed ? 1 : 0);
            values.put("completed_at", completed ? Long.valueOf(now) : null);
            values.put("updated_at", now);
            if (database.getWritableDatabase().update("tasks", values, "id=? AND completed<>?",
                    new String[]{id, completed ? "1" : "0"}) == 0) {
                try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT id FROM tasks WHERE id=?", new String[]{id})) {
                    if (!cursor.moveToFirst()) throw new StoreException(Failure.NOT_FOUND);
                }
            }
            return null;
        }, callback);
    }

    public void deleteTask(int account, String id, Callback<Void> callback) {
        execute(account, true, () -> {
            if (database.getWritableDatabase().delete("tasks", "id=?", new String[]{id}) != 1) {
                throw new StoreException(Failure.NOT_FOUND);
            }
            return null;
        }, callback);
    }

    /**
     * Immutable consent snapshot for explicit cloud import. Reads only; original rows are never changed. {@code scope}
     * is ProOrigin.importScope(storageToken, principal), "o-&lt;token&gt;/&lt;principal&gt;", stored in the
     * cloud_import_map.principal column: mappings of another origin, and bare-principal rows (pre-origin, v1), never match.
     */
    public void importSnapshot(int account, String scope, Callback<ZenlyTaskImport.Snapshot> callback) {
        execute(account, () -> {
            if (scope == null || scope.isEmpty()) throw new IllegalArgumentException("scope");
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                ArrayList<Folder> folders = new ArrayList<>();
                LinkedHashMap<String, String> incarnations = new LinkedHashMap<>();
                ArrayList<Task> tasks = new ArrayList<>();
                ArrayList<ZenlyTaskImport.Mapping> mappings = new ArrayList<>();
                try (Cursor cursor = db.rawQuery("SELECT f.id,f.title,f.created_at,i.incarnation FROM folders f JOIN folder_incarnations i ON i.folder_id=f.id ORDER BY CASE WHEN f.id='inbox' THEN 0 ELSE 1 END,f.created_at,f.id", null)) {
                    while (cursor.moveToNext()) {
                        folders.add(new Folder(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
                        incarnations.put(cursor.getString(0), cursor.getString(3));
                    }
                }
                try (Cursor cursor = db.rawQuery("SELECT count(*) FROM folders", null)) {
                    // Every folder must have an incarnation (migration/trigger invariant); never guess one.
                    if (!cursor.moveToFirst() || cursor.getInt(0) != folders.size()) throw new StoreException(Failure.STORAGE);
                }
                try (Cursor cursor = db.rawQuery("SELECT id,folder_id,title,description,due_at,priority,completed,completed_at,created_at,updated_at FROM tasks ORDER BY folder_id,created_at,id", null)) {
                    while (cursor.moveToNext()) {
                        tasks.add(new Task(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                                cursor.getString(3), cursor.isNull(4) ? null : cursor.getLong(4), cursor.getInt(5),
                                cursor.getInt(6) != 0, cursor.isNull(7) ? null : cursor.getLong(7),
                                cursor.getLong(8), cursor.getLong(9)));
                    }
                }
                try (Cursor cursor = db.rawQuery("SELECT m.entity,m.local_key,m.cloud_id,m.mutation_id,m.sort_order,m.parent_cloud_id,a.mutation_id IS NOT NULL,coalesce(a.acknowledged,0),coalesce(a.verified_generation,0) FROM cloud_import_map m LEFT JOIN cloud_import_attempt a ON a.mutation_id=m.mutation_id WHERE m.principal=?", new String[]{scope})) {
                    while (cursor.moveToNext()) {
                        mappings.add(new ZenlyTaskImport.Mapping(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                                cursor.getString(3), cursor.getInt(4), cursor.isNull(5) ? null : cursor.getString(5),
                                cursor.getInt(6) != 0, cursor.getInt(7) != 0, cursor.getLong(8)));
                    }
                }
                db.setTransactionSuccessful();
                return new ZenlyTaskImport.Snapshot(folders, incarnations, tasks, mappings);
            } finally {
                db.endTransaction();
            }
        }, callback);
    }

    /**
     * Durable before any cloud enqueue; identical rows are idempotent, a different row for the same key is refused.
     * {@code scope} as for {@link #importSnapshot}.
     */
    public void recordImportMappings(int account, String scope, List<ZenlyTaskImport.Mapping> mappings, Callback<Void> callback) {
        List<ZenlyTaskImport.Mapping> rows = Collections.unmodifiableList(new ArrayList<>(mappings));
        execute(account, () -> {
            if (scope == null || scope.isEmpty()) throw new IllegalArgumentException("scope");
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                for (ZenlyTaskImport.Mapping row : rows) {
                    ZenlyTaskImport.Mapping existing = null;
                    try (Cursor cursor = db.rawQuery("SELECT entity,local_key,cloud_id,mutation_id,sort_order,parent_cloud_id FROM cloud_import_map WHERE principal=? AND entity=? AND local_key=?",
                            new String[]{scope, row.entity, row.localKey})) {
                        if (cursor.moveToFirst()) {
                            existing = new ZenlyTaskImport.Mapping(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                                    cursor.getString(3), cursor.getInt(4), cursor.isNull(5) ? null : cursor.getString(5));
                        }
                    }
                    if (existing != null) {
                        if (!existing.sameAs(row)) throw new StoreException(Failure.INVALID);
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("principal", scope);
                    values.put("entity", row.entity);
                    values.put("local_key", row.localKey);
                    values.put("cloud_id", row.cloudId);
                    values.put("mutation_id", row.mutationId);
                    values.put("sort_order", row.sortOrder);
                    values.put("parent_cloud_id", row.parentCloudId);
                    db.insertOrThrow("cloud_import_map", null, values);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return null;
        }, callback);
    }

    // ---- Tasks R4: import provenance, migration record, release (design 30 §6.4-6.5) ----

    /**
     * Blocking, on the cloud executor only, right before the first send of these creates: their attempt rows (content
     * free; ids without an import mapping are no-ops). False = not recorded within 2 s (or another user holds the
     * slot): the creates are not sent yet.
     */
    public static boolean recordImportAttempts(int account, long rawTelegramId, List<String> mutationIds) {
        return provenance(account, rawTelegramId, mutationIds, false);
    }

    public static boolean recordImportAttempt(int account, long rawTelegramId, String mutationId) {
        return recordImportAttempts(account, rawTelegramId, Collections.singletonList(mutationId));
    }

    /** Blocking, on the cloud executor only, after their receipts: {@code acknowledged = 1}. False = retry later. */
    public static boolean acknowledgeImport(int account, long rawTelegramId, List<String> mutationIds) {
        return provenance(account, rawTelegramId, mutationIds, true);
    }

    private static boolean provenance(int account, long rawTelegramId, List<String> mutationIds, boolean acknowledged) {
        if (mutationIds == null || mutationIds.isEmpty()) return true;
        final ZenlyTasksStore store = existing(account, rawTelegramId);
        if (store == null) {
            // No local database: no import mapping can exist, so nothing to record (never create the file here).
            return rawTelegramId > 0 && UserConfig.getInstance(account).getClientUserId() == rawTelegramId;
        }
        final ArrayList<String> ids = new ArrayList<>(mutationIds);
        Future<Boolean> future = queue.submit(() -> {
            if (UserConfig.getInstance(account).getClientUserId() != store.userId) return false;
            SQLiteDatabase db = store.database.getWritableDatabase();
            db.beginTransaction();
            try {
                for (String id : ids) {
                    db.execSQL("INSERT OR IGNORE INTO cloud_import_attempt(mutation_id) SELECT mutation_id FROM cloud_import_map WHERE mutation_id=?", new Object[]{id});
                    if (acknowledged) db.execSQL("UPDATE cloud_import_attempt SET acknowledged=1 WHERE mutation_id=?", new Object[]{id});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        });
        try {
            return Boolean.TRUE.equals(future.get(PROVENANCE_WAIT_MS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // Never log ids or SQL values; the caller retries later.
            return false;
        }
    }

    /** The open store of this user, or null when its database file does not exist (nothing to read or release). */
    private static ZenlyTasksStore existing(int account, long rawTelegramId) {
        if (rawTelegramId <= 0) return null;
        synchronized (ZenlyTasksStore.class) {
            ZenlyTasksStore store = instances.get(rawTelegramId);
            if (store != null) return store;
            Context context = ApplicationLoader.applicationContext;
            if (context == null || !context.getDatabasePath(Database.name(rawTelegramId)).exists()) return null;
            store = new ZenlyTasksStore(rawTelegramId);
            instances.put(rawTelegramId, store);
            return store;
        }
    }

    /** The account's migration record (null = none). */
    public void migrationRecord(int account, Callback<ZenlyTaskImport.MigrationRecord> callback) {
        execute(account, () -> readMigration(database.getReadableDatabase()), callback);
    }

    /**
     * Compare-and-set of the record: written (true) only while the stored one equals {@code expected} (null = none);
     * {@code record} null deletes it. A withdrawal or an erasure that changed it meanwhile wins (false).
     */
    public void setMigrationRecord(int account, ZenlyTaskImport.MigrationRecord record, ZenlyTaskImport.MigrationRecord expected,
                                   Callback<Boolean> callback) {
        execute(account, () -> {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                ZenlyTaskImport.MigrationRecord current = readMigration(db);
                if (current == null ? expected != null : !current.equals(expected)) return false;
                writeMigration(db, record);
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }, callback);
    }

    /** Any mapping of any scope, bare pre-origin (v1) principals included: the account copied rows explicitly. */
    public void hasAnyImportMapping(int account, Callback<Boolean> callback) {
        execute(account, () -> {
            try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT 1 FROM cloud_import_map LIMIT 1", null)) {
                return cursor.moveToFirst();
            }
        }, callback);
    }

    /** Live tasks and non-inbox folders without a mapping of {@code scope}: the cut-over's U. */
    public void unmappedLiveRowCount(int account, String scope, Callback<Integer> callback) {
        execute(account, () -> {
            if (scope == null || scope.isEmpty()) throw new IllegalArgumentException("scope");
            try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT (SELECT count(*) FROM tasks t WHERE NOT EXISTS (SELECT 1 FROM cloud_import_map m WHERE m.principal=? AND m.entity='task' AND m.local_key=t.id))+(SELECT count(*) FROM folders f JOIN folder_incarnations i ON i.folder_id=f.id WHERE f.id<>'inbox' AND NOT EXISTS (SELECT 1 FROM cloud_import_map m WHERE m.principal=? AND m.entity='folder' AND m.local_key=i.incarnation))",
                    new String[]{scope, scope})) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        }, callback);
    }

    /**
     * Resend-once (design 30 §6.5): these acknowledged creates are absent after a resync at {@code generation}; they
     * become attempted-unacknowledged at that generation, so the next plan RESENDs each once with its reserved id.
     */
    public void markImportReverified(int account, List<String> mutationIds, long generation, Callback<Void> callback) {
        List<String> ids = Collections.unmodifiableList(new ArrayList<>(mutationIds));
        execute(account, () -> {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                for (String id : ids) {
                    db.execSQL("UPDATE cloud_import_attempt SET verified_generation=?,acknowledged=0 WHERE mutation_id=?", new Object[]{generation, id});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return null;
        }, callback);
    }

    /**
     * A cloud store the migration step had not checked yet (new, or recreated after a logout or a launch cleanup; its
     * resync generation starts at 0 again): every verification recorded against an earlier store is void. The record and
     * every attempt row go to verified generation -1, older than any generation of a store, so a completed copy is
     * verified again and an acknowledged row absent from the new store is resent once (design 30 §3.8). State, mappings
     * and acknowledgements stay. (Kept after markImportReverified: run_tests.py reads that one's UPDATE as the first.)
     */
    public void invalidateMigrationVerification(int account, Callback<Void> callback) {
        execute(account, () -> {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("UPDATE cloud_migration SET verified_generation=-1");
                db.execSQL("UPDATE cloud_import_attempt SET verified_generation=-1");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return null;
        }, callback);
    }

    /**
     * Consent withdrawn ({@code withdrawn}) or the backend account erased ({@code erased}): every import mapping of
     * this user goes (attempt rows cascade) and the record becomes {@code released}, so a re-grant claims afresh. Any
     * thread; asynchronous on the store queue; keyed by the raw Telegram id because the slot may already be empty. A
     * user without a local database has nothing to release.
     */
    public static void releaseMigration(int account, long rawTelegramId, String reason) {
        final ZenlyTasksStore store = existing(account, rawTelegramId);
        if (store == null) return;
        queue.execute(() -> {
            try {
                SQLiteDatabase db = store.database.getWritableDatabase();
                db.beginTransaction();
                try {
                    db.execSQL("DELETE FROM cloud_import_map");
                    ZenlyTaskImport.MigrationRecord current = readMigration(db);
                    if (current != null) writeMigration(db, current.withState(ZenlyTaskImport.MigrationRecord.RELEASED, reason));
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (RuntimeException e) {
                FileLog.e("Zenlygram Tasks migration release failed");
            }
        });
    }

    /** After an erasure, behind {@link #releaseMigration}: no record at all, so a later link claims afresh. Any thread. */
    public static void clearMigrationRecord(int account, long rawTelegramId) {
        final ZenlyTasksStore store = existing(account, rawTelegramId);
        if (store == null) return;
        queue.execute(() -> {
            try {
                store.database.getWritableDatabase().execSQL("DELETE FROM cloud_migration");
            } catch (RuntimeException e) {
                FileLog.e("Zenlygram Tasks migration record clear failed");
            }
        });
    }

    // ---- ZENLYGRAM 13.2.1: two writes of the app's Tasks area (org.telegram.zenlygram.tasks) that no call above offers ----

    /** ZENLYGRAM 13.2.1: the rows of {@link #addRows}, read on the store queue. Null = nothing to add. */
    public interface RowSource { Snapshot rows(); }

    /**
     * ZENLYGRAM 13.2.1: the one-time import of the app's old device-wide board (zg_tasks.db) needs rows that keep their
     * own ids and times; saveFolder / saveTask always mint both. Adds the rows this store does not hold yet (by id) in
     * one transaction, the source read on the store queue first, so every later call sees them. Not a checked write: an
     * import's rows belong in the store whatever its fence says (in an archived board they are its new rows). The fixed
     * inbox is never written. A row the tables refuse is left out and counted. Result: {added, refused}.
     */
    public void addRows(int account, RowSource source, Callback<int[]> callback) {
        execute(account, () -> {
            Snapshot rows = source.rows();
            int added = 0, refused = 0;
            if (rows == null) return new int[]{0, 0};
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                for (Folder folder : rows.folders) {
                    if (folder.id == null || INBOX_ID.equals(folder.id)) continue;
                    try (Cursor held = db.rawQuery("SELECT 1 FROM folders WHERE id=?", new String[]{folder.id})) {
                        if (held.moveToFirst()) continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("id", folder.id);
                    values.put("title", folder.title);
                    values.put("created_at", folder.createdAt);
                    // insertOrThrow, never insert(): that one logs the refused values.
                    try {
                        db.insertOrThrow("folders", null, values);
                        added++;
                    } catch (android.database.sqlite.SQLiteConstraintException e) {
                        refused++;
                    }
                }
                for (Task task : rows.tasks) {
                    if (task.id == null) continue;
                    try (Cursor held = db.rawQuery("SELECT 1 FROM tasks WHERE id=?", new String[]{task.id})) {
                        if (held.moveToFirst()) continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put("id", task.id);
                    values.put("folder_id", task.folderId);
                    values.put("title", task.title);
                    values.put("description", task.description == null ? "" : task.description);
                    values.put("due_at", task.dueAt);
                    values.put("priority", task.priority);
                    values.put("completed", task.completed ? 1 : 0);
                    values.put("completed_at", task.completedAt);
                    values.put("created_at", task.createdAt);
                    values.put("updated_at", task.updatedAt);
                    try {
                        db.insertOrThrow("tasks", null, values);
                        added++;
                    } catch (android.database.sqlite.SQLiteConstraintException e) {
                        refused++;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return new int[]{added, refused};
        }, callback);
    }

    /**
     * ZENLYGRAM 13.2.1: the last account logged out, and iOS then wipes Tasks entirely (SGAccountCleanup); no call above
     * removes a user's store, and deleting its file under the open helper would leave the old rows readable in this
     * process. On the store queue, behind everything queued before: every open store is closed and forgotten, and the
     * device task database of every user is deleted (never a cloud database: those carry an origin in their name). Any
     * thread; {@code done} (may be null) runs on the main thread.
     */
    public static void deleteAllStores(Runnable done) {
        queue.execute(() -> {
            try {
                synchronized (ZenlyTasksStore.class) {
                    for (ZenlyTasksStore store : instances.values()) {
                        try {
                            store.database.close();
                        } catch (RuntimeException ignored) {
                            // The file is deleted next.
                        }
                    }
                    instances.clear();
                    Context context = ApplicationLoader.applicationContext;
                    String[] names = context == null ? null : context.databaseList();
                    if (names != null) {
                        for (String name : names) {
                            if (name.matches("zenly_tasks_[0-9]+\\.db")) context.deleteDatabase(name);
                        }
                    }
                }
            } catch (RuntimeException e) {
                FileLog.e("Zenlygram Tasks device stores could not be removed");
            }
            if (done != null) AndroidUtilities.runOnUIThread(done);
        });
    }

    private static ZenlyTaskImport.MigrationRecord readMigration(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT state,reason,scope,normalization_locale,claimed_at,enqueued_at,completed_at,verified_generation,lists,tasks,kept_local FROM cloud_migration WHERE singleton=1", null)) {
            if (!c.moveToFirst()) return null;
            return new ZenlyTaskImport.MigrationRecord(c.getString(0), c.isNull(1) ? null : c.getString(1), c.isNull(2) ? null : c.getString(2),
                    c.getString(3), c.getLong(4), c.isNull(5) ? null : c.getLong(5), c.isNull(6) ? null : c.getLong(6), c.getLong(7),
                    c.getInt(8), c.getInt(9), c.getInt(10));
        }
    }

    private static void writeMigration(SQLiteDatabase db, ZenlyTaskImport.MigrationRecord record) {
        if (record == null) {
            db.execSQL("DELETE FROM cloud_migration");
            return;
        }
        db.execSQL("INSERT OR REPLACE INTO cloud_migration(singleton,state,reason,scope,normalization_locale,claimed_at,enqueued_at,completed_at,verified_generation,lists,tasks,kept_local) VALUES(1,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{record.state, record.reason, record.scope, record.locale, record.claimedAt, record.enqueuedAt,
                        record.completedAt, record.verifiedGeneration, record.lists, record.tasks, record.keptLocal});
    }

    private static final class StoreException extends RuntimeException {
        final Failure failure;
        StoreException(Failure failure) { this.failure = failure; }
    }

    private static final class Database extends SQLiteOpenHelper {
        static final int VERSION = 3;

        /**
         * Version 3 (Tasks R4): local import provenance (resend-once) and the account's migration record. Additive; kept
         * out of literal execSQL calls so the v1/v2 SQL fixtures (cloud/state/sql_fixture_tests.py) keep reading exactly
         * the v1 schema and the v2 migration; run_tests.py executes this list.
         */
        static final String[] SCHEMA_V3 = {
                "CREATE TABLE cloud_import_attempt (mutation_id TEXT PRIMARY KEY NOT NULL REFERENCES cloud_import_map(mutation_id) ON DELETE CASCADE,acknowledged INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged IN (0,1)),verified_generation INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE cloud_migration (singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1),state TEXT NOT NULL CHECK(state IN ('claimed','enqueued','completed','released','skipped')),reason TEXT,scope TEXT,normalization_locale TEXT NOT NULL CHECK(normalization_locale IN ('uz','ru','en')),claimed_at INTEGER NOT NULL,enqueued_at INTEGER,completed_at INTEGER,verified_generation INTEGER NOT NULL DEFAULT 0,lists INTEGER NOT NULL DEFAULT 0,tasks INTEGER NOT NULL DEFAULT 0,kept_local INTEGER NOT NULL DEFAULT 0)",
        };

        static String name(long userId) {
            return "zenly_tasks_" + userId + ".db";
        }

        Database(Context context, long userId) {
            super(context, name(userId), null, VERSION, db -> {
                // Preserve local-only tasks; the framework error reaches the STORAGE failure path.
                FileLog.e("Zenlygram Tasks database corruption detected; automatic reset refused");
            });
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,created_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL,folder_id TEXT NOT NULL REFERENCES folders(id) ON DELETE CASCADE,title TEXT NOT NULL CHECK(length(trim(title)) BETWEEN 1 AND 200),description TEXT NOT NULL DEFAULT '' CHECK(length(description)<=10000),due_at INTEGER,priority INTEGER NOT NULL DEFAULT 0 CHECK(priority BETWEEN 0 AND 4),completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN (0,1)),completed_at INTEGER,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,CHECK(due_at IS NULL OR due_at>0),CHECK((completed=0 AND completed_at IS NULL) OR (completed=1 AND completed_at IS NOT NULL)))");
            db.execSQL("CREATE INDEX tasks_folder ON tasks(folder_id)");
            db.execSQL("CREATE INDEX tasks_due ON tasks(completed,due_at)");
            db.execSQL("INSERT INTO folders(id,title,created_at) VALUES('inbox','',0)");
            createCloudImportTables(db);
            createV3Tables(db);
        }

        /**
         * 1 -> 3 (the v2 tables, then v3) and 2 -> 3 are additive: originals are untouched; runs inside the helper's
         * user_version transaction, so an interrupted upgrade leaves the old version intact. Anything else is refused.
         */
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (newVersion != VERSION || oldVersion != 1 && oldVersion != 2) {
                throw new IllegalStateException("Unsupported task database migration");
            }
            if (oldVersion == 1) createCloudImportTables(db);
            createV3Tables(db);
        }

        private static void createV3Tables(SQLiteDatabase db) {
            for (String statement : SCHEMA_V3) db.execSQL(statement);
        }

        /**
         * Local-only metadata for explicit cloud import: a persistent incarnation per actual folder INSERT (backfilled for
         * existing folders; title/order UPDATEs keep it) and the content-free import mapping. Its principal column holds
         * the origin-scoped key "o-&lt;token&gt;/&lt;principal&gt;" (ProOrigin.importScope); older bare-principal rows are
         * pre-origin and never match one, yet still count as "a mapping exists" for a check over the whole table.
         */
        private static void createCloudImportTables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE folder_incarnations (folder_id TEXT PRIMARY KEY NOT NULL REFERENCES folders(id) ON DELETE CASCADE,incarnation TEXT NOT NULL UNIQUE)");
            db.execSQL("CREATE TRIGGER folder_incarnation_mint AFTER INSERT ON folders BEGIN INSERT OR REPLACE INTO folder_incarnations(folder_id,incarnation) VALUES(NEW.id,lower(hex(randomblob(4)))||'-'||lower(hex(randomblob(2)))||'-4'||substr(lower(hex(randomblob(2))),2)||'-'||substr('89ab',1+(random()&3),1)||substr(lower(hex(randomblob(2))),2)||'-'||lower(hex(randomblob(6)))); END");
            db.execSQL("INSERT INTO folder_incarnations(folder_id,incarnation) SELECT id,lower(hex(randomblob(4)))||'-'||lower(hex(randomblob(2)))||'-4'||substr(lower(hex(randomblob(2))),2)||'-'||substr('89ab',1+(random()&3),1)||substr(lower(hex(randomblob(2))),2)||'-'||lower(hex(randomblob(6))) FROM folders WHERE id NOT IN (SELECT folder_id FROM folder_incarnations)");
            db.execSQL("CREATE TABLE cloud_import_map (principal TEXT NOT NULL,entity TEXT NOT NULL CHECK(entity IN ('folder','task')),local_key TEXT NOT NULL,cloud_id TEXT NOT NULL,mutation_id TEXT NOT NULL UNIQUE,sort_order INTEGER NOT NULL,parent_cloud_id TEXT,PRIMARY KEY(principal,entity,local_key),UNIQUE(principal,cloud_id))");
        }
    }
}
