package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Production WebSocket of the Tasks realtime channel: one TLS connection per instance through the platform default
 * {@link SSLSocketFactory} (so the app's network security config and system trust apply; no custom trust manager),
 * SNI for host names, TLS endpoint identification where the platform has it (API 24+) and, on every API level, an
 * explicit check with the platform default HostnameVerifier after the handshake and before any byte is written. The
 * upgrade request carries only Host, the WebSocket headers and the canonical Origin — never a cookie, credential or
 * token; a redirect or any non-101 answer fails. Frames: {@link TasksRealtimeFrames} (masked, bounded to
 * {@link TasksRealtimeWire#MAX_FRAME_BYTES}, fragmentation, ping→pong, close handshake).
 * <p>
 * One reader thread per connection; writes go through a per-connection single writer thread with a bounded queue, so
 * {@link #send} never blocks the caller and a peer that floods pings without reading cannot grow memory. Every event
 * is handed to {@code deliver} (the owner's serial executor); none after {@link #close}, which never blocks either.
 * Nothing thrown on the reader thread — not even a {@link LinkageError} of an API the device lacks — escapes it.
 */
final class TasksRealtimeWebSocket implements TasksRealtimeSocket.Transport {
    static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Above the heartbeat's own dead deadline (≤ 2 × 120 s): a quiet healthy socket is never dropped by it. */
    static final int READ_TIMEOUT_MS = (2 * TasksRealtimeWire.MAX_ACTIVITY_TIMEOUT + 30) * 1000;
    /** Queued writes per connection (Pusher frames are rare and tiny); more means the peer stopped reading. */
    static final int MAX_QUEUED_WRITES = 16;
    /** How long a locally closed connection waits for the peer's close frame before the TCP socket is dropped. */
    private static final long CLOSE_GRACE_MS = 2_000;
    private static ScheduledExecutorService reaper;

    /** Applies the TLS parameters to a freshly layered socket (seam for host tests of old-API behaviour). */
    interface TlsSetup { void apply(SSLSocket tls, String host); }

    static final TlsSetup PLATFORM_TLS = (tls, host) -> tls.setSSLParameters(parameters(tls.getSSLParameters(), host));

    private final Executor deliver;
    private final HostnameVerifier hostnameVerifier;
    private final TlsSetup tlsSetup;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean pongPending = new AtomicBoolean();
    private volatile TasksRealtimeSocket.TransportEvents events;
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile OutputStream out;
    private ThreadPoolExecutor writer;

    TasksRealtimeWebSocket(Executor deliver) {
        this(deliver, HttpsURLConnection.getDefaultHostnameVerifier(), PLATFORM_TLS);
    }

    /** Host tests only: the JDK's default HostnameVerifier rejects every host outside HttpsURLConnection. */
    TasksRealtimeWebSocket(Executor deliver, HostnameVerifier hostnameVerifier, TlsSetup tlsSetup) {
        this.deliver = deliver;
        this.hostnameVerifier = hostnameVerifier;
        this.tlsSetup = tlsSetup;
    }

    @Override public void open(final TasksRealtimeWire.Endpoint endpoint, TasksRealtimeSocket.TransportEvents events) {
        this.events = events;
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(MAX_QUEUED_WRITES),
                r -> daemon(r, "ZenlyTasksRealtimeWrite"));
        daemon(() -> run(endpoint), "ZenlyTasksRealtime").start();
    }

    @Override public void send(final String text) {
        if (closed) return;
        try {
            writer.execute(() -> {
                if (closed) return;
                try {
                    write(TasksRealtimeFrames.text(text, random));
                } catch (IOException lost) {
                    emit(TasksRealtimeSocket.TransportEvents::failed);
                }
            });
        } catch (RejectedExecutionException backlog) {
            // The peer stopped reading: a broken connection, never an unbounded queue.
            emit(TasksRealtimeSocket.TransportEvents::failed);
        }
    }

    /** Never blocks the caller (the repository's database thread): every socket close runs on the reaper. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        events = null;
        final Socket current = socket;
        if (current == null || out == null) {
            // Not upgraded yet: nothing to say to the peer; SSLSocket.close() may still write close_notify.
            if (current != null) reaper().execute(() -> closeQuietly(current));
        } else {
            try {
                writer.execute(() -> {
                    try {
                        write(TasksRealtimeFrames.control(TasksRealtimeFrames.OP_CLOSE,
                                TasksRealtimeFrames.closePayload(TasksRealtimeFrames.CLOSE_GOING_AWAY), random));
                    } catch (IOException ignored) {
                        // Already gone.
                    }
                });
            } catch (RejectedExecutionException backlog) {
                // No room for a goodbye: the reaper drops the connection.
            }
            reaper().schedule(() -> closeQuietly(current), CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
        }
        if (writer != null) writer.shutdown();
    }

    // ---- reader thread ----

    private void run(TasksRealtimeWire.Endpoint endpoint) {
        Socket raw = new Socket();
        socket = raw;
        try {
            if (closed) return;
            raw.connect(new InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(CONNECT_TIMEOUT_MS);
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, endpoint.host, endpoint.port, true);
            socket = tls;
            if (closed) return;
            tlsSetup.apply(tls, endpoint.host);
            tls.startHandshake();
            // Enforced on every API level (the only hostname check where endpoint identification is unavailable).
            if (!hostnameVerifier.verify(endpoint.host, tls.getSession())) {
                throw new IOException("hostname");
            }
            InputStream in = new BufferedInputStream(tls.getInputStream(), 16384);
            OutputStream output = tls.getOutputStream();
            String key = TasksRealtimeFrames.newKey(random);
            output.write(TasksRealtimeFrames.request(endpoint, key).getBytes(StandardCharsets.US_ASCII));
            output.flush();
            TasksRealtimeFrames.readResponse(in, key);
            tls.setSoTimeout(READ_TIMEOUT_MS);
            out = output;
            if (closed) return;
            emit(TasksRealtimeSocket.TransportEvents::opened);
            readLoop(in);
        } catch (TasksRealtimeFrames.ProtocolError violation) {
            if (out != null) sendClose(violation.closeCode);
            emit(TasksRealtimeSocket.TransportEvents::failed);
        } catch (IOException | RuntimeException lost) {
            emit(TasksRealtimeSocket.TransportEvents::failed);
        } catch (Error unexpected) {
            // E.g. a missing platform method on an old API level: fail this attempt, never kill the process.
            emit(TasksRealtimeSocket.TransportEvents::failed);
        } finally {
            closeQuietly(socket);
            closeQuietly(raw);
        }
    }

    private void readLoop(InputStream in) throws IOException {
        TasksRealtimeFrames.Assembler assembler = new TasksRealtimeFrames.Assembler(TasksRealtimeWire.MAX_FRAME_BYTES);
        while (true) {
            TasksRealtimeFrames.Message message = assembler.accept(TasksRealtimeFrames.read(in, TasksRealtimeWire.MAX_FRAME_BYTES));
            if (message == null) continue;
            switch (message.opcode) {
                case TasksRealtimeFrames.OP_TEXT:
                    final String text = message.text;
                    emit(e -> e.text(text));
                    break;
                case TasksRealtimeFrames.OP_BINARY:
                    emit(TasksRealtimeSocket.TransportEvents::binary);
                    break;
                case TasksRealtimeFrames.OP_PING:
                    pong(message.payload);
                    break;
                case TasksRealtimeFrames.OP_CLOSE: {
                    // Close handshake: echo the peer's (validated) code, or none, then drop the TCP connection.
                    if (!closed) sendClose(message.closeCode);
                    final int code = message.closeCode;
                    emit(e -> e.closed(code));
                    return;
                }
                default:
                    break; // PONG to an RFC ping we never send.
            }
        }
    }

    /** At most one RFC pong queued: a flood of pings collapses into the one answer still pending (§5.5.3). */
    private void pong(final byte[] payload) {
        if (closed || !pongPending.compareAndSet(false, true)) return;
        try {
            writer.execute(() -> {
                pongPending.set(false);
                try {
                    write(TasksRealtimeFrames.control(TasksRealtimeFrames.OP_PONG, payload, random));
                } catch (IOException ignored) {
                    // The reader sees the loss.
                }
            });
        } catch (RejectedExecutionException backlog) {
            pongPending.set(false);
        }
    }

    // ---- helpers ----

    /**
     * Endpoint identification HTTPS and SNI. Both setters are API 24+: on API 23 they are missing methods/classes
     * ({@link LinkageError}), skipped here; the explicit HostnameVerifier check after the handshake still applies.
     */
    static SSLParameters parameters(SSLParameters parameters, String host) {
        try {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        } catch (RuntimeException | LinkageError unsupported) {
            // Pre-API 24: hostname is verified explicitly after the handshake.
        }
        if (!isIpLiteral(host)) {
            try {
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            } catch (RuntimeException | LinkageError older) {
                // Pre-API 24: the platform socket sends SNI from the createSocket host.
            }
        }
        return parameters;
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    private void sendClose(int code) {
        try {
            byte[] payload = code == TasksRealtimeFrames.NO_CODE ? new byte[0] : TasksRealtimeFrames.closePayload(code);
            write(TasksRealtimeFrames.control(TasksRealtimeFrames.OP_CLOSE, payload, random));
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    private void write(byte[] frame) throws IOException {
        OutputStream output = out;
        if (output == null) throw new IOException("not open");
        synchronized (this) {
            output.write(frame);
            output.flush();
        }
    }

    private interface Event { void deliver(TasksRealtimeSocket.TransportEvents events); }

    private void emit(final Event event) {
        final TasksRealtimeSocket.TransportEvents target = events;
        if (target == null || closed) return;
        deliver.execute(() -> {
            if (!closed) event.deliver(target);
        });
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static synchronized ScheduledExecutorService reaper() {
        if (reaper == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "ZenlyTasksRealtimeReaper"));
            executor.setRemoveOnCancelPolicy(true);
            reaper = executor;
        }
        return reaper;
    }
}
