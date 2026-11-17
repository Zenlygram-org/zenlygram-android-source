/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zenlygram.customer.ConsentManifest;
import org.telegram.messenger.zenlygram.customer.ConsentMachine;
import org.telegram.messenger.zenlygram.customer.ConsentSnapshot;
import org.telegram.messenger.zenlygram.customer.ConsentText;
import org.telegram.messenger.zenlygram.customer.DeleteFlow;
import org.telegram.messenger.zenlygram.customer.ZenlyConsent;
import org.telegram.messenger.zenlygram.customer.ZenlyCustomer;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.zenlygram.cloud.ZgCloudConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Customer data on Android as the app's screens use it: consent, «My data», account deletion (iOS SGCustomerSync,
 * docs/customer-data.md). A thin facade over the cloud client
 * ({@code org.telegram.messenger.zenlygram.customer}): {@code ZenlyConsent} is the one consent store (grant, decline,
 * withdrawal, the snapshots), {@code ZenlyCustomer} the coordinator that, after a grant, links the account, uploads the
 * consent, the profile and the statistics, and runs withdrawals and deletions. Nothing here stores a decision, talks to
 * the network or decides what may be sent; it reads the client's state for the screens and hands it the user's
 * decisions in the order the client was written for.
 *
 * <h3>Activation</h3>
 * <ul>
 *   <li>Consent is <b>required</b> in this build ({@code BuildConfig.ZENLYGRAM_CONSENT_MODE}): the wall
 *       ({@link ZgConsentWall}, decision {@link ZgConsentWallPolicy}) blocks a signed-in account until it agrees.</li>
 *   <li>Like iOS (docs/customer-data.md §2) the bundled text must verify ({@link ZgConsentBundle}) and the operator
 *       values must all be present and valid, or missing only while the API host is local
 *       ({@link ZgConsentWallPolicy#acceptsMissingValues}). Otherwise consent is not active: no wall, no grant.</li>
 *   <li>Settings → Zenlygram → Data &amp; consent is always there: the state, the consent text, My data, the withdrawal,
 *       Delete my Zenlygram account and data (Play's in-app deletion path) and the Privacy Policy.</li>
 * </ul>
 *
 * <p>Main thread. {@link #consentChanged} is posted on the global NotificationCenter whenever the client reports a
 * consent change of any account ({@code NotificationCenter.zenlygramConsentChanged}).
 */
public final class ZgCustomer {

    /** Global NotificationCenter: an account's consent changed. No arguments. */
    public static final int consentChanged = 0x5A470C02;

    private static volatile boolean started;
    private static volatile ZgConsentBundle bundle;
    private static volatile boolean bundleLoaded;
    private static volatile Boolean usable;

    private ZgCustomer() {
    }

    // -----------------------------------------------------------------------------------------------------------
    // Start
    // -----------------------------------------------------------------------------------------------------------

    private static final NotificationCenter.NotificationCenterDelegate BRIDGE = (id, account, args) -> {
        if (id == NotificationCenter.zenlygramConsentChanged) {
            NotificationCenter.getGlobalInstance().postNotificationName(consentChanged);
        }
    };

    /**
     * Once per process, on the main thread, before the first answer about any account: the client's consent
     * notification is passed on as {@link #consentChanged}, the earlier statistics queue files go, and the consents the
     * earlier store recorded move into the client's ({@link ZgConsentMigration}). Every entry point that reads or
     * writes consent calls it first; off the main thread it does nothing and the caller sees the state not moved yet
     * (the wall then defers).
     */
    static boolean start() {
        if (started) {
            return true;
        }
        if (Looper.myLooper() != Looper.getMainLooper() || ApplicationLoader.applicationContext == null) {
            return false;
        }
        started = true; // before the move: its notifications reach observers that ask this class again
        NotificationCenter.getGlobalInstance().addObserver(BRIDGE, NotificationCenter.zenlygramConsentChanged);
        ZgAnalytics.deleteLegacyQueues();
        if (ZgConsentMigration.run()) {
            // Inside another notification's delivery the observer above only joins afterwards, so the move's own
            // change is announced once more: a screen that is already up reads the moved consent.
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(consentChanged));
        }
        return true;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Build settings and texts
    // -----------------------------------------------------------------------------------------------------------

    public static boolean isRequired() {
        return ZenlyConsent.mode() == ConsentMachine.Mode.REQUIRED;
    }

    private static ZgConsentBundle bundle() {
        if (!bundleLoaded) {
            synchronized (ZgCustomer.class) {
                if (!bundleLoaded) {
                    final Context context = ApplicationLoader.applicationContext;
                    bundle = context == null ? null : ZgConsentBundle.load(path -> readAsset(context, "zenlygram/consent/" + path));
                    bundleLoaded = context != null;
                }
            }
        }
        return bundle;
    }

    /** The verified manifest of the bundled consent texts, or null when the tree does not verify (then no grant is possible). */
    public static ConsentManifest text() {
        final ZgConsentBundle current = bundle();
        return current == null ? null : current.manifest;
    }

    /**
     * Whether consent is active on this build (iOS activation {@code .active}): the bundled text verifies, no operator
     * value is refused, and all are present unless the API host is local (iOS {@code isUsable(apiHost:)}).
     */
    public static boolean canGrant() {
        final ZgConsentBundle current = bundle();
        if (current == null) {
            return false;
        }
        Boolean value = usable;
        if (value == null) {
            boolean complete = true;
            for (String operatorValue : ZenlyConsent.operatorValues().values()) {
                final String trimmed = operatorValue == null ? "" : operatorValue.trim();
                complete &= !trimmed.isEmpty() && !trimmed.contains("FILL_ME");
            }
            // A refused value (a line break, a tab, "{{" or "}}" in it) leaves no text to show in any language.
            boolean invalid = false;
            for (String locale : ConsentManifest.LOCALES) {
                invalid |= ZenlyConsent.text(locale) == null;
            }
            value = ZgConsentWallPolicy.operatorUsable(complete, invalid, ZgCloudConfig.AUTHORITY);
            usable = value;
        }
        return value;
    }

    /**
     * The current text in {@code locale} (uz / ru / en) as the client shows and hashes it, filled with this build's
     * operator values; null when the bundle does not verify or a value is refused. A grant is made with exactly this
     * object ({@link #grant}).
     */
    public static ConsentText.Shown render(String locale) {
        return bundle() == null ? null : ZenlyConsent.text(locale);
    }

    /** uz / ru / en from the app language; any other language opens in uz (iOS SGConsentLocale.forWall). */
    public static String defaultLocale() {
        final LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
        return localeFor(info != null ? info.getLangCode() : null);
    }

    static String localeFor(String appLanguage) {
        if (appLanguage != null) {
            final String lower = appLanguage.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ru")) {
                return "ru";
            }
            if (lower.startsWith("en")) {
                return "en";
            }
        }
        return ConsentManifest.DEFAULT_LOCALE;
    }

    /** "Data operator: name · e-mail" of this build (the literal placeholders where a value is missing, like iOS). */
    public static String operatorLine() {
        final Map<String, String> values = ZenlyConsent.operatorValues();
        final String name = values.get("OPERATOR_NAME");
        final String email = values.get("CONTACT_EMAIL");
        return LocaleController.formatString(R.string.ZGC_DataConsent_Operator,
            TextUtils.isEmpty(name) ? "{{OPERATOR_NAME}}" : name, TextUtils.isEmpty(email) ? "{{CONTACT_EMAIL}}" : email);
    }

    // -----------------------------------------------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------------------------------------------

    /** The Telegram id signed in on {@code account}, or 0. */
    public static long tg(int account) {
        try {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
                return 0;
            }
            final UserConfig config = UserConfig.getInstance(account);
            return config.isClientActivated() ? Math.max(0, config.getClientUserId()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The client's consent state of the user signed in on {@code account}; null for a slot without one. */
    public static ConsentSnapshot snapshot(int account) {
        start();
        final long tg = tg(account);
        return tg <= 0 ? null : ZenlyConsent.snapshot(tg);
    }

    /** The account's consent as other packages read it ({@link ZgConsentStore.Record}; never null). */
    public static ZgConsentStore.Record record(int account) {
        return ZgConsentStore.Record.of(snapshot(account));
    }

    /** Whether this device holds a link of the account to the Zenlygram server. */
    public static boolean isLinked(int account) {
        try {
            return tg(account) > 0 && ZenlyPro.get(account).snapshot().linked;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** The account's deletion record at the configured origin, or null. */
    static DeleteFlow.Record deletion(int account) {
        try {
            return ZenlyCustomer.deletionState(account);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** A deletion of the account runs, waits for a retry or completed: nothing else is sent or recorded meanwhile. */
    static boolean deletionBlocks(int account) {
        final DeleteFlow.Record record = deletion(account);
        return record != null && record.blocks();
    }

    private static final ZgConsentWallPolicy.Decision NOT_ASKED;

    static {
        final ZgConsentWallPolicy.Inputs granted = new ZgConsentWallPolicy.Inputs();
        granted.state = "granted";
        granted.recordTextVersion = Integer.MAX_VALUE;
        NOT_ASKED = ZgConsentWallPolicy.decide(granted);
    }

    /**
     * The wall decision for {@code account} (iOS SGConsentWallPolicy over the client's state): nothing to show when the
     * slot holds no user or consent is not active on this build (fail closed); deferred until {@link #start} has run.
     */
    public static ZgConsentWallPolicy.Decision wallDecision(int account) {
        final boolean ready = start();
        final ZgConsentBundle current = bundle();
        final long tg = tg(account);
        if (tg <= 0 || current == null || !canGrant()) {
            return NOT_ASKED;
        }
        final ConsentSnapshot snapshot = ZenlyConsent.snapshot(tg);
        String rendered = null;
        final ConsentMachine.Grant grant = snapshot.record.grant;
        if (snapshot.record.state == ConsentMachine.State.GRANTED && grant != null) {
            rendered = renderedSha256(current, grant.textVersion, grant.locale);
        }
        final DeleteFlow.Record deletion = deletion(account);
        return ZgConsentWallPolicy.decide(ZgConsentFacts.wallInputs(isRequired(), ready, snapshot, current.manifest.currentVersion,
            current.manifest.minValidVersion, rendered, deletion != null && deletion.state == DeleteFlow.State.DONE));
    }

    /** This build's hash of (version, locale); the operator values never change within a build, so it is kept. */
    private static String renderedSha256(ZgConsentBundle current, int version, String locale) {
        if (version == current.manifest.currentVersion) {
            final ConsentText.Shown shown = ZenlyConsent.text(locale);
            return shown == null ? null : shown.sha256;
        }
        final String key = version + "/" + locale;
        synchronized (RENDERED) {
            if (!RENDERED.containsKey(key)) {
                RENDERED.put(key, current.renderedSha256(version, locale, ZenlyConsent.operatorValues()));
            }
            return RENDERED.get(key);
        }
    }

    private static final java.util.HashMap<String, String> RENDERED = new java.util.HashMap<>();

    /** Whether the wall (required) or the prompt (optional) should be up for {@code account}. */
    public static boolean shouldPresentWall(int account) {
        final ZgConsentWallPolicy.Presentation p = wallDecision(account).presentation;
        return p == ZgConsentWallPolicy.Presentation.WALL || p == ZgConsentWallPolicy.Presentation.PROMPT
            || p == ZgConsentWallPolicy.Presentation.TEXT_UNAVAILABLE;
    }

    /** The value of the Settings row: Agreed / Not agreed / Declined / Withdrawn / Review needed. */
    public static CharSequence stateLabel(int account) {
        switch (ZgConsentFacts.summary(snapshot(account))) {
            case GRANTED:
                return getString(R.string.ZGC_Settings_Data_State_Granted);
            case DECLINED:
                return getString(R.string.ZGC_Settings_Data_State_Declined);
            case WITHDRAWN:
                return getString(R.string.ZGC_Settings_Data_State_Withdrawn);
            case REVIEW:
                return getString(R.string.ZenlyDataConsentValueReview);
            case NONE:
            default:
                return getString(R.string.ZGC_Settings_Data_State_None);
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Decisions (the call order of the client's own screens: the store first, then the coordinator)
    // -----------------------------------------------------------------------------------------------------------

    /**
     * "I agree" with the age box ticked, for exactly the text on screen. True once the grant is stored; the coordinator
     * then links the account and uploads by itself. False: nothing was written (the wall says so).
     */
    public static boolean grant(int account, ConsentText.Shown shown) {
        if (!start() || shown == null || !canGrant()) {
            return false;
        }
        final DeleteFlow.Record deletion = deletion(account);
        if (deletion != null && deletion.state == DeleteFlow.State.DONE) {
            // The erased account's record goes first (its done text was shown), so the new grant is not removed with it.
            ZenlyCustomer.acknowledgeDeletion(account);
        }
        if (!ZenlyConsent.grant(account, shown)) {
            return false;
        }
        ZenlyCustomer.onConsentChanged(account);
        return true;
    }

    /**
     * Optional mode: "I don't agree" records declined where the prompt would ask. Required mode: nothing is written
     * (the decline page is a view state, like iOS).
     */
    public static boolean decline(int account) {
        if (!start() || isRequired()) {
            return true;
        }
        if (ZenlyConsent.wallFor(account) == ConsentMachine.Wall.NONE) {
            return true; // no open question: an earlier decision is never overwritten from here
        }
        if (!ZenlyConsent.decline(account)) {
            return false;
        }
        ZenlyCustomer.onConsentChanged(account);
        return true;
    }

    /**
     * The confirmed withdrawal ({@code textLocale}: the language of the text or screen it was confirmed on). The client
     * commits it, purges the statistics queue, discards unsent Cloud Tasks changes and uploads it when the device is
     * linked; the link itself stays. True when the account is withdrawn now.
     */
    public static boolean withdraw(int account, String textLocale) {
        if (!start() || !ZenlyCustomer.withdraw(account, textLocale)) {
            return false;
        }
        beginSending(account);
        return true;
    }

    /** "Try again" on a withdrawal that has not reached the server: the client sends it now. */
    public static void retryWithdrawal(int account) {
        if (!start()) {
            return;
        }
        ZenlyCustomer.retryWithdrawal(account);
        beginSending(account);
        NotificationCenter.getGlobalInstance().postNotificationName(consentChanged);
    }

    // -----------------------------------------------------------------------------------------------------------
    // "Sending your withdrawal…" (iOS SGCustomerFlows.sendingWindow)
    // -----------------------------------------------------------------------------------------------------------

    /** The longest "Sending your withdrawal…". */
    static final long SENDING_WINDOW_MS = 10_000;

    private static final long[] sendingUntil = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] sendingUser = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private static void beginSending(final int account) {
        if (account < 0 || account >= sendingUntil.length) {
            return;
        }
        sendingUntil[account] = SystemClock.elapsedRealtime() + SENDING_WINDOW_MS;
        sendingUser[account] = tg(account);
        // The progress gives way to the delivery status: the screens read it again.
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(consentChanged), SENDING_WINDOW_MS + 100);
    }

    /**
     * The send the user started or retried is still inside its 10 seconds. The client reports the acknowledgement
     * (then nothing is pending any more) but not a failed attempt, so after a failure this stays true until the window
     * ends, where iOS drops it at once.
     */
    static boolean isSending(int account) {
        return account >= 0 && account < sendingUntil.length && sendingUser[account] != 0 && sendingUser[account] == tg(account)
            && SystemClock.elapsedRealtime() < sendingUntil[account];
    }

    /** Where the account's withdrawal stands (the wall's notice and Data &amp; consent). */
    static ZgConsentFacts.Delivery delivery(int account) {
        return ZgConsentFacts.delivery(snapshot(account), ZenlyPro.authority(), isLinked(account), isSending(account));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Logout
    // -----------------------------------------------------------------------------------------------------------

    /**
     * Telegram logout of {@code account}, called right after ZgBackend.onLogout (the client has queued a pending
     * withdrawal, purged this user's consent, statistics and customer keys and revoked the link). What is left for this
     * class: the keys of the store this app had before the client, should the launch move not have removed them, and the
     * screen state kept per slot.
     */
    public static void onLogout(int account) {
        ZgConsentMigration.forget(tg(account));
        if (account >= 0 && account < sendingUntil.length) {
            sendingUntil[account] = 0;
            sendingUser[account] = 0;
        }
        ZgConsentWall.release(account);
    }

    private static byte[] readAsset(Context context, String path) {
        try (InputStream in = context.getAssets().open(path)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 256 * 1024) {
                    return null; // the client reads no larger text either
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
