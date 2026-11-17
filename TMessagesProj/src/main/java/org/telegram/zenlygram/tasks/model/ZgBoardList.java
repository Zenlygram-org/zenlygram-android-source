/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import java.util.Objects;

/** One list as the board and the Lists screen show it, from either store (iOS TodoFolder / SGTasksCloudList). Immutable. */
public final class ZgBoardList {

    public final String id;
    /** The list's own name; empty for the device Inbox, whose name the UI localizes. */
    public final String title;
    /**
     * The device's built-in Inbox: tasks added under "All" go there. It is listed only while it holds a task, cannot be
     * renamed, and "deleting" it deletes its tasks. Cloud has no such list: its Inbox is the tasks without a list.
     */
    public final boolean inbox;
    /** Whether the list's latest change reached the server; a device list is always synced. */
    public final ZgBoardTask.Sync sync;

    public ZgBoardList(String id, String title, boolean inbox, ZgBoardTask.Sync sync) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.inbox = inbox;
        this.sync = sync == null ? ZgBoardTask.Sync.SYNCED : sync;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZgBoardList)) {
            return false;
        }
        final ZgBoardList other = (ZgBoardList) o;
        return inbox == other.inbox && sync == other.sync && Objects.equals(id, other.id) && Objects.equals(title, other.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, inbox, sync);
    }
}
