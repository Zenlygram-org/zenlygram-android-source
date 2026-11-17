package org.telegram.messenger.zenlygram.tasks.cloud;

import org.telegram.messenger.zenlygram.tasks.ZenlyTaskModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host tests of the sync owner's pure decisions (TasksSyncPolicy, design 30 §6.1) and the cut-over table
 * (TasksCutOver, §7.5) the owner feeds into the write fence, plus the repository's send-time consent check
 * (TasksSyncPolicy.sendAllowed) and the Device archive's lines (TasksCutOver.archiveShowsProgress / archiveAwaitsCopy).
 * Own JVM: the gate's fail-closed default is process state. The Android glue that wires them (ZenlyTasksSyncOwner,
 * TasksCloudRepository, ZenlyTasksActivity and its strings) is read as source ({@link #wiring}); args[0] is the Android
 * root. Run through run-tests.sh; no Android SDK, no JUnit.
 */
public final class TasksSyncPolicyTests {
    private static int checks;
    private static final String S = "o-faketoken/01jfakeprincipala", OTHER = "o-faketoken/01jotherprincipal";

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws IOException {
        TasksCloudGate.Permission allowed = TasksCloudGate.Permission.ALLOWED, waiting = TasksCloudGate.Permission.WAITING,
                forbidden = TasksCloudGate.Permission.FORBIDDEN;

        // Gate: fail closed until ZenlyTasksSyncOwner.onPostInit installs the source.
        for (int account = 0; account < 4; account++) check(TasksCloudGate.permission(account) == forbidden, "FORBIDDEN before install");
        check(!TasksCloudGate.allowed(0), "nothing allowed before install");
        TasksCloudGate.install(account -> TasksSyncPolicy.permission(account == 1, account != 3));
        check(TasksCloudGate.permission(1) == allowed && TasksCloudGate.permission(0) == waiting && TasksCloudGate.permission(3) == forbidden,
                "the installed source decides per account");
        TasksCloudGate.install(account -> { throw new IllegalStateException("customer layer not ready"); });
        check(TasksCloudGate.permission(1) == forbidden, "a throwing source is FORBIDDEN");
        TasksCloudGate.install(account -> null);
        check(TasksCloudGate.permission(1) == forbidden, "a null answer is FORBIDDEN");
        TasksCloudGate.install(null);
        check(TasksCloudGate.permission(1) == forbidden, "uninstalled again: FORBIDDEN");

        // Permission table: tasksMayRun ? ALLOWED : recordingAllowed ? WAITING : FORBIDDEN.
        check(TasksSyncPolicy.permission(true, true) == allowed, "ready -> ALLOWED");
        check(TasksSyncPolicy.permission(true, false) == allowed, "tasksMayRun alone decides ALLOWED");
        check(TasksSyncPolicy.permission(false, true) == waiting, "consent, not ready -> WAITING");
        check(TasksSyncPolicy.permission(false, false) == forbidden, "no consent -> FORBIDDEN");

        // Drive / stop.
        check(TasksSyncPolicy.driveAction(allowed, false) == TasksSyncPolicy.DriveAction.DRIVE, "ALLOWED drives");
        check(TasksSyncPolicy.driveAction(allowed, true) == TasksSyncPolicy.DriveAction.DRIVE, "ALLOWED keeps driving (foreground catch-up)");
        check(TasksSyncPolicy.driveAction(waiting, true) == TasksSyncPolicy.DriveAction.STOP, "WAITING stops a driven repository");
        check(TasksSyncPolicy.driveAction(forbidden, true) == TasksSyncPolicy.DriveAction.STOP, "FORBIDDEN stops a driven repository");
        check(TasksSyncPolicy.driveAction(waiting, false) == TasksSyncPolicy.DriveAction.NONE, "WAITING idle stays idle");
        check(TasksSyncPolicy.driveAction(forbidden, false) == TasksSyncPolicy.DriveAction.NONE, "FORBIDDEN idle stays idle");

        // Re-grant resume.
        check(TasksSyncPolicy.needsRegrantResume(allowed, true), "ALLOWED again after a withdrawal resumes");
        check(!TasksSyncPolicy.needsRegrantResume(allowed, false), "not withdrawn: nothing to resume");
        check(!TasksSyncPolicy.needsRegrantResume(waiting, true) && !TasksSyncPolicy.needsRegrantResume(forbidden, true), "only ALLOWED resumes");

        // Send time (contract §6.7): a request made at epoch 7 goes out only while the gate is ALLOWED, the database is not
        // withdrawn and its epoch is still 7.
        check(TasksSyncPolicy.sendAllowed(allowed, false, 7, 7), "consent as when the request was made: sent");
        check(!TasksSyncPolicy.sendAllowed(waiting, false, 7, 7) && !TasksSyncPolicy.sendAllowed(forbidden, false, 7, 7)
                && !TasksSyncPolicy.sendAllowed(null, false, 7, 7), "the withdrawal turned the gate off: refused at once (before the discard ran)");
        check(!TasksSyncPolicy.sendAllowed(allowed, true, 7, 7), "the gate is back, the database still withdrawn (resume pending or lost): refused");
        check(!TasksSyncPolicy.sendAllowed(allowed, true, 7, 8), "discarded, not resumed: refused");
        check(!TasksSyncPolicy.sendAllowed(allowed, false, 7, 8), "withdrawn and quickly re-granted while it waited (the epoch moved): refused");
        check(TasksSyncPolicy.sendAllowed(allowed, false, 8, 8), "a request made after the re-grant: sent");

        // Write state.
        check(TasksSyncPolicy.writeState(true, TasksCutOver.NONE) == ZenlyTaskModel.WriteState.MIGRATING, "the step's local part: MIGRATING");
        check(TasksSyncPolicy.writeState(true, TasksCutOver.RETIRED) == ZenlyTaskModel.WriteState.MIGRATING, "MIGRATING wins over read-only");
        check(TasksSyncPolicy.writeState(false, TasksCutOver.RETIRED) == ZenlyTaskModel.WriteState.ARCHIVED, "retired: ARCHIVED");
        check(TasksSyncPolicy.writeState(false, archive(TasksCutOver.Record.ENQUEUED, 0)) == ZenlyTaskModel.WriteState.ARCHIVED, "archive: ARCHIVED");
        check(TasksSyncPolicy.writeState(false, TasksCutOver.NONE) == ZenlyTaskModel.WriteState.OPEN, "none: OPEN");
        check(TasksSyncPolicy.writeState(false, null) == ZenlyTaskModel.WriteState.OPEN, "unknown: OPEN");

        // §7.5, top-down.
        for (TasksCutOver.Record record : TasksCutOver.Record.values()) {
            check(TasksCutOver.evaluate(forbidden, record, S, S, 3) == TasksCutOver.NONE, "row 1: FORBIDDEN -> none (" + record + ")");
            check(TasksCutOver.evaluate(null, record, S, S, 3) == TasksCutOver.NONE, "row 1: null P -> none (" + record + ")");
        }
        for (TasksCutOver.Record record : new TasksCutOver.Record[]{TasksCutOver.Record.CLAIMED, TasksCutOver.Record.ENQUEUED, TasksCutOver.Record.COMPLETED}) {
            check(TasksCutOver.evaluate(allowed, record, S, OTHER, 0) == TasksCutOver.NONE, "row 2: another scope -> none (" + record + ")");
        }
        for (TasksCloudGate.Permission p : new TasksCloudGate.Permission[]{allowed, waiting}) {
            TasksCutOver migrating = TasksCutOver.evaluate(p, TasksCutOver.Record.ENQUEUED, S, S, 5);
            check(migrating.kind == TasksCutOver.Kind.ARCHIVE && migrating.reason == TasksCutOver.Reason.MIGRATING && migrating.isReadOnly()
                    && migrating.defaultsToCloud(), "row 3: enqueued -> archive(migrating) (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.ENQUEUED, S, null, 0).reason == TasksCutOver.Reason.MIGRATING,
                    "row 3 holds while unbound (no flapping) (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, S, 0) == TasksCutOver.RETIRED, "row 4: completed, U = 0 -> retired (" + p + ")");
            TasksCutOver purged = TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, null, 0, false);
            check(purged.reason == TasksCutOver.Reason.MIGRATING && purged.isReadOnly(),
                    "row 4 needs the copy on this device (I1): after a logout purge -> archive(migrating), never retired (" + p + ")");
            TasksCutOver newRows = TasksCutOver.evaluate(p, TasksCutOver.Record.COMPLETED, S, S, 4);
            check(newRows.kind == TasksCutOver.Kind.ARCHIVE && newRows.reason == TasksCutOver.Reason.NEW_ROWS && newRows.newRows == 4,
                    "row 5: completed, U > 0 -> archive(newRows U) (" + p + ")");
            // Row 7: nothing copied yet (no record, a claim whose step has not run — Tasks unreachable, snapshot
            // incomplete, enqueue failed —, released, skipped): writable.
            check(TasksCutOver.evaluate(p, null, null, S, 9) == TasksCutOver.NONE, "row 7: no record (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.CLAIMED, S, S, 9) == TasksCutOver.NONE, "row 7: claimed, Tasks unreachable -> writable (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.CLAIMED, null, null, 9) == TasksCutOver.NONE, "row 7: claimed without scope (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.RELEASED, S, S, 9) == TasksCutOver.NONE, "row 7: released (" + p + ")");
            check(TasksCutOver.evaluate(p, TasksCutOver.Record.SKIPPED, S, S, 9) == TasksCutOver.NONE, "row 7: skipped (" + p + ")");
        }
        check(TasksSyncPolicy.writeState(false, TasksCutOver.evaluate(allowed, TasksCutOver.Record.CLAIMED, S, S, 9)) == ZenlyTaskModel.WriteState.OPEN,
                "claimed + Tasks unreachable: the board stays writable");
        check(TasksCutOver.Record.fromWire("enqueued") == TasksCutOver.Record.ENQUEUED && TasksCutOver.Record.fromWire("bogus") == null
                && TasksCutOver.Record.fromWire(null) == null, "record wire values");
        archiveLines();
        wiring(args[0]);
        System.out.println("Tasks sync policy (gate, drive, re-grant, send time, write state, cut-over §7.5, archive lines, wiring): "
                + checks + " checks passed");
    }

    /**
     * The Device archive's lines (RES-CX2-2; the truth table of iOS RES-CX2-1 over the reason, the record and I1): the
     * progress line only for an enqueued copy in this account's store, "the Cloud copy returns" exactly for
     * archive(migrating) without that copy. After a logout purge the new store publishes {0, 0} unsent, so the progress
     * line used to read "N of N" for a queued and a completed copy alike.
     */
    private static void archiveLines() {
        TasksCloudGate.Permission allowed = TasksCloudGate.Permission.ALLOWED, waiting = TasksCloudGate.Permission.WAITING;
        TasksCutOver.Record enqueued = TasksCutOver.Record.ENQUEUED, completed = TasksCutOver.Record.COMPLETED;
        TasksCutOver queued = TasksCutOver.evaluate(allowed, enqueued, S, S, 0, true);
        check(TasksCutOver.archiveShowsProgress(queued, enqueued, true) && !TasksCutOver.archiveAwaitsCopy(queued, true),
                "queued in this store: the progress line, no 'returns' line");
        TasksCutOver queuedPurged = TasksCutOver.evaluate(waiting, enqueued, S, null, 0, false);
        check(!TasksCutOver.archiveShowsProgress(queuedPurged, enqueued, false) && TasksCutOver.archiveAwaitsCopy(queuedPurged, false),
                "queued over a purged store: no 'N of N'; the Cloud copy returns");
        TasksCutOver completedAway = TasksCutOver.evaluate(waiting, completed, S, null, 0, false);
        check(!TasksCutOver.archiveShowsProgress(completedAway, completed, false) && TasksCutOver.archiveAwaitsCopy(completedAway, false),
                "completed, not on this device: no progress line; the Cloud copy returns");
        check(!TasksCutOver.archiveShowsProgress(completedAway, completed, true),
                "a completed copy is never shown as being copied (iOS recordState != .completed), whatever I1 says");
        for (TasksCutOver.Record record : new TasksCutOver.Record[]{null, TasksCutOver.Record.CLAIMED, completed, TasksCutOver.Record.RELEASED,
                TasksCutOver.Record.SKIPPED}) {
            check(!TasksCutOver.archiveShowsProgress(queued, record, true),
                    "archive(migrating) with record " + record + ": no progress line (an unreadable record shows none)");
        }
        check(TasksCutOver.archiveAwaitsCopy(queued, false) && TasksCutOver.archiveAwaitsCopy(completedAway, false)
                && !TasksCutOver.archiveAwaitsCopy(completedAway, true), "under archive(migrating) the 'returns' line follows I1 alone");
        // Neither line outside archive(migrating): new rows, retired, writable (also a FORBIDDEN gate's NONE beside the
        // owner's cached I1), no cut-over.
        TasksCutOver newRows = TasksCutOver.evaluate(allowed, completed, S, S, 4, false);
        check(newRows.reason == TasksCutOver.Reason.NEW_ROWS && newRows.newRows == 4, "the NEW_ROWS input");
        check(TasksCutOver.evaluate(TasksCloudGate.Permission.FORBIDDEN, enqueued, S, S, 0, true) == TasksCutOver.NONE, "the FORBIDDEN input");
        for (TasksCutOver other : new TasksCutOver[]{newRows, TasksCutOver.RETIRED, TasksCutOver.NONE, null}) {
            for (boolean own : new boolean[]{true, false}) {
                check(!TasksCutOver.archiveAwaitsCopy(other, own), "no 'returns' line: " + other + " (own copy " + own + ")");
                for (TasksCutOver.Record record : new TasksCutOver.Record[]{null, TasksCutOver.Record.CLAIMED, enqueued, completed,
                        TasksCutOver.Record.RELEASED, TasksCutOver.Record.SKIPPED}) {
                    check(!TasksCutOver.archiveShowsProgress(other, record, own), "no progress line: " + other + " (" + record + ", own copy " + own + ")");
                }
            }
        }
    }

    private static TasksCutOver archive(TasksCutOver.Record record, int unmapped) {
        return TasksCutOver.evaluate(TasksCloudGate.Permission.ALLOWED, record, S, S, unmapped);
    }

    // ---- the Android glue, read as source (run-tests.sh cannot run it) ----

    /**
     * The re-grant resume (FIX-A6's open item): the owner's evaluate lets an ALLOWED account's repository resume a database
     * still marked withdrawn (a lost resume), ahead of the step; the repository decides with needsRegrantResume on the gate
     * read again on its executor. The send-time re-check: every request of the repository's transport adapter carries its
     * send gate, which asks sendAllowed with the epoch of the moment the request was made; the copies it reads follow
     * every change of the ledger's consent meta. And I1: the owner feeds the store read and the record's verified
     * generation into the cut-over; the repository only delegates the read to the engine's
     * (TasksCloudEngine.cutOverStoreFacts, which the state suite runs on the real ledger), and its migration context
     * waits out a pending resnapshot. The Device archive's lines (RES-CX2-2): the owner caches each evaluation's I1
     * right before its cut-over and drops it with the cut-over, announcing nothing for it; the app's device source
     * reads it beside the cut-over and the record, the archive model decides the lines by the record's state and the
     * text builder words them (progress, then "the Cloud copy returns", then kept local, as iOS); coming back to the
     * screen rebuilds only the banner; the string is iOS's in the three locales. (The import had all of this in one
     * activity, ZenlyTasksActivity; this app reads it from org.telegram.zenlygram.tasks.)
     */
    private static void wiring(String androidRoot) throws IOException {
        String owner = source(androidRoot, "tasks/ZenlyTasksSyncOwner.java");
        String repository = source(androidRoot, "tasks/cloud/TasksCloudRepository.java");

        String evaluate = body(owner, "private static void evaluate(int account, TasksCloudPolicy.Occasion occasion, boolean step)");
        int resume = evaluate.indexOf("if (permission == TasksCloudGate.Permission.ALLOWED) repository.resumeIfRegranted();");
        check(resume >= 0, "evaluate: an ALLOWED account's repository resumes a lost re-grant");
        check(resume < evaluate.indexOf("queueStep(account, repository)"), "evaluate: the resume is queued ahead of the migration step");
        String resumeIfRegranted = body(repository, "public void resumeIfRegranted()");
        check(resumeIfRegranted.contains("if (TasksSyncPolicy.needsRegrantResume(TasksCloudGate.permission(account), ledger.consentWithdrawn())) {")
                && resumeIfRegranted.contains("e.resumeAfterConsentRegrant();"), "the executor resumes exactly when needsRegrantResume(gate, flag)");

        String gate = body(repository, "private TasksApi.SendGate sendGate()");
        check(gate.contains("final long epoch = consentEpoch;")
                && gate.contains("TasksSyncPolicy.sendAllowed(TasksCloudGate.permission(account), consentWithdrawn, epoch, consentEpoch)"),
                "send gate: sendAllowed over the gate, the flag and the epoch captured when the request is made");
        String remote = body(repository, "private final class Remote implements TasksCloudRemote, TasksCloudRealtime.Service");
        int calls = 0;
        Matcher call = Pattern.compile("TasksApi\\.forAccount\\(account\\)\\.(\\w+)\\(([^\\n]*)").matcher(remote);
        while (call.find()) {
            if ("identity".equals(call.group(1))) continue;
            calls++;
            check(call.group(2).contains("sendGate()"), "the adapter's " + call.group(1) + " call carries the send gate");
        }
        check(calls == 6, "the adapter makes the six named calls: " + calls);
        // Every change of the ledger's consent meta (open, discard, resume) is copied before anything else can send.
        Matcher change = Pattern.compile("(ledger = new TasksCloudLedger\\(sql\\);|discardAfterConsentWithdrawal\\(\\)\\[0\\];"
                + "|\\b(?:e|engine)\\.resumeAfterConsentRegrant\\(\\);)").matcher(repository);
        int changes = 0;
        while (change.find()) {
            changes++;
            String next = repository.substring(change.end(), repository.indexOf(';', change.end()) + 1).trim();
            check(next.equals("noteConsent();") || next.endsWith(".noteConsent();") || next.startsWith("noteConsent();"),
                    "the consent copies follow " + change.group(1) + " (next: " + next + ")");
        }
        check(changes == 5, "open, two discards and two resumes copy the consent meta: " + changes);

        String refresh = body(owner, "private static void refreshCutOverBlocking(int account)");
        check(refresh.contains("long verified = record == null ? -1L : record.verifiedGeneration;")
                && refresh.contains("TasksCutOver.ownCopyOnDevice(permission, state, scope, verified, () -> storeFacts(account))")
                && refresh.contains("TasksCutOver.evaluate(permission, state, scope, currentScope(account), unmapped, ownCopy)"),
                "the owner reads I1 from the account's store, with the record's verified generation, into the cut-over");
        check(body(owner, "private static TasksCutOver.StoreFacts storeFacts(int account)").contains("repository.cutOverStoreFacts()"),
                "I1 comes from the repository's store facts");
        // The repository only delegates (and still answers null on any failure): the read is the engine's.
        check(normalized(body(repository, "public TasksCutOver.StoreFacts cutOverStoreFacts()")).equals("{ try { return blocking(e -> "
                        + "e.cutOverStoreFacts(principal -> ProOrigin.importScope(originToken, principal))); } catch (RuntimeException e) { return null; } }"),
                "the repository's store facts are the engine's read with this origin's import scope, null on any failure");
        String read = body(source(androidRoot, "tasks/cloud/TasksCloudEngine.java"), "TasksCutOver.StoreFacts cutOverStoreFacts(ImportScope scope)");
        check(read.contains("String bound = ledger.readMeta(TasksCloudLedger.META_PRINCIPAL);") && read.contains("ledger.migrationChecked()")
                && read.contains("ledger.resyncGeneration()"), "the engine reads the bound principal, the store's check and its generation from the ledger");
        check(!read.contains("requirePrincipal(") && !read.contains("bind(") && !read.contains("bindPrincipal(")
                && !Pattern.compile("\\bprincipal\\b").matcher(read).find(), "the engine's read binds nothing and reads no in-memory principal");
        String context = body(repository, "@Override public TasksAutoMigration.CloudContext context()");
        check(context.contains("ledger.resnapshotPending()") && context.contains("ledger.resyncGeneration()") && context.contains("ledger.migrationChecked()"),
                "the migration context refuses while a resnapshot is pending and reads the generation and the check through the ledger");
        check(body(repository, "@Override public void markChecked()").contains("ledger.writeMeta(TasksCloudLedger.META_MIGRATION_CHECKED, \"1\");"),
                "markChecked writes the ledger's key");
        check(!Pattern.compile("\\bString\\s+META_MIGRATION_CHECKED\\b").matcher(repository).find(),
                "the repository declares no META_MIGRATION_CHECKED of its own (the ledger's key, cleared by a rebind)");

        // The Device archive's lines (RES-CX2-2): I1 is cached with each cut-over the owner computes.
        check(owner.contains("private static final AtomicBoolean[] ownCopies = newFlags();"), "the owner keeps one I1 flag per slot");
        check(refresh.contains("boolean ownCopy = false;") && refresh.contains("ownCopy = TasksCutOver.ownCopyOnDevice(permission, state, scope, verified,")
                && !refresh.contains("boolean ownCopy = TasksCutOver."), "refresh: I1 is false unless the evaluation reads it (FORBIDDEN, signed out)");
        check(normalized(refresh).contains("ownCopies[account].set(ownCopy); cutOvers[account].set(next); announceMode(account);"),
                "refresh: this evaluation's I1 is cached right before its cut-over (whoever reads the cut-over reads its I1 or a newer one)");
        check(normalized(evaluate).contains("ownCopies[account].set(false); cutOvers[account].set(TasksCutOver.NONE); announceMode(account);"),
                "evaluate: a slot without a repository drops its I1 with its cut-over");
        check(occurrences(owner, "ownCopies[account].set(") == 2, "I1 is written only there");
        check(normalized(body(owner, "public static boolean ownCopyOnDevice(int account)")).equals("{ return valid(account) && ownCopies[account].get(); }"),
                "ownCopyOnDevice(account) answers the cached I1 of a valid slot");
        check(!body(owner, "private static void announceMode(final int account)").contains("ownCop")
                && occurrences(owner, "postNotificationName(NotificationCenter.zenlygramTasksModeChanged)") == 1,
                "an I1 change alone announces nothing: updateMode would close the archive's open choice dialogs");

        // The Device archive's lines in this app (the import's ZenlyTasksActivity.showArchiveBanner, split by layer): the
        // device source reads the owner's I1 beside the cut-over, the record and the unsent counts
        // (ZgDeviceTasksSource.archive), the model decides which lines apply (ZgTasksArchive.of), and the text builder
        // words them in iOS's order (ZgTasksText.archiveText).
        String deviceSource = appSource(androidRoot, "tasks/source/ZgDeviceTasksSource.java");
        String archive = body(deviceSource, "private ZgTasksArchive archive()");
        check(archive.contains("boolean ownCopy = ZenlyTasksSyncOwner.ownCopyOnDevice(account);")
                && archive.contains("return ZgTasksArchive.of(cutOver, record, ownCopy, ZenlyTasksSyncOwner.cachedUnsentCounts(account));"),
                "the banner reads the owner's I1 beside the cut-over, the record and the unsent counts");
        String of = body(appSource(androidRoot, "tasks/model/ZgTasksArchive.java"),
                "public static ZgTasksArchive of(TasksCutOver cutOver, ZenlyTaskImport.MigrationRecord record, boolean ownCopy, int[] unsent)");
        check(of.contains("TasksCutOver.Record state = record == null ? null : TasksCutOver.Record.fromWire(record.state);"),
                "the banner reads the record's state");
        check(!of.contains("if (total > 0 && unsent != null && unsent.length > 1) {"), "no bare progress line (it read 'N of N' after a purge)");
        String progress = body(of, "if (TasksCutOver.archiveShowsProgress(cutOver, state, ownCopy) && total > 0 && unsent != null && unsent.length > 1)");
        check(normalized(progress).equals("{ done = Math.max(0, Math.min(total, total - unsent[1])); shown = total; }"),
                "the progress counts only by archiveShowsProgress, their arithmetic unchanged");
        check(normalized(of).contains("return new ZgTasksArchive(Reason.MIGRATING, done, shown, 0, keptLocal, TasksCutOver.archiveAwaitsCopy(cutOver, ownCopy));"),
                "'the Cloud copy returns' exactly by archiveAwaitsCopy");
        String banner = body(appSource(androidRoot, "tasks/ui/ZgTasksText.java"), "public static String archiveText(ZgTasksArchive archive)");
        check(normalized(body(banner, "if (archive.total > 0)")).equals("{ detail.append(\"\\n\").append(LocaleController.formatString("
                        + "R.string.ZG_tasks_migration_progress, archive.done, archive.total)); }"),
                "the progress line is worded from the model's counts, only while it has a total");
        check(normalized(body(banner, "if (archive.awaitsCopy)")).equals("{ detail.append(\"\\n\").append(LocaleController.getString("
                        + "R.string.ZG_tasks_archive_copyReturns)); }"),
                "'the Cloud copy returns' is worded exactly when the model says so");
        String tasksUi = appSources(androidRoot, "tasks");
        check(occurrences(tasksUi, "R.string.ZG_tasks_archive_copyReturns") == 1 && occurrences(tasksUi, "R.string.ZG_tasks_migration_progress") == 1,
                "each line is appended only there");
        int newRowsAt = banner.indexOf("\"ZG_tasks_archive_newRows\""), progressAt = banner.indexOf("R.string.ZG_tasks_migration_progress"),
                returnsAt = banner.indexOf("R.string.ZG_tasks_archive_copyReturns"), keptAt = banner.indexOf("\"ZG_tasks_migration_keptLocal\"");
        check(newRowsAt >= 0 && newRowsAt < progressAt && progressAt < returnsAt && returnsAt < keptAt,
                "the archive(migrating) branch: progress, then the Cloud copy returns, then kept local (iOS's order)");
        // Coming back to the screen: the board re-routes when the cut-over changed, and the device store it starts
        // reads the cut-over and the record again (a new I1 and unsent counts) without touching any screen.
        String onResume = body(appSource(androidRoot, "tasks/ui/ZgTasksBoardActivity.java"), "public void onResume()");
        check(onResume.contains("applyRouting();") && onResume.contains("pane.source.start(this);")
                        && onResume.indexOf("applyRouting();") < onResume.indexOf("pane.source.start(this);"),
                "resume: a changed cut-over re-routes the board, then the shown store is started");
        String started = body(deviceSource, "public void start(Object screen)");
        check(started.contains("cutOver = ZenlyTasksSyncOwner.cutOver(account);") && started.contains("refreshArchive();"),
                "resume: the device store reads the cut-over again and rebuilds its banner");
        String rebuild = body(deviceSource, "private void refreshArchive()");
        check(rebuild.contains("store.migrationRecord(account,") && rebuild.contains("publish();")
                        && !rebuild.contains("dismiss") && !rebuild.contains("Dialog") && !rebuild.contains("Fragment"),
                "the banner rebuild reads the record again and touches no editor or dialog");
        check(body(deviceSource, "private void modeChanged()").contains("refreshArchive();"), "a changed cut-over builds the banner the same way");
        String[][] copyReturns = {
                {"values", "The Cloud copy returns to this device once this account is confirmed with Zenlygram and Cloud has synced."},
                {"values-ru", "Облачная копия вернётся на это устройство, когда аккаунт будет подтверждён в Zenlygram и облако синхронизируется."},
                {"values-uz", "Bulutdagi nusxa bu hisob Zenlygram bilan tasdiqlanib, Bulut sinxronlangach shu qurilmaga qaytadi."}};
        for (String[] locale : copyReturns) {
            check(locale[1].equals(taskStrings(androidRoot, locale[0]).get("ZG_tasks_archive_copyReturns")),
                    locale[0] + ": ZG_tasks_archive_copyReturns is the text of iOS tasks_archive_copyReturns");
        }
    }

    private static int occurrences(String source, String part) {
        int count = 0;
        for (int at = source.indexOf(part); at >= 0; at = source.indexOf(part, at + part.length())) count++;
        return count;
    }

    /** The plain strings of {@code folder}/strings_zenlygram_tasks2.xml (TasksBatchSplitTests.cloudStrings' reader). */
    private static Map<String, String> taskStrings(String androidRoot, String folder) throws IOException {
        Map<String, String> values = new HashMap<>();
        Matcher string = Pattern.compile("<string name=\"(\\w+)\">([^<]*)</string>").matcher(new String(Files.readAllBytes(
                Paths.get(androidRoot, "TMessagesProj/src/main/res", folder, "strings_zenlygram_tasks2.xml")), StandardCharsets.UTF_8));
        while (string.find()) values.put(string.group(1), string.group(2));
        return values;
    }

    /** A source of the app's Zenlygram code (org.telegram.zenlygram), e.g. {@code tasks/ui/ZgTasksText.java}. */
    private static String appSource(String androidRoot, String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(androidRoot, "TMessagesProj/src/main/java/org/telegram/zenlygram", path)),
                StandardCharsets.UTF_8);
    }

    /** Every Java source under a package of the app's Zenlygram code, joined; the Cloud screens' own package is left out. */
    private static String appSources(String androidRoot, String directory) throws IOException {
        StringBuilder all = new StringBuilder();
        java.nio.file.Path root = Paths.get(androidRoot, "TMessagesProj/src/main/java/org/telegram/zenlygram", directory);
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(root)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.sorted()::iterator) {
                String name = file.toString();
                if (!name.endsWith(".java") || name.contains("/cloudui/")) continue;
                all.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    /** Whitespace runs as one space (line breaks and indentation do not matter). */
    private static String normalized(String code) {
        return code.replaceAll("\\s+", " ").trim();
    }

    private static String source(String androidRoot, String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(androidRoot, "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram", path)),
                StandardCharsets.UTF_8);
    }

    /** The braces-balanced body that follows {@code signature} (these bodies hold no brace in a string or comment). */
    private static String body(String source, String signature) {
        int at = source.indexOf(signature);
        check(at >= 0, "found: " + signature);
        int open = source.indexOf('{', at + signature.length());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced body: " + signature);
    }
}
