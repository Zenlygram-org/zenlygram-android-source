package org.telegram.messenger.zenlygram.tasks.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.ProOrigin;
import org.telegram.messenger.zenlygram.pro.TasksApi;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.messenger.zenlygram.tasks.ZenlyTaskImport;
import org.telegram.messenger.zenlygram.tasks.ZenlyTasksStore;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Account-scoped cloud Tasks repository (one per authenticated origin and raw Telegram user id). UI talks only to this
 * class and immutable {@link TasksCloudState}; SQL, JSON and transport stay behind it. All state work is serialized on
 * one executor. A repository touches only its own origin's database ({@link TasksCloudSchema#fileName}), and only while
 * that origin is the configured one and the slot holds its user; logout and launch cleanup cover every origin.
 */
public final class TasksCloudRepository {
    public interface Observer { void onStateChanged(TasksCloudState state); }

    public interface Subscription { void unsubscribe(); }

    public interface Result<T> { void complete(T value, TasksCloudError error); }

    public interface HeldDelete {
        String taskId();

        String mutationId();

        void undo(Result<Void> done);

        /** Snackbar gone / timeout / screen closed: promotes the held delete exactly once. */
        void dismiss();
    }

    public static final long HELD_DELETE_TIMEOUT_MS = TasksCloudEngine.HELD_DELETE_TIMEOUT_MS;

    /**
     * Cleanup markers of the origin-scoped databases; {@code cleanup_<rawTG>} means "wipe this user's cloud databases in
     * every origin". The pre-origin marker file ("zenly_tasks_cloud") is never requested.
     */
    private static final String PREFERENCES = "zenly_tasks_cloud.v2";
    private static final String CLEANUP_PREFIX = "cleanup_";
    private static final String[] FILE_SUFFIXES = {"", "-journal", "-wal", "-shm"};

    private static final Object LOCK = new Object();
    /** Keyed {@code <origin storage token>:<rawTG>}: the same user under another origin is another repository. */
    private static final HashMap<String, TasksCloudRepository> INSTANCES = new HashMap<>();
    /** Wipe obligations (in memory + durable marker); discharged only after files are gone AND the marker cleared. */
    private static final TasksCloudCleanup CLEANUP = new TasksCloudCleanup(new AndroidMarkers(), new AndroidFiles());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ZenlyTasksCloud");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        executorThread = thread;
        return thread;
    });
    /** The EXECUTOR's thread: a synchronous seam called on it runs inline instead of waiting on itself. */
    private static volatile Thread executorThread;
    /** The app-level owner (ZenlyTasksSyncOwner through startApp): drives the engine, never realtime. */
    private static final Object APP_OWNER = new Object();
    /** onConsentWithdrawn waits this long for the discard; past it the count is unknown (-1), the discard still runs. */
    private static final long WITHDRAW_WAIT_MS = 2_000;
    private static final long FLUSH_POLL_MS = 100;
    /** Fires realtime timers; the action itself always runs on EXECUTOR (one daemon thread, never one per timer). */
    private static final ScheduledExecutorService REALTIME_TIMER = realtimeTimer();

    private static ScheduledExecutorService realtimeTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "ZenlyTasksRealtimeTimer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private final int account;
    private final long rawTelegramId;
    /** Storage token of the origin this repository was created under; its database is named with it. */
    private final String originToken;
    private volatile boolean closed;
    // Executor-confined.
    private CloudDatabase database;
    private TasksCloudEngine engine;
    /** The engine's ledger (meta reads of the migration step: resync generation, pending import acks). */
    private TasksCloudLedger ledger;
    /**
     * Content-free realtime hints while the board is visible in the foreground (null until the engine opens). Written
     * on the executor only; volatile because network callbacks read it on other threads to fence a replaced controller.
     */
    private volatile TasksCloudRealtime realtime;
    /** A visible board is resumed (main thread writes through run(), the executor reads): realtime is board-only. */
    private volatile boolean realtimeVisible;
    /** Executor-confined: the engine's account-level hold as last seen by the realtime controller. */
    private boolean realtimePaused;
    /** {user, migration} unsent entries of the last publication; null = unknown. Written on the executor. */
    private volatile int[] unsentCounts;
    /**
     * This database's consent flag and epoch (TasksCloudLedger meta), copied on the executor whenever they change (open,
     * discard, resume) for the send gate, which TasksApi asks on the Pro worker ({@link #sendGate}).
     */
    private volatile boolean consentWithdrawn;
    private volatile long consentEpoch;
    // Main-thread-confined.
    private TasksCloudState current;
    private final ArrayList<Observer> observers = new ArrayList<>();
    /** Resumed board screens and the app-level owner; sync stops only when the last one leaves, realtime with the last board. */
    private final TasksCloudBoards boards = new TasksCloudBoards();
    /** Last observed linked principal/generation; decides which states may be shown (main thread). */
    private final TasksCloudIdentityGate gate = new TasksCloudIdentityGate();
    /** Assigned in the constructor after {@link #account} (definite assignment for the lambda body, JLS 16). */
    private final NotificationCenter.NotificationCenterDelegate proObserver;

    public static TasksCloudRepository forAccount(int account) {
        long rawTelegramId = UserConfig.getInstance(account).getClientUserId();
        if (rawTelegramId <= 0) throw new IllegalStateException("Account is not signed in");
        TasksCloudRepository replaced = null, repository;
        synchronized (LOCK) {
            // Read once (lock-free accessor): the registry key and the repository's database name name the same origin.
            String originToken = ZenlyPro.storageToken();
            String key = instanceKey(originToken, rawTelegramId);
            repository = INSTANCES.get(key);
            if (repository != null && repository.account != account) {
                replaced = repository;
                repository = null;
            }
            if (repository == null) {
                repository = new TasksCloudRepository(account, rawTelegramId, originToken);
                INSTANCES.put(key, repository);
            }
        }
        if (replaced != null) {
            // Same user moved slot: the old handle closes before the new one opens (FIFO executor); data stays.
            replaced.fence();
            final TasksCloudRepository old = replaced;
            EXECUTOR.execute(old::closeOnExecutor);
        }
        return repository;
    }

    private static String instanceKey(String originToken, long rawTelegramId) {
        return originToken + ":" + rawTelegramId;
    }

    /**
     * Under the origin configured at this moment. Frozen signature: TasksCloudLegacyIsolationRuntimeTest builds fixture
     * repositories with it reflectively (with fence, closeOnExecutor, EXECUTOR, INSTANCES and LOCK).
     */
    private TasksCloudRepository(int account, long rawTelegramId) {
        this(account, rawTelegramId, ZenlyPro.storageToken());
    }

    private TasksCloudRepository(int account, long rawTelegramId, String originToken) {
        this.account = account;
        this.rawTelegramId = rawTelegramId;
        this.originToken = originToken;
        this.proObserver = (id, slot, args) -> {
            if (id == NotificationCenter.zenlygramProStatusChanged && slot == this.account) onProStatusChanged();
        };
        this.current = TasksCloudState.empty(TasksCloudState.Status.LOADING, rawTelegramId);
    }

    public long rawTelegramId() {
        return rawTelegramId;
    }

    // ---- parent lifecycle hooks ----

    /**
     * Before ZenlyPro.unlink / UserConfig.clearConfig with the rawTG captured before clearing. Synchronously commits the
     * durable cleanup marker (returned) and fences every live repository of this user, whatever its origin; the exact
     * wipe of this user's cloud database files in every origin is queued and clears the marker only once every file is
     * verifiably gone. Local tasks and pre-origin (legacy) files are never touched.
     */
    public static boolean onLogout(int account, long rawTelegramId) {
        if (rawTelegramId <= 0) return false;
        boolean marker = CLEANUP.markForLogout(rawTelegramId);
        final List<TasksCloudRepository> fenced = removeEveryOrigin(rawTelegramId);
        for (TasksCloudRepository repository : fenced) repository.fence();
        EXECUTOR.execute(() -> wipe(rawTelegramId, fenced));
        return marker;
    }

    /**
     * After every UserConfig.loadConfig, before account stacks / Pro network. Non-positive entries are ignored. Owners
     * are discovered from origin-scoped names only (any origin); a legacy file is never an owner.
     */
    public static void reconcileAfterLaunch(long[] liveRawTelegramIds) {
        final HashSet<Long> live = new HashSet<>();
        if (liveRawTelegramIds != null) for (long id : liveRawTelegramIds) if (id > 0) live.add(id);
        EXECUTOR.execute(() -> {
            ArrayList<Long> owners = new ArrayList<>();
            String[] names = ApplicationLoader.applicationContext.databaseList();
            if (names != null) {
                for (String name : names) {
                    TasksCloudSchema.Owner owner = TasksCloudSchema.ownerOf(stripSuffix(name));
                    if (owner != null) owners.add(owner.rawTelegramId);
                }
            }
            for (long id : CLEANUP.launchTargets(live, owners)) {
                List<TasksCloudRepository> fenced = removeEveryOrigin(id);
                CLEANUP.require(id);
                for (TasksCloudRepository repository : fenced) repository.fence();
                wipe(id, fenced);
            }
        });
    }

    /**
     * Unregisters every repository of this user, whatever its origin; the caller fences them. (INSTANCES.remove(rawTG)
     * would still compile against the token-keyed map and silently remove nothing.)
     */
    private static List<TasksCloudRepository> removeEveryOrigin(long rawTelegramId) {
        ArrayList<TasksCloudRepository> removed = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<Map.Entry<String, TasksCloudRepository>> entries = INSTANCES.entrySet().iterator();
            while (entries.hasNext()) {
                TasksCloudRepository repository = entries.next().getValue();
                if (repository.rawTelegramId != rawTelegramId) continue;
                removed.add(repository);
                entries.remove();
            }
        }
        return removed;
    }

    // ---- Tasks seams for the customer-data client (30-tasks-r4 §6.1; signatures frozen, Tasks R4 fills the bodies) ----

    /**
     * Unsent user-origin cloud changes of this account (every outbox state but acknowledged), delivered on the main
     * thread; -1 with an error = unknown (no count shown).
     */
    public static void unsentChangeCount(int account, Result<Integer> done) {
        if (done == null) return;
        TasksCloudRepository repository;
        try {
            repository = forAccount(account);
        } catch (RuntimeException e) {
            AndroidUtilities.runOnUIThread(() -> done.complete(-1, new TasksCloudError(TasksCloudError.Code.NOT_LINKED)));
            return;
        }
        repository.call((Integer count, TasksCloudError error) -> done.complete(error == null ? count : -1, error),
                e -> e.unsentCounts()[0]);
    }

    /**
     * Consent withdrawn for this user: nothing of theirs may be uploaded again. Under the configured origin, in one
     * cloud transaction: every unsent outbox entry is discarded, the account is marked withdrawn and its consent epoch
     * advances, so no insert (edit, import, migration step) is queued until {@link #resumeAfterConsentRegrant}; no Tasks
     * request follows; the cache stays readable. Returns the discarded user-origin count, -1 = unknown (no configured
     * origin, the slot holds someone else, storage, or it took longer than 2 s — the discard still completes). Any
     * thread. The local store releases the account's migration (mappings gone, record {@code released}), so a re-grant
     * claims afresh.
     */
    public static int onConsentWithdrawn(int account, long rawTelegramId) {
        if (rawTelegramId <= 0) return -1;
        ZenlyTasksStore.releaseMigration(account, rawTelegramId, ZenlyTaskImport.MigrationRecord.REASON_WITHDRAWN);
        final TasksCloudRepository repository;
        try {
            if (UserConfig.getInstance(account).getClientUserId() != rawTelegramId) return -1;
            repository = forAccount(account);
        } catch (RuntimeException e) {
            return -1;
        }
        final int[] discarded = {-1};
        final CountDownLatch done = new CountDownLatch(1);
        Runnable work = () -> {
            try {
                discarded[0] = repository.engine().discardAfterConsentWithdrawal()[0];
                repository.noteConsent();
                repository.updateRealtime();
            } catch (RuntimeException e) {
                // Not linked under the configured origin, fenced or storage: nothing of this origin can be uploaded now.
            } finally {
                done.countDown();
            }
        };
        if (Thread.currentThread() == executorThread) {
            work.run();
            return discarded[0];
        }
        EXECUTOR.execute(work);
        try {
            if (!done.await(WITHDRAW_WAIT_MS, TimeUnit.MILLISECONDS)) return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
        return discarded[0];
    }

    /**
     * The user's backend account was erased (deletion done). The cloud part is exactly logout's fence and wipe; the
     * local store releases the migration and then clears its record, so a later link to a new core account claims
     * afresh. Any thread.
     */
    public static void onAccountErased(int account, long rawTelegramId) {
        onLogout(account, rawTelegramId);
        ZenlyTasksStore.releaseMigration(account, rawTelegramId, ZenlyTaskImport.MigrationRecord.REASON_ERASED);
        ZenlyTasksStore.clearMigrationRecord(account, rawTelegramId);
    }

    private static String stripSuffix(String name) {
        for (int i = FILE_SUFFIXES.length - 1; i > 0; i--) {
            if (name.endsWith(FILE_SUFFIXES[i])) return name.substring(0, name.length() - FILE_SUFFIXES[i].length());
        }
        return name;
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * The manual answers to invitations of one raw Telegram user (TasksInviteAnswers): a preferences file of its own,
     * which no logout, withdrawal or cloud wipe touches — after a re-login nothing could rebuild it, and it holds only
     * content-free ids and times. Executor only.
     */
    private static final class InviteAnswersStore implements TasksInviteAnswers.Store {
        private static final String FILE = "zenly_tasks_invite_answers";
        private final String key;

        InviteAnswersStore(long rawTelegramId) {
            key = Long.toString(rawTelegramId);
        }

        private static SharedPreferences file() {
            return ApplicationLoader.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }

        @Override public String load() {
            try {
                return file().getString(key, null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override public void save(String json) {
            try {
                SharedPreferences.Editor editor = file().edit();
                if (json == null) editor.remove(key);
                else editor.putString(key, json);
                editor.commit();
            } catch (RuntimeException e) {
                // Not durable this time: the rule may accept a contact's later invitation of the same task.
            }
        }
    }

    /** SharedPreferences markers; commit() false is reported as not durable (its memory may still have changed). */
    private static final class AndroidMarkers implements TasksCloudCleanup.Markers {
        @Override public boolean commit(long rawTelegramId, boolean present) {
            try {
                SharedPreferences.Editor editor = preferences().edit();
                if (present) editor.putBoolean(CLEANUP_PREFIX + rawTelegramId, true);
                else editor.remove(CLEANUP_PREFIX + rawTelegramId);
                return editor.commit();
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override public boolean present(long rawTelegramId) {
            try {
                return preferences().getBoolean(CLEANUP_PREFIX + rawTelegramId, false);
            } catch (RuntimeException e) {
                return true; // unreadable obligation: wipe rather than reopen
            }
        }

        @Override public Set<Long> all() {
            HashSet<Long> ids = new HashSet<>();
            try {
                for (String key : preferences().getAll().keySet()) {
                    if (!key.startsWith(CLEANUP_PREFIX)) continue;
                    try {
                        ids.add(Long.parseLong(key.substring(CLEANUP_PREFIX.length())));
                    } catch (NumberFormatException ignored) {
                        // Not ours; leave it.
                    }
                }
            } catch (RuntimeException ignored) {
                // Unreadable: per-open present() still refuses to reopen.
            }
            return ids;
        }
    }

    /**
     * Exactly this user's cloud database files in every origin (main, -journal, -wal, -shm), found by their
     * origin-scoped names; a legacy name is never constructed or matched.
     */
    private static final class AndroidFiles implements TasksCloudCleanup.Files {
        @Override public boolean deleteAll(long rawTelegramId) {
            try {
                Context context = ApplicationLoader.applicationContext;
                HashSet<String> owned = new HashSet<>();
                String[] names = context.databaseList();
                if (names != null) {
                    for (String name : names) {
                        String main = stripSuffix(name);
                        TasksCloudSchema.Owner owner = TasksCloudSchema.ownerOf(main);
                        if (owner != null && owner.rawTelegramId == rawTelegramId) owned.add(main);
                    }
                }
                boolean gone = true;
                for (String name : owned) {
                    context.deleteDatabase(name);
                    File main = context.getDatabasePath(name);
                    for (String suffix : FILE_SUFFIXES) {
                        File file = new File(main.getPath() + suffix);
                        if (file.exists() && !file.delete()) gone = false;
                    }
                }
                return gone;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** Executor only. Closes every fenced handle, then discharges the obligation only if files AND marker are cleared. */
    private static boolean wipe(long rawTelegramId, List<TasksCloudRepository> fenced) {
        for (TasksCloudRepository repository : fenced) repository.closeOnExecutor();
        boolean done = CLEANUP.wipe(rawTelegramId);
        if (!done) FileLog.e("Zenlygram cloud Tasks cleanup incomplete; retried on next open or launch");
        return done;
    }

    /** Any thread: from now on no callback, timer or open can touch this repository's database. */
    private void fence() {
        closed = true;
        AndroidUtilities.runOnUIThread(() -> {
            if (!observers.isEmpty()) NotificationCenter.getInstance(account).removeObserver(proObserver, NotificationCenter.zenlygramProStatusChanged);
            current = TasksCloudState.empty(TasksCloudState.Status.CLOSED, rawTelegramId);
            for (Observer observer : new ArrayList<>(observers)) observer.onStateChanged(current);
        });
    }

    /**
     * Main thread. Presentation-only Pro changes (expiry, policy, access) are ignored. A different linked principal
     * immediately hides the old one's view and fences its already-queued publications; the engine rebinds on its
     * executor. A bare generation change (re-link/rotation) lets the engine re-evaluate pauses. Unlinked keeps the cache.
     */
    private void onProStatusChanged() {
        if (!closed) show(current);
    }

    /** Fresh Pro identity of THIS repository's Telegram user; null when the lookup fails or the slot changed user. */
    private TasksCloudIdentityGate.Identity lookup() {
        try {
            if (UserConfig.getInstance(account).getClientUserId() != rawTelegramId) return null;
            TasksApi.Identity identity = TasksApi.forAccount(account).identity();
            return identity.telegramUserId == rawTelegramId ? new TasksCloudIdentityGate.Identity(identity.principal, identity.generation) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Main thread; the single publication boundary. Admits the candidate against a FRESH identity (a delivery queued
     * before Pro's own status event cannot show the old principal), notifies every current observer when what they see
     * changes, and asks the engine to rebind on an observed identity change. Returns whether a rebind was requested.
     * The placeholder it may produce is content-free and never re-admitted, so nothing recurses.
     */
    private boolean show(TasksCloudState candidate) {
        TasksCloudIdentityGate.Verdict verdict = gate.admit(lookup(), candidate);
        boolean changed = verdict.state != current;
        current = verdict.state;
        if (changed) for (Observer observer : new ArrayList<>(observers)) observer.onStateChanged(current);
        if (verdict.rebind) run(e -> { e.identityChanged(); updateRealtime(); return null; });
        return verdict.rebind;
    }

    private void closeOnExecutor() {
        if (realtime != null) realtime.stop();
        realtime = null;
        if (engine != null) engine.close();
        engine = null;
        ledger = null;
        if (database != null) {
            try {
                database.close();
            } catch (RuntimeException ignored) {
                // The files are deleted next; nothing to preserve.
            }
            database = null;
        }
    }

    // ---- open ----

    private TasksCloudEngine engine() {
        if (closed) throw new TasksSql.ClosedException();
        // Before any marker read or database name: only this repository's own origin, configured now (lock-free
        // accessor, safe on the executor), and only while the slot holds its user. Otherwise content-free NOT_LINKED.
        String configured = ZenlyPro.configuredStorageToken();
        if (configured == null || !configured.equals(originToken)
                || UserConfig.getInstance(account).getClientUserId() != rawTelegramId) {
            throw new TasksCloudError(TasksCloudError.Code.NOT_LINKED);
        }
        if (engine != null) return engine;
        // Never (re)create the database while any obligation remains: a stale on-disk marker would delete it later.
        if (!CLEANUP.clearBeforeOpen(rawTelegramId)) throw new TasksCloudError(TasksCloudError.Code.STORAGE);
        database = new CloudDatabase(ApplicationLoader.applicationContext, originToken, rawTelegramId);
        AndroidSql sql = new AndroidSql(database.getWritableDatabase(), this);
        Remote remote = new Remote();
        ledger = new TasksCloudLedger(sql);
        noteConsent(); // before the engine can make its first request
        engine = new TasksCloudEngine(account, rawTelegramId, ledger, remote, EXECUTOR,
                (runnable, delayMs) -> AndroidUtilities.runOnUIThread(runnable, delayMs),
                System::currentTimeMillis, this::published);
        engine.setProvenance(new LocalProvenance(account, rawTelegramId));
        engine.setInviteAnswers(new InviteAnswersStore(rawTelegramId));
        engine.setCreationGate(() -> {
            try {
                // A create the server refused is sent again only when the server would accept it: its rule is the
                // account's own grant (or a free policy), never this device's local grace.
                return ZenlyPro.get(account).serverGrants(ZenlyPro.Capability.TASKS);
            } catch (RuntimeException e) {
                return false;
            }
        });
        realtime = new TasksCloudRealtime(remote, this::scheduleRealtime, () -> ThreadLocalRandom.current().nextDouble(), new RealtimeHooks());
        return engine;
    }

    // ---- consent at send (contract §6.7) ----

    /** Executor only, after the ledger's consent meta may have changed: the copies the send gate reads. */
    private void noteConsent() {
        consentWithdrawn = ledger.consentWithdrawn();
        consentEpoch = ledger.consentEpoch();
    }

    /**
     * Executor (the transport adapter, as each request is made): the gate TasksApi asks on the Pro worker right before
     * the request is signed. A withdrawal turns the account's gate off at once and its discard advances this database's
     * consent epoch, so a request that waited on the worker through it is refused there (nothing signed or sent; STALE,
     * requeued for the discard), even after a quick re-grant.
     */
    private TasksApi.SendGate sendGate() {
        final long epoch = consentEpoch;
        return () -> TasksSyncPolicy.sendAllowed(TasksCloudGate.permission(account), consentWithdrawn, epoch, consentEpoch);
    }

    // ---- realtime (executor-confined) ----

    /**
     * Executor only. Re-evaluates whether realtime is wanted: a VISIBLE board resumed (the app-level owner never wants
     * it), the identity, the account's holds — and the consent gate, since its metadata/auth calls are Tasks requests.
     */
    private void updateRealtime() {
        if (closed || engine == null || realtime == null) return;
        try {
            realtimePaused = engine.accountPaused();
            boolean wanted = realtimeVisible && engine.isStarted() && TasksCloudGate.allowed(account);
            realtime.update(new TasksCloudRealtime.Conditions(wanted, realtimeIdentity(), realtimePaused));
        } catch (RuntimeException e) {
            FileLog.e("Zenlygram cloud Tasks realtime update failed");
        }
    }

    /** Executor (engine publications): caches the unsent counts and lets a new account-level hold close realtime. */
    private void published(TasksCloudState state) {
        unsentCounts = state.status == TasksCloudState.Status.STORAGE_FAILED ? null
                : new int[]{state.unsentUserChanges, state.unsentMigrationChanges};
        AndroidUtilities.runOnUIThread(() -> deliver(state));
        if (engine != null && engine.accountPaused() != realtimePaused) updateRealtime();
    }

    /** The current Pro identity of THIS repository's Telegram user (null otherwise), with its TasksApi handle. */
    private TasksCloudRemote.Identity realtimeIdentity() {
        try {
            if (UserConfig.getInstance(account).getClientUserId() != rawTelegramId) return null;
            TasksApi.Identity identity = TasksApi.forAccount(account).identity();
            if (identity.telegramUserId != rawTelegramId) return null;
            return new TasksCloudRemote.Identity(identity.telegramUserId, identity.principal, identity.generation, identity);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A realtime block on EXECUTOR, only while this repository and the same controller are alive; never throws. */
    private void realtimeTask(final TasksCloudRealtime owner, final Runnable block) {
        EXECUTOR.execute(() -> {
            if (closed || realtime == null || realtime != owner) return;
            try {
                block.run();
            } catch (RuntimeException e) {
                FileLog.e("Zenlygram cloud Tasks realtime step failed");
            }
        });
    }

    private TasksRealtimeSocket.Cancellable scheduleRealtime(final Runnable action, long delayMs) {
        final TasksCloudRealtime owner = realtime;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ScheduledFuture<?> future = REALTIME_TIMER.schedule(() -> realtimeTask(owner, () -> {
            if (!cancelled.get()) action.run();
        }), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return () -> {
            cancelled.set(true);
            future.cancel(false);
        };
    }

    private final class RealtimeHooks implements TasksCloudRealtime.Hooks {
        @Override public void perform(Runnable block) { realtimeTask(realtime, block); }

        @Override public void requestPull() { run(e -> { e.requestSync(); return null; }); }

        @Override public long gateDelayMs() { return engine == null ? 0 : engine.readsGateMs(); }

        @Override public TasksCloudRemote.Identity currentIdentity() { return realtimeIdentity(); }

        @Override public void refused(TasksCloudRemote.Failure failure, TasksCloudRemote.Identity identity) {
            if (engine == null || identity == null) return;
            engine.realtimeRefused(failure, identity);
            // After the controller's own bookkeeping finished: the pause now decides the conditions.
            realtimeTask(realtime, TasksCloudRepository.this::updateRealtime);
        }
    }

    private void deliver(TasksCloudState state) {
        if (!closed) show(state);
    }

    private interface EngineWork<T> { T run(TasksCloudEngine engine); }

    private <T> void call(final Result<T> done, final EngineWork<T> work) {
        EXECUTOR.execute(() -> {
            T value = null;
            TasksCloudError error = null;
            try {
                value = work.run(engine());
            } catch (TasksCloudError e) {
                error = e;
            } catch (TasksWire.Invalid e) {
                error = new TasksCloudError(TasksCloudError.Code.INVALID, e.field);
            } catch (TasksSql.ClosedException e) {
                error = new TasksCloudError(TasksCloudError.Code.CLOSED);
            } catch (RuntimeException e) {
                // Never log task content, SQL values or identifiers.
                error = new TasksCloudError(TasksCloudError.Code.STORAGE);
            }
            if (done == null) return;
            final T result = value;
            final TasksCloudError failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (closed) done.complete(null, new TasksCloudError(TasksCloudError.Code.CLOSED));
                else done.complete(failure == null ? result : null, failure);
            });
        });
    }

    private void run(final EngineWork<Void> work) {
        EXECUTOR.execute(() -> {
            try {
                work.run(engine());
            } catch (TasksSql.ClosedException e) {
                // Fenced: nothing to do.
            } catch (TasksCloudError e) {
                if (e.code == TasksCloudError.Code.NOT_LINKED) {
                    // No configured origin, another origin, or the slot no longer holds this user: nothing to show.
                    AndroidUtilities.runOnUIThread(() -> deliver(TasksCloudState.empty(TasksCloudState.Status.NOT_LINKED, rawTelegramId)));
                } else {
                    AndroidUtilities.runOnUIThread(() -> deliver(current.withStatus(TasksCloudState.Status.STORAGE_FAILED, false)));
                }
            } catch (RuntimeException e) {
                AndroidUtilities.runOnUIThread(() -> deliver(current.withStatus(TasksCloudState.Status.STORAGE_FAILED, false)));
            }
        });
    }

    // ---- observation and lifecycle ----

    /**
     * Main thread. Re-admits the held state with a fresh identity BEFORE the new observer's synchronous first callback
     * (also for 2nd+ subscribers; existing observers are notified of a replacement), then delivers every change and
     * observes Pro rebinds while anyone is subscribed.
     */
    public Subscription subscribe(final Observer observer) {
        if (closed) {
            // fence() posts the CLOSED clear; until it runs, `current` may still hold content: never hand that out.
            observer.onStateChanged(TasksCloudState.empty(TasksCloudState.Status.CLOSED, rawTelegramId));
            return () -> { };
        }
        if (observers.isEmpty()) NotificationCenter.getInstance(account).addObserver(proObserver, NotificationCenter.zenlygramProStatusChanged);
        boolean rebinding = show(current);
        observers.add(observer);
        observer.onStateChanged(current);
        if (!rebinding) run(engine -> { engine.publish(); return null; });
        return () -> {
            if (observers.remove(observer) && observers.isEmpty()) {
                NotificationCenter.getInstance(account).removeObserver(proObserver, NotificationCenter.zenlygramProStatusChanged);
            }
        };
    }

    /** Main thread. Fresh identity read; may notify observers and schedule rebind. Not a side-effect-free getter. */
    public TasksCloudState currentState() {
        if (closed) return TasksCloudState.empty(TasksCloudState.Status.CLOSED, rawTelegramId);
        show(current);
        return current;
    }

    /** Main thread. {@code board} resumed (visible AND app in the foreground): sync and, when wanted, the realtime socket. */
    public void start(Object board) {
        boards.show(board, true);
        final boolean visible = boards.hasVisible();
        run(engine -> { realtimeVisible = visible; engine.start(); updateRealtime(); return null; });
    }

    /**
     * Main thread. {@code board} paused (hidden, removed or app in the background): the realtime socket closes once no
     * visible board of this account is resumed; polling stops once no owner at all (board or the app) remains.
     */
    public void stop(Object board) {
        final boolean last = boards.hide(board);
        final boolean visible = boards.hasVisible();
        run(engine -> { realtimeVisible = visible; if (last) engine.stop(); updateRealtime(); return null; });
    }

    /**
     * Main thread. The app-level owner (ZenlyTasksSyncOwner, Tasks R4): the engine drains and syncs while the app runs,
     * whether or not a board is shown; realtime stays board-only. Repeated calls re-run the foreground catch-up.
     */
    public void startApp() {
        boards.show(APP_OWNER, false);
        final boolean visible = boards.hasVisible();
        run(engine -> { realtimeVisible = visible; engine.start(); updateRealtime(); return null; });
    }

    /** Main thread. The app-level owner leaves (background): polling stops unless a board is still resumed. */
    public void stopApp() {
        if (boards.hide(APP_OWNER)) run(engine -> { engine.stop(); updateRealtime(); return null; });
    }

    /**
     * Main thread. Drives the outbox once more (e.g. on background entry) and reports the unsent {user, migration}
     * counts once nothing is in flight or {@code timeoutMs} passed; null with an error when the repository is
     * unavailable.
     */
    public void flush(long timeoutMs, Result<int[]> done) {
        final long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        EXECUTOR.execute(() -> {
            try {
                engine().kick();
            } catch (TasksCloudError e) {
                deliverResult(done, null, e);
                return;
            } catch (RuntimeException e) {
                deliverResult(done, null, new TasksCloudError(TasksCloudError.Code.STORAGE));
                return;
            }
            awaitIdle(deadline, done);
        });
    }

    /** Executor only. */
    private void awaitIdle(final long deadline, final Result<int[]> done) {
        TasksCloudEngine current = engine;
        if (closed || current == null) {
            deliverResult(done, null, new TasksCloudError(TasksCloudError.Code.CLOSED));
            return;
        }
        if (current.idle() || System.currentTimeMillis() >= deadline) {
            int[] counts;
            try {
                counts = current.unsentCounts();
            } catch (RuntimeException e) {
                deliverResult(done, null, new TasksCloudError(TasksCloudError.Code.STORAGE));
                return;
            }
            deliverResult(done, counts, null);
            return;
        }
        REALTIME_TIMER.schedule(() -> EXECUTOR.execute(() -> awaitIdle(deadline, done)), FLUSH_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private <T> void deliverResult(final Result<T> done, final T value, final TasksCloudError error) {
        if (done != null) AndroidUtilities.runOnUIThread(() -> done.complete(value, error));
    }

    /** Any thread: {user, migration} unsent entries of the last publication, or null when unknown. */
    public int[] cachedUnsentCounts() {
        int[] counts = unsentCounts;
        return counts == null ? null : counts.clone();
    }

    /**
     * The consent gate changed for this account (the sync owner's onPermissionChanged): the consent fence resets, a pull
     * follows when ALLOWED now, and realtime follows the gate.
     */
    public void onPermissionChanged() {
        run(engine -> { engine.permissionChanged(); updateRealtime(); return null; });
    }

    /**
     * Consent withdrawn: see {@link #onConsentWithdrawn}; delivers the discarded user-origin count on the main thread.
     */
    public void discardAfterConsentWithdrawal(Result<Integer> done) {
        call(done, e -> {
            int discarded = e.discardAfterConsentWithdrawal()[0];
            noteConsent();
            updateRealtime();
            return discarded;
        });
    }

    /** Consent granted again: unsent migration leftovers removed, inserts allowed again, a fresh snapshot pulled. */
    public void resumeAfterConsentRegrant() {
        run(engine -> { engine.resumeAfterConsentRegrant(); noteConsent(); updateRealtime(); return null; });
    }

    /**
     * The sync owner on an ALLOWED gate (design 30 §5.8, as iOS SGTasksSyncCoordinator does): a database still marked
     * withdrawn although the gate is back resumes now (TasksSyncPolicy.needsRegrantResume). That covers a lost resume — a
     * re-grant's resume that reached the executor before the withdrawal's discard, or one lost with the process — which
     * would otherwise hold Cloud Tasks in CONSENT_REQUIRED. The gate is read again here, so a withdrawal in between
     * resumes nothing; quiet unless it resumes.
     */
    public void resumeIfRegranted() {
        call(null, e -> {
            if (TasksSyncPolicy.needsRegrantResume(TasksCloudGate.permission(account), ledger.consentWithdrawn())) {
                e.resumeAfterConsentRegrant();
                noteConsent();
                updateRealtime();
            }
            return null;
        });
    }

    /** The consent epoch a migration step captures before its local part (TasksCloudEngine.enqueueImport). */
    public void consentEpoch(Result<Long> done) {
        call(done, TasksCloudEngine::consentEpoch);
    }

    /** §7.4 automatic requeue of STALLED / BLOCKED_CREATION entries on a driver occasion (the sync owner, TR4-A3). */
    public void requeue(TasksCloudPolicy.Occasion occasion) {
        run(engine -> { engine.requeue(occasion); return null; });
    }

    public void refresh() { run(engine -> { engine.refresh(); updateRealtime(); return null; }); }

    // ---- intents ----

    public void createList(String title, int sortOrder, Result<String> listId) { call(listId, e -> e.createList(title, sortOrder)); }

    public void updateList(String listId, String title, int sortOrder, Result<String> done) { call(done, e -> e.updateList(listId, title, sortOrder)); }

    public void deleteList(String listId, Result<String> done) { call(done, e -> e.deleteList(listId)); }

    public void createTask(TasksWire.Content content, Result<String> taskId) { call(taskId, e -> e.createTask(content)); }

    public void updateTask(String taskId, TasksWire.Content content, Result<String> done) { call(done, e -> e.updateTask(taskId, content)); }

    public void setCompleted(String taskId, boolean completed, Result<String> done) { call(done, e -> e.setCompleted(taskId, completed)); }

    public void invite(String taskId, String recipientTgId, Result<String> invitationId) { call(invitationId, e -> e.invite(taskId, recipientTgId)); }

    // ---- assignments: the assignee pick is the invitation (iOS wf15) ----

    /**
     * Pick-time check before anything is queued: a Telegram user other than the account itself, and one more manual pick
     * within the client pacer. Errors: INVALID, SELF_ASSIGNMENT, ASSIGNMENT_LIMIT (field = TasksInvitationPacer.Limit).
     */
    public void checkAssignment(String recipientTgId, Result<Void> done) { call(done, e -> { e.checkAssignment(recipientTgId); return null; }); }

    /** The composer's Add with an assignee ({@code recipientTgId} null: without one). The create never carries an annotation. */
    public void createTask(TasksWire.Content content, String recipientTgId, Result<String> taskId) { call(taskId, e -> e.createTask(content, recipientTgId)); }

    /** The assignee pick on an owned task: the invitation.request to that contact, a new epoch. */
    public void assign(String taskId, String recipientTgId, Result<String> invitationId) { call(invitationId, e -> e.assign(taskId, recipientTgId)); }

    /** Send again: an ended assignment goes to the same recipient as a new epoch. */
    public void resendAssignment(String taskId, Result<String> invitationId) { call(invitationId, e -> e.resendAssignment(taskId)); }

    /** Removes exactly the assignment epoch the confirmation showed; a newer one is refused (CHANGED_ELSEWHERE). */
    public void revoke(String taskId, String expectedInvitationId, Result<String> done) { call(done, e -> e.revoke(taskId, expectedInvitationId)); }

    /** "Remove assignee" on a legacy note-only task: clears the owner-only note (null when there was none). */
    public void removeLegacyAnnotation(String taskId, Result<String> done) { call(done, e -> e.removeLegacyAnnotation(taskId)); }

    /**
     * The contact rule's device-local inputs (the account's contacts, its Telegram blocked list, the pending senders'
     * block flags): kept in memory only, never persisted and never sent. Runs the rule over every pending invitation now
     * and after every pull that follows.
     */
    public void runAutoAccept(TasksAutoAcceptPolicy.Inputs inputs) { run(e -> { e.runAutoAccept(inputs); return null; }); }

    /** The app tore the account's sources down: nothing is accepted automatically until fresh inputs arrive. */
    public void clearAutoAcceptInputs() {
        EXECUTOR.execute(() -> {
            if (!closed && engine != null) engine.clearAutoAcceptInputs();
        });
    }

    public void respond(String invitationId, TasksWire.Decision decision, Result<String> done) { call(done, e -> e.respond(invitationId, decision)); }

    public void revoke(String taskId, Result<String> done) { call(done, e -> e.revoke(taskId)); }

    public void blockSender(String invitationId, Result<String> done) { call(done, e -> e.blockSender(invitationId)); }

    public void unblockSender(String senderTgId, Result<String> done) { call(done, e -> e.unblockSender(senderTgId)); }

    public void retry(String mutationId, Result<Void> done) { call(done, e -> { e.retry(mutationId); return null; }); }

    public void discard(String mutationId, Result<Void> done) { call(done, e -> { e.discard(mutationId); return null; }); }

    public void resolveConflict(String mutationId, boolean reapply, Result<String> done) { call(done, e -> e.resolveConflict(mutationId, reapply)); }

    public void dismissNotice(long noticeId) { run(e -> { e.dismissNotice(noticeId); return null; }); }

    public void holdDelete(final String taskId, final Result<HeldDelete> done) {
        call(done, e -> new Hold(taskId, e.holdDelete(taskId)));
    }

    private final class Hold implements HeldDelete {
        private final String taskId, mutationId;
        private final AtomicBoolean settled = new AtomicBoolean();

        Hold(String taskId, String mutationId) {
            this.taskId = taskId;
            this.mutationId = mutationId;
        }

        @Override public String taskId() { return taskId; }

        @Override public String mutationId() { return mutationId; }

        @Override public void undo(Result<Void> done) {
            if (!settled.compareAndSet(false, true)) {
                AndroidUtilities.runOnUIThread(() -> done.complete(null, new TasksCloudError(TasksCloudError.Code.BUSY_STATE)));
                return;
            }
            call(done, e -> {
                if (!e.undo(mutationId)) throw new TasksCloudError(TasksCloudError.Code.BUSY_STATE);
                return null;
            });
        }

        @Override public void dismiss() {
            if (settled.compareAndSet(false, true)) run(e -> { e.promote(mutationId); return null; });
        }
    }

    // ---- import ----

    /**
     * The explicit copy's preview. Once this server proved wire 4 it follows the R4 copy rules (design 30 §7.1): rows
     * normalized as the account's migration builds them (its record's locale and time base, else the UI language and
     * now), so the migration's re-check of the shared import scope rebuilds a copied row byte for byte. An R3-only (or
     * rolled-back) server gets exactly the R3 copy it always got.
     */
    public void previewImport(final Result<TasksCloudImport.ImportPreview> done) {
        call(new Result<String>() {
            @Override public void complete(String principal, TasksCloudError error) {
                if (error != null) {
                    done.complete(null, error);
                    return;
                }
                ZenlyTasksStore store = localStore(done);
                if (store == null) return;
                // Mappings are scoped to this origin and principal; a pre-origin (bare principal) mapping never matches.
                final String scope = ProOrigin.importScope(originToken, principal);
                store.importSnapshot(account, scope, (snapshot, failure) -> {
                    if (failure != null) {
                        done.complete(null, storeError(failure));
                        return;
                    }
                    store.migrationRecord(account, (record, recordFailure) -> {
                        if (recordFailure != null) {
                            done.complete(null, storeError(recordFailure));
                            return;
                        }
                        // Main thread: the placeholder language of a copy without a migration record, as the step reads it.
                        final String language = LocaleController.getInstance().getCurrentLocale().getLanguage();
                        call(done, e -> {
                            if (!principal.equals(e.requirePrincipal())) throw new TasksCloudError(TasksCloudError.Code.PRINCIPAL_CHANGED);
                            // Server time, like the migration: a row in the server's future is refused here, not by a 422
                            // for its whole batch.
                            long now = ZenlyPro.trustedNowSeconds() * 1000L;
                            TasksCloudImport.Rules rules = ledger.isServerWire4() ? TasksCloudImport.Rules.forCopy(record, scope, language, now) : null;
                            return TasksCloudImport.preview(rawTelegramId, principal, snapshot, new HashSet<>(e.knownCloudIds()),
                                    new HashSet<>(e.outboxMutationIds()), now, rules);
                        });
                    });
                });
            }
        }, e -> {
            String principal = e.requirePrincipal();
            if (!e.shadowComplete()) throw new TasksCloudError(TasksCloudError.Code.NOT_READY);
            return principal;
        });
    }

    /** Explicit consent: mappings are recorded durably in the local store first, then creates are queued. */
    public void confirmImport(final TasksCloudImport.ImportPreview preview, final TasksCloudImport.ImportChoices choices,
                              final Result<TasksCloudImport.ImportResult> done) {
        call(new Result<TasksCloudImport.Plan>() {
            @Override public void complete(TasksCloudImport.Plan plan, TasksCloudError error) {
                if (error != null) {
                    done.complete(null, error);
                    return;
                }
                final TasksCloudImport.ImportResult result = new TasksCloudImport.ImportResult(plan.lists, plan.tasks, plan.leftLocal);
                if (plan.operations.isEmpty()) {
                    done.complete(result, null);
                    return;
                }
                ZenlyTasksStore store = localStore(done);
                if (store == null) return;
                store.recordImportMappings(account, ProOrigin.importScope(originToken, preview.principal), plan.mappings, (ignored, failure) -> {
                    if (failure != null) {
                        done.complete(null, failure == ZenlyTasksStore.Failure.INVALID
                                ? new TasksCloudError(TasksCloudError.Code.IMPORT_STALE) : storeError(failure));
                        return;
                    }
                    call(done, e -> {
                        e.enqueueImport(preview.principal, plan);
                        return result;
                    });
                });
            }
        }, e -> {
            if (preview.telegramUserId != rawTelegramId || !preview.principal.equals(e.requirePrincipal())) {
                throw new TasksCloudError(TasksCloudError.Code.PRINCIPAL_CHANGED);
            }
            return TasksCloudImport.plan(preview, choices);
        });
    }

    private static TasksCloudError storeError(ZenlyTasksStore.Failure failure) {
        return new TasksCloudError(failure == ZenlyTasksStore.Failure.ACCOUNT_CHANGED ? TasksCloudError.Code.CLOSED : TasksCloudError.Code.STORAGE);
    }

    /** Main thread. Null (after reporting CLOSED) when the slot no longer holds this user. */
    private <T> ZenlyTasksStore localStore(Result<T> done) {
        try {
            if (!closed && UserConfig.getInstance(account).getClientUserId() == rawTelegramId) return ZenlyTasksStore.get(account);
        } catch (IllegalStateException ignored) {
            // Signed out meanwhile.
        }
        done.complete(null, new TasksCloudError(TasksCloudError.Code.CLOSED));
        return null;
    }

    // ---- transport adapter (no credentials cross this boundary) ----

    private final class Remote implements TasksCloudRemote, TasksCloudRealtime.Service {
        @Override public Identity identity() {
            TasksApi.Identity identity = TasksApi.forAccount(account).identity();
            return new Identity(identity.telegramUserId, identity.principal, identity.generation, identity);
        }

        /** The very TasksApi.Identity captured for this step; a mismatch at send/response is STALE, never retagged. */
        private TasksApi.Identity captured(Identity expected) {
            return expected.handle instanceof TasksApi.Identity ? (TasksApi.Identity) expected.handle : null;
        }

        // Every request carries this database's send gate (sendGate): its consent is asked again right before signing.

        @Override public void mutate(Identity expected, TasksWire.Mutation mutation, Reply<TasksWire.MutationResponse> reply) {
            TasksApi.forAccount(account).mutate(captured(expected), mutation, sendGate(), adapt(reply));
        }

        @Override public void snapshot(Identity expected, String cursor, int limit, Reply<TasksWire.SnapshotResponse> reply) {
            TasksApi.forAccount(account).snapshot(captured(expected), cursor, limit, sendGate(), adapt(reply));
        }

        @Override public void changes(Identity expected, String cursor, int limit, Reply<TasksWire.ChangesResponse> reply) {
            TasksApi.forAccount(account).changes(captured(expected), cursor, limit, sendGate(), adapt(reply));
        }

        /** Every ERROR item classified exactly as the single route would (TasksApi.classifyItem). */
        @Override public void mutateBatch(Identity expected, TasksWire.Batch batch, Reply<BatchReply> reply) {
            TasksApi.forAccount(account).mutateBatch(captured(expected), batch, sendGate(), new TasksApi.Callback<TasksWire.BatchResponse>() {
                @Override public void onSuccess(TasksWire.BatchResponse response) {
                    ArrayList<Failure> failures = new ArrayList<>(response.results.size());
                    for (TasksWire.BatchItemResult result : response.results) {
                        failures.add(result.kind == TasksWire.BatchItemResult.Kind.ERROR
                                ? failure(TasksApi.classifyItem(result.status, result.error), response.serverWire) : null);
                    }
                    reply.success(new BatchReply(response, failures));
                }

                @Override public void onFailure(TasksApi.Failure failure) { reply.failure(failure(failure, failure.serverWire)); }
            });
        }

        @Override public void metadata(Identity expected, Reply<TasksRealtimeWire.Metadata> reply) {
            TasksApi.forAccount(account).realtime(captured(expected), sendGate(), adapt(reply));
        }

        @Override public void authorize(Identity expected, String socketId, Reply<TasksRealtimeWire.Subscription> reply) {
            TasksApi.forAccount(account).realtimeAuth(captured(expected), socketId, sendGate(), adapt(reply));
        }

        /** Socket, transport events and timers all run on EXECUTOR, fenced to the controller that opened them. */
        @Override public TasksCloudRealtime.Connection open(TasksRealtimeWire.Metadata metadata, TasksRealtimeSocket.Scheduler scheduler,
                                                           TasksRealtimeSocket.Authorize authorize, TasksRealtimeSocket.Listener listener) {
            final TasksCloudRealtime owner = realtime;
            return new TasksRealtimeSocket(metadata, scheduler, new TasksRealtimeWebSocket(block -> realtimeTask(owner, block)),
                    authorize, listener);
        }

        private <T> TasksApi.Callback<T> adapt(final Reply<T> reply) {
            return new TasksApi.Callback<T>() {
                @Override public void onSuccess(T value) { reply.success(value); }

                @Override public void onFailure(TasksApi.Failure failure) { reply.failure(failure(failure, failure.serverWire)); }
            };
        }

        /** Same kind, code and reason, plus the R4 fields, effective_at and the answer's X-Tasks-Wire echo. */
        private Failure failure(TasksApi.Failure failure, Integer serverWire) {
            return new Failure(Failure.Kind.valueOf(failure.kind.name()), failure.code, failure.reason, failure.httpStatus,
                    failure.retryAfterSeconds, failure.fields, failure.effectiveAt, serverWire);
        }
    }

    /**
     * Local import provenance of TasksCloudEngine (store v3, cloud_import_attempt): blocking on the store queue (at most
     * 2 s) from this executor; ids without an import mapping are no-ops there. Not recorded = not sent yet; an
     * acknowledgement not recorded stays in import_ack_pending for a later flush.
     */
    private static final class LocalProvenance implements TasksCloudEngine.Provenance {
        private final int account;
        private final long rawTelegramId;

        LocalProvenance(int account, long rawTelegramId) {
            this.account = account;
            this.rawTelegramId = rawTelegramId;
        }

        @Override public boolean recordAttempts(List<String> createMutationIds) {
            return ZenlyTasksStore.recordImportAttempts(account, rawTelegramId, createMutationIds);
        }

        @Override public boolean acknowledged(List<String> createMutationIds) {
            return ZenlyTasksStore.acknowledgeImport(account, rawTelegramId, createMutationIds);
        }
    }

    // ---- automatic migration (design 30 §6.5; the sync owner's worker thread) ----

    /**
     * The step waits this long for one executor turn to start; a turn still queued then never runs (the step ends and
     * reruns with the next trigger), a started one is waited for (TasksAutoMigration.awaitTurn).
     */
    private static final long MIGRATION_WAIT_MS = 10_000;

    /** The blocking cloud side of TasksAutoMigration for this repository. Never call it on the main thread. */
    public TasksAutoMigration.Cloud migrationCloud() {
        return new MigrationCloud();
    }

    private final class MigrationCloud implements TasksAutoMigration.Cloud {
        @Override public TasksAutoMigration.CloudContext context() {
            return blocking(e -> {
                String principal;
                try {
                    principal = e.requirePrincipal();
                } catch (TasksCloudError notLinked) {
                    return null;
                }
                // A pending resnapshot (a 409, a re-grant resume) keeps the old shadow until the next snapshot lands; it may
                // still list rows the server erased, and a check against it would never resend them (iOS needs_resnapshot).
                if (!e.shadowComplete() || ledger == null || ledger.staging() != null || ledger.resnapshotPending()) return null;
                // The server's time (ZenlyPro's offset, set by the Tasks answers that completed this snapshot), never the
                // bare device clock: the server refuses a created_at/completed_at more than 300 s past its own now.
                return new TasksAutoMigration.CloudContext(principal, ProOrigin.importScope(originToken, principal),
                        ledger.resyncGeneration(), new HashSet<>(e.knownCloudIds()),
                        new HashSet<>(e.outboxMutationIds()), new HashSet<>(ledger.pendingImportAcks()),
                        ZenlyPro.trustedNowSeconds() * 1000L, ledger.migrationChecked());
            });
        }

        @Override public long consentEpoch() {
            Long epoch = blocking(TasksCloudEngine::consentEpoch);
            if (epoch == null) throw new TasksCloudError(TasksCloudError.Code.STORAGE);
            return epoch;
        }

        @Override public int enqueue(String principal, TasksCloudImport.Plan plan, long expectedConsentEpoch) {
            Integer inserted = blocking(e -> e.enqueueImport(principal, plan, TasksCloudSchema.ORIGIN_MIGRATION, expectedConsentEpoch));
            return inserted == null ? 0 : inserted;
        }

        @Override public void markChecked() {
            blocking(e -> {
                if (ledger == null) throw new TasksCloudError(TasksCloudError.Code.NOT_READY);
                ledger.writeMeta(TasksCloudLedger.META_MIGRATION_CHECKED, "1");
                return null;
            });
        }
    }

    /**
     * The sync owner's worker (never the main thread): what this account's cloud store on this device holds for the
     * cut-over's I1 (design 30 §7.5; iOS SGTasksCloudRepository.cutOverStoreFacts). The read is
     * TasksCloudEngine.cutOverStoreFacts, in one executor turn, with this origin's import scope. A logout purged the store
     * and kept the record, so a store created since is unbound until its first linked cycle. Null when the store does not
     * open (no configured origin, another user in the slot, storage, fenced), its generation is malformed or its scope
     * invalid: no copy here.
     */
    public TasksCutOver.StoreFacts cutOverStoreFacts() {
        try {
            return blocking(e -> e.cutOverStoreFacts(principal -> ProOrigin.importScope(originToken, principal)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * One executor turn, waited for (inline on the executor); its TasksCloudError is rethrown as is. A turn still queued
     * after MIGRATION_WAIT_MS never runs (NOT_READY); a started one is waited for to its end, so a migration enqueue
     * never commits after its step reported it unfinished and lowered the write fence (TasksAutoMigration.awaitTurn).
     */
    private <T> T blocking(final EngineWork<T> work) {
        if (Thread.currentThread() == executorThread) return work.run(engine());
        try {
            return TasksAutoMigration.awaitTurn(EXECUTOR, () -> work.run(engine()), MIGRATION_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TasksCloudError(TasksCloudError.Code.CLOSED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TasksCloudError) throw (TasksCloudError) cause;
            if (cause instanceof TasksSql.ClosedException) throw new TasksCloudError(TasksCloudError.Code.CLOSED);
            throw new TasksCloudError(TasksCloudError.Code.STORAGE);
        }
    }

    // ---- Android SQLite ----

    private static final class CloudDatabase extends SQLiteOpenHelper {
        CloudDatabase(Context context, String originToken, long rawTelegramId) {
            super(context, TasksCloudSchema.fileName(originToken, rawTelegramId), null, TasksCloudSchema.VERSION, db -> {
                // Unsent drafts may live here: refuse the framework's automatic delete; STORAGE surfaces instead.
                FileLog.e("Zenlygram cloud Tasks database corruption detected; automatic reset refused");
            });
            setWriteAheadLoggingEnabled(true);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            TasksCloudLedger.create(new AndroidSql(db, null), UUID.randomUUID().toString());
        }

        /** 1 → 2 (Tasks R4: outbox columns, import_ack_pending, forced resnapshot); any other change stays unsupported. */
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            TasksCloudLedger.upgrade(new AndroidSql(db, null), oldVersion, newVersion);
        }
    }

    /** TasksSql over one SQLiteDatabase; commits only while the owning repository is not fenced. */
    private static final class AndroidSql implements TasksSql {
        private final SQLiteDatabase db;
        private final TasksCloudRepository fence;

        AndroidSql(SQLiteDatabase db, TasksCloudRepository fence) {
            this.db = db;
            this.fence = fence;
        }

        private void check() {
            if (fence != null && fence.closed) throw new ClosedException();
        }

        private SQLiteStatement compile(String sql, Object[] args) {
            SQLiteStatement statement = db.compileStatement(sql);
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value == null) statement.bindNull(i + 1);
                else if (value instanceof String) statement.bindString(i + 1, (String) value);
                else if (value instanceof Long || value instanceof Integer) statement.bindLong(i + 1, ((Number) value).longValue());
                else if (value instanceof byte[]) statement.bindBlob(i + 1, (byte[]) value);
                else throw new IllegalArgumentException("bind_type");
            }
            return statement;
        }

        @Override public void exec(String sql, Object... args) {
            check();
            SQLiteStatement statement = compile(sql, args);
            try {
                statement.execute();
            } finally {
                statement.close();
            }
        }

        @Override public int update(String sql, Object... args) {
            check();
            SQLiteStatement statement = compile(sql, args);
            try {
                return statement.executeUpdateDelete();
            } finally {
                statement.close();
            }
        }

        @Override public List<Object[]> query(String sql, String... args) {
            check();
            ArrayList<Object[]> rows = new ArrayList<>();
            try (Cursor cursor = db.rawQuery(sql, args)) {
                int columns = cursor.getColumnCount();
                while (cursor.moveToNext()) {
                    Object[] row = new Object[columns];
                    for (int i = 0; i < columns; i++) {
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: row[i] = null; break;
                            case Cursor.FIELD_TYPE_INTEGER: row[i] = cursor.getLong(i); break;
                            case Cursor.FIELD_TYPE_BLOB: row[i] = cursor.getBlob(i); break;
                            default: row[i] = cursor.getString(i); break;
                        }
                    }
                    rows.add(row);
                }
            }
            return rows;
        }

        @Override public <T> T transaction(Work<T> work) {
            check();
            db.beginTransaction();
            try {
                T result = work.run();
                check(); // fenced mid-transaction: roll back instead of committing
                db.setTransactionSuccessful();
                return result;
            } finally {
                db.endTransaction();
            }
        }
    }
}
