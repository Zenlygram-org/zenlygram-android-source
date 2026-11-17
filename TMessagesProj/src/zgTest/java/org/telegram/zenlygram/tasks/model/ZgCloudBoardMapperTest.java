/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.content;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.id;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.list;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.own;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.pending;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.shared;
import static org.telegram.zenlygram.tasks.model.ZgCloudFixtures.state;

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAssigneeDisplay;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate.Permission;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.PendingState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Status;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire.AssignmentState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire.OperationKind;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgBoard.Banner;
import org.telegram.zenlygram.tasks.model.ZgBoard.Gate;
import org.telegram.zenlygram.tasks.model.ZgBoard.ReadOnly;
import org.telegram.zenlygram.tasks.model.ZgBoardTask.Sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The repository's view of an account's Cloud tasks as the board shows it (iOS TasksCloudBoardState): the gate, the
 * banner, the read-only reason, the order of the rows, and what each row says and allows.
 */
public class ZgCloudBoardMapperTest {

    private static final String SCOPE = "o-token/principal";
    private static final TasksCutOver MIGRATING = TasksCutOver.evaluate(Permission.ALLOWED, TasksCutOver.Record.ENQUEUED, SCOPE, SCOPE, 0);

    /** A linked account with consent, as the source gathers it. */
    private static ZgCloudBoardMapper.Inputs inputs(TasksCloudState state) {
        final ZgCloudBoardMapper.Inputs in = new ZgCloudBoardMapper.Inputs();
        in.state = state;
        in.permission = Permission.ALLOWED;
        in.cutOver = TasksCutOver.NONE;
        in.linked = true;
        in.nowSeconds = ZgCloudFixtures.BEFORE_EXPIRY;
        return in;
    }

    private static ZgBoard map(TasksCloudState state) {
        return ZgCloudBoardMapper.map(inputs(state));
    }

    private static List<String> ids(List<ZgBoardTask> tasks) {
        final ArrayList<String> ids = new ArrayList<>();
        for (ZgBoardTask task : tasks) {
            ids.add(task.id);
        }
        return ids;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Read-only, gate, banner
    // -----------------------------------------------------------------------------------------------------------

    @Test
    public void consentDecidesWhetherTheBoardIsReadOnly() {
        for (Status status : Status.values()) {
            for (boolean withdrawn : new boolean[]{false, true}) {
                final ReadOnly allowed = ZgCloudBoardMapper.readOnly(status, Permission.ALLOWED, withdrawn);
                final ReadOnly waiting = ZgCloudBoardMapper.readOnly(status, Permission.WAITING, withdrawn);
                if (status == Status.CONSENT_FENCED) {
                    // The server said so: whatever this device believes about the consent.
                    assertSame(ReadOnly.CONSENT_FENCED, allowed);
                    assertSame(ReadOnly.CONSENT_FENCED, waiting);
                    assertSame(ReadOnly.CONSENT_FENCED, ZgCloudBoardMapper.readOnly(status, Permission.FORBIDDEN, withdrawn));
                } else {
                    assertNull(allowed);
                    assertNull(waiting);
                    final ReadOnly expected = withdrawn ? ReadOnly.WITHDRAWN : ReadOnly.CONSENT_OFF;
                    assertSame(expected, ZgCloudBoardMapper.readOnly(status, Permission.FORBIDDEN, withdrawn));
                    assertSame(expected, ZgCloudBoardMapper.readOnly(status, null, withdrawn));
                }
            }
        }
    }

    @Test
    public void aStoreThatCannotBeShownIsAGateWhateverElseIsTrue() {
        for (ReadOnly readOnly : new ReadOnly[]{null, ReadOnly.CONSENT_OFF, ReadOnly.WITHDRAWN, ReadOnly.CONSENT_FENCED}) {
            for (boolean linked : new boolean[]{false, true}) {
                assertSame(Gate.STORAGE, ZgCloudBoardMapper.gate(Status.STORAGE_FAILED, true, linked, readOnly, true));
                assertSame(Gate.SIGNED_OUT, ZgCloudBoardMapper.gate(Status.CLOSED, true, linked, readOnly, true));
            }
        }
    }

    @Test
    public void anUnlinkedAccountWithNothingCachedGetsTheLinkGate() {
        for (Status status : new Status[]{Status.LOADING, Status.NOT_LINKED, Status.READY, Status.OFFLINE, Status.UNLINKED_PAUSED,
            Status.SESSION_INVALID, Status.UNAVAILABLE, Status.CONSENT_REQUIRED}) {
            assertSame(Gate.UNLINKED, ZgCloudBoardMapper.gate(status, false, false, null, false));
            // Content already on this device stays visible (a paused banner says why it does not sync).
            assertNull(ZgCloudBoardMapper.gate(status, true, false, null, false));
            assertNull(ZgCloudBoardMapper.gate(status, false, true, null, false));
            // A consent hold explains itself in the banner; the gate would hide the read-only archive.
            assertNull(ZgCloudBoardMapper.gate(status, false, false, ReadOnly.CONSENT_OFF, false));
            // After the cut-over Cloud is where the user works: changes queue on this device until the link is back.
            assertNull(ZgCloudBoardMapper.gate(status, false, false, null, true));
        }
    }

    @Test
    public void theBannerNamesTheFirstThingThatHoldsTheBoard() {
        // 1. A read-only reason, whatever the status.
        for (Status status : Status.values()) {
            assertSame(Banner.CONSENT_OFF, ZgCloudBoardMapper.banner(status, true, true, ReadOnly.CONSENT_OFF, false, false));
            assertSame(Banner.WITHDRAWN, ZgCloudBoardMapper.banner(status, true, true, ReadOnly.WITHDRAWN, false, true));
            assertSame(Banner.CONSENT_FENCED, ZgCloudBoardMapper.banner(status, true, false, ReadOnly.CONSENT_FENCED, true, true));
        }
        // 2. Consent given, the confirmation is on its way.
        assertSame(Banner.PREPARING, ZgCloudBoardMapper.banner(Status.CONSENT_REQUIRED, false, true, null, true, false));
        assertSame(Banner.PREPARING, ZgCloudBoardMapper.banner(Status.READY, true, true, null, true, true));
        assertSame(Banner.NOT_LINKED_YET, ZgCloudBoardMapper.banner(Status.CONSENT_REQUIRED, false, false, null, true, true));
        // Not cut over and not linked: the usual recovery, by the status.
        assertSame(Banner.PAUSED, ZgCloudBoardMapper.banner(Status.NOT_LINKED, false, false, null, true, false));
        // 3. The engine's status.
        assertSame(Banner.PAUSED, ZgCloudBoardMapper.banner(Status.UNLINKED_PAUSED, true, true, null, false, false));
        assertSame(Banner.PAUSED, ZgCloudBoardMapper.banner(Status.NOT_LINKED, true, true, null, false, false));
        assertSame(Banner.SESSION, ZgCloudBoardMapper.banner(Status.SESSION_INVALID, true, true, null, false, false));
        assertSame(Banner.OFFLINE, ZgCloudBoardMapper.banner(Status.OFFLINE, true, true, null, false, false));
        assertSame(Banner.UNAVAILABLE, ZgCloudBoardMapper.banner(Status.UNAVAILABLE, true, true, null, false, false));
        assertSame(Banner.PREPARING, ZgCloudBoardMapper.banner(Status.CONSENT_REQUIRED, true, true, null, false, false));
        assertSame(Banner.LOADING, ZgCloudBoardMapper.banner(Status.LOADING, false, true, null, false, false));
        assertSame(Banner.LOADING, ZgCloudBoardMapper.banner(Status.READY, false, true, null, false, false));
        // Ready and complete: nothing to say.
        assertNull(ZgCloudBoardMapper.banner(Status.READY, true, true, null, false, false));
        assertNull(ZgCloudBoardMapper.banner(Status.LOADING, true, true, null, false, false));
        // Cached content of an account that is not linked any more.
        assertSame(Banner.PAUSED, ZgCloudBoardMapper.banner(Status.READY, true, false, null, false, false));
    }

    @Test
    public void withoutARepositoryTheBoardIsSignedOut() {
        final ZgBoard board = map(null);
        assertSame(ZgTasksScope.CLOUD, board.scope);
        assertSame(Gate.SIGNED_OUT, board.gate);
        assertFalse(board.isUsable());
        assertFalse(board.complete);
        assertTrue(board.tasks.isEmpty());
    }

    @Test
    public void cachedContentIsNeverShownBehindAGate() {
        final TasksCloudState failed = state().status(Status.STORAGE_FAILED).task(own(id(1), content(null, "Cached", 0))).build();
        final ZgBoard board = map(failed);
        assertSame(Gate.STORAGE, board.gate);
        assertTrue(board.tasks.isEmpty());
        assertTrue(board.lists.isEmpty());
        assertNull(board.banner);
    }

    @Test
    public void anUnlinkedAccountSeesTheGateOrItsCachedTasksWithAPausedBanner() {
        final ZgCloudBoardMapper.Inputs empty = inputs(state().status(Status.NOT_LINKED).complete(false).build());
        empty.linked = false;
        empty.permission = Permission.WAITING;
        assertSame(Gate.UNLINKED, ZgCloudBoardMapper.map(empty).gate);

        final ZgCloudBoardMapper.Inputs cached = inputs(state().status(Status.UNLINKED_PAUSED).task(own(id(1), content(null, "Kept", 0))).build());
        cached.linked = false;
        cached.permission = Permission.WAITING;
        final ZgBoard board = ZgCloudBoardMapper.map(cached);
        assertNull(board.gate);
        assertSame(Banner.PAUSED, board.banner);
        assertEquals(1, board.tasks.size());
        // Not read-only: a change waits on this device until the account is linked again.
        assertTrue(board.canCompose());

        // After the cut-over the same account works in Cloud and is told that it is not confirmed yet.
        final ZgCloudBoardMapper.Inputs cutOver = inputs(state().status(Status.CONSENT_REQUIRED).complete(false).build());
        cutOver.linked = false;
        cutOver.permission = Permission.WAITING;
        cutOver.cutOver = MIGRATING;
        final ZgBoard waiting = ZgCloudBoardMapper.map(cutOver);
        assertNull(waiting.gate);
        assertSame(Banner.NOT_LINKED_YET, waiting.banner);
        assertTrue(waiting.canCompose());
    }

    @Test
    public void aConsentHoldKeepsTheContentAndTakesEveryActionAway() {
        final TasksCloudState state = state().status(Status.CONSENT_REQUIRED)
            .list(list(id(10), "Work", 0))
            .task(own(id(1), content(id(10), "Mine", 0)))
            .task(shared(id(2), "Theirs", 0, id(50), id(50)))
            .build();
        for (boolean withdrawn : new boolean[]{false, true}) {
            final ZgCloudBoardMapper.Inputs in = inputs(state);
            in.permission = Permission.FORBIDDEN;
            in.consentWithdrawn = withdrawn;
            // A cut-over reads as none without consent; the device rows are writable again.
            in.cutOver = MIGRATING;
            in.deviceRowsToCopy = true;
            final ZgBoard board = ZgCloudBoardMapper.map(in);
            assertNull(board.gate);
            assertSame(withdrawn ? ReadOnly.WITHDRAWN : ReadOnly.CONSENT_OFF, board.readOnly);
            assertSame(withdrawn ? Banner.WITHDRAWN : Banner.CONSENT_OFF, board.banner);
            assertFalse(board.canCompose());
            assertEquals(2, board.tasks.size());
            assertEquals(1, board.lists.size());
            for (ZgBoardTask task : board.tasks) {
                assertFalse(task.canComplete);
                assertFalse(task.canEdit);
                assertFalse(task.canDelete);
                assertFalse(task.canLeave);
                // The banner says why; a row does not repeat another reason.
                assertFalse(task.accessInactive);
            }
            assertFalse(board.offersDeviceCopy);
        }
        final ZgBoard fenced = map(state().status(Status.CONSENT_FENCED).task(own(id(1), content(null, "Mine", 0))).build());
        assertSame(ReadOnly.CONSENT_FENCED, fenced.readOnly);
        assertSame(Banner.CONSENT_FENCED, fenced.banner);
        assertFalse(fenced.task(id(1)).canComplete);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Rows
    // -----------------------------------------------------------------------------------------------------------

    @Test
    public void theCloudInboxComesFirstThenTheListsThenWhatIsSharedWithMe() {
        final TasksCloudState state = state()
            .list(list(id(10), "Work", 0))
            .list(list(id(11), "Home", 1))
            .task(shared(id(1), "Shared", 0, id(50), id(50)))
            .task(own(id(2), content(id(11), "Home second", 5)))
            .task(own(id(3), content(id(10), "Work only", 9)))
            .task(own(id(4), content(null, "Inbox second", 2)))
            .task(own(id(5), content(id(11), "Home first", 1)))
            .task(own(id(6), content(null, "Inbox first", 1)))
            .task(own(id(7), content(null, "Inbox also second", 2)))
            .build();
        final ZgBoard board = map(state);
        assertNull(board.gate);
        assertNull(board.banner);
        assertNull(board.readOnly);
        assertTrue(board.complete);
        // Inside a group by sort order, equal ones as the repository lists them.
        assertEquals(Arrays.asList(id(6), id(4), id(7), id(3), id(5), id(2), id(1)), ids(board.tasks));
        // The Cloud Inbox is no list: only the two real ones are chips.
        assertEquals(2, board.lists.size());
        assertEquals(id(10), board.lists.get(0).id);
        assertEquals("Work", board.lists.get(0).title);
        assertFalse(board.lists.get(0).inbox);
        assertEquals(id(11), board.lists.get(1).id);
        assertNull(board.task(id(6)).listId);
        assertEquals(id(11), board.task(id(5)).listId);
    }

    @Test
    public void anOwnTaskWithAnOlderNoteIsACandidateThatWasNeverSentAndKeepsTheOwnersActions() {
        final TasksWire.Content content = new TasksWire.Content(null, "Call", "Notes", 1_800_000_000L, 2, 0, new TasksWire.Annotation("777", "Ali"));
        final ZgBoard board = map(state().task(own(id(1), content)).build());
        final ZgBoardTask task = board.task(id(1));
        assertNotNull(task);
        assertEquals("Call", task.title);
        assertEquals(Long.valueOf(1_800_000_000L), task.dueAt);
        // The note is never the device board's local assignee: it names a contact the task was not sent to.
        assertNull(task.assignee);
        assertEquals("777", task.assigneeTgId);
        assertEquals("Ali", task.assigneeNoteName);
        assertSame(TasksAssigneeDisplay.State.NOT_SENT, task.assigneeState);
        assertFalse(task.isShared());
        assertTrue(task.canComplete);
        assertTrue(task.canEdit);
        assertTrue(task.canDelete);
        assertFalse(task.canLeave);
        assertSame(Sync.SYNCED, task.sync);
        assertFalse(task.completed);
        // Nobody assigned: no id, no state.
        final ZgBoardTask plain = map(state().task(own(id(2), content(null, "Plain", 0))).build()).task(id(2));
        assertNull(plain.assigneeTgId);
        assertNull(plain.assigneeNoteName);
        assertNull(plain.assigneeState);
    }

    @Test
    public void aTaskSharedWithMeCanOnlyBeCompletedOrLeft() {
        final ZgBoard board = map(state().task(shared(id(1), "Theirs", 0, id(50), id(50))).build());
        final ZgBoardTask task = board.task(id(1));
        assertTrue(task.isShared());
        assertEquals(ZgCloudFixtures.OTHER_TG, task.sharedByTgId);
        // A recipient never sees the owner's list or assignee.
        assertNull(task.listId);
        assertNull(task.assignee);
        assertNull(task.assigneeTgId);
        assertNull(task.assigneeState);
        assertFalse(task.provisional);
        assertTrue(task.canComplete);
        assertTrue(task.canLeave);
        assertFalse(task.canEdit);
        assertFalse(task.canDelete);
        assertFalse(task.accessInactive);
    }

    @Test
    public void aSharedTaskWithoutALiveInvitationOnlyOpens() {
        // The invitation this account accepted is no longer the live one (the owner revoked or replaced it; not synced yet).
        final ZgBoardTask task = map(state().task(shared(id(1), "Theirs", 0, id(50), null)).build()).task(id(1));
        assertTrue(task.accessInactive);
        assertFalse(task.canComplete);
        assertFalse(task.canLeave);
        assertFalse(task.canEdit);
        assertFalse(task.canDelete);
    }

    @Test
    public void theOwnerReadsTheStateOfTheAssignment() {
        final AssignmentState[] states = {AssignmentState.PENDING, AssignmentState.ACCEPTED, AssignmentState.DECLINED, AssignmentState.REVOKED,
            AssignmentState.LEFT, AssignmentState.EXPIRED};
        final TasksAssigneeDisplay.State[] expected = {TasksAssigneeDisplay.State.SENT, TasksAssigneeDisplay.State.ACCEPTED,
            TasksAssigneeDisplay.State.DECLINED, TasksAssigneeDisplay.State.REMOVED, TasksAssigneeDisplay.State.LEFT, TasksAssigneeDisplay.State.EXPIRED};
        for (int i = 0; i < states.length; i++) {
            final TasksWire.Assignment assignment = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, states[i], ZgCloudFixtures.EXPIRES);
            final ZgBoardTask task = map(state().task(own(id(1), content(null, "Assigned", 0), false, null, assignment, id(50), null)).build()).task(id(1));
            assertSame(expected[i], task.assigneeState);
            // The assignee is the assignment's recipient, by id; no note names them.
            assertEquals(ZgCloudFixtures.OTHER_TG, task.assigneeTgId);
            assertNull(task.assigneeNoteName);
            assertNull(task.assignee);
            // The owner's own actions stay.
            assertTrue(task.canEdit);
        }
        // A pending assignment past its expiry reads as not accepted in time before the server says so; without a clock it stays sent.
        final TasksWire.Assignment sent = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.PENDING, ZgCloudFixtures.EXPIRES);
        final TasksCloudState pendingState = state().task(own(id(1), content(null, "T", 0), false, null, sent, id(50), null)).build();
        final ZgCloudBoardMapper.Inputs late = inputs(pendingState);
        late.nowSeconds = ZgCloudFixtures.AFTER_EXPIRY;
        assertSame(TasksAssigneeDisplay.State.EXPIRED, ZgCloudBoardMapper.map(late).task(id(1)).assigneeState);
        late.nowSeconds = TasksWire.epochSeconds(ZgCloudFixtures.EXPIRES);
        assertSame(TasksAssigneeDisplay.State.EXPIRED, ZgCloudBoardMapper.map(late).task(id(1)).assigneeState);
        late.nowSeconds = 0;
        assertSame(TasksAssigneeDisplay.State.SENT, ZgCloudBoardMapper.map(late).task(id(1)).assigneeState);
        // An accepted one does not expire; once the task is completed it reads completed.
        final TasksWire.Assignment accepted = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.ACCEPTED, ZgCloudFixtures.EXPIRES);
        final ZgCloudBoardMapper.Inputs later = inputs(state().task(own(id(1), content(null, "T", 0), false, null, accepted, id(50), null)).build());
        later.nowSeconds = ZgCloudFixtures.AFTER_EXPIRY;
        assertSame(TasksAssigneeDisplay.State.ACCEPTED, ZgCloudBoardMapper.map(later).task(id(1)).assigneeState);
        final ZgBoardTask done = map(state().task(ZgCloudFixtures.assigned(id(1), content(null, "T", 0), true, accepted, null, null, false, null)).build()).task(id(1));
        assertSame(TasksAssigneeDisplay.State.COMPLETED, done.assigneeState);
    }

    @Test
    public void aRequestOnItsWayIsSaidByTheAssigneesStateNotASecondTimeByTheSyncLine() {
        final TasksWire.Assignment old = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.DECLINED, ZgCloudFixtures.EXPIRES);
        // Queued here, not acknowledged: "Sending…" for the picked person, whatever the stale server row says.
        final TasksCloudState.Pending request = pending(id(1), OperationKind.INVITATION_REQUEST, PendingState.QUEUED, false);
        final ZgBoardTask sending = map(state().task(ZgCloudFixtures.assigned(id(1), content(null, "T", 0), false, old, request, "3003", false, null))
            .pending(request).build()).task(id(1));
        assertSame(TasksAssigneeDisplay.State.SENDING, sending.assigneeState);
        assertEquals("3003", sending.assigneeTgId);
        assertSame(Sync.SYNCED, sending.sync);
        // Acknowledged, the pull has not shown it yet: "Sent".
        final TasksCloudState.Pending acked = pending(id(1), OperationKind.INVITATION_REQUEST, PendingState.QUEUED, true);
        final ZgBoardTask sentRow = map(state().task(ZgCloudFixtures.assigned(id(1), content(null, "T", 0), false, old, acked, "3003", true, null))
            .build()).task(id(1));
        assertSame(TasksAssigneeDisplay.State.SENT, sentRow.assigneeState);
        // A request the user has to look at keeps its sync line.
        final TasksCloudState.Pending stuck = pending(id(1), OperationKind.INVITATION_REQUEST, PendingState.CONFLICT, true);
        final ZgBoardTask conflicted = map(state().task(own(id(1), content(null, "T", 0), false, null, old, id(50), stuck)).pending(stuck).build()).task(id(1));
        assertSame(Sync.NEEDS_ATTENTION, conflicted.sync);
        assertSame(TasksAssigneeDisplay.State.DECLINED, conflicted.assigneeState);
        // Any other unconfirmed change is said by the sync line and leaves the assignee's state in place.
        final TasksWire.Assignment live = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.ACCEPTED, ZgCloudFixtures.EXPIRES);
        final TasksCloudState.Pending edit = pending(id(1), OperationKind.TASK_UPDATE, PendingState.QUEUED, false);
        final ZgBoardTask edited = map(state().task(own(id(1), content(null, "T", 0), false, null, live, id(50), edit)).pending(edit).build()).task(id(1));
        assertSame(TasksAssigneeDisplay.State.ACCEPTED, edited.assigneeState);
        assertSame(Sync.NOT_SENT, edited.sync);
        // A deferred assignment (a long rate limit): "Queued" for the picked person.
        final ZgBoardTask queued = map(state().task(ZgCloudFixtures.assigned(id(1), content(null, "T", 0), false, null, null, null, false, "3003")).build()).task(id(1));
        assertSame(TasksAssigneeDisplay.State.QUEUED, queued.assigneeState);
        assertEquals("3003", queued.assigneeTgId);
    }

    @Test
    public void aRemoveMadeHereReadsRemovedBeforeTheServerShowsIt() {
        // The synced assignment is still live, but this device's own Remove is in the outbox: the overlay has no live epoch.
        final TasksWire.Assignment live = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.ACCEPTED, ZgCloudFixtures.EXPIRES);
        final TasksCloudState.Pending revoke = pending(id(1), OperationKind.INVITATION_REVOKE, PendingState.QUEUED, false);
        final ZgBoardTask removed = map(state().task(own(id(1), content(null, "T", 0), false, null, live, null, revoke)).pending(revoke).build()).task(id(1));
        assertSame(TasksAssigneeDisplay.State.REMOVED, removed.assigneeState);
        assertEquals(ZgCloudFixtures.OTHER_TG, removed.assigneeTgId);
        assertSame(Sync.NOT_SENT, removed.sync);
        // An ended assignment without a live epoch is what the server says it is.
        final TasksWire.Assignment declined = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.DECLINED, ZgCloudFixtures.EXPIRES);
        assertSame(TasksAssigneeDisplay.State.DECLINED,
            map(state().task(own(id(1), content(null, "T", 0), false, null, declined, null, null)).build()).task(id(1)).assigneeState);
    }

    @Test
    public void anAutomaticAcceptOnItsWayIsAProvisionalRowAfterTheAssignedOnes() {
        final TasksWire.InvitationItem arriving = new TasksWire.InvitationItem(id(60), id(7), "4", ZgCloudFixtures.OTHER_TG, "From a contact",
            1_800_000_000L, ZgCloudFixtures.EXPIRES);
        final TasksCloudState.Pending accept = ZgCloudFixtures.pending(id(7),
            TasksWire.Operation.invitationRespond(id(7), "4", id(60), TasksWire.Decision.ACCEPT), PendingState.QUEUED);
        final ZgBoard board = map(state().task(own(id(1), content(null, "Mine", 0))).task(shared(id(2), "Theirs", 0, id(50), id(50)))
            .invitation(ZgCloudFixtures.invitation(id(61), id(8), ZgCloudFixtures.EXPIRES)).provisional(arriving).pending(accept).build());
        assertEquals(Arrays.asList(id(1), id(2), id(7)), ids(board.tasks));
        final ZgBoardTask row = board.task(id(7));
        assertTrue(row.provisional);
        assertTrue(row.isShared());
        assertEquals(ZgCloudFixtures.OTHER_TG, row.sharedByTgId);
        assertEquals("From a contact", row.title);
        assertEquals(Long.valueOf(1_800_000_000L), row.dueAt);
        // The invitation's preview is not access: nothing but opening it.
        assertFalse(row.canComplete);
        assertFalse(row.canEdit);
        assertFalse(row.canDelete);
        assertFalse(row.canLeave);
        // The device's own accept is not a change the user made, and the provisional row is not an invitation that waits.
        assertEquals(0, board.unsentWaiting);
        assertEquals(1, board.invitations);
        // An accept the user has to look at is counted.
        final TasksCloudState.Pending stuck = ZgCloudFixtures.pending(id(7),
            TasksWire.Operation.invitationRespond(id(7), "4", id(60), TasksWire.Decision.ACCEPT), PendingState.STALLED);
        assertEquals(1, map(state().provisional(arriving).pending(stuck).build()).unsentAttention);
    }

    @Test
    public void completionShowsItsTimeOnceTheServerConfirmedIt() {
        final String at = "2026-11-10T08:00:00Z";
        final ZgBoardTask confirmed = map(state().task(own(id(1), content(null, "Done", 0), true, at, null, null, null)).build()).task(id(1));
        assertTrue(confirmed.completed);
        assertEquals(Long.valueOf(TasksWire.epochSeconds(at)), confirmed.completedAt);
        assertFalse(confirmed.completedJustNow);

        // Completed on this device a moment ago: no time yet, and it sorts on top of Completed.
        final TasksCloudState.Pending complete = pending(id(2), OperationKind.TASK_COMPLETE, PendingState.QUEUED, false);
        final ZgBoardTask justNow = map(state().task(own(id(2), content(null, "Just done", 0), true, null, null, null, complete)).pending(complete).build())
            .task(id(2));
        assertTrue(justNow.completed);
        assertNull(justNow.completedAt);
        assertTrue(justNow.completedJustNow);
    }

    // -----------------------------------------------------------------------------------------------------------
    // Sync lines and counts
    // -----------------------------------------------------------------------------------------------------------

    @Test
    public void theSyncLineNeverCallsAHandedOverChangeNotSent() {
        assertSame(Sync.SYNCED, ZgCloudBoardMapper.sync(null));
        // A delete inside its Undo window is not queued yet: its row is simply gone.
        assertSame(Sync.SYNCED, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_DELETE, PendingState.HELD, false)));
        assertSame(Sync.NOT_SENT, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_CREATE, PendingState.QUEUED, false)));
        assertSame(Sync.NOT_SENT, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_CREATE, PendingState.WAITING, false)));
        assertSame(Sync.NOT_SENT, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_CREATE, PendingState.BACKOFF, false)));
        // Handed to the network at least once: it may already be applied.
        assertSame(Sync.CONFIRMING, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_CREATE, PendingState.SENDING, false)));
        assertSame(Sync.CONFIRMING, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_CREATE, PendingState.QUEUED, true)));
        assertSame(Sync.CONFIRMING, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_CREATE, PendingState.BACKOFF, true)));
        for (PendingState state : new PendingState[]{PendingState.STALLED, PendingState.BLOCKED_CREATION, PendingState.INVALID, PendingState.CONFLICT,
            PendingState.QUARANTINED, PendingState.NOT_FOUND, PendingState.WRONG_PRINCIPAL}) {
            assertTrue(ZgCloudBoardMapper.needsAttention(state));
            assertSame(Sync.NEEDS_ATTENTION, ZgCloudBoardMapper.sync(pending(id(1), OperationKind.TASK_UPDATE, state, true)));
        }
        for (PendingState state : new PendingState[]{PendingState.HELD, PendingState.QUEUED, PendingState.SENDING, PendingState.BACKOFF, PendingState.WAITING}) {
            assertFalse(ZgCloudBoardMapper.needsAttention(state));
        }
        // Every state of the engine is decided here: a new one must be sorted into one of the two groups.
        assertEquals(12, PendingState.values().length);
    }

    @Test
    public void rowsAndListsShowTheirOwnUnconfirmedChange() {
        final TasksCloudState.Pending createList = pending(id(10), OperationKind.LIST_CREATE, PendingState.QUEUED, false);
        final TasksCloudState.Pending createTask = pending(id(1), OperationKind.TASK_CREATE, PendingState.STALLED, true);
        final ZgBoard board = map(state()
            .list(list(id(10), "New list", 0, createList))
            .task(ZgCloudFixtures.unconfirmed(id(1), content(id(10), "New task", 0), createTask))
            .pending(createList).pending(createTask)
            .build());
        assertSame(Sync.NOT_SENT, board.lists.get(0).sync);
        assertSame(Sync.NEEDS_ATTENTION, board.task(id(1)).sync);
        // A task the server has not seen is this account's own.
        assertTrue(board.task(id(1)).canEdit);
        assertFalse(board.task(id(1)).isShared());
    }

    @Test
    public void unsentChangesAreCountedByWhetherTheyNeedTheUser() {
        final ZgBoard board = map(state()
            .pending(pending(id(1), OperationKind.TASK_CREATE, PendingState.QUEUED, false))
            .pending(pending(id(2), OperationKind.TASK_UPDATE, PendingState.SENDING, true))
            .pending(pending(id(3), OperationKind.TASK_UPDATE, PendingState.BACKOFF, true))
            .pending(pending(id(4), OperationKind.TASK_UPDATE, PendingState.CONFLICT, true))
            .pending(pending(id(5), OperationKind.TASK_CREATE, PendingState.INVALID, true))
            // Still inside its Undo window: neither waiting nor an issue.
            .pending(pending(id(6), OperationKind.TASK_DELETE, PendingState.HELD, false))
            .build());
        assertEquals(3, board.unsentWaiting);
        assertEquals(2, board.unsentAttention);
    }

    @Test
    public void onlyInvitationsThatCanStillBeAnsweredAreCounted() {
        final TasksCloudState state = state()
            .invitation(ZgCloudFixtures.invitation(id(60), id(1), ZgCloudFixtures.EXPIRES))
            .invitation(ZgCloudFixtures.invitation(id(61), id(2), "2026-11-10T10:00:00Z"))
            .build();
        assertEquals(1, map(state).invitations);
        final ZgCloudBoardMapper.Inputs later = inputs(state);
        later.nowSeconds = ZgCloudFixtures.AFTER_EXPIRY;
        assertEquals(0, ZgCloudBoardMapper.map(later).invitations);
        // Without a clock nothing is called expired.
        final ZgCloudBoardMapper.Inputs noClock = inputs(state);
        noClock.nowSeconds = 0;
        assertEquals(2, ZgCloudBoardMapper.map(noClock).invitations);
        // Invitations alone are content: an unlinked account with one is not sent to the link gate.
        final ZgCloudBoardMapper.Inputs unlinked = inputs(state);
        unlinked.linked = false;
        assertNull(ZgCloudBoardMapper.map(unlinked).gate);
    }

    @Test
    public void noticesKeepTheirIdsAndKinds() {
        final TasksCloudState.Notice.Kind[] kinds = TasksCloudState.Notice.Kind.values();
        final ZgCloudFixtures.StateBuilder builder = state();
        for (int i = 0; i < kinds.length; i++) {
            builder.notice(ZgCloudFixtures.notice(100 + i, kinds[i]));
        }
        final ZgBoard board = map(builder.build());
        assertEquals(kinds.length, board.notices.size());
        for (int i = 0; i < kinds.length; i++) {
            assertEquals(100 + i, board.notices.get(i).id);
            assertEquals(kinds[i].name(), board.notices.get(i).kind.name());
        }
    }

    @Test
    public void theDeviceCopyIsOfferedOnlyWhenItCanQueueSomethingNow() {
        final TasksCloudState ready = state().build();
        final ZgCloudBoardMapper.Inputs offer = inputs(ready);
        offer.deviceRowsToCopy = true;
        assertTrue(ZgCloudBoardMapper.map(offer).offersDeviceCopy);

        assertFalse(map(ready).offersDeviceCopy);

        final ZgCloudBoardMapper.Inputs loading = inputs(state().complete(false).build());
        loading.deviceRowsToCopy = true;
        assertFalse(ZgCloudBoardMapper.map(loading).offersDeviceCopy);

        final ZgCloudBoardMapper.Inputs unlinked = inputs(state().task(own(id(1), content(null, "Cached", 0))).build());
        unlinked.deviceRowsToCopy = true;
        unlinked.linked = false;
        assertFalse(ZgCloudBoardMapper.map(unlinked).offersDeviceCopy);

        final ZgCloudBoardMapper.Inputs fenced = inputs(state().status(Status.CONSENT_FENCED).build());
        fenced.deviceRowsToCopy = true;
        assertFalse(ZgCloudBoardMapper.map(fenced).offersDeviceCopy);
    }

    @Test
    public void aLoadingBoardSaysSoAndKeepsItsRows() {
        final ZgBoard board = map(state().status(Status.LOADING).complete(false).task(own(id(1), content(null, "Cached", 0))).build());
        assertSame(Banner.LOADING, board.banner);
        assertFalse(board.complete);
        assertEquals(1, board.tasks.size());
        assertTrue(board.canCompose());
    }

    // -----------------------------------------------------------------------------------------------------------
    // Where a new row goes
    // -----------------------------------------------------------------------------------------------------------

    @Test
    public void aNewTaskGoesToTheEndOfItsListAndANewListAfterTheLastOne() {
        assertEquals(0, ZgCloudBoardMapper.nextTaskOrder(null, null));
        assertEquals(0, ZgCloudBoardMapper.nextListOrder(null));
        final TasksCloudState state = state()
            .list(list(id(10), "Work", 3))
            .list(list(id(11), "Home", 7))
            .task(own(id(1), content(null, "Inbox", 4)))
            .task(own(id(2), content(id(10), "Work", 9)))
            // Not this account's order: a shared task has its owner's.
            .task(shared(id(3), "Theirs", 50, id(50), id(50)))
            .build();
        assertEquals(5, ZgCloudBoardMapper.nextTaskOrder(state, null));
        assertEquals(10, ZgCloudBoardMapper.nextTaskOrder(state, id(10)));
        assertEquals(0, ZgCloudBoardMapper.nextTaskOrder(state, id(11)));
        assertEquals(8, ZgCloudBoardMapper.nextListOrder(state));
        final TasksCloudState full = state().task(own(id(1), content(null, "Last", Integer.MAX_VALUE))).build();
        assertEquals(Integer.MAX_VALUE, ZgCloudBoardMapper.nextTaskOrder(full, null));
    }

    @Test
    public void anOlderNoteOfAnotherPersonIsNotTheNameOfTheAssignee() {
        // The task was sent to 2002 while an older note still names 777: the row names the assignee, without the note's name.
        final TasksWire.Assignment assignment = new TasksWire.Assignment(id(50), ZgCloudFixtures.OTHER_TG, AssignmentState.PENDING, ZgCloudFixtures.EXPIRES);
        final TasksWire.Content content = new TasksWire.Content(null, "T", null, null, 0, 0, new TasksWire.Annotation("777", "Ali"));
        final ZgBoardTask task = map(state().task(own(id(1), content, false, null, assignment, id(50), null)).build()).task(id(1));
        assertEquals(ZgCloudFixtures.OTHER_TG, task.assigneeTgId);
        assertNull(task.assigneeNoteName);
        assertSame(TasksAssigneeDisplay.State.SENT, task.assigneeState);
        // The same person in both: the note's name is the fallback for the assignment's recipient.
        final TasksWire.Content same = new TasksWire.Content(null, "T", null, null, 0, 0, new TasksWire.Annotation(ZgCloudFixtures.OTHER_TG, "Vali"));
        assertEquals("Vali", map(state().task(own(id(1), same, false, null, assignment, id(50), null)).build()).task(id(1)).assigneeNoteName);
    }
}
