/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.core;

/**
 * Which Zenlygram features this Android build has really ported.
 *
 * <p>Every row of Settings > Zenlygram names the feature it needs (ZgSettingsSpec). A row whose feature is not
 * ported is not shown, so the screen never offers a switch that does nothing (the iOS lesson of the dead
 * Helper Bots row). A debug build can still show those rows greyed out: Settings > Zenlygram > Developer >
 * Preview all rows.
 *
 * <p>To ship a feature: implement it, change its {@code false} below to {@code true}, and give its settings rows
 * a click handler in ZgSettingsSpec if a plain switch is not enough. {@link #owner} is the research spec
 * that describes the port ("swiftgram" = a small inherited Swiftgram option that has no spec of its own yet).
 */
public enum ZgFeatures {

    /** The settings skeleton itself: About and Developer rows. */
    CORE(true, "branding-settings"),

    // Zenlygram Pro: the status screen and the entitlement (org.telegram.zenlygram.pro.ZgPro, free mode only).
    PRO_STATUS(true, "pro-entitlement"),
    PRO_ENTITLEMENT(true, "pro-entitlement"),
    /**
     * The Pro section's own "Link account" row. Hidden while Pro is free, as iOS hides every Pro surface then; the link
     * itself is always reachable from Account &amp; data &gt; Telegram Account (ZgLinkAccountActivity).
     */
    PRO_LINK_ACCOUNT(false, "pro-entitlement"),
    /**
     * The hub's "Restore Purchases" row: the Google Play build while Pro is a subscription
     * (ZgPro.offersRestorePurchases); it opens the Pro screen, where the restore starts by itself.
     */
    PRO_RESTORE_PURCHASES(true, "pro-entitlement"),

    // Hidden & locked chats
    /** Secret Vault: org.telegram.zenlygram.vault (reveal: long-press on the chat-list title; no secret code yet). */
    SECRET_VAULT(true, "secret-vault"),
    /** Chat Lock: org.telegram.zenlygram.chatlock on the shared lock core org.telegram.zenlygram.lock. */
    CHAT_LOCK(true, "chat-lock"),

    // Privacy & security, extra privacy
    /** Hide Foreign Numbers: org.telegram.zenlygram.dialogs (ZgForeignNumbers), applied live. */
    BLOCK_FOREIGN_USERS(true, "extras"),
    /** Auto-Accept Requests: org.telegram.zenlygram.autoaccept (ZgAutoAccept). */
    AUTO_ACCEPT_REQUESTS(true, "extras"),
    /** Call Confirmation: org.telegram.zenlygram.calls.ZgCallConfirmation. */
    CALL_CONFIRMATION(true, "swiftgram-privacy-profile"),
    /** Hide Phone in Settings: org.telegram.zenlygram.privacy.ZgHidePhone. */
    HIDE_PHONE_IN_SETTINGS(true, "swiftgram-privacy-profile"),
    /** Ask Before Viewing (stories): org.telegram.zenlygram.stories.ZgStoryWarning. */
    STORY_VIEW_WARNING(true, "swiftgram-privacy-profile"),

    // Profiles
    /** Show Deleted Messages: org.telegram.zenlygram.history (ZgKeptDeleted). */
    KEPT_DELETED_MESSAGES(true, "edited-deleted-history"),
    // Profile rows (ID, data center, created, registered): org.telegram.zenlygram.profile.ZgProfileInfo
    REGISTRATION_DATE(true, "swiftgram-privacy-profile"),
    /** Also org.telegram.zenlygram.profile.ZgChatCreationDate (one channels.getMessages([1]) per chat and process). */
    CREATION_DATE(true, "swiftgram-privacy-profile"),
    DATA_CENTER(true, "swiftgram-privacy-profile"),
    PROFILE_ID(true, "swiftgram-privacy-profile"),
    /** View First Message in the profile menu: org.telegram.zenlygram.extras (ZgViewFirstMessage). */
    VIEW_FIRST_MESSAGE(true, "extras"),

    // Translation, voice-to-text, voice messages
    /** Translate Messages > Service (Telegram / Google Translate): org.telegram.zenlygram.translate.ZgTranslate. */
    TRANSLATION_BACKEND(true, "swiftgram-translate-voice"),
    /** Quick Translate button next to incoming messages: org.telegram.zenlygram.translate.ZgQuickTranslate. */
    QUICK_TRANSLATE(true, "swiftgram-translate-voice"),
    /** Link to Telegram's "Translate Entire Chat" + no dead-end Premium upsell (ZgTranslate); ships with TRANSLATION_BACKEND. */
    TRANSLATE_ENTIRE_CHAT(true, "swiftgram-translate-voice"),
    /**
     * iOS offers Telegram or Apple speech recognition. Android: Telegram or "On this device" (the phone's on-device
     * SpeechRecognizer, Android 13+): org.telegram.zenlygram.voice.ZgVoiceToText. The section is offered only on phones
     * that have an on-device recognizer; everywhere else Telegram transcription works as upstream.
     */
    TRANSCRIPTION_BACKEND(true, "wp-b-media"),
    /** Force Device Microphone for voice and round video messages: org.telegram.zenlygram.voice.ZgMic. */
    FORCE_BUILTIN_MIC(true, "swiftgram-translate-voice"),

    // Chat list
    /** 56 dp rows, 40 dp avatars: DialogCell geometry hooks, constants in org.telegram.zenlygram.chatlist.ZgChatListLayout. */
    COMPACT_CHAT_LIST(true, "swiftgram-chatlist-chats"),
    /** Telegram's own Chat list view > Two lines (SharedConfig.useThreeLinesLayout). */
    COMPACT_MESSAGE_PREVIEW(true, "swiftgram-chatlist-chats"),
    /** Unlimited Pinned Chats: org.telegram.zenlygram.pins (ZgPins), device-local extra pins. */
    UNLIMITED_PINNED_CHATS(true, "extras"),
    /** org.telegram.zenlygram.chatlist.ZgChatListSwipe (DialogsActivity / TopicsFragment swipe hooks). */
    CHAT_SWIPE_OPTIONS(true, "swiftgram-chatlist-chats"),

    // Chats & messages (org.telegram.zenlygram.chat.ZgChatAppearance)
    HIDE_REACTIONS(true, "swiftgram-chatlist-chats"),
    SECONDS_IN_MESSAGES(true, "swiftgram-chatlist-chats"),
    /** MessageObject / ChatMessageCell width hooks and ChatActivity.getValidGroupedMessage (ZgChatAppearance.isWideChannelPost). */
    WIDE_CHANNEL_POSTS(true, "swiftgram-chatlist-chats"),
    CHANNEL_BOTTOM_BUTTON(true, "swiftgram-chatlist-chats"),
    /** Telegram's own Power Saving > Dust-effect deletion (LiteMode.FLAG_CHAT_THANOS). */
    SNAP_DELETION_EFFECT(true, "swiftgram-chatlist-chats"),
    PULL_TO_NEXT_CHANNEL(true, "swiftgram-chatlist-chats"),
    PULL_TO_NEXT_TOPIC(true, "swiftgram-chatlist-chats"),

    // Composing & input
    /** Edits Telegram's own "send_by_enter" option (Chat Settings > Send by Enter); ZgSettings.sendWithReturnKey is unused. */
    SEND_WITH_RETURN_KEY(true, "swiftgram-input-stickers"),
    /** Double-tap to edit an own message: org.telegram.zenlygram.extras (ZgDoubleTapEdit, ChatActivity). */
    DOUBLE_TAP_TO_EDIT(true, "extras"),
    /** org.telegram.zenlygram.input.ZgInput, hook in ChatActivityEnterView.updateSendAsButton. */
    SEND_AS_BUTTON(true, "swiftgram-input-stickers"),
    /** org.telegram.zenlygram.input.ZgInput, hooks in ChatActivityEnterView.checkSendButton / sendMessage. */
    RECORDING_BUTTON(true, "swiftgram-input-stickers"),
    /** org.telegram.zenlygram.input.ZgInput, hooks in ChatActivityEnterView (emoji button, icon). */
    FORCE_EMOJI_TAB(true, "swiftgram-input-stickers"),
    /**
     * Never ported: Telegram Android already lists the standard emoji right after Recent, for Premium users too
     * (EmojiView.EmojiGridAdapter.updateRows), which is the order this iOS switch produces. Rows 1206 / 1207 stay hidden.
     */
    DEFAULT_EMOJIS_FIRST(false, "swiftgram-input-stickers"),

    // Edit history and the message menu
    /** Save Edit History: org.telegram.zenlygram.history (ZgEditHistory). */
    EDIT_HISTORY(true, "edited-deleted-history"),
    /** Message menu layout and the "Zenlygram" sub-menu: org.telegram.zenlygram.chat.ZgContextMenu. */
    CONTEXT_MENU(true, "swiftgram-input-stickers"),

    // Tabs and folders (org.telegram.zenlygram.chatlist: ZgTabs, ZgFolders)
    /** Hide Tab bar, Show Tab Names, Show Contacts / Calls Tab, Wide Tab bar. */
    TAB_BAR(true, "swiftgram-folders-tabs-stories"),
    /** Telegram Android's tab bar has no search button (search is the field above the chat list); never ported. */
    TAB_BAR_SEARCH_BUTTON(false, "swiftgram-folders-tabs-stories"),
    /** Tasks board, lists, deadlines, Tasks tab: org.telegram.zenlygram.tasks (ZgTasks). */
    TASKS(true, "tasks"),
    FOLDERS_AT_BOTTOM(true, "swiftgram-folders-tabs-stories"),
    REMEMBER_LAST_FOLDER(true, "swiftgram-folders-tabs-stories"),
    ALL_CHATS_HIDDEN(true, "swiftgram-folders-tabs-stories"),
    COMPACT_FOLDER_NAMES(true, "swiftgram-folders-tabs-stories"),
    ALL_CHATS_TITLE(true, "swiftgram-folders-tabs-stories"),

    // Stories (org.telegram.zenlygram.chatlist.ZgStories)
    HIDE_STORIES(true, "swiftgram-folders-tabs-stories"),
    /** Telegram Android 13.2.1 has no chat-list swipe into the story camera; the row would do nothing. */
    STORY_SWIPE_TO_RECORD(false, "swiftgram-folders-tabs-stories"),
    REPOST_TO_STORY(true, "swiftgram-folders-tabs-stories"),

    // Media & downloads, photos, stickers, round videos, camera
    /** Disable Auto-Download: org.telegram.zenlygram.download (ZgAutoDownload), Telegram's presets of every account. */
    DISABLE_AUTO_DOWNLOAD(true, "extras"),
    /** Download / Upload Boost: org.telegram.zenlygram.media.ZgNetworkBoost (FileLoadOperation, FileUploadOperation). */
    DOWNLOAD_BOOST(true, "swiftgram-media-network-misc"),
    UPLOAD_BOOST(true, "swiftgram-media-network-misc"),
    /** Photo quality slider, Send HD Photos: org.telegram.zenlygram.media.ZgPhotoQuality. */
    PHOTO_QUALITY(true, "swiftgram-media-network-misc"),
    SEND_LARGE_PHOTOS(true, "swiftgram-media-network-misc"),
    /** org.telegram.zenlygram.chat.ZgStickers, hook in ChatMessageCell.setMessageContent (20-100 %). */
    STICKER_SIZE(true, "swiftgram-input-stickers"),
    /** org.telegram.zenlygram.chat.ZgStickers, hook in ChatMessageCell.drawTime. */
    STICKER_TIMESTAMP(true, "swiftgram-input-stickers"),
    /** Video PIP with Swipe, rear camera for round videos, gallery camera: org.telegram.zenlygram.media.ZgMediaOptions. */
    VIDEO_PIP_SWIPE(true, "swiftgram-media-network-misc"),
    ROUND_VIDEO_REAR_CAMERA(true, "swiftgram-media-network-misc"),
    /** Send as Round Video from the attach menu: org.telegram.zenlygram.extras (ZgRoundVideoFromGallery). */
    ROUND_VIDEO_FROM_GALLERY(true, "extras"),
    /** Save to Gallery on a round video, branded like iOS SGRoundVideoBranding: org.telegram.zenlygram.media.ZgRoundVideoExport. */
    ROUND_VIDEO_EXPORT(true, "wp-b-media"),
    GALLERY_CAMERA(true, "swiftgram-media-network-misc"),

    // Account colours, tools, calls
    /** Account colours saturation: org.telegram.zenlygram.appearance.ZgAccountColors. */
    ACCOUNT_COLORS(true, "swiftgram-media-network-misc"),
    /** Unread Message Reminder: org.telegram.zenlygram.reminder (ZgUnreadReminder). */
    UNREAD_REMINDER(true, "extras"),
    /** Telegram's own "Force TCP" for calls (org.telegram.zenlygram.settings.ZgTelegramSettings). */
    VOIP_FORCE_TCP(true, "swiftgram-media-network-misc"),
    /**
     * Call recording of Telegram 1:1 calls (both sides, from the app's own WebRTC audio): org.telegram.zenlygram.callrecording.
     * Record button + consent on the call screen, REC badge + ongoing-call notification text, recordings list,
     * Saved Messages delivery.
     */
    CALL_RECORDING(true, "wp-b-media"),

    // Advanced, holiday effects
    /** Shortcut to Telegram's own "Show 18+ Content", only while the server allows changing it (ZgTelegramSettings). */
    CONTENT_SETTINGS(true, "swiftgram-media-network-misc"),
    /** Hidden like iOS: there is no bundled proxy pool (iOS ships an empty proxies.txt), so it could never work. */
    AUTO_PROXY(false, "extras"),
    /** Snow / Lightning, 20 December - 10 January: org.telegram.zenlygram.appearance.ZgHolidayEffects. */
    HOLIDAY_EFFECTS(true, "swiftgram-media-network-misc");

    private final boolean ported;
    /** The research spec that owns this feature. */
    public final String owner;

    ZgFeatures(boolean ported, String owner) {
        this.ported = ported;
        this.owner = owner;
    }

    public boolean isPorted() {
        return ported;
    }
}
