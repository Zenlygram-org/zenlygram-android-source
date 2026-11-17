/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.zenlygram.tasks.ZgTasksGate;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.ui.ZgTasksNameAlert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Cloud Tasks "Unsent Changes" (iOS TasksCloudIssuesController): every change saved on this device that the server has
 * not confirmed, with why it is waiting and, only where {@link ZgCloudIssues} allows it, the explicit choices. Nothing
 * is merged or sent again without one of these taps.
 *
 * <ul>
 *   <li>The changes that wait for the user come first, then the ones the repository is still working on (no buttons).
 *       The copies of device tasks that are simply waiting are one line with their count: an automatic copy can be
 *       thousands of rows.</li>
 *   <li>Each choice is a repository intent, or two of them in a row where the repository has no single one:
 *       Save as New Task creates the entry's task again and then discards the entry; Edit and Send Again discards a
 *       refused edit and sends the corrected one, or creates the corrected task / list and then discards the refused
 *       create (see {@link ZgCloudTaskEditorActivity#repair}). A list created that way is a new list: tasks that were
 *       waiting for the refused one are asked about one by one.</li>
 *   <li>Every row looks its entry up again when it is tapped and runs only what the entry offers now.</li>
 *   <li>While a consent hold makes the account read-only only Discard runs. Creating again (a retried or corrected
 *       create, a saved copy) passes the same CREATE gate as a new task.</li>
 *   <li>The repository's notices (a task no longer shared, a delete that was kept, ...) are listed first and can be
 *       closed.</li>
 * </ul>
 */
public class ZgTasksUnsentActivity extends ZgCloudScreen {

    private static final int MENU_RETRY = 1;

    private static final int ID_READ_ONLY = 1;
    private static final int ID_STATUS = 2;
    private static final int ID_COPIES = 3;
    private static final int ID_COPIES_GAP = 4;
    private static final int ID_MORE = 5;
    private static final int ID_FOOTER = 6;

    private static final int SLOT_INFO = 1;
    private static final int SLOT_GAP = 2;
    private static final int SLOT_NOTICE = 3;
    private static final int SLOT_NOTICE_CLOSE = 4;
    private static final int SLOT_NOTICE_GAP = 5;
    private static final int SLOT_ACTION = 10;

    /** Tap actions below this are {@link ZgCloudIssues.Action} ordinals. */
    private static final int DO_CLOSE_NOTICE = 100;

    /** How many entries are drawn: the ones that need the user, then the ones that only wait. */
    private static final int MAX_ATTENTION = 200;
    private static final int MAX_WAITING = 100;

    private SimpleActionBarMenuItem retryItem;
    /** Titles of the tasks and lists this screen has seen: what a change is about once its row is gone. */
    private final HashMap<String, String> titles = new HashMap<>();

    public ZgTasksUnsentActivity(int account) {
        super(account);
    }

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.TASKS_UNSENT_CHANGES;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_cloudui_issues_title);
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_RETRY && repository != null) {
                    repository.refresh();
                }
            }
        });
        retryItem = simpleActionBar.createMenu().addItem(MENU_RETRY, R.drawable.msg_retry);
        retryItem.setContentDescription(getString(R.string.ZG_cloudui_issues_retry));
        updateRetry();
        return view;
    }

    @Override
    protected void onStateChanged() {
        final TasksCloudState value = state;
        if (value != null) {
            for (TasksCloudState.TaskView task : value.tasks) {
                titles.put(task.id, task.content.title);
            }
            for (TasksCloudState.ListView list : value.lists) {
                titles.put(list.id, list.title);
            }
            final ArrayList<String> people = new ArrayList<>();
            for (TasksCloudState.Pending pending : value.pending) {
                if (pending.kind == TasksWire.OperationKind.SENDER_UNBLOCK) {
                    people.add(pending.resourceId);
                }
            }
            loadNames(people);
        }
        updateRetry();
        super.onStateChanged();
    }

    /** Offered only while an explicit sync can move something. */
    private void updateRetry() {
        if (retryItem == null) {
            return;
        }
        final TasksCloudState value = state;
        final boolean shown = value != null && repository != null && !contentHidden() && ZgCloudIssues.retryResumesSync(value.status, readOnly());
        retryItem.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Rows
    // -----------------------------------------------------------------------------------------------------------

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final TasksCloudState value = state;
        final int unavailable = unavailableText();
        if (value == null || contentHidden()) {
            items.add(UItem.asShadow(ID_STATUS, getString(unavailable != 0 ? unavailable : R.string.ZenlyCloudTasksStatusClosed)));
            return;
        }
        final ZgCloudReadOnly readOnly = readOnly();
        addReadOnlyNotice(items, ID_READ_ONLY);

        for (TasksCloudState.Notice notice : value.notices) {
            final String key = String.valueOf(notice.id);
            items.add(ZgCloudInfoCell.Factory.of(rowId(key, SLOT_NOTICE), null, getString(ZgCloudText.noticeRes(notice.kind))));
            items.add(action(rowId(key, SLOT_NOTICE_CLOSE), 0, getString(R.string.ZenlyCloudTasksClose), false, new ZgCloudTap(DO_CLOSE_NOTICE, key)));
            items.add(UItem.asShadow(rowId(key, SLOT_NOTICE_GAP), null));
        }

        int attention = 0;
        int waiting = 0;
        int copies = 0;
        int hidden = 0;
        for (TasksCloudState.Pending pending : value.pending) {
            if (ZgCloudIssues.listed(pending.state) && ZgCloudIssues.needsAttention(pending.state)) {
                if (attention++ < MAX_ATTENTION) {
                    fillEntry(items, value, pending, readOnly);
                } else {
                    hidden++;
                }
            }
        }
        for (TasksCloudState.Pending pending : value.pending) {
            if (!ZgCloudIssues.listed(pending.state) || ZgCloudIssues.needsAttention(pending.state)) {
                continue;
            }
            if (pending.migration) {
                copies++;
            } else if (waiting++ < MAX_WAITING) {
                fillEntry(items, value, pending, readOnly);
            } else {
                hidden++;
            }
        }
        if (copies > 0) {
            items.add(ZgCloudInfoCell.Factory.of(ID_COPIES, getString(R.string.ZenlyCloudTasksChangeMigration),
                formatString(R.string.ZG_cloudui_issues_copiesWaiting, copies)));
            items.add(UItem.asShadow(ID_COPIES_GAP, null));
        }
        if (hidden > 0) {
            items.add(UItem.asShadow(ID_MORE, formatString(R.string.ZG_cloudui_issues_more, hidden)));
        }
        if (attention == 0 && waiting == 0 && copies == 0 && value.notices.isEmpty()) {
            // Never "All changes are sent" before the outbox could be read.
            items.add(UItem.asShadow(ID_STATUS, getString(value.status == TasksCloudState.Status.LOADING && value.contentFree()
                ? R.string.ZenlyCloudTasksStatusLoading : R.string.ZG_cloudui_issues_empty)));
        }
        items.add(UItem.asShadow(ID_FOOTER, getString(R.string.ZG_cloudui_issues_footer)));
    }

    /** One change: its kind above, what it is about and why it waits below, then its choices. */
    private void fillEntry(ArrayList<UItem> items, TasksCloudState value, TasksCloudState.Pending pending, ZgCloudReadOnly readOnly) {
        final String id = pending.mutationId;
        final ZgCloudIssues.Display display = ZgCloudIssues.display(pending.state, pending.reason, hasFields(pending), pending.error,
            value.status, readOnly);
        final StringBuilder text = new StringBuilder();
        final String subject = subject(value, pending);
        if (subject != null) {
            text.append(subject).append('\n');
        }
        text.append(ZgCloudText.state(display, pending.conflictFields,
            display == ZgCloudIssues.Display.MEMBERS_CHANGED ? listTaskCount(value, pending.resourceId) : 0));
        items.add(ZgCloudInfoCell.Factory.of(rowId(id, SLOT_INFO), ZgCloudText.operation(pending.kind, pending.migration), text));
        for (ZgCloudIssues.Action action : actions(value, pending)) {
            items.add(action(rowId(id, SLOT_ACTION + action.ordinal()), icon(action), getString(ZgCloudText.actionRes(action)),
                action.discards() || action == ZgCloudIssues.Action.DELETE_AGAIN, new ZgCloudTap(action.ordinal(), id)));
        }
        items.add(UItem.asShadow(rowId(id, SLOT_GAP), null));
    }

    private static int icon(ZgCloudIssues.Action action) {
        switch (action) {
            case RETRY:
                return R.drawable.msg_retry;
            case APPLY_MINE:
            case KEEP_MINE:
                return R.drawable.msg_send;
            case SAVE_AS_NEW:
                return R.drawable.msg_copy;
            case REPAIR_TASK:
            case REPAIR_LIST:
                return R.drawable.msg_edit;
            case KEEP_SERVER:
            case USE_OTHER:
                return R.drawable.msg_cancel;
            default:
                return R.drawable.msg_delete;
        }
    }

    private static boolean hasFields(TasksCloudState.Pending pending) {
        return pending.conflictFields != null && !pending.conflictFields.isEmpty();
    }

    private static List<ZgCloudIssues.Action> actions(TasksCloudState value, TasksCloudState.Pending pending) {
        return ZgCloudIssues.actions(pending.state, pending.kind, pending.reason, hasFields(pending), pending.error, pending.migration,
            contentKnown(value, pending));
    }

    private static boolean contentKnown(TasksCloudState value, TasksCloudState.Pending pending) {
        final TasksWire.Operation draft = pending.draft;
        if (draft instanceof TasksWire.Operation.ListCreate || draft instanceof TasksWire.Operation.ListUpdate) {
            return true;
        }
        return taskContent(value, pending) != null;
    }

    /** What a change is about: the title it carries, else the title of its task or list while this screen knows it. */
    private String subject(TasksCloudState value, TasksCloudState.Pending pending) {
        final TasksWire.Operation draft = pending.draft;
        String title = null;
        if (draft instanceof TasksWire.Operation.ListCreate) {
            title = ((TasksWire.Operation.ListCreate) draft).title;
        } else if (draft instanceof TasksWire.Operation.ListUpdate) {
            title = ((TasksWire.Operation.ListUpdate) draft).title;
        } else if (draft instanceof TasksWire.Operation.TaskCreate) {
            title = ((TasksWire.Operation.TaskCreate) draft).content.title;
        } else if (draft instanceof TasksWire.Operation.TaskUpdate) {
            title = ((TasksWire.Operation.TaskUpdate) draft).content.title;
        } else if (draft instanceof TasksWire.Operation.TaskPatch) {
            final Object patched = ((TasksWire.Operation.TaskPatch) draft).set.get(TasksWire.ContentField.TITLE);
            title = patched instanceof String ? (String) patched : null;
        }
        if (title == null) {
            if (pending.kind == TasksWire.OperationKind.SENDER_UNBLOCK) {
                return person(pending.resourceId);
            }
            if (pending.kind == TasksWire.OperationKind.SENDER_BLOCK) {
                return null;
            }
            if (draft instanceof TasksWire.Operation.InvitationRespond) {
                final TasksWire.InvitationItem invitation = value.invitation(((TasksWire.Operation.InvitationRespond) draft).invitationId);
                if (invitation != null) {
                    title = invitation.title;
                }
            }
        }
        if (title == null) {
            // Every other change names its task or list by id.
            title = titles.get(pending.resourceId);
        }
        return title == null ? null : ZgCloudText.isolated(title);
    }

    /** The owner's tasks in a list now: a list delete is asked again with this count. */
    private static int listTaskCount(TasksCloudState value, String listId) {
        int count = 0;
        if (value != null && listId != null) {
            for (TasksCloudState.TaskView task : value.tasks) {
                if (task.owner && listId.equals(task.content.listId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static TasksCloudState.Pending find(TasksCloudState value, String mutationId) {
        if (value != null && mutationId != null) {
            for (TasksCloudState.Pending pending : value.pending) {
                if (mutationId.equals(pending.mutationId)) {
                    return pending;
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Choices
    // -----------------------------------------------------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object2 instanceof ZgCloudTap) || repository == null) {
            return;
        }
        final ZgCloudTap tap = (ZgCloudTap) item.object2;
        if (tap.action == DO_CLOSE_NOTICE) {
            try {
                repository.dismissNotice(Long.parseLong(tap.key));
            } catch (NumberFormatException ignore) {
                // Not one of this screen's rows.
            }
            return;
        }
        final ZgCloudIssues.Action[] all = ZgCloudIssues.Action.values();
        if (tap.action >= 0 && tap.action < all.length) {
            perform(all[tap.action], tap.key);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** The entry as it is now if it still offers {@code action}; otherwise null after saying why nothing ran. */
    private TasksCloudState.Pending current(ZgCloudIssues.Action action, String mutationId) {
        final TasksCloudState value = state;
        final TasksCloudState.Pending pending = find(value, mutationId);
        if (pending == null) {
            showNotice(getString(R.string.ZG_cloudui_error_gone));
            return null;
        }
        if (!actions(value, pending).contains(action)) {
            showNotice(getString(R.string.ZG_cloudui_error_changedElsewhere));
            render();
            return null;
        }
        return pending;
    }

    private void perform(ZgCloudIssues.Action action, String mutationId) {
        final TasksCloudState.Pending pending = current(action, mutationId);
        if (pending == null || repository == null) {
            return;
        }
        // While the account is read-only only a discard (this device only) runs.
        if (!action.discards() && refuseReadOnly()) {
            return;
        }
        switch (action) {
            case RETRY:
                if (!ZgCloudIssues.needsCreateGate(action, pending.kind) || ZgTasksGate.allowCreate(this)) {
                    repository.retry(mutationId, result(null));
                }
                break;
            case APPLY_MINE:
            case KEEP_MINE:
                repository.resolveConflict(mutationId, true, result(null));
                break;
            case DELETE_AGAIN:
                // No Undo for a list: the delete is confirmed again with the list's tasks as they are now.
                confirm(getString(R.string.ZG_tasks_deleteFolder_title),
                    formatString(R.string.ZenlyCloudTasksMembersChanged, listTaskCount(state, pending.resourceId)),
                    getString(R.string.Delete), true, null, () -> {
                        if (current(action, mutationId) != null && repository != null && !refuseReadOnly()) {
                            repository.resolveConflict(mutationId, true, result(null));
                        }
                    });
                break;
            case SAVE_AS_NEW:
                saveAsNew(pending);
                break;
            case REPAIR_TASK:
                repairTask(pending);
                break;
            case REPAIR_LIST:
                repairList(pending);
                break;
            default:
                confirm(getString(R.string.ZG_cloudui_discard_title), getString(R.string.ZG_cloudui_discard_text),
                    getString(ZgCloudText.actionRes(action)), true, null, () -> {
                        if (repository != null) {
                            repository.discard(mutationId, result(null));
                        }
                    });
                break;
        }
    }

    /**
     * A vanished own task, or one decided under another Zenlygram account, as a new task: a creation, so it passes the
     * CREATE gate, and it keeps its list only while that list still exists (else the Cloud Inbox). The entry is
     * discarded once the new task is queued.
     */
    private void saveAsNew(TasksCloudState.Pending pending) {
        final String mutationId = pending.mutationId;
        final Runnable run = () -> {
            final TasksCloudState.Pending entry = current(ZgCloudIssues.Action.SAVE_AS_NEW, mutationId);
            if (entry == null || repository == null || refuseReadOnly() || !ZgTasksGate.allowCreate(this)) {
                return;
            }
            final TasksCloudState value = state;
            final TasksWire.Content content = taskContent(value, entry);
            if (content == null) {
                showNotice(getString(R.string.ZG_cloudui_error_gone));
                return;
            }
            final TasksWire.Content copy;
            try {
                copy = new TasksWire.Content(content.listId != null && value.list(content.listId) != null ? content.listId : null,
                    content.title, content.description, content.dueAt, content.priority, content.sortOrder, content.annotation);
            } catch (IllegalArgumentException e) {
                showNotice(getString(R.string.ZenlyCloudTasksInvalid));
                return;
            }
            repository.createTask(copy, result(created -> {
                if (repository != null) {
                    repository.discard(mutationId, result(null));
                }
            }));
        };
        if (pending.state == TasksCloudState.PendingState.WRONG_PRINCIPAL) {
            // Copying it is a new creation in the account linked now: said before anything is created.
            confirm(getString(R.string.ZG_cloudui_accountChanged_copyTitle), getString(R.string.ZG_cloudui_accountChanged_copyText),
                getString(R.string.ZG_cloudui_action_saveAsNew), false, null, run);
        } else {
            run.run();
        }
    }

    /**
     * A refused task create or edit opens the editor on that entry's own content. Later edits of the same task still
     * carry the refused content: they are discarded (or settle) first, so the correction is neither overwritten nor
     * refused again.
     */
    private void repairTask(TasksCloudState.Pending pending) {
        final TasksCloudState value = state;
        final TasksCloudState.TaskView task = value.task(pending.resourceId);
        if (task == null || !task.owner || taskContent(value, pending) == null) {
            showNotice(getString(R.string.ZG_cloudui_error_gone));
            return;
        }
        boolean after = false;
        for (TasksCloudState.Pending other : value.pending) {
            if (other == pending) {
                after = true;
            } else if (after && pending.resourceId.equals(other.resourceId)
                && (other.kind == TasksWire.OperationKind.TASK_UPDATE || other.kind == TasksWire.OperationKind.TASK_PATCH)) {
                showNotice(getString(R.string.ZG_cloudui_repair_laterChanges));
                return;
            }
        }
        if (ZgCloudIssues.needsCreateGate(ZgCloudIssues.Action.REPAIR_TASK, pending.kind) && !ZgTasksGate.allowCreate(this)) {
            return;
        }
        presentFragment(ZgCloudTaskEditorActivity.repair(currentAccount, pending.resourceId, pending.mutationId));
    }

    /**
     * A refused list create or rename only has a name to fix, and the same name is not sent again. A rename is the
     * refused entry discarded and the new name sent; a create is a new list with the new name, then the refused one
     * discarded.
     */
    private void repairList(TasksCloudState.Pending pending) {
        final String mutationId = pending.mutationId;
        final String listId = pending.resourceId;
        final boolean create = pending.draft instanceof TasksWire.Operation.ListCreate;
        final String refused;
        final int sortOrder;
        if (create) {
            refused = ((TasksWire.Operation.ListCreate) pending.draft).title;
            sortOrder = ((TasksWire.Operation.ListCreate) pending.draft).sortOrder;
        } else if (pending.draft instanceof TasksWire.Operation.ListUpdate) {
            refused = ((TasksWire.Operation.ListUpdate) pending.draft).title;
            sortOrder = ((TasksWire.Operation.ListUpdate) pending.draft).sortOrder;
        } else {
            showNotice(getString(R.string.ZG_cloudui_error_gone));
            return;
        }
        if (create && !ZgTasksGate.allowCreate(this)) {
            return;
        }
        ZgTasksNameAlert.show(this, getString(R.string.ZG_tasks_renameFolder_title), getString(R.string.ZG_cloudui_repair_listText),
            getString(R.string.ZG_tasks_newFolder_placeholder), refused, ZgTasksTitlePolicy.FOLDER_LIMIT, name -> {
                if (destroyed || repository == null) {
                    return;
                }
                if (name.equals(ZgTasksTitlePolicy.trim(refused))) {
                    showNotice(getString(R.string.ZG_cloudui_repair_unchanged));
                    return;
                }
                if (current(ZgCloudIssues.Action.REPAIR_LIST, mutationId) == null || refuseReadOnly()) {
                    return;
                }
                if (create) {
                    repository.createList(name, sortOrder, result(created -> {
                        if (repository != null) {
                            repository.discard(mutationId, result(null));
                        }
                    }));
                } else {
                    repository.discard(mutationId, result(discarded -> {
                        if (repository != null) {
                            repository.updateList(listId, name, sortOrder, result(null));
                        }
                    }));
                }
            });
    }
}
