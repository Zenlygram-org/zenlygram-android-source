/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import org.telegram.ui.ActionBar.BaseFragment;

import java.util.Locale;

/**
 * Deep links into Settings &gt; Zenlygram (iOS OpenUrl + SGSettingsFocus), read by LaunchActivity (marked ZENLYGRAM):
 *
 * <ul>
 *   <li>{@code tg://zg/settings} (also the iOS spelling {@code tg://sg/settings}) opens the hub;
 *       {@code ?row=<key>} (an iOS row key, e.g. {@code chatLock}, {@code showDeletedMessages}, {@code pageTabBar};
 *       {@code telegramAccount}, or the iOS {@code proLinkAccount}, for the Telegram Account row)
 *       scrolls to that row and flashes it, opening its page first. An unknown or hidden key lands on the plain hub.</li>
 *   <li>{@code tg://pro} (also {@code tg://zg/pro}) opens the Zenlygram Pro status screen.</li>
 * </ul>
 */
public final class ZgSettingsDeepLink {

    private ZgSettingsDeepLink() {
    }

    /** Whether LaunchActivity should hand {@code url} to {@link #createFragment}. */
    public static boolean matches(String url) {
        return kind(url) != 0;
    }

    /** 1 = settings, 2 = Pro, 0 = not ours. Pure (JVM-tested). */
    static int kind(String url) {
        if (url == null) {
            return 0;
        }
        final String u = url.trim().toLowerCase(Locale.US);
        final String rest;
        if (u.startsWith("tg://")) {
            rest = u.substring(5);
        } else if (u.startsWith("tg:")) {
            rest = u.substring(3);
        } else {
            return 0;
        }
        final String path = stripQuery(rest);
        if (path.equals("zg/settings") || path.equals("sg/settings") || path.equals("zg/settings/") || path.equals("sg/settings/")) {
            return 1;
        }
        if (path.equals("pro") || path.equals("zg/pro") || path.equals("sg/pro")) {
            return 2;
        }
        return 0;
    }

    /** The {@code row} query value of {@code url}, or null. Pure (JVM-tested). */
    static String rowKey(String url) {
        if (url == null) {
            return null;
        }
        final int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String part : url.substring(q + 1).split("&")) {
            final int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals("row")) {
                final String value = part.substring(eq + 1).trim();
                if (!value.isEmpty() && value.matches("[A-Za-z0-9]{1,64}")) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String stripQuery(String s) {
        final int q = s.indexOf('?');
        final int h = s.indexOf('#');
        int end = s.length();
        if (q >= 0) {
            end = Math.min(end, q);
        }
        if (h >= 0) {
            end = Math.min(end, h);
        }
        return s.substring(0, end);
    }

    /** The screen for {@code url} (call only when {@link #matches}). */
    public static BaseFragment createFragment(String url) {
        if (kind(url) == 2) {
            return ZgSettingsEntry.createProFragment();
        }
        final String key = rowKey(url);
        if (key == null) {
            return new ZenlygramSettingsActivity();
        }
        if ("proSubscription".equals(key)) {
            return ZgSettingsEntry.createProFragment();
        }
        return ZenlygramSettingsActivity.withFocus(key);
    }
}
