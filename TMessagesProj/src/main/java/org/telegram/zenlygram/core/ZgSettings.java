/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Zenlygram's device-wide settings: the private SharedPreferences file {@value #PREFS_NAME}.
 *
 * <p>Every key and default is the one iOS uses in SGSimpleSettings, so behaviour and docs match one to one. iOS keeps
 * a few keys in the app-group suite for its extensions; Android runs notification code in-process, so one file is
 * enough. Settings that iOS keeps in Telegram's own stores (Contacts / Calls / Tasks tab, folders at the bottom,
 * VoIP over TCP, media auto-download) are not here: the feature that ports them writes Telegram's Android setting,
 * unless Android has no such setting (showContactsTab, foldersTabAtBottom).
 *
 * <p>Typed accessors: {@code ZgSettings.hideReactions.get()} / {@code .set(true)}. Values are read once and cached;
 * writes use apply(). Keep secrets out of this file (PINs, vault membership, lock state): it is plain preferences.
 *
 * <p>NEW badges are kept in the same file as {@code newBadgeFirstSeen.<key>} (epoch seconds) and
 * {@code newBadgeInteracted.<key>}, like iOS SGNewBadgeTracker.
 */
public final class ZgSettings {

    public static final String PREFS_NAME = "zenlygram";

    /** Bumped when stored values need a migration (see {@link #migrate}). */
    private static final int SETTINGS_VERSION = 2;
    private static final String KEY_SETTINGS_VERSION = "zg_settings_version";

    private static final String KEY_NEW_BADGE_FIRST_SEEN = "newBadgeFirstSeen.";
    private static final String KEY_NEW_BADGE_INTERACTED = "newBadgeInteracted.";
    /** A NEW badge disappears 30 days after it was first shown, even without any interaction. */
    public static final long NEW_BADGE_TTL_SECONDS = 30L * 24L * 60L * 60L;

    private static final ArrayList<Setting> ALL = new ArrayList<>();
    private static volatile SharedPreferences preferences;

    private ZgSettings() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Values of the string settings (iOS raw values)
    // ---------------------------------------------------------------------------------------------------------

    public static final class TranslationBackend {
        public static final String DEFAULT = "default", GOOGLE = "gtranslate", SYSTEM = "system";
        /** Android only: Telegram without its own server-chosen Google fallbacks (the iOS "default" behaviour, #60). */
        public static final String TELEGRAM_ONLY = "telegramOnly";
        private TranslationBackend() {}
    }

    public static final class TranscriptionBackend {
        public static final String DEFAULT = "default", APPLE = "apple";
        private TranscriptionBackend() {}
    }

    public static final class ChatListLines {
        public static final String ONE = "1", TWO = "2", THREE = "3";
        private ChatListLines() {}
    }

    public static final class DoubleTapAction {
        public static final String DEFAULT = "default", NONE = "none", EDIT = "edit";
        private DoubleTapAction() {}
    }

    public static final class AllChatsTitle {
        public static final String NONE = "none", SHORT = "short", LONG = "long";
        private AllChatsTitle() {}
    }

    public static final class DownloadBoost {
        public static final String NONE = "none", MEDIUM = "medium", MAXIMUM = "maximum";
        private DownloadBoost() {}
    }

    public static final class VideoPipSwipe {
        public static final String UP = "up", DOWN = "down", NONE = "none";
        private VideoPipSwipe() {}
    }

    public static final class HolidayStyle {
        public static final String DEFAULT = "default", SNOW = "snow", LIGHTNING = "lightning";
        private HolidayStyle() {}
    }

    /** Debug builds: the Zenlygram Pro state the Developer section previews ({@link #devProPreview}). */
    public static final class ProPreview {
        public static final String OFF = "off", GRACE = "grace", INACTIVE = "inactive", LAPSED = "lapsed",
            RECONNECT = "reconnect", ACTIVE = "active";
        private ProPreview() {}
    }

    // ---------------------------------------------------------------------------------------------------------
    // Settings (iOS SGSimpleSettings names and defaults)
    // ---------------------------------------------------------------------------------------------------------

    // Hidden & locked chats (the vault feature owns the PIN flows; these are only its switches)
    public static final BoolSetting secretVaultEnabled = bool("secretVaultEnabled", false);
    public static final BoolSetting secretVaultUseSecretCode = bool("secretVaultUseSecretCode", false);

    // Privacy & security, extra privacy
    public static final BoolSetting blockForeignUsers = bool("blockForeignUsers", false);
    /** The user's own phone number, cached for blockForeignUsers. */
    public static final StringSetting ownPhoneNumber = string("ownPhoneNumber", "");
    public static final BoolSetting autoAcceptRequests = bool("autoAcceptRequests", false);
    public static final BoolSetting confirmCalls = bool("confirmCalls", true);
    public static final BoolSetting hidePhoneInSettings = bool("hidePhoneInSettings", true);
    public static final BoolSetting warnOnStoriesOpen = bool("warnOnStoriesOpen", false);

    // Profiles, kept history
    public static final BoolSetting showDeletedMessages = bool("showDeletedMessages", true);
    public static final BoolSetting editHistoryEnabled = bool("editHistoryEnabled", true);
    /**
     * Copy of the Pro decision: true = new history capture is blocked (paid mode without access). Written only by
     * org.telegram.zenlygram.pro.ZgPro (= !ZgPro.canCaptureRetainedHistory()); always false while Pro is free.
     */
    public static final BoolSetting editHistoryCaptureBlocked = bool("editHistoryCaptureBlocked", false);
    /** Saved purge requests (Unix seconds) written when a retention feature is turned off. */
    public static final LongSetting keptDeletedPurgeBefore = longValue("keptDeletedPurgeBefore", 0L);
    public static final LongSetting editHistoryPurgeBefore = longValue("editHistoryPurgeBefore", 0L);
    public static final BoolSetting showRegDate = bool("showRegDate", true);
    public static final BoolSetting showCreationDate = bool("showCreationDate", true);
    public static final BoolSetting showDC = bool("showDC", false);
    public static final BoolSetting showProfileId = bool("showProfileId", true);
    public static final BoolSetting showViewFirstMessage = bool("showViewFirstMessage", false);

    // Translation, voice-to-text, voice messages
    public static final ChoiceSetting translationBackend = choice("translationBackend", TranslationBackend.DEFAULT,
            TranslationBackend.DEFAULT, TranslationBackend.GOOGLE, TranslationBackend.SYSTEM, TranslationBackend.TELEGRAM_ONLY);
    public static final BoolSetting quickTranslateButton = bool("quickTranslateButton", false);
    public static final ChoiceSetting transcriptionBackend = choice("transcriptionBackend", TranscriptionBackend.DEFAULT,
            TranscriptionBackend.DEFAULT, TranscriptionBackend.APPLE);
    public static final BoolSetting forceBuiltInMic = bool("forceBuiltInMic", false);
    /**
     * Android only: turning forceBuiltInMic on switched Telegram's "Microphone for voice messages" from Headset to
     * Built-in, so turning it off switches it back (org.telegram.zenlygram.voice.ZgMic).
     */
    public static final BoolSetting forceBuiltInMicRestoreSco = bool("zgForceBuiltInMicRestoreSco", false);

    // Chat list
    public static final BoolSetting compactChatList = bool("compactChatList", false);
    /** Message preview lines; the "Compact Message Preview" switch writes "1" (on) or "3" (off). */
    public static final ChoiceSetting chatListLines = choice("chatListLines", ChatListLines.THREE,
            ChatListLines.ONE, ChatListLines.TWO, ChatListLines.THREE);
    public static final BoolSetting unlimitedPinnedChats = bool("unlimitedPinnedChats", false);
    public static final BoolSetting disableChatSwipeOptions = bool("disableChatSwipeOptions", false);
    public static final BoolSetting disableDeleteChatSwipeOption = bool("disableDeleteChatSwipeOption", false);

    // Chats & messages
    public static final BoolSetting hideReactions = bool("hideReactions", false);
    public static final BoolSetting secondsInMessages = bool("secondsInMessages", false);
    public static final BoolSetting wideChannelPosts = bool("wideChannelPosts", false);
    public static final BoolSetting hideChannelBottomButton = bool("hideChannelBottomButton", false);
    public static final BoolSetting disableSnapDeletionEffect = bool("disableSnapDeletionEffect", false);
    public static final BoolSetting disableScrollToNextChannel = bool("disableScrollToNextChannel", false);
    public static final BoolSetting disableScrollToNextTopic = bool("disableScrollToNextTopic", false);

    // Composing & input
    /**
     * Registered for iOS key parity only; never read on Android. The "Send with Enter key" row edits Telegram's own
     * "send_by_enter" option (mainconfig, Chat Settings > Send by Enter) instead.
     */
    public static final BoolSetting sendWithReturnKey = bool("sendWithReturnKey", false);
    /** The "Double-tap to edit message" switch writes "edit" (on) or "default" (off). */
    public static final ChoiceSetting messageDoubleTapActionOutgoing = choice("messageDoubleTapActionOutgoing", DoubleTapAction.DEFAULT,
            DoubleTapAction.DEFAULT, DoubleTapAction.NONE, DoubleTapAction.EDIT);
    public static final BoolSetting disableSendAsButton = bool("disableSendAsButton", false);
    public static final BoolSetting hideRecordingButton = bool("hideRecordingButton", false);
    public static final BoolSetting forceEmojiTab = bool("forceEmojiTab", false);
    /** Registered for iOS key parity only; never read (ZgFeatures.DEFAULT_EMOJIS_FIRST is not applicable on Android). */
    public static final BoolSetting defaultEmojisFirst = bool("defaultEmojisFirst", false);

    // Message menu: true keeps the item at the top level, false moves it into the "Zenlygram" sub-menu
    public static final BoolSetting contextShowSaveToCloud = bool("contextShowSaveToCloud", true);
    public static final BoolSetting contextShowSaveMedia = bool("contextShowSaveMedia", true);
    public static final BoolSetting contextShowReply = bool("contextShowReply", true);
    public static final BoolSetting contextShowPin = bool("contextShowPin", true);
    public static final BoolSetting contextShowSelectFromUser = bool("contextShowSelectFromUser", true);
    public static final BoolSetting contextShowMessageReplies = bool("contextShowMessageReplies", true);
    public static final BoolSetting contextShowHideForwardName = bool("contextShowHideForwardName", true);
    public static final BoolSetting contextShowReport = bool("contextShowReport", true);
    public static final BoolSetting contextShowRestrict = bool("contextShowRestrict", true);
    public static final BoolSetting contextShowJson = bool("contextShowJson", false);
    /**
     * Android only (iOS has no such switch): the five Swiftgram items (Save to Cloud, Hide senders' names, Select from
     * Author, Restrict, JSON) and the "Zenlygram" sub-menu of the message menu; the ten switches above apply only while
     * it is on. Off (the default): none of them. Edit History, Add to Tasks and the items of kept deleted copies and
     * wide channel posts follow their own switches.
     */
    public static final BoolSetting contextMenuZenlygramItems = bool("contextMenuZenlygramItems", false);

    // Tabs
    public static final BoolSetting hideTabBar = bool("hideTabBar", false);
    public static final BoolSetting showTabNames = bool("showTabNames", true);
    public static final BoolSetting tabBarSearchEnabled = bool("tabBarSearchEnabled", true);
    public static final BoolSetting wideTabBar = bool("wideTabBar", false);
    /** Android only: iOS keeps it in CallListSettings.showContactsTab; Telegram Android has no such setting. */
    public static final BoolSetting showContactsTab = bool("showContactsTab", true);
    /**
     * iOS lenta-v6 CallListSettings.showContactsAndCallsTabs: one switch for the Contacts and Calls tabs, off by default
     * (both then open from Telegram's Settings). Applied by {@link org.telegram.zenlygram.chatlist.ZgTabs}, which also
     * keeps Telegram's per-account UserConfig.showCallsTab in step with it.
     */
    public static final BoolSetting showContactsAndCallsTabs = bool("showContactsAndCallsTabs", false);
    /** iOS SGSimpleSettings.contactsAndCallsTabsMigrated: the one-time move of an existing install to the new default. */
    public static final BoolSetting contactsAndCallsTabsMigrated = bool("contactsAndCallsTabsMigrated", false);

    // Folders
    /** Android only: iOS keeps it in ExperimentalUISettings.foldersTabAtBottom; Telegram Android has no such setting. */
    public static final BoolSetting foldersTabAtBottom = bool("foldersTabAtBottom", false);
    public static final BoolSetting rememberLastFolder = bool("rememberLastFolder", false);
    public static final BoolSetting allChatsHidden = bool("allChatsHidden", false);
    public static final BoolSetting compactFolderNames = bool("compactFolderNames", false);
    public static final ChoiceSetting allChatsTitleLengthOverride = choice("allChatsTitleLengthOverride", AllChatsTitle.NONE,
            AllChatsTitle.NONE, AllChatsTitle.SHORT, AllChatsTitle.LONG);

    // Stories
    public static final BoolSetting hideStories = bool("hideStories", false);
    public static final BoolSetting disableSwipeToRecordStory = bool("disableSwipeToRecordStory", false);
    public static final BoolSetting showRepostToStoryV2 = bool("showRepostToStoryV2", true);

    // Media & downloads
    public static final BoolSetting autoDownloadDisabled = bool("autoDownloadDisabled", false);
    public static final ChoiceSetting downloadSpeedBoost = choice("downloadSpeedBoost", DownloadBoost.NONE,
            DownloadBoost.NONE, DownloadBoost.MEDIUM, DownloadBoost.MAXIMUM);
    public static final BoolSetting uploadSpeedBoost = bool("uploadSpeedBoost", false);

    // Photos, stickers
    /**
     * JPEG quality of sent photos. Android default 80 (iOS 70): Telegram Android's own standard quality, so the default
     * keeps every photo as sharp as stock Telegram (org.telegram.zenlygram.media.ZgPhotoQuality maps the value). The
     * slider starts at 10, ZgPhotoQuality's floor, so the percentage shown is always the real quality (iOS: 0-100).
     */
    public static final IntSetting outgoingPhotoQuality = integer("outgoingPhotoQuality", 80, 10, 100);
    public static final BoolSetting sendLargePhotos = bool("sendLargePhotos", false);
    /** Percent of Telegram's sticker size. iOS allows 0; Android starts at 20 (ZgStickers.MIN_SIZE_PERCENT). */
    public static final IntSetting stickerSize = integer("stickerSize", 100, 20, 100);
    public static final BoolSetting stickerTimestamp = bool("stickerTimestamp", true);

    // Round videos, camera & gallery
    /** The "Video PIP with Swipe" switch writes "up" (on) or "none" (off). */
    public static final ChoiceSetting videoPIPSwipeDirection = choice("videoPIPSwipeDirection", VideoPipSwipe.UP,
            VideoPipSwipe.UP, VideoPipSwipe.DOWN, VideoPipSwipe.NONE);
    public static final BoolSetting startTelescopeWithRearCam = bool("startTelescopeWithRearCam", false);
    public static final BoolSetting sendRoundVideoFromGallery = bool("sendRoundVideoFromGallery", true);
    public static final BoolSetting disableGalleryCamera = bool("disableGalleryCamera", false);
    public static final BoolSetting disableGalleryCameraPreview = bool("disableGalleryCameraPreview", false);

    // Account colours
    /** Saturation of name and reply colours; 0 turns account colours off. */
    public static final IntSetting accountColorsSaturation = percent("accountColorsSaturation", 100);

    // Tools
    public static final BoolSetting unreadReminderEnabled = bool("unreadReminderEnabled", false);
    public static final IntSetting unreadReminderMinutes = integer("unreadReminderMinutes", 5, 1, 24 * 60);

    // Call recording
    public static final BoolSetting callRecordingConsentAccepted = bool("callRecordingConsentAccepted", false);
    public static final BoolSetting callRecordingNotifyOtherParty = bool("callRecordingNotifyOtherParty", false);
    public static final BoolSetting callRecordingSendToSavedMessages = bool("callRecordingSendToSavedMessages", false);

    // Advanced
    public static final BoolSetting autoProxyEnabled = bool("autoProxyEnabled", false);

    // Holiday effects
    /** Whether holiday effects may be offered at all (iOS: remote config). */
    public static final BoolSetting canUseNY = bool("canUseNY", false);
    public static final ChoiceSetting nyStyle = choice("nyStyle", HolidayStyle.DEFAULT,
            HolidayStyle.DEFAULT, HolidayStyle.SNOW, HolidayStyle.LIGHTNING);

    // Android only
    /** Debug builds: Settings > Zenlygram also shows, greyed out, the rows of features that are not ported yet. */
    public static final BoolSetting devPreviewAllRows = bool("zgDevPreviewAllRows", false);
    /**
     * Debug builds: the Developer section of Settings > Zenlygram is shown (unlocked by tapping the version row seven
     * times). It also shows while Preview all rows is on, so that switch can always be turned off again.
     */
    public static final BoolSetting devSectionUnlocked = bool("zgDevSectionUnlocked", false);

    /**
     * Debug builds: Zenlygram Pro behaves as if the signed policy were paid and the account in this state, so the
     * paid screens and the create gate can be looked at before the owner turns paid mode on. Nothing is sent or
     * stored about it elsewhere, and a release build never reads it ({@link #proPreview()}).
     */
    public static final ChoiceSetting devProPreview = choice("zgDevProPreview", ProPreview.OFF,
            ProPreview.OFF, ProPreview.GRACE, ProPreview.INACTIVE, ProPreview.LAPSED, ProPreview.RECONNECT, ProPreview.ACTIVE);

    public static boolean isPreviewAllRows() {
        return BuildVars.DEBUG_VERSION && devPreviewAllRows.get();
    }

    /** The previewed Pro state of a debug build; always {@link ProPreview#OFF} in a release build. */
    public static String proPreview() {
        return BuildVars.DEBUG_VERSION ? devProPreview.get() : ProPreview.OFF;
    }

    /** Whether Settings > Zenlygram lists its Developer section (debug builds only). */
    public static boolean isDeveloperSectionShown() {
        return BuildVars.DEBUG_VERSION && (devSectionUnlocked.get() || devPreviewAllRows.get());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Last folder per account (iOS lastAccountFolders [accountPeerId: filter id])
    // ---------------------------------------------------------------------------------------------------------

    private static final String KEY_LAST_ACCOUNT_FOLDER = "lastAccountFolders.";
    /** The saved folder is "All Chats" (iOS -1). */
    public static final int LAST_FOLDER_ALL_CHATS = -1;
    /** Nothing saved for this account yet. */
    public static final int LAST_FOLDER_NONE = Integer.MIN_VALUE;

    /**
     * The folder the chat list of Telegram user {@code clientUserId} showed last: a DialogFilter id,
     * {@link #LAST_FOLDER_ALL_CHATS} or {@link #LAST_FOLDER_NONE}. Keyed by user id, not account slot, because Android
     * reuses slots.
     */
    public static int getLastFolder(long clientUserId) {
        try {
            return getPreferences().getInt(KEY_LAST_ACCOUNT_FOLDER + clientUserId, LAST_FOLDER_NONE);
        } catch (ClassCastException e) {
            return LAST_FOLDER_NONE;
        }
    }

    public static void setLastFolder(long clientUserId, int folderId) {
        getPreferences().edit().putInt(KEY_LAST_ACCOUNT_FOLDER + clientUserId, folderId).apply();
    }

    /** Every setting above, in declaration order. */
    public static List<Setting> getAll() {
        return Collections.unmodifiableList(ALL);
    }

    // ---------------------------------------------------------------------------------------------------------
    // NEW badges (iOS SGNewBadgeTracker)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether the NEW pill of {@code key} is still shown. The first call stamps the first-seen time, so call it only
     * for a row that is really on screen. The pill goes away after {@link #retireNewBadge} or 30 days.
     */
    public static synchronized boolean isNewBadgeVisible(String key) {
        if (TextUtils.isEmpty(key)) {
            return false;
        }
        final SharedPreferences prefs = getPreferences();
        try {
            if (prefs.getBoolean(KEY_NEW_BADGE_INTERACTED + key, false)) {
                return false;
            }
            final long now = System.currentTimeMillis() / 1000L;
            final long firstSeen = prefs.getLong(KEY_NEW_BADGE_FIRST_SEEN + key, 0L);
            if (firstSeen <= 0L) {
                prefs.edit().putLong(KEY_NEW_BADGE_FIRST_SEEN + key, now).apply();
                return true;
            }
            return now - firstSeen < NEW_BADGE_TTL_SECONDS;
        } catch (ClassCastException e) {
            return false;
        }
    }

    /** The row was toggled or opened: its NEW pill is gone for good. */
    public static synchronized void retireNewBadge(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        final SharedPreferences prefs = getPreferences();
        boolean interacted;
        try {
            interacted = prefs.getBoolean(KEY_NEW_BADGE_INTERACTED + key, false);
        } catch (ClassCastException e) {
            interacted = false;
        }
        if (!interacted) {
            prefs.edit().putBoolean(KEY_NEW_BADGE_INTERACTED + key, true).apply();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------------------------

    public static SharedPreferences getPreferences() {
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            synchronized (ZgSettings.class) {
                prefs = preferences;
                if (prefs == null) {
                    prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    migrate(prefs);
                    preferences = prefs;
                }
            }
        }
        return prefs;
    }

    /**
     * Blocks until every pending apply() of this file is on disk. Call it before the process is killed on purpose
     * (ZgRestart), or a switch the user just changed could be lost.
     */
    public static void flush() {
        getPreferences().edit().commit();
    }

    private static void migrate(SharedPreferences prefs) {
        int version;
        try {
            version = prefs.getInt(KEY_SETTINGS_VERSION, 0);
        } catch (ClassCastException e) {
            version = 0;
        }
        if (version < SETTINGS_VERSION) {
            // Version 1 is the first Android release. The one-off iOS migrations (showRepostToStory -> V2,
            // compactMessagePreview -> chatListLines, captureDefaultsOnV1) are not needed on a fresh Android
            // install: the defaults above are already the final ones.
            final SharedPreferences.Editor editor = prefs.edit();
            if (version < 2) {
                // Version 2 (Phase 2): every row of a feature in the build is live, so Preview all rows, which test
                // builds left switchable at the bottom of the screen, starts off again: rows of features that are
                // not in the build must not show greyed out on the user's phone.
                editor.remove("zgDevPreviewAllRows"); // devPreviewAllRows (a literal: no dependence on field order)
            }
            editor.putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION).apply();
        }
    }

    private static BoolSetting bool(String key, boolean defaultValue) {
        return register(new BoolSetting(key, defaultValue));
    }

    private static IntSetting integer(String key, int defaultValue, int min, int max) {
        return register(new IntSetting(key, defaultValue, min, max));
    }

    private static IntSetting percent(String key, int defaultValue) {
        return integer(key, defaultValue, 0, 100);
    }

    private static LongSetting longValue(String key, long defaultValue) {
        return register(new LongSetting(key, defaultValue));
    }

    private static StringSetting string(String key, String defaultValue) {
        return register(new StringSetting(key, defaultValue));
    }

    private static ChoiceSetting choice(String key, String defaultValue, String... values) {
        return register(new ChoiceSetting(key, defaultValue, values));
    }

    private static <T extends Setting> T register(T setting) {
        ALL.add(setting);
        return setting;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Typed settings
    // ---------------------------------------------------------------------------------------------------------

    public abstract static class Setting {
        /** The preferences key, identical to the iOS SGSimpleSettings key. */
        public final String key;

        Setting(String key) {
            this.key = key;
        }

        /** Whether a value was ever written (false = the default is in use). */
        public boolean isSet() {
            return getPreferences().contains(key);
        }

        /** Forgets the stored value, so the default applies again. */
        public abstract void reset();
    }

    public static final class BoolSetting extends Setting {
        public final boolean defaultValue;
        private volatile boolean loaded;
        private volatile boolean value;

        BoolSetting(String key, boolean defaultValue) {
            super(key);
            this.defaultValue = defaultValue;
        }

        public boolean get() {
            if (!loaded) {
                synchronized (this) {
                    if (!loaded) {
                        boolean v;
                        try {
                            v = getPreferences().getBoolean(key, defaultValue);
                        } catch (ClassCastException e) {
                            v = defaultValue;
                        }
                        value = v;
                        loaded = true;
                    }
                }
            }
            return value;
        }

        public synchronized void set(boolean newValue) {
            value = newValue;
            loaded = true;
            getPreferences().edit().putBoolean(key, newValue).apply();
        }

        /** Flips the value and returns the new one. */
        public synchronized boolean toggle() {
            final boolean newValue = !get();
            set(newValue);
            return newValue;
        }

        @Override
        public synchronized void reset() {
            value = defaultValue;
            loaded = true;
            getPreferences().edit().remove(key).apply();
        }
    }

    public static final class IntSetting extends Setting {
        public final int defaultValue;
        public final int min;
        public final int max;
        private volatile boolean loaded;
        private volatile int value;

        IntSetting(String key, int defaultValue, int min, int max) {
            super(key);
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
        }

        public int get() {
            if (!loaded) {
                synchronized (this) {
                    if (!loaded) {
                        int v;
                        try {
                            v = getPreferences().getInt(key, defaultValue);
                        } catch (ClassCastException e) {
                            v = defaultValue;
                        }
                        value = clamp(v);
                        loaded = true;
                    }
                }
            }
            return value;
        }

        /** Stores the value, clamped to [min, max]. */
        public synchronized void set(int newValue) {
            newValue = clamp(newValue);
            value = newValue;
            loaded = true;
            getPreferences().edit().putInt(key, newValue).apply();
        }

        @Override
        public synchronized void reset() {
            value = defaultValue;
            loaded = true;
            getPreferences().edit().remove(key).apply();
        }

        private int clamp(int v) {
            return Math.max(min, Math.min(max, v));
        }
    }

    public static final class LongSetting extends Setting {
        public final long defaultValue;
        private volatile boolean loaded;
        private volatile long value;

        LongSetting(String key, long defaultValue) {
            super(key);
            this.defaultValue = defaultValue;
        }

        public long get() {
            if (!loaded) {
                synchronized (this) {
                    if (!loaded) {
                        long v;
                        try {
                            v = getPreferences().getLong(key, defaultValue);
                        } catch (ClassCastException e) {
                            v = defaultValue;
                        }
                        value = v;
                        loaded = true;
                    }
                }
            }
            return value;
        }

        public synchronized void set(long newValue) {
            value = newValue;
            loaded = true;
            getPreferences().edit().putLong(key, newValue).apply();
        }

        @Override
        public synchronized void reset() {
            value = defaultValue;
            loaded = true;
            getPreferences().edit().remove(key).apply();
        }
    }

    public static class StringSetting extends Setting {
        public final String defaultValue;
        private volatile boolean loaded;
        private volatile String value;

        StringSetting(String key, String defaultValue) {
            super(key);
            this.defaultValue = defaultValue;
        }

        public String get() {
            if (!loaded) {
                synchronized (this) {
                    if (!loaded) {
                        String v;
                        try {
                            v = getPreferences().getString(key, defaultValue);
                        } catch (ClassCastException e) {
                            v = defaultValue;
                        }
                        value = normalize(v);
                        loaded = true;
                    }
                }
            }
            return value;
        }

        public synchronized void set(String newValue) {
            newValue = validate(newValue);
            value = newValue;
            loaded = true;
            getPreferences().edit().putString(key, newValue).apply();
        }

        @Override
        public synchronized void reset() {
            value = defaultValue;
            loaded = true;
            getPreferences().edit().remove(key).apply();
        }

        /** Maps a stored value to the value get() returns. */
        String normalize(String stored) {
            return stored != null ? stored : defaultValue;
        }

        /** Checks a value before it is stored. */
        String validate(String newValue) {
            return newValue != null ? newValue : defaultValue;
        }
    }

    /** A string setting limited to a fixed set of values (an iOS String-backed enum). */
    public static final class ChoiceSetting extends StringSetting {
        private final String[] values;

        ChoiceSetting(String key, String defaultValue, String... values) {
            super(key, defaultValue);
            this.values = values;
        }

        /** The allowed values, in the order iOS lists them. */
        public String[] getValues() {
            return values.clone();
        }

        public boolean is(String value) {
            return TextUtils.equals(get(), value);
        }

        @Override
        String normalize(String stored) {
            return isAllowed(stored) ? stored : defaultValue;
        }

        @Override
        String validate(String newValue) {
            if (!isAllowed(newValue)) {
                throw new IllegalArgumentException(key + " does not accept '" + newValue + "'");
            }
            return newValue;
        }

        private boolean isAllowed(String value) {
            if (value == null) {
                return false;
            }
            for (String v : values) {
                if (v.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
