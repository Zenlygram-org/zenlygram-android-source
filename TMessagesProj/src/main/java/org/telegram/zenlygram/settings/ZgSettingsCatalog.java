/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings &gt; Zenlygram is a hub and twelve pages (iOS SGSettingsCatalog, settings IA 2026-11). This class is the
 * data-only inventory of that tree, plain Java with no Android type, so it is tested on the JVM
 * (ZgSettingsCatalogTest):
 *
 * <ul>
 *   <li>the iOS tables verbatim: the pages in hub order, the hub's own row keys, the row keys of every page, the
 *       gated keys and the NEW-badge oracle. A row key is the iOS row's setting / link name (= its NEW-badge key);
 *       keys are never renamed, deep links depend on them;</li>
 *   <li>the Android placement: which page every {@link ZgSettingsSpec} section lives on ({@link #pageOfSection},
 *       {@code null} = the hub), and which Android row stands for an iOS key ({@link #rowIdForKey}) for deep links
 *       ({@code tg://zg/settings?row=<key>}, also {@code tg://sg/settings?row=}).</li>
 * </ul>
 *
 * <p>Android differences: the Contacts &amp; Calls switch is the hub's first row (iOS lenta-v6), so the iOS tab-bar keys
 * showContactsTab / showCallsTab land on it; the About and Developer sections (Android only) stay on the hub; the
 * Telegram Account row of the ACCOUNT &amp; DATA block answers to the iOS key proLinkAccount and to telegramAccount.
 */
public final class ZgSettingsCatalog {

    private ZgSettingsCatalog() {
    }

    /** One page of the hub, in hub order (iOS SGSettingsPage). */
    public enum Page {
        // Feature pages, pushed from MAIN FEATURES rows.
        SECRET_VAULT("secretVault", false),
        HISTORY("history", false),
        CALL_RECORDING("callRecording", false),
        // Group pages, pushed from MORE SETTINGS rows.
        CHATS("chats", true),
        MESSAGES("messages", true),
        MESSAGE_MENU("messageMenu", true),
        TAB_BAR("tabBar", true),
        PRIVACY("privacy", true),
        TRANSLATION("translation", true),
        MEDIA("media", true),
        COLORS("colors", true),
        CALLS_ADVANCED("callsAdvanced", true);

        /** The iOS case name. */
        public final String key;
        /** Group pages sit under MORE SETTINGS; in the hub's search their headers get the page title as a prefix. */
        public final boolean isGroup;

        Page(String key, boolean isGroup) {
            this.key = key;
            this.isGroup = isGroup;
        }

        /** The key of the hub row that opens this page: "page" + the case name with its first letter uppercased. */
        public String hubRowKey() {
            return "page" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }

        /** The page for its {@link #key}, or null. */
        public static Page fromKey(String key) {
            for (Page page : values()) {
                if (page.key.equals(key)) {
                    return page;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // iOS tables (SGSettingsCatalog.swift, release/appstore), verbatim
    // ---------------------------------------------------------------------------------------------------------

    /** The hub's own rows in hub order: MAIN FEATURES, ACCOUNT &amp; DATA, ZENLYGRAM PRO, then MORE SETTINGS. */
    public static final List<String> HUB_ROW_KEYS = Collections.unmodifiableList(Arrays.asList(
        "pageSecretVault",
        "chatLock",
        "protectionRecovery",
        "tasks",
        "pageHistory",
        "pageCallRecording",
        "proLinkAccount",
        "dataConsent",
        "privacyPolicy",
        "proSubscription",
        "proRestorePurchases",
        "pageChats",
        "pageMessages",
        "pageMessageMenu",
        "pageTabBar",
        "pagePrivacy",
        "pageTranslation",
        "pageMedia",
        "pageColors",
        "pageCallsAdvanced"
    ));

    /** The rows of a page in page order, section by section (iOS rowKeys(on:)). */
    public static List<String> rowKeys(Page page) {
        switch (page) {
            case SECRET_VAULT:
                return Arrays.asList("secretVaultEnabled", "secretVaultUseSecretCode", "secretVaultChangePin", "protectionRecovery");
            case HISTORY:
                return Arrays.asList("showDeletedMessages", "editHistoryEnabled");
            case CALL_RECORDING:
                return Arrays.asList("callRecordings", "callRecordingNotifyOtherParty", "callRecordingSendToSavedMessages");
            case CHATS:
                return Arrays.asList(
                    "compactChatList", "compactMessagePreview", "unlimitedPinnedChats", "disableChatSwipeOptions",
                    "disableDeleteChatSwipeOption",
                    "foldersAtBottom", "rememberLastFolder", "allChatsHidden", "compactFolderNames", "allChatsTitleLengthOverride",
                    "hideStories", "disableSwipeToRecordStory", "showRepostToStory",
                    "unreadReminderEnabled");
            case MESSAGES:
                return Arrays.asList(
                    "hideReactions", "secondsInMessages", "wideChannelPosts", "hideChannelBottomButton",
                    "disableSnapDeletionEffect", "disableScrollToNextChannel", "disableScrollToNextTopic",
                    "sendWithReturnKey", "messageDoubleTapActionOutgoingEdit", "disableSendAsButton", "hideRecordingButton",
                    "forceEmojiTab", "defaultEmojisFirst");
            case MESSAGE_MENU:
                return Arrays.asList(
                    "contextShowSaveToCloud", "contextShowSaveMedia", "contextShowReply", "contextShowPin",
                    "contextShowSelectFromUser", "contextShowMessageReplies", "contextShowHideForwardName",
                    "contextShowReport", "contextShowRestrict", "contextShowJson");
            case TAB_BAR:
                return Arrays.asList("hideTabBar", "showTabNames", "tabBarSearchEnabled", "showContactsTab", "showCallsTab",
                    "showTasksTab", "wideTabBar");
            case PRIVACY:
                return Arrays.asList(
                    "blockForeignUsers", "autoAcceptRequests", "confirmCalls",
                    "hidePhoneInSettings", "warnOnStoriesOpen",
                    "showRegDate", "showCreationDate", "showDC", "showProfileId", "showViewFirstMessage");
            case TRANSLATION:
                return Arrays.asList("translationBackend", "quickTranslateButton", "languageSettings", "transcriptionBackend",
                    "forceBuiltInMic");
            case MEDIA:
                return Arrays.asList(
                    "autoDownloadDisabled", "downloadSpeedBoost", "uploadSpeedBoost",
                    "outgoingPhotoQuality", "sendLargePhotos", "stickerSize", "stickerTimestamp",
                    "swipeForVideoPIP", "startTelescopeWithRearCam", "sendRoundVideoFromGallery",
                    "disableGalleryCamera", "disableGalleryCameraPreview");
            case COLORS:
                return Arrays.asList("accountColorsSaturation", "nyStyleSnow", "nyStyleLightning");
            case CALLS_ADVANCED:
            default:
                return Arrays.asList("enableVoipTcp", "contentSettings", "autoProxyEnabled", "aiAssistantEnabled", "aiAssistantApiKey");
        }
    }

    /** Rows that render on more than one screen (iOS sharedRowKeys). */
    public static final Set<String> SHARED_ROW_KEYS = Collections.singleton("protectionRecovery");

    /** Rows built only in some states (iOS gatedRowKeys). */
    public static final Set<String> GATED_ROW_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "proSubscription", "proRestorePurchases", "dataConsent", "privacyPolicy", "pageCallRecording", "callRecordings",
        "callRecordingNotifyOtherParty", "callRecordingSendToSavedMessages", "secretVaultUseSecretCode",
        "secretVaultChangePin", "protectionRecovery", "aiAssistantEnabled", "aiAssistantApiKey", "contentSettings",
        "autoProxyEnabled", "nyStyleSnow", "nyStyleLightning"
    )));

    /** Test oracle for the NEW roll-up: the rows of each page born with a NEW badge (iOS newBadgeKeys). */
    public static List<String> newBadgeKeys(Page page) {
        switch (page) {
            case SECRET_VAULT:
                return Arrays.asList("secretVaultEnabled", "secretVaultUseSecretCode", "secretVaultChangePin");
            case HISTORY:
                return Arrays.asList("showDeletedMessages", "editHistoryEnabled");
            case CHATS:
                return Arrays.asList("unlimitedPinnedChats", "unreadReminderEnabled");
            case PRIVACY:
                return Arrays.asList("blockForeignUsers", "autoAcceptRequests", "showViewFirstMessage");
            case MEDIA:
                return Arrays.asList("autoDownloadDisabled", "sendRoundVideoFromGallery");
            case CALLS_ADVANCED:
                return Arrays.asList("autoProxyEnabled", "aiAssistantEnabled");
            default:
                return Collections.emptyList();
        }
    }

    /** Every row key once: the hub rows, then the pages in hub order (a shared key keeps its first place). */
    public static List<String> allRowKeys() {
        final LinkedHashSet<String> keys = new LinkedHashSet<>(HUB_ROW_KEYS);
        for (Page page : Page.values()) {
            keys.addAll(rowKeys(page));
        }
        return Collections.unmodifiableList(new java.util.ArrayList<>(keys));
    }

    /** The page a row key lives on: null for the hub's own rows and for unknown keys (iOS page(containing:)). */
    public static Page pageContaining(String key) {
        if (key == null || HUB_ROW_KEYS.contains(key)) {
            return null;
        }
        for (Page page : Page.values()) {
            if (rowKeys(page).contains(key)) {
                return page;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Android placement
    // ---------------------------------------------------------------------------------------------------------

    /** The hub's own sections (ZgSettingsHub): quick switch, MAIN FEATURES, MORE SETTINGS. 83 is kept free: the ACCOUNT &amp; DATA block between them is ZgSettingsSpec.SECTION_DATA. */
    public static final int SECTION_HUB_QUICK = 81;
    public static final int SECTION_HUB_FEATURES = 82;
    public static final int SECTION_HUB_GROUPS = 84;

    public static final int ROW_CONTACTS_AND_CALLS = 8101;
    public static final int ROW_PAGE_SECRET_VAULT = 8201;
    public static final int ROW_CHAT_LOCK = 8202;
    public static final int ROW_PROTECTION_RECOVERY_NOTICE = 8203;
    public static final int ROW_PROTECTION_RECOVERY = 8204;
    public static final int ROW_TASKS = 8205;
    public static final int ROW_PAGE_HISTORY = 8206;
    public static final int ROW_PAGE_CALL_RECORDING = 8207;
    /** MORE SETTINGS: 8401 + the group page's position among the group pages. */
    public static final int ROW_PAGE_GROUP_FIRST = 8401;

    /** The hub row that opens {@code page}. */
    public static int hubRowId(Page page) {
        switch (page) {
            case SECRET_VAULT:
                return ROW_PAGE_SECRET_VAULT;
            case HISTORY:
                return ROW_PAGE_HISTORY;
            case CALL_RECORDING:
                return ROW_PAGE_CALL_RECORDING;
            default:
                return ROW_PAGE_GROUP_FIRST + page.ordinal() - Page.CHATS.ordinal();
        }
    }

    /** The page opened by hub row {@code rowId}, or null. */
    public static Page pageOfHubRow(int rowId) {
        for (Page page : Page.values()) {
            if (hubRowId(page) == rowId) {
                return page;
            }
        }
        return null;
    }

    /**
     * The page a {@link ZgSettingsSpec} section is listed on; null for the hub (Pro, About, Developer, the hub's own
     * blocks). An unknown section (one added later without a placement) also lands on the hub, so nothing becomes
     * unreachable.
     */
    public static Page pageOfSection(int sectionId) {
        switch (sectionId) {
            case ZgSettingsSpec.SECTION_HIDDEN_CHATS:
                return Page.SECRET_VAULT;
            case ZgSettingsSpec.SECTION_DELETED_MESSAGES:
            case ZgSettingsSpec.SECTION_EDIT_HISTORY:
                return Page.HISTORY;
            case ZgSettingsSpec.SECTION_CALL_RECORDING:
                return Page.CALL_RECORDING;
            case ZgSettingsSpec.SECTION_CHAT_LIST:
            case ZgSettingsSpec.SECTION_FOLDERS:
            case ZgSettingsSpec.SECTION_STORIES:
            case ZgSettingsSpec.SECTION_TOOLS: // Reminders
                return Page.CHATS;
            case ZgSettingsSpec.SECTION_MESSAGES:
            case ZgSettingsSpec.SECTION_COMPOSING:
                return Page.MESSAGES;
            case ZgSettingsSpec.SECTION_CONTEXT_MENU:
                return Page.MESSAGE_MENU;
            case ZgSettingsSpec.SECTION_TABS:
                return Page.TAB_BAR;
            case ZgSettingsSpec.SECTION_PRIVACY_SECURITY:
            case ZgSettingsSpec.SECTION_EXTRA_PRIVACY:
            case ZgSettingsSpec.SECTION_PROFILES:
                return Page.PRIVACY;
            case ZgSettingsSpec.SECTION_TRANSLATION:
            case ZgSettingsSpec.SECTION_VOICE_TO_TEXT:
            case ZgSettingsSpec.SECTION_VOICE_MESSAGES:
                return Page.TRANSLATION;
            case ZgSettingsSpec.SECTION_MEDIA:
            case ZgSettingsSpec.SECTION_PHOTOS:
            case ZgSettingsSpec.SECTION_STICKERS:
            case ZgSettingsSpec.SECTION_ROUND_VIDEOS:
            case ZgSettingsSpec.SECTION_CAMERA:
                return Page.MEDIA;
            case ZgSettingsSpec.SECTION_ACCOUNT_COLORS:
            case ZgSettingsSpec.SECTION_HOLIDAY:
                return Page.COLORS;
            case ZgSettingsSpec.SECTION_AI_ASSISTANT:
            case ZgSettingsSpec.SECTION_CALLS:
            case ZgSettingsSpec.SECTION_ADVANCED:
                return Page.CALLS_ADVANCED;
            default:
                return null;
        }
    }

    /** iOS row key → Android row id. Built once; see {@link #ANDROID_MISSING_KEYS} for the keys without a row. */
    private static final Map<String, Integer> ROW_IDS = new HashMap<>();
    /** iOS keys Android has no row for: the AI assistant (refused by this build on iOS as well) and the iOS
     *  language-settings shortcut. */
    public static final Set<String> ANDROID_MISSING_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "aiAssistantEnabled", "aiAssistantApiKey", "languageSettings"
    )));

    static {
        final Object[] table = {
            // hub
            "pageSecretVault", ROW_PAGE_SECRET_VAULT, "chatLock", ROW_CHAT_LOCK, "protectionRecovery", ROW_PROTECTION_RECOVERY,
            "tasks", ROW_TASKS, "pageHistory", ROW_PAGE_HISTORY, "pageCallRecording", ROW_PAGE_CALL_RECORDING,
            // iOS proLinkAccount is the Telegram Account row of ACCOUNT & DATA; telegramAccount is its Android name.
            // The copy among the Pro rows (ZgSettingsSpec.ROW_PRO_LINK_ACCOUNT) is never shown and has no key.
            "proLinkAccount", ZgSettingsSpec.ROW_DATA_TELEGRAM_ACCOUNT, "telegramAccount", ZgSettingsSpec.ROW_DATA_TELEGRAM_ACCOUNT,
            "dataConsent", ZgSettingsSpec.ROW_DATA_CONSENT,
            "privacyPolicy", ZgSettingsSpec.ROW_DATA_PRIVACY, "proSubscription", ZgSettingsSpec.ROW_PRO_SUBSCRIPTION,
            "proRestorePurchases", 103,
            "showContactsAndCallsTabs", ROW_CONTACTS_AND_CALLS, "showContactsTab", ROW_CONTACTS_AND_CALLS,
            "showCallsTab", ROW_CONTACTS_AND_CALLS,
            // secret vault
            "secretVaultEnabled", 201, "secretVaultUseSecretCode", 203, "secretVaultChangePin", 204,
            // history
            "showDeletedMessages", 3201, "editHistoryEnabled", 1301,
            // call recording
            "callRecordings", 2601, "callRecordingNotifyOtherParty", 2602, "callRecordingSendToSavedMessages", 2603,
            // chats
            "compactChatList", 1001, "compactMessagePreview", 1002, "unlimitedPinnedChats", 1003,
            "disableChatSwipeOptions", 1004, "disableDeleteChatSwipeOption", 1005,
            "foldersAtBottom", 1601, "rememberLastFolder", 1602, "allChatsHidden", 1604, "compactFolderNames", 1605,
            "allChatsTitleLengthOverride", 1606,
            "hideStories", 1701, "disableSwipeToRecordStory", 1702, "showRepostToStory", 1703,
            "unreadReminderEnabled", 2403,
            // messages
            "hideReactions", 1101, "secondsInMessages", 1102, "wideChannelPosts", 1103, "hideChannelBottomButton", 1104,
            "disableSnapDeletionEffect", 1105, "disableScrollToNextChannel", 1106, "disableScrollToNextTopic", 1107,
            "sendWithReturnKey", 1201, "messageDoubleTapActionOutgoingEdit", 1202, "disableSendAsButton", 1203,
            "hideRecordingButton", 1204, "forceEmojiTab", 1205, "defaultEmojisFirst", 1206,
            // message menu
            "contextShowSaveToCloud", 1401, "contextShowSaveMedia", 1402, "contextShowReply", 1403, "contextShowPin", 1404,
            "contextShowSelectFromUser", 1405, "contextShowMessageReplies", 1406, "contextShowHideForwardName", 1407,
            "contextShowReport", 1408, "contextShowRestrict", 1409, "contextShowJson", 1410,
            // tab bar
            "hideTabBar", 1501, "showTabNames", 1502, "tabBarSearchEnabled", 1503, "showTasksTab", 1506, "wideTabBar", 1507,
            // privacy
            "blockForeignUsers", 301, "autoAcceptRequests", 303, "confirmCalls", 304, "hidePhoneInSettings", 401,
            "warnOnStoriesOpen", 403, "showRegDate", 503, "showCreationDate", 505, "showDC", 507, "showProfileId", 508,
            "showViewFirstMessage", 509,
            // translation & voice
            "translationBackend", 701, "quickTranslateButton", 703, "transcriptionBackend", 801, "forceBuiltInMic", 901,
            // media
            "autoDownloadDisabled", 1801, "downloadSpeedBoost", 1802, "uploadSpeedBoost", 1804, "outgoingPhotoQuality", 1902,
            "sendLargePhotos", 1904, "stickerSize", 2002, "stickerTimestamp", 2003, "swipeForVideoPIP", 2101,
            "startTelescopeWithRearCam", 2103, "sendRoundVideoFromGallery", 2104, "disableGalleryCamera", 2201,
            "disableGalleryCameraPreview", 2202,
            // colors
            "accountColorsSaturation", 2302, "nyStyleSnow", 2801, "nyStyleLightning", 2802,
            // calls & advanced
            "enableVoipTcp", 2501, "contentSettings", 2701, "autoProxyEnabled", 2702,
        };
        for (int i = 0; i < table.length; i += 2) {
            ROW_IDS.put((String) table[i], (Integer) table[i + 1]);
        }
        for (Page page : Page.values()) {
            if (page.isGroup) {
                ROW_IDS.put(page.hubRowKey(), hubRowId(page));
            }
        }
    }

    /** The Android row that stands for the iOS key {@code key}; 0 when there is none. */
    public static int rowIdForKey(String key) {
        final Integer id = key != null ? ROW_IDS.get(key) : null;
        return id != null ? id : 0;
    }

    /** Every iOS key with an Android row. */
    public static Set<String> mappedKeys() {
        return Collections.unmodifiableSet(ROW_IDS.keySet());
    }

    /** The section of an Android row id (row ids are section * 100 + n). */
    public static int sectionOfRow(int rowId) {
        return rowId / 100;
    }

    /** The page Android lists the row {@code rowId} on; null for the hub. */
    public static Page pageOfRow(int rowId) {
        return pageOfSection(sectionOfRow(rowId));
    }

    /** Pages whose hub row a deep link (or focus) to {@code key} must open first; null: the row is on the hub. */
    public static Page focusPage(String key) {
        final int id = rowIdForKey(key);
        return id != 0 ? pageOfRow(id) : null;
    }

    /** The group pages in hub order (MORE SETTINGS). */
    public static List<Page> groupPages() {
        final java.util.ArrayList<Page> pages = new java.util.ArrayList<>();
        for (Page page : Page.values()) {
            if (page.isGroup) {
                pages.add(page);
            }
        }
        return pages;
    }
}
