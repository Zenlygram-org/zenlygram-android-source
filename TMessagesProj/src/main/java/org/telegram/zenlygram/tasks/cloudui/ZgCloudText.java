/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudError;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.tgnet.TLRPC;
import org.telegram.zenlygram.tasks.model.ZgTasksDeadline;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.ui.ZgTasksText;
import org.telegram.zenlygram.vault.ZgVault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The words of the Cloud Tasks screens: which text each repository state, refusal, outbox entry and sharing state
 * reads as, people's names, and dates. The {@code ...Res} functions are pure tables (the JVM tests check that every
 * value has its own text); the rest reads the app language. Nothing here says a change reached the server before the
 * server confirmed it.
 */
final class ZgCloudText {

    private ZgCloudText() {
    }

    // -----------------------------------------------------------------------------------------------------------
    // The account
    // -----------------------------------------------------------------------------------------------------------

    static int readOnlyRes(ZgCloudReadOnly reason) {
        switch (reason) {
            case WITHDRAWN:
                return R.string.ZenlyCloudTasksWithdrawn;
            case CONSENT_FENCED:
                return R.string.ZenlyCloudTasksConsentFenced;
            default:
                return R.string.ZenlyCloudTasksConsentOff;
        }
    }

    /**
     * What a screen says instead of its content while the account's cloud tasks cannot be trusted to be complete: 0 when
     * they can. Never "nothing here" before the server's list has been loaded.
     *
     * @param available the repository is open for this account (false: signed out, or the slot holds someone else)
     */
    static int unavailableRes(boolean available, TasksCloudState.Status status, boolean shadowComplete, boolean syncing, boolean contentFree) {
        if (!available || status == TasksCloudState.Status.CLOSED) {
            return R.string.ZenlyCloudTasksStatusClosed;
        }
        if (status == TasksCloudState.Status.STORAGE_FAILED) {
            return R.string.ZenlyCloudTasksStatusError;
        }
        if (status == TasksCloudState.Status.NOT_LINKED && contentFree) {
            return R.string.ZenlyCloudTasksStatusNotLinked;
        }
        if (shadowComplete) {
            return 0;
        }
        if (status == TasksCloudState.Status.LOADING || syncing) {
            return R.string.ZenlyCloudTasksStatusLoading;
        }
        return R.string.ZenlyCloudTasksNotLoaded;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------------------------------------------------

    /** Why an action did not happen. Never an id, a value or the engine's own message. */
    static String error(TasksCloudError error) {
        if (error == null) {
            return getString(R.string.ZenlyCloudTasksErrorGeneric);
        }
        if (error.code == TasksCloudError.Code.INVALID) {
            return getString(fieldErrorRes(error.field));
        }
        if (error.code == TasksCloudError.Code.ASSIGNMENT_LIMIT) {
            return getString(assignLimitRes(error.field));
        }
        return getString(errorRes(error.code));
    }

    static int errorRes(TasksCloudError.Code code) {
        switch (code) {
            case NOT_LINKED:
            case CLOSED:
                return R.string.ZenlyCloudTasksErrorAccount;
            case PRINCIPAL_CHANGED:
                return R.string.ZenlyCloudTasksErrorPrincipal;
            case NOT_READY:
                return R.string.ZenlyCloudTasksErrorNotReady;
            case NOT_FOUND:
                return R.string.ZG_cloudui_error_gone;
            case NOT_OWNER:
                return R.string.ZG_cloudui_error_notOwner;
            case BUSY_STATE:
                return R.string.ZenlyCloudTasksErrorBusy;
            case IMPORT_STALE:
                return R.string.ZenlyCloudTasksErrorImportStale;
            case STORAGE:
                return R.string.ZenlyCloudTasksErrorStorage;
            case CONSENT_WITHDRAWN:
                return R.string.ZenlyCloudTasksWithdrawn;
            case INVALID:
                return R.string.ZenlyCloudTasksInvalid;
            case SELF_ASSIGNMENT:
                return R.string.ZG_tasks_assign_self;
            case ASSIGNMENT_LIMIT:
                return R.string.ZG_tasks_assign_limitTotal;
            case CHANGED_ELSEWHERE:
            case NOT_ALLOWED:
                return R.string.ZG_cloudui_error_changedElsewhere;
            default:
                return R.string.ZenlyCloudTasksErrorGeneric;
        }
    }

    /** The sentence of a pick the client pacer refused, by the limit it named (TasksInvitationPacer.Limit). */
    static int assignLimitRes(String limit) {
        if ("PER_PERSON".equals(limit)) {
            return R.string.ZG_tasks_assign_limitPerPerson;
        }
        if ("PER_MINUTE".equals(limit)) {
            return R.string.ZG_tasks_assign_limitPerMinute;
        }
        if ("PENDING".equals(limit)) {
            return R.string.ZG_tasks_assign_limitPending;
        }
        return R.string.ZG_tasks_assign_limitTotal;
    }

    /**
     * The rule a value breaks, by the field the wire names ("content.title", "set.description", "annotation.display_name",
     * "is_completed" for sharing a done task, ...).
     */
    static int fieldErrorRes(String field) {
        if (field == null) {
            return R.string.ZenlyCloudTasksInvalid;
        }
        if (field.equals("is_completed")) {
            return R.string.ZG_tasks_assign_footer_completedTask;
        }
        if (field.endsWith("title")) {
            return R.string.ZG_cloudui_error_title;
        }
        if (field.endsWith("description")) {
            return R.string.ZG_cloudui_error_notes;
        }
        if (field.endsWith("due_at")) {
            return R.string.ZenlyCloudTasksDueTooLate;
        }
        if (field.contains("annotation") || field.endsWith("display_name") || field.endsWith("recipient_tg_id")) {
            return R.string.ZG_cloudui_error_recipient;
        }
        return R.string.ZenlyCloudTasksInvalid;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Unsent changes
    // -----------------------------------------------------------------------------------------------------------

    /** The user-facing kind of a change; a field edit (task.patch) reads as a task edit. */
    static int operationRes(TasksWire.OperationKind kind) {
        switch (kind) {
            case LIST_CREATE:
                return R.string.ZG_cloudui_op_listCreate;
            case LIST_UPDATE:
                return R.string.ZG_cloudui_op_listUpdate;
            case LIST_DELETE:
                return R.string.ZG_cloudui_op_listDelete;
            case TASK_CREATE:
                return R.string.ZG_cloudui_op_taskCreate;
            case TASK_COMPLETE:
                return R.string.ZG_cloudui_op_taskComplete;
            case TASK_DELETE:
                return R.string.ZG_cloudui_op_taskDelete;
            case INVITATION_REQUEST:
                return R.string.ZG_cloudui_op_inviteRequest;
            case INVITATION_RESPOND:
                return R.string.ZG_cloudui_op_inviteRespond;
            case INVITATION_REVOKE:
                return R.string.ZG_cloudui_op_inviteRevoke;
            case SENDER_BLOCK:
                return R.string.ZG_cloudui_op_senderBlock;
            case SENDER_UNBLOCK:
                return R.string.ZG_cloudui_op_senderUnblock;
            default:
                return R.string.ZG_cloudui_op_taskUpdate;
        }
    }

    /** What a change is; a copy of a device task reads as such, not as something the user just created. */
    static String operation(TasksWire.OperationKind kind, boolean migration) {
        return getString(migration ? R.string.ZenlyCloudTasksChangeMigration : operationRes(kind));
    }

    /** {@link ZgCloudIssues.Display#FIELDS_CHANGED} takes the field names, {@code MEMBERS_CHANGED} the task count. */
    static int stateRes(ZgCloudIssues.Display display) {
        switch (display) {
            case SENDING:
                return R.string.ZenlyCloudTasksPendingSending;
            case RETRYING:
                return R.string.ZenlyCloudTasksPendingBackoff;
            case WAITING_FOR_EARLIER:
                return R.string.ZG_cloudui_state_waitingForEarlier;
            case PAUSED:
                return R.string.ZG_cloudui_state_paused;
            case SESSION_PROBLEM:
                return R.string.ZG_cloudui_state_sessionProblem;
            case OFFLINE:
                return R.string.ZG_cloudui_state_offline;
            case SERVICE_UNAVAILABLE:
                return R.string.ZG_cloudui_state_serviceUnavailable;
            case CONSENT_REQUIRED:
                return R.string.ZenlyCloudTasksConsentOff;
            case CONSENT_FENCED:
                return R.string.ZenlyCloudTasksConsentFenced;
            case STALLED:
                return R.string.ZG_cloudui_state_stalled;
            case CREATION_NOT_PERMITTED:
                return R.string.ZG_cloudui_state_creationNotPermitted;
            case INVALID:
                return R.string.ZG_cloudui_state_invalid;
            case INTEGRITY:
                return R.string.ZG_cloudui_state_integrity;
            case CONFLICT_REVISION:
                return R.string.ZG_cloudui_state_conflictRevision;
            case CONFLICT_INVITATION_CHANGED:
                return R.string.ZG_cloudui_state_conflictInvitationChanged;
            case CONFLICT_INVITATION_EXPIRED:
                return R.string.ZG_cloudui_state_conflictInvitationExpired;
            case CONFLICT_COMPLETED_TASK:
                return R.string.ZG_cloudui_state_conflictCompletedTask;
            case FIELDS_CHANGED:
                return R.string.ZenlyCloudTasksChangedFields;
            case MEMBERS_CHANGED:
                return R.string.ZenlyCloudTasksMembersChanged;
            case DEPENDENCY_DROPPED:
                return R.string.ZG_cloudui_state_dependencyDropped;
            case NOT_FOUND:
                return R.string.ZG_cloudui_state_notFound;
            case ACCOUNT_CHANGED:
                return R.string.ZG_cloudui_state_accountChanged;
            default:
                return R.string.ZG_cloudui_state_waiting;
        }
    }

    /**
     * @param fields      the fields of a field conflict, in content order
     * @param memberCount the tasks a list holds now (a list delete asked again)
     */
    static String state(ZgCloudIssues.Display display, List<TasksWire.ContentField> fields, int memberCount) {
        if (display == ZgCloudIssues.Display.FIELDS_CHANGED) {
            return fields == null || fields.isEmpty() ? getString(R.string.ZG_cloudui_state_conflictRevision)
                : formatString(R.string.ZenlyCloudTasksChangedFields, fieldList(fields));
        }
        if (display == ZgCloudIssues.Display.MEMBERS_CHANGED) {
            return formatString(R.string.ZenlyCloudTasksMembersChanged, memberCount);
        }
        return getString(stateRes(display));
    }

    static int actionRes(ZgCloudIssues.Action action) {
        switch (action) {
            case RETRY:
                return R.string.ZG_cloudui_action_retry;
            case APPLY_MINE:
                return R.string.ZG_cloudui_action_applyMine;
            case KEEP_MINE:
                return R.string.ZenlyCloudTasksConflictKeepMine;
            case DELETE_AGAIN:
                return R.string.Delete;
            case SAVE_AS_NEW:
                return R.string.ZG_cloudui_action_saveAsNew;
            case REPAIR_TASK:
                return R.string.ZG_cloudui_action_repairTask;
            case REPAIR_LIST:
                return R.string.ZG_cloudui_action_repairList;
            case KEEP_SERVER:
                return R.string.ZG_cloudui_action_keepServer;
            case USE_OTHER:
                return R.string.ZenlyCloudTasksUseOther;
            default:
                return R.string.ZG_cloudui_action_discard;
        }
    }

    /** The owner's assignee is the wire's annotation: it is named as the editor names it. */
    static int fieldRes(TasksWire.ContentField field) {
        switch (field) {
            case TITLE:
                return R.string.ZenlyCloudTasksFieldTitle;
            case DESCRIPTION:
                return R.string.ZenlyCloudTasksFieldNotes;
            case LIST_ID:
                return R.string.ZenlyCloudTasksFieldList;
            case DUE_AT:
                return R.string.ZenlyCloudTasksFieldDeadline;
            case PRIORITY:
                return R.string.ZenlyCloudTasksFieldPriority;
            case SORT_ORDER:
                return R.string.ZenlyCloudTasksFieldPosition;
            default:
                return R.string.ZG_tasks_assignee_title;
        }
    }

    static String fieldList(List<TasksWire.ContentField> fields) {
        final StringBuilder names = new StringBuilder();
        for (TasksWire.ContentField field : fields) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(getString(fieldRes(field)));
        }
        return names.toString();
    }

    static int noticeRes(TasksCloudState.Notice.Kind kind) {
        switch (kind) {
            case ACCESS_LOST:
                return R.string.ZenlyCloudTasksNoticeAccessLost;
            case NOT_FOUND:
                return R.string.ZenlyCloudTasksNoticeNotFound;
            case REMOVED_IN_CLOUD:
                return R.string.ZenlyCloudTasksNoticeRemovedInCloud;
            case DELETE_KEPT:
                return R.string.ZenlyCloudTasksDeleteKept;
            default:
                return R.string.ZenlyCloudTasksNoticeDependencyDropped;
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Sharing
    // -----------------------------------------------------------------------------------------------------------

    /** The state of a task's sharing beside its recipient. */
    static int sharingStateRes(ZgCloudSharing.State state) {
        switch (state) {
            case CONFIRMING:
                return R.string.ZG_cloudui_syncConfirmationPending;
            case PENDING:
                return R.string.ZG_cloudui_assignment_pending;
            case ACCEPTED:
                return R.string.ZG_cloudui_assignment_accepted;
            case DECLINED:
                return R.string.ZG_cloudui_assignment_declined;
            case REVOKED:
                return R.string.ZG_cloudui_assignment_revoked;
            case LEFT:
                return R.string.ZG_cloudui_assignment_left;
            case EXPIRED:
                return R.string.ZG_cloudui_assignment_expired;
            default:
                return R.string.ZG_cloudui_access_onlyYou;
        }
    }

    /** What the recipient can do, as the owner reads it. */
    static int accessRes(ZgCloudSharing.Access access) {
        switch (access) {
            case INVITATION_PENDING_CONFIRMATION:
                return R.string.ZG_cloudui_access_recipient_pendingInvitation;
            case INVITED:
                return R.string.ZG_cloudui_access_recipient_invited;
            case ACCEPTED:
                return R.string.ZG_cloudui_access_recipient_accepted;
            case REVOCATION_PENDING_CONFIRMATION:
                return R.string.ZG_cloudui_access_recipient_pendingRevocation;
            case ENDED:
                return R.string.ZG_cloudui_access_recipient_ended;
            default:
                return R.string.ZG_cloudui_access_onlyYou;
        }
    }

    static int priorityRes(int priority) {
        switch (priority) {
            case 1:
                return R.string.ZG_cloudui_priority_low;
            case 2:
                return R.string.ZG_cloudui_priority_medium;
            case 3:
                return R.string.ZG_cloudui_priority_high;
            case 4:
                return R.string.ZG_cloudui_priority_urgent;
            default:
                return R.string.ZG_cloudui_priority_none;
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // People
    // -----------------------------------------------------------------------------------------------------------

    /**
     * The name this account's own Telegram data has for a user, or null. The wire carries no names, so one supplied by
     * another user is never shown; the phone number is never used as a name; a user hidden in this account's Vault
     * stays nameless outside it.
     */
    static String knownName(int account, String telegramId) {
        final long id = userId(telegramId);
        if (id <= 0 || ZgVault.isHidden(account, id)) {
            return null;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(id);
        if (user == null || UserObject.isDeleted(user)) {
            return null;
        }
        final String name = ZgTasksTitlePolicy.trim(ContactsController.formatName(user.first_name, user.last_name));
        return ZgTasksTitlePolicy.containsVisible(name) ? name : null;
    }

    /** "Name (Telegram ID 1)" for a user this account knows, otherwise "Telegram user 1". The id is always shown. */
    static String person(int account, String telegramId) {
        final String name = knownName(account, telegramId);
        if (name == null) {
            return formatString(R.string.ZenlyCloudTasksTelegramUser, telegramId);
        }
        return formatString(R.string.ZG_cloudui_person_withId, ZgTasksDeadline.isolatedDisplayText(name), telegramId);
    }

    /**
     * Reads the users of {@code telegramIds} that are not in memory from this account's local Telegram database (no
     * request is made) and runs {@code loaded} on the UI thread when any was found. {@code asked} remembers what was
     * looked up, so every id is read once per screen.
     */
    static void loadNames(int account, Collection<String> telegramIds, Set<String> asked, Runnable loaded) {
        final ArrayList<Long> missing = new ArrayList<>();
        final MessagesController controller = MessagesController.getInstance(account);
        for (String telegramId : telegramIds) {
            final long id = userId(telegramId);
            if (id > 0 && asked.add(telegramId) && controller.getUser(id) == null && !ZgVault.isHidden(account, id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            final ArrayList<TLRPC.User> users = storage.getUsers(missing);
            if (users.isEmpty()) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                controller.putUsers(users, true);
                loaded.run();
            });
        });
    }

    private static long userId(String telegramId) {
        if (telegramId == null) {
            return 0;
        }
        try {
            return Long.parseLong(telegramId);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Dates and other people's text
    // -----------------------------------------------------------------------------------------------------------

    /** A deadline or another moment in Unix seconds, as the board writes deadlines. */
    static String moment(long seconds) {
        return ZgTasksText.deadlineText(seconds, System.currentTimeMillis() / 1000L);
    }

    /** A wire Instant as a local date and time; empty when it cannot be read. */
    static String instant(String value) {
        try {
            return moment(TasksWire.epochSeconds(value));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Text written by the user or by someone else, isolated so that it cannot reorder the line around it. */
    static String isolated(String text) {
        return ZgTasksDeadline.isolatedDisplayText(text);
    }
}
