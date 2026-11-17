package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudStateTests.*;

/**
 * Cut-over I1 after a logout purge, the Android side of iOS FIX-I4 (review finding SGTasksCutOver.swift:75; design 30
 * §7.5): a Telegram logout wipes the account's cloud store (TasksCloudRepository.onLogout) and keeps its migration record
 * and mappings (ZenlyTasksStore), so a {@code completed} record with every row mapped no longer proves its copy is on the
 * device. Before the fix row 4 returned RETIRED from the record alone: after a re-login the device board was hidden over an
 * empty store until the account was linked again and its first snapshot landed. Now the sync owner reads the store
 * ({@link TasksCutOver#ownCopyOnDevice}) and RETIRED needs the copy here; otherwise the board stays a readable archive.
 *
 * <p>RES-CX1-2 (iOS RES-CX1-1): a completed copy also needs the migration step's check of this store at its resync
 * generation, so a new store's first snapshot, a void verification and a 409 since the completion keep the archive; a
 * rebind to another principal drops the check.
 *
 * <p>RES-CX2-2 (iOS RES-CX2-1): at each stage of a re-login the Device archive's lines follow that I1, read from the
 * store: no progress line and "the Cloud copy returns" while the copy is not here, the progress line once an enqueued
 * copy's creates are back (or its complete snapshot: the accepted "N of N" window), neither once retired.
 *
 * <p>The pure table and predicate, then the store facts on the real engine and ledger (host SQLite bridge, scripted
 * remote): {@link #facts} runs the production read, TasksCloudEngine.cutOverStoreFacts (the repository only delegates to
 * it in one executor turn), and models only ProOrigin.importScope's mapping. Its five facts: the import scope of the
 * bound principal, a complete snapshot, the unsent migration creates, the step's check of the store (migration_checked)
 * and the store's resync generation. Own JVM (the consent gate is process state). The owner's glue (ZenlyTasksSyncOwner,
 * the repository's delegation, the migration context) is checked as source by the sync runner, not run here.
 */
public final class TasksCutOverParityTests {
    private static final String S = "o-faketoken/" + A, OTHER = "o-faketoken/" + B;
    private static final TasksCloudGate.Permission ALLOWED = TasksCloudGate.Permission.ALLOWED, WAITING = TasksCloudGate.Permission.WAITING,
            FORBIDDEN = TasksCloudGate.Permission.FORBIDDEN;

    public static void main(String[] args) throws Exception {
        bridge = args[0];
        dir = new File(args[1]);
        tableNeverRetiresWithoutTheOwnCopy();
        ownCopyPredicate();
        ownerReadsTheStoreOnlyForACopy();
        storeFactsAcrossARelogin();
        rebindVoidsTheStoreCheck();
        storeFactsAreTheEngineRead();
        resnapshotPendingAfterA409();
        System.out.println("Tasks cut-over I1 parity (logout purge; host SQLite bridge): " + checks + " checks passed");
    }

    // ---- the §7.5 table with I1 ----

    private static void tableNeverRetiresWithoutTheOwnCopy() {
        for (TasksCloudGate.Permission p : Arrays.asList(ALLOWED, WAITING)) {
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, S, 0, true) == TasksCutOver.RETIRED,
                    "row 4: completed, U = 0, the copy on this device -> retired (" + p + ")");
            TasksCutOver purged = TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, null, 0, false);
            check(purged.kind == TasksCutOver.Kind.ARCHIVE && purged.reason == TasksCutOver.Reason.MIGRATING,
                    "row 4: completed, U = 0, the store purged by a logout -> archive(migrating), not retired (" + p + ")");
            check(purged.isReadOnly() && purged.defaultsToCloud() && !TasksCutOver.RETIRED.equals(purged),
                    "the device rows stay readable (read-only archive; the router still opens Cloud first) (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, S, 0, false).equals(purged),
                    "the same once linked again, until the copy is back (" + p + ")");
            TasksCutOver newRows = TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, S, 3, false);
            check(newRows.reason == TasksCutOver.Reason.NEW_ROWS && newRows.newRows == 3, "row 5 is unchanged without the copy (" + p + ")");
            for (boolean own : new boolean[]{true, false}) {
                check(TasksCutOver.evaluate(p, TasksCutOver.Record.ENQUEUED, S, null, 0, own).reason == TasksCutOver.Reason.MIGRATING,
                        "row 3 is unchanged (" + p + ", own copy " + own + ")");
                check(TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, OTHER, 0, own) == TasksCutOver.NONE,
                        "row 2 still comes first (" + p + ", own copy " + own + ")");
                for (TasksCutOver.Record record : Arrays.asList(null, TasksCutOver.Record.CLAIMED, TasksCutOver.Record.RELEASED, TasksCutOver.Record.SKIPPED)) {
                    check(TasksCutOver.evaluate(p, record, S, S, 0, own) == TasksCutOver.NONE, "row 7 is unchanged (" + record + ", " + own + ")");
                }
            }
        }
        for (TasksCloudGate.Permission p : Arrays.asList(FORBIDDEN, null)) {
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, S, 0, false) == TasksCutOver.NONE, "row 1 first (" + p + ")");
        }
        // The five-argument form (host tables, the router's rows) means "the copy is here": it equals I1 = true everywhere.
        for (TasksCloudGate.Permission p : Arrays.asList(ALLOWED, WAITING, FORBIDDEN, null)) {
            for (TasksCutOver.Record record : Arrays.asList(null, TasksCutOver.Record.CLAIMED, TasksCutOver.Record.ENQUEUED,
                    TasksCutOver.Record.COMPLETED, TasksCutOver.Record.RELEASED, TasksCutOver.Record.SKIPPED)) {
                for (String current : Arrays.asList(S, OTHER, null)) {
                    for (int unmapped : new int[]{0, 2}) {
                        check(TasksCutOver.evaluate(p, record, S, current, unmapped).equals(TasksCutOver.evaluate(p, record, S, current, unmapped, true)),
                                "five arguments = own copy (" + p + ", " + record + ", " + current + ", " + unmapped + ")");
                    }
                }
            }
        }
    }

    // ---- I1 (iOS TasksCutOver.isOwnCopyOnDevice) ----

    private static void ownCopyPredicate() {
        TasksCutOver.Record completed = TasksCutOver.Record.COMPLETED, enqueued = TasksCutOver.Record.ENQUEUED;
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, null), "a store that does not open holds no copy");
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, checkedStore(null, false, 0)), "a store created after the purge: unbound");
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, checkedStore(null, true, 3)), "unbound: nothing it holds is this copy");
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, checkedStore(S, false, 0)),
                "bound to the record's scope, first snapshot not complete, nothing queued: not yet");
        check(TasksCutOver.ownCopyOnDevice(completed, S, 0, checkedStore(S, true, 0)), "a complete snapshot of the record's scope");
        check(TasksCutOver.ownCopyOnDevice(enqueued, S, 0, checkedStore(S, false, 2)), "the copy's creates queued in this outbox");
        check(TasksCutOver.ownCopyOnDevice(completed, S, 0, checkedStore(S, false, 1)), "re-enqueued creates count for a completed copy");
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, checkedStore(OTHER, true, 5)), "another server account's store is not this copy");
        for (TasksCutOver.Record record : Arrays.asList(null, TasksCutOver.Record.CLAIMED, TasksCutOver.Record.RELEASED, TasksCutOver.Record.SKIPPED)) {
            check(!TasksCutOver.ownCopyOnDevice(record, S, 0, checkedStore(S, true, 1)), "no copy to look for (" + record + ")");
        }
        check(!TasksCutOver.ownCopyOnDevice(completed, null, 0, checkedStore(S, true, 1)), "a record without scope has no copy here");

        // A completed copy counts only once the step has checked it against this store at its resync generation (the
        // complement of TasksAutoMigration's re-check; iOS RES-CX1-1's vectors). An enqueued copy needs neither.
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, new TasksCutOver.StoreFacts(S, true, 0, false, 0)),
                "completed, the store not checked yet (new, rebound): not here");
        check(!TasksCutOver.ownCopyOnDevice(completed, S, 0, new TasksCutOver.StoreFacts(S, true, 0, true, 1)),
                "completed at generation 0, a 409 since (the store at 1): not here");
        check(!TasksCutOver.ownCopyOnDevice(completed, S, -1, checkedStore(S, true, 0)), "completed, its verification voided (-1): not here");
        check(TasksCutOver.ownCopyOnDevice(completed, S, 2, new TasksCutOver.StoreFacts(S, true, 0, true, 2)),
                "completed at the store's generation 2, the store checked: here");
        check(TasksCutOver.ownCopyOnDevice(enqueued, S, 0, new TasksCutOver.StoreFacts(S, true, 0, false, 0)), "enqueued, the store not checked: here");
        check(TasksCutOver.ownCopyOnDevice(enqueued, S, -1, new TasksCutOver.StoreFacts(S, false, 2, false, 1)),
                "enqueued, verified -1 on an unchecked store at generation 1: its queued creates are here");
    }

    /** A store the step has checked, at resync generation 0 (the vectors from before RES-CX1-2 keep their meaning). */
    private static TasksCutOver.StoreFacts checkedStore(String boundScope, boolean completeSnapshot, int pendingMigrationCreates) {
        return new TasksCutOver.StoreFacts(boundScope, completeSnapshot, pendingMigrationCreates, true, 0);
    }

    /** The lazy form the sync owner calls: the store (it may open the repository) is read only for consent and a copy. */
    private static void ownerReadsTheStoreOnlyForACopy() {
        final int[] reads = {0};
        final TasksCutOver.StoreFacts complete = new TasksCutOver.StoreFacts(S, true, 0, true, 0);
        TasksCutOver.StoreReader reader = () -> {
            reads[0]++;
            return complete;
        };
        for (TasksCloudGate.Permission p : Arrays.asList(FORBIDDEN, null)) {
            check(!TasksCutOver.ownCopyOnDevice(p, TasksCutOver.Record.COMPLETED, S, 0, reader), "no consent: no copy (" + p + ")");
        }
        for (TasksCutOver.Record record : Arrays.asList(null, TasksCutOver.Record.CLAIMED, TasksCutOver.Record.RELEASED, TasksCutOver.Record.SKIPPED)) {
            check(!TasksCutOver.ownCopyOnDevice(ALLOWED, record, S, 0, reader), "no copy record (" + record + ")");
        }
        check(reads[0] == 0, "the store is never read without consent and a copy");
        check(TasksCutOver.ownCopyOnDevice(WAITING, TasksCutOver.Record.COMPLETED, S, 0, reader) && reads[0] == 1, "WAITING: read once");
        check(TasksCutOver.ownCopyOnDevice(ALLOWED, TasksCutOver.Record.ENQUEUED, S, 0, reader) && reads[0] == 2, "ALLOWED: read once");
        check(!TasksCutOver.ownCopyOnDevice(ALLOWED, TasksCutOver.Record.COMPLETED, S, 0, () -> null), "a store that does not open");
        check(!TasksCutOver.ownCopyOnDevice(ALLOWED, TasksCutOver.Record.COMPLETED, S, 0, null), "no reader");
    }

    // ---- the store facts across a re-login (real engine and ledger) ----

    /**
     * The production read, TasksCloudEngine.cutOverStoreFacts, which TasksCloudRepository.cutOverStoreFacts runs in one
     * executor turn; only ProOrigin.importScope's mapping of the bound principal is modelled.
     */
    private static TasksCutOver.StoreFacts facts(Harness h) {
        return h.engine.cutOverStoreFacts(principal -> "o-faketoken/" + principal);
    }

    /** What the step leaves on a store it has checked (TasksCloudRepository.MigrationCloud.markChecked). */
    private static void markChecked(Harness h) {
        h.ledger.writeMeta(TasksCloudLedger.META_MIGRATION_CHECKED, "1");
    }

    /** The owner's computation for a completed record of scope S with every row mapped (U = 0), verified at 0. */
    private static TasksCutOver completedCutOver(TasksCloudGate.Permission p, String currentScope, Harness h) {
        return completedCutOver(p, currentScope, h, 0);
    }

    /** The same for a record the step verified at resync generation {@code verified}. */
    private static TasksCutOver completedCutOver(TasksCloudGate.Permission p, String currentScope, Harness h, long verified) {
        boolean own = TasksCutOver.ownCopyOnDevice(p, TasksCutOver.Record.COMPLETED, S, verified, () -> facts(h));
        return TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, currentScope, 0, own);
    }

    /** The owner's I1 for a record of scope S verified at 0, read from the harness's store at this stage. */
    private static boolean ownCopy(TasksCloudGate.Permission p, TasksCutOver.Record record, Harness h) {
        return TasksCutOver.ownCopyOnDevice(p, record, S, 0, () -> facts(h));
    }

    /**
     * The Device archive's line for that record (RES-CX2-2; iOS RES-CX2-1), with the I1 read from the harness at this
     * stage passed to the owner's cut-over (U = 0) and to both helpers: "progress" (an enqueued copy in this store),
     * "returns" (the copy is not in this store: the Cloud copy returns once the account is confirmed and synced), "none",
     * or "both" (never).
     */
    private static void expectLine(String expected, TasksCloudGate.Permission p, TasksCutOver.Record record, String currentScope, Harness h,
                                   String what) {
        boolean own = ownCopy(p, record, h);
        TasksCutOver cutOver = TasksCutOver.evaluate(p, record, S, currentScope, 0, own);
        boolean progress = TasksCutOver.archiveShowsProgress(cutOver, record, own), returns = TasksCutOver.archiveAwaitsCopy(cutOver, own);
        String line = progress && returns ? "both" : progress ? "progress" : returns ? "returns" : "none";
        check(expected.equals(line), what + " (" + record + ", " + p + ", own copy " + own + ", " + cutOver + "): " + expected + " expected, got " + line);
    }

    private static void storeFactsAcrossARelogin() {
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        Harness before = new Harness();
        before.bootstrap();
        markChecked(before); // the step checked this store and completed the copy at its generation 0
        check(completedCutOver(ALLOWED, S, before) == TasksCutOver.RETIRED, "before the logout: bound, complete, checked -> retired");
        expectLine("none", ALLOWED, TasksCutOver.Record.COMPLETED, S, before, "before the logout: retired, neither archive line");
        before.sql.close();

        // Logout wiped the store (a new database file); the same account signs in again and agrees to the wall: consent
        // without a link yet (P WAITING, S unknown). Its engine runs but finds no linked identity: the store stays unbound.
        TasksCloudGate.install(account -> TasksCloudGate.Permission.WAITING);
        Harness h = new Harness();
        h.remote.identity = new TasksCloudRemote.Identity(TG, null, 3);
        h.engine.start();
        h.drain();
        h.remote.none("unlinked: no Tasks request");
        TasksCutOver waiting = completedCutOver(WAITING, null, h);
        check(facts(h).boundScope == null && waiting.reason == TasksCutOver.Reason.MIGRATING,
                "re-login before the link: the new store is unbound -> archive, the device board readable (was retired)");
        // The archive's lines (RES-CX2-2): none of the copy is in this store, which publishes {0, 0} unsent, so a progress
        // line would read "N of N" (it did, for a confirmed and a queued copy alike); the banner says the copy returns.
        for (TasksCutOver.Record record : Arrays.asList(TasksCutOver.Record.COMPLETED, TasksCutOver.Record.ENQUEUED)) {
            check(!ownCopy(WAITING, record, h), "re-login before the link: the unbound store holds no copy (" + record + ")");
            expectLine("returns", WAITING, record, null, h, "re-login before the link: the Cloud copy returns, no progress line");
        }

        // Linked again (P ALLOWED): the first step binds the store; its snapshot is still in flight.
        h.remote.identity = new TasksCloudRemote.Identity(TG, A, 4);
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        h.engine.permissionChanged();
        h.drain();
        Request first = h.remote.take("snapshot");
        TasksCutOver.StoreFacts bound = facts(h);
        check(S.equals(bound.boundScope) && !bound.completeSnapshot && bound.pendingMigrationCreates == 0, "bound to the record's scope, snapshot pending");
        check(completedCutOver(ALLOWED, S, h).reason == TasksCutOver.Reason.MIGRATING, "still the archive until the copy is back");
        for (TasksCutOver.Record record : Arrays.asList(TasksCutOver.Record.COMPLETED, TasksCutOver.Record.ENQUEUED)) {
            expectLine("returns", ALLOWED, record, S, h, "bound, its snapshot pending: the copy is not here yet");
        }

        // The first complete snapshot lands: the confirmed copy may be on this device again, but the migration step has not
        // checked it against this new store yet (it re-checks the record first and re-enqueues what is missing:
        // TasksMigrationTests). No step runs while P is WAITING, so this lasts until the link: the archive either way.
        snapshot(first, null, "s1");
        h.drain();
        changes(h.remote.take("changes"), "c2", false);
        h.drain();
        TasksCutOver.StoreFacts landed = facts(h);
        check(landed.completeSnapshot && !landed.migrationChecked && landed.resyncGeneration == 0, "complete snapshot, not checked yet");
        check(completedCutOver(ALLOWED, S, h).reason == TasksCutOver.Reason.MIGRATING
                && completedCutOver(WAITING, null, h).reason == TasksCutOver.Reason.MIGRATING,
                "complete snapshot, the copy not checked against this store -> still the archive (was retired)");
        expectLine("returns", ALLOWED, TasksCutOver.Record.COMPLETED, S, h, "complete, not checked: a confirmed copy still awaits the step's check");
        expectLine("returns", WAITING, TasksCutOver.Record.COMPLETED, null, h, "the same while P is WAITING");
        // The accepted window: a complete snapshot of the record's scope alone counts as an enqueued copy (I1), so before the
        // step re-enqueues the purged creates the progress line reads "N of N" (the same window as iOS RES-CX2-1).
        check(ownCopy(ALLOWED, TasksCutOver.Record.ENQUEUED, h), "a complete snapshot alone is the enqueued copy here");
        expectLine("progress", ALLOWED, TasksCutOver.Record.ENQUEUED, S, h,
                "an enqueued copy over a complete snapshot, nothing re-enqueued yet: the progress line ('N of N' until the step re-enqueues)");
        // The step checked the store and completed the copy at its generation 0: retired, also when P drops to WAITING.
        markChecked(h);
        check(completedCutOver(ALLOWED, S, h) == TasksCutOver.RETIRED && completedCutOver(WAITING, null, h) == TasksCutOver.RETIRED,
                "checked -> retired (ALLOWED and WAITING: no flapping)");
        expectLine("none", ALLOWED, TasksCutOver.Record.COMPLETED, S, h, "checked: retired, neither archive line");
        expectLine("none", WAITING, TasksCutOver.Record.COMPLETED, null, h, "checked: retired while WAITING, neither archive line");
        h.sql.close();

        // A store whose snapshot is not complete yet but already holds the re-enqueued migration creates holds the copy
        // (editable in Cloud, offline too); a completed copy there waits for the step's check as well.
        Harness queued = new Harness();
        queued.engine.start();
        queued.drain();
        Request pending = queued.remote.take("snapshot");
        check(queued.engine.enqueueImport(A, TasksCloudR4Tests.migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, queued.engine.consentEpoch()) == 2,
                "the step's re-enqueue");
        check(!facts(queued).completeSnapshot && facts(queued).pendingMigrationCreates == 2 && !facts(queued).migrationChecked,
                "queued creates of the record's scope, the store not checked yet");
        check(TasksCutOver.ownCopyOnDevice(ALLOWED, TasksCutOver.Record.ENQUEUED, S, -1, () -> facts(queued)),
                "an enqueued copy: its queued creates are the copy, checked or not");
        boolean own = ownCopy(ALLOWED, TasksCutOver.Record.ENQUEUED, queued);
        TasksCutOver resending = TasksCutOver.evaluate(ALLOWED, TasksCutOver.Record.ENQUEUED, S, S, 0, own);
        check(own && TasksCutOver.archiveShowsProgress(resending, TasksCutOver.Record.ENQUEUED, own) && !TasksCutOver.archiveAwaitsCopy(resending, own),
                "two re-enqueued creates, no complete snapshot: the progress line again, not 'the Cloud copy returns'");
        expectLine("progress", WAITING, TasksCutOver.Record.ENQUEUED, null, queued, "the same while P is WAITING");
        check(completedCutOver(ALLOWED, S, queued).reason == TasksCutOver.Reason.MIGRATING, "a completed copy waits for the step's check");
        expectLine("returns", ALLOWED, TasksCutOver.Record.COMPLETED, S, queued, "a completed copy there awaits the step's check");
        markChecked(queued);
        check(completedCutOver(ALLOWED, S, queued) == TasksCutOver.RETIRED, "checked: queued creates of the record's scope are the copy");
        expectLine("none", ALLOWED, TasksCutOver.Record.COMPLETED, S, queued, "checked: retired, neither archive line");
        check(pending != null, "snapshot was requested");
        queued.sql.close();

        // A store bound to another server account holds none of this copy, even complete and checked.
        Harness other = new Harness();
        other.remote.identity = new TasksCloudRemote.Identity(TG, B, 5);
        other.bootstrap();
        markChecked(other);
        check(OTHER.equals(facts(other).boundScope) && facts(other).completeSnapshot && facts(other).migrationChecked, "complete, but bound to B");
        check(!TasksCutOver.ownCopyOnDevice(ALLOWED, TasksCutOver.Record.COMPLETED, S, 0, () -> facts(other)), "not the copy of scope A");
        other.sql.close();
    }

    /**
     * R-I5's Android twin: a rebind to another principal drops the store's check in the same transaction
     * (TasksCloudLedger.bindPrincipal), so after A -> B -> A the first step on A's re-downloaded snapshot re-checks the
     * completed copy (TasksMigrationTests.newCloudStoreRechecksTheCopy) while the board stays the archive.
     */
    private static void rebindVoidsTheStoreCheck() {
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        Harness h = new Harness();
        h.bootstrap();
        markChecked(h);
        check(completedCutOver(ALLOWED, S, h) == TasksCutOver.RETIRED, "bound to A, complete, checked -> retired");

        h.remote.identity = new TasksCloudRemote.Identity(TG, B, 2);
        h.engine.identityChanged();
        h.drain();
        snapshot(h.remote.take("snapshot"), null, "sb", task(TasksWire.newId(), "1", "B's task"));
        h.drain();
        changes(h.remote.take("changes"), "cb", false);
        h.drain();
        check(B.equals(h.ledger.readMeta(TasksCloudLedger.META_PRINCIPAL)) && h.engine.shadowComplete(), "rebound to B, B's snapshot complete");
        check(h.ledger.readMeta(TasksCloudLedger.META_MIGRATION_CHECKED) == null, "the rebind dropped the check of A's store (was \"1\")");

        h.remote.identity = new TasksCloudRemote.Identity(TG, A, 3);
        h.engine.identityChanged();
        h.drain();
        snapshot(h.remote.take("snapshot"), null, "sa");
        h.drain();
        changes(h.remote.take("changes"), "ca", false);
        h.drain();
        h.remote.none("nothing in flight after A's snapshot");
        TasksCutOver.StoreFacts back = facts(h);
        check(S.equals(back.boundScope) && back.completeSnapshot && !back.migrationChecked, "back on A: bound to S, complete, not checked");
        check(completedCutOver(ALLOWED, S, h).reason == TasksCutOver.Reason.MIGRATING
                && completedCutOver(WAITING, null, h).reason == TasksCutOver.Reason.MIGRATING,
                "the completed copy waits for the step's re-check against A's new snapshot (was retired)");
        h.sql.close();
    }

    /**
     * The store facts are the engine's read on the real ledger (the repository only delegates; the sync runner checks
     * that as source): every fact from the persisted store, the import scope asked only for a bound store and once with
     * its principal, no bind and no in-memory field (a cold engine, a newer linked identity not applied yet), and a
     * malformed generation thrown to the repository's catch. Fresh harnesses, each closed; a read that throws where it
     * must not is a failed check.
     */
    private static void storeFactsAreTheEngineRead() {
        final List<String> asked = new ArrayList<>();
        TasksCloudEngine.ImportScope recording = principal -> {
            asked.add(principal);
            return "o-faketoken/" + principal;
        };

        // Unbound: a store created after a logout purge; consent, no link yet (P WAITING).
        TasksCloudGate.install(account -> TasksCloudGate.Permission.WAITING);
        Harness h = new Harness();
        h.remote.identity = new TasksCloudRemote.Identity(TG, null, 3);
        h.engine.start();
        h.drain();
        h.remote.none("unlinked: no Tasks request");
        TasksCutOver.StoreFacts unbound = read(h, recording, "unbound");
        check(unbound.boundScope == null && !unbound.completeSnapshot && unbound.pendingMigrationCreates == 0, "unbound: no scope, no snapshot, nothing queued");
        check(!unbound.migrationChecked && unbound.resyncGeneration == 0, "unbound: not checked, generation 0");
        check(asked.isEmpty(), "unbound: the import scope is never asked");

        // Linked (P ALLOWED): the first step binds the store; its snapshot is in flight.
        h.remote.identity = new TasksCloudRemote.Identity(TG, A, 4);
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        h.engine.permissionChanged();
        h.drain();
        Request first = h.remote.take("snapshot");
        TasksCutOver.StoreFacts bound = read(h, recording, "bound");
        check(S.equals(bound.boundScope) && asked.equals(Collections.singletonList(A)), "bound: the scope of A, asked exactly once with A");
        check(!bound.completeSnapshot && bound.pendingMigrationCreates == 0, "bound: the snapshot still in flight");

        // The first complete snapshot; then one unsent user entry and two migration creates.
        snapshot(first, null, "s1");
        h.drain();
        changes(h.remote.take("changes"), "c2", false);
        h.drain();
        h.engine.createTask(content(null, "Offline draft"));
        h.drain();
        fail(h.remote.take("mutate"), TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", null, 0);
        h.drain();
        check(h.engine.enqueueImport(A, TasksCloudR4Tests.migrationPlan(2), TasksCloudSchema.ORIGIN_MIGRATION, h.engine.consentEpoch()) == 2,
                "two migration creates queued");
        h.drain();
        h.remote.none("nothing in flight: the user entry waits for its backoff, the creates for the batch route");
        TasksCutOver.StoreFacts queued = read(h, recording, "queued");
        check(queued.completeSnapshot && queued.pendingMigrationCreates == 2 && h.engine.unsentCounts()[0] == 1,
                "complete; the two migration creates count, the unsent user entry does not");
        check(!queued.migrationChecked && queued.resyncGeneration == 0, "not checked, generation 0 until the step and a 409 write them");
        check(completedCutOver(ALLOWED, S, h).reason == TasksCutOver.Reason.MIGRATING, "not checked: a completed copy stays the archive");
        h.ledger.writeMeta(TasksCloudLedger.META_MIGRATION_CHECKED, "1");
        check(read(h, recording, "checked").migrationChecked && completedCutOver(ALLOWED, S, h) == TasksCutOver.RETIRED,
                "checked at generation 0: retired");
        h.ledger.writeMeta(TasksCloudLedger.META_RESYNC_GENERATION, "7");
        TasksCutOver.StoreFacts resynced = read(h, recording, "resynced");
        check(resynced.migrationChecked && resynced.resyncGeneration == 7, "checked, generation 7, as stored");
        check(completedCutOver(ALLOWED, S, h).reason == TasksCutOver.Reason.MIGRATING && completedCutOver(ALLOWED, S, h, 7) == TasksCutOver.RETIRED,
                "a copy verified at 0 waits for the re-check after the resyncs; one verified at 7 is here");

        // Cold start: a new engine on the same file, never started (its in-memory principal null): the persisted store.
        Harness cold = h.restart();
        asked.clear();
        TasksCutOver.StoreFacts persisted = read(cold, recording, "cold start");
        check(S.equals(persisted.boundScope) && asked.equals(Collections.singletonList(A)), "cold start: the persisted principal's scope, asked once with A");
        check(persisted.completeSnapshot && persisted.pendingMigrationCreates == 2 && persisted.migrationChecked && persisted.resyncGeneration == 7,
                "cold start: every fact as persisted");
        check(completedCutOver(WAITING, null, cold, 7) == TasksCutOver.RETIRED && completedCutOver(WAITING, null, cold).reason == TasksCutOver.Reason.MIGRATING,
                "cold start, P WAITING: a checked copy verified at the store's generation is here, one verified before its resyncs is not");

        // A newer linked identity the engine has not applied: the read still reports the store as bound and binds nothing.
        cold.remote.identity = new TasksCloudRemote.Identity(TG, B, 9);
        asked.clear();
        TasksCutOver.StoreFacts linkedElsewhere = read(cold, recording, "rebound without identityChanged");
        check(S.equals(linkedElsewhere.boundScope) && linkedElsewhere.completeSnapshot && linkedElsewhere.migrationChecked
                && asked.equals(Collections.singletonList(A)), "a newer identity not applied yet: still the store bound to A, complete, checked");
        check(A.equals(cold.ledger.readMeta(TasksCloudLedger.META_PRINCIPAL)) && "1".equals(cold.ledger.readMeta(TasksCloudLedger.META_SHADOW_COMPLETE))
                && "1".equals(cold.ledger.readMeta(TasksCloudLedger.META_MIGRATION_CHECKED)), "the read never binds: A's store, snapshot and check kept");
        // Only the engine's own step binds it to B (no request: never started), and that rebind drops the check.
        cold.engine.identityChanged();
        cold.drain();
        cold.remote.none("not started: no Tasks request");
        asked.clear();
        TasksCutOver.StoreFacts rebound = read(cold, recording, "rebound");
        check(OTHER.equals(rebound.boundScope) && !rebound.completeSnapshot && !rebound.migrationChecked && asked.equals(Collections.singletonList(B)),
                "rebound by the engine: B's scope, no snapshot, not checked");
        cold.sql.close();

        // Last, on its own store: a malformed resync generation throws to the repository's catch (null: no copy here).
        Harness malformed = new Harness();
        malformed.bootstrap();
        malformed.ledger.writeMeta(TasksCloudLedger.META_RESYNC_GENERATION, "x");
        expect(NumberFormatException.class, () -> malformed.engine.cutOverStoreFacts(recording), "a malformed resync generation");
        malformed.sql.close();
    }

    /** The engine's read; an exception it must not throw is a failed check (with its cause), not a crash of the suite. */
    private static TasksCutOver.StoreFacts read(Harness h, TasksCloudEngine.ImportScope scope, String what) {
        try {
            return h.engine.cutOverStoreFacts(scope);
        } catch (RuntimeException e) {
            check(false, what + ": the store facts read threw " + e);
            return null;
        }
    }

    /**
     * A 409 (tasks_resync_required) keeps the shadow and its completeness and drops the cursor until the next snapshot
     * lands: resnapshotPending is the migration context's refusal then (iOS needs_resnapshot), where the old rule (a
     * complete shadow, no staging) read the store as ready and verified a copy against a shadow that may still list rows
     * the server erased.
     */
    private static void resnapshotPendingAfterA409() {
        TasksCloudGate.install(account -> TasksCloudGate.Permission.ALLOWED);
        Harness h = new Harness();
        h.bootstrap();
        check(!h.ledger.resnapshotPending(), "bootstrapped: no resnapshot pending");
        long generation = h.ledger.resyncGeneration();
        h.ledger.requireResnapshot();
        check(h.ledger.resnapshotPending() && "1".equals(h.ledger.readMeta(TasksCloudLedger.META_SHADOW_COMPLETE)) && h.ledger.staging() == null,
                "after a 409: pending, shadow_complete still 1, no staging yet");
        check(h.engine.shadowComplete() && h.ledger.staging() == null, "the old context() rule alone would read this store as ready");
        check(h.ledger.resyncGeneration() == generation + 1, "the 409 counted one resync generation");
        h.engine.refresh();
        h.drain();
        snapshot(h.remote.take("snapshot"), null, "s9");
        h.drain();
        check(h.ledger.resnapshotPending(), "the new snapshot still catching up: pending");
        changes(h.remote.take("changes"), "c9", false);
        h.drain();
        check(!h.ledger.resnapshotPending() && h.engine.shadowComplete(), "the next snapshot landed: not pending");
        h.sql.close();
    }
}
