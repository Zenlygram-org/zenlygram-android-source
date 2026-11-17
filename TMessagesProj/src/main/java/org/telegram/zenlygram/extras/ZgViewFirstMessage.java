/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.extras;

import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.zenlygram.chatlock.ZgChatLockGate;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.List;

/**
 * "View First Message" in the profile menu (iOS showViewFirstMessage, Settings > Zenlygram > Profiles >
 * Jump to First Message).
 *
 * <p>ProfileActivity adds the menu item when {@link #isOffered} says so and calls {@link #open} on a tap. The chat
 * opens and jumps to its oldest message with ChatActivity.jumpToDate(1) (offset_date 1 = the start of the
 * history), the same request Telegram's own calendar jump uses. Nothing is sent to anyone.
 */
public final class ZgViewFirstMessage {

    /** ProfileActivity menu id. Upstream ids end at 48; far away from them and from other Zenlygram items. */
    public static final int MENU_ID = 5201;

    private static final int POLL_MS = 200;
    private static final int MAX_POLLS = 25; // 5 s, like the iOS wait for the chat controller
    private static final int SETTLE_MS = 600; // iOS navigates after 0.6 s
    private static final int MAX_COVERED_POLLS = 1500; // 5 min behind the Chat Lock cover (the PIN is being typed)

    private ZgViewFirstMessage() {
    }

    /**
     * Whether the profile menu offers the item: the switch is on, and the profile is a user, bot, group or channel
     * other than yourself, not a secret chat and not a forum topic or forum (a forum opens as a topic list).
     */
    public static boolean isOffered(int account, long userId, long chatId, boolean secretChat, long topicId) {
        if (!ZgFeatures.VIEW_FIRST_MESSAGE.isPorted() || !ZgSettings.showViewFirstMessage.get() || secretChat || topicId != 0) {
            return false;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        if (userId != 0) {
            final TLRPC.User user = controller.getUser(userId);
            return user != null && !UserObject.isUserSelf(user);
        }
        if (chatId != 0) {
            final TLRPC.Chat chat = controller.getChat(chatId);
            return chat != null && !ChatObject.isForum(chat) && !ChatObject.isMonoForum(chat);
        }
        return false;
    }

    /** Opens the chat of {@code dialogId} (or goes back to it when the profile was opened from it) and jumps to the start. */
    public static void open(BaseFragment profile, long dialogId) {
        if (profile == null || profile.getParentActivity() == null || dialogId == 0) {
            return;
        }
        final INavigationLayout layout = profile.getParentLayout();
        if (layout != null && layout.getFragmentStack() != null) {
            final List<BaseFragment> stack = layout.getFragmentStack();
            final int index = stack.indexOf(profile);
            if (index > 0 && stack.get(index - 1) instanceof ChatActivity) {
                final ChatActivity below = (ChatActivity) stack.get(index - 1);
                if (below.getDialogId() == dialogId && below.getChatMode() == 0 && !below.isThreadChat()) {
                    profile.finishFragment();
                    jumpWhenReady(below, 0);
                    return;
                }
            }
        }
        final Bundle args = new Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        if (!profile.getMessagesController().checkCanOpenChat(args, profile)) {
            return;
        }
        final ChatActivity chat = new ChatActivity(args);
        if (profile.presentFragment(chat)) {
            jumpWhenReady(chat, 0);
        }
    }

    private static void jumpWhenReady(ChatActivity chat, int attempt) {
        jumpWhenReady(chat, attempt, 0);
    }

    /**
     * Waits for the chat's first messages, then jumps. A chat behind its Chat Lock cover is neither loaded nor scrolled
     * (and shows no loading spinner over the PIN sheet): the jump waits until the PIN unlocks it, or stops when the
     * chat closes (a cancelled PIN closes it).
     */
    private static void jumpWhenReady(ChatActivity chat, int attempt, int coveredPolls) {
        AndroidUtilities.runOnUIThread(() -> {
            if (chat.isFinished || chat.getParentActivity() == null) {
                return;
            }
            if (ZgChatLockGate.isCovered(chat)) {
                if (coveredPolls + 1 < MAX_COVERED_POLLS) {
                    jumpWhenReady(chat, attempt, coveredPolls + 1);
                }
                return;
            }
            if (chat.getFragmentView() == null || chat.messages.isEmpty()) {
                if (attempt + 1 < MAX_POLLS) {
                    jumpWhenReady(chat, attempt + 1, coveredPolls);
                }
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!chat.isFinished && chat.getParentActivity() != null && !chat.messages.isEmpty() && !ZgChatLockGate.isCovered(chat)) {
                    chat.jumpToDate(1);
                }
            }, SETTLE_MS);
        }, attempt == 0 && coveredPolls == 0 ? SETTLE_MS : POLL_MS);
    }
}
