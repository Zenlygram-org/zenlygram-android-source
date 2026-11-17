/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.chatlist;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.DialogsActivity;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;

import java.util.ArrayList;

/**
 * Settings > Zenlygram > Folders, read by the hooks in DialogsActivity, FilterTabsView and MainTabsActivity
 * (marked ZENLYGRAM).
 *
 * <p>The layout settings (Folders at Bottom, Hide "All Chats", Smaller spacing) need a restart, as on iOS: each is
 * read once per process and kept, so every screen of this process agrees even when a fragment is rebuilt before the
 * restart. A feature that is not ported always gives the stock answer.
 *
 * <p>Tab ids are indices into {@code MessagesController.getDialogFilters()}; in stock Telegram they are also the tab
 * positions. While "All Chats" is hidden they are not, so position lookups go through the tab ids.
 */
public final class ZgFolders {

    private static volatile Boolean allChatsHiddenSnapshot;
    private static volatile Boolean compactNamesSnapshot;
    private static volatile Boolean atBottomSnapshot;

    private ZgFolders() {
    }

    // ---------------------------------------------------------------------------------------------------------
    // Restart snapshots
    // ---------------------------------------------------------------------------------------------------------

    /** Hide "All Chats" as this process started with it. */
    public static boolean isAllChatsHiddenSnapshot() {
        Boolean v = allChatsHiddenSnapshot;
        if (v == null) {
            v = ZgFeatures.ALL_CHATS_HIDDEN.isPorted() && ZgSettings.allChatsHidden.get();
            allChatsHiddenSnapshot = v;
        }
        return v;
    }

    private static boolean isCompactNamesSnapshot() {
        Boolean v = compactNamesSnapshot;
        if (v == null) {
            v = ZgFeatures.COMPACT_FOLDER_NAMES.isPorted() && ZgSettings.compactFolderNames.get();
            compactNamesSnapshot = v;
        }
        return v;
    }

    private static boolean isAtBottomSnapshot() {
        Boolean v = atBottomSnapshot;
        if (v == null) {
            v = ZgFeatures.FOLDERS_AT_BOTTOM.isPorted() && ZgSettings.foldersTabAtBottom.get();
            atBottomSnapshot = v;
        }
        return v;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Folders at Bottom
    // ---------------------------------------------------------------------------------------------------------

    /** The folder bar of this chat list sits above the main tabs. Only the root chat list; pickers keep it on top. */
    public static boolean atBottom(DialogsActivity fragment) {
        return fragment != null && fragment.hasMainTabs && isAtBottomSnapshot();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Smaller spacing
    // ---------------------------------------------------------------------------------------------------------

    /** FilterTabsView.TAB_PADDING_WIDTH: halved with "Smaller spacing" (read when the class loads). */
    public static float tabPaddingWidthDp(float stock) {
        try {
            return isCompactNamesSnapshot() ? stock / 2f : stock;
        } catch (Throwable e) {
            return stock;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // "All Chats" title
    // ---------------------------------------------------------------------------------------------------------

    /** Whether the default tab shows "All" instead of "All Chats". {@code automatic} is Telegram's own choice. */
    public static boolean useShortAllChatsTitle(boolean automatic) {
        if (!ZgFeatures.ALL_CHATS_TITLE.isPorted()) {
            return automatic;
        }
        final String value = ZgSettings.allChatsTitleLengthOverride.get();
        if (ZgSettings.AllChatsTitle.SHORT.equals(value)) {
            return true;
        } else if (ZgSettings.AllChatsTitle.LONG.equals(value)) {
            return false;
        }
        return automatic;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Hide "All Chats"
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Whether updateFilterTabs leaves out the default tab: only with at least one custom folder, and never while the
     * strip is being reordered (drag-reorder maps tab positions onto filter indices, so every filter needs its tab).
     */
    public static boolean skipAllChatsTab(int filtersCount, boolean editing) {
        return filtersCount > 1 && !editing && isAllChatsHiddenSnapshot();
    }

    /** Whether {@code selectedType} is the default filter and has no tab (hidden "All Chats"). */
    public static boolean isHiddenDefault(ArrayList<MessagesController.DialogFilter> filters, int selectedType, FilterTabsView tabs) {
        if (!isAllChatsHiddenSnapshot() || filters == null || tabs == null || selectedType < 0 || selectedType >= filters.size()) {
            return false;
        }
        return filters.get(selectedType).isDefault() && !tabs.hasTab(selectedType) && tabs.getTabsCount() > 0;
    }

    /** The position of the tab whose id is {@code id}, or -1. In stock Telegram this is {@code id} itself. */
    public static int tabPosition(FilterTabsView tabs, int id) {
        if (tabs == null) {
            return -1;
        }
        for (int p = 0, n = tabs.getTabsCount(); p < n; p++) {
            final FilterTabsView.Tab tab = tabs.getTab(p);
            if (tab != null && tab.id == id) {
                return p;
            }
        }
        return -1;
    }

    /**
     * FilterTabsView.getDefaultTabId() for updateFilterTabs' "one filter left" branch: with "All Chats" hidden the old
     * strip has no default tab (-1), but the only filter left is the default one, at index 0.
     */
    public static int defaultTabId(FilterTabsView tabs) {
        final int id = tabs.getDefaultTabId();
        return id >= 0 || !isAllChatsHiddenSnapshot() ? id : 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Open Last Folder
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A folder page of the root chat list settled on screen. Saved whether or not the option is on (like iOS), so
     * turning it on later already knows the folder. -1 = All Chats, else the filter id (indices move on reorder).
     */
    public static void onFolderShown(DialogsActivity fragment, int index) {
        if (fragment == null || !fragment.hasMainTabs || !ZgFeatures.REMEMBER_LAST_FOLDER.isPorted()) {
            return;
        }
        final ArrayList<MessagesController.DialogFilter> filters = fragment.getMessagesController().getDialogFilters();
        if (filters == null || index < 0 || index >= filters.size()) {
            return;
        }
        final MessagesController.DialogFilter filter = filters.get(index);
        final long userId = UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId();
        if (userId == 0) {
            return;
        }
        final int value = filter.isDefault() ? ZgSettings.LAST_FOLDER_ALL_CHATS : filter.id;
        if (ZgSettings.getLastFolder(userId) != value) {
            ZgSettings.setLastFolder(userId, value);
        }
    }

    /**
     * The filter index the root chat list should open first, or -1 (stock: the first tab). Only with "Open Last
     * Folder" on; a deleted or locked folder is not restored.
     */
    public static int restoreFolderIndex(DialogsActivity fragment, ArrayList<MessagesController.DialogFilter> filters) {
        if (fragment == null || !fragment.hasMainTabs || filters == null || !ZgFeatures.REMEMBER_LAST_FOLDER.isPorted()
                || !ZgSettings.rememberLastFolder.get()) {
            return -1;
        }
        final long userId = UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId();
        if (userId == 0) {
            return -1;
        }
        final int saved = ZgSettings.getLastFolder(userId);
        if (saved == ZgSettings.LAST_FOLDER_NONE) {
            return -1;
        }
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter filter = filters.get(i);
            final boolean match = saved == ZgSettings.LAST_FOLDER_ALL_CHATS ? filter.isDefault() : !filter.isDefault() && filter.id == saved;
            if (match) {
                return filter.locked ? -1 : i;
            }
        }
        return -1;
    }
}
