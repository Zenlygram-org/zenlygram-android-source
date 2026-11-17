/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.autoaccept;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.ArrayList;

/**
 * Auto-Accept Requests (iOS SGAutoAcceptManager): approves every pending join request of the groups and channels in
 * which the account may add members (creator, or admin with the "add members" right), with one
 * messages.hideAllChatJoinRequests(approved) per chat, the same call as "approve all" on iOS.
 *
 * <ul>
 *   <li>Triggers: a live updatePendingJoinRequests (MemberRequestsController hook), any loaded chat info with pending
 *       requests (chatInfoDidLoad, e.g. when a chat is opened), and a sweep every 60 s over the first 300 chats of
 *       the main list and the archive that reads only the chat info already in memory (no requests to find work).</li>
 *   <li>A chat is approved at most once per 15 s; FLOOD_WAIT pauses the account for the given time, other errors
 *       pause that chat for 5 minutes.</li>
 *   <li>Every logged-in account approves in its own chats. Opt-in, default off; turned off after the last logout
 *       (iOS resetForNoAccounts).</li>
 * </ul>
 * Everything runs on the UI thread.
 */
public final class ZgAutoAccept {

    private static final long COOLDOWN_MS = 15_000L;
    private static final long ERROR_BACKOFF_MS = 5 * 60_000L;
    private static final long SWEEP_INTERVAL_MS = 60_000L;
    private static final long FIRST_SWEEP_DELAY_MS = 10_000L;
    private static final int SWEEP_CHAT_COUNT = 300;

    /** Per account: chat id -> elapsedRealtime before which the chat is not approved again. */
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<Long>[] blockedUntil = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] floodUntil = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private static boolean initialized;
    private static boolean sweepScheduled;

    private static final Runnable sweep = () -> {
        sweepScheduled = false;
        if (!isEnabled()) {
            return;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                sweepAccount(a);
            }
        }
        scheduleSweep(SWEEP_INTERVAL_MS);
    };

    private static final NotificationCenter.NotificationCenterDelegate chatInfoObserver = (id, account, args) -> {
        if (id != NotificationCenter.chatInfoDidLoad || args.length < 1 || !(args[0] instanceof TLRPC.ChatFull) || !isEnabled()) {
            return;
        }
        final TLRPC.ChatFull chatFull = (TLRPC.ChatFull) args[0];
        if (chatFull.requests_pending > 0) {
            approveIfAllowed(account, chatFull.id);
        }
    };

    private ZgAutoAccept() {
    }

    public static boolean isEnabled() {
        return ZgFeatures.AUTO_ACCEPT_REQUESTS.isPorted() && ZgSettings.autoAcceptRequests.get();
    }

    /** ApplicationLoader.postInitApplication. */
    public static void init() {
        AndroidUtilities.runOnUIThread(() -> {
            if (initialized) {
                return;
            }
            initialized = true;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(chatInfoObserver, NotificationCenter.chatInfoDidLoad);
            }
            if (isEnabled()) {
                scheduleSweep(FIRST_SWEEP_DELAY_MS);
            }
        });
    }

    /** The settings switch changed. */
    public static void onSettingChanged(boolean enabled) {
        AndroidUtilities.runOnUIThread(() -> {
            if (enabled) {
                AndroidUtilities.cancelRunOnUIThread(sweep);
                sweepScheduled = false;
                scheduleSweep(500);
            } else {
                AndroidUtilities.cancelRunOnUIThread(sweep);
                sweepScheduled = false;
            }
        });
    }

    /**
     * MemberRequestsController.onPendingRequestsUpdated: the server says {@code chatId} has {@code pending} join
     * requests (also when its chat info is not loaded, which chatInfoDidLoad would miss).
     */
    public static void onPendingRequests(int account, long chatId, int pending) {
        if (pending > 0 && chatId > 0 && isEnabled()) {
            approveIfAllowed(account, chatId);
        }
    }

    /** Logout: nothing more is approved for that slot; the last logout also turns the feature off. */
    public static void onAccountLoggedOut(int account, boolean lastAccount) {
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
            blockedUntil[account] = null;
            floodUntil[account] = 0;
        }
        if (lastAccount) {
            AndroidUtilities.cancelRunOnUIThread(sweep);
            sweepScheduled = false;
            if (ZgSettings.autoAcceptRequests.get()) {
                ZgSettings.autoAcceptRequests.set(false);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void scheduleSweep(long delay) {
        if (sweepScheduled) {
            return;
        }
        sweepScheduled = true;
        AndroidUtilities.runOnUIThread(sweep, delay);
    }

    private static void sweepAccount(int account) {
        final MessagesController messagesController = MessagesController.getInstance(account);
        for (int folderId = 0; folderId <= 1; folderId++) {
            final ArrayList<TLRPC.Dialog> dialogs = messagesController.getDialogs(folderId);
            for (int i = 0, n = Math.min(dialogs.size(), SWEEP_CHAT_COUNT); i < n; i++) {
                final TLRPC.Dialog dialog = dialogs.get(i);
                if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                    continue;
                }
                final TLRPC.ChatFull chatFull = messagesController.getChatFull(-dialog.id);
                if (chatFull != null && chatFull.requests_pending > 0) {
                    approveIfAllowed(account, -dialog.id);
                }
            }
        }
    }

    static boolean canApprove(TLRPC.Chat chat) {
        return chat != null && !chat.deactivated && !ChatObject.isNotInChat(chat)
            && ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_INVITE);
    }

    private static void approveIfAllowed(int account, long chatId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || chatId <= 0 || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final MessagesController messagesController = MessagesController.getInstance(account);
        final TLRPC.Chat chat = messagesController.getChat(chatId);
        if (!canApprove(chat)) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now < floodUntil[account]) {
            return;
        }
        LongSparseArray<Long> blocked = blockedUntil[account];
        if (blocked == null) {
            blockedUntil[account] = blocked = new LongSparseArray<>();
        }
        final Long until = blocked.get(chatId);
        if (until != null && now < until) {
            return;
        }
        final TLRPC.InputPeer peer = messagesController.getInputPeer(-chatId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
            return;
        }
        blocked.put(chatId, now + COOLDOWN_MS);

        final TLRPC.TL_messages_hideAllChatJoinRequests req = new TLRPC.TL_messages_hideAllChatJoinRequests();
        req.approved = true;
        req.peer = peer;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).loadFullChat(chatId, 0, true));
            } else if (error != null) {
                final String text = error.text;
                AndroidUtilities.runOnUIThread(() -> onError(account, chatId, text));
            }
        });
    }

    private static void onError(int account, long chatId, String text) {
        final long now = SystemClock.elapsedRealtime();
        if (text != null && text.startsWith("FLOOD_WAIT_")) {
            final int seconds = Math.max(1, Utilities.parseInt(text));
            floodUntil[account] = now + seconds * 1000L + COOLDOWN_MS;
        } else {
            final LongSparseArray<Long> blocked = blockedUntil[account];
            if (blocked != null) {
                blocked.put(chatId, now + ERROR_BACKOFF_MS);
            }
            if (!TextUtils.isEmpty(text)) {
                FileLog.d("zg auto-accept: " + text);
            }
        }
    }
}
