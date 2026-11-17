/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.dialogs;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.ArrayList;

/**
 * Hide Foreign Numbers (iOS blockForeignUsers, ChatListNodeEntries): the main All-chats list leaves out one-on-one
 * chats whose phone calling code differs from the account's own.
 *
 * <p>A purely visual, local filter: nobody is blocked, reported or told, the chats keep their unread badges and
 * notifications, and search, the archive, folders and every chat picker still show them. Kept, as on iOS: groups,
 * channels, secret chats, bots, Saved Messages, service and support accounts, Replies, people whose number is hidden,
 * and everything while the own code is unknown. Applied live (DialogsActivity re-reads its list on dialogsNeedReload).
 *
 * <p>Called by DialogsActivity.getDialogsArray on the UI thread, which happens on every list update and scroll, so the
 * filtered copy is cached and only rebuilt when the source list's contents or order change.
 */
public final class ZgForeignNumbers {

    private static final class Cache {
        ArrayList<TLRPC.Dialog> source;
        Object[] snapshot;
        String ownCode;
        ArrayList<TLRPC.Dialog> result;
        /** The own phone {@link #ownPhoneCode} was extracted from (scrolling asks again and again: no new strings). */
        String ownPhone;
        String ownPhoneCode;
    }

    private static final Cache[] caches = new Cache[UserConfig.MAX_ACCOUNT_COUNT];

    private ZgForeignNumbers() {
    }

    public static boolean isEnabled() {
        return ZgFeatures.BLOCK_FOREIGN_USERS.isPorted() && ZgSettings.blockForeignUsers.get();
    }

    /**
     * The list DialogsActivity shows for {@code folderId}: {@code source} itself unless the filter is on, this is the
     * main list (folder 0) and some chat is hidden. Returning the very same list when nothing is hidden keeps every
     * upstream code path unchanged.
     */
    public static ArrayList<TLRPC.Dialog> filterMain(int account, int folderId, ArrayList<TLRPC.Dialog> source) {
        if (folderId != 0 || source == null || source.isEmpty() || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || !isEnabled()) {
            return source;
        }
        final UserConfig userConfig = UserConfig.getInstance(account);
        final TLRPC.User self = userConfig.getCurrentUser();
        final String ownPhone = self != null ? self.phone : null;
        Cache cache = caches[account];
        if (cache == null) {
            caches[account] = cache = new Cache();
        }
        if (ownPhone == null || !ownPhone.equals(cache.ownPhone)) {
            cache.ownPhone = ownPhone;
            cache.ownPhoneCode = ownPhone != null ? ZgCountryCode.extract(ownPhone) : null;
        }
        final String ownCode = cache.ownPhoneCode;
        if (ownCode == null) {
            return source;
        }
        if (cache.source == source && ownCode.equals(cache.ownCode) && sameElements(cache.snapshot, source)) {
            return cache.result;
        }
        final MessagesController messagesController = MessagesController.getInstance(account);
        final long selfId = userConfig.getClientUserId();
        ArrayList<TLRPC.Dialog> result = null;
        for (int i = 0, n = source.size(); i < n; i++) {
            final TLRPC.Dialog dialog = source.get(i);
            if (isForeign(messagesController, dialog, ownCode, selfId)) {
                if (result == null) {
                    result = new ArrayList<>(n);
                    result.addAll(source.subList(0, i));
                }
            } else if (result != null) {
                result.add(dialog);
            }
        }
        cache.source = source;
        cache.snapshot = source.toArray();
        cache.ownCode = ownCode;
        cache.result = result != null ? result : source;
        return cache.result;
    }

    /** The switch changed: every open chat list re-reads its dialogs now (no restart, unlike iOS). */
    public static void onSettingChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                caches[a] = null;
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
                }
            }
        });
    }

    public static void onAccountLoggedOut(int account) {
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
            caches[account] = null;
        }
    }

    private static boolean isForeign(MessagesController messagesController, TLRPC.Dialog dialog, String ownCode, long selfId) {
        if (dialog == null || dialog instanceof TLRPC.TL_dialogFolder || !DialogObject.isUserDialog(dialog.id)) {
            return false; // groups, channels, secret chats, the archive row
        }
        final long userId = dialog.id;
        if (userId == selfId || UserObject.isService(userId) || UserObject.isReplyUser(userId)) {
            return false;
        }
        final TLRPC.User user = messagesController.getUser(userId);
        if (user == null || user.bot || user.self || UserObject.isDeleted(user) || MessagesController.isSupportUser(user)) {
            return false;
        }
        final String peerCode = ZgCountryCode.extract(user.phone);
        return peerCode != null && !peerCode.equals(ownCode);
    }

    private static boolean sameElements(Object[] snapshot, ArrayList<TLRPC.Dialog> list) {
        if (snapshot == null || snapshot.length != list.size()) {
            return false;
        }
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] != list.get(i)) {
                return false;
            }
        }
        return true;
    }
}
