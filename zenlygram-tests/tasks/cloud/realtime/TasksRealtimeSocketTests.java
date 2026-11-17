package org.telegram.messenger.zenlygram.tasks.cloud;

import java.util.ArrayList;
import java.util.List;

/**
 * Host tests of the real TasksRealtimeSocket (Pusher 7 connection state machine) over a scripted WebSocket seam and a
 * manual clock: exact handshake order, fresh authorization per socket id, no hint before the exact ack, heartbeat
 * (pusher:ping / pong, dead deadline ≤ 2 × activity_timeout), connect timeout, close codes, inert after stop.
 */
public final class TasksRealtimeSocketTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    static final String KEY = "appkey";
    static final String CHANNEL = "private-tasks." + TasksRealtimeWireTests.repeat('B', 43);
    static final String SIG = TasksRealtimeWireTests.repeat('f', 64);

    static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    static final class FakeTransport implements TasksRealtimeSocket.Transport {
        TasksRealtimeWire.Endpoint endpoint;
        TasksRealtimeSocket.TransportEvents events;
        final List<String> sent = new ArrayList<>();
        int opens, closes;

        @Override public void open(TasksRealtimeWire.Endpoint endpoint, TasksRealtimeSocket.TransportEvents events) {
            opens++;
            this.endpoint = endpoint;
            this.events = events;
        }

        @Override public void send(String text) { sent.add(text); }

        @Override public void close() { closes++; }

        String last() { return sent.isEmpty() ? null : sent.get(sent.size() - 1); }
    }

    static final class Authorizations implements TasksRealtimeSocket.Authorize {
        final List<String> socketIds = new ArrayList<>();
        final List<TasksCloudRemote.Reply<TasksRealtimeWire.Subscription>> replies = new ArrayList<>();

        @Override public void authorize(String socketId, TasksCloudRemote.Reply<TasksRealtimeWire.Subscription> reply) {
            socketIds.add(socketId);
            replies.add(reply);
        }
    }

    static final class Events implements TasksRealtimeSocket.Listener {
        int subscribed, invalidated;
        final List<TasksRealtimeSocket.Close> closed = new ArrayList<>();

        @Override public void subscribed() { subscribed++; }

        @Override public void invalidated() { invalidated++; }

        @Override public void closed(TasksRealtimeSocket.Close close) { closed.add(close); }
    }

    static final class Rig {
        final ManualScheduler clock = new ManualScheduler();
        final FakeTransport transport = new FakeTransport();
        final Authorizations auth = new Authorizations();
        final Events events = new Events();
        final TasksRealtimeSocket socket;

        Rig(int metadataTimeout) {
            TasksRealtimeWire.Metadata metadata = TasksRealtimeWire.decodeMetadata("{\"schema_version\":1,\"enabled\":true,\"ws_url\":\"wss://h.example/realtime/app/"
                    + KEY + "\",\"activity_timeout\":" + metadataTimeout + ",\"server_time\":\"2026-10-29T10:00:00Z\"}", "https://h.example/v1");
            socket = new TasksRealtimeSocket(metadata, clock, transport, auth, events);
        }

        void established(String socketId, int timeout) {
            transport.events.text("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"" + socketId
                    + "\\\",\\\"activity_timeout\\\":" + timeout + "}\"}");
        }

        void grant(int index, String socketId, String key) {
            auth.replies.get(index).success(TasksRealtimeWire.decodeSubscription("{\"schema_version\":1,\"channel\":\"" + CHANNEL + "\",\"auth\":\""
                    + key + ":" + SIG + "\",\"server_time\":\"2026-10-29T10:00:00Z\"}", socketId));
        }

        void ack(String channel) {
            transport.events.text("{\"event\":\"pusher_internal:subscription_succeeded\",\"channel\":\"" + channel + "\",\"data\":\"{}\"}");
        }

        void hint() {
            transport.events.text("{\"event\":\"tasks.changed\",\"channel\":\"" + CHANNEL + "\",\"data\":\"{\\\"v\\\":1}\"}");
        }

        /** open → established → authorized → subscribed. */
        Rig live() {
            socket.start();
            transport.events.opened();
            established("11.22", 30);
            grant(0, "11.22", KEY);
            ack(CHANNEL);
            return this;
        }

        TasksRealtimeSocket.Close.Reason closedReason() {
            return events.closed.size() == 1 ? events.closed.get(0).reason : null;
        }
    }

    public static void main(String[] args) {
        handshakeOrder();
        noHintBeforeExactAck();
        authorizationGuards();
        heartbeat();
        timeoutsAndCloses();
        inertAfterStop();
        if (!failures.isEmpty()) {
            System.out.println("Tasks realtime socket: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks realtime socket (Pusher 7 state machine): " + checks + " checks passed");
    }

    static void handshakeOrder() {
        Rig r = new Rig(30);
        r.socket.start();
        check(r.transport.opens == 1 && r.transport.endpoint.pathAndQuery.startsWith("/realtime/app/" + KEY + "?protocol=7&client=zenlygram-android"),
                "start opens the rebuilt endpoint once");
        r.socket.start();
        check(r.transport.opens == 1, "start is idempotent");
        r.transport.events.opened();
        check(r.auth.socketIds.isEmpty() && r.transport.sent.isEmpty(), "nothing sent before connection_established");
        r.established("11.22", 30);
        check(r.auth.socketIds.size() == 1 && "11.22".equals(r.auth.socketIds.get(0)), "connection_established → one fresh authorization of exactly that socket id");
        check(r.transport.sent.isEmpty(), "no subscribe before the authorization answer");
        r.grant(0, "11.22", KEY);
        check(r.transport.sent.size() == 1 && r.transport.last().equals("{\"event\":\"pusher:subscribe\",\"data\":{\"auth\":\"" + KEY + ":" + SIG
                + "\",\"channel\":\"" + CHANNEL + "\"}}"), "subscribe with exactly the returned auth/channel");
        check(r.events.subscribed == 0 && !r.socket.isSubscribed(), "not subscribed before the ack");
        r.ack(CHANNEL);
        check(r.events.subscribed == 1 && r.socket.isSubscribed(), "subscribed only after subscription_succeeded on the exact channel");
        r.hint();
        r.hint();
        check(r.events.invalidated == 2, "every tasks.changed on the channel is reported (the coordinator coalesces)");
        r.transport.events.text("{\"event\":\"tasks.changed\",\"channel\":\"" + CHANNEL + "\",\"data\":{\"v\":1}}");
        check(r.events.invalidated == 3, "object data form accepted too");
        r.transport.events.text("{\"event\":\"tasks.changed\",\"channel\":\"private-tasks." + TasksRealtimeWireTests.repeat('C', 43) + "\",\"data\":\"{\\\"v\\\":1}\"}");
        check(r.events.invalidated == 3 && r.events.closed.isEmpty(), "a hint on another channel is ignored");
        r.transport.events.text("{\"event\":\"tasks.changed\",\"channel\":\"" + CHANNEL + "\",\"data\":\"{\\\"v\\\":2}\"}");
        check(r.events.invalidated == 3 && r.events.closed.isEmpty(), "an unknown hint version is ignored, not fatal");
        r.transport.events.text("{\"event\":\"pusher:ping\",\"data\":{}}");
        check(TasksRealtimeWire.PONG_FRAME.equals(r.transport.last()), "pusher:ping answered with an application pusher:pong");
    }

    static void noHintBeforeExactAck() {
        Rig r = new Rig(30);
        r.socket.start();
        r.transport.events.opened();
        r.established("1.1", 30);
        r.hint();
        check(r.events.invalidated == 0, "no hint while authorizing");
        r.grant(0, "1.1", KEY);
        r.hint();
        check(r.events.invalidated == 0, "no hint while subscribing");
        r.ack("private-tasks." + TasksRealtimeWireTests.repeat('Z', 43));
        check(r.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION && r.events.subscribed == 0 && r.transport.closes == 1,
                "an ack for another channel is a violation, never live");

        Rig refused = new Rig(30);
        refused.socket.start();
        refused.established("1.1", 30);
        refused.grant(0, "1.1", KEY);
        refused.transport.events.text("{\"event\":\"pusher:subscription_error\",\"channel\":\"" + CHANNEL + "\",\"data\":{\"type\":\"AuthError\"}}");
        check(refused.closedReason() == TasksRealtimeSocket.Close.Reason.SUBSCRIPTION_REFUSED, "subscription_error closes as SUBSCRIPTION_REFUSED");
    }

    static void authorizationGuards() {
        Rig wrongKey = new Rig(30);
        wrongKey.socket.start();
        wrongKey.established("1.1", 30);
        wrongKey.grant(0, "1.1", "otherkey");
        check(wrongKey.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION && wrongKey.transport.sent.isEmpty(),
                "an authorization signed for another app key never subscribes");

        Rig wrongSocket = new Rig(30);
        wrongSocket.socket.start();
        wrongSocket.established("1.1", 30);
        wrongSocket.grant(0, "9.9", KEY);
        check(wrongSocket.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION && wrongSocket.transport.sent.isEmpty(),
                "a subscription signed for another socket id never subscribes");

        Rig refusedAuth = new Rig(30);
        refusedAuth.socket.start();
        refusedAuth.established("1.1", 30);
        TasksCloudRemote.Failure unlinked = new TasksCloudRemote.Failure(TasksCloudRemote.Failure.Kind.UNLINKED, "tasks_account_not_linked", null, 403, 0);
        refusedAuth.auth.replies.get(0).failure(unlinked);
        check(refusedAuth.closedReason() == TasksRealtimeSocket.Close.Reason.AUTHORIZATION && refusedAuth.events.closed.get(0).failure == unlinked,
                "a refused authorization closes with its typed failure");

        Rig twice = new Rig(30).live();
        twice.established("2.2", 30);
        check(twice.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION && twice.auth.socketIds.size() == 1,
                "a second connection_established is a violation (no re-authorization in place)");

        Rig binary = new Rig(30).live();
        binary.transport.events.binary();
        check(binary.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION, "a binary message is a violation");

        Rig garbage = new Rig(30).live();
        garbage.transport.events.text("{\"event\":");
        check(garbage.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION, "malformed JSON is a violation");
    }

    static void heartbeat() {
        Rig r = new Rig(30).live();
        int sent = r.transport.sent.size();
        r.clock.advance(29_999);
        check(r.transport.sent.size() == sent, "no ping before activity_timeout of silence");
        r.clock.advance(1);
        check(TasksRealtimeWire.PING_FRAME.equals(r.transport.last()) && r.transport.sent.size() == sent + 1, "one application pusher:ping after activity_timeout");
        r.transport.events.text("{\"event\":\"pusher:pong\"}");
        r.clock.advance(29_999);
        check(r.events.closed.isEmpty(), "a pong cancels the dead deadline");
        r.clock.advance(1);
        check(TasksRealtimeWire.PING_FRAME.equals(r.transport.last()), "next ping after another quiet activity_timeout");
        r.clock.advance(29_999);
        check(r.events.closed.isEmpty(), "still alive just before 2 × activity_timeout");
        r.clock.advance(1);
        check(r.closedReason() == TasksRealtimeSocket.Close.Reason.HEARTBEAT_TIMEOUT && r.transport.closes == 1,
                "an unanswered ping closes at exactly 2 × activity_timeout");
        check(r.clock.pending().isEmpty(), "no timer outlives the close");

        Rig governed = new Rig(120);
        governed.socket.start();
        governed.established("1.1", 10); // the frame's own validated timeout governs this connection (G3)
        int before = governed.transport.sent.size();
        governed.clock.advance(10_000);
        check(governed.transport.sent.size() == before + 1 && TasksRealtimeWire.PING_FRAME.equals(governed.transport.last()),
                "the established frame's activity_timeout (10 s) overrides the metadata's (120 s)");

        Rig anyFrame = new Rig(30).live();
        anyFrame.clock.advance(20_000);
        anyFrame.transport.events.text("{\"event\":\"something.else\"}");
        anyFrame.clock.advance(20_000);
        check(!TasksRealtimeWire.PING_FRAME.equals(anyFrame.transport.last()), "any frame proves the connection and restarts the idle timer");

        Rig badTimeout = new Rig(30);
        badTimeout.socket.start();
        badTimeout.transport.events.text("{\"event\":\"pusher:connection_established\",\"data\":\"{\\\"socket_id\\\":\\\"1.1\\\",\\\"activity_timeout\\\":5}\"}");
        check(badTimeout.closedReason() == TasksRealtimeSocket.Close.Reason.PROTOCOL_VIOLATION && badTimeout.auth.socketIds.isEmpty(),
                "an out-of-range established timeout fails the attempt (no clamping)");
    }

    static void timeoutsAndCloses() {
        Rig silent = new Rig(30);
        silent.socket.start();
        silent.transport.events.opened();
        silent.clock.advance(TasksRealtimeSocket.CONNECT_TIMEOUT_MS - 1);
        check(silent.events.closed.isEmpty(), "connect deadline not yet");
        silent.clock.advance(1);
        check(silent.closedReason() == TasksRealtimeSocket.Close.Reason.TRANSPORT, "no connection_established within 10 s → TRANSPORT");

        Rig established = new Rig(30);
        established.socket.start();
        established.established("1.1", 30);
        established.clock.advance(TasksRealtimeSocket.CONNECT_TIMEOUT_MS);
        check(established.events.closed.isEmpty(), "the connect timer is cancelled once established");

        Rig server = new Rig(30).live();
        server.transport.events.closed(4001);
        check(server.closedReason() == TasksRealtimeSocket.Close.Reason.SERVER && server.events.closed.get(0).code == 4001, "close 4001 reported with its code");
        Rig abnormal = new Rig(30).live();
        abnormal.transport.events.closed(1006);
        check(abnormal.closedReason() == TasksRealtimeSocket.Close.Reason.TRANSPORT, "close 1006 is a transport loss");
        Rig error = new Rig(30).live();
        error.transport.events.text("{\"event\":\"pusher:error\",\"data\":{\"message\":\"Over capacity\",\"code\":4100}}");
        check(error.closedReason() == TasksRealtimeSocket.Close.Reason.SERVER && error.events.closed.get(0).code == 4100, "pusher:error closes with its code");
        Rig lost = new Rig(30).live();
        lost.transport.events.failed();
        lost.transport.events.failed();
        check(lost.events.closed.size() == 1 && lost.transport.closes == 1, "exactly one terminal close");
    }

    static void inertAfterStop() {
        Rig r = new Rig(30);
        r.socket.start();
        r.established("1.1", 30);
        r.socket.stop();
        check(r.transport.closes == 1 && r.events.closed.isEmpty(), "stop closes silently");
        r.grant(0, "1.1", KEY);
        r.ack(CHANNEL);
        r.hint();
        r.transport.events.text("{\"event\":\"pusher:ping\"}");
        r.clock.advance(300_000);
        check(r.transport.sent.isEmpty() && r.events.subscribed == 0 && r.events.invalidated == 0 && r.events.closed.isEmpty()
                && r.clock.pending().isEmpty(), "after stop: a late authorization, ack, hint, ping or timer does nothing");
        r.socket.stop();
        check(r.transport.closes == 1, "stop is idempotent");
        Rig never = new Rig(30);
        never.socket.stop();
        check(never.transport.closes == 0, "stopping a never-started socket closes nothing");
    }
}
