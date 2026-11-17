/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire.AssignmentState;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudSharing.Access;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudSharing.Role;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudSharing.State;

/** What a task's sharing reads as and what its owner may do about it (iOS TasksCloudSharingAvailability and friends). */
public class ZgCloudSharingTest {

    private static final String INVITATION = "11111111-1111-4111-8111-111111111111";
    private static final String NEWER = "22222222-2222-4222-8222-222222222222";
    private static final String RECIPIENT = "777000111";
    private static final String EXPIRES = "2026-11-20T10:00:00Z";
    private static final long BEFORE = TasksWire.epochSeconds("2026-11-14T10:00:00Z");
    private static final long AFTER = TasksWire.epochSeconds("2026-11-21T10:00:00Z");

    private static TasksWire.Assignment assignment(AssignmentState state) {
        return new TasksWire.Assignment(INVITATION, RECIPIENT, state, EXPIRES);
    }

    /** A settled, open task of the owner with this synced assignment and nothing of this device's on top. */
    private static ZgCloudSharing settled(AssignmentState state, long now) {
        final TasksWire.Assignment assignment = state == null ? null : assignment(state);
        final String live = state == AssignmentState.PENDING || state == AssignmentState.ACCEPTED ? INVITATION : null;
        return ZgCloudSharing.of(true, true, false, false, assignment, live, null, now);
    }

    @Test
    public void aTaskNobodyWasInvitedToCanBeShared() {
        final ZgCloudSharing sharing = settled(null, BEFORE);
        assertEquals(State.NOT_SHARED, sharing.state);
        assertEquals(Access.NOT_SHARED, sharing.access);
        assertNull(sharing.recipientTgId);
        assertNull(sharing.invitationId);
        assertTrue(sharing.canShare);
        assertFalse(sharing.canRevoke);
        assertFalse(sharing.replacesCurrent);
        assertFalse(sharing.endsAcceptedAccess());
    }

    @Test
    public void aLiveInvitationCanBeCancelledAndASecondShareReplacesIt() {
        final ZgCloudSharing pending = settled(AssignmentState.PENDING, BEFORE);
        assertEquals(State.PENDING, pending.state);
        assertEquals(Access.INVITED, pending.access);
        assertEquals(RECIPIENT, pending.recipientTgId);
        assertEquals(INVITATION, pending.invitationId);
        assertTrue(pending.canShare);
        assertTrue(pending.canRevoke);
        assertTrue(pending.replacesCurrent);
        // Cancelling an unanswered invitation is not "Stop Sharing".
        assertFalse(pending.endsAcceptedAccess());

        final ZgCloudSharing accepted = settled(AssignmentState.ACCEPTED, AFTER);
        assertEquals(State.ACCEPTED, accepted.state);
        assertEquals(Access.ACCEPTED, accepted.access);
        assertTrue(accepted.canRevoke);
        assertTrue(accepted.replacesCurrent);
        // Accepted access never expires.
        assertTrue(accepted.endsAcceptedAccess());
    }

    @Test
    public void anEndedInvitationLeavesNothingToCancel() {
        final AssignmentState[] ended = {AssignmentState.DECLINED, AssignmentState.REVOKED, AssignmentState.LEFT, AssignmentState.EXPIRED};
        final State[] states = {State.DECLINED, State.REVOKED, State.LEFT, State.EXPIRED};
        for (int i = 0; i < ended.length; i++) {
            final ZgCloudSharing sharing = settled(ended[i], BEFORE);
            assertEquals(states[i], sharing.state);
            assertEquals(Access.ENDED, sharing.access);
            assertEquals(RECIPIENT, sharing.recipientTgId);
            assertNull(sharing.invitationId);
            assertTrue(sharing.canShare);
            assertFalse(sharing.canRevoke);
            assertFalse(sharing.replacesCurrent);
        }
    }

    @Test
    public void aPendingInvitationPastItsExpiryReadsAsExpired() {
        final ZgCloudSharing sharing = settled(AssignmentState.PENDING, AFTER);
        assertEquals(State.EXPIRED, sharing.state);
        assertEquals(Access.ENDED, sharing.access);
        assertFalse(sharing.canRevoke);
        assertFalse(sharing.replacesCurrent);
        assertTrue(sharing.canShare);
        assertTrue(ZgCloudSharing.expired(EXPIRES, TasksWire.epochSeconds(EXPIRES)));
        assertFalse(ZgCloudSharing.expired(EXPIRES, TasksWire.epochSeconds(EXPIRES) - 1));
        // An unreadable instant never expires anything here: the server stays authoritative.
        assertFalse(ZgCloudSharing.expired("soon", AFTER));
    }

    @Test
    public void aShareMadeHereIsNeverShownAsDelivered() {
        // Nothing synced yet, this device asked to share.
        ZgCloudSharing sharing = ZgCloudSharing.of(true, true, true, false, null, NEWER, RECIPIENT, BEFORE);
        assertEquals(State.CONFIRMING, sharing.state);
        assertEquals(Access.INVITATION_PENDING_CONFIRMATION, sharing.access);
        assertEquals(RECIPIENT, sharing.recipientTgId);
        assertFalse(sharing.canShare);
        assertFalse(sharing.canRevoke);
        assertFalse(sharing.replacesCurrent);

        // Replacing a live invitation: the new one is the one that waits, and its recipient may be unknown.
        sharing = ZgCloudSharing.of(true, true, false, false, assignment(AssignmentState.ACCEPTED), NEWER, null, BEFORE);
        assertEquals(State.CONFIRMING, sharing.state);
        assertEquals(Access.INVITATION_PENDING_CONFIRMATION, sharing.access);
        assertNull(sharing.recipientTgId);
        assertFalse(sharing.canShare);
        assertFalse(sharing.canRevoke);
    }

    @Test
    public void aCancellationMadeHereWaitsForTheServerToo() {
        final ZgCloudSharing sharing = ZgCloudSharing.of(true, true, true, false, assignment(AssignmentState.ACCEPTED), null, null, BEFORE);
        assertEquals(State.CONFIRMING, sharing.state);
        assertEquals(Access.REVOCATION_PENDING_CONFIRMATION, sharing.access);
        assertEquals(RECIPIENT, sharing.recipientTgId);
        assertNull(sharing.invitationId);
        assertFalse(sharing.canShare);
        assertFalse(sharing.canRevoke);
    }

    @Test
    public void sharingNeedsASettledOpenTaskOfTheOwner() {
        // Not synced yet (its create is unconfirmed).
        assertFalse(ZgCloudSharing.of(true, false, true, false, null, null, null, BEFORE).canShare);
        // Something unsent.
        assertFalse(ZgCloudSharing.of(true, true, true, false, null, null, null, BEFORE).canShare);
        // Completed: shown, never offered.
        assertFalse(ZgCloudSharing.of(true, true, false, true, null, null, null, BEFORE).canShare);
        // A completed task with a live invitation can still be un-shared.
        final ZgCloudSharing done = ZgCloudSharing.of(true, true, false, true, assignment(AssignmentState.ACCEPTED), INVITATION, null, BEFORE);
        assertFalse(done.canShare);
        assertTrue(done.canRevoke);
        // Unsent changes also hold a cancellation back.
        assertFalse(ZgCloudSharing.of(true, true, true, false, assignment(AssignmentState.PENDING), INVITATION, null, BEFORE).canRevoke);
        // Not the owner: nothing.
        final ZgCloudSharing recipient = ZgCloudSharing.of(false, true, false, false, assignment(AssignmentState.ACCEPTED), INVITATION, null, BEFORE);
        assertFalse(recipient.canShare);
        assertFalse(recipient.canRevoke);
    }

    @Test
    public void aRecipientHasAccessOnlyThroughAnAcceptedInvitation() {
        assertEquals(Role.OWNER, ZgCloudSharing.role(true, null));
        assertEquals(Role.OWNER, ZgCloudSharing.role(true, assignment(AssignmentState.DECLINED)));
        assertEquals(Role.ACCEPTED_RECIPIENT, ZgCloudSharing.role(false, assignment(AssignmentState.ACCEPTED)));
        assertEquals(Role.NO_ACCESS, ZgCloudSharing.role(false, null));
        for (AssignmentState state : new AssignmentState[]{AssignmentState.PENDING, AssignmentState.DECLINED, AssignmentState.REVOKED,
            AssignmentState.LEFT, AssignmentState.EXPIRED}) {
            assertEquals(state.name(), Role.NO_ACCESS, ZgCloudSharing.role(false, assignment(state)));
        }
    }
}
