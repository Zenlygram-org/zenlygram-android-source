package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The automatic first migration of one account's device rows into its own cloud (design 30 §6.5), the Android
 * counterpart of iOS SGTasksMigration without its device-owner marker (each per-account database migrates into its own
 * account). Pure coordinator step, host-tested (zenlygram-tests/tasks/migration): the owner (ZenlyTasksSyncOwner) runs
 * it on its own worker thread for an ALLOWED account, after a cycle with a complete snapshot, one step in flight per
 * account, with blocking adapters over the local store ({@link Local}) and the cloud repository ({@link Cloud}).
 *
 * <p>The record: none → an import mapping of any scope means the rows were copied explicitly: {@code skipped
 * (existing_mappings)}; else {@code claimed} under the current import scope (analytics {@code started}). {@code released}
 * (withdrawal, erasure) → claimed again. {@code claimed|enqueued|completed} under another scope → {@code skipped
 * (scope_changed)}, mappings kept. Then {@code claimed}: the local step (no network) with the account's write fence at
 * MIGRATING and the consent epoch captured first — one snapshot, its R4 plan, mappings recorded durably, creates
 * enqueued with origin {@code migration} and that epoch (refused after a withdrawal or an epoch change, so nothing is
 * queued), the record {@code enqueued} with its counts; a failure leaves {@code claimed} and the board writable.
 * {@code enqueued} (or {@code completed} verified at an older resync generation): mapped rows owed again (lost with a
 * logout, or resent once after a late erasure) are re-enqueued under the fence; once every mapped row is in the cloud or
 * acknowledged-and-absent after its resend-once check: {@code completed} at the current generation (analytics
 * {@code completed} on the first completion). A completed record whose rows are not confirmed after a resync returns to
 * {@code enqueued}, so the board does not retire on the first absence.
 *
 * <p>A cloud store the step has not checked yet (a new database: its first open, or recreated after a logout or a launch
 * cleanup wiped the old one; its resync generation starts at 0 again; or one rebound to another principal,
 * TasksCloudLedger.bindPrincipal) first voids every verification recorded against an earlier store: the record and the
 * attempt rows go to generation -1, so a completed copy is verified against the new
 * store and an acknowledged row absent there is resent once. Times are the server's (the cloud context's estimate, not
 * the device clock): the record's own, and the copy's created_at/completed_at, checked against the time of its first
 * enqueue (kept in the record), so a resend rebuilds identical bytes.
 */
public final class TasksAutoMigration {
    /** Blocking access to the account's local store (store queue); failures throw {@link Failure}. */
    public interface Local {
        ZenlyTaskImport.MigrationRecord record();

        /** Compare-and-set; false = changed meanwhile (that state stands). */
        boolean replaceRecord(ZenlyTaskImport.MigrationRecord expected, ZenlyTaskImport.MigrationRecord next);

        boolean hasAnyImportMapping();

        ZenlyTaskImport.Snapshot importSnapshot(String scope);

        void recordImportMappings(String scope, List<ZenlyTaskImport.Mapping> mappings);

        void markImportReverified(List<String> mutationIds, long generation);

        /**
         * A new cloud store: every verification recorded against an earlier one is void. The record's and every attempt
         * row's verified generation become -1 (older than any generation of a store); state, mappings and
         * acknowledgements stay.
         */
        void invalidateVerification();
    }

    /**
     * Blocking access to the account's cloud repository (its executor). A call that has started on the executor is
     * waited for to its end; only one still queued at the deadline is dropped ({@link #awaitTurn}).
     */
    public interface Cloud {
        /**
         * null = the step cannot run yet: not linked, snapshot incomplete, a resnapshot running or pending (a 409 or a
         * re-grant resume dropped the cursor; until the next snapshot lands the shadow may still list erased rows), closed.
         */
        CloudContext context();

        long consentEpoch();

        /** The migration-origin creates with the captured epoch (TasksCloudEngine.enqueueImport); throws TasksCloudError. */
        int enqueue(String principal, TasksCloudImport.Plan plan, long expectedConsentEpoch);

        /** The step has checked this store ({@link CloudContext#storeChecked}; a marker that dies with the database). */
        void markChecked();
    }

    /** Before the snapshot / after the enqueue AND the record update (also after a failure). */
    public interface Fence {
        void raise(int account);

        void lower(int account);
    }

    /** The cloud's inputs of one step, read in one executor turn. */
    public static final class CloudContext {
        public final String principal, scope;
        public final long resyncGeneration;
        /**
         * The server's time estimated at this read (ms; the Tasks answers that completed the snapshot set it): the
         * step's time. 0 = unknown: the caller's clock.
         */
        public final long serverNowMillis;
        /**
         * False until the step has checked this store: a new cloud database (its first open, or recreated after a
         * logout or a launch cleanup) or one rebound to another principal (TasksCloudLedger.bindPrincipal), against
         * which nothing recorded earlier was verified.
         */
        public final boolean storeChecked;
        final Set<String> knownCloudIds, outboxMutationIds, pendingImportAcks;

        public CloudContext(String principal, String scope, long resyncGeneration, Set<String> knownCloudIds, Set<String> outboxMutationIds,
                            Set<String> pendingImportAcks, long serverNowMillis, boolean storeChecked) {
            this.principal = principal;
            this.scope = scope;
            this.resyncGeneration = resyncGeneration;
            this.knownCloudIds = Collections.unmodifiableSet(new HashSet<>(knownCloudIds));
            this.outboxMutationIds = Collections.unmodifiableSet(new HashSet<>(outboxMutationIds));
            this.pendingImportAcks = Collections.unmodifiableSet(new HashSet<>(pendingImportAcks));
            this.serverNowMillis = serverNowMillis;
            this.storeChecked = storeChecked;
        }
    }

    /** A local storage failure; {@code code} as error.occurred(tasks_migration) reports it ({@code sqlite_<n>}, …). */
    public static final class Failure extends RuntimeException {
        public final String code;

        public Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    private TasksAutoMigration() { }

    /**
     * One step for {@code account}; returns the record afterwards (null = nothing to do yet). {@code locale}: the record
     * locale for a fresh claim (uz, ru, en); {@code nowMillis}: the caller's clock, used only when the cloud context has
     * no server estimate (the step's time is the server's, design 30 §7.1). A storage or cloud failure emits
     * {@code migration(failed)} + {@code error(tasks_migration)} and is rethrown; not linked, an incomplete snapshot and a
     * withdrawal racing the step are not failures.
     */
    public static ZenlyTaskImport.MigrationRecord step(int account, Local local, Cloud cloud, Fence fence, String locale, long nowMillis) {
        try {
            return run(account, local, cloud, fence, locale, nowMillis);
        } catch (TasksCloudError e) {
            switch (e.code) {
                case NOT_LINKED:
                case NOT_READY:
                case CLOSED:
                case CONSENT_WITHDRAWN:
                case PRINCIPAL_CHANGED:
                    break;
                default:
                    failed(account, e.code.name().toLowerCase(java.util.Locale.ROOT));
            }
            throw e;
        } catch (Failure e) {
            failed(account, e.code);
            throw e;
        } catch (RuntimeException e) {
            failed(account, "unknown");
            throw e;
        }
    }

    private static void failed(int account, String code) {
        TasksAnalyticsSink sink = TasksAnalyticsSink.Holder.get();
        sink.migration(account, TasksAnalyticsSink.MIGRATION_FAILED, null, null, null, null, code);
        sink.error(account, TasksAnalyticsSink.DOMAIN_MIGRATION, code);
    }

    private static ZenlyTaskImport.MigrationRecord run(int account, Local local, Cloud cloud, Fence fence, String locale, long nowMillis) {
        CloudContext context = cloud.context();
        if (context == null) return local.record();
        if (!context.storeChecked) {
            // A new store reads generation 0 like the wiped one: what was verified against that one is void, so a
            // completed copy is checked again and an acknowledged row absent here is resent once (§3.8).
            local.invalidateVerification();
            cloud.markChecked();
        }
        long stepMillis = context.serverNowMillis > 0 ? context.serverNowMillis : nowMillis;
        long now = stepMillis / 1000;
        ZenlyTaskImport.MigrationRecord record = local.record();
        if (record == null) {
            ZenlyTaskImport.MigrationRecord next = local.hasAnyImportMapping()
                    ? ZenlyTaskImport.MigrationRecord.claimed(context.scope, locale, now)
                            .withState(ZenlyTaskImport.MigrationRecord.SKIPPED, ZenlyTaskImport.MigrationRecord.REASON_EXISTING_MAPPINGS)
                    : ZenlyTaskImport.MigrationRecord.claimed(context.scope, locale, now);
            record = replace(local, null, next);
            if (record != next) return record;
            if (ZenlyTaskImport.MigrationRecord.CLAIMED.equals(next.state)) started(account);
        } else if (ZenlyTaskImport.MigrationRecord.SKIPPED.equals(record.state)) {
            return record;
        } else if (ZenlyTaskImport.MigrationRecord.RELEASED.equals(record.state)) {
            // The withdrawal or erasure voided the mappings: claim afresh with the record's locale.
            ZenlyTaskImport.MigrationRecord next = ZenlyTaskImport.MigrationRecord.claimed(context.scope, record.locale, now);
            ZenlyTaskImport.MigrationRecord stored = replace(local, record, next);
            if (stored != next) return stored;
            record = next;
            started(account);
        } else if (record.scope != null && !record.scope.equals(context.scope)) {
            // The server account changed (rebind/merge): the copy belongs to the old cloud; mappings kept.
            return replace(local, record, record.withState(ZenlyTaskImport.MigrationRecord.SKIPPED, ZenlyTaskImport.MigrationRecord.REASON_SCOPE_CHANGED));
        } else if (record.scope == null) {
            ZenlyTaskImport.MigrationRecord scoped = record.withScope(context.scope);
            ZenlyTaskImport.MigrationRecord stored = replace(local, record, scoped);
            if (stored != scoped) return stored;
            record = scoped;
        }
        if (ZenlyTaskImport.MigrationRecord.CLAIMED.equals(record.state)) return copy(account, local, cloud, fence, record, stepMillis);
        if (ZenlyTaskImport.MigrationRecord.ENQUEUED.equals(record.state)
                || ZenlyTaskImport.MigrationRecord.COMPLETED.equals(record.state) && record.verifiedGeneration < context.resyncGeneration) {
            return verify(account, local, cloud, fence, record, context, stepMillis);
        }
        return record;
    }

    /**
     * The time a copy's created_at/completed_at are checked against (design 30 §7.1): its first enqueue's, kept in the
     * record, so a resend rebuilds identical bytes (§6.5; the server hashes both fields) and a time kept then is not in
     * the server's future later; before the first enqueue, the step's (server) time.
     */
    static long normalizedAt(ZenlyTaskImport.MigrationRecord record, long stepMillis) {
        return record.enqueuedAt != null ? record.enqueuedAt * 1000L : stepMillis;
    }

    /**
     * One turn of the cloud repository's executor for the blocking {@link Cloud} calls. A turn still queued after
     * {@code waitMs} is dropped and never runs (NOT_READY: nothing happened, the step reruns with the next trigger); a
     * turn that has started is waited for to its end, because what it writes (the migration enqueue) lands whatever the
     * step does: the step never ends, nor lowers its fence, on an enqueue whose outcome it does not know. Engine turns
     * are finite (bounded store waits, asynchronous network). A failure inside the turn arrives as its
     * ExecutionException.
     */
    public static <T> T awaitTurn(ExecutorService executor, final Callable<T> work, long waitMs) throws InterruptedException, ExecutionException {
        final AtomicBoolean taken = new AtomicBoolean();
        Future<T> future = executor.submit(() -> taken.compareAndSet(false, true) ? work.call() : null);
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (taken.compareAndSet(false, true)) {
                future.cancel(false); // still queued: it never runs
                throw new TasksCloudError(TasksCloudError.Code.NOT_READY);
            }
            return future.get();
        }
    }

    /** The CAS result: {@code next} when written, else the record as it stands now. */
    private static ZenlyTaskImport.MigrationRecord replace(Local local, ZenlyTaskImport.MigrationRecord expected, ZenlyTaskImport.MigrationRecord next) {
        return local.replaceRecord(expected, next) ? next : local.record();
    }

    private static void started(int account) {
        TasksAnalyticsSink.Holder.get().migration(account, TasksAnalyticsSink.MIGRATION_STARTED, null, null, null, null, null);
    }

    /**
     * One fenced local step: epoch, snapshot, plan, reverify, mappings, enqueue. Returns the plan it enqueued.
     * {@code normalizedAtMillis}: {@link #normalizedAt}.
     */
    private static MigrationPlan enqueueStep(Local local, Cloud cloud, ZenlyTaskImport.MigrationRecord record,
                                                             long normalizedAtMillis, boolean mappedOnly) {
        long epoch = cloud.consentEpoch();
        CloudContext context = cloud.context();
        if (context == null) throw new TasksCloudError(TasksCloudError.Code.NOT_READY);
        ZenlyTaskImport.Snapshot snapshot = local.importSnapshot(context.scope);
        MigrationPlan plan = migrationPlan(snapshot, new TasksMigrationNormalizer(record.locale),
                context.knownCloudIds, context.outboxMutationIds, context.pendingImportAcks, context.resyncGeneration, normalizedAtMillis, mappedOnly);
        if (!plan.reverify.isEmpty()) local.markImportReverified(plan.reverify, context.resyncGeneration);
        if (!plan.plan.mappings.isEmpty()) local.recordImportMappings(context.scope, plan.plan.mappings);
        if (!plan.plan.operations.isEmpty()) cloud.enqueue(context.principal, plan.plan, epoch);
        return plan;
    }

    private static ZenlyTaskImport.MigrationRecord copy(int account, Local local, Cloud cloud, Fence fence,
                                                        ZenlyTaskImport.MigrationRecord record, long stepMillis) {
        long at = normalizedAt(record, stepMillis);
        fence.raise(account);
        try {
            MigrationPlan plan = enqueueStep(local, cloud, record, at, false);
            return replace(local, record, record.enqueued(at / 1000, plan.lists, plan.tasks, plan.keptLocal));
        } finally {
            fence.lower(account);
        }
    }

    private static ZenlyTaskImport.MigrationRecord verify(int account, Local local, Cloud cloud, Fence fence,
                                                          ZenlyTaskImport.MigrationRecord record, CloudContext context, long stepMillis) {
        long normalizedMillis = normalizedAt(record, stepMillis);
        ZenlyTaskImport.Snapshot snapshot = local.importSnapshot(context.scope);
        MigrationPlan preview = migrationPlan(snapshot, new TasksMigrationNormalizer(record.locale),
                context.knownCloudIds, context.outboxMutationIds, context.pendingImportAcks, context.resyncGeneration, normalizedMillis, true);
        if (preview.mappedEnqueueable > 0) {
            fence.raise(account);
            try {
                enqueueStep(local, cloud, record, normalizedMillis, true);
                if (ZenlyTaskImport.MigrationRecord.ENQUEUED.equals(record.state)) return record;
                Long at = record.enqueuedAt == null ? Long.valueOf(normalizedMillis / 1000) : record.enqueuedAt;
                return replace(local, record, record.enqueued(at, record.lists, record.tasks, record.keptLocal));
            } finally {
                fence.lower(account);
            }
        }
        if (!preview.confirmed()) {
            // Still in flight: a re-checked completed copy shows its archive again until its rows are confirmed.
            if (!ZenlyTaskImport.MigrationRecord.COMPLETED.equals(record.state)) return record;
            return replace(local, record, record.enqueued(record.enqueuedAt, record.lists, record.tasks, record.keptLocal));
        }
        boolean first = record.completedAt == null;
        long now = stepMillis / 1000;
        ZenlyTaskImport.MigrationRecord completed = record.completed(first ? Long.valueOf(now) : record.completedAt, context.resyncGeneration);
        ZenlyTaskImport.MigrationRecord stored = replace(local, record, completed);
        if (stored == completed && first) {
            long started = completed.enqueuedAt != null ? completed.enqueuedAt : completed.claimedAt;
            TasksAnalyticsSink.Holder.get().migration(account, TasksAnalyticsSink.MIGRATION_COMPLETED, completed.lists, completed.tasks,
                    completed.keptLocal, Math.max(0, now - started), null);
        }
        return stored;
    }
    // ---- the R4 plan (design 30 §6.5, §7.1; TasksCloudImport statuses and identities) ----

    /** A migration plan: the creates to enqueue now plus what the step reads from it. Content-free beyond the plan. */
    static final class MigrationPlan {
        final TasksCloudImport.Plan plan;
        /** Acknowledged rows absent after a resync: re-verified (markImportReverified) before they are resent once. */
        final List<String> reverify;
        /** Statuses of the rows that already had a mapping of the scope (an earlier step's rows). */
        final List<TasksCloudImport.RowStatus> mappedStatuses;
        /** Lists / tasks the copy covers (enqueued now, pending, in the cloud or removed there; the Cloud Inbox is no list). */
        final int lists, tasks;
        /** Rows that could not be copied and stay on this device. */
        final int keptLocal;
        /** Creates of rows that already had a mapping, enqueued by this plan. */
        final int mappedEnqueueable;

        MigrationPlan(TasksCloudImport.Plan plan, List<String> reverify, List<TasksCloudImport.RowStatus> mappedStatuses, int lists, int tasks, int keptLocal, int mappedEnqueueable) {
            this.plan = plan;
            this.reverify = Collections.unmodifiableList(new ArrayList<>(reverify));
            this.mappedStatuses = Collections.unmodifiableList(new ArrayList<>(mappedStatuses));
            this.lists = lists;
            this.tasks = tasks;
            this.keptLocal = keptLocal;
            this.mappedEnqueueable = mappedEnqueueable;
        }

        /** Every row of the copy is in the cloud or was removed there (after its resend-once check). */
        boolean confirmed() {
            for (TasksCloudImport.RowStatus status : mappedStatuses) {
                if (status != TasksCloudImport.RowStatus.ALREADY_IMPORTED && status != TasksCloudImport.RowStatus.REMOVED_IN_CLOUD) return false;
            }
            return true;
        }
    }

    /**
     * The R4 copy of one immutable snapshot: the fixed inbox is the Cloud Inbox, every other folder a list, every row
     * normalized (never refused), identities from the immutable mapping (new rows mint theirs; a rerun, a restart or a
     * re-login resends the SAME ids). {@code mappedOnly}: only rows that already have a mapping are enqueued again (the
     * verification step; rows created later are the archive's "new rows"). {@code nowMillis}: the server time the rows'
     * created_at/completed_at are checked against ({@link #normalizedAt}).
     */
    static MigrationPlan migrationPlan(ZenlyTaskImport.Snapshot snapshot, TasksMigrationNormalizer normalizer, Set<String> knownCloudIds,
                                       Set<String> outboxMutationIds, Set<String> pendingAcks, long resyncGeneration, long nowMillis,
                                       boolean mappedOnly) {
        long nowSeconds = nowMillis / 1000;
        Map<String, ZenlyTaskImport.Mapping> folderMappings = TasksCloudImport.mappings(snapshot, ZenlyTaskImport.Mapping.FOLDER);
        Map<String, ZenlyTaskImport.Mapping> taskMappings = TasksCloudImport.mappings(snapshot, ZenlyTaskImport.Mapping.TASK);
        ArrayList<ZenlyTaskImport.Mapping> mappings = new ArrayList<>();
        ArrayList<String> mutationIds = new ArrayList<>(), chains = new ArrayList<>(), listKeys = new ArrayList<>(), reverify = new ArrayList<>();
        ArrayList<TasksWire.Operation> operations = new ArrayList<>();
        ArrayList<TasksCloudImport.RowStatus> mappedStatuses = new ArrayList<>();
        HashSet<String> reverifySet = new HashSet<>();
        HashMap<String, TasksCloudImport.Target> targets = new HashMap<>();
        int lists = 0, tasks = 0, keptLocal = 0, mappedEnqueueable = 0, listIndex = 0;
        for (ZenlyTaskModel.Folder folder : snapshot.folders) {
            String incarnation = snapshot.incarnations.get(folder.id);
            ZenlyTaskImport.Mapping mapping = folderMappings.get(incarnation);
            if (mapping == null && TasksMigrationNormalizer.isCloudInbox(folder.id)) {
                targets.put(folder.id, TasksCloudImport.Target.CLOUD_INBOX);
                continue;
            }
            int index = listIndex++;
            TasksCloudImport.RowStatus status = mapping == null ? TasksCloudImport.RowStatus.NEW
                    : TasksCloudImport.mappedStatus("list", mapping, knownCloudIds, outboxMutationIds, pendingAcks, resyncGeneration, reverifySet);
            if (mapping != null) mappedStatuses.add(status);
            if (status == TasksCloudImport.RowStatus.ALREADY_IMPORTED || status == TasksCloudImport.RowStatus.PENDING) {
                targets.put(folder.id, new TasksCloudImport.Target(mapping.cloudId, false));
                lists++;
                continue;
            }
            if (status == TasksCloudImport.RowStatus.REMOVED_IN_CLOUD || mapping == null && mappedOnly) {
                // Removed lists are never re-created; unmapped folders wait for an explicit copy.
                targets.put(folder.id, TasksCloudImport.Target.BLOCKED);
                if (status == TasksCloudImport.RowStatus.REMOVED_IN_CLOUD) lists++;
                continue;
            }
            String listId = mapping == null ? TasksWire.newId() : mapping.cloudId;
            String mutationId = mapping == null ? TasksWire.newId() : mapping.mutationId;
            TasksMigrationNormalizer.List list = normalizer.list(folder.title, folder.createdAt, index, nowSeconds);
            int sortOrder = mapping == null ? list.sortOrder : mapping.sortOrder;
            TasksWire.Operation operation;
            try {
                operation = TasksWire.Operation.listCreate(listId, list.title, sortOrder, list.createdAt);
            } catch (TasksWire.Invalid e) {
                targets.put(folder.id, TasksCloudImport.Target.BLOCKED);
                keptLocal++;
                continue;
            }
            if (mapping == null) {
                mappings.add(new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.FOLDER, incarnation, listId, mutationId, sortOrder, null));
            } else {
                mappedEnqueueable++;
                if (reverifySet.contains(mutationId)) reverify.add(mutationId);
            }
            mutationIds.add(mutationId);
            operations.add(operation);
            chains.add(TasksCloudLedger.listChain(listId));
            listKeys.add(null);
            targets.put(folder.id, new TasksCloudImport.Target(listId, false));
            lists++;
        }
        HashMap<String, Integer> positions = new HashMap<>();
        for (ZenlyTaskModel.Task task : snapshot.tasks) {
            int index = TasksCloudImport.next(positions, task.folderId);
            ZenlyTaskImport.Mapping mapping = taskMappings.get(task.id);
            TasksCloudImport.RowStatus status = mapping == null ? TasksCloudImport.RowStatus.NEW
                    : TasksCloudImport.mappedStatus("task", mapping, knownCloudIds, outboxMutationIds, pendingAcks, resyncGeneration, reverifySet);
            if (mapping != null) mappedStatuses.add(status);
            if (status == TasksCloudImport.RowStatus.ALREADY_IMPORTED || status == TasksCloudImport.RowStatus.PENDING || status == TasksCloudImport.RowStatus.REMOVED_IN_CLOUD) {
                tasks++;
                continue;
            }
            if (mapping == null && mappedOnly) continue;
            String parent;
            if (mapping != null) {
                parent = mapping.parentCloudId; // fixed at the first copy, never re-resolved
            } else {
                TasksCloudImport.Target target = targets.get(task.folderId);
                if (target == null || target.blocked) {
                    keptLocal++;
                    continue;
                }
                parent = target.listId;
            }
            String taskId = mapping == null ? TasksWire.newId() : mapping.cloudId;
            String mutationId = mapping == null ? TasksWire.newId() : mapping.mutationId;
            TasksMigrationNormalizer.Task normalized = normalizer.task(task, index, nowSeconds);
            int sortOrder = mapping == null ? normalized.sortOrder : mapping.sortOrder;
            TasksWire.Operation operation;
            try {
                operation = TasksWire.Operation.taskCreate(taskId, new TasksWire.Content(parent, normalized.title, normalized.description,
                        normalized.dueAt, normalized.priority, sortOrder, null), normalized.createdAt, normalized.completed, normalized.completedAt);
            } catch (TasksWire.Invalid e) {
                keptLocal++;
                continue;
            }
            if (mapping == null) {
                mappings.add(new ZenlyTaskImport.Mapping(ZenlyTaskImport.Mapping.TASK, task.id, taskId, mutationId, sortOrder, parent));
            } else {
                mappedEnqueueable++;
                if (reverifySet.contains(mutationId)) reverify.add(mutationId);
            }
            mutationIds.add(mutationId);
            operations.add(operation);
            chains.add(TasksCloudLedger.taskChain(taskId));
            listKeys.add(parent == null ? null : TasksCloudLedger.listChain(parent));
            tasks++;
        }
        int queuedLists = 0;
        for (TasksWire.Operation operation : operations) if (!(operation instanceof TasksWire.Operation.TaskCreate)) queuedLists++;
        TasksCloudImport.Plan plan = new TasksCloudImport.Plan(mappings, mutationIds, operations, chains, listKeys, queuedLists, operations.size() - queuedLists, keptLocal);
        return new MigrationPlan(plan, reverify, mappedStatuses, lists, tasks, keptLocal, mappedEnqueueable);
    }
}
