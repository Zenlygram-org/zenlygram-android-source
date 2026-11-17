/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgBoardTask;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.source.ZgDeviceTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Edit Task (iOS TasksTaskEditorController): the name, the assignee and the deadline of a task on this device. Never
 * gated: it edits, it does not create. A Cloud task has its own editor (ZgCloudTaskEditorActivity).
 *
 * <ul>
 *   <li>Save is disabled while the name has nothing visible; Save without a change just closes; a too-long name shows
 *       the limit. A save the store refuses keeps the editor open with the draft: while the device tasks are being
 *       copied to Cloud it says to try again in a moment; any other failure shows the generic error.</li>
 *   <li>While the device board is the read-only archive of its Cloud copy the task opens to be read: no field, picker
 *       or Save, under the archive's title, and the footer says why. A board that becomes the archive while the
 *       editor is open turns it read-only the same way; the draft stays visible.</li>
 *   <li>Back with changes asks "Discard changes?" (Keep Editing / Discard); the dialog closes only on a choice, and the
 *       swipe-back gesture is off while there are changes.</li>
 *   <li>Whitespace that Save would trim is not a change. The list, notes, priority and completion are never touched.</li>
 *   <li>The editor closes when its task is gone (its account left this device).</li>
 * </ul>
 */
public class ZgTaskEditorActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    private static final int MENU_SAVE = 1;
    private static final int ID_ASSIGNEE = 1;
    private static final int ID_REMOVE_ASSIGNEE = 2;
    private static final int ID_DEADLINE = 3;
    private static final int ID_REMOVE_DEADLINE = 4;
    private static final int ID_HEADER = 5;
    private static final int ID_NAME = 6;

    private final String taskId;
    private ZgDeviceTasksSource source;
    private ZgTasksSource.Subscription subscription;
    /** The task as it was opened: what counts as a change is measured against it. */
    private ZgBoardTask task;
    private ZgTodoAssignee assignee;
    private Long dueAt;
    private boolean readOnly;
    private EditTextCell nameCell;
    private SimpleActionBarMenuItem saveItem;
    private boolean sheetOpen;
    private boolean saving;
    private boolean finishing;
    private boolean destroyed;

    public ZgTaskEditorActivity(int account, String taskId) {
        super();
        this.currentAccount = account;
        this.taskId = taskId;
    }

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.TASKS_TASK_EDITOR;
    }

    @Override
    public boolean onFragmentCreate() {
        source = ZgTasksSources.device(currentAccount);
        task = source.board().task(taskId);
        if (task == null) {
            return false; // deleted meanwhile: there is nothing to open
        }
        assignee = task.assignee;
        dueAt = task.dueAt;
        readOnly = !task.canEdit;
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
        // Read-only: the archive's title, as on iOS.
        return getString(readOnly ? R.string.ZG_tasks_archive_segment : R.string.ZG_tasks_editTask_screenTitle);
    }

    @Override
    public View createView(Context context) {
        nameCell = new EditTextCell(context, getString(R.string.ZG_tasks_newTask_placeholder), true, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                updateSaveEnabled();
            }
        };
        final InputFilter[] existing = nameCell.editText.getFilters();
        final InputFilter[] filters = new InputFilter[existing.length + 1];
        System.arraycopy(existing, 0, filters, 0, existing.length);
        filters[existing.length] = (source, start, end, dest, dstart, dend) -> {
            final String updated = TextUtils.concat(dest.subSequence(0, dstart), source.subSequence(start, end),
                dest.subSequence(dend, dest.length())).toString();
            return ZgTasksTitlePolicy.acceptsDraftEdit(dest, updated) ? null : dest.subSequence(dstart, dend);
        };
        nameCell.editText.setFilters(filters);
        nameCell.setText(task.title);

        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == MENU_SAVE) {
                    save();
                }
            }
        });
        final SimpleActionBarMenu menu = simpleActionBar.createMenu();
        saveItem = menu.addItemWithWidth(MENU_SAVE, R.drawable.ic_ab_done, dp(56), getString(R.string.Save));
        applyReadOnly();
        updateSaveEnabled();
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

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (listView != null) {
            AndroidUtilities.runOnUIThread(() -> listView.adapter.notifyDataSetChanged());
        }
    }

    /** The store changed under the editor: its task may be gone, or its board may have become the archive. */
    private void onBoard(ZgBoard board) {
        if (destroyed || finishing) {
            return;
        }
        final ZgBoardTask current = board.task(taskId);
        if (current == null) {
            if (board.gate != null || board.complete && !board.failed) {
                // Nothing is left to edit (the account left this device, or the task was deleted elsewhere).
                AndroidUtilities.runOnUIThread(this::close);
            }
            return;
        }
        if (!current.canEdit && !readOnly) {
            setReadOnly();
        }
    }

    /** The board is the archive now: the draft stays visible, nothing can be changed (iOS update(archiveReason:)). */
    private void setReadOnly() {
        if (readOnly) {
            return;
        }
        readOnly = true;
        if (fragmentView != null) {
            simpleActionBar.setTitle(getTitle());
            applyReadOnly();
            refresh();
        }
    }

    private void applyReadOnly() {
        if (nameCell == null) {
            return;
        }
        if (readOnly) {
            AndroidUtilities.hideKeyboard(nameCell.editText);
        }
        nameCell.editText.setEnabled(!readOnly);
        nameCell.editText.setFocusable(!readOnly);
        nameCell.editText.setFocusableInTouchMode(!readOnly);
        if (saveItem != null) {
            saveItem.setShown(!readOnly, false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (nameCell == null) {
            return;
        }
        items.add(UItem.asHeader(ID_HEADER, getString(R.string.ZG_tasks_editTask_nameHeader)));
        items.add(UItem.asCustom(ID_NAME, nameCell));
        items.add(UItem.asShadow(-1, null));
        // Read-only: values, not controls.
        items.add(UItem.asButton(ID_ASSIGNEE, R.drawable.msg_contacts, getString(R.string.ZG_tasks_assignee_title),
            assignee != null ? ZgTasksUi.deviceAssigneeText(currentAccount, assignee.displayName) : getString(R.string.ZG_tasks_metadata_none))
            .setEnabled(!readOnly));
        if (assignee != null && !readOnly) {
            items.add(UItem.asButton(ID_REMOVE_ASSIGNEE, R.drawable.msg_delete, getString(R.string.ZG_tasks_assignee_remove)).red());
        }
        final long now = System.currentTimeMillis() / 1000L;
        items.add(UItem.asButton(ID_DEADLINE, R.drawable.msg_calendar2, getString(R.string.ZG_tasks_deadline_title),
            dueAt != null ? ZgTasksText.deadlineText(dueAt, now) : getString(R.string.ZG_tasks_metadata_none)).setEnabled(!readOnly));
        if (dueAt != null && !readOnly) {
            items.add(UItem.asButton(ID_REMOVE_DEADLINE, R.drawable.msg_delete, getString(R.string.ZG_tasks_deadline_remove)).red());
        }
        items.add(UItem.asShadow(-2, readOnly ? ZgTasksUi.readOnlyText(ZgBoard.ReadOnly.ARCHIVE) : getString(R.string.ZG_tasks_metadata_localFooter)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (readOnly) {
            return;
        }
        switch (item.id) {
            case ID_ASSIGNEE:
                AndroidUtilities.hideKeyboard(nameCell.editText);
                // Where assigning needs sync, a pick here would be a note nobody receives: explained, nothing stored.
                if (ZgTasksUi.answerDevicePick(this, currentAccount)) {
                    break;
                }
                ZgTasksAssigneePicker.present(this, picked -> {
                    assignee = picked;
                    refresh();
                });
                break;
            case ID_REMOVE_ASSIGNEE:
                assignee = null;
                refresh();
                break;
            case ID_DEADLINE:
                pickDeadline();
                break;
            case ID_REMOVE_DEADLINE:
                dueAt = null;
                refresh();
                break;
            default:
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void pickDeadline() {
        final Activity activity = getParentActivity();
        if (sheetOpen || activity == null) {
            return;
        }
        AndroidUtilities.hideKeyboard(nameCell.editText);
        sheetOpen = true;
        final ZgTasksDeadlineSheet sheet = new ZgTasksDeadlineSheet(activity, resourceProvider, dueAt, new ZgTasksDeadlineSheet.Callback() {
            @Override
            public void onSet(long value) {
                dueAt = value;
                refresh();
            }

            @Override
            public void onClear() {
                dueAt = null;
                refresh();
            }
        });
        if (showDialog(sheet, d -> sheetOpen = false) == null) {
            sheetOpen = false;
        }
    }

    private void refresh() {
        if (listView != null) {
            listView.adapter.update(true);
        }
        updateSaveEnabled();
    }

    private String draftTitle() {
        return nameCell != null ? ZgTasksTitlePolicy.trim(nameCell.getText()) : task.title;
    }

    /** A read-only editor has nothing to lose: Back just closes it. */
    private boolean isDirty() {
        return !readOnly && (!TextUtils.equals(draftTitle(), task.title) || !Objects.equals(assignee, task.assignee) || !Objects.equals(dueAt, task.dueAt));
    }

    private void updateSaveEnabled() {
        if (saveItem == null) {
            return;
        }
        final boolean enabled = !saving && ZgTasksTitlePolicy.containsVisible(draftTitle());
        saveItem.setEnabled(enabled);
        saveItem.setAlpha(enabled ? 1f : 0.5f);
    }

    private void save() {
        if (readOnly || saving) {
            return;
        }
        final ZgTasksTitlePolicy.Result result = ZgTasksTitlePolicy.validate(nameCell.getText(), ZgTasksTitlePolicy.TASK_LIMIT);
        if (result.kind == ZgTasksTitlePolicy.Kind.EMPTY) {
            return;
        }
        if (result.kind == ZgTasksTitlePolicy.Kind.TOO_LONG) {
            ZgTasks.showError(this, formatString(R.string.ZG_tasks_error_titleTooLong, ZgTasksTitlePolicy.TASK_LIMIT));
            return;
        }
        if (!isDirty()) {
            close();
            return;
        }
        saving = true;
        updateSaveEnabled();
        source.updateTask(taskId, result.title, assignee, dueAt, (ignored, error) -> {
            saving = false;
            if (destroyed || finishing) {
                return;
            }
            updateSaveEnabled();
            if (error == null || error.kind == ZgTasksError.Kind.NOT_FOUND) {
                // Saved; or the task is gone and there is nothing left to save to.
                close();
                return;
            }
            if (error.kind == ZgTasksError.Kind.ARCHIVED) {
                // The board became the archive meanwhile: the draft stays visible, read-only.
                setReadOnly();
            }
            // A fence (the copy's local step, the archive) is a short note and the draft stays; anything else an alert.
            ZgTasksUi.report(this, error, source.board(), ZgTasksTitlePolicy.TASK_LIMIT);
        });
    }

    private void close() {
        if (finishing) {
            return;
        }
        finishing = true;
        if (nameCell != null) {
            AndroidUtilities.hideKeyboard(nameCell.editText);
        }
        finishFragment();
    }

    @Override
    public boolean canBeginSlide() {
        return (finishing || !isDirty()) && super.canBeginSlide();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!finishing && isDirty()) {
            if (invoked) {
                showDiscardDialog();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void showDiscardDialog() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AndroidUtilities.hideKeyboard(nameCell.editText);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(getString(R.string.ZG_tasks_discardChanges_title));
        builder.setMessage(getString(R.string.ZG_tasks_discardChanges_text));
        builder.setNegativeButton(getString(R.string.ZG_tasks_discardChanges_keepEditing), (d, which) -> d.dismiss());
        builder.setPositiveButton(getString(R.string.ZG_tasks_discardChanges_discard), (d, which) -> {
            d.dismiss();
            close();
        });
        final AlertDialog dialog = builder.create();
        if (showDialog(dialog) != null) {
            dialog.setCanceledOnTouchOutside(false);
            dialog.redPositive();
        }
    }
}
