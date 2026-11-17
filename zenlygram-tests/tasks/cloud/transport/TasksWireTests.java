package org.telegram.messenger.zenlygram.tasks.cloud;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM tests of the real TasksWire codec (production sources + the real Gson 2.11.0 jar):
 * the three normative R3 business-byte vectors, exact sealed request bytes with explicit nulls and
 * raw Unicode, all 12 R3 operation kinds and the R4 task.patch sealed and restored byte-identically,
 * strict rejection of duplicate keys / unknown members / wrong JSON types / fractions / exponents /
 * overflow / -0 / malformed UTF-8 / lone surrogates / BOM / depth / size / trailing data, sealing-only
 * text rules, Instants, projections and every response decoder. R4 (tasks-sync.json revision 4): the
 * four x-r4-vectors hash vectors (V-PATCH-1/2, V-LISTDEL-R3/R4, copied inline from
 * B/tests/Fixtures/tasks-r4/vectors.json), task.patch rules, the optional base_members_revision and
 * members_revision, $defs.ErrorRules strictness (fields, effective_at, the R4 reasons), the byte-exact
 * batch body layout and strict batch answers. No network, server or device is involved.
 */
public final class TasksWireTests {
    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    static final String PRINCIPAL = "01arz3ndektsv4rrffq69g5fav";
    static final String LIST = "22222222-2222-4222-8222-222222222222";
    static final String TASK = "33333333-3333-4333-8333-333333333333";
    static final String INVITE = "66666666-6666-4666-a666-666666666666";
    static final String M1 = "11111111-1111-4111-8111-111111111111";
    static final String M2 = "44444444-4444-4444-8444-444444444444";
    static final String M3 = "55555555-5555-4555-8555-555555555555";
    static final String T = "2026-10-24T00:00:00Z";

    public static void main(String[] args) {
        normativeVectors();
        exactRequestBytes();
        canonicalEscapes();
        allKindsRoundTrip();
        restoreRejectsMalformed();
        numbersAndIds();
        sealingRules();
        instants();
        projections();
        snapshots();
        changes();
        mutationResponses();
        errorEnvelopes();
        maximalPages();
        r4Vectors();
        patchBodies();
        patchRules();
        listDeleteR4();
        listProjectionR4();
        errorEnvelopesR4();
        batches();
        batchResponses();
        contentFields();
        if (!failures.isEmpty()) {
            System.out.println("Tasks wire: " + failures.size() + " of " + checks + " checks FAILED");
            for (String failure : failures) System.out.println("  FAIL " + failure);
            System.exit(1);
        }
        System.out.println("Tasks wire: " + checks + " checks passed");
    }

    // ---- Normative R3 vectors (zenlygram-tasks-business-hash-vectors-v1-r3.json, SHA 11afc1cc…) ----

    private static void normativeVectors() {
        TasksWire.Mutation listCreate = TasksWire.Mutation.seal(PRINCIPAL, M1,
                TasksWire.Operation.listCreate(LIST, "Ishlar", 0, null));
        vector(listCreate, "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"11111111-1111-4111-8111-111111111111\","
                + "\"list.create\",[\"22222222-2222-4222-8222-222222222222\",\"Ishlar\",0,null]]", 170,
                "c6d73bb69d9db2ec2dcbbfd91acfd21c312fba3c49025a9f7dc27c595d37e862", "list-create");

        vector(unicodeTaskCreate(), "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"44444444-4444-4444-8444-444444444444\","
                + "\"task.create\",[\"33333333-3333-4333-8333-333333333333\",[null,\"Hisobot / O\u2018zbekiston\","
                + "\"\u201cTayyor\u201d\\nKeyingi\u2028qator\",1792512000,2,-1,[\"123456789\",\"Ali\"]],null,false,null]]", 271,
                "45c2f4005d98a0c1d9af316a8643f616703caa6dfe8de0287450feb6c5881ac7", "unicode-task-create");

        TasksWire.Mutation completion = TasksWire.Mutation.seal(PRINCIPAL, M3,
                TasksWire.Operation.taskComplete(TASK, "7", null, true));
        vector(completion, "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"55555555-5555-4555-8555-555555555555\","
                + "\"task.complete\",[\"33333333-3333-4333-8333-333333333333\",\"7\",null,true]]", 170,
                "cbfe591d3b6eeccd2ef2e7a9549ae471b2967e650f82d7aae5ad3fb42c719c8b", "completion");
    }

    static TasksWire.Mutation unicodeTaskCreate() {
        TasksWire.Content content = new TasksWire.Content(null, "Hisobot / O\u2018zbekiston", "\u201cTayyor\u201d\nKeyingi\u2028qator",
                1792512000L, 2, -1, new TasksWire.Annotation("123456789", "Ali"));
        return TasksWire.Mutation.seal(PRINCIPAL, M2, TasksWire.Operation.taskCreate(TASK, content, null, false, null));
    }

    private static void vector(TasksWire.Mutation mutation, String canonical, int length, String sha, String name) {
        byte[] expected = canonical.getBytes(StandardCharsets.UTF_8);
        check(expected.length == length, name + ": vector literal has the normative utf8_length");
        check(Arrays.equals(mutation.businessBytes(), expected), name + ": business bytes equal canonical_utf8");
        check(sha.equals(mutation.businessHash()), name + ": business SHA-256 equals the normative hash");
        check(sha.equals(TasksWire.sha256Hex(expected)), name + ": SHA-256 helper agrees on the literal");
    }

    private static void exactRequestBytes() {
        TasksWire.Mutation mutation = unicodeTaskCreate();
        String expected = "{\"mutation\":{\"schema_version\":1,\"mutation_id\":\"" + M2 + "\",\"operation\":{\"kind\":\"task.create\","
                + "\"task_id\":\"" + TASK + "\",\"content\":{\"list_id\":null,\"title\":\"Hisobot / O\u2018zbekiston\","
                + "\"description\":\"\u201cTayyor\u201d\\nKeyingi\u2028qator\",\"due_at\":1792512000,\"priority\":2,\"sort_order\":-1,"
                + "\"annotation\":{\"telegram_user_id\":\"123456789\",\"display_name\":\"Ali\"}},\"created_at\":null,"
                + "\"is_completed\":false,\"completed_at\":null}}}";
        check(Arrays.equals(mutation.body(), expected.getBytes(StandardCharsets.UTF_8)),
                "sealed body: exact bytes, explicit nulls, raw '/' and U+2028, no creation_proof");
        byte[] copy = mutation.body();
        copy[0] = 'x';
        check(mutation.body()[0] == '{', "body() returns a defensive copy");
        TasksWire.Mutation other = TasksWire.Mutation.seal("01arz3ndektsv4rrffq69g5fbb", M2, mutation.operation);
        check(Arrays.equals(other.body(), mutation.body()) && !other.businessHash().equals(mutation.businessHash()),
                "principal enters the business hash only, never the request body");
    }

    private static void canonicalEscapes() {
        String title = "a\u0001b\u001fc\u007fd\"e\\f/g\b\t\f\r\u2029h";
        TasksWire.Mutation mutation = TasksWire.Mutation.seal(PRINCIPAL, M1, TasksWire.Operation.listCreate(LIST, title, 0, null));
        String business = new String(mutation.businessBytes(), StandardCharsets.UTF_8);
        check(business.contains("\"a\\u0001b\\u001fc\u007fd\\\"e\\\\f/g\\b\\t\\f\\r\u2029h\""),
                "canonical string: lowercase \\u00xx, standard short escapes, raw DEL/'/'/U+2029");
        check(Arrays.equals(TasksWire.Mutation.restore(PRINCIPAL, mutation.body()).body(), mutation.body()), "escaped title restores byte-identically");
    }

    // ---- All 12 kinds ------------------------------------------------------------------------

    static List<TasksWire.Operation> everyKind() {
        TasksWire.Content content = new TasksWire.Content(LIST, "Title", null, null, 0, 2147483647, null);
        Map<TasksWire.ContentField, Object> set = new HashMap<>(), expect = new HashMap<>();
        set.put(TasksWire.ContentField.DUE_AT, null);
        expect.put(TasksWire.ContentField.DUE_AT, 1792512000L);
        return Arrays.asList(
                TasksWire.Operation.listCreate(LIST, "Ishlar", -2147483648, T),
                TasksWire.Operation.listUpdate(LIST, "9223372036854775807", "Renamed", 3),
                TasksWire.Operation.listDelete(LIST, "1"),
                TasksWire.Operation.taskCreate(TASK, content, T, true, null),
                TasksWire.Operation.taskUpdate(TASK, "2", content),
                TasksWire.Operation.taskComplete(TASK, "3", INVITE, false),
                TasksWire.Operation.taskDelete(TASK, "4"),
                TasksWire.Operation.invitationRequest(TASK, "5", INVITE, "4294967296"),
                TasksWire.Operation.invitationRespond(TASK, "6", INVITE, TasksWire.Decision.LEAVE),
                TasksWire.Operation.invitationRevoke(TASK, "7", INVITE),
                TasksWire.Operation.senderBlock(INVITE),
                TasksWire.Operation.senderUnblock("9223372036854775807"),
                TasksWire.Operation.taskPatch(TASK, set, expect));
    }

    private static void allKindsRoundTrip() {
        List<TasksWire.Operation> operations = everyKind();
        check(operations.size() == TasksWire.OperationKind.values().length && operations.size() == 13,
                "factories exist for all 12 R3 $defs kinds and the R4 task.patch");
        for (int i = 0; i < operations.size(); i++) {
            TasksWire.Operation operation = operations.get(i);
            check(operation.kind == TasksWire.OperationKind.values()[i], operation.kind.wire() + ": factory kind");
            TasksWire.Mutation sealed = TasksWire.Mutation.seal(PRINCIPAL, TasksWire.newId(), operation);
            TasksWire.Mutation restored = TasksWire.Mutation.restore(PRINCIPAL, sealed.body());
            check(restored.equals(sealed) && restored.operation.equals(operation) && restored.operation.getClass() == operation.getClass(),
                    operation.kind.wire() + ": restore yields the same subtype and values");
            check(restored.businessHash().equals(sealed.businessHash()) && restored.mutationId.equals(sealed.mutationId),
                    operation.kind.wire() + ": same id and business hash after restore");
            check(TasksWire.OperationKind.fromWire(operation.kind.wire()) == operation.kind, operation.kind.wire() + ": wire name round trip");
        }
        check("4294967296".equals(((TasksWire.Operation.InvitationRequest) operations.get(7)).recipientTgId),
                "raw CloudUser ids >= 2^32 stay exact decimal strings");
        check(operations.get(10).resourceId().equals(INVITE) && operations.get(11).resourceId().equals("9223372036854775807"),
                "sender.* resource ids");
        String id = TasksWire.newId();
        check(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), "newId is a lowercase UUIDv4");
    }

    private static void restoreRejectsMalformed() {
        String good = new String(TasksWire.Mutation.seal(PRINCIPAL, M1, TasksWire.Operation.listDelete(LIST, "1")).body(), StandardCharsets.UTF_8);
        check(TasksWire.Mutation.restore(PRINCIPAL, utf8(good)).operation.kind == TasksWire.OperationKind.LIST_DELETE, "canonical list.delete restores");
        rejectsRestore(good.replace("\"list_id\":", "\"list_id\":\"" + LIST + "\",\"list_id\":"), "duplicate operation key");
        rejectsRestore(good.replace("{\"mutation\":{", "{\"mutation\":{\"mutation_id\":\"" + M1 + "\","), "duplicate mutation key");
        rejectsRestore(good.replace("\"base_revision\":\"1\"", "\"base_revision\":\"1\",\"extra\":null"), "unknown operation member");
        rejectsRestore(good.substring(0, good.length() - 1) + ",\"creation_proof\":{\"jws\":\"x\"}}", "creation_proof (Android never sends one)");
        rejectsRestore(good.replace(",\"base_revision\":\"1\"", ""), "missing member (no implicit default)");
        rejectsRestore(good.replace("\"schema_version\":1", "\"schema_version\":2"), "schema_version 2");
        rejectsRestore(good.replace("\"schema_version\":1", "\"schema_version\":1.0"), "schema_version 1.0 fraction");
        rejectsRestore(good.replace("\"schema_version\":1", "\"schema_version\":1e0"), "schema_version exponent");
        rejectsRestore(good.replace("\"schema_version\":1", "\"schema_version\":\"1\""), "schema_version as string");
        rejectsRestore(good.replace("\"base_revision\":\"1\"", "\"base_revision\":1"), "revision as number");
        rejectsRestore(good.replace("{\"mutation\":", "{ \"mutation\":"), "non-canonical whitespace");
        rejectsRestore("\uFEFF" + good, "BOM");
        rejectsRestore(good + " ", "trailing whitespace (non-canonical)");
        rejectsRestore(good + "{}", "trailing value");
        rejectsRestore(good.replace("\"kind\":\"list.delete\"", "\"kind\":\"list.remove\""), "unknown operation kind");
        // An alphabetic UUID: the lowercase form restores, the same bytes with uppercase hex do not (no case normalization).
        String alphabetic = "abcdefab-cdef-4abc-bdef-abcdefabcdef";
        String lower = new String(TasksWire.Mutation.seal(PRINCIPAL, M1, TasksWire.Operation.listDelete(alphabetic, "1")).body(), StandardCharsets.UTF_8);
        check(TasksWire.Mutation.restore(PRINCIPAL, utf8(lower)).operation.resourceId().equals(alphabetic), "lowercase alphabetic UUID restores");
        String upper = lower.replace(alphabetic, alphabetic.toUpperCase(java.util.Locale.ROOT));
        check(!upper.equals(lower), "uppercase fixture actually differs from the canonical body");
        rejectsRestore(upper, "uppercase UUID");
        rejectsRestore("{\"mutation\":{\"schema_version\":1,\"mutation_id\":\"" + M1 + "\",\"operation\":{\"kind\":\"list.create\",\"list_id\":\"" + LIST
                + "\",\"title\":\"x\\ud800\",\"sort_order\":0,\"created_at\":null}}}", "lone surrogate escape");
        rejectsRestore("{'mutation':1}", "single quotes");
        rejectsRestore("{\"mutation\":1}//c", "comment");
        byte[] invalidUtf8 = utf8(good);
        invalidUtf8[invalidUtf8.length - 5] = (byte) 0xC3;
        check(rejects(() -> TasksWire.Mutation.restore(PRINCIPAL, invalidUtf8)), "restore rejects malformed UTF-8");
        StringBuilder deep = new StringBuilder("{\"mutation\":");
        for (int i = 0; i < 20; i++) deep.append('[');
        for (int i = 0; i < 20; i++) deep.append(']');
        rejectsRestore(deep.append('}').toString(), "nesting deeper than 16");
        StringBuilder big = new StringBuilder();
        while (big.length() <= TasksWire.MAX_MUTATION_BYTES) big.append(' ');
        rejectsRestore(big + good, "more than 65536 bytes");
        // Schema-valid content whose escaped body exceeds the 65536-byte request limit (16384 controls, six bytes each).
        TasksWire.Operation oversized = new TasksWire.Operation.TaskCreate(TASK,
                new TasksWire.Content(null, "x", repeat("\u0001", 16384), null, 0, 0, null), null, false, null);
        String refused = null;
        try { TasksWire.Mutation.seal(PRINCIPAL, M1, oversized); }
        catch (TasksWire.Invalid invalid) { refused = invalid.field; }
        check("size".equals(refused), "seal refuses a request body over 65536 bytes");
        check(rejects(() -> TasksWire.Operation.taskCreate(TASK, new TasksWire.Content(null, "x", repeat("\u0001", 16384), null, 0, 0, null),
                null, false, null)), "the public factory already refuses it (description grapheme limit)");
        check(rejects(() -> TasksWire.Mutation.seal("", M1, TasksWire.Operation.listDelete(LIST, "1"))), "empty principal refused");
        check(rejects(() -> TasksWire.Mutation.seal("bad principal", M1, TasksWire.Operation.listDelete(LIST, "1"))), "principal with space refused");
    }

    private static void numbersAndIds() {
        check(rejects(() -> TasksWire.Operation.listDelete(LIST, "9223372036854775808")), "revision beyond signed 64-bit rejected");
        check(rejects(() -> TasksWire.Operation.listDelete(LIST, "0")), "revision 0 rejected");
        check(rejects(() -> TasksWire.Operation.listDelete(LIST, "01")), "revision leading zero rejected");
        check(rejects(() -> TasksWire.Operation.listDelete(LIST, "-1")), "negative revision rejected");
        check(rejects(() -> TasksWire.Operation.senderUnblock("12345678901234567890")), "20-digit Telegram id rejected");
        check(rejects(() -> TasksWire.Operation.listDelete("22222222-2222-1222-8222-222222222222", "1")), "non-v4 UUID rejected");
        String base = "{\"mutation\":{\"schema_version\":1,\"mutation_id\":\"" + M1 + "\",\"operation\":{\"kind\":\"list.create\",\"list_id\":\""
                + LIST + "\",\"title\":\"x\",\"sort_order\":SORT,\"created_at\":null}}}";
        check(TasksWire.Mutation.restore(PRINCIPAL, utf8(base.replace("SORT", "-2147483648"))) != null, "sort_order int32 minimum accepted");
        rejectsRestore(base.replace("SORT", "2147483648"), "sort_order int32 overflow");
        rejectsRestore(base.replace("SORT", "-0"), "sort_order -0");
        rejectsRestore(base.replace("SORT", "00"), "sort_order leading zero");
        rejectsRestore(base.replace("SORT", "0.5"), "sort_order fraction");
        rejectsRestore(base.replace("SORT", "1E2"), "sort_order exponent");
        rejectsRestore(base.replace("SORT", "99999999999999999999999"), "sort_order huge");
        rejectsRestore(base.replace("SORT", "NaN"), "NaN");
        rejectsRestore(base.replace("SORT", "true"), "boolean for integer");
        rejectsRestore(base.replace("SORT", "null"), "null for required integer");
        check(rejects(() -> new TasksWire.Content(null, "x", null, TasksWire.MAX_DEADLINE + 1, 0, 0, null)), "due_at beyond 2147483647 rejected");
        check(rejects(() -> new TasksWire.Content(null, "x", null, -1L, 0, 0, null)), "negative due_at rejected");
        check(rejects(() -> new TasksWire.Content(null, "x", null, null, 5, 0, null)), "priority 5 rejected");
        check(new TasksWire.Content(null, "x", "", null, 0, 0, null).description.isEmpty(), "empty description kept distinct from null");
    }

    private static void sealingRules() {
        check(rejects(() -> TasksWire.Operation.listCreate(LIST, " Ishlar", 0, null)), "leading space refused at seal");
        check(rejects(() -> TasksWire.Operation.listCreate(LIST, "Ishlar\n", 0, null)), "trailing newline refused at seal");
        check(rejects(() -> TasksWire.Operation.listCreate(LIST, "Ishlar\u00a0", 0, null)), "trailing NBSP (Zs) refused at seal");
        check(rejects(() -> TasksWire.Operation.listCreate(LIST, "\u200b", 0, null)), "format-only title refused (no visible scalar)");
        check(rejects(() -> TasksWire.Operation.listCreate(LIST, "\u0301", 0, null)), "combining-mark-only title refused");
        check(TasksWire.Operation.listCreate(LIST, repeat("a", 100), 0, null) != null, "list title of 100 accepted");
        check(rejects(() -> TasksWire.Operation.listCreate(LIST, repeat("a", 101), 0, null)), "list title of 101 refused");
        TasksWire.Content c200 = new TasksWire.Content(null, repeat("b", 200), null, null, 0, 0, null);
        check(TasksWire.Operation.taskUpdate(TASK, "1", c200) != null, "task title of 200 accepted");
        TasksWire.Content c201 = new TasksWire.Content(null, repeat("b", 201), null, null, 0, 0, null);
        check(rejects(() -> TasksWire.Operation.taskUpdate(TASK, "1", c201)), "task title of 201 refused");
        TasksWire.Content bytes = new TasksWire.Content(null, "x", repeat("\u0436", 4097), null, 0, 0, null);
        check(rejects(() -> TasksWire.Operation.taskUpdate(TASK, "1", bytes)), "description over 4096 graphemes refused");
        TasksWire.Content name = new TasksWire.Content(null, "x", null, null, 0, 0, new TasksWire.Annotation("1", "Ali "));
        check(rejects(() -> TasksWire.Operation.taskUpdate(TASK, "1", name)), "annotation name boundary whitespace refused");
        check(rejects(() -> TasksWire.Operation.taskCreate(TASK, c200, null, false, T)), "is_completed=false with completed_at refused");
        check(TasksWire.Operation.taskCreate(TASK, c200, null, true, null).completedAt == null, "completed with unknown time stays null");
        // Decoded server data follows the schema only: a boundary space is not re-graded locally.
        TasksWire.Projection list = TasksWire.decodeProjection("{\"kind\":\"list\",\"id\":\"" + LIST + "\",\"revision\":\"1\",\"title\":\" spaced \","
                + "\"sort_order\":0,\"created_at\":\"" + T + "\",\"updated_at\":\"" + T + "\"}");
        check(list instanceof TasksWire.ListItem && " spaced ".equals(((TasksWire.ListItem) list).title), "decoder keeps server title verbatim");
    }

    private static void instants() {
        check(TasksWire.epochSeconds(T) == 1792800000L, "Instant to Unix seconds");
        check(TasksWire.epochSeconds("2000-02-29T23:59:59Z") == 951868799L, "leap day");
        check("1970-01-01T00:00:00Z".equals(TasksWire.instant(0)), "epoch formats");
        check("9999-12-31T23:59:59Z".equals(TasksWire.instant(253402300799L)), "maximum Instant");
        check("0000-01-01T00:00:00Z".equals(TasksWire.instant(-62167219200L)), "minimum Instant");
        check(TasksWire.instant(TasksWire.epochSeconds("2026-03-01T12:34:56Z")).equals("2026-03-01T12:34:56Z"), "round trip");
        check(rejects(() -> TasksWire.instant(253402300800L)), "year 10000 refused");
        check(rejects(() -> TasksWire.epochSeconds("2026-02-29T00:00:00Z")), "invalid calendar date refused");
        check(rejects(() -> TasksWire.epochSeconds("2026-10-24T24:00:00Z")), "hour 24 refused");
        check(rejects(() -> TasksWire.epochSeconds("2026-10-24T00:00:00.000Z")), "fractional seconds refused");
        check(rejects(() -> TasksWire.epochSeconds("2026-10-24T00:00:00+00:00")), "offset refused");
        check(rejects(() -> TasksWire.epochSeconds("2026-10-24t00:00:00z")), "lowercase refused");
    }

    // ---- Projections and responses -------------------------------------------------------------

    static String taskJson(String role, String listId, String annotation, String assignment, boolean completed, String completedAt) {
        return "{\"kind\":\"task\",\"id\":\"" + TASK + "\",\"revision\":\"12\",\"owner_tg_id\":\"4294967297\",\"viewer_role\":\"" + role + "\","
                + "\"content\":{\"list_id\":" + listId + ",\"title\":\"T\",\"description\":null,\"due_at\":0,\"priority\":4,\"sort_order\":7,"
                + "\"annotation\":" + annotation + "},\"is_completed\":" + completed + ",\"completed_at\":" + completedAt + ","
                + "\"created_at\":\"" + T + "\",\"updated_at\":\"" + T + "\",\"assignment\":" + assignment + "}";
    }

    static String accepted() {
        return "{\"invitation_id\":\"" + INVITE + "\",\"recipient_tg_id\":\"5\",\"state\":\"accepted\",\"expires_at\":\"" + T + "\"}";
    }

    static String listJson() {
        return "{\"kind\":\"list\",\"id\":\"" + LIST + "\",\"revision\":\"3\",\"title\":\"L\",\"sort_order\":1,\"created_at\":\"" + T
                + "\",\"updated_at\":\"" + T + "\"}";
    }

    static String invitationJson() {
        return "{\"kind\":\"invitation\",\"id\":\"" + INVITE + "\",\"task_id\":\"" + TASK + "\",\"task_revision\":\"2\",\"owner_tg_id\":\"9\","
                + "\"title\":\"Share\",\"due_at\":null,\"expires_at\":\"" + T + "\"}";
    }

    private static void projections() {
        String owner = taskJson("owner", "\"" + LIST + "\"", "{\"telegram_user_id\":\"8\",\"display_name\":\"Ali\"}", "null", true, "null");
        String assignee = taskJson("assignee", "null", "null", accepted(), false, "null");
        String block = "{\"kind\":\"block\",\"id\":\"9223372036854775807\",\"created_at\":\"" + T + "\"}";
        for (String json : Arrays.asList(owner, assignee, listJson(), invitationJson(), block)) {
            TasksWire.Projection decoded = TasksWire.decodeProjection(json);
            check(TasksWire.encodeProjection(decoded).equals(json), decoded.kind.wire() + ": canonical encode reproduces the wire object");
            check(TasksWire.decodeProjection(TasksWire.encodeProjection(decoded)).equals(decoded), decoded.kind.wire() + ": stored round trip");
        }
        TasksWire.TaskItem task = (TasksWire.TaskItem) TasksWire.decodeProjection(owner);
        check(task.isCompleted && task.completedAt == null && "4294967297".equals(task.ownerTgId) && task.content.dueAt == 0L,
                "owner task: unknown completion time, raw >=2^32 owner id, due_at 0 kept");
        check(TasksWire.decodeProjection(invitationJson()) instanceof TasksWire.InvitationItem, "invitation decodes to InvitationItem");
        rejectsProjection(taskJson("assignee", "\"" + LIST + "\"", "null", accepted(), false, "null"), "assignee with owner list");
        rejectsProjection(taskJson("assignee", "null", "{\"telegram_user_id\":\"8\",\"display_name\":\"Ali\"}", accepted(), false, "null"),
                "assignee with private annotation");
        rejectsProjection(taskJson("assignee", "null", "null", accepted().replace("accepted", "pending"), false, "null"), "assignee not accepted");
        rejectsProjection(taskJson("assignee", "null", "null", "null", false, "null"), "assignee without assignment");
        rejectsProjection(taskJson("owner", "null", "null", "null", false, "\"" + T + "\""), "open task with completed_at");
        rejectsProjection(owner.replace("\"priority\":4", "\"priority\":4,\"priority\":4"), "duplicate nested key");
        rejectsProjection(owner.replace("\"revision\":\"12\"", "\"revision\":\"12\",\"etag\":\"x\""), "unknown member");
        rejectsProjection(owner.replace("\"due_at\":0", "\"due_at\":2147483648"), "due_at overflow");
        rejectsProjection(owner.replace("\"revision\":\"12\"", "\"revision\":12"), "numeric revision");
        rejectsProjection(owner.replace("\"owner_tg_id\":\"4294967297\"", "\"owner_tg_id\":4294967297"), "numeric Telegram id");
        rejectsProjection(listJson().replace("\"kind\":\"list\"", "\"kind\":\"folder\""), "unknown kind");
        rejectsProjection("{\"kind\":\"block\",\"id\":\"" + LIST + "\",\"created_at\":\"" + T + "\"}", "block with UUID id");
        rejectsProjection(listJson().replace("\"sort_order\":1", "\"sort_order\":1.0"), "fractional sort order");
    }

    private static void snapshots() {
        String page = "{\"schema_version\":1,\"items\":[" + listJson() + "," + invitationJson() + "],\"next_cursor\":null,"
                + "\"changes_cursor\":\"c\",\"server_time\":\"" + T + "\"}";
        TasksWire.SnapshotResponse response = TasksWire.decodeSnapshotResponse(page, 50);
        check(response.items.size() == 2 && response.nextCursor == null && "c".equals(response.changesCursor) && T.equals(response.serverTime),
                "snapshot page decodes");
        check(rejects(() -> response.items.add(null)), "snapshot items are unmodifiable");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page, 1)), "more items than the requested limit rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page, 0)), "limit 0 rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page, 101)), "limit 101 rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page.replace(invitationJson(), listJson()), 50)), "duplicate (kind,id) rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page.replace(",\"next_cursor\":null", ""), 50)), "missing next_cursor rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page.replace("\"changes_cursor\":\"c\"", "\"changes_cursor\":\"\""), 50)),
                "empty cursor rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"), 50)),
                "duplicate top-level key rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page.substring(0, page.length() - 1), 50)), "truncated document rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page.substring(0, page.length() - 1) + ",\"debug\":true}", 50)), "unknown top member rejected");
        check(rejects(() -> TasksWire.decodeSnapshotResponse("[]", 50)), "array root rejected");
    }

    private static void changes() {
        String body = "{\"schema_version\":1,\"changes\":[{\"position\":\"0\",\"action\":\"upsert\",\"item\":" + listJson() + "},"
                + "{\"position\":\"9223372036854775807\",\"action\":\"remove\",\"kind\":\"block\",\"id\":\"42\"}],"
                + "\"next_cursor\":\"n\",\"has_more\":false,\"server_time\":\"" + T + "\"}";
        TasksWire.ChangesResponse response = TasksWire.decodeChangesResponse(body, 100);
        check(response.changes.size() == 2 && !response.hasMore && "n".equals(response.nextCursor), "changes page decodes");
        TasksWire.Change upsert = response.changes.get(0), remove = response.changes.get(1);
        check(upsert.action == TasksWire.ChangeAction.UPSERT && upsert.item instanceof TasksWire.ListItem && LIST.equals(upsert.id),
                "upsert carries its projection");
        check(remove.action == TasksWire.ChangeAction.REMOVE && remove.item == null && remove.kind == TasksWire.ProjectionKind.BLOCK
                && "42".equals(remove.id), "remove carries kind+id only");
        String swapped = "{\"schema_version\":1,\"changes\":[{\"position\":\"5\",\"action\":\"remove\",\"kind\":\"task\",\"id\":\"" + TASK + "\"},"
                + "{\"position\":\"5\",\"action\":\"remove\",\"kind\":\"list\",\"id\":\"" + LIST + "\"}],\"next_cursor\":\"n\",\"has_more\":true,"
                + "\"server_time\":\"" + T + "\"}";
        check(rejects(() -> TasksWire.decodeChangesResponse(swapped, 100)), "non-ascending positions rejected");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"id\":\"42\"", "\"id\":\"" + LIST + "\""), 100)), "block removal with UUID rejected");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"kind\":\"block\",\"id\":\"42\"", "\"kind\":\"task\",\"id\":\"42\""), 100)),
                "task removal with Telegram id rejected");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"action\":\"upsert\",", "\"action\":\"upsert\",\"kind\":\"list\","), 100)),
                "upsert with extra member rejected");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"position\":\"0\"", "\"position\":\"01\""), 100)), "position leading zero");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"position\":\"0\"", "\"position\":0"), 100)), "numeric position");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"9223372036854775807\"", "\"9223372036854775808\""), 100)),
                "position overflow");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"has_more\":false", "\"has_more\":0"), 100)), "has_more as number");
        check(rejects(() -> TasksWire.decodeChangesResponse(body.replace("\"next_cursor\":\"n\"", "\"next_cursor\":null"), 100)), "null next_cursor");
    }

    static String receipt(String mutationId, String operation, String kind, String id, String revision) {
        return "{\"schema_version\":1,\"receipt\":{\"mutation_id\":\"" + mutationId + "\",\"operation\":\"" + operation + "\","
                + "\"outcome\":\"committed\",\"resource_kind\":\"" + kind + "\",\"resource_id\":\"" + id + "\",\"resource_revision\":" + revision
                + ",\"committed_at\":\"" + T + "\"},\"server_time\":\"" + T + "\"}";
    }

    private static void mutationResponses() {
        TasksWire.Mutation complete = TasksWire.Mutation.seal(PRINCIPAL, M3, TasksWire.Operation.taskComplete(TASK, "7", null, true));
        TasksWire.MutationResponse ok = TasksWire.decodeMutationResponse(receipt(M3, "task.complete", "task", TASK, "\"8\""), complete);
        check(ok.receipt.outcome == TasksWire.Outcome.COMMITTED && "8".equals(ok.receipt.resourceRevision) && T.equals(ok.serverTime),
                "matching receipt decodes");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M1, "task.complete", "task", TASK, "\"8\""), complete)), "other mutation id");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M3, "task.update", "task", TASK, "\"8\""), complete)), "other operation");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M3, "task.complete", "list", TASK, "\"8\""), complete)), "wrong resource kind");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M3, "task.complete", "task", LIST, "\"8\""), complete)), "other resource id");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M3, "task.complete", "task", TASK, "null"), complete)), "missing task revision");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M3, "task.complete", "task", TASK, "8"), complete)), "numeric revision");
        TasksWire.Mutation block = TasksWire.Mutation.seal(PRINCIPAL, M1, TasksWire.Operation.senderBlock(INVITE));
        check(TasksWire.decodeMutationResponse(receipt(M1, "sender.block", "block", "4294967296", "null"), block).receipt.resourceRevision == null,
                "sender.block receipt names the inviter Telegram id with null revision");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M1, "sender.block", "block", "4294967296", "\"1\""), block)),
                "block receipt with revision rejected");
        TasksWire.Mutation unblock = TasksWire.Mutation.seal(PRINCIPAL, M1, TasksWire.Operation.senderUnblock("77"));
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M1, "sender.unblock", "block", "78", "null"), unblock)),
                "unblock receipt for another sender rejected");
        String withExtra = receipt(M3, "task.complete", "task", TASK, "\"8\"").replace("\"outcome\"", "\"title\":\"leak\",\"outcome\"");
        check(rejects(() -> TasksWire.decodeMutationResponse(withExtra, complete)), "receipt with unknown member rejected");
        check(rejects(() -> TasksWire.decodeMutationResponse(receipt(M3, "task.complete", "task", TASK, "\"8\"").replace("committed\"", "done\""), complete)),
                "unknown outcome rejected");
    }

    private static void errorEnvelopes() {
        TasksWire.ErrorEnvelope conflict = TasksWire.decodeErrorEnvelope("{\"error\":\"tasks_conflict\",\"message\":\"m\",\"server_time\":\"" + T
                + "\",\"reason\":\"revision_changed\"}");
        check(conflict != null && conflict.strict && conflict.reason == TasksWire.ConflictReason.REVISION_CHANGED && T.equals(conflict.serverTime),
                "strict conflict envelope");
        TasksWire.ErrorEnvelope noReason = TasksWire.decodeErrorEnvelope("{\"error\":\"tasks_conflict\",\"message\":\"m\",\"server_time\":\"" + T + "\"}");
        check(noReason != null && !noReason.strict, "conflict without reason is not strict");
        TasksWire.ErrorEnvelope extraReason = TasksWire.decodeErrorEnvelope("{\"error\":\"tasks_not_found\",\"message\":\"m\",\"server_time\":\"" + T
                + "\",\"reason\":\"revision_changed\"}");
        check(extraReason != null && !extraReason.strict, "reason outside tasks_conflict is not strict");
        TasksWire.ErrorEnvelope middleware = TasksWire.decodeErrorEnvelope("{\"error\":\"invalid_token\",\"message\":\"m\",\"retry_after\":3}");
        check(middleware != null && !middleware.strict && "invalid_token".equals(middleware.code) && middleware.serverTime == null,
                "existing middleware envelope accepted loosely");
        TasksWire.ErrorEnvelope badTime = TasksWire.decodeErrorEnvelope("{\"error\":\"tasks_unavailable\",\"message\":\"m\",\"server_time\":\"now\"}");
        check(badTime != null && !badTime.strict && badTime.serverTime == null, "invalid server_time never trusted");
        check(TasksWire.decodeErrorEnvelope("{\"error\":\"a\",\"error\":\"b\"}") == null, "duplicate key error envelope ignored");
        check(TasksWire.decodeErrorEnvelope("<html>") == null && TasksWire.decodeErrorEnvelope("[]") == null
                && TasksWire.decodeErrorEnvelope("{\"error\":\"\"}") == null && TasksWire.decodeErrorEnvelope("{\"error\":5}") == null,
                "non-envelopes ignored");
    }

    // ---- Maximal schema-valid pages (raw transport bound) -------------------------------------------

    /** U+1F600: one Unicode scalar (maxLength unit), two UTF-16 units, 12 bytes once written as escaped surrogates. */
    static final String ASTRAL = "\ud83d\ude00";

    /** Owner task with every free-text string at its schema maxLength of supplementary scalars. */
    static TasksWire.TaskItem maximalTask(String id) {
        String max = "9223372036854775807";
        TasksWire.Content content = new TasksWire.Content(LIST, repeat(ASTRAL, 8192), repeat(ASTRAL, 16384), TasksWire.MAX_DEADLINE, 4,
                Integer.MIN_VALUE, new TasksWire.Annotation(max, repeat(ASTRAL, 4096)));
        return new TasksWire.TaskItem(id, max, max, TasksWire.ViewerRole.OWNER, content, true, T, T, T,
                new TasksWire.Assignment(INVITE, max, TasksWire.AssignmentState.ACCEPTED, T));
    }

    /**
     * Worst-case schema-valid serialization of compact JSON: every character inside every string
     * literal (member names included) as a \\u escape, so each supplementary scalar costs 12 bytes and
     * every other string character 6. The input is canonical output without quotes/backslashes inside strings.
     */
    static String escapeAllStrings(String json) {
        StringBuilder out = new StringBuilder(json.length() * 6);
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') { inString = !inString; out.append(c); continue; }
            if (c == '\\') throw new IllegalArgumentException("fixture strings must not contain escapes");
            if (inString) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    private static String maximalItem(String id) { return escapeAllStrings(TasksWire.encodeProjection(maximalTask(id))); }

    private static String maximalCursor() { return escapeAllStrings("\"" + repeat(ASTRAL, 4096) + "\""); }

    /** {@code count} maximal tasks and two maximal cursors, every string fully escaped. */
    public static String maximalSnapshotPage(int count) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) items.append(',');
            items.append(maximalItem(TasksWire.newId()));
        }
        return escapeAllStrings("{\"schema_version\":1,\"items\":[") + items + escapeAllStrings("],\"next_cursor\":") + maximalCursor()
                + escapeAllStrings(",\"changes_cursor\":") + maximalCursor() + escapeAllStrings(",\"server_time\":\"" + T + "\"}");
    }

    private static void maximalPages() {
        String item = maximalItem(TASK);
        int itemBytes = utf8(item).length;
        check(itemBytes <= TasksWire.MAX_ITEM_BYTES && itemBytes > 12 * (8192 + 16384 + 4096),
                "astral-escaped maximal item fits the derived item bound (" + itemBytes + " <= " + TasksWire.MAX_ITEM_BYTES + ")");
        check(TasksWire.decodeProjection(item).equals(maximalTask(TASK)), "escaped maximal item decodes to the same projection");
        check(rejects(() -> TasksWire.maxPageBytes(0)) && rejects(() -> TasksWire.maxPageBytes(101))
                && TasksWire.maxPageBytes(100) == TasksWire.MAX_PAGE_BYTES && TasksWire.maxPageBytes(1) < TasksWire.maxPageBytes(20),
                "page bound is derived per requested limit");

        String one = maximalSnapshotPage(1);
        check(utf8(one).length <= TasksWire.maxPageBytes(1), "1-item maximal page fits maxPageBytes(1)");
        check(TasksWire.decodeSnapshotResponse(one, 1).items.size() == 1, "1-item maximal page decodes with limit 1");

        String page = maximalSnapshotPage(TasksWire.MAX_LIMIT);
        int pageBytes = utf8(page).length;
        check(pageBytes <= TasksWire.maxPageBytes(100) && pageBytes > 100 * 12 * (8192 + 16384 + 4096),
                "astral-escaped 100-item page fits maxPageBytes(100) (" + pageBytes + " <= " + TasksWire.MAX_PAGE_BYTES + ")");
        TasksWire.SnapshotResponse snapshot = TasksWire.decodeSnapshotResponse(page, TasksWire.MAX_LIMIT);
        check(snapshot.items.size() == 100 && snapshot.nextCursor.codePointCount(0, snapshot.nextCursor.length()) == 4096,
                "maximal snapshot page decodes completely");
        check(rejects(() -> TasksWire.decodeSnapshotResponse(page, 1)), "the same page is refused for a limit-1 request");

        StringBuilder changes = new StringBuilder(escapeAllStrings("{\"schema_version\":1,\"changes\":["));
        for (int i = 0; i < TasksWire.MAX_LIMIT; i++) {
            if (i > 0) changes.append(',');
            changes.append(escapeAllStrings("{\"position\":\"" + (9223372036854775707L + i) + "\",\"action\":\"upsert\",\"item\":"))
                    .append(maximalItem(TasksWire.newId())).append('}');
        }
        changes.append(escapeAllStrings("],\"next_cursor\":")).append(maximalCursor())
                .append(escapeAllStrings(",\"has_more\":true,\"server_time\":\"" + T + "\"}"));
        check(utf8(changes.toString()).length <= TasksWire.maxPageBytes(100), "astral-escaped maximal changes page fits the bound");
        check(TasksWire.decodeChangesResponse(changes.toString(), TasksWire.MAX_LIMIT).changes.size() == 100, "maximal changes page decodes");

        String over = TasksWire.encodeProjection(maximalTask(TASK)).replace("\"title\":\"", "\"title\":\"" + ASTRAL);
        check(rejects(() -> TasksWire.decodeProjection(over)), "one supplementary scalar beyond Title maxLength rejected");
    }

    // ---- R4 (tasks-sync.json revision 4) -------------------------------------------------------

    static final String PATCH_1 = "66666666-6666-4666-8666-666666666666";
    static final String PATCH_2 = "77777777-7777-4777-8777-777777777777";
    static final String LIST_DELETE = "88888888-8888-4888-8888-888888888888";
    static final String BATCH = "99999999-9999-4999-9999-999999999999";

    /** x-r4-vectors.hash_vectors V-PATCH-1: set/expect given out of content order, exactly as the fixture lists them. */
    static TasksWire.Mutation vPatch1() {
        Map<TasksWire.ContentField, Object> set = new LinkedHashMap<>(), expect = new LinkedHashMap<>();
        set.put(TasksWire.ContentField.PRIORITY, 3);
        set.put(TasksWire.ContentField.TITLE, "Hisobot 2");
        expect.put(TasksWire.ContentField.PRIORITY, 2);
        expect.put(TasksWire.ContentField.TITLE, "Hisobot / O‘zbekiston");
        return TasksWire.Mutation.seal(PRINCIPAL, PATCH_1, TasksWire.Operation.taskPatch(TASK, set, expect));
    }

    /** V-PATCH-2: clears list, annotation and moves the task (a user's cloud-editor patch). */
    static TasksWire.Mutation vPatch2() {
        Map<TasksWire.ContentField, Object> set = new LinkedHashMap<>(), expect = new LinkedHashMap<>();
        set.put(TasksWire.ContentField.LIST_ID, null);
        set.put(TasksWire.ContentField.SORT_ORDER, 2048);
        set.put(TasksWire.ContentField.ANNOTATION, null);
        expect.put(TasksWire.ContentField.LIST_ID, LIST);
        expect.put(TasksWire.ContentField.SORT_ORDER, -1);
        expect.put(TasksWire.ContentField.ANNOTATION, new TasksWire.Annotation("123456789", "Ali"));
        return TasksWire.Mutation.seal(PRINCIPAL, PATCH_2, TasksWire.Operation.taskPatch(TASK, set, expect));
    }

    private static void r4Vectors() {
        vector(vPatch1(), "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"66666666-6666-4666-8666-666666666666\","
                + "\"task.patch\",[\"33333333-3333-4333-8333-333333333333\",[[\"title\",\"Hisobot 2\"],[\"priority\",3]],"
                + "[[\"title\",\"Hisobot / O‘zbekiston\"],[\"priority\",2]]]]", 245,
                "6193411e93311f66fbad3b0f833a692d44a990ee1ba9c4cb0e357c41f06c3f0c", "V-PATCH-1");
        vector(vPatch2(), "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"77777777-7777-4777-8777-777777777777\","
                + "\"task.patch\",[\"33333333-3333-4333-8333-333333333333\",[[\"list_id\",null],[\"sort_order\",2048],[\"annotation\",null]],"
                + "[[\"list_id\",\"22222222-2222-4222-8222-222222222222\"],[\"sort_order\",-1],[\"annotation\",[\"123456789\",\"Ali\"]]]]]", 318,
                "ad41a47effcf0e3022648c4278167ea14f71cc503d8dbd704e3611548f684b83", "V-PATCH-2");
        TasksWire.Mutation r3 = TasksWire.Mutation.seal(PRINCIPAL, LIST_DELETE, TasksWire.Operation.listDelete(LIST, "3"));
        vector(r3, "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"88888888-8888-4888-8888-888888888888\","
                + "\"list.delete\",[\"22222222-2222-4222-8222-222222222222\",\"3\"]]", 158,
                "ac5c6e31a9e616e9608df595538209a3888471fb8456cb549f9ea735885f8a81", "V-LISTDEL-R3");
        TasksWire.Mutation r4 = TasksWire.Mutation.seal(PRINCIPAL, LIST_DELETE, TasksWire.Operation.listDelete(LIST, "3", "7"));
        vector(r4, "[\"zenlygram-tasks-business-v1\",\"01arz3ndektsv4rrffq69g5fav\",\"88888888-8888-4888-8888-888888888888\","
                + "\"list.delete\",[\"22222222-2222-4222-8222-222222222222\",\"3\",\"7\"]]", 162,
                "a7fe3b0bfe45cfd11e343bad1095ef5cd52badbc93506cc0aaf7ca6583a5f76b", "V-LISTDEL-R4");
        check(!r3.requiresWire4() && r4.requiresWire4() && vPatch1().requiresWire4() && !unicodeTaskCreate().requiresWire4(),
                "R4-only operations are recognisable (the downgrade re-encodes exactly those)");
        Map<TasksWire.ContentField, Object> set = new HashMap<>(), expect = new HashMap<>();
        set.put(TasksWire.ContentField.TITLE, "Hisobot 2");
        set.put(TasksWire.ContentField.PRIORITY, 3L); // A Long priority is the same value.
        expect.put(TasksWire.ContentField.TITLE, "Hisobot / O‘zbekiston");
        expect.put(TasksWire.ContentField.PRIORITY, 2);
        TasksWire.Mutation hashed = TasksWire.Mutation.seal(PRINCIPAL, PATCH_1, TasksWire.Operation.taskPatch(TASK, set, expect));
        check(Arrays.equals(hashed.body(), vPatch1().body()) && hashed.businessHash().equals(vPatch1().businessHash()),
                "map order and integral type never reach the bytes or the hash");
    }

    private static void patchBodies() {
        TasksWire.Mutation patch = vPatch2();
        String expected = "{\"mutation\":{\"schema_version\":1,\"mutation_id\":\"" + PATCH_2 + "\",\"operation\":{\"kind\":\"task.patch\","
                + "\"task_id\":\"" + TASK + "\",\"set\":{\"list_id\":null,\"sort_order\":2048,\"annotation\":null},"
                + "\"expect\":{\"list_id\":\"" + LIST + "\",\"sort_order\":-1,\"annotation\":{\"telegram_user_id\":\"123456789\","
                + "\"display_name\":\"Ali\"}}}}}";
        check(Arrays.equals(patch.body(), utf8(expected)), "task.patch body: set/expect keys in content order, explicit nulls, annotation object");
        TasksWire.Mutation restored = TasksWire.Mutation.restore(PRINCIPAL, patch.body());
        check(restored.equals(patch) && restored.operation instanceof TasksWire.Operation.TaskPatch
                && restored.businessHash().equals(patch.businessHash()), "task.patch restores byte-identically with the same hash");
        TasksWire.Operation.TaskPatch op = (TasksWire.Operation.TaskPatch) restored.operation;
        check(op.fields().equals(Arrays.asList(TasksWire.ContentField.LIST_ID, TasksWire.ContentField.SORT_ORDER, TasksWire.ContentField.ANNOTATION))
                && op.set.containsKey(TasksWire.ContentField.LIST_ID) && op.set.get(TasksWire.ContentField.LIST_ID) == null
                && Integer.valueOf(-1).equals(op.expect.get(TasksWire.ContentField.SORT_ORDER))
                && new TasksWire.Annotation("123456789", "Ali").equals(op.expect.get(TasksWire.ContentField.ANNOTATION))
                && TASK.equals(op.resourceId()), "restored patch: fields, explicit nulls and typed values");
        check(rejects(() -> op.set.put(TasksWire.ContentField.TITLE, "x")), "patch maps are unmodifiable");
        String one = new String(vPatch1().body(), StandardCharsets.UTF_8);
        check(one.contains("\"set\":{\"title\":\"Hisobot 2\",\"priority\":3},\"expect\":{\"title\":\"Hisobot / O‘zbekiston\",\"priority\":2}"),
                "V-PATCH-1 seals in content order although its maps list priority first");
        rejectsRestore(one.replace("{\"title\":\"Hisobot 2\",\"priority\":3}", "{\"priority\":3,\"title\":\"Hisobot 2\"}"),
                "patch keys out of content order (valid JSON, not this client's canonical sealed form)");
        rejectsRestore(one.replace("\"set\":{\"title\":\"Hisobot 2\",\"priority\":3}", "\"set\":{}"), "empty set");
        rejectsRestore(one.replace(",\"priority\":2}", "}"), "expect without one of set's keys");
        rejectsRestore(one.replace(",\"priority\":2}", ",\"priority\":2,\"sort_order\":1}"), "expect with a key set lacks");
        rejectsRestore(one.replace("\"priority\":3}", "\"priority\":3,\"status\":1}"), "unknown patch field");
        rejectsRestore(one.replace("\"priority\":3}", "\"priority\":\"3\"}"), "priority as string");
        rejectsRestore(one.replace("\"priority\":3}", "\"priority\":5}"), "priority 5");
        rejectsRestore(one.replace("\"priority\":3}", "\"priority\":3.0}"), "fractional priority");
        rejectsRestore(one.replace("\"priority\":3}", "\"priority\":null}"), "null priority");
        rejectsRestore(one.replace("\"title\":\"Hisobot 2\"", "\"title\":null"), "null title");
        rejectsRestore(one.replace("\"priority\":3}", "\"priority\":3,\"priority\":3}"), "duplicate patch key");
        rejectsRestore(one.replace("\"task_id\":", "\"base_revision\":\"1\",\"task_id\":"), "a base_revision on task.patch");
        rejectsRestore(one.replace("\"set\":{\"title\":\"Hisobot 2\",\"priority\":3}", "\"set\":[]"), "set as array");
        String two = new String(patch.body(), StandardCharsets.UTF_8);
        rejectsRestore(two.replace("\"sort_order\":2048", "\"sort_order\":2147483648"), "sort_order beyond int32");
        String alphabetic = "abcdefab-cdef-4abc-bdef-abcdefabcdef";
        check(TasksWire.Mutation.restore(PRINCIPAL, utf8(two.replace("\"list_id\":\"" + LIST + "\"", "\"list_id\":\"" + alphabetic + "\""))) != null,
                "a lowercase alphabetic list_id restores");
        rejectsRestore(two.replace("\"list_id\":\"" + LIST + "\"", "\"list_id\":\"" + alphabetic.toUpperCase(java.util.Locale.ROOT) + "\""),
                "uppercase list_id");
        rejectsRestore(two.replace("\"display_name\":\"Ali\"}", "\"display_name\":\"Ali\",\"x\":1}"), "annotation with an extra member");
        String due = "{\"mutation\":{\"schema_version\":1,\"mutation_id\":\"" + PATCH_1 + "\",\"operation\":{\"kind\":\"task.patch\",\"task_id\":\""
                + TASK + "\",\"set\":{\"due_at\":DUE},\"expect\":{\"due_at\":null}}}}";
        check(TasksWire.Mutation.restore(PRINCIPAL, utf8(due.replace("DUE", "0"))) != null
                && TasksWire.Mutation.restore(PRINCIPAL, utf8(due.replace("DUE", "2147483647"))) != null, "due_at 0 and 2147483647 restore");
        rejectsRestore(due.replace("DUE", "-1"), "negative due_at");
        rejectsRestore(due.replace("DUE", "2147483648"), "due_at beyond 2147483647");
    }

    private static Map<TasksWire.ContentField, Object> fields(Object... pairs) {
        Map<TasksWire.ContentField, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put((TasksWire.ContentField) pairs[i], pairs[i + 1]);
        return map;
    }

    private static void patchRules() {
        TasksWire.ContentField title = TasksWire.ContentField.TITLE;
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(title, " Spaced"), fields(title, "Old"))), "set title with boundary space refused at seal");
        check(TasksWire.Operation.taskPatch(TASK, fields(title, "New"), fields(title, " spaced ")) != null,
                "expect keeps the server's own value: schema rules only (never unpatchable)");
        check(TasksWire.Operation.taskPatch(TASK, fields(title, repeat("b", 200)), fields(title, "Old")) != null, "set title of 200 accepted");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(title, repeat("b", 201)), fields(title, "Old"))), "set title of 201 refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(title, "​"), fields(title, "Old"))), "invisible set title refused");
        TasksWire.ContentField description = TasksWire.ContentField.DESCRIPTION;
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(description, repeat("ж", 4097)), fields(description, null))),
                "set description over 4096 graphemes refused");
        check(TasksWire.Operation.taskPatch(TASK, fields(description, " trailing "), fields(description, null)) != null,
                "a description has no edge rule");
        TasksWire.ContentField annotation = TasksWire.ContentField.ANNOTATION;
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(annotation, new TasksWire.Annotation("1", "Ali ")), fields(annotation, null))),
                "set annotation name with boundary space refused");
        check(TasksWire.Operation.taskPatch(TASK, fields(annotation, null), fields(annotation, new TasksWire.Annotation("1", "Ali "))) != null,
                "expect annotation held to the schema only");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, new HashMap<TasksWire.ContentField, Object>(), new HashMap<TasksWire.ContentField, Object>())),
                "empty set refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, null, fields(title, "Old"))), "null set refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(title, "New"), fields(TasksWire.ContentField.PRIORITY, 1))),
                "expect with other keys refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(title, "New", TasksWire.ContentField.PRIORITY, 2), fields(title, "Old"))),
                "expect missing a key refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.PRIORITY, "3"), fields(TasksWire.ContentField.PRIORITY, 1))),
                "priority as a String refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(title, 5), fields(title, "Old"))), "title as a number refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.PRIORITY, null), fields(TasksWire.ContentField.PRIORITY, 1))),
                "null priority refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.SORT_ORDER, 2147483648L), fields(TasksWire.ContentField.SORT_ORDER, 1))),
                "sort_order beyond int32 refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.DUE_AT, 2147483648L), fields(TasksWire.ContentField.DUE_AT, null))),
                "due_at beyond 2147483647 refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.DUE_AT, 1.5), fields(TasksWire.ContentField.DUE_AT, null))),
                "fractional due_at refused");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.LIST_ID, "not-a-uuid"), fields(TasksWire.ContentField.LIST_ID, null))),
                "list_id that is not a UUIDv4 refused");
        Map<TasksWire.ContentField, Object> nullKey = fields(title, "New");
        nullKey.put(null, "x");
        check(rejects(() -> TasksWire.Operation.taskPatch(TASK, nullKey, fields(title, "Old"))), "a null field key refused");
        TasksWire.Operation.TaskPatch due = TasksWire.Operation.taskPatch(TASK, fields(TasksWire.ContentField.DUE_AT, 5), fields(TasksWire.ContentField.DUE_AT, null));
        check(Long.valueOf(5).equals(due.set.get(TasksWire.ContentField.DUE_AT)), "an Integer due_at is kept as its Long value");
        check(rejects(() -> TasksWire.Operation.taskPatch("TASK", fields(title, "New"), fields(title, "Old"))), "patch of a non-UUID task refused");
        // A user patch may fall back to the single route: it must fit 65536 request bytes like any mutation.
        String big = repeat("\u0001", 4096);
        Map<TasksWire.ContentField, Object> wide = fields(description, big, title, "x");
        String refused = null;
        try {
            TasksWire.Mutation.seal(PRINCIPAL, PATCH_1, new TasksWire.Operation.TaskPatch(TASK, fields(description, repeat("\u0001", 16384)),
                    fields(description, repeat("\u0001", 16384))));
        } catch (TasksWire.Invalid invalid) {
            refused = invalid.field;
        }
        check("size".equals(refused), "a patch over the single route's 65536 bytes is refused at seal");
        check(TasksWire.Mutation.seal(PRINCIPAL, PATCH_1, TasksWire.Operation.taskPatch(TASK, wide, fields(description, null, title, "y"))) != null,
                "a patch within the bound seals");
    }

    private static void listDeleteR4() {
        TasksWire.Mutation r3 = TasksWire.Mutation.seal(PRINCIPAL, LIST_DELETE, TasksWire.Operation.listDelete(LIST, "3"));
        String r3Body = "{\"mutation\":{\"schema_version\":1,\"mutation_id\":\"" + LIST_DELETE + "\",\"operation\":{\"kind\":\"list.delete\","
                + "\"list_id\":\"" + LIST + "\",\"base_revision\":\"3\"}}}";
        check(Arrays.equals(r3.body(), utf8(r3Body)), "R3 list.delete body unchanged: no base_members_revision key");
        TasksWire.Mutation r4 = TasksWire.Mutation.seal(PRINCIPAL, LIST_DELETE, TasksWire.Operation.listDelete(LIST, "3", "7"));
        check(Arrays.equals(r4.body(), utf8(r3Body.replace("\"base_revision\":\"3\"", "\"base_revision\":\"3\",\"base_members_revision\":\"7\""))),
                "R4 list.delete: base_members_revision follows base_revision");
        TasksWire.Operation.ListDelete restored3 = (TasksWire.Operation.ListDelete) TasksWire.Mutation.restore(PRINCIPAL, r3.body()).operation;
        TasksWire.Operation.ListDelete restored4 = (TasksWire.Operation.ListDelete) TasksWire.Mutation.restore(PRINCIPAL, r4.body()).operation;
        check(restored3.baseMembersRevision == null && "7".equals(restored4.baseMembersRevision)
                && TasksWire.Mutation.restore(PRINCIPAL, r4.body()).businessHash().equals(r4.businessHash()), "both shapes restore byte-identically");
        check(!r3.equals(r4) && !r3.operation.equals(r4.operation), "the members fence is part of the business identity");
        String body = new String(r4.body(), StandardCharsets.UTF_8);
        rejectsRestore(body.replace("\"base_members_revision\":\"7\"", "\"base_members_revision\":\"0\""), "base_members_revision 0");
        rejectsRestore(body.replace("\"base_members_revision\":\"7\"", "\"base_members_revision\":\"07\""), "base_members_revision leading zero");
        rejectsRestore(body.replace("\"base_members_revision\":\"7\"", "\"base_members_revision\":7"), "numeric base_members_revision");
        rejectsRestore(body.replace("\"base_members_revision\":\"7\"", "\"base_members_revision\":null"), "null base_members_revision");
        rejectsRestore(body.replace("\"list_id\":", "\"base_members_revision\":\"7\",\"list_id\":"), "duplicate base_members_revision");
        rejectsRestore(r3Body.replace("\"list_id\":", "\"members_revision\":\"7\",\"list_id\":"), "a projection key on an operation");
        check(rejects(() -> TasksWire.Operation.listDelete(LIST, "3", null)), "the R4 factory requires the fence value");
        check(rejects(() -> TasksWire.Operation.listDelete(LIST, "3", "9223372036854775808")), "fence beyond signed 64-bit refused");
        String update = new String(TasksWire.Mutation.seal(PRINCIPAL, M1, TasksWire.Operation.listUpdate(LIST, "3", "L", 0)).body(), StandardCharsets.UTF_8);
        rejectsRestore(update.replace("\"sort_order\":0", "\"sort_order\":0,\"base_members_revision\":\"7\""),
                "base_members_revision is optional on list.delete only");
    }

    static String listJsonR4() {
        return "{\"kind\":\"list\",\"id\":\"" + LIST + "\",\"revision\":\"3\",\"members_revision\":\"9\",\"title\":\"L\",\"sort_order\":1,"
                + "\"created_at\":\"" + T + "\",\"updated_at\":\"" + T + "\"}";
    }

    private static void listProjectionR4() {
        TasksWire.ListItem r4 = (TasksWire.ListItem) TasksWire.decodeProjection(listJsonR4());
        check("9".equals(r4.membersRevision) && "3".equals(r4.revision), "R4 list projection carries members_revision");
        check(TasksWire.encodeProjection(r4).equals(listJsonR4()), "members_revision is stored right after revision ($defs.ListR4 order)");
        TasksWire.ListItem r3 = (TasksWire.ListItem) TasksWire.decodeProjection(listJson());
        check(r3.membersRevision == null && TasksWire.encodeProjection(r3).equals(listJson()), "an R3 list projection is unchanged (no key)");
        check(!r3.equals(r4), "members_revision is part of the stored projection");
        String last = listJson().replace("\"updated_at\":\"" + T + "\"}", "\"updated_at\":\"" + T + "\",\"members_revision\":\"9\"}");
        check(TasksWire.encodeProjection(TasksWire.decodeProjection(last)).equals(listJsonR4()), "key order of the answer is irrelevant");
        rejectsProjection(listJsonR4().replace("\"members_revision\":\"9\"", "\"members_revision\":\"0\""), "members_revision 0");
        rejectsProjection(listJsonR4().replace("\"members_revision\":\"9\"", "\"members_revision\":9"), "numeric members_revision");
        rejectsProjection(listJsonR4().replace("\"members_revision\":\"9\"", "\"members_revision\":null"), "null members_revision");
        rejectsProjection(taskJson("owner", "null", "null", "null", false, "null").replace("\"revision\":\"12\"",
                "\"revision\":\"12\",\"members_revision\":\"1\""), "members_revision on a task");
        check(new TasksWire.ListItem(LIST, "3", "L", 1, T, T).membersRevision == null, "the R3 constructor stays");
        String page = "{\"schema_version\":1,\"items\":[" + listJsonR4() + "],\"next_cursor\":null,\"changes_cursor\":\"c\",\"server_time\":\"" + T + "\"}";
        check("9".equals(((TasksWire.ListItem) TasksWire.decodeSnapshotResponse(page, 1).items.get(0)).membersRevision),
                "an R4 snapshot page decodes");
        check(TasksWire.decodeSnapshotResponse(page, 1).serverWire == null && TasksWire.decodeSnapshotResponse(page, 1, 4).serverWire == 4,
                "the X-Tasks-Wire echo is recorded on the result (null when absent)");
    }

    private static TasksWire.ErrorEnvelope envelope(String code, String extras) {
        return TasksWire.decodeErrorEnvelope("{\"error\":\"" + code + "\",\"message\":\"m\",\"server_time\":\"" + T + "\"" + extras + "}");
    }

    private static void errorEnvelopesR4() {
        check(TasksWire.ConflictReason.fromWireOrNull("field_changed") == TasksWire.ConflictReason.FIELD_CHANGED
                && TasksWire.ConflictReason.fromWireOrNull("members_changed") == TasksWire.ConflictReason.MEMBERS_CHANGED
                && TasksWire.ConflictReason.FIELD_CHANGED.wire().equals("field_changed"), "R4 conflict reasons");
        TasksWire.ErrorEnvelope field = envelope("tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"title\",\"priority\"]");
        check(field != null && field.strict && field.reason == TasksWire.ConflictReason.FIELD_CHANGED
                && field.fields.equals(Arrays.asList(TasksWire.ContentField.TITLE, TasksWire.ContentField.PRIORITY))
                && "field_changed".equals(field.wireReason), "field_changed with its fields is strict");
        check(!envelope("tasks_conflict", ",\"reason\":\"field_changed\"").strict, "field_changed without fields is not strict");
        check(!envelope("tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"priority\",\"title\"]").strict, "fields out of content order");
        check(!envelope("tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"title\",\"title\"]").strict, "repeated field");
        check(!envelope("tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[\"status\"]").strict, "unknown field");
        check(!envelope("tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":[]").strict, "empty fields");
        check(!envelope("tasks_conflict", ",\"reason\":\"field_changed\",\"fields\":\"title\"").strict, "fields as a string");
        TasksWire.ErrorEnvelope extraFields = envelope("tasks_conflict", ",\"reason\":\"revision_changed\",\"fields\":[\"title\"]");
        check(!extraFields.strict && extraFields.fields.isEmpty() && extraFields.reason == TasksWire.ConflictReason.REVISION_CHANGED,
                "fields with another reason are not strict");
        TasksWire.ErrorEnvelope members = envelope("tasks_conflict", ",\"reason\":\"members_changed\"");
        check(members.strict && members.reason == TasksWire.ConflictReason.MEMBERS_CHANGED && members.fields.isEmpty(), "members_changed is strict");
        TasksWire.ErrorEnvelope consent = envelope("tasks_consent_required", ",\"reason\":\"withdrawn\",\"effective_at\":\"2026-10-30T08:00:00Z\"");
        check(consent.strict && "withdrawn".equals(consent.wireReason) && consent.reason == null
                && "2026-10-30T08:00:00Z".equals(consent.effectiveAt), "tasks_consent_required: withdrawn + effective_at is strict");
        check(!envelope("tasks_consent_required", ",\"reason\":\"withdrawn\"").strict, "consent without effective_at is not strict");
        check(!envelope("tasks_consent_required", ",\"effective_at\":\"" + T + "\"").strict, "consent without reason is not strict");
        check(!envelope("tasks_consent_required", ",\"reason\":\"no_consent\",\"effective_at\":\"" + T + "\"").strict, "consent with another reason");
        TasksWire.ErrorEnvelope badInstant = envelope("tasks_consent_required", ",\"reason\":\"withdrawn\",\"effective_at\":\"now\"");
        check(!badInstant.strict && badInstant.effectiveAt == null, "an invalid effective_at is never trusted");
        check(!envelope("tasks_not_found", ",\"effective_at\":\"" + T + "\"").strict, "effective_at outside tasks_consent_required");
        TasksWire.ErrorEnvelope disabled = envelope("tasks_unavailable", ",\"reason\":\"batch_disabled\"");
        TasksWire.ErrorEnvelope paused = envelope("tasks_unavailable", ",\"reason\":\"migration_paused\"");
        check(disabled.strict && "batch_disabled".equals(disabled.wireReason) && paused.strict && "migration_paused".equals(paused.wireReason),
                "tasks_unavailable with batch_disabled / migration_paused is strict");
        check(envelope("tasks_unavailable", "").strict, "tasks_unavailable without reason stays strict (R3)");
        check(!envelope("tasks_unavailable", ",\"reason\":\"withdrawn\"").strict && !envelope("tasks_unavailable", ",\"reason\":\"maintenance\"").strict,
                "tasks_unavailable with another reason is not strict");
        check(!envelope("tasks_invalid_request", ",\"reason\":\"batch_disabled\"").strict, "an R4 reason on another code is not strict");
        TasksWire.ErrorEnvelope erased = TasksWire.decodeErrorEnvelope("{\"error\":\"invalid_token\",\"message\":\"m\",\"reason\":\"account_erased\"}");
        check(erased != null && !erased.strict && "account_erased".equals(erased.wireReason) && erased.reason == null,
                "an existing 401 envelope keeps its reason for the session handling");
        check(TasksWire.decodeErrorEnvelope("{\"error\":\"invalid_token\",\"message\":\"m\",\"reason\":5}").wireReason == null, "a non-string reason is ignored");
    }

    static String inner(TasksWire.Mutation mutation) {
        String body = new String(mutation.body(), StandardCharsets.UTF_8);
        return body.substring("{\"mutation\":".length(), body.length() - 1);
    }

    static TasksWire.Mutation listCreate(String mutationId) {
        return TasksWire.Mutation.seal(PRINCIPAL, mutationId, TasksWire.Operation.listCreate(LIST, "Ishlar / O‘zbek", 0, null));
    }

    private static void batches() {
        TasksWire.Mutation create = listCreate(M1);
        TasksWire.Mutation migrated = unicodeTaskCreate();
        TasksWire.Mutation patch = vPatch2();
        List<TasksWire.Batch.Item> items = Arrays.asList(new TasksWire.Batch.Item(create, TasksWire.Origin.USER),
                new TasksWire.Batch.Item(migrated, TasksWire.Origin.MIGRATION), new TasksWire.Batch.Item(patch, TasksWire.Origin.USER));
        TasksWire.Batch batch = TasksWire.Batch.of(BATCH, items);
        String expected = "{\"schema_version\":1,\"batch_id\":\"" + BATCH + "\",\"mutations\":[" + inner(create) + ",{\"origin\":\"migration\","
                + inner(migrated).substring(1) + "," + inner(patch) + "]}";
        check(Arrays.equals(batch.body(), utf8(expected)), "batch body: envelope, user items as sealed, a migration item behind origin");
        String body = new String(batch.body(), StandardCharsets.UTF_8);
        check(body.contains(inner(create)) && body.contains(inner(patch)) && body.contains(inner(migrated).substring(1)),
                "every sealed mutation object travels byte for byte");
        check(batch.bodyBytes() == batch.body().length && TasksWire.Batch.bodyBytes(items) == batch.bodyBytes()
                && create.itemBytes(TasksWire.Origin.USER) == utf8(inner(create)).length
                && migrated.itemBytes(TasksWire.Origin.MIGRATION) == utf8(inner(migrated)).length + 21, "sizing before a batch is made is exact");
        check(TasksWire.Batch.bodyBytes(Collections.singletonList(items.get(0))) == 85 + utf8(inner(create)).length, "85 envelope bytes");
        check(BATCH.equals(batch.batchId) && PRINCIPAL.equals(batch.principal) && batch.items.size() == 3, "batch id, principal, order");
        check(rejects(() -> batch.items.add(items.get(0))), "batch items are unmodifiable");
        byte[] copy = batch.body();
        copy[0] = 'x';
        check(batch.body()[0] == '{', "body() returns a defensive copy");
        check(rejects(() -> TasksWire.Batch.of(BATCH, Collections.<TasksWire.Batch.Item>emptyList())), "an empty batch refused");
        List<TasksWire.Batch.Item> many = new ArrayList<>();
        for (int i = 0; i <= TasksWire.MAX_BATCH_ITEMS; i++) many.add(new TasksWire.Batch.Item(listCreate(TasksWire.newId()), TasksWire.Origin.USER));
        check(TasksWire.Batch.of(BATCH, many.subList(0, 100)).items.size() == 100, "100 items accepted");
        check(rejects(() -> TasksWire.Batch.of(BATCH, many)), "101 items refused");
        check(rejects(() -> TasksWire.Batch.of(BATCH, Arrays.asList(items.get(0), new TasksWire.Batch.Item(create, TasksWire.Origin.USER)))),
                "a repeated mutation id refused");
        check(rejects(() -> TasksWire.Batch.of(BATCH, Collections.singletonList(new TasksWire.Batch.Item(patch, TasksWire.Origin.MIGRATION)))),
                "origin migration on a patch refused");
        TasksWire.Mutation update = TasksWire.Mutation.seal(PRINCIPAL, M3, TasksWire.Operation.listUpdate(LIST, "3", "L", 0));
        check(rejects(() -> TasksWire.Batch.of(BATCH, Collections.singletonList(new TasksWire.Batch.Item(update, TasksWire.Origin.MIGRATION)))),
                "origin migration on an update refused");
        check(TasksWire.Batch.of(BATCH, Collections.singletonList(new TasksWire.Batch.Item(create, TasksWire.Origin.MIGRATION))) != null,
                "origin migration on list.create accepted");
        TasksWire.Mutation other = TasksWire.Mutation.seal("01arz3ndektsv4rrffq69g5fbb", M3, TasksWire.Operation.listDelete(LIST, "1"));
        check(rejects(() -> TasksWire.Batch.of(BATCH, Arrays.asList(items.get(0), new TasksWire.Batch.Item(other, TasksWire.Origin.USER)))),
                "items of two principals refused");
        check(TasksWire.Batch.of("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa", items) != null
                && rejects(() -> TasksWire.Batch.of("AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA", items))
                && rejects(() -> TasksWire.Batch.of("aaaaaaaa-aaaa-1aaa-aaaa-aaaaaaaaaaaa", items)), "batch id: a lowercase UUIDv4 only");
        check(rejects(() -> TasksWire.Batch.of(BATCH, Arrays.asList(items.get(0), null))), "a null item refused");
        check(rejects(() -> new TasksWire.Batch.Item(create, null)) && rejects(() -> new TasksWire.Batch.Item(null, TasksWire.Origin.USER)),
                "item needs a mutation and an origin");
        List<TasksWire.Batch.Item> heavy = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            TasksWire.Content content = new TasksWire.Content(null, repeat(ASTRAL, 200), repeat(ASTRAL, 4096), null, 0, 0, null);
            heavy.add(new TasksWire.Batch.Item(TasksWire.Mutation.seal(PRINCIPAL, TasksWire.newId(),
                    TasksWire.Operation.taskCreate(TasksWire.newId(), content, null, false, null)), TasksWire.Origin.USER));
        }
        String refused = null;
        try { TasksWire.Batch.of(BATCH, heavy); }
        catch (TasksWire.Invalid invalid) { refused = invalid.field; }
        check(TasksWire.Batch.bodyBytes(heavy) > TasksWire.MAX_BATCH_BYTES && "size".equals(refused), "a body over 524288 bytes refused");
        int fits = 0;
        while (TasksWire.Batch.bodyBytes(heavy.subList(0, fits + 1)) <= TasksWire.MAX_BATCH_BYTES) fits++;
        check(TasksWire.Batch.of(BATCH, heavy.subList(0, fits)).bodyBytes() <= TasksWire.MAX_BATCH_BYTES, "the largest fitting prefix seals");
    }

    static String receiptJson(String mutationId, String operation, String kind, String id, String revision) {
        return "{\"mutation_id\":\"" + mutationId + "\",\"operation\":\"" + operation + "\",\"outcome\":\"committed\",\"resource_kind\":\""
                + kind + "\",\"resource_id\":\"" + id + "\",\"resource_revision\":" + revision + ",\"committed_at\":\"" + T + "\"}";
    }

    static String batchAnswer(String batchId, String retryAfter, String... results) {
        StringBuilder out = new StringBuilder("{\"schema_version\":1,\"batch_id\":\"" + batchId + "\",\"results\":[");
        for (int i = 0; i < results.length; i++) out.append(i == 0 ? "" : ",").append(results[i]);
        return out.append("],\"retry_after\":").append(retryAfter).append(",\"server_time\":\"").append(T).append("\"}").toString();
    }

    static String receiptResult(String mutationId, String receipt) {
        return "{\"mutation_id\":\"" + mutationId + "\",\"status\":200,\"receipt\":" + receipt + "}";
    }

    static String errorResult(String mutationId, int status, String error) {
        return "{\"mutation_id\":\"" + mutationId + "\",\"status\":" + status + ",\"error\":" + error + "}";
    }

    static String skippedResult(String mutationId) {
        return "{\"mutation_id\":\"" + mutationId + "\",\"status\":null,\"skipped\":true}";
    }

    private static void batchResponses() {
        TasksWire.Mutation create = listCreate(M1), migrated = unicodeTaskCreate(), patch = vPatch2();
        TasksWire.Batch batch = TasksWire.Batch.of(BATCH, Arrays.asList(new TasksWire.Batch.Item(create, TasksWire.Origin.USER),
                new TasksWire.Batch.Item(migrated, TasksWire.Origin.MIGRATION), new TasksWire.Batch.Item(patch, TasksWire.Origin.USER)));
        String ok = receiptResult(M1, receiptJson(M1, "list.create", "list", LIST, "\"1\""));
        String paused = errorResult(M2, 503, "{\"error\":\"tasks_unavailable\",\"message\":\"m\",\"reason\":\"migration_paused\",\"retry_after\":3600}");
        String skipped = skippedResult(PATCH_2);
        TasksWire.BatchResponse response = TasksWire.decodeBatchResponse(batchAnswer(BATCH, "3600", ok, paused, skipped), batch, 4);
        check(response.results.size() == 3 && BATCH.equals(response.batchId) && response.retryAfter == 3600 && T.equals(response.serverTime)
                && response.serverWire == 4, "batch answer decodes with its retry_after and the X-Tasks-Wire echo");
        TasksWire.BatchItemResult first = response.results.get(0), second = response.results.get(1), third = response.results.get(2);
        check(first.kind == TasksWire.BatchItemResult.Kind.RECEIPT && first.status == 200 && M1.equals(first.mutationId)
                && first.receipt.operation == TasksWire.OperationKind.LIST_CREATE && first.error == null, "receipt result checked against its item");
        check(second.kind == TasksWire.BatchItemResult.Kind.ERROR && second.status == 503 && second.error.strict
                && "tasks_unavailable".equals(second.error.code) && "migration_paused".equals(second.error.wireReason)
                && second.error.retryAfter == 3600 && T.equals(second.error.serverTime) && second.receipt == null, "item error with the batch's server_time");
        check(third.kind == TasksWire.BatchItemResult.Kind.SKIPPED && third.status == 0 && PATCH_2.equals(third.mutationId), "skipped item");
        check(rejects(() -> response.results.add(first)), "results are unmodifiable");
        check(TasksWire.decodeBatchResponse(batchAnswer(BATCH, "3600", ok, paused, skipped), batch, null).serverWire == null, "no echo: null");
        String patched = receiptResult(PATCH_2, receiptJson(PATCH_2, "task.patch", "task", TASK, "\"8\""));
        TasksWire.BatchResponse receipts = TasksWire.decodeBatchResponse(batchAnswer(BATCH, "null", ok,
                receiptResult(M2, receiptJson(M2, "task.create", "task", TASK, "\"1\"")), patched), batch, 4);
        check(receipts.retryAfter == null && receipts.results.get(2).receipt.operation == TasksWire.OperationKind.TASK_PATCH,
                "a task.patch receipt answers the patch item");
        String limited = errorResult(M2, 429, "{\"error\":\"tasks_rate_limited\",\"message\":\"m\"}");
        check(TasksWire.decodeBatchResponse(batchAnswer(BATCH, "17", ok, limited, skipped), batch, null).results.get(1).error.retryAfter == 17,
                "a 429 item without its own retry_after gets the batch's");
        String consent = errorResult(M1, 403, "{\"error\":\"tasks_consent_required\",\"message\":\"m\",\"reason\":\"withdrawn\",\"effective_at\":\"" + T + "\"}");
        TasksWire.ErrorEnvelope fence = TasksWire.decodeBatchResponse(batchAnswer(BATCH, "null", consent, skippedResult(M2), skipped), batch, null)
                .results.get(0).error;
        check(fence.strict && T.equals(fence.effectiveAt) && fence.retryAfter == 0, "consent fence item carries effective_at");
        String revoked = errorResult(M1, 401, "{\"error\":\"device_revoked\",\"message\":\"m\"}");
        check(TasksWire.decodeBatchResponse(batchAnswer(BATCH, "null", revoked, skippedResult(M2), skipped), batch, null).results.get(0).status == 401,
                "a 401 item (identity fence inside the item) decodes");
        String fieldChanged = errorResult(PATCH_2, 409, "{\"error\":\"tasks_conflict\",\"message\":\"m\",\"reason\":\"field_changed\",\"fields\":[\"list_id\",\"annotation\"]}");
        check(TasksWire.decodeBatchResponse(batchAnswer(BATCH, "null", ok, receiptResult(M2, receiptJson(M2, "task.create", "task", TASK, "\"1\"")),
                fieldChanged), batch, null).results.get(2).error.fields.equals(Arrays.asList(TasksWire.ContentField.LIST_ID, TasksWire.ContentField.ANNOTATION)),
                "field_changed item names its fields");

        rejectsBatch(batchAnswer(TasksWire.newId(), "null", ok, paused, skipped), batch, "another batch_id");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused), batch, "fewer results than items");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped, skipped), batch, "more results than items");
        rejectsBatch(batchAnswer(BATCH, "null", paused, ok, skipped), batch, "results out of request order");
        rejectsBatch(batchAnswer(BATCH, "null", receiptResult(M1, receiptJson(M1, "list.update", "list", LIST, "\"1\"")), paused, skipped), batch,
                "a receipt of another operation");
        rejectsBatch(batchAnswer(BATCH, "null", receiptResult(M1, receiptJson(M1, "list.create", "list", TASK, "\"1\"")), paused, skipped), batch,
                "a receipt for another resource");
        rejectsBatch(batchAnswer(BATCH, "null", receiptResult(M1, receiptJson(M2, "list.create", "list", LIST, "\"1\"")), paused, skipped), batch,
                "a receipt naming another mutation");
        rejectsBatch(batchAnswer(BATCH, "null", ok.replace("\"status\":200", "\"status\":\"200\""), paused, skipped), batch, "string status");
        rejectsBatch(batchAnswer(BATCH, "null", ok.replace("\"status\":200", "\"status\":200.0"), paused, skipped), batch, "fractional status");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused.replace("\"status\":503", "\"status\":500"), skipped), batch, "item status 500");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused.replace("\"status\":503", "\"status\":413"), skipped), batch, "item status 413");
        rejectsBatch(batchAnswer(BATCH, "null", ok, errorResult(M2, 409, "{\"error\":\"tasks_conflict\",\"message\":\"m\",\"reason\":\"revision_changed\","
                + "\"retry_after\":3}"), skipped), batch, "retry_after on a 409 item");
        rejectsBatch(batchAnswer(BATCH, "null", ok, errorResult(M2, 404, "{\"error\":\"tasks_not_found\"}"), skipped), batch, "item error without message");
        rejectsBatch(batchAnswer(BATCH, "null", ok, errorResult(M2, 404, "{\"error\":\"tasks_not_found\",\"message\":\"m\",\"server_time\":\"" + T + "\"}"),
                skipped), batch, "server_time inside an item error");
        rejectsBatch(batchAnswer(BATCH, "null", ok, errorResult(M2, 409, "{\"error\":\"tasks_conflict\",\"message\":\"m\"}"), skipped), batch,
                "conflict item without reason");
        rejectsBatch(batchAnswer(BATCH, "null", ok, errorResult(M2, 401, "{\"error\":\"invalid_token\",\"message\":\"m\",\"reason\":\"account_erased\"}"),
                skipped), batch, "a reason on a DeviceAuth item code");
        rejectsBatch(batchAnswer(BATCH, "null", ok, errorResult(M2, 403, "{\"error\":\"tasks_consent_required\",\"message\":\"m\",\"reason\":\"withdrawn\"}"),
                skipped), batch, "consent item without effective_at");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped.replace("\"skipped\":true", "\"skipped\":false")), batch, "skipped false");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped.replace("\"status\":null", "\"status\":200")), batch, "skipped with a status");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped.replace("}", ",\"receipt\":null}")), batch, "skipped with a receipt");
        rejectsBatch(batchAnswer(BATCH, "0", ok, paused, skipped), batch, "retry_after 0");
        rejectsBatch(batchAnswer(BATCH, "\"5\"", ok, paused, skipped), batch, "retry_after as string");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped).replace("\"retry_after\":null", "\"retry_after\":null,\"debug\":1"), batch,
                "unknown top-level member");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped).replace(",\"retry_after\":null", ""), batch, "missing retry_after");
        rejectsBatch(batchAnswer(BATCH, "null", ok, paused, skipped).replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"), batch,
                "duplicate key");
        check(rejects(() -> TasksWire.decodeBatchResponse(batchAnswer(BATCH, "null", ok, paused, skipped), null, null)), "a batch answer needs its batch");
    }

    private static void contentFields() {
        TasksWire.Content content = new TasksWire.Content(LIST, "Title", "d", 7L, 2, 5, new TasksWire.Annotation("8", "Ali"));
        check(LIST.equals(TasksWire.ContentField.LIST_ID.valueIn(content)) && Integer.valueOf(2).equals(TasksWire.ContentField.PRIORITY.valueIn(content))
                && Long.valueOf(7).equals(TasksWire.ContentField.DUE_AT.valueIn(content))
                && Integer.valueOf(5).equals(TasksWire.ContentField.SORT_ORDER.valueIn(content)), "valueIn reads typed values");
        TasksWire.Operation.TaskPatch patch = (TasksWire.Operation.TaskPatch) vPatch2().operation;
        TasksWire.Content applied = content.applying(patch.set);
        check(applied.listId == null && applied.sortOrder == 2048 && applied.annotation == null && "Title".equals(applied.title)
                && "d".equals(applied.description) && applied.dueAt == 7L && applied.priority == 2, "applying a patch's set (the R3 re-encoding)");
        check(rejects(() -> content.applying(fields(TasksWire.ContentField.PRIORITY, 9))), "applying keeps the schema rules");
        check(TasksWire.ContentField.fromWire("sort_order") == TasksWire.ContentField.SORT_ORDER && rejects(() -> TasksWire.ContentField.fromWire("status")),
                "content field wire names");
        check(TasksWire.WIRE == 4 && TasksWire.MAX_BATCH_BYTES == 524288 && TasksWire.MAX_BATCH_ITEMS == 100
                && TasksWire.MAX_BATCH_RESPONSE_BYTES == 262144, "R4 constants");
    }

    private static void rejectsBatch(String json, TasksWire.Batch batch, String what) {
        check(rejects(() -> TasksWire.decodeBatchResponse(json, batch, null)), "batch answer rejects " + what);
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static void rejectsRestore(String body, String what) {
        check(rejects(() -> TasksWire.Mutation.restore(PRINCIPAL, utf8(body))), "restore rejects " + what);
    }

    private static void rejectsProjection(String json, String what) {
        check(rejects(() -> TasksWire.decodeProjection(json)), "projection rejects " + what);
    }

    private static boolean rejects(Runnable action) {
        try { action.run(); return false; }
        catch (TasksWire.Invalid | UnsupportedOperationException expected) { return true; }
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) failures.add(name);
    }
}
