/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * Tells a Tasks screen when "today" may have changed (iOS TasksTimeContextObserver): the date changed at midnight, the
 * clock was set, or the time zone changed. Registered only while the screen is resumed; no timer, no background work.
 * The screen then re-derives "Today / Tomorrow", the calendar's today and the shortcuts, without touching drafts.
 */
final class ZgTasksTimeObserver {

    private final Runnable changed;
    private BroadcastReceiver receiver;
    private Context registeredContext;

    ZgTasksTimeObserver(Runnable changed) {
        this.changed = changed;
    }

    void start(Context context) {
        if (receiver != null || context == null) {
            return;
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                AndroidUtilities.runOnUIThread(changed);
            }
        };
        registeredContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            ContextCompat.registerReceiver(registeredContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            FileLog.e(e);
            receiver = null;
            registeredContext = null;
        }
    }

    void stop() {
        if (receiver == null) {
            return;
        }
        try {
            registeredContext.unregisterReceiver(receiver);
        } catch (Exception e) {
            FileLog.e(e);
        }
        receiver = null;
        registeredContext = null;
    }
}
