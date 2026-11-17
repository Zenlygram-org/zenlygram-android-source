package org.telegram.messenger.zenlygram.pro;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** No redirects, URL credentials, custom trust manager, or token logging. Bodies are bounded while read. */
final class ProTransport {
    private static final int MAX_BODY_BYTES = 131072;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    /** One lazily created daemon thread aborting overdue public requests; never one per request. */
    private static ScheduledExecutorService watchdog;

    static final class Response {
        final int status;
        final JSONObject body;
        final int retryAfter;
        /** Backend IdempotencyKey middleware replayed a stored answer: its body and server_time are not current. */
        final boolean replayed;

        Response(int status, JSONObject body, int retryAfter, boolean replayed) {
            this.status = status;
            this.body = body;
            this.retryAfter = Math.max(0, retryAfter);
            this.replayed = replayed;
        }
    }

    /** Raw answer of the public policy GET: the caller decodes and verifies the text itself. */
    static final class Text {
        final int status;
        final String body;
        final int retryAfter;

        Text(int status, String body, int retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = Math.max(0, retryAfter);
        }
    }

    /** Sends exactly {@code body} (JSON UTF-8), e.g. bytes a device proof already covers. */
    static Response requestBytes(String baseUrl, String path, String method, byte[] body, Map<String, String> headers) throws Exception {
        HttpsURLConnection connection = open(baseUrl, path, method, 15000, 20000);
        for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
        try {
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int status = connection.getResponseCode();
            JSONObject parsed = new JSONObject(read(connection, status, MAX_BODY_BYTES, Long.MAX_VALUE));
            int retryAfter = Math.max(parsed.optInt("retry_after", 0), retryAfterHeader(connection));
            return new Response(status, parsed, retryAfter, "true".equalsIgnoreCase(connection.getHeaderField("Idempotent-Replay")));
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Unauthenticated GET for public data: no Authorization or device headers, no cookies or cache,
     * no redirects (to any host), {@code accept} as the one Accept header (the claim shapes the caller
     * can read, e.g. ProMonetization.ACCEPT), and at most {@code maxBytes} read from the body whatever
     * Content-Length claims. {@code timeoutMs} is one total budget for connecting, the response and
     * the body: checked before every body read, and enforced by disconnecting a request still
     * running at the deadline (an OS-level DNS lookup may finish late; its result is then discarded).
     * A 429 returns its status and Retry-After without reading the error body (body null), so a
     * missing, oversized, failing or slow error body cannot hide the cooldown from the caller.
     */
    static Text getPublic(String baseUrl, String path, String accept, int maxBytes, int timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        HttpsURLConnection connection = open(baseUrl, path, "GET", timeoutMs, timeoutMs);
        connection.setRequestProperty("Accept", accept); // Replaces open()'s application/json: one Accept header.
        ScheduledFuture<?> abort = watchdog().schedule(connection::disconnect, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            int status = connection.getResponseCode();
            if (status == HTTP_TOO_MANY_REQUESTS) return new Text(status, null, retryAfterHeader(connection));
            return new Text(status, read(connection, status, maxBytes, deadline), retryAfterHeader(connection));
        } finally {
            abort.cancel(false);
            connection.disconnect();
        }
    }

    /** Tasks cloud routes: one total budget for connect, upload, status and body (R3 native_rotation: <=5 s). */
    static final int TASKS_TIMEOUT_MS = 5000;
    /**
     * The R4 batch call alone (x-endpoints[3] client_budget): the server's window starts only after the whole
     * body has arrived, so connect, upload, status and body share one 20 s budget instead.
     */
    static final int TASKS_BATCH_TIMEOUT_MS = 20000;
    /** No single connect or read of a Tasks attempt waits longer than this (the batch call's 10 s idle bound). */
    static final int TASKS_IDLE_TIMEOUT_MS = 10000;
    /** x-wire-negotiation: sent once on every Tasks request, echoed by an R4 server on every answer. */
    static final String TASKS_WIRE_HEADER = "X-Tasks-Wire";

    /** Raw Tasks answer: the bounded strict-UTF-8 body text exactly as received (null for 429), never a parsed tree. */
    static final class TasksReply {
        final int status;
        final String body;
        final int retryAfter;
        /** The answer's X-Tasks-Wire header as a number; null when absent or not a plain positive decimal. */
        final Integer serverWire;

        TasksReply(int status, String body, int retryAfter) {
            this(status, body, retryAfter, null);
        }

        TasksReply(int status, String body, int retryAfter, Integer serverWire) {
            this.status = status;
            this.body = body;
            this.retryAfter = Math.max(0, retryAfter);
            this.serverWire = serverWire;
        }
    }

    /**
     * One device-authenticated Tasks attempt (TasksApi): exactly the sealed {@code body} bytes
     * (Content-Type application/json) or no body, the caller's fresh signed headers, no redirects,
     * caches or URL credentials, at most {@code maxBytes} of strict UTF-8 body and one total
     * {@code timeoutMs} budget (TASKS_TIMEOUT_MS, or TASKS_BATCH_TIMEOUT_MS for the batch call) enforced
     * like getPublic (deadline checked before every body read, the watchdog disconnects an attempt still
     * running; no single connect or read waits beyond TASKS_IDLE_TIMEOUT_MS). The text goes to the strict
     * Tasks decoder untouched; the X-Tasks-Wire echo is read on every status. A 429 returns its status and
     * Retry-After without reading the body; an error status without any body returns its status with body
     * null (classified by status, never as offline). No retry here.
     */
    static TasksReply tasks(String baseUrl, String pathAndQuery, String method, byte[] body, Map<String, String> headers,
                            int maxBytes, int timeoutMs) throws Exception {
        if (timeoutMs <= 0) throw new IllegalArgumentException("Tasks timeout");
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        int idleMs = Math.min(timeoutMs, TASKS_IDLE_TIMEOUT_MS);
        HttpsURLConnection connection = open(baseUrl, pathAndQuery, method, idleMs, idleMs);
        ScheduledFuture<?> abort = watchdog().schedule(connection::disconnect, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int status = connection.getResponseCode();
            Integer wire = serverWireHeader(connection);
            if (status == HTTP_TOO_MANY_REQUESTS) return new TasksReply(status, null, retryAfterHeader(connection), wire);
            if (status >= 400) {
                InputStream error = connection.getErrorStream();
                if (error == null) return new TasksReply(status, null, retryAfterHeader(connection), wire);
                try (InputStream stream = error) {
                    return new TasksReply(status, ProMonetization.readBounded(stream, maxBytes, deadline, System::nanoTime),
                            retryAfterHeader(connection), wire);
                }
            }
            return new TasksReply(status, read(connection, status, maxBytes, deadline), retryAfterHeader(connection), wire);
        } finally {
            abort.cancel(false);
            connection.disconnect();
        }
    }

    private static Integer serverWireHeader(HttpsURLConnection connection) {
        String value = connection.getHeaderField(TASKS_WIRE_HEADER);
        return value != null && value.matches("[1-9][0-9]{0,8}") ? Integer.valueOf(value) : null;
    }

    /** Customer-data routes (CustomerApi): one total budget for connect, upload, status and body. */
    static final int CUSTOMER_TIMEOUT_MS = 30000;
    /** Largest customer answer read, as strict UTF-8 (128 KiB). */
    static final int CUSTOMER_MAX_BYTES = 131072;

    /** Raw customer answer: the bounded strict-UTF-8 body text exactly as received, never a parsed tree. */
    static final class CustomerReply {
        final int status;
        /** Null for a 429 (never read) and for an answer without any body. */
        final String body;
        final int retryAfter;
        /** Backend IdempotencyKey middleware replayed a stored answer (account/delete): its server_time is not current. */
        final boolean replayed;

        CustomerReply(int status, String body, int retryAfter, boolean replayed) {
            this.status = status;
            this.body = body;
            this.retryAfter = Math.max(0, retryAfter);
            this.replayed = replayed;
        }
    }

    /**
     * A customer attempt that ended without a usable answer; the cause is the original failure.
     * {@code connected}: the connection was established first (a timeout or reset while writing or
     * reading, or an unreadable answer after its status), so the server may have processed the
     * request. False for an invalid URL or a DNS, TCP or TLS failure: nothing reached the server.
     */
    static final class CustomerFailure extends java.io.IOException {
        private static final long serialVersionUID = 1L;
        final boolean connected;

        CustomerFailure(Exception cause, boolean connected) {
            super(cause);
            this.connected = connected;
        }

        /** An answer arrived but its body broke the byte bound or strict UTF-8 (never a timeout). */
        boolean unreadable() {
            return getCause() instanceof ProMonetization.Rejected
                    && ((ProMonetization.Rejected) getCause()).reason == ProMonetization.Reason.MALFORMED;
        }
    }

    /**
     * One device-authenticated customer attempt (CustomerApi): exactly {@code body} (Content-Type
     * application/json) or no body, the caller's fresh signed headers (and its Idempotency-Key, if any),
     * no redirects, caches or URL credentials, at most {@code maxBytes} of strict UTF-8 body and one
     * total CUSTOMER_TIMEOUT_MS budget enforced like tasks (deadline checked before every body read,
     * the watchdog disconnects an attempt still running). A 429 returns its status and Retry-After
     * without reading the body; an answer without any body returns its status with body null
     * (classified by status, never as offline). Every failure is a CustomerFailure that records whether
     * the connection had been established. No retry here.
     */
    static CustomerReply customer(String baseUrl, String path, String method, byte[] body, Map<String, String> headers,
                                  int maxBytes) throws CustomerFailure {
        return customer(baseUrl, path, method, body, headers, maxBytes, CUSTOMER_TIMEOUT_MS);
    }

    /** As above with an explicit total budget (host tests prove the deadline with a short one). */
    static CustomerReply customer(String baseUrl, String path, String method, byte[] body, Map<String, String> headers,
                                  int maxBytes, int timeoutMs) throws CustomerFailure {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final HttpsURLConnection connection;
        try { connection = open(baseUrl, path, method, timeoutMs, timeoutMs); }
        catch (Exception invalid) { throw new CustomerFailure(invalid, false); }
        ScheduledFuture<?> abort = watchdog().schedule(connection::disconnect, timeoutMs, TimeUnit.MILLISECONDS);
        boolean connected = false;
        try {
            for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
            }
            connection.connect(); // DNS, TCP and TLS: a failure up to here never reached the server.
            connected = true;
            if (body != null) {
                try (OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            }
            int status = connection.getResponseCode();
            int retryAfter = retryAfterHeader(connection);
            if (status == HTTP_TOO_MANY_REQUESTS) return new CustomerReply(status, null, retryAfter, false);
            boolean replayed = "true".equalsIgnoreCase(connection.getHeaderField("Idempotent-Replay"));
            InputStream answer = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (answer == null) return new CustomerReply(status, null, retryAfter, replayed);
            try (InputStream stream = answer) {
                return new CustomerReply(status, ProMonetization.readBounded(stream, maxBytes, deadline, System::nanoTime), retryAfter, replayed);
            }
        } catch (Exception failure) {
            throw new CustomerFailure(failure, connected);
        } finally {
            abort.cancel(false);
            connection.disconnect();
        }
    }

    private static synchronized ScheduledExecutorService watchdog() {
        if (watchdog == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "ZenlygramProWatchdog");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true); // Cancelled deadlines do not accumulate.
            watchdog = executor;
        }
        return watchdog;
    }

    private static HttpsURLConnection open(String baseUrl, String path, String method, int connectMs, int readMs) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(baseUrl + "/" + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static String read(HttpsURLConnection connection, int status, int maxBytes, long deadlineNanos) throws Exception {
        InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (response == null) throw new java.io.IOException("Empty Pro response");
        try (InputStream stream = response) { return ProMonetization.readBounded(stream, maxBytes, deadlineNanos, System::nanoTime); }
    }

    private static int retryAfterHeader(HttpsURLConnection connection) {
        try { return Integer.parseInt(connection.getHeaderField("Retry-After")); }
        catch (Exception ignored) { return 0; }
    }

    private ProTransport() { }
}
