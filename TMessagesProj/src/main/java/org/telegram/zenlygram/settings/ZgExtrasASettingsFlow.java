/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import org.telegram.zenlygram.autoaccept.ZgAutoAccept;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.dialogs.ZgForeignNumbers;
import org.telegram.zenlygram.download.ZgAutoDownload;
import org.telegram.zenlygram.reminder.ZgUnreadReminder;

/**
 * What the switches of Hide Foreign Numbers, Auto-Accept Requests, Disable Auto-Download and Unread Message Reminder
 * do besides storing their ZgSettings key (rows 301, 303, 1801, 2403 of {@link ZgSettingsSpec}).
 */
final class ZgExtrasASettingsFlow {

    private ZgExtrasASettingsFlow() {
    }

    /** Row 301: applied live, the chat lists re-read their dialogs (iOS needs a restart). */
    static ZgRow.BoolValue foreignNumbers() {
        return new ZgRow.BoolValue() {
            @Override
            public boolean get() {
                return ZgSettings.blockForeignUsers.get();
            }

            @Override
            public void set(boolean value) {
                ZgSettings.blockForeignUsers.set(value);
                ZgForeignNumbers.onSettingChanged();
            }
        };
    }

    /** Row 303: starts or stops the approvals (a first sweep runs right away). */
    static ZgRow.BoolValue autoAccept() {
        return new ZgRow.BoolValue() {
            @Override
            public boolean get() {
                return ZgSettings.autoAcceptRequests.get();
            }

            @Override
            public void set(boolean value) {
                ZgSettings.autoAcceptRequests.set(value);
                ZgAutoAccept.onSettingChanged(value);
            }
        };
    }

    /** Row 1801: Telegram's auto-download switches of every account; reads as off once the user turns one back on. */
    static ZgRow.BoolValue autoDownload() {
        return new ZgRow.BoolValue() {
            @Override
            public boolean get() {
                return ZgAutoDownload.isDisabled();
            }

            @Override
            public void set(boolean value) {
                ZgAutoDownload.setDisabled(value);
            }
        };
    }

    /** Row 2403: turning it on asks for notification permission (Android 13+). */
    static void toggleUnreadReminder(ZenlygramSettingsActivity screen) {
        final boolean enabled = ZgSettings.unreadReminderEnabled.toggle();
        org.telegram.zenlygram.customer.ZgStats.settingToggled(screen.getCurrentAccount(), "unreadReminderEnabled", enabled); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
        ZgUnreadReminder.onSettingChanged(screen, enabled);
    }
}
