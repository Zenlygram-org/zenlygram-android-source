/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.media;

import org.telegram.zenlygram.core.ZgSettings;

/**
 * Outgoing photo quality (iOS SGSimpleSettings outgoingPhotoQuality) and Send HD Photos (sendLargePhotos).
 *
 * <p>Telegram Android already compresses sharper than iOS: standard photos at JPEG 80, HD at 99, story photos at 95,
 * edited photos rebuilt at 87 / 99. The slider therefore shifts all of them by the same amount around its Android
 * default {@value #ANDROID_DEFAULT}: at 80 every path keeps Telegram's own quality, 100 gives 100 everywhere, 50 gives
 * 50 / 69 / 65. The percentage shown equals the real JPEG quality of standard photos. Below 10 the image would be
 * unusable, so 10 is the floor, and the slider starts there (ZgSettings.outgoingPhotoQuality).
 */
public final class ZgPhotoQuality {

    /** The slider default on Android: Telegram Android's own standard JPEG quality (iOS defaults to 70). */
    public static final int ANDROID_DEFAULT = 80;
    private static final int MIN_QUALITY = 10;
    private static final int MAX_QUALITY = 100;

    private ZgPhotoQuality() {
    }

    /** The JPEG quality to use where Telegram uses {@code telegramQuality}. Always 10-100. */
    public static int jpeg(int telegramQuality) {
        final int shifted = telegramQuality + ZgSettings.outgoingPhotoQuality.get() - ANDROID_DEFAULT;
        return Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, shifted));
    }

    /**
     * Send HD Photos: photos start in HD in the picker (2560 px). Telegram's own memory of the last HD / SD choice still
     * applies on top, and a per-photo choice in the photo editor wins for that photo.
     */
    public static boolean hdByDefault() {
        return ZgSettings.sendLargePhotos.get();
    }
}
