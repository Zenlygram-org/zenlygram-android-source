/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import org.telegram.ui.Components.IconBackgroundColors;

/**
 * Icon tiles of the Zenlygram settings rows.
 *
 * <p>iOS draws an SF Symbol on a flat colour square (SGSettingsIconColor). Android uses the gradient tiles of
 * Telegram's own Settings (SettingsActivity.SettingCell.Background) with a white msg_* glyph. The glyph chosen for
 * each iOS symbol is written next to the row in ZgSettingsSpec ({@code icon("eye.slash.fill", R.drawable.msg_archive_hide,
 * Tile.PURPLE)}).
 */
public final class ZgSettingsIcons {

    private ZgSettingsIcons() {
    }

    /** iOS SGSettingsIconColor mapped to a top/bottom gradient. */
    public enum Tile {
        RED(IconBackgroundColors.RED),
        GREEN(IconBackgroundColors.GREEN),
        BLUE(IconBackgroundColors.BLUE_DEEP),
        LIGHT_BLUE(IconBackgroundColors.BLUE_LIGHT),
        TEAL(IconBackgroundColors.CYAN),
        ORANGE(IconBackgroundColors.ORANGE),
        PURPLE(IconBackgroundColors.PURPLE),
        GRAY(IconBackgroundColors.GRAY),
        // iOS colours that Telegram's palette does not have
        PINK(0xFFFF5A7A, 0xFFE8325A),
        VIOLET(0xFF7A78F0, 0xFF5552DE),
        YELLOW(0xFFFFD43B, 0xFFF0B400),
        GOLD(0xFFE6C35C, 0xFFC49B25),
        // Brand tiles of the main Settings entries (sampled from the iOS ZenlygramPro / ZenlygramSettings images)
        BRAND_PRO(0xFF0A1A3F, 0xFF050B1E),
        BRAND_LOGO(0xFF141A24, 0xFF000000);

        public final int top;
        public final int bottom;

        Tile(IconBackgroundColors colors) {
            this(colors.top, colors.bottom);
        }

        Tile(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }
}
