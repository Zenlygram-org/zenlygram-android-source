/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.model;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.data.ZgTodoFolder;
import org.telegram.zenlygram.tasks.data.ZgTodoTask;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rows of the old device-wide board (zg_tasks.db, ZgTodoStore) as rows of an account's device store
 * (ZenlyTasksStore), for the one-time import. Pure Java (JVM tests); nothing here reads or writes a database.
 *
 * <ul>
 *   <li>Ids are kept: a task and a list keep their own id, so importing again adds nothing.</li>
 *   <li>Times: the old store kept Unix seconds, the device store keeps Unix milliseconds. A row's time is its second;
 *       rows of one list that the old store ordered differently than their seconds do (the same second, or a clock
 *       that was set back) get the next free millisecond, because the device store orders by creation time and has
 *       no sort order of its own. So the board shows the old order.</li>
 *   <li>The old inbox ("sgtasks.inbox") under one of its default titles is the device store's built-in Inbox (which
 *       the copy to Cloud files in the Cloud Inbox, as iOS does for exactly these titles). A renamed one is a list of
 *       its own and keeps its name and id.</li>
 *   <li>The assignee is not a column of the device store: it comes back separately, by task id, for the side store.</li>
 *   <li>Every row satisfies the device store's table rules (title 1..200 code points, description at most 10000, a
 *       positive deadline, priority 0..4, a completion time exactly when completed), so no row can stop the import.
 *       A title over 200 code points is cut at a character boundary with "…" and kept whole in the description, as
 *       the copy to Cloud does.</li>
 * </ul>
 */
public final class ZgLegacyTasksMapper {

    /** The list the old store filed "All" quick-adds in (iOS TodoStorage.inboxFolderId). */
    public static final String LEGACY_INBOX_ID = "sgtasks.inbox";
    /**
     * The titles the old inbox was created with: ZG_tasks_inbox_title of the three shipped languages at the time
     * (iOS SGTasksMigrationNormalizer.defaultInboxTitles). Fixed: they describe stored data, not the current strings.
     */
    private static final String[] DEFAULT_INBOX_TITLES = {"Inbox", "Kiruvchi", "Входящие"};

    /** The device store's title rule (its table CHECK counts code points). */
    static final int TITLE_CODE_POINTS = 200;
    static final int DESCRIPTION_CODE_POINTS = 10000;
    /** 9999-12-31T23:59:59Z: the last second a Cloud instant can name; nothing later is a real time. */
    static final long MAX_SECONDS = 253402300799L;
    private static final String ELLIPSIS = "…";

    private ZgLegacyTasksMapper() {
    }

    /** What the import writes. */
    public static final class Rows {
        /** Lists in the old order; the built-in Inbox is never among them. */
        public final List<ZenlyTaskModel.Folder> folders;
        public final List<ZenlyTaskModel.Task> tasks;
        /** Task id to its local assignee. */
        public final Map<String, ZgTodoAssignee> assignees;

        Rows(List<ZenlyTaskModel.Folder> folders, List<ZenlyTaskModel.Task> tasks, Map<String, ZgTodoAssignee> assignees) {
            this.folders = Collections.unmodifiableList(folders);
            this.tasks = Collections.unmodifiableList(tasks);
            this.assignees = Collections.unmodifiableMap(assignees);
        }

        public boolean isEmpty() {
            return folders.isEmpty() && tasks.isEmpty();
        }
    }

    /**
     * @param folders       the old lists in their order (ZgTodoStore.loadFolders: sort order, then creation)
     * @param tasks         the old tasks
     * @param untitledTask  the title of a task whose stored title shows nothing (never written by the old store)
     * @param untitledList  likewise for a list
     */
    public static Rows map(List<ZgTodoFolder> folders, List<ZgTodoTask> tasks, String untitledTask, String untitledList) {
        final ArrayList<ZenlyTaskModel.Folder> outFolders = new ArrayList<>();
        final HashMap<String, String> folderIds = new HashMap<>();
        long previous = 0;
        if (folders != null) {
            for (ZgTodoFolder folder : folders) {
                if (folder == null || folder.id == null || folder.id.isEmpty() || folderIds.containsKey(folder.id)) {
                    continue;
                }
                if (isBuiltInInbox(folder.id, folder.title)) {
                    folderIds.put(folder.id, ZenlyTaskModel.INBOX_ID);
                    continue;
                }
                String title = clean(folder.title);
                if (!ZenlyTaskModel.hasVisibleScalar(title)) {
                    title = untitledList;
                }
                previous = Math.max(millis(folder.createdAt), previous + 1);
                outFolders.add(new ZenlyTaskModel.Folder(folder.id, title, previous));
                folderIds.put(folder.id, folder.id);
            }
        }

        // The old order of each list: sort order, then creation, then as read.
        final LinkedHashMap<String, ArrayList<Indexed>> byFolder = new LinkedHashMap<>();
        final HashMap<String, Boolean> seen = new HashMap<>();
        if (tasks != null) {
            for (int i = 0; i < tasks.size(); i++) {
                final ZgTodoTask task = tasks.get(i);
                if (task == null || task.id == null || task.id.isEmpty() || seen.put(task.id, Boolean.TRUE) != null) {
                    continue;
                }
                // A task whose list is gone is never dropped: it lands in the Inbox.
                final String mapped = task.folderId == null ? null : folderIds.get(task.folderId);
                final String folderId = mapped != null ? mapped : ZenlyTaskModel.INBOX_ID;
                ArrayList<Indexed> list = byFolder.get(folderId);
                if (list == null) {
                    byFolder.put(folderId, list = new ArrayList<>());
                }
                list.add(new Indexed(task, i));
            }
        }
        final ArrayList<ZenlyTaskModel.Task> outTasks = new ArrayList<>();
        final LinkedHashMap<String, ZgTodoAssignee> assignees = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayList<Indexed>> entry : byFolder.entrySet()) {
            final ArrayList<Indexed> list = entry.getValue();
            Collections.sort(list, (a, b) -> {
                if (a.task.sortOrder != b.task.sortOrder) {
                    return a.task.sortOrder < b.task.sortOrder ? -1 : 1;
                }
                if (a.task.createdAt != b.task.createdAt) {
                    return a.task.createdAt < b.task.createdAt ? -1 : 1;
                }
                return Integer.compare(a.index, b.index);
            });
            long last = 0;
            for (Indexed indexed : list) {
                final ZgTodoTask task = indexed.task;
                last = Math.max(millis(task.createdAt), last + 1);
                outTasks.add(task(task, entry.getKey(), last, untitledTask));
                if (task.assignee != null) {
                    final ZgTodoAssignee assignee = ZgTasksTitlePolicy.validatedAssignee(task.assignee.peerId, task.assignee.displayName);
                    if (assignee != null) {
                        assignees.put(task.id, assignee);
                    }
                }
            }
        }
        return new Rows(outFolders, outTasks, assignees);
    }

    /** The old inbox under a default title, or a row that already carries the built-in Inbox's id. */
    static boolean isBuiltInInbox(String id, String title) {
        if (ZenlyTaskModel.INBOX_ID.equals(id)) {
            return true;
        }
        if (!LEGACY_INBOX_ID.equals(id) || title == null) {
            return false;
        }
        for (String known : DEFAULT_INBOX_TITLES) {
            if (known.equals(title)) {
                return true;
            }
        }
        return false;
    }

    private static ZenlyTaskModel.Task task(ZgTodoTask task, String folderId, long createdAt, String untitledTask) {
        final String full = clean(task.title);
        String title;
        String description = task.description == null ? "" : stripNul(task.description);
        if (!ZenlyTaskModel.hasVisibleScalar(full)) {
            title = untitledTask;
        } else {
            title = fitTitle(full);
            if (!title.equals(full)) {
                // The whole title survives in the description when it fits there (the copy to Cloud does the same).
                final String candidate = description.isEmpty() ? full : full + "\n\n" + description;
                if (candidate.codePointCount(0, candidate.length()) <= DESCRIPTION_CODE_POINTS) {
                    description = candidate;
                }
            }
        }
        description = cut(description, DESCRIPTION_CODE_POINTS);
        Long dueAt = null;
        if (task.dueAt != null && task.dueAt >= 0) {
            // The device store needs a positive deadline; the old store's 0 (1970-01-01 00:00:00 UTC) stays that second.
            dueAt = Math.max(1L, millis(task.dueAt));
        }
        final long updatedAt = millis(task.updatedAt);
        Long completedAt = null;
        if (task.completed) {
            completedAt = task.completedAt != null ? millis(task.completedAt) : updatedAt;
        }
        return new ZenlyTaskModel.Task(task.id, folderId, title, description, dueAt, Math.min(Math.max(task.priority, 0), 4),
            task.completed, completedAt, createdAt, updatedAt);
    }

    /** Whole seconds as milliseconds; a negative or absurd time is clamped, never wrapped. */
    static long millis(long seconds) {
        return Math.min(Math.max(seconds, 0L), MAX_SECONDS) * 1000L;
    }

    /** No NUL (SQLite's length() stops at one) and no white space at the ends, as the device store trims a title. */
    static String clean(String value) {
        return value == null ? "" : ZenlyTaskModel.trimEdges(stripNul(value));
    }

    private static String stripNul(String value) {
        return value.indexOf('\u0000') >= 0 ? value.replace("\u0000", "") : value;
    }

    /**
     * {@code title} when the table can hold it (at most 200 code points); otherwise its longest prefix that ends on a
     * character boundary and has at most 199 code points and 199 UTF-16 units, followed by "…", so the cut title also
     * passes the store's rule for a later edit.
     */
    static String fitTitle(String title) {
        if (title.codePointCount(0, title.length()) <= TITLE_CODE_POINTS) {
            return title;
        }
        final int limit = TITLE_CODE_POINTS - 1;
        final BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(title);
        int end = 0;
        int points = 0;
        for (int start = graphemes.first(), next = graphemes.next(); next != BreakIterator.DONE; start = next, next = graphemes.next()) {
            final int clusterPoints = title.codePointCount(start, next);
            if (points + clusterPoints > limit || next > limit) {
                break;
            }
            points += clusterPoints;
            end = next;
        }
        if (end == 0) {
            // One character of more than 199 code points: cut inside it rather than lose the task.
            end = cutIndex(title, limit, limit);
        }
        final String head = ZenlyTaskModel.trimEdges(title.substring(0, end));
        return ZenlyTaskModel.hasVisibleScalar(head) ? head + ELLIPSIS : ELLIPSIS;
    }

    /** At most {@code limit} code points of {@code value}. */
    static String cut(String value, int limit) {
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        return value.substring(0, cutIndex(value, limit, Integer.MAX_VALUE));
    }

    /** The end of the longest prefix with at most {@code points} code points and {@code units} UTF-16 units. */
    private static int cutIndex(String value, int points, int units) {
        int index = 0;
        int count = 0;
        while (index < value.length() && count < points) {
            final int next = index + Character.charCount(value.codePointAt(index));
            if (next > units) {
                break;
            }
            index = next;
            count++;
        }
        return index;
    }

    private static final class Indexed {
        final ZgTodoTask task;
        final int index;

        Indexed(ZgTodoTask task, int index) {
            this.task = task;
            this.index = index;
        }
    }
}
