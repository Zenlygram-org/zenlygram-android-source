/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.ListView;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Notice;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Pending;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.PendingState;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Status;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.TaskView;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the repository's immutable view for the board tests. Only the engine creates these values in the app (their
 * constructors are package-private there), so the tests reach the same constructors by reflection instead of adding a
 * test door to the engine. The wire values inside are made by the wire types' own, validating constructors.
 */
final class ZgCloudFixtures {

    static final String OWN_TG = "1001";
    static final String OTHER_TG = "2002";
    static final String CREATED = "2026-11-01T10:00:00Z";
    static final String EXPIRES = "2026-11-20T10:00:00Z";
    static final long BEFORE_EXPIRY = TasksWire.epochSeconds("2026-11-14T10:00:00Z");
    static final long AFTER_EXPIRY = TasksWire.epochSeconds("2026-11-21T10:00:00Z");

    private ZgCloudFixtures() {
    }

    /** A valid wire id that differs by {@code n} (0..255). */
    static String id(int n) {
        return String.format("%08x-1111-4111-8111-111111111111", n);
    }

    static final class StateBuilder {
        Status status = Status.READY;
        boolean complete = true;
        final List<ListView> lists = new ArrayList<>();
        final List<TaskView> tasks = new ArrayList<>();
        final List<TasksWire.InvitationItem> invitations = new ArrayList<>();
        final List<Pending> pending = new ArrayList<>();
        final List<Notice> notices = new ArrayList<>();
        /** Invitations whose automatic accept is on its way (the provisional rows). */
        final List<TasksWire.InvitationItem> provisional = new ArrayList<>();

        StateBuilder provisional(TasksWire.InvitationItem invitation) {
            provisional.add(invitation);
            return this;
        }

        StateBuilder status(Status status) {
            this.status = status;
            return this;
        }

        StateBuilder complete(boolean complete) {
            this.complete = complete;
            return this;
        }

        StateBuilder list(ListView list) {
            lists.add(list);
            return this;
        }

        StateBuilder task(TaskView task) {
            tasks.add(task);
            return this;
        }

        StateBuilder invitation(TasksWire.InvitationItem invitation) {
            invitations.add(invitation);
            return this;
        }

        StateBuilder pending(Pending entry) {
            pending.add(entry);
            return this;
        }

        StateBuilder notice(Notice notice) {
            notices.add(notice);
            return this;
        }

        TasksCloudState build() {
            return create(TasksCloudState.class,
                new Class<?>[]{Status.class, boolean.class, boolean.class, long.class, String.class, String.class, List.class, List.class,
                    List.class, List.class, List.class, List.class, int.class, int.class, List.class},
                status, false, complete, Long.parseLong(OWN_TG), "principal", CREATED, lists, tasks, invitations,
                Collections.<TasksWire.BlockItem>emptyList(), pending, notices, 0, 0, provisional);
        }
    }

    static StateBuilder state() {
        return new StateBuilder();
    }

    /** A confirmed list. */
    static ListView list(String id, String title, int sortOrder) {
        return list(id, title, sortOrder, null);
    }

    static ListView list(String id, String title, int sortOrder, Pending pending) {
        final TasksWire.ListItem server = pending == null ? new TasksWire.ListItem(id, "1", title, sortOrder, CREATED, CREATED) : null;
        return create(ListView.class, new Class<?>[]{String.class, TasksWire.ListItem.class, String.class, int.class, Pending.class},
            id, server, title, sortOrder, pending);
    }

    static TasksWire.Content content(String listId, String title, int sortOrder) {
        return new TasksWire.Content(listId, title, null, null, 0, sortOrder, null);
    }

    /** A confirmed, open task of this account. */
    static TaskView own(String id, TasksWire.Content content) {
        return own(id, content, false, null, null, null, null);
    }

    /**
     * A task of this account. {@code completedAt} (an instant) only for a completed task the server confirmed;
     * {@code pending} = its latest change is not confirmed yet.
     */
    static TaskView own(String id, TasksWire.Content content, boolean completed, String completedAt, TasksWire.Assignment assignment,
                        String liveInvitationId, Pending pending) {
        // The server's row as last synced: completion as the server knows it.
        final boolean serverCompleted = completed && completedAt != null;
        final TasksWire.TaskItem server = new TasksWire.TaskItem(id, "1", OWN_TG, TasksWire.ViewerRole.OWNER, content, serverCompleted,
            serverCompleted ? completedAt : null, CREATED, CREATED, assignment);
        return create(TaskView.class,
            new Class<?>[]{String.class, TasksWire.TaskItem.class, TasksWire.Content.class, boolean.class, boolean.class,
                TasksWire.Assignment.class, String.class, Pending.class},
            id, server, content, completed, true, assignment, liveInvitationId, pending);
    }

    /**
     * A confirmed task of this account with what the engine knows about its assignee beyond the synced assignment: the
     * recipient of this device's own request ({@code requestAcknowledged}: the server has it, the pull has not shown it)
     * and the recipient of a deferred assignment.
     */
    static TaskView assigned(String id, TasksWire.Content content, boolean completed, TasksWire.Assignment assignment, Pending pending,
                             String requestRecipient, boolean requestAcknowledged, String deferredRecipient) {
        final TasksWire.TaskItem server = new TasksWire.TaskItem(id, "1", OWN_TG, TasksWire.ViewerRole.OWNER, content, completed,
            completed ? CREATED : null, CREATED, CREATED, assignment);
        return create(TaskView.class,
            new Class<?>[]{String.class, TasksWire.TaskItem.class, TasksWire.Content.class, boolean.class, boolean.class,
                TasksWire.Assignment.class, String.class, Pending.class, String.class, boolean.class, String.class},
            id, server, content, completed, true, assignment, assignment != null ? assignment.invitationId : null, pending,
            requestRecipient, requestAcknowledged, deferredRecipient);
    }

    /** An outbox entry with the operation it carries (the board tells the device's own accept by it). */
    static Pending pending(String resourceId, TasksWire.Operation draft, PendingState state) {
        return create(Pending.class,
            new Class<?>[]{String.class, TasksWire.OperationKind.class, String.class, PendingState.class, String.class,
                TasksWire.ConflictReason.class, boolean.class, TasksWire.Operation.class, boolean.class, List.class},
            "m-" + resourceId + "-" + draft.kind.name() + "-" + state.name(), draft.kind, resourceId, state, null, null, false, draft, false,
            Collections.<TasksWire.ContentField>emptyList());
    }

    /** A task this device created and the server has not confirmed: no server row at all. */
    static TaskView unconfirmed(String id, TasksWire.Content content, Pending pending) {
        return create(TaskView.class,
            new Class<?>[]{String.class, TasksWire.TaskItem.class, TasksWire.Content.class, boolean.class, boolean.class,
                TasksWire.Assignment.class, String.class, Pending.class},
            id, null, content, false, true, null, null, pending);
    }

    /** A task {@link #OTHER_TG} shares with this account under an accepted invitation. */
    static TaskView shared(String id, String title, int sortOrder, String invitationId, String liveInvitationId) {
        final TasksWire.Content content = content(null, title, sortOrder);
        final TasksWire.Assignment assignment = new TasksWire.Assignment(invitationId, OWN_TG, TasksWire.AssignmentState.ACCEPTED, EXPIRES);
        final TasksWire.TaskItem server = new TasksWire.TaskItem(id, "1", OTHER_TG, TasksWire.ViewerRole.ASSIGNEE, content, false, null,
            CREATED, CREATED, assignment);
        return create(TaskView.class,
            new Class<?>[]{String.class, TasksWire.TaskItem.class, TasksWire.Content.class, boolean.class, boolean.class,
                TasksWire.Assignment.class, String.class, Pending.class},
            id, server, content, false, false, assignment, liveInvitationId, null);
    }

    static Pending pending(String resourceId, TasksWire.OperationKind kind, PendingState state, boolean sent) {
        return create(Pending.class,
            new Class<?>[]{String.class, TasksWire.OperationKind.class, String.class, PendingState.class, String.class,
                TasksWire.ConflictReason.class, boolean.class, TasksWire.Operation.class, boolean.class, List.class},
            "m-" + resourceId + "-" + kind.name() + "-" + state.name(), kind, resourceId, state, null, null, sent, null, false,
            Collections.<TasksWire.ContentField>emptyList());
    }

    static Notice notice(long id, Notice.Kind kind) {
        return create(Notice.class, new Class<?>[]{long.class, Notice.Kind.class, String.class}, id, kind, id(200));
    }

    static TasksWire.InvitationItem invitation(String id, String taskId, String expiresAt) {
        return new TasksWire.InvitationItem(id, taskId, "1", OTHER_TG, "Invited task", null, expiresAt);
    }

    private static <T> T create(Class<T> type, Class<?>[] parameters, Object... arguments) {
        try {
            final Constructor<T> constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("The engine's " + type.getSimpleName() + " constructor changed: " + e, e);
        }
    }
}
