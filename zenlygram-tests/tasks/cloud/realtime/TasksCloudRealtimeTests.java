package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.List;

/**
 * Host tests of the real TasksCloudRealtime controller with a scripted service, fake connections, a manual clock and
 * a settable identity: lifecycle gating, catch-up pull, 250 ms coalescing, bounded jittered backoff, Retry-After over
 * the 60 s cap, kill-switch recheck, teardown on hide / identity change, attempt fencing of late callbacks.
 */
public final class TasksCloudRealtimeTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    static final String BASE = "https://h.example/v1";
    static final String T = "2026-10-29T10:00:00Z";

    static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    static final TasksRealtimeWire.Metadata ENABLED = TasksRealtimeWire.decodeMetadata("{\"schema_version\":1,\"enabled\":true,"
            + "\"ws_url\":\"wss://h.example/realtime/app/key\",\"activity_timeout\":30,\"server_time\":\"" + T + "\"}", BASE);
    static final TasksRealtimeWire.Metadata DISABLED = TasksRealtimeWire.decodeMetadata("{\"schema_version\":1,\"enabled\":false,"
            + "\"ws_url\":null,\"activity_timeout\":30,\"server_time\":\"" + T + "\"}", BASE);

    static final class FakeConnection implements TasksCloudRealtime.Connection {
        final TasksRealtimeSocket.Authorize authorize;
        final TasksRealtimeSocket.Listener listener;
        boolean started, stopped;

        FakeConnection(TasksRealtimeSocket.Authorize authorize, TasksRealtimeSocket.Listener listener) {
            this.authorize = authorize;
            this.listener = listener;
        }

        @Override public void start() { started = true; }

        @Override public void stop() { stopped = true; }
    }

    static final class Rig implements TasksCloudRealtime.Service, TasksCloudRealtime.Hooks {
        final ManualScheduler clock = new ManualScheduler();
        TasksCloudRemote.Identity identity = new TasksCloudRemote.Identity(42, "principal-a", 7);
        long gate;
        double jitter = 0.999999;
        int pulls;
        final List<TasksCloudRemote.Identity> metadataCalls = new ArrayList<>();
        final List<TasksCloudRemote.Reply<TasksRealtimeWire.Metadata>> metadataReplies = new ArrayList<>();
        final List<String> authCalls = new ArrayList<>();
        final List<TasksCloudRemote.Identity> authIdentities = new ArrayList<>();
        final List<TasksCloudRemote.Reply<TasksRealtimeWire.Subscription>> authReplies = new ArrayList<>();
        final List<FakeConnection> connections = new ArrayList<>();
        final List<TasksCloudRemote.Failure> refusals = new ArrayList<>();
        final TasksCloudRealtime realtime = new TasksCloudRealtime(this, clock, () -> jitter, this);

        // Service
        @Override public void metadata(TasksCloudRemote.Identity expected, TasksCloudRemote.Reply<TasksRealtimeWire.Metadata> reply) {
            metadataCalls.add(expected);
            metadataReplies.add(reply);
        }

        @Override public void authorize(TasksCloudRemote.Identity expected, String socketId, TasksCloudRemote.Reply<TasksRealtimeWire.Subscription> reply) {
            authIdentities.add(expected);
            authCalls.add(socketId);
            authReplies.add(reply);
        }

        @Override public TasksCloudRealtime.Connection open(TasksRealtimeWire.Metadata metadata, TasksRealtimeSocket.Scheduler scheduler,
                                                           TasksRealtimeSocket.Authorize authorize, TasksRealtimeSocket.Listener listener) {
            FakeConnection connection = new FakeConnection(authorize, listener);
            connections.add(connection);
            return connection;
        }

        // Hooks
        @Override public void perform(Runnable block) { block.run(); }

        @Override public void requestPull() { pulls++; }

        @Override public long gateDelayMs() { return gate; }

        @Override public TasksCloudRemote.Identity currentIdentity() { return identity; }

        @Override public void refused(TasksCloudRemote.Failure failure, TasksCloudRemote.Identity identity) { refusals.add(failure); }

        // Driving
        void show() { realtime.update(new TasksCloudRealtime.Conditions(true, identity, false)); }

        void hide() { realtime.update(new TasksCloudRealtime.Conditions(false, identity, false)); }

        void answerMetadata(TasksRealtimeWire.Metadata value) { metadataReplies.get(metadataReplies.size() - 1).success(value); }

        void failMetadata(TasksCloudRemote.Failure.Kind kind, String code, int retryAfter) {
            metadataReplies.get(metadataReplies.size() - 1).failure(new TasksCloudRemote.Failure(kind, code, null, 0, retryAfter));
        }

        FakeConnection connection() { return connections.get(connections.size() - 1); }

        /** show → metadata → open → authorize → subscribed. */
        FakeConnection live() {
            show();
            answerMetadata(ENABLED);
            FakeConnection connection = connection();
            connection.listener.subscribed();
            return connection;
        }

        /** The delay of the single pending retry timer, or -1. */
        long retryDelay() {
            List<ManualScheduler.Timer> pending = clock.pending();
            return pending.size() == 1 ? pending.get(0).delay : -1;
        }
    }

    static TasksRealtimeSocket.Close close(TasksRealtimeSocket.Close.Reason reason, Integer code, TasksCloudRemote.Failure failure) {
        return new TasksRealtimeSocket.Close(reason, code, failure);
    }

    public static void main(String[] args) {
        gating();
        liveAndCatchUp();
        coalescing();
        killSwitch();
        backoffBounds();
        retryAfterOutranksCap();
        hideAndIdentityTeardown();
        refusalsAndRest();
        stableConnectionForgivesBackoff();
        if (!failures.isEmpty()) {
            System.out.println("Tasks realtime controller: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks realtime controller (lifecycle/backoff/fencing): " + checks + " checks passed");
    }

    static void gating() {
        Rig r = new Rig();
        r.hide();
        check(r.metadataCalls.isEmpty() && r.realtime.status() == TasksCloudRealtime.Status.OFF, "hidden/background board: nothing");
        r.realtime.update(new TasksCloudRealtime.Conditions(true, new TasksCloudRemote.Identity(42, null, 7), false));
        check(r.metadataCalls.isEmpty(), "no linked principal: nothing");
        r.realtime.update(new TasksCloudRealtime.Conditions(true, null, false));
        check(r.metadataCalls.isEmpty(), "no identity for this user: nothing");
        r.realtime.update(new TasksCloudRealtime.Conditions(true, r.identity, true));
        check(r.metadataCalls.isEmpty(), "account pause: nothing");
        r.show();
        check(r.metadataCalls.size() == 1 && r.metadataCalls.get(0) == r.identity && r.realtime.status() == TasksCloudRealtime.Status.CONNECTING,
                "visible + foreground + linked + no pause: one metadata GET for the current identity");
        r.show();
        check(r.metadataCalls.size() == 1, "a repeated update does not start a second attempt");

        Rig gated = new Rig();
        gated.gate = 5_000;
        gated.show();
        check(gated.metadataCalls.isEmpty() && gated.realtime.status() == TasksCloudRealtime.Status.FALLBACK && gated.retryDelay() == 5_000,
                "a closed shared Retry-After gate: no metadata GET until it opens");
        gated.gate = 0;
        gated.clock.advance(5_000);
        check(gated.metadataCalls.size() == 1, "metadata asked once the gate opened");
    }

    static void liveAndCatchUp() {
        Rig r = new Rig();
        r.show();
        r.answerMetadata(ENABLED);
        FakeConnection c = r.connection();
        check(c.started && r.realtime.status() == TasksCloudRealtime.Status.CONNECTING, "enabled metadata opens one connection");
        c.authorize.authorize("11.22", new TasksCloudRemote.Reply<TasksRealtimeWire.Subscription>() {
            @Override public void success(TasksRealtimeWire.Subscription value) { }

            @Override public void failure(TasksCloudRemote.Failure failure) { }
        });
        check(r.authCalls.size() == 1 && "11.22".equals(r.authCalls.get(0)) && r.authIdentities.get(0) == r.identity,
                "the socket's authorization is a signed call for the attempt's identity and exact socket id");
        check(r.pulls == 0, "no pull before subscribed");
        c.listener.subscribed();
        check(r.realtime.status() == TasksCloudRealtime.Status.LIVE && r.pulls == 1, "subscribed: LIVE and one catch-up pull for missed hints");

        Rig gatedAuth = new Rig();
        gatedAuth.show();
        gatedAuth.answerMetadata(ENABLED);
        gatedAuth.gate = 8_000;
        gatedAuth.connection().authorize.authorize("1.1", null);
        check(gatedAuth.authCalls.isEmpty() && gatedAuth.connection().stopped && gatedAuth.realtime.status() == TasksCloudRealtime.Status.FALLBACK,
                "the auth POST waits for a closed reads gate (socket closed, retried later)");
    }

    static void coalescing() {
        Rig r = new Rig();
        FakeConnection c = r.live();
        int base = r.pulls;
        c.listener.invalidated();
        c.listener.invalidated();
        c.listener.invalidated();
        r.clock.advance(249);
        check(r.pulls == base, "no pull inside the 250 ms window");
        r.clock.advance(1);
        check(r.pulls == base + 1, "a burst of hints coalesces into exactly one pull after 250 ms");
        c.listener.invalidated();
        r.clock.advance(250);
        check(r.pulls == base + 2, "a later hint opens a new window");
        c.listener.invalidated();
        r.hide();
        r.clock.advance(1_000);
        check(r.pulls == base + 2, "a pending coalesced pull is cancelled when the board hides");
    }

    static void killSwitch() {
        Rig r = new Rig();
        r.show();
        r.answerMetadata(DISABLED);
        check(r.connections.isEmpty() && r.realtime.status() == TasksCloudRealtime.Status.DISABLED, "enabled:false: no socket, DISABLED (REST poll only)");
        r.clock.advance(TasksCloudRealtime.DISABLED_RECHECK_MS - 1);
        check(r.metadataCalls.size() == 1, "no metadata re-read before 300 s");
        r.clock.advance(1);
        check(r.metadataCalls.size() == 2, "the kill switch is re-read after 300 s");
        r.answerMetadata(ENABLED);
        check(r.connections.size() == 1, "re-enabled: connects");
    }

    static void backoffBounds() {
        Rig r = new Rig();
        r.jitter = 0.999999;
        r.show();
        long[] expected = {1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000};
        boolean exact = true, bounded = true;
        for (long base : expected) {
            r.failMetadata(TasksCloudRemote.Failure.Kind.UNAVAILABLE, "tasks_unavailable", 0);
            long delay = r.retryDelay();
            exact &= Math.abs(delay - base) <= 1;
            bounded &= delay >= TasksCloudRealtime.MIN_RECONNECT_MS && delay <= TasksCloudRealtime.MAX_RECONNECT_MS;
            check(r.realtime.status() == TasksCloudRealtime.Status.FALLBACK, "outage is FALLBACK, never live");
            r.clock.advance(delay);
        }
        check(exact, "exponential 1, 2, 4 … 60 s (capped) at the top of the jitter range");
        check(bounded && r.metadataCalls.size() == expected.length + 1, "every retry 1..60 s; one metadata GET per attempt (G5)");

        Rig low = new Rig();
        low.jitter = 0;
        low.show();
        low.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
        check(low.retryDelay() == 1_000, "jitter never goes under 1 s");
        low.clock.advance(1_000);
        for (int i = 0; i < 10; i++) {
            low.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
            long delay = low.retryDelay();
            check(delay >= 1_000 && delay <= 60_000, "bounded jittered delay " + delay);
            low.clock.advance(delay);
        }
        check(TasksCloudRealtime.backoffBaseMs(1) == 1_000 && TasksCloudRealtime.backoffBaseMs(7) == 60_000
                && TasksCloudRealtime.backoffBaseMs(100) == 60_000, "backoff base 1 s .. 60 s, no overflow");

        Rig unavailable = new Rig();
        unavailable.show();
        unavailable.failMetadata(TasksCloudRemote.Failure.Kind.UNAVAILABLE, "tasks_unavailable", 90);
        check(unavailable.retryDelay() == 90_000, "a 503 Retry-After lengthens the reconnect delay");
    }

    static void retryAfterOutranksCap() {
        Rig r = new Rig();
        r.show();
        r.gate = 120_000; // the engine's shared gate after the refusal below
        r.failMetadata(TasksCloudRemote.Failure.Kind.RATE_LIMITED, "rate_limited", 120);
        check(r.refusals.size() == 1 && r.refusals.get(0).kind == TasksCloudRemote.Failure.Kind.RATE_LIMITED, "a realtime 429 goes to the shared reads gate");
        check(r.retryDelay() == 120_000, "Retry-After (120 s) outranks the 60 s reconnect cap");
        r.clock.advance(119_999);
        check(r.metadataCalls.size() == 1, "nothing before the Retry-After deadline");
    }

    static void hideAndIdentityTeardown() {
        Rig r = new Rig();
        FakeConnection c = r.live();
        r.hide();
        check(c.stopped && r.realtime.status() == TasksCloudRealtime.Status.OFF && r.clock.pending().isEmpty(),
                "board hidden / app backgrounded: socket closed, every timer cancelled");
        int pulls = r.pulls;
        c.listener.invalidated();
        c.listener.subscribed();
        r.clock.advance(1_000);
        check(r.pulls == pulls && r.realtime.status() == TasksCloudRealtime.Status.OFF, "late events of the closed attempt do nothing");
        r.show();
        check(r.metadataCalls.size() == 2, "board shown again: a fresh attempt (new metadata GET)");

        Rig late = new Rig();
        late.show();
        late.hide();
        late.answerMetadata(ENABLED);
        check(late.connections.isEmpty(), "a metadata answer after hide never opens a socket");

        Rig rebind = new Rig();
        FakeConnection old = rebind.live();
        rebind.identity = new TasksCloudRemote.Identity(42, "principal-a", 8); // same principal, new generation (re-bind)
        rebind.show();
        check(old.stopped && rebind.metadataCalls.size() == 2 && rebind.metadataCalls.get(1).generation == 8,
                "a new Pro generation (re-bind) tears the socket down and reconnects with fresh authorization");

        Rig moved = new Rig();
        FakeConnection first = moved.live();
        int before = moved.pulls;
        moved.identity = new TasksCloudRemote.Identity(42, "principal-b", 9); // moved under the attempt, no update yet
        first.listener.invalidated();
        moved.clock.advance(1_000);
        check(first.stopped && moved.pulls == before && moved.realtime.status() == TasksCloudRealtime.Status.OFF,
                "an identity change seen in a callback drops the attempt: never acts for another account");

        Rig inFlight = new Rig();
        inFlight.show();
        inFlight.identity = new TasksCloudRemote.Identity(43, "principal-c", 1); // account switch while metadata is in flight
        inFlight.answerMetadata(ENABLED);
        check(inFlight.connections.isEmpty() && inFlight.realtime.status() == TasksCloudRealtime.Status.OFF,
                "metadata that outlived its identity is dropped");

        Rig authLate = new Rig();
        authLate.show();
        authLate.answerMetadata(ENABLED);
        final boolean[] delivered = {false};
        authLate.connection().authorize.authorize("5.5", new TasksCloudRemote.Reply<TasksRealtimeWire.Subscription>() {
            @Override public void success(TasksRealtimeWire.Subscription value) { delivered[0] = true; }

            @Override public void failure(TasksCloudRemote.Failure failure) { delivered[0] = true; }
        });
        authLate.identity = new TasksCloudRemote.Identity(42, null, 8); // logout / unlink
        authLate.authReplies.get(0).failure(new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.STALE, "session_changed", null, 0, 0));
        check(!delivered[0] && authLate.connection().stopped, "an authorization answer after logout never reaches the socket");

        Rig stopped = new Rig();
        FakeConnection s = stopped.live();
        stopped.realtime.stop();
        check(s.stopped && stopped.realtime.status() == TasksCloudRealtime.Status.OFF && stopped.clock.pending().isEmpty(), "stop (logout fence) closes everything");
    }

    static void refusalsAndRest() {
        Rig unlinked = new Rig();
        FakeConnection c = unlinked.live();
        TasksCloudRemote.Failure failure = new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 403, 0);
        c.listener.closed(close(TasksRealtimeSocket.Close.Reason.AUTHORIZATION, null, failure));
        check(unlinked.refusals.size() == 1 && unlinked.refusals.get(0) == failure && unlinked.realtime.status() == TasksCloudRealtime.Status.OFF
                && unlinked.clock.pending().isEmpty(), "403 on auth: the engine records its durable pause, realtime off, no retry");

        Rig session = new Rig();
        session.show();
        session.failMetadata(TasksCloudRemote.Failure.Kind.SESSION, "invalid_token", 0);
        check(session.refusals.size() == 1 && session.clock.pending().isEmpty(), "401 on metadata: session pause, no retry");

        Rig violation = new Rig();
        FakeConnection v = violation.live();
        v.listener.closed(close(TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION, null, null));
        check(violation.realtime.status() == TasksCloudRealtime.Status.FALLBACK && violation.clock.pending().isEmpty(),
                "a protocol violation rests (REST poll only), no retry storm");
        violation.show();
        check(violation.metadataCalls.size() == 1, "resting survives repeated updates while visible");
        violation.hide();
        violation.show();
        check(violation.metadataCalls.size() == 2, "the next board start tries again");

        Rig rebound = new Rig();
        rebound.live().listener.closed(close(TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION, null, null));
        check(rebound.clock.pending().isEmpty() && rebound.metadataCalls.size() == 1, "rested after a violation");
        rebound.identity = new TasksCloudRemote.Identity(42, "principal-a", 8); // re-bind while the board stays visible
        rebound.show();
        check(rebound.metadataCalls.size() == 2 && rebound.metadataCalls.get(1).generation == 8
                && rebound.realtime.status() == TasksCloudRealtime.Status.CONNECTING,
                "an identity change clears the old binding's rest: one fresh metadata GET for the new generation");

        Rig backedOff = new Rig();
        backedOff.show();
        for (int i = 0; i < 5; i++) {
            backedOff.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
            backedOff.clock.advance(backedOff.retryDelay());
        }
        backedOff.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
        check(backedOff.retryDelay() >= 16_000, "six failures: a long backoff");
        backedOff.identity = new TasksCloudRemote.Identity(43, "principal-b", 1);
        backedOff.show();
        int calls = backedOff.metadataCalls.size();
        backedOff.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
        check(backedOff.metadataCalls.get(calls - 1).telegramUserId == 43 && backedOff.retryDelay() <= 1_000,
                "the new account starts immediately with a fresh backoff budget");

        Rig malformed = new Rig();
        malformed.show();
        malformed.failMetadata(TasksCloudRemote.Failure.Kind.PROTOCOL, "invalid_response", 0);
        check(malformed.realtime.status() == TasksCloudRealtime.Status.FALLBACK && malformed.clock.pending().isEmpty(),
                "a malformed metadata answer (e.g. ws_url off the origin) rests");

        Rig noReconnect = new Rig();
        noReconnect.live().listener.closed(close(TasksRealtimeSocket.Close.Reason.SERVER, 4001, null));
        check(noReconnect.clock.pending().isEmpty() && noReconnect.realtime.status() == TasksCloudRealtime.Status.FALLBACK, "close 4000-4099: do not reconnect");

        Rig quick = new Rig();
        quick.live().listener.closed(close(TasksRealtimeSocket.Close.Reason.SERVER, 4201, null));
        long delay = quick.retryDelay();
        check(delay >= 1_000 && delay <= 1_001, "close 4200-4299: reconnect after ~1 s");
        quick.clock.advance(delay);
        check(quick.metadataCalls.size() == 2, "reconnect asks the metadata again (G5)");

        Rig lost = new Rig();
        lost.live().listener.closed(close(TasksRealtimeSocket.Close.Reason.TRANSPORT, null, null));
        check(lost.retryDelay() >= 1_000 && lost.realtime.status() == TasksCloudRealtime.Status.FALLBACK, "transport loss: backoff, FALLBACK");
        lost.clock.advance(lost.retryDelay());
        lost.answerMetadata(ENABLED);
        int pulls = lost.pulls;
        lost.connection().listener.subscribed();
        check(lost.pulls == pulls + 1 && lost.realtime.status() == TasksCloudRealtime.Status.LIVE, "reconnected: catch-up pull again");
    }

    static void stableConnectionForgivesBackoff() {
        Rig r = new Rig();
        r.show();
        r.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
        r.clock.advance(r.retryDelay());
        r.failMetadata(TasksCloudRemote.Failure.Kind.OFFLINE, "connection_failed", 0);
        r.clock.advance(r.retryDelay());
        r.answerMetadata(ENABLED);
        r.connection().listener.subscribed();
        r.connection().listener.closed(close(TasksRealtimeSocket.Close.Reason.TRANSPORT, null, null));
        check(r.retryDelay() >= 3_999, "a connection that dropped before being stable keeps the growing backoff (3rd failure ≈ 4 s)");
        r.clock.advance(r.retryDelay());
        r.answerMetadata(ENABLED);
        r.connection().listener.subscribed();
        r.clock.advance(30_000);
        r.connection().listener.closed(close(TasksRealtimeSocket.Close.Reason.TRANSPORT, null, null));
        check(r.retryDelay() <= 1_000, "stable for one activity_timeout: backoff forgiven");
    }
}
