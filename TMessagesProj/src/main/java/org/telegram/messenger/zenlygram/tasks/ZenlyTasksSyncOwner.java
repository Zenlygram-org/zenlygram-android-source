package org.telegram.messenger.zenlygram.tasks;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.messenger.zenlygram.pro.ProOrigin;
import org.telegram.messenger.zenlygram.pro.TasksApi;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAnalyticsSink;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAutoMigration;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudGate;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudPolicy;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudRepository;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksCutOver;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksMigrationNormalizer;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksSyncPolicy;
import org.telegram.tgnet.ConnectionsManager;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-level owner of Cloud Tasks sync (design 30 §6.1, §6.5): bridges the customer-data layer into the pure Tasks code
 * and drives every signed-in account's repository whether or not a board is shown.
 * <ul>
 * <li>onPostInit installs the consent gate source ({@code tasksMayRun ? ALLOWED : recordingAllowed ? WAITING :
 * FORBIDDEN}; until then TasksCloudGate is FORBIDDEN), the analytics sink onto ZenlyStats and the local write fence,
 * and observes zenlygramCustomerStateChanged, zenlygramConsentChanged, didUpdateConnectionState and
 * zenlygramProStatusChanged.</li>
 * <li>Each input change re-evaluates the account: permission changes reach the repository (onPermissionChanged), an
 * ALLOWED account is driven in the foreground (startApp + requeue(occasion)), a dropped one stops. An ALLOWED account
 * whose database is still marked withdrawn resumes (TasksSyncPolicy.needsRegrantResume: a lost resume).</li>
 * <li>The automatic migration runs on one worker thread, one step at a time, after a cycle with a complete snapshot
 * (TasksAutoMigration); the cut-over per account is recomputed after every step and permission change, with I1 read
 * from the account's cloud store (a logout purges it and keeps the record).</li>
 * <li>Background: one more drain (flush), then stopApp when the in-flight request finished or after 10 s.</li>
 * <li>A driven account also gets its contact rule ({@link ZenlyTasksContactRule}): a task a Telegram contact assigned
 * to it is accepted by the device; the rule is torn down with the drive.</li>
 * </ul>
 * Main thread unless noted. Never logs task content or identifiers.
 */
public final class ZenlyTasksSyncOwner {
    private static final int SLOTS = UserConfig.MAX_ACCOUNT_COUNT;
    private static final long TIMER_MS = 300_000;
    private static final long FLUSH_MS = 10_000;
    /** State publications trigger a migration step at most this often per account (timer and inputs always do). */
    private static final long STEP_INTERVAL_MS = 10_000;
    private static final long STORE_WAIT_MS = 10_000;

    private static boolean started, foreground;
    private static final boolean[] driven = new boolean[SLOTS];
    private static final TasksCloudGate.Permission[] lastPermission = new TasksCloudGate.Permission[SLOTS];
    private static final TasksCloudRepository.Subscription[] subscriptions = new TasksCloudRepository.Subscription[SLOTS];
    private static final long[] lastStepAt = new long[SLOTS];
    // Any thread (the store queue reads them through the write fence).
    private static final AtomicReference<TasksCutOver>[] cutOvers = newCutOvers();
    /** I1 of the last cut-over evaluation (any thread): the record's copy is in the account's cloud store on this device. */
    private static final AtomicBoolean[] ownCopies = newFlags();
    /** The effective cut-over last announced with zenlygramTasksModeChanged (main thread). */
    private static final TasksCutOver[] announced = newAnnounced();
    private static final AtomicBoolean[] migrating = newFlags();
    private static final AtomicBoolean[] stepQueued = newFlags();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ZenlyTasksMigration");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.setDaemon(true);
        return thread;
    });

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            for (int account = 0; account < SLOTS; account++) evaluate(account, null, true);
            AndroidUtilities.runOnUIThread(this, TIMER_MS);
        }
    };

    private static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        if (id == NotificationCenter.zenlygramConsentChanged) {
            for (int slot = 0; slot < SLOTS; slot++) evaluate(slot, null, false);
        } else if (id == NotificationCenter.zenlygramCustomerStateChanged) {
            evaluate(account, TasksCloudPolicy.Occasion.LINK, true);
        } else if (id == NotificationCenter.zenlygramProStatusChanged) {
            evaluate(account, TasksCloudPolicy.Occasion.ENTITLEMENT, false);
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            if (ConnectionsManager.getInstance(account).getConnectionState() == ConnectionsManager.ConnectionStateConnected) {
                evaluate(account, TasksCloudPolicy.Occasion.NETWORK, false);
            }
        }
    };

    private static final TasksAutoMigration.Fence FENCE = new TasksAutoMigration.Fence() {
        @Override public void raise(int account) {
            if (valid(account)) migrating[account].set(true);
        }

        /** Worker: the cut-over reads the new record BEFORE the fence drops, so no write slips in as OPEN. */
        @Override public void lower(int account) {
            if (!valid(account)) return;
            try {
                refreshCutOverBlocking(account);
            } finally {
                migrating[account].set(false);
            }
        }
    };

    private ZenlyTasksSyncOwner() { }

    @SuppressWarnings("unchecked")
    private static AtomicReference<TasksCutOver>[] newCutOvers() {
        AtomicReference<TasksCutOver>[] values = new AtomicReference[SLOTS];
        for (int i = 0; i < SLOTS; i++) values[i] = new AtomicReference<>(TasksCutOver.NONE);
        return values;
    }

    private static TasksCutOver[] newAnnounced() {
        TasksCutOver[] values = new TasksCutOver[SLOTS];
        for (int i = 0; i < SLOTS; i++) values[i] = TasksCutOver.NONE;
        return values;
    }

    private static AtomicBoolean[] newFlags() {
        AtomicBoolean[] values = new AtomicBoolean[SLOTS];
        for (int i = 0; i < SLOTS; i++) values[i] = new AtomicBoolean();
        return values;
    }

    private static boolean valid(int account) {
        return account >= 0 && account < SLOTS;
    }

    /**
     * Any thread: posts zenlygramTasksModeChanged on the account's instance (UI thread) when its effective cut-over
     * ({@link #cutOver}, which also follows the gate) differs from the one last announced.
     */
    private static void announceMode(final int account) {
        if (!valid(account)) return;
        AndroidUtilities.runOnUIThread(() -> {
            TasksCutOver now = cutOver(account);
            TasksCutOver before = announced[account];
            announced[account] = now;
            if (!before.equals(now)) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.zenlygramTasksModeChanged);
            }
        });
    }

    // ---- lifecycle (ApplicationLoader) ----

    /** ApplicationLoader.postInitApplication, right after ZenlyCustomer.onPostInit. */
    public static void onPostInit() {
        if (started) return;
        started = true;
        TasksCloudGate.install(ZenlyTasksSyncOwner::gate);
        TasksAnalyticsSink.Holder.set(new StatsSink());
        ZenlyTasksStore.setWriteFence(account -> TasksSyncPolicy.writeState(valid(account) && migrating[account].get(), cutOver(account)));
        NotificationCenter.getGlobalInstance().addObserver(OBSERVER, NotificationCenter.zenlygramConsentChanged);
        for (int account = 0; account < SLOTS; account++) {
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(OBSERVER, NotificationCenter.zenlygramCustomerStateChanged);
            center.addObserver(OBSERVER, NotificationCenter.zenlygramProStatusChanged);
            center.addObserver(OBSERVER, NotificationCenter.didUpdateConnectionState);
        }
        for (int account = 0; account < SLOTS; account++) evaluate(account, TasksCloudPolicy.Occasion.LAUNCH, true);
    }

    /** ApplicationLoader.onResume: every ALLOWED account syncs again; the 300 s timer runs while in the foreground. */
    public static void onAppResumed() {
        if (!started) return;
        boolean wasForeground = foreground;
        foreground = true;
        for (int account = 0; account < SLOTS; account++) evaluate(account, TasksCloudPolicy.Occasion.FOREGROUND, true);
        if (!wasForeground) {
            AndroidUtilities.cancelRunOnUIThread(TICK);
            AndroidUtilities.runOnUIThread(TICK, TIMER_MS);
        }
    }

    /** ApplicationLoader.onPause: drain once more; stopApp when the in-flight request finished or after 10 s. */
    public static void onAppPaused() {
        if (!started) return;
        foreground = false;
        AndroidUtilities.cancelRunOnUIThread(TICK);
        for (int account = 0; account < SLOTS; account++) {
            if (!driven[account]) continue;
            final TasksCloudRepository repository = repository(account);
            if (repository == null) continue;
            repository.flush(FLUSH_MS, (counts, error) -> {
                if (!foreground) repository.stopApp();
            });
        }
    }

    // ---- reads (any thread unless noted) ----

    public static TasksCloudGate.Permission permission(int account) {
        return TasksCloudGate.permission(account);
    }

    /** §7.5 as last computed; NONE while the gate is FORBIDDEN (a withdrawal makes the board writable at once). */
    public static TasksCutOver cutOver(int account) {
        if (!valid(account) || TasksCloudGate.permission(account) == TasksCloudGate.Permission.FORBIDDEN) return TasksCutOver.NONE;
        return cutOvers[account].get();
    }

    /**
     * I1 of the last cut-over evaluation: the record's copy is in this account's cloud store on this device. Meaningful
     * only beside an ARCHIVE cut-over (the Device archive's lines, TasksCutOver.archiveShowsProgress / archiveAwaitsCopy,
     * both require one): under a FORBIDDEN gate {@link #cutOver} answers NONE while this keeps the cached flag. A change
     * of it alone announces nothing (the open archive picks it up when its banner is built again).
     */
    public static boolean ownCopyOnDevice(int account) {
        return valid(account) && ownCopies[account].get();
    }

    /** {user, migration} unsent cloud changes of the last publication; null = unknown. */
    public static int[] cachedUnsentCounts(int account) {
        TasksCloudRepository repository = repository(account);
        return repository == null ? null : repository.cachedUnsentCounts();
    }

    /**
     * LogoutActivity: what logging out leaves behind in this account's cloud outbox (resolved by resource name, so the
     * strings may ship with the UI round); null when nothing to say or unknown (the generic logout text stays).
     */
    public static String logoutNotice(Context context, int account) {
        if (context == null) return null;
        int[] counts = cachedUnsentCounts(account);
        if (counts == null) return null;
        StringBuilder notice = new StringBuilder();
        if (counts[0] > 0) {
            String text = string(context, "ZenlyTasksLogoutUnsent");
            if (text != null) notice.append(String.format(text, counts[0]));
        }
        if (counts.length > 1 && counts[1] > 0) {
            String text = string(context, "ZenlyTasksLogoutMigration");
            if (text != null) notice.append(notice.length() > 0 ? "\n\n" : "").append(text);
        }
        return notice.length() == 0 ? null : notice.toString();
    }

    private static String string(Context context, String name) {
        try {
            // ZENLYGRAM 13.2.1: by id, through the app's string table (a release build has no string resources to look up by name).
            if ("ZenlyTasksLogoutUnsent".equals(name)) return LocaleController.getString(org.telegram.messenger.R.string.ZenlyTasksLogoutUnsent);
            if ("ZenlyTasksLogoutMigration".equals(name)) return LocaleController.getString(org.telegram.messenger.R.string.ZenlyTasksLogoutMigration);
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- gate ----

    /** Any thread (TasksCloudGate source). */
    private static TasksCloudGate.Permission gate(int account) {
        long tg = UserConfig.getInstance(account).getClientUserId();
        if (tg <= 0) return TasksCloudGate.Permission.FORBIDDEN;
        return TasksSyncPolicy.permission(ZenlyCustomer.tasksMayRun(account), ZenlyConsent.recordingAllowed(tg));
    }

    // ---- per-account evaluation (main thread) ----

    private static TasksCloudRepository repository(int account) {
        if (!valid(account) || !UserConfig.getInstance(account).isClientActivated()) return null;
        try {
            return TasksCloudRepository.forAccount(account);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Re-reads the gate, tells the repository about a permission change, resumes a database still marked withdrawn under
     * an ALLOWED gate, drives or stops it, requeues on the occasion (null = none) and, for an ALLOWED account, queues a
     * migration step ({@code step}) and the cut-over.
     */
    private static void evaluate(int account, TasksCloudPolicy.Occasion occasion, boolean step) {
        if (!valid(account)) return;
        TasksCloudRepository repository = repository(account);
        if (repository == null) {
            driven[account] = false;
            lastPermission[account] = null;
            unsubscribe(account);
            ZenlyTasksContactRule.detach(account);
            ownCopies[account].set(false);
            cutOvers[account].set(TasksCutOver.NONE);
            announceMode(account);
            return;
        }
        TasksCloudGate.Permission permission = TasksCloudGate.permission(account);
        boolean changed = permission != lastPermission[account];
        lastPermission[account] = permission;
        if (changed) {
            repository.onPermissionChanged();
            announceMode(account);
        }
        // A re-grant whose resume was lost (it reached the executor before the withdrawal's discard, or the process died)
        // leaves Cloud Tasks in CONSENT_REQUIRED under an ALLOWED gate. The executor checks the flag and resumes ahead of
        // the step queued below.
        if (permission == TasksCloudGate.Permission.ALLOWED) repository.resumeIfRegranted();
        switch (TasksSyncPolicy.driveAction(permission, driven[account])) {
            case DRIVE:
                if (foreground) {
                    repository.startApp();
                    if (occasion != null) repository.requeue(occasion);
                }
                driven[account] = true;
                subscribe(account, repository);
                // The contact rule's device-local inputs (contacts, blocked list, senders' flags) for this account.
                ZenlyTasksContactRule.attach(account, repository);
                break;
            case STOP:
                repository.stopApp();
                driven[account] = false;
                unsubscribe(account);
                ZenlyTasksContactRule.detach(account);
                break;
            default:
                break;
        }
        if (permission == TasksCloudGate.Permission.ALLOWED && (step || changed)) {
            queueStep(account, repository);
        } else if (changed || step) {
            queueCutOver(account);
        }
    }

    /** Publications of a driven repository: a cycle may have completed its snapshot, so a step may run (rate limited). */
    private static void subscribe(final int account, final TasksCloudRepository repository) {
        if (subscriptions[account] != null) return;
        subscriptions[account] = repository.subscribe(state -> {
            if (state == null || state.status != org.telegram.messenger.zenlygram.tasks.cloud.TasksCloudState.Status.READY) return;
            long now = System.currentTimeMillis();
            if (now - lastStepAt[account] < STEP_INTERVAL_MS) return;
            if (TasksCloudGate.permission(account) == TasksCloudGate.Permission.ALLOWED) queueStep(account, repository);
        });
    }

    private static void unsubscribe(int account) {
        TasksCloudRepository.Subscription subscription = subscriptions[account];
        subscriptions[account] = null;
        if (subscription != null) subscription.unsubscribe();
    }

    // ---- migration worker ----

    private static void queueStep(final int account, final TasksCloudRepository repository) {
        lastStepAt[account] = System.currentTimeMillis();
        if (!stepQueued[account].compareAndSet(false, true)) return;
        final long tg = UserConfig.getInstance(account).getClientUserId();
        final String locale = TasksMigrationNormalizer.localeFor(LocaleController.getInstance().getCurrentLocale().getLanguage());
        WORKER.execute(() -> {
            stepQueued[account].set(false);
            if (UserConfig.getInstance(account).getClientUserId() != tg || tg <= 0) return;
            if (TasksCloudGate.permission(account) == TasksCloudGate.Permission.ALLOWED) {
                try {
                    // The device clock is only the fallback: the step's time is the server estimate its cloud context
                    // carries (design 30 §7.1; a fast device clock would put fresh rows in the server's future).
                    TasksAutoMigration.step(account, new StoreLocal(account), repository.migrationCloud(), FENCE, locale,
                            System.currentTimeMillis());
                } catch (RuntimeException e) {
                    // Reported by the step (analytics, content-free); it reruns with the next trigger.
                    FileLog.e("Zenlygram Tasks migration step did not finish");
                }
            }
            try {
                refreshCutOverBlocking(account);
            } catch (RuntimeException ignored) {
                // The last cut-over stays.
            }
        });
    }

    private static void queueCutOver(final int account) {
        WORKER.execute(() -> {
            try {
                refreshCutOverBlocking(account);
            } catch (RuntimeException ignored) {
                // The last cut-over stays.
            }
        });
    }

    /** Worker only: P, the record, its scope, the current scope, U and I1 into the §7.5 table. */
    private static void refreshCutOverBlocking(int account) {
        TasksCloudGate.Permission permission = TasksCloudGate.permission(account);
        TasksCutOver next;
        boolean ownCopy = false;
        if (permission == TasksCloudGate.Permission.FORBIDDEN || !UserConfig.getInstance(account).isClientActivated()) {
            next = TasksCutOver.NONE;
        } else {
            StoreLocal local = new StoreLocal(account);
            ZenlyTaskImport.MigrationRecord record = local.record();
            TasksCutOver.Record state = record == null ? null : TasksCutOver.Record.fromWire(record.state);
            String scope = record == null ? null : record.scope;
            int unmapped = scope != null ? local.unmappedLiveRowCount(scope) : 0;
            // I1: a logout purged the account's cloud store and kept its record, so the record alone does not prove the copy
            // is on this device; the account's store is read (as iOS does) before a completed copy may retire the board,
            // and the step must have checked the copy against that store at its resync generation (the record's verified).
            long verified = record == null ? -1L : record.verifiedGeneration;
            ownCopy = TasksCutOver.ownCopyOnDevice(permission, state, scope, verified, () -> storeFacts(account));
            next = TasksCutOver.evaluate(permission, state, scope, currentScope(account), unmapped, ownCopy);
        }
        // I1 first: whoever reads the new cut-over reads this evaluation's I1 (or a newer one) with it.
        ownCopies[account].set(ownCopy);
        cutOvers[account].set(next);
        announceMode(account);
    }

    /** Worker only: what the account's cloud store on this device holds; null when it does not open (no copy here). */
    private static TasksCutOver.StoreFacts storeFacts(int account) {
        TasksCloudRepository repository = repository(account);
        return repository == null ? null : repository.cutOverStoreFacts();
    }

    /** The import scope of the account's current binding, null when unbound. */
    private static String currentScope(int account) {
        try {
            TasksApi.Identity identity = TasksApi.forAccount(account).identity();
            if (identity == null || identity.principal == null) return null;
            return ProOrigin.importScope(ZenlyPro.storageToken(), identity.principal);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Blocking adapter over the account's local store for the worker (callbacks arrive on the main thread). */
    private static final class StoreLocal implements TasksAutoMigration.Local {
        private final int account;

        StoreLocal(int account) {
            this.account = account;
        }

        private interface Call<T> { void start(ZenlyTasksStore store, ZenlyTasksStore.Callback<T> callback); }

        private <T> T await(Call<T> call) {
            final ZenlyTasksStore store;
            try {
                store = ZenlyTasksStore.get(account);
            } catch (IllegalStateException e) {
                throw new TasksAutoMigration.Failure("account_changed");
            }
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<T> value = new AtomicReference<>();
            final AtomicReference<ZenlyTasksStore.Failure> failure = new AtomicReference<>();
            call.start(store, (result, error) -> {
                value.set(result);
                failure.set(error);
                done.countDown();
            });
            try {
                if (!done.await(STORE_WAIT_MS, TimeUnit.MILLISECONDS)) throw new TasksAutoMigration.Failure("timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TasksAutoMigration.Failure("interrupted");
            }
            if (failure.get() != null) throw new TasksAutoMigration.Failure("sqlite_" + failure.get().name().toLowerCase(java.util.Locale.ROOT));
            return value.get();
        }

        @Override public ZenlyTaskImport.MigrationRecord record() {
            return await((ZenlyTasksStore store, ZenlyTasksStore.Callback<ZenlyTaskImport.MigrationRecord> callback) -> store.migrationRecord(account, callback));
        }

        @Override public boolean replaceRecord(ZenlyTaskImport.MigrationRecord expected, ZenlyTaskImport.MigrationRecord next) {
            Boolean written = await((ZenlyTasksStore store, ZenlyTasksStore.Callback<Boolean> callback) -> store.setMigrationRecord(account, next, expected, callback));
            return Boolean.TRUE.equals(written);
        }

        @Override public boolean hasAnyImportMapping() {
            return Boolean.TRUE.equals(await((ZenlyTasksStore store, ZenlyTasksStore.Callback<Boolean> callback) -> store.hasAnyImportMapping(account, callback)));
        }

        @Override public ZenlyTaskImport.Snapshot importSnapshot(String scope) {
            return await((ZenlyTasksStore store, ZenlyTasksStore.Callback<ZenlyTaskImport.Snapshot> callback) -> store.importSnapshot(account, scope, callback));
        }

        @Override public void recordImportMappings(String scope, List<ZenlyTaskImport.Mapping> mappings) {
            await((ZenlyTasksStore store, ZenlyTasksStore.Callback<Void> callback) -> store.recordImportMappings(account, scope, mappings, callback));
        }

        @Override public void markImportReverified(List<String> mutationIds, long generation) {
            await((ZenlyTasksStore store, ZenlyTasksStore.Callback<Void> callback) -> store.markImportReverified(account, mutationIds, generation, callback));
        }

        @Override public void invalidateVerification() {
            await((ZenlyTasksStore store, ZenlyTasksStore.Callback<Void> callback) -> store.invalidateMigrationVerification(account, callback));
        }

        int unmappedLiveRowCount(String scope) {
            Integer count = await((ZenlyTasksStore store, ZenlyTasksStore.Callback<Integer> callback) -> store.unmappedLiveRowCount(account, scope, callback));
            return count == null ? 0 : count;
        }
    }

    /** Typed Tasks analytics onto the customer-data facade (content-free; never throws). */
    private static final class StatsSink implements TasksAnalyticsSink {
        private static CustomerEvents.TasksScope scope(boolean cloud) {
            return cloud ? CustomerEvents.TasksScope.CLOUD : CustomerEvents.TasksScope.LOCAL;
        }

        @Override public void taskCreated(int account, boolean cloud, boolean hasDue, int priority, boolean inList) {
            ZenlyStats.taskCreated(account, scope(cloud), hasDue, priority, inList);
        }

        @Override public void taskCompleted(int account, boolean cloud, boolean reopened) {
            ZenlyStats.taskCompleted(account, scope(cloud), reopened);
        }

        @Override public void taskDeleted(int account, boolean cloud) {
            ZenlyStats.taskDeleted(account, scope(cloud));
        }

        @Override public void listCreated(int account, boolean cloud) {
            ZenlyStats.listCreated(account, scope(cloud));
        }

        @Override public void listDeleted(int account, boolean cloud, int tasks) {
            ZenlyStats.listDeleted(account, scope(cloud), tasks);
        }

        @Override public void shared(int account, String action) {
            for (CustomerEvents.SharedAction value : CustomerEvents.SharedAction.values()) {
                if (value.wire.equals(action)) ZenlyStats.tasksShared(account, value);
            }
        }

        @Override public void migration(int account, String phase, Integer lists, Integer tasks, Integer skipped, Long durationS, String errorCode) {
            for (CustomerEvents.MigrationPhase value : CustomerEvents.MigrationPhase.values()) {
                if (value.wire.equals(phase)) ZenlyStats.tasksMigration(account, value, lists, tasks, skipped, durationS, errorCode);
            }
        }

        @Override public void syncIssue(int account, String kind) {
            for (CustomerEvents.SyncIssueKind value : CustomerEvents.SyncIssueKind.values()) {
                if (value.wire.equals(kind)) ZenlyStats.tasksSyncIssue(account, value);
            }
        }

        @Override public void error(int account, String domain, String code) {
            for (CustomerEvents.ErrorDomain value : CustomerEvents.ErrorDomain.values()) {
                if (value.wire.equals(domain)) ZenlyStats.error(account, value, code);
            }
        }
    }
}
