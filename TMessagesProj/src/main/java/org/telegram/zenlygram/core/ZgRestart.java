/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.core;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

/**
 * "Restart required" for settings that only take effect after a relaunch (iOS: an UndoOverlay toast whose button
 * calls exit(0)).
 */
public final class ZgRestart {

    private ZgRestart() {
    }

    /** Shows "Restart required" with a "Restart Now" button over {@code fragment}. */
    public static void showRestartBulletin(BaseFragment fragment) {
        if (fragment == null || !BulletinFactory.canShowBulletin(fragment)) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(
                R.raw.info,
                getString(R.string.ZG_Common_RestartRequired),
                getString(R.string.ZG_Common_RestartNow),
                Bulletin.DURATION_PROLONG,
                () -> restartApp(fragment.getParentActivity())
        ).show();
    }

    /**
     * Relaunches the app: writes pending Zenlygram settings to disk, finishes every activity, starts the launcher
     * activity and ends the process (same pattern as DebugSettingsActivity's tablet-mode switch).
     */
    public static void restartApp(Activity activity) {
        try {
            ZgSettings.flush();
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (activity != null) {
            final Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            activity.finishAffinity();
            if (intent != null) {
                activity.startActivity(intent);
            }
        }
        System.exit(0);
    }
}
