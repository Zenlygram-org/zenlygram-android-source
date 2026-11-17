/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import java.util.Objects;

/**
 * The person a task is for: one of the user's own Telegram contacts (iOS TodoAssignee).
 *
 * <p>An annotation of the task's owner, never an invitation: the contact is not messaged or notified.
 * <ul>
 *   <li>A device task keeps it on this device only (the side store {@link ZgTaskAssigneeStore}); it is never sent
 *       anywhere, and the copy of the device tasks to Cloud leaves it out.</li>
 *   <li>A Cloud task carries it as its owner-only annotation: only the owner ever sees it.</li>
 * </ul>
 * {@link #peerId} is the raw Telegram user id (&gt; 0); {@link #displayName} is the name shown, already trimmed and
 * checked by {@link org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy#validatedAssignee}.
 */
public final class ZgTodoAssignee {

    public final long peerId;
    public final String displayName;

    public ZgTodoAssignee(long peerId, String displayName) {
        this.peerId = peerId;
        this.displayName = displayName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgTodoAssignee)) {
            return false;
        }
        final ZgTodoAssignee other = (ZgTodoAssignee) o;
        return peerId == other.peerId && Objects.equals(displayName, other.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId, displayName);
    }
}
