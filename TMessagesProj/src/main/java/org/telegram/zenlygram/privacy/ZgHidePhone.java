/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.privacy;

import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Hide Phone in Settings (iOS hidePhoneInSettings): your own number is not shown in the Settings header (only
 * @username stays), in My Profile, and as the title of the My Account row (it reads "Phone number" there). UI only:
 * Telegram's Privacy and Security still decides what other people see, as the notice under the switch says.
 *
 * <p>Hooks (marked ZENLYGRAM): SettingsActivity.setInfo (header subtitle), ProfileActivity.updateRowsIds (My Profile
 * phone row and the legacy settings number row), UserInfoActivity.fillItems (My Account row title). Change Number,
 * the "Is this still your number?" suggestion and other people's numbers are unchanged, as on iOS. The Settings
 * header follows after a restart (row 401 asks for one, like iOS); My Profile and My Account on their next rebuild.
 */
public final class ZgHidePhone {

    private ZgHidePhone() {
    }

    public static boolean inSettings() {
        return ZgFeatures.HIDE_PHONE_IN_SETTINGS.isPorted() && ZgSettings.hidePhoneInSettings.get();
    }
}
