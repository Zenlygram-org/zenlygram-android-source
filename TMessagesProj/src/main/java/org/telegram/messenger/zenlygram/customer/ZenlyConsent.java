package org.telegram.messenger.zenlygram.customer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The consent store and its public API (design 50 §5; contract §6.2), the Android glue around the pure
 * {@link ConsentMachine}: the vendored texts from {@code assets/zenlygram/consent/} (manifest via org.json), the
 * operator values from BuildConfig, the per-account state in {@code SharedPreferences("zenlygram_consent")} under the
 * contract keys ({@code consent.v1.<tg>}, {@code consent.outbox.v1.<tg>}, {@code consent.acked.v1.<tg>@<authority>},
 * Android-internal {@code consent.rejected.v1.<tg>}).
 * <p>
 * Threads: the main thread is the only writer; every write is one synchronous {@code commit()} (small file; the wall
 * waits on nothing but local storage) and only after it succeeded is a new immutable {@link ConsentSnapshot} published
 * through a volatile copy-on-write map, followed by the global {@link NotificationCenter#zenlygramConsentChanged}
 * (args: Integer account). Readers on any thread (the link consent provider, statistics) read only that map. Nothing
 * here touches the network or logs.
 * <p>
 * Entry points of other packages: AND-7's ZenlyCustomer feeds binding losses ({@link #setBindingLoss}, which also
 * stores the grant a loss ended as needs_reconsent), at launch the
 * grants whose text this build renders differently ({@link #normaliseChangedTexts}), server answers
 * ({@link #applyConsentsAnswer}, {@link #applyConsentRequired}, {@link #markRejected}), new bindings
 * ({@link #clearAcked}) and erasure / logout ({@link #forget}); a 409 {@code consent_stale} on {@code bind/start},
 * automatic or manual (AND-5), ends in {@link #markConsentStale}.
 */
public final class ZenlyConsent {
    /** The privacy policy (design 50 §3.9; also linked by the consent text). */
    public static final String PRIVACY_POLICY_URL = "https://zenlygram.org/privacy-policy.html";
    /** The public account deletion page (contract §2.3; uz, unprefixed). */
    public static final String ACCOUNT_DELETE_URL = "https://zenlygram.org/account/delete";

    static final String PREFERENCES = "zenlygram_consent";
    private static final String ASSETS = "zenlygram/consent/";
    private static final int MAX_ASSET_BYTES = 256 * 1024;

    private static final Object LOCK = new Object();
    /** Telegram user id → snapshot; replaced as a whole (copy-on-write) after every successful commit. */
    private static volatile Map<Long, ConsentSnapshot> snapshots = Collections.emptyMap();
    private static volatile boolean loaded;
    private static volatile Texts texts;
    private static final ConcurrentHashMap<String, ConsentText.Shown> SHOWN = new ConcurrentHashMap<>();

    private ZenlyConsent() {}

    /** The bundled manifest, read once ({@code manifest == null}: missing or broken). */
    private static final class Texts {
        final ConsentManifest manifest;

        Texts(ConsentManifest manifest) { this.manifest = manifest; }
    }

    private interface Transition { ConsentMachine.Step apply(ConsentSnapshot current); }

    // ---- Build settings, texts and links ----

    /** {@code BuildConfig.ZENLYGRAM_CONSENT_MODE} (never toggled by the server). */
    public static ConsentMachine.Mode mode() { return ConsentMachine.Mode.of(BuildConfig.ZENLYGRAM_CONSENT_MODE); }

    /** The vendored manifest, or null when it is missing or broken (then no text can be shown or granted). */
    public static ConsentManifest manifest() {
        Texts current = texts;
        if (current == null) {
            // Before ApplicationLoader has its context no asset can be read: that is no answer to keep for the process
            // (min_valid_version would read 0 and no text could be shown until the next start).
            if (ApplicationLoader.applicationContext == null) return null;
            current = new Texts(readManifest());
            texts = current;
        }
        return current.manifest;
    }

    /**
     * The current version's text in {@code locale} as the wall shows and hashes it (the operator values of this build
     * filled in), or null when the manifest or the file is missing, not UTF-8, or an operator value is refused — the
     * wall then shows its "update the app" state and nothing can be granted.
     */
    public static ConsentText.Shown text(String locale) {
        if (!ConsentManifest.LOCALES.contains(locale)) return null;
        ConsentText.Shown cached = SHOWN.get(locale);
        if (cached != null) return cached;
        ConsentManifest manifest = manifest();
        ConsentManifest.Version version = manifest == null ? null : manifest.current();
        ConsentManifest.Text entry = version == null ? null : version.text(locale);
        String template = entry == null ? null : readAsset(ASSETS + entry.file);
        if (template == null) return null;
        try {
            ConsentText.Shown shown = ConsentText.shown(version, locale, template, operatorValues(), manifest.placeholders);
            ConsentText.Shown earlier = SHOWN.putIfAbsent(locale, shown);
            return earlier != null ? earlier : shown;
        } catch (IllegalArgumentException refusedValue) {
            return null;
        }
    }

    /** The four operator values of this build by manifest placeholder (contract §5.4; empty in local builds). */
    public static Map<String, String> operatorValues() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("OPERATOR_NAME", BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_NAME);
        values.put("OPERATOR_STIR", BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_STIR);
        values.put("RESPONSIBLE_PERSON", BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_RESPONSIBLE_PERSON);
        values.put("CONTACT_EMAIL", BuildConfig.ZENLYGRAM_CONSENT_OPERATOR_CONTACT_EMAIL);
        return values;
    }

    /** The text language shown first: the app language when it is uz, ru or en, else uz (design 50 §5.8). */
    public static String defaultLocale() {
        LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
        if (info == null || info.shortName == null) return ConsentManifest.DEFAULT_LOCALE;
        return ConsentManifest.defaultLocale(info.getLangCode(), info.getBaseLangCode());
    }

    /** The web deletion page in the app language (uz unprefixed, /ru, /en). */
    public static String accountDeleteUrl() { return accountDeleteUrl(defaultLocale()); }

    /** The web deletion page in {@code locale} (anything but ru and en is the uz page). */
    public static String accountDeleteUrl(String locale) {
        if ("ru".equals(locale)) return "https://zenlygram.org/ru/account/delete";
        if ("en".equals(locale)) return "https://zenlygram.org/en/account/delete";
        return ACCOUNT_DELETE_URL;
    }

    // ---- Readers (any thread; only the published snapshots) ----

    /** Loads every stored account once (ZenlyCustomer.onPostInit; any reader also loads on first use). */
    public static void load() { ensureLoaded(); }

    /** The current snapshot of {@code tgUserId} (an empty one when nothing is stored). */
    public static ConsentSnapshot snapshot(long tgUserId) {
        ensureLoaded();
        ConsentSnapshot snapshot = snapshots.get(tgUserId);
        return snapshot != null ? snapshot : ConsentSnapshot.empty(tgUserId, minValidVersion());
    }

    /** The snapshot of the user signed in on {@code account}, or null when the slot holds none. */
    public static ConsentSnapshot snapshotFor(int account) {
        long tg = activatedUserId(account);
        return tg == 0 ? null : snapshot(tg);
    }

    /** The effective state of {@code tgUserId} (design 50 §5.5, with the derived needs_reconsent). */
    public static ConsentMachine.State state(long tgUserId) { return snapshot(tgUserId).effectiveState(); }

    /** The wall for the user signed in on {@code account}; NONE for a slot without an activated user. */
    public static ConsentMachine.Wall wallFor(int account) {
        ConsentSnapshot snapshot = snapshotFor(account);
        return snapshot == null ? ConsentMachine.Wall.NONE : snapshot.wall(mode());
    }

    /** The wall blocks every in-app surface of {@code account} (required mode, no effective grant). */
    public static boolean blocksUi(int account) { return wallFor(account) == ConsentMachine.Wall.BLOCKING; }

    /** Customer data may be recorded for {@code tgUserId}: effectively granted, latest grant not rejected. */
    public static boolean recordingAllowed(long tgUserId) { return snapshot(tgUserId).recordingAllowed(); }

    /**
     * The exact latest grant JSON for {@code bind/start}, or null for every state but an effective, non-rejected grant
     * without a pending withdrawal at the configured origin (design 50 §8.1; ZenlyCustomer.linkGrantJson adds the
     * deletion check).
     */
    public static String grantJsonIfGranted(long tgUserId) { return snapshot(tgUserId).grantJsonIfGranted(ZenlyPro.authority()); }

    /** An un-acked withdrawal of {@code tgUserId} waits for upload at the configured origin. */
    public static boolean pendingWithdraw(long tgUserId) { return snapshot(tgUserId).pendingWithdraw(ZenlyPro.authority()); }

    /** Every Telegram user id with stored consent data (launch reconcile of signed-out users). */
    public static long[] telegramUserIds() {
        ensureLoaded();
        ArrayList<Long> ids = new ArrayList<>(snapshots.keySet());
        Collections.sort(ids);
        long[] result = new long[ids.size()];
        for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
        return result;
    }

    // ---- User decisions (main thread) ----

    /**
     * "I agree" on {@code account} for the text it showed: writes the record and the grant event synchronously. True
     * when the account is effectively granted afterwards (also when it already was); false for a slot without a user, a
     * text that is not this build's current one, or a failed commit (the wall then shows ZenlyConsentSaveFailed).
     */
    public static boolean grant(int account, final ConsentText.Shown text) {
        final long tg = activatedUserId(account);
        ConsentText.Shown bundled = text == null ? null : text(text.locale);
        if (tg == 0 || bundled == null || bundled.version != text.version || !bundled.sha256.equals(text.sha256)) return false;
        final ConsentMachine.Mode mode = mode();
        final String consentId = UUID.randomUUID().toString();
        final long now = ZenlyPro.trustedNowSeconds();
        final String appVersion = ZenlyPro.appVersionName();
        final String authority = ZenlyPro.authority();
        ConsentMachine.Step step = update(tg, current -> ConsentMachine.grant(current, text, mode, consentId, now, appVersion, authority));
        return step != null && step.snapshot.recordingAllowed();
    }

    // ZENLYGRAM 13.2.1: the app's one-time move of a consent it recorded before it used this store
    // (org.telegram.zenlygram.customer.ZgConsentMigration). Without it that grant could only be given again under a new
    // consent id and the time of the move, which is not when the user agreed.
    /**
     * {@link #grant} with the consent id, the instant (epoch seconds) and the app version the original grant event
     * named, for a user this store holds nothing for; main thread. True when the grant is stored; false for a slot
     * without a user, a text that is not this build's current one, values the contract refuses, a user with any stored
     * consent state, or a failed commit.
     */
    public static boolean adoptGrant(int account, final ConsentText.Shown text, final String consentId, final long recordedAt,
            final String appVersion) {
        final long tg = activatedUserId(account);
        ConsentText.Shown bundled = text == null ? null : text(text.locale);
        if (tg == 0 || bundled == null || bundled.version != text.version || !bundled.sha256.equals(text.sha256)) return false;
        final ConsentMachine.Mode mode = mode();
        final String authority = ZenlyPro.authority();
        ConsentMachine.Step step = update(tg, current -> current.isEmpty()
                ? ConsentMachine.grant(current, text, mode, consentId, recordedAt, appVersion, authority) : ConsentMachine.Step.unchanged(current));
        return step != null && step.changed && step.snapshot.recordingAllowed();
    }

    /** Optional mode: "I don't agree", the close glyph or Back on the prompt record declined. False when nothing changed. */
    public static boolean decline(int account) {
        final long tg = activatedUserId(account);
        if (tg == 0) return false;
        final ConsentMachine.Mode mode = mode();
        final long now = ZenlyPro.trustedNowSeconds();
        ConsentMachine.Step step = update(tg, current -> ConsentMachine.decline(current, mode, now));
        return step != null && step.changed;
    }

    /** {@link #withdraw(int, String)} in the app's text language (Data & consent). */
    public static boolean withdraw(int account) { return withdraw(account, defaultLocale()); }

    /**
     * The confirmed withdrawal (design 50 §12): the withdraw event ({@code locale} = the language of the screen or text
     * shown: uz, ru or en) and state withdrawn, committed synchronously. True when the account is withdrawn now; the
     * caller then purges the event queue and Tasks and uploads when linked.
     */
    public static boolean withdraw(int account, String textLocale) {
        final long tg = activatedUserId(account);
        if (tg == 0) return false;
        final ConsentMachine.Mode mode = mode();
        final String locale = ConsentManifest.LOCALES.contains(textLocale) ? textLocale : defaultLocale();
        final String consentId = UUID.randomUUID().toString();
        final long now = ZenlyPro.trustedNowSeconds();
        final String appVersion = ZenlyPro.appVersionName();
        final String authority = ZenlyPro.authority();
        ConsentMachine.Step step = update(tg, current -> ConsentMachine.withdraw(current, mode, locale, consentId, now, appVersion, authority));
        return step != null && step.changed;
    }

    // ---- Server answers and link bookkeeping (main thread; AND-5 / AND-7) ----

    /** A 200 of {@code POST /v1/profile/consents} at {@code authority}; null when the commit failed. */
    public static ConsentMachine.Step applyConsentsAnswer(long tgUserId, final String authority, final ConsentMachine.Answer answer) {
        if (answer == null) return null;
        final long now = ZenlyPro.trustedNowSeconds();
        return update(tgUserId, current -> ConsentMachine.consentsAnswered(current, authority, answer.results, answer.effective, now));
    }

    /** Entries the server acknowledged at {@code authority} without an effective block; null when the commit failed. */
    public static ConsentMachine.Step markAcked(long tgUserId, String authority, Collection<String> consentIds) {
        ArrayList<ConsentMachine.Result> results = new ArrayList<>();
        if (consentIds != null) {
            for (String id : consentIds) results.add(new ConsentMachine.Result(id, "recorded", false));
        }
        return applyConsentsAnswer(tgUserId, authority, new ConsentMachine.Answer(results, null));
    }

    /** A 403 {@code consent_required} ({@code reason}, the block's {@code effective_at} or 0); null when the commit failed. */
    public static ConsentMachine.Step applyConsentRequired(long tgUserId, final String reason, final long effectiveAt) {
        final long now = ZenlyPro.trustedNowSeconds();
        return update(tgUserId, current -> ConsentMachine.consentRequired(current, reason, effectiveAt, now));
    }

    /** A terminal refusal of a consents request: its entries are rejected with {@code code}; null when the commit failed. */
    public static ConsentMachine.Step markRejected(long tgUserId, final Collection<String> consentIds, final String code) {
        return update(tgUserId, current -> ConsentMachine.rejected(current, consentIds, code));
    }

    /**
     * 409 {@code consent_stale} on a {@code bind/start} that carried {@code sentGrantJson} (the exact string
     * {@link #grantJsonIfGranted} returned), for the automatic link's CONSENT_STALE outcome and for a manual link before
     * it retries without consent: the stored grant becomes needs_reconsent (LINK_ENDED) when it is still the latest one.
     * Null when the commit failed; {@code purgeEvents} says the caller purges the event queue.
     */
    public static ConsentMachine.Step markConsentStale(long tgUserId, final String sentGrantJson) {
        final long now = ZenlyPro.trustedNowSeconds();
        return update(tgUserId, current -> ConsentMachine.consentStale(current, sentGrantJson, now));
    }

    /**
     * Launch only (ZenlyCustomer.onPostInit, after the stored binding losses were fed and before anything else reads the
     * state): for every stored account, selected or not, a grant whose text this build renders with another
     * {@code text_sha256} (the operator values changed since it was given) becomes needs_reconsent
     * ({@link ConsentMachine#operatorChanged}); a grant whose link already ended keeps that notice. Each text is rendered
     * inside the transition, under the writer lock, so exactly the grant that is replaced is judged; readers stay
     * lock-free. Not part of {@link #load} or the readers: device tests publish grants with fake hashes after the
     * process started.
     */
    public static void normaliseChangedTexts() {
        ensureLoaded();
        final ConsentManifest manifest = manifest();
        if (manifest == null) return;
        final Map<String, String> values = operatorValues();
        // The grants of all accounts name a few bundled files: each is read at most once per pass.
        final HashMap<String, String> read = new HashMap<>();
        final ConsentMachine.Templates templates = file -> {
            if (!read.containsKey(file)) read.put(file, readAsset(ASSETS + file));
            return read.get(file);
        };
        final long now = ZenlyPro.trustedNowSeconds();
        for (long tg : telegramUserIds()) {
            update(tg, current -> {
                // Only a stored grant is judged, so no other record's text is rendered.
                String rendered = current.record.state != ConsentMachine.State.GRANTED ? null
                        : ConsentMachine.renderedSha256(current.record.grant, manifest, values, templates);
                return ConsentMachine.operatorChanged(current, rendered, now);
            });
        }
    }

    /**
     * The binding loss of {@code tgUserId} at the configured origin (epoch seconds; 0 clears it after a link), kept in
     * memory: ZenlyCustomer persists it in {@code binding.v1} and feeds it again at launch. A higher loss is published
     * at once, without a commit, so it ends the grant in this process even when storage fails; then the grant it ended
     * is stored as needs_reconsent (LINK_ENDED, {@link ConsentMachine#linkEnded}; compliance X-7(b)), so a later link
     * that clears the loss never makes that grant effective again; only a new grant does
     * ({@link ConsentMachine#feedBindingLoss}). If that commit fails, every later feed stores the grant again, and the
     * feed that lowers or clears the loss (a later link) stores it in the same commit as the lower value or, when that
     * commit fails too, publishes nothing, so the old loss stays in memory and the grant stays ended in this process.
     * A restart keeps only what was stored: if no consent commit succeeded before the process ended and a later link
     * had already saved {@code lost_at} 0 in {@code binding.v1}, the next launch feeds no loss and the stored grant is
     * effective again (a recorded residual). Returns the loss's step ({@code purgeEvents}); null when no account or
     * when the commit of a lower value failed.
     */
    public static ConsentMachine.Step setBindingLoss(long tgUserId, final long lostAt) {
        return ConsentMachine.feedBindingLoss(transition -> update(tgUserId, transition::apply), lostAt);
    }

    /** A binding with another id was adopted at {@code authority}: every entry is uploaded there again. */
    public static ConsentMachine.Step clearAcked(long tgUserId, final String authority) {
        return update(tgUserId, current -> ConsentMachine.clearAcked(current, authority));
    }

    /**
     * Logout, a completed deletion or a 401 {@code account_erased}: removes every consent key of {@code tgUserId} (record,
     * outbox, rejected, the acked sets of every authority) and its binding loss. False when the commit failed.
     */
    public static boolean forget(long tgUserId) {
        if (tgUserId <= 0) return false;
        ensureLoaded();
        synchronized (LOCK) {
            if (!commitLocked(tgUserId, null)) return false;
            publishLocked(tgUserId, ConsentMachine.forget(tgUserId, minValidVersion()));
        }
        notifyChanged(tgUserId);
        return true;
    }

    // ---- Store ----

    private static ConsentMachine.Step update(long tgUserId, Transition transition) {
        if (tgUserId <= 0) return null;
        ensureLoaded();
        ConsentMachine.Step step;
        synchronized (LOCK) {
            ConsentSnapshot current = snapshots.get(tgUserId);
            if (current == null) current = ConsentSnapshot.empty(tgUserId, minValidVersion());
            try {
                step = transition.apply(current);
            } catch (IllegalArgumentException outsideContract) {
                // An event the contract refuses (e.g. a Telegram id above 2^53 - 1) is never stored or sent.
                return null;
            }
            if (!step.changed) return step;
            if (!step.snapshot.storedValues().equals(current.storedValues()) && !commitLocked(tgUserId, step.snapshot)) return null;
            publishLocked(tgUserId, step.snapshot);
        }
        notifyChanged(tgUserId);
        return step;
    }

    /** Replaces every stored key of {@code tgUserId} with {@code next}'s values (none for null) in one commit. */
    private static boolean commitLocked(long tgUserId, ConsentSnapshot next) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            ConsentSnapshot.Keys.Key parsed = ConsentSnapshot.Keys.parse(key);
            if (parsed != null && parsed.tgUserId == tgUserId) editor.remove(key);
        }
        if (next != null) {
            for (Map.Entry<String, String> entry : next.storedValues().entrySet()) editor.putString(entry.getKey(), entry.getValue());
        }
        return editor.commit();
    }

    private static void publishLocked(long tgUserId, ConsentSnapshot next) {
        HashMap<Long, ConsentSnapshot> copy = new HashMap<>(snapshots);
        if (next.isEmpty()) {
            copy.remove(tgUserId);
        } else {
            copy.put(tgUserId, next);
        }
        snapshots = Collections.unmodifiableMap(copy);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            SharedPreferences preferences = preferences();
            if (preferences == null) return;
            int minValid = minValidVersion();
            HashMap<Long, Parts> parts = new HashMap<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                ConsentSnapshot.Keys.Key key = ConsentSnapshot.Keys.parse(entry.getKey());
                if (key == null || !(entry.getValue() instanceof String)) continue;
                Parts account = parts.get(key.tgUserId);
                if (account == null) parts.put(key.tgUserId, account = new Parts());
                Object tree = parse((String) entry.getValue());
                if (key.kind == ConsentSnapshot.Keys.Kind.RECORD) {
                    account.record = tree;
                } else if (key.kind == ConsentSnapshot.Keys.Kind.OUTBOX) {
                    account.outbox = tree;
                } else if (key.kind == ConsentSnapshot.Keys.Kind.ACKED) {
                    account.acked.put(key.authority, tree);
                } else {
                    account.rejected = tree;
                }
            }
            HashMap<Long, ConsentSnapshot> loadedSnapshots = new HashMap<>();
            for (Map.Entry<Long, Parts> entry : parts.entrySet()) {
                Parts account = entry.getValue();
                ConsentSnapshot snapshot = ConsentSnapshot.decode(entry.getKey(), account.record, account.outbox, account.acked,
                        account.rejected, 0, minValid);
                if (!snapshot.isEmpty()) loadedSnapshots.put(entry.getKey(), snapshot);
            }
            snapshots = Collections.unmodifiableMap(loadedSnapshots);
            loaded = true;
        }
    }

    /** The stored values of one account, as parsed JSON trees. */
    private static final class Parts {
        Object record, outbox, rejected;
        final LinkedHashMap<String, Object> acked = new LinkedHashMap<>();
    }

    private static SharedPreferences preferences() {
        Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void notifyChanged(final long tgUserId) {
        Runnable post = () -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (activatedUserId(account) == tgUserId) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zenlygramConsentChanged, account);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            post.run();
        } else {
            AndroidUtilities.runOnUIThread(post);
        }
    }

    private static long activatedUserId(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT ? UserConfig.getInstance(account).getClientUserId() : 0;
    }

    private static int minValidVersion() {
        ConsentManifest manifest = manifest();
        return manifest == null ? 0 : manifest.minValidVersion;
    }

    // ---- Assets and JSON ----

    private static ConsentManifest readManifest() {
        String text = readAsset(ASSETS + "manifest.json");
        if (text == null) return null;
        Object tree = parse(text);
        return tree == null ? null : ConsentManifest.fromTree(tree);
    }

    /** A strictly UTF-8 asset of at most 256 KiB, or null. */
    private static String readAsset(String path) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return null;
        InputStream input = null;
        try {
            input = context.getAssets().open(path);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_ASSET_BYTES) return null;
            }
            return ConsentText.decodeUtf8(bytes.toByteArray());
        } catch (IOException missing) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Nothing was written.
                }
            }
        }
    }

    /** A JSON object or array as plain values (Map / List / String / Integer / Long / Double / Boolean / null), or null. */
    private static Object parse(String text) {
        try {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) return plain(new JSONObject(trimmed));
            if (trimmed.startsWith("[")) return plain(new JSONArray(trimmed));
        } catch (JSONException unreadable) {
            // Unreadable stored values read as absent.
        }
        return null;
    }

    private static Object plain(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, plain(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<Object> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) list.add(plain(array.opt(i)));
            return list;
        }
        return value == JSONObject.NULL ? null : value;
    }
}
