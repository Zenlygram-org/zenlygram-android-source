/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.icons;

import org.telegram.ui.LauncherIconController;

/**
 * Launcher icon switches.
 *
 * <p>{@link LauncherIconController.LauncherIcon} lists the Zenlygram icon (DEFAULT, the iOS ZSignature art) and the iOS
 * alternate pack (Telegram/Telegram-iOS/Z*.alticon, converted by Tools/zenlygram/icons/make_alt_icons.sh), each with a
 * disabled activity-alias in AndroidManifest.xml. Telegram's own alternates (Vintage, Aqua, Premium, Turbo, Nox) show its
 * paper plane: they are not in the enum and their aliases stay disabled. Like on iOS, no Zenlygram icon is
 * Premium-locked, so Telegram Premium's "Additional App Icons" perk stays hidden ({@link #hasPremiumIcons()}).
 */
public final class ZgAppIcons {

    private ZgAppIcons() {
    }

    /** The App Icon picker in Settings &gt; Appearance (ThemeActivity) is shown when there is something to choose. */
    public static boolean isPickerAvailable() {
        return LauncherIconController.LauncherIcon.values().length > 1;
    }

    /** Telegram Premium's app icon perk needs at least 3 premium icons (PremiumAppIconsPreviewView). */
    public static boolean hasPremiumIcons() {
        int count = 0;
        for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
            if (icon.premium) {
                count++;
            }
        }
        return count >= 3;
    }
}
