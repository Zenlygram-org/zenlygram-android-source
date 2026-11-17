package org.telegram.messenger.zenlygram.tasks.cloud;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * The real production TasksRealtimeWebSocket against a loopback TLS server (keytool cert, SAN ip:127.0.0.1, installed
 * as the JVM default SSLContext so the class's own {@code SSLSocketFactory.getDefault()} path is used): upgrade request
 * shape, Sec-WebSocket-Accept, fragmented text, RFC ping→pong with payload, masked client text, close handshake echo,
 * TLS endpoint identification refusing a host the certificate does not name (proven by the server-side handshake
 * alert, not just a failed connect), and the API-23 shape (no endpoint identification / SNI setters: NoSuchMethodError)
 * failing closed through the explicit hostname check instead of killing the reader thread.
 * <p>
 * JDK TLS, not Android/Conscrypt. The production verifier {@code HttpsURLConnection.getDefaultHostnameVerifier()} is not
 * exercised (on a JDK it rejects every host); the tests inject a SAN-checking verifier with the same contract.
 */
public final class TasksRealtimeTlsTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();
    private static final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    public static void main(String[] args) throws Exception {
        char[] password = args[1].toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) { keys.load(in, password); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLContext.setDefault(context); // what the production class's SSLSocketFactory.getDefault() now returns
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));

        exchange(context);
        wrongHost(context);
        verifierConsulted(context);
        api23(context);
        check(uncaught.isEmpty(), "no Throwable ever escaped a realtime thread (" + uncaught + ")");
        if (!failures.isEmpty()) {
            System.out.println("Tasks realtime TLS WebSocket: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks realtime TLS WebSocket (loopback, JDK TLS): " + checks + " checks passed");
    }

    static final class Recorder implements TasksRealtimeSocket.TransportEvents {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();

        @Override public void opened() { events.add("opened"); }

        @Override public void text(String text) { events.add("text:" + text); }

        @Override public void binary() { events.add("binary"); }

        @Override public void closed(int code) { events.add("closed:" + code); }

        @Override public void failed() { events.add("failed"); }

        String next() throws InterruptedException {
            String event = events.poll(10, TimeUnit.SECONDS);
            return event == null ? "timeout" : event;
        }
    }

    /** The production verifier's contract, checked against the peer certificate's IP/DNS SANs. */
    static final HostnameVerifier SAN_VERIFIER = (host, session) -> {
        try {
            X509Certificate leaf = (X509Certificate) session.getPeerCertificates()[0];
            Collection<List<?>> names = leaf.getSubjectAlternativeNames();
            if (names != null) for (List<?> name : names) if (host.equals(name.get(1))) return true;
        } catch (Exception ignored) {
            // Unreadable: refuse.
        }
        return false;
    };

    /**
     * SSLParameters as on API 23: no endpoint identification setter. (The JDK's setServerNames is final, so the SNI
     * setter's identical LinkageError guard cannot be simulated here.)
     */
    static final class Api23Parameters extends SSLParameters {
        int missingCalls;

        Api23Parameters(SSLParameters platform) {
            super(platform.getCipherSuites(), platform.getProtocols());
        }

        @Override public void setEndpointIdentificationAlgorithm(String algorithm) {
            missingCalls++;
            throw new NoSuchMethodError("javax.net.ssl.SSLParameters.setEndpointIdentificationAlgorithm");
        }
    }

    static SSLServerSocket server(SSLContext context) throws Exception {
        return (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    }

    static TasksRealtimeWire.Endpoint endpoint(String host, int port) {
        return TasksRealtimeWire.Endpoint.of("wss://" + host + ":" + port + "/realtime/app/key1", "https://" + host + ":" + port + "/v1");
    }

    static String head(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!out.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IllegalStateException("eof");
            out.write(b);
        }
        return out.toString("ISO-8859-1");
    }

    static void write(OutputStream out, boolean fin, int opcode, byte[] payload) throws Exception {
        out.write((fin ? 0x80 : 0) | opcode);
        out.write(payload.length);
        out.write(payload);
        out.flush();
    }

    /** One client frame: must be masked; returns {opcode, unmasked payload}. */
    static Object[] readClient(InputStream in) throws Exception {
        int b0 = in.read(), b1 = in.read();
        boolean masked = (b1 & 0x80) != 0;
        int len = b1 & 0x7F;
        byte[] mask = new byte[4];
        for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        byte[] payload = new byte[len];
        for (int i = 0; i < len; i++) payload[i] = (byte) (in.read() ^ mask[i & 3]);
        return new Object[]{masked && (b0 & 0x80) != 0 ? (b0 & 0x0F) : -1, payload};
    }

    static void exchange(SSLContext context) throws Exception {
        final SSLServerSocket server = server(context);
        final int port = server.getLocalPort();
        final List<String> serverSide = new CopyOnWriteArrayList<>();
        Thread peer = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                String request = head(in);
                serverSide.add(request);
                String key = null;
                for (String line : request.split("\r\n")) if (line.startsWith("Sec-WebSocket-Key: ")) key = line.substring(19);
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + TasksRealtimeFrames.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                write(out, false, 1, "{\"event\":\"pusher:".getBytes(StandardCharsets.UTF_8));
                write(out, true, 9, "hb".getBytes(StandardCharsets.UTF_8));
                write(out, true, 0, "ping\"}".getBytes(StandardCharsets.UTF_8));
                Object[] pong = readClient(in);
                serverSide.add("pong:" + pong[0] + ":" + new String((byte[]) pong[1], StandardCharsets.UTF_8));
                Object[] text = readClient(in);
                serverSide.add("text:" + text[0] + ":" + new String((byte[]) text[1], StandardCharsets.UTF_8));
                write(out, true, 8, new byte[]{0x0F, (byte) 0xA1});
                Object[] close = readClient(in);
                byte[] code = (byte[]) close[1];
                serverSide.add("close:" + close[0] + ":" + (code.length == 2 ? ((code[0] & 0xFF) << 8 | (code[1] & 0xFF)) : -1));
            } catch (Exception e) {
                serverSide.add("server error " + e);
            }
        });
        peer.start();
        Recorder recorder = new Recorder();
        TasksRealtimeWebSocket socket = new TasksRealtimeWebSocket(Runnable::run, SAN_VERIFIER, TasksRealtimeWebSocket.PLATFORM_TLS);
        socket.open(endpoint("127.0.0.1", port), recorder);
        check("opened".equals(recorder.next()), "TLS + upgrade completed through the default SSLSocketFactory");
        check("text:{\"event\":\"pusher:ping\"}".equals(recorder.next()), "fragmented text reassembled across an interleaved RFC ping");
        socket.send("hello");
        check(("closed:4001").equals(recorder.next()), "server close 4001 surfaced with its code");
        peer.join(10_000);
        server.close();
        String request = serverSide.isEmpty() ? "" : serverSide.get(0);
        check(request.startsWith("GET /realtime/app/key1?protocol=7&client=zenlygram-android&version=1.0&flash=false HTTP/1.1\r\n")
                && request.contains("\r\nHost: 127.0.0.1:" + port + "\r\n") && request.contains("\r\nOrigin: https://127.0.0.1:" + port + "\r\n")
                && !request.toLowerCase().contains("cookie") && !request.toLowerCase().contains("authorization"),
                "upgrade request: exact path/query, Host, Origin; no cookie or credential");
        check(serverSide.contains("pong:10:hb"), "RFC ping answered with a masked pong echoing its payload");
        check(serverSide.contains("text:1:hello"), "client text frame masked and intact");
        check(serverSide.contains("close:8:4001"), "close handshake: the client echoes the peer's close code");
        socket.close();
    }

    /** Server peer that records how the client ended the TLS handshake. */
    static Thread handshakePeer(final SSLServerSocket server, final List<String> marks) {
        Thread peer = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                marks.add("accepted");
                socket.startHandshake();
                marks.add("handshake-ok");
                if (socket.getInputStream().read() < 0) marks.add("client-closed-before-any-byte");
            } catch (SSLHandshakeException rejected) {
                marks.add("handshake-rejected-by-client");
            } catch (Exception other) {
                marks.add("other:" + other.getClass().getSimpleName());
            }
        });
        peer.start();
        return peer;
    }

    static void wrongHost(SSLContext context) throws Exception {
        final SSLServerSocket server = server(context);
        final List<String> marks = new CopyOnWriteArrayList<>();
        Thread peer = handshakePeer(server, marks);
        Recorder recorder = new Recorder();
        // An always-true verifier: only TLS endpoint identification can refuse here. "localhost" must reach this
        // IPv4 listener; the certificate names only ip:127.0.0.1.
        TasksRealtimeWebSocket socket = new TasksRealtimeWebSocket(Runnable::run, (host, session) -> true, TasksRealtimeWebSocket.PLATFORM_TLS);
        socket.open(endpoint("localhost", server.getLocalPort()), recorder);
        check("failed".equals(recorder.next()), "a host the certificate does not name fails");
        peer.join(10_000);
        server.close();
        check(marks.contains("accepted") && marks.contains("handshake-rejected-by-client") && !marks.contains("handshake-ok"),
                "…by the client's certificate check during the handshake, not a failed connect (" + marks + ")");
    }

    static void verifierConsulted(SSLContext context) throws Exception {
        final SSLServerSocket server = server(context);
        final List<String> marks = new CopyOnWriteArrayList<>();
        Thread peer = handshakePeer(server, marks);
        Recorder recorder = new Recorder();
        TasksRealtimeWebSocket socket = new TasksRealtimeWebSocket(Runnable::run, (host, session) -> false, TasksRealtimeWebSocket.PLATFORM_TLS);
        socket.open(endpoint("127.0.0.1", server.getLocalPort()), recorder);
        check("failed".equals(recorder.next()), "the HostnameVerifier is consulted after the handshake and can refuse");
        peer.join(10_000);
        server.close();
        check(marks.contains("handshake-ok") && marks.contains("client-closed-before-any-byte"),
                "a verifier refusal closes before any upgrade byte is written (" + marks + ")");
    }

    static void api23(SSLContext context) throws Exception {
        // 1. The parameter guard itself: both missing setters are attempted and swallowed.
        Api23Parameters old = new Api23Parameters(new SSLParameters());
        Throwable thrown = null;
        try {
            TasksRealtimeWebSocket.parameters(old, "realtime.example.org");
        } catch (Throwable t) {
            thrown = t;
        }
        check(thrown == null && old.missingCalls == 1 && old.getServerNames() != null && !old.getServerNames().isEmpty(),
                "API 23: the missing endpoint-identification setter's NoSuchMethodError is caught, SNI still set (" + thrown + ")");

        TasksRealtimeWebSocket.TlsSetup api23Tls = (tls, host) -> tls.setSSLParameters(
                TasksRealtimeWebSocket.parameters(new Api23Parameters(tls.getSSLParameters()), host));

        // 2. Without endpoint identification the wrong-host handshake succeeds; the explicit check still refuses.
        SSLServerSocket server = server(context);
        List<String> marks = new CopyOnWriteArrayList<>();
        Thread peer = handshakePeer(server, marks);
        Recorder recorder = new Recorder();
        new TasksRealtimeWebSocket(Runnable::run, SAN_VERIFIER, api23Tls).open(endpoint("localhost", server.getLocalPort()), recorder);
        check("failed".equals(recorder.next()), "API 23 shape: a host the certificate does not name fails");
        peer.join(10_000);
        server.close();
        check(marks.contains("handshake-ok") && marks.contains("client-closed-before-any-byte"),
                "…by the explicit post-handshake hostname check (no TLS endpoint identification), before any byte (" + marks + ")");

        // 3. API 23 shape with the right host still connects.
        server = server(context);
        final SSLServerSocket upgrading = server;
        Thread ok = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) upgrading.accept()) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                String request = head(in);
                String key = null;
                for (String line : request.split("\r\n")) if (line.startsWith("Sec-WebSocket-Key: ")) key = line.substring(19);
                socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + TasksRealtimeFrames.accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                in.read();
            } catch (Exception ignored) {
                // The client closes.
            }
        });
        ok.start();
        Recorder good = new Recorder();
        TasksRealtimeWebSocket connected = new TasksRealtimeWebSocket(Runnable::run, SAN_VERIFIER, api23Tls);
        connected.open(endpoint("127.0.0.1", server.getLocalPort()), good);
        check("opened".equals(good.next()), "API 23 shape: the named host still connects");
        connected.close();
        ok.join(10_000);
        server.close();

        // 4. Any Error on the reader thread (here: thrown by the TLS setup itself) fails the attempt, never the process.
        server = server(context);
        marks = new CopyOnWriteArrayList<>();
        peer = handshakePeer(server, marks);
        Recorder crashed = new Recorder();
        new TasksRealtimeWebSocket(Runnable::run, SAN_VERIFIER, (tls, host) -> {
            throw new NoSuchMethodError("javax.net.ssl.SSLParameters.setEndpointIdentificationAlgorithm");
        }).open(endpoint("127.0.0.1", server.getLocalPort()), crashed);
        check("failed".equals(crashed.next()), "a NoSuchMethodError on the reader thread becomes `failed`");
        server.close();
        peer.join(10_000);
        Thread.sleep(200);
        check(uncaught.isEmpty(), "…and nothing reaches the uncaught-exception handler");
        check(Collections.disjoint(marks, Collections.singleton("handshake-ok")), "…and no handshake was completed");
    }
}
