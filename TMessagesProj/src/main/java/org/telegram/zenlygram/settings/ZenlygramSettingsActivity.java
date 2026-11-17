/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.settings;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.zenlygram.core.ZgConfig;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgRestart;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.pro.ZgPro;

import java.util.ArrayList;

/**
 * Settings > Zenlygram (iOS sgSettingsController): the hub ({@link ZgSettingsHub}) and its twelve pages
 * ({@link ZgSettingsCatalog.Page}), every page built from the {@link ZgSettingsSpec} sections the catalog places on it.
 * One class for both: {@link #forPage} opens a page, the plain constructor the hub.
 *
 * <ul>
 *   <li>The hub's search lists the matching rows of the hub and of every page (headers of group pages get the page
 *       title in front, iOS "TAB BAR — TABS"); a page's search covers that page.</li>
 *   <li>A page row carries the NEW pill while one of its page's live rows does (iOS roll-up).</li>
 *   <li>A focus key (deep link {@code tg://zg/settings?row=<key>}, {@link ZgSettingsDeepLink}) scrolls to that row and
 *       flashes it; a key on a page opens the page first.</li>
 *   <li>Rows of features that are not ported are hidden (ZgFeatures). Debug builds can show them greyed out with
 *       Developer > Preview all rows (the section appears after seven taps on the version row); a note at the top
 *       then says why they are grey and how to hide them.</li>
 *   <li>A tap retires the row's NEW badge, then runs the row's action, or writes its setting (switches and pickers),
 *       asks for a restart where needed, and rebuilds the list in place. A row dimmed by another setting says which
 *       one to change first.</li>
 *   <li>Search in the header filters rows by title, description, value and notice (ZgSettingsSearch); Back closes
 *       the search first.</li>
 *   <li>Statistics name the screen as iOS does ({@link #zenlyScreen}).</li>
 * </ul>
 */
public class ZenlygramSettingsActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    private static final int MENU_SEARCH = 1;
    private static final int ID_NO_RESULTS = 1;
    /** "Greyed rows are not in this build yet", above the list while Preview all rows is on. */
    private static final int ID_PREVIEW_NOTICE = 2;
    /** Gap item after a section: section * 100 + 99. */
    private static final int ID_SECTION_GAP = 99;

    private enum State {
        HIDDEN,
        /** Feature not ported (or not available): shown greyed out because Preview all rows is on. */
        PREVIEW,
        LIVE
    }

    private ArrayList<ZgSection> spec;
    /** The hub's sections (built around the spec sections placed on the hub). */
    private ArrayList<ZgSection> hub;
    /** Null: this screen is the hub. */
    private ZgSettingsCatalog.Page page;
    /** iOS key of the row to scroll to and flash once (deep link); cleared once handled. */
    private String focusKey;
    /** Per page: {state, hasNew} from the last build (the hub's page rows read it). */
    private final java.util.EnumMap<ZgSettingsCatalog.Page, int[]> pageInfo = new java.util.EnumMap<>(ZgSettingsCatalog.Page.class);
    private final SparseArray<ZgRow> rowsById = new SparseArray<>();
    private ActionBarMenuItem searchItem;
    /** The search text; null while the search field is closed. */
    private String searchQuery;

    /** The hub (Settings > Zenlygram). */
    public ZenlygramSettingsActivity() {
    }

    /** One page of the hub; {@code focusKey} (an iOS row key, may be null) is scrolled to and flashed. */
    public static ZenlygramSettingsActivity forPage(ZgSettingsCatalog.Page page, String focusKey) {
        final ZenlygramSettingsActivity screen = new ZenlygramSettingsActivity();
        screen.page = page;
        screen.focusKey = focusKey;
        return screen;
    }

    /** The hub, then the row named by {@code focusKey} (opening its page first). */
    public static ZenlygramSettingsActivity withFocus(String focusKey) {
        final ZenlygramSettingsActivity screen = new ZenlygramSettingsActivity();
        screen.focusKey = focusKey;
        return screen;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (listView.scrollingByUser) {
                    AndroidUtilities.hideKeyboard(fragmentView);
                }
            }
        });
        createSearchItem();
        NotificationCenter.getGlobalInstance().listen(fragmentView, ZgPro.didChange, args -> refresh()); // Subscription value
        // Telegram Account: linked / not linked, also when the automatic link finishes while the hub is open.
        NotificationCenter.getInstance(currentAccount).listen(fragmentView, NotificationCenter.zenlygramProStatusChanged, args -> refresh());
        // Advanced > Content Settings appears once the server says this account may change "Show 18+ Content".
        NotificationCenter.getInstance(currentAccount).listen(fragmentView, NotificationCenter.contentSettingsLoaded, args -> refresh());
        getMessagesController().getContentSettings(null);
        return fragmentView;
    }

    private void createSearchItem() {
        final ActionBarMenuItem.ActionBarMenuItemSearchListener listener = new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searchQuery = "";
                refresh();
            }

            @Override
            public void onSearchCollapse() {
                searchQuery = null;
                refresh();
                scrollToTop();
            }

            @Override
            public void onTextChanged(EditText editText) {
                final String query = editText.getText().toString();
                if (!TextUtils.equals(searchQuery, query)) {
                    searchQuery = query;
                    refresh();
                    scrollToTop();
                }
            }
        };
        if (simpleActionBar != null) {
            searchItem = simpleActionBar.createMenu().addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider());
        } else {
            searchItem = actionBar.createMenu().addItem(MENU_SEARCH, R.drawable.outline_header_search, getResourceProvider());
        }
        searchItem.setIsSearchField(true).setActionBarMenuItemSearchListener(listener);
        searchItem.setSearchFieldHint(getString(R.string.Search));
        searchItem.setContentDescription(getString(R.string.Search));
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return page != null ? pageTitle(page) : getString(R.string.ZG_AppName);
    }

    /**
     * The screen's name in the statistics (iOS SGSettingsScreen.analyticsScreen): the hub; the Deleted &amp; Edited
     * Messages page under the name the taxonomy reserves for it; every other page as the preferences screen.
     */
    @Override
    public CustomerEvents.Screen zenlyScreen() {
        if (page == null) {
            return CustomerEvents.Screen.ZG_HUB;
        }
        return page == ZgSettingsCatalog.Page.HISTORY ? CustomerEvents.Screen.EDIT_HISTORY_SETTINGS : CustomerEvents.Screen.ZG_PREFERENCES;
    }

    /** The page's title: its hub row's title. */
    static CharSequence pageTitle(ZgSettingsCatalog.Page page) {
        switch (page) {
            case SECRET_VAULT:
                return getString(R.string.ZG_Settings_SecretVault_Title);
            case HISTORY:
                return getString(R.string.ZG_Hub_History);
            case CALL_RECORDING:
                return getString(R.string.ZG_Hub_CallRecording);
            case CHATS:
                return getString(R.string.ZG_Hub_Chats);
            case MESSAGES:
                return getString(R.string.ZG_Hub_Messages);
            case MESSAGE_MENU:
                return getString(R.string.ZG_Hub_MessageMenu);
            case TAB_BAR:
                return getString(R.string.ZG_Hub_TabBar);
            case PRIVACY:
                return getString(R.string.ZG_Hub_Privacy);
            case TRANSLATION:
                return getString(R.string.ZG_Hub_Translation);
            case MEDIA:
                return getString(R.string.ZG_Hub_Media);
            case COLORS:
                return getString(R.string.ZG_Hub_Colors);
            case CALLS_ADVANCED:
            default:
                return getString(R.string.ZG_Hub_CallsAdvanced);
        }
    }

    /** Opens {@code target} (a hub page row, or a focus on one of its rows). */
    public void openPage(ZgSettingsCatalog.Page target, String focus) {
        presentFragment(forPage(target, focus));
    }

    @Override
    public void onResume() {
        super.onResume();
        // Data and Storage may have turned an account's auto-download back on: Disable Auto-Download follows the user.
        org.telegram.zenlygram.download.ZgAutoDownload.sync();
        // Values may have changed in a screen opened from here (Pro, Chat Lock, ...), and NEW badges retire.
        refresh();
        if (focusKey != null) {
            final String key = focusKey;
            focusKey = null;
            AndroidUtilities.runOnUIThread(() -> applyFocus(key), 300);
        }
    }

    /**
     * Deep-link focus: on the hub a key on a page opens that page (which then flashes the row); otherwise the row is
     * scrolled to and flashed when it is on this screen. Unknown or hidden keys leave the screen as it is.
     */
    private void applyFocus(String key) {
        if (listView == null || isFinished) {
            return;
        }
        final int rowId = ZgSettingsCatalog.rowIdForKey(key);
        if (rowId == 0) {
            return;
        }
        final ZgSettingsCatalog.Page target = ZgSettingsCatalog.pageOfRow(rowId);
        if (page == null && target != null) {
            if (pageState(target) != ZgSettingsHub.PAGE_HIDDEN) {
                openPage(target, key);
            }
            return;
        }
        if (target != page) {
            return;
        }
        for (int i = 0; i < listView.adapter.getItemCount(); i++) {
            final UItem item = listView.adapter.getItem(i);
            if (item != null && item.id == rowId) {
                final int position = i;
                listView.layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
                listView.highlightRow(() -> position);
                return;
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebindAfterConfigurationChange(listView);
    }

    /**
     * Telegram's notice cells (UniversalAdapter + TextInfoPrivacyCell) cap their text at the screen width they were
     * bound with, so after a rotation they keep the old width until rebound (Telegram's own UniversalFragment screens
     * do the same). Rebinds the list once the new screen size is known (LaunchActivity updates it in its own
     * onConfigurationChanged, after this one may run).
     */
    static void rebindAfterConfigurationChange(UniversalRecyclerView listView) {
        if (listView != null) {
            AndroidUtilities.runOnUIThread(() -> listView.adapter.notifyDataSetChanged());
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (simpleActionBar != null && simpleActionBar.isSearchFieldVisible()) {
            if (invoked) {
                simpleActionBar.closeSearchField();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Building the list
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean preview = ZgSettings.isPreviewAllRows();
        getSpec();
        updatePageInfo(preview);
        final ArrayList<Entry> shown;
        if (searchQuery == null) {
            shown = collectEntries(preview, screenSections(), null);
        } else if (page != null) {
            shown = ZgSettingsSearch.filter(collectEntries(preview, screenSections(), null), searchQuery);
        } else {
            // The hub's search: the hub's own rows (without the page rows of MORE SETTINGS), then every page's rows.
            final ArrayList<Entry> all = new ArrayList<>();
            for (Entry entry : collectEntries(preview, screenSections(), null)) {
                if (entry.row.section.id != ZgSettingsCatalog.SECTION_HUB_GROUPS) {
                    all.add(entry);
                }
            }
            for (ZgSettingsCatalog.Page p : ZgSettingsCatalog.Page.values()) {
                all.addAll(collectEntries(preview, sectionsOf(p), p.isGroup ? p : null));
            }
            shown = ZgSettingsSearch.filter(all, searchQuery);
        }
        if (shown.isEmpty()) {
            if (!TextUtils.isEmpty(ZgSettingsSearch.normalizeQuery(searchQuery))) {
                items.add(UItem.asShadow(ID_NO_RESULTS, getString(R.string.SettingsNoResults)));
            }
            return;
        }
        if (preview && searchQuery == null) {
            items.add(UItem.asShadow(ID_PREVIEW_NOTICE, formatString(R.string.ZG_Developer_PreviewAllRows_Banner,
                getString(R.string.ZG_Developer_PreviewAllRows), getString(R.string.ZG_Header_Developer))));
        }
        int section = -1;
        boolean endsWithShadow = true;
        for (Entry entry : shown) {
            final int entrySection = entry.getSectionId();
            if (entrySection != section) {
                if (section != -1 && !endsWithShadow) {
                    items.add(UItem.asShadow(section * 100 + ID_SECTION_GAP, null));
                }
                section = entrySection;
            }
            items.add(createItem(entry));
            endsWithShadow = entry.row.kind == ZgRow.Kind.NOTICE;
        }
        if (!endsWithShadow) {
            items.add(UItem.asShadow(section * 100 + ID_SECTION_GAP, null));
        }
        applyDividers(items);
    }

    /** The sections of this screen: the hub's, or the spec sections the catalog places on this page. */
    private ArrayList<ZgSection> screenSections() {
        return page == null ? hub : sectionsOf(page);
    }

    private ArrayList<ZgSection> sectionsOf(ZgSettingsCatalog.Page target) {
        final ArrayList<ZgSection> result = new ArrayList<>();
        for (ZgSection section : getSpec()) {
            if (ZgSettingsCatalog.pageOfSection(section.id) == target && target != null) {
                result.add(section);
            }
        }
        return result;
    }

    /** Recomputes every page's state and NEW roll-up, read by the hub's page rows (iOS pageHasNew). */
    private void updatePageInfo(boolean preview) {
        pageInfo.clear();
        for (ZgSettingsCatalog.Page p : ZgSettingsCatalog.Page.values()) {
            int state = ZgSettingsHub.PAGE_HIDDEN;
            int hasNew = 0;
            for (Entry entry : collectEntries(preview, sectionsOf(p), null)) {
                if (entry.row.kind.isStructural()) {
                    continue;
                }
                if (entry.state == State.LIVE) {
                    state = ZgSettingsHub.PAGE_LIVE;
                    if (entry.isNew) {
                        hasNew = 1;
                    }
                } else if (state == ZgSettingsHub.PAGE_HIDDEN) {
                    state = ZgSettingsHub.PAGE_PREVIEW;
                }
            }
            pageInfo.put(p, new int[]{state, hasNew});
        }
    }

    private int pageState(ZgSettingsCatalog.Page p) {
        final int[] info = pageInfo.get(p);
        return info != null ? info[0] : ZgSettingsHub.PAGE_HIDDEN;
    }

    private boolean pageHasNew(ZgSettingsCatalog.Page p) {
        final int[] info = pageInfo.get(p);
        return info != null && info[1] != 0;
    }

    /**
     * Visible rows of {@code sections} in order, each section with its header first. Sections without a visible row
     * are left out. {@code prefix}: a group page whose title goes in front of the headers (hub search).
     */
    private ArrayList<Entry> collectEntries(boolean preview, ArrayList<ZgSection> sections, ZgSettingsCatalog.Page prefix) {
        final ArrayList<Entry> result = new ArrayList<>();
        final ArrayList<Entry> sectionEntries = new ArrayList<>();
        for (ZgSection section : sections) {
            if (!test(section.visibleWhen)) {
                continue;
            }
            sectionEntries.clear();
            boolean hasContent = false;
            boolean hasLiveContent = false;
            for (ZgRow row : section.rows) {
                if (row.kind == ZgRow.Kind.HEADER) {
                    continue;
                }
                final State state = getState(row, preview);
                if (state == State.HIDDEN) {
                    continue;
                }
                if (!row.kind.isStructural()) {
                    hasContent = true;
                    hasLiveContent |= state == State.LIVE;
                }
                sectionEntries.add(resolve(row, state, preview));
            }
            if (!hasContent) {
                continue;
            }
            if (section.getHeader().title != null) {
                final Entry header = resolve(section.getHeader(), hasLiveContent ? State.LIVE : State.PREVIEW, preview);
                if (prefix != null && header.title != null) {
                    header.title = formatString(R.string.ZG_Hub_SearchHeader, pageTitle(prefix), header.title);
                }
                result.add(header);
            }
            result.addAll(sectionEntries);
        }
        return result;
    }

    private State getState(ZgRow row, boolean preview) {
        final ZgSection section = row.section;
        if (!test(section.visibleWhen) || !test(row.visibleWhen)) {
            return State.HIDDEN;
        }
        final ZgFeatures feature = row.getFeature();
        if (feature == null) {
            return State.HIDDEN;
        }
        if (feature.isPorted() && test(section.availableWhen) && test(row.availableWhen)) {
            return State.LIVE;
        }
        return preview ? State.PREVIEW : State.HIDDEN;
    }

    private Entry resolve(ZgRow row, State state, boolean preview) {
        final Entry entry = new Entry(row, state);
        entry.title = get(row.title);
        CharSequence description = get(row.description);
        if (row.restart && !TextUtils.isEmpty(description)) {
            description = TextUtils.concat(description, getString(R.string.ZG_Settings_RestartRequiredHint));
        }
        entry.description = description;
        entry.value = row.kind == ZgRow.Kind.SELECTOR ? row.getChoiceLabel() : get(row.value);
        if (row.newBadgeKey != null) {
            // A previewed row shows the pill without starting the 30-day clock of a feature that is not there yet.
            entry.isNew = state == State.LIVE ? ZgSettings.isNewBadgeVisible(row.newBadgeKey) : preview;
        }
        if (row.rollUpNew != null) {
            entry.isNew = state == State.LIVE && row.rollUpNew.test();
        }
        return entry;
    }

    private UItem createItem(Entry entry) {
        final ZgRow row = entry.row;
        final boolean live = entry.state == State.LIVE;
        final boolean enabled = live && test(row.enabledWhen);
        final ZgSettingsIcons.Tile tile = row.tile;
        switch (row.kind) {
            case HEADER:
                return UItem.asHeader(row.id, entry.title).setEnabled(live);
            case SUBHEADER:
                return UItem.asBlackHeader(entry.title).setId(row.id).setEnabled(live);
            case TOGGLE:
                return ZgSettingRowCell.Factory.asToggle(row.id, row.iconRes, tile.top, tile.bottom,
                    entry.title, entry.description, readBool(row), entry.isNew, enabled);
            case DISCLOSURE:
                return ZgSettingRowCell.Factory.asDisclosure(row.id, row.iconRes, tile.top, tile.bottom,
                    entry.title, entry.description, entry.value, entry.isNew, enabled);
            case INFO:
                return ZgSettingRowCell.Factory.asInfo(row.id, row.iconRes, tile.top, tile.bottom,
                    entry.title, entry.description, entry.value, !live);
            case SELECTOR:
                return UItem.asButton(row.id, entry.title, entry.value).setEnabled(enabled);
            case SLIDER:
                return createSlider(row, enabled);
            case CHECK:
                return UItem.asCheck(row.id, entry.title).setChecked(readBool(row)).setEnabled(enabled);
            case NOTICE:
            default:
                return UItem.asShadow(row.id, AndroidUtilities.replaceTags(entry.title != null ? entry.title : ""));
        }
    }

    private UItem createSlider(ZgRow row, boolean enabled) {
        final UItem item = UItem.asIntSlideView(1, row.slider.min, row.slider.get(), row.slider.max,
            v -> v + "%", value -> onSliderChanged(row, value));
        item.id = row.id;
        // The same Options object on every refresh, so the list keeps the slider instead of re-inserting it.
        item.object = row.getSliderOptions();
        item.onBind(view -> bindSliderEnabled(view, enabled));
        return item;
    }

    /** Tag of the transparent view that takes the touches of a disabled slider (see {@link #bindSliderEnabled}). */
    private static final String SLIDER_TOUCH_SHIELD = "zgSliderTouchShield";

    /**
     * A slider that is not live (a previewed, not ported feature) is dimmed and cannot be dragged or adjusted with
     * TalkBack, and reports itself disabled. Only the contents are dimmed: the card background under the row stays as
     * it is. Bound views are recycled, so a live slider gets everything back.
     *
     * <p>Telegram's SeekBarView moves in onInterceptTouchEvent and ignores setEnabled, so a disabled slider gets a
     * transparent view on top that takes the touch; the list still scrolls when the finger moves vertically.
     */
    private static void bindSliderEnabled(View view, boolean enabled) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        final ViewGroup group = (ViewGroup) view;
        view.setAlpha(1.0f);
        View shield = group.findViewWithTag(SLIDER_TOUCH_SHIELD);
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            if (child == shield) {
                continue;
            }
            child.setAlpha(enabled ? 1.0f : 0.5f);
            if (child instanceof SeekBarView) {
                final SeekBarView seekBar = (SeekBarView) child;
                seekBar.setEnabled(enabled);
                seekBar.setAccessibilityDelegate(enabled ? seekBar.getSeekBarAccessibilityDelegate() : DISABLED_SLIDER_ACCESSIBILITY);
            }
        }
        if (!enabled && shield == null) {
            shield = new View(group.getContext());
            shield.setTag(SLIDER_TOUCH_SHIELD);
            shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            shield.setOnTouchListener((v, event) -> true);
            group.addView(shield, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (shield != null) {
            shield.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
    }

    /** TalkBack for a disabled slider: the slider's own description, marked disabled, without the adjust actions. */
    private static final View.AccessibilityDelegate DISABLED_SLIDER_ACCESSIBILITY = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            if (host instanceof SeekBarView) {
                ((SeekBarView) host).getSeekBarAccessibilityDelegate().onInitializeAccessibilityNodeInfo(host, info);
            } else {
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
            info.setEnabled(false);
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                return false;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    /** Dividers only between rows of one card. Part of the item contents, so they update with the list. */
    private static void applyDividers(ArrayList<UItem> items) {
        for (int i = 0; i < items.size(); i++) {
            final UItem item = items.get(i);
            if (ZgSettingRowCell.Factory.is(item)) {
                final UItem next = i + 1 < items.size() ? items.get(i + 1) : null;
                ZgSettingRowCell.Factory.setDivider(item, next != null && !UniversalAdapter.isShadow(next.viewType));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final ZgRow row = getRow(item.id);
        if (row == null || !isLive(row)) {
            return;
        }
        if (!test(row.enabledWhen)) {
            // Dimmed by another setting (RecyclerListView still delivers the tap): say which one instead of nothing.
            final CharSequence hint = get(row.disabledHint);
            if (!TextUtils.isEmpty(hint)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, hint).show();
            }
            return;
        }
        if (!item.enabled) {
            return;
        }
        if (row.newBadgeKey != null) {
            ZgSettings.retireNewBadge(row.newBadgeKey);
        }
        if (row.action != null) {
            row.action.run(this, row);
        } else {
            switch (row.kind) {
                case TOGGLE:
                case CHECK:
                    // A controlled switch (vault, secret code) moves only through its feature's action.
                    if (row.boolValue != null && !row.controlled) {
                        row.boolValue.set(!row.boolValue.get());
                        org.telegram.zenlygram.customer.ZgStats.rowToggled(currentAccount, row.id, row.boolValue.get()); // ZENLYGRAM: statistics, feature.toggle of the reported switches (recorded only with the data consent)
                        if (row.onChanged != null) {
                            row.onChanged.run(this, row);
                        }
                        if (row.restart) {
                            askForRestart();
                        }
                    }
                    break;
                case SELECTOR:
                    showSelector(row);
                    break;
                default:
                    break;
            }
        }
        refresh();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final String url = getLinkUrl(item.id);
        if (url == null) {
            return false;
        }
        copyText(url, true);
        return true;
    }

    private void onSliderChanged(ZgRow row, int value) {
        // No list rebuild here: the slider is being dragged. A previewed slider moves but stores nothing.
        if (!isLive(row) || !test(row.enabledWhen)) {
            return;
        }
        row.slider.set(value);
        if (row.action != null) {
            row.action.run(this, row);
        }
    }

    private void showSelector(ZgRow row) {
        final Activity activity = getParentActivity();
        if (activity == null || row.choice == null || row.options == null) {
            return;
        }
        final String[] labels = new String[row.options.length];
        int selected = -1;
        final String current = row.choice.get();
        for (int i = 0; i < row.options.length; i++) {
            final CharSequence label = row.options[i].label.get();
            labels[i] = label != null ? label.toString() : "";
            if (row.options[i].value.equals(current)) {
                selected = i;
            }
        }
        final CharSequence title = get(row.title);
        showDialog(AlertsCreator.createSingleChoiceDialog(activity, labels, title != null ? title.toString() : null, selected, (dialog, which) -> {
            if (which < 0 || which >= row.options.length) {
                return;
            }
            final String value = row.options[which].value;
            if (!value.equals(row.choice.get())) {
                row.choice.set(value);
                if (row.onChanged != null) {
                    row.onChanged.run(this, row);
                }
                if (row.restart) {
                    askForRestart();
                }
            }
            refresh();
        }));
    }

    private static String getLinkUrl(int id) {
        if (id == ZgSettingsSpec.ROW_ABOUT_WEBSITE) {
            return ZgConfig.WEBSITE_URL;
        } else if (id == ZgSettingsSpec.ROW_ABOUT_SOURCE_CODE) {
            return ZgConfig.SOURCE_CODE_URL;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    // For row actions (ZgRow.Action)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Rebuilds the list in place; changed rows animate. While a slider is being dragged the rebuild waits: a slider
     * whose value changed counts as a new list item, so rebuilding would replace it under the finger.
     */
    public void refresh() {
        if (listView == null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(refreshAfterDrag);
        if (isSliderDragging()) {
            AndroidUtilities.runOnUIThread(refreshAfterDrag, 200);
            return;
        }
        listView.adapter.update(true);
    }

    private final Runnable refreshAfterDrag = this::refresh;

    private boolean isSliderDragging() {
        return listView != null && listView.isAttachedToWindow() && hasDraggedSeekBar(listView, 3);
    }

    private static boolean hasDraggedSeekBar(View view, int depth) {
        if (view instanceof SeekBarView) {
            return ((SeekBarView) view).isDragging();
        }
        if (depth > 0 && view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasDraggedSeekBar(group.getChildAt(i), depth - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "Restart required" bulletin with a "Restart Now" button. */
    public void askForRestart() {
        ZgRestart.showRestartBulletin(this);
    }

    public void openUrl(String url) {
        final Activity activity = getParentActivity();
        if (activity != null && !TextUtils.isEmpty(url)) {
            Browser.openUrl(activity, url);
        }
    }

    /** Seven taps on the version row, each within this long of the previous one, show the Developer section. */
    private static final long VERSION_TAP_WINDOW_MS = 1500;
    private int versionTaps;
    private long lastVersionTapAt;

    /** About > version row: copies the version; in debug builds seven quick taps show the Developer section. */
    public void onVersionRowTapped(CharSequence versionText) {
        final long now = SystemClock.elapsedRealtime();
        versionTaps = now - lastVersionTapAt <= VERSION_TAP_WINDOW_MS ? versionTaps + 1 : 1;
        lastVersionTapAt = now;
        if (BuildVars.DEBUG_VERSION && !ZgSettings.devSectionUnlocked.get() && versionTaps >= 7) {
            versionTaps = 0;
            ZgSettings.devSectionUnlocked.set(true); // onClick rebuilds the list right after this action
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.ZG_Developer_Unlocked)).show();
            return;
        }
        copyText(versionText, false);
    }

    public void copyText(CharSequence text, boolean isLink) {
        if (TextUtils.isEmpty(text) || !AndroidUtilities.addToClipboard(text)) {
            return;
        }
        if (isLink) {
            BulletinFactory.of(this).createCopyLinkBulletin().show();
        } else {
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private ArrayList<ZgSection> getSpec() {
        if (spec == null) {
            spec = ZgSettingsSpec.build();
            hub = ZgSettingsHub.build(spec, new ZgSettingsHub.Pages() {
                @Override
                public int state(ZgSettingsCatalog.Page p) {
                    return pageState(p);
                }

                @Override
                public boolean hasNew(ZgSettingsCatalog.Page p) {
                    return pageHasNew(p);
                }
            });
            rowsById.clear();
            for (ArrayList<ZgSection> list : java.util.Arrays.asList(spec, hub)) {
                for (ZgSection section : list) {
                    for (ZgRow row : section.rows) {
                        rowsById.put(row.id, row);
                    }
                }
            }
        }
        return spec;
    }

    private ZgRow getRow(int id) {
        getSpec();
        return rowsById.get(id);
    }

    private boolean isLive(ZgRow row) {
        return getState(row, ZgSettings.isPreviewAllRows()) == State.LIVE;
    }

    private void scrollToTop() {
        if (listView != null) {
            listView.layoutManager.scrollToPositionWithOffset(0, 0);
        }
    }

    private static boolean test(ZgRow.Condition condition) {
        return condition == null || condition.test();
    }

    private static CharSequence get(ZgRow.Text text) {
        return text != null ? text.get() : null;
    }

    private static boolean readBool(ZgRow row) {
        return row.boolValue != null && row.boolValue.get();
    }

    /** A visible row with its texts resolved for this build of the list. */
    private static final class Entry implements ZgSettingsSearch.Entry {
        final ZgRow row;
        final State state;
        CharSequence title;
        CharSequence description;
        CharSequence value;
        boolean isNew;

        Entry(ZgRow row, State state) {
            this.row = row;
            this.state = state;
        }

        @Override
        public int getSectionId() {
            return row.section.id;
        }

        @Override
        public boolean isHeader() {
            return row.kind.isHeader();
        }

        @Override
        public boolean isNotice() {
            return row.kind == ZgRow.Kind.NOTICE;
        }

        @Override
        public boolean matches(String query) {
            return row.kind != ZgRow.Kind.SLIDER && row.searchable && ZgSettingsSearch.contains(query, title, description, value);
        }
    }
}
