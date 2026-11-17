/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What "My data" shows of a {@code GET /v1/profile/me} answer (iOS SGMyDataView, SGProfileMe): the sections Account,
 * Consent, Consent history (newest first, at most 50), Profile (each value with where it came from), Devices,
 * Activity, Statistics events, Subscription and Retention, server data only. A section the server left out is not
 * shown; a missing value is "—".
 *
 * <p>Events of the Secret Vault and Chat Lock ({@code vault.*}, {@code chat_lock.*}) are folded into one count with no
 * names and no times: those features exist to conceal, and anyone holding the unlocked phone could otherwise read when
 * chats were hidden. Pure Java (org.json); the texts that depend on the app language come through {@link Texts}
 * (ZgMyDataTest).
 */
final class ZgMyData {

    /** Events that are never listed by name or time. */
    private static final String[] PRIVACY_PREFIXES = {"vault.", "chat_lock."};
    private static final int NEWEST = 50;
    static final String MISSING = "—";

    private ZgMyData() {
    }

    /** The section titles and field names; the screen maps each to its string. */
    enum Label {
        ACCOUNT, CONSENT, HISTORY, PROFILE, DEVICES, ACTIVITY, EVENTS, SUBSCRIPTION, RETENTION,
        ID, TELEGRAM_ID, CREATED,
        PHONE, COUNTRY, USERNAMES, FIRST_NAME, LAST_NAME, LANGUAGE, PREMIUM,
        MODEL, OS, APP, LOCALE, TIME_ZONE, DISTRIBUTION, FIRST_SEEN, LAST_SEEN,
        FIRST_ACTIVE, LAST_ACTIVE, ACTIVE_DAYS, SESSIONS_30, SECONDS_30,
        EVENTS_TOTAL, RETAINED
    }

    /** The app-language texts a row needs. */
    interface Texts {
        /** A server instant in the device's time zone (the raw text when unreadable); null for null. */
        String time(String instant);

        String duration(long seconds);

        String days(int count);

        String months(int count);

        String years(int count);

        /** The server's consent state as the Settings row names it (an unknown one as it is). */
        String consentState(String state);

        /** A consent history action (grant / withdraw) as the Settings row names it; null for an unknown one. */
        String consentAction(String action);

        /** Where a profile value came from; null for an unknown source. */
        String source(String source);

        /** "Privacy features: N events". */
        String privacyEvents(int count);
    }

    static final class Row {
        /** The small line above the value: a field name, or null. */
        final Label label;
        /** A small line that is not a field name (an event's registry name), or null. */
        final String rawLabel;
        final String value;
        final String detail;

        Row(Label label, String rawLabel, String value, String detail) {
            this.label = label;
            this.rawLabel = rawLabel;
            this.value = value;
            this.detail = detail;
        }
    }

    static final class Section {
        /** Null for a further device of the Devices section (a gap instead of a second title). */
        final Label title;
        final List<Row> rows = new ArrayList<>();

        Section(Label title) {
            this.title = title;
        }

        private void field(Label label, String value) {
            rows.add(new Row(label, null, value == null ? MISSING : value, null));
        }
    }

    /**
     * The sections of an answer body, or null when it is not a version 1 answer (the screen then says the data could
     * not be loaded).
     */
    static List<Section> sections(String body, Texts texts) {
        final JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception unreadable) {
            return null;
        }
        if (root.optInt("schema_version", 0) != 1) {
            return null;
        }
        final ArrayList<Section> sections = new ArrayList<>();

        final JSONObject account = root.optJSONObject("account");
        if (account != null) {
            final Section section = new Section(Label.ACCOUNT);
            section.field(Label.ID, string(account, "id"));
            section.field(Label.TELEGRAM_ID, string(account, "tg_user_id"));
            section.field(Label.CREATED, texts.time(string(account, "created_at")));
            sections.add(section);
        }

        // Always there: without a consent on the server the row says so.
        final JSONObject consent = root.optJSONObject("consent");
        final Section consentSection = new Section(Label.CONSENT);
        consentSection.rows.add(new Row(null, null, texts.consentState(consent == null ? null : string(consent, "state")),
            join(" · ", consent == null ? null : texts.time(string(consent, "effective_at")), version(consent),
                consent == null ? null : string(consent, "locale"), consent == null ? null : string(consent, "consent_mode"))));
        sections.add(consentSection);

        final List<JSONObject> history = objects(root.optJSONArray("consent_history"));
        if (!history.isEmpty()) {
            // Newest first; an entry without a readable time goes last.
            Collections.sort(history, (a, b) -> Long.compare(ZgCustomerFormat.serverInstant(string(b, "recorded_at")),
                ZgCustomerFormat.serverInstant(string(a, "recorded_at"))));
            final Section section = new Section(Label.HISTORY);
            for (int i = 0; i < Math.min(NEWEST, history.size()); i++) {
                final JSONObject entry = history.get(i);
                final String action = string(entry, "action");
                final String named = texts.consentAction(action);
                section.rows.add(new Row(null, null, named != null ? named : action != null ? action : MISSING,
                    join(" · ", texts.time(string(entry, "recorded_at")), version(entry), string(entry, "locale"), string(entry, "platform"),
                        string(entry, "app_version"), string(entry, "outcome"))));
            }
            sections.add(section);
        }

        final JSONObject profile = root.optJSONObject("profile");
        if (profile != null) {
            final Section section = new Section(Label.PROFILE);
            sourced(section, Label.PHONE, profile.optJSONObject("phone"), texts);
            final String country = string(profile, "country");
            if (country != null) {
                section.field(Label.COUNTRY, country);
            }
            sourced(section, Label.USERNAMES, profile.optJSONObject("usernames"), texts);
            sourced(section, Label.FIRST_NAME, profile.optJSONObject("first_name"), texts);
            sourced(section, Label.LAST_NAME, profile.optJSONObject("last_name"), texts);
            sourced(section, Label.LANGUAGE, profile.optJSONObject("language_code"), texts);
            sourced(section, Label.PREMIUM, profile.optJSONObject("is_premium"), texts);
            if (!section.rows.isEmpty()) {
                sections.add(section);
            }
        }

        boolean firstDevice = true;
        for (JSONObject device : objects(root.optJSONArray("devices"))) {
            final Section section = new Section(firstDevice ? Label.DEVICES : null);
            firstDevice = false;
            section.field(Label.MODEL, join(" · ", string(device, "device_model"), string(device, "device_class")));
            section.field(Label.OS, join(" ", string(device, "platform"), string(device, "os_version")));
            final String build = string(device, "app_build");
            section.field(Label.APP, join(" ", string(device, "app_version"), build == null ? null : "(" + build + ")"));
            section.field(Label.LOCALE, string(device, "locale"));
            section.field(Label.TIME_ZONE, string(device, "timezone"));
            section.field(Label.DISTRIBUTION, string(device, "distribution"));
            section.field(Label.FIRST_SEEN, texts.time(string(device, "first_seen_at")));
            section.field(Label.LAST_SEEN, texts.time(string(device, "last_seen_at")));
            sections.add(section);
        }

        final JSONObject activity = root.optJSONObject("activity");
        if (activity != null) {
            final Section section = new Section(Label.ACTIVITY);
            section.field(Label.FIRST_ACTIVE, texts.time(string(activity, "first_active_at")));
            section.field(Label.LAST_ACTIVE, texts.time(string(activity, "last_active_at")));
            section.field(Label.ACTIVE_DAYS, string(activity, "active_days"));
            section.field(Label.SESSIONS_30, string(activity, "sessions_30d"));
            final long seconds = integer(activity, "session_seconds_30d");
            section.field(Label.SECONDS_30, seconds < 0 ? null : texts.duration(seconds));
            sections.add(section);
        }

        final JSONObject events = root.optJSONObject("events");
        if (events != null) {
            final Section section = new Section(Label.EVENTS);
            section.field(Label.EVENTS_TOTAL, string(events, "total"));
            final long kept = integer(events, "retention_days");
            if (kept > 0) {
                section.field(Label.RETAINED, texts.days(clamp(kept)));
            }
            long privacy = 0;
            for (JSONObject entry : objects(events.optJSONArray("by_name"))) {
                final String name = string(entry, "name");
                final long count = integer(entry, "count");
                if (name == null || count < 0) {
                    continue;
                }
                if (privacyFeature(name)) {
                    privacy += count;
                } else {
                    section.rows.add(new Row(null, name, String.valueOf(count), null));
                }
            }
            if (privacy > 0) {
                section.rows.add(new Row(null, null, texts.privacyEvents(clamp(privacy)), null));
            }
            int shown = 0;
            for (JSONObject entry : objects(events.optJSONArray("recent"))) {
                final String name = string(entry, "name");
                if (name == null || privacyFeature(name)) {
                    continue;
                }
                if (shown++ == NEWEST) {
                    break;
                }
                final String time = texts.time(string(entry, "ts"));
                section.rows.add(new Row(null, name, time == null ? MISSING : time, null));
            }
            sections.add(section);
        }

        final JSONObject subscription = root.optJSONObject("subscription");
        if (subscription != null) {
            final Section section = new Section(Label.SUBSCRIPTION);
            final String status = string(subscription, "status");
            final String reason = string(subscription, "reason");
            section.rows.add(new Row(null, null, status != null ? status : reason != null ? reason : MISSING,
                join(" · ", texts.time(string(subscription, "until")), string(subscription, "source"))));
            sections.add(section);
        }

        final JSONObject retention = root.optJSONObject("retention");
        if (retention != null) {
            final Section section = new Section(Label.RETENTION);
            final String rule = string(retention, "profile");
            section.field(Label.PROFILE, rule == null ? null : rule.replace('_', ' '));
            final long days = integer(retention, "events_days");
            section.field(Label.EVENTS, days > 0 ? texts.days(clamp(days)) : null);
            final long months = integer(retention, "activity_months");
            section.field(Label.ACTIVITY, months > 0 ? texts.months(clamp(months)) : null);
            final long years = integer(retention, "consent_log_years_after_erasure");
            section.field(Label.CONSENT, years > 0 ? texts.years(clamp(years)) : null);
            sections.add(section);
        }
        return sections;
    }

    /** {@code name} belongs to the Secret Vault or Chat Lock. */
    static boolean privacyFeature(String name) {
        for (String prefix : PRIVACY_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** A profile field {@code {value, source, observed_at}}: its value, and where and when it was observed. */
    private static void sourced(Section section, Label label, JSONObject field, Texts texts) {
        final String value = value(field, label == Label.USERNAMES);
        if (value == null) {
            return;
        }
        final String source = string(field, "source");
        final String named = source == null ? null : texts.source(source);
        section.rows.add(new Row(label, null, value, join(" · ", named != null ? named : source, texts.time(string(field, "observed_at")))));
    }

    /** The {@code value} of a profile field: a list joined with commas (usernames with their @), a boolean as ✓ / —. */
    private static String value(JSONObject field, boolean usernames) {
        if (field == null) {
            return null;
        }
        final Object value = field.opt("value");
        if (value instanceof JSONArray) {
            final JSONArray list = (JSONArray) value;
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                final Object item = list.opt(i);
                if (item == null || item == JSONObject.NULL) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(usernames ? "@" : "").append(item);
            }
            return out.length() == 0 ? null : out.toString();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "✓" : MISSING;
        }
        return string(field, "value");
    }

    private static String version(JSONObject object) {
        final String version = object == null ? null : string(object, "text_version");
        return version == null ? null : "v" + version;
    }

    /** A scalar member as text (numbers and booleans too); null for null, a missing key, a container or "". */
    static String string(JSONObject object, String key) {
        if (object == null) {
            return null;
        }
        final Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL || value instanceof JSONObject || value instanceof JSONArray) {
            return null;
        }
        final String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    /** A whole-number member, or -1 when it is missing or not a whole number. */
    private static long integer(JSONObject object, String key) {
        final Object value = object.opt(key);
        if (value instanceof Integer || value instanceof Long) {
            return Math.max(-1, ((Number) value).longValue());
        }
        return -1;
    }

    private static int clamp(long value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }

    private static List<JSONObject> objects(JSONArray array) {
        final ArrayList<JSONObject> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                final JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    list.add(object);
                }
            }
        }
        return list;
    }

    private static String join(String separator, String... parts) {
        final StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(part);
        }
        return out.length() == 0 ? null : out.toString();
    }
}
