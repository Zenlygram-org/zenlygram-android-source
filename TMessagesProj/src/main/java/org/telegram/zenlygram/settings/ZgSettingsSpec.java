/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.zenlygram.settings.ZgRow.bool;
import static org.telegram.zenlygram.settings.ZgRow.choiceIs;
import static org.telegram.zenlygram.settings.ZgRow.concat;
import static org.telegram.zenlygram.settings.ZgRow.format;
import static org.telegram.zenlygram.settings.ZgRow.inverted;
import static org.telegram.zenlygram.settings.ZgRow.literal;
import static org.telegram.zenlygram.settings.ZgRow.res;
import static org.telegram.zenlygram.settings.ZgRow.telegramGlobalBool;
import static org.telegram.zenlygram.settings.ZgRow.telegramOwned;

import android.graphics.Color;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BatteryDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ThanosEffect;
import org.telegram.ui.LanguageSelectActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.zenlygram.appearance.ZgAccountColors;
import org.telegram.zenlygram.appearance.ZgHolidayEffects;
import org.telegram.zenlygram.chat.ZgChatAppearance;
import org.telegram.zenlygram.chatlist.ZgChatListLayout;
import org.telegram.zenlygram.chatlist.ZgChatListSwipe;
import org.telegram.zenlygram.cloud.ZgBackend;
import org.telegram.zenlygram.core.ZgConfig;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.core.ZgUiEvents;
import org.telegram.zenlygram.chatlist.ZgTabs;
import org.telegram.zenlygram.history.ZgHistorySettingsFlow;
import org.telegram.zenlygram.pro.ZgLinkAccountActivity;
import org.telegram.zenlygram.pro.ZgPro;
import org.telegram.zenlygram.settings.ZgSettingsIcons.Tile;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.translate.ZgTranslate;
import org.telegram.zenlygram.translate.ZgTranslateSettings;
import org.telegram.zenlygram.voice.ZgMic;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Everything Settings > Zenlygram can show, in the order of iOS SGControllerEntries (SGSettingsController.swift).
 *
 * <p>Each row has a fixed id (section * 100 + n; ids of rows that iOS has and Android does not stay reserved), its
 * texts (strings_zenlygram.xml, a few Telegram strings), the iOS SF Symbol and the msg_* glyph + tile that stand in
 * for it, the NEW-badge key, the restart flag, enabled / visibility conditions, and the {@link ZgFeatures} it needs.
 * A row whose feature is not ported is hidden (debug builds can preview it greyed out).
 *
 * <p>A feature joins the screen by setting its ZgFeatures entry to ported. A plain switch then already works (it
 * writes its ZgSettings key). Rows with a flow of their own (PIN set-up, confirmation before deleting kept history,
 * Telegram-owned settings, screens to open) also need an {@code .action(...)} here.
 *
 * <p>Android differences from iOS: section headers are sentence case (ZG_Header_*); the context menu notice is
 * the section footer (a notice right under the header would leave the header alone in its own card); System
 * translation and Apple speech recognition are not offered; the AI assistant section is not ported (this build
 * refuses it, like iOS); the vault / Chat Lock recovery rows and the account colour previews come with their
 * features.
 */
final class ZgSettingsSpec {

    private ZgSettingsSpec() {
    }

    // Section ids, in display order. Row ids are section * 100 + n.
    static final int SECTION_PRO = 1;
    static final int SECTION_HIDDEN_CHATS = 2;
    static final int SECTION_PRIVACY_SECURITY = 3;
    static final int SECTION_EXTRA_PRIVACY = 4;
    static final int SECTION_PROFILES = 5;
    /** iOS AI assistant (rows 601-604). Refused by this build on iOS too; not ported. */
    static final int SECTION_AI_ASSISTANT = 6;
    static final int SECTION_TRANSLATION = 7;
    static final int SECTION_VOICE_TO_TEXT = 8;
    static final int SECTION_VOICE_MESSAGES = 9;
    static final int SECTION_CHAT_LIST = 10;
    static final int SECTION_MESSAGES = 11;
    static final int SECTION_COMPOSING = 12;
    static final int SECTION_EDIT_HISTORY = 13;
    static final int SECTION_CONTEXT_MENU = 14;
    static final int SECTION_TABS = 15;
    static final int SECTION_FOLDERS = 16;
    static final int SECTION_STORIES = 17;
    static final int SECTION_MEDIA = 18;
    static final int SECTION_PHOTOS = 19;
    static final int SECTION_STICKERS = 20;
    static final int SECTION_ROUND_VIDEOS = 21;
    static final int SECTION_CAMERA = 22;
    static final int SECTION_ACCOUNT_COLORS = 23;
    static final int SECTION_TOOLS = 24;
    static final int SECTION_CALLS = 25;
    static final int SECTION_CALL_RECORDING = 26;
    static final int SECTION_ADVANCED = 27;
    static final int SECTION_HOLIDAY = 28;
    // WP-A settings hub (2026-11): sections that exist because the flat list became a hub and pages.
    /** "Deleted messages" on the Deleted &amp; Edited page (iOS Settings.DeletedMessages.Header); rows 501 / 502 became 3201 / 3202. */
    static final int SECTION_DELETED_MESSAGES = 32;
    // Sections 81-84 are the hub's own blocks (ZgSettingsHub).
    // Android only
    // ZENLYGRAM WP-C BEGIN
    static final int SECTION_DATA = 40;
    static final int ROW_DATA_CONSENT = 4001;
    static final int ROW_DATA_DELETE = 4002;
    static final int ROW_DATA_PRIVACY = 4003;
    // ZENLYGRAM WP-C END
    /** "Telegram Account", the first row of the block. 4004 and not 4000: section * 100 is the id of the section's header. */
    static final int ROW_DATA_TELEGRAM_ACCOUNT = 4004;
    /** The same link among the Pro rows; hidden while nothing is sold. */
    static final int ROW_PRO_LINK_ACCOUNT = 102;
    static final int SECTION_ABOUT = 90;
    static final int SECTION_DEVELOPER = 91;

    static final int ROW_PRO_SUBSCRIPTION = 101;
    static final int ROW_ABOUT_VERSION = 9001;
    static final int ROW_ABOUT_WEBSITE = 9002;
    static final int ROW_ABOUT_SOURCE_CODE = 9003;
    static final int ROW_DEVELOPER_PREVIEW = 9101;
    static final int ROW_DEVELOPER_PRO_PREVIEW = 9102;

    /**
     * Whether the Secret Vault is on but needs recovery (iOS ChatPincodeManager.protectionRecoveryStatus()
     * .vaultNeedsRecovery): its PIN or its list of hidden chats can't be used on this device any more.
     */
    static boolean vaultNeedsRecovery() {
        return org.telegram.zenlygram.vault.ZgVaultSettings.needsRecovery();
    }

    /** Whether every feature that the Pro notice (row 104) names is in this build. */
    static boolean proNoticeFeaturesPorted() {
        return ZgFeatures.SECRET_VAULT.isPorted() && ZgFeatures.CHAT_LOCK.isPorted() && ZgFeatures.TASKS.isPorted()
            && ZgFeatures.EDIT_HISTORY.isPorted() && ZgFeatures.KEPT_DELETED_MESSAGES.isPorted();
    }

    static ArrayList<ZgSection> build() {
        final ArrayList<ZgSection> sections = new ArrayList<>();

        // 1. Zenlygram Pro
        sections.add(new ZgSection(SECTION_PRO, res(R.string.ZG_Header_Settings_Pro)).feature(ZgFeatures.PRO_STATUS)
            .add(ZgRow.disclosure(ROW_PRO_SUBSCRIPTION, res(R.string.ZG_Settings_Pro_Subscription))
                .icon("star.circle.fill", R.drawable.msg_premium_liststar, Tile.GOLD)
                .value(ZgSettingsEntry::getProStatusValue)
                .newBadge("proSubscription")
                .action((screen, row) -> screen.presentFragment(ZgSettingsEntry.createProFragment())))
            // Android: the link is an identity step and lives in the Account & data block (ROW_DATA_TELEGRAM_ACCOUNT),
            // as on iOS, so this second row is never shown (ZgPro.offersAccountLinking). Restore shows in the Google
            // Play build while Pro is a subscription and opens the Pro screen, where the restore starts by itself.
            .add(ZgRow.disclosure(ROW_PRO_LINK_ACCOUNT, res(R.string.ZG_Settings_Pro_LinkAccount))
                .icon("person.badge.key.fill", R.drawable.msg_contact, Tile.BLUE)
                .value(() -> ZgLinkAccountActivity.stateLabel(UserConfig.selectedAccount))
                .feature(ZgFeatures.PRO_LINK_ACCOUNT)
                .visibleWhen(ZgPro::offersAccountLinking)
                .action((screen, row) -> screen.presentFragment(new ZgLinkAccountActivity())))
            .add(ZgRow.disclosure(103, res(R.string.ZG_Settings_Pro_Restore))
                .icon("arrow.clockwise", R.drawable.msg_retry, Tile.TEAL)
                .feature(ZgFeatures.PRO_RESTORE_PURCHASES)
                .visibleWhen(ZgPro::offersRestorePurchases)
                .action((screen, row) -> screen.presentFragment(org.telegram.zenlygram.pro.ZgProActivity.forRestore())))
            // iOS shows the entitlement's degraded reason here instead, when there is one (never while Pro is free).
            // The text names all four Pro features and the two history switches, so it waits until they are ported.
            .add(ZgRow.notice(104, res(R.string.ZG_Settings_Pro_Notice)).feature(ZgFeatures.PRO_ENTITLEMENT)
                .availableWhen(ZgSettingsSpec::proNoticeFeaturesPorted)));

        // 2. Hidden & locked chats
        // Secret Vault (org.telegram.zenlygram.vault): its own prefs zg_secret_vault hold the switch, the PIN record and the
        // encrypted members; ZgSettings.secretVaultEnabled / secretVaultUseSecretCode are not used on Android.
        final ZgRow.Condition vaultUsable = org.telegram.zenlygram.vault.ZgVaultSettings::isUsable;
        sections.add(new ZgSection(SECTION_HIDDEN_CHATS, res(R.string.ZG_Header_Settings_Privacy_HiddenChats)).feature(ZgFeatures.SECRET_VAULT)
            .add(ZgRow.toggle(201, res(R.string.ZG_Settings_SecretVault_Title), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return vaultUsable.test();
                    }

                    @Override
                    public void set(boolean value) {
                        // Controlled: only the vault's own flow (PIN set-up / PIN check) changes it.
                    }
                })
                .description(res(R.string.ZG_Settings_SecretVault_Desc))
                .icon("eye.slash.fill", R.drawable.msg_archive_hide, Tile.PURPLE)
                .newBadge("secretVaultEnabled")
                .controlled()
                .action((screen, row) -> org.telegram.zenlygram.vault.ZgVaultSettings.onSwitchClick(screen, screen::refresh)))
            // 202: the retired iOS "Open" row.
            // 203 / 205: the secret code (the PIN typed into chat search, ZgVaultSecretCode) replaces the title long-press.
            .add(ZgRow.toggle(203, res(R.string.ZG_Settings_SecretVault_UseSecretCode), bool(ZgSettings.secretVaultUseSecretCode))
                .description(res(R.string.ZG_Settings_SecretVault_UseSecretCode_Desc))
                .icon("character.cursor.ibeam", R.drawable.msg_text_outlined, Tile.TEAL)
                .newBadge("secretVaultUseSecretCode")
                .controlled()
                .action((screen, row) -> org.telegram.zenlygram.vault.ZgVaultSecretCode.onSwitchClick(screen, screen::refresh))
                .availableWhen(org.telegram.zenlygram.vault.ZgVaultSettings::isSecretCodeAvailable)
                .visibleWhen(vaultUsable))
            .add(ZgRow.disclosure(204, res(R.string.ZG_Settings_SecretVault_ChangePin))
                .icon("key.fill", R.drawable.msg_pin_code, Tile.GRAY)
                .newBadge("secretVaultChangePin")
                .visibleWhen(vaultUsable)
                .action((screen, row) -> org.telegram.zenlygram.vault.ZgVaultSettings.onChangePinClick(screen, screen::refresh)))
            .add(ZgRow.notice(205, res(R.string.ZG_Settings_SecretVault_UseSecretCode_Notice))
                .availableWhen(org.telegram.zenlygram.vault.ZgVaultSettings::isSecretCodeAvailable)
                .visibleWhen(vaultUsable))
            // 206-207: iOS "Reveal with a secret code — paused" + notice (no secret code on Android).
            // 210 (Android): the vault is on but needs a new PIN on this device; tapping row 201 opens the recovery.
            .add(ZgRow.notice(210, res(R.string.ZG_Vault_Settings_RecoveryNotice))
                .visibleWhen(ZgSettingsSpec::vaultNeedsRecovery))
            .add(ZgRow.notice(208, res(R.string.ZG_Settings_SecretVault_Notice))
                .visibleWhen(() -> !vaultNeedsRecovery()
                    && !(org.telegram.zenlygram.vault.ZgVaultSettings.isSecretCodeAvailable() && vaultUsable.test()))));
            // 209 Chat Lock moved to the hub (ZgSettingsHub.ROW_CHAT_LOCK, opens the Chat Lock Center).
            // 211: iOS "Restore protection" row (Android: row 201 / 209 open their recovery pages).

        // 3. Privacy & security
        sections.add(new ZgSection(SECTION_PRIVACY_SECURITY, res(R.string.ZG_Header_Settings_PrivacySecurity))
            // Android applies it live (no restart; the notice drops the iOS "requires a restart" sentence).
            .add(ZgRow.toggle(301, res(R.string.ZG_Settings_BlockForeignUsers), ZgExtrasASettingsFlow.foreignNumbers())
                .description(res(R.string.ZG_Settings_BlockForeignUsers_Desc))
                .icon("person.crop.circle.badge.xmark", R.drawable.msg_user_remove, Tile.ORANGE)
                .newBadge("blockForeignUsers")
                .feature(ZgFeatures.BLOCK_FOREIGN_USERS))
            .add(ZgRow.notice(302, res(R.string.ZG_ExtrasA_BlockForeignUsers_Notice)).feature(ZgFeatures.BLOCK_FOREIGN_USERS))
            .add(ZgRow.toggle(303, res(R.string.ZG_Settings_AutoAcceptRequests), ZgExtrasASettingsFlow.autoAccept())
                .description(res(R.string.ZG_Settings_AutoAcceptRequests_Desc))
                .icon("checkmark.circle.fill", R.drawable.msg_requests, Tile.GREEN)
                .newBadge("autoAcceptRequests")
                .feature(ZgFeatures.AUTO_ACCEPT_REQUESTS))
            .add(ZgRow.notice(306, res(R.string.ZG_ExtrasA_AutoAcceptRequests_Notice)).feature(ZgFeatures.AUTO_ACCEPT_REQUESTS))
            .add(ZgRow.toggle(304, res(R.string.ZG_Settings_CallConfirmation), bool(ZgSettings.confirmCalls))
                .description(res(R.string.ZG_Settings_CallConfirmation_Desc))
                .icon("phone.badge.checkmark", R.drawable.msg_calls, Tile.ORANGE)
                .feature(ZgFeatures.CALL_CONFIRMATION))
            .add(ZgRow.notice(305, res(R.string.ZG_Settings_CallConfirmation_Notice)).feature(ZgFeatures.CALL_CONFIRMATION)));

        // 4. Extra privacy
        sections.add(new ZgSection(SECTION_EXTRA_PRIVACY, res(R.string.ZG_Header_Settings_Privacy_Invisible))
            .add(ZgRow.toggle(401, res(R.string.ZG_Settings_HidePhoneInSettingsUI), bool(ZgSettings.hidePhoneInSettings))
                .description(res(R.string.ZG_Settings_HidePhoneInSettingsUI_Desc))
                .icon("phone.down.fill", R.drawable.msg_newphone, Tile.GRAY)
                .restart()
                .feature(ZgFeatures.HIDE_PHONE_IN_SETTINGS))
            .add(ZgRow.notice(402, res(R.string.ZG_Settings_HidePhoneInSettingsUI_Notice)).feature(ZgFeatures.HIDE_PHONE_IN_SETTINGS))
            .add(ZgRow.toggle(403, res(R.string.ZG_Settings_Stories_WarnBeforeView), bool(ZgSettings.warnOnStoriesOpen))
                .description(res(R.string.ZG_Settings_Stories_WarnBeforeView_Desc))
                .icon("exclamationmark.triangle.fill", R.drawable.msg_warning, Tile.YELLOW)
                .feature(ZgFeatures.STORY_VIEW_WARNING)));

        // 5. Profiles
        sections.add(new ZgSection(SECTION_PROFILES, res(R.string.ZG_Header_Settings_Profiles))
            // 501 / 502 (Show deleted messages) moved to SECTION_DELETED_MESSAGES as 3201 / 3202 (Deleted & Edited page).
            .add(ZgRow.toggle(503, res(R.string.ZG_Settings_ShowRegDate), bool(ZgSettings.showRegDate))
                .description(res(R.string.ZG_Settings_ShowRegDate_Desc))
                .icon("calendar.badge.clock", R.drawable.msg_calendar2, Tile.GREEN)
                .feature(ZgFeatures.REGISTRATION_DATE))
            .add(ZgRow.notice(504, res(R.string.ZG_Settings_ShowRegDate_Notice)).feature(ZgFeatures.REGISTRATION_DATE))
            .add(ZgRow.toggle(505, res(R.string.ZG_Settings_ShowCreationDate), bool(ZgSettings.showCreationDate))
                .description(res(R.string.ZG_Settings_ShowCreationDate_Desc))
                .icon("calendar.badge.plus", R.drawable.msg_calendar, Tile.LIGHT_BLUE)
                .feature(ZgFeatures.CREATION_DATE))
            .add(ZgRow.notice(506, res(R.string.ZG_Settings_ShowCreationDate_Notice)).feature(ZgFeatures.CREATION_DATE))
            .add(ZgRow.toggle(507, res(R.string.ZG_Settings_ShowDC), bool(ZgSettings.showDC))
                .description(res(R.string.ZG_Settings_ShowDC_Desc))
                .icon("server.rack", R.drawable.msg_satellite, Tile.TEAL)
                .feature(ZgFeatures.DATA_CENTER))
            .add(ZgRow.toggle(508, res(R.string.ZG_Settings_ShowProfileID), bool(ZgSettings.showProfileId))
                .description(res(R.string.ZG_Settings_ShowProfileID_Desc))
                .icon("number.circle.fill", R.drawable.msg_info, Tile.BLUE)
                .feature(ZgFeatures.PROFILE_ID))
            .add(ZgRow.toggle(509, res(R.string.ZG_Settings_ViewFirstMessage), bool(ZgSettings.showViewFirstMessage))
                .description(res(R.string.ZG_Settings_ViewFirstMessage_Desc))
                .icon("arrow.up.to.line", R.drawable.msg_go_up, Tile.BLUE)
                .newBadge("showViewFirstMessage")
                .feature(ZgFeatures.VIEW_FIRST_MESSAGE)));

        // 6. AI assistant (601-604): not ported, iOS refuses it in this build as well.

        // 7. Translate messages (org.telegram.zenlygram.translate). iOS "System" (on-device, iOS 18) has no Android
        // counterpart: Telegram (default, exactly upstream), "Telegram only" (Android value: no Google ever, the iOS
        // default behaviour) and Google Translate are offered; a stored "system" reads as Telegram.
        final ZgRow.Condition translateSectionShown = () -> ZgTranslate.showTranslateSection(MessagesController.getInstance(UserConfig.selectedAccount));
        sections.add(new ZgSection(SECTION_TRANSLATION, res(R.string.TranslateMessages))
            .add(ZgRow.selector(701, res(R.string.ZG_Settings_Translation_Backend), ZgSettings.translationBackend,
                    new ZgRow.Option(ZgSettings.TranslationBackend.DEFAULT, res(R.string.ZG_Settings_Translation_Backend_default)),
                    new ZgRow.Option(ZgSettings.TranslationBackend.TELEGRAM_ONLY, res(R.string.ZG_Translate_Backend_TelegramOnly)),
                    new ZgRow.Option(ZgSettings.TranslationBackend.GOOGLE, res(R.string.ZG_Settings_Translation_Backend_gtranslate)),
                    // never offered (the picker lists the three above); a stored iOS value shows and works as Telegram
                    new ZgRow.Option(ZgSettings.TranslationBackend.SYSTEM, res(R.string.ZG_Settings_Translation_Backend_default)))
                .feature(ZgFeatures.TRANSLATION_BACKEND)
                .action((screen, row) -> ZgTranslateSettings.showServicePicker(screen, screen::refresh)))
            // Android notices: the iOS texts name System and the Send-menu translation, which Android does not have.
            .add(ZgRow.notice(702, () -> ZgTranslate.usesGoogle()
                    ? getString(R.string.ZG_Translate_Notice_Google)
                    : ZgTranslate.isTelegramOnly()
                    ? formatString(R.string.ZG_Translate_Notice_Telegram, getString(R.string.ZG_Settings_Translation_Backend_gtranslate))
                    : formatString(R.string.ZG_Translate_Notice_Default, getString(R.string.ZG_Translate_Backend_TelegramOnly), getString(R.string.ZG_Settings_Translation_Backend_gtranslate)))
                .feature(ZgFeatures.TRANSLATION_BACKEND))
            .add(ZgRow.toggle(703, res(R.string.ZG_Settings_Translation_QuickTranslateButton), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return ZgSettings.quickTranslateButton.get();
                    }

                    @Override
                    public void set(boolean value) {
                        ZgTranslateSettings.setQuickTranslateButton(value);
                    }
                })
                // Android text: the button is next to the message (the iOS text says "in the message menu").
                .description(res(R.string.ZG_QuickTranslate_Desc))
                .icon("character.bubble.fill", R.drawable.msg_translate, Tile.PINK)
                .feature(ZgFeatures.QUICK_TRANSLATE))
            // Shown while Quick Translate cannot work with the selected service on this account (no button would appear).
            .add(ZgRow.notice(706, format(R.string.ZG_QuickTranslate_Unavailable_Notice, res(R.string.ZG_Settings_Translation_Backend_gtranslate)))
                .visibleWhen(() -> !ZgTranslate.quickAvailable(MessagesController.getInstance(UserConfig.selectedAccount)))
                .feature(ZgFeatures.QUICK_TRANSLATE))
            // Opens Telegram's Language screen, which lists its Translate section only where translation is offered
            // (ZgTranslate.showTranslateSection); elsewhere it would be a plain language list.
            .add(ZgRow.disclosure(704, res(R.string.TranslateEntireChat))
                .icon("globe", R.drawable.msg_language, Tile.PINK)
                .availableWhen(translateSectionShown)
                .feature(ZgFeatures.TRANSLATE_ENTIRE_CHAT)
                .action((screen, row) -> screen.presentFragment(new LanguageSelectActivity())))
            .add(ZgRow.notice(705, format(R.string.ZG_Settings_Translation_EntireChat_Notice, res(R.string.TelegramPremium)))
                .availableWhen(translateSectionShown)
                .feature(ZgFeatures.TRANSLATE_ENTIRE_CHAT)));

        // 8. Voice-to-text. iOS offers Telegram or Apple; Android has no Apple backend, so only Telegram is listed and
        // the fallback notice still names the iOS service: the feature that ports this section rewrites it.
        // WP-B: Android's counterpart of Apple is the phone's on-device SpeechRecognizer (stored as "apple", like iOS),
        // offered only where it exists (Android 13+ with an on-device service: org.telegram.zenlygram.voice.ZgVoiceToText).
        sections.add(new ZgSection(SECTION_VOICE_TO_TEXT, res(R.string.ZG_Header_Settings_Transcription)).feature(ZgFeatures.TRANSCRIPTION_BACKEND)
            .availableWhen(org.telegram.zenlygram.voice.ZgVoiceToText::isSupportedOnThisPhone)
            .add(ZgRow.selector(801, res(R.string.ZG_Settings_Transcription_Backend), ZgSettings.transcriptionBackend,
                new ZgRow.Option(ZgSettings.TranscriptionBackend.DEFAULT, res(R.string.ZG_Settings_Transcription_Backend_default)),
                new ZgRow.Option(ZgSettings.TranscriptionBackend.APPLE, res(R.string.ZG_Settings_Transcription_Backend_device))))
            .add(ZgRow.notice(802, format(R.string.ZG_Settings_Transcription_Backend_Notice, res(R.string.ZG_Settings_Transcription_Backend_default))))
            .add(ZgRow.notice(803, res(R.string.ZG_Settings_Transcription_Backend_DeviceNotice))
                .visibleWhen(() -> ZgSettings.transcriptionBackend.is(ZgSettings.TranscriptionBackend.APPLE))));

        // 9. Voice messages
        sections.add(new ZgSection(SECTION_VOICE_MESSAGES, res(R.string.PrivacyVoiceMessages)).feature(ZgFeatures.FORCE_BUILTIN_MIC)
            // Also switches Telegram's "Microphone for voice messages" to Built-in (org.telegram.zenlygram.voice.ZgMic).
            .add(ZgRow.toggle(901, res(R.string.ZG_Settings_forceBuiltInMic), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return ZgSettings.forceBuiltInMic.get();
                    }

                    @Override
                    public void set(boolean value) {
                        ZgMic.setForced(value);
                    }
                })
                .description(res(R.string.ZG_Settings_forceBuiltInMic_Desc))
                .icon("mic.fill", R.drawable.msg_voice_unmuted, Tile.RED))
            .add(ZgRow.notice(902, res(R.string.ZG_Settings_forceBuiltInMic_Notice))));

        // 10. Chat list. On Android none of these rows needs a restart: the list re-lays out when it is shown again and
        // the swipe rules are read at the start of every gesture.
        sections.add(new ZgSection(SECTION_CHAT_LIST, res(R.string.ZG_Header_Settings_ChatList))
            // Compact Chat List: 56 dp rows with 40 dp avatars (ZgChatListLayout, DialogCell hooks). It builds on Telegram's
            // two-line list, so the row shows the effective state and switching it on also selects Two lines.
            .add(ZgRow.toggle(1001, res(R.string.ZG_Settings_CompactChatList), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return ZgChatListLayout.isCompactEnabled();
                    }

                    @Override
                    public void set(boolean value) {
                        ZgChatListLayout.setCompact(value);
                    }
                })
                .description(concat(res(R.string.ZG_Settings_CompactChatList_Desc), concat(literal(" "), res(R.string.ZG_ChatlistChats_CompactNote))))
                .icon("rectangle.compress.vertical", R.drawable.msg_list, Tile.LIGHT_BLUE)
                .feature(ZgFeatures.COMPACT_CHAT_LIST))
            // Android: Telegram's own "Chat list view: Two lines" (SharedConfig.useThreeLinesLayout, inverted) is exactly
            // the compact preview, so the row writes that setting. The compact list implies it and disables the row.
            .add(ZgRow.toggle(1002, res(R.string.ZG_Settings_CompactMessagePreview), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return !SharedConfig.useThreeLinesLayout || ZgChatListLayout.isCompactEnabled();
                    }

                    @Override
                    public void set(boolean value) {
                        if (!value) {
                            ZgChatListLayout.setCompact(false);
                        }
                        SharedConfig.setUseThreeLinesLayout(!value);
                    }
                })
                .description(concat(res(R.string.ZG_Settings_CompactMessagePreview_Desc), concat(literal(" "),
                    format(R.string.ZG_ChatlistChats_SameAsTelegram, () -> getString(R.string.ChatList) + " \u203A " + getString(R.string.ChatListDefault)))))
                .icon("text.alignleft", R.drawable.msg_message, Tile.TEAL)
                .enabledWhen(() -> !ZgChatListLayout.isCompactEnabled(), turnOffFirst(res(R.string.ZG_Settings_CompactChatList)))
                .feature(ZgFeatures.COMPACT_MESSAGE_PREVIEW))
            .add(ZgRow.toggle(1003, res(R.string.ZG_Settings_UnlimitedPinnedChats), bool(ZgSettings.unlimitedPinnedChats))
                .description(res(R.string.ZG_Settings_UnlimitedPinnedChats_Notice))
                .icon("pin.circle.fill", R.drawable.msg_pin, Tile.ORANGE)
                .newBadge("unlimitedPinnedChats")
                .feature(ZgFeatures.UNLIMITED_PINNED_CHATS))
            .add(ZgRow.toggle(1004, res(R.string.ZG_Settings_ChatSwipeOptions), inverted(ZgSettings.disableChatSwipeOptions))
                .description(concat(res(R.string.ZG_Settings_ChatSwipeOptions_Desc), concat(literal(" "), res(R.string.ZG_ChatlistChats_SwipeNote))))
                .icon("hand.draw.fill", R.drawable.msg_select, Tile.BLUE)
                .feature(ZgFeatures.CHAT_SWIPE_OPTIONS))
            // Android swipes a chat with one action chosen in Chat Settings: this row exists while that action is Delete.
            .add(ZgRow.toggle(1005, res(R.string.ZG_Settings_DeleteChatSwipeOption), inverted(ZgSettings.disableDeleteChatSwipeOption))
                .description(res(R.string.ZG_Settings_DeleteChatSwipeOption_Desc))
                .icon("trash.fill", R.drawable.msg_delete, Tile.RED)
                .enabledWhen(() -> !ZgSettings.disableChatSwipeOptions.get(), turnOnFirst(res(R.string.ZG_Settings_ChatSwipeOptions)))
                .availableWhen(() -> ZgChatListSwipe.isDeleteSwipeAction(UserConfig.selectedAccount))
                .feature(ZgFeatures.CHAT_SWIPE_OPTIONS)));

        // 11. Chats & messages. Rows 1101-1103 bump ZgChatAppearance.generation, so open chats re-lay out their messages.
        sections.add(new ZgSection(SECTION_MESSAGES, res(R.string.ZG_Header_Settings_Messages))
            .add(ZgRow.toggle(1101, res(R.string.ZG_Settings_HideReactions), ZgRow.afterSet(bool(ZgSettings.hideReactions), ZgChatAppearance::bump))
                .description(res(R.string.ZG_Settings_HideReactions_Desc))
                .icon("heart.slash.fill", R.drawable.msg_reactions, Tile.RED)
                .feature(ZgFeatures.HIDE_REACTIONS))
            .add(ZgRow.toggle(1102, res(R.string.ZG_Settings_secondsInMessages), ZgRow.afterSet(bool(ZgSettings.secondsInMessages), ZgChatAppearance::bump))
                .description(res(R.string.ZG_Settings_secondsInMessages_Desc))
                .icon("clock.fill", R.drawable.msg_recent, Tile.BLUE)
                .feature(ZgFeatures.SECONDS_IN_MESSAGES))
            // Android: the side Share / AI summary buttons of wide posts move to the message menu (Forward, AI Summary).
            .add(ZgRow.toggle(1103, res(R.string.ZG_Settings_wideChannelPosts), ZgRow.afterSet(bool(ZgSettings.wideChannelPosts), ZgChatAppearance::bump))
                .description(concat(res(R.string.ZG_Settings_wideChannelPosts_Desc), concat(literal(" "), res(R.string.ZG_wide_channel_posts_SideButtonsNote))))
                .icon("arrow.left.and.right.square.fill", R.drawable.msg_expand, Tile.ORANGE)
                .feature(ZgFeatures.WIDE_CHANNEL_POSTS))
            // Android description: the iOS one says "join or mute", but the Join button is never hidden.
            .add(ZgRow.toggle(1104, res(R.string.ZG_Settings_showChannelBottomButton), inverted(ZgSettings.hideChannelBottomButton))
                .description(res(R.string.ZG_ChatlistChats_ChannelPanelDesc))
                .icon("rectangle.bottomthird.inset.filled", R.drawable.msg_channel, Tile.TEAL)
                .feature(ZgFeatures.CHANNEL_BOTTOM_BUTTON))
            // Android: Telegram's own Power Saving > Animations in Chats > Dust-effect deletion (LiteMode), which Telegram
            // refuses to change while the power saver is applied; the row does the same (bulletin).
            .add(ZgRow.toggle(1105, res(R.string.ZG_Settings_SnapDeletionEffect), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return LiteMode.isEnabledSetting(LiteMode.FLAG_CHAT_THANOS);
                    }

                    @Override
                    public void set(boolean value) {
                        LiteMode.toggleFlag(LiteMode.FLAG_CHAT_THANOS, value);
                    }
                })
                .description(concat(res(R.string.ZG_Settings_SnapDeletionEffect_Desc), concat(literal(" "),
                    format(R.string.ZG_ChatlistChats_SameAsTelegram, () -> getString(R.string.LiteOptionsChat) + " \u203A " + getString(R.string.LiteOptionsThanos)))))
                .icon("sparkles", R.drawable.msg_customize, Tile.PURPLE)
                .controlled()
                .action((screen, row) -> {
                    if (LiteMode.isPowerSaverApplied()) {
                        BulletinFactory.of(screen).createSimpleBulletin(new BatteryDrawable(.1f, Color.WHITE, Theme.getColor(Theme.key_dialogSwipeRemove), 1.3f),
                            getString(R.string.LiteBatteryRestricted)).show();
                    } else {
                        row.boolValue.set(!row.boolValue.get());
                    }
                })
                .availableWhen(ThanosEffect::supports)
                .feature(ZgFeatures.SNAP_DELETION_EFFECT))
            .add(ZgRow.toggle(1106, res(R.string.ZG_Settings_PullToNextChannel), inverted(ZgSettings.disableScrollToNextChannel))
                .description(res(R.string.ZG_Settings_PullToNextChannel_Desc))
                .icon("arrow.down.circle.fill", R.drawable.msg_go_down, Tile.BLUE)
                .feature(ZgFeatures.PULL_TO_NEXT_CHANNEL))
            .add(ZgRow.toggle(1107, res(R.string.ZG_Settings_PullToNextTopic), inverted(ZgSettings.disableScrollToNextTopic))
                .description(res(R.string.ZG_Settings_PullToNextTopic_Desc))
                .icon("arrow.down.to.line.circle.fill", R.drawable.msg_topics, Tile.LIGHT_BLUE)
                .feature(ZgFeatures.PULL_TO_NEXT_TOPIC)));

        // 12. Composing & input
        sections.add(new ZgSection(SECTION_COMPOSING, res(R.string.ZG_Header_Settings_Composing))
            // Telegram Android has this option (Chat Settings > Send by Enter): the row edits Telegram's value.
            .add(ZgRow.toggle(1201, res(R.string.ZG_InputStickers_SendWithEnterKey), telegramGlobalBool("send_by_enter", false))
                .description(res(R.string.ZG_InputStickers_SendWithEnterKey_Desc))
                .icon("return", R.drawable.msg_send, Tile.BLUE)
                .feature(ZgFeatures.SEND_WITH_RETURN_KEY))
            .add(ZgRow.toggle(1202, res(R.string.ZG_Settings_messageDoubleTapActionOutgoingEdit),
                    choiceIs(ZgSettings.messageDoubleTapActionOutgoing, ZgSettings.DoubleTapAction.EDIT, ZgSettings.DoubleTapAction.DEFAULT))
                .description(res(R.string.ZG_Settings_messageDoubleTapActionOutgoingEdit_Desc))
                .icon("hand.tap.fill", R.drawable.msg_edit, Tile.PURPLE)
                .feature(ZgFeatures.DOUBLE_TAP_TO_EDIT))
            .add(ZgRow.toggle(1203, format(R.string.ZG_Settings_SendAsButton, res(R.string.SendMessageAsTitle)), inverted(ZgSettings.disableSendAsButton))
                .description(res(R.string.ZG_Settings_SendAsButton_Desc))
                .icon("person.crop.circle.badge.checkmark", R.drawable.msg_openprofile, Tile.TEAL)
                .feature(ZgFeatures.SEND_AS_BUTTON))
            .add(ZgRow.toggle(1204, res(R.string.ZG_Settings_RecordingButton), inverted(ZgSettings.hideRecordingButton))
                .description(res(R.string.ZG_Settings_RecordingButton_Desc))
                .icon("mic.circle.fill", R.drawable.msg_tabs_mic1, Tile.RED)
                .feature(ZgFeatures.RECORDING_BUTTON))
            .add(ZgRow.toggle(1205, res(R.string.ZG_Settings_ForceEmojiTab), bool(ZgSettings.forceEmojiTab))
                .description(res(R.string.ZG_Settings_ForceEmojiTab_Desc))
                .icon("face.smiling.fill", R.drawable.msg_emoji_smiles, Tile.YELLOW)
                .feature(ZgFeatures.FORCE_EMOJI_TAB))
            .add(ZgRow.toggle(1206, res(R.string.ZG_Settings_DefaultEmojisFirst), bool(ZgSettings.defaultEmojisFirst))
                .description(res(R.string.ZG_Settings_DefaultEmojisFirst_Desc))
                .icon("face.smiling", R.drawable.msg_emoji_cat, Tile.GOLD)
                .feature(ZgFeatures.DEFAULT_EMOJIS_FIRST))
            .add(ZgRow.notice(1207, res(R.string.ZG_Settings_DefaultEmojisFirst_Notice)).feature(ZgFeatures.DEFAULT_EMOJIS_FIRST)));

        // 32. Deleted messages (iOS history page, DELETED MESSAGES): Show Deleted Messages, from the former Profiles section.
        sections.add(new ZgSection(SECTION_DELETED_MESSAGES, res(R.string.ZG_Hub_DeletedMessagesHeader))
            // Turning it off first offers to delete what was kept (ZgHistorySettingsFlow). The Android description
            // drops the stale iOS "part of Zenlygram Pro" sentence: reading kept messages is never gated.
            .add(ZgRow.toggle(3201, res(R.string.ZG_Settings_ShowDeletedMessages), bool(ZgSettings.showDeletedMessages))
                .description(res(R.string.ZG_History_ShowDeletedMessages_Desc))
                .icon("trash.slash.fill", R.drawable.msg_delete, Tile.ORANGE)
                .newBadge("showDeletedMessages")
                .controlled()
                .action((screen, row) -> ZgHistorySettingsFlow.onShowDeletedMessagesClicked(screen))
                .feature(ZgFeatures.KEPT_DELETED_MESSAGES))
            // Android notice: the iOS text plus what removes kept copies here (Clear Local Database, a long time offline).
            .add(ZgRow.notice(3202, res(R.string.ZG_History_ShowDeletedMessages_Notice)).feature(ZgFeatures.KEPT_DELETED_MESSAGES)));

        // 13. Edit history. Turning it off first offers to delete the kept versions (ZgHistorySettingsFlow); the
        // description drops the stale iOS "part of Zenlygram Pro" sentence.
        sections.add(new ZgSection(SECTION_EDIT_HISTORY, res(R.string.ZG_Header_EditHistory)).feature(ZgFeatures.EDIT_HISTORY)
            .add(ZgRow.toggle(1301, res(R.string.ZG_Settings_EditHistory), bool(ZgSettings.editHistoryEnabled))
                .description(res(R.string.ZG_History_EditHistory_Desc))
                .icon("clock.arrow.circlepath", R.drawable.msg_recent, Tile.TEAL)
                .newBadge("editHistoryEnabled")
                .controlled()
                .action((screen, row) -> ZgHistorySettingsFlow.onSaveEditHistoryClicked(screen))));

        // 14. Context menu. iOS shows the notice above the switches; here it is the section footer. Android: a master
        // switch first (off = none of the five Swiftgram items and no sub-menu; Edit History, Add to Tasks and the
        // kept-copy / wide-post items follow their own switches); the item switches show only while it is on.
        final ZgRow.Condition contextMenuOn = ZgSettings.contextMenuZenlygramItems::get;
        sections.add(new ZgSection(SECTION_CONTEXT_MENU, res(R.string.ZG_Header_Settings_ContextMenu)).feature(ZgFeatures.CONTEXT_MENU)
            .add(ZgRow.toggle(1412, res(R.string.ZG_ContextMenu_Enabled), bool(ZgSettings.contextMenuZenlygramItems))
                .description(res(R.string.ZG_ContextMenu_Enabled_Desc))
                .icon("contextualmenu.and.cursorarrow", R.drawable.msg_customize, Tile.BLUE))
            .add(ZgRow.toggle(1401, res(R.string.ZG_ContextMenu_SaveToCloud), bool(ZgSettings.contextShowSaveToCloud))
                .description(res(R.string.ZG_ContextMenu_SaveToCloud_Desc))
                .icon("icloud.and.arrow.down.fill", R.drawable.msg_saved, Tile.BLUE)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1402, res(R.string.SaveToGallery), bool(ZgSettings.contextShowSaveMedia))
                .description(res(R.string.ZG_InputStickers_ContextShowSaveGallery_Desc))
                .icon("square.and.arrow.down.fill", R.drawable.msg_download, Tile.GREEN)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1403, res(R.string.Reply), bool(ZgSettings.contextShowReply))
                .description(res(R.string.ZG_Settings_ContextShowReply_Desc))
                .icon("arrowshape.turn.up.left.fill", R.drawable.msg_reply_small, Tile.BLUE)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1404, res(R.string.PinMessage), bool(ZgSettings.contextShowPin))
                .description(res(R.string.ZG_Settings_ContextShowPin_Desc))
                .icon("pin.fill", R.drawable.msg_pin, Tile.ORANGE)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1405, res(R.string.ZG_ContextMenu_SelectFromUser), bool(ZgSettings.contextShowSelectFromUser))
                .description(res(R.string.ZG_ContextMenu_SelectFromUser_Desc))
                .icon("person.crop.circle.badge.checkmark", R.drawable.msg_select, Tile.TEAL)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1406, res(R.string.ViewThread), bool(ZgSettings.contextShowMessageReplies))
                .description(res(R.string.ZG_Settings_ContextShowMessageReplies_Desc))
                .icon("bubble.left.and.bubble.right.fill", R.drawable.msg_viewreplies, Tile.PURPLE)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1407, res(R.string.HideSenderNames), bool(ZgSettings.contextShowHideForwardName))
                .description(res(R.string.ZG_Settings_ContextShowHideForwardName_Desc))
                .icon("person.fill.xmark", R.drawable.msg_forward, Tile.ORANGE)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1408, res(R.string.ReportChat), bool(ZgSettings.contextShowReport))
                .description(res(R.string.ZG_Settings_ContextShowReport_Desc))
                .icon("exclamationmark.bubble.fill", R.drawable.msg_report, Tile.ORANGE)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1409, res(R.string.Restrict), bool(ZgSettings.contextShowRestrict))
                .description(res(R.string.ZG_Settings_ContextShowRestrict_Desc))
                .icon("nosign", R.drawable.msg_block, Tile.RED)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.toggle(1410, literal("JSON"), bool(ZgSettings.contextShowJson))
                .description(res(R.string.ZG_Settings_ContextShowJson_Desc))
                .icon("curlybraces", R.drawable.formatting_code, Tile.GRAY)
                .visibleWhen(contextMenuOn))
            .add(ZgRow.notice(1411, res(R.string.ZG_Settings_ContextMenu_Notice)).visibleWhen(contextMenuOn)));

        // 15. Tabs. Every option but "Hide Tab bar" is disabled while the tab bar is hidden; "Hide Tab bar" exists only
        // with TAB_BAR, so without it the tab bar always shows.
        final ZgRow.Condition tabBarShown = () -> !ZgFeatures.TAB_BAR.isPorted() || !ZgSettings.hideTabBar.get();
        final ZgRow.Text tabBarHint = turnOffFirst(res(R.string.ZG_Settings_Tabs_HideTabBar));
        sections.add(new ZgSection(SECTION_TABS, res(R.string.ZG_Header_Settings_Tabs)).feature(ZgFeatures.TAB_BAR)
            .add(ZgRow.toggle(1501, res(R.string.ZG_Settings_Tabs_HideTabBar), bool(ZgSettings.hideTabBar))
                // Android: the tab bar is the only way to Contacts, Settings, the profile and accounts; they move to ⋮.
                .description(res(R.string.ZG_FoldersTabsStories_HideTabBar_Desc))
                .icon("rectangle.bottomthird.inset.filled", R.drawable.msg_viewchats, Tile.BLUE)
                .restart())
            .add(ZgRow.toggle(1502, res(R.string.ZG_Settings_Tabs_ShowNames), bool(ZgSettings.showTabNames))
                .description(res(R.string.ZG_Settings_Tabs_ShowNames_Desc))
                .icon("textformat", R.drawable.msg_text_outlined, Tile.TEAL)
                .restart()
                .enabledWhen(tabBarShown, tabBarHint))
            .add(ZgRow.toggle(1503, res(R.string.ZG_Settings_Tabs_SearchButton), bool(ZgSettings.tabBarSearchEnabled))
                .description(res(R.string.ZG_Settings_Tabs_SearchButton_Desc))
                .icon("magnifyingglass", R.drawable.msg_search, Tile.BLUE)
                .enabledWhen(tabBarShown, tabBarHint)
                .feature(ZgFeatures.TAB_BAR_SEARCH_BUTTON))
            // 1504 / 1505 (Show Contacts Tab, Show Calls Tab) became the one "Contacts & Calls in tab bar" switch at the top of
            // the hub (ZgSettingsHub.ROW_CONTACTS_AND_CALLS, iOS lenta-v6).
            // Tasks: the row needs only TASKS (the rest of the section waits for TAB_BAR). The switch is applied live by
            // MainTabsActivity, keeping the selected tab; hiding it never touches a task, but "Add to Tasks" (message
            // menu) and the ⋮ Tasks item (tab bar hidden) follow it (ZgTasksMessageMenu, ZgTabs).
            .add(ZgRow.toggle(1506, res(R.string.ZG_Settings_Tabs_ShowTasks), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return ZgTasks.isTabVisible();
                    }

                    @Override
                    public void set(boolean value) {
                        ZgTasks.setTabVisible(value);
                    }
                })
                // Android text: hiding the tab also removes "Add to Tasks" from the message menu and Tasks from ⋮.
                .description(res(R.string.ZG_FoldersTabsStories_ShowTasks_Desc))
                .icon("checklist", R.drawable.msg_list, Tile.BLUE)
                .enabledWhen(tabBarShown, tabBarHint)
                .feature(ZgFeatures.TASKS))
            .add(ZgRow.toggle(1507, res(R.string.ZG_Settings_Tabs_WideTabBar), bool(ZgSettings.wideTabBar))
                .description(res(R.string.ZG_Settings_Tabs_WideTabBar_Desc))
                .icon("arrow.left.and.right", R.drawable.msg_expand, Tile.PURPLE)
                .restart()
                .enabledWhen(tabBarShown, tabBarHint))
            .add(ZgRow.notice(1508, res(R.string.ZG_Settings_Tabs_WideTabBar_Notice))));

        // 16. Folders. Rows 1601, 1602 and 1604 use Android copies of the iOS texts: the generated Uzbek ones say "papka"
        // where the rest of the section says "jild".
        sections.add(new ZgSection(SECTION_FOLDERS, res(R.string.Filters))
            // Android has no Telegram setting for it: a Zenlygram key. Android difference: it needs a restart (iOS: live).
            .add(ZgRow.toggle(1601, res(R.string.ZG_Settings_Folders_BottomTab), bool(ZgSettings.foldersTabAtBottom))
                .description(res(R.string.ZG_FoldersTabsStories_BottomTab_Desc))
                .icon("dock.rectangle", R.drawable.msg_folders, Tile.BLUE)
                .restart()
                .feature(ZgFeatures.FOLDERS_AT_BOTTOM))
            .add(ZgRow.toggle(1602, res(R.string.ZG_FoldersTabsStories_RememberLast), bool(ZgSettings.rememberLastFolder))
                .description(res(R.string.ZG_FoldersTabsStories_RememberLast_Desc))
                .icon("bookmark.fill", R.drawable.msg_saved, Tile.ORANGE)
                .feature(ZgFeatures.REMEMBER_LAST_FOLDER))
            .add(ZgRow.notice(1603, res(R.string.ZG_Settings_Folders_RememberLast_Notice)).feature(ZgFeatures.REMEMBER_LAST_FOLDER))
            .add(ZgRow.toggle(1604, format(R.string.ZG_Settings_Folders_AllChatsHidden, res(R.string.FilterAllChats)), bool(ZgSettings.allChatsHidden))
                .description(res(R.string.ZG_FoldersTabsStories_AllChatsHidden_Desc))
                .icon("eye.slash", R.drawable.msg_archive_hide, Tile.GRAY)
                .restart()
                .feature(ZgFeatures.ALL_CHATS_HIDDEN))
            .add(ZgRow.toggle(1605, res(R.string.ZG_Settings_Folders_CompactNames), bool(ZgSettings.compactFolderNames))
                .description(res(R.string.ZG_FoldersTabsStories_CompactNames_Desc)) // the iOS text says "shorter names"; it is the spacing
                .icon("textformat.size.smaller", R.drawable.msg_zoomout, Tile.TEAL)
                .restart()
                .feature(ZgFeatures.COMPACT_FOLDER_NAMES))
            // The dialog lists the titles themselves ("All" / "All Chats"), the row shows Default / Short / Long.
            .add(ZgRow.selector(1606, res(R.string.ZG_Settings_Folders_AllChatsTitle), ZgSettings.allChatsTitleLengthOverride,
                    new ZgRow.Option(ZgSettings.AllChatsTitle.NONE, res(R.string.ZG_Settings_Folders_AllChatsTitle_none)),
                    new ZgRow.Option(ZgSettings.AllChatsTitle.SHORT, quoted(R.string.FilterAllChatsShort), res(R.string.ZG_Settings_Folders_AllChatsTitle_short)),
                    new ZgRow.Option(ZgSettings.AllChatsTitle.LONG, quoted(R.string.FilterAllChats), res(R.string.ZG_Settings_Folders_AllChatsTitle_long)))
                .onChanged((screen, row) -> ZgUiEvents.post(ZgSettings.allChatsTitleLengthOverride.key))
                // No "All Chats" tab to title while Hide "All Chats" is on.
                .enabledWhen(() -> !ZgFeatures.ALL_CHATS_HIDDEN.isPorted() || !ZgSettings.allChatsHidden.get(),
                    turnOffFirst(format(R.string.ZG_Settings_Folders_AllChatsHidden, res(R.string.FilterAllChats))))
                .feature(ZgFeatures.ALL_CHATS_TITLE)));

        // 17. Stories
        sections.add(new ZgSection(SECTION_STORIES, res(R.string.AutoDownloadStories))
            .add(ZgRow.toggle(1701, res(R.string.ZG_Settings_Stories_Hide), bool(ZgSettings.hideStories))
                .description(res(R.string.ZG_Settings_Stories_Hide_Desc))
                .icon("circle.dashed", R.drawable.msg_stories_myhide, Tile.VIOLET)
                .onChanged((screen, row) -> ZgUiEvents.post(ZgSettings.hideStories.key))
                .feature(ZgFeatures.HIDE_STORIES))
            .add(ZgRow.toggle(1702, res(R.string.ZG_Settings_Stories_DisableSwipeToRecord), bool(ZgSettings.disableSwipeToRecordStory))
                .description(res(R.string.ZG_Settings_Stories_DisableSwipeToRecord_Desc))
                .icon("hand.draw.fill", R.drawable.msg_stories_add, Tile.PINK)
                .feature(ZgFeatures.STORY_SWIPE_TO_RECORD))
            .add(ZgRow.toggle(1703, () -> getString(R.string.RepostToStory).replace('\n', ' '), bool(ZgSettings.showRepostToStoryV2))
                .description(res(R.string.ZG_Settings_Stories_RepostToStory_Desc))
                .icon("square.and.arrow.up.circle.fill", R.drawable.msg_shareout, Tile.TEAL)
                .feature(ZgFeatures.REPOST_TO_STORY)));

        // 18. Media & downloads. Download / Upload Boost: org.telegram.zenlygram.media.ZgNetworkBoost. Android reads the
        // Download Boost level at the start of every download, so unlike iOS it needs no restart (no restart hint).
        sections.add(new ZgSection(SECTION_MEDIA, res(R.string.ZG_Header_Settings_Media))
            .add(ZgRow.toggle(1801, res(R.string.ZG_Settings_DisableAutoDownload), ZgExtrasASettingsFlow.autoDownload())
                .description(res(R.string.ZG_Settings_DisableAutoDownload_Desc))
                .icon("arrow.down.circle.fill", R.drawable.msg_download_settings, Tile.ORANGE)
                .newBadge("autoDownloadDisabled")
                .feature(ZgFeatures.DISABLE_AUTO_DOWNLOAD))
            .add(ZgRow.selector(1802, res(R.string.ZG_Settings_DownloadsBoost), ZgSettings.downloadSpeedBoost,
                    new ZgRow.Option(ZgSettings.DownloadBoost.NONE, res(R.string.ZG_Settings_DownloadsBoost_none)),
                    new ZgRow.Option(ZgSettings.DownloadBoost.MEDIUM, res(R.string.ZG_Settings_DownloadsBoost_medium)),
                    new ZgRow.Option(ZgSettings.DownloadBoost.MAXIMUM, res(R.string.ZG_Settings_DownloadsBoost_maximum)))
                .feature(ZgFeatures.DOWNLOAD_BOOST))
            .add(ZgRow.notice(1803, res(R.string.ZG_Settings_DownloadsBoost_Notice))
                .feature(ZgFeatures.DOWNLOAD_BOOST))
            .add(ZgRow.toggle(1804, res(R.string.ZG_Settings_UploadsBoost), bool(ZgSettings.uploadSpeedBoost))
                .description(res(R.string.ZG_Settings_UploadsBoost_Desc))
                .icon("arrow.up.circle.fill", R.drawable.msg_speed, Tile.GREEN)
                .feature(ZgFeatures.UPLOAD_BOOST)));

        // 19. Photos (org.telegram.zenlygram.media.ZgPhotoQuality). The slider defaults to 80 on Android: Telegram's own
        // standard JPEG quality, kept on every path at that value.
        sections.add(new ZgSection(SECTION_PHOTOS, res(R.string.LocalPhotoCache))
            .add(ZgRow.subheader(1901, res(R.string.Quality)).feature(ZgFeatures.PHOTO_QUALITY))
            .add(ZgRow.slider(1902, ZgSettings.outgoingPhotoQuality).feature(ZgFeatures.PHOTO_QUALITY))
            .add(ZgRow.notice(1903, res(R.string.ZG_Settings_Photo_Quality_Notice)).feature(ZgFeatures.PHOTO_QUALITY))
            .add(ZgRow.toggle(1904, res(R.string.ZG_Settings_Photo_SendLarge), bool(ZgSettings.sendLargePhotos))
                .description(res(R.string.ZG_Settings_Photo_SendLarge_Desc))
                .icon("photo.fill", R.drawable.msg_photos, Tile.BLUE)
                .feature(ZgFeatures.SEND_LARGE_PHOTOS))
            .add(ZgRow.notice(1905, res(R.string.ZG_Settings_Photo_SendLarge_Notice)).feature(ZgFeatures.SEND_LARGE_PHOTOS)));

        // 20. Stickers
        sections.add(new ZgSection(SECTION_STICKERS, res(R.string.StickersName))
            .add(ZgRow.subheader(2001, res(R.string.ZG_Header_Settings_Stickers_Size)).feature(ZgFeatures.STICKER_SIZE))
            // Open chats re-lay out their stickers (ChatMessageCell compares ZgChatAppearance.generation, like rows 1101-1103).
            .add(ZgRow.slider(2002, ZgSettings.stickerSize).action((screen, row) -> ZgChatAppearance.bump()).feature(ZgFeatures.STICKER_SIZE))
            .add(ZgRow.toggle(2003, res(R.string.ZG_Settings_Stickers_Timestamp), bool(ZgSettings.stickerTimestamp))
                .description(res(R.string.ZG_Settings_Stickers_Timestamp_Desc))
                .icon("clock.badge.fill", R.drawable.msg_recent, Tile.TEAL)
                .feature(ZgFeatures.STICKER_TIMESTAMP)));

        // 21. Round videos
        sections.add(new ZgSection(SECTION_ROUND_VIDEOS, res(R.string.ZG_Header_Settings_VideoNotes))
            .add(ZgRow.toggle(2101, res(R.string.ZG_Settings_swipeForVideoPIP),
                    choiceIs(ZgSettings.videoPIPSwipeDirection, ZgSettings.VideoPipSwipe.UP, ZgSettings.VideoPipSwipe.NONE))
                .description(res(R.string.ZG_Settings_swipeForVideoPIP_Desc))
                .icon("pip.fill", R.drawable.msg_voice_pip, Tile.BLUE)
                .feature(ZgFeatures.VIDEO_PIP_SWIPE))
            .add(ZgRow.notice(2102, res(R.string.ZG_Settings_swipeForVideoPIP_Notice)).feature(ZgFeatures.VIDEO_PIP_SWIPE))
            .add(ZgRow.toggle(2103, res(R.string.ZG_Settings_VideoNotes_StartWithRearCam), bool(ZgSettings.startTelescopeWithRearCam))
                .description(res(R.string.ZG_Settings_VideoNotes_StartWithRearCam_Desc))
                .icon("camera.rotate.fill", R.drawable.msg_photo_switch2, Tile.BLUE)
                .feature(ZgFeatures.ROUND_VIDEO_REAR_CAMERA))
            .add(ZgRow.toggle(2104, res(R.string.ZG_Settings_RoundVideoFromGallery), bool(ZgSettings.sendRoundVideoFromGallery))
                .description(res(R.string.ZG_Settings_RoundVideoFromGallery_Desc))
                .icon("video.circle.fill", R.drawable.msg_video, Tile.BLUE)
                .newBadge("sendRoundVideoFromGallery")
                .feature(ZgFeatures.ROUND_VIDEO_FROM_GALLERY))
            // Android: the entry point is the attach menu's send options (iOS: a button in the round video camera).
            .add(ZgRow.notice(2105, res(R.string.ZG_ExtrasB_RoundVideoFromGallery_Notice)).feature(ZgFeatures.ROUND_VIDEO_FROM_GALLERY)));

        // 22. Camera & gallery
        sections.add(new ZgSection(SECTION_CAMERA, res(R.string.ZG_Header_Settings_CameraPicker)).feature(ZgFeatures.GALLERY_CAMERA)
            .add(ZgRow.toggle(2201, res(R.string.ZG_Settings_GalleryCamera), inverted(ZgSettings.disableGalleryCamera))
                .description(res(R.string.ZG_Settings_GalleryCamera_Desc))
                .icon("camera.fill", R.drawable.msg_camera, Tile.GRAY))
            .add(ZgRow.toggle(2202, res(R.string.ZG_Settings_GalleryCameraPreview), inverted(ZgSettings.disableGalleryCameraPreview))
                .description(res(R.string.ZG_Settings_GalleryCameraPreview_Desc))
                .icon("camera.viewfinder", R.drawable.msg_media_gallery, Tile.GRAY)
                .enabledWhen(() -> !ZgSettings.disableGalleryCamera.get(), turnOnFirst(res(R.string.ZG_Settings_GalleryCamera)))));

        // 23. Account colours (org.telegram.zenlygram.appearance.ZgAccountColors). 2303-2305: the iOS name-colour previews,
        // one at 0 % (Telegram's default colour) and three otherwise; 2306-2309 stay reserved. The slider calls back on
        // every step, so the previews and the open chats are refreshed once it rests.
        sections.add(new ZgSection(SECTION_ACCOUNT_COLORS, res(R.string.ZG_Header_Settings_CustomColors)).feature(ZgFeatures.ACCOUNT_COLORS)
            .add(ZgRow.subheader(2301, res(R.string.ZG_Header_Settings_CustomColors_Saturation)))
            .add(ZgRow.slider(2302, ZgSettings.accountColorsSaturation)
                .action((screen, row) -> ZgAccountColors.onSaturationChanged(() -> {
                    if (screen.getParentActivity() != null) {
                        screen.refresh();
                    }
                })))
            .add(ZgRow.info(2303, () -> ZgAccountColors.preview(0)).notSearchable())
            .add(ZgRow.info(2304, () -> ZgAccountColors.preview(1)).notSearchable().visibleWhen(() -> ZgAccountColors.previewCount() > 1))
            .add(ZgRow.info(2305, () -> ZgAccountColors.preview(2)).notSearchable().visibleWhen(() -> ZgAccountColors.previewCount() > 2))
            .add(ZgRow.notice(2310, res(R.string.ZG_Settings_CustomColors_Saturation_Notice))));

        // 24. Tools. 2401: Helper Bots, hidden on iOS as well (its bots do not exist yet).
        sections.add(new ZgSection(SECTION_TOOLS, res(R.string.ZG_Hub_RemindersHeader)) // iOS chats page, REMINDERS
            // 2402 Tasks moved to the hub (ZgSettingsHub.ROW_TASKS).
            .add(ZgRow.toggle(2403, res(R.string.ZG_Settings_UnreadReminder), bool(ZgSettings.unreadReminderEnabled))
                .description(res(R.string.ZG_Settings_UnreadReminder_Desc))
                .icon("bell.badge.fill", R.drawable.msg_notifications, Tile.ORANGE)
                .newBadge("unreadReminderEnabled")
                .controlled()
                .action((screen, row) -> ZgExtrasASettingsFlow.toggleUnreadReminder(screen))
                .feature(ZgFeatures.UNREAD_REMINDER))
            .add(ZgRow.notice(2404, res(R.string.ZG_Settings_UnreadReminder_Notice)).feature(ZgFeatures.UNREAD_REMINDER)));

        // 25. Calls (Force TCP is Telegram's own VoIP setting on Android, also in its hidden call debug menu)
        sections.add(new ZgSection(SECTION_CALLS, res(R.string.Calls)).feature(ZgFeatures.VOIP_FORCE_TCP)
            .add(ZgRow.toggle(2501, res(R.string.ZG_Settings_ForceTCP), ZgTelegramSettings.forceTcpInCalls())
                .description(res(R.string.ZG_Settings_ForceTCP_Desc))
                .icon("network", R.drawable.msg_link2, Tile.GREEN))
            .add(ZgRow.notice(2502, res(R.string.ZG_Common_KnowWhatYouDo))));

        // 26. Call recording. iOS: shown while recording is available or recordings exist, the two switches only
        // while it is available, and an "unavailable" notice otherwise. The feature adds those conditions.
        sections.add(new ZgSection(SECTION_CALL_RECORDING, res(R.string.ZG_Header_Settings_CallRecording)).feature(ZgFeatures.CALL_RECORDING)
            .add(ZgRow.disclosure(2601, res(R.string.ZG_CallRecording_List_Title))
                .icon("waveform", R.drawable.msg_voicechat2, Tile.RED)
                .action((screen, row) -> screen.presentFragment(new org.telegram.zenlygram.callrecording.ZgCallRecordingsActivity()))) // WP-B: call recording
            .add(ZgRow.toggle(2602, res(R.string.ZG_Settings_CallRecording_Notify), bool(ZgSettings.callRecordingNotifyOtherParty))
                .description(res(R.string.ZG_Settings_CallRecording_Notify_Desc))
                .icon("bell.badge", R.drawable.msg_tone_on, Tile.ORANGE))
            .add(ZgRow.toggle(2603, res(R.string.ZG_Settings_CallRecording_SendToSaved), bool(ZgSettings.callRecordingSendToSavedMessages))
                .description(res(R.string.ZG_Settings_CallRecording_SendToSaved_Desc))
                .icon("bookmark", R.drawable.msg_saved, Tile.BLUE))
            .add(ZgRow.notice(2604, res(R.string.ZG_Settings_CallRecording_Notice))));

        // 27. Advanced. iOS: Content Settings only when the web settings allow it, Auto Proxy only while the bundled
        // proxy list is not empty (both are availableWhen conditions of their features). Android: Content Settings
        // opens Telegram's own "Show 18+ Content" switch (Chat Settings, with Telegram's confirmation and age check),
        // offered only while the server lets the account change it, like Telegram's own row.
        sections.add(new ZgSection(SECTION_ADVANCED, res(R.string.ZG_Header_Settings_Advanced))
            .add(ZgRow.disclosure(2701, res(R.string.ZG_Settings_ContentSettings))
                .icon("eye.trianglebadge.exclamationmark.fill", R.drawable.filled_sensitive, Tile.RED)
                .value(() -> ZgTelegramSettings.sensitiveContentValue(UserConfig.selectedAccount))
                .availableWhen(() -> ZgTelegramSettings.canChangeSensitiveContent(UserConfig.selectedAccount))
                .action((screen, row) -> screen.presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC).highlightSensitiveRow()))
                .feature(ZgFeatures.CONTENT_SETTINGS))
            .add(ZgRow.toggle(2702, res(R.string.ZG_Settings_AutoProxy), bool(ZgSettings.autoProxyEnabled))
                .description(res(R.string.ZG_Settings_AutoProxy_Desc))
                .icon("lock.shield", R.drawable.outline_shield_check, Tile.GREEN)
                .newBadge("autoProxyEnabled")
                .feature(ZgFeatures.AUTO_PROXY))
            .add(ZgRow.notice(2703, res(R.string.ZG_Settings_AutoProxy_Notice)).feature(ZgFeatures.AUTO_PROXY)));

        // 28. Holiday effects (moved to the bottom by Zenlygram iOS); Snow and Lightning exclude each other. iOS took the
        // availability from the Swiftgram server; Android offers them from 20 December to 10 January
        // (org.telegram.zenlygram.appearance.ZgHolidayEffects).
        sections.add(new ZgSection(SECTION_HOLIDAY, res(R.string.ZG_Header_Settings_NY)).feature(ZgFeatures.HOLIDAY_EFFECTS)
            .availableWhen(ZgHolidayEffects::isAvailable)
            .add(ZgRow.check(2801, res(R.string.ZG_Settings_NY_Style_snow),
                choiceIs(ZgSettings.nyStyle, ZgSettings.HolidayStyle.SNOW, ZgSettings.HolidayStyle.DEFAULT)))
            .add(ZgRow.check(2802, res(R.string.ZG_Settings_NY_Style_lightning),
                choiceIs(ZgSettings.nyStyle, ZgSettings.HolidayStyle.LIGHTNING, ZgSettings.HolidayStyle.DEFAULT)))
            .add(ZgRow.notice(2803, res(R.string.ZG_Settings_NY_Notice))));

        // ZENLYGRAM WP-C BEGIN — Account & data (iOS hub block ACCOUNT & DATA: Telegram Account, Data & Consent, Privacy
        // Policy; Android adds the account deletion row). Always visible: Google Play needs an in-app account deletion
        // path. The hub shows this section between Main features and Pro; it holds the only Privacy Policy row.
        sections.add(new ZgSection(SECTION_DATA, res(R.string.ZGL_Hub_AccountData_Header)).feature(ZgFeatures.CORE)
            // The Telegram account this device is linked to (iOS proLinkAccount): an identity step for the data consent
            // and Cloud Tasks, never a purchase, so it is here whether or not a Pro row is shown. The value is the
            // client's stored session, read locally; the hub rebuilds on zenlygramProStatusChanged.
            .add(ZgRow.disclosure(ROW_DATA_TELEGRAM_ACCOUNT, res(R.string.ZG_Settings_Pro_LinkAccount))
                .icon("person.badge.key.fill", R.drawable.msg_contact, Tile.BLUE)
                .value(() -> ZgLinkAccountActivity.stateLabel(UserConfig.selectedAccount))
                .visibleWhen(ZgBackend::enabled)
                .action((screen, row) -> screen.presentFragment(new ZgLinkAccountActivity())))
            .add(ZgRow.disclosure(ROW_DATA_CONSENT, res(R.string.ZGC_Settings_Data_Consent))
                .icon("hand.raised.fill", R.drawable.msg_policy, Tile.BLUE)
                .value(() -> org.telegram.zenlygram.customer.ZgCustomer.stateLabel(UserConfig.selectedAccount))
                .action((screen, row) -> screen.presentFragment(new org.telegram.zenlygram.customer.ZgDataConsentActivity())))
            .add(ZgRow.disclosure(ROW_DATA_DELETE, res(R.string.ZGC_DataConsent_Delete))
                .icon("trash.fill", R.drawable.msg_delete, Tile.RED)
                .action((screen, row) -> screen.presentFragment(new org.telegram.zenlygram.customer.ZgAccountDeleteActivity())))
            .add(ZgRow.disclosure(ROW_DATA_PRIVACY, res(R.string.ZGC_Settings_Data_PrivacyPolicy))
                .icon("doc.text.fill", R.drawable.msg_report, Tile.GRAY)
                .visibleWhen(() -> ZgConfig.PRIVACY_POLICY_URL != null && ZgConfig.PRIVACY_POLICY_URL.startsWith("https://"))
                .action((screen, row) -> screen.openUrl(ZgConfig.PRIVACY_POLICY_URL))));
        // ZENLYGRAM WP-C END

        // 90. About Zenlygram (Android only)
        final ZgSection about = new ZgSection(SECTION_ABOUT, res(R.string.ZG_Header_About)).feature(ZgFeatures.CORE)
            .add(ZgRow.disclosure(ROW_ABOUT_VERSION, ZgSettingsSpec::versionTitle)
                .description(format(R.string.ZG_About_BasedOn, literal(ZgConfig.UPSTREAM_TELEGRAM_VERSION)))
                .icon("info.circle.fill", R.drawable.zg_glyph, Tile.BRAND_LOGO)
                .action((screen, row) -> screen.onVersionRowTapped(versionText())))
            .add(ZgRow.disclosure(ROW_ABOUT_WEBSITE, res(R.string.ZG_About_Website))
                .value(literal(ZgConfig.WEBSITE_DISPLAY_NAME))
                .icon("globe", R.drawable.msg_language, Tile.BLUE)
                .action((screen, row) -> screen.openUrl(ZgConfig.WEBSITE_URL)));
        if (ZgConfig.SOURCE_CODE_URL != null) {
            about.add(ZgRow.disclosure(ROW_ABOUT_SOURCE_CODE, res(R.string.ZG_About_SourceCode))
                .description(literal(ZgConfig.SOURCE_CODE_URL))
                .icon("chevron.left.forwardslash.chevron.right", R.drawable.iv_code, Tile.GRAY)
                .action((screen, row) -> screen.openUrl(ZgConfig.SOURCE_CODE_URL)));
        } else {
            about.add(ZgRow.info(ROW_ABOUT_SOURCE_CODE, res(R.string.ZG_About_SourceCode))
                .description(res(R.string.ZG_About_SourceCode_Soon))
                .icon("chevron.left.forwardslash.chevron.right", R.drawable.iv_code, Tile.GRAY));
        }
        about.add(ZgRow.notice(9004, concat(res(R.string.ZG_Auth_UnofficialClientNotice),
            concat(literal(" "), res(R.string.ZG_About_NotAffiliated)))));
        sections.add(about);

        // 91. Developer: debug builds only, after seven taps on the version row (or while Preview all rows is on, so it
        // can always be turned off again).
        sections.add(new ZgSection(SECTION_DEVELOPER, res(R.string.ZG_Header_Developer)).feature(ZgFeatures.CORE)
            .visibleWhen(ZgSettings::isDeveloperSectionShown)
            .add(ZgRow.toggle(ROW_DEVELOPER_PREVIEW, res(R.string.ZG_Developer_PreviewAllRows), bool(ZgSettings.devPreviewAllRows))
                .description(res(R.string.ZG_Developer_PreviewAllRows_Desc))
                .icon("eye.fill", R.drawable.msg_settings, Tile.GRAY))
            // Debug only, English only: Zenlygram Pro as if the policy were paid and the account in the chosen state.
            .add(ZgRow.selector(ROW_DEVELOPER_PRO_PREVIEW, literal("Preview Pro state"), ZgSettings.devProPreview,
                    new ZgRow.Option(ZgSettings.ProPreview.OFF, literal("Off (the real state)")),
                    new ZgRow.Option(ZgSettings.ProPreview.GRACE, literal("Paid: free until the end of the local grace")),
                    new ZgRow.Option(ZgSettings.ProPreview.INACTIVE, literal("Paid: not active (never subscribed)")),
                    new ZgRow.Option(ZgSettings.ProPreview.LAPSED, literal("Paid: subscription ended")),
                    new ZgRow.Option(ZgSettings.ProPreview.RECONNECT, literal("Paid: reconnect")),
                    new ZgRow.Option(ZgSettings.ProPreview.ACTIVE, literal("Paid: active")))
                .onChanged((screen, row) -> ZgPro.onPreviewChanged())
                .icon("star.circle.fill", R.drawable.msg_premium_liststar, Tile.GOLD)));

        return sections;
    }

    /** "Zenlygram for Android v13.2.1 (7291)". */
    static CharSequence versionTitle() {
        return formatString(R.string.ZG_TelegramVersion,
            String.format(Locale.US, "v%s (%d)", ZgConfig.getAppVersionName(), ZgConfig.getAppVersionBuild()));
    }

    /** What the version row copies: both lines, as shown ("Zenlygram for Android v13.2.1 (7291)\nBased on ..."). */
    static String versionText() {
        return versionTitle() + "\n" + formatString(R.string.ZG_About_BasedOn, ZgConfig.UPSTREAM_TELEGRAM_VERSION);
    }

    /** Tap hint of a row disabled while the switch {@code title} is on. */
    private static ZgRow.Text turnOffFirst(ZgRow.Text title) {
        return format(R.string.ZG_Settings_DisabledHint_TurnOff, title);
    }

    /** Tap hint of a row disabled while the switch {@code title} is off. */
    private static ZgRow.Text turnOnFirst(ZgRow.Text title) {
        return format(R.string.ZG_Settings_DisabledHint_TurnOn, title);
    }

    private static ZgRow.Text quoted(int resId) {
        return () -> "\"" + getString(resId) + "\"";
    }
}
