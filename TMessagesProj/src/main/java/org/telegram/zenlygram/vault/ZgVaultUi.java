/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Rect;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.pro.ZgPro;
import org.telegram.zenlygram.pro.ZgProGate;

import java.util.ArrayList;

/**
 * Chat-list glue (DialogsActivity hooks, main thread): "Add to Vault" in the selection ⋮ menu and in the avatar-peek
 * menu, the add flow (Pro gate, bulletin with Undo) and the reveal gesture on the chat-list title.
 */
public final class ZgVaultUi {

    /** Action-mode overflow item (DialogsActivity uses 100-111; Chat Lock 9011). */
    public static final int ACTION_ADD = 9001;

    private ZgVaultUi() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Selection ⋮ menu
    // ---------------------------------------------------------------------------------------------------------

    /** Adds the hidden item to the action-mode overflow; {@link #updateActionModeItem} shows it when it applies. */
    @Nullable
    public static ActionBarMenuSubItem addActionModeItem(@Nullable ActionBarMenuItem otherItem) {
        if (otherItem == null || !ZgVault.isPorted()) {
            return null;
        }
        try {
            final ActionBarMenuSubItem item = otherItem.addSubItem(ACTION_ADD, R.drawable.msg_stories_myhide, getString(R.string.ZG_Vault_AddToVault));
            item.setVisibility(View.GONE);
            return item;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Visible while the vault works and every selected row is a chat that can be hidden. */
    public static void updateActionModeItem(@Nullable ActionBarMenuSubItem item, int account, @Nullable ArrayList<Long> selected) {
        if (item == null) {
            return;
        }
        boolean show = ZgVault.isOperable() && selected != null && !selected.isEmpty();
        if (show) {
            for (int i = 0; i < selected.size(); i++) {
                if (!isHideable(account, selected.get(i))) {
                    show = false;
                    break;
                }
            }
        }
        item.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** The item was tapped (the caller closes the action mode). */
    @MainThread
    public static void onActionModeClick(@Nullable BaseFragment fragment, @Nullable ArrayList<Long> selected) {
        if (fragment == null || selected == null || selected.isEmpty()) {
            return;
        }
        addToVault(fragment, new ArrayList<>(selected));
    }

    /** "Add to Vault" in the avatar-peek menu of the chat list (closes the peek first). */
    public static void addPreviewMenuItem(@Nullable BaseFragment fragment, @Nullable ActionBarPopupWindow.ActionBarPopupWindowLayout menu, long dialogId, boolean isCommunity) {
        if (fragment == null || menu == null || isCommunity || fragment.getParentActivity() == null || !ZgVault.isOperable()
            || !isHideable(fragment.getCurrentAccount(), dialogId)) {
            return;
        }
        try {
            final ActionBarMenuSubItem item = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false);
            item.setTextAndIcon(getString(R.string.ZG_Vault_AddToVault), R.drawable.msg_stories_myhide);
            item.setMinimumWidth(160);
            item.setOnClickListener(v -> {
                fragment.finishPreviewFragment();
                final ArrayList<Long> ids = new ArrayList<>();
                ids.add(dialogId);
                addToVault(fragment, ids);
            });
            menu.addView(item);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Hides the chats (Pro-gated create), then "Chat hidden" with Undo for 5 s. */
    @MainThread
    public static void addToVault(BaseFragment fragment, ArrayList<Long> dialogIds) {
        final int account = fragment.getCurrentAccount();
        final ArrayList<Long> ids = new ArrayList<>();
        for (Long id : dialogIds) {
            if (id != null && isHideable(account, id) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty() || !ZgVault.isOperable()) {
            return;
        }
        if (!ZgProGate.check(fragment, ZgPro.GatedAction.CREATE_VAULT_ENTRY)) {
            return;
        }
        final long[] array = new long[ids.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = ids.get(i);
        }
        if (!ZgVaultEffects.hide(account, array)) {
            ZgVaultFlows.showBulletin(fragment, R.raw.error, getString(R.string.ZG_Vault_HideFailed));
            return;
        }
        final CharSequence text = array.length == 1 ? getString(R.string.ZG_Vault_ChatHidden) : formatString(R.string.ZG_Vault_ChatsHidden, array.length);
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_hide, text, getString(R.string.Undo), Bulletin.DURATION_PROLONG, () -> {
                if (ZgVault.isOperable()) {
                    ZgVaultEffects.unhide(account, array);
                }
            }).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * A real chat of this account that is not hidden yet: not Saved Messages, a folder or community row, or the promo
     * (proxy sponsor / PSA) chat.
     */
    static boolean isHideable(int account, long dialogId) {
        if (dialogId == 0 || DialogObject.isFolderDialogId(dialogId) || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        if (ZgVault.isHidden(account, dialogId)) {
            return false;
        }
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            if (controller.isPromoDialog(dialogId, false)) {
                return false;
            }
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            return !(dialog instanceof TLRPC.TL_dialogCommunity) && !(dialog instanceof TLRPC.TL_dialogFolder);
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reveal gesture
    // ---------------------------------------------------------------------------------------------------------

    /**
     * DialogsActivity.createView, main chat list only: a long-press on the title (the app name at the top of the chat
     * list) asks for the vault PIN. The tap keeps Telegram's "scroll to top". Every condition is checked at press time:
     * the vault is on and no selection or search is shown; the press started on the title.
     */
    public static void attachRevealGesture(@Nullable BaseFragment fragment, @Nullable ActionBar actionBar, boolean mainList) {
        if (fragment == null || actionBar == null || !mainList || !ZgVault.isPorted()) {
            return;
        }
        try {
            final float[] down = new float[]{-1, -1};
            actionBar.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    down[0] = event.getX();
                    down[1] = event.getY();
                }
                return false;
            });
            actionBar.setOnLongClickListener(v -> {
                if (!ZgVault.isActive() || !ZgVaultSecretCode.allowsTitleReveal() || fragment.getParentActivity() == null || fragment.isFinished
                    || actionBar.isActionModeShowed() || actionBar.isSearchFieldVisible()) {
                    return false;
                }
                final SimpleTextView title = actionBar.getTitleTextView();
                if (title == null || title.getVisibility() != View.VISIBLE || title.getAlpha() < 0.5f) {
                    return false;
                }
                // TalkBack's long press has no touch point: the title is what it focuses.
                if (!isTouchExploration(actionBar) && !isTitleUnder(actionBar, title, down[0], down[1])) {
                    return false;
                }
                try {
                    actionBar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignore) {
                }
                ZgVaultFlows.reveal(fragment);
                return true;
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isTouchExploration(View view) {
        try {
            final AccessibilityManager manager = (AccessibilityManager) view.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            return manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether ({@code x}, {@code y}) in {@code actionBar} coordinates falls on the visible title view. */
    private static boolean isTitleUnder(ActionBar actionBar, View title, float x, float y) {
        if (x < 0 || y < 0) {
            return false;
        }
        float left = 0;
        float top = 0;
        View view = title;
        while (view != null && view != actionBar) {
            if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.5f) {
                return false;
            }
            left += view.getLeft() + view.getTranslationX();
            top += view.getTop() + view.getTranslationY();
            final ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        if (view != actionBar) {
            return false;
        }
        final Rect rect = new Rect((int) left, (int) top, (int) (left + title.getWidth()), (int) (top + title.getHeight()));
        return rect.contains((int) x, (int) y);
    }
}
