/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

/**
 * Start-up of the retained history (Show Deleted Messages, Save Edit History). Called once from
 * ApplicationLoader.postInitApplication (also on the push path, before a push is handled).
 *
 * <p>For every signed-in account it prepares the tables and loads the in-memory index on the storage queue, and it
 * prepares them again whenever an account's database is (re)opened (login, logout, Telegram's reset or recovery),
 * which also schedules the sweeper. Every storage entry point prepares lazily as well, so nothing depends on the
 * order of these notifications.
 */
public final class ZgHistoryModule {

    private static boolean inited;

    private static final NotificationCenter.NotificationCenterDelegate databaseObserver = (id, account, args) -> {
        if (id == NotificationCenter.onDatabaseOpened || id == NotificationCenter.onDatabaseReset) {
            prepareAsync(account);
        }
    };

    private ZgHistoryModule() {
    }

    public static synchronized void init() {
        if (inited) {
            return;
        }
        inited = true;
        // postInitApplication can run on a binder thread (widgets, notification image provider); observers are
        // registered on the main thread only.
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                try {
                    final NotificationCenter center = NotificationCenter.getInstance(a);
                    center.addObserver(databaseObserver, NotificationCenter.onDatabaseOpened);
                    center.addObserver(databaseObserver, NotificationCenter.onDatabaseReset);
                    prepareAsync(a);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    private static void prepareAsync(int account) {
        try {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
                return;
            }
            if (!UserConfig.getInstance(account).isClientActivated()) {
                // Logged out: its database (and everything retained in it) is gone, so is the in-memory mirror.
                final ZgHistoryIndex index = ZgHistoryIndex.peek(account);
                if (index != null) {
                    index.clear();
                }
                return;
            }
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    ZgHistoryStore.prepare(account, storage);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
