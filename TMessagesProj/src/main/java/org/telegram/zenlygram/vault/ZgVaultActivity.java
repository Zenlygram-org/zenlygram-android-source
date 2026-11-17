/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.vault;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

/**
 * The Secret Vault list (iOS SecretVaultController): only this account's hidden chats, while its vault is open.
 * Chats open above it and it stays below them; "Remove from Vault" exists only here. No folders, archive, stories or
 * compose button. A search filters the hidden chats by name or username. The window is FLAG_SECURE while it shows.
 *
 * <p>Fails closed: it cannot be created, and it removes itself on resume, when the account's vault is not open.
 */
public class ZgVaultActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ZgVault.Listener, org.telegram.messenger.zenlygram.customer.ZenlyStats.NamedScreen { // ZENLYGRAM: statistics name the screen

    @Override
    public org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen zenlyScreen() {
        return org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.VAULT_CHATS;
    }

    private static final int MENU_SEARCH = 1;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private LinearLayout emptyView;
    private TextView emptyTitle;
    private TextView emptyText;
    private RLottieImageView emptyImage;
    private ActionBarMenuItem searchItem;
    private String query;
    private FlagSecureReason flagSecure;
    private final ArrayList<TLRPC.Dialog> rows = new ArrayList<>();

    public ZgVaultActivity(int account) {
        super();
        setCurrentAccount(account);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!ZgVault.isActive() || !ZgVaultSession.isOpen(currentAccount)) {
            return false;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        ZgVault.addListener(this);
        ZgVaultEffects.loadMissing(currentAccount);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        ZgVault.removeListener(this);
        if (flagSecure != null) {
            flagSecure.detach();
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ZG_Settings_SecretVault_Title));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        searchItem = actionBar.createMenu().addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true)
            .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchExpand() {
                }

                @Override
                public void onSearchCollapse() {
                    query = null;
                    rebuild();
                }

                @Override
                public void onTextChanged(EditText editText) {
                    final String text = editText.getText().toString().trim();
                    query = text.isEmpty() ? null : text;
                    rebuild();
                }
            });
        searchItem.setSearchFieldHint(getString(R.string.Search));
        searchItem.setContentDescription(getString(R.string.Search));

        final FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = content;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            final TLRPC.Dialog dialog = getRow(position);
            if (dialog != null) {
                ZgVaultChatOpener.open(this, dialog.id);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            final TLRPC.Dialog dialog = getRow(position);
            if (dialog == null) {
                return false;
            }
            showOptions(view, dialog);
            return true;
        });
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        emptyImage = new RLottieImageView(context);
        emptyImage.setAnimation(R.raw.chats_hide, 100, 100);
        emptyImage.setAutoRepeat(false);
        emptyImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        emptyView.addView(emptyImage, LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
        emptyTitle = new TextView(context);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        emptyTitle.setTypeface(AndroidUtilities.bold());
        emptyTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 0));
        emptyText = new TextView(context);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setLineSpacing(AndroidUtilities.dp(2), 1f);
        emptyView.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 40, 8, 40, 0));
        content.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 0, 0, 0, 56));

        rebuild();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!ZgVault.isActive() || !ZgVaultSession.isOpen(currentAccount)) {
            // The vault closed while this screen was below something else: never show it again without the PIN.
            AndroidUtilities.runOnUIThread(() -> {
                if (!isFinished) {
                    removeSelfFromStack(true);
                }
            });
            return;
        }
        if (flagSecure == null && getParentActivity() != null) {
            flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true); // debug builds too (the owner tests on one)
        }
        if (flagSecure != null) {
            flagSecure.attach();
        }
        rebuild();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (flagSecure != null) {
            flagSecure.detach();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (listView == null) {
            return;
        }
        if (id == NotificationCenter.dialogsNeedReload) {
            rebuild();
        } else if (id == NotificationCenter.updateInterfaces) {
            final int mask = args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            if ((mask & MessagesController.UPDATE_MASK_NEW_MESSAGE) != 0 || mask == 0) {
                rebuild();
                return;
            }
            for (int i = 0, n = listView.getChildCount(); i < n; i++) {
                final View child = listView.getChildAt(i);
                if (child instanceof DialogCell && ((DialogCell) child).update(mask, true)) {
                    rebuild();
                    return;
                }
            }
        } else if (id == NotificationCenter.emojiLoaded) {
            listView.invalidateViews();
        }
    }

    @Override
    public void onVaultChanged() {
        if (!ZgVault.isActive()) {
            if (!isFinished) {
                removeSelfFromStack(true);
            }
            return;
        }
        rebuild();
    }

    // ---------------------------------------------------------------------------------------------------------

    private void rebuild() {
        rows.clear();
        final ArrayList<TLRPC.Dialog> all = ZgVault.getDialogs(currentAccount);
        final String lower = query != null ? query.toLowerCase() : null;
        final String translit = lower != null ? LocaleController.getInstance().getTranslitString(lower) : null;
        for (int i = 0; i < all.size(); i++) {
            final TLRPC.Dialog dialog = all.get(i);
            if (dialog == null || !ZgVault.isHidden(currentAccount, dialog.id)) {
                continue;
            }
            if (lower == null || matches(dialog.id, lower, translit)) {
                rows.add(dialog);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    private boolean matches(long dialogId, String lower, String translit) {
        String name = null;
        String username = null;
        try {
            final MessagesController controller = getMessagesController();
            if (DialogObject.isEncryptedDialog(dialogId)) {
                final TLRPC.EncryptedChat encryptedChat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                final TLRPC.User user = encryptedChat != null ? controller.getUser(encryptedChat.user_id) : null;
                if (user != null) {
                    name = UserObject.getUserName(user);
                    username = UserObject.getPublicUsername(user);
                }
            } else if (DialogObject.isUserDialog(dialogId)) {
                final TLRPC.User user = controller.getUser(dialogId);
                if (user != null) {
                    name = UserObject.getUserName(user);
                    username = UserObject.getPublicUsername(user);
                }
            } else {
                final TLRPC.Chat chat = controller.getChat(-dialogId);
                if (chat != null) {
                    name = chat.title;
                    username = ChatObject.getPublicUsername(chat);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return contains(name, lower, translit) || contains(username, lower, translit);
    }

    private static boolean contains(String value, String lower, String translit) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        final String text = value.toLowerCase();
        return text.contains(lower) || !TextUtils.isEmpty(translit) && text.contains(translit);
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        final boolean empty = rows.isEmpty();
        if (empty) {
            if (query != null) {
                emptyTitle.setText(getString(R.string.NoResult));
                emptyText.setVisibility(View.GONE);
                emptyImage.setVisibility(View.GONE);
            } else {
                emptyTitle.setText(getString(R.string.ZG_Vault_EmptyTitle));
                emptyText.setText(getString(R.string.ZG_Vault_EmptyText));
                emptyText.setVisibility(View.VISIBLE);
                emptyImage.setVisibility(View.VISIBLE);
                if (emptyView.getVisibility() != View.VISIBLE) {
                    emptyImage.playAnimation();
                }
            }
        }
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private TLRPC.Dialog getRow(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    private void showOptions(View view, TLRPC.Dialog dialog) {
        final long dialogId = dialog.id;
        final MessagesController controller = getMessagesController();
        final boolean unread = controller.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark;
        try {
            ItemOptions.makeOptions(this, view)
                .add(R.drawable.msg_views, getString(R.string.ZG_Vault_RemoveFromVault), () -> confirmRemove(dialogId))
                .add(unread ? R.drawable.msg_markread : R.drawable.msg_markunread, getString(unread ? R.string.MarkAsRead : R.string.MarkAsUnread), () -> {
                    if (unread) {
                        markAsRead(dialogId);
                    } else {
                        controller.markDialogAsUnread(dialogId, null, 0);
                    }
                })
                .setScrimViewBackground(new android.graphics.drawable.ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite)))
                .show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void markAsRead(long dialogId) {
        final MessagesController controller = getMessagesController();
        final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        if (dialog == null) {
            return;
        }
        if (controller.isForum(dialogId) || controller.isMonoForumWithManageRights(dialogId)) {
            controller.markAllTopicsAsRead(dialogId);
        }
        controller.markMentionsAsRead(dialogId, 0);
        controller.markDialogAsRead(dialogId, dialog.top_message, dialog.top_message, dialog.last_message_date, false, 0, 0, true, 0);
    }

    /** Neutral confirmation; no Undo (adding again is the Pro-gated create path). */
    private void confirmRemove(long dialogId) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog alert = new AlertDialog.Builder(getParentActivity())
            .setTitle(getString(R.string.ZG_Vault_RemoveConfirmTitle))
            .setMessage(getString(R.string.ZG_Vault_RemoveConfirmText))
            .setNegativeButton(getString(R.string.Cancel), null)
            .setPositiveButton(getString(R.string.ZG_Vault_RemoveConfirmAction), (d, which) -> {
                if (!ZgVaultSession.isOpen(currentAccount) || !ZgVault.isOperable() || !ZgVault.isHidden(currentAccount, dialogId)) {
                    return;
                }
                if (ZgVaultEffects.unhide(currentAccount, new long[]{dialogId})) {
                    ZgVaultFlows.showBulletin(this, R.raw.chats_unhide, getString(R.string.ZG_Vault_ChatUnhidden));
                } else {
                    ZgVaultFlows.showBulletin(this, R.raw.error, getString(R.string.ZG_Vault_SaveFailed));
                }
            })
            .makeRed(DialogInterface.BUTTON_POSITIVE)
            .create();
        showDialog(alert);
    }

    // ---------------------------------------------------------------------------------------------------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final DialogCell cell = new DialogCell(null, context, false, false, currentAccount, null);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final TLRPC.Dialog dialog = getRow(position);
            if (dialog == null || !(holder.itemView instanceof DialogCell)) {
                return;
            }
            final DialogCell cell = (DialogCell) holder.itemView;
            cell.useSeparator = position + 1 < rows.size();
            cell.fullSeparator = false;
            cell.setDialog(dialog, DialogsActivity.DIALOGS_TYPE_DEFAULT, 0);
            cell.checkHeight();
        }
    }
}
