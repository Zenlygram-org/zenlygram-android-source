/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.reminder;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Unread Message Reminder (iOS SGUnreadReminderManager): when the app goes to the background while an account still
 * has unread messages, one local notification per account says so after {@code unreadReminderMinutes} (5).
 * Coming back to the app, or the count reaching 0, cancels it.
 *
 * <ul>
 *   <li>Counts are NotificationsController's (the messages that would notify: muted chats are not counted), for the
 *       accounts whose notifications are shown (Settings > Notifications > "Show notifications from all accounts",
 *       otherwise only the active one).</li>
 *   <li>The alarm is inexact (AlarmManager.setAndAllowWhileIdle), so Doze may delay it; no exact-alarm permission.</li>
 *   <li>The notification shows only the count, never a sender or text, and opens the app through LaunchActivity,
 *       so the passcode still applies.</li>
 *   <li>Request codes and notification ids are 0x5A470000 + account, so a logged-out slot's alarm can always be
 *       cancelled, also at launch.</li>
 * </ul>
 */
public final class ZgUnreadReminder {

    public static final String CHANNEL_ID = "zg_unread_reminder";
    static final int ID_BASE = 0x5A470000;
    static final String ACTION = "org.telegram.zenlygram.reminder.UNREAD_REMINDER";
    static final String EXTRA_ACCOUNT = "zg_account";
    static final String EXTRA_COUNT = "zg_count";

    /** Alarms scheduled by this process; a reminder of an earlier process can only trust the count it carries. */
    static final boolean[] scheduledHere = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private static boolean initialized;

    private static final ForegroundDetector.Listener foregroundListener = new ForegroundDetector.Listener() {
        @Override
        public void onBecameForeground() {
            cancelAll(true);
        }

        @Override
        public void onBecameBackground() {
            scheduleAll();
        }
    };

    private static final NotificationCenter.NotificationCenterDelegate countObserver = (id, account, args) -> {
        if (id != NotificationCenter.notificationsCountUpdated || args.length < 1 || !(args[0] instanceof Integer) || !isEnabled()) {
            return;
        }
        final int a = (Integer) args[0];
        if (a >= 0 && a < UserConfig.MAX_ACCOUNT_COUNT && NotificationsController.getInstance(a).getTotalUnreadCount() <= 0) {
            cancel(a, true); // everything read (here or on another device)
        }
    };

    private ZgUnreadReminder() {
    }

    public static boolean isEnabled() {
        return ZgFeatures.UNREAD_REMINDER.isPorted() && ZgSettings.unreadReminderEnabled.get();
    }

    /** ApplicationLoader.postInitApplication (UI thread). */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        final ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector != null) {
            detector.addListener(foregroundListener);
        }
        NotificationCenter.getGlobalInstance().addObserver(countObserver, NotificationCenter.notificationsCountUpdated);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                cancel(a, true); // left behind by an account that logged out while the process died
            }
        }
        if (!isEnabled()) {
            cancelAll(false);
        } else if (detector != null && detector.isForeground()) {
            cancelAll(true);
        }
    }

    /** The settings switch changed (Settings > Zenlygram > Tools). */
    public static void onSettingChanged(BaseFragment fragment, boolean enabled) {
        if (!enabled) {
            cancelAll(true);
            return;
        }
        ensureNotificationsAllowed(fragment);
    }

    public static void onAccountLoggedOut(int account) {
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
            cancel(account, true);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void scheduleAll() {
        if (!isEnabled()) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated() || !SharedConfig.showNotificationsForAllAccounts && a != UserConfig.selectedAccount) {
                cancel(a, false);
                continue;
            }
            final int count = NotificationsController.getInstance(a).getTotalUnreadCount();
            if (count > 0) {
                schedule(a, count);
            } else {
                cancel(a, false);
            }
        }
    }

    private static void schedule(int account, int count) {
        final Context context = ApplicationLoader.applicationContext;
        try {
            final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, ID_BASE + account, alarmIntent(context, account, count),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final long at = SystemClock.elapsedRealtime() + Math.max(1, ZgSettings.unreadReminderMinutes.get()) * 60_000L;
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent);
            scheduledHere[account] = true;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    static void cancelAll(boolean notificationsToo) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            cancel(a, notificationsToo);
        }
    }

    /** Cancels the pending alarm of {@code account} and, if asked, a reminder that is already shown. */
    static void cancel(int account, boolean notificationToo) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        scheduledHere[account] = false;
        try {
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, ID_BASE + account, alarmIntent(context, account, 0),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pendingIntent != null) {
                final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    alarmManager.cancel(pendingIntent);
                }
                pendingIntent.cancel();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (notificationToo) {
            try {
                NotificationManagerCompat.from(context).cancel(ID_BASE + account);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private static Intent alarmIntent(Context context, int account, int count) {
        final Intent intent = new Intent(context, ZgUnreadReminderReceiver.class);
        intent.setAction(ACTION);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_COUNT, count);
        return intent;
    }

    /** Creates the reminder's notification channel (API 26+); its name follows the app language. */
    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            final String name = LocaleController.getString(R.string.ZG_Settings_UnreadReminder);
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            } else if (!name.contentEquals(channel.getName())) {
                channel.setName(name);
                manager.createNotificationChannel(channel);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Turning the reminder on asks for notification permission (Android 13+) right away, like iOS does. Without it the
     * switch stays on and a bulletin explains why nothing will show, with a way to the system settings.
     */
    private static void ensureNotificationsAllowed(BaseFragment fragment) {
        final Context context = ApplicationLoader.applicationContext;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> {
                if (!granted) {
                    AndroidUtilities.runOnUIThread(() -> showNotificationsOff(fragment));
                }
            });
            return;
        }
        boolean enabled;
        try {
            enabled = NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Exception e) {
            enabled = true;
        }
        if (!enabled) {
            showNotificationsOff(fragment);
        }
    }

    private static void showNotificationsOff(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
            LocaleController.getString(R.string.ZG_ExtrasA_UnreadReminder_NotificationsOff),
            LocaleController.getString(R.string.Settings),
            () -> PermissionRequest.showPermissionSettings(Manifest.permission.POST_NOTIFICATIONS)).show();
    }
}
