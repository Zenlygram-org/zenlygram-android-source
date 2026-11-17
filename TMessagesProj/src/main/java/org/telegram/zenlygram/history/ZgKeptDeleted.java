/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageCustomParamsHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Show Deleted Messages: when other people delete a message, its copy stays in the chat, marked with 🗑 (iOS
 * sgRetainDeletedMessages). Nothing is ever sent to a server: kept copies are rows of the local cache that are simply
 * not deleted, plus a stamp in zg_kept_deleted.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #retainOnReplay}: MessagesController.processUpdateArray, right before the deletion lists are handed
 *       to the UI and to the storage. Kept ids are taken out of the lists on the storage queue, ahead of every
 *       upstream consumer (messagesDeleted, markMessagesAsDeleted, dialog updates, notification removal), which all
 *       run after it on that queue and so skip them. The update thread never waits for it.</li>
 *   <li>{@link #retainOnStorageQueue}: the MESSAGE_DELETED push (MessagesController.deleteMessagesByPush).</li>
 *   <li>{@link #removeKeptIds}: MessagesController.deleteMessages, so deleting a kept copy never asks the server.</li>
 *   <li>{@link #isKept}: the MessageObject capability guards and the chat UI (UI thread, index only).</li>
 * </ul>
 * When Show Deleted Messages is off, deletions are replayed exactly as upstream, kept copies included.
 */
public final class ZgKeptDeleted {

    /** Batches of one "Turn Off and Delete" per account; the sweeper finishes anything left (the cutoff is saved). */
    private static final int PURGE_MAX_STEPS = 200;

    private ZgKeptDeleted() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Questions (any thread, index only)
    // ---------------------------------------------------------------------------------------------------------

    /** Whether this message object is a kept copy of a deleted message. O(log n), never touches the database. */
    public static boolean isKept(MessageObject messageObject) {
        return getDeletedAt(messageObject) != 0;
    }

    /** Deletion time (local clock, Unix seconds) of a kept copy, 0 for any other message. */
    public static int getDeletedAt(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return 0;
        }
        final ZgHistoryIndex index = ZgHistoryIndex.peek(messageObject.currentAccount);
        if (index == null || !index.hasAnyKept()) {
            return 0;
        }
        if (messageObject.scheduled || messageObject.eventId != 0 || messageObject.isSponsored()
            || messageObject.isStory() || messageObject.isQuickReply() || messageObject.isEphemeral()) {
            return 0;
        }
        final int mid = messageObject.getId();
        if (mid <= 0) {
            return 0;
        }
        return index.getDeletedAt(messageObject.getDialogId(), mid);
    }

    public static boolean isKept(int account, long dialogId, int mid) {
        final ZgHistoryIndex index = ZgHistoryIndex.peek(account);
        return index != null && mid > 0 && index.isKept(dialogId, mid);
    }

    /** Whether any message of the group (album) is a kept copy. */
    public static boolean isAnyKept(MessageObject messageObject, MessageObject.GroupedMessages group) {
        if (group != null && group.messages != null && !group.messages.isEmpty()) {
            for (int i = 0, n = group.messages.size(); i < n; i++) {
                if (isKept(group.messages.get(i))) {
                    return true;
                }
            }
            return false;
        }
        return isKept(messageObject);
    }

    /** Earliest deletion time over the group (or the message), 0 when nothing in it is kept. */
    public static int getEarliestDeletedAt(MessageObject messageObject, MessageObject.GroupedMessages group) {
        if (group != null && group.messages != null && !group.messages.isEmpty()) {
            int earliest = 0;
            for (int i = 0, n = group.messages.size(); i < n; i++) {
                final int deletedAt = getDeletedAt(group.messages.get(i));
                if (deletedAt != 0 && (earliest == 0 || deletedAt < earliest)) {
                    earliest = deletedAt;
                }
            }
            return earliest;
        }
        return getDeletedAt(messageObject);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------------------------------------------

    /**
     * MessagesController.processUpdateArray, right before the deletion lists go to the UI and to the storage: keeps
     * what the policy keeps and takes those ids out of the lists, in place, on the account's storage queue (inline when
     * already on it). It never waits: every consumer of the lists runs after it on that same queue (the UI dispatch is
     * posted through it, so are the markMessagesAsDeleted runnables, and the notification removal runs inside the UI
     * dispatch), so all of them see the lists without the kept ids. Only the lists' contents change, never the map:
     * the update thread still reads its keys. A list can end up empty; the marked guards in processUpdateArray skip it.
     * Key 0 = private chats and basic groups (no peer in updateDeleteMessages), key -channelId = a channel. Returns
     * {@code deleted} itself. Never throws: on any problem the lists stay as they are, i.e. are deleted as upstream.
     */
    public static LongSparseArray<ArrayList<Integer>> retainOnReplay(int account, LongSparseArray<ArrayList<Integer>> deleted) {
        if (deleted == null || deleted.size() == 0) {
            return deleted;
        }
        try {
            if (!ZgHistorySettings.isShowDeletedMessages()) {
                return deleted;
            }
            final boolean mayCapture = ZgHistorySettings.mayCapture();
            if (!mayCapture) {
                final ZgHistoryIndex index = ZgHistoryIndex.peek(account);
                if (index != null && index.isLoaded() && !index.hasAnyKept()) {
                    return deleted; // nothing new may be kept and nothing kept needs protection
                }
            }
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return deleted;
            }
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            if (ZgHistoryStore.isStorageThread(storage)) {
                retainInPlace(account, storage, deleted, mayCapture);
            } else {
                storage.getStorageQueue().postRunnable(() -> retainInPlace(account, storage, deleted, mayCapture));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return deleted;
    }

    /** Storage queue: the keep decision for {@code deleted}, then its lists lose the kept ids. */
    private static void retainInPlace(int account, MessagesStorage storage, LongSparseArray<ArrayList<Integer>> deleted, boolean mayCapture) {
        try {
            final Retained retained = retainOnStorageThread(account, storage, deleted, mayCapture);
            if (retained != null && !retained.isEmpty()) {
                retained.removeInPlace(deleted);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * MessagesController.deleteMessagesByPush, on the storage queue: returns the push's ids without the kept ones (a
     * new list; the push's own list is still read by the notification queue, so it is never changed).
     */
    public static ArrayList<Integer> retainOnStorageQueue(int account, long dialogId, ArrayList<Integer> ids) {
        if (ids == null) {
            return new ArrayList<>();
        }
        if (ids.isEmpty() || dialogId == 0) {
            return ids;
        }
        try {
            if (!ZgHistorySettings.isShowDeletedMessages()) {
                return ids;
            }
            final boolean mayCapture = ZgHistorySettings.mayCapture();
            final ZgHistoryIndex peek = ZgHistoryIndex.peek(account);
            if (!mayCapture && peek != null && peek.isLoaded() && !peek.hasAnyKept()) {
                return ids;
            }
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            if (!ZgHistoryStore.isStorageThread(storage)) {
                return ids;
            }
            final LongSparseArray<ArrayList<Integer>> request = new LongSparseArray<>(1);
            request.put(dialogId, ids);
            final Retained retained = retainOnStorageThread(account, storage, request, mayCapture);
            if (retained == null || retained.isEmpty()) {
                return ids;
            }
            final ArrayList<Integer> kept = retained.keptByKey.get(dialogId);
            if (kept == null || kept.isEmpty()) {
                return ids;
            }
            final ArrayList<Integer> result = new ArrayList<>(ids);
            result.removeAll(kept);
            return result;
        } catch (Throwable e) {
            FileLog.e(e);
            return ids;
        }
    }

    /**
     * MessagesController.deleteMessages: removes kept copies from the server request (they exist only on this device;
     * they are still deleted locally). Returns whether anything was removed.
     */
    public static boolean removeKeptIds(int account, long dialogId, ArrayList<Integer> toSend) {
        if (toSend == null || toSend.isEmpty() || dialogId == 0) {
            return false;
        }
        try {
            final ZgHistoryIndex index = ZgHistoryIndex.peek(account);
            if (index == null || !index.hasKeptIn(dialogId)) {
                return false;
            }
            boolean removed = false;
            for (int i = toSend.size() - 1; i >= 0; i--) {
                final Integer mid = toSend.get(i);
                if (mid != null && index.isKept(dialogId, mid)) {
                    toSend.remove(i);
                    removed = true;
                }
            }
            return removed;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Removing kept copies (local only)
    // ---------------------------------------------------------------------------------------------------------

    /** Loads the kept copies of the given chats (still in the cache) and delivers them on the UI thread. */
    public static void loadKeptInChats(int account, long[] dialogIds, Utilities.Callback<LongSparseArray<ArrayList<Integer>>> onResult) {
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            final LongSparseArray<ArrayList<Integer>> result = new LongSparseArray<>();
            try {
                final SQLiteDatabase db = ZgHistoryStore.prepare(account, storage);
                if (db != null) {
                    for (long did : dialogIds) {
                        if (did == 0 || result.indexOfKey(did) >= 0) {
                            continue;
                        }
                        final ArrayList<Integer> mids = ZgHistoryStore.keptInChat(db, did);
                        if (!mids.isEmpty()) {
                            result.put(did, mids);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> onResult.run(result));
        });
    }

    /** UI thread: deletes these kept copies from this device (cache only, no server request). */
    public static void deleteLocally(int account, LongSparseArray<ArrayList<Integer>> byDialog) {
        if (byDialog == null) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        for (int i = 0, n = byDialog.size(); i < n; i++) {
            final ArrayList<Integer> mids = byDialog.valueAt(i);
            if (mids != null && !mids.isEmpty()) {
                controller.deleteMessages(new ArrayList<>(mids), null, null, byDialog.keyAt(i), 0, false, 0, true);
            }
        }
    }

    /** Number of kept copies on this device over every signed-in account, delivered on the UI thread. */
    public static void countAll(Utilities.Callback<Integer> onResult) {
        final ArrayList<Integer> accounts = activatedAccounts();
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
                        total.addAndGet(ZgHistoryStore.countKeptExisting(db));
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

    /**
     * "Turn Off and Delete": really deletes, on every signed-in account, the kept copies stamped at or before
     * {@code cutoff} (persisted beforehand by ZgHistorySettings.requestKeptDeletedPurge, so the sweeper finishes an
     * interrupted purge). One batch per storage task, so the rest of the storage queue keeps moving in between.
     * {@code onDone} runs on the UI thread.
     */
    public static void purgeAll(int cutoff, Runnable onDone) {
        final ArrayList<Integer> accounts = activatedAccounts();
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
                    final ArrayList<ZgHistoryStore.KeptRow> rows = ZgHistoryStore.selectKeptUpTo(db, cutoff, ZgHistorySettings.SWEEP_BATCH);
                    if (!rows.isEmpty()) {
                        reallyDelete(account, storage, db, rows);
                        more = rows.size() >= ZgHistorySettings.SWEEP_BATCH && step + 1 < PURGE_MAX_STEPS;
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

    /**
     * Storage queue: deletes kept copies for real, through Telegram's own delete path (as if the deletion had not
     * been kept), and removes their stamps, then updates open chats and notifications.
     */
    static void reallyDelete(int account, MessagesStorage storage, SQLiteDatabase db, ArrayList<ZgHistoryStore.KeptRow> rows) {
        final LongSparseArray<ArrayList<Integer>> byUid = new LongSparseArray<>();
        final LongSparseArray<Long> channelOf = new LongSparseArray<>();
        for (int i = 0, n = rows.size(); i < n; i++) {
            final ZgHistoryStore.KeptRow row = rows.get(i);
            ArrayList<Integer> mids = byUid.get(row.uid);
            if (mids == null) {
                byUid.put(row.uid, mids = new ArrayList<>());
                channelOf.put(row.uid, row.channelId);
            }
            mids.add(row.mid);
        }
        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
        for (int i = 0, n = byUid.size(); i < n; i++) {
            final long uid = byUid.keyAt(i);
            final ArrayList<Integer> mids = byUid.valueAt(i);
            final Long channelValue = channelOf.get(uid);
            final long channelId = channelValue != null ? channelValue : 0;
            try {
                final ArrayList<Long> dialogIds = storage.markMessagesAsDeleted(uid, new ArrayList<>(mids), false, true, 0, 0);
                storage.updateDialogsWithDeletedMessages(uid, channelId, new ArrayList<>(mids), dialogIds);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            try {
                ZgHistoryStore.deleteKept(db, uid, mids);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (index != null) {
                index.removeKept(uid, mids);
            }
            final ArrayList<Integer> uiMids = new ArrayList<>(mids);
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    MessagesController.getInstance(account).markDialogMessageAsDeleted(uid, uiMids);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesDeleted, uiMids, channelId, false);
                    final LongSparseArray<ArrayList<Integer>> notifications = new LongSparseArray<>(1);
                    notifications.put(channelId != 0 ? -channelId : 0, new ArrayList<>(uiMids));
                    NotificationsController.getInstance(account).removeDeletedMessagesFromNotifications(notifications, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        }
    }

    static ArrayList<Integer> activatedAccounts() {
        final ArrayList<Integer> result = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                result.add(a);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The keep decision (storage queue)
    // ---------------------------------------------------------------------------------------------------------

    /** Ids taken out of the deletion lists, by the key of the input map. */
    private static final class Retained {
        final LongSparseArray<ArrayList<Integer>> keptByKey = new LongSparseArray<>();

        boolean isEmpty() {
            return keptByKey.size() == 0;
        }

        void add(long key, int mid) {
            ArrayList<Integer> mids = keptByKey.get(key);
            if (mids == null) {
                keptByKey.put(key, mids = new ArrayList<>());
            }
            mids.add(mid);
        }

        /**
         * Removes the kept ids from the lists of {@code deleted}, in place. The map itself (its keys and list objects) is
         * never changed: another thread may be reading it.
         */
        void removeInPlace(LongSparseArray<ArrayList<Integer>> deleted) {
            for (int i = 0, n = deleted.size(); i < n; i++) {
                final ArrayList<Integer> ids = deleted.valueAt(i);
                final ArrayList<Integer> kept = keptByKey.get(deleted.keyAt(i));
                if (ids != null && kept != null && !kept.isEmpty()) {
                    ids.removeAll(new HashSet<>(kept));
                }
            }
        }
    }

    /** A cached message named by the deletion. */
    private static final class Row {
        final long key;
        final long uid;
        final int mid;
        final boolean out;
        final int readState;
        final boolean mention;
        final long channelId;
        TLRPC.Message message;

        Row(long key, long uid, int mid, boolean out, int readState, boolean mention, long channelId) {
            this.key = key;
            this.uid = uid;
            this.mid = mid;
            this.out = out;
            this.readState = readState;
            this.mention = mention;
            this.channelId = channelId;
        }
    }

    /**
     * Storage queue: reads the named messages that are in the cache, applies the keep policy, stamps the new kept
     * copies and returns every id that must not be deleted (new and already kept copies).
     */
    private static Retained retainOnStorageThread(int account, MessagesStorage storage, LongSparseArray<ArrayList<Integer>> deleted, boolean mayCapture) {
        final SQLiteDatabase db = ZgHistoryStore.prepare(account, storage);
        final ZgHistoryIndex index = ZgHistoryIndex.get(account);
        if (db == null || index == null) {
            return null;
        }
        if (!mayCapture && !index.hasAnyKept()) {
            return null;
        }
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final int now = ZgHistorySettings.localNow();

        // 1. The named messages that are in the cache (a message that is not cached has nothing to keep).
        final ArrayList<Row> rows = new ArrayList<>();
        for (int k = 0, nk = deleted.size(); k < nk; k++) {
            final long key = deleted.keyAt(k);
            final ArrayList<Integer> ids = positiveUnique(deleted.valueAt(k));
            for (int start = 0; start < ids.size(); start += ZgHistorySettings.IN_LIST_CHUNK) {
                final String in = TextUtils.join(",", ids.subList(start, Math.min(ids.size(), start + ZgHistorySettings.IN_LIST_CHUNK)));
                final String sql = key == 0
                    ? String.format(Locale.US, "SELECT uid, mid, out, read_state, mention, is_channel FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", in)
                    : String.format(Locale.US, "SELECT uid, mid, out, read_state, mention, is_channel FROM messages_v2 WHERE mid IN(%s) AND uid = %d", in, key);
                SQLiteCursor cursor = null;
                try {
                    cursor = db.queryFinalized(sql);
                    while (cursor.next()) {
                        rows.add(new Row(key, cursor.longValue(0), cursor.intValue(1), cursor.intValue(2) != 0,
                            cursor.intValue(3), cursor.intValue(4) != 0, cursor.longValue(5)));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    return null;
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            return null;
        }

        // 2. History clears (private chats, basic groups): an outgoing message of the chat or 50+ of its messages.
        final LongSparseIntArray counts = new LongSparseIntArray();
        final HashSet<Long> withOutgoing = new HashSet<>();
        for (int i = 0, n = rows.size(); i < n; i++) {
            final Row row = rows.get(i);
            if (ZgRetentionPolicy.isClearLikeCandidate(row.uid, row.channelId != 0, DialogObject.isEncryptedDialog(row.uid))) {
                counts.put(row.uid, counts.get(row.uid, 0) + 1);
                if (row.out) {
                    withOutgoing.add(row.uid);
                }
            }
        }

        // 3. Already kept copies stay (not restamped); new candidates go through the policy.
        final Retained retained = new Retained();
        final ArrayList<Row> candidates = new ArrayList<>();
        for (int i = 0, n = rows.size(); i < n; i++) {
            final Row row = rows.get(i);
            if (DialogObject.isEncryptedDialog(row.uid)) {
                continue;
            }
            final int count = counts.get(row.uid, 0);
            if (count > 0 && ZgRetentionPolicy.isClearLike(withOutgoing.contains(row.uid), count)) {
                continue; // a history clear deletes everything it names, kept copies included
            }
            if (index.isKept(row.uid, row.mid)) {
                retained.add(row.key, row.mid);
            } else if (mayCapture && !row.out) {
                candidates.add(row);
            }
        }

        // 4. The policy, with the stored message.
        final ArrayList<Row> toStamp = new ArrayList<>();
        if (!candidates.isEmpty()) {
            loadMessages(db, candidates, selfId);
            final LongSparseArray<Boolean> adminCache = new LongSparseArray<>();
            for (int i = 0, n = candidates.size(); i < n; i++) {
                final Row row = candidates.get(i);
                final TLRPC.Message message = row.message;
                if (message == null) {
                    continue;
                }
                final ZgRetentionPolicy.Candidate c = new ZgRetentionPolicy.Candidate();
                c.uid = row.uid;
                c.mid = row.mid;
                c.encrypted = false;
                c.outgoing = row.out || message.out;
                c.fromUserId = message.from_id != null ? message.from_id.user_id : 0;
                c.service = message instanceof TLRPC.TL_messageService;
                c.empty = message instanceof TLRPC.TL_messageEmpty;
                c.ttlPeriod = message.ttl_period;
                c.secretMedia = MessageObject.isSecretMedia(message);
                c.canDeleteOthers = canDeleteOthers(account, storage, row.uid, adminCache);
                c.clearLike = false;
                if (ZgRetentionPolicy.shouldRetain(c, selfId)) {
                    toStamp.add(row);
                }
            }
        }

        if (toStamp.isEmpty() && retained.isEmpty()) {
            return null;
        }

        // 5. Stamps. Only ids whose stamp was written are kept; the others are deleted as upstream.
        if (!toStamp.isEmpty()) {
            final ArrayList<ZgHistoryStore.KeptRow> stampRows = new ArrayList<>(toStamp.size());
            for (int i = 0, n = toStamp.size(); i < n; i++) {
                final Row row = toStamp.get(i);
                stampRows.add(new ZgHistoryStore.KeptRow(row.uid, row.mid, now, row.channelId));
            }
            final boolean[] written = ZgHistoryStore.insertKept(db, stampRows);
            final ArrayList<Row> stamped = new ArrayList<>(toStamp.size());
            for (int i = 0, n = toStamp.size(); i < n; i++) {
                if (!written[i]) {
                    continue;
                }
                final Row row = toStamp.get(i);
                index.putKept(row.uid, row.mid, now);
                retained.add(row.key, row.mid);
                stamped.add(row);
            }
            if (!stamped.isEmpty()) {
                afterStamped(account, storage, db, stamped);
                if (index.getKeptCount() > ZgHistorySettings.KEPT_MAX_PER_ACCOUNT) {
                    ZgRetentionSweeper.scheduleSoon(account);
                }
            }
        }
        return retained;
    }

    /** Reads the stored messages of the candidates, with the cached fields restored as the chat loader does. */
    private static void loadMessages(SQLiteDatabase db, ArrayList<Row> rows, long selfId) {
        final LongSparseArray<ArrayList<Row>> byUid = new LongSparseArray<>();
        for (int i = 0, n = rows.size(); i < n; i++) {
            final Row row = rows.get(i);
            ArrayList<Row> list = byUid.get(row.uid);
            if (list == null) {
                byUid.put(row.uid, list = new ArrayList<>());
            }
            list.add(row);
        }
        for (int i = 0, n = byUid.size(); i < n; i++) {
            final long uid = byUid.keyAt(i);
            final ArrayList<Row> list = byUid.valueAt(i);
            final LongSparseArray<Row> byMid = new LongSparseArray<>(list.size());
            final ArrayList<Integer> mids = new ArrayList<>(list.size());
            for (int j = 0, m = list.size(); j < m; j++) {
                byMid.put(list.get(j).mid, list.get(j));
                mids.add(list.get(j).mid);
            }
            for (int start = 0; start < mids.size(); start += ZgHistorySettings.IN_LIST_CHUNK) {
                final String in = TextUtils.join(",", mids.subList(start, Math.min(mids.size(), start + ZgHistorySettings.IN_LIST_CHUNK)));
                SQLiteCursor cursor = null;
                try {
                    cursor = db.queryFinalized(String.format(Locale.US,
                        "SELECT mid, data, read_state, send_state, date, media, ttl, mention, forwards, replies_data, custom_params FROM messages_v2 WHERE uid = %d AND mid IN(%s)",
                        uid, in));
                    while (cursor.next()) {
                        final Row row = byMid.get(cursor.intValue(0));
                        if (row == null) {
                            continue;
                        }
                        row.message = readMessage(cursor, uid, selfId);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
        }
    }

    private static TLRPC.Message readMessage(SQLiteCursor cursor, long uid, long selfId) throws Exception {
        final NativeByteBuffer data = cursor.byteBufferValue(1);
        if (data == null) {
            return null;
        }
        final TLRPC.Message message;
        try {
            message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message == null) {
                return null;
            }
            message.send_state = cursor.intValue(3);
            message.id = cursor.intValue(0);
            if (message.id > 0 && message.send_state != 0 && message.send_state != 3) {
                message.send_state = 0;
            }
            if (uid == selfId) {
                message.out = true;
            }
            message.readAttachPath(data, selfId);
        } finally {
            data.reuse();
        }
        MessageObject.setUnreadFlags(message, cursor.intValue(2));
        message.date = cursor.intValue(4);
        message.dialog_id = uid;
        if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
            message.views = cursor.intValue(5);
            message.forwards = cursor.intValue(8);
        }
        if (message.ttl == 0) {
            message.ttl = cursor.intValue(6);
        }
        if (cursor.intValue(7) != 0) {
            message.mentioned = true;
        }
        final NativeByteBuffer repliesData = cursor.byteBufferValue(9);
        if (repliesData != null) {
            try {
                final TLRPC.MessageReplies replies = TLRPC.MessageReplies.TLdeserialize(repliesData, repliesData.readInt32(false), false);
                if (replies != null) {
                    message.replies = replies;
                }
            } finally {
                repliesData.reuse();
            }
        }
        final NativeByteBuffer customParams = cursor.byteBufferValue(10);
        if (customParams != null) {
            try {
                MessageCustomParamsHelper.readLocalParams(message, customParams);
            } finally {
                customParams.reuse();
            }
        }
        return message;
    }

    /** Whether this account may delete everyone's messages in the chat (then nothing there is kept). */
    private static boolean canDeleteOthers(int account, MessagesStorage storage, long uid, LongSparseArray<Boolean> cache) {
        if (uid >= 0) {
            return false;
        }
        final Boolean cached = cache.get(uid);
        if (cached != null) {
            return cached;
        }
        final long chatId = -uid;
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(chatId);
        if (chat == null) {
            try {
                final ArrayList<TLRPC.Chat> chats = new ArrayList<>(1);
                storage.getChatsInternal(String.valueOf(chatId), chats);
                if (!chats.isEmpty()) {
                    chat = chats.get(0);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        boolean result;
        if (chat == null) {
            result = false; // unknown chat: keep, as iOS does without a peer
        } else if (ChatObject.isChannel(chat)) {
            result = ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_DELETE_MESSAGES);
        } else {
            result = chat.creator || ChatObject.hasAdminRights(chat);
        }
        cache.put(uid, result);
        return result;
    }

    /** Side effects of new kept copies: no pin, no unread mention, and open chats redraw them with the 🗑 mark. */
    private static void afterStamped(int account, MessagesStorage storage, SQLiteDatabase db, ArrayList<Row> stamped) {
        final LongSparseArray<ArrayList<Row>> byUid = new LongSparseArray<>();
        for (int i = 0, n = stamped.size(); i < n; i++) {
            final Row row = stamped.get(i);
            ArrayList<Row> list = byUid.get(row.uid);
            if (list == null) {
                byUid.put(row.uid, list = new ArrayList<>());
            }
            list.add(row);
        }
        final LongSparseArray<ArrayList<TLRPC.Message>> toRedraw = new LongSparseArray<>();
        for (int i = 0, n = byUid.size(); i < n; i++) {
            final long uid = byUid.keyAt(i);
            final ArrayList<Row> list = byUid.valueAt(i);
            final ArrayList<Integer> mids = new ArrayList<>(list.size());
            for (int j = 0, m = list.size(); j < m; j++) {
                mids.add(list.get(j).mid);
            }
            try {
                final ArrayList<Integer> pinned = new ArrayList<>();
                SQLiteCursor cursor = null;
                try {
                    cursor = db.queryFinalized(String.format(Locale.US, "SELECT mid FROM chat_pinned_v2 WHERE uid = %d AND mid IN(%s)", uid, TextUtils.join(",", mids)));
                    while (cursor.next()) {
                        pinned.add(cursor.intValue(0));
                    }
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
                if (!pinned.isEmpty()) {
                    storage.updatePinnedMessages(uid, pinned, false, -1, 0, false, null);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final ArrayList<TLRPC.Message> messages = new ArrayList<>(list.size());
            for (int j = 0, m = list.size(); j < m; j++) {
                final Row row = list.get(j);
                if (row.mention && (row.readState & 2) == 0) {
                    try {
                        storage.markMentionMessageAsRead(uid, row.mid, uid);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                if (row.message != null) {
                    messages.add(row.message);
                }
            }
            if (!messages.isEmpty()) {
                toRedraw.put(uid, messages);
            }
        }
        if (toRedraw.size() == 0) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0, n = toRedraw.size(); i < n; i++) {
                try {
                    final long uid = toRedraw.keyAt(i);
                    final ArrayList<TLRPC.Message> messages = toRedraw.valueAt(i);
                    final ArrayList<MessageObject> objects = new ArrayList<>(messages.size());
                    for (int j = 0, m = messages.size(); j < m; j++) {
                        final MessageObject object = new MessageObject(account, messages.get(j), true, true);
                        // A cell showing it rebuilds its reactions: a kept copy has none (MessageObject.shouldDrawReactions).
                        object.reactionsChanged = true;
                        objects.add(object);
                    }
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, uid, objects);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    private static ArrayList<Integer> positiveUnique(ArrayList<Integer> ids) {
        final ArrayList<Integer> result = new ArrayList<>(ids == null ? 0 : ids.size());
        if (ids == null) {
            return result;
        }
        final HashSet<Integer> seen = new HashSet<>();
        for (int i = 0, n = ids.size(); i < n; i++) {
            final Integer id = ids.get(i);
            if (id != null && id > 0 && seen.add(id)) {
                result.add(id);
            }
        }
        return result;
    }
}
