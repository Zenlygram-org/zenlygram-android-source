/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * The assignees of the device tasks: a small side store next to the account's device store, which has no column for
 * them. One row per (Telegram user id, task id), in {@code zg_tasks_assignees.db}.
 *
 * <ul>
 *   <li>It never leaves the device: no upload, no backup of its own, and the copy of the device tasks to Cloud does not
 *       read it (iOS leaves the assignee out of the copy as well).</li>
 *   <li>A row lives exactly as long as its task: it goes when the task or its list is deleted, a row whose task is
 *       gone is swept after every read of the board ({@link #retain}), and the whole file goes when the last account
 *       logs out and the device tasks are wiped ({@link #wipe}).</li>
 *   <li>Work runs on the store's own queue; {@link #load} answers on the main thread. The device source keeps the
 *       rows of its user in memory and writes through, so nothing waits for a write.</li>
 * </ul>
 */
public final class ZgTaskAssigneeStore {

    public interface Loaded {
        /** Main thread. Task id to assignee; empty when there is none or the store cannot be read. */
        void onLoaded(Map<String, ZgTodoAssignee> assignees);
    }

    private static final String DB_NAME = "zg_tasks_assignees.db";
    private static final ZgTaskAssigneeStore INSTANCE = new ZgTaskAssigneeStore();

    private final Object lock = new Object();
    private final DispatchQueue queue = new DispatchQueue("zgTasksAssignees");
    private Helper helper;

    private ZgTaskAssigneeStore() {
    }

    public static ZgTaskAssigneeStore get() {
        return INSTANCE;
    }

    /** Any thread; the answer arrives on the main thread. */
    public void load(long userId, Loaded callback) {
        queue.postRunnable(() -> {
            final HashMap<String, ZgTodoAssignee> result = new HashMap<>();
            synchronized (lock) {
                try (Cursor c = db().rawQuery("SELECT task_id, peer_id, display_name FROM assignees WHERE user_id = ?", new String[]{String.valueOf(userId)})) {
                    while (c.moveToNext()) {
                        result.put(c.getString(0), new ZgTodoAssignee(c.getLong(1), c.getString(2)));
                    }
                } catch (Exception e) {
                    failed();
                }
            }
            AndroidUtilities.runOnUIThread(() -> callback.onLoaded(result));
        });
    }

    /** Sets the task's assignee; null removes it. */
    public void put(long userId, String taskId, ZgTodoAssignee assignee) {
        queue.postRunnable(() -> {
            synchronized (lock) {
                try {
                    if (assignee == null) {
                        db().delete("assignees", "user_id = ? AND task_id = ?", new String[]{String.valueOf(userId), taskId});
                    } else {
                        db().insertWithOnConflict("assignees", null, values(userId, taskId, assignee), SQLiteDatabase.CONFLICT_REPLACE);
                    }
                } catch (Exception e) {
                    failed();
                }
            }
        });
    }

    /** The sweep after a read of the board: every row of the user whose task is not among {@code taskIds} goes. */
    public void retain(long userId, Collection<String> taskIds) {
        final HashSet<String> keep = new HashSet<>(taskIds);
        queue.postRunnable(() -> {
            synchronized (lock) {
                try {
                    final SQLiteDatabase db = db();
                    final ArrayList<String> orphans = new ArrayList<>();
                    try (Cursor c = db.rawQuery("SELECT task_id FROM assignees WHERE user_id = ?", new String[]{String.valueOf(userId)})) {
                        while (c.moveToNext()) {
                            if (!keep.contains(c.getString(0))) {
                                orphans.add(c.getString(0));
                            }
                        }
                    }
                    delete(db, userId, orphans);
                } catch (Exception e) {
                    failed();
                }
            }
        });
    }

    /**
     * The import of the old board: its assignees, written on the caller's (background) thread before its tasks are, so
     * a task never shows without its assignee. A row that exists is kept: running the import again changes nothing.
     */
    public void putIfAbsentBlocking(long userId, Map<String, ZgTodoAssignee> assignees) {
        if (assignees == null || assignees.isEmpty()) {
            return;
        }
        synchronized (lock) {
            final SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                for (Map.Entry<String, ZgTodoAssignee> entry : assignees.entrySet()) {
                    db.insertWithOnConflict("assignees", null, values(userId, entry.getKey(), entry.getValue()), SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    /** The last account logged out: the file is removed. */
    public void wipe() {
        queue.postRunnable(() -> {
            synchronized (lock) {
                try {
                    if (helper != null) {
                        helper.close();
                        helper = null;
                    }
                    ApplicationLoader.applicationContext.deleteDatabase(DB_NAME);
                } catch (Exception e) {
                    failed();
                }
            }
        });
    }

    private static void delete(SQLiteDatabase db, long userId, Collection<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        db.beginTransaction();
        try {
            for (String taskId : taskIds) {
                db.delete("assignees", "user_id = ? AND task_id = ?", new String[]{String.valueOf(userId), taskId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static ContentValues values(long userId, String taskId, ZgTodoAssignee assignee) {
        final ContentValues values = new ContentValues();
        values.put("user_id", userId);
        values.put("task_id", taskId);
        values.put("peer_id", assignee.peerId);
        values.put("display_name", assignee.displayName);
        return values;
    }

    private SQLiteDatabase db() {
        if (helper == null) {
            helper = new Helper(ApplicationLoader.applicationContext);
        }
        return helper.getWritableDatabase();
    }

    /** Never the exception itself: its message may carry a bound value on some devices. */
    private static void failed() {
        FileLog.e("Zenlygram Tasks: the assignee store could not be read or written");
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, DB_NAME, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE assignees (user_id INTEGER NOT NULL, task_id TEXT NOT NULL, peer_id INTEGER NOT NULL, "
                + "display_name TEXT NOT NULL, PRIMARY KEY (user_id, task_id))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first one.
        }
    }
}
