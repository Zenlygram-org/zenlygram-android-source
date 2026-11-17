/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;

import java.util.EnumSet;
import java.util.Objects;

/**
 * The owner's unsaved edits of one Cloud task (iOS SGTasksCloudTaskDraft and tasksCloudDraftHasChanges), and the
 * {@link TasksWire.Content} they become. Pure: no Android classes, so the JVM tests run it.
 *
 * <p>The draft remembers, field by field, the version it was made from ({@link #base}). A field is <i>touched</i>
 * when what Save would submit for it differs from that version:
 * <ul>
 *   <li>A title is compared trimmed, notes with "nothing visible" as "no notes": white space alone is never an edit.</li>
 *   <li>{@link #rebase} lets the untouched fields follow a newer version of the task, so the editor never shows a
 *       stale value and Save never writes one back.</li>
 *   <li>{@link #merged} is the newer version with only the touched fields replaced. The repository sends exactly the
 *       fields that differ from what it shows, so nothing changed elsewhere is overwritten.</li>
 *   <li>{@link #conflicts} names the touched fields that also changed elsewhere: the one case the user is asked.</li>
 * </ul>
 *
 * <p>Limits (the wire's sealing rules, checked here so a refusal is explained before anything is queued): a title
 * has something visible and at most {@link ZgTasksTitlePolicy#TASK_LIMIT} characters; notes have at most
 * {@link #NOTES_LIMIT} characters and {@link #NOTES_UTF8_BYTES} bytes. Only touched text is checked: a text another
 * app stored stays as it is until the user changes it.
 */
public final class ZgCloudDraft {

    public static final int NOTES_LIMIT = 4096;
    public static final int NOTES_UTF8_BYTES = 16384;
    public static final int PRIORITY_MIN = 0;
    public static final int PRIORITY_MAX = 4;

    public enum Field {
        LIST,
        TITLE,
        NOTES,
        DEADLINE,
        PRIORITY,
        ASSIGNEE
    }

    /** Why the draft cannot be saved as it is. */
    public enum Problem {
        TITLE_EMPTY,
        TITLE_TOO_LONG,
        NOTES_TOO_LONG
    }

    /** Null is the Cloud Inbox. */
    public String listId;
    /** As typed. */
    public String title;
    /** As typed; empty for none. */
    public String notes;
    /** Unix seconds, or null. */
    public Long dueAt;
    public int priority;
    /** The owner-only assignee (never an invitation), or null. */
    public TasksWire.Annotation assignee;

    private TasksWire.Content base;

    public ZgCloudDraft(TasksWire.Content content) {
        base = content;
        listId = content.listId;
        title = content.title;
        notes = content.description == null ? "" : content.description;
        dueAt = content.dueAt;
        priority = content.priority;
        assignee = content.annotation;
    }

    /**
     * The same edits as edits of {@code content}: what the user typed and chose is kept, and only what differs from
     * {@code content} counts as touched. Used when the version the draft was made from is given up (a refused edit that
     * was discarded: the corrected content is then an edit of the task as the server holds it).
     */
    public ZgCloudDraft onto(TasksWire.Content content) {
        final ZgCloudDraft draft = new ZgCloudDraft(content);
        draft.listId = listId;
        draft.title = title;
        draft.notes = notes;
        draft.dueAt = dueAt;
        draft.priority = priority;
        draft.assignee = assignee;
        return draft;
    }

    /** The version the untouched fields equal. */
    public TasksWire.Content base() {
        return base;
    }

    /** The title Save would submit: trimmed when that is a valid title, otherwise as typed (Save refuses it). */
    public String titleValue() {
        if (title == null) {
            return "";
        }
        if (title.equals(base.title)) {
            return base.title;
        }
        final String valid = ZgTasksTitlePolicy.validatedTitle(title, ZgTasksTitlePolicy.TASK_LIMIT);
        return valid != null ? valid : title;
    }

    /** The notes Save would submit: null when nothing visible was typed, otherwise exactly as typed. */
    public String notesValue() {
        final String typed = notes == null ? "" : notes;
        if (typed.equals(base.description == null ? "" : base.description)) {
            return base.description;
        }
        return blank(typed) ? null : typed;
    }

    /** Whether Save would write, for {@code field}, something else than {@code content} holds. */
    private boolean differs(Field field, TasksWire.Content content) {
        switch (field) {
            case LIST:
                return !Objects.equals(listId, content.listId);
            case TITLE:
                return !titleValue().equals(content.title);
            case NOTES:
                return !sameNotes(notesValue(), content.description);
            case DEADLINE:
                return !Objects.equals(dueAt, content.dueAt);
            case PRIORITY:
                return priority != content.priority;
            default:
                return !Objects.equals(assignee, content.annotation);
        }
    }

    public boolean touched(Field field) {
        return differs(field, base);
    }

    public EnumSet<Field> touchedFields() {
        final EnumSet<Field> fields = EnumSet.noneOf(Field.class);
        for (Field field : Field.values()) {
            if (touched(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** Whether Save would change anything. */
    public boolean dirty() {
        for (Field field : Field.values()) {
            if (touched(field)) {
                return true;
            }
        }
        return false;
    }

    /** The first rule a touched text breaks, or null. */
    public Problem problem() {
        if (touched(Field.TITLE)) {
            final ZgTasksTitlePolicy.Result result = ZgTasksTitlePolicy.validate(title, ZgTasksTitlePolicy.TASK_LIMIT);
            if (result.kind == ZgTasksTitlePolicy.Kind.EMPTY) {
                return Problem.TITLE_EMPTY;
            } else if (result.kind == ZgTasksTitlePolicy.Kind.TOO_LONG) {
                return Problem.TITLE_TOO_LONG;
            }
        }
        if (touched(Field.NOTES) && notesTooLong(notes)) {
            return Problem.NOTES_TOO_LONG;
        }
        return null;
    }

    public static boolean notesTooLong(CharSequence text) {
        return text != null && (ZgTasksTitlePolicy.utf8Length(text) > NOTES_UTF8_BYTES || ZgTasksTitlePolicy.graphemeCount(text) > NOTES_LIMIT);
    }

    /**
     * A newer version of the task arrived: every untouched field follows it, a touched one keeps the user's value and
     * the version it was edited from. Returns the fields whose value in the draft changed (the editor re-reads them).
     */
    public EnumSet<Field> rebase(TasksWire.Content current) {
        final EnumSet<Field> touched = touchedFields();
        final EnumSet<Field> moved = EnumSet.noneOf(Field.class);
        if (!touched.contains(Field.LIST) && !Objects.equals(listId, current.listId)) {
            listId = current.listId;
            moved.add(Field.LIST);
        }
        if (!touched.contains(Field.TITLE) && !current.title.equals(base.title)) {
            title = current.title;
            moved.add(Field.TITLE);
        }
        if (!touched.contains(Field.NOTES) && !Objects.equals(current.description, base.description)) {
            notes = current.description == null ? "" : current.description;
            moved.add(Field.NOTES);
        }
        if (!touched.contains(Field.DEADLINE) && !Objects.equals(dueAt, current.dueAt)) {
            dueAt = current.dueAt;
            moved.add(Field.DEADLINE);
        }
        if (!touched.contains(Field.PRIORITY) && priority != current.priority) {
            priority = current.priority;
            moved.add(Field.PRIORITY);
        }
        if (!touched.contains(Field.ASSIGNEE) && !Objects.equals(assignee, current.annotation)) {
            assignee = current.annotation;
            moved.add(Field.ASSIGNEE);
        }
        base = new TasksWire.Content(
            touched.contains(Field.LIST) ? base.listId : current.listId,
            touched.contains(Field.TITLE) ? base.title : current.title,
            touched.contains(Field.NOTES) ? base.description : current.description,
            touched.contains(Field.DEADLINE) ? base.dueAt : current.dueAt,
            touched.contains(Field.PRIORITY) ? base.priority : current.priority,
            current.sortOrder,
            touched.contains(Field.ASSIGNEE) ? base.annotation : current.annotation);
        return moved;
    }

    /**
     * The touched fields that are not, in {@code current}, what they were when the user started editing them (and are
     * not already what the user wants): saving would replace a value the user has not seen.
     */
    public EnumSet<Field> conflicts(TasksWire.Content current) {
        final EnumSet<Field> fields = EnumSet.noneOf(Field.class);
        for (Field field : Field.values()) {
            if (touched(field) && !sameIn(field, base, current) && differs(field, current)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean sameIn(Field field, TasksWire.Content a, TasksWire.Content b) {
        switch (field) {
            case LIST:
                return Objects.equals(a.listId, b.listId);
            case TITLE:
                return a.title.equals(b.title);
            case NOTES:
                return Objects.equals(a.description, b.description);
            case DEADLINE:
                return Objects.equals(a.dueAt, b.dueAt);
            case PRIORITY:
                return a.priority == b.priority;
            default:
                return Objects.equals(a.annotation, b.annotation);
        }
    }

    /**
     * {@code current} with the touched fields replaced by the draft: what Save hands to the repository. A task moved to
     * another list goes to its end ({@code endOfList}); otherwise it keeps its place.
     *
     * @throws TasksWire.Invalid a value the wire itself cannot carry (the field is named, never the value)
     */
    public TasksWire.Content merged(TasksWire.Content current, int endOfList) {
        final boolean moves = touched(Field.LIST) && !Objects.equals(listId, current.listId);
        return new TasksWire.Content(
            touched(Field.LIST) ? listId : current.listId,
            touched(Field.TITLE) ? titleValue() : current.title,
            touched(Field.NOTES) ? notesValue() : current.description,
            touched(Field.DEADLINE) ? dueAt : current.dueAt,
            touched(Field.PRIORITY) ? priority : current.priority,
            moves ? endOfList : current.sortOrder,
            touched(Field.ASSIGNEE) ? assignee : current.annotation);
    }

    /**
     * The whole draft as the content of a task of its own (Save as New Task, a corrected refused change), in
     * {@code list} at {@code sortOrder}.
     *
     * @throws TasksWire.Invalid a value the wire itself cannot carry
     */
    public TasksWire.Content whole(String list, int sortOrder) {
        return new TasksWire.Content(list, titleValue(), notesValue(), dueAt, priority, sortOrder, assignee);
    }

    /**
     * Where a task appended to {@code listId} goes: after the last of {@code contents}, the owner's tasks (null is the
     * Cloud Inbox).
     */
    public static int endOfList(Iterable<TasksWire.Content> contents, String listId) {
        int max = -1;
        for (TasksWire.Content content : contents) {
            if (Objects.equals(listId, content.listId)) {
                max = Math.max(max, content.sortOrder);
            }
        }
        return max == Integer.MAX_VALUE ? max : max + 1;
    }

    private static boolean sameNotes(String a, String b) {
        // "No notes" has two spellings on the wire (null and ""): moving between them is not an edit.
        return (blank(a) && blank(b)) || Objects.equals(a, b);
    }

    private static boolean blank(String text) {
        return text == null || ZgTasksTitlePolicy.trim(text).isEmpty();
    }
}
