/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.profile;

import static org.telegram.messenger.LocaleController.getString;

import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.ProfileActivity;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;

/**
 * The ID, data center, creation date and registration date rows of user, group and channel profiles (iOS
 * PeerInfoProfileItems, section .swiftgram), plus the "ID: …" row of the main Settings list (iOS PeerInfoSettingsItems).
 *
 * <p>iOS shows the profile rows in a card of their own above the info card. Android appends them to the existing info
 * card of ProfileActivity, just before its notifications row (a card of their own would need extra header and shadow
 * rows in ProfileActivity's single adapter). The order is the iOS one: ID, DC, Created, Registered. Each row is a
 * TextDetailCell (value on the main line, label on the grey line, like the phone and username rows); tap or long-press
 * opens Copy (and Learn More on the data center row).
 *
 * <p>Hooks in ProfileActivity (marked ZENLYGRAM): the {@link Rows} field; {@link Rows#reset} next to the other row
 * resets of updateRowsIds; {@link Rows#fillUser} / {@link Rows#fillChat} in the user card and the chat branch;
 * {@link Rows#contains} in getItemViewType, onBindViewHolder ({@link Rows#bind}) and processOnClickOrPress
 * ({@link Rows#onClick}); {@link Rows#fillPositions} in DiffCallback.fillPositions. In SettingsActivity:
 * {@link #addSettingsIdRow} in fillItems and a case for {@link #SETTINGS_ROW_ID} in onClick.
 *
 * <p>Everything here runs on the UI thread. The switches are read on every row rebuild, so a change in Settings >
 * Zenlygram applies to the next profile shown, without a restart (iOS re-reads them on every render too).
 */
public final class ZgProfileInfo {

    public static final int KIND_ID = 0;
    public static final int KIND_DC = 1;
    public static final int KIND_CREATED = 2;
    public static final int KIND_REGISTERED = 3;
    public static final int KIND_COUNT = 4;

    /** Item id of the "ID: …" row in SettingsActivity. Telegram uses 1-27 there, ZgSettingsEntry 1000-1001. */
    public static final int SETTINGS_ROW_ID = 1002;

    /** Where the data center row's "Learn More" leads (iOS PeerInfoScreenOpenPeerInfoContextMenu .aboutDC). */
    static final String DATA_CENTER_INFO_URL = "https://core.telegram.org/api/datacenter";

    /** Telegram launched in 2013: an older registration month is a malformed value. */
    private static final int FIRST_REGISTRATION_YEAR = 2013;

    private ZgProfileInfo() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Switches (the feature flag is the kill switch, the setting is the user's choice)
    // ---------------------------------------------------------------------------------------------------------

    public static boolean showProfileId() {
        return ZgFeatures.PROFILE_ID.isPorted() && ZgSettings.showProfileId.get();
    }

    public static boolean showDataCenter() {
        return ZgFeatures.DATA_CENTER.isPorted() && ZgSettings.showDC.get();
    }

    public static boolean showCreationDate() {
        return ZgFeatures.CREATION_DATE.isPorted() && ZgSettings.showCreationDate.get();
    }

    public static boolean showRegistrationDate() {
        return ZgFeatures.REGISTRATION_DATE.isPorted() && ZgSettings.showRegDate.get();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Main Settings: "ID: <own id>" under the header card (iOS .edit section, accent action row)
    // ---------------------------------------------------------------------------------------------------------

    /** Adds the "ID: …" row and the shadow under it, when Show Profile ID is on. */
    public static void addSettingsIdRow(BaseFragment fragment, ArrayList<UItem> items) {
        if (fragment == null || items == null || !showProfileId()) {
            return;
        }
        final long selfId = fragment.getUserConfig().getClientUserId();
        if (selfId == 0) {
            return;
        }
        items.add(UItem.asButton(SETTINGS_ROW_ID, "ID: " + selfId).accent());
        items.add(UItem.asShadow(null));
    }

    /** A tap on the "ID: …" row copies the own ID (iOS copies it and shows "Text copied"). */
    public static void onSettingsIdClick(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        final long selfId = fragment.getUserConfig().getClientUserId();
        if (selfId != 0) {
            copy(fragment, String.valueOf(selfId));
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rows of one ProfileActivity
    // ---------------------------------------------------------------------------------------------------------

    static final class Entry {
        final int kind;
        /** Main line of the cell. */
        final CharSequence text;
        /** Grey line of the cell. */
        final String label;
        /** What Copy puts on the clipboard (plain text). */
        final String copyText;

        Entry(int kind, CharSequence text, String label, String copyText) {
            this.kind = kind;
            this.text = text;
            this.label = label;
            this.copyText = copyText;
        }
    }

    /**
     * The rows of one ProfileActivity: one fixed slot per kind. Entries and positions are rebuilt together, only in
     * updateRowsIds ({@link #reset} + fill), so they never drift apart; {@link #bind} and {@link #onClick} check bounds.
     */
    public static final class Rows {
        private final Entry[] entries = new Entry[KIND_COUNT];
        private final int[] positions = new int[KIND_COUNT];
        /** The text each row was last shown with, and which of its two stable ids it has (see {@link #fillPositions}). */
        private final String[] shownTexts = new String[KIND_COUNT];
        private final int[] variants = new int[KIND_COUNT];
        private WeakReference<ProfileActivity> fragmentRef;
        /** Shows the creation date row once its network reply is in (the same instance every time, see ZgChatCreationDate). */
        private final Runnable refresh = () -> {
            final ProfileActivity fragment = fragmentRef != null ? fragmentRef.get() : null;
            if (fragment != null && !fragment.isFinished && fragment.getFragmentView() != null) {
                fragment.updateListAnimated(false);
            }
        };

        public Rows() {
            Arrays.fill(positions, -1);
        }

        /** Forgets the previous rows; updateRowsIds calls it next to the other row resets. */
        public void reset() {
            Arrays.fill(positions, -1);
            Arrays.fill(entries, null);
        }

        public boolean contains(int position) {
            return kindAt(position) >= 0;
        }

        private int kindAt(int position) {
            if (position < 0) {
                return -1;
            }
            for (int kind = 0; kind < KIND_COUNT; kind++) {
                if (positions[kind] == position) {
                    return kind;
                }
            }
            return -1;
        }

        /** Adds the rows of a user profile (other users, bots, My Profile, secret chats) and returns the new row count. */
        public int fillUser(ProfileActivity fragment, int rowCount, int account, long userId, TLRPC.UserFull userInfo) {
            fragmentRef = new WeakReference<>(fragment);
            if (userId == 0) {
                return rowCount;
            }
            final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
            if (userInfo != null && userInfo.id != 0 && userInfo.id != userId) {
                userInfo = null;
            }
            if (showProfileId()) {
                try {
                    entries[KIND_ID] = idEntry(String.valueOf(userId));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (showDataCenter()) {
                try {
                    entries[KIND_DC] = dataCenterEntry(userDataCenter(account, userId, user, userInfo), phoneCountry(userInfo));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (showRegistrationDate()) {
                try {
                    entries[KIND_REGISTERED] = registrationEntry(userInfo);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            return assignPositions(rowCount);
        }

        /** Adds the rows of a group or channel profile (not of a topic) and returns the new row count. */
        public int fillChat(ProfileActivity fragment, int rowCount, int account, TLRPC.Chat chat, TLRPC.ChatFull chatInfo) {
            fragmentRef = new WeakReference<>(fragment);
            if (chat == null || chat.id == 0) {
                return rowCount;
            }
            if (chatInfo != null && chatInfo.id != 0 && chatInfo.id != chat.id) {
                chatInfo = null;
            }
            if (showProfileId()) {
                try {
                    // iOS: "-100" + id for channels and supergroups, the plain id for basic groups
                    entries[KIND_ID] = idEntry(ChatObject.isChannel(chat) ? "-100" + chat.id : String.valueOf(chat.id));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (showDataCenter()) {
                try {
                    entries[KIND_DC] = dataCenterEntry(chatDataCenter(chat, chatInfo), null);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (showCreationDate()) {
                try {
                    final int date = ZgChatCreationDate.get(account, chat, chatInfo, refresh);
                    if (date > 0) {
                        final String text = LocaleController.getInstance().getChatFullDate().format((long) date * 1000L);
                        entries[KIND_CREATED] = new Entry(KIND_CREATED, text, getString(R.string.ZG_Profile_CreationDate), text);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            return assignPositions(rowCount);
        }

        private int assignPositions(int rowCount) {
            for (int kind = 0; kind < KIND_COUNT; kind++) {
                positions[kind] = entries[kind] != null ? rowCount++ : -1;
                if (entries[kind] != null) {
                    final String text = String.valueOf(entries[kind].text);
                    if (shownTexts[kind] != null && !shownTexts[kind].equals(text)) {
                        // Same row, new text ("DC 4" -> "DC 4 · country"): ProfileActivity's DiffUtil compares ids only,
                        // so the row takes its other id and is rebound.
                        variants[kind] ^= 1;
                    }
                    shownTexts[kind] = text;
                }
            }
            return rowCount;
        }

        /** Binds the row at {@code position} (a VIEW_TYPE_TEXT_DETAIL cell). */
        public void bind(TextDetailCell cell, int position) {
            if (cell == null) {
                return;
            }
            final int kind = kindAt(position);
            final Entry entry = kind >= 0 ? entries[kind] : null;
            if (entry == null) {
                cell.setTextAndValue("", "", false);
                return;
            }
            cell.setTextAndValue(entry.text, entry.label, false);
            cell.setContentDescriptionValueFirst(true);
        }

        /** Tap or long-press: Copy, plus Learn More on the data center row. Returns whether a menu was shown. */
        public boolean onClick(ProfileActivity fragment, View view, int position, Drawable scrimBackground) {
            final int kind = kindAt(position);
            final Entry entry = kind >= 0 ? entries[kind] : null;
            if (entry == null || fragment == null || view == null || fragment.getParentActivity() == null) {
                return false;
            }
            try {
                final String copyText = entry.copyText;
                final ItemOptions options = ItemOptions.makeOptions(fragment, view);
                options.setScrimViewBackground(scrimBackground);
                options.setLongPressSelectionEnabled(false);
                options.add(R.drawable.msg_copy, getString(R.string.Copy), () -> copy(fragment, copyText));
                if (kind == KIND_DC) {
                    options.add(R.drawable.msg_info, getString(R.string.LearnMore), () -> {
                        if (fragment.getParentActivity() != null) {
                            Browser.openUrl(fragment.getParentActivity(), DATA_CENTER_INFO_URL);
                        }
                    });
                }
                options.show();
                return true;
            } catch (Throwable e) {
                FileLog.e(e);
                return false;
            }
        }

        /**
         * DiffCallback.fillPositions: two stable ids per kind, so a row that appears later animates in, and a row whose
         * text changed in place (ids equal means contents equal there) switches to its other id and is rebound.
         */
        public int fillPositions(int pointer, SparseIntArray positionToItem) {
            for (int kind = 0; kind < KIND_COUNT; kind++) {
                pointer += 2;
                if (positions[kind] >= 0) {
                    positionToItem.put(positions[kind], pointer - 1 + variants[kind]);
                }
            }
            return pointer;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Entries
    // ---------------------------------------------------------------------------------------------------------

    private static Entry idEntry(String id) {
        // LRM: a leading '-' stays on the left in right-to-left layouts
        return new Entry(KIND_ID, "‎" + id, "ID", id);
    }

    /** "DC 2 · 🇺🇿 Uzbekistan", "DC 2", or "DC ? · 🇺🇿 Uzbekistan"; null when neither part is known (iOS rule). */
    private static Entry dataCenterEntry(int dcId, String countryCode) {
        if (dcId <= 0 && countryCode == null) {
            return null;
        }
        final String dc = "DC " + (dcId > 0 ? String.valueOf(dcId) : "?");
        final SpannableStringBuilder text = new SpannableStringBuilder(dc);
        final StringBuilder copy = new StringBuilder(dc);
        if (countryCode != null) {
            text.append(" · ").append(countryText(countryCode));
            copy.append(" · ").append(countryName(countryCode));
        }
        return new Entry(KIND_DC, text, getString(R.string.ZG_Profile_DataCenter), copy.toString());
    }

    /** Telegram's server sends the registration month as "MM.YYYY" (UserFull.settings, as for the chat intro card). */
    private static Entry registrationEntry(TLRPC.UserFull userInfo) {
        if (userInfo == null || userInfo.settings == null) {
            return null;
        }
        final String text = formatRegistrationMonth(userInfo.settings.registration_month);
        if (text == null) {
            return null;
        }
        return new Entry(KIND_REGISTERED, text, getString(R.string.ZG_Chat_RegDate), text);
    }

    /** "March 2019" for "03.2019", null for anything malformed (UserInfoCell.displayDate would throw). */
    static String formatRegistrationMonth(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        final String[] parts = value.trim().split("\\.");
        if (parts.length != 2) {
            return null;
        }
        final int month;
        final int year;
        try {
            month = Integer.parseInt(parts[0].trim());
            year = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        final Calendar calendar = Calendar.getInstance();
        final int maxYear = calendar.get(Calendar.YEAR) + 1;
        if (month < 1 || month > 12 || year < FIRST_REGISTRATION_YEAR || year > maxYear) {
            return null;
        }
        calendar.clear();
        calendar.set(year, month - 1, 1, 12, 0, 0);
        final String text = LocaleController.formatYearMont(calendar.getTimeInMillis() / 1000L, true);
        return "LOC_ERR".equals(text) ? null : text;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Data center (heuristic, as on iOS: the DC where the profile photo is stored)
    // ---------------------------------------------------------------------------------------------------------

    private static int userDataCenter(int account, long userId, TLRPC.User user, TLRPC.UserFull userInfo) {
        if (userId == UserConfig.getInstance(account).getClientUserId()) {
            // Own account: the exact home DC
            final int dc = ConnectionsManager.getInstance(account).getCurrentDatacenterId();
            if (dc > 0) {
                return dc;
            }
        }
        if (userInfo != null && isPhoto(userInfo.profile_photo)) {
            return userInfo.profile_photo.dc_id;
        }
        // A personal photo is one this account set for the contact: its DC is ours, not theirs
        if (user != null && user.photo != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty)
                && !user.photo.personal && user.photo.dc_id > 0) {
            return user.photo.dc_id;
        }
        if (userInfo != null && isPhoto(userInfo.fallback_photo)) {
            return userInfo.fallback_photo.dc_id;
        }
        return 0;
    }

    private static int chatDataCenter(TLRPC.Chat chat, TLRPC.ChatFull chatInfo) {
        if (chat.photo != null && !(chat.photo instanceof TLRPC.TL_chatPhotoEmpty) && chat.photo.dc_id > 0) {
            return chat.photo.dc_id;
        }
        if (chatInfo != null && isPhoto(chatInfo.chat_photo)) {
            return chatInfo.chat_photo.dc_id;
        }
        return 0;
    }

    private static boolean isPhoto(TLRPC.Photo photo) {
        return photo != null && !(photo instanceof TLRPC.TL_photoEmpty) && photo.dc_id > 0;
    }

    /** The two-letter phone country code the server sent for this user (null when unknown). */
    private static String phoneCountry(TLRPC.UserFull userInfo) {
        if (userInfo == null || userInfo.settings == null || TextUtils.isEmpty(userInfo.settings.phone_country)) {
            return null;
        }
        final String code = userInfo.settings.phone_country.trim().toUpperCase(Locale.US);
        return code.length() == 2 ? code : null;
    }

    /** Flag + localized country name; "FT" (an anonymous Fragment number) is "Anonymous" without a flag. */
    private static CharSequence countryText(String code) {
        if (isAnonymousNumber(code)) {
            return getString(R.string.ContactInfoPhoneFragment);
        }
        return LocaleController.getCountryWithFlag(code, 16, R.string.ContactInfoPhoneFragment);
    }

    private static String countryName(String code) {
        if (isAnonymousNumber(code)) {
            return getString(R.string.ContactInfoPhoneFragment);
        }
        final String name = LocaleController.getCountryName(code, R.string.ContactInfoPhoneFragment);
        return TextUtils.isEmpty(name) ? code : name;
    }

    private static boolean isAnonymousNumber(String code) {
        return "FT".equals(code);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void copy(BaseFragment fragment, String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        if (AndroidUtilities.addToClipboard(text)) {
            BulletinFactory.of(fragment).createCopyBulletin(getString(R.string.TextCopied)).show();
        }
    }
}
