/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.data;

import java.util.Objects;

/** A list of the old device-wide board (iOS TodoFolder), as {@link ZgTodoStore} reads it. Immutable; Unix seconds. */
public final class ZgTodoFolder {

    public final String id;
    public final String title;
    public final int sortOrder;
    public final long createdAt;

    public ZgTodoFolder(String id, String title, int sortOrder, long createdAt) {
        this.id = id;
        this.title = title;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgTodoFolder)) {
            return false;
        }
        final ZgTodoFolder other = (ZgTodoFolder) o;
        return sortOrder == other.sortOrder && createdAt == other.createdAt
            && Objects.equals(id, other.id) && Objects.equals(title, other.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, sortOrder, createdAt);
    }
}
