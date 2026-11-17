/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Brand constants shared by the Zenlygram screens: names, links, the upstream release and the brand colours.
 *
 * <p>Account type, contact MIME types and the SMS hash live in {@code org.telegram.zenlygram.identity.ZgIdentity}.
 */
public final class ZgConfig {

    private ZgConfig() {
    }

    public static final String BRAND_NAME = "Zenlygram";

    public static final String WEBSITE_URL = "https://zenlygram.org";
    /** How the website is written in the UI (Settings > Zenlygram > About). */
    public static final String WEBSITE_DISPLAY_NAME = "zenlygram.org";
    public static final String PRIVACY_POLICY_URL = "https://zenlygram.org/privacy-policy.html";
    /**
     * The website's own checkout (card payment through Atmos: Uzcard / Humo). The side-load build opens it in the
     * browser while Pro is a subscription; the Google Play build never does (it sells through Play Billing only).
     */
    public static final String PRO_WEB_URL = WEBSITE_URL + "/pro";
    /** The website's download page: where a side-load build gets its updates (the Play build updates through Play). */
    public static final String DOWNLOAD_URL = WEBSITE_URL + "/download";

    /**
     * The download page in {@code locale} (the website's own rule: Uzbek has no prefix, Russian and English are
     * /ru and /en; anything else is the Uzbek page).
     */
    public static String downloadUrl(String locale) {
        if ("ru".equals(locale)) {
            return WEBSITE_URL + "/ru/download";
        }
        if ("en".equals(locale)) {
            return WEBSITE_URL + "/en/download";
        }
        return DOWNLOAD_URL;
    }
    /**
     * Public source code repository. Placeholder: null until it is published (iOS metadata still says
     * {@code <REPO_URL>}). While it is null the About row says the link will appear there and is not clickable.
     */
    public static final String SOURCE_CODE_URL = null;

    /**
     * The Telegram for Android release this tree is based on (upstream commit 7be672e "update to 13.2.1 (7291)").
     * Bump both values on every upstream merge.
     */
    public static final String UPSTREAM_TELEGRAM_VERSION = "13.2.1";
    public static final int UPSTREAM_TELEGRAM_BUILD = 7291;

    // Brand colours (iOS Zenlygram.icon artwork and SGPayWall).
    public static final int COLOR_BRAND_BLUE = 0xFF1F7FFF;
    public static final int COLOR_BRAND_BLUE_LIGHT = 0xFF4AA3FF;
    public static final int COLOR_BRAND_BLUE_DEEP = 0xFF0A5BD6;
    public static final int COLOR_BRAND_CYAN = 0xFF2EE2F2;
    /** The NEW pill (iOS SGSettingsIconColor.green). */
    public static final int COLOR_NEW_BADGE = 0xFF34C759;
    public static final int COLOR_PRO_GOLD = 0xFFD4AF37;

    // The installed version cannot change while the process runs: read once (the version row is rebuilt on every
    // refresh and search keystroke, and each PackageManager call is a binder round trip).
    private static volatile String appVersionName;
    private static volatile int appVersionBuild = -1;

    /** versionName of the installed app, e.g. "13.2.1". */
    public static String getAppVersionName() {
        String name = appVersionName;
        if (name == null) {
            final PackageInfo info = getPackageInfo();
            if (info == null) {
                return BuildVars.BUILD_VERSION_STRING; // not cached: try again next time
            }
            appVersionName = name = !TextUtils.isEmpty(info.versionName) ? info.versionName : BuildVars.BUILD_VERSION_STRING;
        }
        return name;
    }

    /**
     * Build number of the installed app, e.g. 7291. The app modules store APP_VERSION_CODE * 10 + an ABI digit
     * in versionCode (same decoding as SettingsActivity.getVersionName()).
     */
    @SuppressWarnings("deprecation")
    public static int getAppVersionBuild() {
        int build = appVersionBuild;
        if (build < 0) {
            final PackageInfo info = getPackageInfo();
            if (info == null) {
                return 0; // not cached: try again next time
            }
            appVersionBuild = build = info.versionCode / 10;
        }
        return build;
    }

    private static PackageInfo getPackageInfo() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }
}
