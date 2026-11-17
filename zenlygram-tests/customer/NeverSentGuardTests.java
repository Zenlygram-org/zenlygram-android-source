package org.telegram.messenger.zenlygram.customer;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-JVM "never sent" guard (contract §6.9; design 50 §11.1, §11.7): every event type any builder or the session can
 * produce, serialized, carries only props the registry declares, no forbidden key (phone, username, chat, peer, text,
 * …), only registry enum values or pattern-checked ids and codes (free text never survives), and never the {@code pin}
 * method; the ZenlyStats facade takes no free-form string except productId / basePlanId / errorCode / code / a tab's
 * screen wire name, and no chat, peer, message or user object; no customer source reads location, IP, advertising or
 * device identifiers; and nothing is recorded for an account that is not granted, whose latest grant was rejected,
 * whose binding was lost or whose grant went stale after it, or that has a deletion pending. Run with run-tests.sh,
 * which passes the Android repository root.
 */
public final class NeverSentGuardTests {
    private static int checks;
    private static final long TG = 555000111L;
    private static final String AUTH = "zenlygram.org";
    private static final String SHA = "229fd61b5ac4c2711e1b9635b6e5b24ab44ab5564baac8a2c1067a5b127f64d9";
    private static final ConsentText.Shown TEXT = new ConsentText.Shown(1, ConsentManifest.SCOPES, "uz", "# Rozilik", SHA);
    private static final long T0 = 1793268000L;
    /** No prop key may name or contain any of these (contract §6.9). */
    private static final List<String> FORBIDDEN = Arrays.asList("phone", "username", "user", "name", "chat", "peer", "dialog", "channel", "group",
            "text", "message", "caption", "draft", "contact", "location", "geo", "lat", "lon", "ip", "query", "search", "token", "initdata", "url",
            "email", "photo", "media", "file", "exception", "stack");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: NeverSentGuardTests <android repository root>");
        Path root = Paths.get(args[0]);
        EventRegistryTests.Registry registry = EventRegistryTests.Registry.load(root.resolve("zenlygram-tests/customer/fixtures/analytics-events.json"));
        everyEventSerialized(registry);
        sessionEvents(registry);
        freeTextNeverSurvives();
        facadeParameters(root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer/ZenlyStats.java"));
        noIdentifiersRead(root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/customer"),
                root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/pro/ProFunnelSink.java"));
        nothingRecordedWithoutAGrant();
        screensOfTheFragmentStacks();
        System.out.println("Never sent guard: " + checks + " checks passed");
    }

    /** Every built event: props ⊆ registry, no forbidden key, registry-valid values, method never pin. */
    private static void everyEventSerialized(EventRegistryTests.Registry registry) {
        Set<String> keys = new HashSet<>();
        int events = 0;
        for (CustomerEvents.Event event : EventRegistryTests.allBuilt()) {
            String json = event.json(EventRegistryTests.uuid(), T0, EventRegistryTests.uuid(), events);
            Map<String, Object> tree = MiniJson.object(MiniJson.parse(json));
            Map<String, Object> props = MiniJson.object(tree.get("props"));
            Map<String, Map<String, Object>> declared = registry.events.get(event.name);
            check(declared != null, event.name + " is a registry event");
            check(declared.keySet().containsAll(props.keySet()), event + ": no prop outside the registry");
            int[] dropped = {0};
            check("ok".equals(registry.props(event.name, props, dropped)) && dropped[0] == 0, event + ": every value within its rule");
            for (Map.Entry<String, Object> prop : props.entrySet()) {
                keys.add(prop.getKey());
                check(prop.getValue() instanceof String || prop.getValue() instanceof Integer || prop.getValue() instanceof Boolean,
                        event + ": props are strings, integers or booleans only");
            }
            if (event.name.equals(CustomerEvents.VAULT_UNLOCKED) || event.name.equals(CustomerEvents.CHAT_LOCK_UNLOCKED)
                    || event.name.equals(CustomerEvents.CHAT_LOCK_SET)) {
                check(!"pin".equals(props.get("method")), event + ": the method is never pin");
                check("password".equals(props.get("method")) || "biometric".equals(props.get("method")), event + ": password or biometric");
            }
            check(new HashSet<>(tree.keySet()).equals(new HashSet<>(Arrays.asList("id", "name", "ts", "session_id", "seq", "props"))),
                    "nothing but the six event keys");
            events++;
        }
        for (String key : keys) {
            for (String word : FORBIDDEN) {
                check(!key.equals(word) && !Arrays.asList(key.split("_")).contains(word), "prop key " + key + " is not about " + word);
            }
        }
        check(keys.containsAll(Arrays.asList("screen", "feature", "product_id", "error_code", "method")) && events > 300,
                "the guard saw every kind of prop: " + keys.size() + " keys over " + events + " events");
    }

    /** The session's own events (start, screen, aggregates, end) obey the same rules. */
    private static void sessionEvents(EventRegistryTests.Registry registry) {
        SessionTracker tracker = SessionTracker.fresh(EventRegistryTests::uuid);
        List<SessionTracker.Stamped> stamped = new ArrayList<>(tracker.start(CustomerEvents.SessionTrigger.ACCOUNT_SWITCH,
                CustomerEvents.Screen.ZG_PRO, T0, 0));
        tracker.countError(CustomerEvents.ErrorDomain.STORAGE, "java.lang.IllegalStateException: /data/user/0/x");
        tracker.countError(CustomerEvents.ErrorDomain.LINK, "Telegram said: FLOOD_WAIT_17");
        tracker.countSyncIssue(CustomerEvents.SyncIssueKind.CONFLICT_MANUAL, 3);
        stamped.addAll(tracker.end(CustomerEvents.SessionEndReason.LOGOUT, T0 + 60, 60_000));
        check(stamped.size() == 6, "start, screen, two errors, a sync issue and the end");
        for (SessionTracker.Stamped event : stamped) {
            Map<String, Object> props = MiniJson.object(MiniJson.object(MiniJson.parse(event.json)).get("props"));
            int[] dropped = {0};
            check("ok".equals(registry.props(event.event.name, props, dropped)) && dropped[0] == 0, event.json + " is valid");
            if (event.event.name.equals(CustomerEvents.ERROR_OCCURRED)) {
                check("unknown".equals(props.get("code")), "an exception text or a sentence is never an error code: " + props.get("code"));
            }
        }
    }

    /** Free text never becomes a product id, a base plan, an error code or a screen. */
    private static void freeTextNeverSurvives() {
        Random random = new Random(42);
        String alphabet = "aZ09 ._-/:@+!?éа\n\"\\";
        for (int i = 0; i < 2000; i++) {
            StringBuilder text = new StringBuilder();
            int length = 1 + random.nextInt(80);
            for (int j = 0; j < length; j++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String value = text.toString();
            String code = ErrorCodes.normalize(value);
            check(code.equals("unknown") || code.equals(value), "a code is either kept verbatim (already stable) or unknown");
            check(ErrorCodes.isCode(code), "the result is a stable code");
            check(!code.contains(" ") && !code.contains("/") && !code.contains(":") && code.equals(code.toLowerCase(java.util.Locale.ROOT)),
                    "no sentence, path or URL in a code: " + code);
            CustomerEvents.Event plan = CustomerEvents.proPlanSelect(value, CustomerEvents.Rail.GOOGLE, value);
            if (plan != null) {
                check(CustomerEvents.isProductId(value), "a kept product id matches the pattern");
                check(!plan.propsJson().contains(" ") && !plan.propsJson().contains("/"), "no free text in a purchase event");
            }
            check(CustomerEvents.Screen.of(value) == null || CustomerEvents.Screen.of(value).wire.equals(value), "screens only by wire name");
        }
        check(CustomerEvents.Screen.of("ChatActivity") == null && CustomerEvents.Screen.of("tg.chat") == null, "no chat-level screen exists");
        check(ErrorCodes.telegram("FLOOD_WAIT_17").equals("telegram_flood_wait"), "an RPC error's number (an id-like value) is dropped");
    }

    /** ZenlyStats' public signatures: strings only as patterned ids / codes / a tab wire name; no object of a chat or user. */
    private static void facadeParameters(Path source) throws Exception {
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        Matcher method = Pattern.compile("public static [\\w.<>]+ (\\w+)\\(([^)]*)\\)").matcher(text);
        Set<String> allowedStrings = new HashSet<>(Arrays.asList("productId", "basePlanId", "errorCode", "code", "screenWire"));
        Set<String> allowedLongs = new HashSet<>(Arrays.asList("durationS", "telegramUserId"));
        int methods = 0;
        while (method.find()) {
            methods++;
            String parameters = method.group(2).trim();
            if (parameters.isEmpty()) continue;
            for (String parameter : parameters.split(",")) {
                String[] parts = parameter.trim().split("\\s+");
                String type = parts[parts.length - 2], name = parts[parts.length - 1];
                String where = method.group(1) + "(" + parameter.trim() + ")";
                if (type.equals("String")) check(allowedStrings.contains(name), where + ": a free-form string parameter");
                if (type.equals("long") || type.equals("Long")) check(allowedLongs.contains(name), where + ": an id-like number");
                check(!type.contains("Object") && !type.contains("CharSequence") && !type.contains("TLRPC") && !type.contains("MessageObject")
                        && !type.contains("Map") && !type.contains("List") && !type.contains("JSON") && !type.contains("[]")
                        && !type.equals("Dialog") && !type.equals("User") && !type.equals("Chat"), where + ": no content object");
                check(type.matches("int|boolean|long|Integer|Long|String|BaseFragment|Gate|CustomerEvents\\.\\w+"), where + ": a known type");
            }
        }
        int headers = 0;
        for (Matcher header = Pattern.compile("public static [\\w.<>]+ \\w+\\(").matcher(text); header.find(); ) headers++;
        check(methods == headers && methods >= 35, "every public facade method was checked: " + methods + " of " + headers);
        check(!text.contains("Throwable") && !text.contains("getMessage()") && !text.contains("printStackTrace"), "no exception text is ever read");
    }

    /** No customer source reads location, IP, advertising ids, device or SIM identifiers, the clipboard or contacts. */
    private static void noIdentifiersRead(Path customer, Path sink) throws Exception {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(customer, "*.java")) {
            for (Path file : stream) files.add(file);
        }
        files.add(sink);
        Collections.sort(files);
        String[] apis = {"android.location", "LocationManager", "FusedLocation", "InetAddress", "NetworkInterface", "WifiInfo", "getMacAddress",
                "AdvertisingId", "ANDROID_ID", "TelephonyManager", "getLine1Number", "getDeviceId", "getImei", "getSubscriberId", "ClipboardManager",
                "ContactsContract", "getConnectionInfo"};
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String api : apis) check(!text.contains(api), file.getFileName() + " does not use " + api);
        }
        check(files.size() >= 20, "every customer source was scanned: " + files.size());
    }

    /** CustomerEvents.recordable over the consent states (design 50 §11.1; ZenlyStats and the store's gate use it). */
    private static void nothingRecordedWithoutAGrant() {
        ConsentSnapshot granted = granted(T0);
        check(CustomerEvents.recordable(true, granted, false), "an effective grant records");
        check(!CustomerEvents.recordable(false, granted, false), "a signed-out slot never records");
        check(!CustomerEvents.recordable(true, granted, true), "a pending deletion stops recording");
        check(!CustomerEvents.recordable(true, null, false), "no consent state, no recording");
        check(!CustomerEvents.recordable(true, ConsentSnapshot.empty(TG, 1), false), "none: nothing recorded");
        check(!CustomerEvents.recordable(true, ConsentMachine.decline(ConsentSnapshot.empty(TG, 1), ConsentMachine.Mode.OPTIONAL, T0).snapshot, false),
                "declined: nothing recorded");
        ConsentSnapshot withdrawn = ConsentMachine.withdraw(granted, ConsentMachine.Mode.REQUIRED, "uz", id(2), T0 + 10, "1.0.0", AUTH).snapshot;
        check(!CustomerEvents.recordable(true, withdrawn, false), "withdrawn: nothing recorded");
        ConsentSnapshot rejected = ConsentMachine.rejected(granted, Collections.singletonList(granted.latestGrant().consentId),
                "consent_text_mismatch").snapshot;
        check(!CustomerEvents.recordable(true, rejected, false), "a rejected latest grant: nothing recorded");
        ConsentSnapshot lost = ConsentMachine.bindingLoss(granted, T0 + 5).snapshot;
        check(!CustomerEvents.recordable(true, lost, false), "a binding lost after the grant: nothing recorded");
        ConsentSnapshot lostBefore = ConsentMachine.bindingLoss(granted, T0 - 5).snapshot;
        check(CustomerEvents.recordable(true, lostBefore, false), "a loss before the grant does not end it");
        ConsentSnapshot stale = ConsentMachine.consentStale(granted, granted.latestGrantJson(), T0 + 5).snapshot;
        check(!CustomerEvents.recordable(true, stale, false), "a stale grant (409 consent_stale): nothing recorded");
        ConsentSnapshot remote = ConsentMachine.consentRequired(granted, "withdrawn", T0 + 100, T0 + 200).snapshot;
        check(!CustomerEvents.recordable(true, remote, false), "withdrawn on another device: nothing recorded");
        ConsentSnapshot reconsent = ConsentMachine.consentRequired(granted, "reconsent_required", 0, T0 + 200).snapshot;
        check(!CustomerEvents.recordable(true, reconsent, false), "reconsent required: nothing recorded");
        check(!CustomerEvents.recordable(true, granted.withMinValidVersion(2), false), "a retired text version: nothing recorded");
        check(!CustomerEvents.recordable(true, ConsentMachine.forget(TG, 1), false), "erased / logged out: nothing recorded");
        ConsentSnapshot regranted = ConsentMachine.grant(withdrawn, TEXT, ConsentMachine.Mode.REQUIRED, id(3), T0 + 300, "1.0.0", AUTH).snapshot;
        check(CustomerEvents.recordable(true, regranted, false), "a new grant records again");
    }

    /**
     * The fragment-stack rule (design 50 §11.3; ZenlyStats.screenOf applies it after matching the exact class): Telegram's
     * own screens only as a stack root that is not a picker, Zenlygram screens wherever they sit, nothing chat-level.
     */
    private static void screensOfTheFragmentStacks() {
        for (CustomerEvents.Screen screen : CustomerEvents.Screen.values()) {
            check(CustomerEvents.stackScreen(screen, false, true) == null && CustomerEvents.stackScreen(screen, true, true) == null,
                    screen.wire + ": a picker (forward / share / contact selection) is never reported");
            if (screen.telegram()) {
                check(CustomerEvents.stackScreen(screen, true, false) == screen, screen.wire + " as the root of its stack");
                check(CustomerEvents.stackScreen(screen, false, false) == null, screen.wire + " pushed above another screen is never reported");
            } else {
                check(CustomerEvents.stackScreen(screen, false, false) == screen && CustomerEvents.stackScreen(screen, true, false) == screen,
                        screen.wire + " wherever it sits");
            }
            check(CustomerEvents.Screen.of(screen.wire) == screen, screen.wire + " by its wire name");
        }
        check(CustomerEvents.stackScreen(null, true, false) == null, "an unmapped fragment (a chat, a profile, media, search) → null");
        int telegram = 0;
        for (CustomerEvents.Screen screen : CustomerEvents.Screen.values()) if (screen.telegram()) telegram++;
        check(telegram == 4, "only tg.chat_list, tg.contacts, tg.calls and tg.settings are Telegram surfaces");
        check(CustomerEvents.Screen.of(null) == null && CustomerEvents.Screen.of("zg.paywall") == null && CustomerEvents.Screen.of("TG.CHAT_LIST") == null,
                "a tab without a screen, an iOS-only screen or a wrong spelling reports nothing");
    }

    private static ConsentSnapshot granted(long at) {
        return ConsentMachine.grant(ConsentSnapshot.empty(TG, 1), TEXT, ConsentMachine.Mode.REQUIRED, id(1), at, "1.0.0", AUTH).snapshot;
    }

    private static String id(int n) { return String.format(java.util.Locale.ROOT, "00000000-0000-4000-9000-%012d", n); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
