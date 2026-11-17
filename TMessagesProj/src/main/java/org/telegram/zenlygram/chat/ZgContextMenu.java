/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chat;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.AccountFrozenAlert;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatRightsEditActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.history.ZgKeptDeleted;

import java.util.ArrayList;

/**
 * CONTEXT_MENU: the layout of the message menu (iOS ChatInterfaceStateContextMenus + SGSimpleSettings.contextShow*).
 *
 * <p>Android master switch (Settings &gt; Zenlygram &gt; Context menu, off by default, iOS has none): while it is off
 * this class changes nothing (Edit History, Add to Tasks and the kept-copy / wide-post items of ZgMessageMenu and
 * ZgTasksMessageMenu follow their own switches). While it is on, five Swiftgram items are added (Save to Cloud, Hide senders'
 * names, Select from Author, Restrict, JSON) and ten switches decide which items stay at the top level: a
 * switched-off item moves into a "Zenlygram" sub-page of the same popup, so nothing is lost. JSON is off by default,
 * so the "Zenlygram" entry is then on every message menu, as on iOS. A message Telegram shows no menu for gets none.
 *
 * <p>Wiring (ZENLYGRAM hooks in ChatActivity.createMenu / processSelectedOption / didSelectDialogs):
 * {@link #apply} edits the three parallel lists after fillMessageMenu (so after the Edit History / Tasks items and
 * Telegram's hidden-media filter), the popup gets FLAG_USE_SWIPEBACK while a {@link Submenu} exists, the "Zenlygram"
 * cell opens {@link Submenu#createPage}, and {@link #process} runs the actions that need no ChatActivity internals.
 * Hide senders' names reuses Telegram's forward flow; Select from Author is a small ChatActivity bridge.
 */
public final class ZgContextMenu {

    /**
     * Option ids, in the 9100-9199 block reserved for this feature (Telegram uses 0..117, Edit History 9001-9002).
     * 9101 is taken by Tasks (ZgTasksMessageMenu.OPTION_ZG_ADD_TO_TASKS), so this feature starts at 9110.
     */
    public static final int OPTION_ZG_SUBMENU = 9110;
    public static final int OPTION_ZG_SAVE_TO_CLOUD = 9111;
    public static final int OPTION_ZG_FORWARD_NO_NAMES = 9112;
    public static final int OPTION_ZG_SELECT_FROM_AUTHOR = 9113;
    public static final int OPTION_ZG_RESTRICT = 9114;
    public static final int OPTION_ZG_JSON = 9115;

    private ZgContextMenu() {
    }

    /** The master switch (Android only, off by default): without it this class leaves the message menu alone. */
    public static boolean isEnabled() {
        return ZgFeatures.CONTEXT_MENU.isPorted() && ZgSettings.contextMenuZenlygramItems.get();
    }

    /** The items moved out of the top level, in menu order. */
    public static final class Submenu {
        private final ArrayList<Integer> options = new ArrayList<>();
        private final ArrayList<CharSequence> items = new ArrayList<>();
        private final ArrayList<Integer> icons = new ArrayList<>();

        private Submenu() {
        }

        void add(int option, CharSequence text, int icon) {
            options.add(option);
            items.add(text);
            icons.add(icon);
        }

        public boolean isEmpty() {
            return options.isEmpty();
        }

        /**
         * The sub-page (like Telegram's "seen by" page): Back, a gap, then one cell per item. A tap on an item calls
         * {@code onSelect} with its option id, exactly as a top-level cell calls processSelectedOption.
         */
        public View createPage(Context context, ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
                               Theme.ResourcesProvider resourcesProvider, Utilities.Callback<Integer> onSelect) {
            final LinearLayout page = new LinearLayout(context);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setMinimumWidth(dp(200));

            final ActionBarMenuSubItem backCell = new ActionBarMenuSubItem(context, true, false, resourcesProvider);
            backCell.setItemHeight(44);
            backCell.setTextAndIcon(getString(R.string.Back), R.drawable.msg_arrow_back);
            backCell.getTextView().setPadding(LocaleController.isRTL ? 0 : dp(40), 0, LocaleController.isRTL ? dp(40) : 0, 0);
            backCell.setOnClickListener(v -> {
                if (popupLayout.getSwipeBack() != null) {
                    popupLayout.getSwipeBack().closeForeground();
                }
            });
            page.addView(backCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            page.addView(new ActionBarPopupWindow.GapView(context, resourcesProvider), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

            for (int i = 0, n = options.size(); i < n; i++) {
                final int option = options.get(i);
                final ActionBarMenuSubItem cell = new ActionBarMenuSubItem(context, false, i == n - 1, resourcesProvider);
                cell.setMinimumWidth(dp(200));
                cell.setTextAndIcon(items.get(i), icons.get(i));
                cell.setOnClickListener(v -> {
                    if (onSelect != null) {
                        onSelect.run(option);
                    }
                });
                page.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            return page;
        }
    }

    /**
     * ChatActivity.createMenu, after fillMessageMenu and Telegram's hidden-media filter: adds the Zenlygram items and
     * moves the switched-off ones into the returned sub-menu (null = no sub-menu, the lists are then unchanged
     * except for added top-level items). Never throws.
     */
    public static Submenu apply(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group, int type,
                                ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        // Off (the default): nothing added or moved. An empty list means Telegram shows no menu for this message (e.g. a
        // service message the user can neither reply to nor delete): it stays without one.
        if (!isEnabled() || chat == null || message == null || message.isSponsored()
            || icons == null || items == null || options == null || options.isEmpty()
            || options.size() != items.size() || options.size() != icons.size()
            || options.contains(OPTION_ZG_SUBMENU) || options.contains(OPTION_ZG_JSON)) {
            return null;
        }
        try {
            final long selfId = chat.getUserConfig().getClientUserId();
            final boolean savedMessages = chat.getDialogId() == selfId || chat.getChatMode() == ChatActivity.MODE_SAVED;

            // 1. The Swiftgram items, each at its natural place.
            final int forward = options.indexOf(ChatActivity.OPTION_FORWARD);
            if (forward >= 0) {
                int at = forward + 1;
                if (!savedMessages) {
                    insert(at++, OPTION_ZG_SAVE_TO_CLOUD, R.drawable.msg_saved, getString(R.string.ZG_ContextMenu_SaveToCloud), icons, items, options);
                }
                insert(at, OPTION_ZG_FORWARD_NO_NAMES, R.drawable.msg_forward, getString(R.string.HideSenderNames), icons, items, options);
            }
            if (canRestrict(chat, message, selfId)) {
                final int report = options.indexOf(ChatActivity.OPTION_REPORT_CHAT);
                insert(report >= 0 ? report + 1 : beforeDelete(options), OPTION_ZG_RESTRICT, R.drawable.msg_block2, getString(R.string.Restrict), icons, items, options);
            }
            if (canSelectFromAuthor(chat, message, group, type)) {
                insert(beforeDelete(options), OPTION_ZG_SELECT_FROM_AUTHOR, R.drawable.msg_select, getString(R.string.ZG_ContextMenu_SelectFromUser), icons, items, options);
            }
            insert(beforeDelete(options), OPTION_ZG_JSON, R.drawable.formatting_code, "JSON", icons, items, options);

            // 2. Switched-off items move into the sub-menu, keeping their order, text and icon.
            final boolean repliesChat = UserObject.isReplyUser(chat.getDialogId());
            final Submenu submenu = new Submenu();
            for (int i = 0; i < options.size(); i++) {
                final Integer option = options.get(i);
                if (option != null && !isShownAtTopLevel(option, repliesChat)) {
                    submenu.add(option, items.get(i), icons.get(i));
                    options.remove(i);
                    items.remove(i);
                    icons.remove(i);
                    i--;
                }
            }
            if (submenu.isEmpty()) {
                return null;
            }
            final int delete = options.indexOf(ChatActivity.OPTION_DELETE);
            insert(delete >= 0 ? delete : options.size(), OPTION_ZG_SUBMENU, R.drawable.zg_glyph, getString(R.string.ZG_AppName), icons, items, options);
            return submenu;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * ChatActivity.processSelectedOption: Save to Cloud, Restrict and JSON. True = handled (the menu then closes).
     * Hide senders' names and Select from Author are handled inside ChatActivity.
     */
    public static boolean process(ChatActivity chat, int option, MessageObject message, MessageObject.GroupedMessages group) {
        if (!ZgFeatures.CONTEXT_MENU.isPorted() || chat == null || message == null) {
            return false;
        }
        switch (option) {
            case OPTION_ZG_SAVE_TO_CLOUD:
                saveToCloud(chat, message, group);
                return true;
            case OPTION_ZG_RESTRICT:
                openRestrict(chat, message);
                return true;
            case OPTION_ZG_JSON:
                ZgMessageJson.show(chat, message);
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static boolean isShownAtTopLevel(int option, boolean repliesChat) {
        switch (option) {
            case ChatActivity.OPTION_REPLY:
                return ZgSettings.contextShowReply.get();
            case ChatActivity.OPTION_SAVE_TO_GALLERY:
            case ChatActivity.OPTION_SAVE_TO_GALLERY2:
                return ZgSettings.contextShowSaveMedia.get();
            case ChatActivity.OPTION_PIN:
            case ChatActivity.OPTION_UNPIN:
                return ZgSettings.contextShowPin.get();
            case ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD:
                return ZgSettings.contextShowMessageReplies.get();
            case ChatActivity.OPTION_REPORT_CHAT:
                // In the Replies chat this id is "Block contact": never moved.
                return repliesChat || ZgSettings.contextShowReport.get();
            case OPTION_ZG_SAVE_TO_CLOUD:
                return ZgSettings.contextShowSaveToCloud.get();
            case OPTION_ZG_FORWARD_NO_NAMES:
                return ZgSettings.contextShowHideForwardName.get();
            case OPTION_ZG_SELECT_FROM_AUTHOR:
                return ZgSettings.contextShowSelectFromUser.get();
            case OPTION_ZG_RESTRICT:
                return ZgSettings.contextShowRestrict.get();
            case OPTION_ZG_JSON:
                return ZgSettings.contextShowJson.get();
            default:
                return true;
        }
    }

    /** iOS: the author is a user other than me and I may ban members. Supergroups only (basic groups need an upgrade). */
    private static boolean canRestrict(ChatActivity chat, MessageObject message, long selfId) {
        final TLRPC.Chat currentChat = chat.getCurrentChat();
        if (currentChat == null || !ChatObject.isChannel(currentChat) || !currentChat.megagroup || !ChatObject.canBlockUsers(currentChat)) {
            return false;
        }
        if (message.messageOwner == null || !(message.messageOwner.from_id instanceof TLRPC.TL_peerUser)) {
            return false;
        }
        final long userId = message.messageOwner.from_id.user_id;
        return userId != 0 && userId != selfId && message.getId() > 0 && !message.scheduled;
    }

    /**
     * iOS: selectable messages, not in the pinned list, not the thread head. Never from a kept deleted copy (Show
     * Deleted Messages): the selection starts with it, and a copy gone from the server cannot be forwarded.
     */
    private static boolean canSelectFromAuthor(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group, int type) {
        if (type < 2 || type == 20 || message.getSenderId() == 0 || message.getId() <= 0 || message.isEphemeral()
            || message.messageOwner == null || message.messageOwner.action != null) {
            return false;
        }
        if (ZgKeptDeleted.isAnyKept(message, group)) {
            return false;
        }
        if (chat.getChatMode() == ChatActivity.MODE_PINNED || chat.isInsideContainer || chat.isReport()) {
            return false;
        }
        return !(chat.isThreadChat() && message.getId() == chat.getThreadId());
    }

    private static void saveToCloud(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group) {
        if (chat.getMessagesController().isFrozen()) {
            AccountFrozenAlert.show(chat.getCurrentAccount());
            return;
        }
        final ArrayList<MessageObject> messages = new ArrayList<>();
        if (group != null && group.messages != null && !group.messages.isEmpty()) {
            messages.addAll(group.messages);
        } else {
            messages.add(message);
        }
        final long selfId = chat.getUserConfig().getClientUserId();
        chat.getSendMessagesHelper().sendMessage(messages, selfId, false, false, true, 0, 0, null, -1, 0, 0, null);
        BulletinFactory.of(chat).showForwardedBulletinWithTag(selfId, messages.size());
    }

    /** iOS: fetch the member, then open the restrictions editor (the same call as ProfileActivity's ban flow). */
    private static void openRestrict(ChatActivity chat, MessageObject message) {
        final TLRPC.Chat currentChat = chat.getCurrentChat();
        if (currentChat == null || message.messageOwner == null || message.messageOwner.from_id == null) {
            return;
        }
        final long userId = message.messageOwner.from_id.user_id;
        final MessagesController controller = chat.getMessagesController();
        final TLRPC.InputPeer participant = controller.getInputPeer(userId);
        final TLRPC.InputChannel channel = MessagesController.getInputChannel(currentChat);
        if (userId == 0 || participant == null || channel == null) {
            return;
        }
        final TLRPC.TL_channels_getParticipant req = new TLRPC.TL_channels_getParticipant();
        req.channel = channel;
        req.participant = participant;
        chat.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (chat.getParentActivity() == null || chat.isFinished || chat.isRemovingFromStack()) {
                return;
            }
            TLRPC.TL_chatBannedRights banned = null;
            if (response instanceof TLRPC.TL_channels_channelParticipant) {
                final TLRPC.TL_channels_channelParticipant result = (TLRPC.TL_channels_channelParticipant) response;
                controller.putUsers(result.users, false);
                controller.putChats(result.chats, false);
                if (result.participant != null) {
                    banned = result.participant.banned_rights;
                }
            }
            final ChatRightsEditActivity fragment = new ChatRightsEditActivity(userId, currentChat.id, null,
                currentChat.default_banned_rights, banned, "", ChatRightsEditActivity.TYPE_BANNED, true, false, null);
            chat.presentFragment(fragment);
        }));
    }

    private static int beforeDelete(ArrayList<Integer> options) {
        final int delete = options.indexOf(ChatActivity.OPTION_DELETE);
        return delete >= 0 ? delete : options.size();
    }

    private static void insert(int index, int option, int icon, CharSequence text, ArrayList<Integer> icons,
                               ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (options.contains(option)) {
            return;
        }
        index = Math.max(0, Math.min(index, options.size()));
        options.add(index, option);
        items.add(index, text);
        icons.add(index, icon);
    }
}
