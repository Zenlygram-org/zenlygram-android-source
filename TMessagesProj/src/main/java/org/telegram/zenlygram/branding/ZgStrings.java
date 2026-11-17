/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.branding;

import android.util.SparseIntArray;

import org.telegram.messenger.R;
import org.telegram.zenlygram.core.ZgConfig;

import java.util.HashMap;

/**
 * Brand override for strings that name the app.
 *
 * <p>LocaleController.getStringV2 reads Telegram's cloud language pack first, and every pack has
 * AppName = "Telegram" (also TelegramVersion, UnlockToUse, ...). Changing the bundled values alone
 * would therefore not rebrand the app. Two hooks in getStringV2 handle this:
 * <ul>
 *   <li>{@link #brandOverride}: for a few keys (the app name itself, the login texts) a ZG_* string is read
 *       instead. Cloud packs never contain ZG_* keys, so the bundled Zenlygram text
 *       (values{,-ru,-uz}/strings_zenlygram.xml) is used. The ZG_* texts exist in English, Russian and Uzbek;
 *       other languages get the English text. They come from the ANDROID_EXTRA table of
 *       Tools/zenlygram/ios_strings_to_android.py.</li>
 *   <li>{@link #brandSwap} / {@link #swapBrand}: for the permission, passcode, storage and update texts the
 *       Telegram string is read as usual, in whatever language the user has, and "Telegram" is replaced with
 *       "Zenlygram" in it. No translation is needed, so no language falls back to English.</li>
 * </ul>
 *
 * <p>Only keys where "Telegram" means this app belong here (a sentence about the Telegram service keeps its
 * Telegram string). For a text that names both, {@link #SWAP_FIRST} replaces only the first "Telegram".
 *
 * <p>The launcher label, the manifests and the system account still use the real resources
 * AppName / AppNameBeta from values/strings.xml, which say Zenlygram too.
 */
public final class ZgStrings {

    /** {@link #brandSwap}: every "Telegram" in the text means this app. */
    public static final int SWAP_ALL = 1;
    /** {@link #brandSwap}: only the first "Telegram" means this app; the rest is about the Telegram service. */
    public static final int SWAP_FIRST = 2;

    private static final String TELEGRAM = "Telegram";

    private static final SparseIntArray SWAP_BY_RES = new SparseIntArray(64);
    private static final HashMap<String, Integer> SWAP_BY_NAME = new HashMap<>(96);

    static {
        // Permission requests and their "turn it on in Settings" hints
        swap(R.string.NotificationsPermissionAlertSubtitle, "NotificationsPermissionAlertSubtitle", SWAP_ALL);
        swap(R.string.ContactsPermissionAlert, "ContactsPermissionAlert", SWAP_FIRST);
        swap(R.string.QRCodePermissionNoCameraWithHint, "QRCodePermissionNoCameraWithHint", SWAP_ALL);
        swap(R.string.PermissionStorageWithHint, "PermissionStorageWithHint", SWAP_ALL);
        swap(R.string.PermissionNoAudioWithHint, "PermissionNoAudioWithHint", SWAP_ALL);
        swap(R.string.PermissionNoBluetoothWithHint, "PermissionNoBluetoothWithHint", SWAP_ALL);
        swap(R.string.PermissionNoAudioVideoWithHint, "PermissionNoAudioVideoWithHint", SWAP_ALL);
        swap(R.string.PermissionNoCameraWithHint, "PermissionNoCameraWithHint", SWAP_ALL);
        swap(R.string.PermissionNoCameraMicVideo, "PermissionNoCameraMicVideo", SWAP_ALL);
        swap(R.string.PermissionNoLocation, "PermissionNoLocation", SWAP_ALL);
        swap(R.string.PermissionNoLocationStory, "PermissionNoLocationStory", SWAP_ALL);
        swap(R.string.PermissionNoLocationFriends, "PermissionNoLocationFriends", SWAP_ALL);
        swap(R.string.PermissionNoLocationNavigation, "PermissionNoLocationNavigation", SWAP_ALL);
        swap(R.string.PermissionNoContactsSharing, "PermissionNoContactsSharing", SWAP_ALL);
        swap(R.string.PermissionNoContactsSaving, "PermissionNoContactsSaving", SWAP_ALL);
        swap(R.string.PermissionNoStorageAvatar, "PermissionNoStorageAvatar", SWAP_ALL);
        swap(R.string.PermissionNoAudioStorageStory, "PermissionNoAudioStorageStory", SWAP_ALL);
        swap(R.string.PermissionDrawAboveOtherApps, "PermissionDrawAboveOtherApps", SWAP_ALL);
        swap(R.string.PermissionDrawAboveOtherAppsGroupCall, "PermissionDrawAboveOtherAppsGroupCall", SWAP_ALL);
        swap(R.string.PermissionXiaomiLockscreen, "PermissionXiaomiLockscreen", SWAP_ALL);
        swap(R.string.PermissionFSILockscreen, "PermissionFSILockscreen", SWAP_ALL);
        swap(R.string.PermissionBackgroundLocation, "PermissionBackgroundLocation", SWAP_ALL);
        swap(R.string.VoipNeedMicPermissionWithHint, "VoipNeedMicPermissionWithHint", SWAP_ALL);
        swap(R.string.VoipNeedMicCameraPermissionWithHint, "VoipNeedMicCameraPermissionWithHint", SWAP_ALL);
        swap(R.string.VoipNeedCameraPermission, "VoipNeedCameraPermission", SWAP_ALL);
        swap(R.string.AgeVerificationNeedCameraPermission, "AgeVerificationNeedCameraPermission", SWAP_ALL);
        swap(R.string.BotLocationPermissionRequestDeniedApp, "BotLocationPermissionRequestDeniedApp", SWAP_ALL);
        // Passcode lock
        swap(R.string.ChangePasscodeInfo, "ChangePasscodeInfo", SWAP_ALL);
        swap(R.string.ChangePasscodeInfoShort, "ChangePasscodeInfoShort", SWAP_ALL);
        swap(R.string.CreatePasscodeInfoPIN, "CreatePasscodeInfoPIN", SWAP_ALL);
        swap(R.string.CreatePasscodeInfoPassword, "CreatePasscodeInfoPassword", SWAP_ALL);
        swap(R.string.EnterYourPasscodeInfo, "EnterYourPasscodeInfo", SWAP_ALL);
        swap(R.string.EnterYourTelegramPasscode, "EnterYourTelegramPasscode", SWAP_ALL);
        swap(R.string.WalletAuthorizeTransactionPasscode, "WalletAuthorizeTransactionPasscode", SWAP_ALL);
        // The app on this device: storage, background behaviour, screenshots, contacts sync
        swap(R.string.StorageUsageTelegram, "StorageUsageTelegram", SWAP_ALL);
        swap(R.string.StorageUsageTelegramLess, "StorageUsageTelegramLess", SWAP_ALL);
        swap(R.string.SdCardAlert, "SdCardAlert", SWAP_ALL);
        swap(R.string.SdCardErrorDescription, "SdCardErrorDescription", SWAP_ALL);
        swap(R.string.LocalDatabaseInfo, "LocalDatabaseInfo", SWAP_ALL);
        swap(R.string.MigrateOldFolderDescription, "MigrateOldFolderDescription", SWAP_ALL);
        swap(R.string.LiveLocationPausedText, "LiveLocationPausedText", SWAP_ALL);
        swap(R.string.ProfilePopupNotificationInfo, "ProfilePopupNotificationInfo", SWAP_ALL);
        swap(R.string.ScreenCaptureAlert, "ScreenCaptureAlert", SWAP_ALL);
        swap(R.string.IncomingCallsSystemSettingDescription, "IncomingCallsSystemSettingDescription", SWAP_ALL);
        swap(R.string.UpdateContactsMessage, "UpdateContactsMessage", SWAP_ALL);
        // "Update the app" (an update of Zenlygram, which is based on an older Telegram release)
        swap(R.string.UpdateAppAlert, "UpdateAppAlert", SWAP_ALL);
        swap(R.string.UpdateTelegram, "UpdateTelegram", SWAP_ALL);
        swap(R.string.AppUpdate, "AppUpdate", SWAP_ALL);
        swap(R.string.AppUpdateBeta, "AppUpdateBeta", SWAP_ALL);
        swap(R.string.UnsupportedMedia2, "UnsupportedMedia2", SWAP_ALL);
        swap(R.string.StoryUnsupported, "StoryUnsupported", SWAP_ALL);
        swap(R.string.UnsupportedBlockMessage, "UnsupportedBlockMessage", SWAP_ALL);
        swap(R.string.UnsupportedMessageMessage, "UnsupportedMessageMessage", SWAP_ALL);
        swap(R.string.Gift2ExportTONUpdateRequiredText, "Gift2ExportTONUpdateRequiredText", SWAP_ALL);
    }

    private ZgStrings() {
    }

    private static void swap(int res, String name, int mode) {
        SWAP_BY_RES.put(res, mode);
        SWAP_BY_NAME.put(name, mode);
    }

    /**
     * {@link #SWAP_ALL} or {@link #SWAP_FIRST} if the looked-up text gets the app's name swapped in, 0 otherwise.
     * Called for every string lookup: a binary search over ints, and a map lookup only for the by-name calls
     * without a resource id (getString("Key")).
     */
    public static int brandSwap(String key, int stringRes) {
        if (stringRes != 0) {
            return SWAP_BY_RES.get(stringRes);
        }
        if (key != null) {
            final Integer mode = SWAP_BY_NAME.get(key);
            return mode != null ? mode : 0;
        }
        return 0;
    }

    /** Replaces "Telegram" with "Zenlygram" in {@code value} as {@code mode} says ({@link #brandSwap}). */
    public static String swapBrand(String value, int mode) {
        if (value == null) {
            return null;
        }
        final int index = value.indexOf(TELEGRAM);
        if (index < 0) {
            return value;
        }
        if (mode == SWAP_FIRST) {
            return value.substring(0, index) + ZgConfig.BRAND_NAME + value.substring(index + TELEGRAM.length());
        }
        return value.replace(TELEGRAM, ZgConfig.BRAND_NAME);
    }

    /**
     * Returns the ZG_* string resource to read instead of {@code stringRes} / {@code key}, or 0 to
     * keep the lookup unchanged. Called for every string lookup, so it only compares ints, plus a
     * switch for the deprecated by-name calls such as getString("AppName", R.string.AppName).
     */
    public static int brandOverride(String key, int stringRes) {
        if (stringRes != 0) {
            final int zg = byResId(stringRes);
            if (zg != 0) {
                return zg;
            }
        }
        if (key != null) {
            return byName(key);
        }
        return 0;
    }

    private static int byResId(int res) {
        if (res == R.string.AppName
                || res == R.string.NotificationHiddenName
                || res == R.string.NotificationHiddenChatName
                || res == R.string.SecretChatName
                || res == R.string.Page1Title) {
            return R.string.ZG_AppName;
        } else if (res == R.string.AppNameBeta) {
            return R.string.ZG_AppNameBeta;
        } else if (res == R.string.TelegramVersion) {
            return R.string.ZG_TelegramVersion;
        } else if (res == R.string.UnlockToUse) {
            return R.string.ZG_UnlockToUse;
        } else if (res == R.string.AppLocked) {
            return R.string.ZG_AppLocked;
        } else if (res == R.string.AllowReadCall) {
            return R.string.ZG_AllowReadCall;
        } else if (res == R.string.AllowReadCallAndLog) {
            return R.string.ZG_AllowReadCallAndLog;
        } else if (res == R.string.AllowReadCallLog) {
            return R.string.ZG_AllowReadCallLog;
        } else if (res == R.string.AllowFillNumber) {
            return R.string.ZG_AllowFillNumber;
        } else if (res == R.string.SentCallCode) {
            return R.string.ZG_SentCallCode;
        }
        return 0;
    }

    private static int byName(String key) {
        switch (key) {
            case "AppName":
            case "NotificationHiddenName":
            case "NotificationHiddenChatName":
            case "SecretChatName":
            case "Page1Title":
                return R.string.ZG_AppName;
            case "AppNameBeta":
                return R.string.ZG_AppNameBeta;
            case "TelegramVersion":
                return R.string.ZG_TelegramVersion;
            case "UnlockToUse":
                return R.string.ZG_UnlockToUse;
            case "AppLocked":
                return R.string.ZG_AppLocked;
            case "AllowReadCall":
                return R.string.ZG_AllowReadCall;
            case "AllowReadCallAndLog":
                return R.string.ZG_AllowReadCallAndLog;
            case "AllowReadCallLog":
                return R.string.ZG_AllowReadCallLog;
            case "AllowFillNumber":
                return R.string.ZG_AllowFillNumber;
            case "SentCallCode":
                return R.string.ZG_SentCallCode;
            default:
                return 0;
        }
    }
}
