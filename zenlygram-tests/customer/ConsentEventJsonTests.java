package org.telegram.messenger.zenlygram.customer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Plain-JVM checks of the ConsentEvent JSON (contract §2.4.1, design 50 §5.7) and of CustomerJson: the key sets and
 * key order of fixtures/consents-valid.json (a grant, a withdraw with and without text_version), the exact bytes of
 * every fixture event after a parse and re-serialization, the request bodies (one grant alone or 1..10 withdrawals),
 * refused fields, and the writer's escaping, instants and shape checks. Run with run-tests.sh, which passes the Android
 * repository root.
 */
public final class ConsentEventJsonTests {
    private static int checks;
    private static final String ID = "3f2a6c1e-8b4d-4e2a-9c8b-1f2e3d4c5b6a";
    private static final String SHA = "229fd61b5ac4c2711e1b9635b6e5b24ab44ab5564baac8a2c1067a5b127f64d9";
    private static final List<String> SCOPES = ConsentManifest.SCOPES;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ConsentEventJsonTests <android repository root>");
        Path fixtures = Paths.get(args[0]).resolve("zenlygram-tests/customer/fixtures");
        fixtureEvents(fixtures);
        exactJson();
        requestBodies();
        refusedEvents();
        refusedTrees();
        writer();
        instants();
        shapes();
        System.out.println("ConsentEvent JSON: " + checks + " checks passed");
    }

    /** fixtures/consents-valid.json: key sets, key order and exact bytes of every event; the single-action bodies. */
    private static void fixtureEvents(Path fixtures) throws Exception {
        Map<String, Object> file = MiniJson.object(MiniJson.parseFile(fixtures.resolve("consents-valid.json")));
        long tg = ((Number) file.get("tg_user_id")).longValue();
        int events = 0;
        List<String> grantKeys = null, withdrawKeys = null, withdrawKeysWithoutVersion = null;
        for (Object item : MiniJson.array(file.get("cases"))) {
            Map<String, Object> testCase = MiniJson.object(item);
            Map<String, Object> body = MiniJson.object(testCase.get("body"));
            List<ConsentEvent> parsed = new ArrayList<>();
            for (Object consent : MiniJson.array(body.get("consents"))) {
                Map<String, Object> tree = MiniJson.object(consent);
                ConsentEvent event = ConsentEvent.fromTree(tree);
                check(event != null, testCase.get("id") + ": a valid ConsentEvent");
                check(event.json().equals(MiniJson.write(tree)), testCase.get("id") + ": re-serialized byte for byte, keys in the §5.7 order");
                check(event.tgUserId == tg, "the fixture's Telegram id");
                List<String> keys = new ArrayList<>(tree.keySet());
                if (event.isGrant()) {
                    grantKeys = keys;
                } else if (tree.containsKey("text_version")) {
                    withdrawKeys = keys;
                } else {
                    withdrawKeysWithoutVersion = keys;
                }
                parsed.add(event);
                events++;
            }
            boolean mixed = parsed.size() > 1 && parsed.get(0).isGrant() != parsed.get(1).isGrant();
            if (mixed) {
                refuseBody(parsed, testCase.get("id") + ": a grant is always sent alone");
            } else {
                check(ConsentEvent.requestBody(parsed).equals(MiniJson.write(body)), testCase.get("id") + ": the request body byte for byte");
            }
        }
        check(events == 4, "four fixture events");
        // Our own events have exactly the fixture's key sets, in the same order.
        ConsentEvent grant = ConsentEvent.grant(ID, tg, SCOPES, 1, SHA, "uz", "required", 1793523600L, "12.1.0");
        ConsentEvent withdraw = ConsentEvent.withdraw("9b2d4f6a-8c0e-4a1b-b3d5-e7f9a1c3e5d7", tg, SCOPES, 1, "ru", "optional", 1793525400L, "12.1.0");
        ConsentEvent bare = ConsentEvent.withdraw("e1f3a5c7-b9d1-4e3f-a5b7-c9d1e3f5a7b9", tg, SCOPES, 0, "ru", "required", 1793523900L, "12.1.0");
        check(keys(grant).equals(grantKeys), "grant keys equal the fixture's");
        check(keys(withdraw).equals(withdrawKeys), "withdraw keys equal the fixture's");
        check(keys(bare).equals(withdrawKeysWithoutVersion), "withdraw keys without text_version equal the fixture's");
        check(grant.isGrant() && !grant.isWithdraw() && withdraw.isWithdraw() && "android".equals(grant.platform), "action and platform");
    }

    private static void exactJson() {
        ConsentEvent grant = ConsentEvent.grant(ID, 555000111L, SCOPES, 1, SHA, "uz", "required", 1793268000L, "1.0.0");
        check(("{\"consent_id\":\"" + ID + "\",\"tg_user_id\":555000111,\"action\":\"grant\",\"scopes\":[\"link\",\"profile\",\"statistics\","
                + "\"tasks_sync\"],\"text_version\":1,\"text_sha256\":\"" + SHA + "\",\"locale\":\"uz\",\"consent_mode\":\"required\","
                + "\"age_confirmed\":true,\"recorded_at\":\"2026-10-29T10:00:00Z\",\"platform\":\"android\",\"app_version\":\"1.0.0\"}")
                .equals(grant.json()), "the exact grant JSON of design 50 §5.7");
        ConsentEvent withdraw = ConsentEvent.withdraw(ID, 555000111L, SCOPES, 1, "en", "optional", 1793268000L, "1.0.0");
        check(("{\"consent_id\":\"" + ID + "\",\"tg_user_id\":555000111,\"action\":\"withdraw\",\"scopes\":[\"link\",\"profile\",\"statistics\","
                + "\"tasks_sync\"],\"text_version\":1,\"locale\":\"en\",\"consent_mode\":\"optional\",\"recorded_at\":\"2026-10-29T10:00:00Z\","
                + "\"platform\":\"android\",\"app_version\":\"1.0.0\"}").equals(withdraw.json()), "the exact withdraw JSON");
        check(ConsentEvent.fromTree(MiniJson.parse(grant.json())).equals(grant) && ConsentEvent.fromTree(MiniJson.parse(withdraw.json())).equals(withdraw),
                "events round-trip through their JSON");
        check("0".equals(ConsentEvent.grant(ID, 1, SCOPES, 1, SHA, "uz", "required", 0, "é").appVersion)
                && "12.1.0 (7112)".equals(ConsentEvent.grant(ID, 1, SCOPES, 1, SHA, "uz", "required", 0, "12.1.0 (7112)").appVersion)
                && "1.0".equals(ConsentEvent.grant(ID, 1, SCOPES, 1, SHA, "uz", "required", 0, "1.0é").appVersion),
                "app_version is clipped to ver");
        check(ConsentEvent.grant(ID, ConsentEvent.MAX_TELEGRAM_USER_ID, SCOPES, 32767, SHA, "ru", "optional", CustomerJson.MAX_INSTANT, "x").json()
                .contains("\"tg_user_id\":9007199254740991,"), "the largest Telegram id is written as an integer");
        try {
            grant.scopes.add("x");
            check(false, "event scopes are immutable");
        } catch (UnsupportedOperationException expected) {
            check(true, "event scopes are immutable");
        }
    }

    private static void requestBodies() {
        List<ConsentEvent> withdrawals = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            withdrawals.add(ConsentEvent.withdraw(String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", i), 7L, SCOPES, 1, "uz", "required", 1000 + i / 2, "1"));
        }
        for (int count = 1; count <= 10; count++) {
            String body = ConsentEvent.requestBody(withdrawals.subList(0, count));
            Map<String, Object> tree = MiniJson.object(MiniJson.parse(body));
            check(Integer.valueOf(1).equals(tree.get("schema_version")) && MiniJson.array(tree.get("consents")).size() == count
                    && tree.keySet().equals(new LinkedHashSet<>(Arrays.asList("schema_version", "consents"))), count + " withdrawals in one body");
            check(body.startsWith("{\"schema_version\":1,\"consents\":[" + withdrawals.get(0).json()), "events are embedded verbatim");
        }
        refuseBody(withdrawals, "11 withdrawals");
        refuseBody(Collections.<ConsentEvent>emptyList(), "an empty batch");
        ConsentEvent grant = ConsentEvent.grant(ID, 7L, SCOPES, 1, SHA, "uz", "required", 2000, "1");
        check(ConsentEvent.requestBody(Collections.singletonList(grant)).equals("{\"schema_version\":1,\"consents\":[" + grant.json() + "]}"),
                "one grant alone");
        refuseBody(Arrays.asList(grant, grant), "two grants");
        refuseBody(Arrays.asList(withdrawals.get(0), grant), "a withdrawal with a grant");
        refuseBody(Arrays.asList(grant, withdrawals.get(0)), "a grant with a withdrawal");
        refuseBody(Arrays.asList(withdrawals.get(4), withdrawals.get(0)), "descending recorded_at");
        refuseBody(Arrays.asList(withdrawals.get(0),
                ConsentEvent.withdraw(ID, 8L, SCOPES, 1, "uz", "required", 1000, "1")), "two Telegram users");
        check(ConsentEvent.requestBody(Arrays.asList(withdrawals.get(0), withdrawals.get(1))).length() > 0, "equal recorded_at is ascending");
    }

    private static void refusedEvents() {
        refuseGrant(() -> ConsentEvent.grant(ID.toUpperCase(), 1, SCOPES, 1, SHA, "uz", "required", 0, "1"), "an upper-case consent id");
        refuseGrant(() -> ConsentEvent.grant("3f2a6c1e-8b4d-1e2a-9c8b-1f2e3d4c5b6a", 1, SCOPES, 1, SHA, "uz", "required", 0, "1"), "a version-1 uuid");
        refuseGrant(() -> ConsentEvent.grant(ID, 0, SCOPES, 1, SHA, "uz", "required", 0, "1"), "Telegram id 0");
        refuseGrant(() -> ConsentEvent.grant(ID, ConsentEvent.MAX_TELEGRAM_USER_ID + 1, SCOPES, 1, SHA, "uz", "required", 0, "1"), "id above 2^53-1");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, Collections.<String>emptyList(), 1, SHA, "uz", "required", 0, "1"), "no scopes");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, Arrays.asList("link", "link"), 1, SHA, "uz", "required", 0, "1"), "repeated scopes");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, Arrays.asList("ads"), 1, SHA, "uz", "required", 0, "1"), "an unknown scope");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 0, SHA, "uz", "required", 0, "1"), "version 0");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 32768, SHA, "uz", "required", 0, "1"), "version 32768");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 1, SHA.toUpperCase(), "uz", "required", 0, "1"), "an upper-case hash");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 1, null, "uz", "required", 0, "1"), "a grant without a hash");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 1, SHA, "de", "required", 0, "1"), "another locale");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 1, SHA, "uz", "maybe", 0, "1"), "another mode");
        refuseGrant(() -> ConsentEvent.grant(ID, 1, SCOPES, 1, SHA, "uz", "required", -1, "1"), "a time before 1970");
        refuseGrant(() -> ConsentEvent.withdraw(ID, 1, SCOPES, 32768, "uz", "required", 0, "1"), "a withdrawn version 32768");
        check(!ConsentEvent.withdraw(ID, 1, SCOPES, -3, "uz", "required", 0, "1").json().contains("text_version"),
                "a withdrawal without a known version has no text_version");
    }

    private static void refusedTrees() {
        String grant = ConsentEvent.grant(ID, 555000111L, SCOPES, 1, SHA, "uz", "required", 1793268000L, "1.0.0").json();
        String withdraw = ConsentEvent.withdraw(ID, 555000111L, SCOPES, 1, "uz", "required", 1793268000L, "1.0.0").json();
        refuseTree(grant, m -> m.put("extra", 1), "an unknown key");
        refuseTree(grant, m -> m.remove("age_confirmed"), "a grant without age_confirmed");
        refuseTree(grant, m -> m.put("age_confirmed", false), "age_confirmed false");
        refuseTree(grant, m -> m.remove("text_version"), "a grant without text_version");
        refuseTree(grant, m -> m.put("text_version", "1"), "a text_version string");
        refuseTree(grant, m -> m.put("text_version", 1.0), "a text_version fraction");
        refuseTree(grant, m -> m.put("tg_user_id", "555000111"), "a Telegram id string");
        refuseTree(grant, m -> m.put("recorded_at", "2026-10-29T10:00:00.000Z"), "a fractional instant");
        refuseTree(grant, m -> m.put("platform", "web"), "another platform");
        refuseTree(grant, m -> m.put("app_version", "1/2"), "an app_version outside ver");
        refuseTree(grant, m -> m.remove("locale"), "a missing locale");
        refuseTree(grant, m -> m.put("action", "Grant"), "another action");
        refuseTree(withdraw, m -> m.put("text_sha256", SHA), "a withdrawal with text_sha256");
        refuseTree(withdraw, m -> m.put("age_confirmed", true), "a withdrawal with age_confirmed");
        refuseTree(withdraw, m -> m.put("text_version", 0), "a withdrawal of version 0");
        check(ConsentEvent.fromTree("event") == null && ConsentEvent.fromTree(null) == null, "not an object");
        Map<String, Object> nullVersion = MiniJson.object(MiniJson.parse(withdraw));
        nullVersion.put("text_version", null);
        ConsentEvent event = ConsentEvent.fromTree(nullVersion);
        check(event != null && event.textVersion == 0 && !event.json().contains("text_version"), "a withdrawal's text_version null reads as absent");
        Map<String, Object> ios = MiniJson.object(MiniJson.parse(grant));
        ios.put("platform", "ios");
        check(ConsentEvent.fromTree(ios) != null, "an iOS event is a valid ConsentEvent");
    }

    private static void writer() {
        check("\"\\\"\\\\\\n\\r\\t\\b\\f\\u0001\\u001f\\u2028\\u2029/é😀\"".equals(
                CustomerJson.quote("\"\\\n\r\t\b\f\u0001\u001F  /é😀")), "escaping: quotes, controls, line separators only");
        String text = "a\"b\\c\u0000d e/ó";
        check(text.equals(MiniJson.parse(CustomerJson.quote(text))), "the escaped string parses back");
        CustomerJson.Obj object = new CustomerJson.Obj().put("b", 1).put("a", Arrays.asList("x", 2L, true, null)).put("c", (Object) null)
                .put("d", new CustomerJson.Obj().put("e", false)).put("f", new CustomerJson.Raw("{\"g\":[]}")).put("b", 3);
        check("{\"b\":3,\"a\":[\"x\",2,true,null],\"c\":null,\"d\":{\"e\":false},\"f\":{\"g\":[]}}".equals(object.toString()),
                "insertion order; a replaced key keeps its place; raw JSON verbatim");
        check("[\"a\",\"b\"]".equals(CustomerJson.stringArray(Arrays.asList("a", "b"))) && "[]".equals(CustomerJson.stringArray(Collections.<String>emptyList())),
                "string arrays");
        try {
            CustomerJson.write(1.5);
            check(false, "fractions are never written");
        } catch (IllegalArgumentException expected) {
            check(true, "fractions are never written");
        }
        try {
            new CustomerJson.Raw("");
            check(false, "empty raw JSON is refused");
        } catch (IllegalArgumentException expected) {
            check(true, "empty raw JSON is refused");
        }
    }

    private static void instants() {
        long[] seconds = {0, 951782400L, 4107542400L, 1792512000L, CustomerJson.MAX_INSTANT, 1761991200L};
        String[] texts = {"1970-01-01T00:00:00Z", "2000-02-29T00:00:00Z", "2100-03-01T00:00:00Z", "2026-10-20T16:00:00Z",
                "9999-12-31T23:59:59Z", "2025-11-01T10:00:00Z"};
        for (int i = 0; i < seconds.length; i++) {
            check(texts[i].equals(CustomerJson.instant(seconds[i])), "instant " + seconds[i]);
            check(CustomerJson.parseInstant(texts[i]) == seconds[i], "parse " + texts[i]);
        }
        check("1970-01-01T00:00:00Z".equals(CustomerJson.instant(-5)) && "9999-12-31T23:59:59Z".equals(CustomerJson.instant(CustomerJson.MAX_INSTANT + 9)),
                "instants are clamped");
        Random random = new Random(20261101L);
        for (int i = 0; i < 20000; i++) {
            long value = (long) (random.nextDouble() * CustomerJson.MAX_INSTANT);
            if (CustomerJson.parseInstant(CustomerJson.instant(value)) != value) check(false, "round trip of " + value);
        }
        check(true, "20000 random instants round-trip");
        for (String refused : new String[] {"2026-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-00-10T00:00:00Z", "2026-04-31T00:00:00Z",
                "2026-01-01T24:00:00Z", "2026-01-01T00:60:00Z", "2026-01-01T00:00:60Z", "2026-01-01T00:00:00", "2026-01-01T00:00:00.000Z",
                "2026-1-01T00:00:00Z", "1969-12-31T23:59:59Z", "+026-01-01T00:00:00Z", "2026-01-01 00:00:00Z", "2026-01-01T00:00:00z",
                "2026-01-0aT00:00:00Z", "", null}) {
            check(CustomerJson.parseInstant(refused) == -1 && !CustomerJson.isInstant(refused), "not an instant: " + refused);
        }
        check(CustomerJson.parseInstant("2024-02-29T00:00:00Z") > 0 && CustomerJson.parseInstant("2000-02-29T23:59:59Z") > 0, "leap days");
    }

    private static void shapes() {
        check(CustomerJson.isUuid4(ID) && CustomerJson.isUuid4("00000000-0000-4000-b000-000000000000"), "uuid4");
        for (String refused : new String[] {ID.toUpperCase(), "3f2a6c1e-8b4d-5e2a-9c8b-1f2e3d4c5b6a", "3f2a6c1e-8b4d-4e2a-7c8b-1f2e3d4c5b6a",
                "3f2a6c1e8b4d4e2a9c8b1f2e3d4c5b6a", "3f2a6c1e-8b4d-4e2a-9c8b-1f2e3d4c5b6", "{3f2a6c1e-8b4d-4e2a-9c8b-1f2e3d4c5b6a}", "", null}) {
            check(!CustomerJson.isUuid4(refused), "not a uuid4: " + refused);
        }
        check(CustomerJson.isHex64(SHA) && !CustomerJson.isHex64(SHA.toUpperCase()) && !CustomerJson.isHex64(SHA.substring(1))
                && !CustomerJson.isHex64(SHA.substring(1) + "g") && !CustomerJson.isHex64(null), "hex64");
        check(Long.valueOf(5).equals(CustomerJson.integral(5)) && Long.valueOf(5).equals(CustomerJson.integral(5L)) && CustomerJson.integral(5.0) == null
                && CustomerJson.integral("5") == null, "integral numbers only");
        check(CustomerJson.strings(Arrays.asList("a", 1)) == null && CustomerJson.strings("a") == null
                && Arrays.asList("a", "b").equals(CustomerJson.strings(Arrays.asList("a", "b"))), "string lists");
    }

    private interface Factory { ConsentEvent make(); }

    private interface Mutation { void apply(Map<String, Object> event); }

    private static void refuseGrant(Factory factory, String label) {
        try {
            factory.make();
            check(false, "refused: " + label);
        } catch (IllegalArgumentException expected) {
            check(true, label);
        }
    }

    private static void refuseTree(String json, Mutation mutation, String label) {
        Map<String, Object> tree = MiniJson.object(MiniJson.parse(json));
        check(ConsentEvent.fromTree(tree) != null, "the unmutated event parses (" + label + ")");
        mutation.apply(tree);
        check(ConsentEvent.fromTree(tree) == null, "fromTree refuses " + label);
    }

    private static void refuseBody(List<ConsentEvent> events, String label) {
        try {
            ConsentEvent.requestBody(events);
            check(false, "requestBody refuses " + label);
        } catch (IllegalArgumentException expected) {
            check(true, label);
        }
    }

    private static List<String> keys(ConsentEvent event) { return new ArrayList<>(MiniJson.object(MiniJson.parse(event.json())).keySet()); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
