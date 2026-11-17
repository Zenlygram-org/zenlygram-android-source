/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.history;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Edit History (iOS EditedMessageHistoryController): the earlier versions of one message, newest first, read-only.
 * Never gated by Pro. Long press copies a version's text (Android addition). While Save Edit History is off, or
 * when no version is left, the screen says so.
 */
public class ZgEditHistoryActivity extends UniversalFragment implements org.telegram.messenger.zenlygram.customer.ZenlyStats.NamedScreen { // ZENLYGRAM: statistics name the screen

    @Override
    public org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen zenlyScreen() {
        return org.telegram.messenger.zenlygram.customer.CustomerEvents.Screen.EDIT_HISTORY_MESSAGE;
    }

    private static final int ID_FIRST_REVISION = 100;
    private static final int ID_END = 99;
    private static final int ID_EMPTY = 98;

    private final long dialogId;
    private final int messageId;
    private ArrayList<ZgHistoryStore.Revision> revisions;

    public ZgEditHistoryActivity(int account, long dialogId, int messageId) {
        super();
        setCurrentAccount(account);
        this.dialogId = dialogId;
        this.messageId = messageId;
    }

    @Override
    public boolean onFragmentCreate() {
        ZgEditHistory.loadRevisions(currentAccount, dialogId, messageId, result -> {
            revisions = result;
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
        org.telegram.messenger.zenlygram.customer.ZenlyStats.editHistoryViewed(currentAccount, ZgKeptDeleted.isKept(currentAccount, dialogId, messageId)); // ZENLYGRAM: statistics, edit_history.viewed, once per opening (recorded only with the data consent)
        return super.onFragmentCreate();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return fragmentView;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_ContextMenu_EditedHistory);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (revisions == null) {
            return; // still loading (a local read, a moment)
        }
        if (revisions.isEmpty()) {
            // Expired, removed, or Save Edit History turned off after the menu was built.
            items.add(UItem.asShadow(ID_EMPTY, getString(R.string.ZG_History_EditHistory_Empty)));
            return;
        }
        for (int i = 0, n = revisions.size(); i < n; i++) {
            items.add(ZgEditRevisionCell.Factory.of(ID_FIRST_REVISION + i, revisions.get(i), i + 1 < n));
        }
        items.add(UItem.asShadow(ID_END, null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(view instanceof ZgEditRevisionCell)) {
            return false;
        }
        final CharSequence text = ((ZgEditRevisionCell) view).getPlainText();
        if (TextUtils.isEmpty(text) || !AndroidUtilities.addToClipboard(text)) {
            return false;
        }
        BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
        return true;
    }
}
