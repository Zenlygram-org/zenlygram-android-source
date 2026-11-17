/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.data.ZgTodoFolder;
import org.telegram.zenlygram.tasks.data.ZgTodoTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The rows of the old device-wide board as rows of an account's device store: what the one-time import writes. Every
 * row must also pass the device store's table rules, or it would be left out of the import.
 */
public class ZgLegacyTasksMapperTest {

    private static final String UNTITLED_TASK = "Untitled task";
    private static final String UNTITLED_LIST = "Imported list";
    private static final String INBOX = ZenlyTaskModel.INBOX_ID;

    private static ZgTodoFolder folder(String id, String title, int sortOrder, long createdAt) {
        return new ZgTodoFolder(id, title, sortOrder, createdAt);
    }

    private static ZgTodoTask task(String id, String folderId, String title, int sortOrder, long createdAt) {
        return new ZgTodoTask(id, folderId, title, null, null, 0, false, null, sortOrder, createdAt, createdAt, null);
    }

    private static ZgLegacyTasksMapper.Rows map(List<ZgTodoFolder> folders, List<ZgTodoTask> tasks) {
        final ZgLegacyTasksMapper.Rows rows = ZgLegacyTasksMapper.map(folders, tasks, UNTITLED_TASK, UNTITLED_LIST);
        for (ZenlyTaskModel.Task task : rows.tasks) {
            assertPassesTheTableRules(task);
        }
        return rows;
    }

    private static ZenlyTaskModel.Task only(ZgTodoTask task) {
        final ZgLegacyTasksMapper.Rows rows = map(Collections.singletonList(folder("work", "Work", 0, 100)), Collections.singletonList(task));
        assertEquals(1, rows.tasks.size());
        return rows.tasks.get(0);
    }

    /** The CHECKs of the device store's tasks table (ZenlyTasksStore.Database.onCreate), as SQLite evaluates them. */
    private static void assertPassesTheTableRules(ZenlyTaskModel.Task task) {
        // length(trim(title)) BETWEEN 1 AND 200: trim() takes spaces only, length() counts code points.
        String trimmed = task.title;
        while (trimmed.startsWith(" ")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith(" ")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        final int titlePoints = trimmed.codePointCount(0, trimmed.length());
        assertTrue("title length " + titlePoints, titlePoints >= 1 && titlePoints <= 200);
        assertTrue("no NUL in the title", task.title.indexOf('\u0000') < 0);
        assertTrue("description not null", task.description != null);
        assertTrue("description length", task.description.codePointCount(0, task.description.length()) <= 10000);
        assertTrue("due_at IS NULL OR due_at > 0", task.dueAt == null || task.dueAt > 0);
        assertTrue("priority 0..4", task.priority >= 0 && task.priority <= 4);
        assertEquals("completed_at exactly when completed", task.completed, task.completedAt != null);
        assertTrue("folder", task.folderId != null && !task.folderId.isEmpty());
    }

    @Test
    public void rowsKeepTheirIdsTitlesAndCompletionAndTimesBecomeMilliseconds() {
        final ZgTodoTask open = new ZgTodoTask("t-open", "work", "Ship it", "Notes", 1_800_000_000L, 3, false, null, 0, 1_700_000_000L, 1_700_000_500L, null);
        final ZgTodoTask done = new ZgTodoTask("t-done", "work", "Paid", null, null, 0, true, 1_700_000_900L, 1, 1_700_000_100L, 1_700_000_900L, null);
        final ZgLegacyTasksMapper.Rows rows = map(Collections.singletonList(folder("work", "Work", 0, 1_600_000_000L)), Arrays.asList(open, done));

        assertEquals(1, rows.folders.size());
        assertEquals("work", rows.folders.get(0).id);
        assertEquals("Work", rows.folders.get(0).title);
        assertEquals(1_600_000_000_000L, rows.folders.get(0).createdAt);

        assertEquals(2, rows.tasks.size());
        final ZenlyTaskModel.Task a = rows.tasks.get(0);
        assertEquals("t-open", a.id);
        assertEquals("work", a.folderId);
        assertEquals("Ship it", a.title);
        assertEquals("Notes", a.description);
        assertEquals(Long.valueOf(1_800_000_000_000L), a.dueAt);
        assertEquals(3, a.priority);
        assertFalse(a.completed);
        assertNull(a.completedAt);
        assertEquals(1_700_000_000_000L, a.createdAt);
        assertEquals(1_700_000_500_000L, a.updatedAt);

        final ZenlyTaskModel.Task b = rows.tasks.get(1);
        assertEquals("t-done", b.id);
        assertEquals("", b.description);
        assertNull(b.dueAt);
        assertTrue(b.completed);
        assertEquals(Long.valueOf(1_700_000_900_000L), b.completedAt);
        assertEquals(1_700_000_100_000L, b.createdAt);
        assertTrue(rows.assignees.isEmpty());
        assertFalse(rows.isEmpty());
    }

    @Test
    public void theOldInboxUnderADefaultTitleIsTheBuiltInInbox() {
        for (String title : new String[]{"Inbox", "Kiruvchi", "Входящие"}) {
            final ZgLegacyTasksMapper.Rows rows = map(
                Arrays.asList(folder(ZgLegacyTasksMapper.LEGACY_INBOX_ID, title, 0, 10), folder("work", "Work", 1, 20)),
                Arrays.asList(task("t1", ZgLegacyTasksMapper.LEGACY_INBOX_ID, "In the inbox", 0, 30), task("t2", "work", "At work", 0, 40)));
            // The built-in Inbox exists in every device store: it is never written as a list.
            assertEquals(1, rows.folders.size());
            assertEquals("work", rows.folders.get(0).id);
            assertEquals(INBOX, rows.tasks.get(0).folderId);
            assertEquals("t1", rows.tasks.get(0).id);
            assertEquals("work", rows.tasks.get(1).folderId);
        }
    }

    @Test
    public void aRenamedOldInboxStaysAListOfItsOwn() {
        final ZgLegacyTasksMapper.Rows rows = map(
            Collections.singletonList(folder(ZgLegacyTasksMapper.LEGACY_INBOX_ID, "Errands", 0, 10)),
            Collections.singletonList(task("t1", ZgLegacyTasksMapper.LEGACY_INBOX_ID, "Milk", 0, 30)));
        assertEquals(1, rows.folders.size());
        assertEquals(ZgLegacyTasksMapper.LEGACY_INBOX_ID, rows.folders.get(0).id);
        assertEquals("Errands", rows.folders.get(0).title);
        assertEquals(ZgLegacyTasksMapper.LEGACY_INBOX_ID, rows.tasks.get(0).folderId);
        // A list that is merely titled like the inbox is a list too.
        assertFalse(ZgLegacyTasksMapper.isBuiltInInbox("some-list", "Inbox"));
        assertTrue(ZgLegacyTasksMapper.isBuiltInInbox(ZgLegacyTasksMapper.LEGACY_INBOX_ID, "Inbox"));
        assertFalse(ZgLegacyTasksMapper.isBuiltInInbox(ZgLegacyTasksMapper.LEGACY_INBOX_ID, null));
        assertTrue(ZgLegacyTasksMapper.isBuiltInInbox(INBOX, "anything"));
    }

    @Test
    public void aTaskWhoseListIsGoneLandsInTheInbox() {
        final ZgLegacyTasksMapper.Rows rows = map(Collections.<ZgTodoFolder>emptyList(),
            Arrays.asList(task("t1", "deleted-list", "Orphan", 0, 30), task("t2", null, "No list", 0, 40)));
        assertTrue(rows.folders.isEmpty());
        assertEquals(2, rows.tasks.size());
        assertEquals(INBOX, rows.tasks.get(0).folderId);
        assertEquals(INBOX, rows.tasks.get(1).folderId);
    }

    @Test
    public void theOldOrderOfAListBecomesItsCreationOrder() {
        // Sort order wins over creation time in the old store; the device store only has creation time.
        final ZgLegacyTasksMapper.Rows rows = map(Collections.singletonList(folder("work", "Work", 0, 5)), Arrays.asList(
            task("third", "work", "Third", 2, 100),
            task("first", "work", "First", 0, 300),
            task("second", "work", "Second", 1, 300),
            task("fourth", "work", "Fourth", 3, 100)));
        final List<String> ids = new ArrayList<>();
        long previous = Long.MIN_VALUE;
        for (ZenlyTaskModel.Task task : rows.tasks) {
            ids.add(task.id);
            assertTrue("strictly increasing creation times", task.createdAt > previous);
            previous = task.createdAt;
        }
        assertEquals(Arrays.asList("first", "second", "third", "fourth"), ids);
        // A row keeps its own second where the order allows it; the others get the next free millisecond.
        assertEquals(300_000L, rows.tasks.get(0).createdAt);
        assertEquals(300_001L, rows.tasks.get(1).createdAt);
        assertEquals(300_002L, rows.tasks.get(2).createdAt);
        assertEquals(300_003L, rows.tasks.get(3).createdAt);
    }

    @Test
    public void theOldOrderOfTheListsBecomesTheirCreationOrder() {
        final ZgLegacyTasksMapper.Rows rows = map(Arrays.asList(folder("b", "B", 0, 500), folder("a", "A", 1, 100), folder("c", "C", 2, 100)),
            Collections.<ZgTodoTask>emptyList());
        assertEquals("b", rows.folders.get(0).id);
        assertEquals("a", rows.folders.get(1).id);
        assertEquals("c", rows.folders.get(2).id);
        assertEquals(500_000L, rows.folders.get(0).createdAt);
        assertEquals(500_001L, rows.folders.get(1).createdAt);
        assertEquals(500_002L, rows.folders.get(2).createdAt);
        assertFalse(rows.isEmpty());
    }

    @Test
    public void deadlinesStayRealTimesTheStoreCanKeep() {
        assertNull(only(new ZgTodoTask("t", "work", "T", null, null, 0, false, null, 0, 10, 10, null)).dueAt);
        // The store needs a positive deadline: the old store's second 0 becomes its first millisecond.
        assertEquals(Long.valueOf(1L), only(new ZgTodoTask("t", "work", "T", null, 0L, 0, false, null, 0, 10, 10, null)).dueAt);
        assertEquals(Long.valueOf(5_000L), only(new ZgTodoTask("t", "work", "T", null, 5L, 0, false, null, 0, 10, 10, null)).dueAt);
        // A negative deadline was never a real one.
        assertNull(only(new ZgTodoTask("t", "work", "T", null, -5L, 0, false, null, 0, 10, 10, null)).dueAt);
        // An absurd time is clamped, never wrapped into the past.
        final Long far = only(new ZgTodoTask("t", "work", "T", null, Long.MAX_VALUE, 0, false, null, 0, 10, 10, null)).dueAt;
        assertEquals(Long.valueOf(ZgLegacyTasksMapper.MAX_SECONDS * 1000L), far);
        assertEquals(0L, ZgLegacyTasksMapper.millis(-1));
    }

    @Test
    public void aCompletedTaskAlwaysHasACompletionTime() {
        // The old store could hold a completed task without the time: its last change stands in.
        final ZenlyTaskModel.Task task = only(new ZgTodoTask("t", "work", "T", null, null, 0, true, null, 0, 10, 77, null));
        assertTrue(task.completed);
        assertEquals(Long.valueOf(77_000L), task.completedAt);
        // An open task never carries one, whatever the old row held.
        assertNull(only(new ZgTodoTask("t", "work", "T", null, null, 0, false, 55L, 0, 10, 77, null)).completedAt);
    }

    @Test
    public void priorityIsClampedToTheStoresRange() {
        assertEquals(0, only(new ZgTodoTask("t", "work", "T", null, null, -3, false, null, 0, 10, 10, null)).priority);
        assertEquals(4, only(new ZgTodoTask("t", "work", "T", null, null, 9, false, null, 0, 10, 10, null)).priority);
        assertEquals(2, only(new ZgTodoTask("t", "work", "T", null, null, 2, false, null, 0, 10, 10, null)).priority);
    }

    @Test
    public void aTitleTheTableCannotHoldIsCutAndKeptWholeInTheNotes() {
        final StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            longTitle.append((char) ('a' + i % 26));
        }
        final String full = longTitle.toString();
        final ZenlyTaskModel.Task cut = only(new ZgTodoTask("t", "work", full, "Old notes", null, 0, false, null, 0, 10, 10, null));
        assertEquals(200, cut.title.length());
        assertTrue(cut.title.endsWith("…"));
        assertEquals(full.substring(0, 199), cut.title.substring(0, 199));
        assertEquals(full + "\n\nOld notes", cut.description);

        // Exactly at the limit nothing is cut.
        final String exact = full.substring(0, 200);
        final ZenlyTaskModel.Task kept = only(new ZgTodoTask("t", "work", exact, null, null, 0, false, null, 0, 10, 10, null));
        assertEquals(exact, kept.title);
        assertEquals("", kept.description);
    }

    @Test
    public void aCutNeverSplitsACharacter() {
        // 150 emoji: 150 code points (the table takes them), 300 UTF-16 units.
        final StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            emoji.append("😀");
        }
        assertEquals(emoji.toString(), ZgLegacyTasksMapper.fitTitle(emoji.toString()));

        // 250 emoji must be cut: at most 199 units and 199 code points before the ellipsis, never half a pair.
        for (int i = 0; i < 100; i++) {
            emoji.append("😀");
        }
        final String cut = ZgLegacyTasksMapper.fitTitle(emoji.toString());
        assertTrue(cut.endsWith("…"));
        final String head = cut.substring(0, cut.length() - 1);
        assertTrue(head.length() <= 199);
        assertEquals(0, head.length() % 2);
        assertFalse(Character.isHighSurrogate(head.charAt(head.length() - 1)));
        assertTrue(cut.codePointCount(0, cut.length()) <= 200);
    }

    @Test
    public void aTitleThatShowsNothingGetsAName() {
        assertEquals(UNTITLED_TASK, only(new ZgTodoTask("t", "work", "   ", null, null, 0, false, null, 0, 10, 10, null)).title);
        assertEquals(UNTITLED_TASK, only(new ZgTodoTask("t", "work", null, null, null, 0, false, null, 0, 10, 10, null)).title);
        assertEquals(UNTITLED_TASK, only(new ZgTodoTask("t", "work", "\u0000", null, null, 0, false, null, 0, 10, 10, null)).title);
        final ZgLegacyTasksMapper.Rows rows = map(Collections.singletonList(folder("blank", " \t ", 0, 10)), Collections.<ZgTodoTask>emptyList());
        assertEquals(UNTITLED_LIST, rows.folders.get(0).title);
        // White space at the ends goes, as the device store trims a title it saves.
        assertEquals("Trim me", only(new ZgTodoTask("t", "work", "  Trim me \n", null, null, 0, false, null, 0, 10, 10, null)).title);
    }

    @Test
    public void notesLongerThanTheTableAllowsAreCut() {
        final StringBuilder notes = new StringBuilder();
        for (int i = 0; i < 10_050; i++) {
            notes.append('n');
        }
        final ZenlyTaskModel.Task task = only(new ZgTodoTask("t", "work", "T", notes.toString(), null, 0, false, null, 0, 10, 10, null));
        assertEquals(10_000, task.description.length());
        assertEquals("abc", ZgLegacyTasksMapper.cut("abc", 3));
        assertEquals("ab", ZgLegacyTasksMapper.cut("abc", 2));
    }

    @Test
    public void assigneesComeBackByTaskIdForTheSideStore() {
        final ZgTodoTask with = new ZgTodoTask("t1", "work", "With", null, null, 0, false, null, 0, 10, 10, new ZgTodoAssignee(777L, "  Ali  "));
        final ZgTodoTask invalid = new ZgTodoTask("t2", "work", "Invalid", null, null, 0, false, null, 1, 20, 20, new ZgTodoAssignee(0L, "Nobody"));
        final ZgTodoTask without = task("t3", "work", "Without", 2, 30);
        final ZgLegacyTasksMapper.Rows rows = map(Collections.singletonList(folder("work", "Work", 0, 5)), Arrays.asList(with, invalid, without));
        assertEquals(3, rows.tasks.size());
        assertEquals(1, rows.assignees.size());
        assertEquals(new ZgTodoAssignee(777L, "Ali"), rows.assignees.get("t1"));
    }

    @Test
    public void duplicateAndBrokenRowsAreLeftOutOnce() {
        final ZgLegacyTasksMapper.Rows rows = map(
            Arrays.asList(folder("work", "Work", 0, 5), folder("work", "Work again", 1, 6), folder("", "No id", 2, 7), null),
            Arrays.asList(task("t1", "work", "One", 0, 10), task("t1", "work", "One again", 1, 11), task("", "work", "No id", 2, 12), null));
        assertEquals(1, rows.folders.size());
        assertEquals("Work", rows.folders.get(0).title);
        assertEquals(1, rows.tasks.size());
        assertEquals("One", rows.tasks.get(0).title);
    }

    @Test
    public void anEmptyOldBoardImportsNothing() {
        assertTrue(map(Collections.<ZgTodoFolder>emptyList(), Collections.<ZgTodoTask>emptyList()).isEmpty());
        assertTrue(ZgLegacyTasksMapper.map(null, null, UNTITLED_TASK, UNTITLED_LIST).isEmpty());
        // Only the default inbox, without a task: nothing to write (the built-in Inbox is always there).
        assertTrue(map(Collections.singletonList(folder(ZgLegacyTasksMapper.LEGACY_INBOX_ID, "Inbox", 0, 10)),
            Collections.<ZgTodoTask>emptyList()).isEmpty());
    }
}
