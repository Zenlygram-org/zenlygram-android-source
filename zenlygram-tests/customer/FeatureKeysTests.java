package org.telegram.messenger.zenlygram.customer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-JVM checks of the preference → feature map (design 50 §11.4): the documented switches report their registry
 * feature under either spelling the Preferences screen passes (a {@code ZenlyPreferences.Setting} name or an account row
 * key), every other switch is not reported, and nothing maps to {@code message_filter}.
 * <p>
 * Zenlygram 13.2.1: this app names its settings by their iOS row key ({@code ZgSettingsCatalog}) and lists the switches
 * that report {@code feature.toggle} in {@code org.telegram.zenlygram.customer.ZgStats}. Both are read from source: every
 * key ZgStats names is a row of the catalog (so a rename fails here) and reports the feature iOS reports for that
 * setting. Run with run-tests.sh, which passes the Android repository root.
 */
public final class FeatureKeysTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: FeatureKeysTests <android repository root>");
        Path root = Paths.get(args[0]);
        mapping();
        notReported();
        settingsExist(root);
        retention();
        System.out.println("Feature keys: " + checks + " checks passed");
    }

    private static Map<String, CustomerEvents.Feature> documented() {
        Map<String, CustomerEvents.Feature> map = new LinkedHashMap<>();
        map.put("ROUND_VIDEO_FROM_GALLERY", CustomerEvents.Feature.ROUND_VIDEO_FROM_GALLERY);
        map.put("WARN_BEFORE_STORIES", CustomerEvents.Feature.STORY_CONFIRMATION);
        map.put("CONFIRM_STICKERS", CustomerEvents.Feature.SEND_CONFIRMATION);
        map.put("CONFIRM_VOICE_MESSAGES", CustomerEvents.Feature.SEND_CONFIRMATION);
        map.put("CONFIRM_GIFS", CustomerEvents.Feature.SEND_CONFIRMATION);
        map.put("APK_SHIELD", CustomerEvents.Feature.APK_PROTECTION);
        map.put("HIDE_FOREIGN_NUMBERS", CustomerEvents.Feature.FOREIGN_USER_BLOCK);
        map.put("UNREAD_REMINDER", CustomerEvents.Feature.UNREAD_REMINDER);
        map.put("STRANGER_SHIELD", CustomerEvents.Feature.STRANGER_INBOX);
        return map;
    }

    private static void mapping() {
        for (Map.Entry<String, CustomerEvents.Feature> entry : documented().entrySet()) {
            check(FeatureKeys.of(entry.getKey()) == entry.getValue(), entry.getKey() + " → " + entry.getValue().wire);
            check(FeatureKeys.of(entry.getKey().toLowerCase(java.util.Locale.ROOT)) == entry.getValue(), "the row-key spelling of " + entry.getKey());
        }
        check(FeatureKeys.of("hide_foreign_numbers") == CustomerEvents.Feature.FOREIGN_USER_BLOCK, "the account row KEY_HIDE_FOREIGN_NUMBERS");
        check(FeatureKeys.of("unread_reminder") == CustomerEvents.Feature.UNREAD_REMINDER, "the account row KEY_UNREAD_REMINDER");
        check(FeatureKeys.of(" APK_SHIELD ") == CustomerEvents.Feature.APK_PROTECTION, "surrounding blanks are ignored");
    }

    private static void notReported() {
        for (String key : new String[]{null, "", "CONFIRM_CALLS", "HIDE_PHONE", "COMPACT_CHAT_LIST", "SHOW_TASKS_TAB", "REPOST_TO_STORY",
                "account_notifications", "auto_approve_join_requests", "gallery_camera", "force_tcp_in_calls", "unread_reminder_delay",
                "unread_reminder_sound", "stranger_inbox", "MESSAGE_FILTER", "message_filter"}) {
            check(FeatureKeys.of(key) == null, "'" + key + "' is not reported");
        }
        Set<CustomerEvents.Feature> produced = new HashSet<>(documented().values());
        for (CustomerEvents.Feature feature : produced) check(!feature.wire.equals("message_filter"), "never message_filter");
    }

    /** The switches iOS reports (SGSettingsController.recordFeatureToggle), by their row key, with their feature. */
    private static Map<String, CustomerEvents.Feature> reportedOnIos() {
        Map<String, CustomerEvents.Feature> map = new LinkedHashMap<>();
        map.put("secretVaultEnabled", CustomerEvents.Feature.VAULT);
        map.put("showDeletedMessages", CustomerEvents.Feature.DELETED_MESSAGES);
        map.put("editHistoryEnabled", CustomerEvents.Feature.EDIT_HISTORY);
        map.put("warnOnStoriesOpen", CustomerEvents.Feature.STORY_CONFIRMATION);
        map.put("quickTranslateButton", CustomerEvents.Feature.TRANSLATION);
        map.put("unreadReminderEnabled", CustomerEvents.Feature.UNREAD_REMINDER);
        map.put("blockForeignUsers", CustomerEvents.Feature.FOREIGN_USER_BLOCK);
        map.put("sendRoundVideoFromGallery", CustomerEvents.Feature.ROUND_VIDEO_FROM_GALLERY);
        return map;
    }

    /**
     * Zenlygram 13.2.1 (the import's preference enums have no counterpart here): the switches ZgStats reports are exactly
     * the ones iOS reports, each under the same feature, and each key is a row of this app's settings catalog.
     */
    private static void settingsExist(Path root) throws Exception {
        String stats = new String(Files.readAllBytes(root.resolve("TMessagesProj/src/main/java/org/telegram/zenlygram/customer/ZgStats.java")),
                StandardCharsets.UTF_8);
        Map<String, CustomerEvents.Feature> reported = new LinkedHashMap<>();
        Matcher toggle = Pattern.compile("\\btoggle\\(\"(\\w+)\", CustomerEvents\\.Feature\\.(\\w+)\\)").matcher(stats);
        while (toggle.find()) {
            check(reported.put(toggle.group(1), CustomerEvents.Feature.valueOf(toggle.group(2))) == null, toggle.group(1) + " is listed once");
        }
        check(reported.equals(reportedOnIos()), "ZgStats reports the switches iOS reports, under the same features: " + reported);
        for (CustomerEvents.Feature feature : reported.values()) check(!feature.wire.equals("message_filter"), "never message_filter");

        String catalog = new String(Files.readAllBytes(root.resolve("TMessagesProj/src/main/java/org/telegram/zenlygram/settings/ZgSettingsCatalog.java")),
                StandardCharsets.UTF_8);
        int table = catalog.indexOf("final Object[] table = {");
        check(table >= 0, "the catalog's key table was found");
        String rows = catalog.substring(table, catalog.indexOf("};", table));
        Set<String> keys = new HashSet<>();
        Matcher row = Pattern.compile("\"(\\w+)\",\\s*[\\w.]+").matcher(rows);
        while (row.find()) keys.add(row.group(1));
        check(keys.size() > 60, "the settings catalog was read: " + keys.size());
        for (String key : reported.keySet()) check(keys.contains(key), key + " is a row of the settings catalog");
    }

    private static void retention() {
        check(FeatureKeys.retention(0) == CustomerEvents.Feature.EDIT_HISTORY, "ZenlyRetention.EDIT → edit_history");
        check(FeatureKeys.retention(1) == CustomerEvents.Feature.DELETED_MESSAGES, "ZenlyRetention.DELETED → deleted_messages");
        check(FeatureKeys.retention(2) == null && FeatureKeys.retention(-1) == null, "anything else: not reported");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
