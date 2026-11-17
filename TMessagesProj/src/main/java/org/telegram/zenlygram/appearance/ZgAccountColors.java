/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.appearance;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.SparseIntArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.ArrayList;

/**
 * Account colours saturation (iOS SGSimpleSettings accountColorsSaturation, PeerNameColors.Colors).
 *
 * <ul>
 *   <li>1-99 %: every name / reply / link-preview / profile colour is paler: HSV saturation times the value, hue,
 *       brightness and alpha kept (iOS withReducedSaturation). Telegram reads them in two central places, both hooked:
 *       Theme.getColor(key, provider) for the seven base name colours ({@link #themeColor}) and the PeerColor getters
 *       for server palettes and profile colours ({@link #adjust}); collectible colours come from ReplyMessageLine.</li>
 *   <li>0 %: account colours are off. Names, replies and link previews use Telegram's default colour (the chat call
 *       sites ask {@link #isOff()}, iOS sets peer.nameColor = nil); like iOS, the colour picker and profile colours
 *       keep their full colours.</li>
 *   <li>100 % (default): nothing changes.</li>
 * </ul>
 * Theme.getColor is very hot: {@link #themeColor} costs two int comparisons for every other key, and adjusted
 * colours are cached, so drawing stays allocation-free.
 */
public final class ZgAccountColors {

    /** Delay before the open chats are told to redraw: the slider calls back on every step while it is dragged. */
    private static final long REFRESH_DELAY_MS = 250;
    /** There are only a few dozen palette colours; the bound keeps a stray caller from growing the cache. */
    private static final int MAX_CACHED_COLORS = 512;
    /** iOS PeerNameColors default displayOrder starts with blue, green, orange. */
    private static final int[] DEFAULT_PREVIEW_IDS = {5, 3, 1};

    private static final Object lock = new Object();
    private static final SparseIntArray adjustedColors = new SparseIntArray();
    /** The adjusted colours handed out at the current saturation (see {@link #themeColor}). */
    private static final SparseIntArray adjustedOutputs = new SparseIntArray();
    private static final float[] hsv = new float[3];
    private static int cachedSaturation = -1;
    /** -1 unknown, 1 the seven name keys are consecutive (Theme declares them with colorsCount++), 0 they are not. */
    private static volatile int nameKeysConsecutive = -1;

    private static Runnable pendingRefresh;

    private ZgAccountColors() {
    }

    /** 0 %: account colours are turned off. */
    public static boolean isOff() {
        return ZgSettings.accountColorsSaturation.get() <= 0;
    }

    /** {@code color} with the saturation applied; unchanged at 0 % and 100 %. */
    public static int adjust(int color) {
        final int saturation = ZgSettings.accountColorsSaturation.get();
        if (saturation <= 0 || saturation >= 100) {
            return color;
        }
        synchronized (lock) {
            return adjustLocked(color, saturation);
        }
    }

    private static int adjustLocked(int color, int saturation) {
        if (cachedSaturation != saturation) {
            adjustedColors.clear();
            adjustedOutputs.clear();
            cachedSaturation = saturation;
        }
        final int index = adjustedColors.indexOfKey(color);
        if (index >= 0) {
            return adjustedColors.valueAt(index);
        }
        Color.colorToHSV(color, hsv);
        hsv[1] = hsv[1] * saturation / 100f;
        final int adjusted = Color.HSVToColor(Color.alpha(color), hsv);
        if (adjustedColors.size() >= MAX_CACHED_COLORS) {
            adjustedColors.clear();
            adjustedOutputs.clear();
        }
        adjustedColors.put(color, adjusted);
        adjustedOutputs.put(adjusted, 1);
        return adjusted;
    }

    /**
     * Theme.getColor(key, provider): adjusts only the seven base name colours (Theme.keys_avatar_nameInMessage), after
     * the theme, chat theme or compact mode has picked them. Some providers resolve a key through Theme.getColor(key,
     * provider) again (BaseFragment / UniversalAdapter / AlertDialog ThemedColors), so a colour this class already
     * handed out is passed through unchanged instead of being made paler twice.
     */
    public static int themeColor(int key, int color) {
        if (key < Theme.key_avatar_nameInMessageRed || key > Theme.key_avatar_nameInMessagePink) {
            return color;
        }
        if (nameKeysConsecutive != 1 && !isNameKey(key)) {
            return color;
        }
        return adjustThemeColor(color);
    }

    /** {@link #adjust}, except that a colour already handed out at this saturation is returned unchanged. */
    static int adjustThemeColor(int color) {
        final int saturation = ZgSettings.accountColorsSaturation.get();
        if (saturation <= 0 || saturation >= 100) {
            return color;
        }
        synchronized (lock) {
            if (cachedSaturation == saturation && adjustedOutputs.indexOfKey(color) >= 0 && adjustedColors.indexOfKey(color) < 0) {
                return color;
            }
            return adjustLocked(color, saturation);
        }
    }

    private static boolean isNameKey(int key) {
        final int[] keys = Theme.keys_avatar_nameInMessage;
        if (keys == null) {
            return false;
        }
        if (nameKeysConsecutive == -1) {
            boolean consecutive = keys.length == 7;
            for (int i = 0; consecutive && i < keys.length; i++) {
                consecutive = keys[i] == Theme.key_avatar_nameInMessageRed + i;
            }
            nameKeysConsecutive = consecutive && Theme.key_avatar_nameInMessagePink == Theme.key_avatar_nameInMessageRed + 6 ? 1 : 0;
            if (nameKeysConsecutive == 1) {
                return true;
            }
        }
        for (int k : keys) {
            if (k == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * The slider moved (called on every step while it is dragged). After it rests for a moment, {@code refreshScreen}
     * rebuilds the settings list (previews) and every logged-in account redraws its names (open chats rebind their
     * visible messages through updateInterfaces).
     */
    public static void onSaturationChanged(Runnable refreshScreen) {
        if (pendingRefresh != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingRefresh);
        }
        pendingRefresh = () -> {
            pendingRefresh = null;
            if (refreshScreen != null) {
                refreshScreen.run();
            }
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                }
            }
        };
        AndroidUtilities.runOnUIThread(pendingRefresh, REFRESH_DELAY_MS);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Settings previews (iOS peerColorDisclosurePreview rows)
    // ---------------------------------------------------------------------------------------------------------

    /** How many preview rows are shown: one at 0 % (the default colour), three otherwise. */
    public static int previewCount() {
        return isOff() ? 1 : DEFAULT_PREVIEW_IDS.length;
    }

    /**
     * "First name Last name" in the colour of preview {@code index}: at 0 % Telegram's default reply name colour,
     * otherwise the first three name colours in the server's display order (iOS nameColors.displayOrder), with the
     * saturation applied. Null when {@code index} is not shown.
     */
    public static CharSequence preview(int index) {
        if (index < 0 || index >= previewCount()) {
            return null;
        }
        final String text = LocaleController.getString(R.string.FirstNameSmall) + " " + LocaleController.getString(R.string.LastNameSmall);
        final SpannableString spannable = new SpannableString(text);
        spannable.setSpan(new ForegroundColorSpan(previewColor(index)), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    private static int previewColor(int index) {
        if (isOff()) {
            return Theme.getColor(Theme.key_chat_inReplyNameText, (Theme.ResourcesProvider) null);
        }
        final MessagesController.PeerColors peerColors = MessagesController.getInstance(UserConfig.selectedAccount).peerColors;
        if (peerColors != null) {
            final ArrayList<MessagesController.PeerColor> colors = peerColors.colors;
            int shown = 0;
            for (int i = 0; i < colors.size(); i++) {
                final MessagesController.PeerColor color = colors.get(i);
                if (color == null || color.hidden) {
                    continue;
                }
                if (shown == index) {
                    return color.getColor(0, null); // adjusted by the PeerColor / Theme hooks
                }
                shown++;
            }
        }
        final int id = DEFAULT_PREVIEW_IDS[index];
        return Theme.getColor(Theme.keys_avatar_nameInMessage[id], (Theme.ResourcesProvider) null);
    }
}
