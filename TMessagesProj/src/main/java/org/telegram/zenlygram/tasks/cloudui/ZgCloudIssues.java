/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudSchema;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the Unsent Changes screen says about one outbox entry and which choices it offers for it (iOS
 * tasksCloudIssueDisplayState and SGTasksCloudEntry.allowedActions). Pure: it decides, the screen asks the repository.
 *
 * <p>Differences from iOS, all caused by the Android repository:
 * <ul>
 *   <li>{@link Display#RETRYING}: an entry between two automatic attempts is its own outbox state here.</li>
 *   <li>{@link Display#DEPENDENCY_DROPPED}: discarding a change keeps the later changes that were made on top of it and
 *       asks about each one (iOS drops the ones that never left the device).</li>
 *   <li>A copy of a device task (the automatic migration) is never offered Save as New Task or Edit and Send Again:
 *       both create another task, and the copy would then be sent a second time under its own id.</li>
 * </ul>
 */
public final class ZgCloudIssues {

    private ZgCloudIssues() {
    }

    /** Why an unsent change is where it is, as the user reads it. */
    public enum Display {
        WAITING,
        SENDING,
        /** Not sent at the last attempt; the repository tries again by itself. */
        RETRYING,
        WAITING_FOR_EARLIER,
        /** 403 tasks_account_not_linked: kept until the account is linked again. */
        PAUSED,
        /** No session, or a refused one: waits for linking, not for the network. */
        SESSION_PROBLEM,
        OFFLINE,
        SERVICE_UNAVAILABLE,
        CONSENT_REQUIRED,
        CONSENT_FENCED,
        STALLED,
        CREATION_NOT_PERMITTED,
        INVALID,
        INTEGRITY,
        CONFLICT_REVISION,
        CONFLICT_INVITATION_CHANGED,
        CONFLICT_INVITATION_EXPIRED,
        CONFLICT_COMPLETED_TASK,
        /** field_changed: these fields of an edit changed on another device. */
        FIELDS_CHANGED,
        /** members_changed: the list's tasks changed since its delete was decided. */
        MEMBERS_CHANGED,
        /** An earlier change this one was made on top of was discarded. */
        DEPENDENCY_DROPPED,
        NOT_FOUND,
        /** Decided under another Zenlygram account of this Telegram account: never sent under the new one. */
        ACCOUNT_CHANGED
    }

    /** One choice of an entry. Several share a repository call and differ only in what the button says. */
    public enum Action {
        /** The same change again, same identity. */
        RETRY,
        /** The same intent as a new change on top of the current version. */
        APPLY_MINE,
        /** {@link #APPLY_MINE} for an edit whose fields changed elsewhere. */
        KEEP_MINE,
        /** {@link #APPLY_MINE} for a list delete, confirmed again with the list's current tasks. */
        DELETE_AGAIN,
        /** The entry's task as a new task, then the entry is dropped. */
        SAVE_AS_NEW,
        /** The refused task content, corrected in the editor and sent as a new change. */
        REPAIR_TASK,
        /** The refused list name, corrected and sent as a new change. */
        REPAIR_LIST,
        /** Drops the entry from this device. */
        DISCARD,
        /** {@link #DISCARD} of a conflict: the server's version stays. */
        KEEP_SERVER,
        /** {@link #DISCARD} of a field conflict. */
        USE_OTHER;

        /** Removes the entry without sending anything: the only kind that runs while the account is read-only. */
        public boolean discards() {
            return this == DISCARD || this == KEEP_SERVER || this == USE_OTHER;
        }

        public boolean reapplies() {
            return this == APPLY_MINE || this == KEEP_MINE || this == DELETE_AGAIN;
        }
    }

    /** The screen lists every entry the server has not confirmed, except a delete still inside its Undo window. */
    public static boolean listed(TasksCloudState.PendingState state) {
        return state != TasksCloudState.PendingState.HELD;
    }

    /** The repository will not move the entry by itself: it waits for one of {@link #actions}. */
    public static boolean needsAttention(TasksCloudState.PendingState state) {
        switch (state) {
            case STALLED:
            case BLOCKED_CREATION:
            case INVALID:
            case CONFLICT:
            case QUARANTINED:
            case NOT_FOUND:
            case WRONG_PRINCIPAL:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the repository shows the entry's change as part of its task or list. A conflict, a change whose target is
     * gone, an integrity refusal and another account's change are left out (the server's version is shown); every
     * other entry is applied, a refused (invalid) one included.
     */
    public static boolean applied(TasksCloudState.PendingState state) {
        switch (state) {
            case CONFLICT:
            case QUARANTINED:
            case NOT_FOUND:
            case WRONG_PRINCIPAL:
                return false;
            default:
                return true;
        }
    }

    /**
     * What the entry's line says. A waiting entry reads as the account-level reason it is not moving (the consent hold
     * first, then paused, no session, offline, unavailable) rather than a plain "waiting". A conflict reads by its
     * refinement first: the list's tasks changed, then the changed fields, then a discarded earlier change.
     *
     * @param error the entry's failure code ({@link TasksCloudState.Pending#error}), only read to tell a discarded
     *              dependency from a server conflict without a known reason
     */
    public static Display display(TasksCloudState.PendingState state, TasksWire.ConflictReason reason, boolean hasConflictFields,
                                  String error, TasksCloudState.Status account, ZgCloudReadOnly readOnly) {
        switch (state) {
            case SENDING:
                return Display.SENDING;
            case WAITING:
                return Display.WAITING_FOR_EARLIER;
            case STALLED:
                return Display.STALLED;
            case BLOCKED_CREATION:
                return Display.CREATION_NOT_PERMITTED;
            case INVALID:
                return Display.INVALID;
            case QUARANTINED:
                return Display.INTEGRITY;
            case NOT_FOUND:
                return Display.NOT_FOUND;
            case WRONG_PRINCIPAL:
                return Display.ACCOUNT_CHANGED;
            case CONFLICT:
                if (reason == TasksWire.ConflictReason.MEMBERS_CHANGED) {
                    return Display.MEMBERS_CHANGED;
                }
                if (hasConflictFields || reason == TasksWire.ConflictReason.FIELD_CHANGED) {
                    return Display.FIELDS_CHANGED;
                }
                if (reason == null) {
                    return dependencyDropped(error) ? Display.DEPENDENCY_DROPPED : Display.CONFLICT_REVISION;
                }
                switch (reason) {
                    case INVITATION_CHANGED:
                        return Display.CONFLICT_INVITATION_CHANGED;
                    case INVITATION_EXPIRED:
                        return Display.CONFLICT_INVITATION_EXPIRED;
                    case COMPLETED_TASK:
                        return Display.CONFLICT_COMPLETED_TASK;
                    default:
                        return Display.CONFLICT_REVISION;
                }
            default:
                // QUEUED, BACKOFF (HELD is not listed).
                if (readOnly == ZgCloudReadOnly.CONSENT_FENCED) {
                    return Display.CONSENT_FENCED;
                }
                if (readOnly != null) {
                    return Display.CONSENT_REQUIRED;
                }
                if (account != null) {
                    switch (account) {
                        case UNLINKED_PAUSED:
                            return Display.PAUSED;
                        case NOT_LINKED:
                        case SESSION_INVALID:
                            return Display.SESSION_PROBLEM;
                        case OFFLINE:
                            return Display.OFFLINE;
                        case UNAVAILABLE:
                            return Display.SERVICE_UNAVAILABLE;
                        default:
                            break;
                    }
                }
                return state == TasksCloudState.PendingState.BACKOFF ? Display.RETRYING : Display.WAITING;
        }
    }

    private static boolean dependencyDropped(String error) {
        return TasksCloudSchema.NOTICE_DEPENDENCY_DROPPED.equals(error);
    }

    /**
     * The choices of an entry, in the order the screen shows them; empty while the repository is still working on it.
     *
     * @param migration    the entry copies a device task ({@link TasksCloudState.Pending#migration})
     * @param contentKnown the entry's whole task content (or list name) can be read: a create or a full update always
     *                     carries it, a field edit only over the task as the server holds it
     */
    public static List<Action> actions(TasksCloudState.PendingState state, TasksWire.OperationKind kind, TasksWire.ConflictReason reason,
                                       boolean hasConflictFields, String error, boolean migration, boolean contentKnown) {
        final boolean taskContent = kind == TasksWire.OperationKind.TASK_CREATE || kind == TasksWire.OperationKind.TASK_UPDATE
            || kind == TasksWire.OperationKind.TASK_PATCH;
        final boolean ownCopy = !migration && contentKnown;
        switch (state) {
            case STALLED:
            case BLOCKED_CREATION:
                return list(Action.RETRY, Action.DISCARD);
            case INVALID:
                if (ownCopy && taskContent) {
                    return list(Action.REPAIR_TASK, Action.DISCARD);
                }
                if (ownCopy && (kind == TasksWire.OperationKind.LIST_CREATE || kind == TasksWire.OperationKind.LIST_UPDATE)) {
                    return list(Action.REPAIR_LIST, Action.DISCARD);
                }
                return list(Action.DISCARD);
            case QUARANTINED:
                return list(Action.DISCARD);
            case NOT_FOUND:
            case WRONG_PRINCIPAL:
                return ownCopy && taskContent ? list(Action.SAVE_AS_NEW, Action.DISCARD) : list(Action.DISCARD);
            case CONFLICT:
                return conflictActions(kind, reason, hasConflictFields, reason == null && dependencyDropped(error), ownCopy);
            default:
                return Collections.emptyList();
        }
    }

    private static List<Action> conflictActions(TasksWire.OperationKind kind, TasksWire.ConflictReason reason, boolean hasConflictFields,
                                                boolean dropped, boolean ownCopy) {
        // "Keep Current Version" is about a server version; a discarded dependency has none to keep.
        final Action discard = dropped ? Action.DISCARD : Action.KEEP_SERVER;
        switch (kind) {
            case TASK_UPDATE:
            case TASK_PATCH:
                if (hasConflictFields || reason == TasksWire.ConflictReason.FIELD_CHANGED) {
                    return list(Action.KEEP_MINE, Action.USE_OTHER);
                }
                return list(Action.APPLY_MINE, discard);
            case LIST_DELETE:
                if (reason == TasksWire.ConflictReason.MEMBERS_CHANGED) {
                    return list(Action.DELETE_AGAIN, discard);
                }
                return list(Action.APPLY_MINE, discard);
            case TASK_COMPLETE:
            case TASK_DELETE:
            case LIST_UPDATE:
                return list(Action.APPLY_MINE, discard);
            case INVITATION_REQUEST:
                // A completed task cannot be shared: applying it again would only conflict again.
                return reason == TasksWire.ConflictReason.COMPLETED_TASK ? list(discard) : list(Action.APPLY_MINE, discard);
            case TASK_CREATE:
                // After a discarded dependency (its list's create): sent again it would name a list that is gone.
                return dropped && ownCopy ? list(Action.SAVE_AS_NEW, discard) : list(discard);
            default:
                return list(discard);
        }
    }

    /** Retrying or repairing a creation, and saving a copy, create something: they pass the CREATE gate first. */
    public static boolean needsCreateGate(Action action, TasksWire.OperationKind kind) {
        if (action == Action.SAVE_AS_NEW) {
            return true;
        }
        return (action == Action.RETRY || action == Action.REPAIR_TASK || action == Action.REPAIR_LIST) && kind.isCreate();
    }

    /**
     * Whether "Try Again" (an explicit sync) can move anything: never while a consent hold keeps every request off, nor
     * while only linking the account again can resume it (not linked, paused by the server, a refused session).
     */
    public static boolean retryResumesSync(TasksCloudState.Status status, ZgCloudReadOnly readOnly) {
        if (readOnly != null || status == null) {
            return false;
        }
        switch (status) {
            case READY:
            case LOADING:
            case OFFLINE:
            case UNAVAILABLE:
                return true;
            default:
                return false;
        }
    }

    private static List<Action> list(Action... actions) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(actions)));
    }
}
