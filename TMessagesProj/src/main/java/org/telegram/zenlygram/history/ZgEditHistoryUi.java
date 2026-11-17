/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import android.view.View;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** Entry points to the Edit History screen from a chat. */
public final class ZgEditHistoryUi {

    private ZgEditHistoryUi() {
    }

    /**
     * Opens Edit History for the message (or the album item that has history: long-pressed item, caption item, then
     * the others). Returns false when nothing has a version to show.
     */
    public static boolean open(BaseFragment fragment, MessageObject messageObject, MessageObject.GroupedMessages group) {
        try {
            if (fragment == null) {
                return false;
            }
            final MessageObject target = ZgEditHistory.findWithHistory(messageObject, group);
            if (target == null) {
                return false;
            }
            return fragment.presentFragment(new ZgEditHistoryActivity(target.currentAccount, target.getDialogId(), target.getId()));
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * ChatActivity.createMenu: the native "edited <date>" row at the top of the message menu also opens Edit History
     * when the message has saved versions (Android addition; the row has no click of its own).
     */
    public static void bindEditedRow(BaseFragment fragment, View row, MessageObject messageObject, MessageObject.GroupedMessages group, Runnable closeMenu) {
        try {
            if (fragment == null || row == null || ZgEditHistory.findWithHistory(messageObject, group) == null) {
                return;
            }
            row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, fragment.getResourceProvider()), 6, 0));
            row.setOnClickListener(v -> {
                open(fragment, messageObject, group);
                if (closeMenu != null) {
                    closeMenu.run();
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
