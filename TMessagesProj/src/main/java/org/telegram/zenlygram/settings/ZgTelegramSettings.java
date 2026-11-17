/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import android.content.SharedPreferences;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_account;

/**
 * Rows of Settings > Zenlygram that show and change a setting Telegram Android already has, instead of a Zenlygram
 * preference (iOS keeps these in Telegram's own stores too). One value, two places to change it: nothing to sync.
 */
public final class ZgTelegramSettings {

    /**
     * Telegram's "Force TCP" for private calls (mainconfig of account 0, read by VoIPService at the start of every
     * call; also in the hidden call debug menu). All reflectors then use TCP relays. Group calls ignore it, as on iOS.
     */
    public static final String KEY_FORCE_TCP_IN_CALLS = "dbg_force_tcp_in_calls";

    private ZgTelegramSettings() {
    }

    /** Calls > Force TCP (iOS ExperimentalUISettings.enableVoipTcp). */
    static ZgRow.BoolValue forceTcpInCalls() {
        return new ZgRow.BoolValue() {
            @Override
            public boolean get() {
                return isForceTcpInCalls();
            }

            @Override
            public void set(boolean value) {
                final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                preferences.edit().putBoolean(KEY_FORCE_TCP_IN_CALLS, value).apply();
            }
        };
    }

    public static boolean isForceTcpInCalls() {
        try {
            return MessagesController.getGlobalMainSettings().getBoolean(KEY_FORCE_TCP_IN_CALLS, false);
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * Advanced > Content Settings opens Telegram's own "Show 18+ Content" switch (Chat Settings), with Telegram's
     * confirmation and age check. Offered only while the server lets this account change it, like Telegram's own row.
     */
    static boolean canChangeSensitiveContent(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        final TL_account.contentSettings settings = MessagesController.getInstance(account).getContentSettings();
        return settings != null && settings.sensitive_can_change;
    }

    /** "On" / "Off": the current "Show 18+ Content" value of {@code account}. */
    static CharSequence sensitiveContentValue(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        return LocaleController.getString(MessagesController.getInstance(account).showSensitiveContent() ? R.string.NotificationsOn : R.string.NotificationsOff);
    }
}
