package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * One account's sync + outbox drain state machine. Every method runs on the account's serial executor; at most one
 * Tasks request is in flight. Replies are fenced by request token and the closed flag, so a late reply after
 * stop/logout cannot recreate anything.
 * <p>
 * Tasks R4 (design 30 §6.4, §7): no request at all unless {@link TasksCloudGate} answers ALLOWED for the account and its
 * consent is not withdrawn (CONSENT_REQUIRED); a server consent fence holds the whole account with a doubling backoff
 * (CONSENT_FENCED) and never fails an entry. The outbox drains in batches (POST /v1/tasks/mutations/batch): homogeneous
 * in origin, user entries first, adaptive in size, one entry each after a 422 refused a whole batch; while the batch
 * route is out, user entries take the single route and migration creates wait. Edits become field-level task.patch once
 * the server proved wire 4, conflicts the §7.3 table can answer resolve after the next complete pull, and an R3 server's
 * 422 on an R4-only entry re-encodes it once.
 * <p>
 * Assignments (iOS wf15 §4, §5.3, §12): picking an assignee IS the invitation.request — there is no separate share, and
 * this client never writes the owner-only annotation (a create seals {@code annotation: null}, an edit keeps the task's
 * current one; its only writes are the two owner-field clears, {@link #removeLegacyAnnotation} and the chain after an
 * acknowledged request). Pick-time refusals (self, a completed task, the client pacer) queue nothing. A long server rate
 * limit on a request defers the assignment out of the outbox and every cycle start re-sends the due ones. A pending
 * invitation from one of the recipient's own Telegram contacts is accepted by this device ({@link #runAutoAccept}).
 */
final class TasksCloudEngine {
    interface Clock { long now(); }

    interface Scheduler { void schedule(Runnable runnable, long delayMs); }

    interface Listener { void onState(TasksCloudState state); }

    /**
     * Local import provenance (TR4-A3's ZenlyTasksStore; content-free mutation ids, non-import ids are no-ops there).
     * Both calls may block (the store's executor, bounded): they run on the engine's executor.
     */
    interface Provenance {
        /** Right before the first send of these creates; false = not recorded, so they are not sent yet. */
        boolean recordAttempts(List<String> createMutationIds);

        /** After their receipts (import_ack_pending); false = keep them pending for a later flush. */
        boolean acknowledged(List<String> createMutationIds);
    }

    /** The local create gate of §7.4 (production: ZenlyPro serverGrants(TASKS), what the server would accept). */
    interface CreationGate { boolean allowed(); }

    /**
     * The import scope of a bound principal, as the repository passes it (ProOrigin.importScope of its origin token), so
     * the engine stays free of the pro package and the host runners compile nothing more
     * ({@link TasksCloudEngine#cutOverStoreFacts}).
     */
    interface ImportScope { String of(String principal); }

    /** Small fixed page: Transport reads up to TasksWire.maxPageBytes(limit) within the 5 s budget (≈6.8 MiB at 20). */
    static final int PAGE_LIMIT = 20;
    static final long POLL_MS = 60_000;
    static final long REQUEUE_MS = 1_000;
    static final long HELD_DELETE_TIMEOUT_MS = 5_000;
    static final int MAX_SYNC_FAILURES = 6;
    /** During a batch drain at most one publication per second (design 30 §6.4). */
    static final long PUBLISH_INTERVAL_MS = 1_000;
    /**
     * A Retry-After longer than this on an invitation.request defers the assignment out of the outbox instead of holding
     * its entry: the server's day and per-recipient windows run for hours and its pending-expiry one for days, while its
     * minute window is at most 60 s.
     */
    static final int INVITATION_DEFERRAL_THRESHOLD_S = 120;
    /**
     * The transport never reports a Retry-After above this (TasksApi.MAX_RETRY_AFTER_SECONDS), so a value at it means "at
     * least an hour": the server's daily and per-person windows end at the next UTC midnight, and a deferred assignment
     * then waits for that day (plus a margin for the clocks) instead of asking again every hour.
     */
    static final int RETRY_AFTER_CEILING_S = 3600;
    static final long DEFERRAL_DAY_MARGIN_MS = 60_000;

    private static final Provenance NO_PROVENANCE = new Provenance() {
        @Override public boolean recordAttempts(List<String> createMutationIds) { return true; }

        @Override public boolean acknowledged(List<String> createMutationIds) { return true; }
    };

    private final int account;
    private final long rawTelegramId;
    private final TasksCloudLedger ledger;
    private final TasksCloudRemote remote;
    private final Executor executor;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Listener listener;
    private Provenance provenance = NO_PROVENANCE;
    private CreationGate creationGate = new CreationGate() {
        @Override public boolean allowed() { return false; }
    };

    private boolean closed, started, recovered, syncRequested, changesPaging, blockedUntilRefresh;
    /** A realtime hint that arrived while a request was in flight: survives the cycle that may predate it. */
    private boolean hintPending;
    private long inFlight, requestSeq, wakeToken, pausedGeneration = -1, blockedGeneration = -1, lastSyncAt;
    /** Failure-driven sync wake (backoff / Retry-After / requeue): hints wait for it instead of jumping it. */
    private long retryAt;
    /**
     * The shared reads budget's 429 deadline: set by a 429 on snapshot/changes OR on the realtime metadata/auth calls,
     * and honoured by both (step() holds every snapshot/changes read until it passes; the realtime controller asks
     * readsGateMs()). Mutations are not reads and are not held by it.
     */
    private long rateLimitedUntil;
    private int syncFailures;
    /** Batches since the last complete pull (≤ {@link TasksCloudPolicy#MAX_BATCHES_PER_CYCLE} while a pull is due). */
    private int batchesThisCycle;
    /** Consecutive batches of the current drain; from the second on, publications are coalesced. */
    private int batchRun;
    /** A one-entry 413 of a user entry: sent alone through the single route next. */
    private String sendAlone;
    /**
     * Entries of a multi-item batch refused by a 422 as a whole (the server validates every item while it reads the
     * batch, so one refused item refuses the request and nothing runs): until each was sent in a batch of its own (or
     * left the queue), batches carry one entry, so the refusal lands on the entry that caused it. In memory: after a
     * restart such a batch is refused and split once more, its attempt again not counted.
     */
    private final LinkedHashSet<String> splitIds = new LinkedHashSet<>();
    private long lastPublishAt = Long.MIN_VALUE / 2;
    private boolean publishScheduled;
    private TasksCloudState.Status status = TasksCloudState.Status.LOADING;
    private String principal;
    private final Set<String> liveHolds = new HashSet<>();
    /** The contact rule's device-local inputs; null until the app hands them in and after it tore its sources down (fail closed). */
    private TasksAutoAcceptPolicy.Inputs autoAcceptInputs;
    private TasksInviteAnswers.Store inviteAnswers = TasksInviteAnswers.NO_STORE;

    TasksCloudEngine(int account, long rawTelegramId, TasksCloudLedger ledger, TasksCloudRemote remote, Executor executor,
                     Scheduler scheduler, Clock clock, Listener listener) {
        this.account = account;
        this.rawTelegramId = rawTelegramId;
        this.ledger = ledger;
        this.remote = remote;
        this.executor = executor;
        this.scheduler = scheduler;
        this.clock = clock;
        this.listener = listener;
    }

    void setProvenance(Provenance provenance) {
        this.provenance = provenance == null ? NO_PROVENANCE : provenance;
    }

    void setCreationGate(CreationGate gate) {
        if (gate != null) this.creationGate = gate;
    }

    /** Where the manual answers to invitations are kept (outside this account's database: they survive a logout). */
    void setInviteAnswers(TasksInviteAnswers.Store store) {
        inviteAnswers = store == null ? TasksInviteAnswers.NO_STORE : store;
    }

    private TasksAnalyticsSink analytics() {
        return TasksAnalyticsSink.Holder.get();
    }

    // ---- lifecycle ----

    /**
     * Foreground: once per process recover interrupted sends and promote holds whose UI handle is gone; once per
     * database requeue what an older build marked invalid for a 422 that refused its whole batch.
     */
    void start() {
        if (closed) return;
        if (!recovered) {
            ledger.recover();
            ledger.repairBatchRefusals();
            recovered = true;
        }
        for (String held : ledger.heldIds()) {
            if (!liveHolds.contains(held) && ledger.promoteHeld(held)) analytics().taskDeleted(account, true);
        }
        started = true;
        syncRequested = true;
        syncFailures = 0;
        retryAt = 0;
        flushImportAcks();
        step();
        publish();
    }

    /** Background / no owner left: live holds promote exactly once, polling stops; nothing is wiped. */
    void stop() {
        if (closed) return;
        for (String held : new ArrayList<>(liveHolds)) promote(held);
        started = false;
        wakeToken++;
        batchRun = 0;
        publish();
    }

    /** Explicit user sync; the only resume path for account-level pauses besides observed re-linking. */
    void refresh() {
        if (closed) return;
        if (!recovered) start();
        ledger.writeMeta(TasksCloudLedger.META_PAUSE, null);
        pausedGeneration = -1;
        blockedUntilRefresh = false;
        syncFailures = 0;
        retryAt = 0;
        syncRequested = true;
        step();
        publish();
    }

    /** Logout fence: replies and timers become no-ops; the ledger's SQL fence refuses further transactions. */
    void close() {
        closed = true;
        inFlight = 0;
        wakeToken++;
        liveHolds.clear();
        splitIds.clear();
    }

    /**
     * Pro identity event. A different linked principal is applied at once: the old principal's cached cloud data goes
     * (its outbox stays, never sent), the request still in flight for the old binding is orphaned (its reply can no
     * longer match a token; an interrupted send is re-queued with the same id/bytes). A bare generation change only
     * re-evaluates pauses. Offline/unlinked keep cache and outbox (R3).
     */
    void identityChanged() {
        if (closed) return;
        TasksCloudRemote.Identity identity = remote.identity();
        if (identity.telegramUserId == rawTelegramId && identity.isLinked() && principal != null
                && !identity.principal.equals(principal)) {
            inFlight = 0;
            changesPaging = false;
            ledger.recover();
            bind(identity.principal);
        }
        step();
        publish();
    }

    /**
     * The consent gate changed (the sync owner's onPermissionChanged): the consent fence and its doubling are reset and
     * a pull follows when traffic is allowed now.
     */
    void permissionChanged() {
        if (closed) return;
        ledger.clearConsentFence(true);
        syncRequested = true;
        syncFailures = 0;
        retryAt = 0;
        step();
        publish();
    }

    /**
     * Consent withdrawn (the customer layer decided): every unsent entry is discarded, the account is marked withdrawn
     * and its consent epoch advances in one transaction; the request in flight is orphaned and no Tasks request follows
     * until the re-grant. The cache stays readable. Returns {user, migration} discarded.
     */
    int[] discardAfterConsentWithdrawal() {
        if (closed) throw new TasksCloudError(TasksCloudError.Code.CLOSED);
        int[] discarded = ledger.discardAfterConsentWithdrawal();
        liveHolds.clear();
        inFlight = 0;
        wakeToken++;
        batchRun = 0;
        sendAlone = null;
        splitIds.clear();
        status = TasksCloudState.Status.CONSENT_REQUIRED;
        publish();
        return discarded;
    }

    /** Consent granted again: inserts are allowed again and a fresh snapshot is pulled once traffic is allowed. */
    void resumeAfterConsentRegrant() {
        if (closed) return;
        ledger.resumeAfterConsentRegrant();
        changesPaging = false;
        syncRequested = true;
        syncFailures = 0;
        retryAt = 0;
        step();
        publish();
    }

    /**
     * §7.4: STALLED entries (launch, foreground, network, link, session) and BLOCKED_CREATION ones (launch, foreground,
     * session, entitlement — while the local create gate allows it, or for a migration create) go back to the queue
     * with the same identity, at most {@link TasksCloudPolicy#MAX_AUTO_REQUEUES} times each; a sync_issue per entry.
     */
    void requeue(TasksCloudPolicy.Occasion occasion) {
        if (closed) return;
        boolean stalled = occasion != TasksCloudPolicy.Occasion.ENTITLEMENT;
        boolean blocked = occasion == TasksCloudPolicy.Occasion.LAUNCH || occasion == TasksCloudPolicy.Occasion.FOREGROUND
                || occasion == TasksCloudPolicy.Occasion.SESSION || occasion == TasksCloudPolicy.Occasion.ENTITLEMENT;
        int[] requeued = ledger.autoRequeue(stalled, blocked, blocked && creationGate.allowed());
        for (int i = 0; i < requeued[0]; i++) analytics().syncIssue(account, TasksAnalyticsSink.ISSUE_STALLED);
        for (int i = 0; i < requeued[1]; i++) analytics().syncIssue(account, TasksAnalyticsSink.ISSUE_BLOCKED_CREATION);
        if (requeued[0] + requeued[1] == 0) return;
        syncFailures = 0;
        kick();
    }

    /** Nothing in flight and no batch run going on: a flush may report. */
    boolean idle() {
        return closed || inFlight == 0;
    }

    // ---- realtime (TasksCloudRealtime) ----

    /**
     * Realtime hint or (re)connect catch-up: the same changes pull as "Check for changes", but it never clears a
     * durable pause or a not-linked block (only the explicit refresh does) and never jumps a failure backoff or
     * Retry-After (the scheduled wake runs it). A hint landing while a request is in flight is kept, so an answer that
     * predates the change cannot swallow it.
     */
    void requestSync() {
        if (closed || !started) return;
        syncRequested = true;
        if (inFlight != 0) {
            hintPending = true;
            return;
        }
        if (clock.now() < retryAt) return;
        step();
        publish();
    }

    /** Milliseconds until the shared reads budget's Retry-After gate opens (0 = open). */
    long readsGateMs() {
        return Math.max(0, rateLimitedUntil - clock.now());
    }

    /**
     * An account-level hold (unlinked / session / not linked / consent) is in force: realtime stays off until it lifts
     * (its metadata/auth calls are Tasks requests too).
     */
    boolean accountPaused() {
        return status == TasksCloudState.Status.UNLINKED_PAUSED || status == TasksCloudState.Status.SESSION_INVALID
                || status == TasksCloudState.Status.NOT_LINKED || status == TasksCloudState.Status.CONSENT_REQUIRED
                || status == TasksCloudState.Status.CONSENT_FENCED;
    }

    boolean isStarted() {
        return started && !closed;
    }

    /**
     * A refusal of a realtime call, under this engine's own rules: 401/403/no-binding become the same durable pauses
     * a pull would record, a 429 closes the shared reads gate, a consent fence holds the account. {@code identity} is
     * the current one the call was made for.
     */
    void realtimeRefused(TasksCloudRemote.Failure failure, TasksCloudRemote.Identity identity) {
        if (closed) return;
        TasksCloudPolicy.Action action = TasksCloudPolicy.decide(failure);
        if (action == TasksCloudPolicy.Action.RETRY_AFTER) {
            noteRateLimited(failure);
            return;
        }
        if (action == TasksCloudPolicy.Action.CONSENT_FENCED) {
            consentFenced(failure);
            publish();
            return;
        }
        if (pause(action, identity)) publish();
    }

    private void noteRateLimited(TasksCloudRemote.Failure failure) {
        rateLimitedUntil = Math.max(rateLimitedUntil, clock.now() + Math.max(1, failure.retryAfterSeconds) * 1000L);
    }

    boolean isClosed() {
        return closed;
    }

    // ---- drive ----

    /** Fail closed: ALLOWED for this account and consent not withdrawn. */
    private boolean trafficAllowed() {
        return TasksCloudGate.permission(account) == TasksCloudGate.Permission.ALLOWED && !ledger.consentWithdrawn();
    }

    private void step() {
        if (closed || inFlight != 0) return;
        TasksCloudRemote.Identity identity = remote.identity();
        if (identity.telegramUserId != rawTelegramId || !identity.isLinked()) {
            status = TasksCloudState.Status.NOT_LINKED;
            batchRun = 0;
            return;
        }
        bind(identity.principal);
        if (!trafficAllowed()) {
            // No Tasks request of any kind; resumes on permissionChanged / resumeAfterConsentRegrant.
            status = TasksCloudState.Status.CONSENT_REQUIRED;
            batchRun = 0;
            return;
        }
        if (!resumable(identity)) return;
        if (!started) return;
        long now = clock.now();
        long fencedUntil = ledger.consentFencedUntil();
        if (fencedUntil > now) {
            status = TasksCloudState.Status.CONSENT_FENCED;
            batchRun = 0;
            scheduleStep(fencedUntil - now);
            return;
        }
        if (fencedUntil != 0) ledger.clearConsentFence(false); // its time is over; the doubling stays until a success
        TasksCloudLedger.Staging staging = ledger.staging();
        String cursor = ledger.readMeta(TasksCloudLedger.META_CHANGES_CURSOR);
        if (staging != null || cursor == null) {
            batchRun = 0;
            if (readsGated()) return;
            snapshotStep(staging, identity);
            return;
        }
        if (changesPaging) {
            batchRun = 0;
            if (readsGated()) return;
            changesStep(cursor, identity);
            return;
        }
        reenqueueDueDeferrals(now);
        if (drainStep(identity, now)) return;
        batchRun = 0;
        if (syncRequested) {
            if (readsGated()) return;
            syncRequested = false;
            hintPending = false;
            changesPaging = true;
            changesStep(cursor, identity);
            return;
        }
        status = TasksCloudState.Status.READY;
        scheduleIdleWake();
    }

    /**
     * Sends the next outbox work, if any: a user entry sent alone after a one-entry 413; the single route for user
     * entries while the batch route is out (migration creates wait for it); else the next batch — at most
     * {@link TasksCloudPolicy#MAX_BATCHES_PER_CYCLE} before a due pull, then continuation after it.
     */
    private boolean drainStep(TasksCloudRemote.Identity identity, long now) {
        if (sendAlone != null) {
            String alone = sendAlone;
            sendAlone = null;
            TasksCloudLedger.Entry entry = ledger.find(alone);
            if (entry != null && !entry.migration() && (TasksCloudSchema.QUEUED.equals(entry.state) || TasksCloudSchema.BACKOFF.equals(entry.state))) {
                batchRun = 0;
                sendStep(entry, identity);
                return true;
            }
        }
        if (ledger.batchRouteUnavailable(now)) {
            TasksCloudLedger.Entry next = ledger.nextEligible(principal, now);
            if (next == null) return false;
            batchRun = 0;
            sendStep(next, identity);
            return true;
        }
        if (batchesThisCycle >= TasksCloudPolicy.MAX_BATCHES_PER_CYCLE && syncRequested) return false;
        int maxItems = splitting() ? 1 : TasksWire.MAX_BATCH_ITEMS;
        TasksCloudLedger.Selection selection = ledger.nextEligibleBatch(principal, now, maxItems, ledger.batchBudget());
        if (selection.isEmpty()) return false;
        sendBatchStep(selection, identity);
        return true;
    }

    /**
     * Batches carry one entry while an entry of a batch a 422 refused as a whole has not been sent alone yet; one that
     * left the queue meanwhile (acknowledged, discarded, failed, withdrawn) no longer counts.
     */
    private boolean splitting() {
        if (splitIds.isEmpty()) return false;
        HashSet<String> waiting = new HashSet<>();
        for (TasksCloudLedger.Entry entry : ledger.entries()) {
            if (TasksCloudSchema.QUEUED.equals(entry.state) || TasksCloudSchema.BACKOFF.equals(entry.state)) waiting.add(entry.mutationId);
        }
        splitIds.retainAll(waiting);
        return !splitIds.isEmpty();
    }

    /**
     * While the shared reads Retry-After runs, no snapshot/changes read is sent (whatever asked: poll, hint, start,
     * refresh): the step is re-run when it ends, or earlier for an outbox entry whose own wake comes first.
     */
    private boolean readsGated() {
        long now = clock.now();
        long remaining = rateLimitedUntil - now;
        if (remaining <= 0) return false;
        long wake = principal == null ? 0 : ledger.nextWake(principal);
        scheduleStep(wake > 0 ? Math.min(remaining, Math.max(0, wake - now)) : remaining);
        return true;
    }

    private void bind(String current) {
        if (ledger.bindPrincipal(current)) {
            changesPaging = false;
            syncRequested = true;
            splitIds.clear(); // the old principal's entries are never sent
        }
        principal = current;
    }

    /**
     * Account-level pauses: unlinked (403) / session (401) persist; local no-binding/config waits in memory. A pause holds
     * only for the binding it was answered for. Answered by this engine: until the Pro generation changes (an observed
     * re-link). Answered before it (a restart: that generation is gone): a 401 pause belonged to a binding whose refresh
     * credential the existing 401 handling dropped durably before TasksApi delivered the 401, so the linked identity seen
     * here is a new session — a re-link, even one that kept the server's binding id (a re-bind of the same install and
     * account) — and the pause ends; a 403 pause kept its session, so it waits for a re-bind to another principal
     * (bindPrincipal) or the refresh.
     */
    private boolean resumable(TasksCloudRemote.Identity identity) {
        String pause = ledger.readMeta(TasksCloudLedger.META_PAUSE);
        if (pause != null) {
            boolean held = pausedGeneration >= 0 ? identity.generation == pausedGeneration : !TasksCloudLedger.PAUSE_SESSION.equals(pause);
            if (held) {
                status = TasksCloudLedger.PAUSE_UNLINKED.equals(pause) ? TasksCloudState.Status.UNLINKED_PAUSED : TasksCloudState.Status.SESSION_INVALID;
                return false;
            }
            // Observed re-link (in this process, or since the restart for a 401): fresh credentials, same business ids.
            ledger.writeMeta(TasksCloudLedger.META_PAUSE, null);
            pausedGeneration = -1;
        }
        if (blockedUntilRefresh) {
            if (identity.generation == blockedGeneration) {
                status = TasksCloudState.Status.NOT_LINKED;
                return false;
            }
            blockedUntilRefresh = false;
        }
        return true;
    }

    /** An R4 List projection (members_revision present) proves an R4 server (x-wire-negotiation). */
    private static boolean r4Projection(List<TasksWire.Projection> items) {
        for (TasksWire.Projection item : items) {
            if (item instanceof TasksWire.ListItem && ((TasksWire.ListItem) item).membersRevision != null) return true;
        }
        return false;
    }

    private static boolean r4Changes(List<TasksWire.Change> changes) {
        for (TasksWire.Change change : changes) {
            if (change.item instanceof TasksWire.ListItem && ((TasksWire.ListItem) change.item).membersRevision != null) return true;
        }
        return false;
    }

    private void snapshotStep(TasksCloudLedger.Staging staging, final TasksCloudRemote.Identity identity) {
        if (staging == null) {
            ledger.startSnapshot(PAGE_LIMIT);
            staging = ledger.staging();
            hintPending = false; // a fresh snapshot covers every earlier hint
        }
        final String cursor = staging.cursor;
        final long token = begin();
        if (!staging.catchup) {
            remote.snapshot(identity, cursor, staging.limit, reply(token, identity, false, new Handler<TasksWire.SnapshotResponse>() {
                @Override public void handle(TasksWire.SnapshotResponse page) {
                    ledger.noteServerAnswer(page.serverWire, r4Projection(page.items));
                    ledger.applySnapshotPage(cursor, page);
                }
            }));
        } else {
            remote.changes(identity, cursor, staging.limit, reply(token, identity, false, new Handler<TasksWire.ChangesResponse>() {
                @Override public void handle(TasksWire.ChangesResponse page) {
                    ledger.noteServerAnswer(page.serverWire, r4Changes(page.changes));
                    if (ledger.applyCatchupPage(cursor, page)) syncCompleted();
                }
            }));
        }
    }

    private void changesStep(final String cursor, TasksCloudRemote.Identity identity) {
        final long token = begin();
        remote.changes(identity, cursor, PAGE_LIMIT, reply(token, identity, false, new Handler<TasksWire.ChangesResponse>() {
            @Override public void handle(TasksWire.ChangesResponse page) {
                ledger.noteServerAnswer(page.serverWire, r4Changes(page.changes));
                if (ledger.applyChangesPage(cursor, page)) {
                    changesPaging = false;
                    syncCompleted();
                }
            }
        }));
    }

    private void sendStep(TasksCloudLedger.Entry entry, TasksCloudRemote.Identity identity) {
        if (!entry.sent && entry.operation.kind.isCreate()
                && !provenance.recordAttempts(Collections.singletonList(entry.mutationId))) {
            // Durable import provenance BEFORE the bytes can reach the server; not recorded: not sent yet.
            scheduleRetry(REQUEUE_MS);
            return;
        }
        final TasksWire.Mutation mutation = ledger.beginSend(entry.mutationId);
        if (mutation == null) {
            step();
            return;
        }
        final long token = begin();
        remote.mutate(identity, mutation, reply(token, identity, true, new Handler<TasksWire.MutationResponse>() {
            @Override public void handle(TasksWire.MutationResponse response) {
                ledger.noteServerAnswer(response.serverWire, false);
                if (ledger.acknowledge(mutation.mutationId, response.receipt) && mutation.operation.kind.isCreate()) flushImportAcks();
                syncRequested = true;
            }
        }, mutation.mutationId));
    }

    /** One batch: provenance first, then sealed and marked sending in one transaction, then one request. */
    private void sendBatchStep(TasksCloudLedger.Selection selection, final TasksCloudRemote.Identity identity) {
        List<String> creates = selection.unsentCreates();
        if (!creates.isEmpty() && !provenance.recordAttempts(creates)) {
            batchRun = 0;
            scheduleRetry(REQUEUE_MS);
            return;
        }
        final List<TasksWire.Batch.Item> items = ledger.beginSendBatch(selection);
        if (items.isEmpty()) return;
        final TasksWire.Batch batch;
        try {
            batch = TasksWire.Batch.of(TasksWire.newId(), items);
        } catch (TasksWire.Invalid never) {
            // Sized and validated already, so never expected: nothing was sent; a smaller batch is tried later.
            ledger.requeueUncounted(ids(items));
            ledger.shrinkBatch();
            batchRun = 0;
            scheduleRetry(REQUEUE_MS);
            return;
        }
        if (items.size() == 1) splitIds.remove(items.get(0).mutation.mutationId); // sent alone: its answer is its own
        final String origin = selection.origin;
        batchesThisCycle++;
        batchRun++;
        final long token = begin();
        remote.mutateBatch(identity, batch, new TasksCloudRemote.Reply<TasksCloudRemote.BatchReply>() {
            @Override public void success(final TasksCloudRemote.BatchReply value) {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        if (!accept(token)) return;
                        guarded(new Runnable() {
                            @Override public void run() { onBatchReply(items, origin, value, identity); }
                        });
                    }
                });
            }

            @Override public void failure(final TasksCloudRemote.Failure failure) {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        if (!accept(token)) return;
                        guarded(new Runnable() {
                            @Override public void run() { onBatchFailure(items, origin, failure, identity); }
                        });
                    }
                });
            }
        });
    }

    private static List<String> ids(List<TasksWire.Batch.Item> items) {
        ArrayList<String> ids = new ArrayList<>(items.size());
        for (TasksWire.Batch.Item item : items) ids.add(item.mutation.mutationId);
        return ids;
    }

    /**
     * Every item answered: receipts acknowledged (even when another item paused the account), errors applied exactly
     * as the single route's, skipped items and consent-fenced / migration-paused ones requeued without an attempt; the
     * account-level consequence (pause, fence, resync) applied once; the batch's retry_after holds its origin.
     */
    private void onBatchReply(List<TasksWire.Batch.Item> items, String origin, TasksCloudRemote.BatchReply reply,
                              TasksCloudRemote.Identity identity) {
        long now = clock.now();
        TasksWire.BatchResponse response = reply.response;
        ledger.noteServerAnswer(response.serverWire, true); // a batch 200 is an R4 server
        ledger.clearBatch503();
        boolean skipped = false, acknowledged = false, quiet = false, deferred = false;
        TasksCloudRemote.Failure fence = null, migrationPaused = null, accountFailure = null;
        TasksCloudPolicy.Action account = null;
        ArrayList<String> requeue = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String id = items.get(i).mutation.mutationId;
            TasksWire.BatchItemResult result = response.results.get(i);
            if (result.kind == TasksWire.BatchItemResult.Kind.RECEIPT) {
                acknowledged |= ledger.acknowledge(id, result.receipt);
                continue;
            }
            if (result.kind == TasksWire.BatchItemResult.Kind.SKIPPED) {
                skipped = true;
                requeue.add(id);
                continue;
            }
            TasksCloudRemote.Failure failure = reply.itemFailures.get(i);
            TasksCloudPolicy.Action action = TasksCloudPolicy.decide(failure);
            if (action == TasksCloudPolicy.Action.CONSENT_FENCED) {
                fence = failure;
                requeue.add(id);
            } else if (TasksCloudPolicy.isMigrationPaused(failure)) {
                migrationPaused = failure;
                requeue.add(id);
            } else if (entryFailed(id, action, failure, now)) {
                // The entry left the outbox quietly (a deferred assignment, an automatic entry): nothing for the account
                // but a look at the current rows.
                quiet = true;
                deferred |= action == TasksCloudPolicy.Action.RETRY_AFTER;
            } else if (severity(action) > severity(account)) {
                account = action;
                accountFailure = failure;
            }
        }
        if (quiet) syncRequested = true;
        if (!requeue.isEmpty()) ledger.requeueUncounted(requeue);
        // The server stops a batch at its first throttled item and repeats that item's Retry-After for the batch. When
        // the item was an assignment that left the outbox as a deferral, the wait went with it: nothing else is held.
        if (response.retryAfter != null && !deferred) ledger.holdOrigin(origin, now + response.retryAfter * 1000L);
        if (migrationPaused != null) ledger.holdOrigin(TasksCloudSchema.ORIGIN_MIGRATION, now + pauseMs(migrationPaused));
        if (skipped || fence != null) ledger.resetBatchStreak();
        else ledger.noteFullBatch();
        if (acknowledged) {
            syncRequested = true;
            flushImportAcks();
        }
        if (fence != null) {
            consentFenced(fence);
            return;
        }
        if (account != null && pause(account, identity)) return;
        if (account == TasksCloudPolicy.Action.RESYNC) {
            ledger.requireResnapshot();
            changesPaging = false;
        } else if (account == TasksCloudPolicy.Action.CONFLICT || account == TasksCloudPolicy.Action.NOT_FOUND) {
            syncRequested = true; // re-fetch the current rows (automatic resolution or the user's explicit choice)
        } else if (account == TasksCloudPolicy.Action.REQUEUE && accountFailure != null) {
            if (++syncFailures <= MAX_SYNC_FAILURES) scheduleRetry(REQUEUE_MS);
            return;
        }
        status = TasksCloudState.Status.READY;
        if (!acknowledged && account == null && requeue.size() == items.size() && migrationPaused == null && response.retryAfter == null) {
            // Nothing started (every item skipped) and no hold names why: continuation only after progress, so the same
            // batch waits a backoff instead of being resent at once.
            batchRun = 0;
            if (++syncFailures <= MAX_SYNC_FAILURES) scheduleRetry(TasksCloudPolicy.backoffMs(syncFailures));
            return;
        }
        step();
    }

    /** Which item outcome decides the account's next move (higher wins). */
    private static int severity(TasksCloudPolicy.Action action) {
        if (action == null) return 0;
        switch (action) {
            case PAUSE_SESSION:
            case PAUSE_UNLINKED:
            case PAUSE_NOT_LINKED: return 5;
            case RESYNC: return 4;
            case REQUEUE: return 3;
            case CONFLICT:
            case NOT_FOUND: return 2;
            default: return 1;
        }
    }

    private static long pauseMs(TasksCloudRemote.Failure failure) {
        return failure.retryAfterSeconds > 0 ? failure.retryAfterSeconds * 1000L : TasksCloudPolicy.DEFAULT_MIGRATION_PAUSE_MS;
    }

    /**
     * The batch call failed as a whole (no item answers). Nothing ran (404, batch_disabled, 413, migration_paused, a
     * consent fence, an envelope refusal) or every item's outcome is unknown (timeout, 5xx, malformed): the entries go
     * back without an attempt, except a one-entry batch of unknown outcome, which counts as a single send — so a slow
     * uplink converges to one-entry batches and no entry stalls because of batch size. A 422 names no item: a
     * multi-item batch goes back without an attempt and is resent one entry per batch, so only an entry the server
     * refuses alone becomes invalid.
     */
    private void onBatchFailure(List<TasksWire.Batch.Item> items, String origin, TasksCloudRemote.Failure failure,
                                TasksCloudRemote.Identity identity) {
        batchRun = 0;
        long now = clock.now();
        List<String> ids = ids(items);
        TasksCloudPolicy.Action action = TasksCloudPolicy.decide(failure);
        if (action == TasksCloudPolicy.Action.CONSENT_FENCED) {
            ledger.requeueUncounted(ids);
            consentFenced(failure);
            return;
        }
        if (TasksCloudPolicy.isMigrationPaused(failure)) {
            ledger.requeueUncounted(ids);
            ledger.holdOrigin(TasksCloudSchema.ORIGIN_MIGRATION, now + pauseMs(failure));
            step();
            return;
        }
        if (TasksCloudPolicy.isBatchDisabled(failure) || failure.httpStatus == 404) {
            // No batch route on this server (an R3 one, rolled back, or switched off): user entries take the single route.
            ledger.requeueUncounted(ids);
            ledger.openBatchFallback(now);
            step();
            return;
        }
        if (failure.httpStatus == 413) {
            // A definite non-commit: send less.
            ledger.shrinkBatch();
            if (items.size() == 1 && TasksCloudSchema.ORIGIN_MIGRATION.equals(origin)) {
                // A migration create is never sent alone through the single route: as R3, invalid.
                ledger.fail(ids.get(0), TasksCloudPolicy.Action.INVALID, failure, now, false);
                analytics().syncIssue(account, TasksAnalyticsSink.ISSUE_INVALID);
            } else {
                ledger.requeueUncounted(ids);
                if (items.size() == 1) sendAlone = ids.get(0);
            }
            step();
            return;
        }
        boolean unknownOutcome = failure.kind == TasksCloudRemote.Failure.Kind.OFFLINE
                || failure.kind == TasksCloudRemote.Failure.Kind.UNAVAILABLE
                || (failure.kind == TasksCloudRemote.Failure.Kind.PROTOCOL && !"request_rejected".equals(failure.code));
        if (unknownOutcome) {
            ledger.shrinkBatch();
            boolean fallBack = failure.kind == TasksCloudRemote.Failure.Kind.UNAVAILABLE && ledger.noteBatch503(now);
            if (items.size() == 1) {
                onFailure(failure, identity, ids.get(0));
                if (fallBack) step();
                return;
            }
            ledger.requeueUncounted(ids);
            reportError(failure);
            if (fallBack) {
                step();
                return;
            }
            status = failure.kind == TasksCloudRemote.Failure.Kind.UNAVAILABLE ? TasksCloudState.Status.UNAVAILABLE : TasksCloudState.Status.OFFLINE;
            if (++syncFailures <= MAX_SYNC_FAILURES) {
                scheduleRetry(failure.retryAfterSeconds > 0 ? failure.retryAfterSeconds * 1000L : TasksCloudPolicy.backoffMs(syncFailures));
            }
            return;
        }
        ledger.resetBatchStreak();
        if (action == TasksCloudPolicy.Action.INVALID) {
            if (items.size() > 1 && failure.httpStatus == 422) {
                // The server validates every item while it reads the batch (e.g. a created_at in its future), so one
                // refused item refused the whole request and nothing ran: requeued uncounted, then one entry per batch.
                ledger.requeueUncounted(ids);
                splitIds.addAll(ids);
                step();
                return;
            }
            // The batch itself was refused (shape, header) or its one entry was: each entry as an R3 422, never re-encoded.
            for (String id : ids) {
                // An automatic entry (the re-send of a deferred assignment, the contact rule's accept) ends quietly.
                if (ledger.automaticKind(id) != 0 && ledger.dropAutomatic(id)) continue;
                ledger.fail(id, TasksCloudPolicy.Action.INVALID, failure, now, false);
                analytics().syncIssue(account, TasksAnalyticsSink.ISSUE_INVALID);
            }
            step();
            return;
        }
        // Account-level answers for the whole request (pause, session, stale/busy, a 429, a local refusal): nothing ran.
        ledger.requeueUncounted(ids);
        if (pause(action, identity)) return;
        switch (action) {
            case RETRY_AFTER:
                ledger.holdOrigin(origin, now + Math.max(1, failure.retryAfterSeconds) * 1000L);
                step();
                return;
            case REQUEUE:
                if (++syncFailures <= MAX_SYNC_FAILURES) scheduleRetry(REQUEUE_MS);
                return;
            default:
                status = TasksCloudState.Status.OFFLINE;
                reportError(failure);
                if (++syncFailures <= MAX_SYNC_FAILURES) scheduleRetry(TasksCloudPolicy.backoffMs(syncFailures));
        }
    }

    /**
     * A sync request is satisfied once its cycle (or reconciled snapshot) completes; receipts settle separately. A hint
     * that arrived during the cycle asks for one more cycle (its answer may predate the change). Conflicts marked for
     * automatic resolution are resolved now, against the fresh shadow (§7.3).
     */
    private void syncCompleted() {
        syncFailures = 0;
        syncRequested = hintPending;
        hintPending = false;
        retryAt = 0;
        lastSyncAt = clock.now();
        batchesThisCycle = 0;
        if (principal == null) return;
        for (String issue : ledger.resolvePendingConflicts(principal, clock.now() / 1000)) analytics().syncIssue(account, issue);
        // An invitation that arrived with this pull is accepted — queued — before the drain that follows it.
        performAutoAccept(false);
    }

    /** Hands acknowledged creates to the local import provenance; each id leaves the queue only on success. */
    private void flushImportAcks() {
        List<String> pending = ledger.pendingImportAcks();
        if (pending.isEmpty()) return;
        boolean recorded;
        try {
            recorded = provenance.acknowledged(pending);
        } catch (RuntimeException e) {
            recorded = false;
        }
        if (recorded) ledger.removeImportAcks(pending);
    }

    private long begin() {
        inFlight = ++requestSeq;
        publish();
        if (closed) throw new TasksSql.ClosedException(); // fenced while publishing: never issue the request
        return inFlight;
    }

    private interface Handler<T> { void handle(T value); }

    private <T> TasksCloudRemote.Reply<T> reply(long token, TasksCloudRemote.Identity identity, boolean mutation, Handler<T> onSuccess) {
        return reply(token, identity, mutation, onSuccess, null);
    }

    private <T> TasksCloudRemote.Reply<T> reply(final long token, final TasksCloudRemote.Identity identity, final boolean mutation,
                                                final Handler<T> onSuccess, final String mutationId) {
        return new TasksCloudRemote.Reply<T>() {
            @Override public void success(final T value) {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        if (!accept(token)) return;
                        guarded(new Runnable() {
                            @Override public void run() {
                                onSuccess.handle(value);
                                status = TasksCloudState.Status.READY;
                                step();
                            }
                        });
                    }
                });
            }

            @Override public void failure(final TasksCloudRemote.Failure failure) {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        if (!accept(token)) return;
                        guarded(new Runnable() {
                            @Override public void run() {
                                onFailure(failure, identity, mutation ? mutationId : null);
                            }
                        });
                    }
                });
            }
        };
    }

    private boolean accept(long token) {
        if (closed || inFlight != token) return false;
        inFlight = 0;
        return true;
    }

    private void onFailure(TasksCloudRemote.Failure failure, TasksCloudRemote.Identity identity, String mutationId) {
        batchRun = 0;
        TasksCloudPolicy.Action action = TasksCloudPolicy.decide(failure);
        if (action == TasksCloudPolicy.Action.CONSENT_FENCED) {
            if (mutationId != null) ledger.fail(mutationId, action, failure, clock.now(), false);
            consentFenced(failure);
            return;
        }
        if (mutationId != null) {
            if (refusedAtSend(failure)) {
                ledger.requeueUncounted(Collections.singletonList(mutationId));
            } else if (entryFailed(mutationId, action, failure, clock.now())) {
                // The entry left the outbox quietly (a deferred assignment, an automatic entry): the drain goes on.
                syncRequested = true;
                status = TasksCloudState.Status.READY;
                step();
                return;
            }
        }
        if (pause(action, identity)) return;
        switch (action) {
            case RESYNC:
                ledger.requireResnapshot();
                changesPaging = false;
                step();
                return;
            case REQUEUE:
                if (++syncFailures <= MAX_SYNC_FAILURES) scheduleRetry(REQUEUE_MS);
                return;
            case BACKOFF:
            case RETRY_AFTER:
                status = failure.kind == TasksCloudRemote.Failure.Kind.UNAVAILABLE ? TasksCloudState.Status.UNAVAILABLE : TasksCloudState.Status.OFFLINE;
                reportError(failure);
                if (mutationId != null) {
                    scheduleIdleWake();
                } else {
                    if (action == TasksCloudPolicy.Action.RETRY_AFTER) noteRateLimited(failure);
                    if (++syncFailures <= MAX_SYNC_FAILURES) {
                        scheduleRetry(action == TasksCloudPolicy.Action.RETRY_AFTER
                                ? Math.max(1, failure.retryAfterSeconds) * 1000L : TasksCloudPolicy.backoffMs(syncFailures));
                    }
                }
                return;
            case CONFLICT:
            case NOT_FOUND:
                syncRequested = true; // re-fetch the current row (automatic resolution or the user's explicit choice)
                step();
                return;
            default:
                step(); // terminal per-item state; independent chains continue
        }
    }

    /**
     * The repository's send gate refused the request on the Pro worker before it was signed (TasksApi.CODE_CONSENT_CHANGED,
     * STALE, local; contract §6.7): nothing was sent, so a single-route entry goes back to the queue without an attempt,
     * as a batch's entries do. Any other STALE keeps its attempt: that request may have gone out (outcome unknown).
     */
    /** When a rate-limited assignment is sent again: after its Retry-After, or with the next UTC day when that is a floor. */
    static long deferralDue(long now, int retryAfterSeconds) {
        long due = now + retryAfterSeconds * 1000L;
        if (retryAfterSeconds < RETRY_AFTER_CEILING_S) return due;
        long nextUtcDay = (Math.floorDiv(now, 86_400_000L) + 1) * 86_400_000L;
        return Math.max(due, nextUtcDay + DEFERRAL_DAY_MARGIN_MS);
    }

    private static boolean refusedAtSend(TasksCloudRemote.Failure failure) {
        return failure.kind == TasksCloudRemote.Failure.Kind.STALE && "consent_changed".equals(failure.code);
    }

    /**
     * The entry part of a single-route or batch-item failure. A 422 on an R4-only entry is re-encoded once as R3
     * (downgrade); a conflict the §7.3 table answers is marked for resolution after the next complete pull.
     * <p>
     * Assignments: a long rate limit on an invitation.request defers the assignment out of the outbox; a definite refusal
     * of an automatic entry (the re-send of a deferred assignment, the contact rule's accept) or of a legacy annotation
     * clear drops it without an Unsent Changes row and without a notice — the user did nothing. Returns true for those:
     * the entry is gone and the failure has no consequence for the account.
     */
    private boolean entryFailed(String mutationId, TasksCloudPolicy.Action action, TasksCloudRemote.Failure failure, long now) {
        if (action == TasksCloudPolicy.Action.INVALID && "tasks_invalid_request".equals(failure.code) && ledger.reencodeAsR3(mutationId)) return false;
        TasksCloudLedger.Entry entry = ledger.find(mutationId);
        int automaticKind = entry == null ? 0 : ledger.automaticKind(mutationId);
        boolean automatic = action == TasksCloudPolicy.Action.CONFLICT && entry != null
                && (TasksCloudAutoResolve.resolvesAutomatically(entry, failure.reason)
                || (automaticKind == 2 && failure.reason == TasksWire.ConflictReason.REVISION_CHANGED
                && entry.autoResolutions < TasksCloudPolicy.MAX_AUTO_RESOLUTIONS));
        if (entry != null) {
            if (action == TasksCloudPolicy.Action.RETRY_AFTER && entry.operation instanceof TasksWire.Operation.InvitationRequest
                    && failure.retryAfterSeconds > INVITATION_DEFERRAL_THRESHOLD_S
                    && ledger.deferAssignment(mutationId, deferralDue(now, failure.retryAfterSeconds))) {
                return true;
            }
            boolean definite = action == TasksCloudPolicy.Action.NOT_FOUND || action == TasksCloudPolicy.Action.INVALID
                    || action == TasksCloudPolicy.Action.QUARANTINE || (action == TasksCloudPolicy.Action.CONFLICT && !automatic);
            if (automaticKind != 0 && definite && ledger.dropAutomatic(mutationId)) return true;
            if ((action == TasksCloudPolicy.Action.NOT_FOUND || action == TasksCloudPolicy.Action.CONFLICT)
                    && ledger.dropAnnotationClear(mutationId)) {
                return true;
            }
        }
        ledger.fail(mutationId, action, failure, now, automatic);
        if (action == TasksCloudPolicy.Action.INVALID) analytics().syncIssue(account, TasksAnalyticsSink.ISSUE_INVALID);
        else if (action == TasksCloudPolicy.Action.CONFLICT && !automatic) analytics().syncIssue(account, TasksAnalyticsSink.ISSUE_CONFLICT_MANUAL);
        return false;
    }

    /**
     * x-consent-fence: the request's entries are already back in the queue without an attempt; the whole account holds
     * (30 s doubling to 900 s) and nothing is discarded — the customer layer decides a real withdrawal.
     */
    private void consentFenced(TasksCloudRemote.Failure failure) {
        batchRun = 0;
        long now = clock.now();
        long until = ledger.recordConsentFence(now, failure.effectiveAt);
        status = TasksCloudState.Status.CONSENT_FENCED;
        reportError(failure);
        scheduleStep(Math.max(0, until - now));
    }

    /** error.occurred{tasks_sync}: a backend code, http_&lt;n&gt; or offline. */
    private void reportError(TasksCloudRemote.Failure failure) {
        String code;
        if (failure.kind == TasksCloudRemote.Failure.Kind.OFFLINE) code = "offline";
        else if (failure.code.startsWith("tasks_") || failure.code.equals("batch_disabled") || failure.code.equals("migration_paused")) code = failure.code;
        else if (failure.httpStatus > 0) code = "http_" + failure.httpStatus;
        else code = failure.code;
        analytics().error(account, TasksAnalyticsSink.DOMAIN_SYNC, code);
    }

    /** Account-level pauses: unlinked (403) / session (401) persist; local no-binding/config blocks until refresh. */
    private boolean pause(TasksCloudPolicy.Action action, TasksCloudRemote.Identity identity) {
        switch (action) {
            case PAUSE_UNLINKED:
            case PAUSE_SESSION:
                ledger.writeMeta(TasksCloudLedger.META_PAUSE, action == TasksCloudPolicy.Action.PAUSE_UNLINKED
                        ? TasksCloudLedger.PAUSE_UNLINKED : TasksCloudLedger.PAUSE_SESSION);
                pausedGeneration = identity.generation;
                status = action == TasksCloudPolicy.Action.PAUSE_UNLINKED ? TasksCloudState.Status.UNLINKED_PAUSED : TasksCloudState.Status.SESSION_INVALID;
                return true;
            case PAUSE_NOT_LINKED:
                blockedUntilRefresh = true;
                blockedGeneration = identity.generation;
                status = TasksCloudState.Status.NOT_LINKED;
                return true;
            default:
                return false;
        }
    }

    private void scheduleRetry(long delayMs) {
        retryAt = clock.now() + delayMs;
        scheduleStep(delayMs);
    }

    private void scheduleIdleWake() {
        if (!started || principal == null) return;
        long now = clock.now();
        long wake = ledger.nextWake(principal);
        long hold = ledger.nextHoldEnd(now);
        if (hold > 0 && (wake <= 0 || hold < wake)) wake = hold;
        long deferral = ledger.nextDeferralDue();
        if (deferral > 0 && (wake <= 0 || deferral < wake)) wake = deferral;
        long poll = (lastSyncAt == 0 ? now : lastSyncAt) + POLL_MS;
        long at = wake > 0 ? Math.min(wake, poll) : poll;
        scheduleStep(Math.max(0, at - now));
    }

    private void scheduleStep(long delayMs) {
        final long token = ++wakeToken;
        scheduler.schedule(new Runnable() {
            @Override public void run() {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        if (closed || token != wakeToken || !started) return;
                        guarded(new Runnable() {
                            @Override public void run() {
                                if (clock.now() >= lastSyncAt + POLL_MS) syncRequested = true;
                                step();
                            }
                        });
                    }
                });
            }
        }, delayMs);
    }

    /** Storage/closed failures never escape the executor; the fence wins silently, storage is surfaced. */
    void guarded(Runnable work) {
        try {
            work.run();
        } catch (TasksSql.ClosedException e) {
            closed = true;
            return;
        } catch (RuntimeException e) {
            status = TasksCloudState.Status.STORAGE_FAILED;
            batchRun = 0;
        }
        publish();
    }

    /** During a batch drain (from its second batch on) at most one publication per {@link #PUBLISH_INTERVAL_MS}. */
    void publish() {
        if (closed) return;
        long now = clock.now();
        if (batchRun >= 2 && now - lastPublishAt < PUBLISH_INTERVAL_MS) {
            if (publishScheduled) return;
            publishScheduled = true;
            scheduler.schedule(new Runnable() {
                @Override public void run() {
                    executor.execute(new Runnable() {
                        @Override public void run() {
                            publishScheduled = false;
                            if (!closed) publishNow();
                        }
                    });
                }
            }, PUBLISH_INTERVAL_MS - (now - lastPublishAt));
            return;
        }
        publishNow();
    }

    private void publishNow() {
        lastPublishAt = clock.now();
        try {
            listener.onState(state());
        } catch (TasksSql.ClosedException e) {
            closed = true;
        } catch (RuntimeException e) {
            // Unreadable storage: surface the failure without content rather than a stale or partial view.
            status = TasksCloudState.Status.STORAGE_FAILED;
            listener.onState(TasksCloudState.empty(status, rawTelegramId));
        }
    }

    /**
     * Presentation only. Before this process binds a principal (e.g. cold start while unlinked), use the ledger's
     * persisted principal. Rebinding clears shadow/cursors; old-principal outbox entries are retained, never retagged.
     * This neither binds nor enables sends: intents still require the live linked identity.
     */
    TasksCloudState state() {
        String shown = principal != null ? principal : ledger.readMeta(TasksCloudLedger.META_PRINCIPAL);
        List<TasksCloudLedger.Deferral> deferrals = ledger.deferrals();
        return TasksCloudState.derive(status, inFlight != 0, "1".equals(ledger.readMeta(TasksCloudLedger.META_SHADOW_COMPLETE)),
                rawTelegramId, shown, ledger.readMeta(TasksCloudLedger.META_SERVER_TIME), ledger.shadow(), ledger.entries(),
                ledger.notices(), deferrals, Math.max(0, ledger.deferralRows() - deferrals.size()), ledger.autoAcceptInvitationIds());
    }

    void kick() {
        step();
        publish();
    }

    // ---- intents: one path = resolve, validate, atomic overlay + outbox row ----

    /**
     * The principal intents are sealed for: the live linked one; or, while this Telegram user is unlinked, the
     * principal this database is bound to (sent after the relink; a different principal then shows WRONG_PRINCIPAL).
     */
    String requirePrincipal() {
        if (closed) throw new TasksCloudError(TasksCloudError.Code.CLOSED);
        TasksCloudRemote.Identity identity = remote.identity();
        if (identity.telegramUserId == rawTelegramId && identity.isLinked()) {
            bind(identity.principal);
            return principal;
        }
        if (identity.telegramUserId == rawTelegramId) {
            String bound = principal != null ? principal : ledger.readMeta(TasksCloudLedger.META_PRINCIPAL);
            if (bound != null) return bound;
        }
        throw new TasksCloudError(TasksCloudError.Code.NOT_LINKED);
    }

    private TasksCloudState.TaskView ownedTask(TasksCloudState state, String taskId) {
        TasksCloudState.TaskView task = state.task(taskId);
        if (task == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        if (!task.owner) throw new TasksCloudError(TasksCloudError.Code.NOT_OWNER);
        return task;
    }

    private static String listKey(String listId) {
        return listId == null ? null : TasksCloudLedger.listChain(listId);
    }

    String createList(final String title, final int sortOrder) {
        String p = requirePrincipal();
        final String listId = TasksWire.newId();
        final TasksWire.Operation operation = TasksWire.Operation.listCreate(listId, title, sortOrder, null);
        ledger.enqueue(p, TasksWire.newId(), TasksCloudLedger.listChain(listId), null, null, false, null, base -> operation, false);
        analytics().listCreated(account, true);
        kick();
        return listId;
    }

    String updateList(final String listId, final String title, final int sortOrder) {
        String p = requirePrincipal();
        TasksCloudState.ListView list = state().list(listId);
        if (list == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        // The three-way base of §7.3: the title and order this edit was made from.
        String mutationId = TasksWire.newId();
        ledger.enqueue(p, mutationId, TasksCloudLedger.listChain(listId), null, null, true, null,
                base -> TasksWire.Operation.listUpdate(listId, base, title, sortOrder), false,
                TasksCloudLedger.listBase(list.title, list.sortOrder));
        kick();
        return mutationId;
    }

    String deleteList(final String listId) {
        String p = requirePrincipal();
        TasksCloudState state = state();
        if (state.list(listId) == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        int members = 0;
        for (TasksCloudState.TaskView task : state.tasks) if (task.owner && listId.equals(task.content.listId)) members++;
        String mutationId = enqueueBased(p, TasksCloudLedger.listChain(listId), null, null,
                base -> TasksWire.Operation.listDelete(listId, base), false);
        analytics().listDeleted(account, true, members);
        return mutationId;
    }

    String createTask(TasksWire.Content content) {
        return createTask(content, null);
    }

    /**
     * The composer's Add, with or without an assignee. With one, a single transaction enqueues the task.create and the
     * invitation.request on the same task chain — the request unsealed until the create's receipt. The create never
     * carries an annotation. A refused pick (self, the pacer) queues nothing, the create included; a refused create
     * keeps the request waiting behind it, and discarding the create discards the request.
     */
    String createTask(TasksWire.Content draft, String recipientTgId) {
        String p = requirePrincipal();
        TasksCloudState state = state();
        if (draft.listId != null && state.list(draft.listId) == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        final TasksWire.Content content = withAnnotation(draft, null);
        final String taskId = TasksWire.newId();
        final TasksWire.Operation.TaskCreate operation = TasksWire.Operation.taskCreate(taskId, content, null, false, null);
        if (recipientTgId == null) {
            ledger.enqueue(p, TasksWire.newId(), TasksCloudLedger.taskChain(taskId), listKey(content.listId), null, false, null, base -> operation, false);
        } else {
            String recipient = checkedRecipient(recipientTgId);
            ledger.enqueueCreateWithAssignment(p, TasksWire.newId(), operation, listKey(content.listId), TasksWire.newId(), TasksWire.newId(),
                    recipient, clock.now(), state.pendingAssignments(clock.now() / 1000));
            analytics().shared(account, TasksAnalyticsSink.SHARED_INVITE_SENT);
        }
        analytics().taskCreated(account, true, content.dueAt != null, content.priority, content.listId != null);
        kick();
        return taskId;
    }

    private static TasksWire.Content withAnnotation(TasksWire.Content content, TasksWire.Annotation annotation) {
        if (annotation == null ? content.annotation == null : annotation.equals(content.annotation)) return content;
        return new TasksWire.Content(content.listId, content.title, content.description, content.dueAt, content.priority,
                content.sortOrder, annotation);
    }

    /**
     * Against a server that proved wire 4: a field-level task.patch of exactly the fields that differ from the task as
     * shown (expect = those shown values), sealed at once (no base revision); nothing changed → no entry (null).
     * Otherwise the R3 task.update.
     */
    String updateTask(final String taskId, final TasksWire.Content draft) {
        String p = requirePrincipal();
        TasksCloudState state = state();
        TasksCloudState.TaskView task = ownedTask(state, taskId);
        // A Save keeps the task's current annotation whatever the editor's draft carries: a draft opened before a clear
        // cannot write the name and Telegram id back, and no edit sets one.
        final TasksWire.Content content = withAnnotation(draft, task.content.annotation);
        if (content.listId != null && state.list(content.listId) == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        // A move depends on BOTH lists: a later delete of the list it leaves must wait for the move's receipt.
        String mutationId = TasksWire.newId();
        if (ledger.isServerWire4()) {
            LinkedHashMap<TasksWire.ContentField, Object> set = new LinkedHashMap<>(), expect = new LinkedHashMap<>();
            for (TasksWire.ContentField field : TasksWire.ContentField.values()) {
                Object mine = field.valueIn(content), shown = field.valueIn(task.content);
                if (mine == null ? shown == null : mine.equals(shown)) continue;
                set.put(field, mine);
                expect.put(field, shown);
            }
            if (set.isEmpty()) return null;
            final TasksWire.Operation patch = TasksWire.Operation.taskPatch(taskId, set, expect);
            ledger.enqueue(p, mutationId, TasksCloudLedger.taskChain(taskId), listKey(content.listId), listKey(task.content.listId),
                    false, null, base -> patch, false);
        } else {
            ledger.enqueue(p, mutationId, TasksCloudLedger.taskChain(taskId), listKey(content.listId), listKey(task.content.listId),
                    true, null, base -> TasksWire.Operation.taskUpdate(taskId, base, content), false);
        }
        kick();
        return mutationId;
    }

    String setCompleted(final String taskId, final boolean completed) {
        String p = requirePrincipal();
        TasksCloudState.TaskView task = state().task(taskId);
        if (task == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        final String invitationId = task.liveInvitationId;
        String mutationId = enqueueBased(p, TasksCloudLedger.taskChain(taskId), task.owner ? listKey(task.content.listId) : null,
                task.server == null ? null : task.server.revision,
                base -> TasksWire.Operation.taskComplete(taskId, base, invitationId, completed), false);
        analytics().taskCompleted(account, true, !completed);
        return mutationId;
    }

    /** Durable HELD_DELETE; returns its mutation id. Never sent while held. */
    String holdDelete(final String taskId) {
        String p = requirePrincipal();
        TasksCloudState state = state();
        TasksCloudState.TaskView task = ownedTask(state, taskId);
        String chain = TasksCloudLedger.taskChain(taskId);
        String mutationId = enqueueBased(p, chain, listKey(task.content.listId), null,
                base -> TasksWire.Operation.taskDelete(taskId, base), true);
        liveHolds.add(mutationId);
        scheduleHoldTimeout(mutationId);
        return mutationId;
    }

    private void scheduleHoldTimeout(final String mutationId) {
        scheduler.schedule(new Runnable() {
            @Override public void run() {
                executor.execute(new Runnable() {
                    @Override public void run() {
                        if (closed || !liveHolds.contains(mutationId)) return;
                        guarded(new Runnable() {
                            @Override public void run() { promote(mutationId); }
                        });
                    }
                });
            }
        }, HELD_DELETE_TIMEOUT_MS);
    }

    boolean undo(String mutationId) {
        if (closed) throw new TasksCloudError(TasksCloudError.Code.CLOSED);
        boolean wasLive = liveHolds.remove(mutationId);
        boolean undone = wasLive && ledger.undoHeld(mutationId);
        publish();
        return undone;
    }

    /** Idempotent: the handle is invalidated first, the row promotes at most once (the delete's commit). */
    void promote(String mutationId) {
        if (closed || !liveHolds.remove(mutationId)) return;
        if (ledger.promoteHeld(mutationId)) analytics().taskDeleted(account, true);
        kick();
    }

    // ---- assignments: the assignee pick is the invitation ----

    /** A Telegram user other than the account itself; the validated id. */
    private String checkedRecipient(String recipientTgId) {
        String recipient = TasksWireText.positive(recipientTgId, "recipient_tg_id");
        if (recipient.equals(Long.toString(rawTelegramId))) throw new TasksCloudError(TasksCloudError.Code.SELF_ASSIGNMENT);
        return recipient;
    }

    /**
     * Pick-time check before anything is queued (the composer pill and the editor row ask it the moment a contact is
     * picked): a Telegram user other than the account itself, and one more manual pick within the pacer. Throws INVALID,
     * SELF_ASSIGNMENT or ASSIGNMENT_LIMIT.
     */
    void checkAssignment(String recipientTgId) {
        requirePrincipal();
        String recipient = checkedRecipient(recipientTgId);
        long now = clock.now();
        TasksInvitationPacer.Limit limit = ledger.pacerRefusal(recipient, now, state().pendingAssignments(now / 1000));
        if (limit != null) throw new TasksCloudError(TasksCloudError.Code.ASSIGNMENT_LIMIT, limit.name());
    }

    /**
     * The assignee pick on an owned task (the editor row, "Change assignee"): the invitation.request to that contact, a
     * new epoch. A live assignment is retired by the server when it lands (the UI confirms that first). A never-synced
     * task may be assigned: the request waits unsealed for the create's receipt. Returns the new invitation id.
     */
    String assign(final String taskId, final String recipientTgId) {
        String p = requirePrincipal();
        TasksCloudState state = state();
        TasksCloudState.TaskView task = ownedTask(state, taskId);
        if (task.completed) throw new TasksCloudError(TasksCloudError.Code.INVALID, "is_completed");
        String recipient = checkedRecipient(recipientTgId);
        String invitationId = TasksWire.newId();
        // The live epoch this pick replaces (the screen confirmed it): a conflict is resealed only while that still holds.
        ledger.enqueueAssignment(p, TasksWire.newId(), taskId, listKey(task.content.listId), invitationId, recipient, false, clock.now(),
                state.pendingAssignments(clock.now() / 1000), task.liveInvitationId);
        analytics().shared(account, TasksAnalyticsSink.SHARED_INVITE_SENT);
        kick();
        return invitationId;
    }

    /**
     * Send again: an ended assignment — declined, left, expired, or pending past its expiry — goes to the same recipient
     * as a new epoch. A live or removed assignment is refused (NOT_ALLOWED: replace it, or pick again).
     */
    String resendAssignment(final String taskId) {
        requirePrincipal();
        TasksCloudState.TaskView task = ownedTask(state(), taskId);
        if (task.assignment == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        TasksAssigneeDisplay.State shown = task.assigneeState(clock.now() / 1000);
        if (shown == null || !shown.isEnded()) throw new TasksCloudError(TasksCloudError.Code.NOT_ALLOWED);
        return assign(taskId, task.assignment.recipientTgId);
    }

    /**
     * Removes exactly the assignment epoch the confirmation showed (pending or accepted); a newer epoch is refused
     * (CHANGED_ELSEWHERE).
     */
    String revoke(final String taskId, final String expectedInvitationId) {
        requirePrincipal();
        TasksCloudState.TaskView task = ownedTask(state(), taskId);
        TasksWire.Assignment assignment = task.assignment;
        if (assignment == null || (assignment.state != TasksWire.AssignmentState.PENDING && assignment.state != TasksWire.AssignmentState.ACCEPTED)) {
            throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        }
        if (!assignment.invitationId.equals(expectedInvitationId) || !expectedInvitationId.equals(task.liveInvitationId)) {
            throw new TasksCloudError(TasksCloudError.Code.CHANGED_ELSEWHERE);
        }
        return revoke(taskId);
    }

    /**
     * "Remove assignee" on a legacy annotation-only task: the one explicit annotation write of this client, the
     * owner-field clear — a task.patch expecting the annotation shown (R4 server), the R3 full-content update without it
     * before that. A task without an annotation is left alone (null).
     */
    String removeLegacyAnnotation(final String taskId) {
        String p = requirePrincipal();
        TasksCloudState.TaskView task = ownedTask(state(), taskId);
        if (task.content.annotation == null) return null;
        String mutationId = TasksWire.newId();
        if (ledger.isServerWire4()) {
            final TasksWire.Operation clear = TasksCloudLedger.annotationClear(taskId, task.content.annotation);
            ledger.enqueue(p, mutationId, TasksCloudLedger.taskChain(taskId), listKey(task.content.listId), null, false, null, base -> clear, false);
        } else {
            final TasksWire.Content content = withAnnotation(task.content, null);
            ledger.enqueue(p, mutationId, TasksCloudLedger.taskChain(taskId), listKey(task.content.listId), null, true, null,
                    base -> TasksWire.Operation.taskUpdate(taskId, base, content), false);
        }
        kick();
        return mutationId;
    }

    /**
     * Cycle start: deferred assignments whose time has come go back to the outbox as the same pick retried — while the
     * task is still owned, open, without a request of its own on the way, and with no live assignee other than the one
     * the pick was made to replace (a newer pick elsewhere ends the deferral quietly: nobody's access is ended without
     * the owner's word).
     */
    private void reenqueueDueDeferrals(long now) {
        if (principal == null || !ledger.hasDueDeferralWork(now)) return;
        TasksCloudState state = state();
        HashMap<String, String> sendable = new HashMap<>();
        for (TasksCloudLedger.Deferral deferral : ledger.deferrals()) {
            TasksCloudState.TaskView task = state.task(deferral.taskId);
            if (task == null || !task.owner || task.completed || task.requestRecipientTgId != null || task.removalQueued()) continue;
            if (TasksAssigneeDisplay.isLive(task.assignment, now / 1000) && !task.assignment.invitationId.equals(deferral.replaces)) continue;
            sendable.put(task.id, listKey(task.content.listId));
        }
        int resent = ledger.reenqueueDueDeferrals(principal, now, sendable);
        for (int i = 0; i < resent; i++) analytics().shared(account, TasksAnalyticsSink.SHARED_INVITE_SENT);
    }

    // ---- the contact rule (automatic accept) ----

    /**
     * The app's entry: the account's device-local inputs changed, or the account just became allowed. Runs the policy
     * over every pending invitation now and keeps the inputs for the pulls that follow.
     */
    void runAutoAccept(TasksAutoAcceptPolicy.Inputs inputs) {
        if (closed) return;
        autoAcceptInputs = inputs;
        if (performAutoAccept(true)) publish();
    }

    /**
     * The app tore the account's device-local sources down (the account stopped being allowed, or its Telegram session
     * was replaced): the inputs it handed in are stale from here on, so the policy does nothing until fresh ones arrive.
     */
    void clearAutoAcceptInputs() {
        autoAcceptInputs = null;
    }

    /**
     * Every pending invitation of the shadow the policy accepts gets its automatic invitation.respond {accept}; nothing
     * else changes. {@code kick} is false inside a cycle (the drain that follows sends what was queued). Returns whether
     * anything was queued.
     */
    private boolean performAutoAccept(boolean kick) {
        final TasksAutoAcceptPolicy.Inputs inputs = autoAcceptInputs;
        if (inputs == null || closed || principal == null || !started || !trafficAllowed()) return false;
        TasksCloudRemote.Identity identity = remote.identity();
        if (identity.telegramUserId != rawTelegramId || !identity.isLinked() || !identity.principal.equals(principal)) return false;
        final long nowSeconds = clock.now() / 1000;
        // Tasks-level blocks as the overlay shows them: the shadow's, minus a queued unblock. A queued sender.block names
        // its invitation, which the ledger counts as answered already.
        final HashSet<String> blockedSenders = new HashSet<>();
        for (TasksWire.BlockItem block : state().blocks) blockedSenders.add(block.id);
        final TasksInviteAnswers answers = TasksInviteAnswers.load(inviteAnswers, nowSeconds);
        int accepted = ledger.runAutoAccept(principal, new TasksCloudLedger.AutoAcceptDecider() {
            @Override public boolean accepts(TasksWire.InvitationItem invitation, Set<String> unresolvedInvitationIds,
                                             Map<String, Integer> automaticAttempts) {
                return TasksAutoAcceptPolicy.accepts(invitation, new TasksAutoAcceptPolicy.Context(true, inputs, rawTelegramId,
                        blockedSenders, nowSeconds, unresolvedInvitationIds, answers, automaticAttempts));
            }
        });
        if (accepted == 0) return false;
        for (int i = 0; i < accepted; i++) analytics().shared(account, TasksAnalyticsSink.SHARED_INVITE_ACCEPTED);
        if (kick) step();
        return true;
    }

    String invite(final String taskId, final String recipientTgId) {
        return assign(taskId, recipientTgId);
    }

    /**
     * A manual answer. It is recorded durably ({@link TasksInviteAnswers}): the invitation id for an Accept or a Decline,
     * the task id too for a Decline or a Leave, so the contact rule never overrides what a person decided by hand and no
     * later epoch of a refused task is accepted automatically.
     */
    String respond(final String invitationId, final TasksWire.Decision decision) {
        String p = requirePrincipal();
        TasksCloudState state = state();
        String mutationId = null;
        long nowSeconds = clock.now() / 1000;
        if (decision == TasksWire.Decision.LEAVE) {
            for (final TasksCloudState.TaskView task : state.tasks) {
                if (!task.owner && invitationId.equals(task.liveInvitationId) && task.server != null) {
                    mutationId = enqueueBased(p, TasksCloudLedger.taskChain(task.id), null, task.server.revision,
                            base -> TasksWire.Operation.invitationRespond(task.id, base, invitationId, decision), false);
                    TasksInviteAnswers.recordRefusedTask(inviteAnswers, task.id, invitationId, nowSeconds);
                    break;
                }
            }
            if (mutationId == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        } else {
            final TasksWire.InvitationItem invitation = state.invitation(invitationId);
            if (invitation == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
            mutationId = enqueueBased(p, TasksCloudLedger.taskChain(invitation.taskId), null, invitation.taskRevision,
                    base -> TasksWire.Operation.invitationRespond(invitation.taskId, base, invitationId, decision), false);
            TasksInviteAnswers.recordInvitation(inviteAnswers, invitationId, decision == TasksWire.Decision.DECLINE ? invitation.taskId : null,
                    nowSeconds);
        }
        analytics().shared(account, decision == TasksWire.Decision.ACCEPT ? TasksAnalyticsSink.SHARED_INVITE_ACCEPTED
                : decision == TasksWire.Decision.DECLINE ? TasksAnalyticsSink.SHARED_INVITE_DECLINED : TasksAnalyticsSink.SHARED_LEFT);
        return mutationId;
    }

    String revoke(final String taskId) {
        String p = requirePrincipal();
        final String invitationId = ownedTask(state(), taskId).liveInvitationId;
        if (invitationId == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        String mutationId = enqueueBased(p, TasksCloudLedger.taskChain(taskId), null, null,
                base -> TasksWire.Operation.invitationRevoke(taskId, base, invitationId), false);
        analytics().shared(account, TasksAnalyticsSink.SHARED_REVOKED);
        return mutationId;
    }

    String blockSender(String invitationId) {
        String p = requirePrincipal();
        if (state().invitation(invitationId) == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
        final TasksWire.Operation operation = TasksWire.Operation.senderBlock(invitationId);
        String mutationId = TasksWire.newId();
        ledger.enqueue(p, mutationId, TasksCloudLedger.invitationChain(invitationId), null, null, false, null, base -> operation, false);
        // A manual answer; the block itself stays durable through the server's sender.block.
        TasksInviteAnswers.recordInvitation(inviteAnswers, invitationId, null, clock.now() / 1000);
        analytics().shared(account, TasksAnalyticsSink.SHARED_BLOCKED);
        kick();
        return mutationId;
    }

    String unblockSender(String senderTgId) {
        String p = requirePrincipal();
        final TasksWire.Operation operation = TasksWire.Operation.senderUnblock(senderTgId);
        String mutationId = TasksWire.newId();
        ledger.enqueue(p, mutationId, TasksCloudLedger.blockChain(senderTgId), null, null, false, null, base -> operation, false);
        kick();
        return mutationId;
    }

    private String enqueueBased(String p, String chain, String listKey, String explicitBase, TasksCloudLedger.Builder builder, boolean held) {
        String mutationId = TasksWire.newId();
        ledger.enqueue(p, mutationId, chain, listKey, null, true, explicitBase, builder, held);
        kick();
        return mutationId;
    }

    void retry(String mutationId) {
        if (!ledger.retry(mutationId)) throw new TasksCloudError(TasksCloudError.Code.BUSY_STATE);
        syncFailures = 0;
        kick();
    }

    void discard(String mutationId) {
        liveHolds.remove(mutationId);
        if (!ledger.discard(mutationId)) throw new TasksCloudError(TasksCloudError.Code.BUSY_STATE);
        kick();
    }

    /**
     * CONFLICT only: reapply seals the same intent as a NEW id against the current shadow; never automatic here (the
     * §7.3 table resolves its own cases after the next complete pull). A task.patch is reapplied with fresh
     * expectations (the server's current values) — "keep mine".
     */
    String resolveConflict(String mutationId, boolean reapply) {
        if (!reapply) {
            discard(mutationId);
            return null;
        }
        String p = requirePrincipal();
        TasksCloudLedger.Entry entry = ledger.find(mutationId);
        if (entry == null || !TasksCloudSchema.CONFLICT.equals(entry.state) || !entry.principal.equals(p)) {
            throw new TasksCloudError(TasksCloudError.Code.BUSY_STATE);
        }
        TasksCloudState state = state();
        final TasksWire.Operation old = entry.operation;
        String explicitBase = null;
        // List dependencies are recaptured from the CURRENT view (the row may have moved remotely since the conflict).
        String listKey = entry.listKey, sourceListKey = entry.sourceListKey;
        TasksCloudLedger.Builder builder;
        if (old instanceof TasksWire.Operation.TaskComplete) {
            final TasksWire.Operation.TaskComplete complete = (TasksWire.Operation.TaskComplete) old;
            TasksCloudState.TaskView task = state.task(complete.taskId);
            if (task == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
            final String invitationId = task.liveInvitationId;
            explicitBase = task.server == null ? null : task.server.revision;
            listKey = task.owner ? listKey(task.content.listId) : null;
            sourceListKey = null;
            builder = base -> TasksWire.Operation.taskComplete(complete.taskId, base, invitationId, complete.isCompleted);
        } else if (old instanceof TasksWire.Operation.TaskPatch) {
            final TasksWire.Operation.TaskPatch patch = (TasksWire.Operation.TaskPatch) old;
            TasksWire.TaskItem server = ledger.shadowTask(patch.taskId);
            if (server == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
            LinkedHashMap<TasksWire.ContentField, Object> expect = new LinkedHashMap<>();
            for (TasksWire.ContentField field : patch.set.keySet()) expect.put(field, field.valueIn(server.content));
            final TasksWire.Operation fresh = TasksWire.Operation.taskPatch(patch.taskId, patch.set, expect);
            Object destination = patch.set.containsKey(TasksWire.ContentField.LIST_ID) ? patch.set.get(TasksWire.ContentField.LIST_ID) : server.content.listId;
            listKey = listKey((String) destination);
            sourceListKey = listKey(server.content.listId);
            builder = base -> fresh;
        } else if (old instanceof TasksWire.Operation.TaskUpdate || old instanceof TasksWire.Operation.TaskDelete) {
            String taskId = old.resourceId();
            TasksCloudState.TaskView task = state.task(taskId);
            if (task == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
            listKey = old instanceof TasksWire.Operation.TaskUpdate
                    ? listKey(((TasksWire.Operation.TaskUpdate) old).content.listId) : listKey(task.content.listId);
            sourceListKey = listKey(task.content.listId);
            builder = base -> TasksCloudLedger.withBase(old, base);
        } else if (old instanceof TasksWire.Operation.InvitationRespond
                && ((TasksWire.Operation.InvitationRespond) old).decision != TasksWire.Decision.LEAVE) {
            TasksWire.InvitationItem invitation = state.invitation(((TasksWire.Operation.InvitationRespond) old).invitationId);
            if (invitation == null) throw new TasksCloudError(TasksCloudError.Code.NOT_FOUND);
            explicitBase = invitation.taskRevision;
            builder = base -> TasksCloudLedger.withBase(old, base);
        } else {
            builder = base -> base == null ? old : TasksCloudLedger.withBase(old, base);
        }
        String newId = TasksWire.newId();
        ledger.reapply(mutationId, p, newId, entry.chain, listKey, sourceListKey, explicitBase, builder);
        kick();
        return newId;
    }

    void dismissNotice(long noticeId) {
        ledger.dismissNotice(noticeId);
        publish();
    }

    /** Unsent entries {user, migration} (every state but acknowledged). */
    int[] unsentCounts() {
        return ledger.unsentCounts();
    }

    long consentEpoch() {
        return ledger.consentEpoch();
    }

    // ---- import ----

    /** Shadow ids and outbox mutation ids for preview classification; requires a complete snapshot. */
    List<String> knownCloudIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (TasksWire.Projection item : ledger.shadow()) ids.add(item.kind.wire() + ":" + item.id);
        return ids;
    }

    List<String> outboxMutationIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (TasksCloudLedger.Entry entry : ledger.entries()) ids.add(entry.mutationId);
        return ids;
    }

    boolean shadowComplete() {
        return "1".equals(ledger.readMeta(TasksCloudLedger.META_SHADOW_COMPLETE));
    }

    /**
     * The cut-over's I1 (design 30 §7.5; iOS SGTasksCloudRepository.cutOverStoreFacts): what this store holds for a copy
     * of the account's device rows — the import scope of its bound principal ({@code scope} is asked only for a bound
     * store), whether it holds a complete snapshot, its unsent migration creates, whether the migration step has checked
     * it, and its resync generation. Executor-confined and side-effect free: it reads the ledger alone, never the
     * in-memory binding and never a bind (which would wipe a store bound to another principal), so a store created after
     * a logout purge reads unbound until its first linked cycle and a cold engine reads what was persisted. Run by the
     * repository inside one blocking turn and by the state suite on the real ledger. A malformed resync generation throws.
     */
    TasksCutOver.StoreFacts cutOverStoreFacts(ImportScope scope) {
        String bound = ledger.readMeta(TasksCloudLedger.META_PRINCIPAL);
        return new TasksCutOver.StoreFacts(bound == null ? null : scope.of(bound), shadowComplete(), unsentCounts()[1],
                ledger.migrationChecked(), ledger.resyncGeneration());
    }

    /** The explicit copy (user origin). */
    int enqueueImport(String expectedPrincipal, TasksCloudImport.Plan plan) {
        return enqueueImport(expectedPrincipal, plan, TasksCloudSchema.ORIGIN_USER, null);
    }

    /**
     * {@code origin} {@link TasksCloudSchema#ORIGIN_MIGRATION}: the automatic migration step (TR4-A3) with the consent
     * epoch it captured; refused (CONSENT_WITHDRAWN) while withdrawn or once the epoch changed, so nothing is queued.
     */
    int enqueueImport(String expectedPrincipal, TasksCloudImport.Plan plan, String origin, Long expectedConsentEpoch) {
        String p = requirePrincipal();
        if (!p.equals(expectedPrincipal)) throw new TasksCloudError(TasksCloudError.Code.PRINCIPAL_CHANGED);
        int inserted = ledger.enqueueAll(p, plan.mutationIds, plan.operations, plan.chains, plan.listKeys, origin, expectedConsentEpoch);
        kick();
        return inserted;
    }
}
