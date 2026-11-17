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
import org.telegram.zenlygram.tasks.model.ZgTasksBoardState.Chip;
import org.telegram.zenlygram.tasks.model.ZgTasksBoardState.Row;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What the board shows of a store's board: the filter, the chips, the two sections and the empty states. */
public class ZgTasksBoardStateTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static ZgBoardList list(String id, String title) {
        return new ZgBoardList(id, title, false, ZgBoardTask.Sync.SYNCED);
    }

    private static ZgBoardTask open(String id, String listId) {
        return new ZgBoardTask.Builder(id, "Task " + id).list(listId).ownerRights().build();
    }

    private static ZgBoardTask due(String id, String listId, long dueAt) {
        return new ZgBoardTask.Builder(id, "Task " + id).list(listId).due(dueAt).ownerRights().build();
    }

    private static ZgBoardTask done(String id, String listId, Long completedAt, boolean justNow) {
        return new ZgBoardTask.Builder(id, "Task " + id).list(listId).completed(true, completedAt, justNow).ownerRights().build();
    }

    private static ZgBoard board(ZgTasksScope scope, boolean complete, List<ZgBoardList> lists, ZgBoardTask... tasks) {
        return new ZgBoard.Builder(scope).complete(complete).lists(lists).tasks(Arrays.asList(tasks)).build();
    }

    private static ZgTasksBoardState state(ZgBoard board) {
        final ZgTasksBoardState state = new ZgTasksBoardState(board.scope);
        state.update(board);
        return state;
    }

    private static List<String> ids(List<ZgBoardTask> tasks) {
        final ArrayList<String> ids = new ArrayList<>();
        for (ZgBoardTask task : tasks) {
            ids.add(task.id);
        }
        return ids;
    }

    @Test
    public void theTasksAssignedToMeFollowOneHeader() {
        final ZgBoardTask mine = open("a", null);
        final ZgBoardTask assigned = new ZgBoardTask.Builder("s", "Assigned").sharedBy("777").recipientRights().build();
        final ZgBoardTask arriving = new ZgBoardTask.Builder("p", "Arriving").sharedBy("777").provisional().build();
        final List<Row> open = state(board(ZgTasksScope.CLOUD, true, Collections.<ZgBoardList>emptyList(), mine, assigned, arriving)).sections().get(0);
        assertEquals(4, open.size());
        assertEquals(Row.TASK, open.get(0).kind);
        assertEquals(Row.ASSIGNED_HEADER, open.get(1).kind);
        assertEquals("s", open.get(2).taskId);
        assertEquals("p", open.get(3).taskId);
        // Nothing of my own: the header still comes first. Nothing assigned: no header.
        final List<Row> onlyAssigned = state(board(ZgTasksScope.CLOUD, true, Collections.<ZgBoardList>emptyList(), assigned)).sections().get(0);
        assertEquals(Row.ASSIGNED_HEADER, onlyAssigned.get(0).kind);
        assertEquals(2, onlyAssigned.size());
        final List<Row> onlyMine = state(board(ZgTasksScope.CLOUD, true, Collections.<ZgBoardList>emptyList(), mine)).sections().get(0);
        assertEquals(1, onlyMine.size());
        assertEquals(Row.TASK, onlyMine.get(0).kind);
    }

    private static List<String> taskRows(List<Row> rows) {
        final ArrayList<String> ids = new ArrayList<>();
        for (Row row : rows) {
            if (row.kind == Row.TASK) {
                ids.add(row.taskId);
            }
        }
        return ids;
    }

    @Test
    public void nothingIsClaimedBeforeTheStoreAnswered() {
        final ZgTasksBoardState state = new ZgTasksBoardState(ZgTasksScope.CLOUD);
        assertFalse(state.getBoard().complete);
        final List<List<Row>> sections = state.sections();
        assertEquals(2, sections.size());
        // Neither "No tasks yet" nor "All done": nothing is known to be missing yet.
        assertTrue(sections.get(0).isEmpty());
        assertTrue(sections.get(1).isEmpty());
        assertEquals(1, state.chips().size());
        assertNull(state.chips().get(0).listId);
    }

    @Test
    public void anIncompleteBoardShowsTheRowsItAlreadyHas() {
        final ZgTasksBoardState state = state(board(ZgTasksScope.CLOUD, false, Collections.<ZgBoardList>emptyList(), open("a", null)));
        assertEquals(Collections.singletonList("a"), taskRows(state.sections().get(0)));
    }

    @Test
    public void aCompleteEmptyBoardSaysSo() {
        final ZgTasksBoardState state = state(board(ZgTasksScope.DEVICE, true, Collections.<ZgBoardList>emptyList()));
        final List<Row> open = state.sections().get(0);
        assertEquals(1, open.size());
        assertEquals(Row.EMPTY_NO_TASKS, open.get(0).kind);
        assertTrue(state.sections().get(1).isEmpty());
    }

    @Test
    public void everythingDoneIsNotAnEmptyBoard() {
        final ZgTasksBoardState state = state(board(ZgTasksScope.DEVICE, true, Collections.<ZgBoardList>emptyList(), done("a", "inbox", 10L, false)));
        assertEquals(Row.EMPTY_ALL_DONE, state.sections().get(0).get(0).kind);
        final List<Row> completed = state.sections().get(1);
        // Collapsed: only the disclosure with its count.
        assertEquals(1, completed.size());
        assertEquals(Row.COMPLETED_HEADER, completed.get(0).kind);
        assertEquals(1, completed.get(0).count);
        assertFalse(completed.get(0).expanded);
        state.completedExpanded = true;
        final List<Row> expanded = state.sections().get(1);
        assertEquals(2, expanded.size());
        assertTrue(expanded.get(0).expanded);
        assertEquals("a", expanded.get(1).taskId);
    }

    @Test
    public void openTasksKeepTheBoardsOrderAndCompletedOnesAreNewestFirst() {
        final ZgTasksBoardState state = state(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Work")),
            open("a", "work"), done("old", "work", 100L, false), open("b", "work"), done("unknown", "work", null, false),
            done("new", "work", 300L, false), done("now", "work", null, true)));
        assertEquals(Arrays.asList("a", "b"), ids(state.activeTasks()));
        // Just completed here (no time yet) first, then by time, an unknown time last.
        assertEquals(Arrays.asList("now", "new", "old", "unknown"), ids(state.completedTasks()));
    }

    @Test
    public void aListFilterShowsOnlyItsTasksAndNeverASharedOne() {
        final ZgBoardTask shared = new ZgBoardTask.Builder("s", "Shared").sharedBy("777").recipientRights().build();
        final ZgTasksBoardState state = state(board(ZgTasksScope.CLOUD, true, Arrays.asList(list("work", "Work"), list("home", "Home")),
            open("inbox-task", null), open("w", "work"), open("h", "home"), shared, done("wd", "work", 5L, false)));
        assertNull(state.getFilterListId());
        assertEquals(Arrays.asList("inbox-task", "w", "h", "s"), ids(state.activeTasks()));
        assertNull(state.quickAddListId());

        state.select("work");
        assertEquals("work", state.getFilterListId());
        assertEquals("work", state.quickAddListId());
        assertEquals("Work", state.selectedList().title);
        assertEquals(Collections.singletonList("w"), ids(state.activeTasks()));
        assertEquals(Collections.singletonList("wd"), ids(state.completedTasks()));

        // An unknown list selects "All".
        state.select("nowhere");
        assertNull(state.getFilterListId());
        assertNull(state.selectedList());
    }

    @Test
    public void aSelectedListThatDisappearsFallsBackToAll() {
        final ZgTasksBoardState state = state(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Work")), open("w", "work")));
        state.select("work");
        // Renamed: the selection is by id and stays.
        state.update(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Job")), open("w", "work")));
        assertEquals("work", state.getFilterListId());
        assertEquals("Job", state.selectedList().title);
        // Deleted: "All".
        state.update(board(ZgTasksScope.DEVICE, true, Collections.<ZgBoardList>emptyList()));
        assertNull(state.getFilterListId());
    }

    @Test
    public void chipsCountOpenTasksAndAllCountsEveryOpenTask() {
        final ZgBoardTask shared = new ZgBoardTask.Builder("s", "Shared").sharedBy("777").recipientRights().build();
        final ZgBoardList inbox = new ZgBoardList("inbox", "", true, ZgBoardTask.Sync.SYNCED);
        final ZgTasksBoardState state = state(board(ZgTasksScope.DEVICE, true, Arrays.asList(inbox, list("work", "Work"), list("empty", "Empty")),
            open("i", "inbox"), open("w1", "work"), open("w2", "work"), done("wd", "work", 5L, false), open("free", null), shared));
        state.select("work");
        final List<Chip> chips = state.chips();
        assertEquals(4, chips.size());
        // "All": every open task, also the ones without a list and the shared one.
        assertNull(chips.get(0).listId);
        assertNull(chips.get(0).title);
        assertEquals(5, chips.get(0).openCount);
        assertFalse(chips.get(0).selected);
        assertEquals("inbox", chips.get(1).listId);
        assertTrue(chips.get(1).inbox);
        assertEquals(1, chips.get(1).openCount);
        assertEquals("work", chips.get(2).listId);
        assertEquals("Work", chips.get(2).title);
        assertEquals(2, chips.get(2).openCount);
        assertTrue(chips.get(2).selected);
        assertEquals(0, chips.get(3).openCount);
    }

    @Test
    public void theCalendarNarrowsBothSectionsToADayOrToUndatedTasks() {
        final long day1 = LocalDate.of(2026, 11, 14).atTime(9, 30).toEpochSecond(ZoneOffset.UTC);
        final long day2 = LocalDate.of(2026, 11, 15).atTime(0, 0).toEpochSecond(ZoneOffset.UTC);
        final ZgBoardTask doneOnDay1 = new ZgBoardTask.Builder("d1", "Done").list("work").due(day1).completed(true, 50L, false).ownerRights().build();
        final ZgTasksBoardState state = state(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Work")),
            due("a", "work", day1), due("b", "work", day2), open("undated", "work"), doneOnDay1));

        final List<List<Row>> first = state.calendarSections(ZgTasksCalendar.Filter.day(LocalDate.of(2026, 11, 14)), UTC);
        assertEquals(Collections.singletonList("a"), taskRows(first.get(0)));
        assertEquals(Row.COMPLETED_HEADER, first.get(1).get(0).kind);
        assertEquals(1, first.get(1).get(0).count);

        final List<List<Row>> second = state.calendarSections(ZgTasksCalendar.Filter.day(LocalDate.of(2026, 11, 15)), UTC);
        assertEquals(Collections.singletonList("b"), taskRows(second.get(0)));
        assertTrue(second.get(1).isEmpty());

        final List<List<Row>> undated = state.calendarSections(ZgTasksCalendar.Filter.NO_DEADLINE, UTC);
        assertEquals(Collections.singletonList("undated"), taskRows(undated.get(0)));

        // A day without tasks is empty, not "all done".
        final List<List<Row>> none = state.calendarSections(ZgTasksCalendar.Filter.day(LocalDate.of(2026, 12, 1)), UTC);
        assertEquals(Row.EMPTY_NO_TASKS, none.get(0).get(0).kind);

        // The day markers cover open and completed tasks of the filter, undated ones as null.
        final List<Long> dueDates = state.calendarDueDates();
        assertEquals(4, dueDates.size());
        assertTrue(dueDates.contains(day1));
        assertTrue(dueDates.contains(day2));
        assertTrue(dueDates.contains(null));
    }

    @Test
    public void aBoardSaysWhatItAllows() {
        final ZgBoard gated = new ZgBoard.Builder(ZgTasksScope.CLOUD).gate(ZgBoard.Gate.UNLINKED).build();
        assertFalse(gated.isUsable());
        assertFalse(gated.canCompose());
        final ZgBoard readOnly = new ZgBoard.Builder(ZgTasksScope.CLOUD).readOnly(ZgBoard.ReadOnly.WITHDRAWN).build();
        assertTrue(readOnly.isUsable());
        assertTrue(readOnly.isReadOnly());
        assertFalse(readOnly.canCompose());
        final ZgBoard open = ZgBoard.empty(ZgTasksScope.DEVICE);
        assertTrue(open.isUsable());
        assertTrue(open.canCompose());
        assertNull(open.list(null));
        assertNull(open.task(null));
        // Equal boards are equal: a source publishes only what changed.
        assertEquals(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Work")), open("w", "work")),
            board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Work")), open("w", "work")));
        assertFalse(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Work")), open("w", "work"))
            .equals(board(ZgTasksScope.DEVICE, true, Arrays.asList(list("work", "Job")), open("w", "work"))));
    }
}
