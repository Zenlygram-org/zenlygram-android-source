/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlist;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.SwipeGestureSettingsView;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

/**
 * Chat List Swipe Options and Swipe to Delete Chat (iOS disableChatSwipeOptions / disableDeleteChatSwipeOption).
 *
 * <p>Telegram Android swipes a chat with ONE action picked in Chat Settings (pin, read, archive, mute, delete or
 * folders), not with the iOS button set, so:
 * <ul>
 *   <li>Swipe options off: no chat-list swipe at all (chats, the archive cell, PSA hide, the General topic), and a
 *       horizontal swipe anywhere on the list switches folders, as on iOS.</li>
 *   <li>Swipe to Delete Chat off (offered only while Telegram's swipe action is Delete): that swipe does nothing,
 *       and neither does the PSA hide swipe. The archive cell keeps its hide swipe.</li>
 * </ul>
 * The checks run at the start of every gesture, so a change applies at once. Nothing reaches the server.
 */
public final class ZgChatListSwipe {

    private ZgChatListSwipe() {
    }

    private static boolean swipesOff() {
        return ZgFeatures.CHAT_SWIPE_OPTIONS.isPorted() && ZgSettings.disableChatSwipeOptions.get();
    }

    /** Whether a chat-list row of DialogsActivity must not start a swipe (DialogsActivity.SwipeController). */
    public static boolean blocksDialogSwipe(int account, long dialogId, boolean isPromo) {
        if (!ZgFeatures.CHAT_SWIPE_OPTIONS.isPorted()) {
            return false;
        }
        if (ZgSettings.disableChatSwipeOptions.get()) {
            return true;
        }
        if (ZgSettings.disableDeleteChatSwipeOption.get() && isDeleteSwipeAction(account)) {
            // The archive cell's swipe hides the archive, whatever the action; a PSA promo's swipe hides it.
            return isPromo || !DialogObject.isFolderDialogId(dialogId);
        }
        return false;
    }

    /** Whether a horizontal swipe anywhere on the chat list switches folder tabs (iOS edgeWidth 0). */
    public static boolean swipeSwitchesFolders() {
        return swipesOff();
    }

    /** Whether the General topic's hide / show swipe of a forum's topic list is off (TopicsFragment). */
    public static boolean blocksTopicSwipe() {
        return swipesOff();
    }

    /** Telegram's chat-list swipe action (Chat Settings) is Delete; Swipe to Delete Chat is offered only then. */
    public static boolean isDeleteSwipeAction(int account) {
        return SharedConfig.getChatSwipeAction(account) == SwipeGestureSettingsView.SWIPE_GESTURE_DELETE;
    }
}
