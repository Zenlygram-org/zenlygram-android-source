/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.pro.ZgPro;
import org.telegram.zenlygram.pro.ZgProActivity;
import org.telegram.zenlygram.pro.ZgProText;
import org.telegram.zenlygram.settings.ZgSettingsIcons.Tile;

import java.util.ArrayList;

/**
 * The Zenlygram card of Telegram's main Settings list (iOS PeerInfoSettingsItems, section .swiftgram): its own card
 * above "My Account", with "Zenlygram Pro" and "Zenlygram". No subtitles and never a NEW badge (permanent entry
 * points never carry one on iOS either).
 *
 * <p>Hooks in SettingsActivity (marked ZENLYGRAM): {@link #addEntries} in fillItems, and one case for
 * {@link #ID_PRO} / {@link #ID_SETTINGS} in onClick that opens {@link #createFragment} with presentSettingFragment,
 * so tablets open the screen in the right-hand column. A hook in ProfileActivity.SearchAdapter (Telegram's Settings
 * search) adds the two rows as search results ({@link #createSearchResult}).
 */
public final class ZgSettingsEntry {

    /** Row ids in SettingsActivity. Telegram uses 1-27 there; attach-menu bots never reach its id switch. */
    public static final int ID_PRO = 1000;
    public static final int ID_SETTINGS = 1001;
    /** Contacts and Recent Calls (iOS lenta-v6 PeerInfoSettingsItems), while their tabs are not in the bar. */
    public static final int ID_CONTACTS = 1010;
    public static final int ID_CALLS = 1011;

    /**
     * Ids of the two rows in Telegram's Settings search. Recent searches are stored by this id
     * (settingsSearchRecent2), so they never change; Telegram's own results use 1-999.
     */
    private static final int SEARCH_ID_PRO = 99000;
    private static final int SEARCH_ID_SETTINGS = 99001;

    private ZgSettingsEntry() {
    }

    /**
     * Adds the card. "Zenlygram" is the white Z (drawable/zg_glyph) on the black tile, "Zenlygram Pro" the light-blue
     * Z (drawable/zg_glyph_pro) on the navy tile, so the two rows are told apart at a glance in light and dark themes.
     */
    public static void addEntries(BaseFragment fragment, ArrayList<UItem> items) {
        if (ZgFeatures.PRO_STATUS.isPorted()) {
            items.add(SettingsActivity.SettingCell.Factory.of(ID_PRO, Tile.BRAND_PRO.top, Tile.BRAND_PRO.bottom,
                R.drawable.zg_glyph_pro, getString(R.string.ZG_SettingsProEntry)));
        }
        items.add(SettingsActivity.SettingCell.Factory.of(ID_SETTINGS, Tile.BRAND_LOGO.top, Tile.BRAND_LOGO.bottom,
            R.drawable.zg_glyph, getString(R.string.ZG_AppName)));
        items.add(UItem.asShadow(null));
        if (org.telegram.zenlygram.chatlist.ZgTabs.settingsListsContactsAndCalls()) {
            // "Contacts & Calls in tab bar" is off (the default): both screens open from here (iOS lenta-v6).
            items.add(SettingsActivity.SettingCell.Factory.of(ID_CONTACTS, Tile.GREEN.top, Tile.GREEN.bottom,
                R.drawable.msg_contacts, getString(R.string.Contacts)));
            items.add(SettingsActivity.SettingCell.Factory.of(ID_CALLS, Tile.GREEN.top, Tile.GREEN.bottom,
                R.drawable.msg_calls, getString(R.string.RecentCalls)));
            items.add(UItem.asShadow(null));
        }
    }

    /** A tap on a row of {@link #addEntries} that is not a screen of its own: true when handled. */
    public static boolean onClick(BaseFragment fragment, int id) {
        if (id == ID_CONTACTS) {
            org.telegram.zenlygram.chatlist.ZgTabs.openContacts(fragment);
            return true;
        } else if (id == ID_CALLS) {
            org.telegram.zenlygram.chatlist.ZgTabs.openCalls(fragment);
            return true;
        }
        return false;
    }

    /**
     * The search result for the card row {@code id} ({@link #ID_SETTINGS} or {@link #ID_PRO}) in Telegram's Settings
     * search, or null when that row is not shown in the card either.
     */
    public static ProfileActivity.SearchAdapter.SearchResult createSearchResult(BaseFragment fragment, int id) {
        if (id == ID_PRO) {
            if (!ZgFeatures.PRO_STATUS.isPorted()) {
                return null;
            }
            return new ProfileActivity.SearchAdapter.SearchResult(SEARCH_ID_PRO, getString(R.string.ZG_SettingsProEntry),
                R.drawable.zg_glyph, () -> fragment.presentFragment(createProFragment()));
        }
        return new ProfileActivity.SearchAdapter.SearchResult(SEARCH_ID_SETTINGS, getString(R.string.ZG_AppName),
            R.drawable.zg_glyph, () -> fragment.presentFragment(new ZenlygramSettingsActivity()));
    }

    /** The screen behind an entry row. */
    public static BaseFragment createFragment(int id) {
        if (id == ID_PRO) {
            return createProFragment();
        }
        return new ZenlygramSettingsActivity();
    }

    /**
     * The "Zenlygram Pro" screen (status, and in the Google Play build the plans while Pro is a subscription);
     * ZgProGate also opens it when a create is blocked.
     */
    public static BaseFragment createProFragment() {
        return new ZgProActivity();
    }

    /**
     * Value of the Subscription row (iOS: Free / Free until … / Pro available / Off), from the selected account's
     * ZgPro decision.
     */
    public static CharSequence getProStatusValue() {
        final int account = UserConfig.selectedAccount;
        return ZgProText.hubValue(ZgPro.getAccess(account), ZgPro.getAccessUntil(account));
    }
}
