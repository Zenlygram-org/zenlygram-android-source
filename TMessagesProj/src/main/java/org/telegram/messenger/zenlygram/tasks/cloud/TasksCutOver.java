package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * Cut-over of one account's local (device) Tasks board, design 30 §7.5: pure, the same table as iOS SGTasksCutOver
 * without its device-owner row (Android migrates each per-account database into its own account). Invariant I1: the
 * local board is read-only only while a copy of its rows exists in this account's cloud store (its outbox/overlay
 * from {@code enqueued} on), which the user can open on this device and edit offline. The sync owner (TR4-A3)
 * recomputes it on every input change; the router, the local board and the write fence read the result.
 *
 * <p>Like iOS, the input {@code ownCopyOnDevice} carries I1 for row 4: a Telegram logout purges the account's cloud store
 * (TasksCloudRepository.onLogout) but keeps its record and mappings (ZenlyTasksStore), so after a re-login the record
 * alone no longer proves the copy is here. The board is then never hidden ({@code RETIRED}); it stays a readable archive
 * until the account's next complete snapshot (or the step's re-enqueue) brings the copy back and, for a confirmed copy,
 * until the migration step has checked it against that store at the store's resync generation (a new store, a 409
 * resync since the completion, a rebind to another principal: TasksAutoMigration re-checks it first).
 */
public final class TasksCutOver {
    public enum Kind {
        /** The local board is writable as before R4; the default scope is the stored choice. */
        NONE,
        /** The local board is a read-only archive; the default scope is Cloud. */
        ARCHIVE,
        /** Every local row is confirmed in Cloud: the local board is hidden (always Cloud). */
        RETIRED
    }

    /** Why the board is archived. */
    public enum Reason {
        /**
         * The copy is enqueued and not confirmed yet (progress; the copies are editable in Cloud, offline too). Also a
         * confirmed copy whose account store is not on this device (yet): a logout purged it, or it does not open; and a
         * confirmed copy the migration step has not yet checked against this store: a new store, a 409 since, a rebind.
         */
        MIGRATING,
        /** The copy is confirmed, but {@link #newRows} local rows are not in Cloud ("Copy to Cloud"). */
        NEW_ROWS
    }

    /** The account's migration record (ZenlyTasksStore cloud_migration.state, TR4-A3); null input = no record. */
    public enum Record {
        CLAIMED("claimed"), ENQUEUED("enqueued"), COMPLETED("completed"), RELEASED("released"), SKIPPED("skipped");
        private final String wire;
        Record(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        /** null for null or an unknown value (treated as "no record": row 7, writable). */
        public static Record fromWire(String value) {
            for (Record record : values()) if (record.wire.equals(value)) return record;
            return null;
        }
    }

    /** What the account's cloud store on this device holds (TasksCloudEngine.cutOverStoreFacts, via the repository). */
    public static final class StoreFacts {
        /**
         * The import scope of the store's bound server account; null while the store is unbound (one created after a
         * logout purge, before its first linked cycle).
         */
        public final String boundScope;
        /** The store holds a complete snapshot of that server account. */
        public final boolean completeSnapshot;
        /** Unsent migration creates in the store's outbox. */
        public final int pendingMigrationCreates;
        /**
         * The migration step has checked this store (cloud meta migration_checked): false for a new store and after a
         * rebind to another principal.
         */
        public final boolean migrationChecked;
        /** The store's resync generation (cloud meta resync_generation, 0 when absent). */
        public final long resyncGeneration;

        public StoreFacts(String boundScope, boolean completeSnapshot, int pendingMigrationCreates, boolean migrationChecked,
                          long resyncGeneration) {
            this.boundScope = boundScope;
            this.completeSnapshot = completeSnapshot;
            this.pendingMigrationCreates = pendingMigrationCreates;
            this.migrationChecked = migrationChecked;
            this.resyncGeneration = resyncGeneration;
        }
    }

    /** Reads the store's facts (it may open the account's repository); null when the store does not open. */
    public interface StoreReader { StoreFacts read(); }

    /**
     * I1 for rows 3–5 (iOS TasksCutOver.isOwnCopyOnDevice): the copy of an {@code enqueued}/{@code completed} record is on
     * this device when the account's store is bound to the record's scope and holds either a complete snapshot of it (the
     * confirmed rows) or the copy's queued creates (editable in Cloud, offline too). Creates queued under another server
     * account are not this copy; a store that does not open (null) holds none. A {@code completed} copy counts only once
     * the migration step has checked it against this store at the store's resync generation: the store is checked and
     * {@code verifiedGeneration} (the record's) is not older, the complement of the step's re-check (TasksAutoMigration).
     * A new store, a voided verification (-1) and a 409 since the completion read as "not here".
     */
    public static boolean ownCopyOnDevice(Record record, String recordScope, long verifiedGeneration, StoreFacts store) {
        if ((record != Record.ENQUEUED && record != Record.COMPLETED) || recordScope == null || store == null
                || !recordScope.equals(store.boundScope)) {
            return false;
        }
        if (record == Record.COMPLETED && (!store.migrationChecked || verifiedGeneration < store.resyncGeneration)) return false;
        return store.completeSnapshot || store.pendingMigrationCreates > 0;
    }

    /**
     * I1 as the sync owner reads it: only an account with consent (P WAITING/ALLOWED) and a copy
     * ({@code enqueued}/{@code completed}) has one to look for, and only then does {@code store} run.
     * {@code verifiedGeneration} is the record's ({@link #ownCopyOnDevice(Record, String, long, StoreFacts)}).
     */
    public static boolean ownCopyOnDevice(TasksCloudGate.Permission permission, Record record, String recordScope, long verifiedGeneration,
                                          StoreReader store) {
        if (permission != TasksCloudGate.Permission.WAITING && permission != TasksCloudGate.Permission.ALLOWED) return false;
        if (record != Record.ENQUEUED && record != Record.COMPLETED) return false;
        return ownCopyOnDevice(record, recordScope, verifiedGeneration, store == null ? null : store.read());
    }

    /**
     * The Device archive's progress line (design 30 §6.6; iOS {@code recordState != .completed && copyOnDevice}): only for
     * an enqueued copy whose creates or confirmed snapshot are in this account's store ({@code ownCopyOnDevice}, I1). A
     * completed copy is not being copied, and a store a logout purged would read "N of N".
     */
    public static boolean archiveShowsProgress(TasksCutOver cutOver, Record record, boolean ownCopyOnDevice) {
        return cutOver != null && cutOver.kind == Kind.ARCHIVE && cutOver.reason == Reason.MIGRATING && record == Record.ENQUEUED
                && ownCopyOnDevice;
    }

    /**
     * The archive's copy is not in this account's store on this device (iOS {@code awaitsCloudCopy}): a logout purged it,
     * the store does not open, or the step has not checked a confirmed copy there yet. The banner then says that the Cloud
     * copy returns once the account is confirmed and Cloud has synced.
     */
    public static boolean archiveAwaitsCopy(TasksCutOver cutOver, boolean ownCopyOnDevice) {
        return cutOver != null && cutOver.kind == Kind.ARCHIVE && cutOver.reason == Reason.MIGRATING && !ownCopyOnDevice;
    }

    public static final TasksCutOver NONE = new TasksCutOver(Kind.NONE, null, 0);
    public static final TasksCutOver RETIRED = new TasksCutOver(Kind.RETIRED, null, 0);
    private static final TasksCutOver MIGRATING = new TasksCutOver(Kind.ARCHIVE, Reason.MIGRATING, 0);

    public final Kind kind;
    /** ARCHIVE only; null otherwise. */
    public final Reason reason;
    /** NEW_ROWS only: the live local rows without a mapping of the record's scope; 0 otherwise. */
    public final int newRows;

    private TasksCutOver(Kind kind, Reason reason, int newRows) {
        this.kind = kind;
        this.reason = reason;
        this.newRows = newRows;
    }

    /** Checked local writes of the account are refused (ZenlyTasksStore WriteState ARCHIVED). */
    public boolean isReadOnly() {
        return kind != Kind.NONE;
    }

    /** The Tasks entry opens the cloud board (ARCHIVE or RETIRED). */
    public boolean defaultsToCloud() {
        return kind != Kind.NONE;
    }

    /**
     * {@link #evaluate(TasksCloudGate.Permission, Record, String, String, int, boolean)} with the copy on this device: for
     * callers that model a bound store holding it (host tables, the router's rows). The sync owner always passes I1.
     */
    public static TasksCutOver evaluate(TasksCloudGate.Permission permission, Record record, String recordScope, String currentScope,
                                        int unmappedLiveRows) {
        return evaluate(permission, record, recordScope, currentScope, unmappedLiveRows, true);
    }

    /**
     * The §7.5 table, evaluated top-down. Rows 3–5 apply while P is WAITING too (no flapping when a session drops after
     * the copy started).
     *
     * @param permission P (FORBIDDEN for null)
     * @param record R (null = no record)
     * @param recordScope the record's import scope (ProOrigin.importScope), null when it has none
     * @param currentScope S: the import scope of the account's current binding, null when unbound
     * @param unmappedLiveRows U: live local rows without a mapping of the record's scope
     * @param ownCopyOnDevice I1: the record's copy sits in this account's cloud store on this device
     *                        ({@link #ownCopyOnDevice(Record, String, long, StoreFacts)})
     */
    public static TasksCutOver evaluate(TasksCloudGate.Permission permission, Record record, String recordScope, String currentScope,
                                        int unmappedLiveRows, boolean ownCopyOnDevice) {
        // 1. No consent: exactly as before (also the fallback of a deletion path that still calls the old purge).
        if (permission == null || permission == TasksCloudGate.Permission.FORBIDDEN) return NONE;
        // 2. The account's server account changed since its claim: the owner records skipped(scope_changed), mappings kept.
        if ((record == Record.CLAIMED || record == Record.ENQUEUED || record == Record.COMPLETED)
                && recordScope != null && currentScope != null && !recordScope.equals(currentScope)) {
            return NONE;
        }
        // 3. The copies exist in this account's outbox: editable in Cloud, offline too. Purged with a logout, they are
        //    re-enqueued by the step once the account is linked again and its snapshot is complete; until then the same
        //    readable archive.
        if (record == Record.ENQUEUED) return MIGRATING;
        // 4./5. Confirmed; new local rows keep the archive visible with "Copy to Cloud".
        if (record == Record.COMPLETED) {
            if (unmappedLiveRows > 0) return new TasksCutOver(Kind.ARCHIVE, Reason.NEW_ROWS, unmappedLiveRows);
            // 4. Hidden only while the copy is on this device: after a logout purged the account's store, or while it
            //    does not open, the device rows stay a readable archive.
            return ownCopyOnDevice ? RETIRED : MIGRATING;
        }
        // 7. Nothing copied yet: no record, a claim whose step has not run (Tasks down, snapshot incomplete, enqueue
        //    failed), released or skipped.
        return NONE;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TasksCutOver)) return false;
        TasksCutOver that = (TasksCutOver) other;
        return kind == that.kind && reason == that.reason && newRows == that.newRows;
    }

    @Override public int hashCode() {
        return (kind.hashCode() * 31 + (reason == null ? 0 : reason.hashCode())) * 31 + newRows;
    }

    @Override public String toString() {
        return reason == null ? kind.name() : kind + "(" + reason + (reason == Reason.NEW_ROWS ? " " + newRows : "") + ")";
    }
}
