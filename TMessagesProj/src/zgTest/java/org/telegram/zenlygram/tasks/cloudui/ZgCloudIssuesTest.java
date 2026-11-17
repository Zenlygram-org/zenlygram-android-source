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
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate.Permission;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudSchema;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.PendingState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Status;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire.ConflictReason;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire.OperationKind;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudIssues.Action;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudIssues.Display;

import java.util.Arrays;
import java.util.List;

/**
 * The Unsent Changes table: which choices each outbox entry offers (iOS SGTasksCloudEntry.allowedActions, plus the
 * Android-only states), what its line says, and when the account is read-only.
 */
public class ZgCloudIssuesTest {

    private static final String DROPPED = TasksCloudSchema.NOTICE_DEPENDENCY_DROPPED;

    private static List<Action> actions(PendingState state, OperationKind kind) {
        return ZgCloudIssues.actions(state, kind, null, false, null, false, true);
    }

    private static List<Action> conflict(OperationKind kind, ConflictReason reason, boolean fields) {
        return ZgCloudIssues.actions(PendingState.CONFLICT, kind, reason, fields, "tasks_conflict", false, true);
    }

    private static List<Action> list(Action... actions) {
        return Arrays.asList(actions);
    }

    @Test
    public void aRefusedChangeIsStillPartOfTheTaskAsShown() {
        // The repository's own rule (TasksCloudState.overlays): the editor corrects a refused edit over the task as
        // shown, so this table must not drift from it.
        final List<PendingState> leftOut = Arrays.asList(PendingState.CONFLICT, PendingState.QUARANTINED, PendingState.NOT_FOUND,
            PendingState.WRONG_PRINCIPAL);
        for (PendingState state : PendingState.values()) {
            assertEquals(state.name(), !leftOut.contains(state), ZgCloudIssues.applied(state));
        }
        assertTrue(ZgCloudIssues.applied(PendingState.INVALID));
        assertTrue(ZgCloudIssues.applied(PendingState.STALLED));
        assertTrue(ZgCloudIssues.applied(PendingState.BLOCKED_CREATION));
    }

    @Test
    public void nothingIsOfferedWhileTheRepositoryIsWorking() {
        for (PendingState state : new PendingState[]{PendingState.HELD, PendingState.QUEUED, PendingState.SENDING, PendingState.BACKOFF, PendingState.WAITING}) {
            assertFalse(state.name(), ZgCloudIssues.needsAttention(state));
            for (OperationKind kind : OperationKind.values()) {
                assertTrue(state + " " + kind, actions(state, kind).isEmpty());
            }
        }
        // A delete inside its Undo window is not a queued change yet.
        assertFalse(ZgCloudIssues.listed(PendingState.HELD));
        assertTrue(ZgCloudIssues.listed(PendingState.QUEUED));
        assertTrue(ZgCloudIssues.listed(PendingState.SENDING));
    }

    @Test
    public void everyEntryThatNeedsTheUserCanBeDiscardedLast() {
        final PendingState[] states = {PendingState.STALLED, PendingState.BLOCKED_CREATION, PendingState.INVALID, PendingState.CONFLICT,
            PendingState.QUARANTINED, PendingState.NOT_FOUND, PendingState.WRONG_PRINCIPAL};
        for (PendingState state : states) {
            assertTrue(state.name(), ZgCloudIssues.needsAttention(state));
            for (OperationKind kind : OperationKind.values()) {
                for (ConflictReason reason : new ConflictReason[]{null, ConflictReason.REVISION_CHANGED, ConflictReason.FIELD_CHANGED,
                    ConflictReason.MEMBERS_CHANGED, ConflictReason.COMPLETED_TASK, ConflictReason.INVITATION_CHANGED, ConflictReason.INVITATION_EXPIRED}) {
                    for (boolean known : new boolean[]{true, false}) {
                        for (boolean migration : new boolean[]{true, false}) {
                            final List<Action> actions = ZgCloudIssues.actions(state, kind, reason, reason == ConflictReason.FIELD_CHANGED,
                                reason == null ? DROPPED : "tasks_conflict", migration, known);
                            final String where = state + " " + kind + " " + reason + " known=" + known + " migration=" + migration;
                            assertFalse(where, actions.isEmpty());
                            int discards = 0;
                            for (Action action : actions) {
                                if (action.discards()) {
                                    discards++;
                                }
                            }
                            assertEquals(where, 1, discards);
                            assertTrue(where, actions.get(actions.size() - 1).discards());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void stalledAndRefusedCreationsAreSentAgainOrDiscarded() {
        for (OperationKind kind : OperationKind.values()) {
            assertEquals(list(Action.RETRY, Action.DISCARD), actions(PendingState.STALLED, kind));
            assertEquals(list(Action.RETRY, Action.DISCARD), actions(PendingState.BLOCKED_CREATION, kind));
        }
    }

    @Test
    public void aRefusedChangeIsRepairedOnlyWhereThereIsContentToFix() {
        assertEquals(list(Action.REPAIR_TASK, Action.DISCARD), actions(PendingState.INVALID, OperationKind.TASK_CREATE));
        assertEquals(list(Action.REPAIR_TASK, Action.DISCARD), actions(PendingState.INVALID, OperationKind.TASK_UPDATE));
        assertEquals(list(Action.REPAIR_TASK, Action.DISCARD), actions(PendingState.INVALID, OperationKind.TASK_PATCH));
        assertEquals(list(Action.REPAIR_LIST, Action.DISCARD), actions(PendingState.INVALID, OperationKind.LIST_CREATE));
        assertEquals(list(Action.REPAIR_LIST, Action.DISCARD), actions(PendingState.INVALID, OperationKind.LIST_UPDATE));
        for (OperationKind kind : new OperationKind[]{OperationKind.LIST_DELETE, OperationKind.TASK_COMPLETE, OperationKind.TASK_DELETE,
            OperationKind.INVITATION_REQUEST, OperationKind.INVITATION_RESPOND, OperationKind.INVITATION_REVOKE, OperationKind.SENDER_BLOCK,
            OperationKind.SENDER_UNBLOCK}) {
            assertEquals(kind.name(), list(Action.DISCARD), actions(PendingState.INVALID, kind));
        }
        // A field edit whose task the server no longer shows carries no whole content to correct.
        assertEquals(list(Action.DISCARD), ZgCloudIssues.actions(PendingState.INVALID, OperationKind.TASK_PATCH, null, false, null, false, false));
        // A copy of a device task is never turned into another task.
        assertEquals(list(Action.DISCARD), ZgCloudIssues.actions(PendingState.INVALID, OperationKind.TASK_CREATE, null, false, null, true, true));
        assertEquals(list(Action.DISCARD), ZgCloudIssues.actions(PendingState.INVALID, OperationKind.LIST_CREATE, null, false, null, true, true));
        for (OperationKind kind : OperationKind.values()) {
            assertEquals(kind.name(), list(Action.DISCARD), actions(PendingState.QUARANTINED, kind));
        }
    }

    @Test
    public void aVanishedOrForeignTaskCanBeSavedAsANewOne() {
        for (PendingState state : new PendingState[]{PendingState.NOT_FOUND, PendingState.WRONG_PRINCIPAL}) {
            assertEquals(list(Action.SAVE_AS_NEW, Action.DISCARD), actions(state, OperationKind.TASK_CREATE));
            assertEquals(list(Action.SAVE_AS_NEW, Action.DISCARD), actions(state, OperationKind.TASK_UPDATE));
            assertEquals(list(Action.SAVE_AS_NEW, Action.DISCARD), actions(state, OperationKind.TASK_PATCH));
            assertEquals(list(Action.DISCARD), ZgCloudIssues.actions(state, OperationKind.TASK_PATCH, null, false, null, false, false));
            assertEquals(list(Action.DISCARD), ZgCloudIssues.actions(state, OperationKind.TASK_CREATE, null, false, null, true, true));
            for (OperationKind kind : new OperationKind[]{OperationKind.LIST_CREATE, OperationKind.LIST_UPDATE, OperationKind.LIST_DELETE,
                OperationKind.TASK_COMPLETE, OperationKind.TASK_DELETE, OperationKind.INVITATION_REQUEST, OperationKind.INVITATION_RESPOND,
                OperationKind.INVITATION_REVOKE, OperationKind.SENDER_BLOCK, OperationKind.SENDER_UNBLOCK}) {
                assertEquals(state + " " + kind, list(Action.DISCARD), actions(state, kind));
            }
        }
    }

    @Test
    public void aConflictIsAppliedAgainOrTheServerVersionKept() {
        for (OperationKind kind : new OperationKind[]{OperationKind.TASK_UPDATE, OperationKind.TASK_PATCH, OperationKind.TASK_COMPLETE,
            OperationKind.TASK_DELETE, OperationKind.LIST_UPDATE, OperationKind.LIST_DELETE}) {
            assertEquals(kind.name(), list(Action.APPLY_MINE, Action.KEEP_SERVER), conflict(kind, ConflictReason.REVISION_CHANGED, false));
        }
        // Fields changed elsewhere: Keep mine / Use the other version.
        assertEquals(list(Action.KEEP_MINE, Action.USE_OTHER), conflict(OperationKind.TASK_PATCH, ConflictReason.FIELD_CHANGED, true));
        assertEquals(list(Action.KEEP_MINE, Action.USE_OTHER), conflict(OperationKind.TASK_PATCH, ConflictReason.REVISION_CHANGED, true));
        // The list's tasks changed: the delete is asked again.
        assertEquals(list(Action.DELETE_AGAIN, Action.KEEP_SERVER), conflict(OperationKind.LIST_DELETE, ConflictReason.MEMBERS_CHANGED, false));
        // A completed task cannot be shared: applying again would only conflict again.
        assertEquals(list(Action.KEEP_SERVER), conflict(OperationKind.INVITATION_REQUEST, ConflictReason.COMPLETED_TASK, false));
        assertEquals(list(Action.APPLY_MINE, Action.KEEP_SERVER), conflict(OperationKind.INVITATION_REQUEST, ConflictReason.INVITATION_CHANGED, false));
        for (OperationKind kind : new OperationKind[]{OperationKind.LIST_CREATE, OperationKind.TASK_CREATE, OperationKind.INVITATION_RESPOND,
            OperationKind.INVITATION_REVOKE, OperationKind.SENDER_BLOCK, OperationKind.SENDER_UNBLOCK}) {
            assertEquals(kind.name(), list(Action.KEEP_SERVER), conflict(kind, ConflictReason.REVISION_CHANGED, false));
        }
    }

    @Test
    public void aChangeLeftBehindByADiscardHasNoServerVersionToKeep() {
        assertEquals(list(Action.APPLY_MINE, Action.DISCARD),
            ZgCloudIssues.actions(PendingState.CONFLICT, OperationKind.TASK_COMPLETE, null, false, DROPPED, false, true));
        assertEquals(list(Action.APPLY_MINE, Action.DISCARD),
            ZgCloudIssues.actions(PendingState.CONFLICT, OperationKind.TASK_PATCH, null, false, DROPPED, false, true));
        // A task whose list's create was discarded: sent again it would name a list that is gone.
        assertEquals(list(Action.SAVE_AS_NEW, Action.DISCARD),
            ZgCloudIssues.actions(PendingState.CONFLICT, OperationKind.TASK_CREATE, null, false, DROPPED, false, true));
        assertEquals(list(Action.DISCARD),
            ZgCloudIssues.actions(PendingState.CONFLICT, OperationKind.TASK_CREATE, null, false, DROPPED, true, true));
        assertEquals(list(Action.DISCARD),
            ZgCloudIssues.actions(PendingState.CONFLICT, OperationKind.INVITATION_RESPOND, null, false, DROPPED, false, true));
        assertEquals(Display.DEPENDENCY_DROPPED, ZgCloudIssues.display(PendingState.CONFLICT, null, false, DROPPED, Status.READY, null));
        // A server conflict whose reason this build does not know reads as a plain one.
        assertEquals(Display.CONFLICT_REVISION, ZgCloudIssues.display(PendingState.CONFLICT, null, false, "tasks_conflict", Status.READY, null));
    }

    @Test
    public void onlyDiscardingRunsWhileReadOnlyAndCreatingAgainIsGated() {
        for (Action action : Action.values()) {
            final boolean discards = action == Action.DISCARD || action == Action.KEEP_SERVER || action == Action.USE_OTHER;
            assertEquals(action.name(), discards, action.discards());
        }
        assertTrue(Action.APPLY_MINE.reapplies() && Action.KEEP_MINE.reapplies() && Action.DELETE_AGAIN.reapplies());
        assertFalse(Action.RETRY.reapplies() || Action.DISCARD.reapplies());

        for (OperationKind kind : OperationKind.values()) {
            assertTrue(ZgCloudIssues.needsCreateGate(Action.SAVE_AS_NEW, kind));
            final boolean create = kind == OperationKind.TASK_CREATE || kind == OperationKind.LIST_CREATE;
            assertEquals(kind.name(), create, ZgCloudIssues.needsCreateGate(Action.RETRY, kind));
            assertEquals(kind.name(), create, ZgCloudIssues.needsCreateGate(Action.REPAIR_TASK, kind));
            assertEquals(kind.name(), create, ZgCloudIssues.needsCreateGate(Action.REPAIR_LIST, kind));
            for (Action action : new Action[]{Action.APPLY_MINE, Action.KEEP_MINE, Action.DELETE_AGAIN, Action.DISCARD, Action.KEEP_SERVER, Action.USE_OTHER}) {
                assertFalse(action + " " + kind, ZgCloudIssues.needsCreateGate(action, kind));
            }
        }
    }

    @Test
    public void aWaitingEntryReadsAsTheReasonTheAccountIsNotMoving() {
        assertEquals(Display.WAITING, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.READY, null));
        assertEquals(Display.WAITING, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.LOADING, null));
        assertEquals(Display.RETRYING, ZgCloudIssues.display(PendingState.BACKOFF, null, false, "offline", Status.READY, null));
        assertEquals(Display.PAUSED, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.UNLINKED_PAUSED, null));
        assertEquals(Display.SESSION_PROBLEM, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.SESSION_INVALID, null));
        assertEquals(Display.SESSION_PROBLEM, ZgCloudIssues.display(PendingState.BACKOFF, null, false, null, Status.NOT_LINKED, null));
        assertEquals(Display.OFFLINE, ZgCloudIssues.display(PendingState.BACKOFF, null, false, null, Status.OFFLINE, null));
        assertEquals(Display.SERVICE_UNAVAILABLE, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.UNAVAILABLE, null));
        // The consent hold comes first.
        assertEquals(Display.CONSENT_REQUIRED, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.CONSENT_REQUIRED, ZgCloudReadOnly.CONSENT_OFF));
        assertEquals(Display.CONSENT_REQUIRED, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.CONSENT_REQUIRED, ZgCloudReadOnly.WITHDRAWN));
        assertEquals(Display.CONSENT_FENCED, ZgCloudIssues.display(PendingState.BACKOFF, null, false, null, Status.CONSENT_FENCED, ZgCloudReadOnly.CONSENT_FENCED));
        // A consent that only waits for the link queues changes: plain waiting.
        assertEquals(Display.WAITING, ZgCloudIssues.display(PendingState.QUEUED, null, false, null, Status.CONSENT_REQUIRED, null));
    }

    @Test
    public void everyOtherStateHasItsOwnLine() {
        assertEquals(Display.SENDING, ZgCloudIssues.display(PendingState.SENDING, null, false, null, Status.OFFLINE, null));
        assertEquals(Display.WAITING_FOR_EARLIER, ZgCloudIssues.display(PendingState.WAITING, null, false, null, Status.OFFLINE, null));
        assertEquals(Display.STALLED, ZgCloudIssues.display(PendingState.STALLED, null, false, "offline", Status.READY, null));
        assertEquals(Display.CREATION_NOT_PERMITTED, ZgCloudIssues.display(PendingState.BLOCKED_CREATION, null, false, null, Status.READY, null));
        assertEquals(Display.INVALID, ZgCloudIssues.display(PendingState.INVALID, null, false, null, Status.READY, null));
        assertEquals(Display.INTEGRITY, ZgCloudIssues.display(PendingState.QUARANTINED, null, false, null, Status.READY, null));
        assertEquals(Display.NOT_FOUND, ZgCloudIssues.display(PendingState.NOT_FOUND, null, false, null, Status.READY, null));
        assertEquals(Display.ACCOUNT_CHANGED, ZgCloudIssues.display(PendingState.WRONG_PRINCIPAL, null, false, null, Status.READY, null));
        assertEquals(Display.CONFLICT_REVISION, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.REVISION_CHANGED, false, null, Status.READY, null));
        assertEquals(Display.CONFLICT_INVITATION_CHANGED, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.INVITATION_CHANGED, false, null, Status.READY, null));
        assertEquals(Display.CONFLICT_INVITATION_EXPIRED, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.INVITATION_EXPIRED, false, null, Status.READY, null));
        assertEquals(Display.CONFLICT_COMPLETED_TASK, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.COMPLETED_TASK, false, null, Status.READY, null));
        assertEquals(Display.FIELDS_CHANGED, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.FIELD_CHANGED, true, null, Status.READY, null));
        assertEquals(Display.FIELDS_CHANGED, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.REVISION_CHANGED, true, null, Status.READY, null));
        // The list's tasks changed wins over everything else a conflict could say.
        assertEquals(Display.MEMBERS_CHANGED, ZgCloudIssues.display(PendingState.CONFLICT, ConflictReason.MEMBERS_CHANGED, true, null, Status.READY, null));
        // A problem entry is never renamed by the account's state.
        assertEquals(Display.STALLED, ZgCloudIssues.display(PendingState.STALLED, null, false, null, Status.OFFLINE, ZgCloudReadOnly.CONSENT_OFF));
    }

    @Test
    public void tryAgainIsOfferedOnlyWhenASyncCanMoveSomething() {
        assertTrue(ZgCloudIssues.retryResumesSync(Status.READY, null));
        assertTrue(ZgCloudIssues.retryResumesSync(Status.LOADING, null));
        assertTrue(ZgCloudIssues.retryResumesSync(Status.OFFLINE, null));
        assertTrue(ZgCloudIssues.retryResumesSync(Status.UNAVAILABLE, null));
        // Only linking again resumes these.
        assertFalse(ZgCloudIssues.retryResumesSync(Status.NOT_LINKED, null));
        assertFalse(ZgCloudIssues.retryResumesSync(Status.UNLINKED_PAUSED, null));
        assertFalse(ZgCloudIssues.retryResumesSync(Status.SESSION_INVALID, null));
        // The consent gate holds every request: a retry would send nothing.
        assertFalse(ZgCloudIssues.retryResumesSync(Status.CONSENT_REQUIRED, null));
        assertFalse(ZgCloudIssues.retryResumesSync(Status.CONSENT_FENCED, ZgCloudReadOnly.CONSENT_FENCED));
        assertFalse(ZgCloudIssues.retryResumesSync(Status.READY, ZgCloudReadOnly.CONSENT_OFF));
        assertFalse(ZgCloudIssues.retryResumesSync(Status.STORAGE_FAILED, null));
        assertFalse(ZgCloudIssues.retryResumesSync(Status.CLOSED, null));
        assertFalse(ZgCloudIssues.retryResumesSync(null, null));
    }

    @Test
    public void theAccountIsReadOnlyOnlyUnderAConsentHold() {
        for (Status status : Status.values()) {
            for (Permission permission : Permission.values()) {
                for (boolean withdrawn : new boolean[]{false, true}) {
                    final ZgCloudReadOnly reason = ZgCloudReadOnly.of(status, permission, withdrawn);
                    final String where = status + " " + permission + " " + withdrawn;
                    if (status == Status.CONSENT_FENCED) {
                        assertEquals(where, ZgCloudReadOnly.CONSENT_FENCED, reason);
                    } else if (status == Status.CONSENT_REQUIRED && permission == Permission.FORBIDDEN) {
                        assertEquals(where, withdrawn ? ZgCloudReadOnly.WITHDRAWN : ZgCloudReadOnly.CONSENT_OFF, reason);
                    } else {
                        // Also a consent that only waits for the link: its changes queue on this device.
                        assertNull(where, reason);
                    }
                }
            }
        }
    }
}
