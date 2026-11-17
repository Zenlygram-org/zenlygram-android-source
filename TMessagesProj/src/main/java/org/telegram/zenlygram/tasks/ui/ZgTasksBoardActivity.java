/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.tasks.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.Outline;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.messenger.zenlygram.tasks.cloud.TasksAssigneeDisplay;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.SimpleActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FilledTabsView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.inset.WindowAnimatedInsetsProvider;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.zenlygram.pro.ZgLinkAccountActivity;
import org.telegram.zenlygram.tasks.ZgTasks;
import org.telegram.zenlygram.tasks.ZgTasksGate;
import org.telegram.zenlygram.tasks.cloudui.ZgCloudTaskEditorActivity;
import org.telegram.zenlygram.tasks.cloudui.ZgTasksInvitationsActivity;
import org.telegram.zenlygram.tasks.cloudui.ZgTasksUnsentActivity;
import org.telegram.zenlygram.tasks.data.ZgTodoAssignee;
import org.telegram.zenlygram.tasks.model.ZgBoard;
import org.telegram.zenlygram.tasks.model.ZgBoardList;
import org.telegram.zenlygram.tasks.model.ZgBoardTask;
import org.telegram.zenlygram.tasks.model.ZgTasksBoardState;
import org.telegram.zenlygram.tasks.model.ZgTasksCalendar;
import org.telegram.zenlygram.tasks.model.ZgTasksError;
import org.telegram.zenlygram.tasks.model.ZgTasksRouting;
import org.telegram.zenlygram.tasks.model.ZgTasksScope;
import org.telegram.zenlygram.tasks.model.ZgTasksTitlePolicy;
import org.telegram.zenlygram.tasks.source.ZgTasksSource;
import org.telegram.zenlygram.tasks.source.ZgTasksSources;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The Tasks board (iOS TasksTabController + TasksScopeContainerNode + the two board nodes): a task-first to-do list
 * with the Cloud / On This Device switch and the list filter chips pinned under the header, a List / Calendar view, a
 * collapsible "Completed (N)" section and a quick-add composer pinned above the tabs or the keyboard.
 * The same screen is the Tasks main tab (argument {@code hasMainTabs}) and the screen opened from Settings with a back
 * arrow.
 *
 * <ul>
 *   <li>Two stores, one look. The board shows the account's tasks on this device or its Cloud tasks
 *       ({@link ZgTasksSource}); which one, and what the switch offers, follows the consent gate and the account's
 *       cut-over ({@link ZgTasksRouting}) and is re-read whenever the sync owner announces a change. The user's pick is
 *       remembered per account; a scope that only follows the cut-over is not. Switching never copies anything.</li>
 *   <li>Observer-driven: the board renders the {@link ZgBoard} its source publishes and never assumes its own change
 *       went through. Each store keeps its own filter, Calendar view and composer draft while the other is shown.</li>
 *   <li>Quick add: title check first (empty: nothing; too long: an alert), then the CREATE gate, then the store. The
 *       draft (title, assignee, deadline) is cleared only after the store said the task is saved; a refused write
 *       keeps it (the copy of the device tasks to Cloud is running: "try again in a moment").</li>
 *   <li>Completion sends the explicit target state the row showed. Delete is a held delete with an Undo bulletin: it
 *       is made when the bulletin goes away or the board is left.</li>
 *   <li>A board that cannot take changes (the Device archive; Cloud while consent is off, withdrawn or being
 *       confirmed) has no composer and no row action but opening a task; its banner says why.</li>
 * </ul>
 */
public class ZgTasksBoardActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, WindowAnimatedInsetsProvider.Listener,
        NotificationCenter.NotificationCenterDelegate, ZenlyStats.NamedScreen {

    /** Optional argument: the board to open on ("cloud" / "device"), when the switch offers it. Never remembered. */
    public static final String ARG_SCOPE = "zgTasksScope";

    private static final int MENU_LISTS = 1;
    private static final int MENU_MORE = 2;
    private static final int MENU_INVITATIONS = 3;
    private static final int MENU_UNSENT = 4;

    /** A pinned row under the header (the scope switch, the chips): its height, and how far its glass is inset (dp). */
    private static final int PANEL_HEIGHT = 44;
    private static final int PANEL_GLASS_INSET = 2;
    /** Space between the last pinned row's glass and the first row of the list (dp). */
    private static final int PANEL_LIST_GAP = 6;

    private static final int ID_VIEW_SWITCH = 2;
    private static final int ID_CALENDAR = 3;
    private static final int ID_EMPTY = 4;
    private static final int ID_OPEN_SHADOW = 5;
    private static final int ID_COMPLETED_HEADER = 6;
    private static final int ID_COMPLETED_SHADOW = 7;
    private static final int ID_GATE = 10;
    private static final int ID_GATE_SHADOW = 11;
    private static final int ID_LINK = 12;
    private static final int ID_USE_DEVICE = 13;
    private static final int ID_RETRY = 14;
    private static final int ID_BANNER = 15;
    private static final int ID_UNSENT = 17;
    private static final int ID_INVITATIONS = 18;
    private static final int ID_STATUS_SHADOW = 19;
    private static final int ID_ARCHIVE = 20;
    private static final int ID_ARCHIVE_COPY = 21;
    private static final int ID_IMPORT = 22;
    private static final int ID_IMPORT_SHADOW = 23;
    private static final int ID_PRIVACY = 24;
    private static final int ID_LINK_PROOF = 25;
    private static final int ID_ASSIGNED_HEADER = 26;
    /** A notice's card and its OK row: base + 2 * index (+ 1). */
    private static final int ID_NOTICE_BASE = 1000;

    /** What one store's board keeps while the other one is shown (iOS keeps two board nodes). */
    private static final class Pane {
        final ZgTasksScope scope;
        final ZgTasksBoardState state;
        ZgTasksSource source;
        ZgTasksSource.Subscription subscription;
        boolean calendarMode;
        /** The selected day; null = today, re-derived on every render so it follows midnight. */
        LocalDate calendarDay;
        boolean calendarUndated;
        boolean monthExpanded;
        /** The month the card shows; null = the month of the selected day. */
        YearMonth shownMonth;
        // The composer draft, kept until a create succeeds
        CharSequence draftText = "";
        ZgTodoAssignee draftAssignee;
        Long draftDueAt;
        boolean creating;
        String revealTaskId;
        String pendingSelectListId;
        boolean pendingSelectAll;
        boolean reportedFailure;

        Pane(ZgTasksScope scope) {
            this.scope = scope;
            this.state = new ZgTasksBoardState(scope);
        }
    }

    private boolean hasMainTabs;
    private final Pane devicePane = new Pane(ZgTasksScope.DEVICE);
    private final Pane cloudPane = new Pane(ZgTasksScope.CLOUD);
    private ZgTasksScope scope = ZgTasksScope.DEVICE;
    private ZgTasksRouting.Switch scopeSwitch = ZgTasksRouting.Switch.DEVICE_ONLY;
    private boolean resumed;
    private boolean destroyed;
    /** The delete whose Undo bulletin is showing (at most one), and the store it belongs to. */
    private ZgTasksSource.HeldDelete heldDelete;
    private ZgTasksSource heldSource;

    private boolean restoreComposerFocus;
    private boolean sheetOpen;
    private ZgTasksCopyDialog.Flow copyFlow;

    private FrameLayout root;
    private UniversalRecyclerView listView;
    /** The header's fade, made taller so it also covers the pinned rows (as DataUsage2Activity under its tabs). */
    private View topFade;
    private FrameLayout scopePanel;
    private ZgTasksScopeView scopeView;
    private FrameLayout chipsPanel;
    private ZgTasksChipsView chipsView;
    private FrameLayout viewSwitchContainer;
    private FilledTabsView viewSwitch;
    private ZgTasksCalendarHeaderView calendarHeader;
    private ZgTasksComposerView composer;
    private ZgTasksTimeObserver timeObserver;
    private SimpleActionBarMenuItem listsItem;
    private SimpleActionBarMenuItem moreItem;
    private ActionBarMenuSubItem invitationsItem;
    private ActionBarMenuSubItem unsentItem;

    private int insetTop;
    private int insetBottom;
    private int imeStatic;
    private int imeAnimated;
    private boolean imeAnimating;

    public ZgTasksBoardActivity(Bundle args) {
        super(args);
    }

    private Pane pane() {
        return scope == ZgTasksScope.CLOUD ? cloudPane : devicePane;
    }

    @Override
    public boolean onFragmentCreate() {
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        timeObserver = new ZgTasksTimeObserver(this::onTimeChanged);
        scopeSwitch = ZgTasksSources.scopeSwitch(currentAccount);
        scope = ZgTasksSources.scope(currentAccount);
        final ZgTasksScope asked = arguments != null ? ZgTasksScope.fromWire(arguments.getString(ARG_SCOPE)) : null;
        if (asked != null && offers(scopeSwitch, asked)) {
            scope = asked;
        }
        getNotificationCenter().addObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        getNotificationCenter().removeObserver(this, NotificationCenter.zenlygramTasksModeChanged);
        commitHeldDelete();
        for (Pane pane : new Pane[]{devicePane, cloudPane}) {
            if (pane.source != null && resumed && pane == pane()) {
                pane.source.stop(this);
            }
            if (pane.subscription != null) {
                pane.subscription.unsubscribe();
                pane.subscription = null;
            }
        }
        resumed = false;
        if (copyFlow != null) {
            copyFlow.cancel();
            copyFlow = null;
        }
        if (timeObserver != null) {
            timeObserver.stop();
        }
        if (LaunchActivity.instance != null) {
            LaunchActivity.instance.getRootAnimatedInsetsListener().unsubscribeFromWindowInsetsAnimation(this);
        }
        if (root != null) {
            Bulletin.removeDelegate(root);
        }
        Bulletin.removeDelegate(this);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.zenlygramTasksModeChanged && account == currentAccount && !destroyed) {
            applyRouting();
        }
    }

    /**
     * {@code tasks.archive} while the read-only Device archive is shown, else the board. Read from the routing, which
     * is known before the store answered: the screen is named right when it is pushed.
     */
    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return scope == ZgTasksScope.DEVICE && ZgTasksRouting.deviceIsArchive(scopeSwitch)
            ? CustomerEvents.Screen.TASKS_ARCHIVE : CustomerEvents.Screen.TASKS_BOARD;
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        // In the main tabs the tabs draw their own fade over the navigation bar (as ContactsActivity).
        return !hasMainTabs;
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false; // topFade, which also covers the pinned rows
    }

    @Override
    protected void onViewCreated(View view) {
        super.onViewCreated(view);
        // Above the header (added after createView), whose touch area can reach the pinned rows' top on large screens.
        if (scopePanel != null) {
            scopePanel.bringToFront();
        }
        if (chipsPanel != null) {
            chipsPanel.bringToFront();
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setTitle(getString(R.string.ZG_tasks_tab));
        if (!hasMainTabs) {
            simpleActionBar.setBackButtonDrawable(new BackDrawable(false));
        }
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_LISTS) {
                    openLists();
                } else if (id == MENU_INVITATIONS) {
                    openInvitations();
                } else if (id == MENU_UNSENT) {
                    openUnsentChanges();
                }
            }
        });
        // The entry points of the navigation bar: Lists; on Cloud also Invitations (with the count when there are any)
        // and, while anything is unsent, Unsent Changes.
        final SimpleActionBarMenu menu = simpleActionBar.createMenu();
        moreItem = menu.addItem(MENU_MORE, R.drawable.ic_ab_other);
        moreItem.setContentDescription(getString(R.string.ZG_tasks_cloud_more));
        invitationsItem = moreItem.addSubItem(MENU_INVITATIONS, R.drawable.msg_message, getString(R.string.ZenlyCloudTasksInvitations));
        unsentItem = moreItem.addSubItem(MENU_UNSENT, R.drawable.msg_retry, getString(R.string.ZG_tasks_cloud_issues_title));
        moreItem.setShown(false, false);
        listsItem = menu.addItem(MENU_LISTS, getString(R.string.ZG_tasks_lists_title));

        // The views of the list's custom rows exist before the list: the adapter fills itself in its constructor.
        chipsView = new ZgTasksChipsView(context, resourceProvider);
        chipsView.setDelegate(new ZgTasksChipsView.Delegate() {
            @Override
            public void onSelectFilter(String listId) {
                final Pane pane = pane();
                pane.state.select(listId);
                if (pane.calendarMode) {
                    pane.shownMonth = null;
                }
                updateViews();
            }

            @Override
            public void onNewList() {
                createList();
            }
        });
        viewSwitchContainer = new FrameLayout(context);
        viewSwitch = new FilledTabsView(context);
        viewSwitch.setTabs(getString(R.string.ZG_tasks_calendar_viewList), getString(R.string.ZG_tasks_calendar_viewCalendar));
        viewSwitch.onTabSelected(index -> setCalendarMode(index == 1));
        viewSwitchContainer.addView(viewSwitch, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 12, 0, 12, 0));
        calendarHeader = new ZgTasksCalendarHeaderView(context, resourceProvider);
        calendarHeader.setDelegate(new ZgTasksCalendarHeaderView.Delegate() {
            @Override
            public void onToday() {
                final Pane pane = pane();
                pane.calendarUndated = false;
                pane.calendarDay = null;
                pane.shownMonth = null;
                updateViews();
            }

            @Override
            public void onNoDeadline() {
                pane().calendarUndated = true;
                updateViews();
            }

            @Override
            public void onToggleMonth() {
                final Pane pane = pane();
                pane.monthExpanded = !pane.monthExpanded;
                updateViews();
            }

            @Override
            public void onDaySelected(LocalDate day) {
                final Pane pane = pane();
                pane.calendarUndated = false;
                pane.calendarDay = day.equals(today()) ? null : day;
                pane.shownMonth = YearMonth.from(day);
                updateViews();
            }

            @Override
            public void onMonthChanged(YearMonth month) {
                pane().shownMonth = month;
                updateViews();
            }

            @Override
            public void onChooseMonth() {
                showMonthPicker();
            }
        });

        if (root != null) {
            // The view is rebuilt (a theme change): the old root takes its bulletin delegate with it.
            Bulletin.removeDelegate(root);
        }
        root = new FrameLayout(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        // The section decoration can leave the side gutter beside a short section (the empty-state
        // "No tasks yet" card) unpainted, so stale pixels showed as a ghost duplicate card to its right.
        // Giving the list its own opaque background repaints that region every frame.
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && composer != null && composer.editText.hasFocus()) {
                    AndroidUtilities.hideKeyboard(composer.editText);
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // The Cloud / device switch and the filter chips stay under the header (iOS TasksScopeBarView and
        // TasksBoardChipsView), each on a glass pill, the way DataUsage2Activity pins its tabs; the list scrolls under
        // them. Positions follow the insets and which of the two is shown (applyInsets).
        topFade = new View(context);
        topFade.setBackground(getBaseSimpleGlass().createActionBarFadeDrawable(topFade));
        root.addView(topFade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP));

        scopePanel = glassPanel(context);
        scopeView = new ZgTasksScopeView(context, resourceProvider);
        scopeView.setDelegate(this::selectScope);
        scopePanel.addView(scopeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PANEL_HEIGHT));
        root.addView(scopePanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PANEL_HEIGHT, Gravity.TOP, 6, 0, 6, 0));

        chipsPanel = glassPanel(context);
        chipsView.setPadding(dp(8), dp(6), dp(8), dp(6));
        chipsView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                final int inset = dp(PANEL_GLASS_INSET);
                outline.setRoundRect(inset, inset, view.getWidth() - inset, view.getHeight() - inset, dp((PANEL_HEIGHT - 2 * PANEL_GLASS_INSET) / 2f));
            }
        });
        chipsView.setClipToOutline(true); // chips scrolled to an end are cut by the pill's round edge
        chipsPanel.addView(chipsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PANEL_HEIGHT));
        root.addView(chipsPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PANEL_HEIGHT, Gravity.TOP, 6, 0, 6, 0));

        composer = new ZgTasksComposerView(context, resourceProvider);
        composer.setDelegate(new ZgTasksComposerView.Delegate() {
            @Override
            public void onSubmit() {
                submitQuickAdd();
            }

            @Override
            public void onPickAssignee() {
                pickAssignee();
            }

            @Override
            public void onClearAssignee() {
                pane().draftAssignee = null;
                updateComposer();
            }

            @Override
            public void onPickDeadline() {
                pickDeadline();
            }

            @Override
            public void onClearDeadline() {
                pane().draftDueAt = null;
                updateComposer();
            }
        });
        root.addView(composer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 10, 0, 10, 0));
        composer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) {
                AndroidUtilities.runOnUIThread(this::applyInsets);
            }
        });

        // Bulletins sit above the composer, or above the tabs / navigation bar while the board has none. Both ways a
        // bulletin is made for this screen find the same place: on the root (the Undo of a delete, also the Cloud
        // editor's) and for the fragment (the notes of a refused change).
        final Bulletin.Delegate bulletinDelegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                if (composer != null && root != null && composer.getVisibility() == View.VISIBLE && composer.getTop() > 0) {
                    return root.getHeight() - composer.getTop() + dp(4);
                }
                return insetBottom + (hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0);
            }
        };
        Bulletin.addDelegate(root, bulletinDelegate);
        Bulletin.addDelegate(this, bulletinDelegate);
        if (hasMainTabs) {
            // In the main tabs the pager hands the insets to the page itself.
            ViewCompat.setOnApplyWindowInsetsListener(root, this::onInsetsInternal);
        }
        if (LaunchActivity.instance != null) {
            LaunchActivity.instance.getRootAnimatedInsetsListener().subscribeToWindowInsetsAnimation(this);
        }

        fragmentView = root;
        subscribe(pane());
        composer.setText(pane().draftText);
        updateViews();
        return root;
    }

    /** A pinned row's frame with the glass pill of the header's own buttons behind its content. */
    private FrameLayout glassPanel(Context context) {
        final FrameLayout panel = new FrameLayout(context);
        panel.setBackground(getBaseSimpleGlass().factory.create(panel)
            .setColorProvider(BlurredBackgroundProviderImpl.simpleActionBar(resourceProvider))
            .setPadding(dp(PANEL_GLASS_INSET))
            .setRadius(dp((PANEL_HEIGHT - 2 * PANEL_GLASS_INSET) / 2f)));
        return panel;
    }

    // -----------------------------------------------------------------------------------------------------------
    // Glass, tabs and insets
    // -----------------------------------------------------------------------------------------------------------

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public GlassSourceRenderNode getGlassSource() {
        return getBaseSimpleGlass().getGlassSource();
    }

    @Override
    public GlassSourceRenderNode getFrostedGlassSource() {
        return getBaseSimpleGlass().getFrostedGlassSource();
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        if (isTouchOn(chipsView, ev) && chipsView.canScrollHorizontally(forward ? 1 : -1)) {
            return false;
        }
        final Pane pane = pane();
        if (pane.calendarMode && pane.monthExpanded && calendarHeader != null && isTouchOn(calendarHeader.getGrid(), ev)) {
            return false;
        }
        return true;
    }

    private static boolean isTouchOn(View view, MotionEvent ev) {
        if (view == null || !view.isShown() || ev == null) {
            return false;
        }
        final int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        final float x = ev.getRawX();
        final float y = ev.getRawY();
        return x >= loc[0] && x < loc[0] + view.getWidth() && y >= loc[1] && y < loc[1] + view.getHeight();
    }

    @Override
    public WindowInsetsCompat onInsetsInternal(@NonNull View view, @NonNull WindowInsetsCompat windowInsets) {
        imeStatic = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        return super.onInsetsInternal(view, windowInsets);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        insetTop = top;
        insetBottom = bottom;
        applyInsets();
    }

    @Override
    public View getAnimatedInsetsTargetView() {
        return root;
    }

    @Override
    public void onAnimatedInsetsStarted() {
        imeAnimating = true;
    }

    @Override
    public void onAnimatedInsetsChanged(View view, WindowInsetsCompat insets) {
        imeAnimated = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        applyInsets();
    }

    @Override
    public void onAnimatedInsetsFinished() {
        imeAnimating = false;
        imeStatic = imeAnimated;
        // As WindowInsetsStateHolder: settle on the window's own insets once the keyboard stops moving.
        if (root != null) {
            root.postOnAnimation(this::applyInsets);
        }
        applyInsets();
    }

    /** The keyboard's height over this screen: the animated value while it moves, else the window's current insets. */
    private int currentImeInset() {
        if (imeAnimating) {
            return imeAnimated;
        }
        if (root != null && root.isAttachedToWindow()) {
            final WindowInsetsCompat window = WindowAnimatedInsetsProvider.calculateWindowInsets(root);
            if (window != null) {
                return window.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            }
        }
        return imeStatic;
    }

    /**
     * The composer sits above max(navigation bar + tabs, keyboard); the list leaves room for it and, at the top, for
     * the pinned rows that are shown (the scope switch, then the chips).
     */
    private void applyInsets() {
        if (composer == null || listView == null) {
            return;
        }
        final int tabs = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        final int ime = currentImeInset();
        final int bottom = Math.max(insetBottom + tabs, ime) + dp(8);
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) composer.getLayoutParams();
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom;
            composer.setLayoutParams(lp);
        }
        final boolean composerShown = composer.getVisibility() == View.VISIBLE;
        final int composerHeight = !composerShown ? 0 : composer.getMeasuredHeight() > 0 ? composer.getMeasuredHeight() : dp(96);
        // The first pill's glass starts where Telegram puts the first pill under a glass header (7dp under its buttons);
        // each further one follows its predecessor by the same gap.
        final int pillHeight = dp(PANEL_HEIGHT - 2 * PANEL_GLASS_INSET);
        int glassTop = BaseFragmentSimpleGlass.getPillGlassTop(insetTop);
        boolean pinned = false;
        if (scopePanel != null && scopePanel.getVisibility() == View.VISIBLE) {
            scopePanel.setTranslationY(glassTop - dp(PANEL_GLASS_INSET));
            glassTop += pillHeight + dp(BaseFragmentSimpleGlass.PILLS_GAP_DP);
            pinned = true;
        }
        if (chipsPanel != null && chipsPanel.getVisibility() == View.VISIBLE) {
            chipsPanel.setTranslationY(glassTop - dp(PANEL_GLASS_INSET));
            glassTop += pillHeight + dp(BaseFragmentSimpleGlass.PILLS_GAP_DP);
            pinned = true;
        }
        final int listTop = glassTop - insetTop - (pinned ? dp(BaseFragmentSimpleGlass.PILLS_GAP_DP - PANEL_LIST_GAP) : 0);
        if (topFade != null) {
            final ViewGroup.LayoutParams fadeLp = topFade.getLayoutParams();
            if (fadeLp.height != insetTop + listTop) {
                fadeLp.height = insetTop + listTop;
                topFade.setLayoutParams(fadeLp);
            }
        }
        BaseFragmentSimpleGlass.setListViewParams(listView, insetTop, insetBottom, listTop,
            Math.max(0, bottom - insetBottom) + composerHeight + dp(8));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------------------------

    @Override
    public void onResume() {
        super.onResume();
        // The sync owner may have announced a change before this screen observed it. Before the stores are started:
        // only the board that is shown after it syncs for this screen.
        applyRouting();
        resumed = true;
        final Pane pane = pane();
        subscribe(pane);
        pane.source.start(this);
        applyPendingSelection(pane);
        if (timeObserver != null) {
            timeObserver.start(getParentActivity());
        }
        if (restoreComposerFocus && !sheetOpen) {
            restoreComposerFocus = false;
            focusComposer();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Leaving the board ends the Undo: the held delete is made, and the store stops syncing for this screen.
        commitHeldDelete();
        if (resumed) {
            resumed = false;
            final Pane pane = pane();
            if (pane.source != null) {
                pane.source.stop(this);
            }
        }
        if (timeObserver != null) {
            timeObserver.stop();
        }
        if (composer != null && composer.editText.hasFocus() && !restoreComposerFocus) {
            AndroidUtilities.hideKeyboard(composer.editText);
        }
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        // iOS viewDidAppear. A pushed board is reported as its stack's top screen by LaunchActivity (when it is pushed
        // and when a screen above it closes); a tab is not on that stack, so it names itself.
        if (hasMainTabs) {
            reportScreen();
        }
        ZenlyStats.featureUsed(currentAccount, CustomerEvents.Feature.TASKS);
        reportScope();
    }

    /** The board as the screen on top now ({@link #zenlyScreen}): for a tab, and when the shown board became another screen. */
    private void reportScreen() {
        if (hasMainTabs) {
            ZenlyStats.mainTab(currentAccount, zenlyScreen().wire);
        } else {
            ZenlyStats.onTopFragment(currentAccount, this, false);
        }
    }

    /** iOS emitScopeAnalytics: Cloud counts as a feature of its own. */
    private void reportScope() {
        if (scope == ZgTasksScope.CLOUD) {
            ZenlyStats.featureUsed(currentAccount, CustomerEvents.Feature.TASKS_CLOUD);
        }
    }

    private void onTimeChanged() {
        updateViews();
    }

    private void focusComposer() {
        if (composer == null || fragmentView == null || composer.getVisibility() != View.VISIBLE) {
            return;
        }
        composer.editText.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(composer.editText), 150);
    }

    /** Pickers take the keyboard away; it comes back after them if the field had it. */
    private void takeKeyboardForPicker() {
        restoreComposerFocus = composer != null && composer.editText.hasFocus();
        if (composer != null) {
            AndroidUtilities.hideKeyboard(composer.editText);
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // The two stores
    // -----------------------------------------------------------------------------------------------------------

    private static boolean offers(ZgTasksRouting.Switch scopeSwitch, ZgTasksScope scope) {
        if (scopeSwitch == ZgTasksRouting.Switch.DEVICE_ONLY) {
            return scope == ZgTasksScope.DEVICE;
        }
        if (scopeSwitch == ZgTasksRouting.Switch.CLOUD_ONLY) {
            return scope == ZgTasksScope.CLOUD;
        }
        return true;
    }

    private void subscribe(final Pane pane) {
        if (pane.subscription != null) {
            return;
        }
        pane.source = ZgTasksSources.of(currentAccount, pane.scope);
        pane.subscription = pane.source.subscribe(board -> onBoard(pane, board));
    }

    private void onBoard(Pane pane, ZgBoard board) {
        if (destroyed) {
            return;
        }
        pane.state.update(board);
        if (pane != pane()) {
            return;
        }
        if (board.failed) {
            // What was shown stays (never a misleading empty board); the user is told once per failing streak, while
            // the board is on screen (coming back reads the store again).
            if (!pane.reportedFailure && resumed) {
                pane.reportedFailure = true;
                AndroidUtilities.runOnUIThread(() -> {
                    if (resumed && !destroyed) {
                        ZgTasks.showError(this, getString(R.string.ZG_tasks_error_load));
                    }
                });
            }
        } else {
            pane.reportedFailure = false;
        }
        applyPendingSelection(pane);
        updateViews();
        reveal(pane);
    }

    /**
     * The sync owner changed the account's cut-over (or the consent gate behind it): the switch and the board follow
     * it. A scope that follows is not remembered as the user's pick; an unchanged switch leaves the shown board alone.
     */
    private void applyRouting() {
        final ZgTasksRouting.Switch next = ZgTasksSources.scopeSwitch(currentAccount);
        if (next == scopeSwitch) {
            return;
        }
        final CustomerEvents.Screen before = zenlyScreen();
        scopeSwitch = next;
        final ZgTasksScope target = ZgTasksSources.scope(currentAccount);
        if (target != scope) {
            showScope(target, true);
        } else {
            updateViews();
            // The same board is another screen now (it became the archive, or writable again).
            if (resumed && zenlyScreen() != before) {
                reportScreen();
            }
        }
    }

    /** The user's explicit choice: remembered for the account, under the switch as it is now. */
    private void selectScope(ZgTasksScope next) {
        if (next == scope || !offers(scopeSwitch, next)) {
            return;
        }
        ZgTasksSources.pick(currentAccount, next);
        showScope(next, false);
    }

    /**
     * Shows the other store. The Undo of the board being left is committed first, both boards keep their filter,
     * Calendar view and composer draft, and no task moves between the stores.
     *
     * @param followed the board follows the cut-over, the user did not pick it: a task being typed goes along (the
     *                 board it was typed on may have just stopped taking tasks), unless the other board has a draft
     */
    private void showScope(ZgTasksScope next, boolean followed) {
        if (next == scope) {
            return;
        }
        commitHeldDelete();
        final Pane old = pane();
        if (composer != null) {
            old.draftText = composer.getText().toString();
            if (!followed) {
                AndroidUtilities.hideKeyboard(composer.editText);
            }
        }
        if (resumed && old.source != null) {
            old.source.stop(this);
        }
        scope = next;
        final Pane now = pane();
        if (followed && TextUtils.isEmpty(now.draftText) && now.draftAssignee == null && now.draftDueAt == null) {
            now.draftText = old.draftText;
            // Where assigning needs sync the device board stores no new assignee: a pick made on the other board stays there.
            now.draftAssignee = now.scope == ZgTasksScope.DEVICE && ZgTasksUi.deviceAssigneesAreOldEntries(currentAccount)
                ? null : old.draftAssignee;
            now.draftDueAt = old.draftDueAt;
            old.draftText = "";
            old.draftAssignee = null;
            old.draftDueAt = null;
        }
        if (fragmentView == null) {
            return;
        }
        subscribe(now);
        if (resumed) {
            now.source.start(this);
            // iOS emitScopeAnalytics: the feature of Cloud, and the archive as a screen of its own.
            reportScope();
            if (zenlyScreen() == CustomerEvents.Screen.TASKS_ARCHIVE) {
                reportScreen();
            }
        }
        composer.setText(now.draftText);
        updateViews();
        listView.scrollToPosition(0);
    }

    private void applyPendingSelection(Pane pane) {
        if (!pane.pendingSelectAll && pane.pendingSelectListId == null) {
            return;
        }
        if (pane.pendingSelectListId != null && pane.state.getBoard().list(pane.pendingSelectListId) == null) {
            return; // the new list has not reached the board yet
        }
        pane.state.select(pane.pendingSelectListId);
        pane.pendingSelectListId = null;
        pane.pendingSelectAll = false;
        if (pane == pane()) {
            updateViews();
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------------------------------

    private LocalDate today() {
        return ZgTasksCalendar.dayOf(now(), ZgTasksText.zone());
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private LocalDate selectedDay(Pane pane) {
        return pane.calendarDay != null ? pane.calendarDay : today();
    }

    private ZgTasksCalendar.Filter calendarFilter(Pane pane) {
        return pane.calendarUndated ? ZgTasksCalendar.Filter.NO_DEADLINE : ZgTasksCalendar.Filter.day(selectedDay(pane));
    }

    private String listTitle(ZgBoardList list) {
        return list.inbox ? getString(R.string.ZG_tasks_inbox_title) : list.title;
    }

    private void updateViews() {
        if (fragmentView == null) {
            return;
        }
        final Pane pane = pane();
        final ZgBoard board = pane.state.getBoard();
        final boolean twoScopes = scopeSwitch == ZgTasksRouting.Switch.CLOUD_AND_DEVICE || scopeSwitch == ZgTasksRouting.Switch.CLOUD_AND_ARCHIVE;
        scopePanel.setVisibility(twoScopes ? View.VISIBLE : View.GONE);
        scopeView.set(scope, ZgTasksRouting.deviceIsArchive(scopeSwitch));
        // A gated board has no lists to filter and nothing to add to; a read-only one keeps its filters.
        chipsPanel.setVisibility(board.isUsable() ? View.VISIBLE : View.GONE);
        chipsView.setChips(pane.state.chips(), board.canCompose());
        viewSwitch.setSelected(pane.calendarMode ? 1 : 0);
        updateSwitchColors();
        if (pane.calendarMode) {
            final ZoneId zone = ZgTasksText.zone();
            final LocalDate today = today();
            final ZgTasksCalendar.DueCounts counts = ZgTasksCalendar.dueDateCounts(pane.state.calendarDueDates(), zone);
            final LocalDate day = pane.calendarUndated ? null : selectedDay(pane);
            final int summary = pane.calendarUndated ? counts.undated : counts.count(day);
            final YearMonth month = pane.shownMonth != null ? pane.shownMonth : YearMonth.from(day != null ? day : today);
            calendarHeader.set(day, today, counts, summary, pane.monthExpanded, month);
        }
        final boolean compose = board.canCompose();
        if (!compose && composer.editText.hasFocus()) {
            AndroidUtilities.hideKeyboard(composer.editText);
        }
        composer.setVisibility(compose ? View.VISIBLE : View.GONE);
        composer.setCloud(scope == ZgTasksScope.CLOUD);
        final ZgBoardList selected = pane.state.selectedList();
        composer.setPlaceholder(selected != null ? formatString(R.string.ZG_tasks_quickAdd_placeholderInList, listTitle(selected))
            : getString(R.string.ZG_tasks_quickAdd_placeholder));
        updateComposer();
        updateMenu(board);
        applyInsets();
        listView.adapter.update(true);
    }

    private void updateMenu(ZgBoard board) {
        if (listsItem == null) {
            return;
        }
        // Lists belong to a board that shows content; Invitations and Unsent Changes only exist in Cloud.
        listsItem.setShown(board.isUsable(), true);
        final boolean cloud = board.scope == ZgTasksScope.CLOUD && board.isUsable();
        if (cloud) {
            // Invitations stays reachable without one: the senders this account blocked are unblocked there.
            invitationsItem.setText(board.invitations > 0 ? formatString(R.string.ZG_tasks_cloud_invitations_count, board.invitations)
                : getString(R.string.ZenlyCloudTasksInvitations));
            // The number is what waits for the user; changes that are only on their way are listed there without one.
            unsentItem.setText(board.unsentAttention > 0 ? formatString(R.string.ZG_tasks_cloud_issues_count, board.unsentAttention)
                : getString(R.string.ZG_tasks_cloud_issues_title));
            moreItem.setSubItemShown(MENU_UNSENT, board.unsentAttention + board.unsentWaiting > 0);
        }
        moreItem.setShown(cloud, true);
    }

    private void updateSwitchColors() {
        final int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
        viewSwitch.setColors(Theme.multAlpha(black, 0.06f), Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider),
            Theme.multAlpha(black, 0.6f), black);
    }

    private void updateComposer() {
        if (composer == null) {
            return;
        }
        final Pane pane = pane();
        composer.setAssignee(pane.draftAssignee != null ? pane.draftAssignee.displayName : null);
        final long now = now();
        composer.setDeadline(pane.draftDueAt != null ? ZgTasksText.deadlineText(pane.draftDueAt, now) : null,
            pane.draftDueAt != null && pane.draftDueAt < now);
    }

    private void setCalendarMode(boolean calendar) {
        final Pane pane = pane();
        if (pane.calendarMode == calendar) {
            return;
        }
        pane.calendarMode = calendar;
        if (calendar) {
            pane.calendarUndated = false;
            pane.calendarDay = null;
            pane.shownMonth = null;
            // iOS emitCalendarShown: the calendar is a screen and a feature of its own.
            ZenlyStats.screen(currentAccount, CustomerEvents.Screen.TASKS_CALENDAR);
            ZenlyStats.featureUsed(currentAccount, CustomerEvents.Feature.TASKS_CALENDAR);
        }
        updateViews();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (chipsView == null) {
            return;
        }
        final Pane pane = pane();
        final ZgBoard board = pane.state.getBoard();
        if (board.gate != null) {
            // A gated board shows only its gate: what is wrong, and what the user can do about it.
            fillGate(items, board);
            return;
        }
        // The pinned rows are above the list; the List / Calendar switch is its first row.
        items.add(UItem.asCustomShadow(ID_VIEW_SWITCH, viewSwitchContainer));
        if (pane.calendarMode) {
            items.add(UItem.asCustomShadow(ID_CALENDAR, calendarHeader));
        }
        fillStatus(items, board);
        final List<List<ZgTasksBoardState.Row>> sections = pane.calendarMode
            ? pane.state.calendarSections(calendarFilter(pane), ZgTasksText.zone()) : pane.state.sections();
        final boolean underAll = pane.state.getFilterListId() == null;
        final long now = now();
        final List<ZgTasksBoardState.Row> open = sections.get(0);
        for (ZgTasksBoardState.Row row : open) {
            if (row.kind == ZgTasksBoardState.Row.TASK) {
                addTask(items, pane, row.taskId, underAll, now);
            } else if (row.kind == ZgTasksBoardState.Row.ASSIGNED_HEADER) {
                items.add(UItem.asHeader(ID_ASSIGNED_HEADER, getString(R.string.ZG_tasks_assigned_header)));
            } else {
                items.add(emptyItem(pane, row.kind));
            }
        }
        if (!open.isEmpty()) {
            items.add(UItem.asShadow(ID_OPEN_SHADOW, null));
        }
        final List<ZgTasksBoardState.Row> completed = sections.get(1);
        if (!completed.isEmpty()) {
            for (ZgTasksBoardState.Row row : completed) {
                if (row.kind == ZgTasksBoardState.Row.COMPLETED_HEADER) {
                    items.add(ZgTasksBoardCells.CompletedHeaderCell.Factory.of(ID_COMPLETED_HEADER, row.count, row.expanded));
                } else if (row.kind == ZgTasksBoardState.Row.TASK) {
                    addTask(items, pane, row.taskId, underAll, now);
                }
            }
            items.add(UItem.asShadow(ID_COMPLETED_SHADOW, null));
        }
        if (board.scope == ZgTasksScope.CLOUD) {
            if (board.offersDeviceCopy) {
                items.add(UItem.asButton(ID_IMPORT, R.drawable.msg_copy, getString(R.string.ZG_tasks_cloud_import_action)));
                items.add(UItem.asShadow(ID_IMPORT_SHADOW, null));
            }
            items.add(UItem.asShadow(ID_PRIVACY, getString(R.string.ZG_tasks_cloud_privacyFooter)));
        }
    }

    private void fillGate(ArrayList<UItem> items, ZgBoard board) {
        final boolean cloud = board.scope == ZgTasksScope.CLOUD;
        final boolean deviceOffered = cloud && offers(scopeSwitch, ZgTasksScope.DEVICE);
        switch (board.gate) {
            case UNLINKED:
                items.add(ZgTasksBoardCells.StatusCell.Factory.of(ID_GATE, getString(R.string.ZG_tasks_cloud_unlinked_title),
                    getString(R.string.ZG_tasks_cloud_unlinked_text)));
                items.add(UItem.asButton(ID_LINK, R.drawable.msg_link, getString(R.string.ZG_tasks_cloud_link_action)).accent());
                break;
            case STORAGE:
                items.add(ZgTasksBoardCells.StatusCell.Factory.of(ID_GATE, getString(R.string.ZG_tasks_cloud_unavailable_title),
                    getString(R.string.ZenlyCloudTasksStatusError)));
                items.add(UItem.asButton(ID_RETRY, R.drawable.msg_retry, getString(R.string.ZenlyCloudTasksRetry)).accent());
                break;
            default:
                items.add(ZgTasksBoardCells.StatusCell.Factory.of(ID_GATE, cloud ? getString(R.string.ZG_tasks_cloud_unavailable_title) : null,
                    getString(cloud ? R.string.ZenlyCloudTasksStatusClosed : R.string.ZG_tasks_error_accountChanged)));
                break;
        }
        if (deviceOffered && board.gate != ZgBoard.Gate.SIGNED_OUT) {
            items.add(UItem.asButton(ID_USE_DEVICE, R.drawable.msg_list, getString(R.string.ZG_tasks_cloud_useDevice_action)));
        }
        items.add(UItem.asShadow(ID_GATE_SHADOW, null));
    }

    /** The store's own state above the tasks: the archive banner, the sync banner, unsent changes, invitations, notices. */
    private void fillStatus(ArrayList<UItem> items, ZgBoard board) {
        final int before = items.size();
        if (board.archive != null) {
            items.add(ZgTasksBoardCells.StatusCell.Factory.of(ID_ARCHIVE, getString(R.string.ZG_tasks_archive_segment),
                ZgTasksText.archiveText(board.archive)));
            if (board.archive.offersCopy()) {
                items.add(UItem.asButton(ID_ARCHIVE_COPY, R.drawable.msg_copy, getString(R.string.ZG_tasks_archive_copy)).accent());
            }
        }
        if (board.banner != null) {
            items.add(ZgTasksBoardCells.StatusCell.Factory.of(ID_BANNER, bannerTitle(board.banner), bannerText(board.banner)));
            switch (board.banner) {
                case PAUSED:
                case SESSION:
                case NOT_LINKED_YET:
                    // Linking is the only recovery of a paused account. The server may have dropped the link while
                    // this device still holds its session, so a stored session is not enough here: the link screen
                    // starts a fresh proof by Telegram (iOS openLinkAccount, requiresTelegramProof).
                    items.add(UItem.asButton(ID_LINK_PROOF, R.drawable.msg_link, getString(R.string.ZG_tasks_cloud_link_action)).accent());
                    break;
                case OFFLINE:
                case UNAVAILABLE:
                    items.add(UItem.asButton(ID_RETRY, R.drawable.msg_retry, getString(R.string.ZenlyCloudTasksRetry)).accent());
                    break;
                default:
                    break;
            }
        }
        // One row for what is not confirmed yet, leading to Unsent Changes (iOS .unsent): what needs the user when
        // anything does, else what is still on its way.
        if (board.unsentAttention > 0) {
            items.add(UItem.asButton(ID_UNSENT, R.drawable.msg_report, formatPluralString("ZG_tasks_cloud_status_issues", board.unsentAttention)));
        } else if (board.unsentWaiting > 0) {
            items.add(UItem.asButton(ID_UNSENT, R.drawable.msg_retry, formatPluralString("ZG_tasks_cloud_status_pending", board.unsentWaiting)));
        }
        if (board.invitations > 0) {
            items.add(UItem.asButton(ID_INVITATIONS, R.drawable.msg_message, formatPluralString("ZG_tasks_cloud_invitations_banner", board.invitations)));
        }
        for (int i = 0; i < board.notices.size(); i++) {
            items.add(ZgTasksBoardCells.StatusCell.Factory.of(ID_NOTICE_BASE + 2 * i, null, noticeText(board.notices.get(i).kind)));
            items.add(UItem.asButton(ID_NOTICE_BASE + 2 * i + 1, getString(R.string.OK)));
        }
        if (items.size() > before) {
            items.add(UItem.asShadow(ID_STATUS_SHADOW, null));
        }
    }

    private static String bannerTitle(ZgBoard.Banner banner) {
        return banner == ZgBoard.Banner.PAUSED || banner == ZgBoard.Banner.SESSION ? getString(R.string.ZG_tasks_cloud_paused_title) : null;
    }

    /** The engine's status texts, one per state (the import's ZenlyCloudTasksText.status and consent banner). */
    private static String bannerText(ZgBoard.Banner banner) {
        switch (banner) {
            case LOADING:
                return getString(R.string.ZenlyCloudTasksStatusLoading);
            case PAUSED:
                return getString(R.string.ZenlyCloudTasksStatusPaused);
            case SESSION:
                return getString(R.string.ZenlyCloudTasksStatusSession);
            case OFFLINE:
                return getString(R.string.ZenlyCloudTasksStatusOffline);
            case UNAVAILABLE:
                return getString(R.string.ZenlyCloudTasksStatusUnavailable);
            case CONSENT_OFF:
                return getString(R.string.ZenlyCloudTasksConsentOff);
            case WITHDRAWN:
                return getString(R.string.ZenlyCloudTasksWithdrawn);
            case CONSENT_FENCED:
                return getString(R.string.ZenlyCloudTasksConsentFenced);
            case NOT_LINKED_YET:
                return getString(R.string.ZenlyCloudTasksNotLinkedYet);
            default:
                return getString(R.string.ZenlyCloudTasksPreparing);
        }
    }

    private static String noticeText(ZgBoard.Notice.Kind kind) {
        switch (kind) {
            case ACCESS_LOST:
                return getString(R.string.ZenlyCloudTasksNoticeAccessLost);
            case NOT_FOUND:
                return getString(R.string.ZenlyCloudTasksNoticeNotFound);
            case REMOVED_IN_CLOUD:
                return getString(R.string.ZenlyCloudTasksNoticeRemovedInCloud);
            case DELETE_KEPT:
                return getString(R.string.ZenlyCloudTasksDeleteKept);
            default:
                return getString(R.string.ZenlyCloudTasksNoticeDependencyDropped);
        }
    }

    private void addTask(ArrayList<UItem> items, Pane pane, String taskId, boolean underAll, long now) {
        final ZgBoard board = pane.state.getBoard();
        final ZgBoardTask task = board.task(taskId);
        if (task == null) {
            return;
        }
        final String deadline = task.dueAt != null ? ZgTasksText.deadlineText(task.dueAt, now) : null;
        final boolean overdue = task.dueAt != null && !task.completed && task.dueAt < now;
        String place = null;
        if (task.isShared()) {
            // Who assigned it is said under every filter: such a task belongs to no list of this account.
            place = formatString(R.string.ZG_tasks_cloud_row_sharedBy, ZgTasksUi.person(currentAccount, task.sharedByTgId));
        } else if (underAll) {
            final ZgBoardList list = board.list(task.listId);
            if (list != null) {
                place = listTitle(list);
            } else if (board.scope == ZgTasksScope.CLOUD && task.listId == null) {
                place = getString(R.string.ZenlyCloudTasksInbox);
            }
        }
        final StringBuilder status = new StringBuilder();
        append(status, ZgTasksUi.syncText(task.sync));
        if (task.provisional) {
            // The device is accepting a contact's task: no access until the server confirms it.
            append(status, getString(R.string.ZG_cloudui_syncConfirmationPending));
        }
        if (task.accessInactive) {
            append(status, getString(R.string.ZG_tasks_cloud_access_inactive));
        }
        // The Cloud owner's assignee is the assignment ("Name · state"); a device row's is a note on this device.
        String assignee = null;
        if (task.assigneeTgId != null) {
            assignee = ZgTasksUi.assigneeLine(currentAccount, task);
        } else if (task.assignee != null) {
            assignee = ZgTasksUi.deviceAssigneeText(currentAccount, task.assignee.displayName);
        }
        final ZgTaskCell.Model model = new ZgTaskCell.Model(task, place, deadline, overdue, status.length() > 0 ? status.toString() : null, assignee);
        items.add(ZgTaskCell.Factory.of(model, this::setTaskCompleted));
    }

    private static void append(StringBuilder builder, String part) {
        if (part == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" · ");
        }
        builder.append(part);
    }

    private UItem emptyItem(Pane pane, int kind) {
        if (kind == ZgTasksBoardState.Row.EMPTY_ALL_DONE) {
            return ZgTasksBoardCells.EmptyCell.Factory.of(ID_EMPTY, getString(R.string.ZG_tasks_empty_allDone_title),
                getString(R.string.ZG_tasks_empty_allDone_text));
        }
        if (pane.calendarMode) {
            if (pane.calendarUndated) {
                return ZgTasksBoardCells.EmptyCell.Factory.of(ID_EMPTY, getString(R.string.ZG_tasks_calendar_emptyUndated_title),
                    getString(R.string.ZG_tasks_calendar_emptyUndated_text));
            }
            return ZgTasksBoardCells.EmptyCell.Factory.of(ID_EMPTY, getString(R.string.ZG_tasks_calendar_emptyDay_title),
                getString(R.string.ZG_tasks_calendar_emptyDay_text));
        }
        final ZgBoard board = pane.state.getBoard();
        if (board.readOnly != null) {
            // Nothing here to add to: the text says why instead of pointing at a hidden composer.
            return ZgTasksBoardCells.EmptyCell.Factory.of(ID_EMPTY, getString(R.string.ZG_tasks_empty_noTasks_title),
                ZgTasksUi.readOnlyText(board.readOnly));
        }
        return ZgTasksBoardCells.EmptyCell.Factory.of(ID_EMPTY, getString(R.string.ZG_tasks_empty_noTasks_title),
            getString(pane.state.getFilterListId() == null ? R.string.ZG_tasks_empty_noTasks_text : R.string.ZG_tasks_empty_listEmpty_text));
    }

    // -----------------------------------------------------------------------------------------------------------
    // Interaction
    // -----------------------------------------------------------------------------------------------------------

    private void onClick(UItem item, View view, int position, float x, float y) {
        final ZgBoardTask task = ZgTaskCell.Factory.taskOf(item);
        if (task != null) {
            openTask(task.id);
            return;
        }
        if (item.id == ID_COMPLETED_HEADER && item.instanceOf(ZgTasksBoardCells.CompletedHeaderCell.Factory.class)) {
            final Pane pane = pane();
            pane.state.completedExpanded = !pane.state.completedExpanded;
            updateViews();
            return;
        }
        switch (item.id) {
            case ID_LINK:
                presentFragment(new ZgLinkAccountActivity());
                break;
            case ID_LINK_PROOF:
                presentFragment(ZgLinkAccountActivity.forTelegramProof());
                break;
            case ID_USE_DEVICE:
                selectScope(ZgTasksScope.DEVICE);
                break;
            case ID_RETRY:
                if (pane().source != null) {
                    pane().source.refresh();
                }
                break;
            case ID_UNSENT:
                openUnsentChanges();
                break;
            case ID_INVITATIONS:
                openInvitations();
                break;
            case ID_ARCHIVE_COPY:
            case ID_IMPORT:
                openCopyDialog();
                break;
            default:
                dismissNotice(item.id);
                break;
        }
    }

    private void dismissNotice(int itemId) {
        final int index = itemId - ID_NOTICE_BASE;
        final Pane pane = pane();
        final List<ZgBoard.Notice> notices = pane.state.getBoard().notices;
        if (index < 0 || index % 2 != 1 || index / 2 >= notices.size() || pane.source == null) {
            return;
        }
        pane.source.dismissNotice(notices.get(index / 2).id);
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final ZgBoardTask task = ZgTaskCell.Factory.taskOf(item);
        if (task == null) {
            return false;
        }
        final String id = task.id;
        final boolean completed = task.completed;
        final ItemOptions options = ItemOptions.makeOptions(optionsHost(), view);
        if (task.canComplete) {
            options.add(R.drawable.msg_select, getString(completed ? R.string.ZG_tasks_a11y_markNotDone : R.string.ZG_tasks_a11y_markDone),
                () -> setTaskCompleted(id, !completed));
        }
        // A task that cannot be edited here (shared with this account, or on a read-only board) opens to be read.
        options.add(task.canEdit ? R.drawable.msg_edit : R.drawable.msg_openin,
            getString(task.canEdit ? R.string.ZG_tasks_action_edit : R.string.ZG_tasks_action_open), () -> openTask(id));
        // An ended assignment goes to the same person again; an older note that was never sent is sent for the first time.
        if (task.canEdit && !completed && task.assigneeState != null && task.assigneeState.offersSendAction()) {
            options.add(R.drawable.msg_send, getString(task.assigneeState == TasksAssigneeDisplay.State.NOT_SENT
                ? R.string.ZG_tasks_assign_send : R.string.ZG_tasks_assign_resend), () -> sendAssignee(id));
        }
        if (task.canDelete) {
            options.add(R.drawable.msg_delete, getString(R.string.Delete), true, () -> deleteTask(id));
        } else if (task.canLeave) {
            options.add(R.drawable.msg_leave, getString(R.string.ZenlyCloudTasksLeave), true, () -> leaveTask(id));
        }
        options.show();
        return true;
    }

    /** In the main tabs the menu belongs to the tabs screen, so its dim also covers the tab bar (ItemOptions.downFragment). */
    private BaseFragment optionsHost() {
        if (hasMainTabs && getParentLayout() != null) {
            final BaseFragment last = getParentLayout().getSafeLastFragment();
            if (last instanceof MainTabsActivity) {
                return last;
            }
        }
        return this;
    }

    /** What a refused change says: a fence is a short note (the input stays), anything else an alert. */
    private void report(Pane pane, ZgTasksError error, int limit) {
        if (destroyed) {
            return;
        }
        ZgTasksUi.report(this, error, pane.state.getBoard(), limit);
    }

    /** The explicit target state the row or menu showed, never a toggle of whatever is stored now. */
    private void setTaskCompleted(String id, boolean completed) {
        final Pane pane = pane();
        final ZgBoardTask task = pane.state.task(id);
        if (task == null || pane.source == null) {
            return;
        }
        if (!task.canComplete) {
            final ZgBoard board = pane.state.getBoard();
            if (board.readOnly != null) {
                ZgTasksUi.note(this, ZgTasksUi.readOnlyText(board.readOnly));
            }
            return;
        }
        pane.source.setCompleted(id, completed, (ignored, error) -> {
            if (error != null) {
                report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
            }
        });
    }

    /** A device task opens the device editor (read-only in the archive); a Cloud task opens the Cloud task editor. */
    private void openTask(String id) {
        final Pane pane = pane();
        if (pane.state.task(id) == null || getParentLayout() == null) {
            return;
        }
        final BaseFragment top = getParentLayout().getLastFragment();
        if (top instanceof ZgTaskEditorActivity || top instanceof ZgCloudTaskEditorActivity) {
            return;
        }
        if (composer != null) {
            AndroidUtilities.hideKeyboard(composer.editText);
        }
        presentFragment(pane.scope == ZgTasksScope.CLOUD ? new ZgCloudTaskEditorActivity(currentAccount, id) : new ZgTaskEditorActivity(currentAccount, id));
    }

    /**
     * A held delete: the task leaves the board at once and is deleted in the store when its Undo is gone (the bulletin
     * closed, the board was left, another delete began). One Undo at a time: an earlier one is committed first.
     */
    private void deleteTask(final String id) {
        final Pane pane = pane();
        final ZgTasksSource source = pane.source;
        if (source == null || root == null) {
            return;
        }
        commitHeldDelete();
        source.holdDelete(id, (held, error) -> {
            if (destroyed) {
                if (held != null) {
                    held.commit(null);
                }
                return;
            }
            if (error != null) {
                if (error.kind != ZgTasksError.Kind.NOT_FOUND) {
                    report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
                }
                return; // already gone: nothing to undo
            }
            if (!resumed || pane != pane()) {
                // The board was left meanwhile: no Undo can be shown, so the delete is made.
                held.commit(null);
                return;
            }
            heldDelete = held;
            heldSource = source;
            // The bulletin leaves a little before the store's own Undo window ends, so its Undo never comes too late.
            BulletinFactory.of(root, resourceProvider)
                .createUndoBulletin(getString(R.string.ZG_tasks_taskDeleted), () -> undoDelete(pane, held), () -> commitDelete(pane, held))
                .setDuration((int) Math.max(1500, source.undoWindowMs() - 500))
                .show();
        });
    }

    private void undoDelete(final Pane pane, ZgTasksSource.HeldDelete held) {
        if (heldDelete == held) {
            heldDelete = null;
            heldSource = null;
        }
        held.undo((ignored, error) -> {
            if (error != null) {
                report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
            }
        });
    }

    private void commitDelete(final Pane pane, ZgTasksSource.HeldDelete held) {
        if (heldDelete == held) {
            heldDelete = null;
            heldSource = null;
        }
        held.commit((ignored, error) -> {
            // The task is back on the board: say why it could not be deleted.
            if (error != null) {
                report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
            }
        });
    }

    /** The Undo's time is over for whatever reason: the held delete is made, and its bulletin goes. */
    private void commitHeldDelete() {
        final ZgTasksSource.HeldDelete held = heldDelete;
        if (held != null) {
            commitDelete(heldSource == cloudPane.source ? cloudPane : devicePane, held);
        }
        if (root != null) {
            Bulletin.hideVisible(root);
        }
    }

    /** A task shared with this account can only be left: confirmed, no Undo. */
    private void leaveTask(final String id) {
        final Pane pane = pane();
        final ZgBoardTask task = pane.state.task(id);
        final Activity activity = getParentActivity();
        if (task == null || activity == null || pane.source == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourceProvider);
        builder.setTitle(getString(R.string.ZenlyCloudTasksLeave));
        builder.setMessage(formatString(R.string.ZenlyCloudTasksLeaveConfirm, task.title));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.ZenlyCloudTasksLeave), (d, which) -> pane.source.leave(id, (ignored, error) -> {
            if (error != null) {
                report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
            }
        }));
        final AlertDialog dialog = builder.create();
        if (showDialog(dialog) != null) {
            dialog.redPositive();
        }
    }

    /** The quick-add CREATE path: title check, gate, store; the draft is cleared only after the task is stored. */
    private void submitQuickAdd() {
        final Pane pane = pane();
        final ZgBoard board = pane.state.getBoard();
        if (pane.source == null || pane.creating) {
            return;
        }
        if (!board.canCompose()) {
            if (board.readOnly != null) {
                ZgTasksUi.note(this, ZgTasksUi.readOnlyText(board.readOnly));
            }
            return;
        }
        final ZgTasksTitlePolicy.Result result = ZgTasksTitlePolicy.validate(composer.getText(), ZgTasksTitlePolicy.TASK_LIMIT);
        if (result.kind == ZgTasksTitlePolicy.Kind.EMPTY) {
            return;
        }
        if (result.kind == ZgTasksTitlePolicy.Kind.TOO_LONG) {
            ZgTasks.showError(this, formatString(R.string.ZG_tasks_error_titleTooLong, ZgTasksTitlePolicy.TASK_LIMIT));
            return;
        }
        if (!ZgTasksGate.allowCreate(this)) {
            return; // nothing written, the draft stays
        }
        final ZgTodoAssignee assignee = pane.draftAssignee;
        final Long dueAt = pane.draftDueAt;
        pane.creating = true;
        pane.source.createTask(pane.state.quickAddListId(), result.title, assignee, dueAt, (taskId, error) -> {
            pane.creating = false;
            if (destroyed) {
                return;
            }
            if (error != null) {
                // The draft stays: a fenced write works in a moment; a list deleted meanwhile falls back to "All".
                report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
                return;
            }
            pane.draftAssignee = null;
            pane.draftDueAt = null;
            pane.draftText = "";
            if (pane.calendarMode) {
                if (dueAt == null) {
                    pane.calendarUndated = true;
                } else {
                    pane.calendarUndated = false;
                    final LocalDate day = ZgTasksCalendar.dayOf(dueAt, ZgTasksText.zone());
                    pane.calendarDay = day.equals(today()) ? null : day;
                    pane.shownMonth = null;
                }
            }
            pane.revealTaskId = taskId;
            if (pane == pane()) {
                composer.clearText();
                updateViews();
                reveal(pane);
            }
        });
    }

    /** Scrolls to the task that was just added, once the board that holds it is shown. */
    private void reveal(Pane pane) {
        final String taskId = pane.revealTaskId;
        if (listView == null || taskId == null || pane != pane() || pane.state.task(taskId) == null) {
            return;
        }
        pane.revealTaskId = null;
        listView.post(() -> {
            for (int i = 0; i < listView.adapter.getItemCount(); i++) {
                final ZgBoardTask t = ZgTaskCell.Factory.taskOf(listView.adapter.getItem(i));
                if (t != null && t.id.equals(taskId)) {
                    listView.smoothScrollToPosition(i);
                    break;
                }
            }
        });
    }

    private void createList() {
        final Pane pane = pane();
        if (pane.source == null) {
            return;
        }
        ZgTasks.createList(this, pane.source, listId -> {
            pane.pendingSelectListId = listId;
            pane.pendingSelectAll = false;
            applyPendingSelection(pane);
        });
    }

    private void openLists() {
        final Pane pane = pane();
        if (getParentLayout() == null || getParentLayout().getLastFragment() instanceof ZgTasksListsActivity) {
            return;
        }
        if (composer != null) {
            AndroidUtilities.hideKeyboard(composer.editText);
        }
        presentFragment(new ZgTasksListsActivity(currentAccount, pane.scope, listId -> {
            pane.pendingSelectListId = listId;
            pane.pendingSelectAll = listId == null;
        }));
    }

    private void openInvitations() {
        if (getParentLayout() != null && !(getParentLayout().getLastFragment() instanceof ZgTasksInvitationsActivity)) {
            presentFragment(new ZgTasksInvitationsActivity(currentAccount));
        }
    }

    private void openUnsentChanges() {
        if (getParentLayout() != null && !(getParentLayout().getLastFragment() instanceof ZgTasksUnsentActivity)) {
            presentFragment(new ZgTasksUnsentActivity(currentAccount));
        }
    }

    /** "Copy Device Tasks to Cloud…" and the archive's "Copy to Cloud": the explicit copy, after its preview. */
    private void openCopyDialog() {
        if (copyFlow != null && copyFlow.isActive()) {
            return;
        }
        copyFlow = ZgTasksCopyDialog.open(this, currentAccount);
    }

    private void pickAssignee() {
        if (sheetOpen) {
            return;
        }
        final Pane pane = pane();
        if (pane.source == null) {
            return;
        }
        // On the device board of a build with sync a pick would be a note nobody receives: explained, nothing stored.
        if (pane.scope == ZgTasksScope.DEVICE && ZgTasksUi.answerDevicePick(this, currentAccount)) {
            return;
        }
        takeKeyboardForPicker();
        final Runnable open = () -> {
            // presentFragment refuses while a transition runs, so a double tap cannot push two pickers.
            if (destroyed || !ZgTasksAssigneePicker.present(this, assignee -> checkPickedAssignee(pane, assignee))) {
                if (restoreComposerFocus) {
                    restoreComposerFocus = false;
                    focusComposer();
                }
            }
        };
        if (pane.scope == ZgTasksScope.CLOUD) {
            // The account's first pick: "How assigning works", then the picker.
            ZgTasksUi.explainAssigningOnce(this, currentAccount, open);
        } else {
            open.run();
        }
    }

    /**
     * The store is asked the moment a contact is picked: on the Cloud board the pick is the invitation, so the account
     * itself and the assignment limits are refused here with their sentence and the pill stays empty. Nothing is sent
     * before the task is added.
     */
    private void checkPickedAssignee(final Pane pane, final ZgTodoAssignee assignee) {
        if (destroyed || pane.source == null || assignee == null) {
            return;
        }
        pane.source.checkAssignee(assignee, (ignored, error) -> AndroidUtilities.runOnUIThread(() -> {
            // The picker is closing: a dialog or a note is shown once this screen is in front again.
            if (destroyed) {
                return;
            }
            if (error != null) {
                report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
                return;
            }
            pane.draftAssignee = assignee;
            if (pane == pane()) {
                updateComposer();
            }
        }, 350));
    }

    /** The row menu's Send Again / Send. */
    private void sendAssignee(final String taskId) {
        final Pane pane = pane();
        if (pane.source == null) {
            return;
        }
        ZgTasksUi.explainAssigningOnce(this, currentAccount, () -> {
            if (destroyed || pane.source == null) {
                return;
            }
            pane.source.sendAssignee(taskId, (ignored, error) -> {
                if (error != null) {
                    report(pane, error, ZgTasksTitlePolicy.TASK_LIMIT);
                }
            });
        });
    }

    private void pickDeadline() {
        if (sheetOpen || getParentActivity() == null) {
            return;
        }
        final Pane pane = pane();
        takeKeyboardForPicker();
        sheetOpen = true;
        final ZgTasksDeadlineSheet sheet = new ZgTasksDeadlineSheet(getParentActivity(), resourceProvider, pane.draftDueAt, new ZgTasksDeadlineSheet.Callback() {
            @Override
            public void onSet(long dueAt) {
                pane.draftDueAt = dueAt;
                updateComposer();
            }

            @Override
            public void onClear() {
                pane.draftDueAt = null;
                updateComposer();
            }
        });
        if (showDialog(sheet, d -> afterSheetClosed()) == null) {
            afterSheetClosed();
        }
    }

    private void afterSheetClosed() {
        sheetOpen = false;
        if (restoreComposerFocus) {
            restoreComposerFocus = false;
            focusComposer();
        }
    }

    private void showMonthPicker() {
        if (sheetOpen || getParentActivity() == null) {
            return;
        }
        final Pane pane = pane();
        final YearMonth current = calendarHeader.getGrid().getMonth();
        sheetOpen = true;
        final org.telegram.ui.ActionBar.BottomSheet sheet = ZgTasksMonthGridView.createMonthPicker(getParentActivity(), resourceProvider, current, month -> {
            pane.shownMonth = month;
            updateViews();
        });
        if (showDialog(sheet, d -> sheetOpen = false) == null) {
            sheetOpen = false;
        }
    }

    @Override
    public void onThemeColorsChanged() {
        super.onThemeColorsChanged();
        if (fragmentView != null) {
            updateSwitchColors();
            // Not list rows: nothing else recolors them.
            if (chipsView != null) {
                chipsView.updateColors();
            }
            if (scopeView != null) {
                scopeView.updateColors();
            }
            if (chipsPanel != null) {
                chipsPanel.invalidate();
            }
            if (scopePanel != null) {
                scopePanel.invalidate();
            }
            updateViews();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (listView != null) {
            AndroidUtilities.runOnUIThread(() -> listView.adapter.notifyDataSetChanged());
        }
    }
}
