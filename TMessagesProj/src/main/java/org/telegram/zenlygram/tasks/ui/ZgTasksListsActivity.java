/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.cloudui.ZgTasksUnsentActivity;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgBoardList;
import org.telegram.zenlygram.tasks.model.ZgBoardTask;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;
import org.telegram.zenlygram.tasks.source.ZgTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Lists (iOS TasksListsController and TasksCloudListsController): every list of one store with done/total, and per
 * list Open (selects it on the board and goes back), Rename and Delete (a confirmation that names how many tasks go
 * with it; no Undo). "+" creates a list (the only gated action here).
 *
 * <ul>
 *   <li>Observer-driven like the board: the screen renders the store's board and never assumes its own change went
 *       through. A rename the copy to Cloud refused for the moment comes back with the typed name.</li>
 *   <li>A read-only store (the Device archive; Cloud under a consent hold) only opens lists: no "+", Rename or Delete,
 *       and the reason is shown on top.</li>
 *   <li>Cloud: the Cloud Inbox is no list and is not shown here; a list whose latest change is not confirmed says so,
 *       and one that needs a choice leads to Unsent Changes.</li>
 *   <li>The device's built-in Inbox is listed while it holds a task; it cannot be renamed, and deleting it deletes
 *       its tasks.</li>
 * </ul>
 */
public class ZgTasksListsActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    public interface OpenListener {
        /** The board should select this list. */
        void onOpen(String listId);
    }

    private static final int MENU_ADD = 1;
    private static final int ID_HEADER = 1;
    private static final int ID_EMPTY = 2;
    private static final int ID_NOTE = 3;
    private static final int ID_FOOTER = 4;
    private static final int ID_ROW_BASE = 1000;

    private static final int ACTION_OPEN = 0;
    private static final int ACTION_REVIEW = 1;
    private static final int ACTION_RENAME = 2;
    private static final int ACTION_DELETE = 3;

    private final ZgTasksScope scope;
    private final OpenListener openListener;
    private ZgTasksSource source;
    private ZgTasksSource.Subscription subscription;
    private ZgBoard board;
    /** List id to {done, total} of its tasks. */
    private final HashMap<String, int[]> counts = new HashMap<>();
    private SimpleActionBarMenuItem addItem;
    private boolean sheetOpen;
    private boolean reportedFailure;
    private boolean destroyed;

    public ZgTasksListsActivity(int account, ZgTasksScope scope, OpenListener openListener) {
        super();
        this.currentAccount = account;
        this.scope = scope;
        this.openListener = openListener;
        this.board = ZgBoard.empty(scope);
    }

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.TASKS_LISTS;
    }

    @Override
    public boolean onFragmentCreate() {
        source = ZgTasksSources.of(currentAccount, scope);
        // The current board arrives inside subscribe(), before the list exists.
        subscription = source.subscribe(this::onBoard);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_tasks_lists_title);
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_ADD) {
                    // The new list reaches this screen with the store's next board.
                    ZgTasks.createList(ZgTasksListsActivity.this, source, listId -> {
                    });
                }
            }
        });
        final SimpleActionBarMenu menu = simpleActionBar.createMenu();
        addItem = menu.addItem(MENU_ADD, R.drawable.msg_add);
        addItem.setContentDescription(getString(R.string.ZG_tasks_filter_newList));
        addItem.setShown(board.canCompose(), false);
        return fragmentView;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (source != null) {
            source.start(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (source != null) {
            source.stop(this);
        }
    }

    private void onBoard(ZgBoard next) {
        if (destroyed) {
            return;
        }
        board = next;
        counts.clear();
        for (ZgBoardTask task : next.tasks) {
            if (task.listId == null) {
                continue; // the Cloud Inbox, or a task shared with this account
            }
            int[] c = counts.get(task.listId);
            if (c == null) {
                counts.put(task.listId, c = new int[2]);
            }
            if (task.completed) {
                c[0]++;
            }
            c[1]++;
        }
        if (next.failed) {
            // What was shown stays; the user is told once per failing streak.
            if (!reportedFailure) {
                reportedFailure = true;
                AndroidUtilities.runOnUIThread(() -> ZgTasks.showError(this, getString(R.string.ZG_tasks_error_load)));
            }
        } else {
            reportedFailure = false;
        }
        if (addItem != null) {
            addItem.setShown(next.canCompose(), true);
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private String title(ZgBoardList list) {
        return list.inbox ? getString(R.string.ZG_tasks_inbox_title) : list.title;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (board == null) {
            return;
        }
        final boolean cloud = scope == ZgTasksScope.CLOUD;
        if (board.gate != null) {
            // The board shows the recovery; this screen only says why there is nothing to list.
            items.add(UItem.asShadow(ID_NOTE, gateText(board.gate, cloud)));
            return;
        }
        if (board.readOnly != null) {
            items.add(UItem.asShadow(ID_NOTE, ZgTasksUi.readOnlyText(board.readOnly)));
        }
        if (board.lists.isEmpty()) {
            // Before the store answered completely nothing is known to be missing; a read-only store has no "+".
            if (board.complete && board.canCompose()) {
                items.add(UItem.asShadow(ID_EMPTY, getString(R.string.ZG_tasks_folders_empty)));
            }
        } else {
            int done = 0;
            int total = 0;
            for (ZgBoardList list : board.lists) {
                final int[] c = counts.get(list.id);
                if (c != null) {
                    done += c[0];
                    total += c[1];
                }
            }
            final String header = formatPluralString("ZG_tasks_lists_headerCount", board.lists.size());
            items.add(UItem.asHeader(ID_HEADER, total > 0 ? formatString(R.string.ZG_tasks_lists_headerProgress, header, done, total) : header));
            final int green = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText, resourceProvider);
            for (int i = 0; i < board.lists.size(); i++) {
                final ZgBoardList list = board.lists.get(i);
                final int[] c = counts.get(list.id);
                final CharSequence value;
                if (c == null || c[1] == 0) {
                    value = "0";
                } else {
                    final SpannableString s = new SpannableString(c[0] + "/" + c[1]);
                    if (c[0] == c[1]) {
                        s.setSpan(new ForegroundColorSpan(green), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    value = s;
                }
                // A Cloud list whose latest change is not confirmed says so under its name. Kept in the item too, so
                // a changed status rebinds the row.
                final String status = ZgTasksUi.syncText(list.sync);
                final UItem row = UItem.asButton(ID_ROW_BASE + i, R.drawable.msg_list, title(list), value);
                row.subtext = status;
                row.onBind(view -> {
                    if (view instanceof TextCell) {
                        final TextCell cell = (TextCell) view;
                        // Room for the status line under the name, as Telegram sizes its own cells with a subtitle.
                        final int height = status != null ? 60 : 50;
                        if (cell.heightDp != height) {
                            cell.heightDp = height;
                            cell.requestLayout();
                        }
                        cell.setSubtitle(status);
                    }
                });
                items.add(row);
            }
        }
        items.add(UItem.asShadow(ID_FOOTER, cloud ? getString(R.string.ZG_tasks_cloud_lists_footer) : null));
    }

    private static String gateText(ZgBoard.Gate gate, boolean cloud) {
        switch (gate) {
            case UNLINKED:
                return getString(R.string.ZG_tasks_cloud_unlinked_title);
            case STORAGE:
                return getString(R.string.ZenlyCloudTasksStatusError);
            default:
                return getString(cloud ? R.string.ZenlyCloudTasksStatusClosed : R.string.ZG_tasks_error_accountChanged);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final int index = item.id - ID_ROW_BASE;
        if (index < 0 || index >= board.lists.size() || sheetOpen) {
            return;
        }
        final ZgBoardList list = board.lists.get(index);
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final String listId = list.id;
        // Read-only: lists open, nothing else. Otherwise what the list allows.
        final boolean writable = board.canCompose();
        final ArrayList<CharSequence> titles = new ArrayList<>();
        final ArrayList<Integer> icons = new ArrayList<>();
        final ArrayList<Integer> actions = new ArrayList<>();
        titles.add(getString(R.string.ZG_tasks_action_open));
        icons.add(R.drawable.msg_openin);
        actions.add(ACTION_OPEN);
        if (writable && list.sync == ZgBoardTask.Sync.NEEDS_ATTENTION) {
            titles.add(getString(R.string.ZG_tasks_cloud_status_review));
            icons.add(R.drawable.msg_report);
            actions.add(ACTION_REVIEW);
        }
        if (writable && !list.inbox) {
            titles.add(getString(R.string.ZG_tasks_action_rename));
            icons.add(R.drawable.msg_edit);
            actions.add(ACTION_RENAME);
        }
        if (writable) {
            titles.add(getString(R.string.Delete));
            icons.add(R.drawable.msg_delete);
            actions.add(ACTION_DELETE);
        }
        final int[] iconIds = new int[icons.size()];
        for (int i = 0; i < iconIds.length; i++) {
            iconIds[i] = icons.get(i);
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourceProvider);
        builder.setTitle(title(list));
        builder.setItems(titles.toArray(new CharSequence[0]), iconIds, (dialog, which) -> {
            if (which < 0 || which >= actions.size()) {
                return;
            }
            switch (actions.get(which)) {
                case ACTION_OPEN:
                    if (openListener != null) {
                        openListener.onOpen(listId);
                    }
                    finishFragment();
                    break;
                case ACTION_REVIEW:
                    presentFragment(new ZgTasksUnsentActivity(currentAccount));
                    break;
                case ACTION_RENAME:
                    rename(listId, null, null);
                    break;
                default:
                    confirmDelete(listId);
                    break;
            }
        });
        final BottomSheet sheet = builder.create();
        sheetOpen = true;
        if (showDialog(sheet, d -> sheetOpen = false) == null) {
            sheetOpen = false;
            return;
        }
        final int deleteAt = actions.indexOf(ACTION_DELETE);
        if (deleteAt >= 0) {
            final int red = Theme.getColor(Theme.key_text_RedRegular, resourceProvider);
            sheet.setItemColor(deleteAt, red, red);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /**
     * Renaming changes an existing list: never gated. {@code value} / {@code text}: the name typed before and why it
     * is not saved yet (the copy of the device tasks to Cloud was running its local step), so another try keeps it.
     */
    private void rename(final String listId, final String value, final String text) {
        AndroidUtilities.runOnUIThread(() -> {
            final ZgBoardList list = board.list(listId);
            if (destroyed || list == null) {
                return; // deleted meanwhile
            }
            final int limit = source.listTitleLimit();
            ZgTasksNameAlert.show(this,
                getString(R.string.ZG_tasks_renameFolder_title),
                text != null ? text : getString(R.string.ZG_tasks_newFolder_prompt),
                getString(R.string.ZG_tasks_newFolder_placeholder),
                value != null ? value : list.title, limit, name -> {
                    final ZgBoardList current = board.list(listId);
                    if (current != null && name.equals(current.title)) {
                        return; // nothing to save
                    }
                    source.renameList(listId, name, (ignored, error) -> {
                        if (error == null || destroyed) {
                            return;
                        }
                        if (error.kind == ZgTasksError.Kind.MIGRATING) {
                            rename(listId, name, getString(R.string.ZG_tasks_migration_busy));
                        } else if (error.kind != ZgTasksError.Kind.NOT_FOUND || scope == ZgTasksScope.CLOUD) {
                            // A device list deleted meanwhile just leaves the screen with the next board.
                            ZgTasksUi.report(this, error, board, limit);
                        }
                    });
                });
        }, 200);
    }

    /** Deletes the list and all its tasks after a confirmation that names the count. No Undo. */
    private void confirmDelete(final String listId) {
        final Activity activity = getParentActivity();
        final ZgBoardList list = board.list(listId);
        if (activity == null || list == null) {
            return;
        }
        final int[] c = counts.get(listId);
        final int taskCount = c != null ? c[1] : 0;
        final String message;
        if (scope == ZgTasksScope.CLOUD) {
            message = taskCount == 0 ? getString(R.string.ZG_tasks_cloud_deleteList_textEmpty)
                : formatPluralString("ZG_tasks_cloud_deleteList_text", taskCount);
        } else {
            message = taskCount == 0 ? getString(R.string.ZG_tasks_deleteFolder_textEmpty)
                : formatPluralString("ZG_tasks_deleteFolder_text", taskCount);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(getString(R.string.ZG_tasks_deleteFolder_title));
        builder.setMessage(message);
        builder.setNegativeButton(getString(R.string.Cancel), (d, which) -> d.dismiss());
        builder.setPositiveButton(getString(R.string.Delete), (d, which) -> {
            d.dismiss();
            source.deleteList(listId, (ignored, error) -> {
                if (error == null || destroyed) {
                    return;
                }
                if (error.kind != ZgTasksError.Kind.NOT_FOUND || scope == ZgTasksScope.CLOUD) {
                    ZgTasksUi.report(this, error, board, source.listTitleLimit());
                }
            });
        });
        final AlertDialog dialog = builder.create();
        AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed && showDialog(dialog) != null) {
                dialog.redPositive();
            }
        }, 200);
    }
}
