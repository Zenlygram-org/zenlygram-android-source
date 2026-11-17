package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit, consented import of an immutable local snapshot into the current principal's cloud. Originals stay local.
 * Local times are Unix milliseconds, cloud times are seconds. Two rule sets: R3 (a preview without {@link Rules}):
 * nothing is truncated, re-filed or defaulted silently, a row the server would refuse stays local, and a folder not
 * copied yet can take the user's explicit choice ({@link ImportChoices}). R4 (design 30 §7.1, §6.5; {@link Rules}): the
 * built-in inbox is the virtual Cloud Inbox, every other folder a list under its own normalized title (no choice), and
 * every row is normalized by {@link TasksMigrationNormalizer} exactly as the automatic migration builds it, because both
 * share the import scope: the migration's re-check of that scope rebuilds an explicitly copied row from the device row
 * and its content-free mapping, and a resend must carry the bytes of its first send (the server hashes them). Under
 * both, the new tasks of a folder that makes no list stay local, and the preview says so (BLOCKED_BY_FOLDER).
 */
public final class TasksCloudImport {
    /** Server accepts historical creation dates up to server-now + 300 s. */
    static final long FUTURE_SLACK_MS = 300_000;

    /**
     * BLOCKED_BY_FOLDER — a new task whose folder makes no list for it, so it stays local: the folder is INVALID (R3:
     * until a choice for it; R4: a device title the normalizer cannot repair) or REMOVED_IN_CLOUD (never re-created).
     * R4 (design 30 §6.5): CLOUD_INBOX — the fixed local inbox is the virtual Cloud Inbox (no list.create);
     * REMOVED_IN_CLOUD — the create's receipt was recorded locally and the row is absent from the complete cloud
     * snapshot (re-verified at the current resync generation): removed in the cloud, never re-created.
     */
    public enum RowStatus { NEW, ALREADY_IMPORTED, PENDING, RESEND, INVALID, BLOCKED_BY_FOLDER, CLOUD_INBOX, REMOVED_IN_CLOUD }

    public static final class FolderRow {
        public final String localFolderId, incarnation, title;
        public final boolean inbox;
        public final long createdAtMillis;
        public final RowStatus status;
        public final String invalidField;

        FolderRow(String localFolderId, String incarnation, String title, boolean inbox, long createdAtMillis, RowStatus status, String invalidField) {
            this.localFolderId = localFolderId;
            this.incarnation = incarnation;
            this.title = title;
            this.inbox = inbox;
            this.createdAtMillis = createdAtMillis;
            this.status = status;
            this.invalidField = invalidField;
        }
    }

    public static final class TaskRow {
        public final String localTaskId, localFolderId, parentIncarnation, title;
        public final RowStatus status;
        public final String invalidField;

        TaskRow(String localTaskId, String localFolderId, String parentIncarnation, String title, RowStatus status, String invalidField) {
            this.localTaskId = localTaskId;
            this.localFolderId = localFolderId;
            this.parentIncarnation = parentIncarnation;
            this.title = title;
            this.status = status;
            this.invalidField = invalidField;
        }
    }

    /**
     * Consent preview. Destination = this signed-in Telegram account's linked cloud principal (server-processed, not
     * end-to-end encrypted). Android local tasks carry no assignee annotation, so no person name/id is ever uploaded.
     */
    public static final class ImportPreview {
        /** Raw Telegram user id of the destination account (UI shows its name; never sent by import). */
        public final long telegramUserId;
        public final String principal;
        public final List<FolderRow> folders;
        public final List<TaskRow> tasks;
        public final int importableLists, importableTasks;
        final ZenlyTaskImport.Snapshot snapshot;
        final long nowMillis;
        /** The R4 copy's rules; null = R3. The plan follows the rules its preview was made with. */
        final Rules rules;

        ImportPreview(long telegramUserId, String principal, List<FolderRow> folders, List<TaskRow> tasks,
                      ZenlyTaskImport.Snapshot snapshot, long nowMillis, Rules rules) {
            this.telegramUserId = telegramUserId;
            this.principal = principal;
            this.folders = Collections.unmodifiableList(new ArrayList<>(folders));
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
            int lists = 0, items = 0;
            for (FolderRow row : folders) if (row.status == RowStatus.NEW || row.status == RowStatus.RESEND) lists++;
            for (TaskRow row : tasks) if (row.status == RowStatus.NEW || row.status == RowStatus.RESEND) items++;
            this.importableLists = lists;
            this.importableTasks = items;
            this.snapshot = snapshot;
            this.nowMillis = nowMillis;
            this.rules = rules;
        }

        /** The R4 copy rules made this preview (the copy dialog's wording follows them); false: R3. */
        public boolean usesR4Rules() { return rules != null; }

        /**
         * The copy dialog offers a destination choice for {@code row} only under the R3 rules, for a folder not copied
         * yet (NEW, or INVALID until a choice). Under R4 every create must be the one the migration's re-check rebuilds
         * from the device row (design 30 §7.1), and the content-free mapping keeps no title.
         */
        public boolean offersFolderChoice(FolderRow row) {
            return rules == null && row != null && (row.status == RowStatus.NEW || row.status == RowStatus.INVALID);
        }
    }

    /**
     * The R4 copy's rules (design 30 §7.1): {@link TasksMigrationNormalizer} in the copy's locale, and the server time
     * (ms) a row's created_at/completed_at are checked against.
     */
    static final class Rules {
        final TasksMigrationNormalizer normalizer;
        final long normalizedAtMillis;

        Rules(TasksMigrationNormalizer normalizer, long normalizedAtMillis) {
            if (normalizer == null) throw new IllegalArgumentException("normalizer");
            this.normalizer = normalizer;
            this.normalizedAtMillis = normalizedAtMillis;
        }

        /**
         * The explicit copy into {@code scope} beside the account's migration record ({@code record}, null = none):
         * the record's locale (placeholder titles), else the UI {@code language}'s. While that migration re-checks its
         * copy in this scope (enqueued, completed) the times are checked against its first enqueue
         * ({@link TasksAutoMigration#normalizedAt}), as the re-check rebuilds them; else against {@code nowMillis}, the
         * server's time now.
         */
        static Rules forCopy(ZenlyTaskImport.MigrationRecord record, String scope, String language, long nowMillis) {
            String locale = record != null ? record.locale : TasksMigrationNormalizer.localeFor(language);
            boolean rechecked = record != null && scope != null && scope.equals(record.scope)
                    && (ZenlyTaskImport.MigrationRecord.ENQUEUED.equals(record.state) || ZenlyTaskImport.MigrationRecord.COMPLETED.equals(record.state));
            return new Rules(new TasksMigrationNormalizer(locale), rechecked ? TasksAutoMigration.normalizedAt(record, nowMillis) : nowMillis);
        }

        /** The migration plan's own conversion: whole seconds. */
        long nowSeconds() {
            return normalizedAtMillis / 1000;
        }

        /** list.create as TasksAutoMigration.migrationPlan builds it; a mapped row keeps its sort order. */
        TasksWire.Operation.ListCreate listCreate(String listId, String title, ZenlyTaskModel.Folder folder, int listIndex, ZenlyTaskImport.Mapping mapping) {
            TasksMigrationNormalizer.List list = normalizer.list(title, folder.createdAt, listIndex, nowSeconds());
            return TasksWire.Operation.listCreate(listId, list.title, mapping == null ? list.sortOrder : mapping.sortOrder, list.createdAt);
        }

        /** task.create as TasksAutoMigration.migrationPlan builds it (never an annotation); a mapped row keeps its order. */
        TasksWire.Operation.TaskCreate taskCreate(String taskId, String listId, ZenlyTaskModel.Task task, int position, ZenlyTaskImport.Mapping mapping) {
            TasksMigrationNormalizer.Task normalized = normalizer.task(task, position, nowSeconds());
            return TasksWire.Operation.taskCreate(taskId, new TasksWire.Content(listId, normalized.title, normalized.description,
                    normalized.dueAt, normalized.priority, mapping == null ? normalized.sortOrder : mapping.sortOrder, null),
                    normalized.createdAt, normalized.completed, normalized.completedAt);
        }
    }

    /** Immutable explicit user choices for folders that are not importable as-is. */
    public static final class ImportChoices {
        final Map<String, String> titles;
        final Set<String> cloudInbox;

        private ImportChoices(Map<String, String> titles, Set<String> cloudInbox) {
            this.titles = Collections.unmodifiableMap(titles);
            this.cloudInbox = Collections.unmodifiableSet(cloudInbox);
        }

        public static ImportChoices none() {
            return new ImportChoices(new HashMap<String, String>(), new HashSet<String>());
        }

        /**
         * The user typed a cloud list title for this folder (e.g. the untitled local Inbox). R3 copy only; ignored under
         * the R4 copy rules (the folder becomes a list under its own normalized title).
         */
        public ImportChoices withFolderTitle(String incarnation, String title) {
            HashMap<String, String> nextTitles = new HashMap<>(titles);
            nextTitles.put(incarnation, title);
            HashSet<String> nextInbox = new HashSet<>(cloudInbox);
            nextInbox.remove(incarnation);
            return new ImportChoices(nextTitles, nextInbox);
        }

        /**
         * The user chose to put this folder's tasks into the virtual Cloud Inbox instead of a real list. R3 copy only;
         * ignored under the R4 copy rules (the folder becomes a list under its own normalized title).
         */
        public ImportChoices withFolderInCloudInbox(String incarnation) {
            HashMap<String, String> nextTitles = new HashMap<>(titles);
            nextTitles.remove(incarnation);
            HashSet<String> nextInbox = new HashSet<>(cloudInbox);
            nextInbox.add(incarnation);
            return new ImportChoices(nextTitles, nextInbox);
        }
    }

    public static final class ImportResult {
        public final int queuedLists, queuedTasks, leftLocal;

        ImportResult(int queuedLists, int queuedTasks, int leftLocal) {
            this.queuedLists = queuedLists;
            this.queuedTasks = queuedTasks;
            this.leftLocal = leftLocal;
        }
    }

    /** Content-free mappings (recorded locally first) plus the create operations, list creates before tasks. */
    static final class Plan {
        final List<ZenlyTaskImport.Mapping> mappings;
        final List<String> mutationIds, chains, listKeys;
        final List<TasksWire.Operation> operations;
        final int lists, tasks, leftLocal;

        Plan(List<ZenlyTaskImport.Mapping> mappings, List<String> mutationIds, List<TasksWire.Operation> operations,
             List<String> chains, List<String> listKeys, int lists, int tasks, int leftLocal) {
            this.mappings = Collections.unmodifiableList(new ArrayList<>(mappings));
            this.mutationIds = Collections.unmodifiableList(new ArrayList<>(mutationIds));
            this.operations = Collections.unmodifiableList(new ArrayList<>(operations));
            this.chains = Collections.unmodifiableList(new ArrayList<>(chains));
            this.listKeys = Collections.unmodifiableList(listKeys);
            this.lists = lists;
            this.tasks = tasks;
            this.leftLocal = leftLocal;
        }
    }

    private TasksCloudImport() { }

    /** Local deadline (ms, &gt; 0) to cloud due_at seconds: floor division, explicit range, null stays null. */
    public static Long dueSecondsFromLocalMillis(Long millis) {
        if (millis == null) return null;
        if (millis <= 0) throw new TasksWire.Invalid("due_at");
        long seconds = millis / 1000; // positive: truncation == floor
        if (seconds > TasksWire.MAX_DEADLINE) throw new TasksWire.Invalid("due_at");
        return seconds;
    }

    /** Historical local instant (ms) as a cloud Instant, refusing dates the server would reject. */
    static String instantFromLocalMillis(long millis, long nowMillis, String field) {
        if (millis < 0 || millis > nowMillis + FUTURE_SLACK_MS) throw new TasksWire.Invalid(field);
        return TasksWire.instant(millis / 1000);
    }

    static Map<String, ZenlyTaskImport.Mapping> mappings(ZenlyTaskImport.Snapshot snapshot, String entity) {
        HashMap<String, ZenlyTaskImport.Mapping> result = new HashMap<>();
        for (ZenlyTaskImport.Mapping mapping : snapshot.mappings) if (mapping.entity.equals(entity)) result.put(mapping.localKey, mapping);
        return result;
    }

    /** Local folder rows keep created_at=0 as "unknown" (the built-in Inbox); unknown sends null, never 1970. */
    private static TasksWire.Operation listCreate(String listId, String title, int sortOrder, ZenlyTaskModel.Folder folder, long now) {
        String createdAt = folder.createdAt <= 0 ? null : instantFromLocalMillis(folder.createdAt, now, "created_at");
        return TasksWire.Operation.listCreate(listId, title, sortOrder, createdAt);
    }

    private static TasksWire.Operation taskCreate(String taskId, String listId, int sortOrder, ZenlyTaskModel.Task task, long now) {
        TasksWire.Content content = new TasksWire.Content(listId, task.title, task.description,
                dueSecondsFromLocalMillis(task.dueAt), task.priority, sortOrder, null);
        String completedAt = task.completed && task.completedAt != null ? instantFromLocalMillis(task.completedAt, now, "completed_at") : null;
        return TasksWire.Operation.taskCreate(taskId, content, instantFromLocalMillis(task.createdAt, now, "created_at"),
                task.completed, completedAt);
    }

    private static RowStatus mappedStatus(String kind, ZenlyTaskImport.Mapping mapping, Set<String> knownCloudIds, Set<String> outbox) {
        return mappedStatus(kind, mapping, knownCloudIds, outbox, Collections.<String>emptySet(), null, null);
    }

    /**
     * Where a mapped row stands in this account's cloud. An acknowledged create (or one whose acknowledgement still
     * waits in import_ack_pending) absent from the shadow is REMOVED_IN_CLOUD; with {@code resyncGeneration} (R4) and an
     * older verification it is first RESENT once with its reserved id (its id lands in {@code reverify}: a late erasure
     * replays the old receipt, a fresh execution re-creates it). Any other absent row resends its reserved identity.
     */
    static RowStatus mappedStatus(String kind, ZenlyTaskImport.Mapping mapping, Set<String> knownCloudIds, Set<String> outbox,
                                  Set<String> pendingAcks, Long resyncGeneration, Set<String> reverify) {
        if (knownCloudIds.contains(kind + ":" + mapping.cloudId)) return RowStatus.ALREADY_IMPORTED;
        if (outbox.contains(mapping.mutationId)) return RowStatus.PENDING;
        if (mapping.acknowledged || pendingAcks.contains(mapping.mutationId)) {
            if (resyncGeneration != null && mapping.verifiedGeneration < resyncGeneration) {
                if (reverify != null) reverify.add(mapping.mutationId);
                return RowStatus.RESEND;
            }
            return RowStatus.REMOVED_IN_CLOUD;
        }
        return RowStatus.RESEND;
    }

    /**
     * A folder whose plan makes no list for new tasks, until a choice (R3 INVALID) or ever: plan()/planR4() leave its
     * unmapped tasks on the device, as iOS acceptsChildren does for them. A mapped task keeps its own status.
     */
    private static boolean blocksNewTasks(RowStatus folder) {
        return folder == RowStatus.INVALID || folder == RowStatus.REMOVED_IN_CLOUD;
    }

    /** The R3 preview (exactly what an R3 copy shows and sends). */
    static ImportPreview preview(long telegramUserId, String principal, ZenlyTaskImport.Snapshot snapshot, Set<String> knownCloudIds,
                                 Set<String> outboxMutationIds, long nowMillis) {
        return preview(telegramUserId, principal, snapshot, knownCloudIds, outboxMutationIds, nowMillis, null);
    }

    /** {@code rules} null: R3; else the R4 copy ({@link #previewR4}). */
    static ImportPreview preview(long telegramUserId, String principal, ZenlyTaskImport.Snapshot snapshot, Set<String> knownCloudIds,
                                 Set<String> outboxMutationIds, long nowMillis, Rules rules) {
        if (rules != null) return previewR4(telegramUserId, principal, snapshot, knownCloudIds, outboxMutationIds, nowMillis, rules);
        Map<String, ZenlyTaskImport.Mapping> folderMappings = mappings(snapshot, ZenlyTaskImport.Mapping.FOLDER);
        Map<String, ZenlyTaskImport.Mapping> taskMappings = mappings(snapshot, ZenlyTaskImport.Mapping.TASK);
        ArrayList<FolderRow> folders = new ArrayList<>();
        HashMap<String, RowStatus> folderStatus = new HashMap<>();
        for (int index = 0; index < snapshot.folders.size(); index++) {
            ZenlyTaskModel.Folder folder = snapshot.folders.get(index);
            String incarnation = snapshot.incarnations.get(folder.id);
            ZenlyTaskImport.Mapping mapping = folderMappings.get(incarnation);
            RowStatus status = mapping == null ? RowStatus.NEW : mappedStatus("list", mapping, knownCloudIds, outboxMutationIds);
            String invalid = null;
            if (status == RowStatus.NEW || status == RowStatus.RESEND) {
                try {
                    listCreate(mapping == null ? TasksWire.newId() : mapping.cloudId, folder.title,
                            mapping == null ? index : mapping.sortOrder, folder, nowMillis);
                } catch (TasksWire.Invalid e) {
                    status = RowStatus.INVALID;
                    invalid = e.field;
                }
            }
            folderStatus.put(folder.id, status);
            folders.add(new FolderRow(folder.id, incarnation, folder.title, ZenlyTaskModel.INBOX_ID.equals(folder.id),
                    folder.createdAt, status, invalid));
        }
        ArrayList<TaskRow> tasks = new ArrayList<>();
        HashMap<String, Integer> positions = new HashMap<>();
        for (ZenlyTaskModel.Task task : snapshot.tasks) {
            int position = next(positions, task.folderId);
            ZenlyTaskImport.Mapping mapping = taskMappings.get(task.id);
            RowStatus status = mapping == null ? RowStatus.NEW : mappedStatus("task", mapping, knownCloudIds, outboxMutationIds);
            String invalid = null;
            if (status == RowStatus.NEW || status == RowStatus.RESEND) {
                try {
                    taskCreate(mapping == null ? TasksWire.newId() : mapping.cloudId,
                            mapping == null ? TasksWire.newId() : mapping.parentCloudId,
                            mapping == null ? position : mapping.sortOrder, task, nowMillis);
                    if (mapping == null && blocksNewTasks(folderStatus.get(task.folderId))) status = RowStatus.BLOCKED_BY_FOLDER;
                } catch (TasksWire.Invalid e) {
                    status = RowStatus.INVALID;
                    invalid = e.field;
                }
            }
            tasks.add(new TaskRow(task.id, task.folderId, snapshot.incarnations.get(task.folderId), task.title, status, invalid));
        }
        return new ImportPreview(telegramUserId, principal, folders, tasks, snapshot, nowMillis, null);
    }

    /**
     * The R4 copy: the unmapped built-in inbox is CLOUD_INBOX (no list.create; its tasks go to the Cloud Inbox), every
     * other folder a list, every row normalized instead of refused (INVALID only if the wire still refuses it); new rows
     * take index × 1024 (the Cloud Inbox takes no list index), mapped rows keep their identities. Statuses of mapped rows
     * as R3; the migration's re-check owns the resend-once of acknowledged rows.
     */
    private static ImportPreview previewR4(long telegramUserId, String principal, ZenlyTaskImport.Snapshot snapshot, Set<String> knownCloudIds,
                                           Set<String> outboxMutationIds, long nowMillis, Rules rules) {
        Map<String, ZenlyTaskImport.Mapping> folderMappings = mappings(snapshot, ZenlyTaskImport.Mapping.FOLDER);
        Map<String, ZenlyTaskImport.Mapping> taskMappings = mappings(snapshot, ZenlyTaskImport.Mapping.TASK);
        ArrayList<FolderRow> folders = new ArrayList<>();
        HashMap<String, RowStatus> folderStatus = new HashMap<>();
        int listIndex = 0;
        for (ZenlyTaskModel.Folder folder : snapshot.folders) {
            String incarnation = snapshot.incarnations.get(folder.id);
            ZenlyTaskImport.Mapping mapping = folderMappings.get(incarnation);
            RowStatus status;
            String invalid = null;
            if (mapping == null && TasksMigrationNormalizer.isCloudInbox(folder.id)) {
                status = RowStatus.CLOUD_INBOX;
            } else {
                int index = listIndex++;
                status = mapping == null ? RowStatus.NEW : mappedStatus("list", mapping, knownCloudIds, outboxMutationIds);
                if (status == RowStatus.NEW || status == RowStatus.RESEND) {
                    try {
                        rules.listCreate(mapping == null ? TasksWire.newId() : mapping.cloudId, folder.title, folder, index, mapping);
                    } catch (TasksWire.Invalid e) {
                        status = RowStatus.INVALID;
                        invalid = e.field;
                    }
                }
            }
            folderStatus.put(folder.id, status);
            folders.add(new FolderRow(folder.id, incarnation, folder.title, ZenlyTaskModel.INBOX_ID.equals(folder.id),
                    folder.createdAt, status, invalid));
        }
        ArrayList<TaskRow> tasks = new ArrayList<>();
        HashMap<String, Integer> positions = new HashMap<>();
        for (ZenlyTaskModel.Task task : snapshot.tasks) {
            int position = next(positions, task.folderId);
            ZenlyTaskImport.Mapping mapping = taskMappings.get(task.id);
            RowStatus status = mapping == null ? RowStatus.NEW : mappedStatus("task", mapping, knownCloudIds, outboxMutationIds);
            String invalid = null;
            if (status == RowStatus.NEW || status == RowStatus.RESEND) {
                try {
                    rules.taskCreate(mapping == null ? TasksWire.newId() : mapping.cloudId, mapping == null ? TasksWire.newId() : mapping.parentCloudId,
                            task, position, mapping);
                    if (mapping == null && blocksNewTasks(folderStatus.get(task.folderId))) status = RowStatus.BLOCKED_BY_FOLDER;
                } catch (TasksWire.Invalid e) {
                    status = RowStatus.INVALID;
                    invalid = e.field;
                }
            }
            tasks.add(new TaskRow(task.id, task.folderId, snapshot.incarnations.get(task.folderId), task.title, status, invalid));
        }
        return new ImportPreview(telegramUserId, principal, folders, tasks, snapshot, nowMillis, rules);
    }

    static int next(Map<String, Integer> positions, String key) {
        Integer current = positions.get(key);
        int value = current == null ? 0 : current;
        positions.put(key, value + 1);
        return value;
    }

    /** Parent target of a folder after explicit choices: a cloud list id, Cloud Inbox, or blocked. */
    static final class Target {
        static final Target BLOCKED = new Target(null, true);
        static final Target CLOUD_INBOX = new Target(null, false);
        final String listId;
        final boolean blocked;

        Target(String listId, boolean blocked) {
            this.listId = listId;
            this.blocked = blocked;
        }
    }

    /** Stale choices are refused; the plan follows the rules of its preview (R3, or the R4 copy). */
    static Plan plan(ImportPreview preview, ImportChoices choices) {
        ZenlyTaskImport.Snapshot snapshot = preview.snapshot;
        Map<String, ZenlyTaskImport.Mapping> folderMappings = mappings(snapshot, ZenlyTaskImport.Mapping.FOLDER);
        Map<String, ZenlyTaskImport.Mapping> taskMappings = mappings(snapshot, ZenlyTaskImport.Mapping.TASK);
        HashSet<String> incarnations = new HashSet<>(snapshot.incarnations.values());
        for (String chosen : choices.titles.keySet()) if (!incarnations.contains(chosen)) throw new TasksCloudError(TasksCloudError.Code.IMPORT_STALE);
        for (String chosen : choices.cloudInbox) if (!incarnations.contains(chosen)) throw new TasksCloudError(TasksCloudError.Code.IMPORT_STALE);
        if (preview.rules != null) return planR4(preview, folderMappings, taskMappings);

        ArrayList<ZenlyTaskImport.Mapping> mappings = new ArrayList<>();
        ArrayList<String> mutationIds = new ArrayList<>(), chains = new ArrayList<>(), listKeys = new ArrayList<>();
        ArrayList<TasksWire.Operation> operations = new ArrayList<>();
        LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        int lists = 0, tasks = 0, leftLocal = 0;
        for (int index = 0; index < preview.folders.size(); index++) {
            FolderRow row = preview.folders.get(index);
            ZenlyTaskModel.Folder folder = snapshot.folders.get(index);
            ZenlyTaskImport.Mapping mapping = folderMappings.get(row.incarnation);
            if (mapping == null && choices.cloudInbox.contains(row.incarnation)) {
                targets.put(folder.id, Target.CLOUD_INBOX);
                continue;
            }
            if (row.status == RowStatus.ALREADY_IMPORTED || row.status == RowStatus.PENDING) {
                targets.put(folder.id, new Target(mapping.cloudId, false));
                continue;
            }
            if (row.status == RowStatus.REMOVED_IN_CLOUD) {
                // Removed in the cloud: never re-created; its unmapped tasks stay local.
                targets.put(folder.id, Target.BLOCKED);
                continue;
            }
            String title = choices.titles.containsKey(row.incarnation) ? choices.titles.get(row.incarnation) : folder.title;
            if (row.status == RowStatus.INVALID && !choices.titles.containsKey(row.incarnation)) {
                targets.put(folder.id, Target.BLOCKED);
                continue;
            }
            String listId = mapping == null ? TasksWire.newId() : mapping.cloudId;
            String mutationId = mapping == null ? TasksWire.newId() : mapping.mutationId;
            int sortOrder = mapping == null ? index : mapping.sortOrder;
            TasksWire.Operation operation;
            try {
                operation = listCreate(listId, title, sortOrder, folder, preview.nowMillis);
            } catch (TasksWire.Invalid e) {
                throw new TasksCloudError(TasksCloudError.Code.INVALID, e.field);
            }
            if (mapping == null) {
                mappings.add(new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.FOLDER, row.incarnation, listId, mutationId, sortOrder, null));
            }
            mutationIds.add(mutationId);
            operations.add(operation);
            chains.add(TasksCloudLedger.listChain(listId));
            listKeys.add(null);
            targets.put(folder.id, new Target(listId, false));
            lists++;
        }
        HashMap<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < preview.tasks.size(); index++) {
            TaskRow row = preview.tasks.get(index);
            ZenlyTaskModel.Task task = snapshot.tasks.get(index);
            int position = next(positions, task.folderId);
            ZenlyTaskImport.Mapping mapping = taskMappings.get(task.id);
            if (row.status == RowStatus.ALREADY_IMPORTED || row.status == RowStatus.PENDING || row.status == RowStatus.REMOVED_IN_CLOUD) continue;
            if (row.status == RowStatus.INVALID) {
                leftLocal++;
                continue;
            }
            String parent;
            if (mapping != null) {
                parent = mapping.parentCloudId; // never re-resolved after consent
            } else {
                Target target = targets.get(task.folderId);
                if (target == null || target.blocked) {
                    leftLocal++;
                    continue;
                }
                parent = target.listId;
            }
            String taskId = mapping == null ? TasksWire.newId() : mapping.cloudId;
            String mutationId = mapping == null ? TasksWire.newId() : mapping.mutationId;
            int sortOrder = mapping == null ? position : mapping.sortOrder;
            TasksWire.Operation operation;
            try {
                operation = taskCreate(taskId, parent, sortOrder, task, preview.nowMillis);
            } catch (TasksWire.Invalid e) {
                leftLocal++;
                continue;
            }
            if (mapping == null) {
                mappings.add(new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.TASK, task.id, taskId, mutationId, sortOrder, parent));
            }
            mutationIds.add(mutationId);
            operations.add(operation);
            chains.add(TasksCloudLedger.taskChain(taskId));
            listKeys.add(parent == null ? null : TasksCloudLedger.listChain(parent));
            tasks++;
        }
        return new Plan(mappings, mutationIds, operations, chains, listKeys, lists, tasks, leftLocal);
    }

    /**
     * The R4 copy's plan: the creates TasksAutoMigration.migrationPlan builds for the same rows (normalizer, locale,
     * time, identities, sort orders), so its re-check rebuilds them byte for byte. It takes no choice (design 30 §6.5,
     * as iOS; plan() still refuses a stale one): the inbox is the Cloud Inbox and every other folder a list under its
     * own normalized title, the one the re-check rebuilds from the device folder (the content-free mapping keeps no
     * title), and its folder mapping keeps the device's unmapped-row count from offering the folder again. A folder
     * removed in the cloud, or one whose title the normalizer cannot repair, stays local with its new tasks, as the
     * migration keeps it.
     */
    private static Plan planR4(ImportPreview preview, Map<String, ZenlyTaskImport.Mapping> folderMappings,
                               Map<String, ZenlyTaskImport.Mapping> taskMappings) {
        ZenlyTaskImport.Snapshot snapshot = preview.snapshot;
        Rules rules = preview.rules;
        ArrayList<ZenlyTaskImport.Mapping> mappings = new ArrayList<>();
        ArrayList<String> mutationIds = new ArrayList<>(), chains = new ArrayList<>(), listKeys = new ArrayList<>();
        ArrayList<TasksWire.Operation> operations = new ArrayList<>();
        HashMap<String, Target> targets = new HashMap<>();
        int lists = 0, tasks = 0, leftLocal = 0, listIndex = 0;
        for (int index = 0; index < preview.folders.size(); index++) {
            FolderRow row = preview.folders.get(index);
            ZenlyTaskModel.Folder folder = snapshot.folders.get(index);
            ZenlyTaskImport.Mapping mapping = folderMappings.get(row.incarnation);
            if (row.status == RowStatus.CLOUD_INBOX) {
                targets.put(folder.id, Target.CLOUD_INBOX);
                continue;
            }
            int order = listIndex++;
            if (row.status == RowStatus.ALREADY_IMPORTED || row.status == RowStatus.PENDING) {
                targets.put(folder.id, new Target(mapping.cloudId, false));
                continue;
            }
            if (row.status == RowStatus.REMOVED_IN_CLOUD || row.status == RowStatus.INVALID) {
                // Removed in the cloud: never re-created; refused even normalized (ill-formed UTF-16): stays local, as the
                // migration keeps it. Their unmapped tasks too.
                targets.put(folder.id, Target.BLOCKED);
                continue;
            }
            String title = folder.title;
            String listId = mapping == null ? TasksWire.newId() : mapping.cloudId;
            String mutationId = mapping == null ? TasksWire.newId() : mapping.mutationId;
            TasksWire.Operation.ListCreate operation;
            try {
                operation = rules.listCreate(listId, title, folder, order, mapping);
            } catch (TasksWire.Invalid e) {
                throw new TasksCloudError(TasksCloudError.Code.INVALID, e.field);
            }
            if (mapping == null) {
                mappings.add(new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.FOLDER, row.incarnation, listId, mutationId, operation.sortOrder, null));
            }
            mutationIds.add(mutationId);
            operations.add(operation);
            chains.add(TasksCloudLedger.listChain(listId));
            listKeys.add(null);
            targets.put(folder.id, new Target(listId, false));
            lists++;
        }
        HashMap<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < preview.tasks.size(); index++) {
            TaskRow row = preview.tasks.get(index);
            ZenlyTaskModel.Task task = snapshot.tasks.get(index);
            int position = next(positions, task.folderId);
            ZenlyTaskImport.Mapping mapping = taskMappings.get(task.id);
            if (row.status == RowStatus.ALREADY_IMPORTED || row.status == RowStatus.PENDING || row.status == RowStatus.REMOVED_IN_CLOUD) continue;
            if (row.status == RowStatus.INVALID) {
                leftLocal++;
                continue;
            }
            String parent;
            if (mapping != null) {
                parent = mapping.parentCloudId; // never re-resolved after consent
            } else {
                Target target = targets.get(task.folderId);
                if (target == null || target.blocked) {
                    leftLocal++;
                    continue;
                }
                parent = target.listId;
            }
            String taskId = mapping == null ? TasksWire.newId() : mapping.cloudId;
            String mutationId = mapping == null ? TasksWire.newId() : mapping.mutationId;
            TasksWire.Operation.TaskCreate operation;
            try {
                operation = rules.taskCreate(taskId, parent, task, position, mapping);
            } catch (TasksWire.Invalid e) {
                leftLocal++;
                continue;
            }
            if (mapping == null) {
                mappings.add(new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.TASK, task.id, taskId, mutationId, operation.content.sortOrder, parent));
            }
            mutationIds.add(mutationId);
            operations.add(operation);
            chains.add(TasksCloudLedger.taskChain(taskId));
            listKeys.add(parent == null ? null : TasksCloudLedger.listChain(parent));
            tasks++;
        }
        return new Plan(mappings, mutationIds, operations, chains, listKeys, lists, tasks, leftLocal);
    }
}
