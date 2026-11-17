/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import java.util.Objects;

/**
 * One task of the old device-wide board (iOS TodoTask), as {@link ZgTodoStore} reads it for the one-time import.
 * Immutable; timestamps are Unix seconds.
 *
 * <ul>
 *   <li>{@link #dueAt}: the deadline, null for none. 0 (1970-01-01 00:00:00 UTC) is a real deadline.</li>
 *   <li>{@link #completedAt} is set exactly while {@link #completed} is true.</li>
 *   <li>{@link #description} and {@link #priority} were never shown or edited on the device board; the import
 *       carries them as they are.</li>
 * </ul>
 */
public final class ZgTodoTask {

    public final String id;
    public final String folderId;
    public final String title;
    public final String description;
    public final Long dueAt;
    public final int priority;
    public final boolean completed;
    public final Long completedAt;
    public final int sortOrder;
    public final long createdAt;
    public final long updatedAt;
    public final ZgTodoAssignee assignee;

    public ZgTodoTask(String id, String folderId, String title, String description, Long dueAt, int priority,
                      boolean completed, Long completedAt, int sortOrder, long createdAt, long updatedAt,
                      ZgTodoAssignee assignee) {
        this.id = id;
        this.folderId = folderId;
        this.title = title;
        this.description = description;
        this.dueAt = dueAt;
        this.priority = priority;
        this.completed = completed;
        this.completedAt = completedAt;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.assignee = assignee;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgTodoTask)) {
            return false;
        }
        final ZgTodoTask t = (ZgTodoTask) o;
        return priority == t.priority && completed == t.completed && sortOrder == t.sortOrder
            && createdAt == t.createdAt && updatedAt == t.updatedAt
            && Objects.equals(id, t.id) && Objects.equals(folderId, t.folderId) && Objects.equals(title, t.title)
            && Objects.equals(description, t.description) && Objects.equals(dueAt, t.dueAt)
            && Objects.equals(completedAt, t.completedAt) && Objects.equals(assignee, t.assignee);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, folderId, title, description, dueAt, priority, completed, completedAt, sortOrder,
            createdAt, updatedAt, assignee);
    }
}
