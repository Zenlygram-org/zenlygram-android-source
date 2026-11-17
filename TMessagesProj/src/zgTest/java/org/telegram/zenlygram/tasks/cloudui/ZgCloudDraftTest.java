/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.cloudui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudDraft.Field;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudDraft.Problem;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

/** The editor's draft: what counts as an edit, the limits, and the content Save hands to the repository. */
public class ZgCloudDraftTest {

    private static final String LIST_A = "11111111-1111-4111-8111-111111111111";
    private static final String LIST_B = "22222222-2222-4222-8222-222222222222";
    private static final TasksWire.Annotation ALISHER = new TasksWire.Annotation("777000111", "Alisher");

    private static TasksWire.Content content(String list, String title, String notes, Long dueAt, int priority, int sortOrder,
                                             TasksWire.Annotation assignee) {
        return new TasksWire.Content(list, title, notes, dueAt, priority, sortOrder, assignee);
    }

    private static TasksWire.Content original() {
        return content(LIST_A, "Buy milk", "2 litres", 1_800_000_000L, 2, 7, ALISHER);
    }

    private static String repeat(String unit, int count) {
        final StringBuilder sb = new StringBuilder(unit.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    @Test
    public void aFreshDraftHasNothingToSave() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        assertFalse(draft.dirty());
        assertTrue(draft.touchedFields().isEmpty());
        assertNull(draft.problem());
        assertEquals("Buy milk", draft.title);
        assertEquals("2 litres", draft.notes);
        // Saving it would change nothing: the merged content is the task as it is.
        assertEquals(original(), draft.merged(original(), 99));
        // A task without notes shows an empty field.
        assertEquals("", new ZgCloudDraft(content(null, "A", null, null, 0, 0, null)).notes);
    }

    @Test
    public void whiteSpaceAloneIsNotAnEdit() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.title = "  Buy milk \n";
        assertFalse(draft.touched(Field.TITLE));
        assertEquals("Buy milk", draft.titleValue());
        draft.title = "Buy milk and bread ";
        assertTrue(draft.touched(Field.TITLE));
        assertEquals("Buy milk and bread", draft.titleValue());
        assertEquals("Buy milk and bread", draft.merged(original(), 0).title);

        // Notes are sent exactly as typed; only "nothing visible" becomes "no notes".
        final ZgCloudDraft notes = new ZgCloudDraft(content(null, "A", null, null, 0, 0, null));
        notes.notes = "   \n ";
        assertFalse(notes.dirty());
        assertNull(notes.notesValue());
        notes.notes = " keep the spaces ";
        assertTrue(notes.touched(Field.NOTES));
        assertEquals(" keep the spaces ", notes.merged(content(null, "A", null, null, 0, 0, null), 0).description);
    }

    @Test
    public void emptyAndMissingNotesAreTheSameThing() {
        // The wire tells "" from null; moving between them is not a change the user made.
        final TasksWire.Content empty = content(null, "A", "", null, 0, 0, null);
        final ZgCloudDraft draft = new ZgCloudDraft(empty);
        assertFalse(draft.dirty());
        assertEquals("", draft.merged(empty, 0).description);
        draft.notes = "x";
        assertTrue(draft.dirty());
        draft.notes = "";
        assertFalse(draft.dirty());
        draft.notes = "  ";
        assertFalse(draft.dirty());
        // Clearing real notes is an edit, and it clears them.
        final ZgCloudDraft cleared = new ZgCloudDraft(original());
        cleared.notes = "";
        assertTrue(cleared.touched(Field.NOTES));
        assertNull(cleared.merged(original(), 0).description);
    }

    @Test
    public void everyFieldIsTrackedOnItsOwn() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.listId = null;
        assertEquals(EnumSet.of(Field.LIST), draft.touchedFields());
        draft.dueAt = null;
        draft.priority = 4;
        draft.assignee = null;
        assertEquals(EnumSet.of(Field.LIST, Field.DEADLINE, Field.PRIORITY, Field.ASSIGNEE), draft.touchedFields());
        final TasksWire.Content merged = draft.merged(original(), 31);
        assertNull(merged.listId);
        assertNull(merged.dueAt);
        assertEquals(4, merged.priority);
        assertNull(merged.annotation);
        assertEquals("Buy milk", merged.title);
        assertEquals("2 litres", merged.description);
        // Moved to another list: it goes to that list's end.
        assertEquals(31, merged.sortOrder);
        // Chosen back: nothing is left to save.
        draft.listId = LIST_A;
        draft.dueAt = 1_800_000_000L;
        draft.priority = 2;
        draft.assignee = new TasksWire.Annotation("777000111", "Alisher");
        assertFalse(draft.dirty());
    }

    @Test
    public void aTaskThatStaysInItsListKeepsItsPlace() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.title = "Buy bread";
        assertEquals(7, draft.merged(original(), 31).sortOrder);
    }

    @Test
    public void theLimitsAreCheckedOnlyOnTextTheUserChanged() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.title = "   ";
        assertEquals(Problem.TITLE_EMPTY, draft.problem());
        draft.title = "\u200B\u0301";
        assertEquals(Problem.TITLE_EMPTY, draft.problem());
        draft.title = repeat("a", 200);
        assertNull(draft.problem());
        draft.title = repeat("a", 201);
        assertEquals(Problem.TITLE_TOO_LONG, draft.problem());
        // Characters as the user sees them: a letter with a combining accent is one.
        draft.title = repeat("e\u0301", 200);
        assertNull(draft.problem());
        draft.title = repeat("e\u0301", 201);
        assertEquals(Problem.TITLE_TOO_LONG, draft.problem());

        draft.title = "Buy milk";
        draft.notes = repeat("n", ZgCloudDraft.NOTES_LIMIT);
        assertNull(draft.problem());
        draft.notes = repeat("n", ZgCloudDraft.NOTES_LIMIT + 1);
        assertEquals(Problem.NOTES_TOO_LONG, draft.problem());
        // 4096 characters, but more than 16384 bytes.
        draft.notes = repeat("e\u0301\u0301", ZgCloudDraft.NOTES_LIMIT);
        assertTrue(ZgCloudDraft.notesTooLong(draft.notes));
        assertEquals(Problem.NOTES_TOO_LONG, draft.problem());

        // A title another app stored longer than this one allows is not refused until it is edited.
        final TasksWire.Content stored = content(null, repeat("b", 300), repeat("n", 5000), null, 0, 0, null);
        final ZgCloudDraft foreign = new ZgCloudDraft(stored);
        foreign.priority = 1;
        assertNull(foreign.problem());
        assertEquals(stored.title, foreign.merged(stored, 0).title);
        assertEquals(stored.description, foreign.merged(stored, 0).description);
        foreign.title = repeat("b", 299);
        assertEquals(Problem.TITLE_TOO_LONG, foreign.problem());
    }

    @Test
    public void untouchedFieldsFollowANewerVersionAndTouchedOnesStay() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.title = "Buy oat milk";
        final TasksWire.Content newer = content(LIST_B, "Buy milk!", "3 litres", null, 0, 12, null);
        final EnumSet<Field> moved = draft.rebase(newer);
        assertEquals(EnumSet.of(Field.LIST, Field.NOTES, Field.DEADLINE, Field.PRIORITY, Field.ASSIGNEE), moved);
        assertEquals("Buy oat milk", draft.title);
        assertEquals(LIST_B, draft.listId);
        assertEquals("3 litres", draft.notes);
        assertNull(draft.dueAt);
        assertEquals(0, draft.priority);
        assertNull(draft.assignee);
        assertEquals(EnumSet.of(Field.TITLE), draft.touchedFields());
        // The title was changed here and elsewhere: the one question Save asks.
        assertEquals(EnumSet.of(Field.TITLE), draft.conflicts(newer));
        // Applied on top of the newer version: only the title is the user's, the rest is what the task is now.
        assertEquals(content(LIST_B, "Buy oat milk", "3 litres", null, 0, 12, null), draft.merged(newer, 99));
    }

    @Test
    public void aChangeElsewhereToAnUntouchedFieldIsNoConflict() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.notes = "1 litre";
        final TasksWire.Content newer = content(LIST_A, "Buy milk today", "2 litres", 1_800_000_000L, 2, 7, ALISHER);
        assertTrue(draft.conflicts(newer).isEmpty());
        draft.rebase(newer);
        assertEquals("Buy milk today", draft.title);
        assertTrue(draft.conflicts(newer).isEmpty());
        assertEquals(content(LIST_A, "Buy milk today", "1 litre", 1_800_000_000L, 2, 7, ALISHER), draft.merged(newer, 0));
        // The same notes arriving from elsewhere: nothing left to save, nothing to ask.
        final TasksWire.Content same = content(LIST_A, "Buy milk today", "1 litre", 1_800_000_000L, 2, 7, ALISHER);
        assertTrue(draft.conflicts(same).isEmpty());
        assertEquals(same, draft.merged(same, 0));
    }

    @Test
    public void aSecondNewerVersionStillConflictsWithTheFirstEdit() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.priority = 4;
        draft.rebase(content(LIST_A, "Buy milk", "2 litres", 1_800_000_000L, 1, 7, ALISHER));
        // The priority the user started from was 2; it is 3 now.
        final TasksWire.Content latest = content(LIST_A, "Buy milk", "2 litres", 1_800_000_000L, 3, 7, ALISHER);
        draft.rebase(latest);
        assertEquals(4, draft.priority);
        assertEquals(EnumSet.of(Field.PRIORITY), draft.conflicts(latest));
        assertEquals(2, draft.base().priority);
    }

    @Test
    public void aTypedTitleIsKeptWhenOnlyItsSpacesDiffer() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.title = "Buy milk ";
        // Nothing changed elsewhere: the field is not rewritten under the cursor.
        assertTrue(draft.rebase(original()).isEmpty());
        assertEquals("Buy milk ", draft.title);
    }

    @Test
    public void theWholeDraftBecomesATaskOfItsOwn() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.title = " Buy oat milk ";
        draft.notes = "";
        final TasksWire.Content copy = draft.whole(null, 5);
        assertEquals(content(null, "Buy oat milk", null, 1_800_000_000L, 2, 5, ALISHER), copy);
        // The same edits, as edits of another version.
        final TasksWire.Content server = content(LIST_B, "Buy milk", "2 litres", null, 0, 1, null);
        final ZgCloudDraft onto = draft.onto(server);
        assertSame(server, onto.base());
        assertEquals(EnumSet.of(Field.LIST, Field.TITLE, Field.NOTES, Field.DEADLINE, Field.PRIORITY, Field.ASSIGNEE), onto.touchedFields());
        assertEquals(content(LIST_A, "Buy oat milk", null, 1_800_000_000L, 2, 9, ALISHER), onto.merged(server, 9));
    }

    @Test
    public void aValueTheWireCannotCarryNamesItsField() {
        final ZgCloudDraft draft = new ZgCloudDraft(original());
        draft.dueAt = TasksWire.MAX_DEADLINE + 1;
        try {
            draft.merged(original(), 0);
            fail();
        } catch (TasksWire.Invalid e) {
            assertEquals("content.due_at", e.field);
        }
        draft.dueAt = TasksWire.MAX_DEADLINE;
        assertEquals(Long.valueOf(TasksWire.MAX_DEADLINE), draft.merged(original(), 0).dueAt);
        draft.priority = 5;
        try {
            draft.whole(null, 0);
            fail();
        } catch (TasksWire.Invalid e) {
            assertEquals("content.priority", e.field);
        }
    }

    @Test
    public void aNewTaskGoesAfterTheLastOfItsList() {
        final Iterable<TasksWire.Content> tasks = Arrays.asList(
            content(LIST_A, "a", null, null, 0, 3, null),
            content(LIST_A, "b", null, null, 0, 9, null),
            content(null, "c", null, null, 0, 4, null),
            content(LIST_B, "d", null, null, 0, Integer.MAX_VALUE, null));
        assertEquals(10, ZgCloudDraft.endOfList(tasks, LIST_A));
        assertEquals(5, ZgCloudDraft.endOfList(tasks, null));
        assertEquals(Integer.MAX_VALUE, ZgCloudDraft.endOfList(tasks, LIST_B));
        assertEquals(0, ZgCloudDraft.endOfList(Collections.<TasksWire.Content>emptyList(), LIST_A));
    }
}
