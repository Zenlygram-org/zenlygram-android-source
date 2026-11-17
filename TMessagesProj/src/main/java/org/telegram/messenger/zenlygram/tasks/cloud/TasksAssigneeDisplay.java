package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * What the owner reads about a task's assignee (iOS TasksAssigneeDisplayState, wf15 §4.3). Pure: the board and the
 * editor hand the facts in ({@link Inputs}); the UI only renders the answer — the state word, its footer and its one
 * action — and never decides by itself.
 */
public final class TasksAssigneeDisplay {
    /** The owner's state word for a task's assignee, the first matching row of the table in {@link #resolve}. */
    public enum State {
        /** A queued or in-flight invitation.request, not acknowledged yet ("Sending…"). */
        SENDING,
        /** A deferred assignment (a long server rate limit; re-sent by itself) ("Queued"). */
        QUEUED,
        /** Pending on the server, not expired — whoever the recipient is, Zenlygram or not ("Sent"). */
        SENT,
        /** Accepted, task open ("Accepted"). */
        ACCEPTED,
        /** Accepted, task completed (wire 4 cannot say by whom) ("Completed"). */
        COMPLETED,
        DECLINED,
        LEFT,
        /** Expired on the server, or pending past its expiry ("Not accepted within 7 days"). */
        EXPIRED,
        /** Revoked by the owner, until the server's detach ("Removed"). */
        REMOVED,
        /** No assignment, but a legacy candidate: an owner-only note naming a contact, never sent ("Not sent yet"). */
        NOT_SENT;

        public Action action() {
            switch (this) {
                case SENDING:
                case QUEUED:
                    return Action.NONE;
                case SENT:
                case ACCEPTED:
                case COMPLETED:
                    return Action.REPLACE_OR_REMOVE;
                case DECLINED:
                case LEFT:
                case EXPIRED:
                    return Action.RESEND;
                case REMOVED:
                    return Action.PICK_AGAIN;
                default:
                    return Action.SEND;
            }
        }

        public Footer footer() {
            switch (this) {
                case SENDING:
                    return Footer.NONE;
                case QUEUED:
                    return Footer.QUEUED;
                case SENT:
                    return Footer.PENDING;
                case ACCEPTED:
                case COMPLETED:
                    return Footer.ACCEPTED;
                case DECLINED:
                case LEFT:
                case EXPIRED:
                case REMOVED:
                    return Footer.ENDED;
                default:
                    return Footer.NOT_SENT;
            }
        }

        /**
         * The assignee row itself opens the picker. Never while a request is on its way ("Sending…"). In "Queued" it
         * does: the request left the outbox and waits up to a day for its automatic re-send, and a newer pick is what
         * cancels that deferral.
         */
        public boolean rowPicks() {
            return action() != Action.NONE || this == QUEUED;
        }

        /** The row carries Send again / Send. */
        public boolean offersSendAction() {
            return action() == Action.RESEND || action() == Action.SEND;
        }

        /**
         * Someone has (or is about to get) this task through a live epoch: replacing or removing them asks first. A
         * request still on its way, a deferral, an ended epoch and a legacy candidate ask nothing.
         */
        public boolean someoneLosesAccess() {
            return this == SENT || this == ACCEPTED || this == COMPLETED;
        }

        /** Declined, left or expired: the recipient answered (or never did) and nobody has the task. */
        public boolean isEnded() {
            return action() == Action.RESEND;
        }
    }

    /** The one action offered beside the row. */
    public enum Action {
        /** Nothing beyond the row (a request on its way). */
        NONE,
        /** Change assignee / Remove assignee (a live assignment). */
        REPLACE_OR_REMOVE,
        /** Send again: the same recipient, a new epoch. */
        RESEND,
        /** Pick again (the owner removed the assignee). */
        PICK_AGAIN,
        /** Send: the legacy candidate's first invitation.request. */
        SEND
    }

    /** The editor footer under the row. */
    public enum Footer { NONE, QUEUED, PENDING, ACCEPTED, ENDED, NOT_SENT }

    /** The facts the table reads, gathered from the repository's state. */
    public static final class Inputs {
        /** The task's server assignment as last synced (nullable). */
        public final TasksWire.Assignment assignment;
        /** An invitation.request of this device for the task is in the outbox, not acknowledged. */
        public final boolean hasQueuedRequest;
        /** A request of this device was acknowledged and the pull has not shown its assignment yet. */
        public final boolean hasAcknowledgedRequest;
        /** A deferred assignment for this task waits for its automatic re-send. */
        public final boolean isDeferred;
        public final boolean isTaskCompleted;
        /** An owner-only note names a contact and nothing was sent for it. */
        public final boolean isLegacyCandidate;
        /** This device's own Remove of the synced assignment is on its way (or acknowledged and not pulled yet). */
        public final boolean isRemovalQueued;
        public final long nowSeconds;

        public Inputs(TasksWire.Assignment assignment, boolean hasQueuedRequest, boolean hasAcknowledgedRequest, boolean isDeferred,
                      boolean isTaskCompleted, boolean isLegacyCandidate, long nowSeconds) {
            this(assignment, hasQueuedRequest, hasAcknowledgedRequest, isDeferred, isTaskCompleted, isLegacyCandidate, false, nowSeconds);
        }

        public Inputs(TasksWire.Assignment assignment, boolean hasQueuedRequest, boolean hasAcknowledgedRequest, boolean isDeferred,
                      boolean isTaskCompleted, boolean isLegacyCandidate, boolean isRemovalQueued, long nowSeconds) {
            this.assignment = assignment;
            this.hasQueuedRequest = hasQueuedRequest;
            this.hasAcknowledgedRequest = hasAcknowledgedRequest;
            this.isDeferred = isDeferred;
            this.isTaskCompleted = isTaskCompleted;
            this.isLegacyCandidate = isLegacyCandidate;
            this.isRemovalQueued = isRemovalQueued;
            this.nowSeconds = nowSeconds;
        }
    }

    private TasksAssigneeDisplay() { }

    /**
     * First match wins; null = no assignee (the row shows "None" and the owner picks). That includes the server's
     * detach of an ended epoch and the detach after the assignee's consent withdrawal or account erasure: no
     * explanation, as specified.
     */
    public static State resolve(Inputs inputs) {
        if (inputs.hasQueuedRequest) return State.SENDING;
        // Acknowledged, not pulled yet: the server holds a fresh pending epoch whatever the stale shadow still says.
        if (inputs.hasAcknowledgedRequest) return State.SENT;
        if (inputs.isDeferred) return State.QUEUED;
        TasksWire.Assignment assignment = inputs.assignment;
        // The owner removed the assignee here and the server has not shown it yet: Removed, not the stale live state.
        if (assignment != null && inputs.isRemovalQueued) return State.REMOVED;
        if (assignment != null) {
            if (isExpired(assignment, inputs.nowSeconds)) return State.EXPIRED;
            switch (assignment.state) {
                case PENDING:
                    return State.SENT;
                case ACCEPTED:
                    return inputs.isTaskCompleted ? State.COMPLETED : State.ACCEPTED;
                case DECLINED:
                    return State.DECLINED;
                case LEFT:
                    return State.LEFT;
                case EXPIRED:
                    return State.EXPIRED;
                default:
                    return State.REMOVED;
            }
        }
        return inputs.isLegacyCandidate ? State.NOT_SENT : null;
    }

    /** Expired on the server, or pending past its expiry by this clock. */
    public static boolean isExpired(TasksWire.Assignment assignment, long nowSeconds) {
        if (assignment.state == TasksWire.AssignmentState.EXPIRED) return true;
        return assignment.state == TasksWire.AssignmentState.PENDING && TasksWire.epochSeconds(assignment.expiresAt) <= nowSeconds;
    }

    /** Accepted, or pending and not past its expiry. */
    public static boolean isLive(TasksWire.Assignment assignment, long nowSeconds) {
        if (assignment == null) return false;
        if (assignment.state == TasksWire.AssignmentState.ACCEPTED) return true;
        return assignment.state == TasksWire.AssignmentState.PENDING && TasksWire.epochSeconds(assignment.expiresAt) > nowSeconds;
    }

    /**
     * Whose name the owner sees for a recipient, in this order: the viewer's own contact name; the task's legacy
     * owner-only note when its id is the recipient's; the numeric fallback. Device-local, never on the wire. Blank
     * names count as absent.
     */
    public static String name(String recipientTgId, String contactName, TasksWire.Annotation annotation, String numeric) {
        if (contactName != null && !contactName.trim().isEmpty()) return contactName.trim();
        if (annotation != null && annotation.telegramUserId.equals(recipientTgId) && annotation.displayName != null
                && !annotation.displayName.trim().isEmpty()) {
            return annotation.displayName.trim();
        }
        return numeric;
    }
}
