/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The reader of the old device-wide tasks database ({@code zg_tasks.db}, iOS TodoDatabase): the lists and tasks the
 * builds before Cloud Tasks kept for the whole device, with times in Unix seconds. The board no longer reads or writes
 * it: every screen works on a {@code ZgTasksSource}, and this class serves only {@link ZgLegacyTasksImport}, which
 * moves its rows into the selected account's device store once.
 *
 * <ul>
 *   <li>It never creates the file: {@link #exists} is asked first.</li>
 *   <li>It never changes a row. After the import the file is renamed to {@code zg_tasks.imported.db}
 *       ({@link #moveAside}) and kept; only the last account's logout removes it ({@link #deleteFiles}), with every
 *       other task on the device.</li>
 * </ul>
 * Any one thread at a time (the importer uses the device store's queue).
 */
public final class ZgTodoStore {

    private static final String IMPORTED_DB_NAME = "zg_tasks.imported.db";
    private static final String[] SIDE_FILES = {"-wal", "-shm", "-journal"};

    private static final String TASK_COLUMNS = "id, folder_id, title, description, due_at, priority, is_completed, completed_at, "
        + "sort_order, created_at, updated_at, assignee_peer_id, assignee_display_name";

    private final ZgTasksDbHelper helper;

    private ZgTodoStore(Context context) {
        this.helper = new ZgTasksDbHelper(context);
    }

    /** Whether the old board's file is still in place: the import is due. */
    public static boolean exists(Context context) {
        return context.getDatabasePath(ZgTasksDbHelper.DB_NAME).exists();
    }

    public static ZgTodoStore open(Context context) {
        return new ZgTodoStore(context.getApplicationContext() != null ? context.getApplicationContext() : context);
    }

    /** All lists in their order (sort order, then creation). Throws when the file cannot be read. */
    public List<ZgTodoFolder> loadFolders() {
        final ArrayList<ZgTodoFolder> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT id, title, sort_order, created_at FROM folders ORDER BY sort_order, created_at", null)) {
            while (c.moveToNext()) {
                result.add(new ZgTodoFolder(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)));
            }
        }
        return result;
    }

    /** All tasks: open ones first, then by stored order. Throws when the file cannot be read. */
    public List<ZgTodoTask> loadTasks() {
        final ArrayList<ZgTodoTask> result = new ArrayList<>();
        try (Cursor c = helper.getReadableDatabase().rawQuery("SELECT " + TASK_COLUMNS + " FROM tasks ORDER BY is_completed ASC, sort_order ASC, created_at ASC", null)) {
            while (c.moveToNext()) {
                final ZgTodoAssignee assignee = !c.isNull(11) && !c.isNull(12) ? new ZgTodoAssignee(c.getLong(11), c.getString(12)) : null;
                result.add(new ZgTodoTask(
                    c.getString(0),
                    c.isNull(1) ? null : c.getString(1),
                    c.getString(2),
                    c.isNull(3) ? null : c.getString(3),
                    c.isNull(4) ? null : c.getLong(4),
                    c.getInt(5),
                    c.getInt(6) != 0,
                    c.isNull(7) ? null : c.getLong(7),
                    c.getInt(8),
                    c.getLong(9),
                    c.getLong(10),
                    assignee
                ));
            }
        }
        return result;
    }

    /** Closes the file; with it the write-ahead log is folded back into the one database file. */
    public void close() {
        try {
            helper.close();
        } catch (RuntimeException ignore) {
            // Nothing was written through this helper.
        }
    }

    /**
     * After the import finished: the old file is kept under another name, so nothing imports it twice and nothing is
     * lost. False when it could not be renamed (the import's own marker still keeps it from running again).
     */
    public static boolean moveAside(Context context) {
        final File from = context.getDatabasePath(ZgTasksDbHelper.DB_NAME);
        if (!from.exists()) {
            return true;
        }
        final File to = context.getDatabasePath(IMPORTED_DB_NAME);
        if (to.exists() || !from.renameTo(to)) {
            return false;
        }
        for (String suffix : SIDE_FILES) {
            final File side = new File(from.getPath() + suffix);
            if (side.exists()) {
                side.renameTo(new File(to.getPath() + suffix));
            }
        }
        return true;
    }

    /** The last account logged out: the old board and its kept copy are removed with every other task on the device. */
    public static void deleteFiles(Context context) {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(ZgTasksDbHelper.DB_NAME));
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(IMPORTED_DB_NAME));
    }
}
