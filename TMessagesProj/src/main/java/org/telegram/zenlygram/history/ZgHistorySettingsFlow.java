/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.settings.ZenlygramSettingsActivity;

/**
 * The two switches of Settings > Zenlygram (rows 501 Show Deleted Messages and 1301 Save Edit History), with the iOS
 * flow (SGSettingsController sgConfirmRetentionDisable): turning one on is immediate; turning it off counts what is
 * stored on every account, turns off at once when nothing is, and otherwise asks first. Cancel or back keeps it on
 * (the switch shows on until the user confirms). "Turn Off and Delete" turns it off, saves the purge timestamp,
 * deletes on every account and shows "Removed from this device". Never gated by Pro: these are the user's consent.
 */
public final class ZgHistorySettingsFlow {

    private static boolean counting;

    private ZgHistorySettingsFlow() {
    }

    public static void onShowDeletedMessagesClicked(ZenlygramSettingsActivity screen) {
        if (!ZgSettings.showDeletedMessages.get()) {
            ZgSettings.showDeletedMessages.set(true);
            org.telegram.zenlygram.customer.ZgStats.settingToggled(screen.getCurrentAccount(), "showDeletedMessages", true); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
            screen.refresh();
            return;
        }
        if (counting) {
            return;
        }
        counting = true;
        ZgKeptDeleted.countAll(count -> {
            counting = false;
            if (!isAlive(screen)) {
                return;
            }
            if (count == null || count <= 0) {
                turnOffShowDeleted(screen, false);
                return;
            }
            confirm(screen, getString(R.string.ZG_Settings_ShowDeletedMessages_Disable_Title),
                formatString(R.string.ZG_Settings_ShowDeletedMessages_Disable_Text, String.valueOf(count)),
                getString(R.string.ZG_Settings_ShowDeletedMessages_Disable_Action),
                () -> turnOffShowDeleted(screen, true));
        });
    }

    public static void onSaveEditHistoryClicked(ZenlygramSettingsActivity screen) {
        if (!ZgSettings.editHistoryEnabled.get()) {
            ZgSettings.editHistoryEnabled.set(true);
            org.telegram.zenlygram.customer.ZgStats.settingToggled(screen.getCurrentAccount(), "editHistoryEnabled", true); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
            screen.refresh();
            return;
        }
        if (counting) {
            return;
        }
        counting = true;
        ZgEditHistory.countAll(count -> {
            counting = false;
            if (!isAlive(screen)) {
                return;
            }
            if (count == null || count <= 0) {
                turnOffEditHistory(screen, false);
                return;
            }
            confirm(screen, getString(R.string.ZG_Settings_EditHistory_Disable_Title),
                formatString(R.string.ZG_Settings_EditHistory_Disable_Text, String.valueOf(count)),
                getString(R.string.ZG_Settings_EditHistory_Disable_Action),
                () -> turnOffEditHistory(screen, true));
        });
    }

    private static void turnOffShowDeleted(ZenlygramSettingsActivity screen, boolean announce) {
        ZgSettings.showDeletedMessages.set(false);
        org.telegram.zenlygram.customer.ZgStats.settingToggled(screen.getCurrentAccount(), "showDeletedMessages", false); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
        // Persisted before anything is deleted: an interrupted purge is finished by the next sweep.
        final int cutoff = ZgHistorySettings.requestKeptDeletedPurge();
        flushSoon();
        screen.refresh();
        ZgKeptDeleted.purgeAll(cutoff, announce ? () -> announceRemoved(screen) : null);
    }

    private static void turnOffEditHistory(ZenlygramSettingsActivity screen, boolean announce) {
        ZgSettings.editHistoryEnabled.set(false);
        org.telegram.zenlygram.customer.ZgStats.settingToggled(screen.getCurrentAccount(), "editHistoryEnabled", false); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
        final int cutoff = ZgHistorySettings.requestEditHistoryPurge();
        flushSoon();
        screen.refresh();
        ZgEditHistory.purgeAll(cutoff, announce ? () -> announceRemoved(screen) : null);
    }

    private static void announceRemoved(ZenlygramSettingsActivity screen) {
        if (!isAlive(screen)) {
            return;
        }
        BulletinFactory.of(screen).createSimpleBulletin(R.raw.done, getString(R.string.ZG_Settings_Retention_Removed)).show();
        screen.refresh();
    }

    private static void confirm(ZenlygramSettingsActivity screen, String title, String text, String action, Runnable onConfirm) {
        final Activity activity = screen.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, screen.getResourceProvider());
        builder.setTitle(title);
        builder.setMessage(text);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(action, (dialog, which) -> onConfirm.run());
        builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        screen.showDialog(dialog);
    }

    private static boolean isAlive(ZenlygramSettingsActivity screen) {
        return screen != null && !screen.isFinished && screen.getParentActivity() != null;
    }

    /** Writes the purge timestamp to disk now (off the UI thread), so a crash right after cannot lose it. */
    private static void flushSoon() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ZgSettings.flush();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}
