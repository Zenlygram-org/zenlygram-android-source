/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlist;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.tasks.ZgTasks;

/**
 * Settings > Zenlygram > Tabs, read by the hooks in MainTabsActivity, MainTabsLayout, GlassTabView and DialogsActivity
 * (marked ZENLYGRAM).
 *
 * <p>Hide Tab bar, Show Tab Names and Wide Tab bar change the layout and need a restart, as on iOS: each is read once
 * per process and kept, so the main tabs and the chat list always agree, even when a fragment is rebuilt (theme
 * change, account switch) before the restart. "Contacts &amp; Calls in tab bar" is live: one Zenlygram key for both tabs,
 * mirrored into Telegram's per-account UserConfig.showCallsTab. Without the TAB_BAR feature every answer is the stock one.
 */
public final class ZgTabs {

    private static volatile Boolean tabBarHiddenSnapshot;
    private static volatile Boolean tabNamesSnapshot;
    private static volatile Boolean wideTabBarSnapshot;

    private ZgTabs() {
    }

    private static boolean ported() {
        return ZgFeatures.TAB_BAR.isPorted();
    }

    /** Hide Tab bar (restart): no bottom tab bar; its destinations are in the chat list's ⋮ menu. */
    public static boolean isTabBarHidden() {
        Boolean v = tabBarHiddenSnapshot;
        if (v == null) {
            v = ported() && ZgSettings.hideTabBar.get();
            tabBarHiddenSnapshot = v;
        }
        return v;
    }

    /** Show Tab Names (restart): false = icons only. */
    public static boolean showTabNames() {
        Boolean v = tabNamesSnapshot;
        if (v == null) {
            v = !ported() || ZgSettings.showTabNames.get();
            tabNamesSnapshot = v;
        }
        return v;
    }

    /** Wide Tab bar (restart): the bar spans the whole width instead of being capped and centred. */
    public static boolean isWideTabBar() {
        Boolean v = wideTabBarSnapshot;
        if (v == null) {
            v = ported() && ZgSettings.wideTabBar.get();
            wideTabBarSnapshot = v;
        }
        return v;
    }

    /**
     * The Contacts tab (live): shown with the one "Contacts &amp; Calls in tab bar" switch (iOS lenta-v6
     * showContactsAndCallsTabs, off by default; Contacts then opens from Telegram's Settings).
     */
    public static boolean showContactsTab() {
        return !ported() || ZgSettings.showContactsAndCallsTabs.get();
    }

    /** "Contacts &amp; Calls in tab bar" (Settings &gt; Zenlygram, the first row of the hub). */
    public static boolean showContactsAndCallsTabs() {
        return ported() && ZgSettings.showContactsAndCallsTabs.get();
    }

    /**
     * Turns both tabs on or off, live: the Zenlygram key for Contacts and Telegram's per-account Calls tab
     * (UserConfig.showCallsTab) of every logged-in account, so switching accounts never brings one back.
     */
    public static void setContactsAndCallsTabs(boolean show) {
        if (!ported()) {
            return;
        }
        ZgSettings.showContactsAndCallsTabs.set(show);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                setShowCallsTab(a, show);
            }
        }
        org.telegram.zenlygram.core.ZgUiEvents.post(ZgSettings.showContactsAndCallsTabs.key);
    }

    /**
     * Before the main tabs read anything (MainTabsActivity): the one-time move of an existing install to the new default
     * (iOS SGSimpleSettings.contactsAndCallsTabsMigrated: both tabs leave the bar), then {@code account}'s Calls tab is
     * brought in line with the switch (an account logged in later starts with Telegram's own value).
     */
    public static void prepare(int account) {
        if (!ported()) {
            return;
        }
        if (!ZgSettings.contactsAndCallsTabsMigrated.get()) {
            ZgSettings.contactsAndCallsTabsMigrated.set(true);
            ZgSettings.showContactsAndCallsTabs.set(false);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final UserConfig config = UserConfig.getInstance(a);
                if (config.isClientActivated() && config.showCallsTab) {
                    config.setShowCallsTab(false);
                }
            }
        }
        final UserConfig config = UserConfig.getInstance(account);
        final boolean show = ZgSettings.showContactsAndCallsTabs.get();
        if (config.showCallsTab != show) {
            config.setShowCallsTab(show);
        }
    }

    /**
     * Telegram's own "Show / Hide Calls tab" items (Calls tab menu, call log): with the tab bar ported they move the one
     * switch, so Contacts follows and the switch never disagrees with the bar. False: not handled, run the stock code.
     */
    public static boolean onTelegramCallsTabToggle(boolean show) {
        if (!ported()) {
            return false;
        }
        setContactsAndCallsTabs(show);
        return true;
    }

    /**
     * Whether Telegram's Settings lists Contacts and Recent Calls (iOS lenta-v6 PeerInfoSettingsItems): while their tabs
     * are not in the bar.
     */
    public static boolean settingsListsContactsAndCalls() {
        return ported() && (!ZgSettings.showContactsAndCallsTabs.get() || isTabBarHidden());
    }

    /** Opens Contacts as a pushed screen (Telegram's Settings row, the hub, the ⋮ menu). */
    public static void openContacts(org.telegram.ui.ActionBar.BaseFragment fragment) {
        final Bundle args = new Bundle();
        args.putBoolean("needPhonebook", true);
        args.putBoolean("needFinishFragment", false);
        fragment.presentFragment(new ContactsActivity(args));
    }

    /** Opens Recent Calls as a pushed screen. */
    public static void openCalls(org.telegram.ui.ActionBar.BaseFragment fragment) {
        final Bundle args = new Bundle();
        args.putBoolean("needFinishFragment", false); // like Telegram's Recent Calls item: opening a chat keeps the list
        fragment.presentFragment(new CallLogActivity(args));
    }

    /**
     * Whether a swipe of the main pager must not start: always while the tab bar is hidden (only Chats is reachable
     * by swiping), and when the swipe would land on the hidden Contacts page. Tab taps are not affected.
     */
    public static boolean blocksPagerScroll(int currentPosition, boolean forward, int contactsPosition) {
        if (!ported()) {
            return false;
        }
        if (isTabBarHidden()) {
            return true;
        }
        if (!showContactsTab()) {
            return forward ? currentPosition + 1 == contactsPosition : currentPosition - 1 == contactsPosition;
        }
        return false;
    }

    /** Show Calls Tab for {@code account}: the same as Telegram's CallLogActivity.setCallsTabVisible. */
    public static void setShowCallsTab(int account, boolean show) {
        final UserConfig config = UserConfig.getInstance(account);
        if (config.showCallsTab == show) {
            return;
        }
        config.setShowCallsTab(show);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.callTabsVisibleToggled);
    }

    /**
     * The tab bar's destinations, added to the chat list's ⋮ menu while the tab bar is hidden (Android has no side
     * menu, so this is the only way to them). Settings is left out when Telegram already added it (Calls tab on).
     */
    public static void addHiddenTabBarItems(DialogsActivity fragment, ItemOptions io, Runnable openAccounts) {
        if (fragment == null || io == null) {
            return;
        }
        io.addGap();
        if (ZgFeatures.TASKS.isPorted() && ZgTasks.isTabVisible()) {
            // The Tasks tab (Show Tasks Tab) hides with the bar: its board opens as a screen with a back arrow.
            io.add(R.drawable.msg_list, getString(R.string.ZG_tasks_tab), () -> ZgTasks.open(fragment));
        }
        io.add(R.drawable.msg_contacts, getString(R.string.Contacts), () -> {
            final Bundle args = new Bundle();
            args.putBoolean("needPhonebook", true);
            args.putBoolean("needFinishFragment", false);
            fragment.presentFragment(new ContactsActivity(args));
        });
        io.add(R.drawable.msg_calls, getString(R.string.Calls), () -> {
            final Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false); // like Telegram's Recent Calls item: opening a chat keeps the list
            fragment.presentFragment(new CallLogActivity(args));
        });
        if (!fragment.getUserConfig().showCallsTab) {
            io.add(R.drawable.msg_settings_old, getString(R.string.Settings), () -> fragment.presentFragment(new SettingsActivity()));
        }
        io.add(R.drawable.msg_openprofile, getString(R.string.MyProfile), () -> {
            final Bundle args = new Bundle();
            args.putLong("user_id", fragment.getUserConfig().getClientUserId());
            args.putBoolean("my_profile", true);
            fragment.presentFragment(new ProfileActivity(args));
        });
        if (openAccounts != null) {
            // Opened after this menu has started to close, so the two popups do not fight over the scrim.
            io.add(R.drawable.msg_contact, getString(R.string.ZG_FoldersTabsStories_SwitchAccount), () -> AndroidUtilities.runOnUIThread(openAccounts, 150));
        }
    }
}
