package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.ScriptedHttps.Answer;
import org.telegram.messenger.zenlygram.pro.ScriptedHttps.Connection;
import org.telegram.messenger.zenlygram.pro.ScriptedHttps.Fault;
import org.telegram.messenger.zenlygram.pro.ScriptedHttps.TrackedStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-JVM behavior tests of the real ProTransport.customer (the raw customer-data attempt behind
 * CustomerApi) against the scripted HTTPS boundary in ScriptedHttps, run in their own JVM by
 * run-transport-tests.sh. They cover: exact method, URL and uploaded bytes; the caller's signed headers
 * sent once each, Content-Type application/json only with a body, Idempotency-Key passed through and
 * Idempotent-Replay reported; no redirects followed, no caches; the 30 s budget (connect and read
 * timeouts; the total deadline and watchdog proven with a short budget); the byte bound and strict
 * UTF-8 on success and error bodies; a 429's status and Retry-After without reading its body; an error
 * without a body keeps its status; and whether a failure happened after the connection was established
 * (CustomerApi.possiblySent). The boundary is a host fake: no real TLS, DNS, backend or device behavior,
 * and no answer here is a real server receipt. Header values are synthetic.
 */
public final class CustomerTransportTests {
    private static final String API = "https://customer.example.test/v1";
    private static final String KEY = "5f0c3f5e-8f2a-4b7c-9d1e-2a3b4c5d6e7f";
    private static final String T = "2026-11-01T00:00:00Z";
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    /** Either the returned reply or the thrown exception of one customer call. */
    private static final class Outcome {
        final ProTransport.CustomerReply reply;
        final Exception error;
        final long elapsedMs;
        final List<Connection> opened = ScriptedHttps.opened();
        final List<String> unscripted = ScriptedHttps.unscripted();

        Outcome(ProTransport.CustomerReply reply, Exception error, long elapsedMs) {
            this.reply = reply;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        Connection only() { return opened.size() == 1 ? opened.get(0) : null; }

        ProTransport.CustomerFailure failure() {
            return error instanceof ProTransport.CustomerFailure ? (ProTransport.CustomerFailure) error : null;
        }

        ProMonetization.Reason rejection() {
            Throwable cause = failure() == null ? null : failure().getCause();
            return cause instanceof ProMonetization.Rejected ? ((ProMonetization.Rejected) cause).reason : null;
        }

        boolean cleanedUp() { return only() != null && only().disconnects.get() >= 1 && unscripted.isEmpty(); }

        boolean streamsClosed() {
            if (only() == null) return false;
            for (TrackedStream stream : only().handedOut()) if (!stream.closed) return false;
            return true;
        }
    }

    public static void main(String[] args) {
        ScriptedHttps.install();
        signedPost();
        deletionKeyAndReplay();
        getHasNoBody();
        throttled();
        errorBodies();
        bodylessAnswers();
        redirectsNotFollowed();
        boundsAndUtf8();
        budget();
        connectionStates();
        if (!failures.isEmpty()) {
            System.out.println("Customer transport: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Customer transport: " + checks + " checks passed");
    }

    private static Map<String, String> signed(String... extra) {
        Map<String, String> headers = ScriptedHttps.headers("Authorization", "Bearer fixture-token", "X-Device-Id", "fixture-device",
                "X-Device-Nonce", "fixture-nonce", "X-Device-Ts", "1792512000", "X-Device-Sig", "fixture-sig");
        for (int i = 0; i + 1 < extra.length; i += 2) headers.put(extra[i], extra[i + 1]);
        return headers;
    }

    private static Outcome call(String path, String method, byte[] body, Map<String, String> headers, int maxBytes, Answer... answers) {
        ScriptedHttps.script(answers);
        long started = System.nanoTime();
        ProTransport.CustomerReply reply = null;
        Exception error = null;
        try { reply = ProTransport.customer(API, path, method, body, headers, maxBytes); }
        catch (Exception e) { error = e; }
        return new Outcome(reply, error, (System.nanoTime() - started) / 1_000_000L);
    }

    private static Outcome budgeted(int timeoutMs, Answer answer) {
        ScriptedHttps.script(answer);
        long started = System.nanoTime();
        ProTransport.CustomerReply reply = null;
        Exception error = null;
        try { reply = ProTransport.customer(API, "profile/events", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES, timeoutMs); }
        catch (Exception e) { error = e; }
        return new Outcome(reply, error, (System.nanoTime() - started) / 1_000_000L);
    }

    private static void signedPost() {
        byte[] body = utf8("{\"schema_version\":1,\"consents\":[{\"locale\":\"uz\",\"note\":\"O\u2018zbek \u2713\"}]}");
        String answer = "{\"schema_version\":1,\"results\":[],\"consent\":{\"state\":\"none\"},\"erasure\":null,\"server_time\":\"" + T + "\"}";
        Outcome post = call("profile/consents", "POST", body, signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8(answer))));
        Connection connection = post.only();
        check(post.error == null && connection != null, "POST completes on one connection (got " + describe(post.error) + ")");
        if (connection == null) return;
        check((API + "/profile/consents").equals(connection.getURL().toString()), "exact route URL");
        check("POST".equals(connection.sentMethod) && connection.sentDoOutput, "POST with a body");
        check(Arrays.equals(body, connection.uploaded.toByteArray()), "uploaded bytes are exactly the body");
        check(Collections.singletonList("application/json").equals(connection.sentHeaders.get("Content-Type")), "Content-Type exactly application/json");
        check(Collections.singletonList("application/json").equals(connection.sentHeaders.get("Accept")), "Accept application/json");
        for (Map.Entry<String, String> header : signed().entrySet()) {
            check(Collections.singletonList(header.getValue()).equals(connection.sentHeaders.get(header.getKey())),
                    header.getKey() + ": the caller's signed value, sent once");
        }
        Set<String> expected = new HashSet<>(signed().keySet());
        expected.add("Accept");
        expected.add("Content-Type");
        check(connection.sentHeaders.keySet().equals(expected),
                "no other header (no Idempotency-Key, app version, refresh token or cookie): " + connection.sentHeaders.keySet());
        check(!connection.sentFollowRedirects && !connection.sentUseCaches, "no redirects, no caches");
        check(post.reply.status == 200 && answer.equals(post.reply.body) && post.reply.retryAfter == 0 && !post.reply.replayed,
                "the answer text reaches the caller untouched");
        check(post.cleanedUp() && post.streamsClosed() && !connection.handedOut().isEmpty(), "body stream closed and connection disconnected");
    }

    private static void deletionKeyAndReplay() {
        byte[] body = utf8("{\"schema_version\":1,\"confirm\":\"delete_account\"}");
        String answer = "{\"schema_version\":1,\"state\":\"erased\",\"server_time\":\"" + T + "\"}";
        Outcome fresh = call("account/delete", "POST", body, signed("Idempotency-Key", KEY), 4096,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8(answer))));
        Connection connection = fresh.only();
        check(connection != null && Collections.singletonList(KEY).equals(connection.sentHeaders.get("Idempotency-Key")),
                "Idempotency-Key passed through exactly once");
        check(connection != null && (API + "/account/delete").equals(connection.getURL().toString()), "exact delete URL");
        check(fresh.reply != null && !fresh.reply.replayed, "a fresh answer is not a replay");
        Outcome replayed = call("account/delete", "POST", body, signed("Idempotency-Key", KEY), 4096,
                new Answer(200, ScriptedHttps.headers("Idempotent-Replay", "true"), ScriptedHttps.bytes(utf8(answer))));
        check(replayed.reply != null && replayed.reply.replayed && answer.equals(replayed.reply.body), "Idempotent-Replay: true is reported");
        Outcome conflict = call("account/delete", "POST", body, signed("Idempotency-Key", KEY), 4096,
                new Answer(409, ScriptedHttps.headers("Retry-After", "30"), ScriptedHttps.bytes(utf8(
                        "{\"error\":\"idempotency_in_flight\",\"message\":\"m\",\"server_time\":\"" + T + "\"}"))));
        check(conflict.reply != null && conflict.reply.status == 409 && conflict.reply.retryAfter == 30
                && conflict.reply.body.contains("idempotency_in_flight") && conflict.cleanedUp(), "409 keeps its body and Retry-After");
    }

    private static void getHasNoBody() {
        String answer = "{\"schema_version\":1,\"account\":null,\"server_time\":\"" + T + "\"}";
        Outcome get = call("profile/me", "GET", null, signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8(answer))));
        Connection connection = get.only();
        check(connection != null && "GET".equals(connection.sentMethod) && !connection.sentDoOutput && connection.uploaded.size() == 0
                && !connection.sentHeaders.containsKey("Content-Type"), "GET sends no body and no Content-Type");
        check(connection != null && (API + "/profile/me").equals(connection.getURL().toString()), "exact profile/me URL, no query");
        check(get.reply != null && answer.equals(get.reply.body) && get.cleanedUp(), "GET answer returned untouched");
    }

    private static void throttled() {
        for (java.util.function.Function<Connection, TrackedStream> body : Arrays.<java.util.function.Function<Connection, TrackedStream>>asList(
                ScriptedHttps.endless(), ScriptedHttps.failing(), ScriptedHttps.none(), ScriptedHttps.drip(100))) {
            Outcome limited = call("profile/events", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                    new Answer(429, ScriptedHttps.headers("Retry-After", "17"), body));
            check(limited.error == null && limited.reply.status == 429 && limited.reply.retryAfter == 17 && limited.reply.body == null,
                    "429: status and Retry-After reach the caller (got " + describe(limited.error) + ")");
            check(limited.only() != null && limited.only().bodyRequests.get() == 0 && limited.cleanedUp() && limited.elapsedMs < 2000,
                    "429: the body is never requested, one connection, disconnected");
        }
        Outcome bare = call("profile/sync", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(429, ScriptedHttps.headers(), ScriptedHttps.none()));
        check(bare.reply != null && bare.reply.status == 429 && bare.reply.retryAfter == 0, "429 without Retry-After: 0 (the caller waits its minimum)");
        Outcome dated = call("profile/sync", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(429, ScriptedHttps.headers("Retry-After", "Sun, 01 Nov 2026 00:00:00 GMT"), ScriptedHttps.none()));
        check(dated.reply != null && dated.reply.retryAfter == 0, "an HTTP-date Retry-After is not misread as seconds");
    }

    private static void errorBodies() {
        Object[][] table = {
                {403, "{\"error\":\"consent_required\",\"message\":\"m\",\"server_time\":\"" + T + "\",\"reason\":\"no_consent\"}"},
                {422, "{\"error\":\"consent_text_mismatch\",\"message\":\"m\",\"server_time\":\"" + T + "\",\"field\":\"consents[0].text_sha256\"}"},
                {401, "{\"error\":\"invalid_token\",\"message\":\"m\",\"server_time\":\"" + T + "\",\"reason\":\"account_erased\"}"},
                {503, "{\"error\":\"profile_unavailable\",\"message\":\"m\",\"server_time\":\"" + T + "\"}"},
                {404, "<html>not found</html>"},
        };
        for (Object[] row : table) {
            Outcome outcome = call("profile/sync", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                    new Answer((Integer) row[0], ScriptedHttps.headers("Retry-After", "30"), ScriptedHttps.bytes(utf8((String) row[1]))));
            check(outcome.error == null && outcome.reply.status == (Integer) row[0] && row[1].equals(outcome.reply.body)
                    && outcome.reply.retryAfter == 30, row[0] + ": the bounded error body text and Retry-After reach the caller");
            check(outcome.cleanedUp() && outcome.streamsClosed(), row[0] + ": error stream closed, connection disconnected");
        }
    }

    /** An answer without any body keeps its status: classified by status, never as offline. */
    private static void bodylessAnswers() {
        for (int status : new int[]{200, 401, 404, 413, 500, 503}) {
            Outcome bare = call("profile/consents", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                    new Answer(status, ScriptedHttps.headers("Retry-After", "5"), ScriptedHttps.none()));
            check(bare.error == null && bare.reply != null && bare.reply.status == status && bare.reply.body == null
                    && bare.reply.retryAfter == 5 && bare.cleanedUp(), status + " without a body: its status, body null");
        }
    }

    private static void redirectsNotFollowed() {
        Outcome redirect = call("profile/me", "GET", null, signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(302, ScriptedHttps.headers("Location", "https://elsewhere.example.test/v1/profile/me"), ScriptedHttps.bytes(utf8("moved"))));
        check(redirect.error == null && redirect.reply.status == 302 && redirect.opened.size() == 1 && redirect.unscripted.isEmpty(),
                "302 is returned, never followed");
        check(redirect.only() != null && !redirect.only().sentFollowRedirects && redirect.cleanedUp(), "sent with redirects disabled, disconnected");
    }

    private static void boundsAndUtf8() {
        Outcome endless = call("profile/me", "GET", null, signed(), 4096, new Answer(200, ScriptedHttps.headers(), ScriptedHttps.endless()));
        check(endless.rejection() == ProMonetization.Reason.MALFORMED && endless.failure().unreadable() && endless.failure().connected,
                "200 endless body: an unreadable answer after connecting (got " + describe(endless.error) + ")");
        check(maxConsumed(endless) <= 4097 && endless.cleanedUp() && endless.streamsClosed(), "200 endless body: stops one byte past the bound");
        Outcome errorEndless = call("profile/sync", "POST", utf8("{}"), signed(), 4096,
                new Answer(403, ScriptedHttps.headers(), ScriptedHttps.endless()));
        check(errorEndless.rejection() == ProMonetization.Reason.MALFORMED && maxConsumed(errorEndless) <= 4097 && errorEndless.cleanedUp(),
                "an endless error body is bounded too");
        Outcome invalid = call("profile/me", "GET", null, signed(), 4096,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(new byte[]{'{', (byte) 0xC3, '}'})));
        check(invalid.rejection() == ProMonetization.Reason.MALFORMED && invalid.cleanedUp(), "malformed UTF-8 rejected, never replaced");
        char[] fill = new char[4096];
        Arrays.fill(fill, 'a');
        String exact = new String(fill);
        Outcome atLimit = call("profile/me", "GET", null, signed(), 4096, new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8(exact))));
        check(atLimit.reply != null && exact.equals(atLimit.reply.body), "a body of exactly the byte bound is accepted");
        Outcome reset = call("profile/me", "GET", null, signed(), 4096, new Answer(200, ScriptedHttps.headers(), ScriptedHttps.failing()));
        check(reset.failure() != null && reset.failure().connected && !reset.failure().unreadable()
                && reset.failure().getCause() instanceof IOException && reset.cleanedUp(), "a reset while reading: a failure after connecting");
        check(ProTransport.CUSTOMER_MAX_BYTES == 131072, "the customer answer bound is 128 KiB");
    }

    private static void budget() {
        Outcome normal = call("profile/sync", "POST", utf8("{}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8("{}"))));
        check(ProTransport.CUSTOMER_TIMEOUT_MS == 30000 && normal.only() != null
                && normal.only().sentConnectTimeout == ProTransport.CUSTOMER_TIMEOUT_MS && normal.only().sentReadTimeout == ProTransport.CUSTOMER_TIMEOUT_MS,
                "connect and read timeouts are the 30 s total budget");
        Outcome drip = budgeted(300, new Answer(200, ScriptedHttps.headers(), ScriptedHttps.drip(100)));
        check(drip.rejection() == ProMonetization.Reason.TIMED_OUT && drip.failure().connected && !drip.failure().unreadable(),
                "a dripping body is abandoned at the total deadline, after connecting (got " + describe(drip.error) + ")");
        check(drip.elapsedMs < 3000 && drip.cleanedUp(), "dripping body: bounded time and disconnected");
        Outcome blocked = budgeted(200, new Answer(200, ScriptedHttps.headers(), ScriptedHttps.blockedUntilDisconnect(5000)));
        check(blocked.failure() != null && blocked.failure().connected && !"never aborted".equals(blocked.failure().getCause().getMessage())
                && blocked.elapsedMs < 3000 && blocked.cleanedUp(), "the watchdog aborts a blocked read near the deadline");
        Outcome stalled = budgeted(200, new Answer(200, ScriptedHttps.headers(), ScriptedHttps.none(), Fault.STALL));
        check(stalled.failure() != null && stalled.failure().connected && stalled.reply == null && stalled.elapsedMs < 3000
                && !"never aborted".equals(stalled.failure().getCause().getMessage()) && stalled.cleanedUp(),
                "no status before the deadline: the watchdog ends it, a failure after connecting (possibly sent)");
    }

    /** Whether the request may have reached the server: only once the connection was established. */
    private static void connectionStates() {
        Outcome refused = call("profile/consents", "POST", utf8("{\"a\":1}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8("{}")), Fault.CONNECT));
        check(refused.failure() != null && !refused.failure().connected && refused.only() != null
                && refused.only().uploaded.size() == 0 && refused.only().bodyRequests.get() == 0, "a connect failure: never connected, nothing uploaded");
        check(refused.cleanedUp(), "a connect failure still disconnects");
        Outcome reset = call("profile/consents", "POST", utf8("{\"a\":1}"), signed(), ProTransport.CUSTOMER_MAX_BYTES,
                new Answer(200, ScriptedHttps.headers(), ScriptedHttps.bytes(utf8("{}")), Fault.WRITE));
        check(reset.failure() != null && reset.failure().connected && reset.failure().getCause() instanceof IOException
                && !reset.failure().unreadable() && reset.cleanedUp(), "a reset while writing: a failure after connecting");
        ScriptedHttps.script();
        long started = System.nanoTime();
        Exception unopened = null;
        try { ProTransport.customer(API, "profile/me", "GET", null, signed(), ProTransport.CUSTOMER_MAX_BYTES); }
        catch (Exception e) { unopened = e; }
        check(unopened instanceof ProTransport.CustomerFailure && !((ProTransport.CustomerFailure) unopened).connected
                && ScriptedHttps.unscripted().size() == 1 && ScriptedHttps.opened().isEmpty() && (System.nanoTime() - started) < 2_000_000_000L,
                "a connection that cannot even be opened: never connected");
    }

    private static int maxConsumed(Outcome outcome) {
        int max = 0;
        if (outcome.only() == null) return max;
        for (TrackedStream stream : outcome.only().handedOut()) max = Math.max(max, stream.consumed.get());
        return max;
    }

    private static String describe(Exception error) {
        if (error == null) return "no exception";
        Throwable cause = error.getCause();
        return error.getClass().getSimpleName() + (cause == null ? "" : " <- " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }

    private CustomerTransportTests() { }
}
