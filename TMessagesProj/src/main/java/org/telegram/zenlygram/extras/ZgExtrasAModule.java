/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.extras;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.zenlygram.autoaccept.ZgAutoAccept;
import org.telegram.zenlygram.dialogs.ZgForeignNumbers;
import org.telegram.zenlygram.download.ZgAutoDownload;
import org.telegram.zenlygram.pins.ZgPins;
import org.telegram.zenlygram.reminder.ZgUnreadReminder;

/**
 * Start-up and logout of the small extras: Hide Foreign Numbers, Auto-Accept Requests, Unlimited Pinned Chats,
 * Unread Message Reminder and Disable Auto-Download. Two upstream hooks call it: ApplicationLoader.postInitApplication
 * ({@link #init}) and MessagesController.performLogout ({@link #onAccountLoggedOut}).
 */
public final class ZgExtrasAModule {

    private static boolean initialized;

    private static final NotificationCenter.NotificationCenterDelegate accountObserver = (id, account, args) -> {
        if (id == NotificationCenter.dialogDeleted) {
            // MessagesController posts (dialogId, 0) for a deleted chat; TopicsController posts (-chatId, topicId) when a
            // forum topic or a direct-messages thread goes, and the chat itself (with its pin) stays.
            final boolean topic = args.length > 1 && args[1] instanceof Number && ((Number) args[1]).longValue() != 0;
            if (!topic && args.length > 0 && args[0] instanceof Long) {
                ZgPins.onDialogDeleted(account, (Long) args[0]);
            }
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            ZgAutoDownload.sync(); // a new login gets auto-download off too (or a re-enabled account turns the switch off)
        }
    };

    private ZgExtrasAModule() {
    }

    /** ApplicationLoader.postInitApplication, after the accounts are loaded. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    final NotificationCenter center = NotificationCenter.getInstance(a);
                    center.addObserver(accountObserver, NotificationCenter.dialogDeleted);
                    center.addObserver(accountObserver, NotificationCenter.mainUserInfoChanged);
                }
                ZgUnreadReminder.init();
                ZgAutoAccept.init();
                ZgAutoDownload.sync();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** MessagesController.performLogout, after the slot's UserConfig was cleared. */
    public static void onAccountLoggedOut(int account) {
        try {
            boolean lastAccount = true;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (a != account && UserConfig.getInstance(a).isClientActivated()) {
                    lastAccount = false;
                    break;
                }
            }
            ZgPins.onAccountLoggedOut(account);
            ZgAutoDownload.onAccountLoggedOut(account);
            ZgUnreadReminder.onAccountLoggedOut(account);
            ZgForeignNumbers.onAccountLoggedOut(account);
            ZgAutoAccept.onAccountLoggedOut(account, lastAccount);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
