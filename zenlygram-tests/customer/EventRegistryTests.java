package org.telegram.messenger.zenlygram.customer;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Plain-JVM checks of the analytics taxonomy (contract §3.1–§3.5; design 50 §11.1, §16.1) against the backend's shared
 * fixtures: TAXONOMY_VERSION equals the registry's; every Java enum is a subset of its registry enum (and of each
 * event's inline list); a test-side validator written from the contract (independent of the production builders)
 * agrees with the server on every case of fixtures/events-valid.json and fixtures/events-invalid.json — per-event
 * verdicts, counts, dropped props and the batch-level 422 paths; every event any builder can produce (every enum value,
 * clamped and boundary integers, valid, invalid and absent ids and codes) validates; the client-built fixture bodies are
 * rebuilt byte-equivalently (as parsed JSON) from the typed builders and the envelope writer; an Android session's
 * envelope passes the batch rules for an android binding; and the device context's normalization. Run with
 * run-tests.sh, which passes the Android repository root.
 */
public final class EventRegistryTests {
    private static int checks;
    private static int ids;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: EventRegistryTests <android repository root>");
        Path fixtures = Paths.get(args[0]).resolve("zenlygram-tests/customer/fixtures");
        Registry registry = Registry.load(fixtures.resolve("analytics-events.json"));
        versions(registry);
        enumsAreSubsets(registry);
        validatorAgreesWithTheServer(registry, fixtures.resolve("events-valid.json"));
        validatorAgreesWithTheServer(registry, fixtures.resolve("events-invalid.json"));
        everyBuiltEventIsValid(registry);
        clientBuiltBodies(registry, fixtures.resolve("events-valid.json"));
        androidEnvelope(registry, fixtures.resolve("events-valid.json"));
        deviceContext();
        System.out.println("Event registry: " + checks + " checks passed");
    }

    // ---- Registry ----

    private static void versions(Registry registry) {
        check(registry.version == CustomerEvents.TAXONOMY_VERSION, "TAXONOMY_VERSION equals the registry's taxonomy_version");
        check(CustomerEvents.SCHEMA_VERSION == 1, "schema_version 1");
        check(CustomerEvents.MAX_EVENT_BYTES == 2048 && CustomerEvents.MAX_SEQ == 1000000, "2 KiB events, seq 0..1000000");
        String[] named = {CustomerEvents.SESSION_START, CustomerEvents.SESSION_END, CustomerEvents.SCREEN_VIEW, CustomerEvents.FEATURE_TOGGLE,
                CustomerEvents.FEATURE_USED, CustomerEvents.TASK_CREATED, CustomerEvents.TASK_COMPLETED, CustomerEvents.TASK_DELETED,
                CustomerEvents.LIST_CREATED, CustomerEvents.LIST_DELETED, CustomerEvents.TASKS_SHARED, CustomerEvents.TASKS_MIGRATION,
                CustomerEvents.TASKS_SYNC_ISSUE, CustomerEvents.VAULT_UNLOCKED, CustomerEvents.VAULT_CHAT_ADDED, CustomerEvents.VAULT_CHAT_REMOVED,
                CustomerEvents.CHAT_LOCK_SET, CustomerEvents.CHAT_LOCK_UNLOCKED, CustomerEvents.CHAT_LOCK_REMOVED, CustomerEvents.EDIT_HISTORY_VIEWED,
                CustomerEvents.PRO_SCREEN_VIEW, CustomerEvents.PRO_PLAN_SELECT, CustomerEvents.PRO_PURCHASE_START, CustomerEvents.PRO_PURCHASE_SUCCESS,
                CustomerEvents.PRO_PURCHASE_FAIL, CustomerEvents.PRO_RESTORE, CustomerEvents.PRO_TRIAL_START, CustomerEvents.LINK_ATTEMPT,
                CustomerEvents.ERROR_OCCURRED};
        check(new HashSet<>(Arrays.asList(named)).equals(registry.events.keySet()), "the Java names are exactly the registry's events");
    }

    private static void enumsAreSubsets(Registry registry) throws Exception {
        subset(CustomerEvents.Screen.class, registry.enums.get("screen"), "screen");
        subset(CustomerEvents.Feature.class, registry.enums.get("feature"), "feature");
        subset(CustomerEvents.ErrorDomain.class, registry.enums.get("error_domain"), "error_domain");
        subset(CustomerEvents.Rail.class, registry.enums.get("rail"), "rail");
        subset(CustomerEvents.TasksScope.class, registry.enums.get("tasks_scope"), "tasks_scope");
        check(wires(CustomerEvents.Rail.class).equals(new LinkedHashSet<>(registry.enums.get("rail"))), "every rail");
        check(wires(CustomerEvents.ErrorDomain.class).equals(new LinkedHashSet<>(registry.enums.get("error_domain"))), "every error domain");
        check(!wires(CustomerEvents.Feature.class).contains("message_filter"), "Android has no message filter");
        check(!wires(CustomerEvents.Screen.class).contains("zg.paywall") && !wires(CustomerEvents.Screen.class).contains("zg.link_account"),
                "the iOS-only screens are not Android's");
        subset(CustomerEvents.SessionTrigger.class, registry.inline("app.session_start", "trigger"), "trigger");
        subset(CustomerEvents.SessionEndReason.class, registry.inline("app.session_end", "reason"), "session end reason");
        check(!wires(CustomerEvents.SessionEndReason.class).contains("terminate"), "Android never claims terminate");
        subset(CustomerEvents.SharedAction.class, registry.inline("tasks.shared", "action"), "shared action");
        subset(CustomerEvents.MigrationPhase.class, registry.inline("tasks.migration", "phase"), "migration phase");
        subset(CustomerEvents.SyncIssueKind.class, registry.inline("tasks.sync_issue", "kind"), "sync issue kind");
        subset(CustomerEvents.UnlockMethod.class, registry.inline("vault.unlocked", "method"), "vault method");
        subset(CustomerEvents.UnlockMethod.class, registry.inline("chat_lock.unlocked", "method"), "chat lock method");
        subset(CustomerEvents.LockMethod.class, registry.inline("chat_lock.lock_set", "method"), "lock_set method");
        check(!wires(CustomerEvents.UnlockMethod.class).contains("pin") && !wires(CustomerEvents.LockMethod.class).contains("pin"),
                "Android never sends the pin method");
        subset(CustomerEvents.EditHistoryKind.class, registry.inline("edit_history.viewed", "kind"), "edit history kind");
        subset(CustomerEvents.ProSource.class, registry.inline("pro.screen_view", "source"), "pro source");
        subset(CustomerEvents.ProMode.class, registry.inline("pro.screen_view", "mode"), "pro mode");
        subset(CustomerEvents.FailReason.class, registry.inline("pro.purchase_fail", "reason"), "fail reason");
        subset(CustomerEvents.RestoreOutcome.class, registry.inline("pro.restore", "outcome"), "restore outcome");
        subset(CustomerEvents.TrialOutcome.class, registry.inline("pro.trial_start", "outcome"), "trial outcome");
        subset(CustomerEvents.LinkTrigger.class, registry.inline("link.attempt", "trigger"), "link trigger");
        subset(CustomerEvents.LinkMethod.class, registry.inline("link.attempt", "method"), "link method");
        subset(CustomerEvents.LinkOutcome.class, registry.inline("link.attempt", "outcome"), "link outcome");
        // The integer and pattern types the builders clamp or check to.
        check(registry.intType("count")[0] == CustomerEvents.COUNT_MIN && registry.intType("count")[1] == CustomerEvents.COUNT_MAX, "count range");
        check(registry.intType("amount")[0] == 0 && registry.intType("amount")[1] == CustomerEvents.AMOUNT_MAX, "amount range");
        check(registry.intType("duration_s")[0] == 0 && registry.intType("duration_s")[1] == CustomerEvents.DURATION_MAX, "duration range");
        check(registry.intType("priority")[0] == 0 && registry.intType("priority")[1] == CustomerEvents.PRIORITY_MAX, "priority range");
    }

    // ---- The validator against the server's own verdicts ----

    private static void validatorAgreesWithTheServer(Registry registry, Path file) throws Exception {
        Map<String, Object> fixture = MiniJson.object(MiniJson.parseFile(file));
        long receivedAt = CustomerJson.parseInstant((String) fixture.get("received_at"));
        check(receivedAt > 0, "received_at");
        int cases = 0;
        for (Object item : MiniJson.array(fixture.get("cases"))) {
            Map<String, Object> testCase = MiniJson.object(item);
            String id = (String) testCase.get("id");
            Map<String, Object> expected = MiniJson.object(testCase.get("expected"));
            Object body = testCase.get("body");
            String batch = batchError(body, (String) testCase.get("binding_platform"));
            if ("batch".equals(testCase.get("reason"))) {
                check(batch != null, id + ": the whole batch is refused");
                String error = (String) expected.get("error");
                Object field = expected.get("field");
                check(batch.equals(error + ":" + (field == null ? "" : field)), id + ": refused as " + error + " at " + field + " (got " + batch + ")");
                continue;
            }
            check(batch == null, id + ": the envelope is valid (" + batch + ")");
            Verdicts verdicts = screen(registry, MiniJson.object(body), receivedAt);
            check(verdicts.accepted == number(expected.get("accepted")), id + ": accepted " + verdicts.accepted);
            check(verdicts.duplicates == number(expected.get("duplicates")), id + ": duplicates");
            check(verdicts.dropped == number(expected.get("dropped_props")), id + ": dropped props " + verdicts.dropped);
            List<Object> rejected = new ArrayList<>();
            for (String[] entry : verdicts.rejected) {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                map.put("id", entry[0]);
                map.put("reason", entry[1]);
                rejected.add(map);
            }
            check(rejected.equals(expected.get("rejected")), id + ": rejected " + rejected);
            if (testCase.containsKey("offending_event")) {
                int index = (int) number(testCase.get("offending_event"));
                check(verdicts.perEvent.get(index).equals(testCase.get("reason")), id + ": the offending event is " + testCase.get("reason"));
            }
            cases++;
        }
        check(cases > 0, "per-event cases");
    }

    // ---- Every event the builders can produce ----

    private static void everyBuiltEventIsValid(Registry registry) {
        List<CustomerEvents.Event> events = allBuilt();
        Set<String> names = new HashSet<>();
        long ts = 1793527200L; // 2026-11-01T10:00:00Z
        String session = uuid();
        int seq = 0;
        List<String> jsons = new ArrayList<>();
        for (CustomerEvents.Event event : events) {
            check(event != null, "a builder with valid input never drops the event");
            names.add(event.name);
            String json = event.json(uuid(), ts, session, seq);
            check(CustomerEvents.utf8Length(json) <= CustomerEvents.MAX_EVENT_BYTES, event + " fits 2 KiB");
            Map<String, Object> tree = MiniJson.object(MiniJson.parse(json));
            check(new ArrayList<>(tree.keySet()).equals(Arrays.asList("id", "name", "ts", "session_id", "seq", "props")),
                    "event keys in the contract order");
            int[] dropped = {0};
            String verdict = registry.props(event.name, MiniJson.object(tree.get("props")), dropped);
            check("ok".equals(verdict) && dropped[0] == 0, event + " is valid: " + verdict);
            jsons.add(json);
            seq++;
        }
        check(names.equals(registry.events.keySet()), "the builders cover every registry event: " + names.size());
        // Inputs a builder must refuse or repair.
        check(CustomerEvents.proPlanSelect("my product", CustomerEvents.Rail.GOOGLE, null) == null, "free text is never a product_id");
        check(CustomerEvents.proPurchaseStart("", CustomerEvents.Rail.GOOGLE, null) == null, "an empty product_id drops the event");
        check(CustomerEvents.proPurchaseSuccess(repeat('a', 101), CustomerEvents.Rail.GOOGLE, null, true) == null, "101 characters drop it");
        check(CustomerEvents.proPurchaseFail(null, CustomerEvents.Rail.GOOGLE, null, CustomerEvents.FailReason.FAILED, null) == null,
                "a null product_id drops it");
        check(!CustomerEvents.proPlanSelect("zenlygram.pro", CustomerEvents.Rail.GOOGLE, "Monthly Plan").propsJson().contains("base_plan_id"),
                "an invalid base_plan_id is omitted");
        check(CustomerEvents.proRestore(CustomerEvents.Rail.GOOGLE, CustomerEvents.RestoreOutcome.FAILED, "java.io.IOException: timeout")
                .propsJson().contains("\"error_code\":\"unknown\""), "an exception text becomes unknown");
        check(CustomerEvents.taskCreated(CustomerEvents.TasksScope.CLOUD, false, 99, false).propsJson().contains("\"priority\":4"), "priority clamps up");
        check(CustomerEvents.taskCreated(CustomerEvents.TasksScope.CLOUD, false, -3, false).propsJson().contains("\"priority\":0"), "priority clamps down");
        check(CustomerEvents.listDeleted(CustomerEvents.TasksScope.LOCAL, 5000000).propsJson().contains("\"tasks\":100000"), "amount clamps");
        check(CustomerEvents.sessionEnd(999999, CustomerEvents.SessionEndReason.BACKGROUND).propsJson().contains("\"duration_s\":86400"),
                "duration clamps");
        check(CustomerEvents.errorOccurred(CustomerEvents.ErrorDomain.OTHER, "x", 0).propsJson().contains("\"count\":1"), "count is at least 1");
        check(CustomerEvents.syncIssue(CustomerEvents.SyncIssueKind.STALLED, 7000).propsJson().contains("\"count\":1000"), "count is at most 1000");
        check(!CustomerEvents.linkAttempt(false, false, false, null, -1).propsJson().contains("duration_s"), "an unknown duration is omitted");
        // A whole batch of them passes the batch rules.
        String body = CustomerEvents.envelope(uuid(), ts, androidContext().json(), jsons.subList(0, Math.min(200, jsons.size())));
        check(batchError(MiniJson.parse(body), "android") == null, "an envelope of built events passes the batch rules");
        Verdicts verdicts = screen(registry, MiniJson.object(MiniJson.parse(body)), ts);
        check(verdicts.rejected.isEmpty() && verdicts.accepted == Math.min(200, jsons.size()), "every built event is accepted: " + verdicts.rejected.size());
    }

    /**
     * One event per builder and input class: every enum value, clamped and boundary integers, optional props present and
     * absent, valid and invalid codes and ids (NeverSentGuardTests serializes the same set).
     */
    static List<CustomerEvents.Event> allBuilt() {
        List<CustomerEvents.Event> out = new ArrayList<>();
        for (CustomerEvents.SessionTrigger trigger : CustomerEvents.SessionTrigger.values()) out.add(CustomerEvents.sessionStart(trigger));
        for (CustomerEvents.SessionEndReason reason : CustomerEvents.SessionEndReason.values()) {
            out.add(CustomerEvents.sessionEnd(0, reason));
            out.add(CustomerEvents.sessionEnd(-5, reason));
            out.add(CustomerEvents.sessionEnd(86401, reason));
        }
        for (CustomerEvents.Screen screen : CustomerEvents.Screen.values()) out.add(CustomerEvents.screenView(screen));
        for (CustomerEvents.Feature feature : CustomerEvents.Feature.values()) {
            out.add(CustomerEvents.featureToggle(feature, true));
            out.add(CustomerEvents.featureToggle(feature, false));
            out.add(CustomerEvents.featureUsed(feature));
        }
        for (CustomerEvents.TasksScope scope : CustomerEvents.TasksScope.values()) {
            for (int priority : new int[]{Integer.MIN_VALUE, -1, 0, 2, 4, 5, Integer.MAX_VALUE}) {
                out.add(CustomerEvents.taskCreated(scope, priority % 2 == 0, priority, priority > 0));
            }
            out.add(CustomerEvents.taskCompleted(scope, true));
            out.add(CustomerEvents.taskCompleted(scope, false));
            out.add(CustomerEvents.taskDeleted(scope));
            out.add(CustomerEvents.listCreated(scope));
            for (int tasks : new int[]{Integer.MIN_VALUE, 0, 7, 100000, 100001}) out.add(CustomerEvents.listDeleted(scope, tasks));
        }
        for (CustomerEvents.SharedAction action : CustomerEvents.SharedAction.values()) out.add(CustomerEvents.tasksShared(action));
        for (CustomerEvents.MigrationPhase phase : CustomerEvents.MigrationPhase.values()) {
            out.add(CustomerEvents.tasksMigration(phase, null, null, null, null, null));
            out.add(CustomerEvents.tasksMigration(phase, 2, 14, 0, 6L, null));
            out.add(CustomerEvents.tasksMigration(phase, -1, 1000000, Integer.MAX_VALUE, Long.MAX_VALUE, "tasks_unavailable"));
            out.add(CustomerEvents.tasksMigration(phase, 0, 0, 0, -10L, "Exception at line 3"));
        }
        out.add(CustomerEvents.vaultUnlocked(true));
        out.add(CustomerEvents.vaultUnlocked(false));
        out.add(CustomerEvents.vaultChatAdded());
        out.add(CustomerEvents.vaultChatRemoved());
        out.add(CustomerEvents.chatLockSet(true));
        out.add(CustomerEvents.chatLockSet(false));
        out.add(CustomerEvents.chatLockUnlocked(true));
        out.add(CustomerEvents.chatLockUnlocked(false));
        out.add(CustomerEvents.chatLockRemoved());
        out.add(CustomerEvents.editHistoryViewed(true));
        out.add(CustomerEvents.editHistoryViewed(false));
        for (CustomerEvents.ProSource source : CustomerEvents.ProSource.values()) {
            for (CustomerEvents.ProMode mode : CustomerEvents.ProMode.values()) out.add(CustomerEvents.proScreenView(source, mode));
        }
        String longProduct = repeat('p', 100);
        String longPlan = "p" + repeat('1', 62);
        String longCode = "e" + repeat('x', 63);
        for (CustomerEvents.Rail rail : CustomerEvents.Rail.values()) {
            for (String plan : new String[]{null, "monthly", longPlan, "Monthly", "-x", ""}) {
                out.add(CustomerEvents.proPlanSelect("zenlygram.pro.monthly", rail, plan));
                out.add(CustomerEvents.proPurchaseStart(longProduct, rail, plan));
                out.add(CustomerEvents.proPurchaseSuccess("Zenly_Pro-1.Year", rail, plan, true));
                out.add(CustomerEvents.proPurchaseSuccess("zenlygram.pro.monthly", rail, plan, false));
            }
            for (CustomerEvents.FailReason reason : CustomerEvents.FailReason.values()) {
                for (String code : new String[]{null, "billing_1", "billing_-3", longCode, "Not a code", "http_503"}) {
                    out.add(CustomerEvents.proPurchaseFail("zenlygram.pro.yearly", rail, "yearly", reason, code));
                }
            }
            for (CustomerEvents.RestoreOutcome outcome : CustomerEvents.RestoreOutcome.values()) {
                out.add(CustomerEvents.proRestore(rail, outcome, null));
                out.add(CustomerEvents.proRestore(rail, outcome, "nothing_to_restore"));
                out.add(CustomerEvents.proRestore(rail, outcome, "UPPER"));
            }
        }
        for (CustomerEvents.TrialOutcome outcome : CustomerEvents.TrialOutcome.values()) {
            out.add(CustomerEvents.proTrialStart(outcome, null));
            out.add(CustomerEvents.proTrialStart(outcome, "trial_already_used"));
            out.add(CustomerEvents.proTrialStart(outcome, "https://example.invalid/x"));
        }
        for (boolean auto : new boolean[]{true, false}) {
            for (boolean attest : new boolean[]{true, false}) {
                for (boolean success : new boolean[]{true, false}) {
                    out.add(CustomerEvents.linkAttempt(auto, attest, success, null, -1));
                    out.add(CustomerEvents.linkAttempt(auto, attest, success, "attest_unavailable", 0));
                    out.add(CustomerEvents.linkAttempt(auto, attest, success, "telegram_bot_invalid", 4));
                    out.add(CustomerEvents.linkAttempt(auto, attest, success, "", 999999));
                }
            }
        }
        for (CustomerEvents.ErrorDomain domain : CustomerEvents.ErrorDomain.values()) {
            out.add(CustomerEvents.errorOccurred(domain, "timeout", 1));
            out.add(CustomerEvents.errorOccurred(domain, ErrorCodes.sqlite(13), 1000));
            out.add(CustomerEvents.errorOccurred(domain, null, 5000));
            out.add(CustomerEvents.errorOccurred(domain, "Disk full: /data/x", -1));
        }
        for (CustomerEvents.SyncIssueKind kind : CustomerEvents.SyncIssueKind.values()) {
            out.add(CustomerEvents.syncIssue(kind, 1));
            out.add(CustomerEvents.syncIssue(kind, 1001));
        }
        return out;
    }

    // ---- The client-built fixture bodies, rebuilt ----

    private static void clientBuiltBodies(Registry registry, Path file) throws Exception {
        Map<String, Object> fixture = MiniJson.object(MiniJson.parseFile(file));
        int rebuilt = 0;
        for (Object item : MiniJson.array(fixture.get("cases"))) {
            Map<String, Object> testCase = MiniJson.object(item);
            if (!Boolean.TRUE.equals(testCase.get("client_built"))) continue;
            Map<String, Object> body = MiniJson.object(testCase.get("body"));
            List<String> jsons = new ArrayList<>();
            for (Object raw : MiniJson.array(body.get("events"))) {
                Map<String, Object> event = MiniJson.object(raw);
                CustomerEvents.Event built = rebuild((String) event.get("name"), MiniJson.object(event.get("props")));
                check(built != null, testCase.get("id") + ": " + event.get("name") + " has a builder");
                String json = built.json((String) event.get("id"), CustomerJson.parseInstant((String) event.get("ts")),
                        (String) event.get("session_id"), (int) number(event.get("seq")));
                check(MiniJson.parse(json).equals(event), testCase.get("id") + ": " + event.get("name") + " rebuilt equal");
                jsons.add(json);
                rebuilt++;
            }
            String envelope = CustomerEvents.envelope((String) body.get("batch_id"), CustomerJson.parseInstant((String) body.get("sent_at")),
                    MiniJson.write(body.get("context")), jsons);
            check(MiniJson.parse(envelope).equals(body), testCase.get("id") + ": the envelope rebuilt equal");
            check(new ArrayList<>(MiniJson.object(MiniJson.parse(envelope)).keySet()).equals(Arrays.asList("schema_version", "taxonomy_version",
                    "batch_id", "sent_at", "context", "events")), "envelope keys in the contract order");
        }
        check(rebuilt >= 29, "every client-built fixture event rebuilt: " + rebuilt);
    }

    /** The builder call that gives a fixture event (wire values mapped back to the Java enums). */
    private static CustomerEvents.Event rebuild(String name, Map<String, Object> props) throws Exception {
        switch (name) {
            case "app.session_start": return CustomerEvents.sessionStart(wire(CustomerEvents.SessionTrigger.class, props.get("trigger")));
            case "app.session_end":
                return CustomerEvents.sessionEnd(number(props.get("duration_s")), wire(CustomerEvents.SessionEndReason.class, props.get("reason")));
            case "screen.view": return CustomerEvents.screenView(CustomerEvents.Screen.of((String) props.get("screen")));
            case "feature.toggle":
                return CustomerEvents.featureToggle(wire(CustomerEvents.Feature.class, props.get("feature")), (Boolean) props.get("enabled"));
            case "feature.used": return CustomerEvents.featureUsed(wire(CustomerEvents.Feature.class, props.get("feature")));
            case "tasks.task_created":
                return CustomerEvents.taskCreated(wire(CustomerEvents.TasksScope.class, props.get("scope")), (Boolean) props.get("has_due"),
                        (int) number(props.get("priority")), (Boolean) props.get("in_list"));
            case "tasks.task_completed":
                return CustomerEvents.taskCompleted(wire(CustomerEvents.TasksScope.class, props.get("scope")), (Boolean) props.get("reopened"));
            case "tasks.task_deleted": return CustomerEvents.taskDeleted(wire(CustomerEvents.TasksScope.class, props.get("scope")));
            case "tasks.list_created": return CustomerEvents.listCreated(wire(CustomerEvents.TasksScope.class, props.get("scope")));
            case "tasks.list_deleted":
                return CustomerEvents.listDeleted(wire(CustomerEvents.TasksScope.class, props.get("scope")), (int) number(props.get("tasks")));
            case "tasks.shared": return CustomerEvents.tasksShared(wire(CustomerEvents.SharedAction.class, props.get("action")));
            case "tasks.migration":
                return CustomerEvents.tasksMigration(wire(CustomerEvents.MigrationPhase.class, props.get("phase")), integer(props.get("lists")),
                        integer(props.get("tasks")), integer(props.get("skipped")),
                        props.containsKey("duration_s") ? (Long) number(props.get("duration_s")) : null, (String) props.get("error_code"));
            case "tasks.sync_issue":
                return CustomerEvents.syncIssue(wire(CustomerEvents.SyncIssueKind.class, props.get("kind")), (int) number(props.get("count")));
            case "vault.unlocked": return CustomerEvents.vaultUnlocked("biometric".equals(props.get("method")));
            case "vault.chat_added": return CustomerEvents.vaultChatAdded();
            case "vault.chat_removed": return CustomerEvents.vaultChatRemoved();
            case "chat_lock.lock_set": return CustomerEvents.chatLockSet((Boolean) props.get("biometric"));
            case "chat_lock.unlocked": return CustomerEvents.chatLockUnlocked("biometric".equals(props.get("method")));
            case "chat_lock.lock_removed": return CustomerEvents.chatLockRemoved();
            case "edit_history.viewed": return CustomerEvents.editHistoryViewed("deleted".equals(props.get("kind")));
            case "pro.screen_view":
                return CustomerEvents.proScreenView(wire(CustomerEvents.ProSource.class, props.get("source")),
                        wire(CustomerEvents.ProMode.class, props.get("mode")));
            case "pro.plan_select":
                return CustomerEvents.proPlanSelect((String) props.get("product_id"), wire(CustomerEvents.Rail.class, props.get("rail")),
                        (String) props.get("base_plan_id"));
            case "pro.purchase_start":
                return CustomerEvents.proPurchaseStart((String) props.get("product_id"), wire(CustomerEvents.Rail.class, props.get("rail")),
                        (String) props.get("base_plan_id"));
            case "pro.purchase_success":
                return CustomerEvents.proPurchaseSuccess((String) props.get("product_id"), wire(CustomerEvents.Rail.class, props.get("rail")),
                        (String) props.get("base_plan_id"), (Boolean) props.get("trial"));
            case "pro.purchase_fail":
                return CustomerEvents.proPurchaseFail((String) props.get("product_id"), wire(CustomerEvents.Rail.class, props.get("rail")),
                        (String) props.get("base_plan_id"), wire(CustomerEvents.FailReason.class, props.get("reason")), (String) props.get("error_code"));
            case "pro.restore":
                return CustomerEvents.proRestore(wire(CustomerEvents.Rail.class, props.get("rail")),
                        wire(CustomerEvents.RestoreOutcome.class, props.get("outcome")), (String) props.get("error_code"));
            case "pro.trial_start":
                return CustomerEvents.proTrialStart(wire(CustomerEvents.TrialOutcome.class, props.get("outcome")), (String) props.get("error_code"));
            case "link.attempt":
                return CustomerEvents.linkAttempt("auto".equals(props.get("trigger")), "attest".equals(props.get("method")),
                        "success".equals(props.get("outcome")), (String) props.get("error_code"),
                        props.containsKey("duration_s") ? number(props.get("duration_s")) : -1);
            case "error.occurred":
                return CustomerEvents.errorOccurred(wire(CustomerEvents.ErrorDomain.class, props.get("domain")), (String) props.get("code"),
                        (int) number(props.get("count")));
            default:
                return null;
        }
    }

    // ---- An Android session's envelope ----

    private static void androidEnvelope(Registry registry, Path file) throws Exception {
        long t0 = 1793523600L; // 2026-11-01T09:00:00Z
        SessionTracker tracker = SessionTracker.fresh(EventRegistryTests::uuid);
        List<SessionTracker.Stamped> stamped = new ArrayList<>(tracker.start(CustomerEvents.SessionTrigger.LAUNCH,
                CustomerEvents.Screen.TG_CHAT_LIST, t0, 1_000));
        long elapsed = 1_000;
        int step = 0;
        for (CustomerEvents.Event event : allBuilt()) {
            elapsed += 31_000;
            stamped.addAll(tracker.record(event, t0 + ++step, elapsed));
            if (step % 7 == 0) tracker.countError(CustomerEvents.ErrorDomain.NETWORK, ErrorCodes.TIMEOUT);
            if (step % 11 == 0) tracker.countSyncIssue(CustomerEvents.SyncIssueKind.STALLED, 2);
        }
        stamped.addAll(tracker.end(CustomerEvents.SessionEndReason.BACKGROUND, t0 + step + 1, elapsed + 5_000));
        CustomerEvents.Context context = androidContext();
        final Map<String, String> contexts = Collections.singletonMap(context.key(), context.json());
        int sent = 0;
        int index = 0;
        while (index < stamped.size()) {
            EventQueuePolicy.Selector selector = new EventQueuePolicy.Selector(EventQueuePolicy.MAX_BATCH_EVENTS, t0 + step + 2,
                    key -> contexts.containsKey(key) ? contexts.get(key).length() : -1);
            List<String> jsons = new ArrayList<>();
            for (int i = index; i < stamped.size(); i++) {
                SessionTracker.Stamped event = stamped.get(i);
                if (!selector.offer(new EventQueuePolicy.Row(i, event.ts, context.key(), event.bytes))) break;
                jsons.add(event.json);
            }
            check(!jsons.isEmpty() && selector.expired().isEmpty(), "a batch every time");
            String body = CustomerEvents.envelope(uuid(), t0 + step + 2, context.json(), jsons);
            check(CustomerEvents.utf8Length(body) == selector.envelopeBytes(), "the selector sizes the envelope exactly");
            check(CustomerEvents.utf8Length(body) <= EventQueuePolicy.MAX_BATCH_BYTES && jsons.size() <= EventQueuePolicy.MAX_BATCH_EVENTS,
                    "≤ 200 events and ≤ 256 KiB");
            check(batchError(MiniJson.parse(body), "android") == null, "an Android batch passes the batch rules for an android binding");
            check(batchError(MiniJson.parse(body), "ios") != null, "context.platform must equal the binding's");
            Verdicts verdicts = screen(registry, MiniJson.object(MiniJson.parse(body)), t0 + step + 2);
            check(verdicts.rejected.isEmpty() && verdicts.duplicates == 0 && verdicts.dropped == 0 && verdicts.accepted == jsons.size(),
                    "every event of an Android session is accepted");
            sent += jsons.size();
            index += jsons.size();
        }
        check(sent == stamped.size() && sent > 400, "the whole session was sent in batches: " + sent);
        // The Android context has exactly the fixture context's keys, in the contract order.
        Map<String, Object> fixture = MiniJson.object(MiniJson.parseFile(file));
        Map<String, Object> first = MiniJson.object(MiniJson.object(MiniJson.object(MiniJson.array(fixture.get("cases")).get(0)).get("body")).get("context"));
        check(new ArrayList<>(MiniJson.object(MiniJson.parse(context.json())).keySet()).equals(new ArrayList<>(first.keySet())),
                "context keys equal the fixture's, in order");
    }

    private static CustomerEvents.Context androidContext() {
        return CustomerEvents.Context.of(false, "14", "12.1.0", "5678", "Google Pixel 8", "ru", "Asia/Tashkent", "play");
    }

    // ---- Device context ----

    private static void deviceContext() {
        CustomerEvents.Context context = androidContext();
        check(context.json().equals("{\"platform\":\"android\",\"device_class\":\"phone\",\"os_version\":\"14\",\"app_version\":\"12.1.0\","
                + "\"app_build\":\"5678\",\"device_model\":\"Google Pixel 8\",\"ui_language\":\"ru\",\"timezone\":\"Asia/Tashkent\","
                + "\"distribution\":\"play\"}"), "the context JSON, keys in contract order");
        check(context.key().equals(ConsentText.sha256Hex(context.json())) && CustomerJson.isHex64(context.key()), "context_key = SHA-256 of the JSON");
        check(context.equals(androidContext()) && !context.equals(CustomerEvents.Context.of(true, "14", "12.1.0", "5678", "Google Pixel 8", "ru",
                "Asia/Tashkent", "play")), "contexts compare by their JSON");
        CustomerEvents.Context odd = CustomerEvents.Context.of(true, "14 (QPR3)é", "12.1.0-dev+build/7", "56a78", "  Google\t\tPixel Tablet ",
                "pt_BR", "Mars/Olympus Mons", "beta");
        check(odd.deviceClass.equals("tablet"), "tablet");
        check(odd.osVersion.equals("14 (QPR3)") && UnicodeText.isVer(odd.osVersion), "os_version in the ver form");
        check(odd.appVersion.equals("12.1.0-dev+build7") && UnicodeText.isVer(odd.appVersion), "app_version in the ver form");
        check(odd.appBuild == null, "a non-decimal build is null");
        check("Google Pixel Tablet".equals(odd.deviceModel), "the model: printable ASCII, whitespace collapsed");
        check("pt-br".equals(odd.uiLanguage), "ui_language lower-cased, '_' read as '-'");
        check(odd.timezone == null, "a timezone outside iana_tz is null");
        check(odd.distribution.equals("direct"), "an unknown distribution is direct");
        check(odd.json().contains("\"app_build\":null") && odd.json().contains("\"timezone\":null"), "descriptive nulls are written as null");
        CustomerEvents.Context empty = CustomerEvents.Context.of(false, "", null, null, "\u0001", "local_1234", null, null);
        check(empty.osVersion.equals("0") && empty.appVersion.equals("0"), "an empty version is \"0\" (always a ver)");
        check(empty.deviceModel == null && empty.uiLanguage == null && empty.timezone == null, "no model, language or zone");
        for (String language : new String[]{"uz", "ru", "en", "pt-br", "zh-hans", "UZ", "uz_latn_uz", "en-US"}) {
            check(CustomerEvents.Context.uiLanguage(language) != null, language + " is a ui_language");
        }
        for (String language : new String[]{"", "u", "uzbe", "uz-", "uz-x", "uz-latn-uz-extra", "local_1", "uz beta"}) {
            check(CustomerEvents.Context.uiLanguage(language) == null, "'" + language + "' is not");
        }
        for (String distribution : new String[]{"debug", "play", "direct", "appstore", "testflight"}) {
            check(CustomerEvents.Context.distribution(distribution).equals(distribution), distribution + " kept");
        }
        check(EventIngestUiLanguage.primary("pt-br").equals("pt") && EventIngestUiLanguage.primary("zh-hans").equals("zh"),
                "the server keeps the primary subtag of the client form");
    }

    /** The server's ui_language reading (EventIngest::uiLanguage): the lower-cased primary subtag. */
    private static final class EventIngestUiLanguage {
        private static final Pattern FORM = Pattern.compile("([A-Za-z]{2,3})(?:[-_][A-Za-z0-9]{1,8}){0,3}");

        static String primary(String value) {
            java.util.regex.Matcher matcher = FORM.matcher(value);
            return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
        }
    }

    // ---- The test-side validator (contract §3.1, §3.2, §3.5; ProfileBoundary + EventIngest rules) ----

    /** The vendored registry, read with MiniJson. */
    static final class Registry {
        final long version;
        final Map<String, Map<String, Object>> types = new LinkedHashMap<>();
        final Map<String, List<String>> enums = new LinkedHashMap<>();
        final Map<String, Map<String, Map<String, Object>>> events = new LinkedHashMap<>();

        private Registry(long version) { this.version = version; }

        static Registry load(Path file) throws Exception {
            Map<String, Object> tree = MiniJson.object(MiniJson.parseFile(file));
            Registry registry = new Registry(number(tree.get("taxonomy_version")));
            for (Map.Entry<String, Object> type : MiniJson.object(tree.get("types")).entrySet()) {
                registry.types.put(type.getKey(), MiniJson.object(type.getValue()));
            }
            for (Map.Entry<String, Object> values : MiniJson.object(tree.get("enums")).entrySet()) {
                registry.enums.put(values.getKey(), strings(values.getValue()));
            }
            for (Map.Entry<String, Object> event : MiniJson.object(tree.get("events")).entrySet()) {
                Map<String, Map<String, Object>> props = new LinkedHashMap<>();
                for (Map.Entry<String, Object> prop : MiniJson.object(event.getValue()).entrySet()) {
                    props.put(prop.getKey(), MiniJson.object(prop.getValue()));
                }
                registry.events.put(event.getKey(), props);
            }
            return registry;
        }

        /** An event's own enum list ({"enum": [...]}). */
        List<String> inline(String event, String prop) { return strings(events.get(event).get(prop).get("enum")); }

        /** {min, max} of an integer type. */
        long[] intType(String name) {
            Map<String, Object> type = types.get(name);
            return new long[]{number(type.get("min")), number(type.get("max"))};
        }

        /** "ok", "unknown_event" or "invalid_props" for {@code name} with {@code props}; undeclared props are counted in dropped[0]. */
        String props(String name, Map<String, Object> props, int[] dropped) {
            Map<String, Map<String, Object>> rules = events.get(name);
            if (rules == null) return "unknown_event";
            int undeclared = 0;
            for (Map.Entry<String, Object> prop : props.entrySet()) {
                Map<String, Object> rule = rules.get(prop.getKey());
                if (rule == null) {
                    undeclared++;
                } else if (!accepts(rule, prop.getValue())) {
                    return "invalid_props";
                }
            }
            for (Map.Entry<String, Map<String, Object>> rule : rules.entrySet()) {
                if (Boolean.TRUE.equals(rule.getValue().get("required")) && !props.containsKey(rule.getKey())) return "invalid_props";
            }
            dropped[0] += undeclared;
            return "ok";
        }

        private boolean accepts(Map<String, Object> rule, Object value) {
            Object type = rule.get("type");
            if ("boolean".equals(type)) return value instanceof Boolean;
            if (type instanceof String) {
                Map<String, Object> definition = types.get(type);
                if (definition == null) return false;
                if ("string".equals(definition.get("type"))) {
                    return value instanceof String && anchored((String) definition.get("pattern")).matcher((String) value).matches();
                }
                if ("integer".equals(definition.get("type"))) {
                    if (!(value instanceof Integer || value instanceof Long)) return false;
                    long number = ((Number) value).longValue();
                    return number >= number(definition.get("min")) && number <= number(definition.get("max"));
                }
                return false;
            }
            Object values = rule.get("enum");
            List<String> allowed = values instanceof String ? enums.get(values) : strings(values);
            return value instanceof String && allowed != null && allowed.contains(value);
        }
    }

    /** The per-event verdicts of one valid envelope (EventIngest::screen). */
    static final class Verdicts {
        int accepted, duplicates, dropped;
        final List<String[]> rejected = new ArrayList<>();
        /** Per event: ok, duplicate or the rejection reason. */
        final List<String> perEvent = new ArrayList<>();
    }

    static Verdicts screen(Registry registry, Map<String, Object> body, long receivedAt) {
        Verdicts verdicts = new Verdicts();
        long sentAt = CustomerJson.parseInstant((String) body.get("sent_at"));
        long skew = receivedAt - sentAt;
        long shift = Math.abs(skew) > 120 ? skew : 0;
        Set<String> accepted = new HashSet<>();
        Map<String, Long> lastSeq = new HashMap<>();
        for (Object item : MiniJson.array(body.get("events"))) {
            String reason = envelopeReason(item);
            if (reason != null) {
                reject(verdicts, echoedId(item), reason);
                continue;
            }
            Map<String, Object> event = MiniJson.object(item);
            String id = (String) event.get("id");
            if (accepted.contains(id)) {
                verdicts.duplicates++;
                verdicts.perEvent.add("duplicate");
                continue;
            }
            String session = (String) event.get("session_id");
            long seq = number(event.get("seq"));
            if (lastSeq.containsKey(session) && seq <= lastSeq.get(session)) {
                reject(verdicts, id, "invalid_envelope");
                continue;
            }
            lastSeq.put(session, seq);
            int[] dropped = {0};
            String verdict = registry.props((String) event.get("name"), MiniJson.object(event.get("props")), dropped);
            long effective = CustomerJson.parseInstant((String) event.get("ts")) + shift;
            if (!"ok".equals(verdict)) {
                reject(verdicts, id, verdict);
            } else if (effective < receivedAt - 7 * 86400) {
                reject(verdicts, id, "stale");
            } else if (effective > receivedAt + 300) {
                reject(verdicts, id, "future");
            } else {
                accepted.add(id);
                verdicts.accepted++;
                verdicts.dropped += dropped[0];
                verdicts.perEvent.add("ok");
            }
        }
        return verdicts;
    }

    private static void reject(Verdicts verdicts, String id, String reason) {
        verdicts.rejected.add(new String[]{id, reason});
        verdicts.perEvent.add(reason);
    }

    /** invalid_envelope when the item is not an event object of contract §3.1 within 2048 bytes, else null. */
    private static String envelopeReason(Object item) {
        if (!(item instanceof Map)) return "invalid_envelope";
        if (MiniJson.write(item).getBytes(StandardCharsets.UTF_8).length > CustomerEvents.MAX_EVENT_BYTES) return "invalid_envelope";
        Map<String, Object> event = MiniJson.object(item);
        if (!new HashSet<>(Arrays.asList("id", "name", "ts", "session_id", "seq", "props")).equals(event.keySet())) return "invalid_envelope";
        if (!(event.get("name") instanceof String) || !(event.get("props") instanceof Map)) return "invalid_envelope";
        if (!(event.get("id") instanceof String) || !CustomerJson.isUuid4((String) event.get("id"))) return "invalid_envelope";
        if (!(event.get("ts") instanceof String) || !CustomerJson.isInstant((String) event.get("ts"))) return "invalid_envelope";
        if (!(event.get("session_id") instanceof String) || !CustomerJson.isUuid4((String) event.get("session_id"))) return "invalid_envelope";
        Object seq = event.get("seq");
        if (!(seq instanceof Integer || seq instanceof Long) || number(seq) < 0 || number(seq) > CustomerEvents.MAX_SEQ) return "invalid_envelope";
        return null;
    }

    private static String echoedId(Object item) {
        Object id = item instanceof Map ? ((Map<?, ?>) item).get("id") : null;
        return id instanceof String && ((String) id).getBytes(StandardCharsets.UTF_8).length <= 64 ? (String) id : null;
    }

    private static final List<String> ENVELOPE_KEYS = Arrays.asList("schema_version", "taxonomy_version", "batch_id", "sent_at", "context", "events");
    private static final List<String> CONTEXT_KEYS = Arrays.asList("platform", "device_class", "os_version", "app_version", "app_build",
            "device_model", "ui_language", "timezone", "distribution");
    private static final Pattern VER = Pattern.compile("[0-9A-Za-z._+() -]{1,32}");

    /** "<error>:<field>" when the server refuses the whole batch (ProfileBoundary, then EventIngest::batch), else null. */
    static String batchError(Object value, String bindingPlatform) {
        if (!(value instanceof Map)) return "invalid_request:$";
        Map<String, Object> body = MiniJson.object(value);
        Object schema = body.get("schema_version");
        if (!(schema instanceof Integer || schema instanceof Long)) return "invalid_request:schema_version";
        if (number(schema) != 1) return "unsupported_schema_version:";
        for (String key : new String[]{"refresh_token", "device_id", "nonce", "ts", "sig"}) {
            if (body.containsKey(key)) return "invalid_request:" + key;
        }
        String keys = keys(body, "", ENVELOPE_KEYS);
        if (keys != null) return keys;
        Object taxonomy = body.get("taxonomy_version");
        if (!(taxonomy instanceof Integer || taxonomy instanceof Long) || number(taxonomy) < 1 || number(taxonomy) > 32767) {
            return "invalid_request:taxonomy_version";
        }
        if (!(body.get("batch_id") instanceof String) || !CustomerJson.isUuid4((String) body.get("batch_id"))) return "invalid_request:batch_id";
        if (!(body.get("sent_at") instanceof String) || !CustomerJson.isInstant((String) body.get("sent_at"))) return "invalid_request:sent_at";
        if (!(body.get("context") instanceof Map)) return "invalid_request:context";
        Map<String, Object> context = MiniJson.object(body.get("context"));
        keys = keys(context, "context.", CONTEXT_KEYS);
        if (keys != null) return keys;
        Object platform = context.get("platform");
        if (!"ios".equals(platform) && !"android".equals(platform)) return "invalid_request:context.platform";
        if (("ios".equals(bindingPlatform) || "android".equals(bindingPlatform)) && !platform.equals(bindingPlatform)) {
            return "invalid_request:context.platform";
        }
        if (!"phone".equals(context.get("device_class")) && !"tablet".equals(context.get("device_class"))) return "invalid_request:context.device_class";
        if (!(context.get("os_version") instanceof String) || !VER.matcher((String) context.get("os_version")).matches()) {
            return "invalid_request:context.os_version";
        }
        if (!(context.get("app_version") instanceof String) || !VER.matcher((String) context.get("app_version")).matches()) {
            return "invalid_request:context.app_version";
        }
        if (!CustomerEvents.Context.DISTRIBUTIONS.contains(context.get("distribution"))) return "invalid_request:context.distribution";
        Object events = body.get("events");
        if (!(events instanceof List) || ((List<?>) events).isEmpty() || ((List<?>) events).size() > 200) return "invalid_request:events";
        return null;
    }

    /** ProfileWire::object: the first undeclared key (object order), then the first missing one (declared order). */
    private static String keys(Map<String, Object> object, String prefix, List<String> declared) {
        for (String key : object.keySet()) {
            if (!declared.contains(key)) return "invalid_request:" + prefix + key;
        }
        for (String key : declared) {
            if (!object.containsKey(key)) return "invalid_request:" + prefix + key;
        }
        return null;
    }

    // ---- helpers ----

    private static Pattern anchored(String pattern) {
        String body = pattern;
        if (body.startsWith("^")) body = body.substring(1);
        if (body.endsWith("$")) body = body.substring(0, body.length() - 1);
        return Pattern.compile(body);
    }

    private static <E extends Enum<E>> void subset(Class<E> type, List<String> registry, String label) throws Exception {
        check(registry != null && registry.containsAll(wires(type)), label + ": the Java values are registry values " + wires(type));
    }

    static <E extends Enum<E>> Set<String> wires(Class<E> type) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            Field field = type.getField("wire");
            for (E value : type.getEnumConstants()) out.add((String) field.get(value));
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError(type + " has a wire field");
        }
        return out;
    }

    private static <E extends Enum<E>> E wire(Class<E> type, Object wire) throws Exception {
        Field field = type.getField("wire");
        for (E value : type.getEnumConstants()) {
            if (field.get(value).equals(wire)) return value;
        }
        throw new AssertionError(type.getSimpleName() + " has no value " + wire);
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object item : MiniJson.array(value)) out.add((String) item);
        return out;
    }

    static long number(Object value) {
        if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
        throw new AssertionError("not an integer: " + value);
    }

    private static Integer integer(Object value) { return value == null ? null : (int) number(value); }

    static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    static String uuid() { return String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", ++ids); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
