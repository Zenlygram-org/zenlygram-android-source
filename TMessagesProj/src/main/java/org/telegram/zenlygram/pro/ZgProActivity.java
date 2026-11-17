/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.pro;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.zenlygram.customer.CustomerEvents;
import org.telegram.messenger.zenlygram.customer.ZenlyStats;
import org.telegram.messenger.zenlygram.pro.GooglePlayCheckout;
import org.telegram.messenger.zenlygram.pro.GooglePlayModel;
import org.telegram.messenger.zenlygram.pro.ZenlyPro;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.zenlygram.cloud.ZgBackend;
import org.telegram.zenlygram.cloud.ZgCloudPolicies;
import org.telegram.zenlygram.cloud.ZgPublicPolicy;
import org.telegram.zenlygram.core.ZgConfig;
import org.telegram.zenlygram.core.ZgFeatures;
import org.telegram.zenlygram.core.ZgSettings;
import org.telegram.zenlygram.settings.ZgSettingRowCell;
import org.telegram.zenlygram.settings.ZgSettingsIcons.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * "Zenlygram Pro": the one Pro screen (iOS SGEntitlementStatusContent and the paywall). {@link ZgProGate} opens it when
 * a create is blocked; Settings opens it from its Zenlygram Pro rows.
 *
 * <p><b>Free</b> (the signed policy is free): the access line says so and nothing is sold: no price, no plan, no
 * Subscribe. <b>Paid</b>: the access line is the account's state ({@link ZgPro#getAccess(int)}: active until, free
 * until the end of the local grace, not active, ended, reconnect), and the Google Play build sells below it.
 *
 * <p><b>Google Play</b> (the Play build only, {@link ZgPro#isPlayBuild()}): the section is a projection of the
 * client's checkout ({@link GooglePlayCheckout}, an immutable state per step): Play's own localized prices and
 * introductory phases, one row per plan, Try again, Restore purchases and the way to Play's subscription page. A
 * purchase token is only sent to the server for verification; access always comes from the signed grant the server
 * answers with, never from a local purchase. While Pro is free the section shows only for an account with a known
 * Play subscription (nothing new can be bought; it keeps renewing until cancelled in Play).
 *
 * <p><b>The website</b> (the side-load build only, {@link ZgPro#offersWebPurchase()}): this build has no Play Billing
 * and sells nothing itself. While Pro is a subscription it opens the website's checkout
 * ({@link ZgConfig#PRO_WEB_URL}: card payment through Atmos) in the browser; the buyer signs in there with the Telegram
 * account, and the grant belongs to that account, so it reaches this device through the account link. Back from the
 * browser the screen asks the server for the grant again by itself. The Play build never shows this.
 *
 * <p>The account link has its row here as on the iOS status screen, and a linked account can ask the server for its
 * grant again (after a purchase on another device). Linking itself is {@link ZgLinkAccountActivity}.
 *
 * <p>Every answer is dropped once the screen is gone or its slot belongs to another Telegram user. Main thread only.
 * Nothing here logs.
 */
public class ZgProActivity extends UniversalFragment implements ZenlyStats.NamedScreen {

    private static final int ID_STATUS = 1;
    private static final int ID_STATUS_NOTICE = 2;
    private static final int ID_POLICY = 3;
    private static final int ID_LINK = 4;
    private static final int ID_REFRESH = 5;
    private static final int ID_REFRESHING = 6;
    private static final int ID_ACCOUNT_END = 7;
    private static final int ID_FEATURES_HEADER = 10;
    private static final int ID_FEATURE_VAULT = 11;
    private static final int ID_FEATURE_CHAT_LOCK = 12;
    private static final int ID_FEATURE_TASKS = 13;
    private static final int ID_FEATURE_EDIT_HISTORY = 14;
    private static final int ID_FEATURES_END = 19;
    private static final int ID_PLAY_HEADER = 20;
    private static final int ID_PLAY_PROGRESS = 21;
    private static final int ID_PLAY_STATUS = 22;
    private static final int ID_PLAY_OTHER_ACCOUNT = 23;
    private static final int ID_PLAY_RETRY = 24;
    private static final int ID_PLAY_RESTORE = 25;
    private static final int ID_PLAY_MANAGE = 26;
    private static final int ID_PLAY_END = 27;
    private static final int ID_PRIVACY = 28;
    private static final int ID_PRIVACY_END = 29;
    private static final int ID_PLAY_ACTIVE = 32;
    private static final int ID_WEB_HEADER = 33;
    private static final int ID_WEB_CHECKOUT = 34;
    /** How many times the screen asks for the grant by itself after the website was opened from it. */
    private static final int WEB_RETURN_REFRESHES = 3;
    private static final int ID_DIRECT = 30;
    private static final int ID_LAPSE_POLICY = 31;
    /** Offer rows: this id plus the offer's position among the purchasable offers. */
    private static final int ID_OFFER = 1000;

    /** Null in a build without the authenticated backend: then the screen is the free one and nothing else. */
    private ZenlyPro pro;
    /** The Telegram user the screen was opened for; the slot may be reused by another one. */
    private long accountUserId;
    /** The Play checkout of this screen, or null (the side-load build, free without a Play subscription, signed out). */
    private GooglePlayCheckout play;
    private boolean destroyed;
    private boolean visible;
    /** "Refresh Pro access" is on its way. */
    private boolean refreshing;
    /** What the last refresh answered; shown under the account rows until the next one. */
    private CharSequence notice;
    private int operation;
    /** Opened by the hub's Restore Purchases row: the restore starts once Play allows one. */
    private boolean restoreOnOpen;
    /** Opened by the create gate: once shown, the screen asks whether the one-time paid-mode notice is owed. */
    private boolean openedByGate;
    private boolean screenViewReported;
    /** The purchasable offers of the last list, in row order: a tap buys exactly what the row showed. */
    private List<GooglePlayModel.Offer> shownOffers = new ArrayList<>();
    /** The Play sentence last shown, to announce a change once. */
    private String announcedPlay;
    /** The website's checkout was opened from here: the next returns ask the server for the grant (this many left). */
    private int webReturnRefreshes;

    public ZgProActivity() {
        super();
    }

    /** The screen with a restore that starts by itself (the hub's Restore Purchases row). */
    public static ZgProActivity forRestore() {
        final ZgProActivity screen = new ZgProActivity();
        screen.restoreOnOpen = true;
        return screen;
    }

    /**
     * The screen for a create of {@code account} that was just blocked ({@link ZgProGate}). It shows that account, and
     * once it is really on screen it says, once per free to paid change, that Zenlygram Pro became a subscription
     * (the notice is taken only then, so a screen that could not be presented never spends it).
     */
    public static ZgProActivity forBlockedCreate(int account) {
        final ZgProActivity screen = new ZgProActivity();
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
            screen.setCurrentAccount(account);
        }
        screen.openedByGate = true;
        return screen;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public boolean onFragmentCreate() {
        accountUserId = getUserConfig().getClientUserId();
        if (ZgBackend.enabled()) {
            pro = ZenlyPro.get(currentAccount);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(afterPlayState);
        if (play != null) {
            play.close();
            play = null;
        }
        super.onFragmentDestroy();
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
        // A fresh signed policy for both readers of it (each behind its own gate).
        ZgCloudPolicies.onProScreenOpened();
        if (pro != null) {
            ZenlyPro.refreshPolicy();
        }
        NotificationCenter.getGlobalInstance().listen(fragmentView, ZgPro.didChange, args -> onProChanged());
        // The Telegram Account row (linked / not linked), the access line and the Play section.
        NotificationCenter.getInstance(currentAccount).listen(fragmentView, NotificationCenter.zenlygramProStatusChanged, args -> onProChanged());
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        visible = true;
        if (pro != null && alive()) {
            // A grant bought on another device, a pending push hint: the entitlement of a linked account (rate-limited).
            pro.refreshIfNeeded();
        }
        if (!syncCheckout() && play != null) {
            // Back from the link screen, from Play's sheet or from the background: an in-flight step is kept,
            // anything else reloads (unless the sync above just did).
            play.resume();
        }
        render();
        if (!screenViewReported) {
            screenViewReported = true;
            ZenlyStats.proScreenView(currentAccount, ZenlyStats.proSourceOf(previousFragment()), ZgPro.isPaidMode());
        }
        if (openedByGate) {
            openedByGate = false;
            if (ZgPro.takePaidModeNotice(currentAccount)) {
                showPaidModeNotice();
            }
        }
        if (webReturnRefreshes > 0) {
            // Back from the website's checkout: a payment made there is a grant of this account on the server.
            if (ZgPro.getAccess(currentAccount) == ZgPro.Access.ACTIVE) {
                webReturnRefreshes = 0;
            } else if (pro != null && pro.snapshot().linked && !refreshing) {
                webReturnRefreshes--;
                refreshAccess(true);
            }
        }
    }

    @Override
    public void onPause() {
        visible = false;
        super.onPause();
    }

    /** Statistics: the screen view is reported by the client from the fragment stack (ZenlyStats.onTopFragment). */
    @Override
    public CustomerEvents.Screen zenlyScreen() {
        return CustomerEvents.Screen.ZG_PRO;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Telegram's notice cells keep the width they were bound with: rebind once the new screen size is known.
        AndroidUtilities.runOnUIThread(() -> {
            if (listView != null && !destroyed) {
                listView.adapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.ZG_SettingsProEntry);
    }

    /** The screen acts only for the Telegram user it was opened for. */
    private boolean alive() {
        return !destroyed && accountUserId != 0 && accountUserId == UserConfig.getInstance(currentAccount).getClientUserId();
    }

    private BaseFragment previousFragment() {
        if (parentLayout == null) {
            return null;
        }
        final List<BaseFragment> stack = parentLayout.getFragmentStack();
        final int index = stack == null ? -1 : stack.indexOf(this);
        return index > 0 ? stack.get(index - 1) : null;
    }

    private void render() {
        if (listView != null && !destroyed) {
            listView.adapter.update(true);
            announcePlayChange();
        }
    }

    /** The policy (its mode or its local grace length), a grant, the end of the local grace or the link changed. */
    private void onProChanged() {
        if (destroyed) {
            return;
        }
        syncCheckout();
        render();
    }

    private void showPaidModeNotice() {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.ZG_SettingsProEntry))
            .setMessage(getString(R.string.ZG_Entitlement_Message_PaidModeNotice))
            .setPositiveButton(getString(R.string.OK), null)
            .create());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Google Play
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Keeps the checkout in step with the policy and the link: it exists in the Play build for a signed-in account
     * while Pro is a subscription, and while Pro is free only for an account that holds a genuine paid period (its
     * Play subscription, if it has one, can be restored and managed; nothing new is sold). A hidden screen waits:
     * its next resume syncs.
     *
     * @return true when this call loaded (or reloaded) the checkout, so the caller does not load it again
     */
    private boolean syncCheckout() {
        if (pro == null || destroyed || !visible) {
            return false;
        }
        final ZenlyPro.Snapshot snapshot = pro.snapshot();
        final boolean wanted = checkoutWanted(snapshot);
        if (play == null) {
            if (!wanted) {
                return false;
            }
            play = new GooglePlayCheckout(currentAccount, accountUserId, state -> onPlayState());
            play.reload();
            return true;
        }
        final GooglePlayModel.State state = play.state();
        if (state.inFlight()) {
            // A purchase in Play's sheet or a verification keeps running, wanted or not; a policy that turned free
            // stops offering at once. A checkout nobody needs any more goes away when the step ends (afterPlayState).
            play.onMonetizationChanged();
            return false;
        }
        if (!wanted) {
            play.close();
            play = null;
            return false;
        }
        final boolean linkChanged = snapshot.linked == (state.stage == GooglePlayModel.Stage.LINK_REQUIRED);
        final boolean modeChanged = state.free == snapshot.purchasesAvailable;
        if (linkChanged || modeChanged) {
            play.reload(); // linked meanwhile, the link was lost, or the policy changed: start over for that state
            return true;
        }
        return false;
    }

    /**
     * The Play build, for the signed-in account the screen was opened for, while Pro is a subscription; while it is
     * free only for an account that holds a genuine paid period.
     */
    private boolean checkoutWanted(ZenlyPro.Snapshot snapshot) {
        final boolean holder = snapshot.status == ZenlyPro.Status.FREE && snapshot.untilEpochSeconds > 0;
        return alive() && ZgPro.isPlayBuild() && (snapshot.purchasesAvailable || holder);
    }

    private void onPlayState() {
        if (destroyed) {
            return;
        }
        render();
        // Never from inside the checkout's own callback: it may still be about to attach what it just read.
        AndroidUtilities.cancelRunOnUIThread(afterPlayState);
        AndroidUtilities.runOnUIThread(afterPlayState);
    }

    /**
     * Once Play has settled: a checkout that is no longer wanted (the policy turned free while a step ran) is closed,
     * and the restore the hub's row asked for starts, once, if the state allows one.
     */
    private final Runnable afterPlayState = () -> {
        if (destroyed || play == null || pro == null) {
            return;
        }
        final GooglePlayModel.State state = play.state();
        if (state.stage == GooglePlayModel.Stage.LOADING || state.inFlight()) {
            return; // the next state asks again
        }
        if (!checkoutWanted(pro.snapshot())) {
            play.close();
            play = null;
            render();
            return;
        }
        if (restoreOnOpen) {
            restoreOnOpen = false;
            if (state.canRestore()) {
                play.restore();
            }
        }
    };

    /** A change of the Play sentence is spoken once (not its first display), and only while its section shows. */
    private void announcePlayChange() {
        final String current = play == null || !showsPlaySection(play.state()) ? null : ZgProText.playStatus(play.state());
        if (current != null && announcedPlay != null && !current.equals(announcedPlay)) {
            AndroidUtilities.makeAccessibilityAnnouncement(current);
        }
        if (current != null) {
            announcedPlay = current;
        }
    }

    /**
     * Whether the Play section is in the list. Paid: always for a signed-in account. Free: only around a known Play
     * subscription of this account or a step in flight, so an account without one sees nothing about Google Play.
     */
    private static boolean showsPlaySection(GooglePlayModel.State state) {
        if (state.stage == GooglePlayModel.Stage.DISABLED || state.stage == GooglePlayModel.Stage.SIGN_IN_REQUIRED) {
            return false;
        }
        if (!state.free) {
            return true;
        }
        return state.inFlight() || state.manage() == GooglePlayModel.Manage.SUBSCRIPTION;
    }

    private void addPlay(ArrayList<UItem> items, GooglePlayModel.State state, boolean alreadyActive) {
        final ArrayList<UItem> rows = new ArrayList<>();
        final String status = ZgProText.playStatus(state);
        final boolean busy = state.stage == GooglePlayModel.Stage.LOADING || state.inFlight();
        if (busy) {
            rows.add(ZgLinkCells.ProgressCell.Factory.of(ID_PLAY_PROGRESS, status));
        } else if (status != null) {
            rows.add(ZgSettingRowCell.Factory.asInfo(ID_PLAY_STATUS, R.drawable.msg_info, Tile.BLUE.top, Tile.BLUE.bottom,
                status, null, null, false));
        }
        shownOffers = new ArrayList<>();
        if (!busy) {
            if (state.purchases.otherAccount) {
                rows.add(ZgSettingRowCell.Factory.asInfo(ID_PLAY_OTHER_ACCOUNT, R.drawable.msg_contact, Tile.ORANGE.top, Tile.ORANGE.bottom,
                    getString(R.string.ZenlyGoogleOtherAccount), null, null, false));
            }
            if (state.canPurchase() && alreadyActive) {
                // A grant from elsewhere (the App Store, the web, a trial, a manual grant): a Play subscription on top of
                // it would be a second payment for the same thing.
                rows.add(ZgSettingRowCell.Factory.asInfo(ID_PLAY_ACTIVE, R.drawable.msg_premium_liststar, Tile.GOLD.top, Tile.GOLD.bottom,
                    getString(R.string.ZG_Pro_Play_AlreadyActive), null, null, false));
            } else if (state.canPurchase()) {
                // Each row names its price, its period and the auto-renewal before the tap; Play's sheet confirms them.
                shownOffers = new ArrayList<>(state.purchasableOffers());
                for (int i = 0; i < shownOffers.size(); i++) {
                    final GooglePlayModel.Offer offer = shownOffers.get(i);
                    rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_OFFER + i, R.drawable.msg_premium_liststar, Tile.GOLD.top, Tile.GOLD.bottom,
                        ZgProText.offerTitle(offer), ZgProText.offerTerms(offer), null, false, !refreshing));
                }
            }
            if (state.canRetry()) {
                rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_PLAY_RETRY, R.drawable.msg_retry, Tile.TEAL.top, Tile.TEAL.bottom,
                    getString(R.string.ZenlyGoogleRetry), null, null, false, !refreshing));
            }
            if (state.canRestore()) {
                rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_PLAY_RESTORE, R.drawable.msg_reset, Tile.TEAL.top, Tile.TEAL.bottom,
                    getString(R.string.ZenlyGoogleRestore), getString(R.string.ZG_Entitlement_Status_Restore_Detail), null, false, !refreshing));
            }
            final GooglePlayModel.Manage manage = state.manage();
            if (manage != GooglePlayModel.Manage.NONE) {
                rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_PLAY_MANAGE, R.drawable.msg_openin, Tile.GRAY.top, Tile.GRAY.bottom,
                    getString(manage == GooglePlayModel.Manage.SUBSCRIPTION ? R.string.ZenlyGoogleManage : R.string.ZenlyGoogleOpenSubscriptions),
                    null, null, false, true));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        items.add(UItem.asHeader(ID_PLAY_HEADER, getString(R.string.ZenlyGoogleSection)));
        addCard(items, rows);
        // Whose account pays, that it renews by itself and where it is cancelled; free: nothing new is sold.
        items.add(UItem.asShadow(ID_PLAY_END, getString(state.free ? R.string.ZenlyGoogleFreeAccess : R.string.ZenlyGoogleAccess)));
    }

    private void purchase(int index) {
        if (play == null || refreshing || index < 0 || index >= shownOffers.size()) {
            return;
        }
        final GooglePlayModel.Offer shown = shownOffers.get(index);
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        // The checkout buys only an offer of its current state; a row left over from an older list starts nothing.
        for (GooglePlayModel.Offer offer : play.state().purchasableOffers()) {
            if (offer == shown) {
                ZenlyStats.proPlanSelect(currentAccount, offer.productId, CustomerEvents.Rail.GOOGLE, offer.basePlanId);
                play.purchase(activity, offer);
                return;
            }
        }
    }

    private void openPlaySubscriptions() {
        final Activity activity = getParentActivity();
        if (activity == null || play == null || play.state().manage() == GooglePlayModel.Manage.NONE) {
            return;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, play.manageUri(activity.getPackageName())));
        } catch (ActivityNotFoundException e) {
            notice = getString(R.string.ZenlyGoogleStoreUnavailable);
            render();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // The account's grant
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Asks the server for this account's grant again; the answer reaches the list through the client's events.
     * {@code quiet}: the screen asked by itself (back from the website), so only the access line speaks; an answer
     * that changed nothing is not announced as an update.
     */
    private void refreshAccess(final boolean quiet) {
        if (pro == null || refreshing || !alive()) {
            return;
        }
        refreshing = true;
        notice = null;
        render();
        final int expected = ++operation;
        pro.refresh(new ZenlyPro.Callback<ZenlyPro.Snapshot>() {
            @Override
            public void onSuccess(ZenlyPro.Snapshot value) {
                if (!alive() || expected != operation) {
                    return;
                }
                refreshing = false;
                notice = quiet ? null : getString(R.string.ZenlyProRefreshed);
                syncCheckout();
                render();
            }

            @Override
            public void onError(ZenlyPro.Error error) {
                if (!alive() || expected != operation) {
                    return;
                }
                refreshing = false;
                notice = quiet ? null : ZgProText.error(error);
                // A 401 clears the session: the Play section goes back to linking.
                syncCheckout();
                render();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------------------------

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final ZgPro.Access access = ZgPro.getAccess(currentAccount);
        final long until = ZgPro.getAccessUntil(currentAccount);
        final boolean paid = ZgPro.isPaidMode();
        final GooglePlayModel.State playState = play == null ? null : play.state();

        items.add(ZgSettingRowCell.Factory.asInfo(ID_STATUS, R.drawable.zg_glyph, Tile.BRAND_PRO.top, Tile.BRAND_PRO.bottom,
            getString(R.string.ZG_SettingsProEntry), ZgProText.status(access, until), null, false));
        items.add(UItem.asShadow(ID_STATUS_NOTICE, statusNotice(access, until)));
        // Where the decision comes from: the verified, signed GET /v1/monetization policy.
        items.add(UItem.asShadow(ID_POLICY, signedPolicyLine(pro != null)));

        if (pro != null) {
            // The account link (iOS: the Link row of the status screen): the state, its one line of explanation and the
            // way to the link screen. Read from the client's stored session, never the network.
            final boolean linked = pro.snapshot().linked;
            // Account commands (the link screen can unlink, the refresh) wait while Play loads, purchases or verifies:
            // its first query may attach a purchase, and an attach needs the session it started with.
            final boolean accountFree = !refreshing && (playState == null || !playState.holdsAccountCommands());
            final ArrayList<UItem> rows = new ArrayList<>();
            rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_LINK, R.drawable.msg_link, Tile.BLUE.top, Tile.BLUE.bottom,
                getString(R.string.ZG_Settings_Pro_LinkAccount),
                getString(linked ? (paid ? R.string.ZG_Entitlement_Status_Linked : R.string.ZGL_LinkAccount_Success_Text)
                    : (paid ? R.string.ZG_Entitlement_Message_NotLinked : R.string.ZGL_Pro_LinkAccount_Auto)),
                getString(linked ? R.string.ZG_Settings_Pro_LinkAccount_Linked : R.string.ZG_Settings_Pro_LinkAccount_NotLinked),
                false, accountFree));
            if (paid && linked && accountUserId != 0) {
                if (refreshing) {
                    rows.add(ZgLinkCells.ProgressCell.Factory.of(ID_REFRESHING, getString(R.string.ZenlyProWorking)));
                } else {
                    rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_REFRESH, R.drawable.msg_retry, Tile.TEAL.top, Tile.TEAL.bottom,
                        getString(R.string.ZenlyProRefresh), getString(R.string.ZG_Pro_Refresh_Detail), null, false, accountFree));
                }
            }
            addCard(items, rows);
            items.add(UItem.asShadow(ID_ACCOUNT_END, notice));
        }

        // What Pro includes comes before any price.
        final boolean preview = ZgSettings.isPreviewAllRows();
        final ArrayList<UItem> features = new ArrayList<>();
        addFeature(features, preview, ZgFeatures.SECRET_VAULT, ID_FEATURE_VAULT, R.drawable.msg_archive_hide, Tile.PURPLE,
            R.string.ZG_PayWall_Vault_Title, R.string.ZG_PayWall_Vault_Notice);
        addFeature(features, preview, ZgFeatures.CHAT_LOCK, ID_FEATURE_CHAT_LOCK, R.drawable.msg_secret, Tile.BLUE,
            R.string.ZG_PayWall_ChatLock_Title, R.string.ZG_PayWall_ChatLock_Notice);
        addFeature(features, preview, ZgFeatures.TASKS, ID_FEATURE_TASKS, R.drawable.msg_list, Tile.BLUE,
            R.string.ZG_PayWall_Tasks_Title, R.string.ZG_PayWall_Tasks_Notice);
        addFeature(features, preview, ZgFeatures.EDIT_HISTORY, ID_FEATURE_EDIT_HISTORY, R.drawable.msg_recent, Tile.TEAL,
            R.string.ZG_PayWall_EditHistory_Title, R.string.ZG_PayWall_EditHistory_Notice);
        if (!features.isEmpty()) {
            items.add(UItem.asHeader(ID_FEATURES_HEADER, getString(R.string.ZG_Pro_Features_Header)));
            addCard(items, features);
            // Paid: what a lapse does and does not do, before anything is bought.
            items.add(UItem.asShadow(ID_FEATURES_END, paid ? getString(R.string.ZG_PayWall_LapsePolicy_Short) : null));
        } else if (paid) {
            items.add(UItem.asShadow(ID_LAPSE_POLICY, getString(R.string.ZG_PayWall_LapsePolicy_Short)));
        }

        shownOffers = new ArrayList<>();
        if (playState != null && showsPlaySection(playState)) {
            addPlay(items, playState, access == ZgPro.Access.ACTIVE);
            if (!playState.free) {
                final ArrayList<UItem> rows = new ArrayList<>();
                rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_PRIVACY, R.drawable.msg_policy, Tile.GRAY.top, Tile.GRAY.bottom,
                    getString(R.string.ZG_PayWall_Privacy), null, null, false, true));
                addCard(items, rows);
                items.add(UItem.asShadow(ID_PRIVACY_END, null));
            }
        } else if (pro != null && ZgPro.offersWebPurchase()) {
            // The side-load build sells nothing itself: the website's checkout, in the browser. An account that
            // already has Pro gets the way to its subscription page there instead of an offer.
            final boolean active = access == ZgPro.Access.ACTIVE;
            final ArrayList<UItem> rows = new ArrayList<>();
            rows.add(ZgSettingRowCell.Factory.asDisclosure(ID_WEB_CHECKOUT, R.drawable.msg_openin, Tile.GOLD.top, Tile.GOLD.bottom,
                getString(active ? R.string.ZG_Pro_Web_Manage : R.string.ZG_Pro_Web_Subscribe),
                getString(active ? R.string.ZG_Pro_Web_Manage_Detail : R.string.ZG_Pro_Web_Subscribe_Detail),
                null, false, !refreshing));
            items.add(UItem.asHeader(ID_WEB_HEADER, getString(R.string.ZG_Pro_Web_Header)));
            addCard(items, rows);
            items.add(UItem.asShadow(ID_DIRECT, getString(R.string.ZG_Pro_Direct_Notice)));
        }
    }

    /** Opens the website's checkout in the browser (never inside the app: it is the site's own payment page). */
    private void openWebCheckout() {
        if (getParentActivity() == null || !ZgPro.offersWebPurchase()) {
            return;
        }
        webReturnRefreshes = WEB_RETURN_REFRESHES;
        Browser.openInExternalBrowser(getParentActivity(), ZgConfig.PRO_WEB_URL, false);
    }

    /** The rows of one card, with a divider under every row but the last. */
    private static void addCard(ArrayList<UItem> items, ArrayList<UItem> rows) {
        for (int i = 0; i < rows.size(); i++) {
            final UItem row = rows.get(i);
            if (ZgSettingRowCell.Factory.is(row)) {
                ZgSettingRowCell.Factory.setDivider(row, i + 1 < rows.size());
            }
            items.add(row);
        }
    }

    /**
     * The paragraph under the access line: what the state means, and the two cases its one line cannot say. The
     * account's own line reads "not active" while another signed-in account's grant keeps the device-wide features
     * open; and during the local grace a board that syncs with the server is the exception.
     */
    private CharSequence statusNotice(ZgPro.Access access, long until) {
        CharSequence text = ZgProText.statusNotice(access, until);
        String more = null;
        if (!access.active && ZgPro.isCoveredByAnotherAccount(currentAccount)) {
            more = getString(R.string.ZG_Pro_OtherAccountCovers);
        } else if (access == ZgPro.Access.GRACE && ZgPro.tasksAskTheServer(currentAccount)) {
            more = getString(R.string.ZG_Pro_Grace_SyncedTasks);
        }
        if (more == null) {
            return text;
        }
        return text == null || text.length() == 0 ? more : text + "\n\n" + more;
    }

    /**
     * "Signed server policy: free, revision 0, checked …", or that there is none yet (then Pro is free). An enforcing
     * build names the policy its decision is made by (the client's verified copy), so the line never contradicts the
     * access line above it; a build without the client names the app's own copy.
     */
    private static CharSequence signedPolicyLine(boolean enforcing) {
        final boolean paid;
        final long revision;
        final long checked;
        if (enforcing) {
            final ZenlyPro.PolicyInfo policy = ZenlyPro.policyInfo();
            if (!policy.adopted) {
                return getString(R.string.ZGC_Pro_Policy_None);
            }
            paid = policy.paid;
            revision = policy.revision;
            checked = policy.issuedAtEpochSeconds;
        } else {
            final ZgPublicPolicy.Monetization policy = ZgCloudPolicies.getMonetization();
            if (policy == null) {
                return getString(R.string.ZGC_Pro_Policy_None);
            }
            paid = policy.paid;
            revision = policy.revision;
            checked = Math.max(policy.iat, ZgCloudPolicies.getLastVerifiedAt());
        }
        return LocaleController.formatString(R.string.ZGC_Pro_Policy_Verified,
            getString(paid ? R.string.ZGC_Pro_Policy_Paid : R.string.ZGC_Pro_Policy_Free),
            String.valueOf(revision),
            LocaleController.formatDateTime(checked, true));
    }

    /** The Pro features are listed only once they are ported, so the screen never promises one this build lacks. */
    private static void addFeature(ArrayList<UItem> items, boolean preview, ZgFeatures feature, int id, int iconRes,
                                   Tile tile, int titleRes, int descriptionRes) {
        if (feature.isPorted() || preview) {
            items.add(ZgSettingRowCell.Factory.asInfo(id, iconRes, tile.top, tile.bottom, getString(titleRes),
                getString(descriptionRes), null, !feature.isPorted()));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null || !item.enabled) {
            return;
        }
        if (item.id == ID_LINK) {
            presentFragment(new ZgLinkAccountActivity());
        } else if (item.id == ID_REFRESH) {
            refreshAccess(false);
        } else if (item.id == ID_WEB_CHECKOUT) {
            openWebCheckout();
        } else if (item.id == ID_PLAY_RETRY) {
            if (play != null && play.state().canRetry()) {
                play.reload();
            }
        } else if (item.id == ID_PLAY_RESTORE) {
            if (play != null) {
                play.restore();
            }
        } else if (item.id == ID_PLAY_MANAGE) {
            openPlaySubscriptions();
        } else if (item.id == ID_PRIVACY) {
            if (getParentActivity() != null) {
                Browser.openUrl(getParentActivity(), ZgConfig.PRIVACY_POLICY_URL);
            }
        } else if (item.id >= ID_OFFER) {
            purchase(item.id - ID_OFFER);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
