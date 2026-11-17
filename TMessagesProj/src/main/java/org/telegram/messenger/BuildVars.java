/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.billingclient.api.ProductDetails;

import java.util.Objects;

public class BuildVars {

    public static boolean DEBUG_VERSION = BuildConfig.DEBUG_VERSION;
    public static boolean LOGS_ENABLED = BuildConfig.DEBUG_VERSION;
    public static boolean DEBUG_PRIVATE_VERSION = BuildConfig.DEBUG_PRIVATE_VERSION;
    public static boolean USE_CLOUD_STRINGS = true;
    public static boolean CHECK_UPDATES = false; // ZENLYGRAM: no Zenlygram update channel yet (upstream true)
    public static boolean NO_SCOPED_STORAGE = Build.VERSION.SDK_INT <= 29;
    public static String BUILD_VERSION_STRING = BuildConfig.BUILD_VERSION_STRING;

    // ZENLYGRAM: own api_id / api_hash, injected at build time from the Gradle properties ZG_API_ID / ZG_API_HASH
    // (TMessagesProj/zenlygram-identity.gradle). Never commit real values here.
    public static int APP_ID = BuildConfig.ZG_API_ID;
    public static String APP_HASH = BuildConfig.ZG_API_HASH;

    // SafetyNet key for Google Identity SDK, set it to empty to disable
    public static String SAFETYNET_KEY = ""; // ZENLYGRAM: disabled, the upstream key belongs to Telegram's Google project
    public static String PLAYSTORE_APP_URL = "https://zenlygram.org/download"; // ZENLYGRAM: was Telegram's Play Store page
    public static String HUAWEI_STORE_URL = "https://appgallery.huawei.com/app/C101184875";
    // ZENLYGRAM: no Google OAuth client of our own (Telegram's only works for its package and certificate). Not empty,
    // because GoogleSignInOptions.Builder.requestIdToken("") throws; with this value Google sign-in fails with an
    // ApiException that LoginActivity catches. See ZgIdentity.isGoogleSignInAvailable().
    public static String GOOGLE_AUTH_CLIENT_ID = "zenlygram-google-sign-in-not-configured.apps.googleusercontent.com";

    public static String HUAWEI_APP_ID = "101184875";

    // You can use this flag to disable Google Play Billing (If you're making fork and want it to be in Google Play)
    public static boolean IS_BILLING_UNAVAILABLE = true; // ZENLYGRAM: a fork cannot sell Premium through Google Play

    // works only on official app ids, disable on your forks
    public static boolean SUPPORTS_PASSKEYS = false; // ZENLYGRAM: disabled on this fork
    // the second main tab: News, or Contacts as before them

    public static boolean SUPPORTS_FPNV = false; // ZENLYGRAM: Firebase phone number verification needs Telegram's Firebase project

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = DEBUG_VERSION || sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
            if (LOGS_ENABLED) {
                final Thread.UncaughtExceptionHandler pastHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
                    FileLog.fatal(exception, false);
                    if (pastHandler != null) {
                        pastHandler.uncaughtException(thread, exception);
                    }
                });
            }
        }
    }

    public static boolean useInvoiceBilling() {
        return BillingController.billingClientEmpty || DEBUG_VERSION && false || ApplicationLoader.isStandaloneBuild() || isBetaApp() && false || isHuaweiStoreApp() || hasDirectCurrency();
    }

    private static boolean hasDirectCurrency() {
        if (!BillingController.getInstance().isReady() || BillingController.PREMIUM_PRODUCT_DETAILS == null) {
            return false;
        }
        for (ProductDetails.SubscriptionOfferDetails offerDetails : BillingController.PREMIUM_PRODUCT_DETAILS.getSubscriptionOfferDetails()) {
            for (ProductDetails.PricingPhase phase : offerDetails.getPricingPhases().getPricingPhaseList()) {
                for (String cur : MessagesController.getInstance(UserConfig.selectedAccount).directPaymentsCurrency) {
                    if (Objects.equals(phase.getPriceCurrencyCode(), cur)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            // ZENLYGRAM: the real package (debug build = "<APP_PACKAGE>.beta"); upstream compared with "org.telegram.messenger.beta"
            betaApp = ApplicationLoader.applicationContext != null && ApplicationLoader.applicationContext.getPackageName().endsWith(".beta");
        }
        return betaApp;
    }


    public static boolean isHuaweiStoreApp() {
        return ApplicationLoader.isHuaweiStoreBuild();
    }

    public static String getSmsHash() {
        // ZENLYGRAM: upstream returned Telegram's SMS Retriever hashes (their package + certificate); ours is computed at runtime
        return org.telegram.zenlygram.identity.ZgIdentity.getSmsRetrieverHash();
    }
}
