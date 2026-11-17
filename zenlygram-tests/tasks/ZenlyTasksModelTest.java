import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel.*;

/** Run through run_tests.py; no Android SDK or JUnit dependency. */
public final class ZenlyTasksModelTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static Task task(String id, String folder, String name, Long due, int priority, boolean completed) {
        return new Task(id, folder, name, "project notes", due, priority, completed,
                completed ? 10L : null, 5, 20);
    }

    private static List<Task> select(List<Task> tasks, String folder, Filter filter, Sort sort, String query) {
        return ZenlyTaskModel.select(tasks, folder, filter, sort, query, 150, 100, 200);
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected validation failure");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    public static void main(String[] args) {
        check(title("  Release  ", MAX_TITLE).equals("Release"), "Trim title");
        invalid(() -> title(" \t\n ", MAX_TITLE));
        invalid(() -> title(null, MAX_TITLE));
        invalid(() -> title(String.join("", Collections.nCopies(201, "x")), MAX_TITLE));
        check(description(null).isEmpty(), "Null notes are empty");
        invalid(() -> description(String.join("", Collections.nCopies(10001, "x"))));
        // Tasks R4 validators (design 30 §6.5): the server's edge whitespace, a visible scalar, 4096 units / 16384 bytes.
        check(title("\u00A0\u3000Ish\u2028\u0085", MAX_TITLE).equals("Ish"), "Server edge whitespace trimmed (NBSP, ideographic, U+2028, U+0085)");
        check(title("\tIsh\n", MAX_TITLE).equals("Ish"), "Control edges trimmed");
        invalid(() -> title("\u200B", MAX_TITLE));
        invalid(() -> title("\u200B\u0301 \t", MAX_TITLE));
        invalid(() -> title("\u3000", MAX_FOLDER_TITLE));
        check(title("a\u200Bb", MAX_TITLE).equals("a\u200Bb"), "Inner invisible scalars stay");
        check(MAX_DESCRIPTION == 4096 && MAX_DESCRIPTION_BYTES == 16384, "R4 description limits");
        check(description(String.join("", Collections.nCopies(4096, "x"))).length() == 4096, "4096 units accepted");
        invalid(() -> description(String.join("", Collections.nCopies(4097, "x"))));
        check(description(String.join("", Collections.nCopies(4096, "\u0436"))).length() == 4096, "4096 two-byte chars = 8192 bytes accepted");
        invalid(() -> description(String.join("", Collections.nCopies(2049, "\uD83D\uDE00"))));
        check(trimEdges("\u2003x\u2003").equals("x") && trimEdges(" \t").isEmpty(), "trimEdges");
        check(hasVisibleScalar("\uD83D\uDE00") && !hasVisibleScalar("\u200D\uFE0F"), "Emoji visible, joiners not");
        check(isEdgeWhitespace(0x85) && isEdgeWhitespace(0x2029) && !isEdgeWhitespace(0x200B), "Edge whitespace set");
        validateTask("Task", "", null, 0);
        validateTask("Task", "", 100L, 4);
        invalid(() -> validateTask("Task", "", 0L, 0));
        invalid(() -> validateTask("Task", "", -1L, 0));
        invalid(() -> validateTask("Task", "", null, -1));
        invalid(() -> validateTask("Task", "", null, 5));

        Task overdue = task("a", "one", "Write report", 99L, 1, false);
        Task today = task("b", "one", "Review", 170L, 4, false);
        Task noDate = task("c", "two", "Call Alex", null, 2, false);
        Task done = task("d", "one", "Archive", 110L, 4, true);
        Task tomorrow = task("e", "two", "Publish", 200L, 0, false);
        List<Task> tasks = Arrays.asList(done, noDate, tomorrow, today, overdue);
        check(select(tasks, "one", Filter.ALL, Sort.UPDATED, "").size() == 3, "Folder isolation");
        check(select(tasks, "missing", Filter.ALL, Sort.UPDATED, "").isEmpty(), "Unknown folder");
        check(select(tasks, null, Filter.OPEN, Sort.UPDATED, "").size() == 4, "Open filter");
        check(select(tasks, null, Filter.COMPLETED, Sort.UPDATED, "").equals(Collections.singletonList(done)), "Completed filter");
        check(select(tasks, null, Filter.TODAY, Sort.UPDATED, "").equals(Collections.singletonList(today)), "Today excludes tomorrow, completed, undated");
        check(select(tasks, null, Filter.OVERDUE, Sort.UPDATED, "").equals(Collections.singletonList(overdue)), "Overdue excludes completed");
        check(select(tasks, null, Filter.ALL, Sort.UPDATED, "  ALEX ").equals(Collections.singletonList(noDate)), "Case-insensitive trimmed search");
        check(select(tasks, null, Filter.ALL, Sort.UPDATED, "project").size() == 5, "Search descriptions");
        check(select(tasks, "one", Filter.OPEN, Sort.UPDATED, "alex").isEmpty(), "Combine search and folder");
        List<Task> dueOrder = select(tasks, null, Filter.ALL, Sort.DUE_DATE, "");
        check(dueOrder.equals(Arrays.asList(overdue, today, tomorrow, noDate, done)), "Undated after dated; completed last");
        check(select(tasks, null, Filter.ALL, Sort.PRIORITY, "").equals(Arrays.asList(today, noDate, overdue, tomorrow, done)), "Highest priority first");
        check(select(tasks, null, Filter.ALL, Sort.TITLE, "").get(0) == noDate, "Alphabetic sort");
        check(tasks.get(0) == done, "Selection never mutates input");

        Task dayStart = task("f", "one", "Start", 100L, 0, false);
        check(select(Collections.singletonList(dayStart), null, Filter.TODAY, Sort.UPDATED, "").size() == 1, "Today includes midnight boundary");
        Task now = task("g", "one", "Now", 150L, 0, false);
        check(select(Collections.singletonList(now), null, Filter.OVERDUE, Sort.UPDATED, "").isEmpty(), "Due at current instant is not past");
        List<Task> stable = select(Arrays.asList(today, overdue), null, Filter.ALL, Sort.UPDATED, "");
        check(stable.get(0) == overdue, "Deterministic ID tie-break");
        Task newer = new Task("h", "one", "Newer", "", null, 0, false, null, 100, 200);
        check(select(Arrays.asList(newer, overdue), null, Filter.ALL, Sort.UPDATED, "").get(0) == newer, "Updated newest first");
        check(select(Arrays.asList(newer, overdue), null, Filter.ALL, Sort.CREATED, "").get(0) == overdue, "Created oldest first");

        ArrayList<Task> mutable = new ArrayList<>(tasks);
        Snapshot snapshot = new Snapshot(Collections.emptyList(), mutable);
        mutable.clear();
        check(snapshot.tasks.size() == 5, "Snapshot copies its input");
        try {
            snapshot.tasks.clear();
            throw new AssertionError("Snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { checks++; }
        System.out.println("Task model: " + checks + " checks passed");
    }
}
