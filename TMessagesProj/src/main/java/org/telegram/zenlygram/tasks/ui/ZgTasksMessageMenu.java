/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.ZgTasksGate;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.source.ZgTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;

import java.util.ArrayList;

/**
 * "Add to Tasks" in the message menu of a chat (Android addition; iOS has no message entry point). The message text
 * (or the album's caption) becomes a task without a list on the board the account uses: its tasks on this device
 * (the Inbox), or its Cloud tasks (the Cloud Inbox) when Cloud is the board it works on; when that board takes no
 * new task now, the other one does ({@link ZgTasksSources#forNewTask}). Only the text is taken: the message and the
 * chat are not linked. Creating it passes the CREATE gate like a quick add.
 *
 * <p>It is offered only next to Telegram's own Copy, so content protection holds: chats and messages that restrict
 * saving content, paid media and messages without text have no Copy and get no "Add to Tasks" either. It follows the
 * Tasks tab: with Show Tasks Tab off it is not offered (Tasks stays reachable in Settings > Zenlygram > Tools).
 */
public final class ZgTasksMessageMenu {

    /** Option id, outside Telegram's ChatActivity.OPTION_* and the history menu's 9001+. */
    public static final int OPTION_ZG_ADD_TO_TASKS = 9101;

    private ZgTasksMessageMenu() {
    }

    /** End of ChatActivity.fillMessageMenu: adds "Add to Tasks" right after Copy for a message with text. */
    public static void adjust(ChatActivity chat, MessageObject selectedObject, MessageObject.GroupedMessages group,
                              ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        try {
            if (!ZgFeatures.TASKS.isPorted() || !ZgTasks.isTabVisible() || selectedObject == null || icons == null || items == null
                || options == null || options.size() != items.size() || options.size() != icons.size() || options.contains(OPTION_ZG_ADD_TO_TASKS)) {
                return;
            }
            // Telegram leaves Copy out where saving content is restricted (ChatActivity: !noforwardsOrPaidMedia).
            final int copy = options.indexOf(ChatActivity.OPTION_COPY);
            if (copy < 0 || isProtected(chat, selectedObject, group) || titleFor(selectedObject, group) == null) {
                return;
            }
            final int at = copy + 1;
            options.add(at, OPTION_ZG_ADD_TO_TASKS);
            items.add(at, getString(R.string.ZG_tasks_addToTasks));
            icons.add(at, R.drawable.msg_list);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** ChatActivity.processSelectedOption: true when it was "Add to Tasks" (the menu then closes). */
    public static boolean processSelectedOption(ChatActivity chat, int option, MessageObject selectedObject, MessageObject.GroupedMessages group) {
        if (option != OPTION_ZG_ADD_TO_TASKS) {
            return false;
        }
        addToTasks(chat, titleFor(selectedObject, group));
        return true;
    }

    private static void addToTasks(final BaseFragment fragment, String title) {
        if (title == null || fragment == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final ZgTasksSource source = ZgTasksSources.forNewTask(account);
        if (source == null) {
            // Neither board takes a task now (the device board is an archive and Cloud is on hold): say why.
            final ZgBoard cloud = ZgTasksSources.cloudAvailable() ? ZgTasksSources.cloud(account).peek() : null;
            ZgTasksUi.note(fragment, cloud != null && cloud.readOnly != null ? ZgTasksUi.readOnlyText(cloud.readOnly)
                : getString(R.string.ZG_tasks_addToTasks_unavailable));
            return;
        }
        if (!ZgTasksGate.allowCreate(fragment)) {
            return;
        }
        source.createTask(null, title, null, null, (taskId, error) -> {
            if (error != null) {
                ZgTasksUi.report(fragment, error, source.board(), ZgTasksTitlePolicy.TASK_LIMIT);
                return;
            }
            if (BulletinFactory.canShowBulletin(fragment)) {
                // "Open" shows the board the task went to.
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, getString(R.string.ZG_tasks_addedToTasks),
                    getString(R.string.ZG_tasks_action_open), () -> ZgTasks.open(fragment, source.scope())).show();
            }
        });
    }

    /** Restricted saving (the chat or a message of the album) or paid media; Copy is absent then anyway. */
    private static boolean isProtected(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group) {
        if (chat != null && chat.isPeerNoForwards()) {
            return true;
        }
        if (isProtected(message)) {
            return true;
        }
        if (group != null && group.messages != null) {
            for (int i = 0, n = group.messages.size(); i < n; i++) {
                if (isProtected(group.messages.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isProtected(MessageObject message) {
        return message != null && (message.type == MessageObject.TYPE_PAID_MEDIA
            || message.messageOwner != null && message.messageOwner.noforwards);
    }

    /**
     * The task title for a message: its text or caption (the album's caption), on one line, at most 200 characters
     * (longer text is cut and ends with "…", see ZgTasksTitlePolicy.titleFromText). Null when there is nothing visible
     * (stickers, service messages, ...).
     */
    static String titleFor(MessageObject message, MessageObject.GroupedMessages group) {
        String text = textOf(message);
        if (TextUtils.isEmpty(text) && group != null && group.messages != null) {
            for (MessageObject m : group.messages) {
                text = textOf(m);
                if (!TextUtils.isEmpty(text)) {
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        return ZgTasksTitlePolicy.titleFromText(text);
    }

    private static String textOf(MessageObject message) {
        if (message == null || message.messageOwner == null || message.messageOwner instanceof TLRPC.TL_messageService || message.isSponsored()) {
            return null;
        }
        return message.messageOwner.message;
    }
}
