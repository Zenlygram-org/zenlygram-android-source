/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

/**
 * Who a Cloud task is shared with and what its owner may do about it right now (iOS TasksCloudSharingAvailability,
 * tasksCloudRecipientAccess and tasksCloudAssignmentDisplayState). Pure: the editor draws from it and checks it again
 * when a row is tapped; the repository's own checks stay authoritative.
 *
 * <p>The repository shows a task's last synced assignment and, separately, the invitation that is live once this
 * device's own unsent share or cancellation is applied. Where the two disagree, a change made here is still waiting
 * for the server ({@link State#CONFIRMING}); it is never shown as delivered.
 */
public final class ZgCloudSharing {

    /** What the viewer is to a task. A recipient without an accepted invitation has no access (fail closed). */
    public enum Role {
        OWNER,
        ACCEPTED_RECIPIENT,
        NO_ACCESS
    }

    /** What the sharing state reads as. */
    public enum State {
        NOT_SHARED,
        /** A share or a cancellation made on this device that the server has not confirmed. */
        CONFIRMING,
        PENDING,
        ACCEPTED,
        DECLINED,
        REVOKED,
        LEFT,
        EXPIRED
    }

    /** What the current (or last) recipient can do, as the owner reads it. */
    public enum Access {
        NOT_SHARED,
        /** A share made here awaits confirmation: the recipient's access may not reflect it yet. */
        INVITATION_PENDING_CONFIRMATION,
        /** A live invitation: until it is accepted the recipient sees only the title and the deadline. */
        INVITED,
        /** Done / not done and Leave; does not expire. */
        ACCEPTED,
        /** A cancellation made here awaits confirmation. */
        REVOCATION_PENDING_CONFIRMATION,
        /** Declined, cancelled, left or expired. */
        ENDED
    }

    public final State state;
    public final Access access;
    /** The recipient's Telegram id, or null when it is not known here (never a guess). */
    public final String recipientTgId;
    /** The live invitation a cancellation would end; null when there is none. */
    public final String invitationId;
    /** Sharing needs a task the server knows, with nothing unsent, that is open. */
    public final boolean canShare;
    public final boolean canRevoke;
    /** A new share replaces a live invitation: the previous recipient loses access. */
    public final boolean replacesCurrent;

    private ZgCloudSharing(State state, Access access, String recipientTgId, String invitationId, boolean canShare, boolean canRevoke,
                           boolean replacesCurrent) {
        this.state = state;
        this.access = access;
        this.recipientTgId = recipientTgId;
        this.invitationId = invitationId;
        this.canShare = canShare;
        this.canRevoke = canRevoke;
        this.replacesCurrent = replacesCurrent;
    }

    /** A cancellation now ends an accepted recipient's access (rather than an unanswered invitation). */
    public boolean endsAcceptedAccess() {
        return canRevoke && state == State.ACCEPTED;
    }

    public static Role role(boolean owner, TasksWire.Assignment assignment) {
        if (owner) {
            return Role.OWNER;
        }
        return assignment != null && assignment.state == TasksWire.AssignmentState.ACCEPTED ? Role.ACCEPTED_RECIPIENT : Role.NO_ACCESS;
    }

    /**
     * @param owner                the viewer owns the task
     * @param synced               the server knows the task (its create was confirmed and synced)
     * @param unsent               the task has a change the server has not confirmed
     * @param completed            the task is done
     * @param assignment           the last synced assignment, or null
     * @param liveInvitationId     the live invitation including this device's unsent share / cancellation, or null
     * @param pendingRecipientTgId the recipient of this device's unconfirmed share, when known
     * @param nowSeconds           the server's time, for an invitation that ran out without a new revision
     */
    public static ZgCloudSharing of(boolean owner, boolean synced, boolean unsent, boolean completed, TasksWire.Assignment assignment,
                                    String liveInvitationId, String pendingRecipientTgId, long nowSeconds) {
        final boolean settled = owner && synced && !unsent;
        final String serverLive = assignment != null && (assignment.state == TasksWire.AssignmentState.PENDING
            || assignment.state == TasksWire.AssignmentState.ACCEPTED) ? assignment.invitationId : null;
        if (liveInvitationId != null && !liveInvitationId.equals(serverLive)) {
            return new ZgCloudSharing(State.CONFIRMING, Access.INVITATION_PENDING_CONFIRMATION, pendingRecipientTgId, liveInvitationId,
                false, false, false);
        }
        if (liveInvitationId == null && serverLive != null) {
            return new ZgCloudSharing(State.CONFIRMING, Access.REVOCATION_PENDING_CONFIRMATION, assignment.recipientTgId, null,
                false, false, false);
        }
        if (assignment == null) {
            return new ZgCloudSharing(State.NOT_SHARED, Access.NOT_SHARED, null, null, settled && !completed, false, false);
        }
        final State state = displayState(assignment, nowSeconds);
        final boolean live = state == State.PENDING || state == State.ACCEPTED;
        final Access access = state == State.PENDING ? Access.INVITED : state == State.ACCEPTED ? Access.ACCEPTED : Access.ENDED;
        return new ZgCloudSharing(state, access, assignment.recipientTgId, live ? assignment.invitationId : null,
            settled && !completed, settled && live, live);
    }

    /** The synced state; a pending invitation past its expiry reads as expired without inventing a new revision. */
    public static State displayState(TasksWire.Assignment assignment, long nowSeconds) {
        switch (assignment.state) {
            case PENDING:
                return expired(assignment.expiresAt, nowSeconds) ? State.EXPIRED : State.PENDING;
            case ACCEPTED:
                return State.ACCEPTED;
            case DECLINED:
                return State.DECLINED;
            case REVOKED:
                return State.REVOKED;
            case LEFT:
                return State.LEFT;
            default:
                return State.EXPIRED;
        }
    }

    /** Whether a wire Instant has passed; an unreadable one never has (the server stays authoritative). */
    public static boolean expired(String expiresAt, long nowSeconds) {
        try {
            return nowSeconds >= TasksWire.epochSeconds(expiresAt);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
