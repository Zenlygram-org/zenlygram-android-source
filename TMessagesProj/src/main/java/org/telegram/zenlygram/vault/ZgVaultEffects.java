/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import android.text.TextUtils;

import androidx.annotation.MainThread;
import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.chatlock.ZgChatLockSession;
import org.telegram.zenlygram.lock.ZgLockSurfaces;
import org.telegram.zenlygram.pins.ZgPins;

import java.util.ArrayList;

/**
 * What hiding and unhiding do besides the membership itself (iOS SecretVaultEffects), main thread, in order.
 *
 * <p>Android keeps every effect local except one: a hidden chat counts as muted inside the app (MessagesController
 * isDialogMuted / NotificationsController hooks) instead of being muted on the server, and it stays in its folder
 * (main list or archive). Only a pin is changed on the server: a hidden pinned chat would still use a pin slot and
 * Telegram's "reorder pins" would silently drop it, so the vault unpins it, records that, and pins it again when the
 * chat leaves the vault (if a pin slot is free). Nothing else on the account changes; nothing is ever deleted.
 *
 * <p>After every change: notifications of the hidden chats are removed, their conversation shortcuts and top-peer hints
 * go, the unread counters are recounted, the lists re-sorted, contacts, stories, the app badge and the home-screen
 * widgets refreshed, and Chat Lock's gates re-check open screens.
 */
final class ZgVaultEffects {

    private static final int MAX_MISSING_LOADS = 50;
    private static final boolean[] loadingMissing = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgVaultEffects() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hide / unhide
    // ---------------------------------------------------------------------------------------------------------

    /** Hides {@code dialogIds} (already checked as hideable and Pro-gated by the caller). False: nothing changed. */
    @MainThread
    static boolean hide(int account, long[] dialogIds) {
        final long[] ids = ZgVaultState.normalize(dialogIds);
        if (ids.length == 0) {
            return false;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        final ArrayList<Long> pinned = new ArrayList<>();
        for (long id : ids) {
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(id);
            if (dialog instanceof TLRPC.TL_dialog && dialog.pinned) {
                pinned.add(id);
            }
        }
        final long[] unpin = toArray(pinned);
        final int zgHiddenBefore = ZgVault.getMemberCount(account); // ZENLYGRAM: statistics, to count the chats that enter
        if (!ZgVault.add(account, ids, unpin)) {
            return false;
        }
        for (long id : unpin) {
            try {
                controller.pinDialog(id, false, null, 0);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        org.telegram.zenlygram.customer.ZgStats.vaultChats(account, ZgVault.getMemberCount(account) - zgHiddenBefore); // ZENLYGRAM: statistics, vault.chat_added per chat (recorded only with the data consent)
        refreshAccount(account, ids, true);
        return true;
    }

    /** Unhides {@code dialogIds} and pins again what the vault unpinned. False: nothing changed. */
    @MainThread
    static boolean unhide(int account, long[] dialogIds) {
        final long[] ids = ZgVaultState.normalize(dialogIds);
        if (ids.length == 0) {
            return false;
        }
        final ArrayList<Long> repin = new ArrayList<>();
        for (long id : ids) {
            if (ZgVault.wasUnpinned(account, id)) {
                repin.add(id);
            }
        }
        final int zgHiddenBefore = ZgVault.getMemberCount(account); // ZENLYGRAM: statistics, to count the chats that leave
        if (!ZgVault.remove(account, ids)) {
            return false;
        }
        for (Long id : repin) {
            repin(account, id);
        }
        org.telegram.zenlygram.customer.ZgStats.vaultChats(account, ZgVault.getMemberCount(account) - zgHiddenBefore); // ZENLYGRAM: statistics, vault.chat_removed per chat (recorded only with the data consent)
        refreshAccount(account, ids, false);
        return true;
    }

    /**
     * Turns the vault off: every vault closes, the switch, PIN and memberships go in one commit (all hidden chats show
     * again in every account), then recorded pins come back. False: the commit failed and nothing changed.
     */
    @MainThread
    static boolean turnOff() {
        final ZgVaultSession session = ZgVaultSession.peek();
        if (session != null) {
            session.closeAll(ZgVaultSession.CLOSE_TURNED_OFF);
        }
        final ZgVaultState previous = ZgVault.clearAll();
        if (previous == null) {
            return false;
        }
        org.telegram.zenlygram.customer.ZgStats.settingToggled(UserConfig.selectedAccount, "secretVaultEnabled", false); // ZENLYGRAM: statistics, feature.toggle (recorded only with the data consent)
        for (int account : ZgVault.activatedAccounts()) {
            final long uid = ZgVault.uidOf(account);
            for (long id : previous.unpinned(uid)) {
                repin(account, id);
            }
            refreshAccount(account, previous.members(uid), false);
        }
        return true;
    }

    /** A hidden basic group became a supergroup: the new id is hidden too (never Pro-gated). */
    @MainThread
    static void carryOver(int account, long uid, long sourceId, long targetId) {
        if (ZgVault.uidOf(account) != uid) {
            return;
        }
        if (ZgVault.carryOver(uid, sourceId, targetId)) {
            refreshAccount(account, new long[]{targetId}, true);
        }
    }

    /** The membership could only be read some time after start-up (busy Keystore): apply it everywhere now. */
    @MainThread
    static void onMembershipLoadedLate() {
        for (int account : ZgVault.activatedAccounts()) {
            refreshAccount(account, ZgVault.getMembers(account), true);
        }
        // Everything was redacted meanwhile (ZgVault.isFailingClosed): the other chats' notifications get their text back.
        ZgLockSurfaces.repostNotifications();
    }

    /** Logout: that account's vault closes and is forgotten; after the last account the whole vault resets. */
    @MainThread
    static void onAccountLogout(int account, long uid) {
        final ZgVaultSession session = ZgVaultSession.peek();
        if (session != null && uid != 0) {
            session.closeUid(uid, ZgVaultSession.CLOSE_LOGOUT);
        }
        ZgVault.publishDialogs(account, null);
        boolean othersRemain = false;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (a != account && UserConfig.getInstance(a).isClientActivated()) {
                othersRemain = true;
                break;
            }
        }
        if (!othersRemain) {
            if (session != null) {
                session.closeAll(ZgVaultSession.CLOSE_LOGOUT);
            }
            ZgVault.clearAll();
        } else {
            ZgVault.forgetAccount(uid);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------------------------------------------

    @MainThread
    static void refreshAccount(int account, long[] changedIds, boolean nowHidden) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final NotificationCenter center = NotificationCenter.getInstance(account);
        if (nowHidden && changedIds.length > 0) {
            final NotificationsController notifications = NotificationsController.getInstance(account);
            for (long id : changedIds) {
                try {
                    notifications.removeNotificationsForDialog(id);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            removeConversationShortcuts(changedIds);
            try {
                final MediaDataController media = MediaDataController.getInstance(account);
                ZgVaultHooks.filterTopPeers(account, media.hints);
                media.buildShortcuts();
                center.postNotificationName(NotificationCenter.reloadHints);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        try {
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> storage.resetAllUnreadCounters(false));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            // An empty chats map instead of null: the lists are rebuilt even while the main screen is paused.
            MessagesController.getInstance(account).sortDialogs(new LongSparseArray<>());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        center.postNotificationName(NotificationCenter.dialogsNeedReload);
        center.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
        try {
            ZgVaultHooks.rebuildContacts(account);
            center.postNotificationName(NotificationCenter.contactsDidLoad);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        center.postNotificationName(NotificationCenter.storiesUpdated);
        try {
            NotificationsController.getInstance(account).updateBadge();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        refreshWidgets();
        try {
            ZgChatLockSession.getInstance().notifyProtectionChanged();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void repin(int account, long dialogId) {
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (!(dialog instanceof TLRPC.TL_dialog) || dialog.pinned) {
                return;
            }
            final boolean secret = DialogObject.isEncryptedDialog(dialogId);
            if (canPinMore(account, controller, dialog.folder_id, secret)) {
                controller.pinDialog(dialogId, true, null, 0);
                return;
            }
            // Telegram's limit is reached. With Unlimited Pinned Chats the chat comes back as a device-local extra pin
            // (ZgPins: never sent to the server, so no toggleDialogPin), instead of losing its pin. If ZgPins, which
            // counts every loaded pin of the folder, still finds a free slot, it is a normal pin.
            if (ZgPins.isEnabled() && !secret) {
                ZgPins.beforeUserPin(account, dialogId, true);
                controller.pinDialog(dialogId, true, null, ZgPins.isExtra(account, dialog.folder_id, dialogId) ? -1 : 0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Whether one more pin of this kind fits in the folder. As in DialogsActivity's own check, secret chats (pinned on
     * this device only) and cloud chats each have their own count against the limit; the device-local extras of
     * Unlimited Pinned Chats do not count, and for secret chats that switch lifts the limit as in DialogsActivity.
     */
    private static boolean canPinMore(int account, MessagesController controller, int folderId, boolean secret) {
        final ArrayList<TLRPC.Dialog> dialogs = controller.getDialogs(folderId);
        int pinnedCount = 0;
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog instanceof TLRPC.TL_dialogFolder) {
                continue;
            }
            if (dialog.pinned) {
                if (DialogObject.isEncryptedDialog(dialog.id) == secret && !ZgPins.isExtra(account, folderId, dialog.id)) {
                    pinnedCount++;
                }
            } else if (!controller.isPromoDialog(dialog.id, false)) {
                break;
            }
        }
        final boolean premium = UserConfig.getInstance(account).isPremium();
        int max = folderId == 0
            ? (premium ? controller.maxPinnedDialogsCountPremium : controller.maxPinnedDialogsCountDefault)
            : (premium ? controller.maxFolderPinnedDialogsCountPremium : controller.maxFolderPinnedDialogsCountDefault);
        if (secret && ZgPins.isEnabled()) {
            max = Math.max(max, ZgPins.UNLIMITED);
        }
        return pinnedCount < max;
    }

    /** The notification conversation shortcuts ("ndid_") of hidden chats (they show name and avatar). */
    private static void removeConversationShortcuts(long[] dialogIds) {
        ZgLockSurfaces.removeConversationShortcuts(dialogIds);
    }

    /** The chats and contacts widgets re-read their rows (hidden chats drop out of them). */
    static void refreshWidgets() {
        ZgLockSurfaces.refreshWidgets();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hidden chats that are not loaded yet
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The vault list must be complete although the chat list loads dialogs page by page: hidden dialogs that are not in
     * memory yet are loaded the way Telegram loads any unknown dialog (users / chats from the local database first,
     * then MessagesController.loadUnknownDialog: messages.getPeerDialogs, stored like a normal page, sorted straight into
     * the vault list). At most {@value #MAX_MISSING_LOADS} per opening. Secret chats are local and come with the normal
     * loading.
     */
    @MainThread
    static void loadMissing(int account) {
        if (!ZgVault.isOperable() || account < 0 || account >= loadingMissing.length || loadingMissing[account]) {
            return;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        final ArrayList<Long> users = new ArrayList<>();
        final ArrayList<Long> chats = new ArrayList<>();
        final ArrayList<Long> missing = new ArrayList<>();
        for (long id : ZgVault.getMembers(account)) {
            if (controller.dialogs_dict.get(id) != null || DialogObject.isEncryptedDialog(id)) {
                continue;
            }
            missing.add(id);
            if (id > 0) {
                if (controller.getUser(id) == null) {
                    users.add(id);
                }
            } else if (controller.getChat(-id) == null) {
                chats.add(-id);
            }
            if (missing.size() >= MAX_MISSING_LOADS) {
                break;
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        loadingMissing[account] = true;
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            final ArrayList<TLRPC.User> loadedUsers = new ArrayList<>();
            final ArrayList<TLRPC.Chat> loadedChats = new ArrayList<>();
            try {
                if (!users.isEmpty()) {
                    storage.getUsersInternal(users, loadedUsers);
                }
                if (!chats.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", chats), loadedChats);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    controller.putUsers(loadedUsers, true);
                    controller.putChats(loadedChats, true);
                    for (long id : missing) {
                        if (controller.dialogs_dict.get(id) != null || !ZgVault.isHidden(account, id)) {
                            continue;
                        }
                        // An unknown peer would give a wrong input peer (no access hash, a channel taken for a chat).
                        if (id > 0 ? controller.getUser(id) == null : controller.getChat(-id) == null) {
                            continue;
                        }
                        final TLRPC.InputPeer peer = controller.getInputPeer(id);
                        if (peer != null && !(peer instanceof TLRPC.TL_inputPeerEmpty)) {
                            if (!ZgVaultHooks.loadUnknownDialog(account, peer)) {
                                break;
                            }
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    loadingMissing[account] = false;
                }
            });
        });
    }

    private static long[] toArray(ArrayList<Long> list) {
        final long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
