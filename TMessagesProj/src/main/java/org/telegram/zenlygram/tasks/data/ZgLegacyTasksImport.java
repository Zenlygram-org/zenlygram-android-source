/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import android.content.Context;

import androidx.annotation.UiThread;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksStore;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksMigrationNormalizer;
import org.telegram.zenlygram.tasks.ZgTasksPrefs;
import org.telegram.zenlygram.tasks.model.ZgLegacyTasksMapper;

import java.util.ArrayList;

/**
 * The one-time import of the old device-wide board ({@code zg_tasks.db}, {@link ZgTodoStore}) into the device store of
 * the account that is selected when it runs (ZenlyTasksStore, one database per Telegram user). The builds before
 * Cloud Tasks kept one board for the whole device; the copy to Cloud, its write fence and the cut-over are written
 * against the per-account store, so the board lives there now.
 *
 * <ul>
 *   <li>It starts with the main screen ({@code ZgTasks.prepare}) and, at the latest, before the first read of a device
 *       board. The old file is read and its rows are added on the device store's own queue, in one job: every later
 *       read of that store, the board's and the copy's, already sees them.</li>
 *   <li>Idempotent: rows keep their ids and a row the store already holds is left as it is; assignees go to the side
 *       store only where none is stored.</li>
 *   <li>Crash-safe: the rows land in one transaction. Only after it are the old file renamed to
 *       {@code zg_tasks.imported.db} (kept, never deleted here) and the marker written. A crash in between repeats the
 *       import at the next launch and adds nothing.</li>
 *   <li>If it cannot finish (the old file or the store cannot be read), the board opens without the old rows and the
 *       next launch tries again; this process does not retry.</li>
 * </ul>
 * Main thread, except where noted. Nothing here logs a title, an id or a count of the user's tasks.
 */
public final class ZgLegacyTasksImport {

    private static boolean running;
    /** This process tried and could not finish: the next launch tries again. */
    private static boolean gaveUp;
    private static final ArrayList<Runnable> waiters = new ArrayList<>();

    private ZgLegacyTasksImport() {
    }

    /**
     * Starts the import into {@code account}'s device store when it is still due. {@code then} (may be null) runs on
     * the main thread once there is nothing more to wait for in this process: the import is done, was never needed, or
     * could not finish now. It runs at once when that is already so.
     */
    @UiThread
    public static void run(int account, Runnable then) {
        if (!running && (gaveUp || ZgTasksPrefs.isLegacyImportDone())) {
            if (then != null) {
                then.run();
            }
            return;
        }
        if (then != null) {
            waiters.add(then);
        }
        if (running) {
            return;
        }
        final ZenlyTasksStore store;
        final long userId = UserConfig.getInstance(account).getClientUserId();
        try {
            store = ZenlyTasksStore.get(account);
        } catch (RuntimeException notSignedIn) {
            // No account to import into yet: a later call, with a signed-in account, starts it.
            finish();
            return;
        }
        running = true;
        final Context context = ApplicationLoader.applicationContext;
        // The names of a task or list whose stored name shows nothing, as the copy to Cloud would name them.
        final TasksMigrationNormalizer names = new TasksMigrationNormalizer(
            TasksMigrationNormalizer.localeFor(LocaleController.getInstance().getCurrentLocale().getLanguage()));
        store.addRows(account, () -> read(context, userId, names), (result, failure) -> {
            if (failure != null) {
                running = false;
                gaveUp = true;
                FileLog.e("Zenlygram Tasks: the old board could not be imported now; the next launch tries again");
                finish();
                return;
            }
            if (result != null && result.length > 1 && result[1] > 0) {
                // Cannot happen with rows from ZgLegacyTasksMapper; if it ever does, the old file still holds them.
                FileLog.e("Zenlygram Tasks: some rows of the old board were refused by the device store");
            }
            Utilities.globalQueue.postRunnable(() -> {
                // The rows are in the device store: from here on nothing may import them again.
                ZgTodoStore.moveAside(context);
                ZgTasksPrefs.setLegacyImportDone();
                AndroidUtilities.runOnUIThread(() -> {
                    running = false;
                    finish();
                });
            });
        });
    }

    /**
     * The device store's queue: the old rows as the device store keeps them, null when there is nothing to import. The
     * assignees are stored first, so a task never appears without its assignee. Throws when the old file cannot be
     * read; nothing is written then.
     */
    private static ZenlyTaskModel.Snapshot read(Context context, long userId, TasksMigrationNormalizer names) {
        if (!ZgTodoStore.exists(context)) {
            return null;
        }
        final ZgTodoStore old = ZgTodoStore.open(context);
        final ZgLegacyTasksMapper.Rows rows;
        try {
            rows = ZgLegacyTasksMapper.map(old.loadFolders(), old.loadTasks(), names.untitledTask(), names.importedList());
        } finally {
            old.close();
        }
        if (rows.isEmpty()) {
            return null;
        }
        ZgTaskAssigneeStore.get().putIfAbsentBlocking(userId, rows.assignees);
        return new ZenlyTaskModel.Snapshot(rows.folders, rows.tasks);
    }

    private static void finish() {
        if (waiters.isEmpty()) {
            return;
        }
        final ArrayList<Runnable> ready = new ArrayList<>(waiters);
        waiters.clear();
        for (Runnable waiter : ready) {
            waiter.run();
        }
    }

    /**
     * The last account logged out: the old board and its kept copy go with every other task on the device, and nothing
     * is left to import. Any thread.
     */
    public static void wipe() {
        final Context context = ApplicationLoader.applicationContext;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ZgTodoStore.deleteFiles(context);
                ZgTasksPrefs.setLegacyImportDone();
            } catch (Exception e) {
                FileLog.e("Zenlygram Tasks: the old board's file could not be removed");
            }
        });
    }
}
