/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SQL of the retained history. Storage queue only.
 *
 * <p>The tables live in a database of their own per account, {@code zg_history.db} next to the account's cache4.db,
 * attached to Telegram's connection (MessagesStorage.getDatabase()) as schema {@code zg}. Telegram's schema is never
 * changed, and Telegram's database recovery (DatabaseMigrationHelper.recoverDatabase copies only Telegram's tables and
 * each chat's last messages into a new file) keeps every stamp: kept copies that survive it keep their 🗑 mark and
 * expiry, and the stamps of the others are dropped as orphans by the sweeper. On the same connection the joins with
 * messages_v2 and the writes made inside Telegram's transactions (putMessages) keep working.
 *
 * <ul>
 *   <li>{@code zg.zg_meta(name, value)}: our own schema version ("history_schema" = 1).</li>
 *   <li>{@code zg.zg_kept_deleted(uid, mid, deleted_at, is_channel)}: a kept copy. The message itself simply stays in
 *       messages_v2 / messages_topics / media_v4, so the chat, search and shared media keep showing it.</li>
 *   <li>{@code zg.zg_edit_history(id, uid, mid, version_date, captured_at, data)}: one earlier version, {@code data}
 *       being the replaced TLRPC.Message serialized exactly like messages_v2.data.</li>
 * </ul>
 * Logout and login (MessagesStorage.cleanup) delete the file together with cache4.db ({@link #onDatabaseFilesDeleted}).
 * Statements here never begin or commit a transaction: they may run inside one of Telegram's (putMessages), and
 * Telegram's SQLiteDatabase commits an open transaction when a new one begins. A prepare that fails (a full disk, a
 * busy file) is retried once from a storage task of its own.
 */
public final class ZgHistoryStore {

    static final int SCHEMA_VERSION = 1;
    /** Rows per multi-row INSERT. */
    private static final int INSERT_CHUNK = 200;

    /** Name of the attached side database on Telegram's connection. */
    static final String SCHEMA = "zg";
    /** The side database of an account, in the account's files directory (next to cache4.db). */
    static final String FILE_NAME = "zg_history.db";
    private static final String META = SCHEMA + ".zg_meta";
    private static final String KEPT = SCHEMA + ".zg_kept_deleted";
    private static final String EDITS = SCHEMA + ".zg_edit_history";

    /** The database each account's schema and index were prepared for (storage thread of that account only). */
    private static final SQLiteDatabase[] preparedFor = new SQLiteDatabase[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgHistoryStore() {
    }

    /** One row of zg_kept_deleted. */
    static final class KeptRow {
        final long uid;
        final int mid;
        final int deletedAt;
        /** Channel id of the message (messages_v2.is_channel), 0 for private chats and basic groups. */
        final long channelId;

        KeptRow(long uid, int mid, int deletedAt, long channelId) {
            this.uid = uid;
            this.mid = mid;
            this.deletedAt = deletedAt;
            this.channelId = channelId;
        }
    }

    /** One earlier version of a message. */
    public static final class Revision {
        public final long id;
        public final int versionDate;
        public final int capturedAt;
        /** The replaced message (text, entities, media), read back like messages_v2.data. */
        public final TLRPC.Message message;

        Revision(long id, int versionDate, int capturedAt, TLRPC.Message message) {
            this.id = id;
            this.versionDate = versionDate;
            this.capturedAt = capturedAt;
            this.message = message;
        }
    }

    static boolean isStorageThread(MessagesStorage storage) {
        return storage != null && Thread.currentThread() == storage.getStorageQueue();
    }

    /**
     * Storage queue of {@code account} only. Attaches the side database to the current connection, creates its tables
     * and (re)loads the index when the database instance changed (first use, logout, reset, recovery). Returns
     * Telegram's database (with {@code zg} attached), or null when it is not open or the side database could not be
     * attached; a failed attach is retried once from a storage task of its own.
     */
    static SQLiteDatabase prepare(int account, MessagesStorage storage) {
        return prepare(account, storage, true);
    }

    private static SQLiteDatabase prepare(int account, MessagesStorage storage, boolean retryLater) {
        if (account < 0 || account >= preparedFor.length || storage == null) {
            return null;
        }
        final SQLiteDatabase db = storage.getDatabase();
        if (db == null) {
            return null;
        }
        if (preparedFor[account] == db) {
            return db;
        }
        try {
            attachSideDatabase(account, db);
        } catch (Throwable e) {
            FileLog.e(e);
            if (retryLater) {
                storage.getStorageQueue().postRunnable(() -> {
                    try {
                        prepare(account, storage, false);
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                });
            }
            return null;
        }
        preparedFor[account] = db;
        reloadIndex(account, db);
        ZgRetentionSweeper.onDatabasePrepared(account);
        return db;
    }

    /** The side database file of an account (MessagesStorage.openDatabase puts cache4.db in the same directory). */
    static File databaseFile(int account) {
        File dir = ApplicationLoader.getFilesDirFixed();
        if (account != 0) {
            dir = new File(dir, "account" + account + "/");
        }
        return new File(dir, FILE_NAME);
    }

    /**
     * Attaches {@code zg_history.db} as {@code zg} (unless it is attached already), creates the tables and moves the
     * tables of an earlier test build out of cache4.db. A side database that cannot be read is replaced by a new one:
     * it only ever holds what Zenlygram kept.
     */
    private static void attachSideDatabase(int account, SQLiteDatabase db) throws Exception {
        final boolean wasAttached = isAttached(db);
        if (!wasAttached) {
            final File file = databaseFile(account);
            try {
                attach(db, file);
                ensureSchema(db);
            } catch (Exception e) {
                if (!isUnreadable(e)) {
                    detachQuietly(db);
                    throw e;
                }
                FileLog.e("ZgHistoryStore: side database unreadable, starting a new one");
                detachQuietly(db);
                deleteFiles(file);
                attach(db, file);
                ensureSchema(db);
            }
        } else {
            ensureSchema(db);
        }
        if (!wasAttached) {
            moveTablesOutOfMain(db);
        }
    }

    private static void attach(SQLiteDatabase db, File file) throws Exception {
        SQLitePreparedStatement state = null;
        try {
            state = db.executeFast("ATTACH DATABASE ? AS " + SCHEMA);
            state.bindString(1, file.getAbsolutePath());
            state.step();
        } finally {
            if (state != null) {
                state.dispose();
            }
        }
        // As Telegram's own database: deleted rows are overwritten, write-ahead log of at most 10 MB.
        db.executeFast("PRAGMA " + SCHEMA + ".secure_delete = ON").stepThis().dispose();
        db.executeFast("PRAGMA " + SCHEMA + ".journal_mode = WAL").stepThis().dispose();
        db.executeFast("PRAGMA " + SCHEMA + ".journal_size_limit = 10485760").stepThis().dispose();
    }

    private static boolean isAttached(SQLiteDatabase db) throws Exception {
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("PRAGMA database_list");
            while (cursor.next()) {
                if (SCHEMA.equals(cursor.stringValue(1))) {
                    return true;
                }
            }
            return false;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static void detachQuietly(SQLiteDatabase db) {
        try {
            if (isAttached(db)) {
                db.executeFast("DETACH DATABASE " + SCHEMA).stepThis().dispose();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** SQLite's texts for a file that is not a usable database (as opposed to a busy disk or an open transaction). */
    private static boolean isUnreadable(Throwable e) {
        final String message = e.getMessage();
        return message != null && (message.contains("malformed") || message.contains("not a database") || message.contains("encrypted"));
    }

    private static void deleteFiles(File file) {
        final String path = file.getAbsolutePath();
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            try {
                final File f = new File(path + suffix);
                if (f.exists() && !f.delete()) {
                    FileLog.e("ZgHistoryStore: could not delete " + f.getName());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /**
     * MessagesStorage.cleanup (logout, login), on the storage queue after cache4.db was closed and deleted: the side
     * database goes too, and the index is emptied. The next prepare starts a new one on the new connection.
     */
    public static void onDatabaseFilesDeleted(int account) {
        try {
            if (account < 0 || account >= preparedFor.length) {
                return;
            }
            preparedFor[account] = null;
            deleteFiles(databaseFile(account));
            final ZgHistoryIndex index = ZgHistoryIndex.peek(account);
            if (index != null) {
                index.clear();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * An earlier test build kept these tables inside cache4.db: their rows move to the side database and the tables are
     * dropped. Every step can run again (the copies skip rows that are already there), so an interrupted move simply
     * finishes on the next prepare.
     */
    private static void moveTablesOutOfMain(SQLiteDatabase db) {
        try {
            if (db.tableExists("zg_kept_deleted")) {
                db.executeFast("INSERT OR IGNORE INTO " + KEPT + "(uid, mid, deleted_at, is_channel) SELECT uid, mid, deleted_at, is_channel FROM main.zg_kept_deleted").stepThis().dispose();
                db.executeFast("DROP TABLE main.zg_kept_deleted").stepThis().dispose();
            }
            if (db.tableExists("zg_edit_history")) {
                db.executeFast("INSERT INTO " + EDITS + "(uid, mid, version_date, captured_at, data) SELECT o.uid, o.mid, o.version_date, o.captured_at, o.data FROM main.zg_edit_history o"
                    + " WHERE NOT EXISTS (SELECT 1 FROM " + EDITS + " n WHERE n.uid = o.uid AND n.mid = o.mid AND n.version_date = o.version_date AND n.captured_at = o.captured_at) ORDER BY o.id").stepThis().dispose();
                db.executeFast("DROP TABLE main.zg_edit_history").stepThis().dispose();
            }
            if (db.tableExists("zg_meta")) {
                db.executeFast("DROP TABLE main.zg_meta").stepThis().dispose();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Like {@link #prepare}, but never attaches or changes the schema here: inside MessagesStorage.putMessages a
     * transaction, a cursor and prepared statements of Telegram are open, so neither ATTACH nor a first-time CREATE
     * TABLE may run there. When the account is not prepared yet, a prepare is queued and null is returned (that one
     * write is skipped).
     */
    static SQLiteDatabase preparedDatabase(int account, MessagesStorage storage) {
        if (account < 0 || account >= preparedFor.length || storage == null) {
            return null;
        }
        final SQLiteDatabase db = storage.getDatabase();
        if (db != null && preparedFor[account] == db) {
            return db;
        }
        storage.getStorageQueue().postRunnable(() -> {
            try {
                prepare(account, storage);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
        return null;
    }

    private static void ensureSchema(SQLiteDatabase db) throws Exception {
        db.executeFast("CREATE TABLE IF NOT EXISTS " + META + "(name TEXT PRIMARY KEY, value INTEGER)").stepThis().dispose();
        db.executeFast("CREATE TABLE IF NOT EXISTS " + KEPT + "(uid INTEGER NOT NULL, mid INTEGER NOT NULL, deleted_at INTEGER NOT NULL, is_channel INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uid, mid))").stepThis().dispose();
        db.executeFast("CREATE INDEX IF NOT EXISTS " + SCHEMA + ".zg_kept_deleted_at ON zg_kept_deleted(deleted_at)").stepThis().dispose();
        db.executeFast("CREATE TABLE IF NOT EXISTS " + EDITS + "(id INTEGER PRIMARY KEY AUTOINCREMENT, uid INTEGER NOT NULL, mid INTEGER NOT NULL, version_date INTEGER NOT NULL, captured_at INTEGER NOT NULL, data BLOB NOT NULL)").stepThis().dispose();
        db.executeFast("CREATE INDEX IF NOT EXISTS " + SCHEMA + ".zg_edit_history_msg ON zg_edit_history(uid, mid)").stepThis().dispose();
        db.executeFast("CREATE INDEX IF NOT EXISTS " + SCHEMA + ".zg_edit_history_at ON zg_edit_history(captured_at)").stepThis().dispose();
        // Our own migrations go here, keyed by this row; version 1 is the first schema.
        db.executeFast("INSERT OR IGNORE INTO " + META + " VALUES('history_schema', " + SCHEMA_VERSION + ")").stepThis().dispose();
    }

    private static void reloadIndex(int account, SQLiteDatabase db) {
        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
        if (index == null) {
            return;
        }
        final LongSparseArray<LongSparseIntArray> kept = new LongSparseArray<>();
        final LongSparseArray<LongSparseIntArray> edits = new LongSparseArray<>();
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT uid, mid, deleted_at FROM " + KEPT);
            while (cursor.next()) {
                put(kept, cursor.longValue(0), cursor.intValue(1), cursor.intValue(2));
            }
            cursor.dispose();
            cursor = db.queryFinalized("SELECT uid, mid, MAX(captured_at) FROM " + EDITS + " GROUP BY uid, mid");
            while (cursor.next()) {
                put(edits, cursor.longValue(0), cursor.intValue(1), cursor.intValue(2));
            }
            cursor.dispose();
            cursor = null;
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        index.replaceAll(kept, edits);
    }

    /** Reloads the index entries of one dialog from the tables. */
    static void reloadIndexFor(int account, SQLiteDatabase db, long uid) {
        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
        if (index == null) {
            return;
        }
        final LongSparseIntArray kept = new LongSparseIntArray();
        final LongSparseIntArray edits = new LongSparseIntArray();
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT mid, deleted_at FROM " + KEPT + " WHERE uid = " + uid);
            while (cursor.next()) {
                kept.put(cursor.intValue(0), Math.max(1, cursor.intValue(1)));
            }
            cursor.dispose();
            cursor = db.queryFinalized("SELECT mid, MAX(captured_at) FROM " + EDITS + " WHERE uid = " + uid + " GROUP BY mid");
            while (cursor.next()) {
                edits.put(cursor.intValue(0), Math.max(1, cursor.intValue(1)));
            }
            cursor.dispose();
            cursor = null;
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        index.replaceKeptIn(uid, kept);
        index.replaceEditsIn(uid, edits);
    }

    /** Reloads only the kept entries (after an orphan cleanup). */
    static void reloadKeptIndex(int account, SQLiteDatabase db) {
        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
        if (index == null) {
            return;
        }
        final LongSparseArray<LongSparseIntArray> kept = new LongSparseArray<>();
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT uid, mid, deleted_at FROM " + KEPT);
            while (cursor.next()) {
                put(kept, cursor.longValue(0), cursor.intValue(1), cursor.intValue(2));
            }
            cursor.dispose();
            cursor = null;
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        index.replaceAllKept(kept);
    }

    private static void put(LongSparseArray<LongSparseIntArray> map, long uid, int mid, int value) {
        LongSparseIntArray mids = map.get(uid);
        if (mids == null) {
            map.put(uid, mids = new LongSparseIntArray());
        }
        mids.put(mid, Math.max(1, value));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Kept copies
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Inserts stamps (INSERT OR IGNORE, so an existing stamp is never renewed). Each statement is atomic; the result
     * tells, row by row, whether its statement succeeded.
     */
    static boolean[] insertKept(SQLiteDatabase db, List<KeptRow> rows) {
        final boolean[] written = new boolean[rows.size()];
        final StringBuilder sql = new StringBuilder();
        for (int start = 0; start < rows.size(); start += INSERT_CHUNK) {
            final int end = Math.min(rows.size(), start + INSERT_CHUNK);
            sql.setLength(0);
            sql.append("INSERT OR IGNORE INTO " + KEPT + "(uid, mid, deleted_at, is_channel) VALUES ");
            for (int i = start; i < end; i++) {
                final KeptRow row = rows.get(i);
                if (i > start) {
                    sql.append(',');
                }
                sql.append(String.format(Locale.US, "(%d,%d,%d,%d)", row.uid, row.mid, row.deletedAt, row.channelId));
            }
            try {
                db.executeFast(sql.toString()).stepThis().dispose();
                for (int i = start; i < end; i++) {
                    written[i] = true;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return written;
    }

    /** Deletes the stamps of (uid, mids); the messages themselves are not touched. */
    static void deleteKept(SQLiteDatabase db, long uid, List<Integer> mids) throws Exception {
        for (int start = 0; start < mids.size(); start += ZgHistorySettings.IN_LIST_CHUNK) {
            final int end = Math.min(mids.size(), start + ZgHistorySettings.IN_LIST_CHUNK);
            db.executeFast(String.format(Locale.US, "DELETE FROM " + KEPT + " WHERE uid = %d AND mid IN(%s)",
                uid, TextUtils.join(",", mids.subList(start, end)))).stepThis().dispose();
        }
    }

    /** Kept copies whose message is still in the cache, over the whole account. */
    static int countKeptExisting(SQLiteDatabase db) {
        return countQuery(db, "SELECT COUNT(*) FROM " + KEPT + " k WHERE EXISTS (SELECT 1 FROM main.messages_v2 m WHERE m.uid = k.uid AND m.mid = k.mid)");
    }

    /** Message ids of the kept copies of one chat whose message is still in the cache. */
    static ArrayList<Integer> keptInChat(SQLiteDatabase db, long uid) {
        final ArrayList<Integer> result = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT k.mid FROM " + KEPT + " k WHERE k.uid = " + uid
                + " AND EXISTS (SELECT 1 FROM main.messages_v2 m WHERE m.uid = k.uid AND m.mid = k.mid) ORDER BY k.mid");
            while (cursor.next()) {
                result.add(cursor.intValue(0));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /** Up to {@code limit} stamps made at or before {@code cutoff}, oldest first. */
    static ArrayList<KeptRow> selectKeptUpTo(SQLiteDatabase db, int cutoff, int limit) {
        return selectKept(db, "SELECT uid, mid, deleted_at, is_channel FROM " + KEPT + " WHERE deleted_at <= " + cutoff
            + " ORDER BY deleted_at LIMIT " + limit);
    }

    /** The {@code limit} oldest stamps. */
    static ArrayList<KeptRow> selectKeptOldest(SQLiteDatabase db, int limit) {
        return selectKept(db, "SELECT uid, mid, deleted_at, is_channel FROM " + KEPT + " ORDER BY deleted_at LIMIT " + limit);
    }

    private static ArrayList<KeptRow> selectKept(SQLiteDatabase db, String sql) {
        final ArrayList<KeptRow> result = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized(sql);
            while (cursor.next()) {
                result.add(new KeptRow(cursor.longValue(0), cursor.intValue(1), cursor.intValue(2), cursor.longValue(3)));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    static int countKeptAll(SQLiteDatabase db) {
        return countQuery(db, "SELECT COUNT(*) FROM " + KEPT);
    }

    /** Oldest stamp, or 0 when nothing is kept. */
    static int minDeletedAt(SQLiteDatabase db) {
        return countQuery(db, "SELECT MIN(deleted_at) FROM " + KEPT);
    }

    /** Removes stamps whose message is no longer in the cache. Returns how many were removed. */
    static int deleteKeptOrphans(SQLiteDatabase db) {
        try {
            db.executeFast("DELETE FROM " + KEPT + " WHERE NOT EXISTS (SELECT 1 FROM main.messages_v2 m WHERE m.uid = zg_kept_deleted.uid AND m.mid = zg_kept_deleted.mid)").stepThis().dispose();
            return changes(db);
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Edit history
    // ---------------------------------------------------------------------------------------------------------

    /** Inserts one revision and keeps the newest {@link ZgHistorySettings#EDIT_MAX_REVISIONS} of the message. */
    static void insertRevision(SQLiteDatabase db, long uid, int mid, int versionDate, int capturedAt, NativeByteBuffer data, int visibleCutoff) throws Exception {
        SQLitePreparedStatement state = null;
        try {
            state = db.executeFast("INSERT INTO " + EDITS + "(uid, mid, version_date, captured_at, data) VALUES(?, ?, ?, ?, ?)");
            state.requery();
            state.bindLong(1, uid);
            state.bindInteger(2, mid);
            state.bindInteger(3, versionDate);
            state.bindInteger(4, capturedAt);
            state.bindByteBuffer(5, data);
            state.step();
        } finally {
            if (state != null) {
                state.dispose();
            }
        }
        // Expired or purged revisions of this message go now; then only the newest 20 stay.
        db.executeFast(String.format(Locale.US, "DELETE FROM " + EDITS + " WHERE uid = %d AND mid = %d AND captured_at <= %d",
            uid, mid, visibleCutoff)).stepThis().dispose();
        db.executeFast(String.format(Locale.US, "DELETE FROM " + EDITS + " WHERE uid = %d AND mid = %d AND id NOT IN (SELECT id FROM " + EDITS + " WHERE uid = %d AND mid = %d ORDER BY id DESC LIMIT %d)",
            uid, mid, uid, mid, ZgHistorySettings.EDIT_MAX_REVISIONS)).stepThis().dispose();
    }

    /** Visible revisions of (uid, mid), newest first. */
    static ArrayList<Revision> loadRevisions(SQLiteDatabase db, long uid, int mid, int visibleCutoff, long selfId) {
        final ArrayList<Revision> result = new ArrayList<>();
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized(String.format(Locale.US,
                "SELECT id, version_date, captured_at, data FROM " + EDITS + " WHERE uid = %d AND mid = %d AND captured_at > %d ORDER BY version_date DESC, id DESC",
                uid, mid, visibleCutoff));
            while (cursor.next()) {
                final NativeByteBuffer data = cursor.byteBufferValue(3);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = null;
                try {
                    message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, selfId);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    message = null;
                } finally {
                    data.reuse();
                }
                if (message != null) {
                    result.add(new Revision(cursor.longValue(0), cursor.intValue(1), cursor.intValue(2), message));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return result;
    }

    /** Messages with at least one revision captured after {@code cutoff}. */
    static int countMessagesWithRevisions(SQLiteDatabase db, int cutoff) {
        return countQuery(db, "SELECT COUNT(*) FROM (SELECT 1 FROM " + EDITS + " WHERE captured_at > " + cutoff + " GROUP BY uid, mid)");
    }

    /** Deletes up to {@code limit} revisions captured at or before {@code cutoff}; returns how many were deleted. */
    static int deleteRevisionsUpTo(SQLiteDatabase db, int cutoff, int limit) {
        try {
            db.executeFast("DELETE FROM " + EDITS + " WHERE id IN (SELECT id FROM " + EDITS + " WHERE captured_at <= " + cutoff + " LIMIT " + limit + ")").stepThis().dispose();
            return changes(db);
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Oldest capture time, or 0 when there is no revision. */
    static int minCapturedAt(SQLiteDatabase db) {
        return countQuery(db, "SELECT MIN(captured_at) FROM " + EDITS);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hooks in MessagesStorage (storage queue)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * MessagesStorage.markMessagesAsDeletedInternal: these messages are being really deleted, so their stamps and
     * revisions go too. {@code messagesByDialogs} maps the dialog id to the ids found in messages_v2.
     */
    public static void onMessagesDeleted(int account, MessagesStorage storage, androidx.collection.LongSparseArray<ArrayList<Integer>> messagesByDialogs) {
        if (messagesByDialogs == null || messagesByDialogs.size() == 0) {
            return;
        }
        try {
            if (!isStorageThread(storage)) {
                final androidx.collection.LongSparseArray<ArrayList<Integer>> copy = messagesByDialogs.clone();
                storage.getStorageQueue().postRunnable(() -> onMessagesDeleted(account, storage, copy));
                return;
            }
            final SQLiteDatabase db = prepare(account, storage);
            final ZgHistoryIndex index = ZgHistoryIndex.get(account);
            if (db == null || index == null || !index.hasAnyKept() && !index.hasAnyEdits()) {
                return;
            }
            for (int i = 0, n = messagesByDialogs.size(); i < n; i++) {
                final long uid = messagesByDialogs.keyAt(i);
                final ArrayList<Integer> mids = messagesByDialogs.valueAt(i);
                if (mids == null || mids.isEmpty()) {
                    continue;
                }
                final ArrayList<Integer> kept = new ArrayList<>();
                final ArrayList<Integer> edited = new ArrayList<>();
                for (int j = 0, m = mids.size(); j < m; j++) {
                    final int mid = mids.get(j);
                    if (index.isKept(uid, mid)) {
                        kept.add(mid);
                    }
                    if (index.getMaxCapturedAt(uid, mid) != 0) {
                        edited.add(mid);
                    }
                }
                if (!kept.isEmpty()) {
                    deleteKept(db, uid, kept);
                    index.removeKept(uid, kept);
                }
                if (!edited.isEmpty()) {
                    for (int start = 0; start < edited.size(); start += ZgHistorySettings.IN_LIST_CHUNK) {
                        final int end = Math.min(edited.size(), start + ZgHistorySettings.IN_LIST_CHUNK);
                        db.executeFast(String.format(Locale.US, "DELETE FROM " + EDITS + " WHERE uid = %d AND mid IN(%s)",
                            uid, TextUtils.join(",", edited.subList(start, end)))).stepThis().dispose();
                    }
                    index.removeEdits(uid, edited);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * MessagesStorage.deleteDialog, after it ran (any mode): stamps whose message is gone are removed; on "delete chat"
     * and "clear history" (messagesOnly 0 / 1) also the revisions of messages that are gone. A cache-only clear
     * (messagesOnly 2) keeps revisions: those messages come back from the server.
     */
    public static void onDialogDeleted(int account, MessagesStorage storage, long did, int messagesOnly) {
        try {
            if (!isStorageThread(storage)) {
                return;
            }
            final ZgHistoryIndex index = ZgHistoryIndex.peek(account);
            final SQLiteDatabase db = prepare(account, storage);
            if (db == null || index == null) {
                return;
            }
            final boolean hasKept = index.hasKeptIn(did);
            final boolean dropRevisions = messagesOnly == 0 || messagesOnly == 1;
            if (!hasKept && !(dropRevisions && index.hasAnyEdits())) {
                return;
            }
            if (hasKept) {
                db.executeFast("DELETE FROM " + KEPT + " WHERE uid = " + did
                    + " AND NOT EXISTS (SELECT 1 FROM main.messages_v2 m WHERE m.uid = zg_kept_deleted.uid AND m.mid = zg_kept_deleted.mid)").stepThis().dispose();
            }
            if (dropRevisions) {
                db.executeFast("DELETE FROM " + EDITS + " WHERE uid = " + did
                    + " AND NOT EXISTS (SELECT 1 FROM main.messages_v2 m WHERE m.uid = zg_edit_history.uid AND m.mid = zg_edit_history.mid)").stepThis().dispose();
            }
            reloadIndexFor(account, db, did);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    static int changes(SQLiteDatabase db) throws Exception {
        final SQLiteCursor cursor = db.queryFinalized("SELECT changes()");
        try {
            return cursor.next() ? cursor.intValue(0) : 0;
        } finally {
            cursor.dispose();
        }
    }

    private static int countQuery(SQLiteDatabase db, String sql) {
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized(sql);
            if (cursor.next() && !cursor.isNull(0)) {
                return cursor.intValue(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return 0;
    }
}
