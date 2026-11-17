package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.ScriptedHttps.Answer;
import org.telegram.messenger.zenlygram.pro.ScriptedHttps.Connection;
import org.telegram.messenger.zenlygram.pro.ScriptedHttps.TrackedStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.telegram.messenger.zenlygram.pro.ProMonetization.MAX_RESPONSE_BYTES;
import static org.telegram.messenger.zenlygram.pro.ProMonetization.REQUEST_TIMEOUT_MS;

/**
 * Plain-JVM behavior tests of the real ProTransport.getPublic (the public policy GET behind
 * ZenlyPro.refreshPolicy) against the scripted HTTPS boundary in ScriptedHttps, run in their own
 * JVM by run-transport-tests.sh. They cover: a 429's status and Retry-After reach the caller even
 * when its error body is missing, oversized, failing or slow (so the cooldown is applied); a 200
 * keeps its byte bound, strict UTF-8, total deadline, watchdog abort and cleanup; redirects are not
 * followed, no credentials are sent, the URL names the platform in its query and the one Accept header
 * lists the ver-4 media type alone; other statuses never surface as success and broken non-429
 * answers still fail. The boundary is a host fake: no real TLS, DNS, provider or device behavior.
 */
public final class ProTransportTests {
    private static final String API = "https://pro.example.test/v1";
    /** The real request of the build: the policy of the Android line, the platform in the query. */
    private static final String PATH = ProMonetization.POLICY_PATH;
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    /** Either the returned Text or the thrown exception of one getPublic call. */
    private static final class Outcome {
        final ProTransport.Text text;
        final Exception error;
        final long elapsedMs;
        final List<Connection> opened = ScriptedHttps.opened();
        final List<String> unscripted = ScriptedHttps.unscripted();

        Outcome(ProTransport.Text text, Exception error, long elapsedMs) {
            this.text = text;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        Connection only() { return opened.size() == 1 ? opened.get(0) : null; }

        boolean disconnected() { return only() != null && only().disconnects.get() >= 1; }

        ProMonetization.Reason rejection() {
            return error instanceof ProMonetization.Rejected ? ((ProMonetization.Rejected) error).reason : null;
        }
    }

    public static void main(String[] args) {
        ScriptedHttps.install();
        throttledAnswerKeepsCooldown();
        successAnswerUnchanged();
        noFalseSuccess();
        if (!failures.isEmpty()) {
            System.out.println("Pro transport: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Pro transport: " + checks + " checks passed");
    }

    /** ZenlyPro.refreshPolicy applies a 429 cooldown only if getPublic returns the 429 and its Retry-After. */
    private static void throttledAnswerKeepsCooldown() {
        Map<String, String> retry30 = ScriptedHttps.headers("Retry-After", "30");

        Outcome missing = fetch(new Answer(429, retry30, ScriptedHttps.none()));
        throttled(missing, 30, "429 without an error body");

        Outcome oversized = fetch(new Answer(429, retry30, ScriptedHttps.endless()));
        throttled(oversized, 30, "429 with an endless error body");
        check(maxConsumed(oversized) <= MAX_RESPONSE_BYTES + 1, "429 endless error body: at most the byte bound is read");

        Outcome failing = fetch(new Answer(429, retry30, ScriptedHttps.failing()));
        throttled(failing, 30, "429 whose error body read fails");

        Outcome slow = fetch(new Answer(429, ScriptedHttps.headers("Retry-After", "7"), ScriptedHttps.drip(100)), MAX_RESPONSE_BYTES, 300);
        throttled(slow, 7, "429 whose error body drips past the deadline");

        Outcome wellFormed = fetch(new Answer(429, retry30, ScriptedHttps.bytes(utf8("{\"error\":\"rate_limited\",\"retry_after\":30}"))));
        throttled(wellFormed, 30, "429 with a well-formed error body");

        Outcome noHeader = fetch(new Answer(429, ScriptedHttps.headers(), ScriptedHttps.none()));
        throttled(noHeader, 0, "429 without Retry-After and without a body (caller waits its minimum)");
    }

    private static void throttled(Outcome outcome, int retryAfter, String name) {
        check(outcome.error == null, name + ": returns instead of throwing (got " + describe(outcome.error) + ")");
        check(outcome.text != null && outcome.text.status == 429, name + ": status 429 reaches the caller");
        check(outcome.text != null && outcome.text.retryAfter == retryAfter,
                name + ": Retry-After " + retryAfter + " reaches the caller (got " + (outcome.text == null ? "none" : outcome.text.retryAfter) + ")");
        check(outcome.disconnected(), name + ": connection disconnected");
        check(outcome.opened.size() == 1 && outcome.unscripted.isEmpty(), name + ": exactly one request, no retry");
        check(outcome.only() != null && outcome.only().bodyRequests.get() == 0, name + ": the error body is never requested");
        check(outcome.text != null && outcome.text.body == null, name + ": no body is returned with the 429");
    }

    /** A 200 keeps its bound, strict decoding, deadline, watchdog abort, cleanup and request shape. */
    private static void successAnswerUnchanged() {
        String body = "{\"policy_token\":\"t\",\"server_time\":\"2026-10-22T00:00:00Z\",\"note\":\"Zenlygram ✓\"}";
        Outcome normal = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8(body))));
        check(normal.error == null && normal.text != null && normal.text.status == 200, "200: returns status 200");
        check(normal.text != null && body.equals(normal.text.body), "200: body is the exact UTF-8 text");
        check(normal.text != null && normal.text.retryAfter == 0, "200: no Retry-After means 0");
        check(allClosed(normal), "200: body stream closed");
        check(normal.disconnected(), "200: connection disconnected");
        requestShape(normal);

        String exact = repeat('a', MAX_RESPONSE_BYTES);
        Outcome atLimit = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8(exact))));
        check(atLimit.text != null && exact.equals(atLimit.text.body), "200: a body of exactly the byte bound is accepted");

        Outcome oversized = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.endless()));
        check(oversized.rejection() == ProMonetization.Reason.MALFORMED, "200 endless body: rejected as MALFORMED (got " + describe(oversized.error) + ")");
        check(maxConsumed(oversized) == MAX_RESPONSE_BYTES + 1, "200 endless body: stops one byte past the bound");
        check(allClosed(oversized) && oversized.disconnected(), "200 endless body: stream closed and connection disconnected");

        Outcome failing = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.failing()));
        check(failing.error instanceof IOException, "200 failing body: the read failure propagates (got " + describe(failing.error) + ")");
        check(allClosed(failing) && failing.disconnected(), "200 failing body: stream closed and connection disconnected");

        Outcome invalid = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(new byte[] {'{', (byte) 0xC3, '}'})));
        check(invalid.rejection() == ProMonetization.Reason.MALFORMED, "200 invalid UTF-8: rejected as MALFORMED");

        Outcome missing = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.none()));
        check(missing.error != null && missing.text == null, "200 without a body stream: fails, never an empty success");
        check(missing.disconnected(), "200 without a body stream: connection disconnected");

        Outcome slow = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.drip(100)), MAX_RESPONSE_BYTES, 300);
        check(slow.rejection() == ProMonetization.Reason.TIMED_OUT, "200 dripping body: abandoned at the total deadline (got " + describe(slow.error) + ")");
        check(slow.elapsedMs < 3000 && slow.disconnected(), "200 dripping body: bounded time and disconnected");

        Outcome blocked = fetch(new Answer(200, ScriptedHttps.headers(), ScriptedHttps.blockedUntilDisconnect(5000)), MAX_RESPONSE_BYTES, 200);
        check(blocked.error instanceof IOException && !"never aborted".equals(blocked.error.getMessage()),
                "200 blocked read: the watchdog disconnect aborts it (got " + describe(blocked.error) + ")");
        check(blocked.elapsedMs < 3000, "200 blocked read: ends near the deadline, not the safety bound");
    }

    /** Public GET: no credentials, cookies, caching or redirects; the configured budget is used. */
    private static void requestShape(Outcome outcome) {
        Connection connection = outcome.only();
        check(connection != null, "request: exactly one connection");
        if (connection == null) return;
        check((API + "/monetization?platform=android").equals(connection.getURL().toString()),
                "request: exact policy URL, the platform in its query (asked " + connection.getURL() + ")");
        check("GET".equals(connection.sentMethod), "request: GET");
        check(!connection.sentFollowRedirects, "request: redirects are not followed");
        check(!connection.sentUseCaches, "request: no cache");
        check(connection.sentConnectTimeout == REQUEST_TIMEOUT_MS && connection.sentReadTimeout == REQUEST_TIMEOUT_MS,
                "request: connect and read timeouts are the total budget");
        check(new HashSet<>(connection.sentHeaders.keySet()).equals(new HashSet<>(Collections.singletonList("Accept"))),
                "request: only Accept is sent, no Authorization, cookie or device headers (sent " + connection.sentHeaders.keySet() + ")");
        check(Collections.singletonList("application/vnd.zenlygram.monetization.v4+json").equals(connection.sentHeaders.get("Accept")),
                "request: one Accept header that lists the ver-4 media type alone (sent " + connection.sentHeaders.get("Accept") + ")");
    }

    /** Non-200 answers keep their own status, and broken non-429 answers still fail. */
    private static void noFalseSuccess() {
        Outcome redirect = fetch(new Answer(302, ScriptedHttps.headers("Location", "https://elsewhere.example.test/v1/monetization"),
                ScriptedHttps.bytes(utf8("moved"))));
        check(redirect.text != null && redirect.text.status == 302, "302: reported as 302, not success (got " + describe(redirect.error) + ")");
        check(redirect.opened.size() == 1 && redirect.unscripted.isEmpty(), "302: the Location is never requested");
        check(redirect.only() != null && !redirect.only().sentFollowRedirects, "302: sent with redirects disabled");
        check(redirect.disconnected(), "302: connection disconnected");

        for (int status : new int[] {204, 301, 304, 400, 401, 403, 404, 500, 503}) {
            Outcome outcome = fetch(new Answer(status, ScriptedHttps.headers("Retry-After", "5"), ScriptedHttps.bytes(utf8("{\"error\":\"x\"}"))));
            check(outcome.text != null && outcome.text.status == status, status + " with a body: its own status is reported (got " + describe(outcome.error) + ")");
            check(outcome.disconnected(), status + ": connection disconnected");
        }

        Outcome unavailable = fetch(new Answer(503, ScriptedHttps.headers("Retry-After", "60"), ScriptedHttps.none()));
        check(unavailable.text == null && unavailable.error != null, "503 without a body: still fails (got " + text(unavailable) + ")");
        Outcome serverOversized = fetch(new Answer(500, ScriptedHttps.headers(), ScriptedHttps.endless()));
        check(serverOversized.rejection() == ProMonetization.Reason.MALFORMED, "500 endless body: still rejected (got " + text(serverOversized) + ")");
        Outcome notFoundFailing = fetch(new Answer(404, ScriptedHttps.headers(), ScriptedHttps.failing()));
        check(notFoundFailing.error instanceof IOException, "404 failing body: still fails (got " + text(notFoundFailing) + ")");
        check(unavailable.disconnected() && serverOversized.disconnected() && notFoundFailing.disconnected(),
                "broken non-429 answers: connections disconnected");
    }

    private static Outcome fetch(Answer answer) {
        return fetch(answer, MAX_RESPONSE_BYTES, REQUEST_TIMEOUT_MS);
    }

    private static Outcome fetch(Answer answer, int maxBytes, int timeoutMs) {
        ScriptedHttps.script(answer);
        long started = System.nanoTime();
        ProTransport.Text text = null;
        Exception error = null;
        try { text = ProTransport.getPublic(API, PATH, ProMonetization.ACCEPT, maxBytes, timeoutMs); }
        catch (Exception e) { error = e; }
        return new Outcome(text, error, (System.nanoTime() - started) / 1_000_000L);
    }

    /** At least one body stream was handed out and every one of them was closed. */
    private static boolean allClosed(Outcome outcome) {
        Connection connection = outcome.only();
        if (connection == null) return false;
        List<TrackedStream> streams = connection.handedOut();
        if (streams.isEmpty()) return false;
        for (TrackedStream stream : streams) {
            if (!stream.closed) return false;
        }
        return true;
    }

    private static int maxConsumed(Outcome outcome) {
        int max = 0;
        if (outcome.only() == null) return max;
        for (TrackedStream stream : outcome.only().handedOut()) max = Math.max(max, stream.consumed.get());
        return max;
    }

    private static String text(Outcome outcome) {
        return outcome.text == null ? describe(outcome.error) : "Text status " + outcome.text.status;
    }

    private static String describe(Exception error) {
        return error == null ? "no exception" : error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    private ProTransportTests() { }
}
