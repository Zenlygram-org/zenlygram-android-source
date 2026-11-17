package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * The repository's realtime controller (port of iOS {@code SGTasksCloudRealtime}): ONE Pusher connection, only while
 * the Cloud board is visible with the app in the foreground, the account has a linked principal and no account-level
 * pause holds. It never touches task content: a hint (and a successful (re)connect) only asks the engine for its
 * existing changes pull, whose single flight, Retry-After and backoff stay authoritative. While the socket is
 * unavailable the engine's 60 s REST poll continues; the status says {@code FALLBACK} / {@code DISABLED}, never LIVE.
 * <p>
 * Confined to the repository's serial executor. Every asynchronous input — metadata, authorization, socket events,
 * timers — carries the attempt it was started under and is dropped when the attempt changed (stop, background,
 * logout, account switch, pause, a newer attempt) or when the Pro identity (Telegram user, principal, generation — the
 * same fence TasksApi uses) moved under it. A rebind changes the generation, so it always gets a new socket and a
 * fresh authorization; a refresh-token rotation keeps the generation (and the server channel).
 */
final class TasksCloudRealtime {
    enum Status {
        /** Not wanted now (board hidden, background, paused, no principal). */
        OFF,
        CONNECTING,
        /** Only after subscription_succeeded on the exact channel. */
        LIVE,
        /** Outage, backoff or resting until the next board start: the 60 s REST poll only. */
        FALLBACK,
        /** The server's intentional kill switch: the 60 s REST poll only; re-read every 300 s. */
        DISABLED
    }

    /** What decides whether a connection is wanted at all. */
    static final class Conditions {
        /** Board visible AND app in the foreground (the screen's resumed state). */
        final boolean active;
        /** The current Pro identity of the repository's Telegram user (null = none / another user). */
        final TasksCloudRemote.Identity identity;
        /** An account-level pause (unlinked / session / not linked) holds. */
        final boolean paused;

        Conditions(boolean active, TasksCloudRemote.Identity identity, boolean paused) {
            this.active = active;
            this.identity = identity;
            this.paused = paused;
        }

        boolean wantsConnection() {
            return active && identity != null && identity.isLinked() && !paused;
        }
    }

    interface Connection {
        void start();

        void stop();
    }

    /** The network side (production: TasksApi + {@link TasksRealtimeSocket} over {@link TasksRealtimeWebSocket}). */
    interface Service {
        void metadata(TasksCloudRemote.Identity expected, TasksCloudRemote.Reply<TasksRealtimeWire.Metadata> reply);

        void authorize(TasksCloudRemote.Identity expected, String socketId, TasksCloudRemote.Reply<TasksRealtimeWire.Subscription> reply);

        /** Only for enabled metadata. Everything it reports must arrive on the owner's executor. */
        Connection open(TasksRealtimeWire.Metadata metadata, TasksRealtimeSocket.Scheduler scheduler,
                        TasksRealtimeSocket.Authorize authorize, TasksRealtimeSocket.Listener listener);
    }

    /** The owner's side; every call is made on the owner's executor. */
    interface Hooks {
        /** Runs a block on the owner's executor (network replies arrive anywhere). */
        void perform(Runnable block);

        /** The engine's existing changes pull (single flight, gated). */
        void requestPull();

        /** Milliseconds until the shared reads Retry-After gate opens; 0 when open. */
        long gateDelayMs();

        /** The identity bound right now (strong fence on every callback). */
        TasksCloudRemote.Identity currentIdentity();

        /** A refusal the engine's own rules handle (durable pauses, the 429 gate). */
        void refused(TasksCloudRemote.Failure failure, TasksCloudRemote.Identity identity);
    }

    /** Uniform in [0, 1) (reconnect jitter). */
    interface Jitter { double next(); }

    static final long COALESCE_MS = 250;
    static final long MIN_RECONNECT_MS = 1_000;
    static final long MAX_RECONNECT_MS = 60_000;
    /** The intentional kill switch ({@code enabled:false}) is re-read this rarely while the board stays open. */
    static final long DISABLED_RECHECK_MS = 300_000;

    private enum Phase { IDLE, FETCHING_METADATA, CONNECTED, WAITING }

    private final Service service;
    private final TasksRealtimeSocket.Scheduler scheduler;
    private final Jitter jitter;
    private final Hooks hooks;

    private Conditions conditions = new Conditions(false, null, false);
    private Phase phase = Phase.IDLE;
    private Connection connection;
    private long attempt;
    private TasksCloudRemote.Identity attemptIdentity;
    private int activityTimeout;
    private boolean live, resting;
    private int failures;
    private TasksRealtimeSocket.Cancellable retryWork, coalesceWork, stableWork;
    private Status status = Status.OFF;

    TasksCloudRealtime(Service service, TasksRealtimeSocket.Scheduler scheduler, Jitter jitter, Hooks hooks) {
        this.service = service;
        this.scheduler = scheduler;
        this.jitter = jitter;
        this.hooks = hooks;
    }

    Status status() { return status; }

    // ---- inputs ----

    void update(Conditions next) {
        boolean wasWanted = conditions.wantsConnection();
        conditions = next;
        if (!next.wantsConnection()) {
            teardown(Status.OFF);
            return;
        }
        if (attemptIdentity != null && !sameIdentity(attemptIdentity, next.identity)) {
            // Account switch, relink, unlink+link: nothing of the old attempt survives, including its rest and backoff
            // (as iOS on a session change): the new binding gets a fresh attempt while the board stays visible.
            teardown(Status.OFF);
            failures = 0;
            resting = false;
        }
        if (!wasWanted) {
            // Board start / foreground / end of a pause: a fresh budget of attempts.
            failures = 0;
            resting = false;
        }
        connectIfIdle();
    }

    void stop() {
        teardown(Status.OFF);
    }

    static boolean sameIdentity(TasksCloudRemote.Identity a, TasksCloudRemote.Identity b) {
        if (a == null || b == null) return false;
        return a.telegramUserId == b.telegramUserId && a.generation == b.generation
                && (a.principal == null ? b.principal == null : a.principal.equals(b.principal));
    }

    // ---- attempts ----

    private void connectIfIdle() {
        if (conditions.wantsConnection() && !resting && phase == Phase.IDLE) connect();
    }

    private void connect() {
        final long current = ++attempt;
        attemptIdentity = conditions.identity;
        // The shared reads budget: nothing is asked while the account's gate is closed (G5).
        long gate = hooks.gateDelayMs();
        if (gate > 0) {
            waitFor(gate, Status.FALLBACK);
            return;
        }
        phase = Phase.FETCHING_METADATA;
        setStatus(Status.CONNECTING);
        service.metadata(attemptIdentity, new TasksCloudRemote.Reply<TasksRealtimeWire.Metadata>() {
            @Override public void success(final TasksRealtimeWire.Metadata value) { hooks.perform(() -> metadataArrived(value, null, current)); }

            @Override public void failure(final TasksCloudRemote.Failure failure) { hooks.perform(() -> metadataArrived(null, failure, current)); }
        });
    }

    private boolean isCurrent(long candidate) {
        if (candidate != attempt || !conditions.wantsConnection()) return false;
        if (!sameIdentity(hooks.currentIdentity(), attemptIdentity)) {
            // The binding moved under this attempt: drop it; the owner's next update reconnects.
            teardown(Status.OFF);
            return false;
        }
        return true;
    }

    private void metadataArrived(TasksRealtimeWire.Metadata metadata, TasksCloudRemote.Failure failure, long candidate) {
        if (!isCurrent(candidate) || phase != Phase.FETCHING_METADATA) return;
        phase = Phase.IDLE;
        if (failure != null) {
            refused(failure, candidate);
            return;
        }
        if (!metadata.isEnabled()) {
            waitFor(DISABLED_RECHECK_MS, Status.DISABLED);
            return;
        }
        open(metadata, candidate);
    }

    private void open(TasksRealtimeWire.Metadata metadata, final long candidate) {
        activityTimeout = metadata.activityTimeout;
        final TasksCloudRemote.Identity identity = attemptIdentity;
        TasksRealtimeSocket.Authorize authorize = (socketId, reply) -> {
            if (!isCurrent(candidate) || phase != Phase.CONNECTED) return;
            long gate = hooks.gateDelayMs();
            if (gate > 0) {
                // The auth POST spends the shared reads budget: not before the gate opens (waitFor closes this socket).
                waitFor(gate, Status.FALLBACK);
                return;
            }
            service.authorize(identity, socketId, new TasksCloudRemote.Reply<TasksRealtimeWire.Subscription>() {
                @Override public void success(final TasksRealtimeWire.Subscription value) {
                    hooks.perform(() -> { if (isCurrent(candidate)) reply.success(value); });
                }

                @Override public void failure(final TasksCloudRemote.Failure failure) {
                    hooks.perform(() -> { if (isCurrent(candidate)) reply.failure(failure); });
                }
            });
        };
        connection = service.open(metadata, scheduler, authorize, new TasksRealtimeSocket.Listener() {
            @Override public void subscribed() { if (isCurrent(candidate) && phase == Phase.CONNECTED) onSubscribed(candidate); }

            @Override public void invalidated() { if (isCurrent(candidate) && phase == Phase.CONNECTED) onInvalidated(candidate); }

            @Override public void closed(TasksRealtimeSocket.Close close) {
                if (!isCurrent(candidate) || phase != Phase.CONNECTED) return;
                connection = null;
                phase = Phase.IDLE;
                live = false;
                cancelStable();
                onClosed(close, candidate);
            }
        });
        phase = Phase.CONNECTED;
        live = false;
        setStatus(Status.CONNECTING);
        connection.start();
    }

    private void onSubscribed(final long candidate) {
        live = true;
        setStatus(Status.LIVE);
        // A (re)connect may have missed hints: repair through the existing REST pull now.
        hooks.requestPull();
        cancelStable();
        stableWork = scheduler.schedule(() -> {
            if (candidate != attempt || !live) return;
            // Stable for an activity timeout: only now is the backoff forgiven.
            stableWork = null;
            failures = 0;
        }, activityTimeout * 1000L);
    }

    private void onInvalidated(final long candidate) {
        if (!live || coalesceWork != null) return;
        coalesceWork = scheduler.schedule(() -> {
            coalesceWork = null;
            if (candidate == attempt && live) hooks.requestPull();
        }, COALESCE_MS);
    }

    private void onClosed(TasksRealtimeSocket.Close close, long candidate) {
        switch (close.reason) {
            case AUTHORIZATION:
                refused(close.failure, candidate);
                return;
            case SERVER:
                int code = close.code == null ? -1 : close.code;
                if (code >= 4000 && code <= 4099) {
                    // The server asked not to reconnect: REST only until the next board start.
                    rest();
                } else if (code >= 4200 && code <= 4299) {
                    failed(candidate, jittered(MIN_RECONNECT_MS));
                } else {
                    failed(candidate, 0);
                }
                return;
            case PROTOCOL_VIOLATION:
            case SUBSCRIPTION_REFUSED:
                // Deterministic: the same frames / refusal would repeat. Rest instead of spending the reads budget.
                rest();
                return;
            default:
                failed(candidate, 0);
        }
    }

    /** A refusal from either named call (metadata GET or the socket's auth POST). */
    private void refused(TasksCloudRemote.Failure failure, long candidate) {
        TasksCloudRemote.Identity identity = attemptIdentity;
        switch (failure.kind) {
            case UNLINKED:
            case SESSION:
                teardown(Status.OFF);
                hooks.refused(failure, identity);
                return;
            case LOCAL:
                if ("link_required".equals(failure.code) || "not_configured".equals(failure.code) || "login_required".equals(failure.code)) {
                    teardown(Status.OFF);
                    hooks.refused(failure, identity);
                    return;
                }
                failed(candidate, 0); // storage, clock skew, rotation pending: transient
                return;
            case RATE_LIMITED:
                // The same reads budget as snapshot/changes: the engine's gate is the single authority.
                hooks.refused(failure, identity);
                failed(candidate, Math.max(hooks.gateDelayMs(), Math.max(1, failure.retryAfterSeconds) * 1000L));
                return;
            case STALE:
            case BUSY:
            case OFFLINE:
            case UNAVAILABLE:
                failed(candidate, failure.retryAfterSeconds * 1000L);
                return;
            default:
                // TASK / PROTOCOL: a 200 that broke the wire, ws_url off the origin, a 4xx refusal — rest, never a storm.
                rest();
        }
    }

    /**
     * One more consecutive transient failure: the bounded exponential 1..60 s with jitter, or longer when a
     * Retry-After says so (G5: shared Retry-After outranks the 60 s cap). Transient failures never exhaust.
     */
    private void failed(long candidate, long minimumMs) {
        if (candidate != attempt) return;
        failures++;
        waitFor(Math.max(jittered(backoffBaseMs(failures)), minimumMs), Status.FALLBACK);
    }

    /** Exponential 1 s .. 60 s. */
    static long backoffBaseMs(int failures) {
        int exponent = Math.max(0, Math.min(failures - 1, 16));
        return Math.min(MIN_RECONNECT_MS << exponent, MAX_RECONNECT_MS);
    }

    /** Uniform in [base / 2, base], never under 1 s. */
    private long jittered(long baseMs) {
        double fraction = Math.min(Math.max(jitter.next(), 0), 1);
        return Math.max(MIN_RECONNECT_MS, (long) (baseMs * (0.5 + 0.5 * fraction)));
    }

    private void waitFor(long delayMs, Status next) {
        closeConnection();
        cancel(retryWork);
        final long candidate = attempt;
        phase = Phase.WAITING;
        setStatus(next);
        retryWork = scheduler.schedule(() -> {
            if (candidate != attempt || phase != Phase.WAITING) return;
            retryWork = null;
            phase = Phase.IDLE;
            connectIfIdle();
        }, delayMs);
    }

    /** No more attempts until the next board start / foreground / end of a pause. */
    private void rest() {
        closeConnection();
        cancel(retryWork);
        retryWork = null;
        phase = Phase.IDLE;
        resting = true;
        setStatus(Status.FALLBACK);
    }

    private void closeConnection() {
        if (connection != null) {
            Connection closing = connection;
            connection = null;
            closing.stop();
        }
        live = false;
        cancelStable();
    }

    private void cancelStable() {
        cancel(stableWork);
        stableWork = null;
    }

    /** Ends the attempt: every later callback of it is dropped, every timer cancelled. */
    private void teardown(Status next) {
        attempt++;
        closeConnection();
        cancel(retryWork);
        retryWork = null;
        cancel(coalesceWork);
        coalesceWork = null;
        phase = Phase.IDLE;
        attemptIdentity = null;
        setStatus(next);
    }

    private void setStatus(Status next) {
        status = next;
    }

    private static void cancel(TasksRealtimeSocket.Cancellable work) {
        if (work != null) work.cancel();
    }
}
