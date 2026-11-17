/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * The old tasks database, {@value #DB_NAME} in the app's databases folder: one file for the whole device, shared by
 * every account (iOS TodoDatabase.shared), written by the builds before Cloud Tasks. Nothing writes it any more: it is
 * opened once, by the one-time import into the selected account's device store ({@link ZgLegacyTasksImport}).
 *
 * <p>Version 1 is the iOS schema v3 without the Cloud tables (folders with their incarnation, tasks with the assignee
 * columns).
 */
final class ZgTasksDbHelper extends SQLiteOpenHelper {

    static final String DB_NAME = "zg_tasks.db";
    private static final int DB_VERSION = 1;

    ZgTasksDbHelper(Context context) {
        // The framework's default handler deletes a database it finds corrupt. This file is the only copy of the old
        // tasks until they are imported: it is left as it is, and the open fails instead.
        super(context, DB_NAME, null, DB_VERSION, db -> { });
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Only reached if the file vanished between the importer's check and the open: an empty board, nothing to import.
        db.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY, title TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, "
            + "created_at INTEGER NOT NULL, incarnation TEXT)");
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, folder_id TEXT REFERENCES folders(id) ON DELETE CASCADE, "
            + "title TEXT NOT NULL, description TEXT, due_at INTEGER, priority INTEGER NOT NULL DEFAULT 0, "
            + "is_completed INTEGER NOT NULL DEFAULT 0, completed_at INTEGER, sort_order INTEGER NOT NULL DEFAULT 0, "
            + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, assignee_peer_id INTEGER, assignee_display_name TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the only one ever written.
    }
}
