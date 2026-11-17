package org.telegram.messenger.zenlygram.customer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.zenlygram.pro.GooglePlayCheckout;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;

import java.util.Locale;
import java.util.TimeZone;

/**
 * The device and app facts customer data carries (contract §3.1 {@code context}, §2.4.3 top-level versions and
 * {@code device}; design 50 §9.1), read on any thread, never throwing: platform {@code android}; {@code device_class}
 * from the smallest screen width (≥ 600 dp → tablet); {@code os_version} {@code Build.VERSION.RELEASE} and
 * {@code app_version} {@code ZenlyPro.appVersionName()}, both in the {@code ver} form the backend requires;
 * {@code app_build} the version code; {@code device_model} {@code Build.MANUFACTURER + " " + Build.MODEL} as printable
 * ASCII; {@code ui_language} the app language ({@code LocaleController} {@code getLangCode()}, lower-cased);
 * {@code locale} the system language tag as {@code bcp47} ({@code Locale.getDefault()} is overwritten with the app
 * language, so the system one comes from {@code LocaleController.getSystemDefaultLocale()}); {@code timezone}
 * {@code TimeZone.getDefault().getID()}; {@code distribution} the app module's {@code zenlygram_distribution}
 * resValue (read by name: it is generated in the app module), else debug / play / direct from this build. The
 * fixed facts are read once per process.
 */
public final class ZenlyCustomerDevice {
    private static volatile String appVersion;
    private static volatile String appBuild;
    private static volatile String distribution;
    /** The last context built; reused while the facts that can change in a process (class, language, zone) did not. */
    private static volatile CustomerEvents.Context last;

    private ZenlyCustomerDevice() {}

    /** The context events are recorded under now. */
    public static CustomerEvents.Context context() {
        boolean tablet = isTablet();
        String uiLanguage = rawUiLanguage();
        String timezone = rawTimezone();
        CustomerEvents.Context previous = last;
        if (previous != null && previous.deviceClass.equals(tablet ? CustomerEvents.Context.TABLET : CustomerEvents.Context.PHONE)
                && same(previous.uiLanguage, CustomerEvents.Context.uiLanguage(uiLanguage))
                && same(previous.timezone, CustomerEvents.Context.timezone(timezone))) {
            return previous;
        }
        CustomerEvents.Context context = CustomerEvents.Context.of(tablet, Build.VERSION.RELEASE, rawAppVersion(), rawAppBuild(),
                Build.MANUFACTURER + " " + Build.MODEL, uiLanguage, timezone, rawDistribution());
        last = context;
        return context;
    }

    /** {@code os_version} ({@code ver}). */
    public static String osVersion() { return UnicodeText.ver(Build.VERSION.RELEASE); }

    /** {@code app_version} ({@code ver}). */
    public static String appVersion() { return UnicodeText.ver(rawAppVersion()); }

    /** {@code device_class}: {@code tablet} or {@code phone}. */
    public static String deviceClass() { return isTablet() ? CustomerEvents.Context.TABLET : CustomerEvents.Context.PHONE; }

    /** {@code device_model} (printable ASCII, at most 64) or null. */
    public static String deviceModel() { return CustomerEvents.Context.deviceModel(Build.MANUFACTURER + " " + Build.MODEL); }

    /** {@code app_build} (the decimal version code) or null. */
    public static String appBuild() { return CustomerEvents.Context.appBuild(rawAppBuild()); }

    /** {@code ui_language} (the app language) or null. */
    public static String uiLanguage() { return CustomerEvents.Context.uiLanguage(rawUiLanguage()); }

    /** {@code locale} (the system language tag as language[-Script][-REGION]) or null. */
    public static String locale() {
        try {
            Locale system = LocaleController.getInstance().getSystemDefaultLocale();
            return system == null ? null : UnicodeText.bcp47(system.toLanguageTag());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** {@code timezone} ({@code iana_tz}) or null. */
    public static String timezone() { return CustomerEvents.Context.timezone(rawTimezone()); }

    /** {@code distribution}: debug, play or direct. */
    public static String distribution() { return CustomerEvents.Context.distribution(rawDistribution()); }

    private static boolean isTablet() {
        try {
            Context context = ApplicationLoader.applicationContext;
            return context != null && context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }

    /** {@code ZenlyPro.appVersionName()} (a package manager call), read once per process. */
    private static String rawAppVersion() {
        String value = appVersion;
        if (value == null) appVersion = value = ZenlyPro.appVersionName();
        return value;
    }

    @SuppressWarnings("deprecation")
    private static String rawAppBuild() {
        String value = appBuild;
        if (value != null) return value.isEmpty() ? null : value;
        value = "";
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                if (code > 0) value = Long.toString(code);
            }
        } catch (Exception unavailable) {
            // No build number: app_build is null.
        }
        appBuild = value;
        return value.isEmpty() ? null : value;
    }

    private static String rawUiLanguage() {
        try {
            LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
            return info == null || info.shortName == null ? null : info.getLangCode();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static String rawTimezone() {
        try {
            return TimeZone.getDefault().getID();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean playFlavor() {
        try {
            Context context = ApplicationLoader.applicationContext;
            return context != null && context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode % 10 == 1;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static String rawDistribution() {
        String value = distribution;
        if (value != null) return value;
        value = null;
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                int id = context.getResources().getIdentifier("zenlygram_distribution", "string", context.getPackageName());
                if (id != 0) value = context.getString(id);
            }
        } catch (RuntimeException unavailable) {
            // Falls back to this build's own flags below.
        }
        if (!"debug".equals(value) && !"play".equals(value) && !"direct".equals(value)) {
            // ZENLYGRAM 13.2.1: the Play bundle flavor's versionCode ends in 1 (APP_VERSION_CODE * 10 + 1), the
            // side-load APK's in 9; there is no per-flavor resource to read.
            value = BuildConfig.DEBUG_VERSION ? "debug" : playFlavor() ? "play" : "direct";
        }
        distribution = value;
        return value;
    }
}
