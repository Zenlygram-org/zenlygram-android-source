/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.TextUtils;
import android.util.SparseArray;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;

/**
 * Chat UI of kept copies: the 🗑 time mark, TalkBack, "this media isn't stored" for media that was never
 * downloaded, the local-only delete dialog and "Remove All Deleted in This Chat" (iOS SGKeptDeletedUI).
 */
public final class ZgKeptDeletedUi {

    /** 🗑 (U+1F5D1 with the emoji variation selector), drawn as plain text by the time paint. */
    private static final String MARK = "🗑️";

    private ZgKeptDeletedUi() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Bubble
    // ---------------------------------------------------------------------------------------------------------

    /**
     * ChatMessageCell.measureTime: "🗑 14:05 · " + the normal time ("edited 13:58", ...) for a kept copy; for an
     * album, the earliest deletion time of its kept items. Applied before the channel signature is joined.
     */
    public static String decorateTime(MessageObject messageObject, MessageObject.GroupedMessages group, String timeString) {
        try {
            final int deletedAt = ZgKeptDeleted.getEarliestDeletedAt(messageObject, group);
            if (deletedAt == 0) {
                return timeString;
            }
            // Seconds in Messages applies to the deletion time too (iOS "🗑 14:05:37").
            final String time = org.telegram.zenlygram.chat.ZgChatAppearance.messageTimeFormat().format((long) deletedAt * 1000L);
            return TextUtils.isEmpty(timeString) ? MARK + " " + time : MARK + " " + time + " · " + timeString;
        } catch (Throwable e) {
            FileLog.e(e);
            return timeString;
        }
    }

    /** TalkBack text appended to the message description: "\nDeleted message, removed <date>", or "". */
    public static String accessibilitySuffix(MessageObject messageObject) {
        try {
            final int deletedAt = ZgKeptDeleted.getDeletedAt(messageObject);
            if (deletedAt == 0) {
                return "";
            }
            if (deletedAt > 1) {
                return "\n" + formatString(R.string.ZG_Chat_KeptDeleted_VoiceOver, LocaleController.formatDateTime(deletedAt, true));
            }
            return "\n" + getString(R.string.ZG_Chat_KeptDeleted_VoiceOver_NoDate);
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    /**
     * ChatActivity's image tap: the photo, video or file of a kept copy that is not on this device can never be
     * downloaded again (the message is gone from the server), so say so instead of opening a viewer that never loads.
     */
    public static boolean interceptMediaOpen(BaseFragment fragment, MessageObject messageObject) {
        try {
            if (fragment == null || !needsUnavailableNotice(messageObject) || isLoading(messageObject)) {
                return false;
            }
            showUnavailable(BulletinFactory.of(fragment));
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** ChatMessageCell's download / play button, same rule (a running download can still be cancelled). */
    public static boolean interceptDownload(ChatMessageCell cell, MessageObject messageObject) {
        try {
            if (!needsUnavailableNotice(messageObject) || isLoading(messageObject)) {
                return false;
            }
            showUnavailable(BulletinFactory.global());
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** A download that started before the deletion is still running: it may finish, so Telegram handles the tap. */
    private static boolean isLoading(MessageObject messageObject) {
        final String fileName = messageObject.getFileName();
        return !TextUtils.isEmpty(fileName) && FileLoader.getInstance(messageObject.currentAccount).isLoadingFile(fileName);
    }

    private static void showUnavailable(BulletinFactory factory) {
        if (factory != null) {
            factory.createSimpleBulletin(R.raw.error, getString(R.string.ZG_Chat_KeptDeleted_MediaUnavailable),
                getString(R.string.ZG_Chat_KeptDeleted_MediaUnavailable_Text)).show();
        }
    }

    private static boolean needsUnavailableNotice(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !ZgKeptDeleted.isKept(messageObject)) {
            return false;
        }
        switch (messageObject.type) {
            case MessageObject.TYPE_PHOTO:
            case MessageObject.TYPE_VIDEO:
            case MessageObject.TYPE_GIF:
            case MessageObject.TYPE_ROUND_VIDEO:
            case MessageObject.TYPE_FILE:
            case MessageObject.TYPE_MUSIC:
            case MessageObject.TYPE_VOICE:
                break;
            default:
                return false;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (!(media instanceof TLRPC.TL_messageMediaPhoto) && !(media instanceof TLRPC.TL_messageMediaDocument)) {
            return false;
        }
        return !isMediaLocal(messageObject);
    }

    /** Whether the message's media file is on this device. When unsure, true: Telegram then tries as usual. */
    static boolean isMediaLocal(MessageObject messageObject) {
        if (messageObject.attachPathExists || messageObject.mediaExists) {
            return true;
        }
        try {
            final String attachPath = messageObject.messageOwner.attachPath;
            if (!TextUtils.isEmpty(attachPath) && new File(attachPath).exists()) {
                return true;
            }
            final File file = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
            return file != null && file.exists();
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Deleting kept copies (this device only)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * ChatActivity.createDeleteMessagesAlert: when every targeted message is a kept copy, shows Telegram's
     * "Delete message?" dialog without the "also delete for" option and deletes from this device only. Returns false
     * (upstream dialog) for any other selection; a mixed selection keeps kept ids out of the server request anyway.
     */
    public static boolean showLocalDeleteAlertIfAllKept(ChatActivity chat, MessageObject selectedObject, MessageObject.GroupedMessages group,
                                                        SparseArray<MessageObject>[] selectedMessages, Runnable onDelete, Runnable hideDim) {
        try {
            if (chat == null || chat.getParentActivity() == null) {
                return false;
            }
            final int mode = chat.getChatMode();
            if (mode == ChatActivity.MODE_SCHEDULED || mode == ChatActivity.MODE_QUICK_REPLIES || mode == ChatActivity.MODE_WELCOME_MESSAGES) {
                return false;
            }
            final LongSparseArray<ArrayList<Integer>> byDialog = new LongSparseArray<>();
            int count = 0;
            if (selectedObject != null) {
                final ArrayList<MessageObject> targets = new ArrayList<>();
                if (group != null && group.messages != null && !group.messages.isEmpty()) {
                    targets.addAll(group.messages);
                } else {
                    targets.add(selectedObject);
                }
                for (int i = 0; i < targets.size(); i++) {
                    final MessageObject target = targets.get(i);
                    if (!ZgKeptDeleted.isKept(target)) {
                        return false;
                    }
                    add(byDialog, target.getDialogId(), target.getId());
                    count++;
                }
            } else if (selectedMessages != null) {
                for (int a = 0; a < selectedMessages.length; a++) {
                    final SparseArray<MessageObject> selection = selectedMessages[a];
                    if (selection == null) {
                        continue;
                    }
                    for (int b = 0; b < selection.size(); b++) {
                        final MessageObject target = selection.valueAt(b);
                        if (!ZgKeptDeleted.isKept(target)) {
                            return false;
                        }
                        add(byDialog, target.getDialogId(), target.getId());
                        count++;
                    }
                }
            }
            if (count == 0) {
                return false;
            }
            final int account = chat.getCurrentAccount();
            final int total = count;
            final AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity(), chat.getResourceProvider());
            builder.setTitle(total == 1 ? getString(R.string.DeleteSingleMessagesTitle)
                : formatString(R.string.DeleteMessagesTitle, formatPluralString("messages", total)));
            builder.setMessage(getString(total == 1 ? R.string.AreYouSureDeleteSingleMessage : R.string.AreYouSureDeleteFewMessages));
            builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                ZgKeptDeleted.deleteLocally(account, byDialog);
                if (selectedMessages != null && selectedObject == null) {
                    for (SparseArray<MessageObject> selection : selectedMessages) {
                        if (selection != null) {
                            selection.clear();
                        }
                    }
                }
                if (onDelete != null) {
                    onDelete.run();
                }
            });
            builder.setNegativeButton(getString(R.string.Cancel), null);
            if (hideDim != null) {
                builder.setOnPreDismissListener(di -> hideDim.run());
            }
            builder.makeRed(AlertDialog.BUTTON_POSITIVE);
            chat.showDialog(builder.create());
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** "Remove All Deleted in This Chat": counts, confirms, then deletes from this device and says so. */
    public static void confirmRemoveAllInChat(ChatActivity chat) {
        if (chat == null) {
            return;
        }
        final int account = chat.getCurrentAccount();
        final long[] dialogIds = chat.getMergeDialogId() != 0
            ? new long[]{chat.getDialogId(), chat.getMergeDialogId()}
            : new long[]{chat.getDialogId()};
        ZgKeptDeleted.loadKeptInChats(account, dialogIds, byDialog -> {
            try {
                showRemoveAllConfirmation(chat, account, byDialog);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void showRemoveAllConfirmation(ChatActivity chat, int account, LongSparseArray<ArrayList<Integer>> byDialog) {
        final Activity activity = chat.getParentActivity();
        if (activity == null || chat.isFinished) {
            return;
        }
        int count = 0;
        for (int i = 0, n = byDialog.size(); i < n; i++) {
            count += byDialog.valueAt(i).size();
        }
        if (count == 0) {
            return; // removed meanwhile (sweeper, another device's history clear): nothing to confirm
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, chat.getResourceProvider());
        builder.setMessage(formatString(R.string.ZG_ContextMenu_KeptDeleted_RemoveAllInChat_Text, String.valueOf(count)));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            ZgKeptDeleted.deleteLocally(account, byDialog);
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.done, getString(R.string.ZG_Settings_Retention_Removed)).show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        chat.showDialog(builder.create());
    }

    private static void add(LongSparseArray<ArrayList<Integer>> map, long dialogId, int mid) {
        ArrayList<Integer> mids = map.get(dialogId);
        if (mids == null) {
            map.put(dialogId, mids = new ArrayList<>());
        }
        mids.add(mid);
    }
}
