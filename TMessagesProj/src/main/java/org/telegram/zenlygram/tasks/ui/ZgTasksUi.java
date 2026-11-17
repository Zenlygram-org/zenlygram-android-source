/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksSyncOwner;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAssigneeDisplay;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.zenlygram.customer.ZgDataConsentActivity;
import org.telegram.zenlygram.pro.ZgLinkAccountActivity;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.ZgTasksPrefs;
import org.telegram.zenlygram.tasks.model.ZgTasksRouting;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgBoardTask;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.vault.ZgVaultHooks;

/**
 * The words the Tasks screens share: why a change did not happen, why a board is read-only, what a row's sync line
 * says, and how a Telegram user is named. Every text says what actually happened; nothing claims a change reached the
 * server before the server confirmed it. Main thread.
 */
public final class ZgTasksUi {

    private ZgTasksUi() {
    }

    /**
     * Shows why a change did not happen. A fence (the copy's local step, the archive, a consent hold) is a short note
     * and the caller keeps the user's input; anything else is an alert.
     *
     * @param limit the name limit a too-long device name is told (a task's or a list's)
     */
    public static void report(BaseFragment fragment, ZgTasksError error, ZgBoard board, int limit) {
        if (fragment == null || error == null) {
            return;
        }
        final String text = errorText(error, board, limit);
        if (error.isFence() || error.isAssignRefusal()) {
            note(fragment, text);
        } else {
            ZgTasks.showError(fragment, text);
        }
    }

    /** A transient note above the composer (iOS tasksNoticeToast); never an error alert. */
    public static void note(BaseFragment fragment, CharSequence text) {
        if (BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, text, 4).show();
        }
    }

    public static String errorText(ZgTasksError error, ZgBoard board, int limit) {
        final boolean cloud = board != null && board.scope == ZgTasksScope.CLOUD;
        switch (error.kind) {
            case MIGRATING:
                return getString(R.string.ZG_tasks_migration_busy);
            case ARCHIVED:
                return getString(R.string.ZG_tasks_archive_banner);
            case CONSENT:
                return readOnlyText(board != null && board.readOnly != null ? board.readOnly : ZgBoard.ReadOnly.WITHDRAWN);
            case INVALID:
                if (!cloud) {
                    // The device store's only rule a checked name can still break is its length.
                    return formatString(R.string.ZG_tasks_error_titleTooLong, limit > 0 ? limit : ZgTasksTitlePolicy.TASK_LIMIT);
                }
                if (error.field != null && error.field.endsWith("due_at")) {
                    return getString(R.string.ZenlyCloudTasksDueTooLate);
                }
                if (error.field != null && (error.field.endsWith("title") || error.field.endsWith("description") || error.field.endsWith("display_name"))) {
                    return getString(R.string.ZenlyCloudTasksFieldInvalid);
                }
                return getString(R.string.ZenlyCloudTasksInvalid);
            case NOT_FOUND:
                return getString(cloud ? R.string.ZenlyCloudTasksErrorNotFound : R.string.ZG_tasks_error_generic);
            case NOT_LINKED:
                return getString(R.string.ZenlyCloudTasksErrorAccount);
            case LINK_CHANGED:
                return getString(R.string.ZenlyCloudTasksErrorPrincipal);
            case NOT_READY:
                return getString(R.string.ZenlyCloudTasksErrorNotReady);
            case NOT_OWNER:
                return getString(R.string.ZenlyCloudTasksErrorNotOwner);
            case BUSY:
                return getString(R.string.ZenlyCloudTasksErrorBusy);
            case IMPORT_STALE:
                return getString(R.string.ZenlyCloudTasksErrorImportStale);
            case ASSIGN_SELF:
                return getString(R.string.ZG_tasks_assign_self);
            case ASSIGN_LIMIT:
                return assignLimitText(error.field);
            case ASSIGN_CHANGED:
                return getString(R.string.ZG_cloudui_error_changedElsewhere);
            case ASSIGN_COMPLETED:
                return getString(R.string.ZG_tasks_assign_footer_completedTask);
            case ASSIGN_NEEDS_SYNC:
                return getString(R.string.ZG_tasks_assign_needsSync_title);
            case ACCOUNT_CHANGED:
                return getString(cloud ? R.string.ZenlyCloudTasksErrorAccount : R.string.ZG_tasks_error_accountChanged);
            case STORAGE:
                return getString(cloud ? R.string.ZenlyCloudTasksErrorStorage : R.string.ZG_tasks_error_generic);
            default:
                return getString(cloud ? R.string.ZenlyCloudTasksErrorGeneric : R.string.ZG_tasks_error_generic);
        }
    }

    /** Why nothing on the board can be changed (its banner says the same). */
    public static String readOnlyText(ZgBoard.ReadOnly reason) {
        switch (reason) {
            case CONSENT_OFF:
                return getString(R.string.ZenlyCloudTasksConsentOff);
            case CONSENT_FENCED:
                return getString(R.string.ZenlyCloudTasksConsentFenced);
            case ARCHIVE:
                return getString(R.string.ZG_tasks_archive_banner);
            default:
                return getString(R.string.ZenlyCloudTasksWithdrawn);
        }
    }

    /** The row's sync line, or null when everything is confirmed. */
    public static String syncText(ZgBoardTask.Sync sync) {
        switch (sync) {
            case NOT_SENT:
                return getString(R.string.ZG_tasks_cloud_row_notSent);
            case CONFIRMING:
                return getString(R.string.ZG_tasks_cloud_row_confirming);
            case NEEDS_ATTENTION:
                return getString(R.string.ZG_tasks_cloud_row_needsAttention);
            default:
                return null;
        }
    }

    /**
     * A build with sync: an assignee is an assignment, so the device board stores no new one (iOS wf15 D1). The older
     * notes stay readable and removable.
     */
    public static boolean deviceAssigneesAreOldEntries(int account) {
        return ZgTasksSources.scopeSwitch(account) != ZgTasksRouting.Switch.DEVICE_ONLY;
    }

    /** A device note as its row reads: "Name (old entry)" where assigning needs sync, the plain name otherwise. */
    public static String deviceAssigneeText(int account, String name) {
        return deviceAssigneesAreOldEntries(account) ? name + " " + getString(R.string.ZG_tasks_assign_oldEntry) : name;
    }

    /**
     * The device board's answer to an assignee pick where assigning needs sync: nothing is stored. While sync is being
     * turned on a note says so; otherwise a card explains what assigning needs and leads to Data & consent, or to the
     * link when only that is missing. Returns false when the board keeps its own local assignee (a build without sync).
     */
    public static boolean answerDevicePick(BaseFragment fragment, int account) {
        if (fragment == null || !deviceAssigneesAreOldEntries(account)) {
            return false;
        }
        final TasksCloudGate.Permission permission = ZenlyTasksSyncOwner.permission(account);
        if (permission == TasksCloudGate.Permission.ALLOWED) {
            note(fragment, getString(R.string.ZG_tasks_assign_waitForSync));
            return true;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return true;
        }
        final boolean needsLink = permission == TasksCloudGate.Permission.WAITING;
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(getString(R.string.ZG_tasks_assign_needsSync_title));
        builder.setMessage(getString(R.string.ZG_tasks_assign_needsSync_text));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(needsLink ? R.string.ZG_tasks_cloud_link_action : R.string.ZG_tasks_assign_needsSync_action),
            (d, which) -> fragment.presentFragment(needsLink ? new ZgLinkAccountActivity() : new ZgDataConsentActivity()));
        fragment.showDialog(builder.create());
        return true;
    }

    /**
     * "How assigning works", once per Telegram account, before its first assignment is sent: {@code then} runs after
     * Got It, or at once when the account has read it. Dismissing the card any other way sends nothing.
     */
    public static void explainAssigningOnce(BaseFragment fragment, int account, Runnable then) {
        final long userId = UserConfig.getInstance(account).getClientUserId();
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (ZgTasksPrefs.isAssignExplained(userId) || activity == null) {
            then.run();
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(getString(R.string.ZG_tasks_assign_explainer_title));
        builder.setMessage(getString(R.string.ZG_tasks_assign_explainer_text));
        builder.setPositiveButton(getString(R.string.ZG_tasks_assign_explainer_ok), (d, which) -> {
            ZgTasksPrefs.setAssignExplained(userId);
            then.run();
        });
        if (fragment.showDialog(builder.create()) == null) {
            // A transition is running: the card comes with the next pick; this one goes on as picked.
            then.run();
        }
    }

    /** The sentence of a refused pick, by the limit the engine named (TasksInvitationPacer.Limit). */
    public static String assignLimitText(String limit) {
        if ("PER_PERSON".equals(limit)) {
            return getString(R.string.ZG_tasks_assign_limitPerPerson);
        }
        if ("PER_MINUTE".equals(limit)) {
            return getString(R.string.ZG_tasks_assign_limitPerMinute);
        }
        if ("PENDING".equals(limit)) {
            return getString(R.string.ZG_tasks_assign_limitPending);
        }
        return getString(R.string.ZG_tasks_assign_limitTotal);
    }

    /** The owner's state word for a task's assignee (iOS tasks_assign_state_*). */
    public static String assigneeStateText(TasksAssigneeDisplay.State state) {
        switch (state) {
            case SENDING:
                return getString(R.string.ZG_tasks_assign_state_sending);
            case QUEUED:
                return getString(R.string.ZG_tasks_assign_state_queued);
            case SENT:
                return getString(R.string.ZG_tasks_assign_state_sent);
            case ACCEPTED:
                return getString(R.string.ZG_tasks_assign_state_accepted);
            case COMPLETED:
                return getString(R.string.ZG_tasks_assign_state_completed);
            case DECLINED:
                return getString(R.string.ZG_tasks_assign_state_declined);
            case LEFT:
                return getString(R.string.ZG_tasks_assign_state_left);
            case EXPIRED:
                return getString(R.string.ZG_tasks_assign_state_expired);
            case REMOVED:
                return getString(R.string.ZG_tasks_assign_state_removed);
            default:
                return getString(R.string.ZG_tasks_assign_state_notSent);
        }
    }

    /**
     * Whom the owner's assignee row names: the name this account knows the user by, else the name an older note of
     * the same id holds, else "Telegram user &lt;id&gt;". A user hidden in the Vault stays "Telegram user".
     */
    public static String assigneeName(int account, String telegramId, String noteName) {
        long id;
        try {
            id = Long.parseLong(telegramId);
        } catch (NumberFormatException notDecimal) {
            return getString(R.string.ZenlyCloudTasksHiddenPerson);
        }
        if (ZgVaultHooks.isHidden(account, id)) {
            return getString(R.string.ZenlyCloudTasksHiddenPerson);
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(id);
        final String known = user != null ? UserObject.getUserName(user) : null;
        return TasksAssigneeDisplay.name(telegramId, known, null, noteName != null && !noteName.trim().isEmpty()
            ? noteName.trim() : formatString(R.string.ZenlyCloudTasksTelegramUser, telegramId));
    }

    /** "Name · state": the assignee as the owner's board row and editor show it. */
    public static String assigneeLine(int account, ZgBoardTask task) {
        final String name = assigneeName(account, task.assigneeTgId, task.assigneeNoteName);
        return task.assigneeState == null ? name
            : formatString(R.string.ZG_tasks_assign_nameState, name, assigneeStateText(task.assigneeState));
    }

    /**
     * A Telegram user by raw id, for display only: the name when this account knows the user, otherwise "Telegram user
     * &lt;id&gt;". A user whose chat is hidden in this account's Vault is "Telegram user", with neither name nor id,
     * as everywhere outside the Vault.
     */
    public static String person(int account, String telegramId) {
        long id;
        try {
            id = Long.parseLong(telegramId);
        } catch (NumberFormatException notDecimal) {
            return getString(R.string.ZenlyCloudTasksHiddenPerson);
        }
        if (ZgVaultHooks.isHidden(account, id)) {
            return getString(R.string.ZenlyCloudTasksHiddenPerson);
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser(id);
        if (user != null) {
            final String name = UserObject.getUserName(user);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return formatString(R.string.ZenlyCloudTasksTelegramUser, telegramId);
    }
}
