/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import android.os.Bundle;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.TopicsFragment;

/**
 * Opens a chat from the vault list the way the main chat list does (DialogsActivity.onItemClick, distilled): a user,
 * a secret chat or a group / channel; a forum as its topics (or as messages, or with tabs, like the main list); a
 * channel's direct-messages forum as its suggestions mode. The chat opens above the vault list, which stays below it.
 */
final class ZgVaultChatOpener {

    private ZgVaultChatOpener() {
    }

    static void open(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null || dialogId == 0) {
            return;
        }
        try {
            final MessagesController controller = fragment.getMessagesController();
            final Bundle args = new Bundle();
            if (DialogObject.isEncryptedDialog(dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
            } else if (DialogObject.isUserDialog(dialogId)) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            if (!controller.checkCanOpenChat(args, fragment)) {
                return;
            }
            final TLRPC.Chat chat = DialogObject.isChatDialog(dialogId) ? controller.getChat(-dialogId) : null;
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (chat != null && (chat.monoforum || chat.forum)) {
                if (chat.monoforum) {
                    args.putInt("chatMode", ChatActivity.MODE_SUGGESTIONS);
                    args.putBoolean("isSubscriberSuggestions", !ChatObject.canManageMonoForum(fragment.getCurrentAccount(), chat));
                    fragment.presentFragment(new ChatActivity(args));
                } else if (ChatObject.areTabsEnabled(chat)) {
                    final ChatActivity activity = new ChatActivity(args);
                    ForumUtilities.applyTopic(activity, MessagesStorage.TopicKey.of(-chat.id, controller.getForumLastTopicId(chat.id)));
                    fragment.presentFragment(activity);
                } else if (dialog != null && dialog.view_forum_as_messages) {
                    fragment.presentFragment(new ChatActivity(args));
                } else {
                    fragment.presentFragment(new TopicsFragment(args));
                }
                return;
            }
            fragment.presentFragment(new ChatActivity(args));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
