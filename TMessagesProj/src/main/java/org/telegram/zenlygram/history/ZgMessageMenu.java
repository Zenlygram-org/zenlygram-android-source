/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.zenlygram.chat.ZgChatAppearance;
import org.telegram.zenlygram.translate.ZgQuickTranslate;

import java.util.ArrayList;

/**
 * The message context menu of ChatActivity (iOS ChatInterfaceStateContextMenus):
 * <ul>
 *   <li>"Edit History" just before Delete, when the message (or its album) has saved versions;</li>
 *   <li>for a kept copy, the actions that need the deleted message on the server are removed (reply, pin, edit,
 *       forward, copy link, threads, report, polls, to-do lists, fact check, statistics, suggestions), and
 *       "Remove All Deleted in This Chat" follows Delete. Copy, Save and Select stay. Translate is removed too
 *       (iOS translates a kept copy by its text; here kept data never leaves the device).</li>
 *   <li>"AI Summary" after Translate for a wide channel post (Wide posts in channels), which has no side summary
 *       button; never on a kept copy (the summary needs the deleted message on the server).</li>
 *   <li>"Quick Translate" / "Show Original" for a wide channel post while Quick Translate is on: the button that
 *       sits beside other incoming messages has no room there. A kept copy gets only "Show Original" (a translation
 *       shown before the deletion), which reverts on the device.</li>
 * </ul>
 */
public final class ZgMessageMenu {

    /** Option ids, outside Telegram's 0..117 (ChatActivity.OPTION_*). */
    public static final int OPTION_ZG_EDIT_HISTORY = 9001;
    public static final int OPTION_ZG_REMOVE_KEPT_IN_CHAT = 9002;
    /** Wide posts in channels have no side AI summary button: "AI Summary" after Translate (ZgChatAppearance.summaryTarget). */
    public static final int OPTION_ZG_AI_SUMMARY = 9003;
    /** Wide posts in channels have no Quick Translate button beside them: the same action here (ZgQuickTranslate.menuTarget). */
    public static final int OPTION_ZG_QUICK_TRANSLATE = 9004;

    private ZgMessageMenu() {
    }

    /** End of ChatActivity.fillMessageMenu (also used by the poll / to-do option menus). */
    public static void adjust(ChatActivity chat, MessageObject selectedObject, MessageObject.GroupedMessages group,
                              ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        try {
            if (selectedObject == null || icons == null || items == null || options == null
                || options.size() != items.size() || options.size() != icons.size()) {
                return;
            }
            if (ZgKeptDeleted.isAnyKept(selectedObject, group)) {
                for (int i = options.size() - 1; i >= 0; i--) {
                    final Integer option = options.get(i);
                    if (option != null && isServerBound(option)) {
                        removeAt(i, icons, items, options);
                    }
                }
                final int delete = options.indexOf(ChatActivity.OPTION_DELETE);
                insert(delete >= 0 ? delete + 1 : options.size(), OPTION_ZG_REMOVE_KEPT_IN_CHAT, R.drawable.msg_delete,
                    getString(R.string.ZG_ContextMenu_KeptDeleted_RemoveAllInChat), icons, items, options);
            }
            if (ZgEditHistory.findWithHistory(selectedObject, group) != null) {
                final int delete = options.indexOf(ChatActivity.OPTION_DELETE);
                insert(delete >= 0 ? delete : options.size(), OPTION_ZG_EDIT_HISTORY, R.drawable.msg_recent,
                    getString(R.string.ZG_ContextMenu_EditedHistory), icons, items, options);
            }
            final boolean kept = ZgKeptDeleted.isAnyKept(selectedObject, group);
            if (!kept && ZgChatAppearance.summaryTarget(selectedObject, group) != null) {
                final int translate = options.indexOf(ChatActivity.OPTION_TRANSLATE);
                final int delete = options.indexOf(ChatActivity.OPTION_DELETE);
                insert(translate >= 0 ? translate + 1 : delete >= 0 ? delete : options.size(), OPTION_ZG_AI_SUMMARY,
                    R.drawable.premium_ai_editor, getString(R.string.SummaryTitle), icons, items, options);
            }
            // A kept copy (or an album with one) gets only "Show Original": the way back from a translation shown before
            // the deletion; nothing is sent for it.
            final MessageObject quick = chat == null ? null : ZgQuickTranslate.menuTarget(chat.getCurrentAccount(), selectedObject, group);
            if (quick != null && (!kept || ZgQuickTranslate.isShownTranslated(quick))) {
                final int translate = options.indexOf(ChatActivity.OPTION_TRANSLATE);
                final int delete = options.indexOf(ChatActivity.OPTION_DELETE);
                insert(translate >= 0 ? translate + 1 : delete >= 0 ? delete : options.size(), OPTION_ZG_QUICK_TRANSLATE, R.drawable.msg_translate,
                    getString(ZgQuickTranslate.isShownTranslated(quick) ? R.string.ShowOriginalButton : R.string.ZG_QuickTranslate_MenuItem),
                    icons, items, options);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** ChatActivity.processSelectedOption: handles our two options; true means handled (the menu then closes). */
    public static boolean processSelectedOption(ChatActivity chat, int option, MessageObject selectedObject, MessageObject.GroupedMessages group) {
        if (option == OPTION_ZG_EDIT_HISTORY) {
            ZgEditHistoryUi.open(chat, selectedObject, group);
            return true;
        } else if (option == OPTION_ZG_REMOVE_KEPT_IN_CHAT) {
            ZgKeptDeletedUi.confirmRemoveAllInChat(chat);
            return true;
        } else if (option == OPTION_ZG_AI_SUMMARY) {
            ZgChatAppearance.openSummary(chat, ZgChatAppearance.summaryTarget(selectedObject, group));
            return true;
        } else if (option == OPTION_ZG_QUICK_TRANSLATE) {
            final MessageObject quick = chat == null ? null : ZgQuickTranslate.menuTarget(chat.getCurrentAccount(), selectedObject, group);
            if (quick != null && (!ZgKeptDeleted.isAnyKept(selectedObject, group) || ZgQuickTranslate.isShownTranslated(quick))) {
                ZgQuickTranslate.onPress(chat, quick);
            }
            return true;
        }
        return false;
    }

    /** ChatActivity.createMenu: "Remove All Deleted in This Chat" is red, like a destructive iOS action. */
    public static void styleItem(ActionBarMenuSubItem cell, int option) {
        if (cell != null && option == OPTION_ZG_REMOVE_KEPT_IN_CHAT) {
            cell.setColorKeys(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
            cell.setSelectorColorKey(Theme.key_text_RedRegular, .12f);
        }
    }

    /** Actions that send the (deleted) message id to a server. */
    private static boolean isServerBound(int option) {
        switch (option) {
            case ChatActivity.OPTION_REPLY:
            case ChatActivity.OPTION_PIN:
            case ChatActivity.OPTION_UNPIN:
            case ChatActivity.OPTION_EDIT:
            case ChatActivity.OPTION_FORWARD:
            case ChatActivity.OPTION_COPY_LINK:
            case ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD:
            case ChatActivity.OPTION_VIEW_IN_TOPIC:
            case ChatActivity.OPTION_REPORT_CHAT:
            case ChatActivity.OPTION_UNVOTE:
            case ChatActivity.OPTION_STOP_POLL_OR_QUIZ:
            case ChatActivity.OPTION_STATISTICS:
            case ChatActivity.OPTION_VIEW_STATISTICS:
            case ChatActivity.OPTION_FACT_CHECK:
            case ChatActivity.OPTION_EDIT_TODO:
            case ChatActivity.OPTION_ADD_TO_TODO:
            case ChatActivity.OPTION_SUGGESTION_EDIT_PRICE:
            case ChatActivity.OPTION_SUGGESTION_EDIT_TIME:
            case ChatActivity.OPTION_SUGGESTION_EDIT_MESSAGE:
            case ChatActivity.OPTION_SUGGESTION_ADD_OFFER:
            case ChatActivity.OPTION_SEND_MONEY:
            case ChatActivity.OPTION_EDIT_SCHEDULE_TIME:
            case ChatActivity.OPTION_SEND_NOW:
            // Translating a kept copy would send its text to Telegram's translator (by id it fails: the message is
            // gone). Kept deleted data never leaves the device, so Translate is not offered; Copy still is.
            case ChatActivity.OPTION_TRANSLATE:
                return true;
            default:
                return false;
        }
    }

    private static void removeAt(int index, ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        options.remove(index);
        items.remove(index);
        icons.remove(index);
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
