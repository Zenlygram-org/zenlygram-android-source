package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Automatic conflict resolution (design 30 §7.3) as pure decisions; TasksCloudLedger applies them in one transaction
 * right after a COMPLETE pull, against the fresh shadow. A 409 the table can answer is stored as a conflict marked
 * {@code auto_resolve = pending}; everything else stays explicit (R3), as does an intent already resolved
 * automatically {@link TasksCloudPolicy#MAX_AUTO_RESOLUTIONS} times (a replacement inherits the count + 1).
 * <pre>
 * task.patch    field_changed F             fields outside F → a new patch (new id) expecting the server's values;
 *                                           the part in F stays explicit (keep mine / use other)
 * task.complete revision/invitation_changed server already as intended → dropped; else resealed (fresh base, epoch)
 * task.delete   revision_changed            dropped; a DELETE_KEPT notice while the task still exists (nothing lost)
 * list.update   revision_changed            three-way merge of title and order with base_snapshot; disjoint → resealed
 * list.delete   revision_changed            resealed on the fresh revision, keeping the members fence it was decided under
 * invitation.request revision_changed       the owner still owns, the task is open and its live assignee (if any) is
 *                                           still the one this pick was made to replace → resealed on the fresh
 *                                           revision as a new epoch; else explicit (the automatic re-send of a deferred
 *                                           assignment ends quietly instead): a reseal never ends an assignment the
 *                                           owner did not confirm ending
 * invitation.revoke  revision_changed       resealed while the same epoch is still the task's live assignment; else the
 *                                           server already holds the intent: dropped
 * anything else (members_changed, task.update, a manual answer, 404 ...)      explicit
 * </pre>
 * The automatic requeue of §7.4 lives in TasksCloudLedger.autoRequeue / TasksCloudEngine.requeue.
 */
final class TasksCloudAutoResolve {
    static final class Replacement {
        final TasksWire.Operation operation;
        /** null: queued again automatically; else the explicit part of a split patch (field_changed, these fields). */
        final List<TasksWire.ContentField> conflictFields;
        /** list.update: the base of the next merge (the row this one was made against). */
        final String baseSnapshot;

        Replacement(TasksWire.Operation operation, List<TasksWire.ContentField> conflictFields, String baseSnapshot) {
            this.operation = operation;
            this.conflictFields = conflictFields;
            this.baseSnapshot = baseSnapshot;
        }
    }

    static final class Decision {
        enum Kind {
            /** The server already holds the intent (or its target is gone): the entry goes. */
            DROP,
            /** Replaced in its place by these entries, each with a new identity. */
            REPLACE,
            /** Stays a conflict for the user. */
            EXPLICIT
        }

        final Kind kind;
        final List<Replacement> replacements;
        /** DROP of a task.delete whose task still exists. */
        final boolean deleteKept;

        private Decision(Kind kind, List<Replacement> replacements, boolean deleteKept) {
            this.kind = kind;
            this.replacements = replacements;
            this.deleteKept = deleteKept;
        }

        static final Decision EXPLICIT = new Decision(Kind.EXPLICIT, Collections.<Replacement>emptyList(), false);
        static final Decision DROP = new Decision(Kind.DROP, Collections.<Replacement>emptyList(), false);

        static Decision replace(Replacement... replacements) {
            ArrayList<Replacement> list = new ArrayList<>();
            Collections.addAll(list, replacements);
            return new Decision(Kind.REPLACE, Collections.unmodifiableList(list), false);
        }
    }

    private TasksCloudAutoResolve() { }

    /** Whether a 409 with {@code reason} on {@code entry} is marked for automatic resolution. */
    static boolean resolvesAutomatically(TasksCloudLedger.Entry entry, TasksWire.ConflictReason reason) {
        if (reason == null || entry.autoResolutions >= TasksCloudPolicy.MAX_AUTO_RESOLUTIONS) return false;
        switch (entry.operation.kind) {
            case TASK_PATCH:
                return reason == TasksWire.ConflictReason.FIELD_CHANGED;
            case TASK_COMPLETE:
                return reason == TasksWire.ConflictReason.REVISION_CHANGED || reason == TasksWire.ConflictReason.INVITATION_CHANGED;
            case TASK_DELETE:
            case LIST_DELETE:
            case INVITATION_REQUEST:
            case INVITATION_REVOKE:
                return reason == TasksWire.ConflictReason.REVISION_CHANGED;
            case LIST_UPDATE:
                return reason == TasksWire.ConflictReason.REVISION_CHANGED && listBase(entry.baseSnapshot) != null;
            default:
                return false;
        }
    }

    /**
     * §7.3 against the fresh shadow ({@code task}/{@code list}: the entry's shadow row, null when absent).
     * {@code nowSeconds} decides whether a pending invitation is still the owner's live epoch. A replacement the
     * sealing rules refuse is no automatic resolution.
     */
    static Decision resolve(TasksCloudLedger.Entry entry, TasksWire.TaskItem task, TasksWire.ListItem list, long nowSeconds) {
        return resolve(entry, task, list, nowSeconds, false);
    }

    /** {@code automaticResend}: the entry is the automatic re-send of a deferred assignment (it never becomes explicit). */
    static Decision resolve(TasksCloudLedger.Entry entry, TasksWire.TaskItem task, TasksWire.ListItem list, long nowSeconds,
                            boolean automaticResend) {
        try {
            TasksWire.Operation operation = entry.operation;
            switch (operation.kind) {
                case TASK_PATCH:
                    return patch(entry, (TasksWire.Operation.TaskPatch) operation, task);
                case TASK_COMPLETE: {
                    TasksWire.Operation.TaskComplete complete = (TasksWire.Operation.TaskComplete) operation;
                    if (task == null) return Decision.EXPLICIT;
                    if (task.isCompleted == complete.isCompleted) return Decision.DROP;
                    String invitationId;
                    if (task.viewerRole == TasksWire.ViewerRole.OWNER) {
                        invitationId = ownerCompletionInvitationId(task.assignment, nowSeconds);
                    } else {
                        if (task.assignment == null || task.assignment.state != TasksWire.AssignmentState.ACCEPTED) return Decision.EXPLICIT;
                        invitationId = task.assignment.invitationId;
                    }
                    return Decision.replace(new Replacement(TasksWire.Operation.taskComplete(complete.taskId, task.revision, invitationId,
                            complete.isCompleted), null, null));
                }
                case TASK_DELETE:
                    // Still there → the task is kept (no data loss); gone → the intent holds. Either way the entry goes.
                    return task == null ? Decision.DROP : new Decision(Decision.Kind.DROP, Collections.<Replacement>emptyList(), true);
                case LIST_UPDATE: {
                    TasksWire.Operation.ListUpdate update = (TasksWire.Operation.ListUpdate) operation;
                    Object[] base = listBase(entry.baseSnapshot);
                    if (list == null || base == null) return Decision.EXPLICIT;
                    String title = merge((String) base[0], update.title, list.title);
                    Integer sortOrder = merge((Integer) base[1], update.sortOrder, list.sortOrder);
                    if (title == null || sortOrder == null) return Decision.EXPLICIT;
                    if (title.equals(list.title) && sortOrder == list.sortOrder) return Decision.DROP;
                    return Decision.replace(new Replacement(TasksWire.Operation.listUpdate(update.listId, list.revision, title, sortOrder), null,
                            TasksCloudLedger.listBase(list.title, list.sortOrder)));
                }
                case LIST_DELETE: {
                    TasksWire.Operation.ListDelete delete = (TasksWire.Operation.ListDelete) operation;
                    if (list == null) return Decision.DROP;
                    // A rename elsewhere does not change the intent; the members fence it was decided under stays, so a
                    // changed set of tasks still asks the user (members_changed).
                    TasksWire.Operation resealed = delete.baseMembersRevision == null
                            ? TasksWire.Operation.listDelete(delete.listId, list.revision)
                            : TasksWire.Operation.listDelete(delete.listId, list.revision, delete.baseMembersRevision);
                    return Decision.replace(new Replacement(resealed, null, null));
                }
                case INVITATION_REQUEST: {
                    TasksWire.Operation.InvitationRequest request = (TasksWire.Operation.InvitationRequest) operation;
                    if (task == null || task.viewerRole != TasksWire.ViewerRole.OWNER || task.isCompleted) {
                        return automaticResend ? Decision.DROP : Decision.EXPLICIT;
                    }
                    // Someone has the task now whom this pick was not made to replace (assigned from another device
                    // meanwhile): sending it would end their access without the owner's word.
                    String live = TasksAssigneeDisplay.isLive(task.assignment, nowSeconds) ? task.assignment.invitationId : null;
                    if (live != null && !live.equals(entry.baseSnapshot)) {
                        return automaticResend ? Decision.DROP : Decision.EXPLICIT;
                    }
                    // A new epoch (a new invitation id), as the explicit reapply of the pick would be.
                    return Decision.replace(new Replacement(TasksWire.Operation.invitationRequest(request.taskId, task.revision,
                            TasksWire.newId(), request.recipientTgId), null, entry.baseSnapshot));
                }
                case INVITATION_REVOKE: {
                    TasksWire.Operation.InvitationRevoke revoke = (TasksWire.Operation.InvitationRevoke) operation;
                    // A task that is gone, or whose epoch ended or changed, already holds the intent.
                    if (task == null || task.viewerRole != TasksWire.ViewerRole.OWNER || task.assignment == null
                            || !task.assignment.invitationId.equals(revoke.invitationId)
                            || (task.assignment.state != TasksWire.AssignmentState.PENDING
                            && task.assignment.state != TasksWire.AssignmentState.ACCEPTED)) {
                        return Decision.DROP;
                    }
                    return Decision.replace(new Replacement(TasksWire.Operation.invitationRevoke(revoke.taskId, task.revision,
                            revoke.invitationId), null, null));
                }
                default:
                    return Decision.EXPLICIT;
            }
        } catch (TasksWire.Invalid refused) {
            return Decision.EXPLICIT;
        }
    }

    /**
     * field_changed F: a field outside F that the server does not already hold as set is patched again against the
     * server's current value; a field the server now holds as set is settled; the rest of F stays explicit, as a patch
     * of its own with its original expectation.
     */
    private static Decision patch(TasksCloudLedger.Entry entry, TasksWire.Operation.TaskPatch patch, TasksWire.TaskItem task) {
        if (task == null || task.viewerRole != TasksWire.ViewerRole.OWNER) return Decision.EXPLICIT;
        Set<TasksWire.ContentField> conflicting = entry.conflictFields.isEmpty() ? EnumSet.noneOf(TasksWire.ContentField.class)
                : EnumSet.copyOf(entry.conflictFields);
        Map<TasksWire.ContentField, Object> autoSet = new LinkedHashMap<>(), autoExpect = new LinkedHashMap<>(),
                keptSet = new LinkedHashMap<>(), keptExpect = new LinkedHashMap<>();
        for (Map.Entry<TasksWire.ContentField, Object> field : patch.set.entrySet()) {
            Object current = field.getKey().valueIn(task.content);
            if (Objects.equals(current, field.getValue())) continue;
            if (conflicting.contains(field.getKey())) {
                keptSet.put(field.getKey(), field.getValue());
                keptExpect.put(field.getKey(), patch.expect.get(field.getKey()));
            } else {
                autoSet.put(field.getKey(), field.getValue());
                autoExpect.put(field.getKey(), current);
            }
        }
        if (autoSet.isEmpty() && keptSet.isEmpty()) return Decision.DROP;
        // Nothing left to send automatically: the conflict is the user's, as it is.
        if (autoSet.isEmpty()) return Decision.EXPLICIT;
        Replacement automatic = new Replacement(TasksWire.Operation.taskPatch(patch.taskId, autoSet, autoExpect), null, null);
        if (keptSet.isEmpty()) return Decision.replace(automatic);
        TasksWire.Operation.TaskPatch kept = TasksWire.Operation.taskPatch(patch.taskId, keptSet, keptExpect);
        return Decision.replace(automatic, new Replacement(kept, kept.fields(), null));
    }

    /** Three-way: an unchanged side takes the other's value; the same new value on both sides agrees; else null. */
    static <T> T merge(T base, T mine, T theirs) {
        if (Objects.equals(mine, base)) return theirs;
        if (Objects.equals(theirs, base) || Objects.equals(theirs, mine)) return mine;
        return null;
    }

    /** The owner's live assignment epoch on the server row: accepted, or pending and not yet expired. */
    static String ownerCompletionInvitationId(TasksWire.Assignment assignment, long nowSeconds) {
        if (assignment == null) return null;
        if (assignment.state == TasksWire.AssignmentState.ACCEPTED) return assignment.invitationId;
        if (assignment.state != TasksWire.AssignmentState.PENDING) return null;
        return TasksWire.epochSeconds(assignment.expiresAt) <= nowSeconds ? null : assignment.invitationId;
    }

    /** [title, sort_order] of a stored list base (TasksCloudLedger.listBase), or null when absent or unreadable. */
    static Object[] listBase(String stored) {
        if (stored == null) return null;
        try {
            Object parsed = TasksWireJson.parse(stored, 65536);
            if (!(parsed instanceof List) || ((List<?>) parsed).size() != 2) return null;
            List<?> values = (List<?>) parsed;
            if (!(values.get(0) instanceof String) || !(values.get(1) instanceof TasksWireJson.Num)) return null;
            return new Object[]{values.get(0), Integer.parseInt(((TasksWireJson.Num) values.get(1)).literal)};
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
