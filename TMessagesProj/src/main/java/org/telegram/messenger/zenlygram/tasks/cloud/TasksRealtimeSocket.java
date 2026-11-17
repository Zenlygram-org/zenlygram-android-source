package org.telegram.messenger.zenlygram.tasks.cloud;

/**
 * One Pusher protocol 7 connection of the Tasks realtime channel (port of iOS {@code SGTasksRealtimeSocket}). It lives
 * exactly once: open → {@code pusher:connection_established} (socket id) → a FRESH signed REST authorization for that
 * socket id → {@code pusher:subscribe} with the exact returned channel/auth → {@code subscription_succeeded} on that
 * channel (only now "subscribed") → content-free {@code tasks.changed} hints. Any failure ends it with one terminal
 * {@code closed}; the owner decides about reconnecting (a new instance, a new socket id, a new authorization).
 * <p>
 * Confined to the owner's serial executor: transport events, timers and authorization answers must all be delivered
 * there. Once {@link #stop()} or the terminal close ran, every later input is ignored. A server rebind is fenced by the
 * owner (Pro generation), so there is no in-place revalidation here. Nothing logs or persists.
 */
final class TasksRealtimeSocket implements TasksCloudRealtime.Connection {
    interface Cancellable { void cancel(); }

    /** Runs {@code action} on the owner's executor after {@code delayMs}. */
    interface Scheduler { Cancellable schedule(Runnable action, long delayMs); }

    /** The raw WebSocket seam (production: {@link TasksRealtimeWebSocket}); events arrive on the owner's executor. */
    interface Transport {
        void open(TasksRealtimeWire.Endpoint endpoint, TransportEvents events);

        void send(String text);

        /** Idempotent; no event is delivered after it returns. */
        void close();
    }

    interface TransportEvents {
        void opened();

        void text(String text);

        void binary();

        /** The peer closed; {@code code} is the WebSocket close code or {@link TasksRealtimeFrames#NO_CODE}. */
        void closed(int code);

        void failed();
    }

    interface Authorize { void authorize(String socketId, TasksCloudRemote.Reply<TasksRealtimeWire.Subscription> reply); }

    interface Listener {
        /** {@code subscription_succeeded} on the exact channel. */
        void subscribed();

        /** A content-free {@code tasks.changed} on the subscribed channel. */
        void invalidated();

        /** Terminal; never emitted for {@link #stop()}. */
        void closed(Close close);
    }

    static final class Close {
        enum Reason {
            /** Lost transport, refused handshake or an unanswered connect. */
            TRANSPORT,
            /** No frame within the dead-connection deadline (≤ 2 × activity timeout). */
            HEARTBEAT_TIMEOUT,
            /** A frame broke the protocol (size, shape, wrong channel/key, binary). */
            PROTOCOL_VIOLATION,
            /** The REST authorization refused; see {@link #failure}. */
            AUTHORIZATION,
            /** {@code pusher:subscription_error}. */
            SUBSCRIPTION_REFUSED,
            /** A Pusher close / error code (4000–4299 decide the reconnect policy); see {@link #code}. */
            SERVER
        }

        final Reason reason;
        final Integer code;
        final TasksCloudRemote.Failure failure;

        Close(Reason reason, Integer code, TasksCloudRemote.Failure failure) {
            this.reason = reason;
            this.code = code;
            this.failure = failure;
        }
    }

    /** open → first frame, bounded by this socket's own timer. */
    static final long CONNECT_TIMEOUT_MS = 10_000;

    private enum Phase { IDLE, OPENING, AWAITING_ESTABLISHED, AUTHORIZING, SUBSCRIBING, SUBSCRIBED, FINISHED }

    private final TasksRealtimeWire.Endpoint endpoint;
    private final Scheduler scheduler;
    private final Transport transport;
    private final Authorize authorize;
    private final Listener listener;

    private Phase phase = Phase.IDLE;
    private int activityTimeout;
    /** AUTHORIZING: the socket id being authorized; SUBSCRIBING/SUBSCRIBED: the exact subscription. */
    private String socketId;
    private TasksRealtimeWire.Subscription subscription;
    private Cancellable idleTimer, deadTimer, connectTimer;

    /** Only for enabled metadata (its endpoint and 10..120 s timeout were validated by the strict decoder). */
    TasksRealtimeSocket(TasksRealtimeWire.Metadata metadata, Scheduler scheduler, Transport transport, Authorize authorize, Listener listener) {
        if (metadata == null || !metadata.isEnabled()) throw new IllegalArgumentException("realtime disabled");
        this.endpoint = metadata.endpoint;
        this.activityTimeout = metadata.activityTimeout;
        this.scheduler = scheduler;
        this.transport = transport;
        this.authorize = authorize;
        this.listener = listener;
    }

    @Override public void start() {
        if (phase != Phase.IDLE) return;
        phase = Phase.OPENING;
        connectTimer = scheduler.schedule(() -> {
            if (phase == Phase.OPENING || phase == Phase.AWAITING_ESTABLISHED) fail(new Close(Close.Reason.TRANSPORT, null, null));
        }, CONNECT_TIMEOUT_MS);
        transport.open(endpoint, new TransportEvents() {
            @Override public void opened() {
                if (phase == Phase.OPENING) phase = Phase.AWAITING_ESTABLISHED;
            }

            @Override public void text(String text) { receive(text); }

            @Override public void binary() { fail(new Close(Close.Reason.PROTOCOL_VIOLATION, null, null)); }

            @Override public void closed(int code) {
                fail(code >= 4000 && code <= 4299 ? new Close(Close.Reason.SERVER, code, null) : new Close(Close.Reason.TRANSPORT, null, null));
            }

            @Override public void failed() { fail(new Close(Close.Reason.TRANSPORT, null, null)); }
        });
    }

    /** Ends the connection silently: timers cancelled, socket closed, no event; later inputs are ignored. */
    @Override public void stop() {
        if (finish()) transport.close();
    }

    boolean isSubscribed() { return phase == Phase.SUBSCRIBED; }

    // ---- inputs ----

    private void receive(String text) {
        if (phase == Phase.FINISHED) return;
        TasksRealtimeWire.Frame frame;
        try {
            frame = TasksRealtimeWire.parseFrame(text);
        } catch (TasksRealtimeWire.FrameException violation) {
            fail(new Close(Close.Reason.PROTOCOL_VIOLATION, null, null));
            return;
        }
        noteActivity();
        switch (frame.kind) {
            case CONNECTION_ESTABLISHED:
                if (phase != Phase.OPENING && phase != Phase.AWAITING_ESTABLISHED) {
                    fail(new Close(Close.Reason.PROTOCOL_VIOLATION, null, null));
                    return;
                }
                cancel(connectTimer);
                connectTimer = null;
                // G3: the connection's own validated activity_timeout governs this connection.
                activityTimeout = frame.activityTimeout;
                phase = Phase.AUTHORIZING;
                socketId = frame.socketId;
                noteActivity();
                final String id = frame.socketId;
                authorize.authorize(id, new TasksCloudRemote.Reply<TasksRealtimeWire.Subscription>() {
                    @Override public void success(TasksRealtimeWire.Subscription value) { authorized(id, value, null); }

                    @Override public void failure(TasksCloudRemote.Failure failure) { authorized(id, null, failure); }
                });
                return;
            case PING:
                transport.send(TasksRealtimeWire.PONG_FRAME);
                return;
            case SUBSCRIPTION_SUCCEEDED:
                if (phase != Phase.SUBSCRIBING) return;
                if (!subscription.channel.equals(frame.channel)) {
                    fail(new Close(Close.Reason.PROTOCOL_VIOLATION, null, null));
                    return;
                }
                phase = Phase.SUBSCRIBED;
                listener.subscribed();
                return;
            case SUBSCRIPTION_ERROR:
                if (phase == Phase.SUBSCRIBING && (frame.channel == null || frame.channel.equals(subscription.channel))) {
                    fail(new Close(Close.Reason.SUBSCRIPTION_REFUSED, null, null));
                }
                return;
            case ERROR:
                fail(new Close(Close.Reason.SERVER, frame.code, null));
                return;
            case CHANGED:
                if (phase == Phase.SUBSCRIBED && subscription.channel.equals(frame.channel)) listener.invalidated();
                return;
            default:
                // PONG (activity already noted) and any other well-formed event: ignored, never logged.
        }
    }

    private void authorized(String id, TasksRealtimeWire.Subscription value, TasksCloudRemote.Failure failure) {
        // Stopped, failed, or an answer for another socket: never subscribe with it.
        if (phase != Phase.AUTHORIZING || !id.equals(socketId)) return;
        if (failure != null) {
            fail(new Close(Close.Reason.AUTHORIZATION, null, failure));
            return;
        }
        if (value == null || !id.equals(value.socketId) || !endpoint.appKey.equals(value.appKey)
                || !TasksRealtimeWire.isValidChannel(value.channel) || !TasksRealtimeWire.isValidAuth(value.auth, endpoint.appKey)) {
            fail(new Close(Close.Reason.PROTOCOL_VIOLATION, null, null));
            return;
        }
        subscription = value;
        phase = Phase.SUBSCRIBING;
        transport.send(TasksRealtimeWire.subscribeFrame(value));
    }

    // ---- heartbeat (G4) ----

    /** Any frame proves the connection: after activityTimeout of silence send pusher:ping, close if nothing within another (≤ 2×). */
    private void noteActivity() {
        cancel(idleTimer);
        cancel(deadTimer);
        idleTimer = null;
        deadTimer = null;
        if (!isEstablished()) return;
        idleTimer = scheduler.schedule(this::idleElapsed, activityTimeout * 1000L);
    }

    private boolean isEstablished() {
        return phase == Phase.AUTHORIZING || phase == Phase.SUBSCRIBING || phase == Phase.SUBSCRIBED;
    }

    private void idleElapsed() {
        if (!isEstablished()) return;
        idleTimer = null;
        cancel(deadTimer);
        deadTimer = scheduler.schedule(() -> {
            if (isEstablished()) fail(new Close(Close.Reason.HEARTBEAT_TIMEOUT, null, null));
        }, activityTimeout * 1000L);
        transport.send(TasksRealtimeWire.PING_FRAME);
    }

    // ---- termination ----

    /** Cancels every timer; true when this call ended an active connection. */
    private boolean finish() {
        if (phase == Phase.FINISHED) return false;
        boolean wasIdle = phase == Phase.IDLE;
        phase = Phase.FINISHED;
        cancel(idleTimer);
        cancel(deadTimer);
        cancel(connectTimer);
        idleTimer = deadTimer = connectTimer = null;
        subscription = null;
        socketId = null;
        return !wasIdle;
    }

    private void fail(Close close) {
        if (!finish()) return;
        transport.close();
        listener.closed(close);
    }

    private static void cancel(Cancellable cancellable) {
        if (cancellable != null) cancellable.cancel();
    }
}
