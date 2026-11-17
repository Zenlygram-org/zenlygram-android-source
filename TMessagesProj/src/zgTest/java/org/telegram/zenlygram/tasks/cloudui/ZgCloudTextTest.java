/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudError;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Status;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.util.HashSet;

/**
 * The text tables of the Cloud Tasks screens: every state, refusal, choice and sharing state has a text, and states that
 * mean different things do not share one.
 */
public class ZgCloudTextTest {

    @Test
    public void everyStateOfAnUnsentChangeHasItsOwnLine() {
        final HashSet<Integer> seen = new HashSet<>();
        for (ZgCloudIssues.Display display : ZgCloudIssues.Display.values()) {
            final int res = ZgCloudText.stateRes(display);
            assertNotEquals(display.name(), 0, res);
            assertTrue(display.name(), seen.add(res));
        }
    }

    @Test
    public void everyChoiceHasItsOwnTitle() {
        final HashSet<Integer> seen = new HashSet<>();
        for (ZgCloudIssues.Action action : ZgCloudIssues.Action.values()) {
            final int res = ZgCloudText.actionRes(action);
            assertNotEquals(action.name(), 0, res);
            assertTrue(action.name(), seen.add(res));
        }
        assertEquals(R.string.ZG_cloudui_action_retry, ZgCloudText.actionRes(ZgCloudIssues.Action.RETRY));
        assertEquals(R.string.ZG_cloudui_action_keepServer, ZgCloudText.actionRes(ZgCloudIssues.Action.KEEP_SERVER));
        assertEquals(R.string.ZG_cloudui_action_saveAsNew, ZgCloudText.actionRes(ZgCloudIssues.Action.SAVE_AS_NEW));
    }

    @Test
    public void everyOperationIsNamedAndAFieldEditIsATaskEdit() {
        final HashSet<Integer> seen = new HashSet<>();
        for (TasksWire.OperationKind kind : TasksWire.OperationKind.values()) {
            final int res = ZgCloudText.operationRes(kind);
            assertNotEquals(kind.name(), 0, res);
            seen.add(res);
        }
        // Twelve user-facing kinds for thirteen wire kinds.
        assertEquals(TasksWire.OperationKind.values().length - 1, seen.size());
        assertEquals(ZgCloudText.operationRes(TasksWire.OperationKind.TASK_UPDATE), ZgCloudText.operationRes(TasksWire.OperationKind.TASK_PATCH));
    }

    @Test
    public void theSharingStateAndTheRecipientAccessAreAllWorded() {
        final HashSet<Integer> states = new HashSet<>();
        for (ZgCloudSharing.State state : ZgCloudSharing.State.values()) {
            final int res = ZgCloudText.sharingStateRes(state);
            assertNotEquals(state.name(), 0, res);
            assertTrue(state.name(), states.add(res));
        }
        assertEquals(R.string.ZG_cloudui_assignment_pending, ZgCloudText.sharingStateRes(ZgCloudSharing.State.PENDING));
        assertEquals(R.string.ZG_cloudui_assignment_accepted, ZgCloudText.sharingStateRes(ZgCloudSharing.State.ACCEPTED));
        assertEquals(R.string.ZG_cloudui_assignment_declined, ZgCloudText.sharingStateRes(ZgCloudSharing.State.DECLINED));
        assertEquals(R.string.ZG_cloudui_assignment_revoked, ZgCloudText.sharingStateRes(ZgCloudSharing.State.REVOKED));
        assertEquals(R.string.ZG_cloudui_assignment_left, ZgCloudText.sharingStateRes(ZgCloudSharing.State.LEFT));
        assertEquals(R.string.ZG_cloudui_assignment_expired, ZgCloudText.sharingStateRes(ZgCloudSharing.State.EXPIRED));
        // A change made here is never worded as delivered.
        assertEquals(R.string.ZG_cloudui_syncConfirmationPending, ZgCloudText.sharingStateRes(ZgCloudSharing.State.CONFIRMING));

        final HashSet<Integer> access = new HashSet<>();
        for (ZgCloudSharing.Access value : ZgCloudSharing.Access.values()) {
            final int res = ZgCloudText.accessRes(value);
            assertNotEquals(value.name(), 0, res);
            assertTrue(value.name(), access.add(res));
        }
    }

    @Test
    public void everyRefusalIsExplained() {
        for (TasksCloudError.Code code : TasksCloudError.Code.values()) {
            assertNotEquals(code.name(), 0, ZgCloudText.errorRes(code));
        }
        assertEquals(R.string.ZG_cloudui_error_gone, ZgCloudText.errorRes(TasksCloudError.Code.NOT_FOUND));
        assertEquals(R.string.ZG_cloudui_error_notOwner, ZgCloudText.errorRes(TasksCloudError.Code.NOT_OWNER));
        assertEquals(R.string.ZenlyCloudTasksWithdrawn, ZgCloudText.errorRes(TasksCloudError.Code.CONSENT_WITHDRAWN));
        assertEquals(ZgCloudText.errorRes(TasksCloudError.Code.NOT_LINKED), ZgCloudText.errorRes(TasksCloudError.Code.CLOSED));
    }

    @Test
    public void aBrokenRuleIsSaidOnItsField() {
        // The names the wire gives the fields of a full update, a field edit and a create.
        assertEquals(R.string.ZG_cloudui_error_title, ZgCloudText.fieldErrorRes("content.title"));
        assertEquals(R.string.ZG_cloudui_error_title, ZgCloudText.fieldErrorRes("set.title"));
        assertEquals(R.string.ZG_cloudui_error_notes, ZgCloudText.fieldErrorRes("content.description"));
        assertEquals(R.string.ZG_cloudui_error_notes, ZgCloudText.fieldErrorRes("set.description"));
        assertEquals(R.string.ZenlyCloudTasksDueTooLate, ZgCloudText.fieldErrorRes("content.due_at"));
        assertEquals(R.string.ZG_cloudui_error_recipient, ZgCloudText.fieldErrorRes("annotation.display_name"));
        assertEquals(R.string.ZG_cloudui_error_recipient, ZgCloudText.fieldErrorRes("set.annotation"));
        assertEquals(R.string.ZG_cloudui_error_recipient, ZgCloudText.fieldErrorRes("recipient_tg_id"));
        // Sharing a task that is done.
        assertEquals(R.string.ZG_tasks_assign_footer_completedTask, ZgCloudText.fieldErrorRes("is_completed"));
        assertEquals(R.string.ZenlyCloudTasksInvalid, ZgCloudText.fieldErrorRes("size"));
        assertEquals(R.string.ZenlyCloudTasksInvalid, ZgCloudText.fieldErrorRes(null));
    }

    @Test
    public void everyHoldPriorityFieldAndNoticeIsWorded() {
        final HashSet<Integer> holds = new HashSet<>();
        for (ZgCloudReadOnly reason : ZgCloudReadOnly.values()) {
            assertTrue(reason.name(), holds.add(ZgCloudText.readOnlyRes(reason)));
        }
        final HashSet<Integer> priorities = new HashSet<>();
        for (int priority = ZgCloudDraft.PRIORITY_MIN; priority <= ZgCloudDraft.PRIORITY_MAX; priority++) {
            assertTrue(String.valueOf(priority), priorities.add(ZgCloudText.priorityRes(priority)));
        }
        // Anything outside the wire's range reads as "None".
        assertEquals(R.string.ZG_cloudui_priority_none, ZgCloudText.priorityRes(9));
        assertEquals(R.string.ZG_cloudui_priority_none, ZgCloudText.priorityRes(-1));
        final HashSet<Integer> fields = new HashSet<>();
        for (TasksWire.ContentField field : TasksWire.ContentField.values()) {
            assertTrue(field.name(), fields.add(ZgCloudText.fieldRes(field)));
        }
        // The wire's annotation is the editor's Assignee.
        assertEquals(R.string.ZG_tasks_assignee_title, ZgCloudText.fieldRes(TasksWire.ContentField.ANNOTATION));
        final HashSet<Integer> notices = new HashSet<>();
        for (TasksCloudState.Notice.Kind kind : TasksCloudState.Notice.Kind.values()) {
            assertTrue(kind.name(), notices.add(ZgCloudText.noticeRes(kind)));
        }
    }

    @Test
    public void nothingIsCalledEmptyBeforeItWasLoaded() {
        // Signed out, or the slot holds someone else.
        assertEquals(R.string.ZenlyCloudTasksStatusClosed, ZgCloudText.unavailableRes(false, Status.READY, true, false, false));
        assertEquals(R.string.ZenlyCloudTasksStatusClosed, ZgCloudText.unavailableRes(true, Status.CLOSED, false, false, true));
        assertEquals(R.string.ZenlyCloudTasksStatusError, ZgCloudText.unavailableRes(true, Status.STORAGE_FAILED, false, false, true));
        // Never linked: nothing was ever loaded. Unlinked with a cache: the cache is shown.
        assertEquals(R.string.ZenlyCloudTasksStatusNotLinked, ZgCloudText.unavailableRes(true, Status.NOT_LINKED, false, false, true));
        assertEquals(0, ZgCloudText.unavailableRes(true, Status.NOT_LINKED, true, false, false));
        // The first snapshot is still on its way.
        assertEquals(R.string.ZenlyCloudTasksStatusLoading, ZgCloudText.unavailableRes(true, Status.LOADING, false, false, true));
        assertEquals(R.string.ZenlyCloudTasksStatusLoading, ZgCloudText.unavailableRes(true, Status.READY, false, true, false));
        assertEquals(R.string.ZenlyCloudTasksNotLoaded, ZgCloudText.unavailableRes(true, Status.OFFLINE, false, false, false));
        // Loaded: the screen may say what it has, also while offline or under a consent hold.
        for (Status status : new Status[]{Status.READY, Status.OFFLINE, Status.UNLINKED_PAUSED, Status.SESSION_INVALID, Status.UNAVAILABLE,
            Status.CONSENT_REQUIRED, Status.CONSENT_FENCED, Status.LOADING}) {
            assertEquals(status.name(), 0, ZgCloudText.unavailableRes(true, status, true, false, false));
        }
    }
}
