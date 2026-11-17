/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The account's device store (ZenlyTasksStore) as a {@link ZgBoard}. Pure Java (JVM tests).
 *
 * <ul>
 *   <li>Order: the built-in Inbox first, then the lists as the store returns them (by creation); inside a list by
 *       creation time, because the store keeps no sort order.</li>
 *   <li>The Inbox is a chip only while it holds a task, as on iOS, where the inbox list appears with its first task.</li>
 *   <li>Times: the store keeps Unix milliseconds, the board Unix seconds.</li>
 *   <li>The assignee is not in the store: it comes from the side store, by task id.</li>
 *   <li>A task whose delete is held for Undo is not shown; it is deleted in the store when the Undo is gone.</li>
 *   <li>An archive (the rows were copied to Cloud) grants no row a change: everything is read-only.</li>
 * </ul>
 */
public final class ZgDeviceBoardMapper {

    private ZgDeviceBoardMapper() {
    }

    /**
     * @param snapshot  the last successful read, null before the first one
     * @param assignees task id to assignee (null = none known yet)
     * @param held      ids of tasks whose delete waits for its Undo (null = none)
     * @param archive   the read-only archive of the account's cut-over, null while the board is writable
     * @param failed    the latest read failed
     */
    public static ZgBoard map(ZenlyTaskModel.Snapshot snapshot, Map<String, ZgTodoAssignee> assignees, Set<String> held,
                              ZgTasksArchive archive, boolean failed) {
        final ZgBoard.Builder board = new ZgBoard.Builder(ZgTasksScope.DEVICE)
            .complete(snapshot != null)
            .failed(failed)
            .archive(archive)
            .readOnly(archive != null ? ZgBoard.ReadOnly.ARCHIVE : null);
        if (snapshot == null) {
            return board.build();
        }
        final HashMap<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < snapshot.folders.size(); i++) {
            ranks.put(snapshot.folders.get(i).id, i);
        }
        final ArrayList<ZenlyTaskModel.Task> ordered = new ArrayList<>(snapshot.tasks.size());
        for (ZenlyTaskModel.Task task : snapshot.tasks) {
            if (held == null || !held.contains(task.id)) {
                ordered.add(task);
            }
        }
        Collections.sort(ordered, (a, b) -> {
            final int ra = rank(ranks, a.folderId);
            final int rb = rank(ranks, b.folderId);
            if (ra != rb) {
                return ra < rb ? -1 : 1;
            }
            if (a.createdAt != b.createdAt) {
                return a.createdAt < b.createdAt ? -1 : 1;
            }
            return a.id.compareTo(b.id);
        });
        boolean inboxUsed = false;
        final ArrayList<ZgBoardTask> tasks = new ArrayList<>(ordered.size());
        for (ZenlyTaskModel.Task task : ordered) {
            inboxUsed |= ZenlyTaskModel.INBOX_ID.equals(task.folderId);
            final ZgBoardTask.Builder row = new ZgBoardTask.Builder(task.id, task.title)
                .list(task.folderId)
                .due(seconds(task.dueAt))
                .completed(task.completed, seconds(task.completedAt), false)
                .assignee(assignees != null ? assignees.get(task.id) : null);
            if (archive == null) {
                row.ownerRights();
            }
            tasks.add(row.build());
        }
        final ArrayList<ZgBoardList> lists = new ArrayList<>(snapshot.folders.size());
        for (ZenlyTaskModel.Folder folder : snapshot.folders) {
            if (ZenlyTaskModel.INBOX_ID.equals(folder.id)) {
                if (inboxUsed) {
                    lists.add(new ZgBoardList(folder.id, "", true, ZgBoardTask.Sync.SYNCED));
                }
            } else {
                lists.add(new ZgBoardList(folder.id, folder.title, false, ZgBoardTask.Sync.SYNCED));
            }
        }
        return board.lists(lists).tasks(tasks).build();
    }

    private static int rank(Map<String, Integer> ranks, String folderId) {
        final Integer rank = ranks.get(folderId);
        return rank != null ? rank : Integer.MAX_VALUE;
    }

    /** Store milliseconds as board seconds, rounded down (the store's times are never negative). */
    public static Long seconds(Long millis) {
        return millis == null ? null : Math.max(0L, millis / 1000L);
    }

    /** Board seconds as the store's milliseconds; the store needs a positive deadline, so second 0 is its first millisecond. */
    public static Long millis(Long seconds) {
        return seconds == null ? null : Math.max(1L, seconds * 1000L);
    }
}
