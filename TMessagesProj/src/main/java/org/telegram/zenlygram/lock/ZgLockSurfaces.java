/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.lock;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ShortcutManager;
import android.os.Build;

import androidx.annotation.MainThread;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatsWidgetProvider;
import org.telegram.messenger.ContactsWidgetProvider;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

/**
 * Surfaces outside the app that show a chat's name or text and must follow a lock change at once (Chat Lock and the
 * Secret Vault): the home-screen widgets, the notifications already in the shade and the launcher / share-sheet
 * conversation shortcuts.
 */
public final class ZgLockSurfaces {

    private ZgLockSurfaces() {
    }

    /** The chats and contacts widgets re-read their rows (locked chats read "Locked chat", hidden ones drop out). */
    public static void refreshWidgets() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            final AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) {
                return;
            }
            final int[] chats = manager.getAppWidgetIds(new ComponentName(context, ChatsWidgetProvider.class));
            if (chats != null && chats.length > 0) {
                manager.notifyAppWidgetViewDataChanged(chats, R.id.list_view);
            }
            final int[] contacts = manager.getAppWidgetIds(new ComponentName(context, ContactsWidgetProvider.class));
            if (contacts != null && contacts.length > 0) {
                manager.notifyAppWidgetViewDataChanged(contacts, R.id.list_view);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** The notification conversation shortcuts ("ndid_") of these chats (they show name and avatar). */
    public static void removeConversationShortcuts(long[] dialogIds) {
        if (Build.VERSION.SDK_INT < 26 || dialogIds == null || dialogIds.length == 0) {
            return;
        }
        final ArrayList<String> ids = new ArrayList<>();
        for (long id : dialogIds) {
            ids.add("ndid_" + id);
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final Context context = ApplicationLoader.applicationContext;
                ShortcutManagerCompat.removeDynamicShortcuts(context, ids);
                if (Build.VERSION.SDK_INT >= 30) {
                    final ShortcutManager manager = context.getSystemService(ShortcutManager.class);
                    if (manager != null) {
                        manager.removeLongLivedShortcuts(ids);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Every activated account posts its notifications again, silently: a chat just locked loses its sender, text,
     * picture and reply / mark-read actions in the shade (and gets them back when unlocked for good).
     */
    @MainThread
    public static void repostNotifications() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                try {
                    NotificationsController.getInstance(a).showNotifications();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
    }
}
