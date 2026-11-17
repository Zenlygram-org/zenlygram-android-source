/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.extras;

import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Double tap on your own message (Swiftgram messageDoubleTapActionOutgoing, Settings > Zenlygram > Composing &
 * input > Double-tap to edit message).
 *
 * <p>ChatActivity asks these two questions in its double-tap listener. "edit" opens the editor for an own message
 * that can be edited (the same conditions as the Edit item of the message menu) and does nothing for one that can
 * not; "none" turns the double tap off for own messages; "default" keeps Telegram's quick reaction. Incoming
 * messages always keep the reaction.
 */
public final class ZgDoubleTapEdit {

    private ZgDoubleTapEdit() {
    }

    /** A double tap on an own message edits it. */
    public static boolean isEditMode() {
        return ZgFeatures.DOUBLE_TAP_TO_EDIT.isPorted()
            && ZgSettings.messageDoubleTapActionOutgoing.is(ZgSettings.DoubleTapAction.EDIT);
    }

    /** A double tap on an own message does nothing (iOS "none"; no switch sets it on Android). */
    public static boolean isNoneMode() {
        return ZgFeatures.DOUBLE_TAP_TO_EDIT.isPorted()
            && ZgSettings.messageDoubleTapActionOutgoing.is(ZgSettings.DoubleTapAction.NONE);
    }

    /** Whether the Zenlygram rule decides the double tap on this message instead of Telegram's reaction. */
    public static boolean overridesOwnMessages() {
        return isEditMode() || isNoneMode();
    }
}
