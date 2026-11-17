/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Save Edit History: when someone edits a message whose text or photo / document changed, the previous version is
 * kept on this device (iOS sgCaptureEditHistory), at most 20 per message, each for 30 days. Long-press the message,
 * Edit History, to read them. Reading is never gated by Pro; only recording new versions is.
 *
 * <p>Edits made on this device are not recorded: Telegram stores the edited text before the server confirms it, so
 * the confirmation finds nothing changed (same as iOS). Edits of messages that are not in the local cache are not
 * recorded either (there is no previous version to keep).
 */
public final class ZgEditHistory {

    private ZgEditHistory() {
    }

    /**
     * MessagesStorage.putMessages(load_type -2), inside its transaction, right after the stored previous version was
     * read: records {@code oldMessage} when the edit changed the text or the photo / document. Never begins or commits
     * a transaction, never changes either message, never throws.
     *
     * @return true when the replaced photo / document belongs to a recorded version, so its cached files should stay
     *         (the Edit History screen can still show it)
     */
    public static boolean captureOnEdit(int account, MessagesStorage storage, TLRPC.Message oldMessage, TLRPC.Message newMessage, int oldSendState) {
        try {
            if (oldMessage == null || newMessage == null) {
                return false;
            }
            if (!ZgHistorySettings.isEditHistoryEnabled()) {
                return false;
            }
            if (!ZgHistorySettings.mayCapture()) {
                // Pro is not active: nothing new is recorded. What was kept stays readable, so the replaced media
                // keeps its files while a version already kept for this message may still show it.
                return keptVersionMayShow(account, oldMessage, newMessage);
            }
            if (oldMessage.id <= 0 || oldMessage.id != newMessage.id || oldSendState != MessageObject.MESSAGE_SEND_STATE_SENT) {
                return false;
            }
            if (oldMessage instanceof TLRPC.TL_messageService || oldMessage instanceof TLRPC.TL_messageEmpty
                || newMessage instanceof TLRPC.TL_messageService || newMessage instanceof TLRPC.TL_messageEmpty) {
                return false;
            }
            final long oldKey = mediaKey(oldMessage.media);
            final long newKey = mediaKey(newMessage.media);
            if (!ZgRetentionPolicy.editChanged(oldMessage.message, oldKey, newMessage.message, newKey)) {
                return false;
            }
            // putMessages already resolved the dialog of the new message for its own query.
            final long uid = MessageObject.getDialogId(newMessage);
            if (uid == 0 || DialogObject.isEncryptedDialog(uid)) {
                return false;
            }
            if (!ZgHistoryStore.isStorageThread(storage)) {
                return false;
            }
            // Telegram's cursor and statements are open here: no schema change, only plain writes to our table.
            final SQLiteDatabase db = ZgHistoryStore.preparedDatabase(account, storage);
            if (db == null) {
                return false;
            }
            final int now = ZgHistorySettings.localNow();
            final boolean wasEdited = (oldMessage.flags & TLRPC.MESSAGE_FLAG_EDITED) != 0;
            final int versionDate = ZgRetentionPolicy.versionDate(wasEdited, oldMessage.edit_date, oldMessage.date);
            NativeByteBuffer data = null;
            try {
                data = new NativeByteBuffer(oldMessage.getObjectSize());
                oldMessage.serializeToStream(data);
                ZgHistoryStore.insertRevision(db, uid, oldMessage.id, versionDate, now, data, ZgHistorySettings.editCutoff(now));
            } finally {
                if (data != null) {
                    data.reuse();
                }
            }
            final ZgHistoryIndex index = ZgHistoryIndex.get(account);
            if (index != null) {
                index.putEdit(uid, oldMessage.id, now);
            }
            return oldKey != 0 && oldKey != newKey;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * While recording is not allowed (Pro is not active): whether the photo or document this edit replaces may still be
     * shown by a version that was kept earlier. An earlier text-only edit kept a version with this same media, so the
     * files stay as long as the message has a visible kept version. Index only; never opens the database.
     */
    private static boolean keptVersionMayShow(int account, TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        final long oldKey = mediaKey(oldMessage.media);
        if (oldKey == 0 || oldKey == mediaKey(newMessage.media) || oldMessage.id <= 0 || oldMessage.id != newMessage.id) {
            return false;
        }
        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
        if (index == null || !index.hasAnyEdits()) {
            return false;
        }
        final long uid = MessageObject.getDialogId(newMessage);
        if (uid == 0) {
            return false;
        }
        final int maxCapturedAt = index.getMaxCapturedAt(uid, oldMessage.id);
        return ZgRetentionPolicy.isRevisionVisible(maxCapturedAt, ZgHistorySettings.editCutoff(ZgHistorySettings.localNow()));
    }

    /**
     * What identifies the media of a version: the photo or document id; 0 for everything else (webpage previews,
     * locations, polls, paid media, contacts, no media), which therefore never makes an edit count.
     */
    static long mediaKey(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return media.photo != null ? media.photo.id : 0;
        }
        if (media instanceof TLRPC.TL_messageMediaDocument) {
            return media.document != null ? media.document.id : 0;
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading (never gated)
    // ---------------------------------------------------------------------------------------------------------

    /** Whether the message has at least one version to show (UI thread, index only). */
    public static boolean hasVisibleHistory(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || !ZgHistorySettings.isEditHistoryEnabled()) {
            return false;
        }
        final ZgHistoryIndex index = ZgHistoryIndex.peek(messageObject.currentAccount);
        if (index == null || !index.hasAnyEdits()) {
            return false;
        }
        if (messageObject.scheduled || messageObject.eventId != 0 || messageObject.isSponsored()
            || messageObject.isStory() || messageObject.isQuickReply() || messageObject.isEphemeral()) {
            return false;
        }
        final int mid = messageObject.getId();
        if (mid <= 0) {
            return false;
        }
        final int maxCapturedAt = index.getMaxCapturedAt(messageObject.getDialogId(), mid);
        return ZgRetentionPolicy.isRevisionVisible(maxCapturedAt, ZgHistorySettings.editCutoff(ZgHistorySettings.localNow()));
    }

    /**
     * The message whose history a menu opens: the long-pressed item first, then the album's caption item, then the
     * other items of the album. Null when none has a version to show.
     */
    public static MessageObject findWithHistory(MessageObject messageObject, MessageObject.GroupedMessages group) {
        if (hasVisibleHistory(messageObject)) {
            return messageObject;
        }
        if (group != null && group.messages != null && !group.messages.isEmpty()) {
            final MessageObject caption = group.findCaptionMessageObject();
            if (caption != null && caption != messageObject && hasVisibleHistory(caption)) {
                return caption;
            }
            for (int i = 0, n = group.messages.size(); i < n; i++) {
                final MessageObject item = group.messages.get(i);
                if (item != messageObject && item != caption && hasVisibleHistory(item)) {
                    return item;
                }
            }
        }
        return null;
    }

    /** Loads the visible versions of (dialogId, mid), newest first, and delivers them on the UI thread. */
    public static void loadRevisions(int account, long dialogId, int mid, Utilities.Callback<ArrayList<ZgHistoryStore.Revision>> onResult) {
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<ZgHistoryStore.Revision> result = new ArrayList<>();
            try {
                if (ZgHistorySettings.isEditHistoryEnabled()) {
                    final SQLiteDatabase db = ZgHistoryStore.prepare(account, storage);
                    if (db != null) {
                        final long selfId = UserConfig.getInstance(account).getClientUserId();
                        result = ZgHistoryStore.loadRevisions(db, dialogId, mid, ZgHistorySettings.editCutoff(ZgHistorySettings.localNow()), selfId);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final ArrayList<ZgHistoryStore.Revision> finalResult = result;
            AndroidUtilities.runOnUIThread(() -> onResult.run(finalResult));
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Settings: count and purge (never gated)
    // ---------------------------------------------------------------------------------------------------------

    /** Messages with saved versions on this device, over every signed-in account, delivered on the UI thread. */
    public static void countAll(Utilities.Callback<Integer> onResult) {
        final ArrayList<Integer> accounts = ZgKeptDeleted.activatedAccounts();
        if (accounts.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onResult.run(0));
            return;
        }
        final AtomicInteger pending = new AtomicInteger(accounts.size());
        final AtomicInteger total = new AtomicInteger();
        for (int account : accounts) {
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                try {
                    final SQLiteDatabase db = ZgHistoryStore.prepare(account, storage);
                    if (db != null) {
                        total.addAndGet(ZgHistoryStore.countMessagesWithRevisions(db, ZgHistorySettings.editCutoff(ZgHistorySettings.localNow())));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        AndroidUtilities.runOnUIThread(() -> onResult.run(total.get()));
                    }
                }
            });
        }
    }

    /** Batches of one "Turn Off and Delete" per account; the sweeper finishes anything left (the cutoff is saved). */
    private static final int PURGE_MAX_STEPS = 1000;

    /**
     * "Turn Off and Delete": deletes, on every signed-in account, the versions captured at or before {@code cutoff}
     * (persisted beforehand by ZgHistorySettings.requestEditHistoryPurge). Versions captured later survive. One batch per
     * storage task, so the rest of the storage queue keeps moving in between; {@code onDone} runs on the UI thread.
     */
    public static void purgeAll(int cutoff, Runnable onDone) {
        final ArrayList<Integer> accounts = ZgKeptDeleted.activatedAccounts();
        if (accounts.isEmpty()) {
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
            return;
        }
        final AtomicInteger pending = new AtomicInteger(accounts.size());
        final Runnable accountDone = () -> {
            if (pending.decrementAndGet() == 0 && onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        };
        for (int account : accounts) {
            purgeStep(account, MessagesStorage.getInstance(account), cutoff, 0, accountDone);
        }
    }

    private static void purgeStep(int account, MessagesStorage storage, int cutoff, int step, Runnable accountDone) {
        storage.getStorageQueue().postRunnable(() -> {
            boolean more = false;
            try {
                final SQLiteDatabase db = ZgHistoryStore.prepare(account, storage);
                if (db != null) {
                    more = ZgHistoryStore.deleteRevisionsUpTo(db, cutoff, ZgHistorySettings.PURGE_BATCH) >= ZgHistorySettings.PURGE_BATCH
                        && step + 1 < PURGE_MAX_STEPS;
                    if (!more) {
                        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
                        if (index != null) {
                            index.pruneEdits(cutoff);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (more) {
                purgeStep(account, storage, cutoff, step + 1, accountDone);
            } else {
                accountDone.run();
            }
        });
    }
}
