/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.zenlygram.settings.ZgSettingsCatalog;

/**
 * Which Zenlygram settings report {@code feature.toggle}, and under which feature of the statistics registry
 * (taxonomy v1; iOS SGSettingsController.recordFeatureToggle). The settings are named by their iOS row key, the name
 * {@link ZgSettingsCatalog} already maps to this app's rows, so the list reads like the iOS one. Every other switch is
 * not reported.
 *
 * <p>Recording is the cloud client's ({@link ZenlyStats}): any thread, never throws, and nothing is recorded for an
 * account whose data consent is not in effect. An event carries the feature and on / off, never a value.
 * {@link #vaultChats} is the one rule beside the switches that the features' own code should not repeat.
 */
public final class ZgStats {

    private ZgStats() {
    }

    private static final class Toggle {
        final String key;
        final CustomerEvents.Feature feature;

        Toggle(String key, CustomerEvents.Feature feature) {
            this.key = key;
            this.feature = feature;
        }
    }

    private static Toggle toggle(String key, CustomerEvents.Feature feature) {
        return new Toggle(key, feature);
    }

    /** The reported switches (zenlygram-tests/customer/FeatureKeysTests holds each key to a row of the catalog). */
    private static final Toggle[] TOGGLES = {
        toggle("secretVaultEnabled", CustomerEvents.Feature.VAULT),
        toggle("showDeletedMessages", CustomerEvents.Feature.DELETED_MESSAGES),
        toggle("editHistoryEnabled", CustomerEvents.Feature.EDIT_HISTORY),
        toggle("warnOnStoriesOpen", CustomerEvents.Feature.STORY_CONFIRMATION),
        toggle("quickTranslateButton", CustomerEvents.Feature.TRANSLATION),
        toggle("unreadReminderEnabled", CustomerEvents.Feature.UNREAD_REMINDER),
        toggle("blockForeignUsers", CustomerEvents.Feature.FOREIGN_USER_BLOCK),
        toggle("sendRoundVideoFromGallery", CustomerEvents.Feature.ROUND_VIDEO_FROM_GALLERY),
    };

    /** The feature a setting reports, or null when it is not reported. */
    public static CustomerEvents.Feature featureOfSetting(String key) {
        for (Toggle toggle : TOGGLES) {
            if (toggle.key.equals(key)) {
                return toggle.feature;
            }
        }
        return null;
    }

    /**
     * The setting {@code key} (its iOS row key) was switched and now is {@code enabled}. Call it where the change is
     * final: after a confirmation, never for a tap that was refused.
     */
    public static void settingToggled(int account, String key, boolean enabled) {
        final CustomerEvents.Feature feature = featureOfSetting(key);
        if (feature != null) {
            ZenlyStats.featureToggle(account, feature, enabled);
        }
    }

    /** The same for the settings list's own switch handler, which knows the row by its id. */
    public static void rowToggled(int account, int rowId, boolean enabled) {
        if (rowId == 0) {
            return;
        }
        for (Toggle toggle : TOGGLES) {
            if (ZgSettingsCatalog.rowIdForKey(toggle.key) == rowId) {
                ZenlyStats.featureToggle(account, toggle.feature, enabled);
                return;
            }
        }
    }

    /**
     * The user's hide or unhide changed the number of chats in the account's Secret Vault by {@code delta}: one
     * {@code vault.chat_added} per chat that entered, one {@code vault.chat_removed} per chat that left, never one for
     * a chat that was there already (iOS SecretVaultManager.addToVault / removeFromVault). Turning the vault off
     * reports its switch, not its chats.
     */
    public static void vaultChats(int account, int delta) {
        for (int i = Math.abs(delta); i > 0; i--) {
            if (delta > 0) {
                ZenlyStats.vaultChatAdded(account);
            } else {
                ZenlyStats.vaultChatRemoved(account);
            }
        }
    }
}
