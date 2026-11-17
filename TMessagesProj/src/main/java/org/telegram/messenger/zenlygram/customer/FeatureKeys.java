package org.telegram.messenger.zenlygram.customer;

import java.util.Locale;

/**
 * Which Zenlygram setting switches report {@code feature.toggle} and under which registry feature (design 50 §11.4),
 * pure and host-tested. The Preferences screen passes its row key: a {@code ZenlyPreferences.Setting} name
 * ({@code ROUND_VIDEO_FROM_GALLERY}) or an account row key ({@code hide_foreign_numbers}); both spellings are read
 * case-insensitively. Every other switch is not reported (null). Android has no message filter, so
 * {@code message_filter} is never produced.
 */
public final class FeatureKeys {
    private FeatureKeys() {}

    /** The feature a switch reports, or null when it is not reported. */
    public static CustomerEvents.Feature of(String key) {
        if (key == null) return null;
        switch (key.trim().toUpperCase(Locale.ROOT)) {
            case "ROUND_VIDEO_FROM_GALLERY":
                return CustomerEvents.Feature.ROUND_VIDEO_FROM_GALLERY;
            case "WARN_BEFORE_STORIES":
                return CustomerEvents.Feature.STORY_CONFIRMATION;
            case "CONFIRM_STICKERS":
            case "CONFIRM_VOICE_MESSAGES":
            case "CONFIRM_GIFS":
                return CustomerEvents.Feature.SEND_CONFIRMATION;
            case "APK_SHIELD":
                return CustomerEvents.Feature.APK_PROTECTION;
            case "HIDE_FOREIGN_NUMBERS":
                return CustomerEvents.Feature.FOREIGN_USER_BLOCK;
            case "UNREAD_REMINDER":
                return CustomerEvents.Feature.UNREAD_REMINDER;
            case "STRANGER_SHIELD":
                // The stranger shield's own row (ZenlySafetyUi.toggleStrangerShield) turns the inbox on and off.
                return CustomerEvents.Feature.STRANGER_INBOX;
            default:
                return null;
        }
    }

    /** {@code ZenlyRetention.EDIT} (0) → {@code edit_history}, {@code ZenlyRetention.DELETED} (1) → {@code deleted_messages}. */
    public static CustomerEvents.Feature retention(int type) {
        if (type == 0) return CustomerEvents.Feature.EDIT_HISTORY;
        if (type == 1) return CustomerEvents.Feature.DELETED_MESSAGES;
        return null;
    }
}
