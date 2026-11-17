/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.telegram.messenger.zenlygram.customer.ConsentEvent;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.messenger.zenlygram.customer.CustomerJson;

import java.io.File;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The move of a consent recorded by the builds before the cloud client's store: what those builds wrote (rebuilt here
 * field by field as ZgConsentStore.grant wrote it) moves with its original consent id, time and app version, the event
 * the client then stores is the event the user's tap made, and nothing else moves.
 */
public class ZgConsentMigrationTest {

    private static final long TG = 5123456789L;
    private static final String AUTH = "zenlygram.org";
    private static final String ID = "3f2b8c1e-7a4d-4e9b-9c0a-5d6e7f8091a2";
    private static final String AT = "2026-11-12T09:30:15Z";

    private static ZgConsentBundle bundle() {
        File dir = new File("src/main/assets/zenlygram/consent");
        if (!dir.isDirectory()) {
            dir = new File("TMessagesProj/src/main/assets/zenlygram/consent");
        }
        final File root = dir;
        final ZgConsentBundle bundle = ZgConsentBundle.load(path -> {
            try {
                return Files.readAllBytes(new File(root, path).toPath());
            } catch (Exception e) {
                return null;
            }
        });
        assertNotNull(bundle);
        return bundle;
    }

    private static Map<String, String> operator() {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("OPERATOR_NAME", "\"ZENLYGRAM\" MCHJ");
        values.put("OPERATOR_STIR", "313454883");
        values.put("RESPONSIBLE_PERSON", "Abdurahmonov Abdurashid");
        values.put("CONTACT_EMAIL", "info@zenlygram.com");
        return values;
    }

    /** This build's texts, as ZenlyConsent.text gives them. */
    private static ZgConsentMigration.Texts texts() {
        final ZgConsentBundle bundle = bundle();
        return locale -> bundle.shown(bundle.manifest.currentVersion, locale, operator());
    }

    private static ConsentText.Shown shown(String locale) {
        return texts().shown(locale);
    }

    /** The grant event exactly as the earlier store queued it. */
    private static JSONObject legacyEvent(String locale) throws Exception {
        final ConsentText.Shown shown = shown(locale);
        final JSONObject event = new JSONObject();
        event.put("consent_id", ID);
        event.put("tg_user_id", TG);
        event.put("action", "grant");
        event.put("scopes", new JSONArray(shown.scopes));
        event.put("text_version", shown.version);
        event.put("text_sha256", shown.sha256);
        event.put("locale", locale);
        event.put("consent_mode", "required");
        event.put("age_confirmed", true);
        event.put("recorded_at", AT);
        event.put("platform", "android");
        event.put("app_version", "13.2.1");
        return event;
    }

    /** The record exactly as the earlier store wrote it. */
    private static JSONObject legacyRecord(String locale) throws Exception {
        final ConsentText.Shown shown = shown(locale);
        final JSONObject record = new JSONObject();
        record.put("state", "granted");
        record.put("consent_id", ID);
        record.put("text_version", shown.version);
        record.put("text_sha256", shown.sha256);
        record.put("locale", locale);
        record.put("consent_mode", "required");
        record.put("decided_at", AT);
        return record;
    }

    /** The outbox as the earlier store wrote it: an array of the events' JSON texts. */
    private static String outbox(JSONObject... events) {
        final JSONArray array = new JSONArray();
        for (JSONObject event : events) {
            array.put(event.toString());
        }
        return array.toString();
    }

    private static ZgConsentMigration.Plan plan(JSONObject record, String outbox) {
        return ZgConsentMigration.plan(TG, record == null ? null : record.toString(), outbox, texts(), "required");
    }

    @Test
    public void aGrantMovesWithItsOriginalIdTimeAndVersion() throws Exception {
        for (String locale : new String[]{"uz", "ru", "en"}) {
            final ZgConsentMigration.Plan plan = plan(legacyRecord(locale), outbox(legacyEvent(locale)));
            assertNotNull(locale, plan);
            assertEquals(ID, plan.consentId);
            assertEquals(CustomerJson.parseInstant(AT), plan.recordedAt);
            assertEquals("13.2.1", plan.appVersion);
            assertEquals(locale, plan.text.locale);
            assertEquals(shown(locale).sha256, plan.text.sha256);
        }
    }

    /**
     * ZenlyConsent.adoptGrant runs the client's own grant transition on an empty snapshot with the plan's values: the
     * stored grant and its event are the original ones, and the client reads its stored form back unchanged.
     */
    @Test
    public void theClientStoresTheEventTheUserMade() throws Exception {
        final JSONObject legacy = legacyEvent("uz");
        final ZgConsentMigration.Plan plan = plan(legacyRecord("uz"), outbox(legacy));
        assertNotNull(plan);
        final ConsentMachine.Step step = ConsentMachine.grant(ConsentSnapshot.empty(TG, 1), plan.text, ConsentMachine.Mode.REQUIRED,
            plan.consentId, plan.recordedAt, plan.appVersion, AUTH);
        assertTrue(step.changed);
        final ConsentSnapshot adopted = step.snapshot;
        assertEquals(ConsentMachine.State.GRANTED, adopted.effectiveState());
        assertTrue(adopted.recordingAllowed());
        assertEquals(ID, adopted.latestGrant().consentId);
        assertEquals(CustomerJson.parseInstant(AT), adopted.latestGrant().decidedAt);
        assertEquals(1, adopted.outbox.size());

        // Field for field the event the earlier store queued (and the bytes the client would send for it).
        final ConsentEvent event = adopted.latestGrantEvent();
        final JSONObject stored = new JSONObject(event.json());
        assertEquals(legacy.length(), stored.length());
        for (Iterator<String> keys = legacy.keys(); keys.hasNext(); ) {
            final String key = keys.next();
            assertEquals(key, String.valueOf(legacy.get(key)), String.valueOf(stored.get(key)));
        }
        assertEquals(ConsentEvent.fromTree(ZgJson.parse(legacy.toString())).json(), event.json());
        assertEquals(AT, stored.getString("recorded_at"));
        assertEquals(TG, stored.getLong("tg_user_id"));

        // It waits for upload like any grant, and survives a restart.
        assertEquals(1, adopted.pending(AUTH).size());
        final Map<String, String> values = adopted.storedValues();
        assertTrue(values.containsKey("consent.v1." + TG));
        assertTrue(values.containsKey("consent.outbox.v1." + TG));
        final ConsentSnapshot reloaded = ConsentSnapshot.decode(TG, ZgJson.parse(values.get("consent.v1." + TG)),
            ZgJson.parse(values.get("consent.outbox.v1." + TG)), null, null, 0, 1);
        assertEquals(values, reloaded.storedValues());
        assertEquals(event.json(), reloaded.latestGrantJson());
    }

    @Test
    public void theEarlierKeysAreNotTheClients() {
        // The two stores share one preferences file; the client must never read the earlier keys as its own.
        assertNull(ConsentSnapshot.Keys.parse(ZgConsentMigration.RECORD_PREFIX + TG));
        assertNull(ConsentSnapshot.Keys.parse(ZgConsentMigration.OUTBOX_PREFIX + TG));
        assertNotNull(ConsentSnapshot.Keys.parse("consent.v1." + TG));
        assertTrue((ZgConsentMigration.RECORD_PREFIX + TG).startsWith(ZgConsentMigration.LEGACY_PREFIX));
        assertTrue((ZgConsentMigration.OUTBOX_PREFIX + TG).startsWith(ZgConsentMigration.LEGACY_PREFIX));
    }

    @Test
    public void theLatestGrantOfALongerHistoryMoves() throws Exception {
        // Granted, withdrawn, granted again: the record names the last grant; the earlier events were never uploaded.
        final JSONObject first = legacyEvent("ru");
        first.put("consent_id", "11111111-1111-4111-8111-111111111111");
        first.put("recorded_at", "2026-11-10T08:00:00Z");
        final JSONObject withdraw = new JSONObject();
        withdraw.put("consent_id", "22222222-2222-4222-8222-222222222222");
        withdraw.put("tg_user_id", TG);
        withdraw.put("action", "withdraw");
        withdraw.put("scopes", new JSONArray(shown("ru").scopes));
        withdraw.put("text_version", 1);
        withdraw.put("locale", "ru");
        withdraw.put("consent_mode", "required");
        withdraw.put("recorded_at", "2026-11-11T08:00:00Z");
        withdraw.put("platform", "android");
        withdraw.put("app_version", "13.2.1");
        final ZgConsentMigration.Plan plan = plan(legacyRecord("uz"), outbox(first, withdraw, legacyEvent("uz")));
        assertNotNull(plan);
        assertEquals(ID, plan.consentId);
        assertEquals("uz", plan.text.locale);
    }

    @Test
    public void onlyAGrantMoves() throws Exception {
        for (String state : new String[]{"withdrawn", "withdrawn_remote", "declined", "needs_reconsent", "none", ""}) {
            final JSONObject record = legacyRecord("uz");
            record.put("state", state);
            assertNull(state, plan(record, outbox(legacyEvent("uz"))));
        }
        assertNull(plan(null, outbox(legacyEvent("uz"))));
        assertNull(ZgConsentMigration.plan(TG, "not json", outbox(legacyEvent("uz")), texts(), "required"));
        assertNull(ZgConsentMigration.plan(0, legacyRecord("uz").toString(), outbox(legacyEvent("uz")), texts(), "required"));
        assertNull(ZgConsentMigration.plan(TG, legacyRecord("uz").toString(), outbox(legacyEvent("uz")), null, "required"));
    }

    @Test
    public void aTextThisBuildRendersDifferentlyDoesNotMove() throws Exception {
        // Other operator values at the time of the grant: another hash for the same version and locale.
        final JSONObject record = legacyRecord("uz");
        record.put("text_sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        final JSONObject event = legacyEvent("uz");
        event.put("text_sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        assertNull(plan(record, outbox(event)));

        // A version this build does not show as its current text (above the current version 2).
        final JSONObject older = legacyRecord("uz");
        older.put("text_version", 3);
        assertNull(plan(older, outbox(legacyEvent("uz"))));

        // A language without a text.
        final JSONObject german = legacyRecord("uz");
        german.put("locale", "de");
        assertNull(plan(german, outbox(legacyEvent("uz"))));

        // This build cannot show its text at all (the bundle does not verify): nothing is compared, nothing moves.
        assertNull(ZgConsentMigration.plan(TG, legacyRecord("uz").toString(), outbox(legacyEvent("uz")), locale -> null, "required"));
    }

    @Test
    public void aRecordWithoutItsExactEventDoesNotMove() throws Exception {
        assertNull("no outbox", plan(legacyRecord("uz"), null));
        assertNull("empty outbox", plan(legacyRecord("uz"), "[]"));
        assertNull("unreadable outbox", plan(legacyRecord("uz"), "{"));

        final JSONObject otherId = legacyEvent("uz");
        otherId.put("consent_id", "99999999-9999-4999-8999-999999999999");
        assertNull("the event of another consent", plan(legacyRecord("uz"), outbox(otherId)));

        final JSONObject otherUser = legacyEvent("uz");
        otherUser.put("tg_user_id", TG + 1);
        assertNull("another user's event", plan(legacyRecord("uz"), outbox(otherUser)));

        final JSONObject otherTime = legacyEvent("uz");
        otherTime.put("recorded_at", "2026-11-12T09:30:16Z");
        assertNull("the record and the event name different instants", plan(legacyRecord("uz"), outbox(otherTime)));

        final JSONObject otherLocale = legacyEvent("ru");
        assertNull("the event names another text", plan(legacyRecord("uz"), outbox(otherLocale)));

        final JSONObject optional = legacyEvent("uz");
        optional.put("consent_mode", "optional");
        assertNull("recorded under another consent mode", plan(legacyRecord("uz"), outbox(optional)));
        assertNull("this build runs in another consent mode",
            ZgConsentMigration.plan(TG, legacyRecord("uz").toString(), outbox(legacyEvent("uz")), texts(), "optional"));

        final JSONObject withdraw = legacyEvent("uz");
        withdraw.put("action", "withdraw");
        assertNull("not a grant event", plan(legacyRecord("uz"), outbox(withdraw)));

        final JSONObject noAge = legacyEvent("uz");
        noAge.remove("age_confirmed");
        assertNull("a grant without the age confirmation", plan(legacyRecord("uz"), outbox(noAge)));

        final JSONObject badId = legacyEvent("uz");
        badId.put("consent_id", "not-a-uuid");
        final JSONObject badRecord = legacyRecord("uz");
        badRecord.put("consent_id", "not-a-uuid");
        assertNull("an id the contract refuses", plan(badRecord, outbox(badId)));

        final JSONObject unknownKey = legacyEvent("uz");
        unknownKey.put("device", "pixel");
        assertNull("an event with a field the contract does not know", plan(legacyRecord("uz"), outbox(unknownKey)));

        final JSONObject ios = legacyEvent("uz");
        ios.put("platform", "ios");
        assertNull("an event of another platform", plan(legacyRecord("uz"), outbox(ios)));
    }

    @Test
    public void anOutboxOfObjectsReadsLikeOneOfTexts() throws Exception {
        // Tolerated: the same events stored as objects instead of their JSON texts.
        final JSONArray array = new JSONArray();
        array.put(legacyEvent("en"));
        assertNotNull(plan(legacyRecord("en"), array.toString()));
    }
}
