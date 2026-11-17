/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.zenlygram.settings.ZgRow.res;

import org.telegram.messenger.R;
import org.telegram.zenlygram.chatlist.ZgTabs;
import org.telegram.zenlygram.chatlock.ZgChatLockCenter;
import org.telegram.zenlygram.chatlock.ZgChatLockFlows;
import org.telegram.zenlygram.chatlock.ZgChatLockStore;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.settings.ZgSettingsCatalog.Page;
import org.telegram.zenlygram.settings.ZgSettingsIcons.Tile;
import org.telegram.zenlygram.tasks.ZgTasks;

import java.util.ArrayList;

/**
 * The hub of Settings &gt; Zenlygram (iOS sgHubEntries): the first screen. From the top:
 *
 * <ol>
 *   <li>"Contacts &amp; Calls in tab bar" (iOS lenta-v6 quickTabs: the owner reaches for it first);</li>
 *   <li>MAIN FEATURES: Secret Vault (page), Chat Lock (the Chat Lock Center), the recovery pair while Chat Lock needs
 *       it, Tasks, Deleted &amp; Edited (page), Call Recording (page, while that feature is in the build);</li>
 *   <li>ACCOUNT &amp; DATA (iOS hub block of that name): the spec's SECTION_DATA, i.e. Telegram Account (linked / not
 *       linked, opens the link screen), Data &amp; Consent (consent, My data, withdraw), Delete my Zenlygram account and
 *       data, Privacy Policy;</li>
 *   <li>Zenlygram Pro (spec section 1);</li>
 *   <li>MORE SETTINGS: the nine group pages;</li>
 *   <li>then every other spec section placed on the hub (Android: About, Developer).</li>
 * </ol>
 *
 * A page row exists while its page has a row to show: it is live while one of them is live, greyed with Developer &gt;
 * Preview all rows when they are all previews, and its NEW pill is rolled up from the page's live rows.
 */
final class ZgSettingsHub {

    /** What the hub needs to know about a page, answered by the screen from the page's built rows. */
    interface Pages {
        /** 0 = nothing to show, 1 = only previewed rows, 2 = a live row. */
        int state(Page page);

        boolean hasNew(Page page);
    }

    static final int PAGE_HIDDEN = 0;
    static final int PAGE_PREVIEW = 1;
    static final int PAGE_LIVE = 2;

    private ZgSettingsHub() {
    }

    /** The hub's sections in display order, built around the spec sections placed on the hub. */
    static ArrayList<ZgSection> build(ArrayList<ZgSection> spec, Pages pages) {
        final ArrayList<ZgSection> hub = new ArrayList<>();

        // 1. Contacts & Calls in tab bar (no header: it sits right under the search, as on iOS).
        final ZgRow.Text tabBarHint = ZgRow.format(R.string.ZG_Settings_DisabledHint_TurnOff, res(R.string.ZG_Settings_Tabs_HideTabBar));
        hub.add(new ZgSection(ZgSettingsCatalog.SECTION_HUB_QUICK, null).feature(ZgFeatures.TAB_BAR)
            .add(ZgRow.toggle(ZgSettingsCatalog.ROW_CONTACTS_AND_CALLS, res(R.string.ZG_Tabs_ShowContactsAndCalls), new ZgRow.BoolValue() {
                    @Override
                    public boolean get() {
                        return ZgTabs.showContactsAndCallsTabs();
                    }

                    @Override
                    public void set(boolean value) {
                        ZgTabs.setContactsAndCallsTabs(value);
                    }
                })
                .description(res(R.string.ZG_Tabs_ShowContactsAndCalls_Desc))
                .icon("person.2.fill", R.drawable.msg_contacts, Tile.GREEN)
                .enabledWhen(() -> !ZgSettings.hideTabBar.get(), tabBarHint)));

        // 2. Main features
        hub.add(new ZgSection(ZgSettingsCatalog.SECTION_HUB_FEATURES, res(R.string.ZG_Hub_FeaturesHeader)).feature(ZgFeatures.CORE)
            .add(pageRow(Page.SECRET_VAULT, res(R.string.ZG_Settings_SecretVault_Title), "eye.slash.fill", R.drawable.msg_archive_hide, Tile.PURPLE, pages)
                .value(() -> secretVaultValue(pages.hasNew(Page.SECRET_VAULT))))
            .add(ZgRow.disclosure(ZgSettingsCatalog.ROW_CHAT_LOCK, res(R.string.ZG_Settings_ChatLock))
                .icon("lock.shield.fill", R.drawable.msg_secret, Tile.BLUE)
                .value(ZgChatLockCenter::hubValue)
                .newBadge("chatLock")
                .feature(ZgFeatures.CHAT_LOCK)
                .action((screen, row) -> ZgChatLockCenter.open(screen)))
            // Only the Chat Lock half of the recovery block lives here; the vault's is on the Secret Vault page.
            .add(ZgRow.notice(ZgSettingsCatalog.ROW_PROTECTION_RECOVERY_NOTICE, res(R.string.ZG_Hub_RecoveryNotice))
                .feature(ZgFeatures.CHAT_LOCK)
                .visibleWhen(ZgChatLockStore::needsRecovery))
            .add(ZgRow.disclosure(ZgSettingsCatalog.ROW_PROTECTION_RECOVERY, res(R.string.ZG_Hub_RestoreProtection))
                .icon("exclamationmark.shield.fill", R.drawable.msg_warning, Tile.ORANGE)
                .value(res(R.string.ZG_Hub_NeedsSetup))
                .feature(ZgFeatures.CHAT_LOCK)
                .visibleWhen(ZgChatLockStore::needsRecovery)
                .action((screen, row) -> ZgChatLockFlows.startRecovery(screen, screen::refresh)))
            .add(ZgRow.disclosure(ZgSettingsCatalog.ROW_TASKS, res(R.string.ZG_Settings_Tasks))
                .icon("checklist", R.drawable.msg_list, Tile.BLUE)
                .newBadge("tasks")
                .feature(ZgFeatures.TASKS)
                .action((screen, row) -> ZgTasks.open(screen)))
            .add(pageRow(Page.HISTORY, res(R.string.ZG_Hub_History), "clock.arrow.circlepath", R.drawable.msg_recent, Tile.TEAL, pages))
            .add(pageRow(Page.CALL_RECORDING, res(R.string.ZG_Hub_CallRecording), "waveform", R.drawable.msg_voicechat2, Tile.RED, pages)));

        // 3. Account & data (iOS ACCOUNT & DATA block): the spec's own section, which holds the one Privacy Policy row.
        // 4. Zenlygram Pro
        final ArrayList<ZgSection> rest = new ArrayList<>();
        ZgSection data = null;
        ZgSection pro = null;
        for (ZgSection section : spec) {
            if (ZgSettingsCatalog.pageOfSection(section.id) != null) {
                continue;
            }
            if (section.id == ZgSettingsSpec.SECTION_DATA) {
                data = section;
            } else if (section.id == ZgSettingsSpec.SECTION_PRO) {
                pro = section;
            } else {
                rest.add(section);
            }
        }
        if (data != null) {
            hub.add(data);
        }
        if (pro != null) {
            hub.add(pro);
        }

        // 5. More settings
        final ZgSection groups = new ZgSection(ZgSettingsCatalog.SECTION_HUB_GROUPS, res(R.string.ZG_Hub_GroupsHeader)).feature(ZgFeatures.CORE)
            .add(pageRow(Page.CHATS, res(R.string.ZG_Hub_Chats), "list.bullet.rectangle.fill", R.drawable.msg_discussion, Tile.LIGHT_BLUE, pages))
            .add(pageRow(Page.MESSAGES, res(R.string.ZG_Hub_Messages), "text.bubble.fill", R.drawable.msg_message, Tile.BLUE, pages))
            .add(pageRow(Page.MESSAGE_MENU, res(R.string.ZG_Hub_MessageMenu), "ellipsis.circle.fill", R.drawable.msg_list, Tile.ORANGE, pages))
            .add(pageRow(Page.TAB_BAR, res(R.string.ZG_Hub_TabBar), "rectangle.bottomthird.inset.filled", R.drawable.msg_viewchats, Tile.TEAL, pages))
            .add(pageRow(Page.PRIVACY, res(R.string.ZG_Hub_Privacy), "person.text.rectangle.fill", R.drawable.msg_secret, Tile.GREEN, pages))
            .add(pageRow(Page.TRANSLATION, res(R.string.ZG_Hub_Translation), "character.book.closed.fill", R.drawable.msg_translate, Tile.PINK, pages))
            .add(pageRow(Page.MEDIA, res(R.string.ZG_Hub_Media), "photo.on.rectangle.angled", R.drawable.msg_media, Tile.ORANGE, pages))
            .add(pageRow(Page.COLORS, res(R.string.ZG_Hub_Colors), "paintpalette.fill", R.drawable.msg_colors, Tile.PURPLE, pages))
            .add(pageRow(Page.CALLS_ADVANCED, res(R.string.ZG_Hub_CallsAdvanced), "gearshape.2.fill", R.drawable.msg_settings, Tile.GRAY, pages));
        hub.add(groups);

        // 6. Android: About, Developer (and any section added later without a page).
        hub.addAll(rest);
        return hub;
    }

    private static ZgRow pageRow(Page page, ZgRow.Text title, String symbol, int iconRes, Tile tile, Pages pages) {
        return ZgRow.disclosure(ZgSettingsCatalog.hubRowId(page), title)
            .icon(symbol, iconRes, tile)
            .feature(ZgFeatures.CORE)
            .visibleWhen(() -> pages.state(page) != PAGE_HIDDEN)
            .availableWhen(() -> pages.state(page) == PAGE_LIVE)
            .rollUpNew(() -> pages.hasNew(page))
            .action((screen, row) -> screen.openPage(page, null));
    }

    /**
     * The Secret Vault row's value (iOS): On while usable, "Needs setup" while it waits for recovery, else Off; the
     * On / Off value is left out while the NEW pill is up (the page's switch shows the state).
     */
    private static CharSequence secretVaultValue(boolean isNew) {
        final boolean enabled = org.telegram.zenlygram.vault.ZgVaultSettings.isEnabled();
        if (enabled && org.telegram.zenlygram.vault.ZgVaultSettings.needsRecovery()) {
            return getString(R.string.ZG_Hub_NeedsSetup);
        }
        if (isNew) {
            return "";
        }
        return getString(org.telegram.zenlygram.vault.ZgVaultSettings.isUsable() ? R.string.ZG_Hub_On : R.string.ZG_Hub_Off);
    }
}
