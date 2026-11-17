/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

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
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAssigneeDisplay;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudError;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.ZgTasksGate;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.ui.ZgTasksAssigneePicker;
import org.telegram.zenlygram.tasks.ui.ZgTasksUi;
import org.telegram.zenlygram.tasks.ui.ZgTasksDeadlineSheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Objects;

/**
 * One Cloud task, opened from the cloud board (iOS TasksCloudTaskEditorController).
 *
 * <p><b>Owner</b>: title, notes, list, deadline and priority; Mark as Done / Not Done; the assignee; Delete Task.
 * Editing is never gated by the sync state. The assignee is the assignment (iOS wf15 §4): picking a contact sends the
 * invitation at once — there is no separate share and the pick is not part of Save. The row reads "Name · state";
 * its actions follow the state (Change / Remove a live assignment after a confirmation that names who loses the task,
 * Send Again for an ended one, Send / Remove for an older note that was never sent). Self, a completed task and the
 * assignment limits are refused at pick time with their sentence.
 *
 * <p><b>Assignee</b> (a task another account assigned to this one): who assigned it, the content read-only, Mark as
 * Done / Not Done and a confirmed Leave. Without an accepted invitation it offers neither (fail closed). While the
 * device's automatic accept of a contact's task is on its way the screen is a read-only preview of the invitation.
 *
 * <ul>
 *   <li>The screen follows the repository. Fields the user has not touched follow a newer version of the task as it
 *       arrives; a touched field keeps the user's value. Save hands the repository the current version with only the
 *       touched fields replaced, so nothing changed elsewhere is overwritten; when a touched field itself changed
 *       elsewhere, Save asks first (Keep Current Version / Apply My Change Again). iOS asks whenever the task changed;
 *       here the repository sends single fields, so the untouched ones need no question.</li>
 *   <li>Every row looks the task up again when it is tapped and acts only if the user may still do what the row said; a
 *       refusal is explained, never retried.</li>
 *   <li>If the task goes away the editor closes and the screen under it says so. Unsaved edits outlive the task: they
 *       stay on screen and Save offers to keep them as a new task (a creation, so it passes the CREATE gate).</li>
 *   <li>Delete closes the editor and holds the delete, as on iOS; the Undo is shown on the screen under it
 *       ({@link ZgCloudHeldDelete}).</li>
 *   <li>While a consent hold makes the account read-only the task opens read-only with the reason. Edits typed before
 *       the hold began stay on screen, but nothing is queued until it ends.</li>
 *   <li>{@link #repair}: the same content fields on a change the server refused, nothing else. Only Save with an actual
 *       change sends the corrected content; the same content is never sent again.</li>
 * </ul>
 */
public class ZgCloudTaskEditorActivity extends ZgCloudScreen {

    private static final int MENU_SAVE = 1;

    private static final int ID_STATUS = 1;
    private static final int ID_READ_ONLY = 2;
    private static final int ID_SYNC = 3;
    private static final int ID_SYNC_REVIEW = 4;
    private static final int ID_SYNC_GAP = 5;
    private static final int ID_TITLE_HEADER = 6;
    private static final int ID_TITLE = 7;
    private static final int ID_TITLE_GAP = 8;
    private static final int ID_NOTES_HEADER = 9;
    private static final int ID_NOTES = 10;
    private static final int ID_NOTES_GAP = 11;
    private static final int ID_LIST = 12;
    private static final int ID_DEADLINE = 13;
    private static final int ID_REMOVE_DEADLINE = 14;
    private static final int ID_PRIORITY = 15;
    private static final int ID_FIELDS_GAP = 16;
    private static final int ID_ASSIGNEE = 17;
    private static final int ID_REMOVE_ASSIGNEE = 18;
    private static final int ID_ASSIGNEE_FOOTER = 19;
    private static final int ID_COMPLETE = 20;
    private static final int ID_COMPLETE_GAP = 21;
    private static final int ID_ACCESS_HEADER = 22;
    private static final int ID_ACCESS_YOU = 23;
    private static final int ID_ACCESS_RECIPIENT = 24;
    private static final int ID_ACCESS_RIGHTS = 25;
    private static final int ID_ACCESS_FOOTER = 26;
    private static final int ID_ACCESS_OWNER = 27;
    private static final int ID_SHARING_HEADER = 28;
    private static final int ID_REVOKE = 29;
    private static final int ID_SHARE = 30;
    private static final int ID_SHARING_FOOTER = 31;
    private static final int ID_DELETE = 32;
    private static final int ID_DELETE_GAP = 33;
    private static final int ID_INFO_TITLE = 34;
    private static final int ID_INFO_NOTES = 35;
    private static final int ID_INFO_LIST = 36;
    private static final int ID_INFO_DEADLINE = 37;
    private static final int ID_INFO_PRIORITY = 38;
    private static final int ID_INFO_GAP = 39;
    private static final int ID_LEAVE = 40;
    private static final int ID_LEAVE_GAP = 41;
    private static final int ID_GONE_DRAFT = 42;
    private static final int ID_REPAIR = 43;
    private static final int ID_ASSIGNEE_HEADER = 44;
    private static final int ID_ASSIGN_CHANGE = 45;
    private static final int ID_ASSIGN_SEND = 46;

    /** What the screen is for the user right now. */
    private enum Mode {
        /** Nothing to show yet, or the account's cloud tasks are closed. */
        UNAVAILABLE,
        OWNER,
        /** The task is gone (or no longer the user's to edit) and the unsaved edits are kept. */
        DRAFT_ONLY,
        /** A refused change being corrected. */
        REPAIR,
        /** A consent hold: the content and the reason, no action. */
        READ_ONLY,
        RECIPIENT,
        /** Shared, but without an accepted invitation. */
        NO_ACCESS,
        /** An automatic accept on its way: the invitation's preview, no access yet. */
        PREVIEW
    }

    private final String taskId;
    /** Repair: the refused outbox entry; null in the plain editor and once the refused entry was replaced. */
    private String repairMutationId;
    /** Opened by {@link #repair} (the title of the screen). */
    private boolean repairing;
    /** The refused entry is a create: its correction is a new task. */
    private boolean repairCreate;

    /** The owner's edits; null until the task (or the refused content) is known, and for a task shared with the user. */
    private ZgCloudDraft draft;
    /** Latched once the draft differed from the task, so edits typed back to the original do not close a draft whose task is gone. */
    private boolean everDirty;
    /** What the rows were last drawn with (the Share row depends on it). */
    private boolean shownDirty;
    private boolean closing;
    private boolean saving;
    /** A delete / a Leave of this task is on its way: the task going away is this screen's own doing. */
    private boolean deleting;
    private boolean leaving;
    private boolean goneNotice;
    /** The screen showed the preview of an automatic accept: if that ends without the task, it closes without a notice. */
    private boolean previewed;
    private boolean sheetOpen;
    /** The fields are being set from the task, not typed. */
    private boolean settingText;
    private TasksCloudRepository.HeldDelete heldDelete;
    private long heldDeadline;
    /** What to do once this screen is in front again (a dialog cannot open while a picker is closing). */
    private Runnable afterPicker;

    private EditTextCell titleCell;
    private EditTextCell notesCell;
    private SimpleActionBarMenuItem saveItem;

    public ZgCloudTaskEditorActivity(int account, String taskId) {
        super(account);
        this.taskId = taskId;
    }

    /**
     * The editor on a task create or edit the server refused as invalid (Unsent Changes, "Edit Task and Send Again"),
     * starting from that entry's own content. The repository has no single call for it, so Save is two:
     * <ul>
     *   <li>a refused edit: the entry is discarded, then the corrected content is sent as an edit of the task as the
     *       server holds it;</li>
     *   <li>a refused create: the corrected task is created (a new task, through the CREATE gate), then the refused
     *       entry is discarded.</li>
     * </ul>
     */
    static ZgCloudTaskEditorActivity repair(int account, String taskId, String mutationId) {
        final ZgCloudTaskEditorActivity editor = new ZgCloudTaskEditorActivity(account, taskId);
        editor.repairMutationId = mutationId;
        editor.repairing = true;
        return editor;
    }

    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.TASKS_TASK_EDITOR;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        if (closing) {
            // The first state already said that the task (or the refused change) is gone: the screen does not open,
            // and the one in front says why.
            onFragmentDestroy();
            return false;
        }
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        if (repairing) {
            return getString(R.string.ZG_cloudui_repair_title);
        }
        final Mode mode = mode();
        // A task another account assigned to this one (or is about to): named as that.
        return getString(mode == Mode.RECIPIENT || mode == Mode.NO_ACCESS || mode == Mode.PREVIEW
            ? R.string.ZG_tasks_assigned_editorTitle : R.string.ZG_cloudui_editor_title);
    }

    // -----------------------------------------------------------------------------------------------------------
    // View
    // -----------------------------------------------------------------------------------------------------------

    @Override
    public View createView(Context context) {
        titleCell = new EditTextCell(context, getString(R.string.ZG_tasks_newTask_placeholder), true, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                if (draft != null && !settingText) {
                    draft.title = newText.toString();
                    afterEdit(false);
                }
            }
        };
        final InputFilter[] existing = titleCell.editText.getFilters();
        final InputFilter[] filters = new InputFilter[existing.length + 1];
        System.arraycopy(existing, 0, filters, 0, existing.length);
        filters[existing.length] = (source, start, end, dest, dstart, dend) -> {
            if (settingText) {
                // A title another app stored may be longer than this one lets type: it is shown whole.
                return null;
            }
            final String updated = TextUtils.concat(dest.subSequence(0, dstart), source.subSequence(start, end),
                dest.subSequence(dend, dest.length())).toString();
            return ZgTasksTitlePolicy.acceptsDraftEdit(dest, updated) ? null : dest.subSequence(dstart, dend);
        };
        titleCell.editText.setFilters(filters);

        // Notes are not limited while typing (the limit counts characters and bytes): Save says when they are too long.
        notesCell = new EditTextCell(context, getString(R.string.ZG_cloudui_editor_notes), true, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                if (draft != null && !settingText) {
                    draft.notes = newText.toString();
                    afterEdit(false);
                }
            }
        };
        notesCell.editText.setMaxLines(8);
        bindFields(true, true);

        final View view = super.createView(context);
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
        saveItem = simpleActionBar.createMenu().addItemWithWidth(MENU_SAVE, R.drawable.ic_ab_done, dp(56), getString(R.string.Save));
        updateSave();
        return view;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (listView != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (listView != null) {
                    listView.adapter.notifyDataSetChanged();
                }
            });
        }
    }

    /** Puts the draft's text into the fields (the draft is what Save reads; the fields only show it). */
    private void bindFields(boolean title, boolean notes) {
        if (draft == null || titleCell == null) {
            return;
        }
        settingText = true;
        if (title && !TextUtils.equals(titleCell.getText(), draft.title)) {
            titleCell.setText(draft.title);
        }
        if (notes && !TextUtils.equals(notesCell.getText(), draft.notes)) {
            notesCell.setText(draft.notes);
        }
        settingText = false;
    }

    private void hideKeyboard() {
        if (titleCell != null) {
            AndroidUtilities.hideKeyboard(titleCell.editText);
            AndroidUtilities.hideKeyboard(notesCell.editText);
        }
    }

    /** The draft changed by the user's hand. {@code rows}: something the rows show changed (not only typed text). */
    private void afterEdit(boolean rows) {
        final boolean dirty = draft != null && draft.dirty();
        if (dirty) {
            everDirty = true;
        }
        updateSave();
        if (rows || dirty != shownDirty) {
            render();
        }
    }

    private void updateSave() {
        if (saveItem == null) {
            return;
        }
        final Mode mode = mode();
        final boolean editable = mode == Mode.OWNER || mode == Mode.DRAFT_ONLY || mode == Mode.REPAIR;
        saveItem.setVisibility(editable ? View.VISIBLE : View.GONE);
        final boolean enabled = editable && !saving && draft != null && ZgTasksTitlePolicy.containsVisible(ZgTasksTitlePolicy.trim(draft.title));
        saveItem.setEnabled(enabled);
        saveItem.setAlpha(enabled ? 1f : 0.5f);
    }

    // -----------------------------------------------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------------------------------------------

    private TasksCloudState.TaskView task() {
        final TasksCloudState value = state;
        return value != null ? value.task(taskId) : null;
    }

    /** The state says what the account holds (not a placeholder while it loads or rebinds, not a closed account). */
    private boolean known(TasksCloudState value) {
        return value != null && !contentHidden() && !(value.status == TasksCloudState.Status.LOADING && value.contentFree());
    }

    private Mode mode() {
        if (repairMutationId != null) {
            return draft != null ? Mode.REPAIR : Mode.UNAVAILABLE;
        }
        final TasksCloudState.TaskView task = task();
        final boolean edited = draft != null && everDirty;
        if (!edited && task != null && readOnly() != null) {
            return Mode.READ_ONLY;
        }
        if (task != null && task.owner && draft != null) {
            return Mode.OWNER;
        }
        if (edited) {
            return Mode.DRAFT_ONLY;
        }
        if (task != null && !task.owner) {
            return ZgCloudSharing.role(false, task.assignment) == ZgCloudSharing.Role.ACCEPTED_RECIPIENT ? Mode.RECIPIENT : Mode.NO_ACCESS;
        }
        return task == null && preview() != null ? Mode.PREVIEW : Mode.UNAVAILABLE;
    }

    /** The invitation whose automatic accept is on its way and whose task this screen was opened for, or null. */
    private TasksWire.InvitationItem preview() {
        final TasksCloudState value = state;
        if (value != null) {
            for (TasksWire.InvitationItem invitation : value.provisional) {
                if (invitation.taskId.equals(taskId)) {
                    return invitation;
                }
            }
        }
        return null;
    }

    @Override
    protected void onStateChanged() {
        if (closing) {
            return;
        }
        final TasksCloudState value = state;
        if (repairMutationId != null) {
            if (draft == null) {
                if (!startRepair(value)) {
                    return;
                }
            } else if (!repairCreate) {
                followRefused(value);
            }
        } else {
            final TasksCloudState.TaskView task = task();
            if (task != null) {
                // The task itself is here: from now on its going away is news again.
                previewed = false;
                if (task.owner) {
                    if (draft == null) {
                        draft = new ZgCloudDraft(task.content);
                        bindFields(true, true);
                    } else {
                        // Untouched fields follow the newer version; what the user changed stays.
                        final EnumSet<ZgCloudDraft.Field> moved = draft.rebase(task.content);
                        bindFields(moved.contains(ZgCloudDraft.Field.TITLE), moved.contains(ZgCloudDraft.Field.NOTES));
                    }
                }
                loadPeople(value, task);
            } else if (deleting || leaving) {
                // This screen's own delete or Leave took the task away: its answer closes the screen.
                return;
            } else if (preview() != null) {
                // The device is accepting this task: its preview stays until the task itself arrives in its place.
                previewed = true;
                loadNames(Collections.singletonList(preview().ownerTgId));
            } else if (previewed && known(value)) {
                // The accept did not go through: the invitation waits in Invitations for the user's answer.
                close();
                return;
            } else if (known(value) && (draft == null || !(everDirty || draft.dirty()))) {
                // Deleted, or no longer shared with the user, and nothing unsaved to keep.
                closeGone();
                return;
            }
        }
        updateSave();
        applyTitle();
        super.onStateChanged();
    }

    /** The title follows what the screen is for the user (an own task, or one assigned to them). */
    private void applyTitle() {
        final CharSequence title = getTitle();
        if (simpleActionBar != null) {
            simpleActionBar.setTitle(title);
        } else if (actionBar != null) {
            actionBar.setTitle(title);
        }
    }

    /** Reads the refused entry's content into the draft. False: the entry is not there to be corrected (the screen closes). */
    private boolean startRepair(TasksCloudState value) {
        if (!known(value)) {
            return true;
        }
        final TasksCloudState.Pending entry = entry(value, repairMutationId);
        final TasksWire.Content refused = entry != null && entry.state == TasksCloudState.PendingState.INVALID
            ? taskContent(value, entry) : null;
        if (refused == null) {
            closeGone();
            return false;
        }
        repairCreate = entry.kind == TasksWire.OperationKind.TASK_CREATE;
        draft = new ZgCloudDraft(refused);
        bindFields(true, true);
        return true;
    }

    /**
     * A refused edit is still part of its task as the repository shows it: what the user has not corrected follows that
     * task, so the correction never writes back a value that changed elsewhere meanwhile.
     */
    private void followRefused(TasksCloudState value) {
        final TasksCloudState.Pending entry = entry(value, repairMutationId);
        if (entry == null || entry.state != TasksCloudState.PendingState.INVALID) {
            // Being replaced by Save, or no longer the refused change: Save decides.
            return;
        }
        final TasksWire.Content refused = taskContent(value, entry);
        if (refused != null) {
            final EnumSet<ZgCloudDraft.Field> moved = draft.rebase(refused);
            bindFields(moved.contains(ZgCloudDraft.Field.TITLE), moved.contains(ZgCloudDraft.Field.NOTES));
        }
    }

    private static TasksCloudState.Pending entry(TasksCloudState value, String mutationId) {
        if (value != null && mutationId != null) {
            for (TasksCloudState.Pending pending : value.pending) {
                if (mutationId.equals(pending.mutationId)) {
                    return pending;
                }
            }
        }
        return null;
    }

    private void loadPeople(TasksCloudState value, TasksCloudState.TaskView task) {
        final ArrayList<String> people = new ArrayList<>();
        if (task.owner) {
            final String assignee = task.assigneeTgId();
            if (assignee != null) {
                people.add(assignee);
            }
        } else if (task.server != null) {
            people.add(task.server.ownerTgId);
        }
        loadNames(people);
    }

    private static long now() {
        return ZenlyPro.trustedNowSeconds();
    }

    /** {saved on this device but not confirmed, refused or stuck}: the state of the task's own unsent changes. */
    private boolean[] unsent(TasksCloudState value, TasksCloudState.TaskView task) {
        boolean attention = false;
        for (TasksCloudState.Pending pending : value.pending) {
            if (taskId.equals(pending.resourceId) && ZgCloudIssues.needsAttention(pending.state)) {
                attention = true;
                break;
            }
        }
        return new boolean[]{task.pending != null || attention, attention};
    }

    // -----------------------------------------------------------------------------------------------------------
    // Rows
    // -----------------------------------------------------------------------------------------------------------

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (titleCell == null) {
            return;
        }
        shownDirty = draft != null && draft.dirty();
        final TasksCloudState value = state;
        final TasksCloudState.TaskView task = task();
        switch (mode()) {
            case OWNER:
                fillSyncStatus(items, value, task);
                fillContent(items, value);
                fillOwner(items, value, task);
                break;
            case DRAFT_ONLY:
                items.add(UItem.asShadow(ID_GONE_DRAFT, getString(R.string.ZG_cloudui_editor_goneDraftFooter)));
                fillContent(items, value);
                break;
            case REPAIR:
                items.add(UItem.asShadow(ID_REPAIR, getString(R.string.ZG_cloudui_repair_explanation)));
                fillContent(items, value);
                break;
            case READ_ONLY:
                fillReadOnly(items, value, task);
                break;
            case RECIPIENT:
                fillRecipient(items, value, task);
                break;
            case NO_ACCESS:
                items.add(UItem.asShadow(ID_STATUS, getString(R.string.ZG_cloudui_access_recipientInactive)));
                break;
            case PREVIEW:
                fillPreview(items, preview());
                break;
            default: {
                final int unavailable = unavailableText();
                items.add(UItem.asShadow(ID_STATUS, getString(value == null || contentHidden()
                    ? (unavailable != 0 ? unavailable : R.string.ZenlyCloudTasksStatusClosed) : R.string.ZenlyCloudTasksStatusLoading)));
                break;
            }
        }
    }

    /** "Waiting for sync confirmation" or "Not sent — needs your attention", with the way to Unsent Changes. */
    private void fillSyncStatus(ArrayList<UItem> items, TasksCloudState value, TasksCloudState.TaskView task) {
        final boolean[] unsent = unsent(value, task);
        if (!unsent[0]) {
            return;
        }
        items.add(ZgCloudInfoCell.Factory.of(ID_SYNC, null,
            getString(unsent[1] ? R.string.ZG_cloudui_needsAttention : R.string.ZG_cloudui_syncConfirmationPending)));
        items.add(action(ID_SYNC_REVIEW, R.drawable.msg_info, getString(R.string.ZG_cloudui_review), false, null));
        items.add(UItem.asShadow(ID_SYNC_GAP, null));
    }

    /** The owner's editable content, shared by the editor, the repair and the kept draft of a task that is gone. */
    private void fillContent(ArrayList<UItem> items, TasksCloudState value) {
        final String none = getString(R.string.ZG_tasks_metadata_none);
        items.add(UItem.asHeader(ID_TITLE_HEADER, getString(R.string.ZG_tasks_newTask_placeholder)));
        items.add(UItem.asCustom(ID_TITLE, titleCell));
        items.add(UItem.asShadow(ID_TITLE_GAP, null));
        items.add(UItem.asHeader(ID_NOTES_HEADER, getString(R.string.ZG_cloudui_editor_notes)));
        items.add(UItem.asCustom(ID_NOTES, notesCell));
        items.add(UItem.asShadow(ID_NOTES_GAP, null));

        items.add(UItem.asButton(ID_LIST, R.drawable.msg_list, getString(R.string.ZenlyCloudTasksFieldList), listTitle(value, draft.listId)));
        items.add(UItem.asButton(ID_DEADLINE, R.drawable.msg_calendar2, getString(R.string.ZG_tasks_deadline_title),
            draft.dueAt != null ? ZgCloudText.moment(draft.dueAt) : none));
        if (draft.dueAt != null) {
            items.add(UItem.asButton(ID_REMOVE_DEADLINE, R.drawable.msg_delete, getString(R.string.ZG_tasks_deadline_remove)).red());
        }
        items.add(UItem.asButton(ID_PRIORITY, R.drawable.msg_stats, getString(R.string.ZenlyCloudTasksFieldPriority),
            getString(ZgCloudText.priorityRes(draft.priority))));
        items.add(UItem.asShadow(ID_FIELDS_GAP, null));
    }

    /** Completion, the assignee and delete: the owner's actions on the task itself. */
    private void fillOwner(ArrayList<UItem> items, TasksCloudState value, TasksCloudState.TaskView task) {
        items.add(completionRow(task));
        items.add(UItem.asShadow(ID_COMPLETE_GAP, null));

        fillAssignee(items, task);

        items.add(action(ID_DELETE, R.drawable.msg_delete, getString(R.string.ZG_cloudui_editor_deleteTask), true, null));
        items.add(UItem.asShadow(ID_DELETE_GAP, null));
    }

    /**
     * The assignee section: one row "Name · state" (it opens the picker whenever a pick is possible), the actions of
     * that state and its footer. What the row offers is decided by TasksAssigneeDisplay alone; the taps check again.
     */
    private void fillAssignee(ArrayList<UItem> items, TasksCloudState.TaskView task) {
        final TasksAssigneeDisplay.State shown = task.assigneeState(now());
        final String assigneeId = task.assigneeTgId();
        final String name = assigneeId != null ? assigneeName(task, assigneeId) : null;
        final String value;
        if (name == null) {
            value = getString(R.string.ZG_tasks_metadata_none);
        } else if (shown == null) {
            value = name;
        } else {
            value = formatString(R.string.ZG_tasks_assign_nameState, name, ZgTasksUi.assigneeStateText(shown));
        }
        items.add(UItem.asHeader(ID_ASSIGNEE_HEADER, getString(R.string.ZG_tasks_assignee_title)));
        items.add(UItem.asButton(ID_ASSIGNEE, R.drawable.msg_contacts, getString(R.string.ZG_tasks_assignee_title), value)
            .setEnabled(!task.completed && (shown == null || shown.rowPicks())));
        if (shown != null && !task.completed) {
            if (shown.someoneLosesAccess()) {
                items.add(action(ID_ASSIGN_CHANGE, R.drawable.msg_replace, getString(R.string.ZG_tasks_assign_change), false, null));
            } else if (shown.offersSendAction()) {
                items.add(action(ID_ASSIGN_SEND, R.drawable.msg_send, getString(shown == TasksAssigneeDisplay.State.NOT_SENT
                    ? R.string.ZG_tasks_assign_send : R.string.ZG_tasks_assign_resend), false, null));
            }
        }
        if (shown != null && (shown.someoneLosesAccess() || shown == TasksAssigneeDisplay.State.NOT_SENT)) {
            items.add(action(ID_REMOVE_ASSIGNEE, R.drawable.msg_user_remove, getString(R.string.ZG_tasks_assignee_remove), true, null));
        }
        items.add(UItem.asShadow(ID_ASSIGNEE_FOOTER, assigneeFooter(task, shown, name)));
    }

    /** Whom the assignee row names: the name this account knows them by, else an older note's, else the number. */
    private String assigneeName(TasksCloudState.TaskView task, String assigneeId) {
        final TasksWire.Annotation note = task.content.annotation;
        return ZgCloudText.isolated(ZgTasksUi.assigneeName(currentAccount, assigneeId,
            note != null && note.telegramUserId.equals(assigneeId) ? note.displayName : null));
    }

    private String assigneeFooter(TasksCloudState.TaskView task, TasksAssigneeDisplay.State shown, String name) {
        if (shown == null) {
            return task.completed ? getString(R.string.ZG_tasks_assign_footer_completedTask) : null;
        }
        switch (shown.footer()) {
            case QUEUED:
                return getString(R.string.ZG_tasks_assign_footer_queued);
            case PENDING:
                return formatString(R.string.ZG_tasks_assign_footer_pending, name);
            case ACCEPTED:
                return formatString(R.string.ZG_tasks_assign_footer_accepted, name);
            case ENDED:
                return getString(task.completed ? R.string.ZG_tasks_assign_footer_completedTask : R.string.ZG_tasks_assign_footer_ended);
            case NOT_SENT:
                return formatString(R.string.ZG_tasks_assign_footer_notSent, name);
            default:
                return null;
        }
    }

    /** An automatic accept on its way: who assigned the task, its title and deadline, and that it is being confirmed. */
    private void fillPreview(ArrayList<UItem> items, TasksWire.InvitationItem invitation) {
        if (invitation == null) {
            return;
        }
        items.add(UItem.asHeader(ID_ACCESS_HEADER, formatString(R.string.ZG_tasks_assigned_by, person(invitation.ownerTgId))));
        // Another user's text: isolated, so it cannot reorder the line around it.
        items.add(ZgCloudInfoCell.Factory.of(ID_INFO_TITLE, getString(R.string.ZG_tasks_newTask_placeholder), ZgCloudText.isolated(invitation.title)));
        items.add(ZgCloudInfoCell.Factory.of(ID_INFO_DEADLINE, getString(R.string.ZG_tasks_deadline_title),
            invitation.dueAt != null ? ZgCloudText.moment(invitation.dueAt) : getString(R.string.ZG_tasks_metadata_none)));
        items.add(UItem.asShadow(ID_INFO_GAP, getString(R.string.ZG_cloudui_syncConfirmationPending)));
    }

    /** The row names the state it leads to, and carries it: a change arriving meanwhile is never flipped back. */
    private UItem completionRow(TasksCloudState.TaskView task) {
        final UItem row = action(ID_COMPLETE, R.drawable.msg_select,
            getString(task.completed ? R.string.ZG_cloudui_editor_reopen : R.string.ZG_cloudui_editor_complete), false, null);
        row.intValue = task.completed ? 0 : 1;
        return row;
    }

    /** The task's content while a consent hold makes Cloud read-only: nothing to change, no action. */
    private void fillReadOnly(ArrayList<UItem> items, TasksCloudState value, TasksCloudState.TaskView task) {
        final ZgCloudReadOnly reason = readOnly();
        if (reason != null) {
            items.add(UItem.asShadow(ID_READ_ONLY, getString(ZgCloudText.readOnlyRes(reason))));
        }
        fillInfo(items, value, task, task.owner);
        items.add(UItem.asShadow(ID_INFO_GAP, null));
    }

    /** Title, notes and deadline as text; the list and the priority only for the owner (they are never shared). */
    private void fillInfo(ArrayList<UItem> items, TasksCloudState value, TasksCloudState.TaskView task, boolean owner) {
        final TasksWire.Content content = task.content;
        // Another user's text on a shared task: isolated, so it cannot reorder the line around it.
        items.add(ZgCloudInfoCell.Factory.of(ID_INFO_TITLE, getString(R.string.ZG_tasks_newTask_placeholder), ZgCloudText.isolated(content.title)));
        if (!TextUtils.isEmpty(content.description)) {
            // Its own row, so no isolation is needed (and isolates would not span its paragraphs anyway).
            items.add(ZgCloudInfoCell.Factory.of(ID_INFO_NOTES, getString(R.string.ZG_cloudui_editor_notes), content.description));
        }
        if (owner) {
            items.add(ZgCloudInfoCell.Factory.of(ID_INFO_LIST, getString(R.string.ZenlyCloudTasksFieldList), listTitle(value, content.listId)));
        }
        items.add(ZgCloudInfoCell.Factory.of(ID_INFO_DEADLINE, getString(R.string.ZG_tasks_deadline_title),
            content.dueAt != null ? ZgCloudText.moment(content.dueAt) : getString(R.string.ZG_tasks_metadata_none)));
        if (owner) {
            items.add(ZgCloudInfoCell.Factory.of(ID_INFO_PRIORITY, getString(R.string.ZenlyCloudTasksFieldPriority),
                getString(ZgCloudText.priorityRes(content.priority))));
        }
    }

    /** A task assigned to the user: who assigned it, the content, what the user may do, then the two actions. */
    private void fillRecipient(ArrayList<UItem> items, TasksCloudState value, TasksCloudState.TaskView task) {
        items.add(UItem.asHeader(ID_ACCESS_HEADER, formatString(R.string.ZG_tasks_assigned_by,
            task.server != null ? person(task.server.ownerTgId) : getString(R.string.ZenlyCloudTasksHiddenPerson))));
        fillInfo(items, value, task, false);
        final boolean[] unsent = unsent(value, task);
        final String rights = getString(R.string.ZG_tasks_assigned_rights);
        items.add(UItem.asShadow(ID_INFO_GAP, !unsent[0] ? rights
            : getString(unsent[1] ? R.string.ZG_cloudui_needsAttention : R.string.ZG_cloudui_syncConfirmationPending) + "\n\n" + rights));

        items.add(completionRow(task));
        items.add(UItem.asShadow(ID_COMPLETE_GAP, null));
        items.add(action(ID_LEAVE, R.drawable.msg_leave, getString(R.string.ZG_cloudui_editor_leave), true, null));
        items.add(UItem.asShadow(ID_LEAVE_GAP, null));
    }

    /** The Cloud Inbox for no list; a list that is not visible here keeps a neutral name (and stays the task's list). */
    private String listTitle(TasksCloudState value, String listId) {
        if (listId == null) {
            return getString(R.string.ZenlyCloudTasksInbox);
        }
        final TasksCloudState.ListView list = value != null ? value.list(listId) : null;
        return list != null ? ZgCloudText.isolated(list.title) : getString(R.string.ZenlyCloudTasksFieldList);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Taps
    // -----------------------------------------------------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (closing) {
            return;
        }
        switch (item.id) {
            case ID_SYNC_REVIEW:
                hideKeyboard();
                presentFragment(new ZgTasksUnsentActivity(currentAccount));
                break;
            case ID_LIST:
                pickList(view);
                break;
            case ID_DEADLINE:
                pickDeadline();
                break;
            case ID_REMOVE_DEADLINE:
                if (draft != null) {
                    draft.dueAt = null;
                    afterEdit(true);
                }
                break;
            case ID_PRIORITY:
                pickPriority(view);
                break;
            case ID_ASSIGNEE:
            case ID_ASSIGN_CHANGE:
                pickAssignee();
                break;
            case ID_ASSIGN_SEND:
                sendAssignee();
                break;
            case ID_REMOVE_ASSIGNEE:
                removeAssignee();
                break;
            case ID_COMPLETE:
                setCompleted(item.intValue == 1);
                break;
            case ID_DELETE:
                delete();
                break;
            case ID_LEAVE:
                leave();
                break;
            default:
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void notice(int text) {
        showNotice(getString(text));
    }

    /** The current choice is marked with a check, so it is visible and spoken. */
    private void pickList(View anchor) {
        final TasksCloudState value = state;
        if (draft == null || value == null) {
            return;
        }
        hideKeyboard();
        final ItemOptions options = ItemOptions.makeOptions(this, anchor);
        options.addChecked(draft.listId == null, getString(R.string.ZenlyCloudTasksInbox), () -> setList(null));
        boolean found = draft.listId == null;
        for (TasksCloudState.ListView list : value.lists) {
            final String id = list.id;
            final boolean chosen = id.equals(draft.listId);
            found |= chosen;
            options.addChecked(chosen, ZgCloudText.isolated(list.title), () -> setList(id));
        }
        if (!found) {
            // The task's list is not visible here (yet): it stays chosen, the task is never moved silently.
            options.addChecked(true, getString(R.string.ZenlyCloudTasksFieldList), () -> { });
        }
        options.show();
    }

    private void setList(String listId) {
        if (draft != null && !Objects.equals(draft.listId, listId)) {
            draft.listId = listId;
            afterEdit(true);
        }
    }

    private void pickPriority(View anchor) {
        if (draft == null) {
            return;
        }
        hideKeyboard();
        final ItemOptions options = ItemOptions.makeOptions(this, anchor);
        for (int value = ZgCloudDraft.PRIORITY_MIN; value <= ZgCloudDraft.PRIORITY_MAX; value++) {
            final int priority = value;
            options.addChecked(draft.priority == priority, getString(ZgCloudText.priorityRes(priority)), () -> {
                if (draft != null && draft.priority != priority) {
                    draft.priority = priority;
                    afterEdit(true);
                }
            });
        }
        options.show();
    }

    private void pickDeadline() {
        final Activity activity = getParentActivity();
        if (sheetOpen || activity == null || draft == null) {
            return;
        }
        hideKeyboard();
        sheetOpen = true;
        final ZgTasksDeadlineSheet sheet = new ZgTasksDeadlineSheet(activity, resourceProvider, draft.dueAt, new ZgTasksDeadlineSheet.Callback() {
            @Override
            public void onSet(long value) {
                if (draft != null) {
                    draft.dueAt = value;
                    afterEdit(true);
                }
            }

            @Override
            public void onClear() {
                if (draft != null) {
                    draft.dueAt = null;
                    afterEdit(true);
                }
            }
        });
        if (showDialog(sheet, d -> sheetOpen = false) == null) {
            sheetOpen = false;
        }
    }

    /** The target is the state the row named. A task that reached it meanwhile (another device, a fast second tap) is left alone. */
    private void setCompleted(boolean completed) {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = task();
        if (task == null) {
            notice(R.string.ZG_cloudui_error_gone);
            return;
        }
        if (ZgCloudSharing.role(task.owner, task.assignment) == ZgCloudSharing.Role.NO_ACCESS) {
            notice(R.string.ZG_cloudui_error_notAssignee);
            return;
        }
        if (task.completed == completed) {
            // Already so: the repository would queue the same change a second time.
            render();
            return;
        }
        repository.setCompleted(taskId, completed, result(null));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Sharing
    // -----------------------------------------------------------------------------------------------------------

    /** The current task if the user owns it, else null after saying why. */
    private TasksCloudState.TaskView ownedTask() {
        final TasksCloudState.TaskView task = task();
        if (task == null) {
            notice(R.string.ZG_cloudui_error_gone);
            return null;
        }
        if (!task.owner) {
            notice(R.string.ZG_cloudui_error_notOwner);
            return null;
        }
        return task;
    }

    /**
     * The assignee row and "Change Assignee": the contact picker. The pick is checked at once (the account itself, the
     * assignment limits) and then IS the invitation: nothing waits for Save.
     */
    private void pickAssignee() {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = ownedTask();
        if (task == null) {
            return;
        }
        if (task.completed) {
            notice(R.string.ZG_tasks_assign_footer_completedTask);
            return;
        }
        final TasksAssigneeDisplay.State shown = task.assigneeState(now());
        if (shown != null && !shown.rowPicks()) {
            return; // a request is on its way
        }
        hideKeyboard();
        // The account's first pick: "How assigning works", then the picker.
        ZgTasksUi.explainAssigningOnce(this, currentAccount, () -> {
            if (destroyed || closing) {
                return;
            }
            ZgTasksAssigneePicker.present(this, picked -> {
                if (!destroyed && picked != null) {
                    final String recipient = String.valueOf(picked.peerId);
                    afterPicker = () -> checkPicked(recipient);
                }
            });
        });
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        final Runnable pending = afterPicker;
        afterPicker = null;
        if (pending != null && !destroyed && !closing) {
            pending.run();
        }
    }

    /** The pick-time check, then the assignment (after its confirmation, if someone loses the task). */
    private void checkPicked(final String recipientTgId) {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        repository.checkAssignment(recipientTgId, result(ok -> confirmAssign(recipientTgId)));
    }

    /**
     * Replacing a live assignment asks first and names who will no longer see the task (the only assign confirmation);
     * a request on its way, a deferral, an ended epoch and an older note ask nothing.
     */
    private void confirmAssign(final String recipientTgId) {
        if (destroyed || closing || repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = ownedTask();
        if (task == null) {
            return;
        }
        final TasksAssigneeDisplay.State shown = task.assigneeState(now());
        final String current = task.assigneeTgId();
        if (shown == null || !shown.someoneLosesAccess() || current == null) {
            assign(recipientTgId, null);
            return;
        }
        if (current.equals(recipientTgId)) {
            return; // the same person already has it
        }
        final String live = task.liveInvitationId;
        confirm(getString(R.string.ZG_tasks_assign_replace_title),
            formatString(R.string.ZG_tasks_assign_replace_text, assigneeName(task, current)),
            getString(R.string.ZG_tasks_assign_replace_confirm), false, null, () -> assign(recipientTgId, live));
    }

    /** {@code expectedLive}: the epoch the confirmation named; null when nobody loses the task. */
    private void assign(String recipientTgId, String expectedLive) {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = ownedTask();
        if (task == null) {
            return;
        }
        if (expectedLive != null && !expectedLive.equals(task.liveInvitationId)) {
            notice(R.string.ZG_cloudui_error_changedElsewhere);
            return;
        }
        repository.assign(taskId, recipientTgId, result(null));
    }

    /** Send Again (an ended assignment, to the same person) or Send (an older note that was never sent). */
    private void sendAssignee() {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = ownedTask();
        if (task == null) {
            return;
        }
        if (task.completed) {
            notice(R.string.ZG_tasks_assign_footer_completedTask);
            return;
        }
        final TasksAssigneeDisplay.State shown = task.assigneeState(now());
        if (shown == null || !shown.offersSendAction()) {
            notice(R.string.ZG_cloudui_error_changedElsewhere);
            return;
        }
        hideKeyboard();
        ZgTasksUi.explainAssigningOnce(this, currentAccount, () -> {
            if (destroyed || closing || repository == null || refuseReadOnly()) {
                return;
            }
            final TasksCloudState.TaskView current = ownedTask();
            if (current == null) {
                return;
            }
            final TasksAssigneeDisplay.State latest = current.assigneeState(now());
            if (latest != shown) {
                notice(R.string.ZG_cloudui_error_changedElsewhere);
            } else if (shown == TasksAssigneeDisplay.State.NOT_SENT) {
                if (current.content.annotation != null) {
                    repository.assign(taskId, current.content.annotation.telegramUserId, result(null));
                }
            } else {
                repository.resendAssignment(taskId, result(null));
            }
        });
    }

    /**
     * Remove Assignee: a live assignment is ended after a confirmation that names who loses the task, exactly the
     * epoch the confirmation showed; an older note that was never sent is just cleared.
     */
    private void removeAssignee() {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = ownedTask();
        if (task == null) {
            return;
        }
        final TasksAssigneeDisplay.State shown = task.assigneeState(now());
        if (shown == TasksAssigneeDisplay.State.NOT_SENT) {
            repository.removeLegacyAnnotation(taskId, result(null));
            return;
        }
        final String current = task.assigneeTgId();
        if (shown == null || !shown.someoneLosesAccess() || current == null) {
            notice(R.string.ZG_cloudui_error_changedElsewhere);
            return;
        }
        if (task.assignment == null || !task.assignment.invitationId.equals(task.liveInvitationId)) {
            // The assignment this device just sent has not come back from the server yet.
            notice(R.string.ZenlyCloudTasksErrorBusy);
            return;
        }
        final String epoch = task.assignment.invitationId;
        confirm(getString(R.string.ZG_tasks_assign_remove_title),
            formatString(R.string.ZG_tasks_assign_replace_text, assigneeName(task, current)),
            getString(R.string.ZG_tasks_assign_remove_confirm), true, null, () -> {
                if (repository != null && !refuseReadOnly()) {
                    repository.revoke(taskId, epoch, result(null));
                }
            });
    }

    /** A confirmed Leave, bound to the accepted invitation the row was drawn for: no hold and no Undo. */
    private void leave() {
        if (repository == null || refuseReadOnly()) {
            return;
        }
        final TasksCloudState.TaskView task = task();
        if (task == null) {
            notice(R.string.ZG_cloudui_error_gone);
            return;
        }
        if (task.owner || ZgCloudSharing.role(false, task.assignment) != ZgCloudSharing.Role.ACCEPTED_RECIPIENT) {
            notice(R.string.ZG_cloudui_error_notAssignee);
            return;
        }
        final String invitationId = task.assignment.invitationId;
        confirm(getString(R.string.ZG_cloudui_leave_title), getString(R.string.ZG_cloudui_leave_text),
            getString(R.string.ZG_cloudui_editor_leave), true, null, () -> {
                final TasksCloudState.TaskView current = task();
                if (current == null || current.owner || current.assignment == null
                    || ZgCloudSharing.role(false, current.assignment) != ZgCloudSharing.Role.ACCEPTED_RECIPIENT
                    || !invitationId.equals(current.assignment.invitationId)) {
                    notice(R.string.ZG_cloudui_error_changedElsewhere);
                    return;
                }
                if (repository == null || refuseReadOnly()) {
                    return;
                }
                // The task leaves the state before the answer arrives: that is this screen's doing, not a surprise.
                leaving = true;
                repository.respond(invitationId, TasksWire.Decision.LEAVE, result(done -> close(), error -> {
                    leaving = false;
                    showError(error);
                    onStateChanged();
                }));
            });
    }

    /**
     * The editor closes and the delete is held; the Undo appears on the screen under it. Unsaved edits go with the task
     * (an Undo brings back the task as it was).
     */
    private void delete() {
        if (repository == null || deleting || refuseReadOnly() || ownedTask() == null) {
            return;
        }
        hideKeyboard();
        deleting = true;
        final TasksCloudRepository opened = repository;
        // Not fenced to the screen like the other results: a held delete must get its Undo (or be released) even if the
        // editor was closed before the repository answered.
        opened.holdDelete(taskId, (held, error) -> {
            if (held == null || error != null) {
                if (!destroyed && opened == repository) {
                    deleting = false;
                    showError(error);
                    onStateChanged();
                }
                return;
            }
            final long deadline = ZgCloudHeldDelete.deadline();
            if (destroyed) {
                ZgCloudHeldDelete.offerUndo(held, deadline);
                return;
            }
            heldDelete = held;
            heldDeadline = deadline;
            close();
        });
    }

    // -----------------------------------------------------------------------------------------------------------
    // Save
    // -----------------------------------------------------------------------------------------------------------

    private void save() {
        if (closing || saving || draft == null) {
            return;
        }
        final Mode mode = mode();
        if (mode != Mode.OWNER && mode != Mode.DRAFT_ONLY && mode != Mode.REPAIR) {
            return;
        }
        if (repository == null) {
            notice(R.string.ZenlyCloudTasksStatusClosed);
            return;
        }
        // Nothing is queued while a consent hold makes Cloud read-only; the edits stay on screen.
        if (refuseReadOnly()) {
            return;
        }
        if (!draft.dirty()) {
            if (mode == Mode.REPAIR) {
                // Sending the same refused content again would only be refused again.
                notice(R.string.ZG_cloudui_repair_unchanged);
            } else {
                close();
            }
            return;
        }
        final ZgCloudDraft.Problem problem = draft.problem();
        if (problem != null) {
            hideKeyboard();
            ZgTasks.showError(this, problem == ZgCloudDraft.Problem.TITLE_TOO_LONG
                ? formatString(R.string.ZG_tasks_error_titleTooLong, ZgTasksTitlePolicy.TASK_LIMIT)
                : getString(problem == ZgCloudDraft.Problem.NOTES_TOO_LONG ? R.string.ZG_cloudui_error_notes : R.string.ZG_cloudui_error_title));
            return;
        }
        if (mode == Mode.REPAIR) {
            saveRepair();
            return;
        }
        final TasksCloudState.TaskView task = task();
        if (task == null || !task.owner) {
            // Deleted, or no longer the user's to edit: the edits are kept only by an explicit choice.
            askGone();
            return;
        }
        if (!draft.conflicts(task.content).isEmpty()) {
            // A field the user changed was also changed elsewhere: the user decides, nothing is merged.
            askConflict();
            return;
        }
        commit();
    }

    private void commit() {
        final TasksCloudState value = state;
        final TasksCloudState.TaskView task = task();
        if (value == null || task == null || !task.owner || repository == null || draft == null || refuseReadOnly()) {
            return;
        }
        if (draft.listId != null && value.list(draft.listId) == null && draft.touched(ZgCloudDraft.Field.LIST)) {
            // The list the user chose was deleted meanwhile: the task stays in its list, and the user looks again.
            draft.listId = task.content.listId;
            afterEdit(true);
            notice(R.string.ZG_cloudui_error_changedElsewhere);
            return;
        }
        final TasksWire.Content content;
        try {
            content = draft.merged(task.content, endOfList(value, draft.listId));
        } catch (IllegalArgumentException e) {
            invalid(e);
            return;
        }
        saving = true;
        updateSave();
        // The repository sends exactly the fields that differ from the task as it shows it; nothing changed: no entry.
        repository.updateTask(taskId, content, result(sent -> close(), this::saveFailed));
    }

    private void saveFailed(TasksCloudError error) {
        saving = false;
        updateSave();
        // The rows follow what the screen is now (a repair whose refused entry is already discarded is a plain editor).
        render();
        if (error != null && error.code == TasksCloudError.Code.INVALID) {
            hideKeyboard();
            ZgTasks.showError(this, getString(ZgCloudText.fieldErrorRes(error.field)));
        } else {
            showError(error);
        }
    }

    /** A value the wire cannot carry at all (not a length rule): said on the field's name, the text stays as it is. */
    private void invalid(IllegalArgumentException e) {
        hideKeyboard();
        ZgTasks.showError(this, getString(ZgCloudText.fieldErrorRes(e instanceof TasksWire.Invalid ? ((TasksWire.Invalid) e).field : null)));
    }

    /** After the last of the owner's tasks in {@code listId} (null: the Cloud Inbox). */
    private static int endOfList(TasksCloudState value, String listId) {
        final ArrayList<TasksWire.Content> contents = new ArrayList<>();
        for (TasksCloudState.TaskView task : value.tasks) {
            if (task.owner) {
                contents.add(task.content);
            }
        }
        return ZgCloudDraft.endOfList(contents, listId);
    }

    private void askConflict() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        hideKeyboard();
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(getString(R.string.ZG_cloudui_conflict_title));
        // Three choices with long names: one under another, as on iOS.
        builder.forceVerticalButtons();
        builder.setMessage(getString(R.string.ZG_cloudui_conflict_text));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.ZG_cloudui_action_keepServer), (d, which) -> close());
        builder.setPositiveButton(getString(R.string.ZG_cloudui_action_applyMine), (d, which) -> {
            if (!closing && !saving) {
                commit();
            }
        });
        final AlertDialog dialog = builder.create();
        if (showDialog(dialog) != null) {
            dialog.setCanceledOnTouchOutside(false);
        }
    }

    private void askGone() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        hideKeyboard();
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(getString(R.string.ZG_cloudui_editor_goneWithChanges_title));
        // Three choices with long names: one under another, as on iOS.
        builder.forceVerticalButtons();
        builder.setMessage(getString(R.string.ZG_cloudui_editor_goneWithChanges_text));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setNeutralButton(getString(R.string.ZG_tasks_discardChanges_discard), (d, which) -> close());
        builder.setPositiveButton(getString(R.string.ZG_cloudui_action_saveAsNew), (d, which) -> saveAsNew());
        final AlertDialog dialog = builder.create();
        if (showDialog(dialog) != null) {
            dialog.setCanceledOnTouchOutside(false);
        }
    }

    /** The kept edits as a new task: in their list while it still exists (else the Cloud Inbox), at its end. */
    private void saveAsNew() {
        final TasksCloudState value = state;
        if (closing || saving || draft == null || value == null || repository == null || refuseReadOnly() || !ZgTasksGate.allowCreate(this)) {
            return;
        }
        final String list = draft.listId != null && value.list(draft.listId) != null ? draft.listId : null;
        final TasksWire.Content content;
        try {
            content = draft.whole(list, endOfList(value, list));
        } catch (IllegalArgumentException e) {
            invalid(e);
            return;
        }
        saving = true;
        updateSave();
        repository.createTask(content, result(created -> close(), this::saveFailed));
    }

    /** See {@link #repair}. */
    private void saveRepair() {
        final TasksCloudState value = state;
        final String mutationId = repairMutationId;
        final TasksCloudState.Pending entry = entry(value, mutationId);
        if (value == null || entry == null || entry.state != TasksCloudState.PendingState.INVALID) {
            // The refused change is no longer there to be replaced; nothing is merged into whatever took its place.
            notice(R.string.ZG_cloudui_error_gone);
            return;
        }
        final TasksWire.Content refused = draft.base();
        final String list;
        if (repairCreate) {
            list = draft.listId != null && value.list(draft.listId) != null ? draft.listId : null;
            if (!ZgTasksGate.allowCreate(this)) {
                return;
            }
        } else {
            list = draft.listId;
            if (list != null && value.list(list) == null) {
                notice(R.string.ZG_cloudui_error_gone);
                return;
            }
        }
        final TasksWire.Content content;
        try {
            content = draft.whole(list, Objects.equals(list, refused.listId) ? refused.sortOrder : endOfList(value, list));
        } catch (IllegalArgumentException e) {
            invalid(e);
            return;
        }
        saving = true;
        updateSave();
        if (repairCreate) {
            repository.createTask(content, result(created -> {
                if (repository == null) {
                    close();
                    return;
                }
                repository.discard(mutationId, result(done -> close(), error -> {
                    // The corrected task is queued; the refused entry stays in Unsent Changes to be discarded there.
                    showError(error);
                    close();
                }));
            }, this::saveFailed));
        } else {
            repository.discard(mutationId, result(discarded -> {
                // From here on this is a plain edit of the task as the server holds it.
                repairMutationId = null;
                final TasksCloudState.TaskView task = task();
                if (task != null && task.owner && draft != null) {
                    draft = draft.onto(task.content);
                }
                if (repository == null) {
                    saveFailed(null);
                    return;
                }
                repository.updateTask(taskId, content, result(sent -> close(), this::saveFailed));
            }, this::saveFailed));
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Leaving the screen
    // -----------------------------------------------------------------------------------------------------------

    private void close() {
        if (closing) {
            return;
        }
        closing = true;
        hideKeyboard();
        // Not finishFragment(): the layout drops that while a transition is running (this screen still arriving, a
        // picker over it leaving), and the screen would stay open with nothing to do. This ends the transition first,
        // and takes only this screen off the stack when another one is over it (a picker, Unsent Changes).
        removeSelfFromStack();
    }

    /** The task went away under the editor: it closes, and the screen that is in front then says why. */
    private void closeGone() {
        goneNotice = true;
        close();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        final TasksCloudRepository.HeldDelete held = heldDelete;
        heldDelete = null;
        // Once this screen has left the stack: the bulletin belongs to the screen that is in front then.
        if (held != null) {
            final long deadline = heldDeadline;
            AndroidUtilities.runOnUIThread(() -> ZgCloudHeldDelete.offerUndo(held, deadline));
        } else if (goneNotice) {
            AndroidUtilities.runOnUIThread(() -> ZgCloudHeldDelete.notice(getString(R.string.ZG_cloudui_error_gone)));
        }
    }

    private boolean hasUnsavedEdits() {
        if (closing || draft == null || !draft.dirty()) {
            return false;
        }
        final Mode mode = mode();
        return mode == Mode.OWNER || mode == Mode.DRAFT_ONLY || mode == Mode.REPAIR;
    }

    @Override
    public boolean canBeginSlide() {
        return !hasUnsavedEdits() && super.canBeginSlide();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (hasUnsavedEdits()) {
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
        hideKeyboard();
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
