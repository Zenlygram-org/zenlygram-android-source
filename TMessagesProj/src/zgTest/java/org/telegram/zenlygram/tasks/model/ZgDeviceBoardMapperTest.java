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

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel.Folder;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel.Snapshot;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel.Task;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** The account's device store as the board shows it. */
public class ZgDeviceBoardMapperTest {

    private static final String INBOX = ZenlyTaskModel.INBOX_ID;
    private static final Folder INBOX_FOLDER = new Folder(INBOX, "", 0);

    private static Task open(String id, String folderId, long createdAt) {
        return new Task(id, folderId, "Task " + id, "", null, 0, false, null, createdAt, createdAt);
    }

    private static List<String> ids(List<ZgBoardTask> tasks) {
        final ArrayList<String> ids = new ArrayList<>();
        for (ZgBoardTask task : tasks) {
            ids.add(task.id);
        }
        return ids;
    }

    @Test
    public void beforeTheFirstReadNothingIsClaimed() {
        final ZgBoard board = ZgDeviceBoardMapper.map(null, null, null, null, false);
        assertSame(ZgTasksScope.DEVICE, board.scope);
        assertFalse(board.complete);
        assertFalse(board.failed);
        assertTrue(board.lists.isEmpty());
        assertTrue(board.tasks.isEmpty());
        assertNull(board.gate);
        assertNull(board.readOnly);
        assertTrue(board.canCompose());
        // A failed first read is told apart from an empty board.
        assertTrue(ZgDeviceBoardMapper.map(null, null, null, null, true).failed);
    }

    @Test
    public void tasksFollowTheirListsThenTheirCreationTime() {
        final Snapshot snapshot = new Snapshot(
            Arrays.asList(INBOX_FOLDER, new Folder("work", "Work", 100), new Folder("home", "Home", 200)),
            Arrays.asList(open("h2", "home", 900), open("w2", "work", 800), open("i1", INBOX, 700), open("w1", "work", 300),
                open("h1", "home", 50), open("w1b", "work", 300)));
        final ZgBoard board = ZgDeviceBoardMapper.map(snapshot, null, null, null, false);
        assertTrue(board.complete);
        // The Inbox first, then the lists as the store returns them; inside a list by creation, then by id.
        assertEquals(Arrays.asList("i1", "w1", "w1b", "w2", "h1", "h2"), ids(board.tasks));
        assertEquals(3, board.lists.size());
        assertEquals(INBOX, board.lists.get(0).id);
        assertTrue(board.lists.get(0).inbox);
        assertEquals("", board.lists.get(0).title);
        assertEquals("work", board.lists.get(1).id);
        assertEquals("Work", board.lists.get(1).title);
        assertFalse(board.lists.get(1).inbox);
        assertEquals("home", board.lists.get(2).id);
        for (ZgBoardList list : board.lists) {
            assertSame(ZgBoardTask.Sync.SYNCED, list.sync);
        }
    }

    @Test
    public void theInboxIsAListOnlyWhileItHoldsATask() {
        final Snapshot empty = new Snapshot(Arrays.asList(INBOX_FOLDER, new Folder("work", "Work", 100)),
            Collections.singletonList(open("w1", "work", 300)));
        final ZgBoard board = ZgDeviceBoardMapper.map(empty, null, null, null, false);
        assertEquals(1, board.lists.size());
        assertEquals("work", board.lists.get(0).id);
        assertNull(board.list(INBOX));

        // A fresh store: only the built-in Inbox, no task. Complete, and nothing to show.
        final ZgBoard fresh = ZgDeviceBoardMapper.map(new Snapshot(Collections.singletonList(INBOX_FOLDER), Collections.<Task>emptyList()),
            null, null, null, false);
        assertTrue(fresh.complete);
        assertTrue(fresh.lists.isEmpty());
        assertTrue(fresh.tasks.isEmpty());
    }

    @Test
    public void aTaskHeldForUndoIsNotShownAndFreesTheInboxChip() {
        final Snapshot snapshot = new Snapshot(Collections.singletonList(INBOX_FOLDER), Arrays.asList(open("a", INBOX, 1), open("b", INBOX, 2)));
        final ZgBoard one = ZgDeviceBoardMapper.map(snapshot, null, new HashSet<>(Collections.singletonList("a")), null, false);
        assertEquals(Collections.singletonList("b"), ids(one.tasks));
        assertEquals(1, one.lists.size());
        final ZgBoard none = ZgDeviceBoardMapper.map(snapshot, null, new HashSet<>(Arrays.asList("a", "b")), null, false);
        assertTrue(none.tasks.isEmpty());
        assertTrue(none.lists.isEmpty());
    }

    @Test
    public void storeMillisecondsBecomeBoardSeconds() {
        final Task done = new Task("d", INBOX, "Done", "", 1_800_000_000_999L, 0, true, 1_700_000_000_500L, 10, 20);
        final ZgBoard board = ZgDeviceBoardMapper.map(new Snapshot(Collections.singletonList(INBOX_FOLDER), Collections.singletonList(done)),
            null, null, null, false);
        final ZgBoardTask task = board.task("d");
        assertNotNull(task);
        assertEquals(Long.valueOf(1_800_000_000L), task.dueAt);
        assertTrue(task.completed);
        assertEquals(Long.valueOf(1_700_000_000L), task.completedAt);
        assertFalse(task.completedJustNow);
        assertEquals(INBOX, task.listId);
        assertSame(ZgBoardTask.Sync.SYNCED, task.sync);
        assertNull(task.assigneeState);
        assertNull(task.assigneeTgId);
        assertFalse(task.isShared());

        assertNull(ZgDeviceBoardMapper.seconds(null));
        assertEquals(Long.valueOf(0L), ZgDeviceBoardMapper.seconds(999L));
        assertEquals(Long.valueOf(1L), ZgDeviceBoardMapper.seconds(1_000L));
        assertNull(ZgDeviceBoardMapper.millis(null));
        assertEquals(Long.valueOf(5_000L), ZgDeviceBoardMapper.millis(5L));
        // The store needs a positive deadline: second 0 is its first millisecond, and reads back as second 0.
        assertEquals(Long.valueOf(1L), ZgDeviceBoardMapper.millis(0L));
        assertEquals(Long.valueOf(0L), ZgDeviceBoardMapper.seconds(ZgDeviceBoardMapper.millis(0L)));
    }

    @Test
    public void theAssigneeComesFromTheSideStoreByTaskId() {
        final Snapshot snapshot = new Snapshot(Collections.singletonList(INBOX_FOLDER), Arrays.asList(open("a", INBOX, 1), open("b", INBOX, 2)));
        final HashMap<String, ZgTodoAssignee> assignees = new HashMap<>();
        assignees.put("b", new ZgTodoAssignee(777L, "Ali"));
        assignees.put("gone", new ZgTodoAssignee(888L, "Nobody's task"));
        final ZgBoard board = ZgDeviceBoardMapper.map(snapshot, assignees, null, null, false);
        assertNull(board.task("a").assignee);
        assertEquals(new ZgTodoAssignee(777L, "Ali"), board.task("b").assignee);
    }

    @Test
    public void aWritableBoardGrantsTheOwnersActionsAndAnArchiveNone() {
        final Snapshot snapshot = new Snapshot(Arrays.asList(INBOX_FOLDER, new Folder("work", "Work", 100)),
            Arrays.asList(open("a", INBOX, 1), open("w", "work", 2)));
        final ZgBoard writable = ZgDeviceBoardMapper.map(snapshot, null, null, null, false);
        for (ZgBoardTask task : writable.tasks) {
            assertTrue(task.canComplete);
            assertTrue(task.canEdit);
            assertTrue(task.canDelete);
            assertFalse(task.canLeave);
            assertFalse(task.accessInactive);
        }
        assertTrue(writable.canCompose());
        assertFalse(writable.isReadOnly());
        assertNull(writable.archive);

        final ZgTasksArchive archive = new ZgTasksArchive(ZgTasksArchive.Reason.NEW_ROWS, 0, 0, 2, 0, false);
        final ZgBoard archived = ZgDeviceBoardMapper.map(snapshot, null, null, archive, false);
        assertSame(ZgBoard.ReadOnly.ARCHIVE, archived.readOnly);
        assertSame(archive, archived.archive);
        assertTrue(archived.isUsable());
        assertFalse(archived.canCompose());
        // The rows stay readable; nothing can be changed.
        assertEquals(2, archived.tasks.size());
        assertEquals(2, archived.lists.size());
        for (ZgBoardTask task : archived.tasks) {
            assertFalse(task.canComplete);
            assertFalse(task.canEdit);
            assertFalse(task.canDelete);
            assertFalse(task.canLeave);
        }
    }

    @Test
    public void aTaskOfAnUnknownListIsKeptAfterTheKnownOnes() {
        final Snapshot snapshot = new Snapshot(Arrays.asList(INBOX_FOLDER, new Folder("work", "Work", 100)),
            Arrays.asList(open("lost", "vanished", 1), open("w", "work", 2)));
        final ZgBoard board = ZgDeviceBoardMapper.map(snapshot, null, null, null, false);
        assertEquals(Arrays.asList("w", "lost"), ids(board.tasks));
    }

    @Test
    public void aFailedReadKeepsWhatWasReadBefore() {
        final Snapshot snapshot = new Snapshot(Collections.singletonList(INBOX_FOLDER), Collections.singletonList(open("a", INBOX, 1)));
        final ZgBoard board = ZgDeviceBoardMapper.map(snapshot, null, null, null, true);
        assertTrue(board.failed);
        assertTrue(board.complete);
        assertEquals(1, board.tasks.size());
    }
}
