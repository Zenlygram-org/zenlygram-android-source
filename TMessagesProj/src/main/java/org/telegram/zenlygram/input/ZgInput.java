/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.input;

import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Switches of the chat input bar (Settings > Zenlygram > Composing &amp; input), read by the ZENLYGRAM hooks in
 * ChatActivityEnterView.
 *
 * <p>Each switch is on only while its feature is ported and the stored value asks for it, so a value written by a
 * build where the feature was visible never changes behaviour once the feature is hidden. All reads are cached
 * preference values (no allocation), so the hooks are safe in layout and animation code.
 *
 * <p>"Send with Enter key" is not here: Telegram Android has that option ("send_by_enter" in mainconfig), and the
 * Zenlygram row edits Telegram's value directly (ZgRow.telegramGlobalBool).
 */
public final class ZgInput {

    private ZgInput() {
    }

    /** SEND_AS_BUTTON: hide the "Send message as..." avatar left of the text field. The chosen identity still sends. */
    public static boolean hideSendAsButton() {
        return ZgFeatures.SEND_AS_BUTTON.isPorted() && ZgSettings.disableSendAsButton.get();
    }

    /** RECORDING_BUTTON: hide the voice / round-video button; the Send button takes its place. */
    public static boolean hideRecordingButton() {
        return ZgFeatures.RECORDING_BUTTON.isPorted() && ZgSettings.hideRecordingButton.get();
    }

    /** FORCE_EMOJI_TAB: the emoji / sticker panel always opens on the Emoji tab. */
    public static boolean forceEmojiTab() {
        return ZgFeatures.FORCE_EMOJI_TAB.isPorted() && ZgSettings.forceEmojiTab.get();
    }
}
