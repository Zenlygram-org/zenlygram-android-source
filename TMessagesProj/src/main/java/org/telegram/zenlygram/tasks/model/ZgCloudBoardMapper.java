/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * The account's Cloud tasks ({@link TasksCloudState}, the repository's immutable view) as a {@link ZgBoard}: iOS
 * TasksCloudBoardState's decisions on the Android engine's states. Pure Java (JVM tests); the source gathers the
 * inputs.
 *
 * <p><b>Read-only</b> (cached content stays, nothing can be queued): the server fenced the account's consent
 * (CONSENT_FENCED), or the consent gate is FORBIDDEN (consent off, or withdrawn).
 *
 * <p><b>Gate</b> (no content, a recovery card): the store cannot be opened; the account is no longer signed in; or the
 * account is not linked and nothing is cached. Never under a read-only hold, and never while consent only waits for
 * the link after the cut-over: Cloud is then where the user works, and changes queue on this device.
 *
 * <p><b>Banner</b>, first match: the read-only reason; while the gate WAITS, "preparing" when linked and "not
 * confirmed yet" after the cut-over; then the engine's status (paused / session / offline / unavailable / loading).
 *
 * <p><b>Rows</b>: the Cloud Inbox (own tasks without a list) first, then the lists in their order, then the tasks
 * assigned to this account; inside a group by the task's sort order. An own task names its assignee — the assignment
 * (the pick is the invitation; iOS wf15) with the owner's state word, or the contact of an older owner-only note that
 * was never sent. A task assigned to this account may only be completed or left, under its accepted invitation; an
 * invitation whose automatic accept is on its way is a provisional row after them, built from its preview.
 */
public final class ZgCloudBoardMapper {

    private ZgCloudBoardMapper() {
    }

    /** What the board depends on besides the repository's state. */
    public static final class Inputs {
        /** The repository's view; null when the account has no repository (signed out). */
        public TasksCloudState state;
        /** The consent gate of Cloud Tasks (ZenlyTasksSyncOwner.permission). */
        public TasksCloudGate.Permission permission = TasksCloudGate.Permission.FORBIDDEN;
        /** The account's cut-over (ZenlyTasksSyncOwner.cutOver). */
        public TasksCutOver cutOver = TasksCutOver.NONE;
        /** The account is linked to Zenlygram (ZenlyPro snapshot). */
        public boolean linked;
        /** The account's consent was withdrawn (the wording of a FORBIDDEN gate only). */
        public boolean consentWithdrawn;
        /** A copy of the device tasks would queue at least one row now. */
        public boolean deviceRowsToCopy;
        /** The server's time as the device estimates it (Unix seconds; 0 = unknown): an invitation's expiry. */
        public long nowSeconds;
    }

    public static ZgBoard map(Inputs in) {
        final TasksCloudState state = in.state;
        final TasksCloudState.Status status = state == null ? TasksCloudState.Status.CLOSED : state.status;
        final boolean hasContent = state != null && !state.contentFree();
        final boolean complete = state != null && state.shadowComplete;
        final TasksCutOver cutOver = ZgTasksRouting.effectiveCutOver(in.permission, in.cutOver);
        final ZgBoard.ReadOnly readOnly = readOnly(status, in.permission, in.consentWithdrawn);
        final boolean waiting = readOnly == null && in.permission == TasksCloudGate.Permission.WAITING;
        final boolean cutOverStarted = cutOver.kind != TasksCutOver.Kind.NONE;
        final ZgBoard.Gate gate = gate(status, hasContent, in.linked, readOnly, waiting && cutOverStarted);

        final ZgBoard.Builder board = new ZgBoard.Builder(ZgTasksScope.CLOUD).complete(complete).gate(gate).readOnly(readOnly);
        if (gate != null || state == null) {
            // Cached content is never surfaced behind a gate.
            return board.build();
        }
        board.banner(banner(status, complete, in.linked, readOnly, waiting, cutOverStarted));

        final ArrayList<ZgBoardList> lists = new ArrayList<>(state.lists.size());
        final HashMap<String, Integer> ranks = new HashMap<>();
        for (TasksCloudState.ListView list : state.lists) {
            ranks.put(list.id, lists.size());
            lists.add(new ZgBoardList(list.id, list.title, false, sync(list.pending)));
        }
        final ArrayList<Ranked> ranked = new ArrayList<>(state.tasks.size());
        for (int i = 0; i < state.tasks.size(); i++) {
            final TasksCloudState.TaskView task = state.tasks.get(i);
            ranked.add(new Ranked(task, groupRank(task, ranks), i));
        }
        Collections.sort(ranked, (a, b) -> {
            if (a.rank != b.rank) {
                return a.rank < b.rank ? -1 : 1;
            }
            final int x = a.task.content.sortOrder;
            final int y = b.task.content.sortOrder;
            if (x != y) {
                return x < y ? -1 : 1;
            }
            return Integer.compare(a.offset, b.offset);
        });
        final ArrayList<ZgBoardTask> tasks = new ArrayList<>(ranked.size() + state.provisional.size());
        for (Ranked r : ranked) {
            tasks.add(task(r.task, readOnly != null, in.nowSeconds));
        }
        // The provisional rows of "Assigned to me", after the accepted ones, in the snapshot's order.
        final HashSet<String> provisionalIds = new HashSet<>();
        for (TasksWire.InvitationItem invitation : state.provisional) {
            provisionalIds.add(invitation.id);
            tasks.add(new ZgBoardTask.Builder(invitation.taskId, invitation.title).due(invitation.dueAt)
                .sharedBy(invitation.ownerTgId).provisional().build());
        }

        int waitingCount = 0;
        int attention = 0;
        for (TasksCloudState.Pending pending : state.pending) {
            if (pending.state == TasksCloudState.PendingState.HELD) {
                continue; // a delete inside its Undo window is not queued yet
            }
            if (isAutomaticAccept(pending, provisionalIds) && !needsAttention(pending.state)) {
                continue; // the device's own accept of a contact's task: not a change the user made
            }
            if (needsAttention(pending.state)) {
                attention++;
            } else {
                waitingCount++;
            }
        }
        int invitations = 0;
        for (TasksWire.InvitationItem invitation : state.invitations) {
            if (!expired(invitation.expiresAt, in.nowSeconds)) {
                invitations++;
            }
        }
        final ArrayList<ZgBoard.Notice> notices = new ArrayList<>(state.notices.size());
        for (TasksCloudState.Notice notice : state.notices) {
            notices.add(new ZgBoard.Notice(notice.id, noticeKind(notice.kind)));
        }
        return board.lists(lists).tasks(tasks).unsent(waitingCount, attention).invitations(invitations).notices(notices)
            // Copying queues creates: never offered while the board is read-only, unlinked or still loading.
            .offersDeviceCopy(in.deviceRowsToCopy && in.linked && complete && readOnly == null)
            .build();
    }

    static ZgBoard.ReadOnly readOnly(TasksCloudState.Status status, TasksCloudGate.Permission permission, boolean withdrawn) {
        if (status == TasksCloudState.Status.CONSENT_FENCED) {
            return ZgBoard.ReadOnly.CONSENT_FENCED;
        }
        if (permission == null || permission == TasksCloudGate.Permission.FORBIDDEN) {
            return withdrawn ? ZgBoard.ReadOnly.WITHDRAWN : ZgBoard.ReadOnly.CONSENT_OFF;
        }
        return null;
    }

    static ZgBoard.Gate gate(TasksCloudState.Status status, boolean hasContent, boolean linked, ZgBoard.ReadOnly readOnly,
                             boolean queuesWhileWaiting) {
        if (status == TasksCloudState.Status.STORAGE_FAILED) {
            return ZgBoard.Gate.STORAGE;
        }
        if (status == TasksCloudState.Status.CLOSED) {
            return ZgBoard.Gate.SIGNED_OUT;
        }
        if (readOnly != null || queuesWhileWaiting) {
            return null;
        }
        // Content already on this device (an unlinked or paused account) stays visible with a paused banner instead.
        return !linked && !hasContent ? ZgBoard.Gate.UNLINKED : null;
    }

    static ZgBoard.Banner banner(TasksCloudState.Status status, boolean complete, boolean linked, ZgBoard.ReadOnly readOnly,
                                 boolean waiting, boolean cutOverStarted) {
        if (readOnly != null) {
            switch (readOnly) {
                case WITHDRAWN:
                    return ZgBoard.Banner.WITHDRAWN;
                case CONSENT_FENCED:
                    return ZgBoard.Banner.CONSENT_FENCED;
                default:
                    return ZgBoard.Banner.CONSENT_OFF;
            }
        }
        if (waiting) {
            if (linked) {
                return ZgBoard.Banner.PREPARING;
            }
            if (cutOverStarted) {
                return ZgBoard.Banner.NOT_LINKED_YET;
            }
            // Not cut over: the usual recovery (link) below.
        }
        switch (status) {
            case UNLINKED_PAUSED:
            case NOT_LINKED:
                return ZgBoard.Banner.PAUSED;
            case SESSION_INVALID:
                return ZgBoard.Banner.SESSION;
            case OFFLINE:
                return ZgBoard.Banner.OFFLINE;
            case UNAVAILABLE:
                return ZgBoard.Banner.UNAVAILABLE;
            case CONSENT_REQUIRED:
                // The gate is open again and the store has not resumed yet (the sync owner resumes it at once).
                return ZgBoard.Banner.PREPARING;
            default:
                if (!linked) {
                    return ZgBoard.Banner.PAUSED;
                }
                return complete ? null : ZgBoard.Banner.LOADING;
        }
    }

    private static ZgBoardTask task(TasksCloudState.TaskView task, boolean boardReadOnly, long nowSeconds) {
        final TasksWire.Content content = task.content;
        Long completedAt = null;
        if (task.completed && task.server != null && task.server.completedAt != null) {
            completedAt = seconds(task.server.completedAt);
        }
        final ZgBoardTask.Builder row = new ZgBoardTask.Builder(task.id, content.title)
            .due(content.dueAt)
            // Completed here and not confirmed yet: no time is known, and it belongs on top of Completed.
            .completed(task.completed, completedAt, task.pending != null)
            // A request on its way is said by the assignee's state word ("Sending…"), not a second time by the sync line.
            .sync(isAssignmentOnItsWay(task.pending) ? ZgBoardTask.Sync.SYNCED : sync(task.pending));
        if (task.owner) {
            row.list(content.listId);
            final String assigneeTgId = task.assigneeTgId();
            if (assigneeTgId != null) {
                final TasksWire.Annotation note = content.annotation;
                row.assignment(assigneeTgId, note != null && note.telegramUserId.equals(assigneeTgId) ? note.displayName : null,
                    task.assigneeState(nowSeconds));
            }
            if (!boardReadOnly) {
                row.ownerRights();
            }
        } else {
            // An assignee never sees the owner's list or the owner's other assignees.
            row.sharedBy(task.server != null ? task.server.ownerTgId : "");
            final boolean accepted = task.assignment != null && task.assignment.state == TasksWire.AssignmentState.ACCEPTED
                && task.liveInvitationId != null;
            if (!boardReadOnly) {
                if (accepted) {
                    row.recipientRights();
                } else {
                    row.accessInactive(true);
                }
            }
        }
        return row.build();
    }

    /** The Cloud Inbox first, then the lists in their order, then the tasks shared with this account. */
    private static int groupRank(TasksCloudState.TaskView task, HashMap<String, Integer> listRanks) {
        if (!task.owner) {
            return Integer.MAX_VALUE;
        }
        if (task.content.listId == null) {
            return -1;
        }
        final Integer rank = listRanks.get(task.content.listId);
        return rank != null ? rank : Integer.MAX_VALUE - 1;
    }

    /** The row's sync line for the latest unconfirmed change of the task or list (null = everything is confirmed). */
    public static ZgBoardTask.Sync sync(TasksCloudState.Pending pending) {
        if (pending == null || pending.state == TasksCloudState.PendingState.HELD) {
            return ZgBoardTask.Sync.SYNCED;
        }
        if (needsAttention(pending.state)) {
            return ZgBoardTask.Sync.NEEDS_ATTENTION;
        }
        // Handed to the network at least once: it may already be applied, so it is never called "not sent".
        return pending.sent || pending.state == TasksCloudState.PendingState.SENDING ? ZgBoardTask.Sync.CONFIRMING : ZgBoardTask.Sync.NOT_SENT;
    }

    /** States the repository does not move past on its own: the user retries, resends or discards (Unsent Changes). */
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

    /** The task's latest unconfirmed change is its assignment request, on its way without a problem. */
    private static boolean isAssignmentOnItsWay(TasksCloudState.Pending pending) {
        return pending != null && pending.kind == TasksWire.OperationKind.INVITATION_REQUEST && !needsAttention(pending.state)
            && pending.state != TasksCloudState.PendingState.HELD;
    }

    /** The outbox entry is the contact rule's own accept of one of the provisional rows. */
    private static boolean isAutomaticAccept(TasksCloudState.Pending pending, HashSet<String> provisionalIds) {
        if (!(pending.draft instanceof TasksWire.Operation.InvitationRespond)) {
            return false;
        }
        final TasksWire.Operation.InvitationRespond respond = (TasksWire.Operation.InvitationRespond) pending.draft;
        return respond.decision == TasksWire.Decision.ACCEPT && provisionalIds.contains(respond.invitationId);
    }

    private static boolean expired(String expiresAt, long nowSeconds) {
        final Long at = seconds(expiresAt);
        return nowSeconds > 0 && at != null && at <= nowSeconds;
    }

    private static Long seconds(String instant) {
        if (instant == null) {
            return null;
        }
        try {
            return TasksWire.epochSeconds(instant);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static ZgBoard.Notice.Kind noticeKind(TasksCloudState.Notice.Kind kind) {
        switch (kind) {
            case ACCESS_LOST:
                return ZgBoard.Notice.Kind.ACCESS_LOST;
            case NOT_FOUND:
                return ZgBoard.Notice.Kind.NOT_FOUND;
            case REMOVED_IN_CLOUD:
                return ZgBoard.Notice.Kind.REMOVED_IN_CLOUD;
            case DELETE_KEPT:
                return ZgBoard.Notice.Kind.DELETE_KEPT;
            default:
                return ZgBoard.Notice.Kind.DEPENDENCY_DROPPED;
        }
    }

    /**
     * The sort order of a task added at the end of a list (null = the Cloud Inbox): one past the last own task there,
     * 0 for an empty list (iOS nextSortOrder).
     */
    public static int nextTaskOrder(TasksCloudState state, String listId) {
        int max = -1;
        if (state != null) {
            for (TasksCloudState.TaskView task : state.tasks) {
                final String other = task.content.listId;
                if (task.owner && (listId == null ? other == null : listId.equals(other))) {
                    max = Math.max(max, task.content.sortOrder);
                }
            }
        }
        return max == Integer.MAX_VALUE ? max : max + 1;
    }

    /** The sort order of a new list: after the last one. */
    public static int nextListOrder(TasksCloudState state) {
        int max = -1;
        if (state != null) {
            for (TasksCloudState.ListView list : state.lists) {
                max = Math.max(max, list.sortOrder);
            }
        }
        return max == Integer.MAX_VALUE ? max : max + 1;
    }

    private static final class Ranked {
        final TasksCloudState.TaskView task;
        final int rank;
        final int offset;

        Ranked(TasksCloudState.TaskView task, int rank, int offset) {
            this.task = task;
            this.rank = rank;
            this.offset = offset;
        }
    }
}
