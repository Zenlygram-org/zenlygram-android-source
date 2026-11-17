/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Everything the Tasks board decides about WHAT it shows of one store's {@link ZgBoard} (iOS TasksBoardState and
 * TasksCloudBoardState): the selected list, which tasks are open or done and in what order, where a quick-added task
 * goes, and when the empty state appears. Pure Java (JVM tests); the board screen owns all I/O. The board is not a
 * kanban: a task is either open or completed.
 */
public final class ZgTasksBoardState {

    /** One row of the board's task area. */
    public static final class Row {
        public static final int EMPTY_NO_TASKS = 0;
        public static final int EMPTY_ALL_DONE = 1;
        public static final int TASK = 2;
        public static final int COMPLETED_HEADER = 3;
        /** "Assigned to Me": before the first open task another account assigned to this one. */
        public static final int ASSIGNED_HEADER = 4;

        public final int kind;
        public final String taskId;
        public final int count;
        public final boolean expanded;

        private Row(int kind, String taskId, int count, boolean expanded) {
            this.kind = kind;
            this.taskId = taskId;
            this.count = count;
            this.expanded = expanded;
        }

        static Row task(String id) {
            return new Row(TASK, id, 0, false);
        }
    }

    /** A filter chip: "All" (listId null) or one list, with its open-task count. */
    public static final class Chip {
        public final String listId;
        /** Null for "All" (the UI localizes it); the list's own title otherwise (empty for the device Inbox). */
        public final String title;
        /** The device Inbox: the UI shows its localized name. */
        public final boolean inbox;
        public final int openCount;
        public final boolean selected;

        Chip(String listId, String title, boolean inbox, int openCount, boolean selected) {
            this.listId = listId;
            this.title = title;
            this.inbox = inbox;
            this.openCount = openCount;
            this.selected = selected;
        }
    }

    private ZgBoard board;
    /** The selected list, or null for "All". A list is selected by id, so renaming it keeps the selection. */
    private String filterListId;
    public boolean completedExpanded;

    public ZgTasksBoardState(ZgTasksScope scope) {
        this.board = ZgBoard.empty(scope);
    }

    /** Replaces the data with a fresh board. A selected list that no longer exists falls back to "All". */
    public void update(ZgBoard board) {
        this.board = board;
        this.filterListId = resolved(filterListId);
    }

    public ZgBoard getBoard() {
        return board;
    }

    /** Selects a list (null = "All"); an unknown id selects "All". */
    public void select(String listId) {
        this.filterListId = resolved(listId);
    }

    private String resolved(String listId) {
        return board.list(listId) != null ? listId : null;
    }

    public String getFilterListId() {
        return filterListId;
    }

    /**
     * Where a quick-added task goes: the selected list, or null under "All" (the store then files it in its inbox:
     * the device Inbox list, or the Cloud Inbox, which is no list).
     */
    public String quickAddListId() {
        return filterListId;
    }

    public ZgBoardList selectedList() {
        return board.list(filterListId);
    }

    public ZgBoardTask task(String id) {
        return board.task(id);
    }

    /** A list filter never shows a task shared with this account: such a task carries no list. */
    private boolean matchesFilter(ZgBoardTask task) {
        return filterListId == null || filterListId.equals(task.listId);
    }

    /** Open tasks of the filter, in the board's order (the store sorted them: group by group, then each group's own order). */
    public List<ZgBoardTask> activeTasks() {
        final ArrayList<ZgBoardTask> result = new ArrayList<>();
        for (ZgBoardTask task : board.tasks) {
            if (!task.completed && matchesFilter(task)) {
                result.add(task);
            }
        }
        return result;
    }

    /** Done tasks of the filter, most recently completed first; an unknown completion time is last. */
    public List<ZgBoardTask> completedTasks() {
        final ArrayList<Indexed> indexed = new ArrayList<>();
        for (int i = 0; i < board.tasks.size(); i++) {
            final ZgBoardTask task = board.tasks.get(i);
            if (task.completed && matchesFilter(task)) {
                indexed.add(new Indexed(task, i, task.completedSortKey()));
            }
        }
        Collections.sort(indexed, (a, b) -> a.key != b.key ? Long.compare(b.key, a.key) : Integer.compare(a.offset, b.offset));
        final ArrayList<ZgBoardTask> result = new ArrayList<>(indexed.size());
        for (Indexed i : indexed) {
            result.add(i.task);
        }
        return result;
    }

    /** "All" first, then every list in order, each with its open-task count. */
    public List<Chip> chips() {
        final HashMap<String, Integer> open = new HashMap<>();
        int total = 0;
        for (ZgBoardTask task : board.tasks) {
            if (!task.completed) {
                total++;
                if (task.listId != null) {
                    final Integer n = open.get(task.listId);
                    open.put(task.listId, n != null ? n + 1 : 1);
                }
            }
        }
        final ArrayList<Chip> chips = new ArrayList<>();
        chips.add(new Chip(null, null, false, total, filterListId == null));
        for (ZgBoardList list : board.lists) {
            final Integer n = open.get(list.id);
            chips.add(new Chip(list.id, list.title, list.inbox, n != null ? n : 0, list.id.equals(filterListId)));
        }
        return chips;
    }

    /** Section 0: open tasks or the empty message. Section 1: the completed disclosure (and, expanded, its tasks). */
    public List<List<Row>> sections() {
        return projectedSections(null);
    }

    /** The sections narrowed to one day or to undated tasks, in the board's own order. ALL is exactly {@link #sections}. */
    public List<List<Row>> calendarSections(ZgTasksCalendar.Filter filter, ZoneId zone) {
        return projectedSections(new ZgTasksCalendar.Matcher(filter, zone));
    }

    /** The deadline of every task the list filter shows, open and completed (null for undated): the day markers. */
    public List<Long> calendarDueDates() {
        final ArrayList<Long> result = new ArrayList<>();
        for (ZgBoardTask task : activeTasks()) {
            result.add(task.dueAt);
        }
        for (ZgBoardTask task : completedTasks()) {
            result.add(task.dueAt);
        }
        return result;
    }

    private List<List<Row>> projectedSections(ZgTasksCalendar.Matcher matcher) {
        final ArrayList<ZgBoardTask> active = new ArrayList<>();
        for (ZgBoardTask task : activeTasks()) {
            if (matcher == null || matcher.matches(task.dueAt)) {
                active.add(task);
            }
        }
        final ArrayList<ZgBoardTask> completed = new ArrayList<>();
        for (ZgBoardTask task : completedTasks()) {
            if (matcher == null || matcher.matches(task.dueAt)) {
                completed.add(task);
            }
        }
        final ArrayList<Row> openRows = new ArrayList<>();
        if (!active.isEmpty()) {
            boolean assignedHeader = false;
            for (ZgBoardTask task : active) {
                // The store puts the tasks assigned to this account last: one header in front of them.
                if (task.isShared() && !assignedHeader) {
                    assignedHeader = true;
                    openRows.add(new Row(Row.ASSIGNED_HEADER, null, 0, false));
                }
                openRows.add(Row.task(task.id));
            }
        } else if (board.complete) {
            openRows.add(new Row(completed.isEmpty() ? Row.EMPTY_NO_TASKS : Row.EMPTY_ALL_DONE, null, 0, false));
        }
        // else: before the store has answered completely nothing is known to be missing, so no "No tasks yet" and no
        // "All done" claim (the status line says what is happening); rows that are already here still show.
        final ArrayList<Row> completedRows = new ArrayList<>();
        if (!completed.isEmpty()) {
            completedRows.add(new Row(Row.COMPLETED_HEADER, null, completed.size(), completedExpanded));
            if (completedExpanded) {
                for (ZgBoardTask task : completed) {
                    completedRows.add(Row.task(task.id));
                }
            }
        }
        final ArrayList<List<Row>> result = new ArrayList<>(2);
        result.add(openRows);
        result.add(completedRows);
        return result;
    }

    private static final class Indexed {
        final ZgBoardTask task;
        final int offset;
        final long key;

        Indexed(ZgBoardTask task, int offset, long key) {
            this.task = task;
            this.offset = offset;
            this.key = key;
        }
    }
}
