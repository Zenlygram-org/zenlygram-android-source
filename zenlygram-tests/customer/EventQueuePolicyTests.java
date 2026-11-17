package org.telegram.messenger.zenlygram.customer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-JVM checks of the queue's bounds, batches and answers (contract §3.1, §6.5; design 50 §11.5–§11.6): the
 * constants; pruning by age, count (5000) and bytes (2 MiB), oldest first, over a long random insert stream; batches of
 * the oldest run of one context, ≤ 200 events (and the per-process limit) and ≤ 256 KiB measured on the real envelope;
 * the split on a context change; events outside the {@code sent_at} window are deleted, never sent; 413 halving; the
 * flush interval bounds; every answer's effect; and, read from the device sources, that a batch built before a
 * withdrawal is never sent after it (contract §6.7). Run with run-tests.sh, which passes the Android repository root.
 */
public final class EventQueuePolicyTests {
    private static int checks;
    private static final long NOW = 1793527200L; // 2026-11-01T10:00:00Z

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: EventQueuePolicyTests <android repository root>");
        constants();
        bounds();
        boundedOverARandomStream();
        selection();
        contextSplits();
        windowAndOrphans();
        halving();
        flushInterval();
        answers();
        sendTimeConsent(Paths.get(args[0]).resolve("TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer"));
        System.out.println("Event queue policy: " + checks + " checks passed");
    }

    private static void constants() {
        check(EventQueuePolicy.MAX_EVENTS == 5000 && EventQueuePolicy.MAX_BYTES == 2L * 1024 * 1024 && EventQueuePolicy.MAX_AGE_SECONDS == 604800,
                "5000 events, 2 MiB, 7 days");
        check(EventQueuePolicy.MAX_BATCH_EVENTS == 200 && EventQueuePolicy.MAX_BATCH_BYTES == 262144, "≤ 200 events and ≤ 256 KiB per batch");
        check(EventQueuePolicy.FLUSH_THRESHOLD == 50 && EventQueuePolicy.PAUSE_SECONDS == 86400, "flush at 50, pause 24 h");
        check(EventQueuePolicy.DEFAULT_FLUSH_INTERVAL_SECONDS == 300 && EventQueuePolicy.MIN_FLUSH_INTERVAL_SECONDS == 60
                && EventQueuePolicy.MAX_FLUSH_INTERVAL_SECONDS == 3600, "flush_interval_s 300, 60..3600");
        check(EventQueuePolicy.oldestKept(NOW) == NOW - 604800, "older than 7 days goes");
    }

    private static void bounds() {
        check(!EventQueuePolicy.overBounds(5000, EventQueuePolicy.MAX_BYTES), "exactly at both bounds is fine");
        check(EventQueuePolicy.overBounds(5001, 10), "one event too many");
        check(EventQueuePolicy.overBounds(1, EventQueuePolicy.MAX_BYTES + 1), "one byte too many");
        EventQueuePolicy.Trim trim = new EventQueuePolicy.Trim(5003, 5003 * 100L);
        int dropped = 0;
        while (trim.over()) {
            trim.drop(100);
            dropped++;
        }
        check(dropped == 3 && trim.count() == 5000, "count: the three oldest go");
        trim = new EventQueuePolicy.Trim(1100, 1100 * 2048L);
        dropped = 0;
        while (trim.over()) {
            trim.drop(2048);
            dropped++;
        }
        check(trim.bytes() <= EventQueuePolicy.MAX_BYTES && trim.bytes() + 2048 > EventQueuePolicy.MAX_BYTES && dropped == 76,
                "bytes: just enough of the oldest go (" + dropped + ")");
    }

    /** A model queue fed 30 000 random events over 20 days and pruned the store's way never leaves a bound, oldest first. */
    private static void boundedOverARandomStream() {
        Random random = new Random(7);
        ArrayDeque<long[]> queue = new ArrayDeque<>(); // {rowId, ts, bytes}
        long bytes = 0;
        long now = NOW - 20L * 86400;
        long rowId = 0;
        for (int i = 0; i < 30000; i++) {
            now += random.nextInt(i % 1000 < 900 ? 10 : 3600);
            int size = 60 + random.nextInt(random.nextInt(10) == 0 ? 1988 : 400);
            queue.addLast(new long[]{++rowId, now, size});
            bytes += size;
            // The store: drop by age, then the oldest while over a bound.
            while (!queue.isEmpty() && queue.peekFirst()[1] < EventQueuePolicy.oldestKept(now)) bytes -= queue.pollFirst()[2];
            EventQueuePolicy.Trim trim = new EventQueuePolicy.Trim(queue.size(), bytes);
            long cut = -1;
            for (long[] row : queue) {
                if (!trim.over()) break;
                cut = row[0];
                trim.drop((int) row[2]);
            }
            while (!queue.isEmpty() && queue.peekFirst()[0] <= cut) bytes -= queue.pollFirst()[2];
            check(queue.size() <= EventQueuePolicy.MAX_EVENTS && bytes <= EventQueuePolicy.MAX_BYTES, "within bounds after insert " + i);
            check(queue.peekLast()[0] == rowId, "the newest event is always kept");
            check(queue.peekFirst()[1] >= EventQueuePolicy.oldestKept(now), "nothing older than 7 days");
        }
    }

    private static void selection() {
        final Map<String, String> contexts = contexts();
        List<EventQueuePolicy.Row> rows = new ArrayList<>();
        for (int i = 0; i < 450; i++) rows.add(new EventQueuePolicy.Row(i + 1, NOW - 450 + i, "A", 300));
        EventQueuePolicy.Selector selector = select(rows, 200, NOW, contexts);
        check(selector.selected().size() == 200 && selector.selected().get(0).rowId == 1 && selector.selected().get(199).rowId == 200,
                "the 200 oldest");
        check(selector.contextKey().equals("A") && selector.expired().isEmpty(), "under their context");
        check(select(rows, 37, NOW, contexts).selected().size() == 37, "the per-process limit");
        check(select(rows, 0, NOW, contexts).selected().size() == 1 && select(rows, 999, NOW, contexts).selected().size() == 200,
                "the limit is clamped to 1..200");
        // 256 KiB: big events stop the batch before 200.
        List<EventQueuePolicy.Row> big = new ArrayList<>();
        for (int i = 0; i < 200; i++) big.add(new EventQueuePolicy.Row(i + 1, NOW, "A", 2048));
        EventQueuePolicy.Selector bySize = select(big, 200, NOW, contexts);
        int fits = (int) ((EventQueuePolicy.MAX_BATCH_BYTES - CustomerEvents.envelopeBytes(contexts.get("A").length()) + 1) / 2049);
        check(bySize.selected().size() == fits && fits < 200, "≤ 256 KiB: " + fits + " events of 2 KiB");
        check(bySize.envelopeBytes() <= EventQueuePolicy.MAX_BATCH_BYTES && bySize.envelopeBytes() + 2049 > EventQueuePolicy.MAX_BATCH_BYTES,
                "as many as fit");
        // The size is that of the real envelope.
        List<String> jsons = new ArrayList<>();
        for (EventQueuePolicy.Row row : bySize.selected()) jsons.add(event(row.bytes));
        String body = CustomerEvents.envelope("00000000-0000-4000-8000-000000000001", NOW, contexts.get("A"), jsons);
        check(CustomerEvents.utf8Length(body) == bySize.envelopeBytes(), "the selector's size is the envelope's exact size");
        check(select(Collections.<EventQueuePolicy.Row>emptyList(), 200, NOW, contexts).contextKey() == null, "an empty queue: no batch");
    }

    private static void contextSplits() {
        Map<String, String> contexts = contexts();
        List<EventQueuePolicy.Row> rows = new ArrayList<>();
        long id = 0;
        for (int i = 0; i < 250; i++) rows.add(new EventQueuePolicy.Row(++id, NOW - 1000, "A", 200));
        for (int i = 0; i < 10; i++) rows.add(new EventQueuePolicy.Row(++id, NOW - 900, "B", 200));
        for (int i = 0; i < 5; i++) rows.add(new EventQueuePolicy.Row(++id, NOW - 800, "A", 200));
        List<String> batches = new ArrayList<>();
        int index = 0;
        while (index < rows.size()) {
            EventQueuePolicy.Selector selector = select(rows.subList(index, rows.size()), 200, NOW, contexts);
            for (EventQueuePolicy.Row row : selector.selected()) check(row.contextKey.equals(selector.contextKey()), "a batch never mixes contexts");
            batches.add(selector.contextKey() + selector.selected().size());
            index += selector.selected().size();
        }
        check(batches.toString().equals("[A200, A50, B10, A5]"), "split where the context changes: " + batches);
    }

    private static void windowAndOrphans() {
        Map<String, String> contexts = contexts();
        List<EventQueuePolicy.Row> rows = new ArrayList<>();
        rows.add(new EventQueuePolicy.Row(1, NOW - 604801, "A", 100));  // 7 days and 1 s before sent_at: stale
        rows.add(new EventQueuePolicy.Row(2, NOW - 10, "gone", 100));    // its context is missing
        rows.add(new EventQueuePolicy.Row(3, NOW - 604800, "A", 100));  // exactly 7 days: sendable
        rows.add(new EventQueuePolicy.Row(4, NOW + 301, "A", 100));     // future
        rows.add(new EventQueuePolicy.Row(5, NOW + 300, "A", 100));     // exactly 300 s ahead: sendable
        rows.add(new EventQueuePolicy.Row(6, NOW, "B", 100));           // another context: next batch
        rows.add(new EventQueuePolicy.Row(7, NOW - 999999, "A", 100));  // never reached
        EventQueuePolicy.Selector selector = select(rows, 200, NOW, contexts);
        check(ids(selector.selected()).equals("[3, 5]"), "the sendable run: " + ids(selector.selected()));
        check(ids(selector.expired()).equals("[1, 2, 4]"), "stale, orphaned and future events are deleted, never sent: " + ids(selector.expired()));
    }

    private static void halving() {
        int limit = 200;
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            limit = EventQueuePolicy.halved(limit, 200);
            seen.add(limit);
        }
        check(seen.toString().equals("[100, 50, 25, 12, 6, 3, 1, 1, 1, 1]"), "each 413 halves, min 1: " + seen);
        check(EventQueuePolicy.halved(200, 37) == 18, "half of the batch actually sent");
        check(EventQueuePolicy.halved(0, 0) == 1 && EventQueuePolicy.halved(-5, 9) == 1, "never below 1");
    }

    private static void flushInterval() {
        check(EventQueuePolicy.flushInterval(0) == 300 && EventQueuePolicy.flushInterval(-1) == 300, "absent: the default");
        check(EventQueuePolicy.flushInterval(30) == 60 && EventQueuePolicy.flushInterval(4000) == 3600, "clamped to 60..3600");
        check(EventQueuePolicy.flushInterval(120) == 120 && EventQueuePolicy.flushInterval(3600) == 3600, "kept inside");
    }

    private static void answers() {
        EventQueuePolicy.Effect ok = effect(200, null, false, 0);
        check(ok.deleteSent && ok.pauseUntilS == 0 && ok.flushIntervalS == 0 && ok.errorCode == null && !ok.backoff && ok.nextLimit == 150,
                "200: delete exactly the sent events, keep the limit");
        EventQueuePolicy.Effect interval = effect(200, null, false, 30);
        check(interval.deleteSent && interval.flushIntervalS == 60, "200 with flush_interval_s: stored, clamped");
        EventQueuePolicy.Effect paused = effect(200, null, true, 900);
        check(paused.deleteSent && paused.pauseUntilS == NOW + 86400 && paused.flushIntervalS == 900, "ingest paused: delete and pause 24 h");
        EventQueuePolicy.Effect tooLarge = effect(413, "payload_too_large", false, 0);
        check(!tooLarge.deleteSent && tooLarge.nextLimit == 40 && !tooLarge.backoff, "413: keep, halve the batch actually sent (80 → 40)");
        EventQueuePolicy.Effect invalid = effect(422, "invalid_request", false, 0);
        check(invalid.deleteSent && "invalid_request".equals(invalid.errorCode) && !invalid.backoff,
                "422: drop the batch, count error.occurred{events_upload, invalid_request}");
        EventQueuePolicy.Effect schema = effect(422, "unsupported_schema_version", false, 0);
        check(schema.deleteSent && "unsupported_schema_version".equals(schema.errorCode), "the server's code is kept");
        EventQueuePolicy.Effect bare = effect(400, null, false, 0);
        check(bare.deleteSent && "http_400".equals(bare.errorCode), "a 4xx without code: http_<status>");
        EventQueuePolicy.Effect forbidden = effect(403, "forbidden", false, 0);
        check(forbidden.deleteSent && "forbidden".equals(forbidden.errorCode), "another 403 drops the payload");
        EventQueuePolicy.Effect local = EventQueuePolicy.afterAnswer(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 0, "invalid_request", null, false),
                false, 0, 200, 10, NOW);
        check(local.deleteSent && "invalid_request".equals(local.errorCode), "a body refused locally is dropped too");
        for (int status : new int[]{429, 500, 502, 503, 404}) {
            EventQueuePolicy.Effect retry = effect(status, status == 429 ? "rate_limited" : "profile_unavailable", false, 0);
            check(!retry.deleteSent && retry.backoff && retry.errorCode == null && retry.nextLimit == 150, status + ": keep and back off");
        }
        EventQueuePolicy.Effect offline = EventQueuePolicy.afterAnswer(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 0, "connection_failed", null, true),
                false, 0, 150, 80, NOW);
        check(!offline.deleteSent && offline.backoff, "offline: keep and back off");
        EventQueuePolicy.Effect inFlight = effect(409, "idempotency_in_flight", false, 0);
        check(!inFlight.deleteSent && inFlight.backoff, "409 in flight: keep, retry soon");
        String[][] keep = {{"401", "invalid_token", null}, {"401", "device_revoked", "account_erased"}, {"403", "consent_required", "withdrawn"},
                {"403", "profile_account_not_linked", null}, {"0", "session_changed", null}};
        for (String[] answer : keep) {
            EventQueuePolicy.Effect effect = EventQueuePolicy.afterAnswer(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, Integer.parseInt(answer[0]),
                    answer[1], answer[2], false), false, 0, 150, 80, NOW);
            check(!effect.deleteSent && !effect.backoff && effect.errorCode == null && effect.pauseUntilS == 0,
                    answer[0] + " " + answer[1] + ": the events stay for the coordinator");
        }
        check(effect(200, null, false, 0).nextLimit == 150 && EventQueuePolicy.afterAnswer(
                CustomerOutcome.of(CustomerOutcome.Route.EVENTS, 200, null, null, false), false, 0, 999, 1, NOW).nextLimit == 200,
                "the limit is kept within 1..200");
    }

    /**
     * Contract §6.7 ("stop events traffic at once"): a batch built before a withdrawal is never sent after it. Both steps
     * are device code (ZenlyEventStoreRuntimeTest drives them on a device), so their source is read here: the store asks
     * its gate once per batch and returns before the batch is built when it refuses; the uploader hands that same gate to
     * the store and asks it again, together with its account's slot (forgotten by every purge), before it takes a budget
     * token or posts, and then reports the batch as not allowed without finishing it.
     */
    private static void sendTimeConsent(Path customer) throws Exception {
        String prepare = code(customer.resolve("ZenlyEventStore.java"), "private Prepared prepare(");
        Matcher asked = Pattern.compile("boolean (\\w+) = permitted\\(allowed\\);").matcher(prepare);
        check(asked.find(), "prepare asks its gate");
        check(prepare.indexOf("permitted(") == prepare.lastIndexOf("permitted("), "prepare asks it once, for the aggregates and the batch alike");
        int refused = prepare.indexOf("if (!" + asked.group(1) + ") return new Prepared(null, ");
        check(refused > asked.end() && refused < prepare.indexOf("new EventQueuePolicy.Selector(") && refused < prepare.indexOf("new Batch("),
                "a refused gate returns no batch before one is selected or built");

        Path uploader = customer.resolve("ZenlyEventUploader.java");
        String start = code(uploader, "private static void start(");
        Matcher gate = Pattern.compile("ZenlyEventStore\\.Allowed (\\w+) = \\(\\) -> ZenlyStats\\.allowedUser\\(tg\\);").matcher(start);
        check(gate.find(), "start names the recording rule of the account's user");
        check(start.contains(", " + gate.group(1) + ", prepared -> {") && start.contains("prepared, " + gate.group(1) + ", done);"),
                "the store and the send step ask that same rule");
        String send = code(uploader, "private static void send(");
        int recheck = send.indexOf("if (STATES.get(account) != state || !allowed.allowed()) {");
        int budget = send.indexOf("INSTALL_BUDGET.tryTake("), post = send.indexOf("api.postEvents(");
        check(recheck >= 0 && budget > recheck && post > budget && recheck < send.indexOf("prepared.batch == null"),
                "send asks the slot and the rule before anything else, so before a budget token and the POST");
        String refusal = send.substring(recheck, send.indexOf("return;", recheck));
        check(refusal.contains("state.inFlight = false;") && refusal.contains("Skip.NOT_ALLOWED") && !refusal.contains("finish("),
                "a refused send frees the slot, reports not allowed and leaves the events to the purge");
        check(send.indexOf("api.postEvents(") == send.lastIndexOf("api.postEvents("), "send posts in one place only");
    }

    /**
     * The code of the method whose declaration begins with {@code declaration} (exactly one in the file), through its
     * closing brace, without comments and with every run of whitespace as one space.
     */
    private static String code(Path file, String declaration) throws Exception {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int begin = source.indexOf(declaration);
        check(begin >= 0 && source.indexOf(declaration, begin + 1) < 0, file.getFileName() + ": exactly one " + declaration);
        StringBuilder code = new StringBuilder();
        int depth = 0;
        for (int i = begin; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && (source.charAt(i + 1) == '/' || source.charAt(i + 1) == '*')) {
                boolean line = source.charAt(i + 1) == '/';
                int end = line ? source.indexOf('\n', i) : source.indexOf("*/", i + 2) + 1;
                check(end > i, file.getFileName() + ": a closed comment");
                i = end;
                code.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != c) end += source.charAt(end) == '\\' ? 2 : 1;
                check(end < source.length(), file.getFileName() + ": a closed literal");
                code.append(source, i, end + 1);
                i = end;
                continue;
            }
            code.append(c);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.toString().replaceAll("\\s+", " ");
            }
        }
        throw new AssertionError(file.getFileName() + ": " + declaration + " is not closed");
    }

    // ---- helpers ----

    private static EventQueuePolicy.Effect effect(int status, String code, boolean paused, long interval) {
        return EventQueuePolicy.afterAnswer(CustomerOutcome.of(CustomerOutcome.Route.EVENTS, status, code, null, false), paused, interval, 150, 80, NOW);
    }

    private static Map<String, String> contexts() {
        Map<String, String> contexts = new HashMap<>();
        contexts.put("A", CustomerEvents.Context.of(false, "14", "12.1.0", "5678", "Google Pixel 8", "ru", "Asia/Tashkent", "play").json());
        contexts.put("B", CustomerEvents.Context.of(true, "14", "12.1.0", "5678", "Google Pixel 8", "ru", "Asia/Tashkent", "play").json());
        return contexts;
    }

    private static EventQueuePolicy.Selector select(List<EventQueuePolicy.Row> rows, int limit, long sentAt, final Map<String, String> contexts) {
        EventQueuePolicy.Selector selector = new EventQueuePolicy.Selector(limit, sentAt,
                key -> contexts.containsKey(key) ? CustomerEvents.utf8Length(contexts.get(key)) : -1);
        for (EventQueuePolicy.Row row : rows) {
            if (!selector.offer(row)) break;
        }
        return selector;
    }

    /** A JSON object of exactly {@code bytes} bytes. */
    private static String event(int bytes) {
        String prefix = "{\"p\":\"";
        return prefix + EventRegistryTests.repeat('x', bytes - prefix.length() - 2) + "\"}";
    }

    private static String ids(List<EventQueuePolicy.Row> rows) {
        List<Long> ids = new ArrayList<>();
        for (EventQueuePolicy.Row row : rows) ids.add(row.rowId);
        return ids.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
