/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.appearance;

import org.telegram.zenlygram.core.ZgSettings;

import java.util.Calendar;

/**
 * Holiday effects over the chat wallpaper (iOS SGSimpleSettings canUseNY / nyStyle, Swiftgram SGNY).
 *
 * <p>iOS took the availability (canUseNY) from the Swiftgram server, which Zenlygram does not use, so iOS never shows
 * the section. Android uses a fixed New Year window instead: 20 December - 10 January, device time zone (the owner's
 * decision; canUseNY still turns the section on outside it). Outside the window the section and the effect disappear
 * and the chosen style is kept for next season.
 *
 * <p>Snow is Telegram's own chat-background snow (SizeNotifierFrameLayout, normally only on 1 January); Lightning is
 * {@link ZgLightningEffect}. Both follow Lite Mode / power saving like Telegram's snow. The checks run on every frame
 * of an open chat, so the season is cached for a minute and nothing allocates.
 */
public final class ZgHolidayEffects {

    private static final long SEASON_CACHE_MS = 60_000L;

    private static volatile long seasonCheckedAt;
    private static volatile boolean season;

    private ZgHolidayEffects() {
    }

    /** Whether the Holiday effects section is offered (and a chosen effect drawn). */
    public static boolean isAvailable() {
        return ZgSettings.canUseNY.get() || isSeason();
    }

    /** Snow chosen and available: Telegram's chat-background snow is drawn. */
    public static boolean isSnow() {
        return ZgSettings.nyStyle.is(ZgSettings.HolidayStyle.SNOW) && isAvailable();
    }

    /** Lightning chosen and available: {@link ZgLightningEffect} is drawn instead of any snow. */
    public static boolean isLightning() {
        return ZgSettings.nyStyle.is(ZgSettings.HolidayStyle.LIGHTNING) && isAvailable();
    }

    /** Snow or Lightning is being drawn by Zenlygram. */
    public static boolean isActive() {
        return !ZgSettings.nyStyle.is(ZgSettings.HolidayStyle.DEFAULT) && isAvailable();
    }

    /** 20 December - 10 January in the device time zone, re-evaluated at most once a minute. */
    static boolean isSeason() {
        final long now = System.currentTimeMillis();
        final long checkedAt = seasonCheckedAt;
        if (checkedAt == 0L || now - checkedAt >= SEASON_CACHE_MS || now < checkedAt) {
            final Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(now);
            season = isSeasonDay(calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));
            seasonCheckedAt = now;
        }
        return season;
    }

    /** {@code month} as in Calendar.MONTH (0 = January), {@code day} 1-31. */
    static boolean isSeasonDay(int month, int day) {
        return month == Calendar.DECEMBER && day >= 20 || month == Calendar.JANUARY && day <= 10;
    }
}
