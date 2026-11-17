package org.telegram.messenger.zenlygram.customer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One ConsentEvent (contract §2.4.1; design 50 §5.7), pure and immutable: minted once per user action, persisted in the
 * outbox before any upload and sent as exactly the bytes of {@link #json} (keys in the §5.7 order). A grant carries
 * {@code text_sha256} and {@code age_confirmed: true}; a withdraw carries neither, and its {@code text_version} (the
 * version withdrawn) is optional. The same JSON is the {@code consent} of {@code POST /v1/bind/start}.
 */
public final class ConsentEvent {
    public static final String GRANT = "grant";
    public static final String WITHDRAW = "withdraw";
    public static final String PLATFORM = "android";
    /** {@code tg_id}: 1..2^53 − 1. */
    public static final long MAX_TELEGRAM_USER_ID = 9007199254740991L;
    /** A consents request carries one grant alone or 1..10 consecutive withdrawals (design 50 §5.7). */
    public static final int MAX_WITHDRAWALS_PER_REQUEST = 10;
    public static final int SCHEMA_VERSION = 1;

    public final String consentId;
    public final long tgUserId;
    public final String action;
    public final List<String> scopes;
    /** 1..32767; 0 = absent (a withdraw only). */
    public final int textVersion;
    /** A grant's hash; null for a withdraw. */
    public final String textSha256;
    public final String locale;
    public final String consentMode;
    /** Epoch seconds of {@code recorded_at}. */
    public final long recordedAt;
    public final String platform;
    public final String appVersion;
    private final String json;

    private ConsentEvent(String consentId, long tgUserId, String action, List<String> scopes, int textVersion, String textSha256,
            String locale, String consentMode, long recordedAt, String platform, String appVersion) {
        this.consentId = consentId;
        this.tgUserId = tgUserId;
        this.action = action;
        this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
        this.textVersion = textVersion;
        this.textSha256 = textSha256;
        this.locale = locale;
        this.consentMode = consentMode;
        this.recordedAt = recordedAt;
        this.platform = platform;
        this.appVersion = appVersion;
        CustomerJson.Obj object = new CustomerJson.Obj()
                .put("consent_id", consentId)
                .put("tg_user_id", tgUserId)
                .put("action", action)
                .put("scopes", this.scopes);
        if (textVersion > 0) object.put("text_version", textVersion);
        if (textSha256 != null) object.put("text_sha256", textSha256);
        object.put("locale", locale).put("consent_mode", consentMode);
        if (GRANT.equals(action)) object.put("age_confirmed", true);
        this.json = object.put("recorded_at", CustomerJson.instant(recordedAt)).put("platform", platform).put("app_version", appVersion).toString();
    }

    /**
     * A grant of the text the user saw: {@code scopes} = that version's set, {@code age_confirmed} always true (the
     * required box). {@code appVersion} is clipped to {@code ver}.
     *
     * @throws IllegalArgumentException when a field is outside the contract
     */
    public static ConsentEvent grant(String consentId, long tgUserId, List<String> scopes, int textVersion, String textSha256,
            String locale, String consentMode, long recordedAt, String appVersion) {
        if (textVersion < 1 || !CustomerJson.isHex64(textSha256)) throw new IllegalArgumentException("not a grant");
        return checked(new ConsentEvent(consentId, tgUserId, GRANT, scopes, textVersion, textSha256, locale, consentMode, recordedAt,
                PLATFORM, UnicodeText.ver(appVersion)));
    }

    /**
     * A withdrawal; {@code textVersion} = the version withdrawn or 0 when unknown.
     *
     * @throws IllegalArgumentException when a field is outside the contract
     */
    public static ConsentEvent withdraw(String consentId, long tgUserId, List<String> scopes, int textVersion, String locale,
            String consentMode, long recordedAt, String appVersion) {
        return checked(new ConsentEvent(consentId, tgUserId, WITHDRAW, scopes, Math.max(0, textVersion), null, locale, consentMode,
                recordedAt, PLATFORM, UnicodeText.ver(appVersion)));
    }

    private static ConsentEvent checked(ConsentEvent event) {
        if (!event.isValid()) throw new IllegalArgumentException("not a consent event");
        return event;
    }

    public boolean isGrant() { return GRANT.equals(action); }

    public boolean isWithdraw() { return WITHDRAW.equals(action); }

    /** The exact JSON object of this event (keys in the order of design 50 §5.7). */
    public String json() { return json; }

    /** Every field within the contract (§0.3, §2.4.1). */
    private boolean isValid() {
        if (!CustomerJson.isUuid4(consentId) || tgUserId < 1 || tgUserId > MAX_TELEGRAM_USER_ID || !ConsentManifest.validScopes(scopes)
                || !ConsentManifest.LOCALES.contains(locale) || !("required".equals(consentMode) || "optional".equals(consentMode))
                || recordedAt < 0 || recordedAt > CustomerJson.MAX_INSTANT || !(PLATFORM.equals(platform) || "ios".equals(platform))
                || !UnicodeText.isVer(appVersion) || textVersion < 0 || textVersion > ConsentManifest.MAX_VERSION) {
            return false;
        }
        if (GRANT.equals(action)) return textVersion >= 1 && CustomerJson.isHex64(textSha256);
        return WITHDRAW.equals(action) && textSha256 == null;
    }

    /**
     * The event of a parsed JSON object (an outbox entry or a fixture), or null when it is not a valid ConsentEvent: no
     * missing or unknown key, every value in its contract shape, {@code age_confirmed} exactly {@code true} on a grant
     * and absent on a withdraw (whose {@code text_version: null} reads as absent, as on the server). Its {@link #json}
     * then re-serializes the same values.
     */
    public static ConsentEvent fromTree(Object tree) {
        Map<String, Object> object = CustomerJson.object(tree);
        if (object == null) return null;
        String action = CustomerJson.string(object.get("action"));
        boolean grant = GRANT.equals(action);
        for (String key : object.keySet()) {
            boolean grantOnly = "text_sha256".equals(key) || "age_confirmed".equals(key);
            if (!KEYS.contains(key) || (grantOnly && !grant)) return null;
        }
        Long tg = CustomerJson.integral(object.get("tg_user_id"));
        List<String> scopes = CustomerJson.strings(object.get("scopes"));
        Object versionValue = object.get("text_version");
        Long version = versionValue == null && !grant ? Long.valueOf(0) : CustomerJson.integral(versionValue);
        long recordedAt = CustomerJson.parseInstant(CustomerJson.string(object.get("recorded_at")));
        String sha = grant ? CustomerJson.string(object.get("text_sha256")) : null;
        if (tg == null || scopes == null || version == null || version < 0 || version > ConsentManifest.MAX_VERSION || recordedAt < 0
                || (grant && !Boolean.TRUE.equals(object.get("age_confirmed"))) || (!grant && !WITHDRAW.equals(action))
                || (versionValue != null && version == 0)) {
            return null;
        }
        ConsentEvent event = new ConsentEvent(CustomerJson.string(object.get("consent_id")), tg, action, scopes, version.intValue(), sha,
                CustomerJson.string(object.get("locale")), CustomerJson.string(object.get("consent_mode")), recordedAt,
                CustomerJson.string(object.get("platform")), CustomerJson.string(object.get("app_version")));
        return event.isValid() ? event : null;
    }

    private static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList("consent_id", "tg_user_id", "action",
            "scopes", "text_version", "text_sha256", "locale", "consent_mode", "age_confirmed", "recorded_at", "platform", "app_version"));

    /**
     * The {@code POST /v1/profile/consents} body (contract §2.4.1): {@code {"schema_version":1,"consents":[…]}} with
     * either one grant alone (so a terminal 422 is attributable) or 1..10 withdrawals, all of one Telegram user, in
     * ascending {@code recorded_at}.
     *
     * @throws IllegalArgumentException for any other batch
     */
    public static String requestBody(List<ConsentEvent> events) {
        if (events == null || events.isEmpty()) throw new IllegalArgumentException("empty consents batch");
        ConsentEvent first = events.get(0);
        if (first.isGrant() ? events.size() != 1 : events.size() > MAX_WITHDRAWALS_PER_REQUEST) {
            throw new IllegalArgumentException("a consents batch is one grant alone or 1.." + MAX_WITHDRAWALS_PER_REQUEST + " withdrawals");
        }
        ArrayList<Object> items = new ArrayList<>(events.size());
        long previous = Long.MIN_VALUE;
        for (ConsentEvent event : events) {
            if (event.isGrant() != first.isGrant() || event.tgUserId != first.tgUserId || event.recordedAt < previous) {
                throw new IllegalArgumentException("a consents batch is one user's events of one action in ascending recorded_at");
            }
            previous = event.recordedAt;
            items.add(new CustomerJson.Raw(event.json));
        }
        return new CustomerJson.Obj().put("schema_version", SCHEMA_VERSION).put("consents", items).toString();
    }

    @Override
    public boolean equals(Object other) { return other instanceof ConsentEvent && json.equals(((ConsentEvent) other).json); }

    @Override
    public int hashCode() { return json.hashCode(); }
}
