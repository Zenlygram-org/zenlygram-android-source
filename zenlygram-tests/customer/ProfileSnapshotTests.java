package org.telegram.messenger.zenlygram.customer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM checks of the profile/sync body (contract §2.4.3, §0.3; design 50 §9.1–§9.2): every client-built body of the
 * backend's shared fixture {@code fixtures/profile-sync-valid.json} is reproduced exactly (as parsed JSON, keys in
 * contract order) from the raw values Android reports; the phone is "+" digits or null; usernames ordered, filtered,
 * deduplicated and bounded; names controls-first-then-NFC (the bug class the iOS verifier found), trimmed, cut at a
 * grapheme boundary and null when empty; versions in the {@code ver} form; {@code ui_language} in the iOS form;
 * {@code locale} as {@code bcp47}; the digest ignores {@code observed_at}. Run with run-tests.sh.
 */
public final class ProfileSnapshotTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ProfileSnapshotTests <android repository root>");
        Path fixtures = Paths.get(args[0]).resolve("zenlygram-tests/customer/fixtures");
        fixture(fixtures);
        phone();
        usernames();
        names();
        versions();
        device();
        digest();
        bounds();
        System.out.println("Profile snapshot: " + checks + " checks passed");
    }

    // ---- The shared fixture ----

    private static void fixture(Path fixtures) throws Exception {
        Map<String, Object> file = MiniJson.object(MiniJson.parseFile(fixtures.resolve("profile-sync-valid.json")));
        long tg = ((Number) file.get("tg_user_id")).longValue();
        int built = 0;
        for (Object item : MiniJson.array(file.get("cases"))) {
            Map<String, Object> testCase = MiniJson.object(item);
            String id = (String) testCase.get("id");
            Map<String, Object> body = MiniJson.object(testCase.get("body"));
            Map<String, Object> telegram = MiniJson.object(body.get("telegram"));
            Map<String, Object> device = MiniJson.object(body.get("device"));
            long observedAt = CustomerJson.parseInstant((String) body.get("observed_at"));
            check(observedAt > 0, id + ": observed_at parses");

            ProfileSnapshot.Input input = inputFor(id, tg);
            ProfileSnapshot snapshot = ProfileSnapshot.of(input);
            Map<String, Object> ours = MiniJson.object(MiniJson.parse(snapshot.json(observedAt)));
            check(keys(ours).equals(keys(body)), id + ": the body keys equal the fixture's, in order: " + keys(ours));
            check(keys(MiniJson.object(ours.get("telegram"))).equals(keys(telegram)), id + ": telegram keys in order");
            check(keys(MiniJson.object(ours.get("device"))).equals(keys(device)), id + ": device keys in order");
            if (!Boolean.TRUE.equals(testCase.get("client_built"))) continue;
            check(ours.get("telegram").equals(telegram), id + ": the telegram section equals the fixture's: " + ours.get("telegram"));
            check(ours.get("observed_at").equals(body.get("observed_at")) && ours.get("schema_version").equals(body.get("schema_version")),
                    id + ": observed_at and schema_version");
            if ("android".equals(device.get("platform"))) {
                check(ours.equals(body), id + ": an Android-built body equals the fixture's exactly: " + snapshot.json(observedAt));
                built++;
            } else {
                // An iOS case: the same Telegram values give the same telegram section; the device facts are Android's.
                check("android".equals(MiniJson.object(ours.get("device")).get("platform")), id + ": Android reports platform android");
            }
        }
        check(built >= 1, "the fixture has an Android client-built case");
    }

    /** The raw values Android would report for each fixture case (the iOS cases' Telegram facts, Android device facts). */
    private static ProfileSnapshot.Input inputFor(String id, long tg) {
        ProfileSnapshot.Input input = new ProfileSnapshot.Input();
        input.telegramUserId = tg;
        input.osVersion = "15";
        input.appVersion = "12.1.0";
        input.deviceModel = "samsung  SM-S928B";
        input.appBuild = "52345";
        input.uiLanguage = "ru";
        input.locale = "ru-KZ";
        input.timezone = "Asia/Almaty";
        input.distribution = "play";
        switch (id) {
            case "ios-phone":
                input.phoneDigits = "998901234567";
                input.usernames = Arrays.asList(new ProfileSnapshot.Username("collectible1", false, true),
                        new ProfileSnapshot.Username("primary_name", true, true), new ProfileSnapshot.Username("old_one", false, false));
                input.firstName = "  Alisher ";
                input.lastName = " \u0007";
                input.premium = false;
                break;
            case "android-phone":
                input.phoneDigits = "77011234567";
                input.usernames = Arrays.asList(new ProfileSnapshot.Username("nodira_test", true, true),
                        new ProfileSnapshot.Username("Nodira_Shop", false, true), new ProfileSnapshot.Username("NODIRA_TEST", false, true));
                input.firstName = "Нодира";
                input.lastName = "Тестова";
                input.premium = true;
                break;
            case "ios-tablet-nothing-known":
                input.phoneDigits = "";
                input.usernames = Collections.emptyList();
                input.username = null;
                input.firstName = "\u0000　 \u0085";
                input.lastName = null;
                input.premium = false;
                break;
            default:
                input.phoneDigits = "5511987654321";
                input.usernames = Collections.singletonList(new ProfileSnapshot.Username("Ana_Test", true, true));
                input.firstName = "Ana";
                input.lastName = "Test";
                break;
        }
        return input;
    }

    // ---- Field rules ----

    private static void phone() {
        check("+998901234567".equals(ProfileSnapshot.phone("998901234567")), "digits get the +");
        check("+77011234567".equals(ProfileSnapshot.phone("+77011234567")), "a leading + is tolerated");
        check("+1234567".equals(ProfileSnapshot.phone("1234567")), "7 digits is the shortest e164");
        check(ProfileSnapshot.phone("123456") == null, "6 digits is too short");
        check("+123456789012345".equals(ProfileSnapshot.phone("123456789012345")), "15 digits is the longest");
        check(ProfileSnapshot.phone("1234567890123456") == null, "16 digits is too long");
        check(ProfileSnapshot.phone("0998901234567") == null, "a leading 0 is not e164");
        check(ProfileSnapshot.phone("") == null && ProfileSnapshot.phone(null) == null && ProfileSnapshot.phone("   ") == null, "unknown → null");
        check(ProfileSnapshot.phone("99890 123") == null && ProfileSnapshot.phone("9989012345a") == null
                && ProfileSnapshot.phone("++998901234567") == null, "anything but digits → null");
        ProfileSnapshot.Input input = base();
        input.phoneDigits = "";
        check(MiniJson.object(MiniJson.parse(ProfileSnapshot.of(input).json(1))).get("telegram").toString().contains("phone=null"),
                "no phone is sent as JSON null");
    }

    private static void usernames() {
        List<ProfileSnapshot.Username> list = Arrays.asList(new ProfileSnapshot.Username("second_one", false, true),
                new ProfileSnapshot.Username("inactive_one", false, false), new ProfileSnapshot.Username("Primary_Name", true, true),
                new ProfileSnapshot.Username("abc", false, true), new ProfileSnapshot.Username("bad-name", false, true),
                new ProfileSnapshot.Username(repeat('x', 33), false, true), new ProfileSnapshot.Username("PRIMARY_NAME", false, true),
                new ProfileSnapshot.Username("third_one", false, true), null, new ProfileSnapshot.Username(null, false, true));
        check(ProfileSnapshot.usernames(list, "ignored_fallback").equals(Arrays.asList("Primary_Name", "second_one", "third_one")),
                "editable first, then the other active ones in server order; invalid, inactive and repeated (ignoring case) dropped: "
                        + ProfileSnapshot.usernames(list, null));
        check(ProfileSnapshot.usernames(Collections.<ProfileSnapshot.Username>emptyList(), "fallback_name").equals(
                Collections.singletonList("fallback_name")), "user.username only when the list names none");
        check(ProfileSnapshot.usernames(null, "fallback_name").equals(Collections.singletonList("fallback_name")), "a null list too");
        check(ProfileSnapshot.usernames(null, "ab").isEmpty() && ProfileSnapshot.usernames(null, null).isEmpty(), "an invalid or no fallback");
        check(ProfileSnapshot.usernames(Collections.singletonList(new ProfileSnapshot.Username("only_inactive", true, false)), "fallback_name")
                .isEmpty(), "a list of only inactive names sends none (the fallback is not used)");
        ArrayList<ProfileSnapshot.Username> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(new ProfileSnapshot.Username("user_" + (1000 + i), i == 39, true));
        List<String> bounded = ProfileSnapshot.usernames(many, null);
        check(bounded.size() == ProfileSnapshot.MAX_USERNAMES && bounded.get(0).equals("user_1039") && bounded.get(1).equals("user_1000"),
                "at most 32, the editable one first");
        check(ProfileSnapshot.usernames(list, null).equals(ProfileSnapshot.usernames(list, null)), "deterministic");
        try {
            ProfileSnapshot.usernames(list, null).add("x_y_z_w");
            check(false, "the list is unmodifiable");
        } catch (UnsupportedOperationException expected) {
            check(true, "the list is unmodifiable");
        }
    }

    private static void names() {
        check("é".equals(ProfileSnapshot.name("é")), "NFC composes");
        check("Å".equals(ProfileSnapshot.name("A\u0000̊")), "a control between a letter and its mark: removed first, then NFC");
        check("é x".equals(ProfileSnapshot.name("e\u0007́ x\u0085")), "C0 and C1 controls go before NFC");
        check("ab".equals(ProfileSnapshot.name("a b ")), "line and paragraph separators go");
        check("Ali Valiyev".equals(ProfileSnapshot.name(" 　 Ali Valiyev \t")), "Unicode White_Space trimmed at both ends");
        check(ProfileSnapshot.name("") == null && ProfileSnapshot.name("  　") == null && ProfileSnapshot.name("\u0000\u001f\u007f\u009f") == null
                && ProfileSnapshot.name(null) == null, "nothing left → null");
        check("oʻzbek".equals(ProfileSnapshot.name("oʻzbek")), "U+02BB is a letter and stays");
        String long128 = repeat('a', 128);
        check(long128.equals(ProfileSnapshot.name(long128)), "128 scalars fit");
        check(long128.equals(ProfileSnapshot.name(long128 + "b")), "the 129th scalar goes");
        String cut = ProfileSnapshot.name(repeat('a', 127) + "x́yz");
        check(repeat('a', 127).equals(cut), "a grapheme that does not fit goes whole (x + U+0301 never composes)");
        String emoji = new StringBuilder().appendCodePoint(0x1F600).toString();
        StringBuilder astral = new StringBuilder();
        for (int i = 0; i < 130; i++) astral.append(emoji);
        String astralName = ProfileSnapshot.name(astral.toString());
        check(UnicodeText.scalarCount(astralName) == 128 && astralName.length() == 256, "scalars are counted, not UTF-16 units");
        check(ProfileSnapshot.name(repeat(' ', 5) + long128 + repeat(' ', 5)).equals(long128), "trimmed before the cut");
        for (String raw : new String[]{"é", "A\u0000̊", "ᄀ\u0000ᅡ", "Zenó Test", " ́x", "q\u0007̣̇",
                "👍🏽", "نورا", "क्ष"}) {
            String name = ProfileSnapshot.name(raw);
            check(name == null || ProfileSnapshot.isNameText(name), "a name_text (NFC, no control, 1..128): " + escape(raw));
            check(name == null || Normalizer.isNormalized(name, Normalizer.Form.NFC), "NFC: " + escape(raw));
        }
        check("가".equals(ProfileSnapshot.name("ᄀ\u0000ᅡ")), "Hangul jamo joined across a removed control compose");
        check(!ProfileSnapshot.isNameText("é") && !ProfileSnapshot.isNameText("a\u0000") && !ProfileSnapshot.isNameText("")
                && !ProfileSnapshot.isNameText(repeat('a', 129)) && ProfileSnapshot.isNameText("é"), "isNameText");
        ProfileSnapshot.Input input = base();
        input.firstName = "\u0000";
        input.lastName = "Valiyev";
        Map<String, Object> telegram = MiniJson.object(MiniJson.object(MiniJson.parse(ProfileSnapshot.of(input).json(1))).get("telegram"));
        check(telegram.containsKey("first_name") && telegram.get("first_name") == null && "Valiyev".equals(telegram.get("last_name")),
                "a first name that cleans to nothing is sent as null (no observation)");
        input.firstName = "Say \"hi\" \\ back/slash";
        Map<String, Object> quoted = MiniJson.object(MiniJson.object(MiniJson.parse(ProfileSnapshot.of(input).json(1))).get("telegram"));
        check("Say \"hi\" \\ back/slash".equals(quoted.get("first_name")), "quotes and backslashes round-trip");
    }

    private static void versions() {
        ProfileSnapshot.Input input = base();
        input.appVersion = "12.1.0 (beta) ß★#!";
        input.osVersion = "16 «Q»";
        ProfileSnapshot snapshot = ProfileSnapshot.of(input);
        check("12.1.0 (beta) ".equals(snapshot.appVersion) && UnicodeText.isVer(snapshot.appVersion), "app_version sanitized to ver");
        check("16 Q".equals(snapshot.osVersion) && UnicodeText.isVer(snapshot.osVersion), "os_version sanitized to ver");
        input.appVersion = repeat('9', 40);
        check(ProfileSnapshot.of(input).appVersion.length() == 32, "at most 32 characters");
        input.appVersion = "★";
        input.osVersion = null;
        snapshot = ProfileSnapshot.of(input);
        check("0".equals(snapshot.appVersion) && "0".equals(snapshot.osVersion), "nothing left → 0 (still a ver)");
        input.appVersion = "android";
        check("android".equals(ProfileSnapshot.of(input).appVersion), "ZenlyPro's unknown version stays");
    }

    private static void device() {
        ProfileSnapshot.Input input = base();
        input.tablet = true;
        input.deviceModel = " Google\t Pixel é Tablet ";
        input.appBuild = "build 5";
        input.uiLanguage = "pt_BR";
        input.locale = "uz_Latn_UZ";
        input.timezone = "UTC+05:00";
        input.distribution = "appstore";
        ProfileSnapshot snapshot = ProfileSnapshot.of(input);
        check("tablet".equals(snapshot.deviceClass), "smallest width ≥ 600 dp → tablet");
        check("Google Pixel Tablet".equals(snapshot.deviceModel), "device_model printable ASCII, whitespace collapsed: " + snapshot.deviceModel);
        check(snapshot.appBuild == null, "a non-decimal build → null");
        check("pt-br".equals(snapshot.uiLanguage), "ui_language in the iOS form: lower-cased, '-'");
        check("uz-Latn-UZ".equals(snapshot.locale), "locale as bcp47");
        check(snapshot.timezone == null, "a non-iana_tz zone → null");
        check("appstore".equals(snapshot.distribution), "a contract distribution value is kept");
        input.distribution = "sideload";
        check("direct".equals(ProfileSnapshot.of(input).distribution), "anything else → direct");
        for (String[] pair : new String[][]{{"uz", "uz"}, {"EN", "en"}, {"zh-Hans-CN", "zh-hans-cn"}, {"x", null}, {"", null}, {null, null},
                {"en-US-x-private", null}, {"ru_RU", "ru-ru"}}) {
            input.uiLanguage = pair[0];
            String value = ProfileSnapshot.of(input).uiLanguage;
            check(pair[1] == null ? value == null : pair[1].equals(value), "ui_language " + pair[0] + " → " + value);
        }
        for (String[] pair : new String[][]{{"ru-KZ", "ru-KZ"}, {"en_GB", "en-GB"}, {"und", null}, {"de-DE-u-co-phonebk", "de-DE"},
                {"es-419", "es-419"}, {"zh-hant-tw", "zh-Hant-TW"}, {null, null}}) {
            input.locale = pair[0];
            String value = ProfileSnapshot.of(input).locale;
            check(pair[1] == null ? value == null : pair[1].equals(value), "locale " + pair[0] + " → " + value);
        }
        input.deviceModel = "★★";
        check(ProfileSnapshot.of(input).deviceModel == null, "a model without printable ASCII → null");
        input.deviceModel = repeat('m', 70);
        check(ProfileSnapshot.of(input).deviceModel.length() == 64, "device_model at most 64");
        check("android".equals(ProfileSnapshot.of(input).json(1).contains("\"platform\":\"android\"") ? "android" : ""), "platform android");
    }

    private static void digest() {
        ProfileSnapshot.Input input = base();
        ProfileSnapshot snapshot = ProfileSnapshot.of(input);
        String at1 = snapshot.json(1793527200L);
        String at2 = snapshot.json(1793530800L);
        check(!at1.equals(at2), "observed_at differs");
        check(snapshot.digest().equals(ProfileSnapshot.of(input).digest()), "the digest ignores observed_at and is deterministic");
        check(CustomerJson.isHex64(snapshot.digest()), "the digest is hex64");
        String withoutObservedAt = at1.replace(",\"observed_at\":\"2026-11-01T10:00:00Z\"", "");
        check(!withoutObservedAt.equals(at1), "SETUP: observed_at was removed");
        check(snapshot.digest().equals(ConsentText.sha256Hex(withoutObservedAt)), "digest = sha256 of the body without observed_at");
        check(at1.startsWith("{\"schema_version\":1,\"observed_at\":\"2026-11-01T10:00:00Z\",\"app_version\":"), "contract key order");
        String base = snapshot.digest();
        ProfileSnapshot.Input changed = base();
        changed.premium = !input.premium;
        check(!base.equals(ProfileSnapshot.of(changed).digest()), "premium changes the digest");
        changed = base();
        changed.firstName = "Other";
        check(!base.equals(ProfileSnapshot.of(changed).digest()), "a name changes the digest");
        changed = base();
        changed.timezone = "Europe/London";
        check(!base.equals(ProfileSnapshot.of(changed).digest()), "a device fact changes the digest");
        changed = base();
        changed.firstName = "\u0000" + input.firstName + "  ";
        check(base.equals(ProfileSnapshot.of(changed).digest()), "values that normalize equally give the same digest");
        check(Arrays.equals(snapshot.body(1793527200L), at1.getBytes(StandardCharsets.UTF_8)), "body() is the UTF-8 of json()");
    }

    private static void bounds() {
        ProfileSnapshot.Input input = base();
        for (long bad : new long[]{0, -1, ProfileSnapshot.MAX_TELEGRAM_USER_ID + 1}) {
            input.telegramUserId = bad;
            try {
                ProfileSnapshot.of(input);
                check(false, "tg_id " + bad + " is refused");
            } catch (IllegalArgumentException expected) {
                check(true, "tg_id " + bad + " is refused");
            }
        }
        input.telegramUserId = ProfileSnapshot.MAX_TELEGRAM_USER_ID;
        check(ProfileSnapshot.of(input).json(1).contains("\"user_id\":9007199254740991"), "the largest tg_id is written exactly");
        ArrayList<ProfileSnapshot.Username> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(new ProfileSnapshot.Username(repeat((char) ('a' + i % 26), 28) + (1000 + i), false, true));
        input.usernames = many;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) name.appendCodePoint(0x1F600);
        input.firstName = name.toString();
        input.lastName = name.toString();
        input.deviceModel = repeat('m', 200);
        int bytes = ProfileSnapshot.of(input).body(CustomerJson.MAX_INSTANT).length;
        check(bytes <= 16384, "the largest body fits the route's 16 KiB: " + bytes);
    }

    // ---- helpers ----

    private static ProfileSnapshot.Input base() {
        ProfileSnapshot.Input input = new ProfileSnapshot.Input();
        input.telegramUserId = 555000111L;
        input.phoneDigits = "998901234567";
        input.usernames = Collections.singletonList(new ProfileSnapshot.Username("primary_name", true, true));
        input.firstName = "Alisher";
        input.lastName = null;
        input.premium = false;
        input.appVersion = "12.1.0";
        input.osVersion = "15";
        input.deviceModel = "samsung SM-S928B";
        input.appBuild = "52345";
        input.uiLanguage = "uz";
        input.locale = "uz-UZ";
        input.timezone = "Asia/Tashkent";
        input.distribution = "direct";
        return input;
    }

    private static List<String> keys(Map<String, Object> object) { return new ArrayList<>(object.keySet()); }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
