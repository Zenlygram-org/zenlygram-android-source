/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pins;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.dialogs.ZgForeignNumbers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * Unlimited Pinned Chats (iOS #33, TogglePeerChatPinned + the SGLocalExtraPins store): with the switch on, a chat
 * pinned above Telegram's limit in the main list or the archive becomes a device-local "extra" pin.
 *
 * <ul>
 *   <li>Extras sit below the synced pins (lower pinnedNum; MessagesController's dialogComparator puts higher numbers
 *       first), in the order they were pinned, and survive restarts (dialogs table + the store below).</li>
 *   <li>They never reach Telegram: reorderPinnedDialogs leaves them out of the order it sends, so the server never
 *       sees a list above its limit and other devices never see them. Unpinning an extra is local too.</li>
 *   <li>Every pinned list that comes from the server (getPinnedDialogs, dialog pages, a dialogs reset) drops them;
 *       the hooks put them back below the synced pins. An extra that the server now reports as pinned (pinned on
 *       another device) becomes a normal synced pin; one that moved to another folder or was deleted is forgotten.</li>
 *   <li>The switch only gates creating new extras: turning it off keeps the existing ones until they are unpinned,
 *       as on iOS, and they do not count against Telegram's limit in the pin checks. With an empty store every hook
 *       is a no-op, so behaviour is exactly upstream.</li>
 *   <li>Secret chats are pinned locally on Android anyway (their own count), so they never become extras.</li>
 * </ul>
 *
 * <p>Store: MessagesController.getMainSettings(account), keys {@code zg_pins_extra_0} / {@code zg_pins_extra_1}
 * (comma-separated dialog ids in pin order) and {@code zg_pins_owner} (the user id they belong to, because Android
 * reuses account slots). Every method runs on the UI thread, like the MessagesController code that calls it.
 */
public final class ZgPins {

    private static final String KEY_OWNER = "zg_pins_owner";
    private static final String KEY_EXTRA = "zg_pins_extra_";
    /** iOS raises the local cap to 1000 as well. */
    public static final int UNLIMITED = 1000;

    /** Extras per account and folder (0 = main list, 1 = archive), in pin order; null = not loaded yet. */
    @SuppressWarnings("unchecked")
    private static final ArrayList<Long>[][] extras = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT][2];
    private static final long[] loadedOwner = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgPins() {
    }

    public static boolean isEnabled() {
        return ZgFeatures.UNLIMITED_PINNED_CHATS.isPorted() && ZgSettings.unlimitedPinnedChats.get();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hooks
    // ---------------------------------------------------------------------------------------------------------

    /**
     * DialogsActivity's pin limit checks: no limit in the main list and the archive while the switch is on. While it
     * is off, the extras kept from before are added to Telegram's limit: those checks count them as pinned, but they
     * take no server slot, so they must not hide Pin or open the limit sheet. Custom folders ({@code filter != null})
     * keep Telegram's own limit, as on iOS. With an empty store the limit is exactly Telegram's.
     */
    public static int effectiveMaxPinned(int account, MessagesController.DialogFilter filter, int folderId, int maxPinnedCount) {
        if (filter != null) {
            return maxPinnedCount;
        }
        if (isEnabled()) {
            return Math.max(maxPinnedCount, UNLIMITED);
        }
        return maxPinnedCount + loadedExtraCount(account, folderId);
    }

    /** Extras of {@code folderId} that are loaded and pinned there, i.e. the ones a pin count of that list includes. */
    static int loadedExtraCount(int account, int folderId) {
        if ((folderId != 0 && folderId != 1) || !valid(account)) {
            return 0;
        }
        final ArrayList<Long> list = load(account, folderId);
        if (list.isEmpty()) {
            return 0;
        }
        final MessagesController messagesController = MessagesController.getInstance(account);
        int count = 0;
        for (Long id : list) {
            final TLRPC.Dialog d = messagesController.dialogs_dict.get(id);
            if (d != null && d.pinned && d.folder_id == folderId && !(d instanceof TLRPC.TL_dialogFolder)) {
                count++;
            }
        }
        return count;
    }

    /**
     * DialogsActivity.pinDialog (the user pins or unpins a chat of the main list or the archive), just before
     * MessagesController.pinDialog: a pin above Telegram's limit is recorded as an extra.
     */
    public static void beforeUserPin(int account, long dialogId, boolean pin) {
        if (!pin || !isEnabled() || DialogObject.isEncryptedDialog(dialogId) || !valid(account)) {
            return;
        }
        final MessagesController messagesController = MessagesController.getInstance(account);
        final TLRPC.Dialog dialog = messagesController.dialogs_dict.get(dialogId);
        if (dialog == null || dialog.pinned || dialog instanceof TLRPC.TL_dialogFolder) {
            return;
        }
        final int folderId = dialog.folder_id;
        if (folderId != 0 && folderId != 1) {
            return;
        }
        final ArrayList<Long> list = load(account, folderId);
        if (list.contains(dialogId)) {
            return;
        }
        // Every loaded pin of the folder counts, also chats another feature keeps out of the visible list.
        int synced = 0;
        for (int i = 0, n = messagesController.dialogs_dict.size(); i < n; i++) {
            final TLRPC.Dialog d = messagesController.dialogs_dict.valueAt(i);
            if (d == null || d instanceof TLRPC.TL_dialogFolder || !d.pinned || d.folder_id != folderId) {
                continue;
            }
            if (!DialogObject.isEncryptedDialog(d.id) && !list.contains(d.id)) {
                synced++;
            }
        }
        if (synced >= serverLimit(account, folderId)) {
            list.add(dialogId);
            save(account, folderId);
        }
    }

    /**
     * MessagesController.pinDialog, after the new pinned state is set and before the list is sorted: an extra goes
     * below the synced pins; an unpinned extra (by the user or the server) is forgotten.
     */
    public static void onPinDialog(int account, TLRPC.Dialog dialog, int folderId, boolean pin) {
        if (dialog == null || (folderId != 0 && folderId != 1) || !valid(account)) {
            return;
        }
        final ArrayList<Long> list = load(account, folderId);
        if (list.isEmpty() || !list.contains(dialog.id)) {
            return;
        }
        if (!pin) {
            list.remove(dialog.id);
            save(account, folderId);
            return;
        }
        place(account, folderId, list);
    }

    /** processUpdateArray, TL_updateDialogPinned: a chat pinned on another device is a normal synced pin now. */
    public static void onServerDialogPinned(int account, long dialogId, boolean pinned) {
        if (!pinned || !valid(account)) {
            return;
        }
        for (int folderId = 0; folderId <= 1; folderId++) {
            final ArrayList<Long> list = load(account, folderId);
            if (list.remove(dialogId)) {
                save(account, folderId);
            }
        }
    }

    /**
     * reorderPinnedDialogs, before it reads the list (a drag ended or the pinned set changed): keeps the extras
     * below the synced pins, in the order the user dragged them to, and makes the list order match the pinned
     * numbers again. The caller then sends only the synced pins ({@link #isExtra}).
     */
    public static void beforeReorder(int account, int folderId) {
        if (!valid(account)) {
            return;
        }
        final ArrayList<Long> list = (folderId == 0 || folderId == 1) ? load(account, folderId) : null;
        if ((list == null || list.isEmpty()) && !ZgForeignNumbers.isEnabled()) {
            return; // nothing of ours involved: exactly upstream
        }
        final MessagesController messagesController = MessagesController.getInstance(account);
        final ArrayList<TLRPC.Dialog> dialogs = messagesController.getDialogs(folderId);
        final ArrayList<TLRPC.Dialog> pinned = new ArrayList<>();
        for (int i = 0, n = dialogs.size(); i < n; i++) {
            final TLRPC.Dialog d = dialogs.get(i);
            if (!(d instanceof TLRPC.TL_dialogFolder) && d.pinned) {
                pinned.add(d);
            }
        }
        if (pinned.isEmpty()) {
            return;
        }
        // What the user sees after a drag: the pinned numbers (DialogsAdapter.moveDialogs swaps them).
        final ArrayList<TLRPC.Dialog> target = new ArrayList<>(pinned);
        // With Hide Foreign Numbers on, the drag swapped them in DialogsActivity's filtered copy, not in this list.
        Collections.sort(target, (a, b) -> Integer.compare(b.pinnedNum, a.pinnedNum));
        if (list != null && !list.isEmpty()) {
            final ArrayList<TLRPC.Dialog> synced = new ArrayList<>();
            final ArrayList<TLRPC.Dialog> local = new ArrayList<>();
            for (TLRPC.Dialog d : target) {
                (list.contains(d.id) ? local : synced).add(d);
            }
            if (!local.isEmpty()) {
                // New store order: the extras in their dragged order, then the ones not in this list (not loaded).
                final ArrayList<Long> order = new ArrayList<>(list.size());
                for (TLRPC.Dialog d : local) {
                    order.add(d.id);
                }
                for (Long id : list) {
                    if (!order.contains(id)) {
                        order.add(id);
                    }
                }
                if (!order.equals(list)) {
                    list.clear();
                    list.addAll(order);
                    save(account, folderId);
                }
                target.clear();
                target.addAll(synced);
                target.addAll(local);
                final int n = target.size();
                for (int i = 0; i < n; i++) {
                    target.get(i).pinnedNum = n - i;
                }
            }
        }
        if (!target.equals(pinned)) {
            messagesController.sortDialogs(null);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        }
    }

    /** Whether {@code dialogId} is a device-local extra pin of {@code folderId} (left out of the server order). */
    public static boolean isExtra(int account, int folderId, long dialogId) {
        if ((folderId != 0 && folderId != 1) || !valid(account)) {
            return false;
        }
        final ArrayList<Long> list = load(account, folderId);
        return !list.isEmpty() && list.contains(dialogId);
    }

    /**
     * loadPinnedDialogs, after the server's pinned list was applied (every other pin was cleared): extras that the
     * server now lists become synced pins, the others are pinned again below them. Their ids join
     * {@code pinnedDialogs}, so unpinAllDialogsExceptNew keeps them pinned in the database.
     *
     * @return whether a dialog changed (the caller then sorts the list)
     */
    public static boolean onServerPinnedLoaded(int account, int folderId, ArrayList<Long> pinnedDialogs) {
        if ((folderId != 0 && folderId != 1) || pinnedDialogs == null || !valid(account)) {
            return false;
        }
        final ArrayList<Long> list = load(account, folderId);
        if (list.isEmpty()) {
            return false;
        }
        boolean storeChanged = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (pinnedDialogs.contains(list.get(i))) {
                list.remove(i);
                storeChanged = true;
            }
        }
        storeChanged |= pruneMoved(account, folderId, list);
        if (storeChanged) {
            save(account, folderId);
        }
        if (list.isEmpty()) {
            return false;
        }
        final boolean changed = place(account, folderId, list);
        for (Long id : list) {
            if (!pinnedDialogs.contains(id)) {
                pinnedDialogs.add(id);
            }
        }
        return changed;
    }

    /**
     * processLoadedDialogs and completeDialogsReset, before the list is sorted: a page from the server says
     * "not pinned" for every extra, so the stored extras are pinned again below the synced pins.
     */
    public static void reapply(int account) {
        if (!valid(account)) {
            return;
        }
        for (int folderId = 0; folderId <= 1; folderId++) {
            final ArrayList<Long> list = load(account, folderId);
            if (list.isEmpty()) {
                continue;
            }
            if (pruneMoved(account, folderId, list)) {
                save(account, folderId);
            }
            if (!list.isEmpty()) {
                place(account, folderId, list);
            }
        }
    }

    /** NotificationCenter.dialogDeleted: a deleted chat is no longer an extra (it would come back pinned). */
    public static void onDialogDeleted(int account, long dialogId) {
        if (!valid(account)) {
            return;
        }
        for (int folderId = 0; folderId <= 1; folderId++) {
            final ArrayList<Long> list = load(account, folderId);
            if (list.remove(dialogId)) {
                save(account, folderId);
            }
        }
    }

    /** Logout: the slot's extras belong to the user who left. */
    public static void onAccountLoggedOut(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        extras[account][0] = null;
        extras[account][1] = null;
        loadedOwner[account] = 0;
        try {
            MessagesController.getMainSettings(account).edit()
                .remove(KEY_OWNER).remove(KEY_EXTRA + 0).remove(KEY_EXTRA + 1).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Placement
    // ---------------------------------------------------------------------------------------------------------

    /** Telegram's own limit for the folder (what the server accepts). */
    static int serverLimit(int account, int folderId) {
        final MessagesController messagesController = MessagesController.getInstance(account);
        final boolean premium = UserConfig.getInstance(account).isPremium();
        if (folderId == 0) {
            return premium ? messagesController.maxPinnedDialogsCountPremium : messagesController.maxPinnedDialogsCountDefault;
        }
        return premium ? messagesController.maxFolderPinnedDialogsCountPremium : messagesController.maxFolderPinnedDialogsCountDefault;
    }

    /** Forgets extras whose chat is loaded but in the other folder now (archiving unpins a chat). */
    private static boolean pruneMoved(int account, int folderId, ArrayList<Long> list) {
        final MessagesController messagesController = MessagesController.getInstance(account);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            final TLRPC.Dialog d = messagesController.dialogs_dict.get(list.get(i));
            if (d != null && d.folder_id != folderId) {
                list.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Pins the loaded extras of {@code folderId} below every other pinned chat of the folder, in store order. The
     * other pins keep their order; they move up only if there is no room below them (numbers stay above 0, which
     * the dialogs table needs). Changed numbers go to the database.
     *
     * @return whether a dialog changed
     */
    private static boolean place(int account, int folderId, ArrayList<Long> list) {
        final MessagesController messagesController = MessagesController.getInstance(account);
        final HashSet<Long> ids = new HashSet<>(list);
        final ArrayList<TLRPC.Dialog> local = new ArrayList<>(list.size());
        for (Long id : list) {
            final TLRPC.Dialog d = messagesController.dialogs_dict.get(id);
            if (d != null && d.folder_id == folderId && !(d instanceof TLRPC.TL_dialogFolder)) {
                local.add(d);
            }
        }
        if (local.isEmpty()) {
            return false;
        }
        final ArrayList<TLRPC.Dialog> others = new ArrayList<>();
        int minOther = Integer.MAX_VALUE;
        for (int i = 0, n = messagesController.dialogs_dict.size(); i < n; i++) {
            final TLRPC.Dialog d = messagesController.dialogs_dict.valueAt(i);
            if (d == null || d instanceof TLRPC.TL_dialogFolder || d.folder_id != folderId || !d.pinned || ids.contains(d.id)) {
                continue;
            }
            others.add(d);
            minOther = Math.min(minOther, d.pinnedNum);
        }
        final int count = local.size();
        if (others.isEmpty()) {
            minOther = count + 1;
        }
        final int shift = Math.max(0, count - (minOther - 1));
        final ArrayList<Long> dids = new ArrayList<>();
        final ArrayList<Integer> nums = new ArrayList<>();
        if (shift > 0) {
            for (TLRPC.Dialog d : others) {
                d.pinnedNum += shift;
                dids.add(d.id);
                nums.add(d.pinnedNum);
            }
        }
        final int top = minOther + shift - 1;
        for (int i = 0; i < count; i++) {
            final TLRPC.Dialog d = local.get(i);
            final int num = top - i;
            if (!d.pinned || d.pinnedNum != num) {
                d.pinned = true;
                d.pinnedNum = num;
                dids.add(d.id);
                nums.add(num);
            }
        }
        if (dids.isEmpty()) {
            return false;
        }
        MessagesStorage.getInstance(account).setDialogsPinned(dids, nums);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Store
    // ---------------------------------------------------------------------------------------------------------

    private static boolean valid(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(account).isClientActivated();
    }

    private static ArrayList<Long> load(int account, int folderId) {
        final long owner = UserConfig.getInstance(account).getClientUserId();
        if (loadedOwner[account] != owner) {
            extras[account][0] = null;
            extras[account][1] = null;
            loadedOwner[account] = owner;
        }
        ArrayList<Long> list = extras[account][folderId];
        if (list == null) {
            list = new ArrayList<>();
            try {
                final SharedPreferences prefs = MessagesController.getMainSettings(account);
                if (prefs.getLong(KEY_OWNER, 0) == owner) {
                    final String stored = prefs.getString(KEY_EXTRA + folderId, "");
                    if (!TextUtils.isEmpty(stored)) {
                        for (String part : stored.split(",")) {
                            try {
                                final long id = Long.parseLong(part.trim());
                                if (id != 0 && !list.contains(id)) {
                                    list.add(id);
                                }
                            } catch (NumberFormatException ignore) {
                            }
                        }
                    }
                } else if (prefs.contains(KEY_EXTRA + folderId)) {
                    // Left behind by another user of this slot.
                    prefs.edit().remove(KEY_EXTRA + 0).remove(KEY_EXTRA + 1).remove(KEY_OWNER).apply();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            extras[account][folderId] = list;
        }
        return list;
    }

    private static void save(int account, int folderId) {
        final ArrayList<Long> list = extras[account][folderId];
        try {
            final SharedPreferences.Editor editor = MessagesController.getMainSettings(account).edit();
            if (list == null || list.isEmpty()) {
                editor.remove(KEY_EXTRA + folderId);
            } else {
                editor.putString(KEY_EXTRA + folderId, TextUtils.join(",", list));
                editor.putLong(KEY_OWNER, UserConfig.getInstance(account).getClientUserId());
            }
            editor.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
