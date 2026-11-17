package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudSchema.*;

/**
 * Durable per-account cloud state over {@link TasksSql}: shadow, staged snapshot, cursors, sealed outbox (which is
 * also the optimistic overlay), held deletes and notices. Every public method is one transaction.
 * Callers run it on one serial executor.
 * <p>
 * Tasks R4 (design 30 §6.4): outbox origins, batch selection, the members wait of list operations (sealing fix), the
 * consent withdrawal epoch and fence, automatic conflict resolution and requeue, import provenance acknowledgements and
 * the phantom settle of acknowledged creates. A per-process cache maps each row (seq, id, sealed) to its decoded
 * mutation: sealed bodies are immutable, so a drain never re-decodes the whole outbox per step.
 */
final class TasksCloudLedger {
    static final String META_INCARNATION = "incarnation", META_PRINCIPAL = "principal", META_CHANGES_CURSOR = "changes_cursor",
            META_SHADOW_COMPLETE = "shadow_complete", META_PAUSE = "pause", META_SERVER_TIME = "server_time";
    /** R4 meta (design 30 §6.2); numbers are decimal strings, times epoch milliseconds. */
    static final String META_SERVER_WIRE = "server_wire", META_BATCH_UNAVAILABLE_UNTIL = "batch_unavailable_until",
            META_CONSENT_WITHDRAWN = "consent_withdrawn", META_SYNC_CYCLE = "sync_cycle", META_CONSENT_EPOCH = "consent_epoch",
            META_CONSENT_FENCED_UNTIL = "consent_fenced_until", META_RESYNC_GENERATION = "resync_generation",
            META_BATCH_BYTE_BUDGET = "batch_byte_budget", META_BATCH_SUCCESS_STREAK = "batch_success_streak",
            META_MIGRATION_PAUSED_UNTIL = "migration_paused_until";
    /** Bookkeeping of the same rules: the fence's current hold and effective_at, the batch 503 streak, the user hold. */
    static final String META_CONSENT_FENCE_MS = "consent_fence_ms", META_CONSENT_EFFECTIVE_AT = "consent_effective_at",
            META_BATCH_503_STREAK = "batch_503_streak", META_USER_HOLD_UNTIL = "user_hold_until";
    /** Set once {@link #repairBatchRefusals} ran on this database (it never runs again). */
    static final String META_BATCH_REFUSALS_REPAIRED = "batch_refusals_repaired";
    /**
     * The migration step has checked this store (TasksAutoMigration.CloudContext.storeChecked), so the verifications the
     * account's migration record and attempt rows hold refer to it. The key dies with the database file and with a rebind
     * ({@link #bindPrincipal}).
     */
    static final String META_MIGRATION_CHECKED = "migration_checked";
    static final String PAUSE_UNLINKED = "unlinked", PAUSE_SESSION = "session";
    /**
     * Assignments (the assignee pick is the invitation; iOS wf15 §12.2 / §5.3), all content-free:
     * {@code assign_deferred.<taskId>} = {@code {"after":<unix seconds>,"to":"<recipientTgId>"}} — a rate-limited
     * assignment waiting outside the outbox for its automatic re-send; {@code assign_resend.<taskId>} = the mutation id of
     * that re-send while it is in the outbox; {@code auto_accept.<invitationId>} = {@code <mutationId>:<attempts>} — the
     * contact rule's own accept. Deleted by a withdrawal's discard, by a rebind and with the database file.
     */
    static final String DEFERRED_PREFIX = "assign_deferred.", RESEND_PREFIX = "assign_resend.", AUTO_ACCEPT_PREFIX = "auto_accept.";
    static final String WIRE_4 = "4";
    private static final String AUTO_RESOLVE_PENDING = "pending";
    /** Provisional base of a chained, not yet sealed operation; replaced before sealing and never sent. */
    private static final String PROVISIONAL_BASE = "1";
    /** Up to this many cache misses are fetched row by row; more (a cold start) in one ordered pass. */
    private static final int BODY_FETCH_ROWS = 32;

    /** One outbox row; {@link #operation} is restored strictly from the stored body. */
    static final class Entry {
        final long seq;
        final String mutationId, principal, kind, resourceId, chain, listKey;
        /** task.update/patch move: the list the task leaves (its destination is {@link #listKey}); null otherwise. */
        final String sourceListKey;
        /** Receipt resource id (list/task UUID, or the inviter Telegram id for sender.*); ACKED only. */
        final String ackedResource;
        final boolean sealed;
        final String resolvedBase, state;
        final boolean sent;
        final String error;
        final TasksWire.ConflictReason reason;
        final int attempts;
        final long notBefore;
        final String ackedRevision;
        /** {@link TasksCloudSchema#ORIGIN_USER} or {@link TasksCloudSchema#ORIGIN_MIGRATION} (batch route only). */
        final String origin;
        final int autoRequeues, autoResolutions;
        /** A conflict waiting for the next complete pull to be resolved automatically (§7.3). */
        final boolean autoResolvePending;
        /** field_changed: the conflicting fields, in content order; empty otherwise. */
        final List<TasksWire.ContentField> conflictFields;
        /**
         * list.update: [title, sort_order] the user edited from (three-way merge base). invitation.request: the live
         * assignment epoch (an invitation id) this pick was made to replace — the one the owner confirmed, or the one
         * the deferred pick it retries was made against; null when the task had no live assignee. Null otherwise.
         */
        final String baseSnapshot;
        /** A list operation stored unsealed until its own related entries settled (sealing fix). */
        final boolean waitsMembers;
        /** ACKED: the first complete changes cycle that must show the resource. */
        final Long ackedCycle;
        final TasksWire.Mutation mutation;
        final TasksWire.Operation operation;

        Entry(Object[] row, TasksWire.Mutation mutation) {
            seq = (Long) row[0];
            mutationId = (String) row[1];
            principal = (String) row[2];
            kind = (String) row[3];
            resourceId = (String) row[4];
            chain = (String) row[5];
            listKey = (String) row[6];
            sealed = ((Long) row[7]) != 0;
            resolvedBase = (String) row[8];
            state = (String) row[9];
            sent = ((Long) row[10]) != 0;
            error = (String) row[11];
            reason = row[12] == null ? null : TasksWire.ConflictReason.fromWireOrNull((String) row[12]);
            attempts = ((Long) row[13]).intValue();
            notBefore = (Long) row[14];
            ackedRevision = (String) row[15];
            sourceListKey = (String) row[16];
            ackedResource = (String) row[17];
            origin = (String) row[18];
            autoRequeues = ((Long) row[19]).intValue();
            autoResolutions = ((Long) row[20]).intValue();
            autoResolvePending = AUTO_RESOLVE_PENDING.equals(row[21]);
            conflictFields = fields((String) row[22]);
            baseSnapshot = (String) row[23];
            waitsMembers = ((Long) row[24]) != 0;
            ackedCycle = (Long) row[25];
            this.mutation = mutation;
            operation = mutation.operation;
            if (!operation.kind.wire().equals(kind) || !operation.resourceId().equals(resourceId) || !mutation.mutationId.equals(mutationId)) {
                throw new IllegalStateException("outbox_integrity");
            }
        }

        boolean open() {
            return !ACKED.equals(state);
        }

        boolean migration() {
            return ORIGIN_MIGRATION.equals(origin);
        }

        /** The stored request body (copy). */
        byte[] body() {
            return mutation.body();
        }
    }

    static final class Staging {
        final boolean catchup;
        final String cursor, changesCursor;
        final int limit;

        Staging(boolean catchup, String cursor, String changesCursor, int limit) {
            this.catchup = catchup;
            this.cursor = cursor;
            this.changesCursor = changesCursor;
            this.limit = limit;
        }
    }

    /** Builds the operation for a resolved (or provisional) base revision; base is null for creates. */
    interface Builder { TasksWire.Operation build(String baseRevision); }

    /**
     * Dependencies of the outbox in seq order, in one pass over key sets instead of all pairs ({@link #dependsOn}
     * exactly): feed every entry through {@link #add} after asking about it.
     */
    static final class Dependencies {
        private final Set<String> openChains = new HashSet<>(), openLists = new HashSet<>(), chains = new HashSet<>(), lists = new HashSet<>();

        /** An earlier unfinished entry has the same chain, is the list this entry touches, or touches this list. */
        boolean blocked(Entry entry) {
            return openChains.contains(entry.chain) || (entry.listKey != null && openChains.contains(entry.listKey))
                    || (entry.sourceListKey != null && openChains.contains(entry.sourceListKey)) || openLists.contains(entry.chain);
        }

        /** A members-waiting list operation: an earlier own entry of its list (acknowledged ones included) remains. */
        boolean membersPending(Entry entry) {
            return entry.waitsMembers && (chains.contains(entry.chain) || lists.contains(entry.chain));
        }

        boolean waiting(Entry entry) {
            return blocked(entry) || membersPending(entry);
        }

        void add(Entry entry) {
            chains.add(entry.chain);
            if (entry.listKey != null) lists.add(entry.listKey);
            if (entry.sourceListKey != null) lists.add(entry.sourceListKey);
            if (!entry.open()) return;
            openChains.add(entry.chain);
            if (entry.listKey != null) openLists.add(entry.listKey);
            if (entry.sourceListKey != null) openLists.add(entry.sourceListKey);
        }
    }

    /** The next batch: entries of one origin with their final sealed mutations, in outbox order. */
    static final class Selection {
        final String origin;
        final List<Entry> entries;
        final List<TasksWire.Mutation> mutations;

        Selection(String origin, List<Entry> entries, List<TasksWire.Mutation> mutations) {
            this.origin = origin;
            this.entries = Collections.unmodifiableList(entries);
            this.mutations = Collections.unmodifiableList(mutations);
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        /** Creates never offered to the network yet (import provenance is recorded before their first send). */
        List<String> unsentCreates() {
            ArrayList<String> ids = new ArrayList<>();
            for (Entry entry : entries) if (!entry.sent && entry.operation.kind.isCreate()) ids.add(entry.mutationId);
            return ids;
        }
    }

    private static final String ENTRY_COLUMNS = "seq,mutation_id,principal,kind,resource_id,chain,list_key,sealed,resolved_base,state,sent,error,reason,attempts,not_before,acked_revision,source_list_key,acked_resource,origin,auto_requeues,auto_resolutions,auto_resolve,conflict_fields,base_snapshot,waits_members,acked_cycle";

    private final TasksSql sql;
    /** (seq, mutation id, sealed, principal) → decoded mutation; pruned on every full load. Executor-confined like the ledger. */
    private final HashMap<String, TasksWire.Mutation> decoded = new HashMap<>();
    /** Bodies decoded since this ledger opened (host tests prove the drain decodes each row about once). */
    private long decodes;

    TasksCloudLedger(TasksSql sql) {
        this.sql = sql;
    }

    /** Fresh database: schema plus a durable repository incarnation that dies with the file. */
    static void create(TasksSql sql, final String incarnation) {
        for (String statement : DDL) sql.exec(statement);
        sql.exec("INSERT INTO meta(key,value) VALUES(?,?)", META_INCARNATION, incarnation);
    }

    /** SQLiteOpenHelper.onUpgrade, inside the helper's transaction: 1 → 2 only; anything else stays unsupported. */
    static void upgrade(TasksSql sql, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new IllegalStateException("Unsupported cloud task database migration");
        for (String statement : UPGRADE_1_TO_2) sql.exec(statement);
    }

    static String listChain(String id) { return "list:" + id; }

    static String taskChain(String id) { return "task:" + id; }

    static String invitationChain(String id) { return "invitation:" + id; }

    static String blockChain(String telegramId) { return "block:" + telegramId; }

    /** Later entry must wait for an earlier unfinished one on the same resource or list dependency. */
    static boolean dependsOn(Entry later, Entry earlier) {
        if (earlier.seq >= later.seq || !earlier.open()) return false;
        if (earlier.chain.equals(later.chain)) return true;
        return touchesList(later, earlier.chain) || touchesList(earlier, later.chain);
    }

    /** A task operation touches its destination list and, for a move, also the list it leaves. */
    private static boolean touchesList(Entry entry, String listChain) {
        return listChain.equals(entry.listKey) || listChain.equals(entry.sourceListKey);
    }

    long decodes() {
        return decodes;
    }

    // ---- meta ----

    private String meta(String key) {
        List<Object[]> rows = sql.query("SELECT value FROM meta WHERE key=?", key);
        return rows.isEmpty() ? null : (String) rows.get(0)[0];
    }

    private void setMeta(String key, String value) {
        if (value == null) sql.update("DELETE FROM meta WHERE key=?", key);
        else sql.exec("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", key, value);
    }

    /** An unreadable number is treated as absent (the rules then start from their defaults). */
    private long longMeta(String key, long fallback) {
        String value = meta(key);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void setLongMeta(String key, long value) {
        setMeta(key, Long.toString(value));
    }

    String readMeta(final String key) {
        return sql.transaction(new TasksSql.Work<String>() {
            @Override public String run() { return meta(key); }
        });
    }

    void writeMeta(final String key, final String value) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() { setMeta(key, value); return null; }
        });
    }

    long readLong(final String key, final long fallback) {
        return sql.transaction(new TasksSql.Work<Long>() {
            @Override public Long run() { return longMeta(key, fallback); }
        });
    }

    /** {@link #META_MIGRATION_CHECKED}: false for a new database and after a rebind to another principal. */
    boolean migrationChecked() {
        return "1".equals(readMeta(META_MIGRATION_CHECKED));
    }

    /** How many resnapshots this store has run ({@link #resnapshot}), 0 when none; a malformed value throws. */
    long resyncGeneration() {
        String generation = readMeta(META_RESYNC_GENERATION);
        return generation == null ? 0 : Long.parseLong(generation);
    }

    /**
     * The store has no changes cursor: it waits for a snapshot. Reconcile sets the cursor and shadow_complete together, so
     * a complete shadow without a cursor exists only between {@link #resnapshot} (a 409 resync, a consent re-grant resume,
     * the 1 → 2 upgrade) and the next complete snapshot; until then that shadow may still list rows the server erased.
     */
    boolean resnapshotPending() {
        return readMeta(META_CHANGES_CURSOR) == null;
    }

    /**
     * Binds cached cloud data to the current canonical principal. A different principal is another cloud account:
     * its shadow, cursors, staging, notices and the migration step's check of this store go; its outbox rows stay (never
     * sent, never retagged) for explicit discard.
     */
    boolean bindPrincipal(final String principal) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                String bound = meta(META_PRINCIPAL);
                if (principal.equals(bound)) return false;
                if (bound != null) {
                    sql.update("DELETE FROM shadow");
                    sql.update("DELETE FROM staged");
                    sql.update("DELETE FROM staging");
                    sql.update("DELETE FROM notices");
                    setMeta(META_CHANGES_CURSOR, null);
                    setMeta(META_SHADOW_COMPLETE, null);
                    setMeta(META_MIGRATION_CHECKED, null);
                    setMeta(META_SERVER_TIME, null);
                    setMeta(META_PAUSE, null);
                    forgetAssignmentMarkers();
                }
                setMeta(META_PRINCIPAL, principal);
                return bound != null;
            }
        });
    }

    /** Process start: an interrupted send has an unknown outcome and is resent with the same bytes. */
    void recover() {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                sql.update("UPDATE outbox SET state=? WHERE state=?", QUEUED, SENDING);
                markOrphanedSuccessors();
                return null;
            }
        });
    }

    // ---- reads ----

    List<Entry> entries() {
        return sql.transaction(new TasksSql.Work<List<Entry>>() {
            @Override public List<Entry> run() { return loadEntries(); }
        });
    }

    /** A body changes only with a new row, a new mutation id (re-encode, resolution) or its sealing. */
    private static String cacheKey(Object[] row) {
        return cacheKey((Long) row[0], (String) row[1], ((Long) row[7]) != 0, (String) row[2]);
    }

    private static String cacheKey(long seq, String mutationId, boolean sealed, String principal) {
        return seq + ":" + mutationId + ":" + (sealed ? 1 : 0) + ":" + principal;
    }

    /** Every row in seq order; bodies are read and decoded only for rows the cache does not hold. */
    private List<Entry> loadEntries() {
        List<Object[]> rows = sql.query("SELECT " + ENTRY_COLUMNS + " FROM outbox ORDER BY seq");
        HashMap<String, TasksWire.Mutation> kept = new HashMap<>(rows.size() * 2 + 1);
        ArrayList<Object[]> missing = new ArrayList<>();
        for (Object[] row : rows) {
            String key = cacheKey(row);
            TasksWire.Mutation mutation = decoded.get(key);
            if (mutation == null) missing.add(row);
            else kept.put(key, mutation);
        }
        if (!missing.isEmpty()) {
            Map<String, byte[]> bodies = new HashMap<>();
            if (missing.size() <= BODY_FETCH_ROWS) {
                for (Object[] row : missing) {
                    List<Object[]> body = sql.query("SELECT body FROM outbox WHERE mutation_id=?", (String) row[1]);
                    if (!body.isEmpty()) bodies.put((String) row[1], (byte[]) body.get(0)[0]);
                }
            } else {
                for (Object[] body : sql.query("SELECT mutation_id,body FROM outbox ORDER BY seq")) bodies.put((String) body[0], (byte[]) body[1]);
            }
            for (Object[] row : missing) {
                kept.put(cacheKey(row), restore((String) row[2], bodies.get((String) row[1])));
            }
        }
        decoded.clear();
        decoded.putAll(kept);
        ArrayList<Entry> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) result.add(new Entry(row, kept.get(cacheKey(row))));
        return result;
    }

    private TasksWire.Mutation restore(String principal, byte[] body) {
        if (body == null) throw new IllegalStateException("outbox_integrity");
        decodes++;
        return TasksWire.Mutation.restore(principal, body);
    }

    private Entry entry(String mutationId) {
        List<Object[]> rows = sql.query("SELECT " + ENTRY_COLUMNS + " FROM outbox WHERE mutation_id=?", mutationId);
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        String key = cacheKey(row);
        TasksWire.Mutation mutation = decoded.get(key);
        if (mutation == null) {
            List<Object[]> body = sql.query("SELECT body FROM outbox WHERE mutation_id=?", mutationId);
            mutation = restore((String) row[2], body.isEmpty() ? null : (byte[]) body.get(0)[0]);
            decoded.put(key, mutation);
        }
        return new Entry(row, mutation);
    }

    /** One row by its mutation id, or null. */
    Entry find(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Entry>() {
            @Override public Entry run() { return entry(mutationId); }
        });
    }

    List<TasksWire.Projection> shadow() {
        return sql.transaction(new TasksSql.Work<List<TasksWire.Projection>>() {
            @Override public List<TasksWire.Projection> run() { return projections("shadow"); }
        });
    }

    private List<TasksWire.Projection> projections(String table) {
        ArrayList<TasksWire.Projection> result = new ArrayList<>();
        for (Object[] row : sql.query("SELECT json FROM " + table + " ORDER BY kind,id")) {
            result.add(TasksWire.decodeProjection((String) row[0]));
        }
        return result;
    }

    private TasksWire.Projection projection(String table, String kind, String id) {
        List<Object[]> rows = sql.query("SELECT json FROM " + table + " WHERE kind=? AND id=?", kind, id);
        return rows.isEmpty() ? null : TasksWire.decodeProjection((String) rows.get(0)[0]);
    }

    TasksWire.TaskItem shadowTask(final String taskId) {
        return sql.transaction(new TasksSql.Work<TasksWire.TaskItem>() {
            @Override public TasksWire.TaskItem run() {
                TasksWire.Projection item = projection("shadow", "task", taskId);
                return item instanceof TasksWire.TaskItem ? (TasksWire.TaskItem) item : null;
            }
        });
    }

    List<TasksCloudState.Notice> notices() {
        return sql.transaction(new TasksSql.Work<List<TasksCloudState.Notice>>() {
            @Override public List<TasksCloudState.Notice> run() {
                ArrayList<TasksCloudState.Notice> result = new ArrayList<>();
                for (Object[] row : sql.query("SELECT id,kind,resource_id FROM notices ORDER BY id")) {
                    result.add(new TasksCloudState.Notice((Long) row[0], noticeKind((String) row[1]), (String) row[2]));
                }
                return result;
            }
        });
    }

    private static TasksCloudState.Notice.Kind noticeKind(String kind) {
        switch (kind) {
            case NOTICE_ACCESS_LOST: return TasksCloudState.Notice.Kind.ACCESS_LOST;
            case NOTICE_NOT_FOUND: return TasksCloudState.Notice.Kind.NOT_FOUND;
            case NOTICE_REMOVED_IN_CLOUD: return TasksCloudState.Notice.Kind.REMOVED_IN_CLOUD;
            case NOTICE_DELETE_KEPT: return TasksCloudState.Notice.Kind.DELETE_KEPT;
            default: return TasksCloudState.Notice.Kind.DEPENDENCY_DROPPED;
        }
    }

    void dismissNotice(final long id) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() { sql.update("DELETE FROM notices WHERE id=?", id); return null; }
        });
    }

    private void notice(String kind, String resourceId) {
        sql.exec("INSERT INTO notices(kind,resource_id) VALUES(?,?)", kind, resourceId);
    }

    /** Highest revision we know for a chain: synced shadow or our own newer acknowledged receipt. */
    private String knownRevision(String chain) {
        String best = null;
        String[] parts = chain.split(":", 2);
        if ("list".equals(parts[0]) || "task".equals(parts[0])) {
            TasksWire.Projection item = projection("shadow", parts[0], parts[1]);
            if (item instanceof TasksWire.ListItem) best = ((TasksWire.ListItem) item).revision;
            else if (item instanceof TasksWire.TaskItem) best = ((TasksWire.TaskItem) item).revision;
        }
        for (Object[] row : sql.query("SELECT acked_revision FROM outbox WHERE chain=? AND state=? AND acked_revision IS NOT NULL", chain, ACKED)) {
            String revision = (String) row[0];
            if (best == null || Long.parseLong(revision) > Long.parseLong(best)) best = revision;
        }
        return best;
    }

    private boolean hasOpenPredecessor(String chain) {
        return !sql.query("SELECT 1 FROM outbox WHERE chain=? AND state<>? LIMIT 1", chain, ACKED).isEmpty();
    }

    private boolean serverWire4() {
        return WIRE_4.equals(meta(META_SERVER_WIRE));
    }

    /**
     * Sealing fix: a list.delete (and, against a server not known to be R4 — whose list revision still advances with
     * every member change — also a list.update) waits while an own entry of that list remains, open or acknowledged
     * but not yet shown by the shadow.
     */
    private boolean waitsForMembers(TasksWire.OperationKind kind) {
        return kind == TasksWire.OperationKind.LIST_DELETE || (kind == TasksWire.OperationKind.LIST_UPDATE && !serverWire4());
    }

    private boolean touched(String listChain) {
        return !sql.query("SELECT 1 FROM outbox WHERE chain=? OR list_key=? OR source_list_key=? LIMIT 1", listChain, listChain, listChain).isEmpty();
    }

    /** An R4 list.delete carries the members fence of the settled shadow list when the server is R4 and shows one. */
    private TasksWire.Operation membersFenced(TasksWire.Operation operation) {
        if (!(operation instanceof TasksWire.Operation.ListDelete) || !serverWire4()) return operation;
        TasksWire.Operation.ListDelete delete = (TasksWire.Operation.ListDelete) operation;
        TasksWire.Projection item = projection("shadow", "list", delete.listId);
        String members = item instanceof TasksWire.ListItem ? ((TasksWire.ListItem) item).membersRevision : null;
        return members == null ? operation : TasksWire.Operation.listDelete(delete.listId, delete.baseRevision, members);
    }

    // ---- consent (R4) ----

    private void requireConsent(Long expectedEpoch) {
        if ("1".equals(meta(META_CONSENT_WITHDRAWN))) throw new TasksCloudError(TasksCloudError.Code.CONSENT_WITHDRAWN);
        if (expectedEpoch != null && expectedEpoch != longMeta(META_CONSENT_EPOCH, 0)) {
            throw new TasksCloudError(TasksCloudError.Code.CONSENT_WITHDRAWN);
        }
    }

    boolean consentWithdrawn() {
        return "1".equals(readMeta(META_CONSENT_WITHDRAWN));
    }

    long consentEpoch() {
        return readLong(META_CONSENT_EPOCH, 0);
    }

    /**
     * Consent withdrawn: every unsent entry is discarded (held deletes and in-flight sends too; acknowledged receipts
     * stay until settled), the account is marked withdrawn and its consent epoch advances, all in one transaction, so no
     * insert (user edit, import, migration step) can be queued until the re-grant. Returns {user, migration} discarded.
     */
    int[] discardAfterConsentWithdrawal() {
        return sql.transaction(new TasksSql.Work<int[]>() {
            @Override public int[] run() {
                int[] discarded = countUnsent();
                sql.update("DELETE FROM outbox WHERE state<>?", ACKED);
                forgetAssignmentMarkers();
                setMeta(META_CONSENT_WITHDRAWN, "1");
                setLongMeta(META_CONSENT_EPOCH, longMeta(META_CONSENT_EPOCH, 0) + 1);
                return discarded;
            }
        });
    }

    /** Re-grant: defensive removal of any unsent migration create, inserts allowed again, a fresh snapshot required. */
    void resumeAfterConsentRegrant() {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                sql.update("DELETE FROM outbox WHERE origin=? AND state<>?", ORIGIN_MIGRATION, ACKED);
                setMeta(META_CONSENT_WITHDRAWN, null);
                resnapshot();
                return null;
            }
        });
    }

    long consentFencedUntil() {
        return readLong(META_CONSENT_FENCED_UNTIL, 0);
    }

    /** §3.12: the whole account holds 30 s, doubling per consecutive fence up to 900 s. Returns the end of the hold. */
    long recordConsentFence(final long now, final String effectiveAt) {
        return sql.transaction(new TasksSql.Work<Long>() {
            @Override public Long run() {
                long hold = TasksCloudPolicy.consentFenceMs(longMeta(META_CONSENT_FENCE_MS, 0));
                setLongMeta(META_CONSENT_FENCED_UNTIL, now + hold);
                setLongMeta(META_CONSENT_FENCE_MS, hold);
                setMeta(META_CONSENT_EFFECTIVE_AT, effectiveAt);
                return now + hold;
            }
        });
    }

    /** The fence's time is over (doubling kept until an answer succeeds), or a permission change resets it entirely. */
    void clearConsentFence(final boolean reset) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                setMeta(META_CONSENT_FENCED_UNTIL, null);
                if (reset) {
                    setMeta(META_CONSENT_FENCE_MS, null);
                    setMeta(META_CONSENT_EFFECTIVE_AT, null);
                }
                return null;
            }
        });
    }

    /**
     * x-wire-negotiation, for every 2xx answer: the echo, a batch 200 or an R4 projection prove wire 4; a 2xx without
     * the echo is an R3 (or rolled-back) server and clears it. Any success also ends the consent fence's doubling.
     */
    void noteServerAnswer(final Integer echo, final boolean provesWire4) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                boolean wire4 = provesWire4 || (echo != null && echo == TasksWire.WIRE);
                if (wire4 != serverWire4()) setMeta(META_SERVER_WIRE, wire4 ? WIRE_4 : null);
                if (meta(META_CONSENT_FENCE_MS) != null) setMeta(META_CONSENT_FENCE_MS, null);
                return null;
            }
        });
    }

    boolean isServerWire4() {
        return WIRE_4.equals(readMeta(META_SERVER_WIRE));
    }

    // ---- counts ----

    private int[] countUnsent() {
        int[] counts = new int[2];
        for (Object[] row : sql.query("SELECT origin,count(*) FROM outbox WHERE state<>? GROUP BY origin", ACKED)) {
            counts[ORIGIN_MIGRATION.equals(row[0]) ? 1 : 0] += ((Long) row[1]).intValue();
        }
        // A deferred assignment left the outbox but is still the user's unsent change (both confirmations name it).
        counts[0] += metaEntries(DEFERRED_PREFIX).size();
        return counts;
    }

    /** Unsent entries (every state but ACKED): {user, migration}. */
    int[] unsentCounts() {
        return sql.transaction(new TasksSql.Work<int[]>() {
            @Override public int[] run() { return countUnsent(); }
        });
    }

    // ---- enqueue (atomic overlay + durable operation) ----

    /**
     * Seals immediately when the base is known; behind an unfinished predecessor of the same chain it stores a
     * provisional body that is resolved from that predecessor's receipt and sealed before its first send.
     */
    Entry enqueue(final String principal, final String mutationId, final String chain, final String listKey,
                  final String sourceListKey, final boolean needsBase, final String explicitBase, final Builder builder,
                  final boolean held) {
        return enqueue(principal, mutationId, chain, listKey, sourceListKey, needsBase, explicitBase, builder, held, null);
    }

    /** {@code baseSnapshot}: a list.update's three-way merge base ({@link #listBase}); null otherwise. */
    Entry enqueue(final String principal, final String mutationId, final String chain, final String listKey,
                  final String sourceListKey, final boolean needsBase, final String explicitBase, final Builder builder,
                  final boolean held, final String baseSnapshot) {
        return sql.transaction(new TasksSql.Work<Entry>() {
            @Override public Entry run() {
                requireConsent(null);
                return insert(principal, mutationId, chain, listKey, sourceListKey, needsBase, explicitBase, builder, held,
                        ORIGIN_USER, baseSnapshot, 0);
            }
        });
    }

    /** Several creates (import) in one transaction; existing mutation ids are skipped (idempotent resume). */
    int enqueueAll(final String principal, final List<String> mutationIds, final List<TasksWire.Operation> operations,
                   final List<String> chains, final List<String> listKeys) {
        return enqueueAll(principal, mutationIds, operations, chains, listKeys, ORIGIN_USER, null);
    }

    /**
     * {@code origin}: the explicit copy is a user action; the automatic migration (TR4-A3) enqueues {@code migration}
     * creates with the consent epoch it captured, so a withdrawal racing the step enqueues nothing.
     */
    int enqueueAll(final String principal, final List<String> mutationIds, final List<TasksWire.Operation> operations,
                   final List<String> chains, final List<String> listKeys, final String origin, final Long expectedEpoch) {
        if (!ORIGIN_USER.equals(origin) && !ORIGIN_MIGRATION.equals(origin)) throw new IllegalArgumentException("origin");
        return sql.transaction(new TasksSql.Work<Integer>() {
            @Override public Integer run() {
                requireConsent(expectedEpoch);
                int inserted = 0;
                for (int i = 0; i < operations.size(); i++) {
                    if (!sql.query("SELECT 1 FROM outbox WHERE mutation_id=?", mutationIds.get(i)).isEmpty()) continue;
                    final TasksWire.Operation operation = operations.get(i);
                    if (ORIGIN_MIGRATION.equals(origin) && !operation.kind.isCreate()) throw new IllegalArgumentException("origin");
                    insert(principal, mutationIds.get(i), chains.get(i), listKeys.get(i), null, false, null, new Builder() {
                        @Override public TasksWire.Operation build(String baseRevision) { return operation; }
                    }, false, origin, null, 0);
                    inserted++;
                }
                return inserted;
            }
        });
    }

    private Entry insert(String principal, String mutationId, String chain, String listKey, String sourceListKey,
                         boolean needsBase, String explicitBase, Builder builder, boolean held, String origin,
                         String baseSnapshot, int autoResolutions) {
        TasksWire.Operation operation;
        boolean sealed, waits = false;
        if (!needsBase) {
            operation = builder.build(null);
            sealed = true;
        } else if (waitsForMembers(builder.build(PROVISIONAL_BASE).kind) && touched(chain)) {
            // Sealed later, from the settled shadow list (base_revision, and the members fence against an R4 server).
            String provisional = knownRevision(chain);
            operation = builder.build(provisional != null ? provisional : explicitBase != null ? explicitBase : PROVISIONAL_BASE);
            sealed = false;
            waits = true;
        } else if (hasOpenPredecessor(chain)) {
            String provisional = knownRevision(chain);
            operation = builder.build(provisional != null ? provisional : explicitBase != null ? explicitBase : PROVISIONAL_BASE);
            sealed = false;
        } else {
            String base = knownRevision(chain);
            if (base == null) base = explicitBase;
            if (base == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
            operation = membersFenced(builder.build(base));
            sealed = true;
        }
        byte[] body = TasksWire.Mutation.seal(principal, mutationId, operation).body();
        sql.exec("INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,list_key,source_list_key,body,sealed,state,origin,base_snapshot,waits_members,auto_resolutions) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                mutationId, principal, operation.kind.wire(), operation.resourceId(), chain, listKey,
                sourceListKey == null || sourceListKey.equals(listKey) ? null : sourceListKey, body, sealed ? 1L : 0L,
                held ? HELD : QUEUED, origin, baseSnapshot, waits ? 1L : 0L, (long) autoResolutions);
        return entry(mutationId);
    }

    /** A list's three-way merge base for list.update: the title and order the user edited from. */
    static String listBase(String title, int sortOrder) {
        return TasksWireJson.canonical(Arrays.<Object>asList(title, (long) sortOrder));
    }

    // ---- drain ----

    /** Not held for its origin, due, and sealable now (sealed, a resolved base, or a members wait that is over). */
    private static boolean candidate(Entry entry, String principal, long now, Dependencies dependencies) {
        if (!entry.principal.equals(principal)) return false;
        if (!QUEUED.equals(entry.state) && !BACKOFF.equals(entry.state)) return false;
        if (entry.notBefore > now) return false;
        if (entry.waitsMembers ? dependencies.membersPending(entry) : !entry.sealed && entry.resolvedBase == null) return false;
        return !dependencies.blocked(entry);
    }

    /**
     * Single route: the oldest sendable USER entry of the current principal whose dependencies are all acknowledged.
     * Migration creates never take the single route (x-creation-proof.migration_exemption): they wait for batches.
     */
    Entry nextEligible(final String principal, final long now) {
        return sql.transaction(new TasksSql.Work<Entry>() {
            @Override public Entry run() {
                Dependencies dependencies = new Dependencies();
                for (Entry entry : loadEntries()) {
                    boolean eligible = !entry.migration() && candidate(entry, principal, now, dependencies);
                    dependencies.add(entry);
                    if (eligible) return entry;
                }
                return null;
            }
        });
    }

    /**
     * §7.2: up to {@code maxItems} chain-free entries of ONE origin (user first; an origin is skipped while held), in
     * outbox order, whose batch body stays within min(budget, 524288) minus the proof reserve when a create is in it —
     * never fewer than one entry. Each comes with its final sealed mutation (a members wait seals from the shadow now;
     * one whose list vanished is settled here instead).
     */
    Selection nextEligibleBatch(final String principal, final long now, final int maxItems, final int budget) {
        return sql.transaction(new TasksSql.Work<Selection>() {
            @Override public Selection run() {
                boolean userHeld = longMeta(META_USER_HOLD_UNTIL, 0) > now;
                boolean migrationHeld = longMeta(META_MIGRATION_PAUSED_UNTIL, 0) > now;
                Dependencies dependencies = new Dependencies();
                ArrayList<Entry> user = new ArrayList<>(), migration = new ArrayList<>();
                for (Entry entry : loadEntries()) {
                    if (candidate(entry, principal, now, dependencies)) {
                        if (entry.migration()) {
                            if (!migrationHeld) migration.add(entry);
                        } else if (!userHeld) {
                            user.add(entry);
                        }
                    }
                    dependencies.add(entry);
                }
                Selection selection = size(ORIGIN_USER, user, maxItems, budget);
                return selection.isEmpty() ? size(ORIGIN_MIGRATION, migration, maxItems, budget) : selection;
            }
        });
    }

    private Selection size(String origin, List<Entry> candidates, int maxItems, int budget) {
        TasksWire.Origin wireOrigin = ORIGIN_MIGRATION.equals(origin) ? TasksWire.Origin.MIGRATION : TasksWire.Origin.USER;
        int limit = Math.min(budget, TasksCloudPolicy.BATCH_MAX_BYTES);
        int bytes = TasksWire.Batch.bodyBytes(Collections.<TasksWire.Batch.Item>emptyList());
        boolean hasCreate = false;
        ArrayList<Entry> chosen = new ArrayList<>();
        ArrayList<TasksWire.Mutation> mutations = new ArrayList<>();
        for (Entry entry : candidates) {
            if (chosen.size() >= maxItems) break;
            TasksWire.Mutation mutation = sealedForSend(entry);
            if (mutation == null) {
                if (entry.waitsMembers) settleVanishedList(entry);
                continue;
            }
            int total = bytes + mutation.itemBytes(wireOrigin) + (chosen.isEmpty() ? 0 : 1);
            boolean create = mutation.operation.kind.isCreate();
            int reserve = hasCreate || create ? TasksCloudPolicy.BATCH_PROOF_RESERVE : TasksCloudPolicy.BATCH_ENVELOPE_RESERVE;
            if (!chosen.isEmpty() && (total > limit - reserve || total > TasksCloudPolicy.BATCH_MAX_BYTES)) break;
            chosen.add(entry);
            mutations.add(mutation);
            bytes = total;
            hasCreate |= create;
        }
        return new Selection(origin, chosen, mutations);
    }

    /**
     * The exact mutation this entry is sent as: its stored body when sealed, a provisional one resolved from its
     * predecessor's receipt, or a members wait sealed from the settled shadow list. Null when it cannot be sealed now.
     */
    private TasksWire.Mutation sealedForSend(Entry entry) {
        if (entry.sealed) return entry.mutation;
        if (entry.waitsMembers) {
            String base = knownRevision(entry.chain);
            if (base == null) return null; // the list is gone: settled by beginSend / beginSendBatch
            return TasksWire.Mutation.seal(entry.principal, entry.mutationId, membersFenced(withBase(entry.operation, base)));
        }
        if (entry.sent || entry.resolvedBase == null) return null;
        return TasksWire.Mutation.seal(entry.principal, entry.mutationId, withBase(entry.operation, entry.resolvedBase));
    }

    /** A members-waiting list operation whose list vanished: a delete has nothing left to do, an update is NOT_FOUND. */
    private void settleVanishedList(Entry entry) {
        if (entry.operation.kind == TasksWire.OperationKind.LIST_DELETE) {
            sql.update("DELETE FROM outbox WHERE mutation_id=?", entry.mutationId);
        } else {
            setState(entry.mutationId, NOT_FOUND, "tasks_not_found", null, 0);
            notice(NOTICE_NOT_FOUND, entry.resourceId);
        }
    }

    /** Earliest backoff wake-up among this principal's entries, or 0. */
    long nextWake(final String principal) {
        return sql.transaction(new TasksSql.Work<Long>() {
            @Override public Long run() {
                List<Object[]> rows = sql.query("SELECT min(not_before) FROM outbox WHERE principal=? AND state=?", principal, BACKOFF);
                return rows.isEmpty() || rows.get(0)[0] == null ? 0L : (Long) rows.get(0)[0];
            }
        });
    }

    /**
     * Final sealing (if provisional) plus the durable "sent" mark, committed BEFORE the network attempt. From here the
     * id and bytes are immutable. Returns the exact mutation to send, or null if the entry changed meanwhile.
     */
    TasksWire.Mutation beginSend(final String mutationId) {
        return sql.transaction(new TasksSql.Work<TasksWire.Mutation>() {
            @Override public TasksWire.Mutation run() {
                Entry entry = entry(mutationId);
                if (entry == null || !(QUEUED.equals(entry.state) || BACKOFF.equals(entry.state))) return null;
                TasksWire.Mutation mutation = sealedForSend(entry);
                if (mutation == null) {
                    if (entry.waitsMembers) settleVanishedList(entry);
                    return null;
                }
                return markSending(entry, mutation) ? mutation : null;
            }
        });
    }

    private boolean markSending(Entry entry, TasksWire.Mutation mutation) {
        boolean marked = sql.update("UPDATE outbox SET body=?,sealed=1,waits_members=0,state=?,sent=1,attempts=attempts+1,error=NULL,reason=NULL WHERE mutation_id=? AND state IN (?,?)",
                mutation.body(), SENDING, entry.mutationId, QUEUED, BACKOFF) == 1;
        // The sealed bytes are known: no re-decode of a provisional entry once it is sealed.
        if (marked && !entry.sealed) decoded.put(cacheKey(entry.seq, entry.mutationId, true, entry.principal), mutation);
        return marked;
    }

    /**
     * The batch counterpart of {@link #beginSend}: every selected entry sealed and marked sending (its attempt counted;
     * {@link #requeueUncounted} takes it back when the item never ran) in ONE transaction. Entries that changed in the
     * meantime are left out.
     */
    List<TasksWire.Batch.Item> beginSendBatch(final Selection selection) {
        return sql.transaction(new TasksSql.Work<List<TasksWire.Batch.Item>>() {
            @Override public List<TasksWire.Batch.Item> run() {
                TasksWire.Origin origin = ORIGIN_MIGRATION.equals(selection.origin) ? TasksWire.Origin.MIGRATION : TasksWire.Origin.USER;
                ArrayList<TasksWire.Batch.Item> items = new ArrayList<>();
                for (int i = 0; i < selection.entries.size(); i++) {
                    if (markSending(selection.entries.get(i), selection.mutations.get(i))) {
                        items.add(new TasksWire.Batch.Item(selection.mutations.get(i), origin));
                    }
                }
                return items;
            }
        });
    }

    /** Entries of a request that never ran (or whose answer is not this scope's): back to the queue, attempt not counted. */
    void requeueUncounted(final List<String> mutationIds) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                for (String id : mutationIds) {
                    sql.update("UPDATE outbox SET state=?,attempts=CASE WHEN attempts>0 THEN attempts-1 ELSE 0 END WHERE mutation_id=? AND state=?",
                            QUEUED, id, SENDING);
                }
                return null;
            }
        });
    }

    /**
     * Receipt committed before any re-gating; the overlay stays until the shadow shows it (see settleReceipts). A
     * create's id also enters import_ack_pending in the same transaction (its local import provenance follows later).
     * Returns whether the entry was acknowledged.
     */
    boolean acknowledge(final String mutationId, final TasksWire.Receipt receipt) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || !SENDING.equals(entry.state)) return false;
                // A row coalesced beyond the next cycle's watermark is discovered by the cycle after it (x-sync.changes).
                long mustShow = longMeta(META_SYNC_CYCLE, 0) + 1;
                sql.update("UPDATE outbox SET state=?,error=NULL,reason=NULL,acked_revision=?,acked_resource=?,acked_cycle=? WHERE mutation_id=?",
                        ACKED, receipt.resourceRevision, receipt.resourceId, mustShow, mutationId);
                if (entry.operation.kind.isCreate()) sql.exec("INSERT OR IGNORE INTO import_ack_pending(mutation_id) VALUES(?)", mutationId);
                if (receipt.resourceRevision != null) {
                    List<Object[]> next = sql.query("SELECT mutation_id,sealed FROM outbox WHERE chain=? AND state<>? AND mutation_id<>? ORDER BY seq LIMIT 1",
                            entry.chain, ACKED, mutationId);
                    if (!next.isEmpty() && ((Long) next.get(0)[1]) == 0) {
                        sql.update("UPDATE outbox SET resolved_base=? WHERE mutation_id=? AND sealed=0", receipt.resourceRevision, next.get(0)[0]);
                    }
                }
                if (entry.operation instanceof TasksWire.Operation.InvitationRequest) {
                    settleAssignmentReceipt(entry, (TasksWire.Operation.InvitationRequest) entry.operation);
                }
                return true;
            }
        });
    }

    /**
     * Applies a classified failure of the in-flight entry. {@code autoResolve}: a conflict the §7.3 table resolves
     * after the next complete pull. Returns false if the entry no longer exists.
     */
    boolean fail(final String mutationId, final TasksCloudPolicy.Action action, final TasksCloudRemote.Failure failure, final long now,
                 final boolean autoResolve) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || !SENDING.equals(entry.state)) return false;
                String reason = failure.reason == null ? null : failure.reason.wire();
                switch (action) {
                    case BACKOFF:
                        if (entry.attempts >= TasksCloudPolicy.MAX_AUTO_ATTEMPTS) setState(mutationId, STALLED, failure.code, null, 0);
                        else setState(mutationId, BACKOFF, failure.code, null, now + TasksCloudPolicy.backoffMs(entry.attempts));
                        break;
                    case RETRY_AFTER:
                        setState(mutationId, BACKOFF, failure.code, null, now + Math.max(1, failure.retryAfterSeconds) * 1000L);
                        break;
                    case REQUEUE:
                    case PAUSE_UNLINKED:
                    case PAUSE_SESSION:
                    case PAUSE_NOT_LINKED:
                    case RESYNC:
                        setState(mutationId, QUEUED, null, null, 0);
                        break;
                    case CONSENT_FENCED:
                        // Never an entry failure: the attempt is taken back.
                        sql.update("UPDATE outbox SET state=?,attempts=CASE WHEN attempts>0 THEN attempts-1 ELSE 0 END WHERE mutation_id=?", QUEUED, mutationId);
                        break;
                    case BLOCK_CREATION:
                        setState(mutationId, BLOCKED_CREATION, failure.code, null, 0);
                        break;
                    case INVALID:
                        setState(mutationId, INVALID, failure.code, null, 0);
                        break;
                    case QUARANTINE:
                        setState(mutationId, QUARANTINED, failure.code, null, 0);
                        break;
                    case CONFLICT:
                        sql.update("UPDATE outbox SET state=?,error=?,reason=?,not_before=0,conflict_fields=?,auto_resolve=? WHERE mutation_id=?",
                                CONFLICT, failure.code, reason, fieldsText(failure.fields), autoResolve ? AUTO_RESOLVE_PENDING : null, mutationId);
                        break;
                    case NOT_FOUND:
                        if (isSharedAccess(entry)) {
                            loseSharedAccess(entry.operation);
                        } else {
                            setState(mutationId, NOT_FOUND, failure.code, null, 0);
                            notice(NOTICE_NOT_FOUND, entry.resourceId);
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
    }

    private void setState(String mutationId, String state, String error, String reason, long notBefore) {
        sql.update("UPDATE outbox SET state=?,error=?,reason=?,not_before=?,conflict_fields=NULL,auto_resolve=NULL WHERE mutation_id=?",
                state, error, reason, notBefore, mutationId);
    }

    /**
     * x-wire-negotiation.downgrade: a 422 on an R4-only entry from an R3 server is a definite non-commit. The entry is
     * re-encoded ONCE as R3 under a NEW mutation id in its place — a task.patch as task.update of the shadow content with
     * the set values (base: the chain's known revision), a members-fenced list.delete without the fence — and
     * server_wire is cleared. False when it cannot be (no server row, or content the R3 rules refuse): then INVALID.
     */
    boolean reencodeAsR3(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || !SENDING.equals(entry.state) || !entry.mutation.requiresWire4()) return false;
                TasksWire.Operation r3;
                try {
                    if (entry.operation instanceof TasksWire.Operation.TaskPatch) {
                        TasksWire.Operation.TaskPatch patch = (TasksWire.Operation.TaskPatch) entry.operation;
                        TasksWire.Projection item = projection("shadow", "task", patch.taskId);
                        String base = knownRevision(entry.chain);
                        if (!(item instanceof TasksWire.TaskItem) || base == null) return false;
                        r3 = TasksWire.Operation.taskUpdate(patch.taskId, base, ((TasksWire.TaskItem) item).content.applying(patch.set));
                    } else {
                        TasksWire.Operation.ListDelete delete = (TasksWire.Operation.ListDelete) entry.operation;
                        r3 = TasksWire.Operation.listDelete(delete.listId, delete.baseRevision);
                    }
                } catch (TasksWire.Invalid refused) {
                    return false;
                }
                replace(entry, TasksWire.newId(), r3, QUEUED, null, null, entry.autoResolutions, entry.baseSnapshot);
                setMeta(META_SERVER_WIRE, null);
                return true;
            }
        });
    }

    /** One entry's place in the outbox (seq) taken by a new identity: never sent, attempts and automatic marks reset. */
    private void replace(Entry entry, String mutationId, TasksWire.Operation operation, String state, TasksWire.ConflictReason reason,
                         List<TasksWire.ContentField> fields, int autoResolutions, String baseSnapshot) {
        byte[] body = TasksWire.Mutation.seal(entry.principal, mutationId, operation).body();
        sql.update("UPDATE outbox SET mutation_id=?,kind=?,resource_id=?,body=?,sealed=1,resolved_base=NULL,state=?,sent=0,error=?,reason=?,attempts=0,not_before=0,acked_revision=NULL,acked_resource=NULL,auto_resolutions=?,auto_resolve=NULL,conflict_fields=?,base_snapshot=?,waits_members=0,acked_cycle=NULL WHERE seq=?",
                mutationId, operation.kind.wire(), operation.resourceId(), body, state, reason == null ? null : "tasks_conflict",
                reason == null ? null : reason.wire(), (long) autoResolutions, fieldsText(fields), baseSnapshot, entry.seq);
    }

    /** Recipient-side selector: an assignee task, or an incoming invitation response. */
    private boolean isSharedAccess(Entry entry) {
        if (entry.operation instanceof TasksWire.Operation.InvitationRespond) {
            return projection("shadow", "task", ((TasksWire.Operation.InvitationRespond) entry.operation).taskId) == null
                    || isAssignee(((TasksWire.Operation.InvitationRespond) entry.operation).taskId);
        }
        if (!entry.chain.startsWith("task:")) return false;
        return isAssignee(entry.chain.substring(5));
    }

    private boolean isAssignee(String taskId) {
        TasksWire.Projection item = projection("shadow", "task", taskId);
        return item instanceof TasksWire.TaskItem && ((TasksWire.TaskItem) item).viewerRole == TasksWire.ViewerRole.ASSIGNEE;
    }

    /** Confirmed loss of shared access: no readable copy, no unauthorized commands, minimal notice only. */
    private void loseSharedAccess(TasksWire.Operation operation) {
        String taskId = null;
        if (operation instanceof TasksWire.Operation.InvitationRespond) {
            TasksWire.Operation.InvitationRespond respond = (TasksWire.Operation.InvitationRespond) operation;
            taskId = respond.taskId;
            sql.update("DELETE FROM shadow WHERE kind='invitation' AND id=?", respond.invitationId);
        } else {
            taskId = operation.resourceId();
        }
        removeSharedTask(taskId);
    }

    private void removeSharedTask(String taskId) {
        sql.update("DELETE FROM shadow WHERE kind='task' AND id=?", taskId);
        for (Object[] row : sql.query("SELECT json FROM shadow WHERE kind='invitation'")) {
            TasksWire.InvitationItem invitation = (TasksWire.InvitationItem) TasksWire.decodeProjection((String) row[0]);
            if (invitation.taskId.equals(taskId)) sql.update("DELETE FROM shadow WHERE kind='invitation' AND id=?", invitation.id);
        }
        boolean left = leftVoluntarily(taskId);
        sql.update("DELETE FROM outbox WHERE chain=?", taskChain(taskId));
        if (!left) notice(NOTICE_ACCESS_LOST, taskId);
    }

    /** The recipient's own Leave is not an access-loss surprise: no notice for it. */
    private boolean leftVoluntarily(String taskId) {
        for (Object[] row : sql.query("SELECT mutation_id FROM outbox WHERE chain=? AND kind=?",
                taskChain(taskId), TasksWire.OperationKind.INVITATION_RESPOND.wire())) {
            Entry entry = entry((String) row[0]);
            if (((TasksWire.Operation.InvitationRespond) entry.operation).decision == TasksWire.Decision.LEAVE) return true;
        }
        return false;
    }


    // ---- assignments: the assignee pick is the invitation (iOS wf15 §4, §5.3, §12) ----

    /** An assignment that waits outside the outbox for its automatic re-send ({@link #DEFERRED_PREFIX}). */
    static final class Deferral {
        final String taskId, recipientTgId;
        final long afterMs;
        /** The live assignment epoch the deferred pick was made to replace (the owner confirmed it), or null. */
        final String replaces;

        Deferral(String taskId, String recipientTgId, long afterMs) {
            this(taskId, recipientTgId, afterMs, null);
        }

        Deferral(String taskId, String recipientTgId, long afterMs, String replaces) {
            this.taskId = taskId;
            this.recipientTgId = recipientTgId;
            this.afterMs = afterMs;
            this.replaces = replaces;
        }

        /** {@code {"after":<unix seconds>,"to":"<recipientTgId>"}}. */
        static String encode(String recipientTgId, long afterMs) {
            return encode(recipientTgId, afterMs, null);
        }

        /** With {@code "replaces":"<invitationId>"} when the pick was made to replace a live assignment. */
        static String encode(String recipientTgId, long afterMs, String replaces) {
            return "{\"after\":" + Math.floorDiv(afterMs, 1000L) + ",\"to\":\"" + recipientTgId + "\""
                    + (replaces == null ? "" : ",\"replaces\":\"" + replaces + "\"") + "}";
        }

        /** Null for a row the decoder refuses (it is still counted as an unsent change, never pretended away). */
        static Deferral decode(String taskId, String value) {
            try {
                TasksWireText.uuid(taskId, "task_id");
                Object parsed = TasksWireJson.parse(value, 256);
                if (!(parsed instanceof Map)) return null;
                Object to = ((Map<?, ?>) parsed).get("to"), after = ((Map<?, ?>) parsed).get("after");
                Object replaces = ((Map<?, ?>) parsed).get("replaces");
                if (!(to instanceof String) || !(after instanceof TasksWireJson.Num)) return null;
                if (replaces != null && !(replaces instanceof String)) return null;
                return new Deferral(taskId, TasksWireText.positive((String) to, "to"), Long.parseLong(((TasksWireJson.Num) after).literal) * 1000L,
                        replaces == null ? null : TasksWireText.uuid((String) replaces, "replaces"));
            } catch (RuntimeException refused) {
                return null;
            }
        }
    }

    /** {@link #AUTO_ACCEPT_PREFIX}: the automatic accept's entry and how many automatic accepts were made. */
    static final class AutoAcceptRecord {
        final String mutationId;
        final int attempts;

        AutoAcceptRecord(String mutationId, int attempts) {
            this.mutationId = mutationId;
            this.attempts = attempts;
        }

        /** {@code <mutationId>:<attempts>}; null when absent or malformed. */
        static AutoAcceptRecord decode(String raw) {
            if (raw == null) return null;
            int separator = raw.lastIndexOf(':');
            if (separator <= 0) return null;
            try {
                int attempts = Integer.parseInt(raw.substring(separator + 1));
                if (attempts < 0) return null;
                return new AutoAcceptRecord(TasksWireText.uuid(raw.substring(0, separator), "mutation_id"), attempts);
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    /** The contact rule over one pending invitation, asked inside the ledger's transaction ({@link #runAutoAccept}). */
    interface AutoAcceptDecider {
        boolean accepts(TasksWire.InvitationItem invitation, Set<String> unresolvedInvitationIds, Map<String, Integer> automaticAttempts);
    }

    /** Meta rows whose key starts with {@code prefix}, as {key, value}, in key order. */
    private List<String[]> metaEntries(String prefix) {
        ArrayList<String[]> rows = new ArrayList<>();
        for (Object[] row : sql.query("SELECT key,value FROM meta WHERE substr(key,1," + prefix.length() + ")=? ORDER BY key", prefix)) {
            rows.add(new String[]{(String) row[0], (String) row[1]});
        }
        return rows;
    }

    private void deleteMetaPrefix(String prefix) {
        sql.update("DELETE FROM meta WHERE substr(key,1," + prefix.length() + ")=?", prefix);
    }

    private void forgetAssignmentMarkers() {
        deleteMetaPrefix(DEFERRED_PREFIX);
        deleteMetaPrefix(RESEND_PREFIX);
        deleteMetaPrefix(AUTO_ACCEPT_PREFIX);
    }

    // -- the pacer's counters --

    /** {recipient today, today, this minute}. */
    private int[] pacerCounters(String recipientTgId, long nowMs) {
        String day = TasksInvitationPacer.dayKey(nowMs);
        return new int[]{
                (int) longMeta(TasksInvitationPacer.recipientCounterKey(day, recipientTgId), 0),
                (int) longMeta(TasksInvitationPacer.dayCounterKey(day), 0),
                (int) longMeta(TasksInvitationPacer.minuteCounterKey(TasksInvitationPacer.minuteKey(nowMs)), 0)};
    }

    private TasksInvitationPacer.Limit pacerLimit(String recipientTgId, long nowMs, int pending) {
        int[] counters = pacerCounters(recipientTgId, nowMs);
        return TasksInvitationPacer.refusal(new TasksInvitationPacer.Counts(counters[0], counters[1], counters[2], pending));
    }

    /**
     * Which limit one more manual pick to {@code recipientTgId} would exceed now (null: allowed). {@code pending}: the
     * owner's assignments that are pending and unexpired on the visible board.
     */
    TasksInvitationPacer.Limit pacerRefusal(final String recipientTgId, final long nowMs, final int pending) {
        return sql.transaction(new TasksSql.Work<TasksInvitationPacer.Limit>() {
            @Override public TasksInvitationPacer.Limit run() { return pacerLimit(recipientTgId, nowMs, pending); }
        });
    }

    private void refuseOverPacer(String recipientTgId, long nowMs, int pending) {
        TasksInvitationPacer.Limit limit = pacerLimit(recipientTgId, nowMs, pending);
        if (limit != null) throw new TasksCloudError(TasksCloudError.Code.ASSIGNMENT_LIMIT, limit.name());
    }

    /** Charges one manual pick (at enqueue: a failed send still counts) and deletes the counters older than two UTC days. */
    private void chargePacer(String recipientTgId, long nowMs) {
        String day = TasksInvitationPacer.dayKey(nowMs);
        String[] keys = {TasksInvitationPacer.recipientCounterKey(day, recipientTgId), TasksInvitationPacer.dayCounterKey(day),
                TasksInvitationPacer.minuteCounterKey(TasksInvitationPacer.minuteKey(nowMs))};
        for (String key : keys) setLongMeta(key, longMeta(key, 0) + 1);
        for (String prefix : new String[]{TasksInvitationPacer.DAY_PREFIX, TasksInvitationPacer.MINUTE_PREFIX}) {
            for (String[] row : metaEntries(prefix)) {
                if (TasksInvitationPacer.isExpired(row[0], nowMs)) setMeta(row[0], null);
            }
        }
    }

    // -- the pick --

    /**
     * The composer's Add with an assignee: the task.create and the invitation.request on the same task chain in ONE
     * transaction — the request unsealed until the create's receipt, then sealed with its revision. A refused pick (the
     * pacer) queues nothing, the create included.
     */
    void enqueueCreateWithAssignment(final String principal, final String createMutationId, final TasksWire.Operation.TaskCreate create,
                                     final String listKey, final String inviteMutationId, final String invitationId,
                                     final String recipientTgId, final long nowMs, final int pending) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                requireConsent(null);
                refuseOverPacer(recipientTgId, nowMs, pending);
                insert(principal, createMutationId, taskChain(create.taskId), listKey, null, false, null, new Builder() {
                    @Override public TasksWire.Operation build(String baseRevision) { return create; }
                }, false, ORIGIN_USER, null, 0);
                insertAssignment(principal, inviteMutationId, create.taskId, listKey, invitationId, recipientTgId, false, nowMs, null);
                return null;
            }
        });
    }

    /**
     * The pick on an existing owned task: a manual one passes the pacer and is charged; the automatic re-send of a
     * deferral is neither checked nor charged. A newer manual pick cancels the task's deferral and an automatic re-send
     * that never left the device. {@code listKey}: the chain of the task's list (null for the Inbox) — the server moves
     * that list's members revision with every assignment, so the list's own operations wait for this entry as they do
     * for any other change of a member task. {@code replaces}: the live assignment epoch this pick replaces (the owner
     * confirmed it), or null — a conflict is resealed only while that is still what the pick would replace.
     */
    void enqueueAssignment(final String principal, final String mutationId, final String taskId, final String listKey,
                           final String invitationId, final String recipientTgId, final boolean automatic, final long nowMs,
                           final int pending, final String replaces) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                requireConsent(null);
                if (!automatic) refuseOverPacer(recipientTgId, nowMs, pending);
                insertAssignment(principal, mutationId, taskId, listKey, invitationId, recipientTgId, automatic, nowMs, replaces);
                return null;
            }
        });
    }

    /** Inside a transaction, after the refusals: the invitation.request itself (a fresh epoch) on the task's chain. */
    private void insertAssignment(String principal, String mutationId, final String taskId, String listKey, final String invitationId,
                                  final String recipientTgId, boolean automatic, long nowMs, String replaces) {
        String resendKey = RESEND_PREFIX + taskId;
        if (!automatic) {
            String resend = meta(resendKey);
            if (resend != null) {
                Entry pending = entry(resend);
                if (pending == null) {
                    setMeta(resendKey, null);
                } else if (!pending.sent && !ACKED.equals(pending.state)) {
                    // Never sent: the newer pick replaces it. Sent once (outcome unknown): it stays, and its answer is
                    // handled as the automatic re-send it is.
                    removeQuietly(pending);
                    setMeta(resendKey, null);
                }
            }
        }
        setMeta(DEFERRED_PREFIX + taskId, null);
        insert(principal, mutationId, taskChain(taskId), listKey, null, true, null, new Builder() {
            @Override public TasksWire.Operation build(String baseRevision) {
                return TasksWire.Operation.invitationRequest(taskId, baseRevision, invitationId, recipientTgId);
            }
        }, false, ORIGIN_USER, replaces, 0);
        if (automatic) setMeta(resendKey, mutationId);
        else chargePacer(recipientTgId, nowMs);
    }

    /**
     * An entry leaves the outbox without a word (a deferred assignment, an automatic entry the server refused): no
     * dependents are marked, and the next entry of its chain that waited unsealed for this one's receipt seals on the
     * chain's known revision instead.
     */
    private void removeQuietly(Entry entry) {
        sql.update("DELETE FROM outbox WHERE seq=?", entry.seq);
        resolveSuccessorBase(entry.chain);
    }

    private void resolveSuccessorBase(String chain) {
        List<Object[]> next = sql.query("SELECT mutation_id,sealed,resolved_base,waits_members FROM outbox WHERE chain=? AND state<>? ORDER BY seq LIMIT 1",
                chain, ACKED);
        if (next.isEmpty()) return;
        Object[] row = next.get(0);
        if (((Long) row[1]) != 0 || row[2] != null || ((Long) row[3]) != 0) return;
        String base = knownRevision(chain);
        if (base != null) sql.update("UPDATE outbox SET resolved_base=? WHERE mutation_id=? AND sealed=0", base, row[0]);
    }

    // -- deferred assignments --

    /**
     * A 429 with a long Retry-After on an in-flight invitation.request (single route or batch item): the entry goes and
     * the assignment waits as {@link #DEFERRED_PREFIX} — no attempt, no hold, and the task's later edits are not blocked
     * behind it. A second long 429 on the automatic re-send rewrites the time. False when the entry is not that.
     */
    boolean deferAssignment(final String mutationId, final long afterMs) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || !SENDING.equals(entry.state) || !(entry.operation instanceof TasksWire.Operation.InvitationRequest)) return false;
                TasksWire.Operation.InvitationRequest request = (TasksWire.Operation.InvitationRequest) entry.operation;
                removeQuietly(entry);
                setMeta(DEFERRED_PREFIX + request.taskId, Deferral.encode(request.recipientTgId, afterMs, entry.baseSnapshot));
                String resendKey = RESEND_PREFIX + request.taskId;
                if (mutationId.equals(meta(resendKey))) setMeta(resendKey, null);
                return true;
            }
        });
    }

    private List<Deferral> loadDeferrals() {
        ArrayList<Deferral> deferrals = new ArrayList<>();
        for (String[] row : metaEntries(DEFERRED_PREFIX)) {
            Deferral deferral = Deferral.decode(row[0].substring(DEFERRED_PREFIX.length()), row[1]);
            if (deferral != null) deferrals.add(deferral);
        }
        return deferrals;
    }

    List<Deferral> deferrals() {
        return sql.transaction(new TasksSql.Work<List<Deferral>>() {
            @Override public List<Deferral> run() { return loadDeferrals(); }
        });
    }

    /** Every {@link #DEFERRED_PREFIX} row, readable or not (an unreadable one is still an unsent change). */
    int deferralRows() {
        return sql.transaction(new TasksSql.Work<Integer>() {
            @Override public Integer run() { return metaEntries(DEFERRED_PREFIX).size(); }
        });
    }

    /** When the earliest deferred assignment is due (epoch ms), or 0. */
    long nextDeferralDue() {
        long earliest = 0;
        for (Deferral deferral : deferrals()) {
            if (earliest == 0 || deferral.afterMs < earliest) earliest = deferral.afterMs;
        }
        return earliest;
    }

    /**
     * Whether the cycle start has anything to do: a deferral whose time has come, or the marker of an automatic re-send
     * whose entry is gone. Asked before the state is read, so a deferral that waits for hours costs two small queries a
     * step and nothing else.
     */
    boolean hasDueDeferralWork(final long nowMs) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                for (Deferral deferral : loadDeferrals()) {
                    if (deferral.afterMs <= nowMs) return true;
                }
                for (String[] marker : metaEntries(RESEND_PREFIX)) {
                    if (sql.query("SELECT 1 FROM outbox WHERE mutation_id=?", marker[1]).isEmpty()) return true;
                }
                return false;
            }
        });
    }

    /**
     * Cycle start: markers of automatic re-sends whose entry is gone are dropped, and every deferral that is due leaves
     * the meta table — back into the outbox as the same pick retried when its task is a key of {@code sendableTasks}
     * (still owned, open, without a queued request of its own, and with no live assignment other than the one the pick
     * was made to replace; the value is the chain of the task's list, or null), quietly otherwise. Returns how many were
     * re-sent.
     */
    int reenqueueDueDeferrals(final String principal, final long nowMs, final Map<String, String> sendableTasks) {
        return sql.transaction(new TasksSql.Work<Integer>() {
            @Override public Integer run() {
                if ("1".equals(meta(META_CONSENT_WITHDRAWN))) return 0;
                for (String[] marker : metaEntries(RESEND_PREFIX)) {
                    if (sql.query("SELECT 1 FROM outbox WHERE mutation_id=?", marker[1]).isEmpty()) setMeta(marker[0], null);
                }
                int resent = 0;
                for (Deferral deferral : loadDeferrals()) {
                    if (deferral.afterMs > nowMs) continue;
                    setMeta(DEFERRED_PREFIX + deferral.taskId, null);
                    if (!sendableTasks.containsKey(deferral.taskId)) continue;
                    insertAssignment(principal, TasksWire.newId(), deferral.taskId, sendableTasks.get(deferral.taskId), TasksWire.newId(),
                            deferral.recipientTgId, true, nowMs, deferral.replaces);
                    resent++;
                }
                return resent;
            }
        });
    }

    /** The entry is the automatic re-send of a deferred assignment ({@link #RESEND_PREFIX} names its mutation id). */
    private boolean automaticResend(Entry entry) {
        return entry.operation instanceof TasksWire.Operation.InvitationRequest
                && entry.mutationId.equals(meta(RESEND_PREFIX + ((TasksWire.Operation.InvitationRequest) entry.operation).taskId));
    }

    /** The entry is the automatic accept of an invitation ({@link #AUTO_ACCEPT_PREFIX} names its mutation id). */
    private boolean automaticAccept(Entry entry) {
        if (!(entry.operation instanceof TasksWire.Operation.InvitationRespond)) return false;
        AutoAcceptRecord record = AutoAcceptRecord.decode(meta(AUTO_ACCEPT_PREFIX + ((TasksWire.Operation.InvitationRespond) entry.operation).invitationId));
        return record != null && record.mutationId.equals(entry.mutationId);
    }

    /** 0: not an automatic entry; 1: the automatic re-send of a deferred assignment; 2: the automatic accept. */
    int automaticKind(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Integer>() {
            @Override public Integer run() {
                Entry entry = entry(mutationId);
                if (entry == null) return 0;
                return automaticResend(entry) ? 1 : automaticAccept(entry) ? 2 : 0;
            }
        });
    }

    /**
     * A definite refusal of an in-flight automatic entry: it goes without an Unsent Changes row and without a notice (the
     * user did nothing). A re-send loses its marker; an automatic accept records that the device gave up on the
     * invitation (attempts at the cap), so the invitation, if still pending, waits in Invitations.
     */
    boolean dropAutomatic(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || !SENDING.equals(entry.state)) return false;
                if (automaticResend(entry)) {
                    removeQuietly(entry);
                    setMeta(RESEND_PREFIX + ((TasksWire.Operation.InvitationRequest) entry.operation).taskId, null);
                    return true;
                }
                if (automaticAccept(entry)) {
                    String key = AUTO_ACCEPT_PREFIX + ((TasksWire.Operation.InvitationRespond) entry.operation).invitationId;
                    AutoAcceptRecord record = AutoAcceptRecord.decode(meta(key));
                    removeQuietly(entry);
                    setMeta(key, entry.mutationId + ":" + Math.max(record.attempts, TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS));
                    return true;
                }
                return false;
            }
        });
    }

    // -- the legacy annotation --

    /** {@code task.patch {set: {annotation: null}}} and nothing else: the owner-field clear of a legacy assignee note. */
    static boolean isAnnotationClear(TasksWire.Operation operation) {
        if (!(operation instanceof TasksWire.Operation.TaskPatch)) return false;
        Map<TasksWire.ContentField, Object> set = ((TasksWire.Operation.TaskPatch) operation).set;
        return set.size() == 1 && set.containsKey(TasksWire.ContentField.ANNOTATION) && set.get(TasksWire.ContentField.ANNOTATION) == null;
    }

    static TasksWire.Operation annotationClear(String taskId, TasksWire.Annotation shown) {
        LinkedHashMap<TasksWire.ContentField, Object> set = new LinkedHashMap<>(), expect = new LinkedHashMap<>();
        set.put(TasksWire.ContentField.ANNOTATION, null);
        expect.put(TasksWire.ContentField.ANNOTATION, shown);
        return TasksWire.Operation.taskPatch(taskId, set, expect);
    }

    /**
     * An in-flight annotation clear the server refused because the note changed elsewhere or the task is gone: dropped
     * quietly — there is nothing left to clear, or the task stays a per-task candidate on the other note. False when the
     * entry is not that.
     */
    boolean dropAnnotationClear(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || !SENDING.equals(entry.state) || !isAnnotationClear(entry.operation)) return false;
                removeQuietly(entry);
                return true;
            }
        });
    }

    /**
     * Inside the receipt's transaction, after an acknowledged invitation.request: its re-send marker (if it was one)
     * goes, and a task whose shadow still carries a legacy annotation gets the chained owner-field clear on the task's
     * chain — sealed at once (R4 server), never queued twice for one task, and never behind a delete of the task.
     */
    private void settleAssignmentReceipt(Entry entry, TasksWire.Operation.InvitationRequest request) {
        String resendKey = RESEND_PREFIX + request.taskId;
        if (entry.mutationId.equals(meta(resendKey))) setMeta(resendKey, null);
        if ("1".equals(meta(META_CONSENT_WITHDRAWN)) || !serverWire4()) return;
        TasksWire.Projection item = projection("shadow", "task", request.taskId);
        if (!(item instanceof TasksWire.TaskItem)) return;
        TasksWire.TaskItem task = (TasksWire.TaskItem) item;
        if (task.viewerRole != TasksWire.ViewerRole.OWNER || task.content.annotation == null) return;
        for (Object[] row : sql.query("SELECT mutation_id FROM outbox WHERE chain=? ORDER BY seq", entry.chain)) {
            Entry other = entry((String) row[0]);
            if (other.operation.kind == TasksWire.OperationKind.TASK_DELETE) return;
            if (other.open() && isAnnotationClear(other.operation)) return;
        }
        final TasksWire.Operation clear = annotationClear(request.taskId, task.content.annotation);
        insert(entry.principal, TasksWire.newId(), entry.chain, task.content.listId == null ? null : listChain(task.content.listId), null,
                false, null, new Builder() {
                    @Override public TasksWire.Operation build(String baseRevision) { return clear; }
                }, false, ORIGIN_USER, null, 0);
    }

    // -- the automatic accept --

    /** Every invitation's automatic attempts so far, by invitation id. */
    private Map<String, Integer> autoAcceptAttempts() {
        HashMap<String, Integer> attempts = new HashMap<>();
        for (String[] row : metaEntries(AUTO_ACCEPT_PREFIX)) {
            AutoAcceptRecord record = AutoAcceptRecord.decode(row[1]);
            if (record != null) attempts.put(row[0].substring(AUTO_ACCEPT_PREFIX.length()), record.attempts);
        }
        return attempts;
    }

    /**
     * The invitations whose automatic accept is queued, in flight, or acknowledged and still awaiting the server's proof:
     * the provisional rows. An entry the pull retired, or one the device gave up on, ends it.
     */
    Set<String> autoAcceptInvitationIds() {
        return sql.transaction(new TasksSql.Work<Set<String>>() {
            @Override public Set<String> run() {
                HashSet<String> ids = new HashSet<>();
                for (String[] row : metaEntries(AUTO_ACCEPT_PREFIX)) {
                    AutoAcceptRecord record = AutoAcceptRecord.decode(row[1]);
                    if (record == null) continue;
                    if (!sql.query("SELECT 1 FROM outbox WHERE mutation_id=? AND kind=?", record.mutationId,
                            TasksWire.OperationKind.INVITATION_RESPOND.wire()).isEmpty()) {
                        ids.add(row[0].substring(AUTO_ACCEPT_PREFIX.length()));
                    }
                }
                return ids;
            }
        });
    }

    /**
     * Rows that mean nothing any more go, once their entry has left the outbox: an acknowledged accept the pull retired
     * (attempts below the cap), and any row of an invitation that is no longer in the shadow. While the entry is still
     * in the outbox (queued, in backoff, in flight, acknowledged and not pulled) its row stays whatever the shadow says,
     * so its answer is still handled as the automatic entry it is. A row at the cap (given up) stays while its
     * invitation is pending, so the policy never tries that invitation again.
     */
    private void sweepAutoAccepts(Set<String> pendingInvitationIds) {
        for (String[] row : metaEntries(AUTO_ACCEPT_PREFIX)) {
            String invitationId = row[0].substring(AUTO_ACCEPT_PREFIX.length());
            AutoAcceptRecord record = AutoAcceptRecord.decode(row[1]);
            if (record == null) {
                setMeta(row[0], null);
                continue;
            }
            if (!sql.query("SELECT 1 FROM outbox WHERE mutation_id=?", record.mutationId).isEmpty()) continue;
            if (!pendingInvitationIds.contains(invitationId) || record.attempts < TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS) {
                setMeta(row[0], null);
            }
        }
    }

    /**
     * One transaction: the bookkeeping is swept, and every pending invitation of the shadow the contact rule accepts gets
     * its automatic {@code invitation.respond {accept}} — sealed on the invitation's task_revision — with its
     * {@link #AUTO_ACCEPT_PREFIX} row. Nothing else changes. Returns how many were queued.
     */
    int runAutoAccept(final String principal, final AutoAcceptDecider decider) {
        return sql.transaction(new TasksSql.Work<Integer>() {
            @Override public Integer run() {
                if ("1".equals(meta(META_CONSENT_WITHDRAWN))) return 0;
                ArrayList<TasksWire.InvitationItem> invitations = new ArrayList<>();
                HashSet<String> pendingIds = new HashSet<>();
                for (Object[] row : sql.query("SELECT json FROM shadow WHERE kind='invitation' ORDER BY id")) {
                    TasksWire.InvitationItem invitation = (TasksWire.InvitationItem) TasksWire.decodeProjection((String) row[0]);
                    invitations.add(invitation);
                    pendingIds.add(invitation.id);
                }
                sweepAutoAccepts(pendingIds);
                if (invitations.isEmpty()) return 0;
                // An answer is open until the pull proves it: a queued or in-flight entry, and an acknowledged one the
                // shadow has not confirmed yet (the invitation may still be listed in that gap; never answered twice).
                HashSet<String> unresolved = new HashSet<>(), blocking = new HashSet<>();
                for (Entry entry : loadEntries()) {
                    if (entry.operation instanceof TasksWire.Operation.InvitationRespond) {
                        unresolved.add(((TasksWire.Operation.InvitationRespond) entry.operation).invitationId);
                    } else if (entry.operation instanceof TasksWire.Operation.SenderBlock) {
                        blocking.add(((TasksWire.Operation.SenderBlock) entry.operation).invitationId);
                    }
                }
                // A block that is queued or not pulled yet names one invitation; it holds for every invitation of that
                // sender (the shadow's block row follows with the pull).
                HashSet<String> blockedOwners = new HashSet<>();
                for (TasksWire.InvitationItem invitation : invitations) {
                    if (blocking.contains(invitation.id)) blockedOwners.add(invitation.ownerTgId);
                }
                unresolved.addAll(blocking);
                for (TasksWire.InvitationItem invitation : invitations) {
                    if (blockedOwners.contains(invitation.ownerTgId)) unresolved.add(invitation.id);
                }
                Map<String, Integer> attempts = autoAcceptAttempts();
                // An automatic accept that never left the device is not final: when the rule no longer holds for its
                // invitation (the sender was blocked or is no contact any more, the user answered by hand, it expired)
                // it is withdrawn — the entry and its row go, and the invitation waits in Invitations.
                for (TasksWire.InvitationItem invitation : invitations) {
                    AutoAcceptRecord record = AutoAcceptRecord.decode(meta(AUTO_ACCEPT_PREFIX + invitation.id));
                    if (record == null) continue;
                    Entry queued = entry(record.mutationId);
                    if (queued == null || queued.sent || !(QUEUED.equals(queued.state) || BACKOFF.equals(queued.state))) continue;
                    HashSet<String> others = new HashSet<>(unresolved);
                    others.remove(invitation.id);
                    HashMap<String, Integer> before = new HashMap<>(attempts);
                    before.put(invitation.id, Math.max(0, record.attempts - 1));
                    if (decider.accepts(invitation, others, before)) continue;
                    removeQuietly(queued);
                    setMeta(AUTO_ACCEPT_PREFIX + invitation.id, null);
                    unresolved.remove(invitation.id);
                    attempts.remove(invitation.id);
                }
                int accepted = 0;
                for (final TasksWire.InvitationItem invitation : invitations) {
                    if (!decider.accepts(invitation, unresolved, attempts)) continue;
                    String mutationId = TasksWire.newId();
                    insert(principal, mutationId, taskChain(invitation.taskId), null, null, true, invitation.taskRevision, new Builder() {
                        @Override public TasksWire.Operation build(String baseRevision) {
                            return TasksWire.Operation.invitationRespond(invitation.taskId, baseRevision, invitation.id, TasksWire.Decision.ACCEPT);
                        }
                    }, false, ORIGIN_USER, null, 0);
                    Integer made = attempts.get(invitation.id);
                    setMeta(AUTO_ACCEPT_PREFIX + invitation.id, mutationId + ":" + ((made == null ? 0 : made) + 1));
                    unresolved.add(invitation.id);
                    accepted++;
                }
                return accepted;
            }
        });
    }

    /**
     * revision_changed on the automatic accept (never a manual answer): the server re-upserted the pending invitation
     * with the task's new revision, so it is resealed on that while the invitation is still pending here, unexpired and
     * under the cap; otherwise the device gives up quietly.
     */
    private TasksCloudAutoResolve.Decision autoAcceptResolution(Entry entry, AutoAcceptRecord record, long nowSeconds) {
        TasksWire.Operation.InvitationRespond respond = (TasksWire.Operation.InvitationRespond) entry.operation;
        TasksWire.Projection item = projection("shadow", "invitation", respond.invitationId);
        if (respond.decision != TasksWire.Decision.ACCEPT || record.attempts >= TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS
                || !(item instanceof TasksWire.InvitationItem)) {
            return TasksCloudAutoResolve.Decision.DROP;
        }
        TasksWire.InvitationItem invitation = (TasksWire.InvitationItem) item;
        if (!invitation.taskId.equals(respond.taskId) || TasksWire.epochSeconds(invitation.expiresAt) <= nowSeconds) {
            return TasksCloudAutoResolve.Decision.DROP;
        }
        return TasksCloudAutoResolve.Decision.replace(new TasksCloudAutoResolve.Replacement(
                TasksWire.Operation.invitationRespond(respond.taskId, invitation.taskRevision, respond.invitationId, TasksWire.Decision.ACCEPT), null, null));
    }

    // ---- explicit user actions ----

    /** Same id and bytes: creation refusal after eligibility repair, exhausted backoff, or waiting backoff. */
    boolean retry(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                return sql.update("UPDATE outbox SET state=?,attempts=0,not_before=0,error=NULL WHERE mutation_id=? AND state IN (?,?,?)",
                        QUEUED, mutationId, BLOCKED_CREATION, STALLED, BACKOFF) == 1;
            }
        });
    }

    /** Drops an unsent or terminal entry with its overlay. Never an in-flight or acknowledged one. */
    boolean discard(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry entry = entry(mutationId);
                if (entry == null || SENDING.equals(entry.state) || ACKED.equals(entry.state)) return false;
                dropDependents(entry);
                sql.update("DELETE FROM outbox WHERE mutation_id=?", mutationId);
                markOrphanedSuccessors();
                return true;
            }
        });
    }

    /** Explicit conflict resolution: drop the conflicted entry and seal its intent as a NEW id, one transaction. */
    void reapply(final String oldMutationId, final String principal, final String newMutationId, final String chain,
                 final String listKey, final String sourceListKey, final String explicitBase, final Builder builder) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                Entry old = entry(oldMutationId);
                if (old == null || !CONFLICT.equals(old.state)) throw new TasksCloudError(TasksCloudError.Code.BUSY_STATE);
                requireConsent(null);
                dropDependents(old);
                sql.update("DELETE FROM outbox WHERE mutation_id=?", oldMutationId);
                markOrphanedSuccessors();
                insert(principal, newMutationId, chain, listKey, sourceListKey, hasBase(old.operation.kind), explicitBase, builder, false,
                        old.origin, old.baseSnapshot, 0);
                return null;
            }
        });
    }

    static boolean hasBase(TasksWire.OperationKind kind) {
        return kind != TasksWire.OperationKind.LIST_CREATE && kind != TasksWire.OperationKind.TASK_CREATE
                && kind != TasksWire.OperationKind.SENDER_BLOCK && kind != TasksWire.OperationKind.SENDER_UNBLOCK
                && kind != TasksWire.OperationKind.TASK_PATCH;
    }

    /**
     * Later unsent work authored on top of a removed entry (same resource, its list, or a moved task's source list)
     * was decided against an optimistic view that no longer holds: e.g. a list.delete(A) queued after a since-discarded
     * move A->B would now cascade the task. Such dependents need explicit re-confirmation, never a silent send.
     */
    private void dropDependents(Entry removed) {
        for (Entry later : loadEntries()) {
            if (later.sent || !dependsOn(later, removed)) continue;
            if (!QUEUED.equals(later.state) && !BACKOFF.equals(later.state) && !HELD.equals(later.state)) continue;
            // The assignment of a task whose create is discarded has nothing left to assign: it goes with it.
            if (removed.operation.kind == TasksWire.OperationKind.TASK_CREATE && later.chain.equals(removed.chain)
                    && later.operation instanceof TasksWire.Operation.InvitationRequest) {
                sql.update("DELETE FROM outbox WHERE seq=?", later.seq);
                setMeta(RESEND_PREFIX + ((TasksWire.Operation.InvitationRequest) later.operation).taskId, null);
                continue;
            }
            setState(later.mutationId, CONFLICT, NOTICE_DEPENDENCY_DROPPED, null, 0);
            notice(NOTICE_DEPENDENCY_DROPPED, later.resourceId);
        }
    }

    /**
     * A provisional successor whose predecessor vanished can never resolve its base: explicit choice needed. A members
     * wait seals from the shadow instead, so it is never orphaned.
     */
    private void markOrphanedSuccessors() {
        List<Entry> all = loadEntries();
        Set<String> openChains = new HashSet<>();
        for (Entry entry : all) {
            if (!entry.sealed && entry.resolvedBase == null && !entry.waitsMembers
                    && (QUEUED.equals(entry.state) || BACKOFF.equals(entry.state) || HELD.equals(entry.state))
                    && !openChains.contains(entry.chain)) {
                setState(entry.mutationId, CONFLICT, NOTICE_DEPENDENCY_DROPPED, null, 0);
                notice(NOTICE_DEPENDENCY_DROPPED, entry.resourceId);
            }
            if (entry.open()) openChains.add(entry.chain);
        }
    }

    // ---- held delete ----

    boolean undoHeld(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                return sql.update("DELETE FROM outbox WHERE mutation_id=? AND state=?", mutationId, HELD) == 1;
            }
        });
    }

    /**
     * held -> queued exactly once. A task whose create was never sent is cancelled locally with its whole chain
     * (no network); a sent-but-unacknowledged create stays ahead of the delete in the chain.
     */
    boolean promoteHeld(final String mutationId) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Entry held = entry(mutationId);
                if (held == null || !HELD.equals(held.state)) return false;
                // Never sent, or definitively refused (403 creation / 422 invalid commit nothing): cancel both locally.
                List<Object[]> create = sql.query("SELECT sent,state FROM outbox WHERE chain=? AND kind=? AND state<>?",
                        held.chain, TasksWire.OperationKind.TASK_CREATE.wire(), ACKED);
                if (!create.isEmpty() && (((Long) create.get(0)[0]) == 0
                        || BLOCKED_CREATION.equals(create.get(0)[1]) || INVALID.equals(create.get(0)[1]))) {
                    sql.update("DELETE FROM outbox WHERE chain=?", held.chain);
                    return true;
                }
                return sql.update("UPDATE outbox SET state=? WHERE mutation_id=? AND state=?", QUEUED, mutationId, HELD) == 1;
            }
        });
    }

    List<String> heldIds() {
        return sql.transaction(new TasksSql.Work<List<String>>() {
            @Override public List<String> run() {
                ArrayList<String> ids = new ArrayList<>();
                for (Object[] row : sql.query("SELECT mutation_id FROM outbox WHERE state=? ORDER BY seq", HELD)) ids.add((String) row[0]);
                return ids;
            }
        });
    }

    // ---- automatic resolution and requeue (§7.3, §7.4; decisions in TasksCloudAutoResolve) ----

    /**
     * After a complete pull, in one transaction: every conflict marked for automatic resolution is resolved against the
     * fresh shadow (TasksCloudAutoResolve). Returns the tasks.sync_issue kinds to emit, one per outcome.
     */
    List<String> resolvePendingConflicts(final String principal, final long nowSeconds) {
        return sql.transaction(new TasksSql.Work<List<String>>() {
            @Override public List<String> run() {
                ArrayList<String> issues = new ArrayList<>();
                for (Entry entry : loadEntries()) {
                    if (!CONFLICT.equals(entry.state) || !entry.autoResolvePending || !entry.principal.equals(principal)) continue;
                    // The automatic re-send of a deferred assignment keeps its marker through a reseal (the new mutation
                    // id) and loses it on a drop; the automatic accept's record follows a reseal (one more attempt) and
                    // marks the invitation given up on a drop (attempts at the cap).
                    String resendKey = null;
                    if (entry.operation instanceof TasksWire.Operation.InvitationRequest) {
                        String key = RESEND_PREFIX + ((TasksWire.Operation.InvitationRequest) entry.operation).taskId;
                        if (entry.mutationId.equals(meta(key))) resendKey = key;
                    }
                    String acceptKey = null;
                    AutoAcceptRecord acceptRecord = null;
                    if (entry.operation instanceof TasksWire.Operation.InvitationRespond) {
                        String key = AUTO_ACCEPT_PREFIX + ((TasksWire.Operation.InvitationRespond) entry.operation).invitationId;
                        AutoAcceptRecord record = AutoAcceptRecord.decode(meta(key));
                        if (record != null && record.mutationId.equals(entry.mutationId)) {
                            acceptKey = key;
                            acceptRecord = record;
                        }
                    }
                    TasksCloudAutoResolve.Decision decision = acceptRecord != null ? autoAcceptResolution(entry, acceptRecord, nowSeconds)
                            : TasksCloudAutoResolve.resolve(entry, shadowTaskItem(entry), shadowListItem(entry), nowSeconds, resendKey != null);
                    switch (decision.kind) {
                        case DROP:
                            sql.update("DELETE FROM outbox WHERE seq=?", entry.seq);
                            // The next entry of the chain may have waited unsealed for this one's receipt (a pick made
                            // right after a Remove): it seals on the chain's known revision instead of waiting forever.
                            resolveSuccessorBase(entry.chain);
                            if (decision.deleteKept) notice(NOTICE_DELETE_KEPT, entry.resourceId);
                            if (resendKey != null) setMeta(resendKey, null);
                            if (acceptKey != null) {
                                setMeta(acceptKey, entry.mutationId + ":" + Math.max(acceptRecord.attempts, TasksAutoAcceptPolicy.MAXIMUM_ATTEMPTS));
                            }
                            issues.add(TasksAnalyticsSink.ISSUE_CONFLICT_AUTO_RESOLVED);
                            break;
                        case REPLACE: {
                            for (int i = 0; i < decision.replacements.size(); i++) {
                                TasksCloudAutoResolve.Replacement replacement = decision.replacements.get(i);
                                String state = replacement.conflictFields == null ? QUEUED : CONFLICT;
                                TasksWire.ConflictReason reason = replacement.conflictFields == null ? null : TasksWire.ConflictReason.FIELD_CHANGED;
                                if (i == 0) {
                                    String resealedId = TasksWire.newId();
                                    replace(entry, resealedId, replacement.operation, state, reason, replacement.conflictFields,
                                            entry.autoResolutions + 1, replacement.baseSnapshot);
                                    if (resendKey != null) setMeta(resendKey, resealedId);
                                    if (acceptKey != null) setMeta(acceptKey, resealedId + ":" + (acceptRecord.attempts + 1));
                                } else {
                                    // A split's explicit part follows its automatic part.
                                    String id = TasksWire.newId();
                                    byte[] body = TasksWire.Mutation.seal(entry.principal, id, replacement.operation).body();
                                    sql.exec("INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,list_key,source_list_key,body,sealed,state,error,reason,origin,auto_resolutions,conflict_fields) VALUES(?,?,?,?,?,?,?,?,1,?,?,?,?,?,?)",
                                            id, entry.principal, replacement.operation.kind.wire(), replacement.operation.resourceId(), entry.chain,
                                            entry.listKey, entry.sourceListKey, body, state, reason == null ? null : "tasks_conflict",
                                            reason == null ? null : reason.wire(), entry.origin, (long) entry.autoResolutions + 1,
                                            fieldsText(replacement.conflictFields));
                                }
                                issues.add(replacement.conflictFields == null ? TasksAnalyticsSink.ISSUE_CONFLICT_AUTO_RESOLVED
                                        : TasksAnalyticsSink.ISSUE_CONFLICT_MANUAL);
                            }
                            break;
                        }
                        default:
                            sql.update("UPDATE outbox SET auto_resolve=NULL WHERE seq=?", entry.seq);
                            issues.add(TasksAnalyticsSink.ISSUE_CONFLICT_MANUAL);
                            break;
                    }
                }
                return issues;
            }
        });
    }

    private TasksWire.TaskItem shadowTaskItem(Entry entry) {
        if (!entry.chain.startsWith("task:")) return null;
        TasksWire.Projection item = projection("shadow", "task", entry.chain.substring(5));
        return item instanceof TasksWire.TaskItem ? (TasksWire.TaskItem) item : null;
    }

    private TasksWire.ListItem shadowListItem(Entry entry) {
        if (!entry.chain.startsWith("list:")) return null;
        TasksWire.Projection item = projection("shadow", "list", entry.chain.substring(5));
        return item instanceof TasksWire.ListItem ? (TasksWire.ListItem) item : null;
    }

    /**
     * §7.4: STALLED (when {@code stalled}) and BLOCKED_CREATION (when {@code blocked}, while creation is allowed locally
     * or for a migration create) back to QUEUED with the same identity and attempts reset, at most
     * {@link TasksCloudPolicy#MAX_AUTO_REQUEUES} times per entry. Returns {stalled, blocked} requeued.
     */
    int[] autoRequeue(final boolean stalled, final boolean blocked, final boolean mayCreate) {
        return sql.transaction(new TasksSql.Work<int[]>() {
            @Override public int[] run() {
                int[] requeued = new int[2];
                String set = "UPDATE outbox SET state=?,attempts=0,not_before=0,error=NULL,auto_requeues=auto_requeues+1 WHERE state=? AND auto_requeues<?";
                long max = TasksCloudPolicy.MAX_AUTO_REQUEUES;
                if (stalled) requeued[0] = sql.update(set, QUEUED, STALLED, max);
                if (blocked) {
                    requeued[1] = mayCreate ? sql.update(set, QUEUED, BLOCKED_CREATION, max)
                            : sql.update(set + " AND origin=?", QUEUED, BLOCKED_CREATION, max, ORIGIN_MIGRATION);
                }
                return requeued;
            }
        });
    }

    /**
     * Once per database, in one transaction: an older build marked every entry of a multi-item batch INVALID when a 422
     * refused the batch as a whole (e.g. one migration create a fast device clock put in the server's future). A 422
     * never names its item, so every entry a 422 left INVALID goes back to the queue once, same id and bytes, the refused
     * attempt taken back; the engine splits a batch refused again, so an entry refused alone stays INVALID for good.
     * Returns the requeued ids (none after the first run).
     */
    List<String> repairBatchRefusals() {
        return sql.transaction(new TasksSql.Work<List<String>>() {
            @Override public List<String> run() {
                ArrayList<String> ids = new ArrayList<>();
                if (meta(META_BATCH_REFUSALS_REPAIRED) != null) return ids;
                for (Object[] row : sql.query("SELECT mutation_id FROM outbox WHERE state=? AND error=? ORDER BY seq", INVALID, "tasks_invalid_request")) {
                    ids.add((String) row[0]);
                }
                for (String id : ids) {
                    sql.update("UPDATE outbox SET state=?,error=NULL,reason=NULL,not_before=0,attempts=CASE WHEN attempts>0 THEN attempts-1 ELSE 0 END WHERE mutation_id=? AND state=?",
                            QUEUED, id, INVALID);
                }
                setMeta(META_BATCH_REFUSALS_REPAIRED, "1");
                return ids;
            }
        });
    }

    // ---- batch route bookkeeping (§7.2) ----

    int batchBudget() {
        return sql.transaction(new TasksSql.Work<Integer>() {
            @Override public Integer run() { return budget(); }
        });
    }

    private int budget() {
        long stored = longMeta(META_BATCH_BYTE_BUDGET, TasksCloudPolicy.BATCH_INITIAL_BYTES);
        return stored < TasksCloudPolicy.BATCH_MIN_BYTES || stored > TasksCloudPolicy.BATCH_MAX_BYTES
                ? TasksCloudPolicy.BATCH_INITIAL_BYTES : (int) stored;
    }

    /** A timeout, an outcome-unknown answer or a 413: half the budget, the success streak restarts. */
    void shrinkBatch() {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                setLongMeta(META_BATCH_BYTE_BUDGET, TasksCloudPolicy.shrinkBatch(budget()));
                setMeta(META_BATCH_SUCCESS_STREAK, null);
                return null;
            }
        });
    }

    /** A batch whose every item was processed (none skipped): three in a row double the budget. */
    void noteFullBatch() {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                long streak = longMeta(META_BATCH_SUCCESS_STREAK, 0) + 1;
                if (streak >= TasksCloudPolicy.BATCH_SUCCESSES_TO_GROW) {
                    setLongMeta(META_BATCH_BYTE_BUDGET, TasksCloudPolicy.growBatch(budget()));
                    setMeta(META_BATCH_SUCCESS_STREAK, null);
                } else {
                    setLongMeta(META_BATCH_SUCCESS_STREAK, streak);
                }
                return null;
            }
        });
    }

    void resetBatchStreak() {
        writeMeta(META_BATCH_SUCCESS_STREAK, null);
    }

    /** A batch answered 200: the 503 streak restarts. */
    void clearBatch503() {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                if (meta(META_BATCH_503_STREAK) != null) setMeta(META_BATCH_503_STREAK, null);
                return null;
            }
        });
    }

    /** One more whole-batch 503; the third in a row opens the single-route fallback. Returns whether it did. */
    boolean noteBatch503(final long now) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                long streak = longMeta(META_BATCH_503_STREAK, 0) + 1;
                if (streak >= TasksCloudPolicy.BATCH_503S_TO_FALL_BACK) {
                    openFallback(now);
                    return true;
                }
                setLongMeta(META_BATCH_503_STREAK, streak);
                return false;
            }
        });
    }

    /** 404 / batch_disabled / three 503s: user entries take the single route for 15 minutes; migration entries wait. */
    void openBatchFallback(final long now) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() { openFallback(now); return null; }
        });
    }

    private void openFallback(long now) {
        setLongMeta(META_BATCH_UNAVAILABLE_UNTIL, now + TasksCloudPolicy.BATCH_UNAVAILABLE_MS);
        setMeta(META_BATCH_503_STREAK, null);
        setMeta(META_BATCH_SUCCESS_STREAK, null);
    }

    boolean batchRouteUnavailable(long now) {
        return readLong(META_BATCH_UNAVAILABLE_UNTIL, 0) > now;
    }

    /**
     * Holds every entry of {@code origin} until {@code until} (never shortened): migration_paused / a migration item's
     * retry_after, or a user item's retry_after (a batch's own retry_after names one origin's bucket).
     */
    void holdOrigin(final String origin, final long until) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                String key = ORIGIN_MIGRATION.equals(origin) ? META_MIGRATION_PAUSED_UNTIL : META_USER_HOLD_UNTIL;
                if (until > longMeta(key, 0)) setLongMeta(key, until);
                return null;
            }
        });
    }

    /** The earliest time a held origin, the batch outage or the consent fence lets something go again (0: none ahead). */
    long nextHoldEnd(final long now) {
        return sql.transaction(new TasksSql.Work<Long>() {
            @Override public Long run() {
                long earliest = 0;
                for (String key : Arrays.asList(META_USER_HOLD_UNTIL, META_MIGRATION_PAUSED_UNTIL, META_BATCH_UNAVAILABLE_UNTIL)) {
                    long until = longMeta(key, 0);
                    if (until > now && (earliest == 0 || until < earliest)) earliest = until;
                }
                return earliest;
            }
        });
    }

    // ---- import provenance (§6.4) ----

    List<String> pendingImportAcks() {
        return sql.transaction(new TasksSql.Work<List<String>>() {
            @Override public List<String> run() {
                ArrayList<String> ids = new ArrayList<>();
                for (Object[] row : sql.query("SELECT mutation_id FROM import_ack_pending ORDER BY mutation_id")) ids.add((String) row[0]);
                return ids;
            }
        });
    }

    void removeImportAcks(final List<String> mutationIds) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                for (String id : mutationIds) sql.update("DELETE FROM import_ack_pending WHERE mutation_id=?", id);
                return null;
            }
        });
    }

    // ---- sync: staged snapshot, catch-up, incremental changes ----

    Staging staging() {
        return sql.transaction(new TasksSql.Work<Staging>() {
            @Override public Staging run() { return loadStaging(); }
        });
    }

    private Staging loadStaging() {
        List<Object[]> rows = sql.query("SELECT phase,cursor,changes_cursor,page_limit FROM staging");
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        return new Staging("catchup".equals(row[0]), (String) row[1], (String) row[2], ((Long) row[3]).intValue());
    }

    /** Begins (or restarts) a staged snapshot; the current shadow stays visible until reconciliation. */
    void startSnapshot(final int limit) {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() {
                sql.update("DELETE FROM staged");
                sql.update("DELETE FROM staging");
                sql.exec("INSERT INTO staging(singleton,phase,cursor,changes_cursor,page_limit) VALUES(1,'pages',NULL,NULL,?)", (long) limit);
                return null;
            }
        });
    }

    /** One page + cursor advance atomically. A reply for a different cursor than requested is ignored. */
    boolean applySnapshotPage(final String requestedCursor, final TasksWire.SnapshotResponse page) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Staging staging = loadStaging();
                if (staging == null || staging.catchup || !same(staging.cursor, requestedCursor)) return false;
                for (TasksWire.Projection item : page.items) put("staged", item);
                String changesCursor = staging.changesCursor != null ? staging.changesCursor : page.changesCursor;
                if (page.nextCursor == null) {
                    sql.update("UPDATE staging SET phase='catchup',cursor=?,changes_cursor=?", changesCursor, changesCursor);
                } else {
                    sql.update("UPDATE staging SET cursor=?,changes_cursor=?", page.nextCursor, changesCursor);
                }
                setMeta(META_SERVER_TIME, page.serverTime);
                return true;
            }
        });
    }

    /** Post-S delta onto the staged copy; on exhaustion replaces the shadow in the same transaction. */
    boolean applyCatchupPage(final String requestedCursor, final TasksWire.ChangesResponse page) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                Staging staging = loadStaging();
                if (staging == null || !staging.catchup || !staging.cursor.equals(requestedCursor)) return false;
                for (TasksWire.Change change : page.changes) applyChange("staged", change, false);
                setMeta(META_SERVER_TIME, page.serverTime);
                if (page.hasMore) {
                    sql.update("UPDATE staging SET cursor=?", page.nextCursor);
                    return false;
                }
                reconcile(page.nextCursor);
                return true;
            }
        });
    }

    private void reconcile(String changesCursor) {
        Set<String> sharedBefore = new HashSet<>();
        for (TasksWire.Projection item : projections("shadow")) {
            if (item instanceof TasksWire.TaskItem && ((TasksWire.TaskItem) item).viewerRole == TasksWire.ViewerRole.ASSIGNEE) sharedBefore.add(item.id);
        }
        for (String taskId : sharedBefore) {
            TasksWire.Projection now = projection("staged", "task", taskId);
            if (!(now instanceof TasksWire.TaskItem) || ((TasksWire.TaskItem) now).viewerRole != TasksWire.ViewerRole.ASSIGNEE) {
                boolean left = leftVoluntarily(taskId);
                sql.update("DELETE FROM outbox WHERE chain=?", taskChain(taskId));
                if (!left) notice(NOTICE_ACCESS_LOST, taskId);
            }
        }
        sql.update("DELETE FROM shadow");
        sql.exec("INSERT INTO shadow(kind,id,json) SELECT kind,id,json FROM staged");
        sql.update("DELETE FROM staged");
        sql.update("DELETE FROM staging");
        setMeta(META_CHANGES_CURSOR, changesCursor);
        setMeta(META_SHADOW_COMPLETE, "1");
        setLongMeta(META_SYNC_CYCLE, longMeta(META_SYNC_CYCLE, 0) + 1);
        settleReceipts(true, null);
    }

    /** Incremental page + cursor atomically. Returns true when this cycle is caught up (has_more=false). */
    boolean applyChangesPage(final String requestedCursor, final TasksWire.ChangesResponse page) {
        return sql.transaction(new TasksSql.Work<Boolean>() {
            @Override public Boolean run() {
                if (loadStaging() != null || !requestedCursor.equals(meta(META_CHANGES_CURSOR))) return false;
                Set<String> touchedKeys = new HashSet<>();
                for (TasksWire.Change change : page.changes) {
                    applyChange("shadow", change, true);
                    touchedKeys.add(change.kind.wire() + ":" + change.id);
                }
                setMeta(META_CHANGES_CURSOR, page.nextCursor);
                setMeta(META_SERVER_TIME, page.serverTime);
                if (!page.hasMore) setLongMeta(META_SYNC_CYCLE, longMeta(META_SYNC_CYCLE, 0) + 1);
                // Mid-cycle only the receipts this page can settle; every receipt (and the phantom rule) at the cycle's end.
                settleReceipts(false, page.hasMore ? touchedKeys : null);
                return !page.hasMore;
            }
        });
    }

    /** tasks_resync_required: keep shadow and outbox, forget the rejected cursor, restage from scratch. */
    void requireResnapshot() {
        sql.transaction(new TasksSql.Work<Void>() {
            @Override public Void run() { resnapshot(); return null; }
        });
    }

    /** resync_generation counts the resnapshots (TR4-A3's resend-once rule for acknowledged import rows). */
    private void resnapshot() {
        setMeta(META_CHANGES_CURSOR, null);
        sql.update("DELETE FROM staged");
        sql.update("DELETE FROM staging");
        setLongMeta(META_RESYNC_GENERATION, longMeta(META_RESYNC_GENERATION, 0) + 1);
    }

    private void applyChange(String table, TasksWire.Change change, boolean live) {
        String kind = change.kind.wire();
        if (change.action == TasksWire.ChangeAction.UPSERT) {
            if (live && change.item instanceof TasksWire.TaskItem && isAssignee(change.item.id)
                    && ((TasksWire.TaskItem) change.item).viewerRole != TasksWire.ViewerRole.ASSIGNEE) {
                removeSharedTask(change.item.id);
            }
            put(table, change.item);
            return;
        }
        if (live && "task".equals(kind) && isAssignee(change.id)) {
            removeSharedTask(change.id);
            return;
        }
        sql.update("DELETE FROM " + table + " WHERE kind=? AND id=?", kind, change.id);
        if (live && ("task".equals(kind) || "list".equals(kind))) {
            // Authoritative removal: our acknowledged receipts for it are settled.
            sql.update("DELETE FROM outbox WHERE chain=? AND state=?", kind + ":" + change.id, ACKED);
        }
    }

    private void put(String table, TasksWire.Projection item) {
        sql.exec("INSERT OR REPLACE INTO " + table + "(kind,id,json) VALUES(?,?,?)", item.kind.wire(), item.id, TasksWire.encodeProjection(item));
    }

    /**
     * Releases receipt overlays once the shadow itself shows the committed effect; never by a cycle count, because a
     * coalesced row may move beyond H on every cycle (x-sync.changes). Settled by: list/task revision &gt;= receipt
     * revision; an authoritative live removal (applyChange); an absent resource for an acknowledged delete; the block
     * row present (sender.block) / absent (sender.unblock); the responded invitation absent (decline). A reconciled
     * snapshot is authoritative coverage: every receipt predates its watermark S (no mutation is sent while staging),
     * so a still-existing row is in the pages or the post-S delta, and anything absent from it is gone.
     * <p>
     * R4 phantom settle (server_wire 4 only): an acknowledged create whose resource the shadow still lacks once a
     * complete changes cycle after its {@code acked_cycle} ended was removed in the cloud (e.g. a durable replay of a
     * since-deleted resource, which no feed entry will ever announce): settled with a REMOVED_IN_CLOUD notice.
     *
     * @param touchedKeys mid-cycle: only receipts of these "kind:id" keys are examined; null: every receipt
     */
    private void settleReceipts(boolean authoritativeSnapshot, Set<String> touchedKeys) {
        boolean phantoms = touchedKeys == null && serverWire4();
        long cycle = longMeta(META_SYNC_CYCLE, 0);
        for (Entry entry : loadEntries()) {
            if (!ACKED.equals(entry.state)) continue;
            if (authoritativeSnapshot) {
                sql.update("DELETE FROM outbox WHERE mutation_id=? AND state=?", entry.mutationId, ACKED);
                continue;
            }
            if (touchedKeys != null && !touchedKeys.contains(entry.chain) && !touchedKeys.contains(settleKey(entry))) continue;
            if (settled(entry)) {
                sql.update("DELETE FROM outbox WHERE mutation_id=? AND state=?", entry.mutationId, ACKED);
            } else if (phantoms && entry.operation.kind.isCreate() && entry.ackedCycle != null && cycle > entry.ackedCycle
                    && shadowRevision(entry.chain) == null) {
                sql.update("DELETE FROM outbox WHERE mutation_id=? AND state=?", entry.mutationId, ACKED);
                notice(NOTICE_REMOVED_IN_CLOUD, entry.resourceId);
            }
        }
    }

    /** The shadow row other than its own chain that settles a receipt (block row, responded invitation), or null. */
    private static String settleKey(Entry entry) {
        switch (entry.operation.kind) {
            case SENDER_BLOCK:
            case SENDER_UNBLOCK:
                return entry.ackedResource == null ? null : "block:" + entry.ackedResource;
            case INVITATION_RESPOND:
                return "invitation:" + ((TasksWire.Operation.InvitationRespond) entry.operation).invitationId;
            default:
                return null;
        }
    }

    private boolean settled(Entry entry) {
        switch (entry.operation.kind) {
            case SENDER_BLOCK:
                return projection("shadow", "block", entry.ackedResource) != null;
            case SENDER_UNBLOCK:
                return projection("shadow", "block", entry.ackedResource) == null;
            case INVITATION_RESPOND: {
                TasksWire.Operation.InvitationRespond respond = (TasksWire.Operation.InvitationRespond) entry.operation;
                if (respond.decision == TasksWire.Decision.DECLINE) {
                    return projection("shadow", "invitation", respond.invitationId) == null;
                }
                break;
            }
            default:
                break;
        }
        String current = shadowRevision(entry.chain);
        if (entry.ackedRevision == null) return false; // kept until an authoritative snapshot
        if (current != null) return Long.parseLong(current) >= Long.parseLong(entry.ackedRevision);
        TasksWire.OperationKind kind = entry.operation.kind;
        return kind == TasksWire.OperationKind.TASK_DELETE || kind == TasksWire.OperationKind.LIST_DELETE;
    }

    private static boolean isListOrTaskChain(String chain) {
        return chain.startsWith("list:") || chain.startsWith("task:");
    }

    /** Synced shadow revision of a list/task chain (null when absent or not a list/task chain). */
    private String shadowRevision(String chain) {
        if (!isListOrTaskChain(chain)) return null;
        String[] parts = chain.split(":", 2);
        TasksWire.Projection item = projection("shadow", parts[0], parts[1]);
        if (item instanceof TasksWire.ListItem) return ((TasksWire.ListItem) item).revision;
        if (item instanceof TasksWire.TaskItem) return ((TasksWire.TaskItem) item).revision;
        return null;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** conflict_fields storage: wire names in content order, comma separated; null for none. */
    static String fieldsText(List<TasksWire.ContentField> fields) {
        if (fields == null || fields.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (TasksWire.ContentField field : fields) {
            if (out.length() > 0) out.append(',');
            out.append(field.wire());
        }
        return out.toString();
    }

    private static List<TasksWire.ContentField> fields(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        ArrayList<TasksWire.ContentField> fields = new ArrayList<>();
        for (String name : text.split(",")) fields.add(TasksWire.ContentField.fromWire(name));
        return Collections.unmodifiableList(fields);
    }

    /** Rebuilds an unsent provisional operation with its resolved base revision (business payload otherwise unchanged). */
    static TasksWire.Operation withBase(TasksWire.Operation operation, String base) {
        switch (operation.kind) {
            case LIST_UPDATE: {
                TasksWire.Operation.ListUpdate op = (TasksWire.Operation.ListUpdate) operation;
                return TasksWire.Operation.listUpdate(op.listId, base, op.title, op.sortOrder);
            }
            case LIST_DELETE:
                return TasksWire.Operation.listDelete(((TasksWire.Operation.ListDelete) operation).listId, base);
            case TASK_UPDATE: {
                TasksWire.Operation.TaskUpdate op = (TasksWire.Operation.TaskUpdate) operation;
                return TasksWire.Operation.taskUpdate(op.taskId, base, op.content);
            }
            case TASK_COMPLETE: {
                TasksWire.Operation.TaskComplete op = (TasksWire.Operation.TaskComplete) operation;
                return TasksWire.Operation.taskComplete(op.taskId, base, op.invitationId, op.isCompleted);
            }
            case TASK_DELETE:
                return TasksWire.Operation.taskDelete(((TasksWire.Operation.TaskDelete) operation).taskId, base);
            case INVITATION_REQUEST: {
                TasksWire.Operation.InvitationRequest op = (TasksWire.Operation.InvitationRequest) operation;
                return TasksWire.Operation.invitationRequest(op.taskId, base, op.invitationId, op.recipientTgId);
            }
            case INVITATION_RESPOND: {
                TasksWire.Operation.InvitationRespond op = (TasksWire.Operation.InvitationRespond) operation;
                return TasksWire.Operation.invitationRespond(op.taskId, base, op.invitationId, op.decision);
            }
            case INVITATION_REVOKE: {
                TasksWire.Operation.InvitationRevoke op = (TasksWire.Operation.InvitationRevoke) operation;
                return TasksWire.Operation.invitationRevoke(op.taskId, base, op.invitationId);
            }
            default:
                throw new IllegalStateException("operation_has_no_base");
        }
    }
}
