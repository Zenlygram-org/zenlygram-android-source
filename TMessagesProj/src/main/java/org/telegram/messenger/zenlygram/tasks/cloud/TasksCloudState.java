package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable UI view of one account's cloud Tasks: server shadow with the optimistic overlay of the durable outbox
 * applied on top. The shadow itself is never modified by the overlay.
 */
public final class TasksCloudState {
    /**
     * R4: CONSENT_REQUIRED — the consent gate (TasksCloudGate) is not ALLOWED or the account's consent was withdrawn, so
     * no Tasks request is made; CONSENT_FENCED — the server answered tasks_consent_required (x-consent-fence) and the
     * account backs off. Both keep the cached content visible; nothing is discarded.
     */
    public enum Status { LOADING, NOT_LINKED, READY, OFFLINE, UNLINKED_PAUSED, SESSION_INVALID, UNAVAILABLE, STORAGE_FAILED, CLOSED,
        CONSENT_REQUIRED, CONSENT_FENCED }

    public enum PendingState { HELD, QUEUED, SENDING, BACKOFF, STALLED, WAITING, BLOCKED_CREATION, INVALID, CONFLICT, QUARANTINED, NOT_FOUND, WRONG_PRINCIPAL }

    public static final class ListView {
        public final String id;
        /** Null while the list.create is unacknowledged or not yet synced. */
        public final TasksWire.ListItem server;
        public final String title;
        public final int sortOrder;
        public final Pending pending;

        ListView(String id, TasksWire.ListItem server, String title, int sortOrder, Pending pending) {
            this.id = id;
            this.server = server;
            this.title = title;
            this.sortOrder = sortOrder;
            this.pending = pending;
        }
    }

    public static final class TaskView {
        public final String id;
        /** Null while the task.create is unacknowledged or not yet synced (then the viewer is the owner). */
        public final TasksWire.TaskItem server;
        public final TasksWire.Content content;
        public final boolean completed;
        public final boolean owner;
        /** Server assignment as last synced (nullable). */
        public final TasksWire.Assignment assignment;
        /** Current live invitation epoch including own pending request/revoke (nullable). */
        public final String liveInvitationId;
        public final Pending pending;
        /**
         * Assignments: the recipient of this device's own invitation.request that the shadow does not show yet — queued,
         * in flight, or acknowledged and not pulled ({@link #requestAcknowledged}); null when there is none.
         */
        public final String requestRecipientTgId;
        public final boolean requestAcknowledged;
        /** Assignments: the recipient of a deferred assignment waiting for its automatic re-send (nullable). */
        public final String deferredRecipientTgId;

        TaskView(String id, TasksWire.TaskItem server, TasksWire.Content content, boolean completed, boolean owner,
                 TasksWire.Assignment assignment, String liveInvitationId, Pending pending) {
            this(id, server, content, completed, owner, assignment, liveInvitationId, pending, null, false, null);
        }

        TaskView(String id, TasksWire.TaskItem server, TasksWire.Content content, boolean completed, boolean owner,
                 TasksWire.Assignment assignment, String liveInvitationId, Pending pending, String requestRecipientTgId,
                 boolean requestAcknowledged, String deferredRecipientTgId) {
            this.id = id;
            this.server = server;
            this.content = content;
            this.completed = completed;
            this.owner = owner;
            this.assignment = assignment;
            this.liveInvitationId = liveInvitationId;
            this.pending = pending;
            this.requestRecipientTgId = requestRecipientTgId;
            this.requestAcknowledged = requestAcknowledged;
            this.deferredRecipientTgId = deferredRecipientTgId;
        }

        /** The owner's state word for this task's assignee at {@code nowSeconds}; null = no assignee. */
        public TasksAssigneeDisplay.State assigneeState(long nowSeconds) {
            if (!owner) return null;
            return TasksAssigneeDisplay.resolve(new TasksAssigneeDisplay.Inputs(assignment, requestRecipientTgId != null && !requestAcknowledged,
                    requestRecipientTgId != null && requestAcknowledged, deferredRecipientTgId != null, completed,
                    content.annotation != null, removalQueued(), nowSeconds));
        }

        /**
         * The synced assignment is live (pending or accepted) and the overlay shows no live epoch: this device's own
         * Remove is in the outbox, or acknowledged and not pulled yet.
         */
        public boolean removalQueued() {
            return owner && assignment != null && liveInvitationId == null && requestRecipientTgId == null
                    && (assignment.state == TasksWire.AssignmentState.PENDING || assignment.state == TasksWire.AssignmentState.ACCEPTED);
        }

        /**
         * Whom the assignee row names: the request on its way, the deferred pick, the synced assignment's recipient, the
         * legacy note's contact — in that order; null when the task has no assignee.
         */
        public String assigneeTgId() {
            if (!owner) return null;
            if (requestRecipientTgId != null) return requestRecipientTgId;
            if (deferredRecipientTgId != null) return deferredRecipientTgId;
            if (assignment != null) return assignment.recipientTgId;
            return content.annotation != null ? content.annotation.telegramUserId : null;
        }
    }

    public static final class Pending {
        public final String mutationId;
        public final TasksWire.OperationKind kind;
        public final String resourceId;
        public final PendingState state;
        /** Wire/transport failure code (allowlisted), nullable. */
        public final String error;
        public final TasksWire.ConflictReason reason;
        public final boolean sent;
        /** The user's operation (for explicit copy/repair); null while HELD. */
        public final TasksWire.Operation draft;
        /** R4: a create of pre-existing local data (automatic migration, batch route only) rather than an own edit. */
        public final boolean migration;
        /** R4 field_changed conflict: the fields changed elsewhere, in content order; empty otherwise. */
        public final List<TasksWire.ContentField> conflictFields;

        Pending(String mutationId, TasksWire.OperationKind kind, String resourceId, PendingState state, String error,
                TasksWire.ConflictReason reason, boolean sent, TasksWire.Operation draft, boolean migration,
                List<TasksWire.ContentField> conflictFields) {
            this.mutationId = mutationId;
            this.kind = kind;
            this.resourceId = resourceId;
            this.state = state;
            this.error = error;
            this.reason = reason;
            this.sent = sent;
            this.draft = draft;
            this.migration = migration;
            this.conflictFields = conflictFields;
        }
    }

    public static final class Notice {
        /**
         * R4: REMOVED_IN_CLOUD — an acknowledged create the cloud no longer shows; DELETE_KEPT — a delete that met a newer
         * version was dropped and the task kept.
         */
        public enum Kind { ACCESS_LOST, NOT_FOUND, DEPENDENCY_DROPPED, REMOVED_IN_CLOUD, DELETE_KEPT }

        public final long id;
        public final Kind kind;
        public final String resourceId;

        Notice(long id, Kind kind, String resourceId) {
            this.id = id;
            this.kind = kind;
            this.resourceId = resourceId;
        }
    }

    public final Status status;
    public final boolean syncing;
    public final boolean shadowComplete;
    public final long ownTelegramId;
    public final String principal;
    public final String serverTime;
    public final List<ListView> lists;
    public final List<TaskView> tasks;
    public final List<TasksWire.InvitationItem> invitations;
    /**
     * Assignments: the pending invitations whose automatic accept (the sender is a contact) is queued, in flight or
     * awaiting the server's proof. Each is a provisional "Assigned to me" row built from the invitation preview, not an
     * Invitations entry; it leaves with the pull that brings the task, or with a quiet drop (the invitation, if still
     * pending, is then back in {@link #invitations}).
     */
    public final List<TasksWire.InvitationItem> provisional;
    public final List<TasksWire.BlockItem> blocks;
    public final List<Pending> pending;
    public final List<Notice> notices;
    /** Unsent own edits / migration creates in the outbox (every state but acknowledged) — the logout and withdrawal counts. */
    public final int unsentUserChanges, unsentMigrationChanges;

    TasksCloudState(Status status, boolean syncing, boolean shadowComplete, long ownTelegramId, String principal,
                    String serverTime, List<ListView> lists, List<TaskView> tasks, List<TasksWire.InvitationItem> invitations,
                    List<TasksWire.BlockItem> blocks, List<Pending> pending, List<Notice> notices) {
        this(status, syncing, shadowComplete, ownTelegramId, principal, serverTime, lists, tasks, invitations, blocks, pending, notices, 0, 0);
    }

    TasksCloudState(Status status, boolean syncing, boolean shadowComplete, long ownTelegramId, String principal,
                    String serverTime, List<ListView> lists, List<TaskView> tasks, List<TasksWire.InvitationItem> invitations,
                    List<TasksWire.BlockItem> blocks, List<Pending> pending, List<Notice> notices, int unsentUserChanges,
                    int unsentMigrationChanges) {
        this(status, syncing, shadowComplete, ownTelegramId, principal, serverTime, lists, tasks, invitations, blocks, pending, notices,
                unsentUserChanges, unsentMigrationChanges, Collections.<TasksWire.InvitationItem>emptyList());
    }

    TasksCloudState(Status status, boolean syncing, boolean shadowComplete, long ownTelegramId, String principal,
                    String serverTime, List<ListView> lists, List<TaskView> tasks, List<TasksWire.InvitationItem> invitations,
                    List<TasksWire.BlockItem> blocks, List<Pending> pending, List<Notice> notices, int unsentUserChanges,
                    int unsentMigrationChanges, List<TasksWire.InvitationItem> provisional) {
        this.provisional = Collections.unmodifiableList(new ArrayList<>(provisional));
        this.status = status;
        this.syncing = syncing;
        this.shadowComplete = shadowComplete;
        this.ownTelegramId = ownTelegramId;
        this.principal = principal;
        this.serverTime = serverTime;
        this.lists = Collections.unmodifiableList(new ArrayList<>(lists));
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        this.invitations = Collections.unmodifiableList(new ArrayList<>(invitations));
        this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        this.pending = Collections.unmodifiableList(new ArrayList<>(pending));
        this.notices = Collections.unmodifiableList(new ArrayList<>(notices));
        this.unsentUserChanges = unsentUserChanges;
        this.unsentMigrationChanges = unsentMigrationChanges;
    }

    static TasksCloudState empty(Status status, long ownTelegramId) {
        List<ListView> none = Collections.emptyList();
        return new TasksCloudState(status, false, false, ownTelegramId, null, null, none, Collections.<TaskView>emptyList(),
                Collections.<TasksWire.InvitationItem>emptyList(), Collections.<TasksWire.BlockItem>emptyList(),
                Collections.<Pending>emptyList(), Collections.<Notice>emptyList());
    }

    /** Carries no cloud rows, pending entries or notices (a pure status placeholder). */
    public boolean contentFree() {
        return lists.isEmpty() && tasks.isEmpty() && invitations.isEmpty() && provisional.isEmpty() && blocks.isEmpty()
                && pending.isEmpty() && notices.isEmpty();
    }

    /** The provisional row of an invitation id, or null. */
    public TasksWire.InvitationItem provisional(String invitationId) {
        for (TasksWire.InvitationItem invitation : provisional) if (invitation.id.equals(invitationId)) return invitation;
        return null;
    }

    /**
     * Assignments: the owner's assignments that are pending and unexpired on the visible board — the shadow's plus this
     * device's own requests the shadow does not show yet (the client pacer's fourth limit).
     */
    public int pendingAssignments(long nowSeconds) {
        int count = 0;
        for (TaskView task : tasks) {
            if (!task.owner) continue;
            if (task.requestRecipientTgId != null) count++;
            else if (task.assignment != null && task.assignment.state == TasksWire.AssignmentState.PENDING
                    && TasksWire.epochSeconds(task.assignment.expiresAt) > nowSeconds) count++;
        }
        return count;
    }

    TasksCloudState withStatus(Status newStatus, boolean newSyncing) {
        return new TasksCloudState(newStatus, newSyncing, shadowComplete, ownTelegramId, principal, serverTime, lists, tasks,
                invitations, blocks, pending, notices, unsentUserChanges, unsentMigrationChanges, provisional);
    }

    public ListView list(String id) {
        for (ListView list : lists) if (list.id.equals(id)) return list;
        return null;
    }

    public TaskView task(String id) {
        for (TaskView task : tasks) if (task.id.equals(id)) return task;
        return null;
    }

    public TasksWire.InvitationItem invitation(String id) {
        for (TasksWire.InvitationItem invitation : invitations) if (invitation.id.equals(id)) return invitation;
        return null;
    }

    // ---- derivation (pure) ----

    private static final class MutableList {
        final String id;
        final TasksWire.ListItem server;
        String title;
        int sortOrder;

        MutableList(String id, TasksWire.ListItem server, String title, int sortOrder) {
            this.id = id;
            this.server = server;
            this.title = title;
            this.sortOrder = sortOrder;
        }
    }

    private static final class MutableTask {
        final String id;
        final TasksWire.TaskItem server;
        TasksWire.Content content;
        boolean completed;
        final boolean owner;
        String liveInvitationId;
        String requestRecipient;
        boolean requestAcked;

        MutableTask(String id, TasksWire.TaskItem server, TasksWire.Content content, boolean completed, boolean owner, String live) {
            this.id = id;
            this.server = server;
            this.content = content;
            this.completed = completed;
            this.owner = owner;
            this.liveInvitationId = live;
        }
    }

    static String liveInvitation(TasksWire.Assignment assignment) {
        if (assignment == null) return null;
        return assignment.state == TasksWire.AssignmentState.PENDING || assignment.state == TasksWire.AssignmentState.ACCEPTED
                ? assignment.invitationId : null;
    }

    static TasksCloudState derive(Status status, boolean syncing, boolean shadowComplete, long ownTelegramId, String principal,
                                  String serverTime, List<TasksWire.Projection> shadow, List<TasksCloudLedger.Entry> outbox,
                                  List<Notice> notices) {
        return derive(status, syncing, shadowComplete, ownTelegramId, principal, serverTime, shadow, outbox, notices,
                Collections.<TasksCloudLedger.Deferral>emptyList(), 0, Collections.<String>emptySet());
    }

    /**
     * {@code deferrals}: the assignments waiting outside the outbox ({@code undecodedDeferrals} more could not be read
     * and still count as unsent); {@code autoAcceptIds}: the invitations whose automatic accept is in the outbox.
     */
    static TasksCloudState derive(Status status, boolean syncing, boolean shadowComplete, long ownTelegramId, String principal,
                                  String serverTime, List<TasksWire.Projection> shadow, List<TasksCloudLedger.Entry> outbox,
                                  List<Notice> notices, List<TasksCloudLedger.Deferral> deferrals, int undecodedDeferrals,
                                  Set<String> autoAcceptIds) {
        LinkedHashMap<String, MutableList> lists = new LinkedHashMap<>();
        LinkedHashMap<String, MutableTask> tasks = new LinkedHashMap<>();
        LinkedHashMap<String, TasksWire.InvitationItem> invitations = new LinkedHashMap<>();
        LinkedHashMap<String, TasksWire.BlockItem> blocks = new LinkedHashMap<>();
        HashMap<String, String> shadowRevisions = new HashMap<>();
        for (TasksWire.Projection item : shadow) {
            if (item instanceof TasksWire.ListItem) {
                TasksWire.ListItem list = (TasksWire.ListItem) item;
                lists.put(list.id, new MutableList(list.id, list, list.title, list.sortOrder));
                shadowRevisions.put(TasksCloudLedger.listChain(list.id), list.revision);
            } else if (item instanceof TasksWire.TaskItem) {
                TasksWire.TaskItem task = (TasksWire.TaskItem) item;
                tasks.put(task.id, new MutableTask(task.id, task, task.content, task.isCompleted,
                        task.viewerRole == TasksWire.ViewerRole.OWNER, liveInvitation(task.assignment)));
                shadowRevisions.put(TasksCloudLedger.taskChain(task.id), task.revision);
            } else if (item instanceof TasksWire.InvitationItem) {
                invitations.put(item.id, (TasksWire.InvitationItem) item);
            } else if (item instanceof TasksWire.BlockItem) {
                blocks.put(item.id, (TasksWire.BlockItem) item);
            }
        }
        LinkedHashMap<String, TasksWire.InvitationItem> shadowInvitations = new LinkedHashMap<>(invitations);
        HashMap<String, Pending> latestByChain = new HashMap<>();
        ArrayList<Pending> pending = new ArrayList<>();
        // One pass over key sets (not all earlier pairs): a 5000-create migration outbox derives in O(N).
        TasksCloudLedger.Dependencies dependencies = new TasksCloudLedger.Dependencies();
        int unsentUser = 0, unsentMigration = 0;
        for (TasksCloudLedger.Entry entry : outbox) {
            boolean ownPrincipal = entry.principal.equals(principal);
            if (!TasksCloudSchema.ACKED.equals(entry.state)) {
                Pending view = new Pending(entry.mutationId, entry.operation.kind, entry.resourceId,
                        displayState(entry, ownPrincipal, dependencies), entry.error, entry.reason, entry.sent,
                        TasksCloudSchema.HELD.equals(entry.state) ? null : entry.operation, entry.migration(), entry.conflictFields);
                pending.add(view);
                latestByChain.put(entry.chain, view);
                if (entry.migration()) unsentMigration++;
                else unsentUser++;
            }
            dependencies.add(entry);
            if (ownPrincipal && overlays(entry, shadowRevisions)) {
                apply(entry.operation, lists, tasks, invitations, blocks);
                if (entry.operation instanceof TasksWire.Operation.InvitationRequest) {
                    TasksWire.Operation.InvitationRequest request = (TasksWire.Operation.InvitationRequest) entry.operation;
                    MutableTask task = tasks.get(request.taskId);
                    if (task != null) {
                        task.requestRecipient = request.recipientTgId;
                        task.requestAcked = TasksCloudSchema.ACKED.equals(entry.state);
                    }
                } else if (entry.operation instanceof TasksWire.Operation.InvitationRevoke) {
                    MutableTask task = tasks.get(((TasksWire.Operation.InvitationRevoke) entry.operation).taskId);
                    if (task != null) {
                        task.requestRecipient = null;
                        task.requestAcked = false;
                    }
                }
            }
        }
        // A deferred assignment left the outbox but is still the user's unsent change.
        unsentUser += deferrals.size() + undecodedDeferrals;
        HashMap<String, String> deferredRecipients = new HashMap<>();
        for (TasksCloudLedger.Deferral deferral : deferrals) deferredRecipients.put(deferral.taskId, deferral.recipientTgId);
        ArrayList<ListView> listViews = new ArrayList<>();
        for (MutableList list : lists.values()) {
            listViews.add(new ListView(list.id, list.server, list.title, list.sortOrder, latestByChain.get(TasksCloudLedger.listChain(list.id))));
        }
        Collections.sort(listViews, new Comparator<ListView>() {
            @Override
            public int compare(ListView a, ListView b) {
                return a.sortOrder != b.sortOrder ? (a.sortOrder < b.sortOrder ? -1 : 1) : a.id.compareTo(b.id);
            }
        });
        ArrayList<TaskView> taskViews = new ArrayList<>();
        for (MutableTask task : tasks.values()) {
            taskViews.add(new TaskView(task.id, task.server, task.content, task.completed, task.owner,
                    task.server == null ? null : task.server.assignment, task.liveInvitationId,
                    latestByChain.get(TasksCloudLedger.taskChain(task.id)), task.owner ? task.requestRecipient : null,
                    task.owner && task.requestAcked, task.owner ? deferredRecipients.get(task.id) : null));
        }
        // The automatic accept hid its invitation (the overlay) and the task is not here yet: a provisional row.
        ArrayList<TasksWire.InvitationItem> provisional = new ArrayList<>();
        for (TasksWire.InvitationItem invitation : shadowInvitations.values()) {
            if (autoAcceptIds.contains(invitation.id) && !invitations.containsKey(invitation.id) && !tasks.containsKey(invitation.taskId)) {
                provisional.add(invitation);
            }
        }
        Collections.sort(taskViews, new Comparator<TaskView>() {
            @Override
            public int compare(TaskView a, TaskView b) {
                int x = a.content.sortOrder, y = b.content.sortOrder;
                return x != y ? (x < y ? -1 : 1) : a.id.compareTo(b.id);
            }
        });
        return new TasksCloudState(status, syncing, shadowComplete, ownTelegramId, principal, serverTime, listViews, taskViews,
                new ArrayList<>(invitations.values()), new ArrayList<>(blocks.values()), pending, notices, unsentUser, unsentMigration,
                provisional);
    }

    /** {@code dependencies} holds every EARLIER entry. */
    private static PendingState displayState(TasksCloudLedger.Entry entry, boolean ownPrincipal, TasksCloudLedger.Dependencies dependencies) {
        if (!ownPrincipal) return PendingState.WRONG_PRINCIPAL;
        switch (entry.state) {
            case TasksCloudSchema.HELD: return PendingState.HELD;
            case TasksCloudSchema.SENDING: return PendingState.SENDING;
            case TasksCloudSchema.STALLED: return PendingState.STALLED;
            case TasksCloudSchema.BLOCKED_CREATION: return PendingState.BLOCKED_CREATION;
            case TasksCloudSchema.INVALID: return PendingState.INVALID;
            // A conflict the next complete pull resolves automatically (§7.3) is not the user's yet.
            case TasksCloudSchema.CONFLICT: return entry.autoResolvePending ? PendingState.WAITING : PendingState.CONFLICT;
            case TasksCloudSchema.QUARANTINED: return PendingState.QUARANTINED;
            case TasksCloudSchema.NOT_FOUND: return PendingState.NOT_FOUND;
            default:
                if (dependencies.waiting(entry)) return PendingState.WAITING;
                return TasksCloudSchema.BACKOFF.equals(entry.state) ? PendingState.BACKOFF : PendingState.QUEUED;
        }
    }

    /**
     * Terminal failures show the current server row; acknowledged entries stay applied only until sync catches up. A
     * conflict awaiting automatic resolution keeps the user's intent applied until it is resealed or dropped.
     */
    private static boolean overlays(TasksCloudLedger.Entry entry, Map<String, String> shadowRevisions) {
        switch (entry.state) {
            case TasksCloudSchema.CONFLICT:
                return entry.autoResolvePending;
            case TasksCloudSchema.NOT_FOUND:
            case TasksCloudSchema.QUARANTINED:
                return false;
            case TasksCloudSchema.ACKED:
                if (entry.ackedRevision == null) return true;
                String current = shadowRevisions.get(entry.chain);
                // Absent: create not synced yet / delete already applied. Present: only while the shadow is older.
                return current == null || Long.parseLong(current) < Long.parseLong(entry.ackedRevision);
            default:
                return true;
        }
    }

    private static void apply(TasksWire.Operation operation, Map<String, MutableList> lists, Map<String, MutableTask> tasks,
                              Map<String, TasksWire.InvitationItem> invitations, Map<String, TasksWire.BlockItem> blocks) {
        switch (operation.kind) {
            case LIST_CREATE: {
                TasksWire.Operation.ListCreate op = (TasksWire.Operation.ListCreate) operation;
                if (!lists.containsKey(op.listId)) lists.put(op.listId, new MutableList(op.listId, null, op.title, op.sortOrder));
                break;
            }
            case LIST_UPDATE: {
                TasksWire.Operation.ListUpdate op = (TasksWire.Operation.ListUpdate) operation;
                MutableList list = lists.get(op.listId);
                if (list != null) {
                    list.title = op.title;
                    list.sortOrder = op.sortOrder;
                }
                break;
            }
            case LIST_DELETE: {
                TasksWire.Operation.ListDelete op = (TasksWire.Operation.ListDelete) operation;
                lists.remove(op.listId);
                Set<String> children = new HashSet<>();
                for (MutableTask task : tasks.values()) {
                    if (task.owner && op.listId.equals(task.content.listId)) children.add(task.id);
                }
                for (String child : children) tasks.remove(child);
                break;
            }
            case TASK_CREATE: {
                TasksWire.Operation.TaskCreate op = (TasksWire.Operation.TaskCreate) operation;
                if (!tasks.containsKey(op.taskId)) tasks.put(op.taskId, new MutableTask(op.taskId, null, op.content, op.isCompleted, true, null));
                break;
            }
            case TASK_UPDATE: {
                TasksWire.Operation.TaskUpdate op = (TasksWire.Operation.TaskUpdate) operation;
                MutableTask task = tasks.get(op.taskId);
                if (task != null && task.owner) task.content = op.content;
                break;
            }
            case TASK_PATCH: {
                TasksWire.Operation.TaskPatch op = (TasksWire.Operation.TaskPatch) operation;
                MutableTask task = tasks.get(op.taskId);
                if (task != null && task.owner) task.content = task.content.applying(op.set);
                break;
            }
            case TASK_COMPLETE: {
                TasksWire.Operation.TaskComplete op = (TasksWire.Operation.TaskComplete) operation;
                MutableTask task = tasks.get(op.taskId);
                if (task != null) task.completed = op.isCompleted;
                break;
            }
            case TASK_DELETE:
                tasks.remove(((TasksWire.Operation.TaskDelete) operation).taskId);
                break;
            case INVITATION_REQUEST: {
                TasksWire.Operation.InvitationRequest op = (TasksWire.Operation.InvitationRequest) operation;
                MutableTask task = tasks.get(op.taskId);
                if (task != null) task.liveInvitationId = op.invitationId;
                break;
            }
            case INVITATION_RESPOND: {
                TasksWire.Operation.InvitationRespond op = (TasksWire.Operation.InvitationRespond) operation;
                invitations.remove(op.invitationId);
                if (op.decision == TasksWire.Decision.LEAVE) {
                    MutableTask task = tasks.get(op.taskId);
                    if (task != null && !task.owner) tasks.remove(op.taskId);
                }
                break;
            }
            case INVITATION_REVOKE: {
                TasksWire.Operation.InvitationRevoke op = (TasksWire.Operation.InvitationRevoke) operation;
                MutableTask task = tasks.get(op.taskId);
                if (task != null && op.invitationId.equals(task.liveInvitationId)) task.liveInvitationId = null;
                break;
            }
            case SENDER_BLOCK: {
                TasksWire.Operation.SenderBlock op = (TasksWire.Operation.SenderBlock) operation;
                TasksWire.InvitationItem blocked = invitations.get(op.invitationId);
                invitations.remove(op.invitationId);
                if (blocked != null) {
                    List<String> fromSender = new ArrayList<>();
                    for (TasksWire.InvitationItem item : invitations.values()) {
                        if (item.ownerTgId.equals(blocked.ownerTgId)) fromSender.add(item.id);
                    }
                    for (String id : fromSender) invitations.remove(id);
                }
                break;
            }
            case SENDER_UNBLOCK:
                blocks.remove(((TasksWire.Operation.SenderUnblock) operation).senderTgId);
                break;
            default:
                break;
        }
    }
}
