/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksAssigneeDisplay;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;

import java.util.Objects;

/**
 * One task as the board shows it, from either store (iOS TodoTask on the device board, TasksCloudTaskDisplay with its
 * TasksCloudTaskPermissions on the Cloud board). Immutable; times are Unix seconds. The board never reads a store
 * row directly: what a row may do is decided once, where the row is built, and carried here.
 */
public final class ZgBoardTask {

    /** Whether the task's latest change reached the server. A device task is always {@link #SYNCED}. */
    public enum Sync {
        SYNCED,
        /** Saved on this device, not handed to the network yet. */
        NOT_SENT,
        /** Handed to the network; the server's confirmation has not arrived. */
        CONFIRMING,
        /** Refused or stuck: it waits for a choice in Unsent Changes. */
        NEEDS_ATTENTION
    }

    public final String id;
    /** The list the task is in; null for the Cloud Inbox and for a task shared with this account. */
    public final String listId;
    public final String title;
    /** The deadline, null for none. */
    public final Long dueAt;
    public final boolean completed;
    /** When it was completed; null when open or unknown. */
    public final Long completedAt;
    /** Completed on this device a moment ago: the time is not known yet, and it sorts first, not last. */
    public final boolean completedJustNow;
    /** The device board's assignee: a note kept on this device only. Null on the Cloud board. */
    public final ZgTodoAssignee assignee;
    /** Assigned to this account by this Telegram user id (decimal); null for an own task. */
    public final String sharedByTgId;
    public final Sync sync;
    /**
     * Cloud, own task: whom the assignee row names (a decimal Telegram id) — the request on its way, the deferred pick,
     * the assignment's recipient, or the contact of an older owner-only note. Null when nobody is assigned.
     */
    public final String assigneeTgId;
    /** Cloud, own task: the name an older owner-only note holds for {@link #assigneeTgId}; null otherwise. */
    public final String assigneeNoteName;
    /** Cloud, own task: the owner's state word for the assignee (iOS TasksAssigneeDisplayState); null when nobody is assigned. */
    public final TasksAssigneeDisplay.State assigneeState;
    /**
     * An "Assigned to me" row whose automatic accept is on its way: the invitation's preview (title, deadline, sender),
     * no access yet. It carries the task's id, which the accepted row keeps when it arrives in its place.
     */
    public final boolean provisional;
    public final boolean canComplete;
    /** The row opens for editing (else read-only). */
    public final boolean canEdit;
    public final boolean canDelete;
    /** A task shared with this account can be left instead of deleted. */
    public final boolean canLeave;
    /** A shared task without an accepted invitation: nothing but opening it is offered, and the row says why. */
    public final boolean accessInactive;

    private ZgBoardTask(Builder b) {
        this.id = b.id;
        this.listId = b.listId;
        this.title = b.title;
        this.dueAt = b.dueAt;
        this.completed = b.completed;
        this.completedAt = b.completed ? b.completedAt : null;
        this.completedJustNow = b.completed && b.completedAt == null && b.completedJustNow;
        this.assignee = b.assignee;
        this.sharedByTgId = b.sharedByTgId;
        this.sync = b.sync;
        this.assigneeTgId = b.assigneeTgId;
        this.assigneeNoteName = b.assigneeNoteName;
        this.assigneeState = b.assigneeState;
        this.provisional = b.provisional;
        this.canComplete = b.canComplete;
        this.canEdit = b.canEdit;
        this.canDelete = b.canDelete;
        this.canLeave = b.canLeave;
        this.accessInactive = b.accessInactive;
    }

    public boolean isShared() {
        return sharedByTgId != null;
    }

    /** The key of the Completed section, most recently completed first: an unknown time (an imported task) is last. */
    long completedSortKey() {
        if (completedAt != null) {
            return completedAt;
        }
        return completedJustNow ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgBoardTask)) {
            return false;
        }
        final ZgBoardTask t = (ZgBoardTask) o;
        return completed == t.completed && completedJustNow == t.completedJustNow && canComplete == t.canComplete
            && canEdit == t.canEdit && canDelete == t.canDelete && canLeave == t.canLeave && accessInactive == t.accessInactive
            && sync == t.sync && assigneeState == t.assigneeState && provisional == t.provisional && Objects.equals(id, t.id)
            && Objects.equals(listId, t.listId) && Objects.equals(title, t.title) && Objects.equals(dueAt, t.dueAt)
            && Objects.equals(completedAt, t.completedAt) && Objects.equals(assignee, t.assignee)
            && Objects.equals(sharedByTgId, t.sharedByTgId) && Objects.equals(assigneeTgId, t.assigneeTgId)
            && Objects.equals(assigneeNoteName, t.assigneeNoteName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, listId, title, dueAt, completed, completedAt, assignee, sharedByTgId, sync, assigneeTgId, assigneeState, provisional);
    }

    public static final class Builder {
        private final String id;
        private final String title;
        private String listId;
        private Long dueAt;
        private boolean completed;
        private Long completedAt;
        private boolean completedJustNow;
        private ZgTodoAssignee assignee;
        private String sharedByTgId;
        private Sync sync = Sync.SYNCED;
        private String assigneeTgId;
        private String assigneeNoteName;
        private TasksAssigneeDisplay.State assigneeState;
        private boolean provisional;
        private boolean canComplete;
        private boolean canEdit;
        private boolean canDelete;
        private boolean canLeave;
        private boolean accessInactive;

        public Builder(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public Builder list(String listId) {
            this.listId = listId;
            return this;
        }

        public Builder due(Long dueAt) {
            this.dueAt = dueAt;
            return this;
        }

        public Builder completed(boolean completed, Long completedAt, boolean justNow) {
            this.completed = completed;
            this.completedAt = completedAt;
            this.completedJustNow = justNow;
            return this;
        }

        public Builder assignee(ZgTodoAssignee assignee) {
            this.assignee = assignee;
            return this;
        }

        public Builder sharedBy(String ownerTgId) {
            this.sharedByTgId = ownerTgId;
            return this;
        }

        public Builder sync(Sync sync) {
            this.sync = sync;
            return this;
        }

        /** The Cloud owner's assignee: who, the name an older note holds for them (or null), and the state word. */
        public Builder assignment(String assigneeTgId, String noteName, TasksAssigneeDisplay.State state) {
            this.assigneeTgId = assigneeTgId;
            this.assigneeNoteName = noteName;
            this.assigneeState = state;
            return this;
        }

        /** An automatic accept on its way: the row is the invitation's preview. */
        public Builder provisional() {
            this.provisional = true;
            return this;
        }

        /** The owner of a writable board: complete, edit and delete. */
        public Builder ownerRights() {
            this.canComplete = true;
            this.canEdit = true;
            this.canDelete = true;
            this.canLeave = false;
            return this;
        }

        /** The accepted recipient of a shared task: complete / reopen and Leave, nothing else. */
        public Builder recipientRights() {
            this.canComplete = true;
            this.canEdit = false;
            this.canDelete = false;
            this.canLeave = true;
            return this;
        }

        public Builder accessInactive(boolean inactive) {
            this.accessInactive = inactive;
            return this;
        }

        public ZgBoardTask build() {
            return new ZgBoardTask(this);
        }
    }
}
