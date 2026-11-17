/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.customer.ConsentEvent;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.messenger.zenlygram.customer.CustomerJson;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

/**
 * The one-time move of the consents this app recorded before it used the cloud client's store. Those builds kept a
 * record ({@code zg.consent.v1.<tg>}) and the exact grant event ({@code zg.consent.outbox.v1.<tg>}, an array of JSON
 * texts) in the same preferences file the client uses, and never uploaded anything.
 *
 * <p>A GRANTED record of a signed-in account moves when the client holds nothing for that user and this build still
 * shows the very text the user agreed to (the same {@code text_sha256} for the record's version and locale). The grant
 * is stored with its original {@code consent_id}, {@code recorded_at} and {@code app_version}
 * ({@code ZenlyConsent.adoptGrant}), so the server later receives the event the user's tap made, not a new one dated
 * at the move. Everything else — another state, a text this build renders differently, a record without its event, a
 * user who is not signed in — is removed, and the wall asks again: a consent is never guessed.
 *
 * <p>{@link #run} runs once per process on the main thread before the first wall decision ({@link ZgCustomer});
 * {@link #plan} is the pure decision (ZgConsentMigrationTest).
 */
final class ZgConsentMigration {

    static final String PREFS = "zenlygram_consent";
    /** Every key of the earlier store starts with this; the client's own keys start with "consent.". */
    static final String LEGACY_PREFIX = "zg.consent.";
    static final String RECORD_PREFIX = "zg.consent.v1.";
    static final String OUTBOX_PREFIX = "zg.consent.outbox.v1.";

    private ZgConsentMigration() {
    }

    /** This build's current text in a locale (uz / ru / en), or null. */
    interface Texts {
        ConsentText.Shown shown(String locale);
    }

    /** A grant to move: exactly what its event named. */
    static final class Plan {
        final ConsentText.Shown text;
        final String consentId;
        /** Epoch seconds of the event's {@code recorded_at}. */
        final long recordedAt;
        final String appVersion;

        Plan(ConsentText.Shown text, String consentId, long recordedAt, String appVersion) {
            this.text = text;
            this.consentId = consentId;
            this.recordedAt = recordedAt;
            this.appVersion = appVersion;
        }
    }

    /**
     * What to move for the user {@code tg}, or null (nothing moves). {@code recordJson} and {@code outboxJson} are the
     * two stored values of that user; {@code mode} is this build's consent mode ("required" / "optional").
     */
    static Plan plan(long tg, String recordJson, String outboxJson, Texts texts, String mode) {
        try {
            if (tg <= 0 || recordJson == null || outboxJson == null || texts == null) {
                return null;
            }
            final JSONObject record = new JSONObject(recordJson);
            if (!"granted".equals(record.optString("state"))) {
                return null;
            }
            final String consentId = record.optString("consent_id", "");
            final String locale = record.optString("locale", "");
            final long decidedAt = CustomerJson.parseInstant(record.optString("decided_at", ""));
            // The text the user agreed to must be the one this build shows for that locale, hash for hash.
            final ConsentText.Shown shown = texts.shown(locale);
            if (shown == null || shown.version != record.optInt("text_version", 0) || !shown.sha256.equals(record.optString("text_sha256", ""))) {
                return null;
            }
            // Its event: the entry of the outbox with the record's consent id. The client's own parser reads it, so
            // only an event the contract accepts moves.
            final JSONArray outbox = new JSONArray(outboxJson);
            for (int i = outbox.length() - 1; i >= 0; i--) {
                final ConsentEvent event = ConsentEvent.fromTree(ZgJson.parse(outbox.optString(i, null)));
                if (event == null || !event.consentId.equals(consentId)) {
                    continue;
                }
                if (!event.isGrant() || event.tgUserId != tg || event.recordedAt <= 0 || event.recordedAt != decidedAt
                    || event.textVersion != shown.version || !shown.sha256.equals(event.textSha256) || !shown.locale.equals(event.locale)
                    || !shown.scopes.equals(event.scopes) || !event.consentMode.equals(mode) || !ConsentEvent.PLATFORM.equals(event.platform)) {
                    return null;
                }
                return new Plan(shown, event.consentId, event.recordedAt, event.appVersion);
            }
            return null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * Main thread. Moves what {@link #plan} allows for every signed-in account, then removes the earlier store's keys.
     * A user whose move failed on storage keeps the keys, so the next launch tries again. True when a consent moved.
     */
    static boolean run() {
        boolean moved = false;
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return false;
            }
            final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final Map<String, ?> all = prefs.getAll();
            final ArrayList<String> legacy = new ArrayList<>();
            for (String key : all.keySet()) {
                if (key.startsWith(LEGACY_PREFIX)) {
                    legacy.add(key);
                }
            }
            if (legacy.isEmpty()) {
                return false;
            }
            final HashSet<String> keep = new HashSet<>();
            final String mode = ZenlyConsent.mode().wire;
            final Texts texts = locale -> ZgCustomer.canGrant() ? ZenlyConsent.text(locale) : null;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                final UserConfig config = UserConfig.getInstance(account);
                final long tg = config.isClientActivated() ? config.getClientUserId() : 0;
                final Object record = tg > 0 ? all.get(RECORD_PREFIX + tg) : null;
                if (!(record instanceof String) || !ZenlyConsent.snapshot(tg).isEmpty()) {
                    continue; // nothing recorded before, or the client already has this user's consent state
                }
                final Object outbox = all.get(OUTBOX_PREFIX + tg);
                final Plan plan = plan(tg, (String) record, outbox instanceof String ? (String) outbox : null, texts, mode);
                if (plan == null) {
                    continue;
                }
                if (ZenlyConsent.adoptGrant(account, plan.text, plan.consentId, plan.recordedAt, plan.appVersion)) {
                    // The same call order as a grant made on the wall: the coordinator takes it from here.
                    ZenlyCustomer.onConsentChanged(account);
                    moved = true;
                } else if (ZenlyConsent.snapshot(tg).isEmpty()) {
                    // The plan only names values the contract accepts, so this was storage: not lost, tried again.
                    keep.add(RECORD_PREFIX + tg);
                    keep.add(OUTBOX_PREFIX + tg);
                }
            }
            final SharedPreferences.Editor editor = prefs.edit();
            for (String key : legacy) {
                if (!keep.contains(key)) {
                    editor.remove(key);
                }
            }
            editor.apply();
        } catch (Throwable t) {
            // Never the throwable itself: a storage message may name a key, and a key names a Telegram id.
            FileLog.e("zg consent: the earlier store was not moved (" + t.getClass().getSimpleName() + ")");
        }
        return moved;
    }

    /** Telegram logout of the user {@code tg}: what the earlier store kept for them goes, moved or not. */
    static void forget(long tg) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null || tg <= 0) {
                return;
            }
            final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.contains(RECORD_PREFIX + tg) || prefs.contains(OUTBOX_PREFIX + tg)) {
                prefs.edit().remove(RECORD_PREFIX + tg).remove(OUTBOX_PREFIX + tg).apply();
            }
        } catch (Throwable t) {
            FileLog.e("zg consent: the earlier store was not cleared (" + t.getClass().getSimpleName() + ")");
        }
    }
}
