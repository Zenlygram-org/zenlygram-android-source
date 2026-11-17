/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.reminder;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.LaunchActivity;

/**
 * Posts the "Unread messages" reminder of one account when its alarm fires (see {@link ZgUnreadReminder}). Not
 * exported: only this app's own PendingIntent reaches it.
 */
public class ZgUnreadReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ZgUnreadReminder.ACTION.equals(intent.getAction())) {
            return;
        }
        try {
            ApplicationLoader.postInitApplication();
            final int account = intent.getIntExtra(ZgUnreadReminder.EXTRA_ACCOUNT, -1);
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !ZgUnreadReminder.isEnabled()
                || !UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            final ForegroundDetector detector = ForegroundDetector.getInstance();
            if (detector != null && detector.isForeground()) {
                return; // the user is in the app
            }
            int count = NotificationsController.getInstance(account).getTotalUnreadCount();
            if (count <= 0) {
                if (ZgUnreadReminder.scheduledHere[account]) {
                    return; // read in the meantime
                }
                // A new process: the counts are not loaded yet, so the count from scheduling time is used (as on iOS).
                count = intent.getIntExtra(ZgUnreadReminder.EXTRA_COUNT, 0);
            }
            ZgUnreadReminder.scheduledHere[account] = false;
            if (count <= 0) {
                return;
            }
            post(context, account, count);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void post(Context context, int account, int count) {
        ZgUnreadReminder.ensureChannel(context);

        final Intent open = new Intent(context, LaunchActivity.class);
        open.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE); // no chat id: opens the chat list
        open.putExtra("currentAccount", account);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent contentIntent = PendingIntent.getActivity(context, ZgUnreadReminder.ID_BASE + account, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, ZgUnreadReminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setColor(0xff11acfa)
            .setContentTitle(LocaleController.getString(R.string.ZG_ExtrasA_UnreadReminder_Title))
            .setContentText(LocaleController.formatPluralString("ZG_ExtrasA_UnreadReminder_Body", count))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent);
        if (activatedAccounts() > 1) {
            final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            if (user != null) {
                builder.setSubText(UserObject.getFirstName(user));
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(ZgUnreadReminder.ID_BASE + account, builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS was refused: nothing can be shown.
            FileLog.e(e);
        }
    }

    private static int activatedAccounts() {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                count++;
            }
        }
        return count;
    }
}
