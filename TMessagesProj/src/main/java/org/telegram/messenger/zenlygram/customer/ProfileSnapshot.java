package org.telegram.messenger.zenlygram.customer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code POST /v1/profile/sync} body of one account (contract §2.4.3; design 50 §9.1–§9.2), pure and host-tested.
 * ZenlyProfileReader collects the raw facts on the main thread ({@link Input}); this class normalizes them exactly as
 * the profile service validates them, so a conforming body is never refused for its shape:
 * <ul>
 * <li>{@code telegram.phone}: {@code "+" + digits} of the session's phone ({@code UserConfig.getClientPhone()},
 * digits without '+'), kept only as an {@code e164}, else null (the server stores it as {@code telegram_session});</li>
 * <li>{@code telegram.usernames}: the editable active username first, then the other active ones in server order (the
 * single {@code user.username} when the user object lists none), each a {@code username}, deduplicated ignoring case,
 * at most 32;</li>
 * <li>{@code first_name} / {@code last_name}: controls and U+2028 / U+2029 removed FIRST, then NFC (removing a control
 * between a letter and a combining mark would otherwise leave a decomposed pair the server refuses as non-NFC),
 * White_Space trimmed, cut at a grapheme boundary to at most 128 scalars; null when nothing is left (a {@code first_name}
 * of null is "no observation", design 10 R8);</li>
 * <li>top-level {@code app_version} / {@code os_version} in the contract {@code ver} form (the raw
 * {@code ZenlyPro.appVersionName()} is only truncated, never sanitized, and the server's ProfileBoundary refuses any
 * other character with 422);</li>
 * <li>{@code device}: platform {@code android}, {@code device_class}, the descriptive values in their client forms
 * (CustomerEvents.Context) and {@code locale} as {@code bcp47}.</li>
 * </ul>
 * The digest is the lowercase hex SHA-256 of the body without {@code observed_at} (same key order), so an unchanged
 * profile never syncs again because of its clock.
 */
public final class ProfileSnapshot {
    public static final int SCHEMA_VERSION = 1;
    public static final String PLATFORM = "android";
    /** contract §2.4.3: at most 32 active usernames. */
    public static final int MAX_USERNAMES = 32;
    /** contract §0.3 {@code name_text}: at most 128 Unicode scalars. */
    public static final int MAX_NAME_SCALARS = 128;
    /** contract §0.3 {@code tg_id}: 1..2^53 − 1. */
    public static final long MAX_TELEGRAM_USER_ID = 9007199254740991L;

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{4,32}");

    /** One entry of the user object's username list ({@code TL_username}). */
    public static final class Username {
        public final String username;
        public final boolean editable;
        public final boolean active;

        public Username(String username, boolean editable, boolean active) {
            this.username = username;
            this.editable = editable;
            this.active = active;
        }
    }

    /** The raw facts of one account as Android reports them (any value may be null when unknown). */
    public static final class Input {
        public long telegramUserId;
        /** {@code UserConfig.getClientPhone()}: the digits of the session's phone, without '+' ("" when unknown). */
        public String phoneDigits;
        /** {@code TLRPC.User.usernames}, in server order (may be empty). */
        public List<Username> usernames = Collections.emptyList();
        /** {@code TLRPC.User.username}: used only when {@link #usernames} names none. */
        public String username;
        public String firstName;
        public String lastName;
        public boolean premium;
        /** {@code ZenlyPro.appVersionName()}. */
        public String appVersion;
        /** {@code Build.VERSION.RELEASE}. */
        public String osVersion;
        /** {@code smallestScreenWidthDp >= 600}. */
        public boolean tablet;
        /** {@code Build.MANUFACTURER + " " + Build.MODEL}. */
        public String deviceModel;
        /** The decimal version code. */
        public String appBuild;
        /** The app language ({@code LocaleController} {@code getLangCode()}). */
        public String uiLanguage;
        /** The system language tag ({@code Locale.toLanguageTag()}). */
        public String locale;
        /** {@code TimeZone.getDefault().getID()}. */
        public String timezone;
        /** {@code debug}, {@code play} or {@code direct}. */
        public String distribution;
    }

    public final long telegramUserId;
    /** {@code e164} or null. */
    public final String phone;
    /** Unmodifiable, 0..32 {@code username}s. */
    public final List<String> usernames;
    /** {@code name_text} or null. */
    public final String firstName;
    /** {@code name_text} or null. */
    public final String lastName;
    public final boolean premium;
    public final String appVersion;
    public final String osVersion;
    public final String deviceClass;
    public final String deviceModel;
    public final String appBuild;
    public final String uiLanguage;
    public final String locale;
    public final String timezone;
    public final String distribution;
    private final String digest;

    private ProfileSnapshot(Input input) {
        if (input.telegramUserId < 1 || input.telegramUserId > MAX_TELEGRAM_USER_ID) {
            throw new IllegalArgumentException("telegram user id outside tg_id");
        }
        telegramUserId = input.telegramUserId;
        phone = phone(input.phoneDigits);
        usernames = usernames(input.usernames, input.username);
        firstName = name(input.firstName);
        lastName = name(input.lastName);
        premium = input.premium;
        appVersion = UnicodeText.ver(input.appVersion);
        osVersion = UnicodeText.ver(input.osVersion);
        deviceClass = input.tablet ? CustomerEvents.Context.TABLET : CustomerEvents.Context.PHONE;
        deviceModel = CustomerEvents.Context.deviceModel(input.deviceModel);
        appBuild = CustomerEvents.Context.appBuild(input.appBuild);
        uiLanguage = CustomerEvents.Context.uiLanguage(input.uiLanguage);
        locale = UnicodeText.bcp47(input.locale);
        timezone = CustomerEvents.Context.timezone(input.timezone);
        distribution = CustomerEvents.Context.distribution(input.distribution);
        digest = ConsentText.sha256Hex(write(-1));
    }

    /**
     * The snapshot of {@code input}.
     *
     * @throws IllegalArgumentException for a Telegram user id outside {@code tg_id} (the caller skips the sync)
     */
    public static ProfileSnapshot of(Input input) {
        if (input == null) throw new IllegalArgumentException("input");
        return new ProfileSnapshot(input);
    }

    /** The body observed at {@code observedAtEpochS} (contract key order, compact). */
    public String json(long observedAtEpochS) { return write(Math.max(0, observedAtEpochS)); }

    /** {@link #json} as UTF-8. */
    public byte[] body(long observedAtEpochS) { return json(observedAtEpochS).getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    /** Lowercase hex SHA-256 of the body without {@code observed_at}. */
    public String digest() { return digest; }

    /** The body, without {@code observed_at} when {@code observedAt < 0} (the digest input). */
    private String write(long observedAt) {
        CustomerJson.Obj body = new CustomerJson.Obj().put("schema_version", SCHEMA_VERSION);
        if (observedAt >= 0) body.put("observed_at", CustomerJson.instant(observedAt));
        CustomerJson.Obj telegram = new CustomerJson.Obj().put("user_id", telegramUserId).put("phone", phone)
                .put("usernames", new ArrayList<Object>(usernames)).put("first_name", firstName).put("last_name", lastName)
                .put("is_premium", premium);
        CustomerJson.Obj device = new CustomerJson.Obj().put("platform", PLATFORM).put("device_class", deviceClass)
                .put("device_model", deviceModel).put("app_build", appBuild).put("ui_language", uiLanguage).put("locale", locale)
                .put("timezone", timezone).put("distribution", distribution);
        return body.put("app_version", appVersion).put("os_version", osVersion).put("telegram", telegram).put("device", device).toString();
    }

    // ---- Field rules (design 50 §9.1) ----

    /** {@code "+" + digits} when that is an {@code e164}, else null (a leading '+' of the raw value is tolerated). */
    public static String phone(String raw) {
        if (raw == null) return null;
        String digits = raw.trim();
        if (digits.startsWith("+")) digits = digits.substring(1);
        String value = "+" + digits;
        return E164.matcher(value).matches() ? value : null;
    }

    /**
     * The active usernames: editable ones first, then the others in server order; only {@code username}s, the first
     * spelling of each name ignoring case, at most 32. {@code fallback} ({@code user.username}) is used only when the list
     * names no entry at all.
     */
    public static List<String> usernames(List<Username> list, String fallback) {
        ArrayList<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (list != null && !list.isEmpty()) {
            for (int pass = 0; pass < 2; pass++) {
                for (Username entry : list) {
                    if (entry == null || !entry.active || entry.editable != (pass == 0)) continue;
                    add(result, seen, entry.username);
                }
            }
        } else {
            add(result, seen, fallback);
        }
        return Collections.unmodifiableList(result);
    }

    private static void add(List<String> result, Set<String> seen, String username) {
        if (result.size() >= MAX_USERNAMES || username == null || !USERNAME.matcher(username).matches()) return;
        if (seen.add(username.toLowerCase(Locale.ROOT))) result.add(username);
    }

    /**
     * A display name as {@code name_text}: controls and U+2028 / U+2029 removed, then NFC, White_Space trimmed, cut at a
     * grapheme boundary to at most 128 scalars and trimmed again; null when nothing is left. Only the ends change after
     * the NFC step, so the result stays NFC (checked once more for safety).
     */
    public static String name(String raw) {
        if (raw == null) return null;
        String text = UnicodeText.nfc(UnicodeText.stripControls(raw));
        text = UnicodeText.trimWhiteSpace(UnicodeText.truncateGraphemes(UnicodeText.trimWhiteSpace(text), MAX_NAME_SCALARS));
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            text = UnicodeText.trimWhiteSpace(UnicodeText.truncateGraphemes(UnicodeText.nfc(text), MAX_NAME_SCALARS));
        }
        return text.isEmpty() ? null : text;
    }

    /** Whether {@code text} is a contract {@code name_text}: 1..128 scalars, NFC, no control, no line separator. */
    public static boolean isNameText(String text) {
        return text != null && !text.isEmpty() && UnicodeText.scalarCount(text) <= MAX_NAME_SCALARS
                && !UnicodeText.hasControlOrLineSeparator(text) && Normalizer.isNormalized(text, Normalizer.Form.NFC);
    }
}
