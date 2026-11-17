package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.INBOX_INC;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.NOW_MS;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.SCOPE;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.WORK_INC;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.cloudStrings;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.create;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.device;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.inOrder;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.methodBody;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.readSource;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksBatchSplitTests.sealed;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.ALREADY_IMPORTED;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.BLOCKED_BY_FOLDER;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.CLOUD_INBOX;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.INVALID;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.NEW;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.PENDING;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.REMOVED_IN_CLOUD;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudImport.RowStatus.RESEND;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.A;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.TG;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.check;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.checks;
import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.expect;

/**
 * Host regression tests (RES-CX2-3) of the explicit copy's dialog contract on the real TasksCloudImport and
 * TasksAutoMigration sources, over TasksBatchSplitTests' device rows (no store, no remote):
 * <ul>
 *   <li>Under the R4 copy rules the dialog offers no folder choice, and a caller's typed title or Cloud Inbox choice is
 *   ignored: every folder but the built-in inbox becomes a list under its own normalized title with its folder mapping,
 *   so the migration's re-check rebuilds every create byte for byte (no 422 tasks_mutation_mismatch) and no copied
 *   folder counts as a new device row again. The R3 copy keeps its choices and typed titles.</li>
 *   <li>New tasks of a folder removed in Cloud preview as staying on the device (BLOCKED_BY_FOLDER) under both rule
 *   sets, so with no choice the preview counts exactly what Copy queues.</li>
 *   <li>The dialog (ZgTasksCopyDialog of org.telegram.zenlygram.tasks.ui, the import's ZenlyCloudTasksImportDialog
 *   ported; Android glue read as source; args[2] is the Android root): its summary, rules and skipped lines and its
 *   folder rows follow the preview's rules, with their strings in en, uz and ru.</li>
 * </ul>
 * ImportPreview.usesR4Rules and offersFolderChoice are looked up by reflection, so a build without them fails a check
 * here rather than the compile.
 */
public final class TasksCopyDialogTests {
    /** The migration's first enqueue (two days ago, server time) and its completed record, as in TasksBatchSplitTests. */
    static final long FIRST_ENQUEUE = NOW_MS - 172_800_000L;
    static final ZenlyTaskImport.MigrationRecord RECORD = ZenlyTaskImport.MigrationRecord.claimed(SCOPE, "ru", FIRST_ENQUEUE / 1000 - 5)
            .enqueued(FIRST_ENQUEUE / 1000, 0, 0, 0).completed(FIRST_ENQUEUE / 1000 + 60, 0);
    static final Set<String> NONE = Collections.emptySet();
    static final String DIALOG = TasksBatchSplitTests.COPY_DIALOG;

    /** Texts that stay as they were, in values, values-uz and values-ru: the R3 lines and the consent's destination. */
    static final String[][] KEPT = {
            {"ZenlyCloudTasksImportSummary", "%1$d folders and %2$d tasks can be copied as they are. Your tasks on this device stay as they are.",
                    "%1$d ta papka va %2$d ta vazifadan shundayligicha nusxa olish mumkin. Qurilmadagi vazifalaringiz o‘zgarmaydi.",
                    "Можно скопировать как есть: папок — %1$d, задач — %2$d. Задачи на этом устройстве останутся без изменений."},
            {"ZenlyCloudTasksImportSkipped", "These stay on this device (nothing is shortened to fit):",
                    "Bular shu qurilmada qoladi (hech narsa qisqartirilmaydi):",
                    "Эти останутся на этом устройстве (ничего не сокращается):"},
            {"ZenlyCloudTasksImportRules", "Only tasks that aren’t in Cloud yet are offered. Titles and notes that break the Cloud rules are "
                    + "trimmed or shortened in the copy; the tasks on this device stay as they are.",
                    "Faqat hali Bulutda boʻlmagan vazifalar taklif qilinadi. Bulut qoidalariga mos kelmaydigan sarlavha va izohlar nusxada "
                    + "qisqartiriladi; qurilmadagi vazifalar oʻzgarmaydi.",
                    "Предлагаются только задачи, которых ещё нет в облаке. Названия и заметки, нарушающие правила облака, в копии обрезаются "
                    + "или сокращаются; задачи на устройстве не меняются."},
            {"ZenlyCloudTasksImportDestination", "Copies are sent to the Zenlygram server as cloud tasks of the account %1$s.",
                    "Nusxalar Zenlygram serveriga %1$s hisobining bulutdagi vazifalari sifatida yuboriladi.",
                    "Копии отправляются на сервер Zenlygram как облачные задачи аккаунта %1$s."},
    };

    // ---- helpers ----

    static List<ZenlyTaskImport.Mapping> noMappings() {
        return Collections.<ZenlyTaskImport.Mapping>emptyList();
    }

    /** The R4 copy beside the completed migration of this scope: the record's locale (ru), times as its re-check judges them. */
    static TasksCloudImport.ImportPreview r4Preview(ZenlyTaskImport.Snapshot snapshot) {
        return TasksCloudImport.preview(TG, A, snapshot, NONE, NONE, NOW_MS, TasksCloudImport.Rules.forCopy(RECORD, SCOPE, "uz", NOW_MS));
    }

    /** The 6-argument preview: the R3 copy. */
    static TasksCloudImport.ImportPreview r3Preview(ZenlyTaskImport.Snapshot snapshot) {
        return TasksCloudImport.preview(TG, A, snapshot, NONE, NONE, NOW_MS);
    }

    static boolean usesR4Rules(TasksCloudImport.ImportPreview preview) {
        return (Boolean) call(preview, "usesR4Rules", new Class<?>[0]);
    }

    static boolean offersFolderChoice(TasksCloudImport.ImportPreview preview, TasksCloudImport.FolderRow row) {
        return (Boolean) call(preview, "offersFolderChoice", new Class<?>[]{TasksCloudImport.FolderRow.class}, row);
    }

    private static Object call(TasksCloudImport.ImportPreview preview, String name, Class<?>[] types, Object... arguments) {
        Method method;
        try {
            method = TasksCloudImport.ImportPreview.class.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("ImportPreview." + name + " is missing", e);
        }
        try {
            return method.invoke(preview, arguments);
        } catch (IllegalAccessException e) {
            throw new AssertionError("ImportPreview." + name + " is not public", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("ImportPreview." + name + " threw", e.getCause());
        }
    }

    static TasksCloudImport.TaskRow row(TasksCloudImport.ImportPreview preview, String taskId) {
        for (TasksCloudImport.TaskRow row : preview.tasks) if (row.localTaskId.equals(taskId)) return row;
        throw new AssertionError("no task row " + taskId);
    }

    static List<TasksCloudImport.RowStatus> statuses(TasksCloudImport.ImportPreview preview, String... taskIds) {
        ArrayList<TasksCloudImport.RowStatus> statuses = new ArrayList<>();
        for (String taskId : taskIds) statuses.add(row(preview, taskId).status);
        return statuses;
    }

    /** Tasks the dialog's folder row counts as staying behind its folder (its blockedTasks). */
    static int blocked(TasksCloudImport.ImportPreview preview, String folderId) {
        int count = 0;
        for (TasksCloudImport.TaskRow row : preview.tasks) if (row.status == BLOCKED_BY_FOLDER && folderId.equals(row.localFolderId)) count++;
        return count;
    }

    static ZenlyTaskImport.Mapping mapping(TasksCloudImport.Plan plan, String entity, String localKey) {
        for (ZenlyTaskImport.Mapping mapping : plan.mappings) if (mapping.entity.equals(entity) && mapping.localKey.equals(localKey)) return mapping;
        return null;
    }

    static TasksWire.Operation.TaskCreate taskCreate(TasksCloudImport.Plan plan, String taskId) {
        for (TasksWire.Operation operation : plan.operations) {
            if (operation instanceof TasksWire.Operation.TaskCreate && ((TasksWire.Operation.TaskCreate) operation).taskId.equals(taskId)) {
                return (TasksWire.Operation.TaskCreate) operation;
            }
        }
        return null;
    }

    /** With no choice the preview's counts are what Copy queues. */
    static TasksCloudImport.Plan queuesWhatItCounts(TasksCloudImport.ImportPreview preview, String what) {
        TasksCloudImport.Plan plan = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none());
        check(preview.importableLists == plan.lists && preview.importableTasks == plan.tasks,
                what + ": the preview counts " + preview.importableLists + "/" + preview.importableTasks + ", Copy queues " + plan.lists + "/" + plan.tasks);
        return plan;
    }

    /**
     * The migration's re-check of the scope hours later over the copy's mappings (the rows are absent, e.g. lost with a
     * logout): it rebuilds each mapped row from the device row under its reserved id, and the server hashes the body.
     */
    static void recheckRebuildsTheCopy(TasksCloudImport.Plan copy, String what) {
        TasksAutoMigration.MigrationPlan recheck = TasksAutoMigration.migrationPlan(device(copy.mappings, FIRST_ENQUEUE),
                new TasksMigrationNormalizer(RECORD.locale), NONE, NONE, NONE, 1, TasksAutoMigration.normalizedAt(RECORD, NOW_MS + 7_200_000L), true);
        Map<String, byte[]> sent = sealed(copy), rebuilt = sealed(recheck.plan);
        check(rebuilt.keySet().equals(sent.keySet()), what + ": the re-check rebuilds every create under its mutation id");
        for (Map.Entry<String, byte[]> entry : sent.entrySet()) {
            check(Arrays.equals(entry.getValue(), rebuilt.get(entry.getKey())), what + ": identical sealed bytes, no tasks_mutation_mismatch on a resend");
        }
    }

    // ---- R4: no folder choice ----

    /** A typed list title (the dialog's former "New list with a name you choose") is ignored: the folder's own title. */
    static void r4CopyKeepsTheFolderTitle() {
        TasksCloudImport.ImportPreview preview = r4Preview(device(noMappings(), FIRST_ENQUEUE));
        TasksCloudImport.Plan copy = TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderTitle(WORK_INC, "Typed name"));
        TasksWire.Operation.ListCreate list = (TasksWire.Operation.ListCreate) copy.operations.get(0);
        check("Ish".equals(list.title), "R4: the list takes the folder's own normalized title ('  Ish '), not a typed one: " + list.title);
        check(copy.lists == 1 && copy.tasks == 4 && copy.leftLocal == 0, "the plan a copy without a choice makes");
        ZenlyTaskImport.Mapping folder = mapping(copy, ZenlyTaskImport.Mapping.FOLDER, WORK_INC);
        check(folder != null && folder.cloudId.equals(list.listId) && folder.sortOrder == list.sortOrder, "its content-free folder mapping");
        recheckRebuildsTheCopy(copy, "a typed title");
        expect(TasksCloudError.class, () -> TasksCloudImport.plan(preview, TasksCloudImport.ImportChoices.none().withFolderTitle("gone", "X")),
                "a stale title choice is still refused under R4");
    }

    /**
     * A Cloud Inbox choice is ignored: the folder is its own list with its folder mapping. ZenlyTasksStore's
     * unmappedLiveRowCount counts a non-inbox folder without one (and a task without its own), so an unmapped copied
     * folder kept a completed copy's Device archive offering "Copy to Cloud" again.
     */
    static void r4CopyIgnoresACloudInboxChoice() {
        ZenlyTaskImport.Snapshot rows = device(noMappings(), FIRST_ENQUEUE);
        TasksCloudImport.Plan copy = TasksCloudImport.plan(r4Preview(rows), TasksCloudImport.ImportChoices.none().withFolderInCloudInbox(WORK_INC));
        check(copy.lists == 1 && copy.operations.get(0) instanceof TasksWire.Operation.ListCreate, "R4: the folder still becomes a list");
        TasksWire.Operation.ListCreate list = (TasksWire.Operation.ListCreate) copy.operations.get(0);
        check("Ish".equals(list.title), "under its own normalized title");
        for (String title : Arrays.asList("Report", "Задача без названия", "Fresh")) {
            check(list.listId.equals(create(copy, title).content.listId), "its task '" + title + "' goes under it, not into the Cloud Inbox");
        }
        check(create(copy, "Inbox task").content.listId == null, "the device Inbox's task goes to the Cloud Inbox");
        for (ZenlyTaskModel.Folder folder : rows.folders) {
            if (ZenlyTaskModel.INBOX_ID.equals(folder.id)) continue;
            ZenlyTaskImport.Mapping mapped = mapping(copy, ZenlyTaskImport.Mapping.FOLDER, rows.incarnations.get(folder.id));
            check(mapped != null && list.listId.equals(mapped.cloudId), "folder " + folder.id + " has its folder mapping (unmappedLiveRowCount's folder term 0)");
        }
        for (ZenlyTaskModel.Task task : rows.tasks) {
            check(mapping(copy, ZenlyTaskImport.Mapping.TASK, task.id) != null, "task " + task.id + " is mapped (the task term 0)");
        }
        recheckRebuildsTheCopy(copy, "a Cloud Inbox choice");
        expect(TasksCloudError.class, () -> TasksCloudImport.plan(r4Preview(rows), TasksCloudImport.ImportChoices.none().withFolderInCloudInbox("gone")),
                "a stale Cloud Inbox choice is still refused under R4");
    }

    /** Only the R3 copy offers a destination choice, and only for a folder not copied yet (NEW, or INVALID until a choice). */
    static void folderChoiceOnlyUnderR3() {
        ZenlyTaskImport.Snapshot rows = device(noMappings(), FIRST_ENQUEUE);
        TasksCloudImport.ImportPreview r4 = r4Preview(rows);
        check(usesR4Rules(r4), "the R4 copy rules made this preview");
        check(r4.folders.get(0).status == CLOUD_INBOX && r4.folders.get(1).status == NEW, "R4: the inbox is the Cloud Inbox, the folder new");
        for (TasksCloudImport.FolderRow row : r4.folders) check(!offersFolderChoice(r4, row), "R4: no choice for a " + row.status + " folder");
        TasksCloudImport.ImportPreview again = r4Preview(device(TasksCloudImport.plan(r4, TasksCloudImport.ImportChoices.none()).mappings, FIRST_ENQUEUE));
        check(again.folders.get(0).status == CLOUD_INBOX && again.folders.get(1).status == RESEND, "R4 over the copy's mappings: the folder resends");
        for (TasksCloudImport.FolderRow row : again.folders) check(!offersFolderChoice(again, row), "R4: no choice for a " + row.status + " folder");

        TasksCloudImport.ImportPreview r3 = r3Preview(rows);
        check(!usesR4Rules(r3), "the 6-argument preview is R3");
        for (TasksCloudImport.FolderRow row : r3.folders) {
            check(row.status == INVALID && offersFolderChoice(r3, row),
                    "R3: the INVALID folder '" + row.title + "' takes a choice (untitled inbox, edge whitespace)");
        }
        check(!offersFolderChoice(r3, null), "no row, no choice");

        long old = FIRST_ENQUEUE - 86_400_000L;
        List<ZenlyTaskModel.Folder> work = Collections.singletonList(new ZenlyTaskModel.Folder("work", "Work", old));
        Map<String, String> incarnations = Collections.singletonMap("work", WORK_INC);
        List<ZenlyTaskModel.Task> noTasks = Collections.emptyList();
        TasksCloudImport.ImportPreview fresh = r3Preview(new ZenlyTaskImport.Snapshot(work, incarnations, noTasks, noMappings()));
        check(fresh.folders.get(0).status == NEW && offersFolderChoice(fresh, fresh.folders.get(0)), "R3: a NEW folder takes a choice");
        TasksCloudImport.Plan r3Copy = TasksCloudImport.plan(fresh, TasksCloudImport.ImportChoices.none());
        String listId = ((TasksWire.Operation.ListCreate) r3Copy.operations.get(0)).listId, mutationId = r3Copy.mutationIds.get(0);
        ZenlyTaskImport.Snapshot copied = new ZenlyTaskImport.Snapshot(work, incarnations, noTasks, r3Copy.mappings);
        TasksCloudImport.ImportPreview resend = r3Preview(copied);
        TasksCloudImport.ImportPreview imported = TasksCloudImport.preview(TG, A, copied, Collections.singleton("list:" + listId), NONE, NOW_MS);
        TasksCloudImport.ImportPreview pending = TasksCloudImport.preview(TG, A, copied, NONE, Collections.singleton(mutationId), NOW_MS);
        check(resend.folders.get(0).status == RESEND && imported.folders.get(0).status == ALREADY_IMPORTED && pending.folders.get(0).status == PENDING,
                "R3, after its copy: the folder resends, is in Cloud, or waits in the outbox");
        for (TasksCloudImport.ImportPreview after : Arrays.asList(resend, imported, pending)) {
            check(!offersFolderChoice(after, after.folders.get(0)), "R3: no choice for a copied folder (" + after.folders.get(0).status + ")");
        }

        TasksCloudImport.Plan titled = TasksCloudImport.plan(r3, TasksCloudImport.ImportChoices.none().withFolderTitle(INBOX_INC, "Inbox"));
        TasksCloudImport.ImportPreview inboxAgain = r3Preview(device(titled.mappings, FIRST_ENQUEUE));
        TasksCloudImport.FolderRow inbox = inboxAgain.folders.get(0);
        check(inbox.status == INVALID && offersFolderChoice(inboxAgain, inbox), "R3 unchanged: a mapped inbox that re-previews INVALID still takes a choice");
    }

    /** Guard: the R3 copy sends exactly the titles the user typed, with the creates it always built. */
    static void r3TypedTitleUnchanged() {
        TasksCloudImport.ImportPreview r3 = r3Preview(device(noMappings(), FIRST_ENQUEUE));
        TasksCloudImport.Plan titled = TasksCloudImport.plan(r3,
                TasksCloudImport.ImportChoices.none().withFolderTitle(INBOX_INC, "Inbox").withFolderTitle(WORK_INC, "Work"));
        check(titled.lists == 2 && titled.tasks == 2 && titled.leftLocal == 2, "R3: two typed lists, the tasks it can send as they are; the others stay");
        TasksWire.Operation.ListCreate inbox = (TasksWire.Operation.ListCreate) titled.operations.get(0);
        TasksWire.Operation.ListCreate work = (TasksWire.Operation.ListCreate) titled.operations.get(1);
        check("Inbox".equals(inbox.title) && "Work".equals(work.title), "R3: exactly the typed titles");
        long old = FIRST_ENQUEUE - 86_400_000L;
        Map<String, byte[]> sent = sealed(titled);
        String inboxId = titled.mutationIds.get(0), workId = titled.mutationIds.get(1);
        check(Arrays.equals(sent.get(inboxId), TasksWire.Mutation.seal(A, inboxId, TasksWire.Operation.listCreate(inbox.listId, "Inbox", 0, null)).body())
                        && Arrays.equals(sent.get(workId), TasksWire.Mutation.seal(A, workId,
                        TasksWire.Operation.listCreate(work.listId, "Work", 1, TasksWire.instant(old / 1000))).body()),
                "R3: the creates as before (local order, the folder's own time, an unknown one sent as null)");
    }

    // ---- a folder removed in Cloud ----

    /**
     * A folder removed in Cloud makes no list: its new tasks stay on the device and preview so (BLOCKED_BY_FOLDER) under
     * both rule sets, its row offers no choice, and with no choice the preview counts exactly what Copy queues. A mapped
     * task keeps its own status.
     */
    static void removedFolderTasksStayOnTheDevice() {
        String folderCloudId = TasksWire.newId();
        ZenlyTaskImport.Mapping folder = new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.FOLDER, WORK_INC, folderCloudId, TasksWire.newId(), 0, null,
                true, true, 0);
        ZenlyTaskImport.Snapshot removed = device(Collections.singletonList(folder), FIRST_ENQUEUE);

        TasksCloudImport.ImportPreview r4 = r4Preview(removed);
        check(r4.folders.get(1).status == REMOVED_IN_CLOUD, "R4: the folder was removed in Cloud");
        TasksCloudImport.Plan r4Copy = queuesWhatItCounts(r4, "R4, removed folder");
        check(r4Copy.lists == 0 && r4Copy.tasks == 1 && r4Copy.leftLocal == 3, "R4: Copy queues the inbox task, three stay");
        check(statuses(r4, "t1", "t2", "t3", "t4").equals(Arrays.asList(NEW, BLOCKED_BY_FOLDER, BLOCKED_BY_FOLDER, BLOCKED_BY_FOLDER)),
                "R4: its new tasks stay on the device, the inbox task goes to the Cloud Inbox: " + statuses(r4, "t1", "t2", "t3", "t4"));
        check(r4.importableLists == 0 && r4.importableTasks == 1, "R4: one task to copy, not " + r4.importableTasks);
        check(blocked(r4, "work") == 3 && !offersFolderChoice(r4, r4.folders.get(1)), "R4: no choice; its row says three tasks stay on this device");

        TasksCloudImport.ImportPreview r3 = r3Preview(removed);
        check(r3.folders.get(1).status == REMOVED_IN_CLOUD, "R3: removed in Cloud");
        check(queuesWhatItCounts(r3, "R3, removed folder").tasks == 0, "R3: Copy queues no task");
        check(statuses(r3, "t1", "t2", "t3", "t4").equals(Arrays.asList(BLOCKED_BY_FOLDER, INVALID, INVALID, BLOCKED_BY_FOLDER)),
                "R3: t4 stays with its folder, t2 and t3 are refused as they are, t1 waits for the inbox's choice: " + statuses(r3, "t1", "t2", "t3", "t4"));
        check(r3.importableLists == 0 && r3.importableTasks == 0, "R3: nothing to copy, not " + r3.importableTasks);
        check(blocked(r3, "work") == 1 && !offersFolderChoice(r3, r3.folders.get(1)) && blocked(r3, "inbox") == 1 && offersFolderChoice(r3, r3.folders.get(0)),
                "R3: one task stays behind the removed folder (no choice); the inbox's waits for its choice");

        // Only a removed folder's new tasks: nothing to copy and no choice, so the dialog has only Close and "nothing".
        ZenlyTaskImport.Snapshot onlyRemoved = new ZenlyTaskImport.Snapshot(removed.folders, removed.incarnations, removed.tasks.subList(1, 4), removed.mappings);
        TasksCloudImport.ImportPreview nothing = r4Preview(onlyRemoved);
        boolean choice = false;
        for (TasksCloudImport.FolderRow row : nothing.folders) choice |= offersFolderChoice(nothing, row);
        check(nothing.importableLists == 0 && nothing.importableTasks == 0 && !choice, "R4: nothing copyable and no choice");
        TasksCloudImport.Plan nothingQueued = queuesWhatItCounts(nothing, "R4, only a removed folder");
        check(nothingQueued.operations.isEmpty() && nothingQueued.leftLocal == 3, "Copy would queue nothing");

        // A mapped task under the removed folder keeps its own status: acknowledged and absent, it is removed too.
        ZenlyTaskImport.Mapping t2 = new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.TASK, "t2", TasksWire.newId(), TasksWire.newId(), 0, folderCloudId,
                true, true, 0);
        ZenlyTaskImport.Snapshot mappedTask = device(Arrays.asList(folder, t2), FIRST_ENQUEUE);
        TasksCloudImport.ImportPreview r4Mapped = r4Preview(mappedTask), r3Mapped = r3Preview(mappedTask);
        check(row(r4Mapped, "t2").status == REMOVED_IN_CLOUD && row(r3Mapped, "t2").status == REMOVED_IN_CLOUD,
                "a mapped task keeps REMOVED_IN_CLOUD under both");
        TasksCloudImport.Plan r4MappedCopy = queuesWhatItCounts(r4Mapped, "R4, t2 removed");
        check(r4Mapped.importableTasks == 1 && r4MappedCopy.tasks == 1 && r4MappedCopy.leftLocal == 2, "R4: the inbox task; t3 and t4 stay");
        TasksCloudImport.Plan r3MappedCopy = queuesWhatItCounts(r3Mapped, "R3, t2 removed");
        check(r3Mapped.importableTasks == 0 && r3MappedCopy.tasks == 0 && r3MappedCopy.leftLocal == 3, "R3: nothing; t1, t3 and t4 stay");

        // Pinned Android-only behaviour (a recorded residual; iOS keeps such a row local): a mapped task whose earlier send
        // has an unknown outcome is offered and resent under its reserved identity into the removed folder's list.
        String t3Cloud = TasksWire.newId();
        ZenlyTaskImport.Mapping t3 = new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.TASK, "t3", t3Cloud, TasksWire.newId(), 1024, folderCloudId,
                true, false, 0);
        TasksCloudImport.ImportPreview attempted = r4Preview(device(Arrays.asList(folder, t2, t3), FIRST_ENQUEUE));
        check(row(attempted, "t3").status == RESEND && attempted.importableTasks == 2, "R4: an attempted mapped task reads RESEND and is counted");
        TasksCloudImport.Plan resent = queuesWhatItCounts(attempted, "R4, t3 attempted");
        TasksWire.Operation.TaskCreate t3Create = taskCreate(resent, t3Cloud);
        check(t3Create != null && folderCloudId.equals(t3Create.content.listId) && resent.leftLocal == 1,
                "R4 (Android only): resent into the removed folder's list; t4 stays");
    }

    // ---- the dialog ----

    /** The dialog's lines and folder rows follow the preview's rules; its R3 texts stay as they were (source and strings). */
    static void dialogWordingFollowsTheRules(String androidRoot) throws IOException {
        String source = readSource(androidRoot, DIALOG);
        String constructor = methodBody(source,
                "private ZgTasksCopyDialog(Context context, CharSequence account, TasksCloudImport.ImportPreview preview, Host host)");
        inOrder(constructor, "the dialog's lines follow the preview's rules",
                "boolean r4 = preview.usesR4Rules();",
                "r4 ? R.string.ZenlyCloudTasksImportSummaryR4 : R.string.ZenlyCloudTasksImportSummary",
                "if (r4) content.addView(info(context, resources, text(R.string.ZenlyCloudTasksImportRules)), spaced(8));",
                "boolean actionable = preview.importableLists > 0 || preview.importableTasks > 0;",
                "new FolderChoice(context, folder, blockedTasks(preview, folder), preview.offersFolderChoice(folder), host)",
                "actionable |= choice.canChoose;",
                "r4 ? R.string.ZenlyCloudTasksImportSkippedR4 : R.string.ZenlyCloudTasksImportSkipped",
                "if (!actionable) {",
                "R.string.ZenlyCloudTasksImportNothing",
                "text(actionable ? R.string.ZenlyCloudTasksCancel : R.string.ZenlyCloudTasksClose)",
                "if (actionable) builder.setPositiveButton(");
        check(constructor.indexOf("R.string.ZenlyCloudTasksImportRules") == constructor.lastIndexOf("R.string.ZenlyCloudTasksImportRules"),
                "the rules line shows only under the R4 rules");
        String folderRow = methodBody(source, "FolderChoice(Context context, TasksCloudImport.FolderRow row, int blocked, boolean choosable, Host host)");
        inOrder(folderRow, "a folder row offers a choice only when the preview does",
                "choosable ? R.string.ZenlyCloudTasksImportBlockedCount : R.string.ZenlyCloudTasksImportStaysCount",
                "canChoose = choosable;",
                "if (!canChoose) return;");
        check(!folderRow.contains("canChoose = row.status"), "a folder row no longer decides its choice by its status");

        Map<String, Map<String, String>> locales = new LinkedHashMap<>();
        for (String folder : Arrays.asList("values", "values-uz", "values-ru")) locales.put(folder, cloudStrings(androidRoot, folder));
        for (String key : Arrays.asList("ZenlyCloudTasksImportSummaryR4", "ZenlyCloudTasksImportSkippedR4", "ZenlyCloudTasksImportStaysCount")) {
            String en = locales.get("values").get(key);
            for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
                String value = locale.getValue().get(key);
                check(value != null && !value.trim().isEmpty() && (locale.getKey().equals("values") || !value.equals(en)),
                        locale.getKey() + " has " + key + ", translated");
            }
            String uz = locales.get("values-uz").get(key);
            check(uz.indexOf('\'') < 0 && uz.indexOf('‘') < 0 && uz.indexOf('’') < 0, "uz " + key + ": no ASCII or quote apostrophe");
        }
        for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
            Map<String, String> strings = locale.getValue();
            String summary = strings.get("ZenlyCloudTasksImportSummaryR4");
            check(summary.contains("%1$d") && summary.contains("%2$d") && strings.get("ZenlyCloudTasksImportStaysCount").contains("%1$d")
                            && strings.get("ZenlyCloudTasksImportSkippedR4").indexOf('%') < 0,
                    locale.getKey() + ": the format arguments the dialog passes");
        }
        Map<String, String> en = locales.get("values");
        check(!en.get("ZenlyCloudTasksImportSummaryR4").contains("as they are") && !en.get("ZenlyCloudTasksImportSkippedR4").contains("shortened"),
                "en: the R4 lines do not describe the R3 copy");
        for (String[] kept : KEPT) {
            int column = 1;
            for (Map.Entry<String, Map<String, String>> locale : locales.entrySet()) {
                check(kept[column++].equals(locale.getValue().get(kept[0])), locale.getKey() + " " + kept[0] + ": unchanged");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // args[0] and args[1] (the SQLite bridge and a work directory) are the state runner's usual arguments; these
        // checks open no store.
        String androidRoot = args[2];
        r4CopyKeepsTheFolderTitle();
        r4CopyIgnoresACloudInboxChoice();
        folderChoiceOnlyUnderR3();
        r3TypedTitleUnchanged();
        removedFolderTasksStayOnTheDevice();
        dialogWordingFollowsTheRules(androidRoot);
        System.out.println("Tasks copy dialog (R4: no folder choice, each folder its own list; removed folders keep new tasks on the device; "
                + "wording by rules): " + checks + " checks passed");
    }
}
