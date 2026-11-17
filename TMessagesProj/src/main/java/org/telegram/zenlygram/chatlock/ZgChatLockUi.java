/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlock;

import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.zenlygram.core.ZgFeatures;

import java.util.ArrayList;

/**
 * Small helpers that upstream hooks call (DialogsActivity, DialogCell, the widget). Main thread unless noted.
 */
public final class ZgChatLockUi {

    /** Action-mode overflow item "Set Pincode" / "Remove Pincode" (outside DialogsActivity's 100-111 and 200-203). */
    public static final int DIALOGS_MENU_ID = 9011;

    private ZgChatLockUi() {
    }

    /** Whether Chat Lock is in this build (ZgFeatures). */
    public static boolean isAvailable() {
        return ZgFeatures.CHAT_LOCK.isPorted();
    }

    // ---------------------------------------------------------------------------------------------------------
    // DialogCell (any thread)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether a chat-list row must read "Locked chat" instead of its last message, draft, typing and media: a locked
     * (or vault-hidden) dialog, not a topic row, a saved sub-dialog, a folder or community row. Title, avatar, date,
     * counters and pin / mute badges stay (iOS).
     */
    public static boolean redactsDialogCell(int account, long dialogId, boolean isTopic, boolean isSavedDialog, int folderId) {
        if (dialogId == 0 || isTopic || isSavedDialog || folderId != 0 || DialogObject.isFolderDialogId(dialogId)) {
            return false;
        }
        if (ZgChatLockSession.isProtected(account, dialogId)) {
            return true;
        }
        // Secret Vault: hidden chats show their text only in the vault list of an open vault.
        return ZgChatLockSession.isVaultHidden(account, dialogId) && !ZgChatLockSession.vaultShowsRows(account);
    }

    public static CharSequence lockedPreviewText() {
        return LocaleController.getString(R.string.ZG_ChatLock_LockedChatPreview);
    }

    /** TalkBack text of a locked row: "Locked chat. PIN required to open". */
    public static CharSequence lockedAccessibilityText() {
        return LocaleController.getString(R.string.ZG_ChatLock_LockedChatPreview) + ". " + LocaleController.getString(R.string.ZG_ChatLock_LockedChatA11yHint);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Search and other cross-chat surfaces (any thread)
    // ---------------------------------------------------------------------------------------------------------

    /** A message of a locked or vault-hidden chat must not appear in cross-chat lists (search, downloads, hashtags). */
    public static boolean hidesMessageGlobally(int account, @Nullable MessageObject message) {
        return message != null && hidesDialogGlobally(account, message.getDialogId());
    }

    public static boolean hidesDialogGlobally(int account, long dialogId) {
        return dialogId != 0 && ZgChatLockSession.hidesContentGlobally(account, dialogId);
    }

    /** A locked forum is always pushed (and gated), never opened inline next to the chat list. */
    public static boolean forbidsInlineForum(int account, long dialogId) {
        return hidesDialogGlobally(account, dialogId);
    }

    /** PhotoViewer: no picture-in-picture for a locked or hidden chat's video (switchToPip refuses it): no PiP item. */
    public static boolean refusesPip(@Nullable MessageObject message) {
        return message != null && hidesMessageGlobally(message.currentAccount, message);
    }

    /**
     * FragmentContextView, the music bar: true when the playing chat is locked or hidden and not unlocked right now.
     * Then its PIN is asked (vault PIN first for a hidden chat) and {@code then} runs after an unlock; nothing runs on
     * cancel. False: the caller opens the music sheet itself.
     */
    public static boolean deferUntilVisible(@Nullable BaseFragment fragment, @Nullable MessageObject message, Runnable then) {
        if (message == null || !hidesDialogGlobally(message.currentAccount, message.getDialogId())) {
            return false;
        }
        final ZgChatLockSession session = ZgChatLockSession.getInstance();
        if (session.isContentVisible(message.currentAccount, message.getDialogId())) {
            return false;
        }
        if (fragment == null || fragment.getParentActivity() == null) {
            return true;
        }
        session.requireUnlock(message.currentAccount, message.getDialogId(), ZgChatLockSession.REASON_EXPLICIT, fragment, result -> {
            if (result == ZgChatLockSession.RESULT_UNLOCKED) {
                then.run();
            }
        });
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // DialogsActivity action mode
    // ---------------------------------------------------------------------------------------------------------

    /** Adds the (hidden) item to the action-mode overflow; {@link #updateDialogsMenuItem} shows it when it applies. */
    @Nullable
    public static ActionBarMenuSubItem addDialogsMenuItem(@Nullable ActionBarMenuItem otherItem) {
        if (otherItem == null || !isAvailable()) {
            return null;
        }
        final ActionBarMenuSubItem item = otherItem.addSubItem(DIALOGS_MENU_ID, R.drawable.msg_permissions, LocaleController.getString(R.string.ZG_CLC_MenuLock));
        item.setVisibility(View.GONE);
        return item;
    }

    /**
     * What the menus offer for {@code dialogId} (iOS SGChatLockFlows §2.5): "Lock Chat" (with or without a master: the
     * master-first chain sets one up), "Remove Lock" in the chat list, "Chat Lock…" in the profile. Never for Saved
     * Messages, a folder, the promo chat, a chat hidden in the Secret Vault, or while recovery is pending.
     */
    public static ZgChatLockCenterSpec.MenuItem menuItem(int account, long dialogId, boolean profileMenu) {
        if (!isAvailable()) {
            return ZgChatLockCenterSpec.MenuItem.NONE;
        }
        final boolean lockable = isLockable(account, dialogId) && !ZgChatLockSession.isVaultHidden(account, dialogId);
        return ZgChatLockCenterSpec.menuItem(profileMenu, lockable, ZgChatLockStore.isLocked(dialogId), ZgChatLockStore.needsRecovery());
    }

    private static int menuText(ZgChatLockCenterSpec.MenuItem kind) {
        switch (kind) {
            case REMOVE:
                return R.string.ZG_CLC_MenuRemoveLock;
            case MANAGE:
                return R.string.ZG_CLC_MenuManageLock;
            default:
                return R.string.ZG_CLC_MenuLock;
        }
    }

    /** Visible for exactly one selected real chat. */
    public static void updateDialogsMenuItem(@Nullable ActionBarMenuSubItem item, @Nullable ArrayList<Long> selectedDialogs, int account) {
        if (item == null) {
            return;
        }
        final long dialogId = selectedDialogs != null && selectedDialogs.size() == 1 ? selectedDialogs.get(0) : 0;
        final ZgChatLockCenterSpec.MenuItem kind = dialogId != 0 ? menuItem(account, dialogId, false) : ZgChatLockCenterSpec.MenuItem.NONE;
        item.setVisibility(kind != ZgChatLockCenterSpec.MenuItem.NONE ? View.VISIBLE : View.GONE);
        if (kind != ZgChatLockCenterSpec.MenuItem.NONE) {
            item.setTextAndIcon(LocaleController.getString(menuText(kind)), R.drawable.msg_permissions);
        }
    }

    /** The item was tapped: lock (Pro-gated, master-first when needed) or remove the lock of the one selected chat. */
    public static void onDialogsMenuClick(BaseFragment fragment, @Nullable ArrayList<Long> selectedDialogs) {
        if (fragment == null || selectedDialogs == null || selectedDialogs.size() != 1) {
            return;
        }
        run(fragment, selectedDialogs.get(0), false);
    }

    private static void run(BaseFragment fragment, long dialogId, boolean profileMenu) {
        switch (menuItem(fragment.getCurrentAccount(), dialogId, profileMenu)) {
            case LOCK:
                ZgChatLockCenter.lockChat(fragment, dialogId);
                break;
            case REMOVE:
                ZgChatLockCenter.removeLock(fragment, dialogId, null);
                break;
            case MANAGE:
                ZgChatLockCenter.openChat(fragment, dialogId);
                break;
            default:
                break;
        }
    }

    /** The same item in the avatar-peek menu of the chat list (closes the peek, then the flow). */
    public static void addPreviewMenuItem(BaseFragment fragment, @Nullable ActionBarPopupWindow.ActionBarPopupWindowLayout menu, long dialogId, boolean isCommunity) {
        if (fragment == null || menu == null || isCommunity || fragment.getParentActivity() == null) {
            return;
        }
        final ZgChatLockCenterSpec.MenuItem kind = menuItem(fragment.getCurrentAccount(), dialogId, false);
        if (kind == ZgChatLockCenterSpec.MenuItem.NONE) {
            return;
        }
        final ActionBarMenuSubItem item = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false);
        item.setTextAndIcon(LocaleController.getString(menuText(kind)), R.drawable.msg_permissions);
        item.setMinimumWidth(160);
        item.setOnClickListener(v -> {
            fragment.finishPreviewFragment();
            run(fragment, dialogId, false);
        });
        menu.addView(item);
    }

    /** Profile ⋮ menu id (ProfileActivity hook). */
    public static final int PROFILE_MENU_ID = 9012;

    /** Adds "Lock Chat" / "Chat Lock…" to a chat profile's ⋮ menu (iOS PeerInfoScreenPerformButtonAction). */
    public static void addProfileMenuItem(@Nullable ActionBarMenuItem otherItem, int account, long dialogId) {
        if (otherItem == null) {
            return;
        }
        final ZgChatLockCenterSpec.MenuItem kind = menuItem(account, dialogId, true);
        if (kind != ZgChatLockCenterSpec.MenuItem.NONE) {
            otherItem.addSubItem(PROFILE_MENU_ID, R.drawable.msg_permissions, LocaleController.getString(menuText(kind)));
        }
    }

    /** A tap on {@link #PROFILE_MENU_ID}. */
    public static void onProfileMenuClick(BaseFragment fragment, long dialogId) {
        if (fragment != null && dialogId != 0) {
            run(fragment, dialogId, true);
        }
    }

    static boolean isLockable(int account, long dialogId) {
        if (dialogId == 0 || DialogObject.isFolderDialogId(dialogId) || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            // A community container row is not a chat.
            return !controller.isPromoDialog(dialogId, false) && !(controller.dialogs_dict.get(dialogId) instanceof TLRPC.TL_dialogCommunity);
        } catch (Throwable e) {
            return true;
        }
    }
}
