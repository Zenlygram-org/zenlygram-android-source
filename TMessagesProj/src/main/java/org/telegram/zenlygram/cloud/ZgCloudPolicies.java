/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.zenlygram.core.ZgConfig;
import org.telegram.zenlygram.pro.ZgPro;

import java.util.Random;

/**
 * The two signed public policies on this install (iOS SGEntitlementPolicy + SGAppUpdatePolicy refresh, §3.9a / §3.9c):
 * fetch, verify against the pinned ring, adopt only a newer (revision, iat), persist the exact signed bytes and
 * re-verify them on every load.
 *
 * <p><b>Cadence.</b> First fetch at the first idle window after launch (+8 s), then on every foreground and every 30
 * minutes while the app stays in the foreground. The app policy GET follows the monetization GET 5–30 s later. Each
 * policy has its own gate: one request in flight and at least 60 s between attempts (monotonic clock). A 429 / 503 /
 * offline answer changes nothing. No credentials are ever sent: the policies name no one.
 *
 * <p><b>Effects.</b> A verified monetization policy goes to {@link ZgPro#applySignedPolicy}, and another revision
 * of it (the owner changed the mode, the trial or the local grace length) to {@link ZgPro#onSignedPolicyRevised};
 * a verified app policy posts {@link #appPolicyChanged} (global NotificationCenter, UI thread), which ZgAppUpdate
 * observes.
 *
 * <p>Hooks: {@link #onAppForeground()} / {@link #onAppBackground()} from LaunchActivity.onResume / onPause.
 */
public final class ZgCloudPolicies {

    /** Global NotificationCenter: the accepted app policy changed. No arguments. */
    public static final int appPolicyChanged = 0x5A470C01;

    static final String PREFS = "zenlygram_cloud";
    /**
     * The accepted policy of the Android line (ver 4). What a build of the general line stored under
     * KEY_MONETIZATION_GENERAL is never read and is removed at load: until the first verified Android policy this
     * copy says "free", like a fresh install.
     */
    static final String KEY_MONETIZATION = "zg.ent.policy.v4@" + ZgCloudConfig.AUTHORITY;
    static final String KEY_MONETIZATION_GENERAL = "zg.ent.policy.v1@" + ZgCloudConfig.AUTHORITY;
    static final String KEY_APP_POLICY = "zg.app.policy.v1@" + ZgCloudConfig.AUTHORITY;
    static final String KEY_LAST_VERIFIED = "zg.policy.verified_at@" + ZgCloudConfig.AUTHORITY;

    private static final long FIRST_DELAY_MS = 8000;
    private static final long REFRESH_INTERVAL_MS = 30 * 60 * 1000L;
    private static final long GATE_COOLDOWN_MS = 60 * 1000L;

    private static final Object lock = new Object();
    private static volatile boolean loaded;
    private static ZgPublicPolicy.Monetization monetization;
    private static ZgPublicPolicy.AppPolicy appPolicy;

    private static final Gate monetizationGate = new Gate();
    private static final Gate appPolicyGate = new Gate();
    private static final Random random = new Random();
    private static boolean firstWindowPassed;
    private static boolean foreground;

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            if (foreground) {
                AndroidUtilities.runOnUIThread(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    private ZgCloudPolicies() {
    }

    /** One request in flight and >= 60 s between attempts (iOS SGEntitlementPolicyRefreshGate). */
    static final class Gate {
        private boolean inFlight;
        private long lastAttempt = Long.MIN_VALUE / 2;

        synchronized boolean tryBegin(long nowMs) {
            if (inFlight || nowMs - lastAttempt < GATE_COOLDOWN_MS) {
                return false;
            }
            inFlight = true;
            lastAttempt = nowMs;
            return true;
        }

        synchronized void end() {
            inFlight = false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    /** UI thread. Starts (or restarts) the foreground cadence. */
    public static void onAppForeground() {
        ensureLoaded();
        foreground = true;
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (!firstWindowPassed) {
            firstWindowPassed = true;
            AndroidUtilities.runOnUIThread(tick, FIRST_DELAY_MS);
        } else {
            AndroidUtilities.runOnUIThread(tick);
        }
    }

    /** UI thread. Stops the 30-minute timer (an in-flight request still completes). */
    public static void onAppBackground() {
        foreground = false;
        AndroidUtilities.cancelRunOnUIThread(tick);
    }

    /** A Pro screen opened: refresh the monetization policy (behind its gate). */
    public static void onProScreenOpened() {
        ensureLoaded();
        refreshMonetization();
    }

    private static void refreshAll() {
        refreshMonetization();
        final long stagger = 5000 + random.nextInt(25001);
        AndroidUtilities.runOnUIThread(ZgCloudPolicies::refreshAppPolicy, stagger);
    }

    // ---------------------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------------------

    /** The accepted monetization policy, or null (= free). */
    public static ZgPublicPolicy.Monetization getMonetization() {
        ensureLoaded();
        synchronized (lock) {
            return monetization;
        }
    }

    /** The accepted app update policy, or null (= no constraint). */
    public static ZgPublicPolicy.AppPolicy getAppPolicy() {
        ensureLoaded();
        synchronized (lock) {
            return appPolicy;
        }
    }

    /** Wall-clock seconds of the last successful verification of a fetched policy, 0 if none. */
    public static long getLastVerifiedAt() {
        final SharedPreferences prefs = prefs();
        return prefs == null ? 0 : prefs.getLong(KEY_LAST_VERIFIED, 0);
    }

    /** The update decision for this install, from the stored token only (synchronous, no network). */
    public static ZgAppUpdateDecision decideAppUpdate() {
        final ZgPublicPolicy.AppPolicy policy = getAppPolicy();
        final long now = ZgPublicPolicy.policyNow(System.currentTimeMillis() / 1000, policy);
        return ZgAppUpdateDecision.decide(policy, ZgConfig.getAppVersionName(), now);
    }

    /** Synchronous, idempotent: re-verifies the stored tokens (never re-expired). A token that no longer verifies is none. */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            final SharedPreferences prefs = prefs();
            if (prefs == null) {
                return;
            }
            if (prefs.contains(KEY_MONETIZATION_GENERAL)) {
                prefs.edit().remove(KEY_MONETIZATION_GENERAL).apply();
            }
            final String storedMonetization = prefs.getString(KEY_MONETIZATION, null);
            if (storedMonetization != null) {
                final ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> parsed = ZgPublicPolicy.parseMonetization(storedMonetization, ZgCloudConfig.pinnedRing(), null);
                monetization = parsed.policy;
            }
            final String storedAppPolicy = prefs.getString(KEY_APP_POLICY, null);
            if (storedAppPolicy != null) {
                final ZgPublicPolicy.Parsed<ZgPublicPolicy.AppPolicy> parsed = ZgPublicPolicy.parseAppPolicy(storedAppPolicy, ZgCloudConfig.pinnedRing(), null);
                appPolicy = parsed.policy;
            }
            loaded = true;
        }
        final ZgPublicPolicy.Monetization current = monetization;
        ZgPro.applySignedPolicy(current != null && current.paid);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fetch
    // ---------------------------------------------------------------------------------------------------------

    private static void refreshMonetization() {
        if (!monetizationGate.tryBegin(SystemClock.elapsedRealtime())) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final String token = fetchToken(ZgCloudConfig.MONETIZATION_URL, ZgCloudConfig.ACCEPT_MONETIZATION);
                if (token == null) {
                    return;
                }
                final ZgPublicPolicy.Monetization current = getMonetization();
                final long now = ZgPublicPolicy.policyNow(System.currentTimeMillis() / 1000, current);
                final ZgPublicPolicy.Parsed<ZgPublicPolicy.Monetization> parsed = ZgPublicPolicy.parseMonetization(token, ZgCloudConfig.pinnedRing(), now);
                if (!parsed.isOk()) {
                    FileLog.d("zg.cloud monetization refused: " + parsed.failure);
                    return;
                }
                noteVerified();
                if (ZgPublicPolicy.order(parsed.policy, current) != ZgPublicPolicy.Order.ADOPT) {
                    return;
                }
                synchronized (lock) {
                    monetization = parsed.policy;
                    store(KEY_MONETIZATION, parsed.policy.compact);
                }
                ZgPro.applySignedPolicy(parsed.policy.paid);
                if (current == null || parsed.policy.revision != current.revision) {
                    ZgPro.onSignedPolicyRevised();
                }
            } finally {
                monetizationGate.end();
            }
        });
    }

    private static void refreshAppPolicy() {
        if (!appPolicyGate.tryBegin(SystemClock.elapsedRealtime())) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final String token = fetchToken(ZgCloudConfig.APP_POLICY_URL, ZgCloudConfig.ACCEPT_APP_POLICY);
                if (token == null) {
                    return;
                }
                final ZgPublicPolicy.AppPolicy current = getAppPolicy();
                final long now = ZgPublicPolicy.policyNow(System.currentTimeMillis() / 1000, current);
                final ZgPublicPolicy.Parsed<ZgPublicPolicy.AppPolicy> parsed = ZgPublicPolicy.parseAppPolicy(token, ZgCloudConfig.pinnedRing(), now);
                if (!parsed.isOk()) {
                    FileLog.d("zg.cloud app policy refused: " + parsed.failure);
                    return;
                }
                noteVerified();
                if (ZgPublicPolicy.order(parsed.policy, current) != ZgPublicPolicy.Order.ADOPT) {
                    return;
                }
                synchronized (lock) {
                    appPolicy = parsed.policy;
                    store(KEY_APP_POLICY, parsed.policy.compact);
                }
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(appPolicyChanged));
            } finally {
                appPolicyGate.end();
            }
        });
    }

    /** The policy_token of a 200 answer, else null (429 / 503 / offline change nothing). server_time is never read. */
    private static String fetchToken(String url, String accept) {
        final ZgBoundedFetch.Response response = ZgBoundedFetch.get(url, accept, userAgent());
        if (response.status != 200 || response.body == null) {
            return null;
        }
        try {
            final Object token = new JSONObject(response.body).opt("policy_token");
            return token instanceof String ? (String) token : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String userAgent() {
        return "Zenlygram-Android/" + ZgConfig.getAppVersionName() + " (" + ZgConfig.getAppVersionBuild() + "; Android " + Build.VERSION.RELEASE + ")";
    }

    private static void noteVerified() {
        final SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putLong(KEY_LAST_VERIFIED, System.currentTimeMillis() / 1000).apply();
        }
    }

    private static void store(String key, String compact) {
        final SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putString(key, compact).apply();
        }
    }

    static SharedPreferences prefs() {
        final Context context = ApplicationLoader.applicationContext;
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
