package org.telegram.messenger.zenlygram.pro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

/**
 * Host-only HTTPS boundary for TasksTransportTests: one scripted answer per opened connection so the
 * real ProTransport.tasks runs unchanged (request properties, uploaded bytes, redirect/cache flags,
 * status/header/body reads, byte bound, total deadline, watchdog abort, cleanup). Installed through
 * URL.setURLStreamHandlerFactory only; no trust manager, socket factory or system property changes,
 * and nothing here proves real TLS, DNS, server or device behavior. An unscripted connection fails.
 */
final class TasksScriptedHttps {
    interface Body { int read(Connection connection, byte[] buffer, int offset, int length) throws IOException; }

    static final class Answer {
        final int status;
        final Map<String, String> headers;
        final Body body;
        Answer(int status, Map<String, String> headers, Body body) {
            this.status = status;
            Map<String, String> lower = new HashMap<>();
            for (Map.Entry<String, String> entry : headers.entrySet()) lower.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            this.headers = Collections.unmodifiableMap(lower);
            this.body = body;
        }
    }

    static final class Connection extends HttpsURLConnection {
        private final Answer answer;
        final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger consumed = new AtomicInteger();
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile Map<String, List<String>> sentHeaders;
        volatile String sentMethod;
        volatile boolean sentFollowRedirects, sentUseCaches, sentDoOutput;
        volatile int sentConnectTimeout, sentReadTimeout;
        volatile boolean bodyClosed;

        Connection(URL url, Answer answer) {
            super(url);
            this.answer = answer;
        }

        private void snapshot() {
            if (sentHeaders != null) return;
            sentHeaders = new HashMap<>(getRequestProperties());
            sentMethod = getRequestMethod();
            sentFollowRedirects = getInstanceFollowRedirects();
            sentUseCaches = getUseCaches();
            sentDoOutput = getDoOutput();
            sentConnectTimeout = getConnectTimeout();
            sentReadTimeout = getReadTimeout();
        }

        @Override public void connect() {
            if (connected) return;
            snapshot();
            connected = true;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            if (!getDoOutput()) throw new IOException("output not enabled");
            snapshot(); // Request properties are fixed once the upload starts.
            connected = true;
            return new OutputStream() {
                @Override public void write(int b) { uploaded.write(b); }
                @Override public void write(byte[] data, int offset, int length) { uploaded.write(data, offset, length); }
            };
        }

        @Override public void disconnect() {
            disconnects.incrementAndGet();
            disconnected.countDown();
        }

        @Override public int getResponseCode() {
            connect();
            return answer.status;
        }

        @Override public InputStream getInputStream() throws IOException {
            connect();
            if (answer.status >= 400) throw new IOException("HTTP " + answer.status);
            return stream();
        }

        @Override public InputStream getErrorStream() {
            if (!connected || answer.status < 400) return null;
            return stream();
        }

        private InputStream stream() {
            if (answer.body == null) return null;
            return new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    int count = read(one, 0, 1);
                    return count == -1 ? -1 : one[0] & 0xff;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    int count = answer.body.read(Connection.this, buffer, offset, length);
                    if (count > 0) consumed.addAndGet(count);
                    return count;
                }
                @Override public void close() { bodyClosed = true; }
            };
        }

        @Override public String getHeaderField(String name) {
            connect();
            return name == null ? null : answer.headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "SCRIPTED_HOST_FIXTURE"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }

    private static final Deque<Answer> SCRIPT = new ArrayDeque<>();
    private static final List<Connection> OPENED = new ArrayList<>();
    private static final List<String> UNSCRIPTED = new ArrayList<>();
    private static boolean installed;

    static synchronized void install() {
        if (installed) return;
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException { return open(url); }
        } : null);
        installed = true;
    }

    static synchronized void script(Answer... answers) {
        SCRIPT.clear();
        OPENED.clear();
        UNSCRIPTED.clear();
        Collections.addAll(SCRIPT, answers);
    }

    static synchronized List<Connection> opened() { return new ArrayList<>(OPENED); }

    static synchronized List<String> unscripted() { return new ArrayList<>(UNSCRIPTED); }

    private static synchronized Connection open(URL url) throws IOException {
        Answer answer = SCRIPT.poll();
        if (answer == null) {
            UNSCRIPTED.add(url.toString());
            throw new IOException("unscripted connection");
        }
        Connection connection = new Connection(url, answer);
        OPENED.add(connection);
        return connection;
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    static Body bytes(byte[] data) {
        final int[] position = {0};
        return (connection, buffer, offset, length) -> {
            if (position[0] >= data.length) return -1;
            int count = Math.min(length, data.length - position[0]);
            System.arraycopy(data, position[0], buffer, offset, count);
            position[0] += count;
            return count;
        };
    }

    static Body endless() {
        return (connection, buffer, offset, length) -> {
            for (int i = 0; i < length; i++) buffer[offset + i] = 'a';
            return length;
        };
    }

    /** One byte per read after delayMs: only a total deadline stops it. */
    static Body drip(long delayMs) {
        return (connection, buffer, offset, length) -> {
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            buffer[offset] = ' ';
            return 1;
        };
    }

    /**
     * The whole body after delayMs, in one go: a slow but complete answer. The wait ends early, with the
     * failure a socket gives, when the connection is disconnected (the watchdog) meanwhile.
     */
    static Body delayed(long delayMs, byte[] data) {
        final Body rest = bytes(data);
        final boolean[] waited = {false};
        return (connection, buffer, offset, length) -> {
            if (!waited[0]) {
                waited[0] = true;
                try {
                    if (connection.disconnected.await(delayMs, TimeUnit.MILLISECONDS)) throw new IOException("socket closed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            return rest.read(connection, buffer, offset, length);
        };
    }

    /** A read blocked until the watchdog disconnects the connection (bounded for safety). */
    static Body blockedUntilDisconnect(long safetyMs) {
        return (connection, buffer, offset, length) -> {
            try {
                if (!connection.disconnected.await(safetyMs, TimeUnit.MILLISECONDS)) throw new IOException("never aborted");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            throw new IOException("socket closed");
        };
    }

    private TasksScriptedHttps() { }
}
