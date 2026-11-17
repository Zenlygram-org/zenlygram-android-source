package org.telegram.messenger.zenlygram.customer;

import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.CustomerApi;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Random;

/**
 * Uploads queued statistics (design 50 §11.6; contract §2.4.5, §6.5): one {@code POST /v1/profile/events} batch per
 * {@link #flush}, on the account's own linked binding through {@code CustomerApi.postEvents} (credentials never leave
 * ZenlyPro). The coordinator (ZenlyCustomer) decides when a flush is due — only when its pipeline allows the events
 * step: background entry, ≥ 50 queued ({@link #setQueueListener}), every {@code flush_interval_s} in the foreground,
 * once after first idle, after the first profile sync — and handles what an answer means for consent and the binding.
 * This class enforces the rest:
 * <ul>
 * <li>one batch in flight per account; the selected account flushes on every trigger, every other account at most once
 * per {@code flush_interval_s};</li>
 * <li>the account's recording rule is asked when the flush starts, on the store's thread before the batch is built,
 * once more right before the POST and a last time on the customer worker right before the batch is signed (where it
 * may wait behind other calls), each also refused once {@link #forget} dropped the account's slot: a batch prepared
 * before a withdrawal, deletion, logout or binding loss is never sent after it (contract §6.7);</li>
 * <li>every send takes a token of the installation's events budget ({@link CustomerBackoff#INSTALL_BUDGET}, 48/h for
 * the whole install against the server's 60/h per device), the selected account first;</li>
 * <li>the answer's effect on the queue ({@link EventQueuePolicy#afterAnswer}): 200 deletes exactly the sent events and
 * stores {@code flush_interval_s}, {@code "ingest": "paused"} pauses uploads for 24 h, 413 halves the batch limit (for
 * this process), 422 drops the batch and counts {@code error.occurred{events_upload, invalid_request}}, 429 / 5xx /
 * 404 / offline back off per account ({@link CustomerBackoff.State}, Retry-After wins); 401, 403 and a session change
 * keep the events.</li>
 * </ul>
 * Main thread only. Nothing here logs.
 */
public final class ZenlyEventUploader {
    /** Why nothing was sent. */
    public enum Skip {
        /** Not a signed-in account that may record (consent, deletion, statistics not installed). */
        NOT_ALLOWED,
        /** No usable origin, or the account is not linked at it. */
        NOT_LINKED,
        /** A batch of this account is in flight. */
        IN_FLIGHT,
        /** The account's events route backs off. */
        BACKOFF,
        /** Not the selected account and its last flush is younger than {@code flush_interval_s}. */
        TOO_SOON,
        /** The server paused uploads ({@code "ingest": "paused"}). */
        PAUSED,
        /** The installation's events budget is used up for now. */
        BUDGET,
        /** Nothing is queued (or nothing sendable). */
        EMPTY
    }

    /** One flush's result (main thread). */
    public static final class Result {
        public final int account;
        public final long telegramUserId;
        /** Non-null: nothing was sent, and why. */
        public final Skip skipped;
        /** The sent batch's outcome (null when skipped). */
        public final CustomerOutcome outcome;
        /** The raw reply of the sent batch (null when skipped): its reason, Retry-After and body for the coordinator. */
        public final CustomerApi.Reply reply;
        /** Events in the sent batch. */
        public final int events;
        /** Events still queued afterwards (the queue's count when skipped). */
        public final int queued;
        /** Monotonic ms until the next attempt may be made (backoff, pause, budget, interval); 0 = now. */
        public final long retryInMs;

        Result(int account, long telegramUserId, Skip skipped, CustomerOutcome outcome, CustomerApi.Reply reply, int events, int queued,
                long retryInMs) {
            this.account = account;
            this.telegramUserId = telegramUserId;
            this.skipped = skipped;
            this.outcome = outcome;
            this.reply = reply;
            this.events = events;
            this.queued = queued;
            this.retryInMs = Math.max(0, retryInMs);
        }
    }

    public interface Callback { void done(Result result); }

    /** Main thread: an account's queue reached {@link EventQueuePolicy#FLUSH_THRESHOLD} events (a flush trigger). */
    public interface QueueListener { void onQueued(int account, int queued); }

    /** Per account slot: the batch limit (halved by 413s, kept for the process), backoff, last flush, in-flight flag. */
    private static final class State {
        final long telegramUserId;
        final String scope;
        int limit = EventQueuePolicy.MAX_BATCH_EVENTS;
        CustomerBackoff.State backoff = CustomerBackoff.State.IDLE;
        long lastFlushMs = -1;
        int flushIntervalS = EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS;
        boolean inFlight;
        /** Set by {@link #forget}; read on the customer worker too. */
        volatile boolean forgotten;

        State(long telegramUserId, String scope) {
            this.telegramUserId = telegramUserId;
            this.scope = scope;
        }
    }

    private static final HashMap<Integer, State> STATES = new HashMap<>();
    private static final Random RANDOM = new SecureRandom();

    private ZenlyEventUploader() {}

    /**
     * Reports, on the main thread, every write that leaves an account with ≥ 50 queued events at the configured origin;
     * null stops it.
     */
    public static void setQueueListener(final QueueListener listener) {
        ZenlyEventStore store = ZenlyEventStore.get();
        if (store == null) return;
        store.setQueueListener(listener == null ? null : (scope, tg, queued) -> {
            if (!scope.equals(ZenlyPro.configuredStorageToken())) return;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (ZenlyStats.telegramUserId(account) == tg) {
                    listener.onQueued(account, queued);
                    return;
                }
            }
        });
    }

    /** The account's {@code flush_interval_s} as last answered by the server (300 until then). */
    public static int flushIntervalSeconds(int account) {
        State state = STATES.get(account);
        return state == null ? EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS : state.flushIntervalS;
    }

    /** A batch of the account is in flight. */
    public static boolean inFlight(int account) {
        State state = STATES.get(account);
        return state != null && state.inFlight;
    }

    /**
     * Sends the account's next batch when allowed (main thread; {@code done} runs exactly once, on the main thread).
     * {@code selected} = it is {@code UserConfig.selectedAccount}: it flushes on every trigger and may use the budget's
     * reserve.
     */
    public static void flush(final int account, final boolean selected, final Callback done) {
        if (done == null) throw new IllegalArgumentException("Missing flush callback");
        try {
            start(account, selected, done);
        } catch (RuntimeException unexpected) {
            State state = STATES.get(account);
            if (state != null) state.inFlight = false;
            done.done(new Result(account, 0, Skip.NOT_ALLOWED, null, null, 0, 0, 0));
        }
    }

    /**
     * Logout, deletion, withdrawal: forgets the account slot's upload state (the queue itself goes with
     * ZenlyEventStore.deleteAll); a batch of the slot still being prepared, or waiting on the customer worker, is then
     * never sent.
     */
    public static void forget(int account) {
        State removed = STATES.remove(account);
        if (removed != null) removed.forgotten = true;
    }

    private static void start(final int account, final boolean selected, final Callback done) {
        final long tg = ZenlyStats.telegramUserId(account);
        final String scope = ZenlyPro.configuredStorageToken();
        final ZenlyEventStore store = ZenlyEventStore.get();
        if (tg <= 0 || store == null || !ZenlyStats.allowed(account)) {
            done.done(new Result(account, tg, Skip.NOT_ALLOWED, null, null, 0, 0, 0));
            return;
        }
        if (scope == null) {
            done.done(new Result(account, tg, Skip.NOT_LINKED, null, null, 0, 0, 0));
            return;
        }
        State current = STATES.get(account);
        if (current == null || current.telegramUserId != tg || !current.scope.equals(scope)) {
            if (current != null && current.inFlight) {
                done.done(new Result(account, tg, Skip.IN_FLIGHT, null, null, 0, 0, 0));
                return;
            }
            current = new State(tg, scope);
            STATES.put(account, current);
        }
        final State state = current;
        long now = SystemClock.elapsedRealtime();
        if (state.inFlight) {
            done.done(new Result(account, tg, Skip.IN_FLIGHT, null, null, 0, 0, 0));
            return;
        }
        if (!state.backoff.ready(now)) {
            done.done(new Result(account, tg, Skip.BACKOFF, null, null, 0, 0, state.backoff.waitMs(now)));
            return;
        }
        long intervalMs = state.flushIntervalS * 1000L;
        if (!selected && state.lastFlushMs >= 0 && now - state.lastFlushMs < intervalMs) {
            done.done(new Result(account, tg, Skip.TOO_SOON, null, null, 0, 0, state.lastFlushMs + intervalMs - now));
            return;
        }
        final CustomerApi api = CustomerApi.forAccount(account);
        final CustomerApi.Identity identity = api.identity();
        if (!identity.isLinked() || identity.telegramUserId != tg) {
            done.done(new Result(account, tg, Skip.NOT_LINKED, null, null, 0, 0, 0));
            return;
        }
        state.inFlight = true;
        final long sentAtS = System.currentTimeMillis() / 1000L;
        final ZenlyEventStore.Allowed allowed = () -> ZenlyStats.allowedUser(tg);
        store.prepareBatch(scope, tg, state.limit, sentAtS, ZenlyCustomerDevice.context(), allowed, prepared -> {
            try {
                send(account, selected, state, store, api, identity, prepared, allowed, done);
            } catch (RuntimeException unexpected) {
                abandon(account, state, done);
            }
        });
    }

    /** An unexpected failure inside a step: the account is free again and the caller hears of it once. */
    private static void abandon(int account, State state, Callback done) {
        state.inFlight = false;
        done.done(new Result(account, state.telegramUserId, Skip.NOT_ALLOWED, null, null, 0, 0, 0));
    }

    private static void send(final int account, boolean selected, final State state, final ZenlyEventStore store, CustomerApi api,
            CustomerApi.Identity identity, ZenlyEventStore.Prepared prepared, ZenlyEventStore.Allowed allowed, final Callback done) {
        final long tg = state.telegramUserId;
        if (STATES.get(account) != state || !allowed.allowed()) {
            // Withdrawn, deleted, logged out or unlinked since the flush started: nothing prepared before that is sent after
            // it. Its events are left to the purge that comes with that, so the batch is not finished here either.
            state.inFlight = false;
            done.done(new Result(account, tg, Skip.NOT_ALLOWED, null, null, 0, prepared.queued, 0));
            return;
        }
        state.flushIntervalS = prepared.flushIntervalS;
        long now = SystemClock.elapsedRealtime();
        if (prepared.batch == null) {
            state.inFlight = false;
            boolean paused = prepared.pausedUntilS > 0;
            long pauseMs = paused ? Math.max(0, prepared.pausedUntilS - System.currentTimeMillis() / 1000L) * 1000L : 0;
            done.done(new Result(account, tg, paused ? Skip.PAUSED : Skip.EMPTY, null, null, 0, prepared.queued, pauseMs));
            return;
        }
        if (!CustomerBackoff.INSTALL_BUDGET.tryTake(CustomerOutcome.Route.EVENTS, selected, now)) {
            state.inFlight = false;
            long wait = CustomerBackoff.INSTALL_BUDGET.waitMs(CustomerOutcome.Route.EVENTS, selected, now);
            done.done(new Result(account, tg, Skip.BUDGET, null, null, 0, prepared.queued, wait));
            return;
        }
        state.lastFlushMs = now;
        final ZenlyEventStore.Batch batch = prepared.batch;
        final int queued = prepared.queued;
        api.postEvents(identity, batch.body(), () -> !state.forgotten && allowed.allowed(), reply -> {
            try {
                if (reply.kind == CustomerApi.Kind.NOT_ALLOWED) {
                    // The same refusal on the customer worker, where the POST waited: nothing was signed or sent, so
                    // nothing backs off and the batch is not finished either (the purge takes its events).
                    state.inFlight = false;
                    done.done(new Result(account, tg, Skip.NOT_ALLOWED, null, null, 0, queued, 0));
                    return;
                }
                answered(account, state, store, batch, reply, done);
            } catch (RuntimeException unexpected) {
                abandon(account, state, done);
            }
        });
    }

    private static void answered(final int account, final State state, ZenlyEventStore store, ZenlyEventStore.Batch batch,
            final CustomerApi.Reply reply, final Callback done) {
        final CustomerOutcome outcome = outcomeOf(reply);
        boolean ingestPaused = false;
        long flushInterval = 0;
        if (outcome.kind == CustomerOutcome.Kind.OK && reply.body != null) {
            try {
                JSONObject body = new JSONObject(reply.body);
                ingestPaused = "paused".equals(body.optString("ingest", ""));
                Object interval = body.opt("flush_interval_s");
                if (interval instanceof Integer || interval instanceof Long) flushInterval = ((Number) interval).longValue();
            } catch (Exception unreadable) {
                // CustomerApi only reports OK for one strict JSON object; a missing field keeps the defaults.
            }
        }
        long now = SystemClock.elapsedRealtime();
        EventQueuePolicy.Effect effect = EventQueuePolicy.afterAnswer(outcome, ingestPaused, flushInterval, state.limit, batch.events,
                System.currentTimeMillis() / 1000L);
        state.limit = effect.nextLimit;
        if (effect.flushIntervalS > 0) state.flushIntervalS = effect.flushIntervalS;
        state.backoff = state.backoff.after(outcome, reply.retryAfterSeconds, RANDOM.nextDouble(), now);
        final int sent = batch.events;
        store.finish(batch, effect, remaining -> {
            state.inFlight = false;
            long retry = state.backoff.waitMs(SystemClock.elapsedRealtime());
            done.done(new Result(account, state.telegramUserId, null, outcome, reply, sent, remaining == null ? 0 : remaining, retry));
        });
    }

    /** The route outcome of a CustomerApi reply (an unreadable 2xx counts as a transport failure: nothing is deleted). */
    static CustomerOutcome outcomeOf(CustomerApi.Reply reply) {
        if (reply.kind == CustomerApi.Kind.STALE) {
            return CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 0, "session_changed", null, false);
        }
        boolean unreadable = reply.kind == CustomerApi.Kind.UNAVAILABLE && reply.status >= 200 && reply.status < 300;
        boolean transport = reply.kind == CustomerApi.Kind.OFFLINE || unreadable;
        return CustomerOutcome.of(CustomerOutcome.Route.EVENTS, reply.status, reply.error, reply.reason, transport);
    }
}
