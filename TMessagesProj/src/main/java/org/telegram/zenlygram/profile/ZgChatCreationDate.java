/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.profile;

import android.os.SystemClock;
import android.util.SparseArray;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Creation date of a group or channel for the "Created" profile row (iOS PeerInfoData channelCreationTimestamp).
 *
 * <ul>
 *   <li>Basic group: {@code chat.date}, its creation date; no request (iOS usually shows nothing here).</li>
 *   <li>Supergroup migrated from a basic group: the old group's creation date when that chat is known (iOS rule).</li>
 *   <li>History not available to this account ({@code available_min_id > 1}, as iOS minAvailableMessageId): unknown.</li>
 *   <li>Otherwise the date of message #1 (the "channel created" service message), fetched once per account, chat
 *       and process with a read-only channels.getMessages([1]). The reply is not put into MessagesController.</li>
 * </ul>
 * Unknown results are cached too (deleted message #1, private channel, errors), so a profile never asks twice.
 * Nothing is requested while the switch is off: ZgProfileInfo only calls {@link #get} when it is on. UI thread only.
 */
public final class ZgChatCreationDate {

    private static final int UNKNOWN = -1;
    /** A request without a reply after this long no longer blocks a new one (the connection may have been reset). */
    private static final long PENDING_TIMEOUT_MS = 60_000L;

    /** account -> chat id -> date (> 0) or {@link #UNKNOWN}. */
    private static final SparseArray<LongSparseArray<Integer>> cache = new SparseArray<>();
    /** account -> chat id -> request in flight. */
    private static final SparseArray<LongSparseArray<Pending>> pending = new SparseArray<>();

    private static final class Pending {
        final long startedAt = SystemClock.elapsedRealtime();
        final ArrayList<Runnable> callbacks = new ArrayList<>();
    }

    private ZgChatCreationDate() {
    }

    /**
     * The creation date (Unix seconds) of {@code chat}, or 0 when unknown or not loaded yet. In the second case one
     * request is started and {@code onLoaded} runs on the UI thread once the date is known (also when it stays unknown).
     */
    public static int get(int account, TLRPC.Chat chat, TLRPC.ChatFull chatFull, Runnable onLoaded) {
        if (chat == null || chat.id == 0) {
            return 0;
        }
        if (!ChatObject.isChannel(chat)) {
            return Math.max(chat.date, 0);
        }
        if (ChatObject.isCommunity(chat)) {
            // A community is not a channel with a message history: nothing to ask for
            return 0;
        }
        final Integer cached = cacheOf(account).get(chat.id);
        if (cached != null) {
            return cached > 0 ? cached : 0;
        }
        if (chatFull == null) {
            // ProfileActivity loads the full chat right away; the rules below need it, so wait for the next rebuild
            return 0;
        }
        if (chatFull.migrated_from_chat_id != 0) {
            final TLRPC.Chat legacy = MessagesController.getInstance(account).getChat(chatFull.migrated_from_chat_id);
            if (legacy != null && legacy.date > 0) {
                store(account, chat.id, legacy.date);
                return legacy.date;
            }
        }
        if (chatFull.available_min_id > 1) {
            store(account, chat.id, UNKNOWN);
            return 0;
        }
        request(account, chat, onLoaded);
        return 0;
    }

    private static void request(int account, TLRPC.Chat chat, Runnable onLoaded) {
        final long chatId = chat.id;
        final LongSparseArray<Pending> inFlight = pendingOf(account);
        final Pending existing = inFlight.get(chatId);
        if (existing != null && SystemClock.elapsedRealtime() - existing.startedAt < PENDING_TIMEOUT_MS) {
            if (onLoaded != null && !existing.callbacks.contains(onLoaded)) {
                existing.callbacks.add(onLoaded);
            }
            return;
        }
        final TLRPC.InputChannel inputChannel = MessagesController.getInputChannel(chat);
        if (inputChannel == null || inputChannel instanceof TLRPC.TL_inputChannelEmpty) {
            store(account, chatId, UNKNOWN);
            return;
        }
        final Pending request = new Pending();
        if (onLoaded != null) {
            request.callbacks.add(onLoaded);
        }
        inFlight.put(chatId, request);

        final TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = inputChannel;
        req.id.add(1);
        try {
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                int date = UNKNOWN;
                try {
                    if (error == null) {
                        date = parse(account, response);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                store(account, chatId, date);
                if (pendingOf(account).get(chatId) == request) {
                    pendingOf(account).remove(chatId);
                }
                for (int i = 0; i < request.callbacks.size(); i++) {
                    try {
                        request.callbacks.get(i).run();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            }));
        } catch (Throwable e) {
            FileLog.e(e);
            inFlight.remove(chatId);
            store(account, chatId, UNKNOWN);
        }
    }

    /** Message #1: its date, or for "migrated from a basic group" the old group's creation date when known. */
    private static int parse(int account, TLObject response) {
        if (!(response instanceof TLRPC.messages_Messages)) {
            return UNKNOWN;
        }
        final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        TLRPC.Message first = null;
        for (int i = 0; i < res.messages.size(); i++) {
            final TLRPC.Message message = res.messages.get(i);
            if (message != null && message.id == 1) {
                first = message;
                break;
            }
        }
        if (first == null || first instanceof TLRPC.TL_messageEmpty) {
            return UNKNOWN;
        }
        if (first instanceof TLRPC.TL_messageService && first.action instanceof TLRPC.TL_messageActionChannelMigrateFrom) {
            final long legacyId = first.action.chat_id;
            for (int i = 0; i < res.chats.size(); i++) {
                final TLRPC.Chat legacy = res.chats.get(i);
                if (legacy != null && legacy.id == legacyId && legacy.date > 0) {
                    return legacy.date;
                }
            }
            final TLRPC.Chat legacy = MessagesController.getInstance(account).getChat(legacyId);
            if (legacy != null && legacy.date > 0) {
                return legacy.date;
            }
        }
        return first.date > 0 ? first.date : UNKNOWN;
    }

    private static void store(int account, long chatId, int date) {
        cacheOf(account).put(chatId, date > 0 ? date : UNKNOWN);
    }

    private static LongSparseArray<Integer> cacheOf(int account) {
        LongSparseArray<Integer> map = cache.get(account);
        if (map == null) {
            map = new LongSparseArray<>();
            cache.put(account, map);
        }
        return map;
    }

    private static LongSparseArray<Pending> pendingOf(int account) {
        LongSparseArray<Pending> map = pending.get(account);
        if (map == null) {
            map = new LongSparseArray<>();
            pending.put(account, map);
        }
        return map;
    }
}
