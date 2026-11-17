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
import java.util.function.Function;

import javax.net.ssl.HttpsURLConnection;

/**
 * Host-only HTTPS boundary for ProTransportTests and CustomerTransportTests: replaces the network side
 * of {@code new URL("https://…").openConnection()} with one scripted answer per opened connection, so
 * the real ProTransport code (request headers, uploaded bytes, redirect flag, status/header/body
 * reads, bounds, deadline, watchdog, cleanup) runs unchanged. Installed once per test JVM through the
 * {@link URL#setURLStreamHandlerFactory} hook; it touches no trust manager, socket factory,
 * hostname verifier or system property, and nothing here proves real TLS, provider or device
 * behavior. An unscripted connection fails the test, so a followed redirect or a retry is visible.
 * A scripted {@link Fault} stands in for a connection that fails before or after it is established.
 */
final class ScriptedHttps {
    /** A connection-level failure instead of (or before) the scripted answer. */
    enum Fault {
        /** connect() fails (DNS, TCP or TLS): the connection is never established. */
        CONNECT,
        /** Established, then reset while the request body is written. */
        WRITE,
        /** Established, then no status: getResponseCode() blocks until the client disconnects (its watchdog). */
        STALL
    }

    /** What the fake server answers: status, response headers, and the body stream factory. */
    static final class Answer {
        final int status;
        final Map<String, String> headers;
        /** Body for status < 400 (getInputStream) or error body for >= 400 (getErrorStream); may return null. */
        final Function<Connection, TrackedStream> body;
        /** Null for a normal answer. */
        final Fault fault;

        Answer(int status, Map<String, String> headers, Function<Connection, TrackedStream> body) {
            this(status, headers, body, null);
        }

        Answer(int status, Map<String, String> headers, Function<Connection, TrackedStream> body, Fault fault) {
            this.status = status;
            this.headers = Collections.unmodifiableMap(lowerCaseKeys(headers));
            this.body = body;
            this.fault = fault;
        }
    }

    /** One opened connection: records what the client sent and how it cleaned up. */
    static final class Connection extends HttpsURLConnection {
        private final Answer answer;
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger bodyRequests = new AtomicInteger();
        private final List<TrackedStream> handedOut = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch disconnected = new CountDownLatch(1);
        /** Exactly the bytes the client wrote as the request body. */
        final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        volatile Map<String, List<String>> sentHeaders;
        volatile String sentMethod;
        volatile boolean sentFollowRedirects;
        volatile boolean sentUseCaches;
        volatile boolean sentDoOutput;
        volatile int sentConnectTimeout, sentReadTimeout;

        Connection(URL url, Answer answer) {
            super(url);
            this.answer = answer;
        }

        @Override
        public void connect() throws IOException {
            if (connected) return;
            // URLConnection forbids reading request properties after connecting: snapshot them now.
            if (sentHeaders == null) {
                sentHeaders = new HashMap<>(getRequestProperties());
                sentMethod = getRequestMethod();
                sentFollowRedirects = getInstanceFollowRedirects();
                sentUseCaches = getUseCaches();
                sentDoOutput = getDoOutput();
                sentConnectTimeout = getConnectTimeout();
                sentReadTimeout = getReadTimeout();
            }
            if (answer.fault == Fault.CONNECT) throw new IOException("connect failed");
            connected = true;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (!getDoOutput()) throw new IOException("output not enabled");
            connect();
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    if (answer.fault == Fault.WRITE) throw new IOException("connection reset");
                    uploaded.write(data, offset, length);
                }
            };
        }

        @Override
        public void disconnect() {
            disconnects.incrementAndGet();
            disconnected.countDown();
        }

        @Override
        public int getResponseCode() throws IOException {
            connect();
            if (answer.fault == Fault.STALL) {
                try {
                    if (!disconnected.await(5000, TimeUnit.MILLISECONDS)) throw new IOException("never aborted");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                throw new IOException("socket closed");
            }
            return answer.status;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            // Like the platform client: an error status has no success body.
            if (answer.status >= 400) throw new IOException("Server returned HTTP response code: " + answer.status);
            return body();
        }

        @Override
        public InputStream getErrorStream() {
            if (!connected || answer.status < 400) return null;
            return body();
        }

        /** Every non-null body stream handed to the client so far. */
        List<TrackedStream> handedOut() {
            synchronized (handedOut) { return new ArrayList<>(handedOut); }
        }

        private InputStream body() {
            bodyRequests.incrementAndGet();
            TrackedStream stream = answer.body.apply(this);
            if (stream != null) handedOut.add(stream);
            return stream;
        }

        @Override
        public String getHeaderField(String name) {
            if (!connected) return null; // Like the platform client after a failed connect: no headers.
            return name == null ? null : answer.headers.get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean usingProxy() { return false; }

        @Override
        public String getCipherSuite() { return "SCRIPTED_HOST_FIXTURE"; }

        @Override
        public Certificate[] getLocalCertificates() { return null; }

        @Override
        public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }

    /** Body stream that records how much the client consumed and whether it closed it. */
    static final class TrackedStream extends InputStream {
        interface Source { int read(byte[] buffer, int offset, int length) throws IOException; }

        private final Source source;
        final AtomicInteger consumed = new AtomicInteger();
        volatile boolean closed;

        TrackedStream(Source source) { this.source = source; }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("stream closed");
            if (length == 0) return 0;
            int count = source.read(buffer, offset, length);
            if (count > 0) consumed.addAndGet(count);
            return count;
        }

        @Override
        public void close() { closed = true; }
    }

    private static final Deque<Answer> SCRIPT = new ArrayDeque<>();
    private static final List<Connection> OPENED = new ArrayList<>();
    private static final List<String> UNSCRIPTED = new ArrayList<>();
    private static boolean installed;

    /** Installs the https handler for this JVM; every other protocol keeps the JDK handler. */
    static synchronized void install() {
        if (installed) return;
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) throws IOException { return open(url); }
        } : null);
        installed = true;
    }

    /** Clears the previous case and scripts the answers for the next one, in order. */
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
            throw new IOException("unscripted connection to " + url);
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

    static Function<Connection, TrackedStream> bytes(byte[] data) {
        return connection -> new TrackedStream(new FiniteBody(data)::read);
    }

    static Function<Connection, TrackedStream> none() {
        return connection -> null;
    }

    /** A body that never ends: only the client's byte bound stops it. */
    static Function<Connection, TrackedStream> endless() {
        return connection -> new TrackedStream((buffer, offset, length) -> {
            for (int i = 0; i < length; i++) buffer[offset + i] = 'a';
            return length;
        });
    }

    /** A connection reset while the body is read. */
    static Function<Connection, TrackedStream> failing() {
        return connection -> new TrackedStream((buffer, offset, length) -> { throw new IOException("connection reset"); });
    }

    /** One byte per read, each after {@code delayMs}: only a total deadline stops it. */
    static Function<Connection, TrackedStream> drip(long delayMs) {
        return connection -> new TrackedStream((buffer, offset, length) -> {
            sleep(delayMs);
            buffer[offset] = 'a';
            return 1;
        });
    }

    /** A read blocked until the connection is disconnected (the watchdog's abort), bounded for safety. */
    static Function<Connection, TrackedStream> blockedUntilDisconnect(long safetyMs) {
        return connection -> new TrackedStream((buffer, offset, length) -> {
            try {
                if (!connection.disconnected.await(safetyMs, TimeUnit.MILLISECONDS)) throw new IOException("never aborted");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            throw new IOException("socket closed");
        });
    }

    private static void sleep(long ms) throws IOException {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> headers) {
        Map<String, String> map = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) map.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        return map;
    }

    /** Finite body source. */
    private static final class FiniteBody {
        private final byte[] data;
        private int position;

        FiniteBody(byte[] data) { this.data = data.clone(); }

        int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) return -1;
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }
    }

    private ScriptedHttps() { }
}
