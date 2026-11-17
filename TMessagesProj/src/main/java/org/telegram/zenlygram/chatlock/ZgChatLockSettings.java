/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * The "Chat Lock" row of Settings > Zenlygram > Hidden & locked chats (iOS SGSettingsController Chat Lock row).
 *
 * <ul>
 *   <li>Off: tap arms Chat Lock (Pro gate, then the master PIN: 4 digits by default, or a password).</li>
 *   <li>On: tap asks "Disable Chat Lock?", then the master pincode (or Reset Chat Lock with device-owner proof);
 *       every chat's lock goes, messages stay.</li>
 *   <li>Needs setup: the credentials can't be checked on this device any more: the recovery page.</li>
 * </ul>
 */
public final class ZgChatLockSettings {

    private ZgChatLockSettings() {
    }

    /** The value on the right of the row. */
    public static CharSequence getValue() {
        if (ZgChatLockStore.needsRecovery()) {
            return LocaleController.getString(R.string.ZG_ChatLock_NeedsSetup);
        }
        return LocaleController.getString(ZgChatLockStore.isEnabled() ? R.string.ZG_Settings_ChatLock_On : R.string.ZG_Settings_ChatLock_Off);
    }

    /** The row was tapped. {@code refresh} redraws the settings list after a change. */
    public static void onRowClick(BaseFragment fragment, @Nullable Runnable refresh) {
        if (ZgChatLockStore.needsRecovery()) {
            ZgChatLockFlows.startRecovery(fragment, refresh);
        } else if (ZgChatLockStore.isEnabled()) {
            ZgChatLockFlows.startDisable(fragment, refresh);
        } else {
            ZgChatLockFlows.startEnable(fragment, refresh);
        }
    }
}
