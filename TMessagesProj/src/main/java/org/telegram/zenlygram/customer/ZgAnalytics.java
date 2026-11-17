/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;

import java.io.File;

/**
 * What is left of this app's first statistics code. The queue, the sessions and the upload are the cloud client's now
 * ({@code org.telegram.messenger.zenlygram.customer.ZenlyStats} / {@code ZenlyEventStore}; taxonomy v1, recorded only
 * for an account whose data consent is in effect). A screen names itself through {@code ZenlyStats.NamedScreen}; a
 * feature reports through the typed methods of {@code ZenlyStats} or {@link ZgStats}.
 *
 * <p>Kept for the callers outside this package: {@link #screen} forwards a screen of the registry to the client, and
 * {@link #onForeground} / {@link #onBackground} do nothing (the client follows the app's foreground state itself).
 * The JSONL queue files of the earlier code ({@code files/zenlygram-customer/events-<tg>.jsonl}) were never uploaded
 * and are deleted once at launch ({@link #deleteLegacyQueues}).
 */
public final class ZgAnalytics {

    /** Screen names of the registry (CustomerEvents.Screen wire names). */
    public static final String SCREEN_PRO = "zg.pro";
    public static final String SCREEN_DATA_CONSENT = "zg.data_consent";
    public static final String SCREEN_MY_DATA = "zg.my_data";
    public static final String SCREEN_ACCOUNT_DELETE = "zg.account_delete";

    private static final String LEGACY_DIRECTORY = "zenlygram-customer";

    private ZgAnalytics() {
    }

    /** Sessions are the cloud client's: nothing to do. */
    public static void onForeground() {
    }

    /** Sessions are the cloud client's: nothing to do. */
    public static void onBackground() {
    }

    /** Reports {@code screen} (a wire name of the registry; anything else is ignored) as shown on {@code account}. */
    public static void screen(int account, String screen) {
        ZenlyStats.screen(account, CustomerEvents.Screen.of(screen));
    }

    /** Removes the earlier code's queue files; off the main thread, harmless when there are none. */
    static void deleteLegacyQueues() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final Context context = ApplicationLoader.applicationContext;
                if (context == null) {
                    return;
                }
                final File directory = new File(context.getFilesDir(), LEGACY_DIRECTORY);
                if (!directory.isDirectory()) {
                    return;
                }
                final File[] files = directory.listFiles();
                boolean empty = true;
                if (files != null) {
                    for (File file : files) {
                        empty &= file.delete();
                    }
                }
                if (!empty || !directory.delete()) {
                    FileLog.e("zg analytics: the earlier queue directory was not removed");
                }
            } catch (Throwable t) {
                FileLog.e("zg analytics: the earlier queue was not removed (" + t.getClass().getSimpleName() + ")");
            }
        });
    }
}
