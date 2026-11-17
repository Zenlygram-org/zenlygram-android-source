/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.zenlygram.customer.ZgMyData.Label;
import org.telegram.zenlygram.customer.ZgMyData.Row;
import org.telegram.zenlygram.customer.ZgMyData.Section;

import java.util.ArrayList;
import java.util.List;

/** "My data": the sections and rows of a GET /v1/profile/me answer, in the iOS order, server data only. */
public class ZgMyDataTest {

    /** Texts without the app's strings: the value is tagged with what would format it. */
    private static final ZgMyData.Texts TEXTS = new ZgMyData.Texts() {
        @Override
        public String time(String instant) {
            return instant == null ? null : "t(" + instant + ")";
        }

        @Override
        public String duration(long seconds) {
            return seconds + "s";
        }

        @Override
        public String days(int count) {
            return count + " days";
        }

        @Override
        public String months(int count) {
            return count + " months";
        }

        @Override
        public String years(int count) {
            return count + " years";
        }

        @Override
        public String consentState(String state) {
            return state == null || "none".equals(state) ? "Not agreed" : "granted".equals(state) ? "Agreed"
                : "withdrawn".equals(state) ? "Withdrawn" : state;
        }

        @Override
        public String consentAction(String action) {
            return "grant".equals(action) ? "Agreed" : "withdraw".equals(action) ? "Withdrawn" : null;
        }

        @Override
        public String source(String source) {
            return "telegram_attest".equals(source) ? "Confirmed by Telegram" : "client".equals(source) ? "From the app" : null;
        }

        @Override
        public String privacyEvents(int count) {
            return "Privacy features: " + count + " events";
        }
    };

    private static final String BODY = "{\"schema_version\":1,"
        + "\"account\":{\"id\":\"acc_01\",\"tg_user_id\":5123456789,\"created_at\":\"2026-11-01T10:00:00Z\"},"
        + "\"consent\":{\"state\":\"granted\",\"consent_id\":\"3f2b8c1e-7a4d-4e9b-9c0a-5d6e7f8091a2\",\"text_version\":1,\"locale\":\"uz\","
        + "\"consent_mode\":\"required\",\"effective_at\":\"2026-11-12T09:30:15Z\"},"
        + "\"consent_history\":["
        + "{\"action\":\"grant\",\"text_version\":1,\"locale\":\"ru\",\"platform\":\"ios\",\"app_version\":\"12.4\",\"recorded_at\":\"2026-11-02T08:00:00Z\",\"outcome\":\"recorded\"},"
        + "{\"action\":\"grant\",\"text_version\":1,\"locale\":\"uz\",\"platform\":\"android\",\"app_version\":\"13.2.1\",\"recorded_at\":\"2026-11-12T09:30:15Z\",\"outcome\":\"recorded\"},"
        + "{\"action\":\"withdraw\",\"text_version\":1,\"locale\":\"ru\",\"platform\":\"ios\",\"recorded_at\":\"2026-11-05T08:00:00.250Z\",\"outcome\":\"recorded\"},"
        + "{\"action\":\"import\",\"recorded_at\":null}],"
        + "\"profile\":{\"phone\":{\"value\":\"+998901234567\",\"source\":\"client\",\"observed_at\":\"2026-11-12T09:31:00Z\"},"
        + "\"country\":\"UZ\","
        + "\"usernames\":{\"value\":[\"alpha\",\"beta\"],\"source\":\"telegram_attest\",\"observed_at\":\"2026-11-12T09:30:20Z\"},"
        + "\"first_name\":{\"value\":\"Ali\",\"source\":\"telegram_bot\"},"
        + "\"last_name\":null,"
        + "\"language_code\":{\"value\":\"uz\",\"source\":\"client\"},"
        + "\"is_premium\":{\"value\":false,\"source\":\"client\"}},"
        + "\"devices\":["
        + "{\"device_id\":\"d1\",\"platform\":\"android\",\"device_class\":\"phone\",\"device_model\":\"Pixel 8\",\"os_version\":\"15\","
        + "\"app_version\":\"13.2.1\",\"app_build\":\"7297\",\"ui_language\":\"uz\",\"locale\":\"uz-UZ\",\"timezone\":\"Asia/Tashkent\","
        + "\"distribution\":\"play\",\"first_seen_at\":\"2026-11-12T09:30:20Z\",\"last_seen_at\":\"2026-11-14T07:00:00Z\"},"
        + "{\"device_id\":\"d2\",\"platform\":\"ios\",\"device_model\":\"iPhone\"}],"
        + "\"activity\":{\"first_active_at\":\"2026-11-02T08:00:00Z\",\"last_active_at\":\"2026-11-14T07:00:00Z\",\"active_days\":9,"
        + "\"sessions_30d\":41,\"session_seconds_30d\":7260},"
        + "\"events\":{\"retention_days\":180,\"total\":120,"
        + "\"by_name\":[{\"name\":\"screen.view\",\"count\":80},{\"name\":\"vault.unlocked\",\"count\":7},{\"name\":\"chat_lock.unlocked\",\"count\":3},"
        + "{\"name\":\"app.session_start\",\"count\":30}],"
        + "\"recent\":[{\"name\":\"screen.view\",\"ts\":\"2026-11-14T07:00:00Z\"},{\"name\":\"vault.chat_added\",\"ts\":\"2026-11-14T06:59:00Z\"},"
        + "{\"name\":\"app.session_start\",\"ts\":\"2026-11-14T06:58:00Z\"}]},"
        + "\"subscription\":{\"reason\":\"free_access\",\"status\":null,\"source\":null,\"until\":null},"
        + "\"retention\":{\"profile\":\"until_withdrawal\",\"events_days\":180,\"activity_months\":25,\"consent_log_years_after_erasure\":3}}";

    private static Section section(List<Section> sections, Label title) {
        for (Section section : sections) {
            if (section.title == title) {
                return section;
            }
        }
        return null;
    }

    private static Row row(Section section, Label label) {
        for (Row row : section.rows) {
            if (row.label == label) {
                return row;
            }
        }
        return null;
    }

    private static List<String> values(Section section) {
        final ArrayList<String> values = new ArrayList<>();
        for (Row row : section.rows) {
            values.add((row.rawLabel != null ? row.rawLabel + "=" : "") + row.value);
        }
        return values;
    }

    @Test
    public void theSectionsInTheIosOrder() {
        final List<Section> sections = ZgMyData.sections(BODY, TEXTS);
        assertNotNull(sections);
        final ArrayList<Label> titles = new ArrayList<>();
        for (Section section : sections) {
            titles.add(section.title);
        }
        assertEquals(java.util.Arrays.asList(Label.ACCOUNT, Label.CONSENT, Label.HISTORY, Label.PROFILE, Label.DEVICES, null,
            Label.ACTIVITY, Label.EVENTS, Label.SUBSCRIPTION, Label.RETENTION), titles);
    }

    @Test
    public void accountConsentAndHistory() {
        final List<Section> sections = ZgMyData.sections(BODY, TEXTS);
        final Section account = section(sections, Label.ACCOUNT);
        assertEquals("acc_01", row(account, Label.ID).value);
        assertEquals("5123456789", row(account, Label.TELEGRAM_ID).value);
        assertEquals("t(2026-11-01T10:00:00Z)", row(account, Label.CREATED).value);

        final Section consent = section(sections, Label.CONSENT);
        assertEquals(1, consent.rows.size());
        assertEquals("Agreed", consent.rows.get(0).value);
        assertEquals("t(2026-11-12T09:30:15Z) · v1 · uz · required", consent.rows.get(0).detail);

        // Newest first; the entry without a time last; an action the registry does not name as it is.
        final Section history = section(sections, Label.HISTORY);
        assertEquals(java.util.Arrays.asList("Agreed", "Withdrawn", "Agreed", "import"), values(history));
        assertEquals("t(2026-11-12T09:30:15Z) · v1 · uz · android · 13.2.1 · recorded", history.rows.get(0).detail);
        assertEquals("t(2026-11-05T08:00:00.250Z) · v1 · ru · ios · recorded", history.rows.get(1).detail);
        assertNull(history.rows.get(3).detail);
    }

    @Test
    public void profileWithSources() {
        final Section profile = section(ZgMyData.sections(BODY, TEXTS), Label.PROFILE);
        assertEquals("+998901234567", row(profile, Label.PHONE).value);
        assertEquals("From the app · t(2026-11-12T09:31:00Z)", row(profile, Label.PHONE).detail);
        assertEquals("UZ", row(profile, Label.COUNTRY).value);
        assertEquals("@alpha, @beta", row(profile, Label.USERNAMES).value);
        assertEquals("Confirmed by Telegram · t(2026-11-12T09:30:20Z)", row(profile, Label.USERNAMES).detail);
        assertEquals("Ali", row(profile, Label.FIRST_NAME).value);
        // A source the app has no name for is shown as the server names it.
        assertEquals("telegram_bot", row(profile, Label.FIRST_NAME).detail);
        assertNull("a value the server does not hold has no row", row(profile, Label.LAST_NAME));
        assertEquals("uz", row(profile, Label.LANGUAGE).value);
        assertEquals("—", row(profile, Label.PREMIUM).value);
    }

    @Test
    public void devicesActivityAndRetention() {
        final List<Section> sections = ZgMyData.sections(BODY, TEXTS);
        final Section first = section(sections, Label.DEVICES);
        assertEquals("Pixel 8 · phone", row(first, Label.MODEL).value);
        assertEquals("android 15", row(first, Label.OS).value);
        assertEquals("13.2.1 (7297)", row(first, Label.APP).value);
        assertEquals("uz-UZ", row(first, Label.LOCALE).value);
        assertEquals("Asia/Tashkent", row(first, Label.TIME_ZONE).value);
        assertEquals("play", row(first, Label.DISTRIBUTION).value);
        assertEquals("t(2026-11-14T07:00:00Z)", row(first, Label.LAST_SEEN).value);
        // The second device follows without a second title; what the server left out is a dash.
        final Section second = sections.get(sections.indexOf(first) + 1);
        assertNull(second.title);
        assertEquals("iPhone", row(second, Label.MODEL).value);
        assertEquals("ios", row(second, Label.OS).value);
        assertEquals("—", row(second, Label.APP).value);
        assertEquals("—", row(second, Label.LAST_SEEN).value);

        final Section activity = section(sections, Label.ACTIVITY);
        assertEquals("9", row(activity, Label.ACTIVE_DAYS).value);
        assertEquals("41", row(activity, Label.SESSIONS_30).value);
        assertEquals("7260s", row(activity, Label.SECONDS_30).value);

        final Section subscription = section(sections, Label.SUBSCRIPTION);
        assertEquals("free_access", subscription.rows.get(0).value);
        assertNull(subscription.rows.get(0).detail);

        final Section retention = section(sections, Label.RETENTION);
        assertEquals("until withdrawal", row(retention, Label.PROFILE).value);
        assertEquals("180 days", row(retention, Label.EVENTS).value);
        assertEquals("25 months", row(retention, Label.ACTIVITY).value);
        assertEquals("3 years", row(retention, Label.CONSENT).value);
    }

    @Test
    public void vaultAndChatLockEventsAreOneCount() {
        final Section events = section(ZgMyData.sections(BODY, TEXTS), Label.EVENTS);
        assertEquals("120", row(events, Label.EVENTS_TOTAL).value);
        assertEquals("180 days", row(events, Label.RETAINED).value);
        assertEquals(java.util.Arrays.asList("120", "180 days", "screen.view=80", "app.session_start=30", "Privacy features: 10 events",
            "screen.view=t(2026-11-14T07:00:00Z)", "app.session_start=t(2026-11-14T06:58:00Z)"), values(events));
        for (Row row : events.rows) {
            assertFalse(String.valueOf(row.rawLabel), row.rawLabel != null && ZgMyData.privacyFeature(row.rawLabel));
        }
        assertTrue(ZgMyData.privacyFeature("vault.unlocked"));
        assertTrue(ZgMyData.privacyFeature("chat_lock.lock_set"));
        assertFalse(ZgMyData.privacyFeature("tasks.task_created"));
    }

    @Test
    public void aSparseAnswerShowsWhatItHas() {
        final List<Section> sections = ZgMyData.sections("{\"schema_version\":1}", TEXTS);
        assertNotNull(sections);
        // Only the consent section, which says there is none on the server.
        assertEquals(1, sections.size());
        assertEquals(Label.CONSENT, sections.get(0).title);
        assertEquals("Not agreed", sections.get(0).rows.get(0).value);
        assertNull(sections.get(0).rows.get(0).detail);

        final List<Section> withdrawn = ZgMyData.sections("{\"schema_version\":1,\"consent\":{\"state\":\"withdrawn\",\"effective_at\":null},"
            + "\"account\":{\"id\":null,\"tg_user_id\":null},\"profile\":{},\"devices\":[],\"consent_history\":[]}", TEXTS);
        assertEquals(2, withdrawn.size());
        assertEquals("—", row(section(withdrawn, Label.ACCOUNT), Label.ID).value);
        assertEquals("—", row(section(withdrawn, Label.ACCOUNT), Label.CREATED).value);
        assertEquals("Withdrawn", section(withdrawn, Label.CONSENT).rows.get(0).value);
    }

    @Test
    public void anAnswerOfAnotherVersionIsNotShown() {
        assertNull(ZgMyData.sections("{\"schema_version\":2,\"account\":{\"id\":\"x\"}}", TEXTS));
        assertNull(ZgMyData.sections("{\"account\":{\"id\":\"x\"}}", TEXTS));
        assertNull(ZgMyData.sections("[]", TEXTS));
        assertNull(ZgMyData.sections("", TEXTS));
    }

    @Test
    public void serverInstants() {
        assertEquals(1_793_865_600L, ZgCustomerFormat.serverInstant("2026-11-05T08:00:00Z"));
        assertEquals(1_793_865_600L, ZgCustomerFormat.serverInstant("2026-11-05T08:00:00.250Z"));
        assertEquals(-1, ZgCustomerFormat.serverInstant("2026-11-05T08:00:00+05:00"));
        assertEquals(-1, ZgCustomerFormat.serverInstant("2026-11-05T08:00:00.Z"));
        assertEquals(-1, ZgCustomerFormat.serverInstant("2026-11-05T08:00:00.2x0Z"));
        assertEquals(-1, ZgCustomerFormat.serverInstant("yesterday"));
        assertEquals(-1, ZgCustomerFormat.serverInstant(null));
    }

    @Test
    public void accountLineFallsBackToAMaskedPhone() {
        assertEquals("+998 ••••••• 67", ZgCustomerFormat.maskedPhone("998901234567"));
        assertEquals("+998 ••••••• 67", ZgCustomerFormat.maskedPhone("+998 90 123-45-67"));
        assertNull(ZgCustomerFormat.maskedPhone("12345"));
        assertNull(ZgCustomerFormat.maskedPhone(null));
        assertEquals("a.", ZgCustomerFormat.sentence("a.."));
    }
}
