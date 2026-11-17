/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * One-line entry points for upstream Telegram code (every call site is marked "ZENLYGRAM: Secret Vault"). All of them
 * exit at once on a volatile flag when the vault is off, are thread-safe (immutable snapshots) and never throw.
 *
 * <p>The main choke point is MessagesController.sortDialogs: {@link #consumeDialog} takes every hidden dialog out of
 * the main list, the archive, folders, communities, the forward / share pickers and the unread-unmuted count, and
 * collects it into the vault list instead. Surfaces that sortDialogs does not build (counters, notifications, search,
 * contacts, hints, share sheets, widgets, stories) call {@link #isHidden} directly.
 */
public final class ZgVaultHooks {

    private static final class SortState {
        long uid;
        ZgVaultState snapshot;
        final ArrayList<TLRPC.Dialog> list = new ArrayList<>();
        /** New supergroup ids of hidden basic groups (they inherit the membership). */
        long[] migrationTargets = new long[0];
        long[] migrationSources = new long[0];
    }

    /** Registered by ContactsController (it owns the private section builder). */
    public interface ContactsRebuilder {
        void rebuild(int account);
    }

    /** Registered by MessagesController (its loadUnknownDialog is not public). */
    public interface DialogLoader {
        void load(int account, TLRPC.InputPeer peer);
    }

    private static final SortState[] sorts = new SortState[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile ContactsRebuilder contactsRebuilder;
    private static volatile DialogLoader dialogLoader;

    private ZgVaultHooks() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // MessagesController.sortDialogs (main thread)
    // ---------------------------------------------------------------------------------------------------------

    /** After the lists were cleared, before the folder-filter and main loops. */
    public static void onSortDialogsBegin(int account) {
        if (account < 0 || account >= sorts.length) {
            return;
        }
        try {
            if (!ZgVault.hasMembers()) {
                sorts[account] = null;
                return;
            }
            final long uid = UserConfig.getInstance(account).getClientUserId();
            final ZgVaultState snapshot = ZgVault.snapshot();
            if (uid == 0 || snapshot.count(uid) == 0) {
                sorts[account] = null;
                return;
            }
            final SortState sort = new SortState();
            sort.uid = uid;
            sort.snapshot = snapshot;
            findMigrations(account, sort);
            sorts[account] = sort;
        } catch (Throwable e) {
            FileLog.e(e);
            sorts[account] = null;
        }
    }

    /**
     * First statement of the main allDialogs loop. True: {@code dialog} is hidden; it was added to the vault list and
     * the loop must skip it ({@code continue}). Folder rows, community rows, Saved Messages, the promo dialog and a
     * hidden basic group that has just become a supergroup (upstream removes it) are never consumed.
     */
    public static boolean consumeDialog(int account, TLRPC.Dialog dialog, @Nullable LongSparseArray<TLRPC.Chat> chatsDict) {
        if (account < 0 || account >= sorts.length) {
            return false;
        }
        final SortState sort = sorts[account];
        if (sort == null || !(dialog instanceof TLRPC.TL_dialog)) {
            return false;
        }
        try {
            final long dialogId = dialog.id;
            if (dialogId == 0 || dialogId == sort.uid) {
                return false;
            }
            final boolean member = sort.snapshot.isMember(sort.uid, dialogId);
            if (!member && Arrays.binarySearch(sort.migrationTargets, dialogId) < 0) {
                return false;
            }
            final MessagesController controller = MessagesController.getInstance(account);
            if (controller.isPromoDialog(dialogId, false)) {
                return false;
            }
            if (member && chatsDict != null && DialogObject.isChatDialog(dialogId) && !DialogObject.isChannel(dialog)) {
                final TLRPC.Chat chat = chatsDict.get(-dialogId);
                if (chat != null && chat.migrated_to != null) {
                    return false;
                }
            }
            sort.list.add(dialog);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Before hasArchivedChats is computed: publishes the vault list and carries memberships over migrations. */
    public static void onSortDialogsEnd(int account) {
        if (account < 0 || account >= sorts.length) {
            return;
        }
        final SortState sort = sorts[account];
        sorts[account] = null;
        try {
            if (sort == null) {
                ZgVault.publishDialogs(account, null);
                return;
            }
            ZgVault.publishDialogs(account, sort.list);
            if (sort.migrationTargets.length > 0) {
                final long uid = sort.uid;
                final long[] sources = sort.migrationSources;
                final long[] targets = sort.migrationTargets;
                AndroidUtilities.runOnUIThread(() -> {
                    for (int i = 0; i < targets.length; i++) {
                        ZgVaultEffects.carryOver(account, uid, sources[i], targets[i]);
                    }
                });
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void findMigrations(int account, SortState sort) {
        final long[] members = sort.snapshot.members(sort.uid);
        long[] sources = null;
        long[] targets = null;
        int count = 0;
        final MessagesController controller = MessagesController.getInstance(account);
        for (long dialogId : members) {
            if (dialogId >= 0 || DialogObject.isEncryptedDialog(dialogId)) {
                continue;
            }
            final TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat == null || chat.migrated_to == null || chat.migrated_to.channel_id == 0) {
                continue;
            }
            final long target = -chat.migrated_to.channel_id;
            if (sort.snapshot.isMember(sort.uid, target)) {
                continue;
            }
            if (sources == null) {
                sources = new long[members.length];
                targets = new long[members.length];
            }
            sources[count] = dialogId;
            targets[count] = target;
            count++;
        }
        if (count > 0) {
            // Sorted by target for the binary search; sources follow their targets.
            final long[][] pairs = new long[count][];
            for (int i = 0; i < count; i++) {
                pairs[i] = new long[]{targets[i], sources[i]};
            }
            Arrays.sort(pairs, (a, b) -> Long.compare(a[0], b[0]));
            sort.migrationTargets = new long[count];
            sort.migrationSources = new long[count];
            for (int i = 0; i < count; i++) {
                sort.migrationTargets[i] = pairs[i][0];
                sort.migrationSources[i] = pairs[i][1];
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Point checks (any thread)
    // ---------------------------------------------------------------------------------------------------------

    /** Whether {@code dialogId} is hidden in the vault of account slot {@code account}. */
    public static boolean isHidden(int account, long dialogId) {
        return ZgVault.isHidden(account, dialogId);
    }

    /** Search results / recents: a user, chat or secret chat that is hidden must not be listed. */
    public static boolean hideSearchObject(int account, @Nullable Object object) {
        if (!ZgVault.hasMembers() || object == null) {
            return false;
        }
        long dialogId = 0;
        if (object instanceof TLRPC.User) {
            dialogId = ((TLRPC.User) object).id;
        } else if (object instanceof TLRPC.Chat) {
            dialogId = -((TLRPC.Chat) object).id;
        } else if (object instanceof TLRPC.EncryptedChat) {
            dialogId = DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) object).id);
        }
        return dialogId != 0 && ZgVault.isHidden(account, dialogId);
    }

    /** Main thread: removes hidden peers from a top-peers list in place (search hints, share hints, shortcuts). */
    public static void filterTopPeers(int account, @Nullable ArrayList<TLRPC.TL_topPeer> peers) {
        if (peers == null || peers.isEmpty() || !ZgVault.hasMembers()) {
            return;
        }
        try {
            for (int i = peers.size() - 1; i >= 0; i--) {
                final TLRPC.TL_topPeer peer = peers.get(i);
                if (peer != null && peer.peer != null && ZgVault.isHidden(account, DialogObject.getPeerDialogId(peer.peer))) {
                    peers.remove(i);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Removes hidden dialog ids from a list in place (home-screen widgets). */
    public static void filterDialogIds(int account, @Nullable ArrayList<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty() || !ZgVault.hasMembers()) {
            return;
        }
        try {
            for (int i = dialogIds.size() - 1; i >= 0; i--) {
                final Long dialogId = dialogIds.get(i);
                if (dialogId != null && ZgVault.isHidden(account, dialogId)) {
                    dialogIds.remove(i);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** ChatActivity's FlagSecureReason: a hidden chat is never captured (screenshots, recording, app switcher). */
    public static boolean isSecureScreen(int account, long dialogId) {
        return ZgVault.isHidden(account, dialogId);
    }

    /**
     * PhotoViewer.openPhoto (its own window): FLAG_SECURE for media of a hidden chat, and for anything opened while a vault
     * is open (the gallery can page on to other media of the same chat; it closes when the vault closes).
     */
    public static boolean isSecureViewer(@Nullable MessageObject message, @Nullable ChatActivity chat, long dialogId) {
        if (!ZgVault.isActive()) {
            return false;
        }
        try {
            if (ZgVaultSession.isAnyOpen()) {
                return true;
            }
            if (message != null && ZgVault.isHidden(message.currentAccount, message.getDialogId())) {
                return true;
            }
            if (chat != null && ZgVault.isHidden(chat.getCurrentAccount(), chat.getDialogId())) {
                return true;
            }
            final int account = message != null ? message.currentAccount : chat != null ? chat.getCurrentAccount() : UserConfig.selectedAccount;
            return dialogId != 0 && ZgVault.isHidden(account, dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    /** Removes hidden dialog ids from a copy of {@code ids} (lists that show names: folder editor rows). */
    public static ArrayList<Long> withoutHidden(int account, @Nullable ArrayList<Long> ids) {
        final ArrayList<Long> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (int i = 0; i < ids.size(); i++) {
            final Long id = ids.get(i);
            if (id != null && !ZgVault.isHidden(account, id)) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * UsersSelectActivity's result: the hidden chats of the initial selection never had a chip (so they were not
     * deselected); they stay selected, so saving a folder never drops a hidden chat from it.
     */
    public static void keepHiddenSelected(int account, @Nullable ArrayList<Long> initialIds, @Nullable ArrayList<Long> result) {
        if (initialIds == null || result == null || !ZgVault.hasMembers()) {
            return;
        }
        for (int i = 0; i < initialIds.size(); i++) {
            final Long id = initialIds.get(i);
            if (id != null && ZgVault.isHidden(account, id) && !result.contains(id)) {
                result.add(id);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    /**
     * MessagesController.performLogout, before the account's config is cleared: that account's vault closes and its
     * hidden chats are forgotten; the last logout resets the whole vault (PIN, switch, throttle).
     */
    public static void onAccountLogout(int account, long uid) {
        if (!ZgVault.isPorted()) {
            return;
        }
        final Runnable work = () -> {
            try {
                ZgVaultEffects.onAccountLogout(account, uid);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        };
        if ((android.os.Looper.myLooper() == android.os.Looper.getMainLooper())) {
            work.run();
        } else {
            AndroidUtilities.runOnUIThread(work);
        }
    }

    /** ContactsController's static initializer. */
    public static void setContactsRebuilder(@Nullable ContactsRebuilder rebuilder) {
        contactsRebuilder = rebuilder;
    }

    /** MessagesController's static initializer. */
    public static void setDialogLoader(@Nullable DialogLoader loader) {
        dialogLoader = loader;
    }

    /** Main thread: rebuilds the account's contact sections (hidden contacts leave or come back). */
    static void rebuildContacts(int account) {
        final ContactsRebuilder rebuilder = contactsRebuilder;
        if (rebuilder != null) {
            rebuilder.rebuild(account);
        }
    }

    /** Main thread: loads one unknown dialog the way Telegram does (messages.getPeerDialogs, stored, sorted). */
    static boolean loadUnknownDialog(int account, TLRPC.InputPeer peer) {
        final DialogLoader loader = dialogLoader;
        if (loader == null) {
            return false;
        }
        loader.load(account, peer);
        return true;
    }

    /** LaunchActivity.needPresentFragment (tablets): the vault list is presented in the main (left) stack. */
    public static boolean isVaultScreen(@Nullable Object fragment) {
        return fragment instanceof ZgVaultActivity;
    }

    /** LaunchActivity.onUserInteraction. */
    public static void noteUserActivity() {
        ZgVaultSession.noteUserActivity();
        if (ZgVault.isTemporarilyUnavailable()) {
            ZgVault.onUserActivity();
        }
    }
}
