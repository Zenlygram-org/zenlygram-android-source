package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Locale;

/**
 * Normalization of the copied device rows (design 30 §7.1), identical to iOS SGTasksMigrationNormalizer. Pure: every
 * create of the automatic first migration is built from these values; the originals stay unchanged in the local
 * archive. Instead of refusing a row the rules trim, fill a placeholder, truncate (keeping the full title in the
 * description), clamp and drop what the server would refuse. Vectors N1–N13: zenlygram-tests/tasks/fixtures/
 * tasks-r4-vectors.json, a verbatim copy of the backend's tests/Fixtures/tasks-r4/vectors.json. Local times are Unix
 * milliseconds (floored to seconds), cloud times seconds. Android tasks have no assignee: every create carries
 * {@code annotation: null}.
 */
public final class TasksMigrationNormalizer {
    public static final int LIST_TITLE_SCALARS = 100, TASK_TITLE_SCALARS = 200, TITLE_BYTES = 8192;
    public static final int DESCRIPTION_SCALARS = 4096, DESCRIPTION_BYTES = 16384;
    /** A creation or completion time later than the estimated server time by more than this is unknown. */
    public static final long FUTURE_SKEW_SECONDS = 300;
    /** Android rows always take their index among their siblings times this as the sort order. */
    public static final int SORT_ORDER_STEP = 1024;
    private static final String ELLIPSIS = "…";

    /** The record's normalization_locale (uz, ru, en): placeholder titles are in this language for good. */
    public final String locale;

    public TasksMigrationNormalizer(String locale) {
        this.locale = ZenlyTaskImport.MigrationRecord.LOCALE_UZ.equals(locale) || ZenlyTaskImport.MigrationRecord.LOCALE_RU.equals(locale)
                ? locale : ZenlyTaskImport.MigrationRecord.LOCALE_EN;
    }

    /** The record locale for a UI language: uz, ru, else en. */
    public static String localeFor(String language) {
        if (language == null) return ZenlyTaskImport.MigrationRecord.LOCALE_EN;
        String value = language.toLowerCase(Locale.ROOT);
        if (value.startsWith("uz")) return ZenlyTaskImport.MigrationRecord.LOCALE_UZ;
        if (value.startsWith("ru")) return ZenlyTaskImport.MigrationRecord.LOCALE_RU;
        return ZenlyTaskImport.MigrationRecord.LOCALE_EN;
    }

    /** tasks_migration_untitledTask */
    public String untitledTask() {
        if (ZenlyTaskImport.MigrationRecord.LOCALE_UZ.equals(locale)) return "Nomsiz vazifa";
        if (ZenlyTaskImport.MigrationRecord.LOCALE_RU.equals(locale)) return "Задача без названия";
        return "Untitled task";
    }

    /** tasks_migration_importedList */
    public String importedList() {
        if (ZenlyTaskImport.MigrationRecord.LOCALE_UZ.equals(locale)) return "Import qilingan roʻyxat";
        if (ZenlyTaskImport.MigrationRecord.LOCALE_RU.equals(locale)) return "Импортированный список";
        return "Imported list";
    }

    // ---- normalized rows ----

    /** The Android fixed inbox ({@code inbox}) is the virtual Cloud Inbox: no list.create, its tasks carry list_id null. */
    public static boolean isCloudInbox(String localFolderId) {
        return ZenlyTaskModel.INBOX_ID.equals(localFolderId);
    }

    public static final class List {
        public final String title;
        public final int sortOrder;
        /** null = unknown (the server stamps its own time). */
        public final String createdAt;

        List(String title, int sortOrder, String createdAt) {
            this.title = title;
            this.sortOrder = sortOrder;
            this.createdAt = createdAt;
        }
    }

    public static final class Task {
        public final String title, description;
        public final Long dueAt;
        public final int priority, sortOrder;
        public final String createdAt;
        public final boolean completed;
        /** null for an open task, and for a completed one whose time is unknown or invalid. */
        public final String completedAt;

        Task(String title, String description, Long dueAt, int priority, int sortOrder, String createdAt, boolean completed, String completedAt) {
            this.title = title;
            this.description = description;
            this.dueAt = dueAt;
            this.priority = priority;
            this.sortOrder = sortOrder;
            this.createdAt = createdAt;
            this.completed = completed;
            this.completedAt = completedAt;
        }

        /** The create's content: never an annotation. */
        public TasksWire.Content content(String listId) {
            return new TasksWire.Content(listId, title, description, dueAt, priority, sortOrder, null);
        }

        /** task.create with this content under {@code listId} (null = Cloud Inbox). */
        public TasksWire.Operation create(String taskId, String listId) {
            return TasksWire.Operation.taskCreate(taskId, content(listId), createdAt, completed, completedAt);
        }
    }

    /** {@code index}: the folder's 0-based position among the folders in local order; {@code nowSeconds}: server time. */
    public List list(String title, long createdAtMillis, int index, long nowSeconds) {
        return new List(listTitle(title), sortOrder(index), instant(createdAtMillis, nowSeconds));
    }

    /** {@code index}: the task's 0-based position within its folder in local order. */
    public Task task(ZenlyTaskModel.Task task, int index, long nowSeconds) {
        String[] text = taskText(task.title, task.description);
        return new Task(text[0], text[1], deadline(task.dueAt), priority(task.priority), sortOrder(index),
                instant(task.createdAt, nowSeconds), task.completed, completedAt(task.completed, task.completedAt, nowSeconds));
    }

    // ---- rules ----

    /** Trimmed; no visible scalar left: the imported-list placeholder; at most 100 scalars / 8192 bytes. */
    public String listTitle(String raw) {
        String trimmed = ZenlyTaskModel.trimEdges(raw == null ? "" : raw);
        String full = ZenlyTaskModel.hasVisibleScalar(trimmed) ? trimmed : importedList();
        return truncate(full, LIST_TITLE_SCALARS, TITLE_BYTES);
    }

    /**
     * {title, description}: the title trimmed (no visible scalar: the untitled placeholder) and cut to 200 scalars;
     * when it was cut, the description becomes the full title (+ "\n\n" + the normalized description, if any) as long
     * as that fits the description limits, else the normalized description stays.
     */
    public String[] taskText(String rawTitle, String rawDescription) {
        String trimmed = ZenlyTaskModel.trimEdges(rawTitle == null ? "" : rawTitle);
        String full = ZenlyTaskModel.hasVisibleScalar(trimmed) ? trimmed : untitledTask();
        String title = truncate(full, TASK_TITLE_SCALARS, TITLE_BYTES);
        String description = description(rawDescription);
        if (!title.equals(full)) {
            String candidate = description == null ? full : full + "\n\n" + description;
            if (scalars(candidate) <= DESCRIPTION_SCALARS && bytes(candidate) <= DESCRIPTION_BYTES) description = candidate;
        }
        return new String[]{title, description};
    }

    /** "" (the local default) is null; at most 4096 scalars / 16384 bytes; no edge rule (trailing whitespace stays). */
    public static String description(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return truncate(raw, DESCRIPTION_SCALARS, DESCRIPTION_BYTES);
    }

    /** Milliseconds floored to seconds; negative, 0 (the local schema requires > 0) or beyond Int32 seconds: null. */
    public static Long deadline(Long millis) {
        if (millis == null || millis <= 0) return null;
        long seconds = millis / 1000L; // non-negative: truncation == floor
        if (seconds <= 0 || seconds > TasksWire.MAX_DEADLINE) return null;
        return seconds;
    }

    /** A local instant (ms, floored): negative or later than now + 300 s is unknown (null); else the wire Instant. */
    public static String instant(Long millis, long nowSeconds) {
        if (millis == null || millis < 0) return null;
        long seconds = millis / 1000L; // non-negative: truncation == floor
        if (seconds > nowSeconds + FUTURE_SKEW_SECONDS) return null;
        return TasksWire.instant(seconds);
    }

    /** null for an open task; for a completed one an invalid time (as {@link #instant}) is unknown (null). */
    public static String completedAt(boolean completed, Long millis, long nowSeconds) {
        return completed ? instant(millis, nowSeconds) : null;
    }

    public static int priority(int value) {
        return Math.min(Math.max(value, 0), 4);
    }

    /** Android: always the index among the siblings times 1024 (saturating). */
    public static int sortOrder(int index) {
        long value = (long) Math.max(index, 0) * SORT_ORDER_STEP;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * truncate(s, L, B): {@code s} with at most L scalars and B UTF-8 bytes; otherwise the longest prefix ending on an
     * extended-grapheme boundary with at most L - 1 scalars and B - 3 bytes, followed by U+2026 (scalar bound ⇒ the
     * server's grapheme count stays within L whatever its ICU version).
     */
    public static String truncate(String value, int limit, int byteLimit) {
        if (scalars(value) <= limit && bytes(value) <= byteLimit) return value;
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(value);
        int scalars = 0, bytes = 0, end = 0;
        for (int start = graphemes.first(), next = graphemes.next(); next != BreakIterator.DONE; start = next, next = graphemes.next()) {
            String cluster = value.substring(start, next);
            int clusterScalars = scalars(cluster), clusterBytes = bytes(cluster);
            if (scalars + clusterScalars > limit - 1 || bytes + clusterBytes > byteLimit - 3) break;
            scalars += clusterScalars;
            bytes += clusterBytes;
            end = next;
        }
        return value.substring(0, end) + ELLIPSIS;
    }

    static int scalars(String value) {
        return value.codePointCount(0, value.length());
    }

    static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
