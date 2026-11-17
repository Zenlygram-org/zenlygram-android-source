package org.telegram.messenger.zenlygram.tasks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Platform-independent task values and list rules. Times are Unix milliseconds. */
public final class ZenlyTaskModel {
    public static final String INBOX_ID = "inbox";
    public static final int MAX_TITLE = 200;
    /**
     * New local writes (Tasks R4, design 30 §6.5): at most 4096 UTF-16 units and {@link #MAX_DESCRIPTION_BYTES} UTF-8
     * bytes, so every new description fits the server's limits. The table CHECK keeps 10000: older, longer descriptions
     * stay readable and are truncated only in the copy (TasksMigrationNormalizer).
     */
    public static final int MAX_DESCRIPTION = 4096;
    public static final int MAX_DESCRIPTION_BYTES = 16384;
    public static final int MAX_FOLDER_TITLE = 80;

    private ZenlyTaskModel() { }

    public enum Filter { ALL, OPEN, COMPLETED, TODAY, OVERDUE }

    /**
     * Write fence of one account's checked local writes (design 30 §6.5, §7.5): MIGRATING only while that account's
     * migration step runs its local part (transient), ARCHIVED while its cut-over makes the board read-only.
     */
    public enum WriteState { OPEN, MIGRATING, ARCHIVED }
    public enum Sort { UPDATED, DUE_DATE, PRIORITY, TITLE, CREATED }

    public static final class Folder {
        public final String id;
        public final String title;
        public final long createdAt;

        public Folder(String id, String title, long createdAt) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
        }
    }

    public static final class Task {
        public final String id;
        public final String folderId;
        public final String title;
        public final String description;
        public final Long dueAt;
        public final int priority;
        public final boolean completed;
        public final Long completedAt;
        public final long createdAt;
        public final long updatedAt;

        public Task(String id, String folderId, String title, String description, Long dueAt,
                    int priority, boolean completed, Long completedAt, long createdAt, long updatedAt) {
            this.id = id;
            this.folderId = folderId;
            this.title = title;
            this.description = description;
            this.dueAt = dueAt;
            this.priority = priority;
            this.completed = completed;
            this.completedAt = completedAt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    public static final class Snapshot {
        public final List<Folder> folders;
        public final List<Task> tasks;

        public Snapshot(List<Folder> folders, List<Task> tasks) {
            this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        }
    }

    /**
     * Trimmed of the server's edge whitespace (U+0009..U+000D, U+0085, Zs, Zl, Zp) and holding a visible scalar, so a
     * new title is never one the cloud would refuse.
     */
    public static String title(String value, int maxLength) {
        String normalized = trimEdges(value == null ? "" : value);
        if (normalized.isEmpty() || normalized.length() > maxLength || !hasVisibleScalar(normalized)) {
            throw new IllegalArgumentException("invalid_title");
        }
        return normalized;
    }

    public static String description(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_DESCRIPTION
                || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTION_BYTES) {
            throw new IllegalArgumentException("invalid_description");
        }
        return normalized;
    }

    /** The server's edge whitespace (x-permissions-and-state.visible_text; same set as TasksWireText). */
    public static boolean isEdgeWhitespace(int codePoint) {
        if ((codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x85) return true;
        int type = Character.getType(codePoint);
        return type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** A scalar outside Cc, Cf, Mn, Mc, Me, Zs, Zl, Zp. */
    public static boolean isVisible(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.NON_SPACING_MARK:
            case Character.COMBINING_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.SPACE_SEPARATOR:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
                return false;
            default:
                return true;
        }
    }

    public static String trimEdges(String value) {
        int start = 0, end = value.length();
        while (start < end && isEdgeWhitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && isEdgeWhitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }

    public static boolean hasVisibleScalar(String value) {
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (isVisible(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public static void validateTask(String title, String description, Long dueAt, int priority) {
        title(title, MAX_TITLE);
        description(description);
        if (priority < 0 || priority > 4 || dueAt != null && dueAt <= 0) {
            throw new IllegalArgumentException("invalid_task");
        }
    }

    /** Day boundaries come from Calendar in the user's zone, including daylight saving changes. */
    public static List<Task> select(List<Task> tasks, String folderId, Filter filter, Sort sort,
                                    String query, long now, long todayStart, long tomorrowStart) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        ArrayList<Task> result = new ArrayList<>();
        for (Task task : tasks) {
            if (folderId != null && !folderId.equals(task.folderId)) continue;
            if (!needle.isEmpty() && !task.title.toLowerCase(Locale.ROOT).contains(needle)
                    && !task.description.toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (filter == Filter.OPEN && task.completed) continue;
            if (filter == Filter.COMPLETED && !task.completed) continue;
            if (filter == Filter.TODAY && (task.completed || task.dueAt == null
                    || task.dueAt < todayStart || task.dueAt >= tomorrowStart)) continue;
            if (filter == Filter.OVERDUE && (task.completed || task.dueAt == null || task.dueAt >= now)) continue;
            result.add(task);
        }
        Comparator<Task> ordering;
        switch (sort) {
            case DUE_DATE:
                ordering = (a, b) -> Long.compare(a.dueAt == null ? Long.MAX_VALUE : a.dueAt,
                        b.dueAt == null ? Long.MAX_VALUE : b.dueAt);
                break;
            case PRIORITY:
                ordering = (a, b) -> Integer.compare(b.priority, a.priority);
                break;
            case TITLE:
                ordering = (a, b) -> a.title.compareToIgnoreCase(b.title);
                break;
            case CREATED:
                ordering = (a, b) -> Long.compare(a.createdAt, b.createdAt);
                break;
            default:
                ordering = (a, b) -> Long.compare(b.updatedAt, a.updatedAt);
                break;
        }
        final Comparator<Task> selectedOrder = ordering;
        Collections.sort(result, (a, b) -> {
            int completedOrder = Boolean.compare(a.completed, b.completed);
            if (completedOrder != 0) return completedOrder;
            int order = selectedOrder.compare(a, b);
            return order != 0 ? order : a.id.compareTo(b.id);
        });
        return result;
    }
}
