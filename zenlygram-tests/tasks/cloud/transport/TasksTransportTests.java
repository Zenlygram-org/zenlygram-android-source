package org.telegram.messenger.zenlygram.pro;

import org.telegram.messenger.zenlygram.pro.TasksScriptedHttps.Answer;
import org.telegram.messenger.zenlygram.pro.TasksScriptedHttps.Connection;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksWire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM behavior tests of the real ProTransport.tasks (the raw sealed Tasks attempt behind
 * TasksApi) against the host-only scripted HTTPS boundary: exact uploaded bytes and headers, no body
 * on GET, no redirect following, no caches, <=5 s timeouts and one total deadline (drip + watchdog),
 * byte bound, strict UTF-8, 429 without body read, the untouched response text reaching the strict
 * decoder (duplicate keys survive transport and are rejected there), cleanup on every path. R4: the
 * X-Tasks-Wire header sent exactly once, its echo read on every status (null when absent or not a
 * plain number), and the batch call's own budget (20 s total, 10 s idle; the deadline follows the
 * caller's budget) with the exact batch body. This is not TLS, DNS, backend or device evidence, and
 * no answer here is a real server receipt.
 */
public final class TasksTransportTests {
    private static final String API = "https://tasks.example.test/v1";
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    private static final class Outcome {
        final ProTransport.TasksReply reply;
        final Exception error;
        final long elapsedMs;
        final List<Connection> opened = TasksScriptedHttps.opened();
        final List<String> unscripted = TasksScriptedHttps.unscripted();

        Outcome(ProTransport.TasksReply reply, Exception error, long elapsedMs) {
            this.reply = reply;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        Connection only() { return opened.size() == 1 ? opened.get(0) : null; }

        ProMonetization.Reason rejection() {
            return error instanceof ProMonetization.Rejected ? ((ProMonetization.Rejected) error).reason : null;
        }

        boolean cleanedUp() { return only() != null && only().disconnects.get() >= 1 && unscripted.isEmpty(); }
    }

    public static void main(String[] args) {
        TasksScriptedHttps.install();
        sealedPost();
        maximalPage();
        bodylessErrors();
        getHasNoBody();
        redirectsNotFollowed();
        boundsAndUtf8();
        throttled();
        totalDeadline();
        wireHeaderAndEcho();
        batchPost();
        batchBudget();
        if (!failures.isEmpty()) {
            System.out.println("Tasks transport: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks transport: " + checks + " checks passed");
    }

    private static Outcome call(String pathAndQuery, String method, byte[] body, Map<String, String> headers, int maxBytes, Answer answer) {
        return call(pathAndQuery, method, body, headers, maxBytes, ProTransport.TASKS_TIMEOUT_MS, answer);
    }

    private static Outcome call(String pathAndQuery, String method, byte[] body, Map<String, String> headers, int maxBytes, int timeoutMs,
                                Answer answer) {
        TasksScriptedHttps.script(answer);
        long started = System.nanoTime();
        ProTransport.TasksReply reply = null;
        Exception error = null;
        try { reply = ProTransport.tasks(API, pathAndQuery, method, body, headers, maxBytes, timeoutMs); }
        catch (Exception e) { error = e; }
        return new Outcome(reply, error, (System.nanoTime() - started) / 1_000_000L);
    }

    private static void sealedPost() {
        TasksWire.Mutation mutation = TasksWire.Mutation.seal("01arz3ndektsv4rrffq69g5fav", "11111111-1111-4111-8111-111111111111",
                TasksWire.Operation.listCreate("22222222-2222-4222-8222-222222222222", "Ishlar / O\u2018zbek", 0, null));
        Map<String, String> headers = TasksScriptedHttps.headers("Authorization", "Bearer fixture", "X-Device-Id", "d",
                "X-Device-Nonce", "n", "X-Device-Ts", "1", "X-Device-Sig", "s", "Idempotency-Key", mutation.mutationId);
        String duplicate = "{\"schema_version\":1,\"schema_version\":1,\"receipt\":{},\"server_time\":\"2026-10-24T00:00:00Z\"}";
        Outcome post = call("tasks/mutations", "POST", mutation.body(), headers, TasksApi.MAX_MUTATION_RESPONSE_BYTES,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.bytes(duplicate.getBytes(StandardCharsets.UTF_8))));
        Connection connection = post.only();
        check(post.error == null && connection != null, "POST completes on one connection");
        if (connection == null) return;
        check(Arrays.equals(connection.uploaded.toByteArray(), mutation.body()), "uploaded bytes are exactly the sealed body");
        check(Collections.singletonList("application/json").equals(connection.sentHeaders.get("Content-Type")), "Content-Type exactly application/json");
        check(Collections.singletonList(mutation.mutationId).equals(connection.sentHeaders.get("Idempotency-Key")), "Idempotency-Key is the mutation id");
        check(Collections.singletonList("Bearer fixture").equals(connection.sentHeaders.get("Authorization")), "caller's fresh auth header sent once");
        check(!connection.sentHeaders.containsKey("X-App-Version") && !connection.sentHeaders.containsKey("X-Refresh-Token")
                && !connection.sentHeaders.containsKey("Cookie"), "no metadata, refresh-token or cookie headers");
        check("POST".equals(connection.sentMethod) && !connection.sentFollowRedirects && !connection.sentUseCaches, "POST, no redirects, no caches");
        check(connection.sentConnectTimeout == ProTransport.TASKS_TIMEOUT_MS && connection.sentReadTimeout == ProTransport.TASKS_TIMEOUT_MS
                && ProTransport.TASKS_TIMEOUT_MS <= 5000, "connect/read timeouts within the 5 s budget");
        check((API + "/tasks/mutations").equals(connection.getURL().toString()), "exact route URL, empty query");
        check(duplicate.equals(post.reply.body), "response text reaches the caller untouched (duplicate key preserved)");
        check(rejects(() -> TasksWire.decodeMutationResponse(post.reply.body, mutation)), "...and the strict decoder rejects it");
        check(post.cleanedUp() && connection.bodyClosed, "connection disconnected and body closed");
    }

    private static void getHasNoBody() {
        Outcome get = call("tasks/snapshot?cursor=a%2Bb&limit=50", "GET", null, TasksScriptedHttps.headers("Authorization", "Bearer f"), 1024,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.bytes("{}".getBytes(StandardCharsets.UTF_8))));
        Connection connection = get.only();
        check(connection != null && "GET".equals(connection.sentMethod) && !connection.sentDoOutput && connection.uploaded.size() == 0
                && !connection.sentHeaders.containsKey("Content-Type"), "GET sends no body and no Content-Type");
        check(connection != null && (API + "/tasks/snapshot?cursor=a%2Bb&limit=50").equals(connection.getURL().toString()), "GET query preserved");
        Outcome error = call("tasks/changes?limit=1", "GET", null, Collections.<String, String>emptyMap(), 1024,
                new Answer(403, Collections.<String, String>emptyMap(), TasksScriptedHttps.bytes(
                        "{\"error\":\"tasks_account_not_linked\",\"message\":\"m\",\"server_time\":\"2026-10-24T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8))));
        check(error.error == null && error.reply.status == 403 && error.reply.body.contains("tasks_account_not_linked") && error.cleanedUp(),
                "error status returns its bounded body text");
    }

    private static void redirectsNotFollowed() {
        Outcome redirect = call("tasks/changes?limit=1", "GET", null, Collections.<String, String>emptyMap(), 1024,
                new Answer(302, TasksScriptedHttps.headers("Location", "https://evil.example.test/v1/tasks/changes"),
                        TasksScriptedHttps.bytes(new byte[0])));
        check(redirect.error == null && redirect.reply.status == 302 && redirect.opened.size() == 1 && redirect.unscripted.isEmpty(),
                "302 is returned, never followed");
        check(TasksApi.classify(302, null, 0).kind == TasksApi.Failure.Kind.PROTOCOL, "...and classified as a protocol failure");
    }

    private static void boundsAndUtf8() {
        Outcome endless = call("tasks/snapshot?limit=1", "GET", null, Collections.<String, String>emptyMap(), 4096,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.endless()));
        check(endless.rejection() == ProMonetization.Reason.MALFORMED && endless.only() != null && endless.only().consumed.get() <= 4097
                && endless.cleanedUp(), "endless body stops at the byte bound");
        Outcome invalid = call("tasks/snapshot?limit=1", "GET", null, Collections.<String, String>emptyMap(), 4096,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.bytes(new byte[]{'{', (byte) 0xC3, '}'})));
        check(invalid.rejection() == ProMonetization.Reason.MALFORMED && invalid.cleanedUp(), "malformed UTF-8 rejected, never replaced");
    }

    /** An astral-escaped maximal page passes its limit-derived raw bound intact and decodes; beyond the bound is refused while reading. */
    private static void maximalPage() {
        String page = org.telegram.messenger.zenlygram.tasks.cloud.TasksWireTests.maximalSnapshotPage(100);
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        int bound = TasksWire.maxPageBytes(100);
        check(bytes.length <= bound && bound > ProMonetization.MAX_RESPONSE_BYTES, "page bound is the schema-derived one, not the small Pro bound");
        Outcome full = call("tasks/snapshot?limit=100", "GET", null, Collections.<String, String>emptyMap(), bound,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.bytes(bytes)));
        check(full.error == null && full.reply != null && page.equals(full.reply.body) && full.cleanedUp(), "maximal page read intact");
        check(full.reply != null && TasksWire.decodeSnapshotResponse(full.reply.body, 100).items.size() == 100, "maximal page decodes after transport");
        Outcome over = call("tasks/snapshot?limit=100", "GET", null, Collections.<String, String>emptyMap(), bound,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.endless()));
        check(over.rejection() == ProMonetization.Reason.MALFORMED && over.only() != null
                && over.only().consumed.get() <= bound + 1 && over.cleanedUp(), "a page beyond the bound is refused while reading");
        int small = TasksWire.maxPageBytes(1);
        Outcome limited = call("tasks/snapshot?limit=1", "GET", null, Collections.<String, String>emptyMap(), small,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.bytes(bytes)));
        check(limited.rejection() == ProMonetization.Reason.MALFORMED && limited.only() != null && limited.only().consumed.get() <= small + 1
                && limited.cleanedUp(), "a limit-1 request never reads the 100-item budget");
    }

    /** An error status without any body keeps its status (never mistaken for an offline failure). */
    private static void bodylessErrors() {
        for (int status : new int[]{401, 404, 413, 500, 503}) {
            Outcome bare = call("tasks/changes?limit=1", "GET", null, Collections.<String, String>emptyMap(), 1024,
                    new Answer(status, TasksScriptedHttps.headers("Retry-After", "5"), null));
            check(bare.error == null && bare.reply != null && bare.reply.status == status && bare.reply.body == null
                    && bare.reply.retryAfter == 5 && bare.cleanedUp(), status + " without a body returns its status");
        }
    }

    private static void throttled() {
        Outcome limited = call("tasks/mutations", "POST", "{}".getBytes(StandardCharsets.UTF_8), Collections.<String, String>emptyMap(), 1024,
                new Answer(429, TasksScriptedHttps.headers("Retry-After", "17"), TasksScriptedHttps.endless()));
        check(limited.error == null && limited.reply.status == 429 && limited.reply.retryAfter == 17 && limited.reply.body == null
                && limited.only() != null && limited.only().consumed.get() == 0 && limited.cleanedUp(), "429 returns Retry-After without reading the body");
    }

    private static void totalDeadline() {
        Outcome drip = call("tasks/snapshot?limit=1", "GET", null, Collections.<String, String>emptyMap(), 1 << 20,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.drip(40)));
        check(drip.rejection() == ProMonetization.Reason.TIMED_OUT && drip.elapsedMs < ProTransport.TASKS_TIMEOUT_MS + 1500 && drip.cleanedUp(),
                "a slow drip cannot outlast the total budget");
        Outcome blocked = call("tasks/snapshot?limit=1", "GET", null, Collections.<String, String>emptyMap(), 1 << 20,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.blockedUntilDisconnect(15000)));
        check(blocked.error != null && blocked.rejection() == null && blocked.elapsedMs >= ProTransport.TASKS_TIMEOUT_MS - 200
                && blocked.elapsedMs < ProTransport.TASKS_TIMEOUT_MS + 2000 && blocked.cleanedUp(), "watchdog aborts a blocked read at the deadline");
    }

    // ---- R4 ------------------------------------------------------------------------------------

    private static Answer answer(int status, Map<String, String> headers, String body) {
        return new Answer(status, headers, body == null ? null : TasksScriptedHttps.bytes(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void wireHeaderAndEcho() {
        Map<String, String> headers = TasksScriptedHttps.headers("Authorization", "Bearer f", "X-Tasks-Wire", "4");
        Outcome get = call("tasks/changes?limit=1", "GET", null, headers, 1024,
                answer(200, TasksScriptedHttps.headers("X-Tasks-Wire", "4"), "{}"));
        Connection connection = get.only();
        check(connection != null && Collections.singletonList("4").equals(connection.sentHeaders.get("X-Tasks-Wire")),
                "the caller's X-Tasks-Wire: 4 goes out exactly once");
        check(get.error == null && Integer.valueOf(4).equals(get.reply.serverWire) && get.cleanedUp(), "200: the echo is surfaced");
        Outcome conflict = call("tasks/mutations", "POST", "{}".getBytes(StandardCharsets.UTF_8), headers, 1024,
                answer(409, TasksScriptedHttps.headers("X-Tasks-Wire", "4"), "{\"error\":\"tasks_conflict\"}"));
        check(conflict.reply != null && conflict.reply.status == 409 && Integer.valueOf(4).equals(conflict.reply.serverWire), "an error status carries its echo");
        Outcome limited = call("tasks/mutations", "POST", "{}".getBytes(StandardCharsets.UTF_8), headers, 1024,
                new Answer(429, TasksScriptedHttps.headers("X-Tasks-Wire", "4", "Retry-After", "3"), TasksScriptedHttps.endless()));
        check(limited.reply != null && Integer.valueOf(4).equals(limited.reply.serverWire) && limited.only().consumed.get() == 0, "429: echo read, body still unread");
        Outcome bare = call("tasks/snapshot?limit=1", "GET", null, headers, 1024, new Answer(503, TasksScriptedHttps.headers("X-Tasks-Wire", "4"), null));
        check(bare.reply != null && bare.reply.body == null && Integer.valueOf(4).equals(bare.reply.serverWire), "a bodyless error carries its echo");
        for (String value : new String[]{null, "", "04", "4, 4", "four", "-4", "4 "}) {
            Map<String, String> echo = value == null ? Collections.<String, String>emptyMap() : TasksScriptedHttps.headers("X-Tasks-Wire", value);
            Outcome outcome = call("tasks/changes?limit=1", "GET", null, headers, 1024, answer(200, echo, "{}"));
            check(outcome.reply != null && outcome.reply.serverWire == null, "echo " + (value == null ? "absent" : "'" + value + "'") + ": null");
        }
        Outcome three = call("tasks/changes?limit=1", "GET", null, headers, 1024, answer(200, TasksScriptedHttps.headers("X-Tasks-Wire", "3"), "{}"));
        check(three.reply != null && Integer.valueOf(3).equals(three.reply.serverWire), "another number is reported as such");
    }

    static TasksWire.Batch batch() {
        TasksWire.Mutation create = TasksWire.Mutation.seal("01arz3ndektsv4rrffq69g5fav", "11111111-1111-4111-8111-111111111111",
                TasksWire.Operation.listCreate("22222222-2222-4222-8222-222222222222", "Ishlar / O‘zbek", 0, null));
        return TasksWire.Batch.of("99999999-9999-4999-9999-999999999999",
                Collections.singletonList(new TasksWire.Batch.Item(create, TasksWire.Origin.MIGRATION)));
    }

    private static void batchPost() {
        TasksWire.Batch batch = batch();
        Map<String, String> headers = TasksScriptedHttps.headers("Authorization", "Bearer fixture", "Idempotency-Key", batch.batchId, "X-Tasks-Wire", "4");
        String answer = "{\"schema_version\":1,\"batch_id\":\"" + batch.batchId + "\",\"results\":[{\"mutation_id\":\"11111111-1111-4111-8111-111111111111\","
                + "\"status\":null,\"skipped\":true}],\"retry_after\":null,\"server_time\":\"2026-10-24T00:00:00Z\"}";
        Outcome post = call("tasks/mutations/batch", "POST", batch.body(), headers, TasksApi.MAX_BATCH_RESPONSE_BYTES, ProTransport.TASKS_BATCH_TIMEOUT_MS,
                answer(200, TasksScriptedHttps.headers("X-Tasks-Wire", "4"), answer));
        Connection connection = post.only();
        check(post.error == null && connection != null && Arrays.equals(connection.uploaded.toByteArray(), batch.body()),
                "the batch body is uploaded byte for byte");
        check(connection != null && (API + "/tasks/mutations/batch").equals(connection.getURL().toString())
                && Collections.singletonList("application/json").equals(connection.sentHeaders.get("Content-Type"))
                && Collections.singletonList(batch.batchId).equals(connection.sentHeaders.get("Idempotency-Key")), "route, media type, Idempotency-Key");
        check(post.reply != null && TasksWire.decodeBatchResponse(post.reply.body, batch, post.reply.serverWire).results.get(0).kind
                == TasksWire.BatchItemResult.Kind.SKIPPED && post.cleanedUp(), "the batch answer reaches the strict decoder untouched");
        Outcome over = call("tasks/mutations/batch", "POST", batch.body(), headers, TasksApi.MAX_BATCH_RESPONSE_BYTES, ProTransport.TASKS_BATCH_TIMEOUT_MS,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.endless()));
        check(over.rejection() == ProMonetization.Reason.MALFORMED && over.only().consumed.get() <= TasksApi.MAX_BATCH_RESPONSE_BYTES + 1,
                "a batch answer beyond 262144 bytes is refused while reading");
    }

    private static void batchBudget() {
        byte[] body = batch().body();
        Outcome quick = call("tasks/mutations/batch", "POST", body, Collections.<String, String>emptyMap(), 1024, ProTransport.TASKS_BATCH_TIMEOUT_MS,
                answer(200, Collections.<String, String>emptyMap(), "{}"));
        check(quick.only() != null && quick.only().sentConnectTimeout == ProTransport.TASKS_IDLE_TIMEOUT_MS
                && quick.only().sentReadTimeout == ProTransport.TASKS_IDLE_TIMEOUT_MS && ProTransport.TASKS_IDLE_TIMEOUT_MS == 10000
                && ProTransport.TASKS_BATCH_TIMEOUT_MS == 20000, "the batch call: 10 s idle inside its 20 s budget");
        // A complete answer that takes longer than the R3 routes' 5 s still arrives within the batch budget.
        Outcome slow = call("tasks/mutations/batch", "POST", body, Collections.<String, String>emptyMap(), 1024, ProTransport.TASKS_BATCH_TIMEOUT_MS,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.delayed(ProTransport.TASKS_TIMEOUT_MS + 1500,
                        "{\"late\":true}".getBytes(StandardCharsets.UTF_8))));
        check(slow.error == null && slow.reply != null && "{\"late\":true}".equals(slow.reply.body) && slow.elapsedMs >= ProTransport.TASKS_TIMEOUT_MS + 1400
                && slow.cleanedUp(), "an answer after 6.5 s completes under the batch budget (an R3 route aborts at 5 s)");
        // The watchdog and the body deadline follow the budget the caller passes (shown fast with 1.2 s).
        Outcome blocked = call("tasks/mutations/batch", "POST", body, Collections.<String, String>emptyMap(), 1 << 20, 1200,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.blockedUntilDisconnect(15000)));
        check(blocked.error != null && blocked.elapsedMs >= 1000 && blocked.elapsedMs < 3000 && blocked.cleanedUp(),
                "the watchdog aborts at the caller's budget");
        Outcome drip = call("tasks/mutations/batch", "POST", body, Collections.<String, String>emptyMap(), 1 << 20, 1200,
                new Answer(200, Collections.<String, String>emptyMap(), TasksScriptedHttps.drip(40)));
        check(drip.rejection() == ProMonetization.Reason.TIMED_OUT && drip.elapsedMs < 3000 && drip.cleanedUp(), "the body deadline follows it too");
        boolean refused = false;
        try { ProTransport.tasks(API, "tasks/changes?limit=1", "GET", null, Collections.<String, String>emptyMap(), 1024, 0); }
        catch (IllegalArgumentException expected) { refused = true; }
        catch (Exception other) { refused = false; }
        check(refused, "a non-positive budget is refused before connecting");
    }

    private static boolean rejects(Runnable action) {
        try { action.run(); return false; }
        catch (TasksWire.Invalid expected) { return true; }
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }
}
